package com.enaide.sdk.core

import com.enaide.sdk.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeoUtilsTest {

    @Test
    fun `distance to self is zero`() {
        val p = GeoPoint(45.0, 9.0)
        assertEquals(0.0, GeoUtils.distanceMeters(p, p), 1e-6)
    }

    @Test
    fun `one degree of latitude is roughly 111km`() {
        val a = GeoPoint(45.0, 9.0)
        val b = GeoPoint(46.0, 9.0)
        val d = GeoUtils.distanceMeters(a, b)
        // 1 deg lat ≈ 111.195 km
        assertTrue("distanza inattesa: $d", abs(d - 111_195.0) < 500.0)
    }

    @Test
    fun `bearing due north is zero`() {
        val a = GeoPoint(45.0, 9.0)
        val b = GeoPoint(45.1, 9.0)
        val bearing = GeoUtils.bearingDegrees(a, b)
        assertTrue("bearing dovrebbe essere ~0, è $bearing", bearing < 1.0 || bearing > 359.0)
    }

    @Test
    fun `bearing due east is roughly 90`() {
        val a = GeoPoint(45.0, 9.0)
        val b = GeoPoint(45.0, 9.1)
        val bearing = GeoUtils.bearingDegrees(a, b)
        assertEquals(90.0, bearing, 0.5)
    }

    @Test
    fun `projection on segment perpendicular midpoint`() {
        val a = GeoPoint(45.0, 9.0)
        val b = GeoPoint(45.0, 9.01) // segmento orizzontale (verso est)
        // Un punto leggermente a nord della metà del segmento.
        val p = GeoPoint(45.0001, 9.005)
        val (proj, dist, t) = GeoUtils.projectOnSegment(p, a, b)
        // Il punto proiettato è ~al centro del segmento
        assertEquals(0.5, t, 0.05)
        assertEquals(45.0, proj.latitude, 1e-6)
        // ~11m a nord (1e-4 deg lat ≈ 11.1m)
        assertTrue("distanza inattesa $dist", dist in 8.0..14.0)
    }

    @Test
    fun `projection before segment clamps to start`() {
        val a = GeoPoint(45.0, 9.0)
        val b = GeoPoint(45.0, 9.01)
        val p = GeoPoint(45.0, 8.99) // a ovest dell'inizio
        val (proj, _, t) = GeoUtils.projectOnSegment(p, a, b)
        assertEquals(0.0, t, 1e-9)
        assertEquals(a.latitude, proj.latitude, 1e-9)
        assertEquals(a.longitude, proj.longitude, 1e-9)
    }

    @Test
    fun `polyline length sums segments`() {
        val pts = listOf(
            GeoPoint(45.0, 9.0),
            GeoPoint(45.0, 9.01),
            GeoPoint(45.001, 9.01),
        )
        val total = GeoUtils.polylineLengthMeters(pts)
        // ~786m + ~111m = ~897m
        assertTrue("total inatteso: $total", total in 850.0..950.0)
    }
}
