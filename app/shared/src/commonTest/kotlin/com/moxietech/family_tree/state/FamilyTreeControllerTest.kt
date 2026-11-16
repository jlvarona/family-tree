package com.moxietech.family_tree.state

import com.moxietech.family_tree.FakeBridge
import com.moxietech.family_tree.TREE_ID
import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreatePersonResponse
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.TreeDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.auth.AuthSession
import com.moxietech.family_tree.auth.AuthState
import com.moxietech.family_tree.auth.SignedOutReason
import com.moxietech.family_tree.client.ApiException
import com.moxietech.family_tree.client.ApiFailure
import com.moxietech.family_tree.client.FamilyTreeApi
import com.moxietech.family_tree.domain.ChildRelation
import com.moxietech.family_tree.domain.Sex
import com.moxietech.family_tree.domain.UnionType
import com.moxietech.family_tree.navigation.Route
import com.moxietech.family_tree.navigation.TreeViewKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
// Aliased so FakeApi's own graph()/person() members can't capture these calls.
import com.moxietech.family_tree.graph as graphOf
import com.moxietech.family_tree.person as personOf
import com.moxietech.family_tree.union as unionOf

class FamilyTreeControllerTest {

    private class FakeSession : AuthSession {
        override val state = MutableStateFlow<AuthState>(AuthState.Starting)
        val signInRoutes = mutableListOf<String>()
        var signedOut = false

        override suspend fun start() = Unit

        override suspend fun signIn(returnRoute: String) {
            signInRoutes += returnRoute
        }

        override fun signOut() {
            signedOut = true
            state.value = AuthState.SignedOut()
        }
    }

    private class FakeApi : FamilyTreeApi {
        val account = AccountDto("owner", "owner@example.com", "Owner", "en")
        val tree = TreeDto(TREE_ID, "Romero", "owner", 0)
        var graph: TreeGraphDto = graphOf(listOf("mum", "dad", "kid"), unionOf("u1", "mum", "dad"))

        /** Each graph call takes the next gate, if any, and waits for it. */
        val graphGates = ArrayDeque<CompletableDeferred<Unit>>()

        /** Each graph call fails with the next queued failure, if any. */
        val graphFailures = ArrayDeque<ApiFailure>()
        var ancestorIds = listOf<String>()
        val calls = mutableListOf<String>()

        /** Each addChild fails with the next queued failure, if any. */
        val addChildFailures = ArrayDeque<ApiFailure>()
        var personCreationGate: CompletableDeferred<Unit>? = null
        private var unionCount = 0

        override suspend fun me() = account.also { calls += "me" }

        override suspend fun trees() = listOf(tree).also { calls += "trees" }

        override suspend fun createTree(name: String) = tree.also { calls += "createTree:$name" }

        override suspend fun tree(treeId: String): TreeDto {
            calls += "tree:$treeId"
            if (treeId != TREE_ID) throw ApiException(ApiFailure.NOT_FOUND)
            return tree
        }

        override suspend fun graph(treeId: String): TreeGraphDto {
            calls += "graph:$treeId"
            graphGates.removeFirstOrNull()?.await()
            graphFailures.removeFirstOrNull()?.let { throw ApiException(it) }
            return graph
        }

        override suspend fun person(treeId: String, personId: String): PersonDto =
            graph.people.firstOrNull { it.id == personId }?.also { calls += "person:$personId" }
                ?: throw ApiException(ApiFailure.NOT_FOUND)

        override suspend fun ancestors(treeId: String, personId: String) =
            ancestorIds.map { personOf(it) }.also { calls += "ancestors:$personId" }

        override suspend fun descendants(treeId: String, personId: String) =
            emptyList<PersonDto>().also { calls += "descendants:$personId" }

        override suspend fun createPerson(treeId: String, request: CreatePersonRequest): CreatePersonResponse {
            calls += "createPerson:${request.givenNames}"
            personCreationGate?.let {
                it.await()
                throw ApiException(ApiFailure.UNAVAILABLE)
            }
            return CreatePersonResponse(personOf("new", request.givenNames))
        }

        /** Saves the union into the graph, as the server would, so a reload shows it. */
        override suspend fun createUnion(treeId: String, request: CreateUnionRequest): UnionDto {
            calls += "createUnion:${request.partner1Id}+${request.partner2Id}:${request.type}"
            val created = unionOf("u-new-${++unionCount}", request.partner1Id, request.partner2Id, type = request.type)
            graph = graph.copy(unions = graph.unions + created)
            return created
        }

