package com.enaide.sdk.format

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitFormatterTest {

    @Test
    fun `distance below one km rounded to 10m`() {
        assertEquals("0 m", UnitFormatter.formatDistance(0.0))
        assertEquals("50 m", UnitFormatter.formatDistance(53.0))
        assertEquals("120 m", UnitFormatter.formatDistance(123.0))
        assertEquals("850 m", UnitFormatter.formatDistance(848.0))
    }

    @Test
    fun `distance between 1 and 10 km has one decimal in italian style`() {
        // Lingua italiana usa la virgola come separatore decimale.
        assertEquals("1,2 km", UnitFormatter.formatDistance(1234.0))
        assertEquals("9,9 km", UnitFormatter.formatDistance(9900.0))
    }

    @Test
    fun `distance above 10 km is integer km`() {
        assertEquals("12 km", UnitFormatter.formatDistance(12_400.0))
        assertEquals("125 km", UnitFormatter.formatDistance(124_999.0))
    }

    @Test
    fun `duration under a minute is now`() {
        assertEquals("Adesso", UnitFormatter.formatDuration(30.0))
        assertEquals("Adesso", UnitFormatter.formatDuration(59.0))
    }

    @Test
    fun `duration in minutes`() {
        assertEquals("5 min", UnitFormatter.formatDuration(300.0))
        assertEquals("59 min", UnitFormatter.formatDuration(3540.0))
    }

    @Test
    fun `duration with hours`() {
        assertEquals("1 h", UnitFormatter.formatDuration(3600.0))
        assertEquals("1 h 30 min", UnitFormatter.formatDuration(5400.0))
        assertEquals("2 h 5 min", UnitFormatter.formatDuration(7500.0))
    }

    @Test
    fun `speed conversion m_s to km_h`() {
        assertEquals("0 km/h", UnitFormatter.formatSpeedKmh(0.0))
        assertEquals("36 km/h", UnitFormatter.formatSpeedKmh(10.0))
        assertEquals("50 km/h", UnitFormatter.formatSpeedKmh(13.89))
    }
}
