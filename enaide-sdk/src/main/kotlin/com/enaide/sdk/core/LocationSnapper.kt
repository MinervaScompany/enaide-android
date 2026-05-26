package com.enaide.sdk.core

import com.enaide.sdk.model.GeoPoint
import com.enaide.sdk.model.Route

/**
 * "Aggancia" la posizione utente al percorso più vicino e calcola la progressione.
 *
 * Approccio: per ciascun segmento della geometria del [Route], calcola la proiezione
 * del punto utente. Sceglie il segmento con distanza minima. Cumula le lunghezze
 * dei segmenti precedenti per ottenere "quanto è stato percorso" del route totale.
 *
 * Limitazioni note (da migliorare in iterazioni successive):
 *  - Per route con segmenti molto vicini fra loro (es. cavalcavia sopra strade
 *    parallele) può scegliere il segmento "sbagliato". Mitigazione tipica: usare
 *    anche il bearing del moto utente per filtrare i candidati. Lo aggiungiamo
 *    dopo aver visto comportamento reale su strada.
 *  - O(N) sui segmenti per ogni fix. Per route lunghi (>10k punti) si farà un
 *    indice spaziale (grid o R-tree) o si limiterà la ricerca a una finestra
 *    attorno all'ultimo match.
 */
internal class LocationSnapper(private val route: Route) {

    /**
     * Lunghezze cumulate per ogni vertice della geometria del route.
     * `cumulative[i]` = distanza dall'inizio del route al vertice i-esimo.
     * Pre-calcolata una volta sola in costruzione per O(1) lookup.
     */
    private val cumulative: DoubleArray = DoubleArray(route.geometry.size).also { arr ->
        for (i in 1 until route.geometry.size) {
            arr[i] = arr[i - 1] + GeoUtils.distanceMeters(route.geometry[i - 1], route.geometry[i])
        }
    }

    /** Lunghezza totale della geometria (può differire leggermente da `route.distanceMeters`). */
    val totalLengthMeters: Double get() = cumulative.lastOrNull() ?: 0.0

    /**
     * Snappa [userLocation] al route più vicino.
     *
     * @return [SnapResult] con punto agganciato, distanza dal route e progressione cumulata.
     */
    fun snap(userLocation: GeoPoint): SnapResult {
        require(route.geometry.size >= 2) { "route con geometria vuota o di un solo punto, non snappabile" }

        var bestDistance = Double.POSITIVE_INFINITY
        var bestSnapped: GeoPoint = route.geometry.first()
        var bestSegmentIndex = 0
        var bestT = 0.0

        for (i in 0 until route.geometry.size - 1) {
            val (proj, dist, t) = GeoUtils.projectOnSegment(
                p = userLocation,
                a = route.geometry[i],
                b = route.geometry[i + 1],
            )
            if (dist < bestDistance) {
                bestDistance = dist
                bestSnapped = proj
                bestSegmentIndex = i
                bestT = t
            }
        }

        val segmentLength = cumulative[bestSegmentIndex + 1] - cumulative[bestSegmentIndex]
        val distanceTraveled = cumulative[bestSegmentIndex] + bestT * segmentLength
        val distanceRemaining = (totalLengthMeters - distanceTraveled).coerceAtLeast(0.0)

        return SnapResult(
            snappedPoint = bestSnapped,
            distanceFromRouteMeters = bestDistance,
            segmentIndex = bestSegmentIndex,
            distanceTraveledMeters = distanceTraveled,
            distanceRemainingMeters = distanceRemaining,
        )
    }

    /**
     * Dato un [segmentIndex] della geometria globale, ritorna l'indice dello
     * [com.enaide.sdk.model.RouteStep] che lo contiene.
     *
     * Si basa sul fatto che gli step sono geometricamente contigui nell'ordine
     * di emissione di Valhalla (vedi ValhallaMapper).
     */
    fun stepIndexForGeometryIndex(segmentIndex: Int): Int {
        // Mappa step → range di indici nella geometria.
        // Implementazione semplice ma O(steps); va benissimo (manciata di step tipici).
        var cursor = 0
        for ((i, step) in route.steps.withIndex()) {
            val stepStart = cursor
            val stepEnd = cursor + (step.geometry.size - 1).coerceAtLeast(0)
            if (segmentIndex in stepStart until stepEnd.coerceAtLeast(stepStart + 1)) {
                return i
            }
            cursor = stepEnd
        }
        return route.steps.lastIndex.coerceAtLeast(0)
    }
}

internal data class SnapResult(
    val snappedPoint: GeoPoint,
    val distanceFromRouteMeters: Double,
    val segmentIndex: Int,
    val distanceTraveledMeters: Double,
    val distanceRemainingMeters: Double,
)