        override suspend fun addChild(treeId: String, unionId: String, request: AddUnionChildRequest): UnionDto {
            calls += "addChild:$unionId:${request.childId}:${request.relation}"
            addChildFailures.removeFirstOrNull()?.let { throw ApiException(it) }
            return graph.unions.single { it.id == unionId }
        }
    }

    private val bridge = FakeBridge()
    private val session = FakeSession()
    private val api = FakeApi()

    private fun TestScope.controller(): FamilyTreeController =
        FamilyTreeController(session, api, bridge, backgroundScope).also {
            it.start()
            runCurrent()
        }

    private fun TestScope.signIn(subject: String = "owner") {
        session.state.value = AuthState.SignedIn(subject)
        runCurrent()
    }

    private fun TestScope.openTree(): FamilyTreeController {
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID"
        return controller().also { signIn() }
    }

    @Test
    fun signedInReloadOpensTheRouteFromTheUrl() = runTest {
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID?view=ancestors&root=kid"
        api.ancestorIds = listOf("mum", "dad")
        val controller = controller()
        signIn()

        val state = controller.state.value
        assertEquals(Route.Tree(TREE_ID, TreeViewKind.ANCESTORS, "kid"), state.route)
        val screen = state.treeScreen!!
        assertEquals(api.tree, screen.tree.valueOrNull)
        assertEquals(listOf("dad", "kid", "mum"), screen.graph.valueOrNull?.people?.map { it.id })
        assertEquals(setOf("mum", "dad"), screen.branch?.valueOrNull)
        assertTrue("ancestors:kid" in api.calls)
    }

    @Test
    fun expiredSessionDropsEveryPieceOfUserData() = runTest {
        val controller = openTree()
        controller.selectPerson("kid")
        assertIs<Loadable.Ready<*>>(controller.state.value.treeScreen?.graph)

        session.state.value = AuthState.SignedOut(SignedOutReason.SESSION_EXPIRED)
        runCurrent()

        val state = controller.state.value
        assertEquals(AuthState.SignedOut(SignedOutReason.SESSION_EXPIRED), state.auth)
        assertEquals(Loadable.Loading, state.account)
        assertEquals(Loadable.Loading, state.trees)
        assertNull(state.treeScreen)
        assertNull(state.personScreen)
        // The route survives so signing in again returns to the same page.
        controller.signIn()
        runCurrent()
        assertEquals(listOf("/trees/$TREE_ID"), session.signInRoutes)
    }

    @Test
    fun responseArrivingAfterTheSessionEndedIsDiscarded() = runTest {
        val gate = CompletableDeferred<Unit>()
        api.graphGates += gate
        val controller = openTree()
        assertEquals(Loadable.Loading, controller.state.value.treeScreen?.graph)

        session.state.value = AuthState.SignedOut(SignedOutReason.SESSION_EXPIRED)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertNull(controller.state.value.treeScreen)
    }

    @Test
    fun responseForAnEarlierVisitToTheSameTreeIsDiscarded() = runTest {
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        api.graphGates += first
        api.graphGates += second
        val controller = openTree()

        controller.navigate(Route.Trees)
        controller.navigate(Route.Tree(TREE_ID))
        runCurrent()
        val current = graphOf(listOf("mum", "dad", "kid", "new"))
        api.graph = current
        second.complete(Unit)
        runCurrent()
        api.graph = graphOf(listOf("stale"))
        first.complete(Unit)
        runCurrent()

        assertEquals(listOf("dad", "kid", "mum", "new"), controller.state.value.treeScreen?.graph?.valueOrNull?.people?.map { it.id })
    }

    @Test
    fun anotherAccountSigningInStartsFromEmptyState() = runTest {
        val controller = openTree()
        controller.selectPerson("kid")

        signIn("someone-else")

        val screen = controller.state.value.treeScreen!!
        assertNull(screen.selectedPersonId)
        assertEquals(2, api.calls.count { it == "me" })
    }

    @Test
    fun signOutClearsStateBeforeLeavingForKeycloak() = runTest {
        val controller = openTree()

        controller.signOut()

        assertTrue(session.signedOut)
        assertNull(controller.state.value.treeScreen)
        assertIs<AuthState.SignedOut>(controller.state.value.auth)
    }

