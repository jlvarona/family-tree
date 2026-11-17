package com.moxietech.family_tree.state

import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CitationDto
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.MergePeopleRequest
import com.moxietech.family_tree.api.MergePeopleResponse
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.auth.AuthSession
import com.moxietech.family_tree.auth.AuthState
import com.moxietech.family_tree.auth.BrowserBridge
import com.moxietech.family_tree.auth.UrlParts
import com.moxietech.family_tree.client.ApiException
import com.moxietech.family_tree.client.ApiFailure
import com.moxietech.family_tree.client.FamilyTreeApi
import com.moxietech.family_tree.client.MediaTransfer
import com.moxietech.family_tree.client.NoMediaTransfer
import com.moxietech.family_tree.api.CreateMediaUploadRequest
import com.moxietech.family_tree.auth.PickedFile
import com.moxietech.family_tree.media.MediaPurpose
import com.moxietech.family_tree.media.MediaRules
import com.moxietech.family_tree.merge.MergeField
import com.moxietech.family_tree.merge.MergeSide
import com.moxietech.family_tree.merge.PersonMerge
import com.moxietech.family_tree.navigation.Route
import com.moxietech.family_tree.navigation.TreeViewKind
import com.moxietech.family_tree.validation.Validation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Drives the web workflow: session, routing, loading and the owner's create
 * actions, as one [AppState] for the Compose UI.
 *
 * **No stale user data.** Every load and mutation runs in a per-session
 * [CoroutineScope], a child of the controller's scope. When the session ends,
 * expires or changes subject, that scope is cancelled and the state is replaced
 * by an empty one before anything else happens. A result is applied only if
 * its scope is still the current session's, and tree or person data only to the
 * same opening of that screen ([TreeScreen.instance], [PersonScreen.instance]),
 * so a late response can't land on another session, tree or visit.
 */
