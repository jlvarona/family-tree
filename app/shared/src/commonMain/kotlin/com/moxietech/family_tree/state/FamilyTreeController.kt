package com.moxietech.family_tree.state

import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.auth.AuthSession
import com.moxietech.family_tree.auth.AuthState
import com.moxietech.family_tree.auth.BrowserBridge
import com.moxietech.family_tree.auth.ParsedUrl
import com.moxietech.family_tree.client.ApiException
import com.moxietech.family_tree.client.ApiFailure
import com.moxietech.family_tree.client.FamilyTreeApi
import com.moxietech.family_tree.navigation.Route
import com.moxietech.family_tree.navigation.TreeViewKind
import com.moxietech.family_tree.validation.Validation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the web workflow: session, routing, loading and the owner's create
 * actions, as one [AppState] for the Compose UI.
 *
 * **No stale user data.** All loading runs in a per-session job. When the
 * session ends, expires or changes subject, that job is cancelled and the state
 * is replaced by an empty one before anything else happens, and every result
 * is applied only if it still belongs to the current session (and, for tree
 * data, the tree still on screen).
 */
class FamilyTreeController(
    private val session: AuthSession,
    private val api: FamilyTreeApi,
    private val bridge: BrowserBridge,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var sessionJob: Job = SupervisorJob(scope.coroutineContext[Job])
    private var generation = 0
    private var subject: String? = null

    fun start() {
        bridge.onHashChange(::onLocationChanged)
        scope.launch { session.state.collect { onAuth(it) } }
        scope.launch { session.start() }
    }

    fun signIn() = session.signIn(_state.value.route.toFragment())

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
            if (screen.tree is Loadable.Failed || screen.graph is Loadable.Failed) loadTreeScreen(screen.treeId)
            if (screen.branch is Loadable.Failed) loadBranch(force = true)
        }
        current.personScreen?.let { if (it.person is Loadable.Failed || it.tree is Loadable.Failed) loadPersonScreen(it) }
    }

    fun selectPerson(personId: String?) = updateTreeScreen { it.copy(selectedPersonId = personId) }

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
        navigate(Route.Tree(personScreen.treeId, screen?.view ?: TreeViewKind.FULL, screen?.rootPersonId ?: personScreen.personId))
        selectPerson(personScreen.personId)
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun clearFormError() = _state.update { it.copy(form = FormState()) }

    fun createTree(name: String, onCreated: () -> Unit) {
        if (Validation.validateTreeName(name).isNotEmpty()) return formError(FormError.NAME_REQUIRED)
        submit {
            val tree = api.createTree(name.trim())
            loadTrees()
            onCreated()
            navigate(Route.Tree(tree.id))
        }
    }

    fun createPerson(input: PersonInput, onCreated: () -> Unit) {
        val treeId = _state.value.treeScreen?.treeId ?: return
        if (Validation.validatePersonName(input.givenNames).isNotEmpty()) return formError(FormError.NAME_REQUIRED)
        submit { gen ->
            val response = api.createPerson(
                treeId,
                CreatePersonRequest(
                    givenNames = input.givenNames.trim(),
                    surnamePaternal = input.surnamePaternal.trim().ifEmpty { null },
                    surnameMaternal = input.surnameMaternal.trim().ifEmpty { null },
                    sex = input.sex.name,
                    isLiving = input.isLiving,
                ),
            )
            val viewer = viewerFor(_state.value.treeScreen)
            applyIfCurrent(gen) { state ->
                state.copy(
                    notice = response.possibleDuplicates.takeIf { it.isNotEmpty() }?.let { candidates ->
                        Notice.PossibleDuplicates(candidates.map { viewer?.displayName(it.person) })
                    },
                )
            }
            updateTreeScreen(treeId) { it.copy(selectedPersonId = response.person.id) }
            onCreated()
            refreshTree(treeId)
        }
    }

    fun linkChild(input: LinkChildInput, onLinked: () -> Unit) {
        val screen = _state.value.treeScreen ?: return
        val graph = screen.graph.valueOrNull ?: return
        val parentId = input.parentId ?: return formError(FormError.PARENT_REQUIRED)
        val childId = input.childId ?: return formError(FormError.CHILD_REQUIRED)
        val otherId = input.otherParentId
        if (otherId == parentId) return formError(FormError.SAME_PARENT_TWICE)
        if (childId == parentId || childId == otherId) return formError(FormError.CHILD_IS_PARENT)
        submit(onFailure = { refreshTree(screen.treeId) }) {
            val union = findUnion(graph, parentId, otherId) ?: api.createUnion(
                screen.treeId,
                CreateUnionRequest(partner1Id = parentId, partner2Id = otherId, type = input.unionType.name),
            )
            api.addChild(screen.treeId, union.id, AddUnionChildRequest(childId, input.relation.name))
            _state.update { it.copy(notice = Notice.ChildLinked) }
            onLinked()
            refreshTree(screen.treeId)
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
        _state.update { it.copy(route = route, form = FormState()) }
        if (_state.value.auth !is AuthState.SignedIn) return
        when (route) {
            Route.Trees -> if (_state.value.trees !is Loadable.Ready) loadTrees()
            is Route.Tree -> {
                val screen = _state.value.treeScreen
                if (screen == null || screen.treeId != route.treeId) {
                    _state.update {
                        it.copy(treeScreen = TreeScreen(route.treeId, view = route.view, rootPersonId = route.rootPersonId))
                    }
                    loadTreeScreen(route.treeId)
                } else {
                    updateTreeScreen { it.copy(view = route.view, rootPersonId = route.rootPersonId) }
                }
                loadBranch(force = false)
            }
            is Route.Person -> {
                val personScreen = PersonScreen(route.treeId, route.personId)
                _state.update { it.copy(personScreen = personScreen) }
                loadPersonScreen(personScreen)
            }
        }
    }

    private fun loadAccount() = inSession { gen ->
        val result = load { api.me() }
        applyIfCurrent(gen) { it.copy(account = result) }
    }

    private fun loadTrees() = inSession { gen ->
        val result = load { api.trees().sortedBy { it.name.lowercase() } }
        applyIfCurrent(gen) { it.copy(trees = result) }
    }

    private fun loadTreeScreen(treeId: String) {
        updateTreeScreen(treeId) { it.copy(tree = Loadable.Loading, graph = Loadable.Loading) }
        inSession { gen ->
            val tree = load { api.tree(treeId) }
            if (gen == generation) updateTreeScreen(treeId) { it.copy(tree = tree) }
        }
        refreshTree(treeId)
    }

    /** Reloads the bundle (and branch) after a change, keeping the current one on screen meanwhile. */
    private fun refreshTree(treeId: String) {
        inSession { gen ->
            val graph = load { sortedGraph(api.graph(treeId)) }
            if (gen != generation) return@inSession
            updateTreeScreen(treeId) { screen ->
                if (graph is Loadable.Failed && screen.graph is Loadable.Ready) screen else screen.copy(graph = graph)
            }
        }
        loadBranch(force = true)
    }

    /** Fetches the root's ancestors or descendants for the current view, when it needs them. */
    private fun loadBranch(force: Boolean) {
        val screen = _state.value.treeScreen ?: return
        val root = screen.rootPersonId
        if (screen.view == TreeViewKind.FULL || root == null) {
            updateTreeScreen(screen.treeId) { it.copy(branch = null, branchKey = null) }
            return
        }
        val key = BranchKey(screen.view, root)
        if (!force && screen.branchKey == key && screen.branch !is Loadable.Failed) return
        updateTreeScreen(screen.treeId) {
            it.copy(branchKey = key, branch = if (it.branchKey == key && it.branch is Loadable.Ready) it.branch else Loadable.Loading)
        }
        inSession { gen ->
            val result = load {
                val people = when (key.view) {
                    TreeViewKind.ANCESTORS -> api.ancestors(screen.treeId, root)
                    else -> api.descendants(screen.treeId, root)
                }
                people.map { it.id }.toSet()
            }
            if (gen != generation) return@inSession
            updateTreeScreen(screen.treeId) { current ->
                if (current.branchKey == key) current.copy(branch = result) else current
            }
        }
    }

    private fun loadPersonScreen(target: PersonScreen) {
        val matches = { s: AppState -> s.personScreen?.let { it.treeId == target.treeId && it.personId == target.personId } == true }
        _state.update { if (matches(it)) it.copy(personScreen = target.copy(tree = Loadable.Loading, person = Loadable.Loading)) else it }
        inSession { gen ->
            val tree = load { api.tree(target.treeId) }
            val person = if (tree is Loadable.Failed) tree else load { api.person(target.treeId, target.personId) }
            applyIfCurrent(gen) { state ->
                if (matches(state)) state.copy(personScreen = state.personScreen?.copy(tree = tree, person = person)) else state
            }
        }
    }

    private fun submit(onFailure: () -> Unit = {}, block: suspend (Int) -> Unit) {
        if (_state.value.form.submitting) return
        _state.update { it.copy(form = FormState(submitting = true)) }
        inSession { gen ->
            try {
                block(gen)
                applyIfCurrent(gen) { it.copy(form = FormState()) }
            } catch (e: ApiException) {
                applyIfCurrent(gen) { it.copy(form = FormState(error = formErrorFor(e.failure))) }
                if (gen == generation) onFailure()
            }
        }
    }

    private fun formError(error: FormError) = _state.update { it.copy(form = FormState(error = error)) }

    private fun updateTreeScreen(treeId: String? = null, change: (TreeScreen) -> TreeScreen) = _state.update { state ->
        val screen = state.treeScreen
        if (screen == null || (treeId != null && screen.treeId != treeId)) state else state.copy(treeScreen = change(screen))
    }

    private fun inSession(block: suspend (Int) -> Unit) {
        val gen = generation
        scope.launch(sessionJob) { block(gen) }
    }

    private fun applyIfCurrent(gen: Int, change: (AppState) -> AppState) {
        if (gen == generation) _state.update(change)
    }

    private fun resetSession() {
        sessionJob.cancel()
        sessionJob = SupervisorJob(scope.coroutineContext[Job])
        generation++
    }

    private fun currentRoute(): Route = Route.parse(ParsedUrl.parse(bridge.currentUrl()).fragment)

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

        /** An existing union of exactly these partners (either order), so a second child reuses it. */
        internal fun findUnion(graph: TreeGraphDto, parentId: String, otherParentId: String?): UnionDto? =
            graph.unions.firstOrNull { union ->
                if (otherParentId == null) {
                    (union.partner1Id == parentId && union.partner2Id == null)
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
