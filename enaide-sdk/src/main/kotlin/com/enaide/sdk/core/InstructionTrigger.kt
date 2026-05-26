package com.enaide.sdk.core

import com.enaide.sdk.model.RouteStep
import com.enaide.sdk.model.SpokenInstruction
import com.enaide.sdk.model.VisualInstruction

/**
 * Decide quando far partire una [SpokenInstruction] per il [RouteStep] corrente.
 *
 * Mantiene un set di id di istruzioni già pronunciate per evitare doppi annunci
 * (il GPS può oscillare avanti e indietro intorno a una soglia).
 *
 * Stessa logica vale per quale [VisualInstruction] mostrare a schermo:
 * è semplicemente quella con la più alta `triggerDistanceBefore...` ancora
 * valida rispetto alla distanza residua.
 */
internal class InstructionTrigger {

    /** Identificatore opaco di istruzione già pronunciata: "stepIndex|triggerDistance|text". */
    private val spokenAlready = mutableSetOf<String>()

    /**
     * Risetta lo stato (es. avvio nuovo route).
     */
    fun reset() {
        spokenAlready.clear()
    }

    /**
     * Calcola l'istruzione visuale corrente per lo [step] dato la distanza alla
     * prossima manovra.
     *
     * @param step step corrente.
     * @param distanceToManeuverMeters distanza residua alla manovra finale dello step.
     */
    fun currentVisual(step: RouteStep, distanceToManeuverMeters: Double): VisualInstruction? {
        // La prima istruzione con trigger >= distanza residua è quella che vediamo.
        // Le istruzioni sono ordinate "lontane prima vicine dopo" → ne prendiamo la prima utile.
        return step.visualInstructions.firstOrNull { it.triggerDistanceBeforeManeuverMeters >= distanceToManeuverMeters }
            ?: step.visualInstructions.lastOrNull()
    }

    /**
     * Calcola l'istruzione vocale da pronunciare *adesso*, se ce n'è una pendente.
     *
     * @return l'istruzione da pronunciare (e mai più), oppure `null` se nulla da dire.
     */
    fun consumePendingSpoken(
        stepIndex: Int,
        step: RouteStep,
        distanceToManeuverMeters: Double,
    ): SpokenInstruction? {
        for (instr in step.spokenInstructions) {
            if (distanceToManeuverMeters <= instr.triggerDistanceBeforeManeuverMeters) {
                val key = "$stepIndex|${instr.triggerDistanceBeforeManeuverMeters}|${instr.text}"
                if (spokenAlready.add(key)) {
                    return instr
                }
            }
        }
        return null
    }
}
