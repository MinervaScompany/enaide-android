package com.enaide.sdk.geocoding

import com.enaide.sdk.EnaideConfig
import com.enaide.sdk.model.GeoPoint
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Implementazione di [GeocodingClient] basata su Nominatim (OpenStreetMap).
 *
 * Endpoint default: istanza pubblica `https://nominatim.openstreetmap.org`
 * (vedi [EnaideConfig.nominatimBaseUrl]). Per produzione self-hostala o usa un
 * provider che rispetti la [usage policy](https://operations.osmfoundation.org/policies/nominatim/):
 *  - max ~1 richiesta/secondo,
 *  - User-Agent identificativo obbligatorio (preso da [EnaideConfig.userAgent]),
 *  - attribuzione OSM nella UI.
 *
 * Il client HTTP e' ownato dall'istanza salvo iniezione: chiudilo via [close].
 */
public class NominatimGeocodingClient internal constructor(
    private val config: EnaideConfig,
    httpClient: HttpClient?,
) : GeocodingClient, AutoCloseable {

    /**
     * Costruttore pubblico: crea un client con HTTP engine OkHttp interno.
     * L'overload che inietta un [HttpClient] e' interno (per i test dell'SDK),
     * cosi' l'API pubblica non espone Ktor ai consumer.
     */
    public constructor(config: EnaideConfig) : this(config, null)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val client: HttpClient = httpClient ?: HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(this@NominatimGeocodingClient.json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeout.inWholeMilliseconds
            connectTimeoutMillis = config.requestTimeout.inWholeMilliseconds
            socketTimeoutMillis = config.requestTimeout.inWholeMilliseconds
        }
        defaultRequest {
            // Nominatim RIFIUTA richieste senza User-Agent identificativo.
            header(HttpHeaders.UserAgent, config.userAgent)
            accept(ContentType.Application.Json)
        }
    }

    override suspend fun search(query: String, limit: Int): GeocodeResult {
        if (query.isBlank()) {
            return GeocodeResult.Failure(GeocodingError.InvalidRequest("query vuota"))
        }

        val response: HttpResponse = try {
            client.get {
                url {
                    takeFrom(config.nominatimBaseUrl)
                    appendPathSegments("search")
                }
                parameter("q", query)
                parameter("format", "jsonv2")
                parameter("limit", limit.coerceIn(1, 50))
                parameter("addressdetails", 0)
            }
        } catch (t: Throwable) {
            return GeocodeResult.Failure(GeocodingError.NetworkError(t))
        }

        if (!response.status.isSuccess()) {
            return GeocodeResult.Failure(serverError(response))
        }

        return try {
            val places: List<NominatimPlace> = response.body()
            GeocodeResult.Success(places.mapNotNull { it.toPlaceOrNull() })
        } catch (t: Throwable) {
            GeocodeResult.Failure(GeocodingError.ParseError(t))
        }
    }

    override suspend fun reverse(point: GeoPoint): GeocodeResult {
        val response: HttpResponse = try {
            client.get {
                url {
                    takeFrom(config.nominatimBaseUrl)
                    appendPathSegments("reverse")
                }
                parameter("lat", point.latitude)
                parameter("lon", point.longitude)
                parameter("format", "jsonv2")
                parameter("addressdetails", 0)
            }
        } catch (t: Throwable) {
            return GeocodeResult.Failure(GeocodingError.NetworkError(t))
        }

        if (!response.status.isSuccess()) {
            return GeocodeResult.Failure(serverError(response))
        }

        // /reverse ritorna un singolo oggetto: un place valido oppure { "error": ... }.
        val raw = runCatching { response.bodyAsText() }.getOrElse { t ->
            return GeocodeResult.Failure(GeocodingError.NetworkError(t))
        }
        return try {
            val place = json.decodeFromString(NominatimPlace.serializer(), raw).toPlaceOrNull()
            if (place != null) {
                GeocodeResult.Success(listOf(place))
            } else {
                // Nessun risultato (campi lat/lon assenti o oggetto errore): lista vuota.
                GeocodeResult.Success(emptyList())
            }
        } catch (t: Throwable) {
            GeocodeResult.Failure(GeocodingError.ParseError(t))
        }
    }

    private suspend fun serverError(response: HttpResponse): GeocodingError {
        val body = runCatching { response.bodyAsText() }.getOrNull()
        return GeocodingError.ServerError(response.status.value, body)
    }

    /** Converte un DTO in [GeocodedPlace], scartandolo se mancano coordinate valide. */
    private fun NominatimPlace.toPlaceOrNull(): GeocodedPlace? {
        val latVal = lat?.toDoubleOrNull() ?: return null
        val lonVal = lon?.toDoubleOrNull() ?: return null
        val name = displayName?.takeIf { it.isNotBlank() } ?: return null
        val point = runCatching { GeoPoint(latVal, lonVal) }.getOrNull() ?: return null
        return GeocodedPlace(point = point, displayName = name, type = type ?: addressType)
    }

    override fun close() {
        client.close()
    }
}
