package com.enaide.sdk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneDirectionTest {

    @Test
    fun `single bit decodes to one direction`() {
        assertEquals(setOf(LaneDirection.THROUGH), LaneDirection.fromBitmask(1))
        assertEquals(setOf(LaneDirection.LEFT), LaneDirection.fromBitmask(4))
        assertEquals(setOf(LaneDirection.SHARP_RIGHT), LaneDirection.fromBitmask(64))
    }

    @Test
    fun `combined bitmask decodes to multiple directions`() {
        // 10 = 2 (SHARP_LEFT) + 8 (SLIGHT_LEFT)
        assertEquals(
            setOf(LaneDirection.SHARP_LEFT, LaneDirection.SLIGHT_LEFT),
            LaneDirection.fromBitmask(10),
        )
        // 72 = 8 (SLIGHT_LEFT) + 64 (SHARP_RIGHT)
        assertEquals(
            setOf(LaneDirection.SLIGHT_LEFT, LaneDirection.SHARP_RIGHT),
            LaneDirection.fromBitmask(72),
        )
    }

    @Test
    fun `zero bitmask decodes to empty set`() {
        assertTrue(LaneDirection.fromBitmask(0).isEmpty())
    }
}
