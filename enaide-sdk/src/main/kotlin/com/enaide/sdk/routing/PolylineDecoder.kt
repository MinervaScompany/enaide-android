package com.enaide.sdk.routing

import com.enaide.sdk.model.GeoPoint

/**
 * Decoder per il formato Google Encoded Polyline.
 *
 * Valhalla restituisce shape codificate con precisione 6 (1e-6 di grado),
 * mentre il formato originale Google usa precisione 5. Il parametro [precision]
 * permette di gestire entrambi i casi.
 *
 * Algoritmo: https://developers.google.com/maps/documentation/utilities/polylinealgorithm
 *
 * Implementazione pura JVM, senza dipendenze esterne, per essere testabile in unit test.
 */
internal object PolylineDecoder {

    /**
     * Decodifica una stringa polyline in lista di [GeoPoint].
     *
     * @param encoded stringa codificata. Stringa vuota → lista vuota.
     * @param precision 5 per polyline standard Google, 6 per polyline6 di Valhalla/Mapbox.
     */
    fun decode(encoded: String, precision: Int = 6): List<GeoPoint> {
        if (encoded.isEmpty()) return emptyList()

        val factor = Math.pow(10.0, precision.toDouble())
        val points = ArrayList<GeoPoint>(encoded.length / 4)

        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
            lat += decodeSignedVarint(encoded, index).also { index += it.consumed }.value
            lng += decodeSignedVarint(encoded, index).also { index += it.consumed }.value
            points.add(GeoPoint(latitude = lat / factor, longitude = lng / factor))
        }
        return points
    }

    private data class VarintResult(val value: Int, val consumed: Int)

    private fun decodeSignedVarint(s: String, start: Int): VarintResult {
        var result = 0
        var shift = 0
        var i = start
        while (true) {
            require(i < s.length) { "polyline troncata a indice $i" }
            val b = s[i].code - 63
            i++
            result = result or ((b and 0x1f) shl shift)
            shift += 5
            if (b < 0x20) break
        }
        // Bit 0 indica il segno: se 1, valore negativo (complemento a 2 di un right-shift).
        val signed = if (result and 1 != 0) (result shr 1).inv() else (result shr 1)
        return VarintResult(value = signed, consumed = i - start)
    }
}
