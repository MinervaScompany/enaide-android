package com.enaide.sdk.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviationDetectorTest {

    @Test
    fun `single fix off-route does not trigger`() {
        val d = DeviationDetector(thresholdMeters = 30.0, confirmationCount = 3)
        assertFalse(d.onLocation(50.0))
    }

    @Test
    fun `three consecutive off-route fixes trigger deviation`() {
        val d = DeviationDetector(thresholdMeters = 30.0, confirmationCount = 3)
        assertFalse(d.onLocation(50.0))
        assertFalse(d.onLocation(40.0))
        assertTrue(d.onLocation(60.0))
    }

    @Test
    fun `single on-route fix resets the counter`() {
        val d = DeviationDetector(thresholdMeters = 30.0, confirmationCount = 3)
        assertFalse(d.onLocation(50.0))
        assertFalse(d.onLocation(50.0))
        assertFalse(d.onLocation(10.0)) // dentro soglia, reset
        assertFalse(d.onLocation(50.0))
        assertFalse(d.onLocation(50.0))
        assertTrue(d.onLocation(50.0)) // ora tre consecutivi
    }

    @Test
    fun `manual reset clears the counter`() {
        val d = DeviationDetector(thresholdMeters = 30.0, confirmationCount = 2)
        assertFalse(d.onLocation(50.0))
        d.reset()
        assertFalse(d.onLocation(50.0)) // dopo reset serve di nuovo un secondo
        assertTrue(d.onLocation(50.0))
    }
}
