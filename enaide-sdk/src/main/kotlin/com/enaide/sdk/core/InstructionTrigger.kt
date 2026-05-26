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
     * Oltre alle soglie in metri delle istruzioni (dal mapper), considera la
     * **velocità reale**: converte soglie a TEMPO (annuncio "preparati" ~[ALERT_SECONDS]s
     * prima, "adesso" ~[NOW_SECONDS]s prima) in distanze dinamiche. Così a piedi
     * non si anticipa troppo e in autostrada non si arriva tardi — come i veri
     * navigatori (OsmAnd usa trigger temporali).
     *
     * @param speedMps velocità corrente; se ~0 si usano solo le soglie in metri.
     * @return l'istruzione da pronunciare (e mai più), oppure `null` se nulla da dire.
     */
    fun consumePendingSpoken(
        stepIndex: Int,
        step: RouteStep,
        distanceToManeuverMeters: Double,
        speedMps: Double = 0.0,
    ): SpokenInstruction? {
        // 1) Soglie esplicite delle istruzioni (come prima).
        for (instr in step.spokenInstructions) {
            if (distanceToManeuverMeters <= instr.triggerDistanceBeforeManeuverMeters) {
                val key = "$stepIndex|${instr.triggerDistanceBeforeManeuverMeters}|${instr.text}"
                if (spokenAlready.add(key)) return instr
            }
        }

        // 2) Soglie a TEMPO basate sulla velocità: ridicono l'ultima istruzione
        //    dello step ai momenti giusti (preparati / adesso), senza ridire ciò
        //    che è già stato detto al punto 1.
        if (speedMps > 0.5) {
            val finalInstr = step.spokenInstructions.lastOrNull() ?: return null
            val alertDist = speedMps * ALERT_SECONDS
            val nowDist = speedMps * NOW_SECONDS
            for ((label, dist) in listOf("alert" to alertDist, "now" to nowDist)) {
                if (distanceToManeuverMeters <= dist) {
                    val key = "$stepIndex|time-$label|${finalInstr.text}"
                    if (spokenAlready.add(key)) return finalInstr
                }
            }
        }
        return null
    }

    private companion object {
        /** Anticipo "preparati alla manovra", in secondi di percorrenza. */
        const val ALERT_SECONDS = 45.0
        /** Anticipo "esegui ora la manovra", in secondi. */
        const val NOW_SECONDS = 8.0
    }
}
