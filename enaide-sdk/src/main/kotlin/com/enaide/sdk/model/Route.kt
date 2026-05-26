package com.enaide.sdk.model

/**
 * Singolo step di un percorso: un tratto fra due manovre consecutive.
 *
 * @property geometry polilinea del tratto, decodificata in punti.
 * @property maneuver manovra eseguita all'inizio dello step.
 * @property distanceMeters lunghezza dello step.
 * @property durationSeconds durata stimata percorrendo lo step.
 * @property roadName nome della strada principale, se noto.
 * @property visualInstructions istruzioni visuali ordinate per trigger discendente.
 * @property spokenInstructions istruzioni vocali ordinate per trigger discendente.
 */
public data class RouteStep(
    public val geometry: List<GeoPoint>,
    public val maneuver: Maneuver,
    public val distanceMeters: Double,
    public val durationSeconds: Double,
    public val roadName: String? = null,
    public val visualInstructions: List<VisualInstruction> = emptyList(),
    public val spokenInstructions: List<SpokenInstruction> = emptyList(),
)

/**
 * Percorso calcolato dal routing engine.
 *
 * Identificato univocamente da [id] (utile a clienti che vogliono cache lato app).
 *
 * @property id identificatore opaco generato dall'SDK.
 * @property geometry polilinea completa (concatenazione delle geometrie degli step).
 * @property steps sequenza ordinata di step.
 * @property distanceMeters lunghezza totale.
 * @property durationSeconds durata totale stimata.
 * @property waypoints punti waypoint richiesti (origine, intermedi, destinazione) in ordine.
 */
public data class Route(
    public val id: String,
    public val geometry: List<GeoPoint>,
    public val steps: List<RouteStep>,
    public val distanceMeters: Double,
    public val durationSeconds: Double,
    public val waypoints: List<GeoPoint>,
)
