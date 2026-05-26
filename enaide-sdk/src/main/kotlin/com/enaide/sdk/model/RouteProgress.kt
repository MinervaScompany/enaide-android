package com.enaide.sdk.model

/**
 * Avanzamento corrente lungo un [Route].
 *
 * Aggiornato a ogni nuovo fix utente. Il `RouteController` è responsabile di
 * tenere coerenti i quattro campi: la distanza totale = `distanceTraveledMeters` + `distanceRemainingMeters`.
 *
 * @property currentStepIndex indice dello step in cui si trova l'utente (0-based).
 * @property distanceAlongStepMeters quanto si è progrediti dentro lo step corrente, da inizio step.
 * @property distanceToNextManeuverMeters distanza residua alla prossima manovra (= fine step corrente).
 * @property distanceTraveledMeters totale percorso dall'inizio del [Route].
 * @property distanceRemainingMeters totale residuo fino al termine.
 * @property durationRemainingSeconds ETA residuo in secondi (stima rolling).
 * @property snappedLocation posizione dell'utente "snappata" sulla geometria del percorso.
 * @property snappedBearingDegrees direzione del segmento di strada su cui si è
 *   snappati (gradi, 0 = nord). È il bearing da usare per orientare camera/freccia
 *   in navigazione: stabile e allineato alla strada, a differenza del bearing GPS
 *   grezzo che traballa a bassa velocità. `null` se non determinabile.
 */
public data class RouteProgress(
    public val currentStepIndex: Int,
    public val distanceAlongStepMeters: Double,
    public val distanceToNextManeuverMeters: Double,
    public val distanceTraveledMeters: Double,
    public val distanceRemainingMeters: Double,
    public val durationRemainingSeconds: Double,
    public val snappedLocation: GeoPoint,
    public val snappedBearingDegrees: Double? = null,
)
