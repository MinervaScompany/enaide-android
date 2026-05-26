package com.enaide.sdk.core

/**
 * Decide quando l'utente è "veramente" fuori percorso.
 *
 * Strategia con isteresi:
 *  - serve [confirmationCount] fix consecutivi con distanza > [thresholdMeters]
 *    per dichiarare deviazione.
 *  - un singolo fix entro soglia resetta il contatore.
 *
 * Motivazione: il GPS consumer ha errori 5-15m in città e impulsi di 50m+
 * nei canyon urbani. Senza isteresi si ricalcolerebbe in continuazione.
 *
 * I valori esatti vanno calibrati su strada — i default in [com.enaide.sdk.EnaideConfig]
 * sono 30m / 3 fix che è un punto di partenza ragionevole.
 */
internal class DeviationDetector(
    private val thresholdMeters: Double,
    private val confirmationCount: Int,
) {
    private var consecutiveOffRoute: Int = 0

    /**
     * Da chiamare a ogni fix con la distanza misurata dal percorso.
     * @return `true` se la deviazione è confermata (ricalcola percorso).
     */
    fun onLocation(distanceFromRouteMeters: Double): Boolean {
        return if (distanceFromRouteMeters > thresholdMeters) {
            consecutiveOffRoute++
            consecutiveOffRoute >= confirmationCount
        } else {
            consecutiveOffRoute = 0
            false
        }
    }

    /** Resetta il contatore (es. dopo che un nuovo route è stato ottenuto). */
    fun reset() {
        consecutiveOffRoute = 0
    }
}
