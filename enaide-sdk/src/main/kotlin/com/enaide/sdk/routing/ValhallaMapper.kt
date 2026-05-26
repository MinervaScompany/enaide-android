package com.enaide.sdk.routing

import com.enaide.sdk.model.GeoPoint
import com.enaide.sdk.model.Maneuver
import com.enaide.sdk.model.ManeuverModifier
import com.enaide.sdk.model.ManeuverType
import com.enaide.sdk.model.Route
import com.enaide.sdk.model.RouteStep
import com.enaide.sdk.model.SpokenInstruction
import com.enaide.sdk.model.VisualInstruction

/**
 * Conversione da risposta Valhalla a modello di dominio enaide.
 *
 * Le costanti dei [type] di manovra sono mappate dai sorgenti Valhalla:
 * https://github.com/valhalla/valhalla/blob/master/proto/directions.proto (DirectionsLeg.Maneuver.Type)
 *
 * Le soglie di trigger per le istruzioni vocali sono valori conservativi tipici
 * per uso urbano + autostradale. Possono essere riviste dopo test su strada.
 */
internal object ValhallaMapper {

    /** Distanza alla manovra alla quale far partire l'avviso "lontano" (es. "fra 800m svolta a destra"). */
    private const val ALERT_TRIGGER_METERS = 800.0

    /** Distanza alla manovra alla quale far partire l'istruzione "vicina" (es. "fra 200m svolta a destra"). */
    private const val PRE_TRIGGER_METERS = 200.0

    /** Distanza minima dello step sotto la quale gli alert lontani vengono soppressi (non avresti tempo di dirli). */
    private const val MIN_STEP_LENGTH_FOR_ALERT_METERS = 1200.0

    /** Primary route della risposta. */
    fun toRoute(response: ValhallaRouteResponse): Route =
        tripToRoute(response.trip, response.id)

    /** Lista percorsi alternativi (vuota se non richiesti o non disponibili). */
    fun toAlternatives(response: ValhallaRouteResponse): List<Route> =
        response.alternates.mapIndexed { idx, alt ->
            tripToRoute(alt.trip, response.id?.let { "$it-alt$idx" })
        }

    private fun tripToRoute(trip: ValhallaTrip, requestId: String?): Route {
        val unitToMeters = unitToMetersFactor(trip.units)

        val allGeometry = mutableListOf<GeoPoint>()
        val allSteps = mutableListOf<RouteStep>()

        for (leg in trip.legs) {
            val legShape = PolylineDecoder.decode(leg.shape, precision = 6)

            // Aggiungiamo i punti del leg alla geometria totale.
            // Evitiamo di duplicare il punto di giunzione fra leg consecutivi.
            if (allGeometry.isEmpty()) {
                allGeometry.addAll(legShape)
            } else if (legShape.isNotEmpty()) {
                allGeometry.addAll(legShape.drop(1))
            }

            for (m in leg.maneuvers) {
                allSteps.add(maneuverToStep(m, legShape, unitToMeters))
            }
        }

        return Route(
            id = requestId ?: generateRouteId(),
            geometry = allGeometry,
            steps = allSteps,
            distanceMeters = trip.summary.length * unitToMeters,
            durationSeconds = trip.summary.time,
            waypoints = trip.locations.map { GeoPoint(it.lat, it.lon) },
        )
    }

