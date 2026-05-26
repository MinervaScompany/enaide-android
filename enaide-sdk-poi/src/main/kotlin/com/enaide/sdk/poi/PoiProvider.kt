package com.enaide.sdk.poi

import com.enaide.sdk.model.GeoPoint
import com.enaide.sdk.model.Route

/**
 * Astrazione di un fornitore di **punti di interesse** (POI).
 *
 * Implementata di default da [OverpassPoiProvider] (OpenStreetMap), ma
 * l'interfaccia permette di affiancare/sostituire altri provider (Foursquare,
 * Google Places, un servizio proprietario) senza toccare la UI: come per
 * `RoutingClient` e `GeocodingClient`.
 *
 * Modulo opzionale ed escludibile. Più provider possono coesistere: l'app sceglie
 * quale iniettare, o ne interroga più d'uno e unisce i risultati.
 */
public interface PoiProvider {

    /**
     * Cerca POI di una [category] entro [radiusMeters] da [center].
     *
     * @param limit massimo numero di risultati.
     */
    public suspend fun nearby(
        center: GeoPoint,
        category: PoiCategory,
        radiusMeters: Int = 1500,
        limit: Int = 30,
    ): PoiResult

    /**
     * Cerca POI di una [category] **lungo un percorso**: utile per "distributori
     * sul tragitto", "aree di sosta", ecc. (stile navigatore).
     *
     * @param corridorMeters semiampiezza del corridoio attorno alla polilinea entro
     *   cui considerare un POI "sul percorso".
     */
    public suspend fun alongRoute(
        route: Route,
        category: PoiCategory,
        corridorMeters: Int = 250,
        limit: Int = 30,
    ): PoiResult
}

/**
 * Punto di interesse.
 *
 * @property point coordinate WGS84.
 * @property name nome leggibile (può essere null se OSM non lo fornisce).
 * @property category categoria normalizzata.
 * @property tags tag OSM grezzi (es. `amenity=fuel`, `opening_hours=...`).
 */
public data class Poi(
    public val point: GeoPoint,
    public val name: String?,
    public val category: PoiCategory,
    public val tags: Map<String, String> = emptyMap(),
)

/**
 * Categorie POI normalizzate, ispirate al set di OsmAnd. Ogni categoria mappa su
 * uno o più filtri OSM (`key=value`), usati per costruire la query Overpass.
 */
public enum class PoiCategory(public val osmFilters: List<Pair<String, String>>) {
    FUEL(listOf("amenity" to "fuel")),
    CHARGING(listOf("amenity" to "charging_station")),
    PARKING(listOf("amenity" to "parking")),
    FOOD(listOf("amenity" to "restaurant", "amenity" to "fast_food", "amenity" to "cafe")),
    SUPERMARKET(listOf("shop" to "supermarket")),
    ATM(listOf("amenity" to "atm", "amenity" to "bank")),
    PHARMACY(listOf("amenity" to "pharmacy")),
    HOSPITAL(listOf("amenity" to "hospital")),
    HOTEL(listOf("tourism" to "hotel")),
    TOILETS(listOf("amenity" to "toilets")),
    ATTRACTION(listOf("tourism" to "attraction", "tourism" to "viewpoint")),
}

/** Esito di una ricerca POI. Sealed, come gli altri sottosistemi. */
public sealed class PoiResult {
    public data class Success(public val pois: List<Poi>) : PoiResult()
    public data class Failure(public val error: PoiError) : PoiResult()
}

/** Categorie di errore lato POI. */
public sealed class PoiError {
    public data class NetworkError(public val cause: Throwable) : PoiError()
    public data class ServerError(public val httpStatus: Int, public val body: String?) : PoiError()
    public data class ParseError(public val cause: Throwable) : PoiError()
}
