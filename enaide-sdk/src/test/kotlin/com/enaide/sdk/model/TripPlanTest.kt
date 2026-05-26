package com.enaide.sdk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripPlanTest {

    private fun stop(lat: Double, label: String) = TripStop(GeoPoint(lat, 9.0), label)

    @Test
    fun `empty plan is not routable`() {
        assertFalse(TripPlan().isRoutable)
        assertFalse(TripPlan(listOf(stop(45.0, "a"))).isRoutable)
        assertTrue(TripPlan.of(stop(45.0, "a"), stop(46.0, "b")).isRoutable)
    }

    @Test
    fun `of builds origin and destination`() {
        val p = TripPlan.of(stop(45.0, "O"), stop(46.0, "D"))
        assertEquals("O", p.origin?.label)
        assertEquals("D", p.destination?.label)
        assertTrue(p.intermediateStops.isEmpty())
    }

    @Test
    fun `addStop inserts before destination by default`() {
        val p = TripPlan.of(stop(45.0, "O"), stop(48.0, "D"))
            .addStop(stop(46.0, "S"))
        assertEquals(listOf("O", "S", "D"), p.stops.map { it.label })
        assertEquals(listOf("S"), p.intermediateStops.map { it.label })
    }

    @Test
    fun `addStop with index respects bounds and never moves origin`() {
        val p = TripPlan.of(stop(45.0, "O"), stop(48.0, "D"))
            .addStop(stop(46.0, "S"), index = 0) // clampato a 1
        assertEquals("O", p.stops.first().label)
        assertEquals("S", p.stops[1].label)
    }

    @Test
    fun `removeStop removes the right element`() {
        val p = TripPlan(listOf(stop(45.0, "O"), stop(46.0, "S"), stop(48.0, "D")))
            .removeStop(1)
        assertEquals(listOf("O", "D"), p.stops.map { it.label })
    }

    @Test
    fun `removeStop out of range is a no-op`() {
        val p = TripPlan.of(stop(45.0, "O"), stop(48.0, "D"))
        assertEquals(p, p.removeStop(9))
    }

    @Test
    fun `moveStop reorders`() {
        val p = TripPlan(listOf(stop(45.0, "O"), stop(46.0, "A"), stop(47.0, "B"), stop(48.0, "D")))
            .moveStop(1, 2)
        assertEquals(listOf("O", "B", "A", "D"), p.stops.map { it.label })
    }

    @Test
    fun `withOrigin and withDestination replace ends`() {
        val p = TripPlan.of(stop(45.0, "O"), stop(48.0, "D"))
            .withOrigin(stop(40.0, "O2"))
            .withDestination(stop(50.0, "D2"))
        assertEquals("O2", p.origin?.label)
        assertEquals("D2", p.destination?.label)
        assertEquals(2, p.stops.size)
    }

    @Test
    fun `waypoints maps points in order`() {
        val p = TripPlan(listOf(stop(45.0, "O"), stop(46.0, "S"), stop(48.0, "D")))
        assertEquals(listOf(45.0, 46.0, 48.0), p.waypoints.map { it.latitude })
    }
}