    @Test
    fun linkingThroughTheParentsOnlyUnionUsesIt() = runTest {
        val controller = openTree()
        var closed = false

        controller.linkChild(LinkChildInput("dad", "mum", UnionChoice.Existing("u1"), "kid", ChildRelation.ADOPTED)) { closed = true }
        runCurrent()

        assertTrue(closed)
        assertTrue(api.calls.none { it.startsWith("createUnion") })
        assertTrue("addChild:u1:kid:ADOPTED" in api.calls)
        assertEquals(Notice.ChildLinked, controller.state.value.treeScreen?.notice)
        assertEquals(2, api.calls.count { it == "graph:$TREE_ID" }, "the tree is reloaded after the link")
    }

    @Test
    fun pairWithTwoUnionsNeedsAnExplicitChoiceAndHonoursIt() = runTest {
        api.graph = graphOf(
            listOf("mum", "dad", "kid"),
            unionOf("first", "mum", "dad", type = "DIVORCED"),
            unionOf("second", "dad", "mum", type = "MARRIED"),
        )
        val controller = openTree()
        assertEquals(
            listOf("first", "second"),
            FamilyTreeController.unionsForParents(controller.state.value.treeScreen!!.graph.valueOrNull!!, "mum", "dad").map { it.id },
        )

        controller.linkChild(LinkChildInput("mum", "dad", null, "kid", ChildRelation.BIRTH)) {}
        assertEquals(FormError.UNION_REQUIRED, controller.state.value.form.error)
        controller.linkChild(LinkChildInput("mum", "dad", UnionChoice.Existing("u1-elsewhere"), "kid", ChildRelation.BIRTH)) {}
        assertEquals(FormError.UNION_REQUIRED, controller.state.value.form.error)

        controller.linkChild(LinkChildInput("mum", "dad", UnionChoice.Existing("second"), "kid", ChildRelation.BIRTH)) {}
        runCurrent()

        assertTrue("addChild:second:kid:BIRTH" in api.calls)
        assertTrue(api.calls.none { it.startsWith("addChild:first") || it.startsWith("createUnion") })
    }

    @Test
    fun newUnionIsCreatedWithTheChosenTypeEvenWhenOneExists() = runTest {
        val controller = openTree()

        controller.linkChild(LinkChildInput("mum", "dad", UnionChoice.New(UnionType.PARTNERS), "kid", ChildRelation.BIRTH)) {}
        runCurrent()

        assertTrue("createUnion:mum+dad:PARTNERS" in api.calls)
        assertTrue("addChild:u-new-1:kid:BIRTH" in api.calls)
        assertTrue(api.calls.none { it.startsWith("addChild:u1") })
    }

    @Test
    fun retryAfterTheChildRequestFailsReusesTheCreatedUnion() = runTest {
        api.addChildFailures += ApiFailure.UNAVAILABLE
        val controller = openTree()
        var closed = false
        val input = LinkChildInput("mum", null, UnionChoice.New(UnionType.UNKNOWN), "kid", ChildRelation.BIRTH)

        controller.linkChild(input) { closed = true }
        runCurrent()

        val form = controller.state.value.form
        assertEquals(FormError.UNAVAILABLE, form.error, "the failure is shown, not dropped")
        assertEquals("u-new-1", form.createdUnionId)
        assertEquals(false, form.submitting)
        assertEquals(false, closed)
        assertTrue(controller.state.value.treeScreen!!.graph.valueOrNull!!.unions.any { it.id == "u-new-1" }, "graph reloaded")

        // The dialog switches to the created union; the retry links through it.
        controller.linkChild(input.copy(union = UnionChoice.Existing(form.createdUnionId!!))) { closed = true }
        runCurrent()

        assertTrue(closed)
        assertEquals(1, api.calls.count { it.startsWith("createUnion") })
        assertEquals(listOf("addChild:u-new-1:kid:BIRTH", "addChild:u-new-1:kid:BIRTH"), api.calls.filter { it.startsWith("addChild") })
        assertEquals(FormState(), controller.state.value.form)
    }

    @Test
    fun duplicateLinkShowsALocalizedConflictAndKeepsTheDialogOpen() = runTest {
        api.addChildFailures += ApiFailure.CONFLICT
        val controller = openTree()
        var closed = false

        controller.linkChild(LinkChildInput("mum", "dad", UnionChoice.Existing("u1"), "kid", ChildRelation.BIRTH)) { closed = true }
        runCurrent()

        assertEquals(false, closed)
        assertEquals(FormState(error = FormError.ALREADY_LINKED), controller.state.value.form)
    }

