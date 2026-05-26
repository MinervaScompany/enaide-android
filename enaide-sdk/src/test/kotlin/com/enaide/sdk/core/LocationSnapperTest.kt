package com.enaide.sdk.core

import com.enaide.sdk.model.GeoPoint
import com.enaide.sdk.model.Maneuver
import com.enaide.sdk.model.ManeuverModifier
import com.enaide.sdk.model.ManeuverType
import com.enaide.sdk.model.Route
import com.enaide.sdk.model.RouteStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSnapperTest {

    /** Route diritto verso est di ~786m + ~786m = ~1572m. */
    private fun straightRoute(): Route {
        val geometry = listOf(
            GeoPoint(45.0, 9.000),
            GeoPoint(45.0, 9.010),
            GeoPoint(45.0, 9.020),
        )
        val step1 = RouteStep(
            geometry = geometry.subList(0, 2),
            maneuver = Maneuver(ManeuverType.DEPART, ManeuverModifier.STRAIGHT, geometry[0]),
            distanceMeters = 786.0,
            durationSeconds = 60.0,
        )
        val step2 = RouteStep(
            geometry = geometry.subList(1, 3),
            maneuver = Maneuver(ManeuverType.ARRIVE, ManeuverModifier.NONE, geometry[2]),
            distanceMeters = 786.0,
            durationSeconds = 60.0,
        )
        return Route(
            id = "test",
            geometry = geometry,
            steps = listOf(step1, step2),
            distanceMeters = 1572.0,
            durationSeconds = 120.0,
            waypoints = listOf(geometry.first(), geometry.last()),
        )
    }

    @Test
    fun `snapping the start of the route gives zero progress`() {
        val r = straightRoute()
        val snapper = LocationSnapper(r)
        val snap = snapper.snap(r.geometry.first())
        assertEquals(0.0, snap.distanceTraveledMeters, 0.5)
        assertTrue(snap.distanceFromRouteMeters < 0.5)
    }

    @Test
    fun `snapping the midpoint of route reports half progress`() {
        val r = straightRoute()
        val snapper = LocationSnapper(r)
        val mid = GeoPoint(45.0, 9.010)
        val snap = snapper.snap(mid)
        // Aspetto ~786m percorsi (= fine primo segmento).
        assertTrue("traveled=${snap.distanceTraveledMeters}", snap.distanceTraveledMeters in 770.0..810.0)
    }

    @Test
    fun `user 50m off the route reports correct distance from route`() {
        val r = straightRoute()
        val snapper = LocationSnapper(r)
        // Sposto a nord di ~55m (5e-4 deg lat ≈ 55.6m)
        val offRoute = GeoPoint(45.0005, 9.010)
        val snap = snapper.snap(offRoute)
        assertTrue("distance from route was ${snap.distanceFromRouteMeters}", snap.distanceFromRouteMeters in 45.0..65.0)
    }

    @Test
    fun `step index reflects geometry segment`() {
        val r = straightRoute()
        val snapper = LocationSnapper(r)
        // primo segmento → step 0
        assertEquals(0, snapper.stepIndexForGeometryIndex(0))
        // secondo segmento → step 1
        assertEquals(1, snapper.stepIndexForGeometryIndex(1))
    }
}
