package com.enaide.sdk.model

/**
 * Fix di posizione utente, normalmente proveniente dal GPS.
 *
 * Disambigua dal tipo [android.location.Location] della piattaforma:
 * l'SDK accetta solo questo tipo come input, così la logica di navigazione
 * resta testabile in puro JVM senza dipendenze Android.
 *
 * @property point coordinate del fix.
 * @property altitudeMeters altitudine in metri sul livello del mare, se nota.
 * @property horizontalAccuracyMeters raggio di incertezza orizzontale a 1σ.
 * @property courseDegrees direzione di moto in gradi (0 = nord, in senso orario). `null` se sconosciuta o utente fermo.
 * @property speedMetersPerSecond velocità misurata in m/s. `null` se sconosciuta.
 * @property timestampEpochMillis tempo del fix in millisecondi UTC (System.currentTimeMillis()).
 */
public data class UserLocation(
    public val point: GeoPoint,
    public val altitudeMeters: Double? = null,
    public val horizontalAccuracyMeters: Double? = null,
    public val courseDegrees: Double? = null,
    public val speedMetersPerSecond: Double? = null,
    public val timestampEpochMillis: Long,
)
