package com.enaide.sdk.vehicle

import com.enaide.sdk.model.DistanceUnit
import com.enaide.sdk.model.RouteOptions
import com.enaide.sdk.model.TransportProfile
import com.enaide.sdk.model.VehicleDimensions

/**
 * Descrizione del mezzo con cui si naviga: tipo di mobilità + (per i mezzi su
 * gomma) sagoma e preferenze. È la forma "user-friendly" che la UI compila e che
 * si traduce nei parametri di routing Valhalla.
 *
 * Si converte in [TransportProfile] + [RouteOptions] via [toProfile] e
 * [toRouteOptions], pronti da passare a `EnaideNavigator.computeRoute(...)`.
 *
 * Modulo opzionale ed escludibile: il core conosce già `TransportProfile`/
 * `VehicleDimensions`; questo modulo aggiunge solo l'ergonomia (preset, builder,
 * validazione) sopra di essi.
 *
 * @property kind tipo di mezzo/mobilità.
 * @property dimensions sagoma e peso — significativi solo per [VehicleKind.TRUCK]; ignorati altrove.
 * @property avoidTolls evita pedaggi dove possibile.
 * @property avoidHighways evita autostrade/superstrade.
 * @property avoidFerries evita traghetti.
 * @property language lingua delle istruzioni (es. "it-IT").
 * @property units unità di misura (km/miglia).
 */
public data class VehicleProfile(
    public val kind: VehicleKind,
    public val dimensions: VehicleDimensions? = null,
    public val avoidTolls: Boolean = false,
    public val avoidHighways: Boolean = false,
    public val avoidFerries: Boolean = false,
    public val language: String = "it-IT",
    public val units: DistanceUnit = DistanceUnit.KILOMETERS,
) {
    /** Profilo di trasporto Valhalla corrispondente. */
    public fun toProfile(): TransportProfile = kind.transportProfile

    /** Opzioni di routing corrispondenti (sagoma inclusa solo per il camion). */
    public fun toRouteOptions(numberOfAlternatives: Int = 0): RouteOptions = RouteOptions(
        numberOfAlternatives = numberOfAlternatives,
        avoidTolls = avoidTolls,
        avoidHighways = avoidHighways,
        avoidFerries = avoidFerries,
        language = language,
        units = units,
        vehicleDimensions = if (kind == VehicleKind.TRUCK) dimensions else null,
    )

    public companion object {
        /** Auto privata, nessuna restrizione. */
        public fun car(): VehicleProfile = VehicleProfile(VehicleKind.CAR)

        /** A piedi. */
        public fun pedestrian(): VehicleProfile = VehicleProfile(VehicleKind.PEDESTRIAN)

        /** In bicicletta. */
        public fun bicycle(): VehicleProfile = VehicleProfile(VehicleKind.BICYCLE)

        /**
         * Camion con sagoma. I default sono quelli di un mezzo medio; passa
         * [dimensions] reali per evitare strade vietate al tuo veicolo specifico.
         */
        public fun truck(dimensions: VehicleDimensions = DefaultTruck): VehicleProfile =
            VehicleProfile(VehicleKind.TRUCK, dimensions = dimensions)

        /** Sagoma camion di default ragionevole (mezzo rigido medio). */
        public val DefaultTruck: VehicleDimensions = VehicleDimensions(
            heightMeters = 3.5,
            widthMeters = 2.5,
            lengthMeters = 9.0,
            weightKg = 12_000.0,
            axleLoadKg = 9_000.0,
            hazmat = false,
        )
    }
}

/**
 * Tipo di mezzo offerto dalla UI. Mappa su [TransportProfile] del core.
 *
 * `CAR` ↔ AUTO; gli altri 1:1. Separato da [TransportProfile] per dare alla UI un
 * vocabolario suo (es. potremmo aggiungere "SCOOTER" qui prima che il core lo
 * esponga).
 */
public enum class VehicleKind(public val transportProfile: TransportProfile, public val displayName: String) {
    CAR(TransportProfile.AUTO, "Auto"),
    PEDESTRIAN(TransportProfile.PEDESTRIAN, "A piedi"),
    BICYCLE(TransportProfile.BICYCLE, "Bici"),
    TRUCK(TransportProfile.TRUCK, "Camion"),
}
