package com.enaide.sdk.model

/**
 * Profilo di mobilità usato per il calcolo del percorso.
 *
 * Mappa 1:1 sui "costing" di Valhalla: `auto`, `truck`, `bicycle`, `pedestrian`.
 * Aggiunte future (es. `motor_scooter`, `taxi`) entrano qui.
 */
public enum class TransportProfile(public val valhallaCosting: String) {
    AUTO("auto"),
    TRUCK("truck"),
    BICYCLE("bicycle"),
    PEDESTRIAN("pedestrian"),
}