    @Test
    fun failedReloadReplacesTheShownGraphWithTheFailure() = runTest {
        val controller = openTree()
        assertIs<Loadable.Ready<*>>(controller.state.value.treeScreen?.graph)
        api.graphFailures += ApiFailure.FORBIDDEN

        controller.linkChild(LinkChildInput("mum", "dad", UnionChoice.Existing("u1"), "kid", ChildRelation.BIRTH)) {}
        runCurrent()

        assertEquals(Loadable.Failed(ApiFailure.FORBIDDEN), controller.state.value.treeScreen?.graph)

        controller.retry()
        runCurrent()
        assertIs<Loadable.Ready<*>>(controller.state.value.treeScreen?.graph)
    }

    @Test
    fun invalidLinksAreCaughtBeforeAnyRequest() = runTest {
        val controller = openTree()
        val before = api.calls.size
        val union = UnionChoice.Existing("u1")

        controller.linkChild(LinkChildInput("mum", "mum", union, "kid", ChildRelation.BIRTH)) {}
        assertEquals(FormError.SAME_PARENT_TWICE, controller.state.value.form.error)
        controller.linkChild(LinkChildInput("mum", "dad", union, "dad", ChildRelation.BIRTH)) {}
        assertEquals(FormError.CHILD_IS_PARENT, controller.state.value.form.error)
        controller.createPerson(PersonInput("  ", "", "", Sex.UNKNOWN, true)) {}
        assertEquals(FormError.NAME_REQUIRED, controller.state.value.form.error)
        runCurrent()

        assertEquals(before, api.calls.size)
    }

    @Test
    fun latePersonWriteFailureDoesNotSetAnErrorOnTheTreeListForm() = runTest {
        val controller = openTree()
        val gate = CompletableDeferred<Unit>()
        api.personCreationGate = gate
        controller.createPerson(PersonInput("Elena", "Romero", "", Sex.FEMALE, true)) {}
        runCurrent()

        controller.navigate(Route.Trees)
        gate.complete(Unit)
        runCurrent()

        assertEquals(Route.Trees, controller.state.value.route)
        assertEquals(FormState(), controller.state.value.form)
    }

    @Test
    fun createdPersonIsSelectedAndTheTreeReloaded() = runTest {
        val controller = openTree()
        api.graph = graphOf(listOf("mum", "dad", "kid", "new"), unionOf("u1", "mum", "dad"))

        controller.createPerson(PersonInput(" Ana ", " Romero ", "", Sex.FEMALE, true)) {}
        runCurrent()

        assertTrue("createPerson:Ana" in api.calls)
        val screen = controller.state.value.treeScreen!!
        assertEquals("new", screen.selectedPersonId)
        assertTrue(screen.graph.valueOrNull!!.people.any { it.id == "new" })
    }

    @Test
    fun personPageReloadThenBackOpensTheTreeCentredOnThePerson() = runTest {
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID/people/kid"
        val controller = controller()
        signIn()

        val page = controller.state.value.personScreen!!
        assertEquals("kid", page.person.valueOrNull?.id)
        assertEquals(api.tree, page.tree.valueOrNull)

        controller.backToTree()
        runCurrent()
        assertEquals(Route.Tree(TREE_ID, TreeViewKind.FULL, "kid"), controller.state.value.route)
        assertEquals("/trees/$TREE_ID?root=kid", bridge.url.substringAfter('#'))
        val screen = controller.state.value.treeScreen!!
        assertEquals("kid", screen.selectedPersonId)
        assertIs<Loadable.Ready<*>>(screen.graph)
    }

    @Test
    fun unknownPersonOrTreeShowsNotFound() = runTest {
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID/people/nobody"
        val controller = controller()
        signIn()
        assertEquals(Loadable.Failed(ApiFailure.NOT_FOUND), controller.state.value.personScreen?.person)

        bridge.changeHash("/trees/other")
        runCurrent()
        assertEquals(Loadable.Failed(ApiFailure.NOT_FOUND), controller.state.value.treeScreen?.tree)
    }

    @Test
    fun backButtonChangesTheView() = runTest {
        val controller = openTree()
        controller.centerOn("kid")
        controller.showView(TreeViewKind.DESCENDANTS)
        runCurrent()
        assertTrue("descendants:kid" in api.calls)
        assertEquals("/trees/$TREE_ID?view=descendants&root=kid", bridge.url.substringAfter('#'))

        bridge.changeHash("/trees/$TREE_ID?root=kid")
        runCurrent()

        assertEquals(TreeViewKind.FULL, controller.state.value.treeScreen?.view)
        assertNull(controller.state.value.treeScreen?.branch)
    }
}
