package com.enaide.sdk.model

/**
 * Opzioni passate al motore di routing per influenzare il calcolo del percorso.
 *
 * Tutti i campi hanno default neutrali: senza specificarne nessuno, ottieni lo
 * stesso comportamento "vanilla" della v0.1.
 *
 * @property numberOfAlternatives quanti percorsi alternativi richiedere oltre
 *   al primario. 0 = solo primario (default). Valhalla supporta fino a 2.
 * @property avoidTolls evita strade a pedaggio dove possibile.
 * @property avoidHighways evita autostrade dove possibile.
 * @property avoidFerries evita traghetti dove possibile.
 * @property language codice lingua per le istruzioni (es. "it-IT", "en-US").
 *   Default `it-IT`. Valhalla supporta una decina di lingue.
 * @property units unità di misura per la response Valhalla (e per le istruzioni vocali).
 * @property vehicleDimensions dimensioni e peso del veicolo. Usato solo se
 *   [TransportProfile.TRUCK]; ignorato per gli altri profili. Permette a Valhalla
 *   di evitare strade vietate al camion specifico.
 */
public data class RouteOptions(
    public val numberOfAlternatives: Int = 0,
    public val avoidTolls: Boolean = false,
    public val avoidHighways: Boolean = false,
    public val avoidFerries: Boolean = false,
    public val language: String = "it-IT",
    public val units: DistanceUnit = DistanceUnit.KILOMETERS,
    public val vehicleDimensions: VehicleDimensions? = null,
) {
    public companion object {
        public val Default: RouteOptions = RouteOptions()
    }
}

public enum class DistanceUnit(public val valhallaValue: String) {
    KILOMETERS("kilometers"),
    MILES("miles"),
}

/**
 * Dimensioni e peso veicolo, in unità SI. Mappa direttamente sui parametri di
 * costing di Valhalla per `truck`:
 * https://valhalla.github.io/valhalla/api/turn-by-turn/api-reference/#costing-options
 *
 * @property heightMeters altezza massima.
 * @property widthMeters larghezza massima.
 * @property lengthMeters lunghezza massima.
 * @property weightKg peso lordo a pieno carico.
 * @property axleLoadKg carico per asse.
 * @property hazmat trasporto merci pericolose (`true` esclude strade vietate al trasporto).
 */
public data class VehicleDimensions(
    public val heightMeters: Double? = null,
    public val widthMeters: Double? = null,
    public val lengthMeters: Double? = null,
    public val weightKg: Double? = null,
    public val axleLoadKg: Double? = null,
    public val hazmat: Boolean = false,
)
