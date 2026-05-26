package com.enaide.sdk.routing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// =============================================================================
// Richiesta
// =============================================================================

/**
 * Body POST per `/route` di Valhalla. Manteniamo lo schema rigido (solo i campi
 * che usiamo davvero) — espandere quando servono opzioni nuove (avoid_polygons,
 * exclude_locations, time-based costing, ecc.).
 */
@Serializable
internal data class ValhallaRouteRequest(
    val locations: List<ValhallaLocation>,
    val costing: String,
    @SerialName("costing_options") val costingOptions: JsonObject? = null,
    @SerialName("directions_options") val directionsOptions: ValhallaDirectionsOptions = ValhallaDirectionsOptions(),
    @SerialName("shape_format") val shapeFormat: String = "polyline6",
    val units: String = "kilometers",
    val alternates: Int = 0,
    val id: String? = null,
)

@Serializable
internal data class ValhallaLocation(
    val lat: Double,
    val lon: Double,
    val type: String = "break",
)

@Serializable
internal data class ValhallaDirectionsOptions(
    val units: String = "kilometers",
    val language: String = "it-IT",
)

// =============================================================================
// Risposta
// =============================================================================

@Serializable
internal data class ValhallaRouteResponse(
    val trip: ValhallaTrip,
    val alternates: List<ValhallaAlternate> = emptyList(),
    val id: String? = null,
)

@Serializable
internal data class ValhallaAlternate(
    val trip: ValhallaTrip,
)

@Serializable
internal data class ValhallaTrip(
    val locations: List<ValhallaLocation>,
    val legs: List<ValhallaLeg>,
    val summary: ValhallaSummary,
    @SerialName("status_message") val statusMessage: String,
    val status: Int,
    val units: String,
    val language: String,
)

@Serializable
internal data class ValhallaLeg(
    val maneuvers: List<ValhallaManeuver>,
    val summary: ValhallaSummary,
    val shape: String,
)

@Serializable
internal data class ValhallaSummary(
    /** Tempo in secondi. */
    val time: Double,
    /** Lunghezza nelle unità della response (`units`). */
    val length: Double,
    @SerialName("has_toll") val hasToll: Boolean = false,
    @SerialName("has_highway") val hasHighway: Boolean = false,
    @SerialName("has_ferry") val hasFerry: Boolean = false,
)

@Serializable
internal data class ValhallaManeuver(
    /** Codice manovra Valhalla (vedi ValhallaManeuverType nel mapper). */
    val type: Int,
    val instruction: String,
    @SerialName("verbal_transition_alert_instruction")
    val verbalTransitionAlert: String? = null,
    @SerialName("verbal_pre_transition_instruction")
    val verbalPreTransition: String? = null,
    @SerialName("verbal_post_transition_instruction")
    val verbalPostTransition: String? = null,
    @SerialName("street_names") val streetNames: List<String>? = null,
    @SerialName("begin_street_names") val beginStreetNames: List<String>? = null,
    /** Tempo dello step in secondi. */
    val time: Double,
    /** Lunghezza step nelle unità della response. */
    val length: Double,
    @SerialName("begin_shape_index") val beginShapeIndex: Int,
    @SerialName("end_shape_index") val endShapeIndex: Int,
    @SerialName("travel_mode") val travelMode: String? = null,
    @SerialName("travel_type") val travelType: String? = null,
    @SerialName("roundabout_exit_count") val roundaboutExitCount: Int? = null,
    @SerialName("bearing_before") val bearingBefore: Double? = null,
    @SerialName("bearing_after") val bearingAfter: Double? = null,
)