    private fun maneuverToStep(
        m: ValhallaManeuver,
        legShape: List<GeoPoint>,
        unitToMeters: Double,
    ): RouteStep {
        val stepDistanceMeters = m.length * unitToMeters

        val stepGeometry = if (m.beginShapeIndex in legShape.indices && m.endShapeIndex in legShape.indices) {
            legShape.subList(m.beginShapeIndex, m.endShapeIndex + 1).toList()
        } else {
            emptyList()
        }

        val maneuverPoint = stepGeometry.firstOrNull()
            ?: legShape.getOrNull(m.beginShapeIndex)
            ?: GeoPoint(0.0, 0.0)

        val maneuver = Maneuver(
            type = mapManeuverType(m.type),
            modifier = mapManeuverModifier(m.type),
            at = maneuverPoint,
            roundaboutExit = m.roundaboutExitCount,
            bearingBefore = m.bearingBefore,
            bearingAfter = m.bearingAfter,
        )

        // Istruzioni visuali: una sola, valida per tutto lo step. La trigger
        // distance è settata al massimo (= lunghezza dello step) così è sempre visibile.
        val visual = VisualInstruction(
            primary = m.streetNames?.firstOrNull() ?: m.instruction,
            secondary = if (m.streetNames != null) m.instruction else null,
            triggerDistanceBeforeManeuverMeters = stepDistanceMeters,
        )

        val spoken = buildList {
            // Alert "lontano" — solo se lo step è abbastanza lungo da rendere sensato dirlo.
            if (stepDistanceMeters >= MIN_STEP_LENGTH_FOR_ALERT_METERS && m.verbalTransitionAlert != null) {
                add(
                    SpokenInstruction(
                        text = m.verbalTransitionAlert,
                        triggerDistanceBeforeManeuverMeters = minOf(ALERT_TRIGGER_METERS, stepDistanceMeters - 50.0),
                    )
                )
            }
            // Istruzione "vicina" — sempre presente se Valhalla la fornisce.
            val pre = m.verbalPreTransition ?: m.instruction
            add(
                SpokenInstruction(
                    text = pre,
                    triggerDistanceBeforeManeuverMeters = minOf(PRE_TRIGGER_METERS, stepDistanceMeters / 2.0),
                )
            )
        }

        val lanes = m.lanes.orEmpty().map { l ->
            com.enaide.sdk.model.Lane(
                directions = com.enaide.sdk.model.LaneDirection.fromBitmask(l.directions),
                valid = l.valid != null && l.valid != 0,
                active = l.active != null && l.active != 0,
            )
        }

        return RouteStep(
            geometry = stepGeometry,
            maneuver = maneuver,
            distanceMeters = stepDistanceMeters,
            durationSeconds = m.time,
            roadName = m.streetNames?.firstOrNull() ?: m.beginStreetNames?.firstOrNull(),
            visualInstructions = listOf(visual),
            spokenInstructions = spoken,
            lanes = lanes,
        )
    }

    /**
     * Mapping Valhalla [type] → [ManeuverType].
     * Riferimento: proto DirectionsLeg.Maneuver.Type in valhalla/proto/directions.proto.
     */
    private fun mapManeuverType(valhallaType: Int): ManeuverType = when (valhallaType) {
        0 -> ManeuverType.NOTIFICATION       // kNone
        1, 2, 3 -> ManeuverType.DEPART       // kStart, kStartRight, kStartLeft
        4, 5, 6 -> ManeuverType.ARRIVE       // kDestination, kDestinationRight, kDestinationLeft
        7 -> ManeuverType.NEW_NAME           // kBecomes
        8 -> ManeuverType.CONTINUE           // kContinue
        9, 10, 11, 12 -> ManeuverType.TURN   // kSlightRight, kRight, kSharpRight
        13, 14 -> ManeuverType.UTURN         // kUturnRight, kUturnLeft
        15, 16, 17 -> ManeuverType.TURN      // kSharpLeft, kLeft, kSlightLeft
        18, 19, 20 -> ManeuverType.ON_RAMP   // kRampStraight, kRampRight, kRampLeft
        21, 22 -> ManeuverType.OFF_RAMP      // kExitRight, kExitLeft
        23, 24, 25 -> ManeuverType.FORK      // kStayStraight, kStayRight, kStayLeft
        26 -> ManeuverType.MERGE             // kMerge
        27 -> ManeuverType.ROUNDABOUT        // kRoundaboutEnter
        28 -> ManeuverType.ROUNDABOUT_EXIT   // kRoundaboutExit
        36, 37 -> ManeuverType.MERGE         // kMergeRight, kMergeLeft
        else -> ManeuverType.TURN
    }

    private fun mapManeuverModifier(valhallaType: Int): ManeuverModifier = when (valhallaType) {
        9, 18 -> ManeuverModifier.SLIGHT_RIGHT  // kSlightRight, kRampStraight (placeholder)
        10, 11, 21, 24 -> ManeuverModifier.RIGHT
        12 -> ManeuverModifier.SHARP_RIGHT
        13 -> ManeuverModifier.UTURN
        14 -> ManeuverModifier.UTURN
        15 -> ManeuverModifier.SHARP_LEFT
        16, 22, 25 -> ManeuverModifier.LEFT
        17, 20 -> ManeuverModifier.SLIGHT_LEFT
        8, 23 -> ManeuverModifier.STRAIGHT
        else -> ManeuverModifier.NONE
    }

    private fun unitToMetersFactor(units: String): Double = when (units.lowercase()) {
        "kilometers", "km" -> 1000.0
        "miles", "mi" -> 1609.344
        else -> 1000.0 // default conservativo
    }

    private fun generateRouteId(): String =
        "route-${System.currentTimeMillis()}-${(0..0xFFFF).random().toString(16)}"
}
