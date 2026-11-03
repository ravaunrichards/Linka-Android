package com.linka.android.core.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteSelectorTest {
    @Test fun selectsBestCandidate() {
        val oneHop = Route("C", listOf("C"), 80, 0.98)
        val twoHop = Route("C", listOf("B", "C"), 250, 0.95)
        assertEquals(oneHop, RouteSelector.select(listOf(twoHop, oneHop)))
    }

    @Test fun rejectsRoutesBeyondHopLimit() {
        val route = Route("Z", (1..9).map { "node-$it" }, 10, 1.0)
        assertNull(RouteSelector.select(listOf(route)))
    }

    @Test fun returnsNullWhenNoRouteExists() {
        assertNull(RouteSelector.select(emptyList()))
    }
}
