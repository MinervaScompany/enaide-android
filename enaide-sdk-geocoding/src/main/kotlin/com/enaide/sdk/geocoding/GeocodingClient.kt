package com.enaide.sdk.geocoding

import com.enaide.sdk.model.GeoPoint

/**
 * Astrazione del servizio di geocoding (indirizzo <-> coordinate).
 *
 * Implementata di default da [NominatimGeocodingClient] (OpenStreetMap), ma
 * l'interfaccia permette di sostituirla in test o con un altro provider
 * (Photon, Pelias, geocoder proprietario) senza toccare la UI.
 *
 * Due operazioni speculari:
 *  - [search] *forward geocoding*: da testo libero a una lista di luoghi.
 *  - [reverse] *reverse geocoding*: da una coordinata all'indirizzo piu' vicino.
 */
public interface GeocodingClient {

    /**
     * Forward geocoding: cerca luoghi che corrispondono a [query].
     *
     * @param query testo libero, es. "Duomo, Milano" o "Via Roma 1, Torino".
     * @param limit numero massimo di risultati (coerced 1..50).
     * @return [GeocodeResult.Success] con la lista (eventualmente vuota) o [GeocodeResult.Failure].
     */
    public suspend fun search(query: String, limit: Int = 5): GeocodeResult

    /**
     * Reverse geocoding: trova l'indirizzo piu' vicino a [point].
     *
     * @return [GeocodeResult.Success] con 0 o 1 risultato, o [GeocodeResult.Failure].
     */
    public suspend fun reverse(point: GeoPoint): GeocodeResult
}

/**
 * Un luogo geocodificato, con testo **già formattato** per la UI.
 *
 * @property point coordinate WGS84 del luogo.
 * @property name titolo pulito e breve (nome del luogo, oppure via + civico).
 * @property secondaryText riga secondaria concisa (città/zona); può essere null.
 * @property displayName etichetta completa grezza del provider (per casi avanzati).
 * @property type categoria OSM grezza (es. "city", "house", "road"). Può essere null.
 */
public data class GeocodedPlace(
    public val point: GeoPoint,
    public val name: String,
    public val secondaryText: String? = null,
    public val displayName: String = name,
    public val type: String? = null,
)

/**
 * Esito di una chiamata di geocoding. Sealed class al posto di eccezioni: il
 * chiamante deve gestire entrambi i casi nel `when`.
 */
public sealed class GeocodeResult {
    public data class Success(public val places: List<GeocodedPlace>) : GeocodeResult()
    public data class Failure(public val error: GeocodingError) : GeocodeResult()
}

/** Categorie di errore lato geocoding. Conserva la causa originale per logging. */
public sealed class GeocodingError {
    public data class NetworkError(public val cause: Throwable) : GeocodingError()
    public data class ServerError(public val httpStatus: Int, public val body: String?) : GeocodingError()
    public data class InvalidRequest(public val message: String) : GeocodingError()
    public data class ParseError(public val cause: Throwable) : GeocodingError()
}
