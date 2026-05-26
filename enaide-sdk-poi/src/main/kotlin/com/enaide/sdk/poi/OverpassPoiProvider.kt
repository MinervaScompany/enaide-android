package com.enaide.sdk.poi

import com.enaide.sdk.EnaideConfig
import com.enaide.sdk.core.GeoUtils
import com.enaide.sdk.model.GeoPoint
import com.enaide.sdk.model.Route
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Implementazione di [PoiProvider] basata su **Overpass API** (OpenStreetMap).
 *
 * Costruisce query Overpass QL dai filtri OSM di [PoiCategory]. Endpoint di
 * default: istanza pubblica `overpass-api.de` (vedi [EnaideConfig.overpassBaseUrl]),
 * soggetta a fair-use: per produzione self-hostala.
 *
 * `alongRoute` interroga il bounding box del percorso e poi filtra lato client i
 * POI che cadono entro il corridoio attorno alla polilinea (usando la proiezione
 * su segmento di [GeoUtils]).
 */
public class OverpassPoiProvider internal constructor(
    private val config: EnaideConfig,
    httpClient: HttpClient?,
) : PoiProvider {

    /** Costruttore pubblico: HTTP engine OkHttp interno (Ktor non esposto). */
    public constructor(config: EnaideConfig) : this(config, null)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: HttpClient = httpClient ?: HttpClient(OkHttp) {
        install(ContentNegotiation) { json(this@OverpassPoiProvider.json) }
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeout.inWholeMilliseconds
            connectTimeoutMillis = config.requestTimeout.inWholeMilliseconds
            socketTimeoutMillis = config.requestTimeout.inWholeMilliseconds
        }
        defaultRequest { header(HttpHeaders.UserAgent, config.userAgent) }
    }

    override suspend fun nearby(
        center: GeoPoint,
        category: PoiCategory,
        radiusMeters: Int,
        limit: Int,
    ): PoiResult {
        val filters = category.osmFilters.joinToString("") { (k, v) ->
            """node["$k"="$v"](around:$radiusMeters,${center.latitude},${center.longitude});"""
        }
        val query = "[out:json][timeout:25];($filters);out center $limit;"
        return runQuery(query, category)
    }

    override suspend fun alongRoute(
        route: Route,
        category: PoiCategory,
        corridorMeters: Int,
        limit: Int,
    ): PoiResult {
        if (route.geometry.size < 2) return PoiResult.Success(emptyList())

        // Bounding box del percorso, allargato del corridoio.
        val lats = route.geometry.map { it.latitude }
        val lons = route.geometry.map { it.longitude }
        val pad = corridorMeters / 111_195.0
        val south = (lats.min() - pad)
        val north = (lats.max() + pad)
        val west = (lons.min() - pad)
        val east = (lons.max() + pad)
        val bbox = "$south,$west,$north,$east"

        val filters = category.osmFilters.joinToString("") { (k, v) -> """node["$k"="$v"]($bbox);""" }
        val query = "[out:json][timeout:25];($filters);out center ${limit * 6};"

        return when (val res = runQuery(query, category)) {
            is PoiResult.Failure -> res
            is PoiResult.Success -> PoiResult.Success(
                res.pois.filter { isWithinCorridor(it.point, route, corridorMeters.toDouble()) }.take(limit)
            )
        }
    }

    private suspend fun runQuery(query: String, category: PoiCategory): PoiResult {
        val response: HttpResponse = try {
            client.post {
                url {
                    takeFrom(config.overpassBaseUrl)
                    appendPathSegments("api", "interpreter")
                }
                setBody("data=" + query)
            }
        } catch (t: Throwable) {
            return PoiResult.Failure(PoiError.NetworkError(t))
        }
        if (!response.status.isSuccess()) {
            val body = runCatching { response.bodyAsText() }.getOrNull()
            return PoiResult.Failure(PoiError.ServerError(response.status.value, body))
        }
        return try {
            val parsed: OverpassResponse = response.body()
            PoiResult.Success(parsed.elements.mapNotNull { it.toPoi(category) })
        } catch (t: Throwable) {
            PoiResult.Failure(PoiError.ParseError(t))
        }
    }

    private fun OverpassElement.toPoi(category: PoiCategory): Poi? {
        val (lat, lon) = latLon() ?: return null
        val point = runCatching { GeoPoint(lat, lon) }.getOrNull() ?: return null
        return Poi(point = point, name = tags["name"], category = category, tags = tags)
    }

    /** True se [p] cade entro [maxMeters] dalla polilinea del percorso. */
    private fun isWithinCorridor(p: GeoPoint, route: Route, maxMeters: Double): Boolean {
        val geo = route.geometry
        for (i in 0 until geo.size - 1) {
            val (_, dist, _) = GeoUtils.projectOnSegment(p, geo[i], geo[i + 1])
            if (dist <= maxMeters) return true
        }
        return false
    }
}