class FamilyTreeController(
    private val session: AuthSession,
    private val api: FamilyTreeApi,
    private val bridge: BrowserBridge,
    private val scope: CoroutineScope,
    /** FT-008: presigned transfers to and from photo storage (no session token). */
    private val transfer: MediaTransfer = NoMediaTransfer,
    /** FT-008: encoded bytes [AppState.photos] may hold; older, unused images are dropped first. */
    private val photoCacheBytes: Long = DEFAULT_PHOTO_CACHE_BYTES,
) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var sessionScope: CoroutineScope = newSessionScope()
    private var subject: String? = null
    private var nextInstance = 1L

    fun start() {
        bridge.onHashChange(::onLocationChanged)
        scope.launch { session.state.collect { onAuth(it) } }
        scope.launch { session.start() }
    }

    fun signIn() {
        val route = _state.value.route.toFragment()
        scope.launch { session.signIn(route) }
    }

    fun signOut() {
        resetSession()
        _state.value = AppState(auth = AuthState.SignedOut(), route = _state.value.route)
        session.signOut()
    }

    fun navigate(route: Route) {
        if (route != _state.value.route) bridge.setHash(route.toFragment())
        showRoute(route)
    }

    fun retry() {
        val current = _state.value
        if (current.account is Loadable.Failed) loadAccount()
        if (current.trees is Loadable.Failed) loadTrees()
        current.treeScreen?.let { screen ->
            if (screen.tree is Loadable.Failed || screen.graph is Loadable.Failed) {
                loadTreeScreen(screen.instance)
            } else if (screen.branch is Loadable.Failed) {
                loadBranch(screen.instance, force = true)
            }
            screen.merge?.takeIf { it.records is Loadable.Failed }?.let { loadMergeRecords(screen.instance, it.id) }
        }
        // FT-008: images that failed to load are fetched again (a fresh short-lived link each time).
        current.photos.filterValues { it is Loadable.Failed }.keys.forEach { key ->
            _state.update { it.copy(photos = it.photos - key) }
            loadPhoto(key.treeId, key.mediaId)
        }
        // Only the profile on screen is retried; one left behind (e.g. after "Back to tree") isn't.
        current.personScreen?.takeIf { isOnRoute(current.route, it) }?.let { page ->
            if (page.person is Loadable.Failed || page.tree is Loadable.Failed) {
                loadPersonScreen(page.instance)
            } else {
                // Only the failed sections are fetched again; the rest stays on screen.
                if (page.graph is Loadable.Failed) loadPersonFamily(page.instance)
                if (page.events is Loadable.Failed) loadPersonTimeline(page.instance)
                if (page.sources is Loadable.Failed || page.citations.values.any { it is Loadable.Failed }) {
                    loadPageCitations(page.instance, retryFailed = true)
                }
            }
        }
    }

    fun selectPerson(personId: String?) = updateTreeScreen(null) { it.copy(selectedPersonId = personId) }

    fun showView(view: TreeViewKind) {
        val screen = _state.value.treeScreen ?: return
        val root = screen.rootPersonId ?: screen.selectedPersonId
        navigate(Route.Tree(screen.treeId, view, root))
    }

    fun centerOn(personId: String) {
        val screen = _state.value.treeScreen ?: return
        navigate(Route.Tree(screen.treeId, screen.view, personId))
    }

    /** From a person page to the tree it belongs to, in the view last used there, centred on that person. */
    fun backToTree() {
        val personScreen = _state.value.personScreen ?: return
        val screen = _state.value.treeScreen?.takeIf { it.treeId == personScreen.treeId }
        navigate(
            Route.Tree(personScreen.treeId, screen?.view ?: TreeViewKind.FULL, screen?.rootPersonId ?: personScreen.personId),
        )
        selectPerson(personScreen.personId)
    }

    /**
     * From a person page to the tree centred on that person, keeping the view
     * last used there (FT-006 "Show in tree"), with the person selected.
     */
    fun showInTree() {
        val personScreen = _state.value.personScreen ?: return
        val screen = _state.value.treeScreen?.takeIf { it.treeId == personScreen.treeId }
        navigate(Route.Tree(personScreen.treeId, screen?.view ?: TreeViewKind.FULL, personScreen.personId))
        selectPerson(personScreen.personId)
    }

    /** Opens a relative's page in the same tree; the URL changes, so back returns here. */
    fun openPerson(personId: String) {
        val personScreen = _state.value.personScreen ?: return
        navigate(Route.Person(personScreen.treeId, personId))
    }

    fun dismissNotice() = updateTreeScreen(null) { it.copy(notice = null) }

    fun clearFormError() = _state.update { it.copy(form = FormState(submitting = it.form.submitting)) }

    fun createTree(name: String, onCreated: () -> Unit) {
        if (Validation.validateTreeName(name).isNotEmpty()) return formError(FormError.NAME_REQUIRED)
        submit { owner, version ->
            val tree = api.createTree(name.trim())
            if (!isCurrent(owner)) return@submit
            loadTrees()
            // Open the new tree unless the user has moved on meanwhile.
            if (formIsCurrent(owner, version)) {
                onCreated()
                navigate(Route.Tree(tree.id))
            }
        }
    }

    fun createPerson(input: PersonInput, onCreated: () -> Unit) {
        val screen = _state.value.treeScreen ?: return
        if (Validation.validatePersonName(input.givenNames).isNotEmpty()) return formError(FormError.NAME_REQUIRED)
        submit { owner, version ->
            val response = api.createPerson(
                screen.treeId,
                CreatePersonRequest(
                    givenNames = input.givenNames.trim(),
                    surnamePaternal = input.surnamePaternal.trim().ifEmpty { null },
                    surnameMaternal = input.surnameMaternal.trim().ifEmpty { null },
                    sex = input.sex.name,
                    isLiving = input.isLiving,
                ),
            )
            val viewer = viewerFor(currentScreen(screen.instance))
            updateScreen(owner, screen.instance) { current ->
                current.copy(
                    selectedPersonId = response.person.id,
                    notice = response.possibleDuplicates.takeIf { it.isNotEmpty() }?.let { candidates ->
                        Notice.PossibleDuplicates(
                            createdPersonId = response.person.id,
                            candidates = candidates.map { DuplicateMatch(it.person.id, viewer?.displayName(it.person)) },
                        )
                    },
                )
            }
            if (formIsCurrent(owner, version)) onCreated()
            if (isCurrent(owner)) refreshTree(screen.instance)
        }
    }

    /**
     * Links a child through the union the user chose: an existing union of
     * exactly these parents, or a new one of the chosen type. If the new union
     * is created but the child request fails, the error is shown and the form
     * is pointed at the created union so a retry doesn't create another.
     */
    fun linkChild(input: LinkChildInput, onLinked: () -> Unit) {
        val screen = _state.value.treeScreen ?: return
        val graph = screen.graph.valueOrNull ?: return
        val parentId = input.parentId ?: return formError(FormError.PARENT_REQUIRED)
        val childId = input.childId ?: return formError(FormError.CHILD_REQUIRED)
        val otherId = input.otherParentId
        if (otherId == parentId) return formError(FormError.SAME_PARENT_TWICE)
        if (childId == parentId || childId == otherId) return formError(FormError.CHILD_IS_PARENT)
        val choice = input.union ?: return formError(FormError.UNION_REQUIRED)
        if (choice is UnionChoice.Existing && unionsForParents(graph, parentId, otherId).none { it.id == choice.unionId }) {
            return formError(FormError.UNION_REQUIRED)
        }
        submit { owner, version ->
            var createdUnionId: String? = null
            try {
                val unionId = when (choice) {
                    is UnionChoice.Existing -> choice.unionId
                    is UnionChoice.New -> api.createUnion(
                        screen.treeId,
                        CreateUnionRequest(partner1Id = parentId, partner2Id = otherId, type = choice.type.name),
                    ).id.also { createdUnionId = it }
                }
                if (!isCurrent(owner)) return@submit
                api.addChild(screen.treeId, unionId, AddUnionChildRequest(childId, input.relation.name))
                updateScreen(owner, screen.instance) { it.copy(notice = Notice.ChildLinked) }
                if (formIsCurrent(owner, version)) onLinked()
            } catch (e: ApiException) {
                if (formIsCurrent(owner, version)) {
                    _state.update { it.copy(form = FormState(error = formErrorFor(e.failure), createdUnionId = createdUnionId)) }
                }
            }
            // Either way the graph may have changed (a new union), so show the saved state.
            if (isCurrent(owner)) refreshTree(screen.instance)
        }
    }

    // --- FT-007: merging two records of one person (owner only) ------------------------

    /**
     * Opens the merge on the tree screen, for the owner only, with one or both
     * records already chosen (from the duplicate notice or the selected person).
     */
    fun startMerge(firstId: String? = null, secondId: String? = null) {
        val screen = _state.value.treeScreen ?: return
        if (viewerFor(screen)?.canWrite != true) return
        val merge = MergeState(id = nextInstance++).withPeople(firstId, secondId)
        updateTreeScreen(screen.instance) { it.copy(merge = merge) }
        loadMergeRecords(screen.instance, merge.id)
    }

    fun chooseFirstForMerge(personId: String) = chooseMergePeople { it.withPeople(personId, it.secondId) }

    fun chooseSecondForMerge(personId: String) = chooseMergePeople { it.withPeople(it.firstId, personId) }

    /** The record that is kept. Changing it asks again which record is removed. */
    fun chooseSurvivor(personId: String) = updateMerge(null, null) { merge ->
        if (merge.records?.valueOrNull?.byId(personId) == null) merge else merge.copy(survivorId = personId, retiredConfirmed = false, error = null)
    }

    /** For a differing [field], keep the value of [personId] (one of the two records). */
    fun chooseMergeValue(field: MergeField, personId: String) = updateMerge(null, null) { merge ->
        if (merge.records?.valueOrNull?.byId(personId) == null) merge else merge.copy(choices = merge.choices + (field to personId), error = null)
    }

    fun confirmRetired(confirmed: Boolean) = updateMerge(null, null) { it.copy(retiredConfirmed = confirmed, error = null) }

    fun cancelMerge() = updateTreeScreen(null) { if (it.merge?.submitting == true) it else it.copy(merge = null) }

    /**
     * Sends the owner's decision: both reviewed versions and, for every field that
     * differs, the side whose value is kept. Checked here first (survivor, every
     * difference chosen, retired record confirmed, no relationship blocker), so the
     * request only goes out complete. A `409` reloads both records and the tree and
     * asks the owner to review again.
     */
    fun submitMerge() {
        val screen = _state.value.treeScreen ?: return
        val merge = screen.merge ?: return
        if (merge.submitting) return
        val records = merge.records?.valueOrNull ?: return
        val survivor = records.byId(merge.survivorId) ?: return mergeError(MergeError.SURVIVOR_REQUIRED)
        val retired = records.other(survivor.id) ?: return mergeError(MergeError.SURVIVOR_REQUIRED)
        val differing = differingFields(survivor, retired)
        if (differing.any { merge.choices[it] != survivor.id && merge.choices[it] != retired.id }) {
            return mergeError(MergeError.CHOICES_REQUIRED)
        }
        if (!merge.retiredConfirmed) return mergeError(MergeError.CONFIRMATION_REQUIRED)
        val graph = screen.graph.valueOrNull
        if (graph != null && PersonMerge.relationshipConflicts(survivor.id, retired.id, graph.unions).isNotEmpty()) {
            return mergeError(MergeError.RELATIONSHIPS)
        }
        val request = MergePeopleRequest(
            survivorId = survivor.id,
            survivorVersion = survivor.version,
            retiredId = retired.id,
            retiredVersion = retired.version,
            choices = differing.associate { field ->
                field.name to (if (merge.choices[field] == survivor.id) MergeSide.SURVIVOR else MergeSide.RETIRED).name
            },
        )
        updateMerge(screen.instance, merge.id) { it.copy(submitting = true, error = null) }
        inSession { owner ->
            try {
                val result = api.mergePeople(screen.treeId, request)
                if (isCurrent(owner)) onMerged(screen, merge.id, result)
            } catch (e: ApiException) {
                if (!isCurrent(owner)) return@inSession
                if (e.failure == ApiFailure.CONFLICT) {
                    // The values or relationships may have changed: review everything again.
                    updateMerge(screen.instance, merge.id) {
                        it.copy(submitting = false, error = MergeError.CHANGED, choices = emptyMap(), retiredConfirmed = false)
                    }
                    loadMergeRecords(screen.instance, merge.id)
                    refreshTree(screen.instance)
                } else {
                    updateMerge(screen.instance, merge.id) { it.copy(submitting = false, error = mergeErrorFor(e.failure)) }
                }
            }
        }
    }

    /**
     * After a merge: the merge and the duplicate notice (which may name the retired
     * record) are dropped, the survivor replaces the retired person as selection and
     * root, the tree reloads, and the survivor's page opens. If the owner has left the
     * tree meanwhile, only the reload happens.
     */
    private fun onMerged(screen: TreeScreen, mergeId: Long, result: MergePeopleResponse) {
        val survivorId = result.survivor.id
        val retiredId = result.retiredId
        val current = currentScreen(screen.instance)
        if (current?.merge?.id == mergeId) {
            updateTreeScreen(screen.instance) {
                it.copy(
                    merge = null,
                    notice = null,
                    selectedPersonId = survivorId,
                    rootPersonId = if (it.rootPersonId == retiredId) survivorId else it.rootPersonId,
                )
            }
        }
        refreshTree(screen.instance)
        val route = _state.value.route
        if (current?.merge?.id != mergeId || route !is Route.Tree || route.treeId != screen.treeId) return
        // Replace a root that no longer exists first, so "back" from the profile lands on a valid view.
        if (route.rootPersonId == retiredId) navigate(route.copy(rootPersonId = survivorId))
        navigate(Route.Person(screen.treeId, survivorId))
    }

    private fun chooseMergePeople(change: (MergeState) -> MergeState) {
        val screen = _state.value.treeScreen ?: return
        val merge = screen.merge ?: return
        if (merge.submitting) return
        val changed = change(merge)
        if (changed == merge) return
        updateMerge(screen.instance, merge.id) { changed }
        loadMergeRecords(screen.instance, merge.id)
    }

    /** New picks drop everything decided about the previous pair. */
    private fun MergeState.withPeople(firstId: String?, secondId: String?): MergeState =
        if (firstId == this.firstId && secondId == this.secondId && records != null) {
            this
        } else {
            MergeState(
                id = id,
                firstId = firstId,
                secondId = secondId,
                error = if (firstId != null && firstId == secondId) MergeError.SAME_PERSON else null,
            )
        }

    /** Fetches both chosen records fresh, so the versions sent are the ones the owner reviews. */
    private fun loadMergeRecords(instance: Long, mergeId: Long) {
        val screen = currentScreen(instance) ?: return
        val merge = screen.merge?.takeIf { it.id == mergeId } ?: return
        val first = merge.firstId ?: return
        val second = merge.secondId ?: return
        if (first == second) return
        // A fresh generation per read: the IDs alone don't tell two reads of the same pair apart.
        val load = nextInstance++
        updateMerge(instance, mergeId) { it.copy(records = Loadable.Loading, recordsLoad = load) }
        inSession { owner ->
            val records = load {
                fun checked(person: PersonDto, id: String) = person.also {
                    requireRoute(it.treeId, screen.treeId)
                    requireRoute(it.id, id)
                }
                MergeRecords(checked(api.person(screen.treeId, first), first), checked(api.person(screen.treeId, second), second))
            }
            if (!isCurrent(owner)) return@inSession
            updateMerge(instance, mergeId) { current ->
                if (current.recordsLoad == load) current.copy(records = records) else current
            }
            // FT-008: the owner compares the photos themselves before choosing one.
            records.valueOrNull?.let { pair ->
                listOfNotNull(pair.first.profileMediaId, pair.second.profileMediaId).forEach { loadPhoto(screen.treeId, it) }
            }
        }
    }

    /** Changes the merge if it is still [mergeId] on tree screen [instance] (any, when null). */
    private fun updateMerge(instance: Long?, mergeId: Long?, change: (MergeState) -> MergeState) = updateTreeScreen(instance) { screen ->
        val merge = screen.merge
        if (merge == null || (mergeId != null && merge.id != mergeId)) screen else screen.copy(merge = change(merge))
    }

    private fun mergeError(error: MergeError) = updateMerge(null, null) { it.copy(error = error) }

    // --- FT-008: profile photos ---------------------------------------------------------

    /**
     * Opens the file chooser for the person on screen (owner only, and only for a person
     * whose details they can see). Call straight from the click: browsers only open a
     * file chooser during a user gesture.
     */
    fun choosePhoto() {
        val page = editablePhotoPage() ?: return
        // The cap goes to the browser, which checks File.size before opening the file.
        bridge.pickFile(MediaRules.SUPPORTED_CONTENT_TYPES, MediaRules.MAX_UPLOAD_BYTES) { file -> onPhotoPicked(page.instance, file) }
    }

    /** Retries the failed step of the last photo change, reusing what already succeeded. */
    fun retryPhoto() {
        val page = editablePhotoPage() ?: return
        val photo = page.photo ?: return
        when {
            photo.removing -> removePhoto()
            photo.pending != null -> runPhotoChange(page.instance, photo.pending)
        }
    }

    fun dismissPhotoError() {
        val page = _state.value.personScreen ?: return
        if (page.photo?.busy == true) return
        updatePersonScreen(page.instance) { it.copy(photo = null) }
    }

    // --- FT-006 follow-up: citations and their scans on the person page --------------

    /**
     * Opens a citation scan shown on the person page and fetches it through a
     * short-lived link (once; the bounded photo cache keeps it). Only a scan of a
     * citation the page lists for the caller can be opened. At most [MAX_OPEN_SCANS]
     * stay open; opening another closes the oldest, so the images on screen stay within
     * the cache.
     */
    fun showScan(mediaId: String) {
        val page = _state.value.personScreen?.takeIf { isOnRoute(_state.value.route, it) } ?: return
        if (mediaId in page.openScans) return
        val person = page.person.valueOrNull ?: return
        val viewer = pageViewer(page) ?: return
        val view = PersonSources.view(person, viewer, page.events, page.graph, page.sources, page.citations)
        if ((view as? SourcesView.Listed)?.entries?.none { it.mediaId == mediaId } != false) return
        updatePersonScreen(page.instance) { it.copy(openScans = (it.openScans + mediaId).takeLast(MAX_OPEN_SCANS)) }
        loadPhoto(page.treeId, mediaId)
    }

    /** Closes a scan. One that failed to load is forgotten, so a later retry doesn't fetch it. */
    fun hideScan(mediaId: String) {
        val page = _state.value.personScreen ?: return
        updatePersonScreen(page.instance) { it.copy(openScans = it.openScans - mediaId) }
        val key = PhotoKey(page.treeId, mediaId)
        if (_state.value.photos[key] is Loadable.Failed) _state.update { it.copy(photos = it.photos - key) }
    }

    /**
     * Requests what the Sources section needs and doesn't have yet: the tree's sources
     * and the citations of every fact on the page ([PersonSources.citedFacts]). Called
     * when the person, the timeline, the family or the account arrives, so each fact's
     * citations are requested as soon as the fact is known. Nothing is requested before
     * the account is known (the viewer decides what may be requested), nor for a hidden
     * person or a union with a hidden partner. Failed parts are fetched again only on
     * [retryFailed]. The requests run in the page's [CitationWork]: at most
     * [MAX_ACTIVE_CITATION_REQUESTS] at once, cancelled with the page.
     */
    private fun loadPageCitations(instance: Long, retryFailed: Boolean = false) {
        val page = currentPersonScreen(instance) ?: return
        val person = page.person.valueOrNull ?: return
        val viewer = pageViewer(page) ?: return
        val facts = PersonSources.citedFacts(person, page.events.valueOrNull, page.graph.valueOrNull, viewer)
        if (facts.isEmpty()) return
        if (page.sources == null || (retryFailed && page.sources is Loadable.Failed)) {
            updatePersonScreen(instance) { it.copy(sources = Loadable.Loading) }
            inCitationWork(instance) { owner ->
                val sources = load { api.sources(page.treeId) }
                if (isCurrent(owner)) updatePersonScreen(instance) { it.copy(sources = sources) }
            }
        }
        val wanted = facts.map { it.target }.filter { target ->
            when (page.citations[target]) {
                null -> true
                is Loadable.Failed -> retryFailed
                else -> false
            }
        }
        if (wanted.isEmpty()) return
        updatePersonScreen(instance) { current -> current.copy(citations = current.citations + wanted.associateWith { Loadable.Loading }) }
        wanted.forEach { target ->
            inCitationWork(instance) { owner ->
                val result: Loadable<List<CitationDto>> = try {
                    Loadable.Ready(api.citations(page.treeId, target))
                } catch (e: ApiException) {
                    // A fact that is gone, or an event the server now hides, has nothing to show.
                    if (e.failure == ApiFailure.NOT_FOUND) Loadable.Ready(emptyList()) else Loadable.Failed(e.failure)
                }
                if (isCurrent(owner)) updatePersonScreen(instance) { it.copy(citations = it.citations + (target to result)) }
            }
        }
    }

    /**
     * The citation and source requests of one person page ([instance]): a scope that is a
     * child of the session's, and a fixed number of permits. Queued requests wait for a
     * permit inside the scope, so cancelling it stops both running and queued work.
     */
    private class CitationWork(val instance: Long, val scope: CoroutineScope, val permits: Semaphore)

    private var citationWork: CitationWork? = null

    /**
     * Runs [block] in the page's [CitationWork] once a permit is free, if that page and
     * session are still current by then. A different page replaces (and cancels) the work.
     */
    private fun inCitationWork(instance: Long, block: suspend (CoroutineScope) -> Unit) {
        val owner = sessionScope
        val work = citationWork?.takeIf { it.instance == instance && it.scope.isActive } ?: run {
            cancelCitationWork()
            CitationWork(
                instance,
                CoroutineScope(owner.coroutineContext + SupervisorJob(owner.coroutineContext[Job])),
                Semaphore(MAX_ACTIVE_CITATION_REQUESTS),
            ).also { citationWork = it }
        }
        work.scope.launch {
            work.permits.withPermit {
                if (isCurrent(owner) && currentPersonScreen(instance) != null) block(owner)
            }
        }
    }

    /** Stops the person page's citation and source requests, running and queued. */
    private fun cancelCitationWork() {
        citationWork?.scope?.cancel()
        citationWork = null
    }

    private fun pageViewer(page: PersonScreen): Viewer? {
        val tree = page.tree.valueOrNull ?: return null
        val account = _state.value.account.valueOrNull ?: return null
        return Viewer(tree, account)
    }

    /** Removes the person's profile photo (the image stays stored, unattached; owner-readable only). */
    fun removePhoto() {
        val page = editablePhotoPage() ?: return
        val person = page.person.valueOrNull ?: return
        if (person.profileMediaId == null) return
        updatePersonScreen(page.instance) { it.copy(photo = PhotoUpload(busy = true, removing = true)) }
        inSession { owner ->
            try {
                val current = currentPersonScreen(page.instance)?.person?.valueOrNull ?: return@inSession
                val updated = api.setProfilePhoto(page.treeId, current.id, null, current.version)
                if (isCurrent(owner)) updatePersonScreen(page.instance) { it.copy(person = Loadable.Ready(updated), photo = null) }
            } catch (e: ApiException) {
                if (!isCurrent(owner)) return@inSession
                if (e.failure == ApiFailure.CONFLICT) reloadPerson(page.instance)
                updatePersonScreen(page.instance) {
                    it.copy(photo = PhotoUpload(error = photoErrorFor(e.failure, PhotoStep.ATTACH), removing = true))
                }
            }
        }
    }

    /** Fetches an image this session may read into [AppState.photos], once per tree and media ID. */
    fun loadPhoto(treeId: String, mediaId: String) {
        val key = PhotoKey(treeId, mediaId)
        val existing = _state.value.photos[key]
        if (existing is Loadable.Ready) {
            putPhoto(key, existing) // most recently used again
            return
        }
        if (existing == Loadable.Loading) return
        putPhoto(key, Loadable.Loading)
        inSession { owner ->
            val result = load {
                val link = api.mediaDownload(treeId, mediaId)
                if (!Route.sameId(link.mediaId, mediaId)) throw ApiException(ApiFailure.NOT_FOUND)
                transfer.download(link.url)
            }
            if (isCurrent(owner)) putPhoto(key, result)
        }
    }

    private fun onPhotoPicked(instance: Long, file: PickedFile) {
        val page = currentPersonScreen(instance) ?: return
        if (page.photo?.busy == true) return
        val format = MediaRules.formatOf(file.contentType)
        val bytes = file.bytes
        // The reported size decides first: an oversized file was never read (bytes null).
        val error = when {
            format == null -> PhotoError.UNSUPPORTED_TYPE
            file.size <= 0 || bytes?.isEmpty() == true -> PhotoError.INVALID_IMAGE
            !MediaRules.isSizeAllowed(file.size) || bytes == null || !MediaRules.isSizeAllowed(bytes.size.toLong()) -> PhotoError.TOO_LARGE
            else -> null
        }
        if (error != null || format == null || bytes == null) {
            updatePersonScreen(instance) { it.copy(photo = PhotoUpload(error = error)) }
            return
        }
        runPhotoChange(instance, PendingPhoto(format.contentType, bytes))
    }

    private enum class PhotoStep { UPLOAD, VERIFY, ATTACH }

    /**
     * Presign → `PUT` straight to storage → finalize → attach at the person's current
     * version. Each step's result is kept in [PendingPhoto] so a retry skips what
     * already succeeded; finalizing again is safe on the server.
     */
    private fun runPhotoChange(instance: Long, start: PendingPhoto) {
        val page = currentPersonScreen(instance) ?: return
        if (page.photo?.busy == true) return
        updatePersonScreen(instance) { it.copy(photo = PhotoUpload(busy = true, pending = start)) }
        inSession { owner ->
            var pending = start
            var step = PhotoStep.UPLOAD
            try {
                val mediaId = pending.mediaId ?: run {
                    val upload = api.createMediaUpload(
                        page.treeId,
                        CreateMediaUploadRequest(MediaPurpose.PROFILE_PHOTO.name, pending.contentType, pending.bytes.size.toLong()),
                    )
                    transfer.upload(upload.upload, pending.bytes)
                    upload.media.id
                }
                pending = pending.withMedia(mediaId)
                step = PhotoStep.VERIFY
                api.finalizeMedia(page.treeId, mediaId)
                step = PhotoStep.ATTACH
                val current = currentPersonScreen(instance)?.person?.valueOrNull ?: return@inSession
                val updated = api.setProfilePhoto(page.treeId, current.id, mediaId, current.version)
                if (!isCurrent(owner)) return@inSession
                updatePersonScreen(instance) { it.copy(person = Loadable.Ready(updated), photo = null) }
                updated.profileMediaId?.let { loadPhoto(page.treeId, it) }
            } catch (e: ApiException) {
                if (!isCurrent(owner)) return@inSession
                val error = photoErrorFor(e.failure, step)
                // What a retry starts from: a failed transfer or an upload the server didn't
                // accept starts over; otherwise the upload is reused.
                val retryFrom = when {
                    error == PhotoError.INVALID_IMAGE -> null
                    step == PhotoStep.UPLOAD || (step == PhotoStep.VERIFY && e.failure == ApiFailure.CONFLICT) -> pending.withMedia(null)
                    else -> pending
                }
                if (error == PhotoError.CHANGED) reloadPerson(instance)
                updatePersonScreen(instance) { it.copy(photo = PhotoUpload(error = error, pending = retryFrom)) }
            }
        }
    }

    /** Re-reads the person on screen after a `409`, so a retry uses the version now stored. */
    private suspend fun reloadPerson(instance: Long) {
        val page = currentPersonScreen(instance) ?: return
        val person = load {
            api.person(page.treeId, page.personId).also {
                requireRoute(it.treeId, page.treeId)
                requireRoute(it.id, page.personId)
            }
        }
        if (person is Loadable.Ready) {
            updatePersonScreen(instance) { it.copy(person = person) }
            person.value.profileMediaId?.let { loadPhoto(page.treeId, it) }
        }
    }

    private fun photoErrorFor(failure: ApiFailure, step: PhotoStep): PhotoError = when {
        step == PhotoStep.UPLOAD && failure in setOf(ApiFailure.UNAVAILABLE, ApiFailure.FORBIDDEN, ApiFailure.NOT_FOUND) ->
            PhotoError.UPLOAD_FAILED
        step == PhotoStep.VERIFY && failure == ApiFailure.INVALID -> PhotoError.INVALID_IMAGE
        step == PhotoStep.VERIFY && failure == ApiFailure.CONFLICT -> PhotoError.UPLOAD_FAILED
        step == PhotoStep.ATTACH && failure == ApiFailure.CONFLICT -> PhotoError.CHANGED
        failure == ApiFailure.FORBIDDEN -> PhotoError.FORBIDDEN
        failure == ApiFailure.NOT_FOUND -> PhotoError.NOT_FOUND
        failure == ApiFailure.UNAVAILABLE -> PhotoError.UNAVAILABLE
        else -> PhotoError.SERVER
    }

    /** The person page on screen, if its person is loaded, visible, and the caller may change it. */
    private fun editablePhotoPage(): PersonScreen? {
        val page = _state.value.personScreen?.takeIf { isOnRoute(_state.value.route, it) } ?: return null
        val tree = page.tree.valueOrNull ?: return null
        val account = _state.value.account.valueOrNull ?: return null
        val person = page.person.valueOrNull ?: return null
        val viewer = Viewer(tree, account)
        return page.takeIf { viewer.canWrite && viewer.canSeeDetails(person) }
    }

    /**
     * Stores [value] as the most recently used image, then evicts least recently used
     * entries until the cache is within [photoCacheBytes] of encoded data and
     * [MAX_CACHED_PHOTOS] entries. Images on screen (the person page's photo and open
     * citation scans, both records of an open merge) and the one just stored are never evicted, so the
     * previews in use stay; an evicted image is fetched again when it's next shown.
     */
    private fun putPhoto(key: PhotoKey, value: Loadable<ByteArray>) = _state.update { state ->
        val photos = LinkedHashMap(state.photos)
        photos.remove(key)
        photos[key] = value
        val pinned = photosOnScreen(state) + key
        fun bytes() = photos.values.sumOf { (it as? Loadable.Ready)?.value?.size?.toLong() ?: 0L }
        val candidates = photos.keys.filter { it !in pinned }.iterator()
        while ((bytes() > photoCacheBytes || photos.size > MAX_CACHED_PHOTOS) && candidates.hasNext()) {
            photos.remove(candidates.next())
        }
        state.copy(photos = photos)
    }

    private fun photosOnScreen(state: AppState): Set<PhotoKey> = buildSet {
        state.personScreen?.let { page ->
            page.person.valueOrNull?.profileMediaId?.let { add(PhotoKey(page.treeId, it)) }
            page.openScans.forEach { add(PhotoKey(page.treeId, it)) }
        }
        state.treeScreen?.let { screen ->
            screen.merge?.records?.valueOrNull?.let { pair ->
                listOfNotNull(pair.first.profileMediaId, pair.second.profileMediaId).forEach { add(PhotoKey(screen.treeId, it)) }
            }
        }
    }

    private fun onAuth(auth: AuthState) {
        if (auth is AuthState.SignedIn) {
            if (auth.subject == subject) {
                _state.update { it.copy(auth = auth) }
                return
            }
            resetSession()
            subject = auth.subject
            val route = currentRoute()
            _state.value = AppState(auth = auth, route = route)
            loadAccount()
            showRoute(route)
        } else {
            subject = null
            resetSession()
            _state.value = AppState(auth = auth, route = currentRoute())
        }
    }

    private fun onLocationChanged() {
        val route = currentRoute()
        if (route != _state.value.route) showRoute(route)
    }

    private fun showRoute(route: Route) {
        formVersion++
        // Every route change leaves the person page on screen (a person route opens a new
        // one), so its citation and source requests stop, running or queued.
        cancelCitationWork()
        _state.update { it.copy(route = route, form = FormState()) }
        if (_state.value.auth !is AuthState.SignedIn) return
        when (route) {
            Route.Trees -> {
                _state.update { it.copy(treeScreen = null, personScreen = null) }
                if (_state.value.trees !is Loadable.Ready) loadTrees()
            }
            is Route.Tree -> {
                val screen = _state.value.treeScreen
                if (screen == null || screen.treeId != route.treeId) {
                    val fresh = TreeScreen(route.treeId, nextInstance++, view = route.view, rootPersonId = route.rootPersonId)
                    _state.update { it.copy(treeScreen = fresh) }
                    loadTreeScreen(fresh.instance)
                } else {
                    updateTreeScreen(screen.instance) { it.copy(view = route.view, rootPersonId = route.rootPersonId) }
                    loadBranch(screen.instance, force = false)
                }
            }
            is Route.Person -> {
                val page = PersonScreen(route.treeId, route.personId, nextInstance++)
                _state.update { it.copy(personScreen = page) }
                loadPersonScreen(page.instance)
            }
        }
    }

    private fun loadAccount() = inSession { owner ->
        val result = load { api.me() }
        applyIf(owner) { it.copy(account = result) }
        // Citations wait for the viewer: request them now if a person page is still open.
        if (isCurrent(owner) && result is Loadable.Ready) {
            _state.value.personScreen?.takeIf { isOnRoute(_state.value.route, it) }?.let { loadPageCitations(it.instance) }
        }
    }

    private fun loadTrees() = inSession { owner ->
        val result = load { api.trees().sortedBy { it.name.lowercase() } }
        applyIf(owner) { it.copy(trees = result) }
    }

    private fun loadTreeScreen(instance: Long) {
        val screen = currentScreen(instance) ?: return
        updateTreeScreen(instance) { it.copy(tree = Loadable.Loading, graph = Loadable.Loading) }
        inSession { owner ->
            val tree = load { api.tree(screen.treeId) }
            updateScreen(owner, instance) { it.copy(tree = tree) }
        }
        refreshTree(instance)
    }

    /**
     * Reloads the bundle (and branch) after opening or changing the tree. The
     * current graph stays on screen while it loads; a failure replaces it, so a
     * tree the caller can no longer read (403/404) or can't reach isn't left
     * showing with live controls, and the failure offers a retry.
     */
    private fun refreshTree(instance: Long) {
        val screen = currentScreen(instance) ?: return
        inSession { owner ->
            val graph = load { sortedGraph(api.graph(screen.treeId)) }
            updateScreen(owner, instance) { it.copy(graph = graph) }
        }
        loadBranch(instance, force = true)
    }

    /** Fetches the root's ancestors or descendants for the current view, when it needs them. */
    private fun loadBranch(instance: Long, force: Boolean) {
        val screen = currentScreen(instance) ?: return
        val root = screen.rootPersonId
        if (screen.view == TreeViewKind.FULL || root == null) {
            updateTreeScreen(instance) { it.copy(branch = null, branchKey = null) }
            return
        }
        val key = BranchKey(screen.view, root)
        if (!force && screen.branchKey == key && screen.branch !is Loadable.Failed) return
        updateTreeScreen(instance) {
            it.copy(branchKey = key, branch = if (it.branchKey == key && it.branch is Loadable.Ready) it.branch else Loadable.Loading)
        }
        inSession { owner ->
            val result = load {
                val people = when (key.view) {
                    TreeViewKind.ANCESTORS -> api.ancestors(screen.treeId, root)
                    else -> api.descendants(screen.treeId, root)
                }
                people.map { it.id }.toSet()
            }
            updateScreen(owner, instance) { current ->
                if (current.branchKey == key) current.copy(branch = result) else current
            }
        }
    }

    /**
     * Loads a person page: the tree, then the person, then the family bundle and
     * the timeline side by side. A record that doesn't belong to the route's
     * tree is treated as not found, so a wrong-tree URL never shows another
     * tree's data even if a response were mismatched. When the tree or person
     * fails, nothing else is requested.
     */
    private fun loadPersonScreen(instance: Long) {
        val target = currentPersonScreen(instance) ?: return
        // A full reload starts the Sources section over, so earlier requests stop.
        cancelCitationWork()
        updatePersonScreen(instance) {
            it.copy(
                tree = Loadable.Loading,
                person = Loadable.Loading,
                graph = Loadable.Loading,
                events = Loadable.Loading,
                sources = null,
                citations = emptyMap(),
                openScans = emptyList(),
            )
        }
        inSession { owner ->
            val tree = load { api.tree(target.treeId).also { requireRoute(it.id, target.treeId) } }
            val person = if (tree is Loadable.Failed) {
                tree
            } else {
                load {
                    api.person(target.treeId, target.personId).also {
                        requireRoute(it.treeId, target.treeId)
                        requireRoute(it.id, target.personId)
                    }
                }
            }
            if (!isCurrent(owner)) return@inSession
            if (person is Loadable.Failed) {
                updatePersonScreen(instance) { it.copy(tree = tree, person = person, graph = person, events = person) }
                return@inSession
            }
            updatePersonScreen(instance) { it.copy(tree = tree, person = person) }
            loadPersonFamily(instance)
            loadPersonTimeline(instance)
            loadPageCitations(instance)
            // FT-008: the server sends a photo ID only for a person this caller may see.
            (person as? Loadable.Ready)?.value?.profileMediaId?.let { loadPhoto(target.treeId, it) }
        }
    }

    private fun loadPersonFamily(instance: Long) {
        val target = currentPersonScreen(instance) ?: return
        updatePersonScreen(instance) { it.copy(graph = Loadable.Loading) }
        inSession { owner ->
            val graph = load { api.graph(target.treeId).also { requireRoute(it.treeId, target.treeId) } }
            if (!isCurrent(owner)) return@inSession
            updatePersonScreen(instance) { it.copy(graph = graph) }
            // The person's unions are now known: their citations can be requested.
            if (graph is Loadable.Ready) loadPageCitations(instance)
        }
    }

    private fun loadPersonTimeline(instance: Long) {
        val target = currentPersonScreen(instance) ?: return
        updatePersonScreen(instance) { it.copy(events = Loadable.Loading) }
        inSession { owner ->
            val events = load { api.events(target.treeId, target.personId) }
            if (!isCurrent(owner)) return@inSession
            updatePersonScreen(instance) { it.copy(events = events) }
            // The timeline's events are now known: their citations can be requested.
            if (events is Loadable.Ready) loadPageCitations(instance)
        }
    }

    private fun currentPersonScreen(instance: Long): PersonScreen? =
        _state.value.personScreen?.takeIf { it.instance == instance && isOnRoute(_state.value.route, it) }

    /**
     * The server accepts dashed and hex-only UUIDs in any case and answers with
     * the canonical form, so UUIDs are compared by identity ([Route.sameId]).
     */
    private fun requireRoute(returned: String, requested: String) {
        if (!Route.sameId(returned, requested)) throw ApiException(ApiFailure.NOT_FOUND)
    }

    /** Runs a form action; an [ApiException] becomes the form's localized error. */
    private var formVersion = 0L

    private fun submit(block: suspend (CoroutineScope, Long) -> Unit) {
        if (_state.value.form.submitting) return
        val version = ++formVersion
        _state.update { it.copy(form = FormState(submitting = true)) }
        inSession { owner ->
            try {
                block(owner, version)
            } catch (e: ApiException) {
                if (formIsCurrent(owner, version)) formError(formErrorFor(e.failure))
            } finally {
                if (formIsCurrent(owner, version)) _state.update { it.copy(form = it.form.copy(submitting = false)) }
            }
        }
    }

    private fun formIsCurrent(owner: CoroutineScope, version: Long): Boolean =
        isCurrent(owner) && formVersion == version

    private fun formError(error: FormError) = _state.update { it.copy(form = FormState(error = error)) }

    private fun currentScreen(instance: Long): TreeScreen? = _state.value.treeScreen?.takeIf { it.instance == instance }

    /** Changes the tree screen if it is still [instance] (any screen when null). */
    private fun updateTreeScreen(instance: Long?, change: (TreeScreen) -> TreeScreen) = _state.update { state ->
        val screen = state.treeScreen
        if (screen == null || (instance != null && screen.instance != instance)) state else state.copy(treeScreen = change(screen))
    }

    private fun updateScreen(owner: CoroutineScope, instance: Long, change: (TreeScreen) -> TreeScreen) {
        if (isCurrent(owner)) updateTreeScreen(instance, change)
    }

    /** Changes the person page only if it is still [instance] and still the current route. */
    private fun updatePersonScreen(instance: Long, change: (PersonScreen) -> PersonScreen) = _state.update { state ->
        val page = state.personScreen
        if (page == null || page.instance != instance || !isOnRoute(state.route, page)) state else state.copy(personScreen = change(page))
    }

    private fun isOnRoute(route: Route, page: PersonScreen): Boolean =
        route is Route.Person && route.treeId == page.treeId && route.personId == page.personId

    /** Launches [block] in the current session's scope, passing that scope as its owner token. */
    private fun inSession(block: suspend (CoroutineScope) -> Unit) {
        val owner = sessionScope
        owner.launch { block(owner) }
    }

    private fun isCurrent(owner: CoroutineScope): Boolean = owner === sessionScope && owner.isActive

    private fun applyIf(owner: CoroutineScope, change: (AppState) -> AppState) {
        if (isCurrent(owner)) _state.update(change)
    }

    private fun newSessionScope(): CoroutineScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private fun resetSession() {
        cancelCitationWork()
        sessionScope.cancel()
        sessionScope = newSessionScope()
    }

    private fun currentRoute(): Route = Route.parse(UrlParts(bridge.currentUrl()).fragment)

    private fun viewerFor(screen: TreeScreen?): Viewer? {
        val tree = screen?.tree?.valueOrNull ?: return null
        val account = _state.value.account.valueOrNull ?: return null
        return Viewer(tree, account)
    }

    private suspend fun <T> load(block: suspend () -> T): Loadable<T> =
        try {
            Loadable.Ready(block())
        } catch (e: ApiException) {
            Loadable.Failed(e.failure)
        }

    companion object {
        /** Images kept for display at once (FT-008); the least recently used is dropped first. */
        internal const val MAX_CACHED_PHOTOS = 40

        /** Citation and source requests a person page runs at once; the rest wait their turn. */
        const val MAX_ACTIVE_CITATION_REQUESTS = 4

        /**
         * Citation scans open on a person page at once. With the profile photo that is the
         * three images [DEFAULT_PHOTO_CACHE_BYTES] has room for at the upload limit.
         */
        const val MAX_OPEN_SCANS = 2

        /**
         * Encoded image bytes kept for display (FT-008). Room for the three images a screen
         * can show at the 20 MiB upload limit, plus recently viewed ones.
         */
        const val DEFAULT_PHOTO_CACHE_BYTES: Long = 64L * 1024 * 1024

        /** People in name order, so the layout and pickers are stable between reloads. */
        internal fun sortedGraph(graph: TreeGraphDto): TreeGraphDto =
            graph.copy(people = graph.people.sortedWith(compareBy({ Viewer.sortKey(it).lowercase() }, { it.id })))

        /**
         * The unions whose partners are exactly these parents (either order), or,
         * with no second parent, the single-partner unions of [parentId]. A pair can
         * have several (married, divorced, remarried); the user picks one.
         */
        fun unionsForParents(graph: TreeGraphDto, parentId: String, otherParentId: String?): List<UnionDto> =
            graph.unions.filter { union ->
                if (otherParentId == null) {
                    union.partner1Id == parentId && union.partner2Id == null
                } else {
                    setOf(union.partner1Id, union.partner2Id) == setOf(parentId, otherParentId)
                }
            }

        /** The profile fields that differ between two records (FT-007), by the `:core` rule the server applies. */
        fun differingFields(first: PersonDto, second: PersonDto): Set<MergeField> =
            PersonMerge.differingFields(PersonMerge.profileOf(first.toDomain()), PersonMerge.profileOf(second.toDomain()))

        internal fun mergeErrorFor(failure: ApiFailure): MergeError = when (failure) {
            ApiFailure.CONFLICT -> MergeError.CHANGED
            ApiFailure.FORBIDDEN -> MergeError.FORBIDDEN
            ApiFailure.NOT_FOUND -> MergeError.NOT_FOUND
            ApiFailure.INVALID -> MergeError.INVALID
            ApiFailure.UNAVAILABLE -> MergeError.UNAVAILABLE
            ApiFailure.SESSION_EXPIRED, ApiFailure.SERVER -> MergeError.SERVER
        }

        internal fun formErrorFor(failure: ApiFailure): FormError = when (failure) {
            ApiFailure.CONFLICT -> FormError.ALREADY_LINKED
            ApiFailure.FORBIDDEN -> FormError.FORBIDDEN
            ApiFailure.NOT_FOUND -> FormError.NOT_FOUND
            ApiFailure.INVALID -> FormError.INVALID
            ApiFailure.UNAVAILABLE -> FormError.UNAVAILABLE
            ApiFailure.SESSION_EXPIRED, ApiFailure.SERVER -> FormError.SERVER
        }
    }
}
