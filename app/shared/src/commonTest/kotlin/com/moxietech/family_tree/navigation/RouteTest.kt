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
