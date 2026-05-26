package com.enaide.sdk.core

import com.enaide.sdk.model.GeoPoint
import com.enaide.sdk.model.Maneuver
import com.enaide.sdk.model.ManeuverModifier
import com.enaide.sdk.model.ManeuverType
import com.enaide.sdk.model.RouteStep
import com.enaide.sdk.model.SpokenInstruction
import com.enaide.sdk.model.VisualInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class InstructionTriggerTest {

    private fun step(): RouteStep = RouteStep(
        geometry = listOf(GeoPoint(45.0, 9.0), GeoPoint(45.001, 9.0)),
        maneuver = Maneuver(
            type = ManeuverType.TURN,
            modifier = ManeuverModifier.LEFT,
            at = GeoPoint(45.001, 9.0),
        ),
        distanceMeters = 1500.0,
        durationSeconds = 60.0,
        visualInstructions = listOf(
            VisualInstruction(primary = "Continua su Via Roma", triggerDistanceBeforeManeuverMeters = 1500.0),
        ),
        spokenInstructions = listOf(
            SpokenInstruction(text = "Fra 800 metri svolta a sinistra", triggerDistanceBeforeManeuverMeters = 800.0),
            SpokenInstruction(text = "Svolta a sinistra", triggerDistanceBeforeManeuverMeters = 200.0),
        ),
    )

    @Test
    fun `no spoken instruction when far from maneuver`() {
        val trigger = InstructionTrigger()
        val result = trigger.consumePendingSpoken(stepIndex = 0, step = step(), distanceToManeuverMeters = 1200.0)
        assertNull(result)
    }

    @Test
    fun `fires alert at 800m threshold`() {
        val trigger = InstructionTrigger()
        val result = trigger.consumePendingSpoken(stepIndex = 0, step = step(), distanceToManeuverMeters = 750.0)
        assertNotNull(result)
        assertEquals("Fra 800 metri svolta a sinistra", result?.text)
    }

    @Test
    fun `does not fire same alert twice`() {
        val trigger = InstructionTrigger()
        val first = trigger.consumePendingSpoken(0, step(), 750.0)
        val second = trigger.consumePendingSpoken(0, step(), 700.0)
        assertNotNull(first)
        assertNull(second)
    }

    @Test
    fun `fires pre-transition instruction at 200m`() {
        val trigger = InstructionTrigger()
        // saltiamo l'alert lontano
        trigger.consumePendingSpoken(0, step(), 750.0)
        val result = trigger.consumePendingSpoken(0, step(), 180.0)
        assertNotNull(result)
        assertEquals("Svolta a sinistra", result?.text)
    }

    @Test
    fun `currentVisual returns the only visual instruction throughout the step`() {
        val trigger = InstructionTrigger()
        val visual = trigger.currentVisual(step(), distanceToManeuverMeters = 500.0)
        assertNotNull(visual)
        assertEquals("Continua su Via Roma", visual?.primary)
    }

    @Test
    fun `reset clears spoken history`() {
        val trigger = InstructionTrigger()
        trigger.consumePendingSpoken(0, step(), 750.0)
        trigger.reset()
        val again = trigger.consumePendingSpoken(0, step(), 750.0)
        assertNotNull(again)
    }
}
