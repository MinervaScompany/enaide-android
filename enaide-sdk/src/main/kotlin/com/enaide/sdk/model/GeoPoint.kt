package com.enaide.sdk.model

import kotlinx.serialization.Serializable

/**
 * Punto geografico in coordinate WGS84.
 *
 * Latitudine in [-90, 90], longitudine in [-180, 180]. Il costruttore valida
 * i range — coordinate fuori scala lanciano [IllegalArgumentException].
 *
 * @property latitude latitudine in gradi decimali.
 * @property longitude longitudine in gradi decimali.
 */
@Serializable
public data class GeoPoint(
    public val latitude: Double,
    public val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude fuori range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude fuori range: $longitude" }
    }
}
