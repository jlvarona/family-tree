package com.moxietech.family_tree.state

import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.auth.AuthSession
import com.moxietech.family_tree.auth.AuthState
import com.moxietech.family_tree.auth.BrowserBridge
import com.moxietech.family_tree.auth.UrlParts
import com.moxietech.family_tree.client.ApiException
import com.moxietech.family_tree.client.ApiFailure
import com.moxietech.family_tree.client.FamilyTreeApi
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
        }
        current.personScreen?.let { page ->
            if (page.person is Loadable.Failed || page.tree is Loadable.Failed) {
                loadPersonScreen(page.instance)
            } else {
                // Only the failed sections are fetched again; the rest stays on screen.
                if (page.graph is Loadable.Failed) loadPersonFamily(page.instance)
                if (page.events is Loadable.Failed) loadPersonTimeline(page.instance)
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
                        Notice.PossibleDuplicates(candidates.map { viewer?.displayName(it.person) })
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
        updatePersonScreen(instance) {
            it.copy(tree = Loadable.Loading, person = Loadable.Loading, graph = Loadable.Loading, events = Loadable.Loading)
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
        }
    }

    private fun loadPersonFamily(instance: Long) {
        val target = currentPersonScreen(instance) ?: return
        updatePersonScreen(instance) { it.copy(graph = Loadable.Loading) }
        inSession { owner ->
            val graph = load { api.graph(target.treeId).also { requireRoute(it.treeId, target.treeId) } }
            if (isCurrent(owner)) updatePersonScreen(instance) { it.copy(graph = graph) }
        }
    }

    private fun loadPersonTimeline(instance: Long) {
        val target = currentPersonScreen(instance) ?: return
        updatePersonScreen(instance) { it.copy(events = Loadable.Loading) }
        inSession { owner ->
            val events = load { api.events(target.treeId, target.personId) }
            if (isCurrent(owner)) updatePersonScreen(instance) { it.copy(events = events) }
        }
    }

    private fun currentPersonScreen(instance: Long): PersonScreen? =
        _state.value.personScreen?.takeIf { it.instance == instance }

    /**
     * The server answers with canonical (lower-case) UUIDs and accepts any
     * spelling in the path, so IDs are compared without case.
     */
    private fun requireRoute(returned: String, requested: String) {
        if (!returned.equals(requested, ignoreCase = true)) throw ApiException(ApiFailure.NOT_FOUND)
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

    private fun updatePersonScreen(instance: Long, change: (PersonScreen) -> PersonScreen) = _state.update { state ->
        val page = state.personScreen
        if (page == null || page.instance != instance) state else state.copy(personScreen = change(page))
    }

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
