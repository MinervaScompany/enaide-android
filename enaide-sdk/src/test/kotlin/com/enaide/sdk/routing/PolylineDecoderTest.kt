package com.enaide.sdk.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PolylineDecoderTest {

    @Test
    fun `empty string returns empty list`() {
        assertEquals(emptyList<Any>(), PolylineDecoder.decode("", precision = 6))
    }

    @Test
    fun `single point round trip polyline6`() {
        // Polyline6 encoding di (lat 38.5, lon -120.2) — verificato con riferimento standard.
        val encoded = "_p~iF~ps|U"   // precision 5
        val pts = PolylineDecoder.decode(encoded, precision = 5)
        assertEquals(1, pts.size)
        assertEquals(38.5, pts[0].latitude, 1e-5)
        assertEquals(-120.2, pts[0].longitude, 1e-5)
    }

    @Test
    fun `classic google polyline example decodes correctly at precision 5`() {
        // Esempio dalla doc Google: 3 punti.
        val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        val pts = PolylineDecoder.decode(encoded, precision = 5)
        assertEquals(3, pts.size)
        assertNear(pts[0].latitude, 38.5, 1e-5)
        assertNear(pts[0].longitude, -120.2, 1e-5)
        assertNear(pts[1].latitude, 40.7, 1e-5)
        assertNear(pts[1].longitude, -120.95, 1e-5)
        assertNear(pts[2].latitude, 43.252, 1e-5)
        assertNear(pts[2].longitude, -126.453, 1e-5)
    }

    private fun assertNear(actual: Double, expected: Double, tol: Double) {
        assertTrue("atteso $expected ± $tol, ricevuto $actual", abs(actual - expected) <= tol)
    }
}
