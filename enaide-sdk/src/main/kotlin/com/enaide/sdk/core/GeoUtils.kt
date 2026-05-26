package com.enaide.sdk.core

import com.enaide.sdk.model.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Funzioni geodetiche di utilità: distanza, bearing, proiezione su segmento.
 *
 * Tutte le formule lavorano in coordinate WGS84. Per distanze brevi (segmenti <1km
 * tipici nella navigazione) la proiezione planare locale dà errori sub-metro,
 * sotto la risoluzione del GPS consumer (~3-5m). Per distanze lunghe usiamo
 * Haversine.
 */
public object GeoUtils {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * Distanza Haversine fra due punti, in metri.
     *
     * Errore < 0.5% rispetto alla geodetica vera (ignora schiacciamento ellissoide),
     * ampiamente sufficiente per navigazione.
     */
    public fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)

        val sinDLat = sin(dLat / 2)
        val sinDLon = sin(dLon / 2)
        val h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceAtMost(1.0)))
    }

    /**
     * Bearing iniziale da [a] a [b] in gradi (0 = nord, 90 = est, in senso orario).
     */
    public fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360.0) % 360.0
    }

    /**
     * Proietta il punto [p] sul segmento [a]-[b], in un sistema cartesiano locale
     * centrato a metà del segmento. Approssimazione valida per segmenti brevi (<1km).
     *
     * @return triple (puntoProiettato, distanzaInMetri, tNormalizzato) dove
     *   `t` ∈ [0,1] indica la posizione del progetto sul segmento (0 = a, 1 = b).
     *   Se la proiezione cade fuori dal segmento, t viene clampato e il punto
     *   restituito è l'estremo più vicino.
     */
    public fun projectOnSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Triple<GeoPoint, Double, Double> {
        // Sistema di riferimento locale equirettangolare centrato in 'a'.
        val cosLat = cos(Math.toRadians(a.latitude))
        val ax = 0.0
        val ay = 0.0
        val bx = (b.longitude - a.longitude) * cosLat
        val by = b.latitude - a.latitude
        val px = (p.longitude - a.longitude) * cosLat
        val py = p.latitude - a.latitude

        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy

        val t = if (len2 == 0.0) 0.0 else ((px - ax) * dx + (py - ay) * dy) / len2
        val tClamped = t.coerceIn(0.0, 1.0)

        val projX = ax + tClamped * dx
        val projY = ay + tClamped * dy

        val projected = GeoPoint(
            latitude = a.latitude + projY,
            longitude = a.longitude + projX / cosLat,
        )
        val distance = distanceMeters(p, projected)
        return Triple(projected, distance, tClamped)
    }

    /**
     * Distanza totale di una polilinea, in metri.
     */
    public fun polylineLengthMeters(points: List<GeoPoint>): Double {
        if (points.size < 2) return 0.0
        var sum = 0.0
        for (i in 1 until points.size) {
            sum += distanceMeters(points[i - 1], points[i])
        }
        return sum
    }
}
