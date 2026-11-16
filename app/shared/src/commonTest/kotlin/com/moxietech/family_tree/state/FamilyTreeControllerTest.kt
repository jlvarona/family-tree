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
// Aliased so FakeApi's own graph()/person() members can't capture these calls.
import com.moxietech.family_tree.graph as graphOf
import com.moxietech.family_tree.person as personOf
import com.moxietech.family_tree.union as unionOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FamilyTreeControllerTest {

    private class FakeSession : AuthSession {
        override val state = MutableStateFlow<AuthState>(AuthState.Starting)
        val signInRoutes = mutableListOf<String>()
        var signedOut = false

        override suspend fun start() = Unit

        override fun signIn(returnRoute: String) {
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
        var graphGate: CompletableDeferred<Unit>? = null
        var ancestorIds = listOf<String>()
        val calls = mutableListOf<String>()
        var addChildFailure: ApiFailure? = null

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
            graphGate?.await()
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
            return CreatePersonResponse(personOf("new", request.givenNames))
        }

        override suspend fun createUnion(treeId: String, request: CreateUnionRequest): UnionDto {
            calls += "createUnion:${request.partner1Id}+${request.partner2Id}"
            return unionOf("u-new", request.partner1Id, request.partner2Id)
        }

        override suspend fun addChild(treeId: String, unionId: String, request: AddUnionChildRequest): UnionDto {
            calls += "addChild:$unionId:${request.childId}:${request.relation}"
            addChildFailure?.let { throw ApiException(it) }
            return unionOf(unionId, "mum", "dad", request.childId)
        }
    }

    private val bridge = FakeBridge()
    private val session = FakeSession()
    private val api = FakeApi()

    private fun TestScope.controller(): FamilyTreeController =
        FamilyTreeController(session, api, bridge, backgroundScope).also {
            it.start()
            advanceUntilIdle()
        }

    private fun TestScope.signIn(subject: String = "owner") {
        session.state.value = AuthState.SignedIn(subject)
        advanceUntilIdle()
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
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID"
        val controller = controller()
        signIn()
        controller.selectPerson("kid")
        assertIs<Loadable.Ready<*>>(controller.state.value.treeScreen?.graph)

        session.state.value = AuthState.SignedOut(SignedOutReason.SESSION_EXPIRED)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(AuthState.SignedOut(SignedOutReason.SESSION_EXPIRED), state.auth)
        assertEquals(Loadable.Loading, state.account)
        assertEquals(Loadable.Loading, state.trees)
        assertNull(state.treeScreen)
        assertNull(state.personScreen)
        assertNull(state.notice)
        // The route survives so signing in again returns to the same page.
        controller.signIn()
        assertEquals(listOf("/trees/$TREE_ID"), session.signInRoutes)
    }

    @Test
    fun responseArrivingAfterTheSessionEndedIsDiscarded() = runTest {
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID"
        val gate = CompletableDeferred<Unit>()
        api.graphGate = gate
        val controller = controller()
        signIn()
        assertEquals(Loadable.Loading, controller.state.value.treeScreen?.graph)

        session.state.value = AuthState.SignedOut(SignedOutReason.SESSION_EXPIRED)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull(controller.state.value.treeScreen)
    }

    @Test
    fun anotherAccountSigningInStartsFromEmptyState() = runTest {
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID"
        val controller = controller()
        signIn("owner")
        controller.selectPerson("kid")

        signIn("someone-else")

        val screen = controller.state.value.treeScreen!!
        assertNull(screen.selectedPersonId)
        assertEquals(2, api.calls.count { it == "me" })
    }

    @Test
    fun signOutClearsStateBeforeLeavingForKeycloak() = runTest {
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID"
        val controller = controller()
        signIn()

        controller.signOut()

        assertTrue(session.signedOut)
        assertNull(controller.state.value.treeScreen)
        assertIs<AuthState.SignedOut>(controller.state.value.auth)
    }

    @Test
    fun linkingASecondChildReusesTheParentsUnion() = runTest {
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID"
        val controller = controller()
        signIn()
        var closed = false

        controller.linkChild(LinkChildInput("dad", "mum", UnionType.MARRIED, "kid", ChildRelation.ADOPTED)) { closed = true }
        advanceUntilIdle()

        assertTrue(closed)
        assertTrue(api.calls.none { it.startsWith("createUnion") })
        assertTrue("addChild:u1:kid:ADOPTED" in api.calls)
        assertEquals(Notice.ChildLinked, controller.state.value.notice)
        assertEquals(2, api.calls.count { it == "graph:$TREE_ID" }, "the tree is reloaded after the link")
    }

    @Test
    fun linkingWithANewParentCreatesTheUnionFirst() = runTest {
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID"
        val controller = controller()
        signIn()

        controller.linkChild(LinkChildInput("mum", null, UnionType.UNKNOWN, "kid", ChildRelation.BIRTH)) {}
        advanceUntilIdle()

        val create = api.calls.indexOf("createUnion:mum+null")
        assertTrue(create >= 0 && api.calls.indexOf("addChild:u-new:kid:BIRTH") > create)
    }

    @Test
    fun duplicateLinkShowsALocalizedConflictAndKeepsTheDialogOpen() = runTest {
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID"
        api.addChildFailure = ApiFailure.CONFLICT
        val controller = controller()
        signIn()
        var closed = false

        controller.linkChild(LinkChildInput("mum", "dad", UnionType.MARRIED, "kid", ChildRelation.BIRTH)) { closed = true }
        advanceUntilIdle()

        assertEquals(false, closed)
        assertEquals(FormState(error = FormError.ALREADY_LINKED), controller.state.value.form)
    }

    @Test
    fun invalidLinksAreCaughtBeforeAnyRequest() = runTest {
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID"
        val controller = controller()
        signIn()
        val before = api.calls.size

        controller.linkChild(LinkChildInput("mum", "mum", UnionType.MARRIED, "kid", ChildRelation.BIRTH)) {}
        assertEquals(FormError.SAME_PARENT_TWICE, controller.state.value.form.error)
        controller.linkChild(LinkChildInput("mum", "dad", UnionType.MARRIED, "dad", ChildRelation.BIRTH)) {}
        assertEquals(FormError.CHILD_IS_PARENT, controller.state.value.form.error)
        controller.createPerson(PersonInput("  ", "", "", Sex.UNKNOWN, true)) {}
        assertEquals(FormError.NAME_REQUIRED, controller.state.value.form.error)
        advanceUntilIdle()

        assertEquals(before, api.calls.size)
    }

    @Test
    fun createdPersonIsSelectedAndTheTreeReloaded() = runTest {
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID"
        val controller = controller()
        signIn()
        api.graph = graphOf(listOf("mum", "dad", "kid", "new"), unionOf("u1", "mum", "dad"))

        controller.createPerson(PersonInput(" Ana ", " Romero ", "", Sex.FEMALE, true)) {}
        advanceUntilIdle()

        assertTrue("createPerson:Ana" in api.calls)
        val screen = controller.state.value.treeScreen!!
        assertEquals("new", screen.selectedPersonId)
        assertTrue(screen.graph.valueOrNull!!.people.any { it.id == "new" })
    }

    @Test
    fun personPageLoadsOnItsOwnForReloads() = runTest {
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID/people/kid"
        val controller = controller()
        signIn()

        val page = controller.state.value.personScreen!!
        assertEquals("kid", page.person.valueOrNull?.id)
        assertEquals(api.tree, page.tree.valueOrNull)

        controller.backToTree()
        val route = controller.state.value.route
        assertEquals(Route.Tree(TREE_ID, TreeViewKind.FULL, "kid"), route)
        assertEquals("kid", controller.state.value.treeScreen?.selectedPersonId)
    }

    @Test
    fun unknownPersonOrTreeShowsNotFound() = runTest {
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID/people/nobody"
        val controller = controller()
        signIn()
        assertEquals(Loadable.Failed(ApiFailure.NOT_FOUND), controller.state.value.personScreen?.person)

        bridge.changeHash("/trees/other")
        advanceUntilIdle()
        assertEquals(Loadable.Failed(ApiFailure.NOT_FOUND), controller.state.value.treeScreen?.tree)
    }

    @Test
    fun backButtonChangesTheView() = runTest {
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID"
        val controller = controller()
        signIn()
        controller.centerOn("kid")
        controller.showView(TreeViewKind.DESCENDANTS)
        advanceUntilIdle()
        assertTrue("descendants:kid" in api.calls)
        assertEquals("/trees/$TREE_ID?view=descendants&root=kid", bridge.url.substringAfter('#'))

        bridge.changeHash("/trees/$TREE_ID?root=kid")
        advanceUntilIdle()

        assertEquals(TreeViewKind.FULL, controller.state.value.treeScreen?.view)
        assertNull(controller.state.value.treeScreen?.branch)
    }
}
