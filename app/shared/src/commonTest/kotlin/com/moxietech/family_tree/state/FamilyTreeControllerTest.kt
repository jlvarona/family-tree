package com.moxietech.family_tree.state

import com.moxietech.family_tree.FakeBridge
import com.moxietech.family_tree.FakeTransfer
import com.moxietech.family_tree.TREE_ID
import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CreateMediaUploadRequest
import com.moxietech.family_tree.api.MediaDownloadDto
import com.moxietech.family_tree.api.MediaDto
import com.moxietech.family_tree.api.MediaUploadDto
import com.moxietech.family_tree.api.PresignedUploadDto
import com.moxietech.family_tree.auth.PickedFile
import com.moxietech.family_tree.media.MediaRules
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreatePersonResponse
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.DuplicateCandidateDto
import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.MergePeopleRequest
import com.moxietech.family_tree.api.MergePeopleResponse
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
import com.moxietech.family_tree.merge.MergeField
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
import com.moxietech.family_tree.event as eventOf
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
        var tree = TreeDto(TREE_ID, "Romero", "owner", 0)
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
            // Like the server: any accepted spelling of the ID, answered with the canonical one.
            if (!Route.sameId(treeId, tree.id)) throw ApiException(ApiFailure.NOT_FOUND)
            return tree
        }

        override suspend fun graph(treeId: String): TreeGraphDto {
            calls += "graph:$treeId"
            graphGates.removeFirstOrNull()?.await()
            graphFailures.removeFirstOrNull()?.let { throw ApiException(it) }
            return graph
        }

        /** Replaces the person record returned, e.g. one from another tree. */
        var personOverride: PersonDto? = null
        val personReadGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var events: List<EventDto> = listOf(birthOf("kid"))

        /** Each events call takes the next gate / failure, if any. */
        val eventGates = ArrayDeque<CompletableDeferred<Unit>>()
        val eventFailures = ArrayDeque<ApiFailure>()

        override suspend fun person(treeId: String, personId: String): PersonDto {
            calls += "person:$personId"
            personOverride?.let { return it }
            val person = graph.people.firstOrNull { it.id == personId } ?: throw ApiException(ApiFailure.NOT_FOUND)
            personReadGates.remove(personId)?.await()
            return person
        }

        override suspend fun events(treeId: String, personId: String): List<EventDto> {
            calls += "events:$personId"
            eventGates.removeFirstOrNull()?.await()
            eventFailures.removeFirstOrNull()?.let { throw ApiException(it) }
            return events.filter { it.personId == personId }
        }

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
            return CreatePersonResponse(personOf("new", request.givenNames), duplicates)
        }

        /** Returned with every created person. */
        var duplicates: List<DuplicateCandidateDto> = emptyList()

        val mergeRequests = mutableListOf<MergePeopleRequest>()

        /** Each merge fails with the next queued failure, if any. */
        val mergeFailures = ArrayDeque<ApiFailure>()
        var mergeGate: CompletableDeferred<Unit>? = null

        /** Applies the merge to the graph as the server would, so a reload shows it. */
        override suspend fun mergePeople(treeId: String, request: MergePeopleRequest): MergePeopleResponse {
            calls += "merge:${request.survivorId}<-${request.retiredId}"
            mergeRequests += request
            mergeGate?.await()
            mergeFailures.removeFirstOrNull()?.let { throw ApiException(it) }
            val survivor = graph.people.single { it.id == request.survivorId }.let { it.copy(version = it.version + 1) }
            graph = graph.copy(
                people = graph.people.filter { it.id != request.retiredId }.map { if (it.id == survivor.id) survivor else it },
                unions = graph.unions.map { union ->
                    union.copy(children = union.children.filter { it.childId != request.retiredId })
                },
            )
            return MergePeopleResponse(survivor, request.retiredId)
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

        // --- FT-008 -----------------------------------------------------------------------
        private var mediaCount = 0
        val finalizeFailures = ArrayDeque<ApiFailure>()

        /** Each assignment fails with the next queued failure; a CONFLICT also bumps the person's version, as a concurrent edit would. */
        val photoFailures = ArrayDeque<ApiFailure>()
        val downloadFailures = ArrayDeque<ApiFailure>()

        override suspend fun createMediaUpload(treeId: String, request: CreateMediaUploadRequest): MediaUploadDto {
            val id = "m${++mediaCount}"
            calls += "presign:$id:${request.purpose}:${request.contentType}:${request.byteSize}"
            return MediaUploadDto(
                MediaDto(id, treeId, request.purpose, "PENDING", request.contentType, null, null, null, 1),
                PresignedUploadDto("https://store.test/uploads/$id?X-Amz-Signature=x", "PUT", mapOf("content-type" to request.contentType), "2026-11-16T10:10:00Z"),
            )
        }

        override suspend fun finalizeMedia(treeId: String, mediaId: String): MediaDto {
            calls += "finalize:$mediaId"
            finalizeFailures.removeFirstOrNull()?.let { throw ApiException(it) }
            return MediaDto(mediaId, treeId, "PROFILE_PHOTO", "READY", "image/jpeg", 3, 1, 1, 2)
        }

        override suspend fun mediaDownload(treeId: String, mediaId: String): MediaDownloadDto {
            calls += "download:$mediaId"
            downloadFailures.removeFirstOrNull()?.let { throw ApiException(it) }
            return MediaDownloadDto(mediaId, "https://store.test/media/$mediaId?X-Amz-Signature=y", "image/jpeg", "2026-11-16T10:05:00Z")
        }

        override suspend fun setProfilePhoto(treeId: String, personId: String, mediaId: String?, version: Long): PersonDto {
            calls += "photo:$personId:$mediaId:$version"
            val stored = graph.people.single { it.id == personId }
            photoFailures.removeFirstOrNull()?.let { failure ->
                if (failure == ApiFailure.CONFLICT) replacePerson(stored.copy(version = stored.version + 1))
                throw ApiException(failure)
            }
            if (stored.version != version) throw ApiException(ApiFailure.CONFLICT)
            return stored.copy(profileMediaId = mediaId, version = version + 1).also(::replacePerson)
        }

        fun replacePerson(person: PersonDto) {
            graph = graph.copy(people = graph.people.map { if (it.id == person.id) person else it })
        }
    }

    private val bridge = FakeBridge()
    private val session = FakeSession()
    private val api = FakeApi()
    private val transfer = FakeTransfer()

    private fun TestScope.controller(
        photoCacheBytes: Long = FamilyTreeController.DEFAULT_PHOTO_CACHE_BYTES,
    ): FamilyTreeController =
        FamilyTreeController(session, api, bridge, backgroundScope, transfer, photoCacheBytes).also {
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

    private fun TestScope.openPersonPage(personId: String = "kid"): FamilyTreeController {
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID/people/$personId"
        return controller().also { signIn() }
    }

    @Test
    fun personPageReloadLoadsTheProfileFamilyAndTimelineFromTheUrl() = runTest {
        val controller = openPersonPage()

        val page = controller.state.value.personScreen!!
        assertEquals(Route.Person(TREE_ID, "kid"), controller.state.value.route)
        assertEquals("kid", page.person.valueOrNull?.id)
        assertEquals(TREE_ID, page.graph.valueOrNull?.treeId)
        assertEquals(listOf("e-kid"), page.events.valueOrNull?.map { it.id })
        assertTrue("events:kid" in api.calls)
    }

    @Test
    fun failedTimelineKeepsTheRestAndRetryReloadsOnlyIt() = runTest {
        api.eventFailures += ApiFailure.UNAVAILABLE
        val controller = openPersonPage()

        val failed = controller.state.value.personScreen!!
        assertEquals(Loadable.Failed(ApiFailure.UNAVAILABLE), failed.events)
        assertIs<Loadable.Ready<*>>(failed.person)
        assertIs<Loadable.Ready<*>>(failed.graph)

        controller.retry()
        runCurrent()

        assertEquals(listOf("e-kid"), controller.state.value.personScreen!!.events.valueOrNull?.map { it.id })
        assertEquals(1, api.calls.count { it == "person:kid" }, "the person isn't fetched again")
        assertEquals(1, api.calls.count { it == "graph:$TREE_ID" }, "the family isn't fetched again")
        assertEquals(2, api.calls.count { it == "events:kid" })
    }

    @Test
    fun failedFamilyIsLocalAndRetryReloadsOnlyIt() = runTest {
        api.graphFailures += ApiFailure.SERVER
        val controller = openPersonPage()
        assertEquals(Loadable.Failed(ApiFailure.SERVER), controller.state.value.personScreen?.graph)
        assertIs<Loadable.Ready<*>>(controller.state.value.personScreen?.events)

        controller.retry()
        runCurrent()

        assertIs<Loadable.Ready<*>>(controller.state.value.personScreen?.graph)
        assertEquals(1, api.calls.count { it == "events:kid" })
    }

    @Test
    fun wrongTreeRouteIsNotFoundAndRequestsNothingElse() = runTest {
        bridge.url = "http://localhost:3000/#/trees/other-tree/people/kid"
        val controller = controller()
        signIn()

        val page = controller.state.value.personScreen!!
        assertEquals(Loadable.Failed(ApiFailure.NOT_FOUND), page.tree)
        assertEquals(Loadable.Failed(ApiFailure.NOT_FOUND), page.person)
        assertEquals(Loadable.Failed(ApiFailure.NOT_FOUND), page.graph)
        assertEquals(Loadable.Failed(ApiFailure.NOT_FOUND), page.events)
        assertTrue(api.calls.none { it.startsWith("person:") || it.startsWith("events:") || it.startsWith("graph:") })
    }

    @Test
    fun personRecordFromAnotherTreeOrIdIsNeverShown() = runTest {
        api.personOverride = personOf("kid").copy(treeId = "tree-2")
        val controller = openPersonPage()
        assertEquals(Loadable.Failed(ApiFailure.NOT_FOUND), controller.state.value.personScreen?.person)
        assertTrue(api.calls.none { it.startsWith("events:") })

        api.personOverride = personOf("someone-else")
        controller.retry()
        runCurrent()
        assertEquals(Loadable.Failed(ApiFailure.NOT_FOUND), controller.state.value.personScreen?.person)
    }

    @Test
    fun compactUuidLinkOpensTheProfileAndCentresOnTheCanonicalId() = runTest {
        val treeUuid = "0f8fad5b-d9cb-469f-a165-70867728950e"
        val personUuid = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        api.tree = TreeDto(treeUuid, "Romero", "owner", 0)
        api.personOverride = personOf(personUuid).copy(treeId = treeUuid)
        api.graph = api.graph.copy(treeId = treeUuid)
        // Hex-only and upper case: a spelling the API accepts.
        bridge.url = "http://localhost:3000/#/trees/${treeUuid.replace("-", "")}/people/${personUuid.replace("-", "").uppercase()}"
        val controller = controller()
        signIn()

        val page = controller.state.value.personScreen!!
        assertEquals(Route.Person(treeUuid, personUuid), controller.state.value.route)
        assertEquals(personUuid, page.person.valueOrNull?.id)
        assertIs<Loadable.Ready<*>>(page.graph)

        controller.showInTree()
        runCurrent()
        assertEquals(Route.Tree(treeUuid, TreeViewKind.FULL, personUuid), controller.state.value.route)
        assertEquals(personUuid, controller.state.value.treeScreen?.selectedPersonId)
    }

    @Test
    fun opaqueIdsMustMatchExactly() = runTest {
        api.personOverride = personOf("KID")
        val controller = openPersonPage("kid")

        assertEquals(Loadable.Failed(ApiFailure.NOT_FOUND), controller.state.value.personScreen?.person)
    }

    @Test
    fun retryAfterLeavingAProfileDoesNotReloadOrUpdateIt() = runTest {
        api.eventFailures += ApiFailure.UNAVAILABLE
        val controller = openPersonPage()
        assertEquals(Loadable.Failed(ApiFailure.UNAVAILABLE), controller.state.value.personScreen?.events)

        controller.backToTree()
        runCurrent()
        controller.retry()
        runCurrent()

        assertEquals(1, api.calls.count { it == "events:kid" }, "the left profile isn't retried")
        assertIs<Route.Tree>(controller.state.value.route)
    }

    @Test
    fun profileResponseArrivingAfterLeavingIsDiscarded() = runTest {
        val gate = CompletableDeferred<Unit>()
        api.eventGates += gate
        val controller = openPersonPage()

        controller.backToTree()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(Loadable.Loading, controller.state.value.personScreen?.events)
        assertIs<Route.Tree>(controller.state.value.route)
    }

    @Test
    fun openingARelativeChangesTheUrlAndTheBackButtonReturns() = runTest {
        val controller = openPersonPage()

        controller.openPerson("mum")
        runCurrent()
        assertEquals("/trees/$TREE_ID/people/mum", bridge.url.substringAfter('#'))
        assertEquals("mum", controller.state.value.personScreen?.person?.valueOrNull?.id)

        bridge.changeHash("/trees/$TREE_ID/people/kid")
        runCurrent()
        assertEquals(Route.Person(TREE_ID, "kid"), controller.state.value.route)
        assertEquals("kid", controller.state.value.personScreen?.person?.valueOrNull?.id)
    }

    @Test
    fun showInTreeKeepsTheViewCentresOnThePersonAndSelectsThem() = runTest {
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID?view=ancestors&root=kid"
        api.ancestorIds = listOf("mum", "dad")
        val controller = controller()
        signIn()

        controller.navigate(Route.Person(TREE_ID, "mum"))
        runCurrent()
        controller.showInTree()
        runCurrent()

        assertEquals(Route.Tree(TREE_ID, TreeViewKind.ANCESTORS, "mum"), controller.state.value.route)
        assertEquals("/trees/$TREE_ID?view=ancestors&root=mum", bridge.url.substringAfter('#'))
        assertEquals("mum", controller.state.value.treeScreen?.selectedPersonId)
        assertTrue("ancestors:mum" in api.calls)
    }

    @Test
    fun backToTreeKeepsTheEarlierViewAndRootAndSelectsThePerson() = runTest {
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID?view=ancestors&root=kid"
        api.ancestorIds = listOf("mum", "dad")
        val controller = controller()
        signIn()

        controller.navigate(Route.Person(TREE_ID, "mum"))
        runCurrent()
        controller.backToTree()
        runCurrent()

        assertEquals(Route.Tree(TREE_ID, TreeViewKind.ANCESTORS, "kid"), controller.state.value.route)
        assertEquals("mum", controller.state.value.treeScreen?.selectedPersonId)
    }

    @Test
    fun lateTimelineForAnEarlierPersonPageIsDiscarded() = runTest {
        val gate = CompletableDeferred<Unit>()
        api.eventGates += gate
        api.events = listOf(birthOf("kid"), birthOf("mum"))
        val controller = openPersonPage()
        assertEquals(Loadable.Loading, controller.state.value.personScreen?.events)

        controller.openPerson("mum")
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        val page = controller.state.value.personScreen!!
        assertEquals("mum", page.personId)
        assertEquals(listOf("e-mum"), page.events.valueOrNull?.map { it.id })
    }

    @Test
    fun expiredSessionDropsThePersonPage() = runTest {
        val controller = openPersonPage()
        assertIs<Loadable.Ready<*>>(controller.state.value.personScreen?.events)

        session.state.value = AuthState.SignedOut(SignedOutReason.SESSION_EXPIRED)
        runCurrent()

        assertNull(controller.state.value.personScreen)
        assertEquals(Route.Person(TREE_ID, "kid"), controller.state.value.route)
    }

    // --- FT-007 merge ---------------------------------------------------------------------

    /** "ana" and "ana2" are the same child of mum and dad, entered twice with different details. */
    private fun duplicatesGraph(vararg extra: UnionDto) = TreeGraphDto(
        TREE_ID,
        listOf(
            personOf("mum"),
            personOf("dad"),
            personOf("ana", "Ana", living = false, paternal = "Romero"),
            // No paternal surname: blank against a value still needs a choice.
            personOf("ana2", "Ana María", living = true),
        ),
        listOf(unionOf("u1", "mum", "dad", "ana", "ana2")) + extra,
    )

    private fun TestScope.openMerge(route: String = "/trees/$TREE_ID", vararg extra: UnionDto): FamilyTreeController {
        api.graph = duplicatesGraph(*extra)
        bridge.url = "http://localhost:3000/#$route"
        val controller = controller().also { signIn() }
        controller.startMerge("ana", "ana2")
        runCurrent()
        return controller
    }

    private val FamilyTreeController.merge get() = state.value.treeScreen?.merge

    /** Keep "ana"; her surname, the duplicate's given names and living status. */
    private fun FamilyTreeController.decide() {
        chooseSurvivor("ana")
        chooseMergeValue(MergeField.GIVEN_NAMES, "ana2")
        chooseMergeValue(MergeField.SURNAME_PATERNAL, "ana")
        chooseMergeValue(MergeField.IS_LIVING, "ana2")
        confirmRetired(true)
    }

    @Test
    fun duplicateNoticeNamesEachMatchAndOpensAMergeWithTheNewPerson() = runTest {
        val controller = openTree()
        api.graph = graphOf(listOf("mum", "dad", "kid", "new"), unionOf("u1", "mum", "dad"))
        api.duplicates = listOf(DuplicateCandidateDto(personOf("kid", "Kid", paternal = "Romero"), 80))

        controller.createPerson(PersonInput("Kid", "Romero", "", Sex.UNKNOWN, true)) {}
        runCurrent()

        val notice = assertIs<Notice.PossibleDuplicates>(controller.state.value.treeScreen?.notice)
        assertEquals("new", notice.createdPersonId)
        assertEquals(listOf(DuplicateMatch("kid", "Kid Romero")), notice.candidates)

        controller.startMerge(notice.createdPersonId, notice.candidates.single().personId)
        runCurrent()

        val merge = controller.merge!!
        assertEquals("new" to "kid", merge.firstId to merge.secondId)
        val records = merge.records?.valueOrNull!!
        assertEquals(listOf("new", "kid"), listOf(records.first.id, records.second.id))
        assertTrue("person:new" in api.calls && "person:kid" in api.calls, "both records are read fresh")
    }

    @Test
    fun mergeRequiresASurvivorEveryDifferenceAndAConfirmationBeforeAnyRequest() = runTest {
        val controller = openMerge()
        assertEquals(
            setOf(MergeField.GIVEN_NAMES, MergeField.SURNAME_PATERNAL, MergeField.IS_LIVING),
            FamilyTreeController.differingFields(controller.merge!!.records!!.valueOrNull!!.first, controller.merge!!.records!!.valueOrNull!!.second),
        )

        controller.submitMerge()
        assertEquals(MergeError.SURVIVOR_REQUIRED, controller.merge?.error)
        controller.chooseSurvivor("ana")
        controller.chooseMergeValue(MergeField.GIVEN_NAMES, "ana2")
        controller.chooseMergeValue(MergeField.IS_LIVING, "ana2")
        controller.submitMerge()
        assertEquals(MergeError.CHOICES_REQUIRED, controller.merge?.error, "the blank surname still needs a choice")
        controller.chooseMergeValue(MergeField.SURNAME_PATERNAL, "ana")
        controller.submitMerge()
        assertEquals(MergeError.CONFIRMATION_REQUIRED, controller.merge?.error)

        controller.confirmRetired(true)
        controller.chooseSurvivor("ana2")
        assertEquals(false, controller.merge?.retiredConfirmed, "changing the survivor asks again which record is removed")
        controller.chooseMergeValue(MergeField.SEX, "nobody")
        assertNull(controller.merge?.choices?.get(MergeField.SEX), "only the two records' values can be chosen")
        runCurrent()

        assertTrue(api.calls.none { it.startsWith("merge:") })
    }

    @Test
    fun successfulMergeSendsOneDecisionReloadsAndOpensTheSurvivor() = runTest {
        val controller = openMerge(route = "/trees/$TREE_ID?root=ana2")
        controller.selectPerson("ana2")
        controller.decide()

        controller.submitMerge()
        runCurrent()

        assertEquals(
            listOf(
                MergePeopleRequest(
                    survivorId = "ana",
                    survivorVersion = 0,
                    retiredId = "ana2",
                    retiredVersion = 0,
                    choices = mapOf("GIVEN_NAMES" to "RETIRED", "SURNAME_PATERNAL" to "SURVIVOR", "IS_LIVING" to "RETIRED"),
                ),
            ),
            api.mergeRequests,
        )
        val screen = controller.state.value.treeScreen!!
        assertNull(screen.merge, "transient merge state is cleaned up")
        assertNull(screen.notice)
        assertEquals("ana", screen.selectedPersonId)
        assertEquals("ana", screen.rootPersonId, "a root that no longer exists is replaced by the survivor")
        assertTrue(screen.graph.valueOrNull!!.people.none { it.id == "ana2" }, "the tree is reloaded")
        assertEquals(Route.Person(TREE_ID, "ana"), controller.state.value.route)
        assertEquals("/trees/$TREE_ID/people/ana", bridge.url.substringAfter('#'))
        assertEquals(1L, controller.state.value.personScreen?.person?.valueOrNull?.version)

        // Back from the survivor's page returns to a tree view that still exists.
        controller.backToTree()
        runCurrent()
        assertEquals(Route.Tree(TREE_ID, TreeViewKind.FULL, "ana"), controller.state.value.route)
    }

    @Test
    fun conflictReloadsBothRecordsAndTheTreeAndAsksForTheChoicesAgain() = runTest {
        api.mergeFailures += ApiFailure.CONFLICT
        val controller = openMerge()
        controller.decide()

        controller.submitMerge()
        runCurrent()

        val merge = controller.merge!!
        assertEquals(MergeError.CHANGED, merge.error)
        assertEquals(false, merge.submitting)
        assertEquals("ana", merge.survivorId)
        assertEquals(emptyMap(), merge.choices)
        assertEquals(false, merge.retiredConfirmed)
        assertIs<Loadable.Ready<*>>(merge.records)
        assertEquals(2, api.calls.count { it == "person:ana2" }, "both records are read again")
        assertEquals(2, api.calls.count { it == "graph:$TREE_ID" })
        assertIs<Route.Tree>(controller.state.value.route)

        controller.decide()
        controller.submitMerge()
        runCurrent()
        assertNull(controller.state.value.treeScreen?.merge)
        assertEquals(2, api.mergeRequests.size)
    }

    @Test
    fun otherFailuresKeepTheReviewAndShowALocalizedError() = runTest {
        api.mergeFailures += ApiFailure.UNAVAILABLE
        val controller = openMerge()
        controller.decide()

        controller.submitMerge()
        runCurrent()

        val merge = controller.merge!!
        assertEquals(MergeError.UNAVAILABLE, merge.error)
        assertEquals(false, merge.submitting)
        assertEquals(3, merge.choices.size)
        assertTrue(merge.retiredConfirmed)
    }

    @Test
    fun relationshipsThatCantBeMergedAreCaughtBeforeTheRequest() = runTest {
        // ana is also recorded as ana2's parent.
        val controller = openMerge("/trees/$TREE_ID", unionOf("u2", "ana", null, "ana2"))
        controller.decide()

        controller.submitMerge()
        runCurrent()

        assertEquals(MergeError.RELATIONSHIPS, controller.merge?.error)
        assertTrue(api.calls.none { it.startsWith("merge:") })
    }

    @Test
    fun choosingAnotherRecordDropsEverythingDecidedAboutTheOldPair() = runTest {
        val controller = openMerge()
        controller.decide()

        controller.chooseSecondForMerge("mum")
        runCurrent()

        val merge = controller.merge!!
        assertEquals("ana" to "mum", merge.firstId to merge.secondId)
        assertNull(merge.survivorId)
        assertEquals(emptyMap(), merge.choices)
        assertEquals(false, merge.retiredConfirmed)
        assertEquals("mum", merge.records?.valueOrNull?.second?.id)

        controller.cancelMerge()
        assertNull(controller.merge)
    }

    @Test
    fun delayedReadFromTheSamePairCannotOverwriteANewerComparison() = runTest {
        val oldRead = CompletableDeferred<Unit>()
        api.personReadGates["ana2"] = oldRead
        val controller = openMerge()
        val latestVersion = api.graph.people.first { it.id == "ana2" }.version + 1
        api.graph = api.graph.copy(people = api.graph.people.map {
            if (it.id == "ana2") it.copy(givenNames = "Ana Lucía", version = latestVersion) else it
        })

        controller.chooseSecondForMerge("mum")
        runCurrent()
        controller.chooseSecondForMerge("ana2")
        runCurrent()
        assertEquals(latestVersion, controller.merge?.records?.valueOrNull?.second?.version)

        oldRead.complete(Unit)
        runCurrent()
        assertEquals(latestVersion, controller.merge?.records?.valueOrNull?.second?.version)
    }

    @Test
    fun onlyTheOwnerCanOpenAMerge() = runTest {
        api.tree = TreeDto(TREE_ID, "Romero", "someone-else", 0)
        val controller = openTree()
        val before = api.calls.size

        controller.startMerge("mum", "dad")
        runCurrent()

        assertNull(controller.merge)
        assertEquals(before, api.calls.size)
    }

    @Test
    fun aMergeAnsweredAfterTheSessionEndedChangesNothing() = runTest {
        val gate = CompletableDeferred<Unit>()
        api.mergeGate = gate
        val controller = openMerge()
        controller.decide()
        controller.submitMerge()
        runCurrent()
        assertEquals(true, controller.merge?.submitting)

        session.state.value = AuthState.SignedOut(SignedOutReason.SESSION_EXPIRED)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertNull(controller.state.value.treeScreen)
        assertEquals(Route.Tree(TREE_ID), controller.state.value.route, "no navigation to the survivor after the session ended")
    }

    @Test
    fun leavingTheTreeDropsTheMerge() = runTest {
        val controller = openMerge()
        controller.decide()

        controller.navigate(Route.Trees)
        controller.navigate(Route.Tree(TREE_ID))
        runCurrent()

        assertNull(controller.merge)
    }

    // --- FT-008 profile photos ----------------------------------------------------------

    private val FamilyTreeController.page get() = state.value.personScreen!!

    private fun jpegFile(size: Int = 100) = PickedFile("image/jpeg", size.toLong(), ByteArray(size) { it.toByte() })

    @Test
    fun ownerUploadsStraightToStorageThenFinalizesAttachesAndShowsThePhoto() = runTest {
        val controller = openPersonPage()
        bridge.pickedFile = jpegFile()

        controller.choosePhoto()
        runCurrent()

        assertEquals(listOf(MediaRules.SUPPORTED_CONTENT_TYPES to MediaRules.MAX_UPLOAD_BYTES), bridge.pickRequests)
        assertTrue("presign:m1:PROFILE_PHOTO:image/jpeg:100" in api.calls)
        assertEquals(listOf("https://store.test/uploads/m1?X-Amz-Signature=x" to 100), transfer.uploads, "bytes go to the presigned URL")
        assertEquals(
            listOf("finalize:m1", "photo:kid:m1:0", "download:m1"),
            api.calls.filter { it.startsWith("finalize") || it.startsWith("photo:") || it.startsWith("download") },
        )
        assertEquals("m1", controller.page.person.valueOrNull?.profileMediaId)
        assertNull(controller.page.photo, "nothing pending")
        val photo = controller.state.value.photos[PhotoKey(TREE_ID, "m1")]
        assertTrue(photo is Loadable.Ready && photo.value.contentEquals(transfer.image))
    }

    @Test
    fun unsupportedOrOversizedFilesAreRefusedBeforeAnyRequest() = runTest {
        val controller = openPersonPage()
        bridge.pickedFile = PickedFile("image/gif", 10, ByteArray(10))
        controller.choosePhoto()
        assertEquals(PhotoError.UNSUPPORTED_TYPE, controller.page.photo?.error)

        // Over the cap by its reported size: the bridge hands it over unread (no bytes).
        bridge.pickedFile = PickedFile("image/png", MediaRules.MAX_UPLOAD_BYTES + 1, ByteArray(1))
        controller.choosePhoto()
        assertEquals(PhotoError.TOO_LARGE, controller.page.photo?.error)
        assertEquals(MediaRules.MAX_UPLOAD_BYTES, bridge.pickRequests.last().second, "the cap is passed to the browser")

        bridge.pickedFile = PickedFile("image/png", 0, ByteArray(0))
        controller.choosePhoto()
        assertEquals(PhotoError.INVALID_IMAGE, controller.page.photo?.error, "an empty file")
        runCurrent()
        assertTrue(api.calls.none { it.startsWith("presign") })
        assertTrue(transfer.uploads.isEmpty())
    }

    @Test
    fun aFailedFinalizeIsRetriedWithoutUploadingAgain() = runTest {
        val controller = openPersonPage()
        api.finalizeFailures += ApiFailure.UNAVAILABLE
        bridge.pickedFile = jpegFile()
        controller.choosePhoto()
        runCurrent()

        assertEquals(PhotoError.UNAVAILABLE, controller.page.photo?.error)
        assertEquals("m1", controller.page.photo?.pending?.mediaId)
        assertNull(controller.page.person.valueOrNull?.profileMediaId)

        controller.retryPhoto()
        runCurrent()

        assertEquals(1, api.calls.count { it.startsWith("presign") }, "no second upload")
        assertEquals(1, transfer.uploads.size)
        assertEquals(2, api.calls.count { it == "finalize:m1" }, "finalize is safe to repeat")
        assertEquals("m1", controller.page.person.valueOrNull?.profileMediaId)
    }

    @Test
    fun aPersonChangedMeanwhileReloadsAndTheRetryAttachesAtTheNewVersion() = runTest {
        val controller = openPersonPage()
        api.photoFailures += ApiFailure.CONFLICT
        bridge.pickedFile = jpegFile()
        controller.choosePhoto()
        runCurrent()

        assertEquals(PhotoError.CHANGED, controller.page.photo?.error)
        assertEquals(1L, controller.page.person.valueOrNull?.version, "the person was reloaded")

        controller.retryPhoto()
        runCurrent()

        assertTrue("photo:kid:m1:1" in api.calls)
        assertEquals(1, api.calls.count { it.startsWith("presign") })
        assertEquals("m1", controller.page.person.valueOrNull?.profileMediaId)
    }

    @Test
    fun aRejectedImageAsksForAnotherFileAndAFailedTransferStartsOver() = runTest {
        val controller = openPersonPage()
        api.finalizeFailures += ApiFailure.INVALID
        bridge.pickedFile = jpegFile()
        controller.choosePhoto()
        runCurrent()
        assertEquals(PhotoError.INVALID_IMAGE, controller.page.photo?.error)
        assertNull(controller.page.photo?.pending, "nothing to retry: choose another file")

        transfer.uploadFailures += ApiFailure.UNAVAILABLE
        controller.choosePhoto()
        runCurrent()
        assertEquals(PhotoError.UPLOAD_FAILED, controller.page.photo?.error)
        assertNull(controller.page.photo?.pending?.mediaId)

        controller.retryPhoto()
        runCurrent()
        assertTrue("presign:m3:PROFILE_PHOTO:image/jpeg:100" in api.calls, "a fresh upload after a failed transfer")
        assertEquals("m3", controller.page.person.valueOrNull?.profileMediaId)
    }

    @Test
    fun removingThePhotoClearsItAtTheCurrentVersion() = runTest {
        api.replacePerson(personOf("kid").copy(profileMediaId = "m0"))
        val controller = openPersonPage()
        controller.removePhoto()
        runCurrent()

        assertTrue("photo:kid:null:0" in api.calls)
        assertNull(controller.page.person.valueOrNull?.profileMediaId)
    }

    @Test
    fun onlyTheOwnerCanChangeAPhoto() = runTest {
        api.tree = api.tree.copy(ownerAccountId = "someone-else")
        val controller = openPersonPage()
        bridge.pickedFile = jpegFile()
        controller.choosePhoto()
        controller.removePhoto()
        runCurrent()
        assertTrue(bridge.pickRequests.isEmpty(), "no file chooser for a non-owner")
        assertTrue(api.calls.none { it.startsWith("presign") || it.startsWith("photo:") })
    }

    @Test
    fun aPersonPagePhotoLoadsThroughAShortLivedLinkAndRetriesAfterAFailure() = runTest {
        api.replacePerson(personOf("kid").copy(profileMediaId = "m9"))
        api.downloadFailures += ApiFailure.UNAVAILABLE
        val controller = openPersonPage()
        assertEquals(Loadable.Failed(ApiFailure.UNAVAILABLE), controller.state.value.photos[PhotoKey(TREE_ID, "m9")])

        controller.retry()
        runCurrent()

        assertIs<Loadable.Ready<*>>(controller.state.value.photos[PhotoKey(TREE_ID, "m9")])
        assertEquals(listOf("https://store.test/media/m9?X-Amz-Signature=y"), transfer.downloads)
    }

    @Test
    fun thePhotoCacheKeepsAByteBudgetAndNeverDropsThePreviewOnScreen() = runTest {
        // 4-byte images in a 10-byte cache: at most two fit.
        transfer.image = byteArrayOf(1, 2, 3, 4)
        api.replacePerson(personOf("kid").copy(profileMediaId = "m0"))
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID/people/kid"
        val controller = controller(photoCacheBytes = 10).also { signIn() }
        fun cached() = controller.state.value.photos.keys.map { it.mediaId }

        assertEquals(listOf("m0"), cached())
        controller.loadPhoto(TREE_ID, "m1")
        runCurrent()
        controller.loadPhoto(TREE_ID, "m2")
        runCurrent()
        assertEquals(listOf("m0", "m2"), cached(), "the oldest unused image went; the page's photo stayed")

        controller.loadPhoto(TREE_ID, "m3")
        controller.loadPhoto(TREE_ID, "m0") // used again before m3 arrives
        runCurrent()
        assertEquals(listOf("m0", "m3"), cached(), "m2 was the least recently used")
        val total = controller.state.value.photos.values.sumOf { (it as? Loadable.Ready)?.value?.size ?: 0 }
        assertTrue(total <= 10)
        assertEquals(1, transfer.downloads.count { it.contains("/m0?") }, "the preview on screen was never fetched again")
    }

    @Test
    fun differentPhotosNeedAnExplicitMergeChoiceAndBothAreShown() = runTest {
        api.graph = duplicatesGraph()
        api.replacePerson(api.graph.people.single { it.id == "ana" }.copy(profileMediaId = "p1"))
        api.replacePerson(api.graph.people.single { it.id == "ana2" }.copy(profileMediaId = "p2"))
        bridge.url = "http://localhost:3000/#/trees/$TREE_ID"
        val controller = controller().also { signIn() }
        controller.startMerge("ana", "ana2")
        runCurrent()

        val records = controller.merge!!.records!!.valueOrNull!!
        assertTrue(MergeField.PROFILE_PHOTO in FamilyTreeController.differingFields(records.first, records.second))
        assertIs<Loadable.Ready<*>>(controller.state.value.photos[PhotoKey(TREE_ID, "p1")])
        assertIs<Loadable.Ready<*>>(controller.state.value.photos[PhotoKey(TREE_ID, "p2")])

        controller.decide()
        controller.submitMerge()
        assertEquals(MergeError.CHOICES_REQUIRED, controller.merge?.error, "the photo isn't kept or dropped silently")

        controller.chooseMergeValue(MergeField.PROFILE_PHOTO, "ana2")
        controller.submitMerge()
        runCurrent()
        assertEquals("RETIRED", api.mergeRequests.single().choices["PROFILE_PHOTO"])
    }
}

private fun birthOf(personId: String) = eventOf("e-$personId", personId, "BIRTH", "1990", "1990-01-01")
