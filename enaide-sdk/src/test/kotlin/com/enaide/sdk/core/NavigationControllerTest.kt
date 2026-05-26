package com.enaide.sdk.core

import com.enaide.sdk.EnaideConfig
import com.enaide.sdk.model.Deviation
import com.enaide.sdk.model.GeoPoint
import com.enaide.sdk.model.Maneuver
import com.enaide.sdk.model.ManeuverModifier
import com.enaide.sdk.model.ManeuverType
import com.enaide.sdk.model.NavigationState
import com.enaide.sdk.model.Route
import com.enaide.sdk.model.RouteStep
import com.enaide.sdk.model.TransportProfile
import com.enaide.sdk.model.UserLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationControllerTest {

    /** Route diritto verso est, due step da ~786m. */
    private fun straightRoute(): Route {
        val geo = listOf(GeoPoint(45.0, 9.000), GeoPoint(45.0, 9.010), GeoPoint(45.0, 9.020))
        return Route(
            id = "t",
            geometry = geo,
            steps = listOf(
                RouteStep(geo.subList(0, 2), Maneuver(ManeuverType.DEPART, ManeuverModifier.STRAIGHT, geo[0]), 786.0, 60.0),
                RouteStep(geo.subList(1, 3), Maneuver(ManeuverType.ARRIVE, ManeuverModifier.NONE, geo[2]), 786.0, 60.0),
            ),
            distanceMeters = 1572.0,
            durationSeconds = 120.0,
            waypoints = listOf(geo.first(), geo.last()),
        )
    }

    private fun loc(p: GeoPoint, speed: Double? = null, course: Double? = null) =
        UserLocation(point = p, speedMetersPerSecond = speed, courseDegrees = course, timestampEpochMillis = 0L)

    @Test
    fun `start emits Navigating`() {
        val c = NavigationController(EnaideConfig())
        c.start(straightRoute())
        assertTrue(c.state.value is NavigationState.Navigating)
    }

    @Test
    fun `progress exposes a road-aligned bearing heading east`() {
        val c = NavigationController(EnaideConfig())
        val r = straightRoute()
        c.start(r)
        c.onLocation(loc(GeoPoint(45.0, 9.005)))
        val nav = c.state.value as NavigationState.Navigating
        val bearing = nav.progress.snappedBearingDegrees
        assertNotNull(bearing)
        // Verso est ≈ 90°.
        assertTrue("bearing $bearing", bearing!! in 80.0..100.0)
    }

    @Test
    fun `reaching the end declares arrival`() {
        val c = NavigationController(EnaideConfig())
        val r = straightRoute()
        c.start(r)
        c.onLocation(loc(r.geometry.last()))
        assertTrue(c.state.value is NavigationState.Arrived)
    }

    @Test
    fun `pedestrian off-route threshold is wider than auto`() {
        val config = EnaideConfig(offRouteThresholdMeters = 30.0, pedestrianOffRouteThresholdMeters = 60.0)
        // ~45m a lato: deviazione per AUTO, ma OK a piedi.
        val offset = GeoPoint(45.0004, 9.005) // ~44m a nord della linea

        val auto = NavigationController(config).apply { start(straightRoute(), TransportProfile.AUTO) }
        auto.onLocation(loc(offset))
        val autoDev = (auto.state.value as NavigationState.Navigating).deviation
        assertTrue("auto dovrebbe essere off-route", autoDev is Deviation.OffRoute)

        val foot = NavigationController(config).apply { start(straightRoute(), TransportProfile.PEDESTRIAN) }
        foot.onLocation(loc(offset))
        val footDev = (foot.state.value as NavigationState.Navigating).deviation
        assertEquals("a piedi dovrebbe restare on-route", Deviation.OnRoute, footDev)
    }

    @Test
    fun `advanceStepManually moves to next step then arrives`() {
        val c = NavigationController(EnaideConfig())
        c.start(straightRoute())
        c.advanceStepManually()
        assertEquals(1, (c.state.value as NavigationState.Navigating).progress.currentStepIndex)
        c.advanceStepManually() // oltre l'ultimo step
        assertTrue(c.state.value is NavigationState.Arrived)
    }

    @Test
    fun `stop returns to Idle`() {
        val c = NavigationController(EnaideConfig())
        c.start(straightRoute())
        c.stop()
        assertEquals(NavigationState.Idle, c.state.value)
    }
}
