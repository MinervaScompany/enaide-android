package com.enaide.sdk.poi

import kotlinx.serialization.Serializable

/**
 * DTO della risposta Overpass API (`/api/interpreter`, formato JSON).
 *
 * Gli elementi `node` hanno `lat`/`lon` diretti; `way`/`relation` hanno il
 * baricentro in `center`. I `tags` portano le chiavi OSM (name, amenity, ...).
 */
@Serializable
internal data class OverpassResponse(
    val elements: List<OverpassElement> = emptyList(),
)

@Serializable
internal data class OverpassElement(
    val type: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val center: OverpassCenter? = null,
    val tags: Map<String, String> = emptyMap(),
) {
    /** Coordinate effettive: dirette per i node, dal center per way/relation. */
    fun latLon(): Pair<Double, Double>? {
        val la = lat ?: center?.lat ?: return null
        val lo = lon ?: center?.lon ?: return null
        return la to lo
    }
}

@Serializable
internal data class OverpassCenter(
    val lat: Double,
    val lon: Double,
)
