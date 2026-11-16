package com.moxietech.family_tree.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteTest {

    @Test
    fun routesRoundTripThroughTheFragment() {
        val routes = listOf(
            Route.Trees,
            Route.Tree("t1"),
            Route.Tree("t1", TreeViewKind.ANCESTORS, "p1"),
            Route.Tree("t1", TreeViewKind.DESCENDANTS, "p 2/x"),
            Route.Tree("t1", TreeViewKind.FULL, "p1"),
            Route.Person("t1", "p1"),
            Route.Person("t 1", "p/1"),
        )
        for (route in routes) assertEquals(route, Route.parse(route.toFragment()), route.toFragment())
    }

    @Test
    fun validUuidsInAnyAcceptedSpellingBecomeCanonicalAndOtherIdsStayOpaque() {
        val tree = "0f8fad5b-d9cb-469f-a165-70867728950e"
        val person = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        val compactTree = tree.replace("-", "")
        val compactPerson = person.replace("-", "").uppercase()

        assertEquals(Route.Person(tree, person), Route.parse("/trees/$compactTree/people/$compactPerson"))
        assertEquals(Route.Tree(tree, TreeViewKind.ANCESTORS, person), Route.parse("/trees/${tree.uppercase()}?view=ancestors&root=$compactPerson"))
        // Not a UUID (too short, opaque, malformed): kept exactly as typed for the API to reject.
        assertEquals(Route.Person("t1", "0f8fad5bd9cb469f"), Route.parse("/trees/t1/people/0f8fad5bd9cb469f"))
        assertEquals(Route.Person("T1", "Kid"), Route.parse("/trees/T1/people/Kid"))
        assertEquals(true, Route.sameId(compactPerson, person))
        assertEquals(false, Route.sameId("KID", "kid"))
    }

    @Test
    fun fragmentsAreReadable() {
        assertEquals("/", Route.Trees.toFragment())
        assertEquals("/trees/t1", Route.Tree("t1").toFragment())
        assertEquals("/trees/t1?view=ancestors&root=p1", Route.Tree("t1", TreeViewKind.ANCESTORS, "p1").toFragment())
        assertEquals("/trees/t1/people/p1", Route.Person("t1", "p1").toFragment())
    }

    @Test
    fun unknownOrIncompleteFragmentsOpenTheTreeList() {
        listOf("", "#", "#/", "/nope", "/trees", "/trees/", "/trees/t1/people", "/trees/t1/people/p1/extra")
            .forEach { assertEquals(Route.Trees, Route.parse(it), it) }
    }

    @Test
    fun unknownViewFallsBackToTheFullTree() {
        assertEquals(Route.Tree("t1", TreeViewKind.FULL, null), Route.parse("/trees/t1?view=sideways&root="))
    }
}
