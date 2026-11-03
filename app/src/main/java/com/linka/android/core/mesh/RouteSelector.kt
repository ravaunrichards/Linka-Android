package com.linka.android.core.mesh

/** Selects among discovered, user-consented candidate routes. */
object RouteSelector {
    fun select(candidates: List<Route>): Route? =
        candidates
            .filter { it.hops.isNotEmpty() && it.hopCount <= 8 }
            .filter { it.reliability in 0.0..1.0 && it.estimatedLatencyMs >= 0 }
            .minByOrNull { route ->
                route.hopCount * 1000 +
                    route.estimatedLatencyMs +
                    ((1.0 - route.reliability) * 2000).toInt()
            }
}
