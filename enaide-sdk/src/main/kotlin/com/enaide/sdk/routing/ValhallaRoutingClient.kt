package com.enaide.sdk.routing

import com.enaide.sdk.EnaideConfig
import com.enaide.sdk.model.GeoPoint
import com.enaide.sdk.model.RouteOptions
import com.enaide.sdk.model.TransportProfile
import com.enaide.sdk.model.VehicleDimensions
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Implementazione di [RoutingClient] che parla con un server Valhalla via HTTP.
 *
 * In fase iniziale puntiamo all'endpoint pubblico FOSSGIS configurato in
 * [EnaideConfig.routingBaseUrl]. Quando avrai il tuo self-hosted basterà
 * cambiare la stringa nel `EnaideConfig`.
 *
 * Il client HTTP è ownato dall'istanza: chiudilo via [close] quando l'SDK termina.
 */
public class ValhallaRoutingClient(
    private val config: EnaideConfig,
    httpClient: HttpClient? = null,
) : RoutingClient, AutoCloseable {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        // Serializza anche i campi uguali al loro default (es. language="it-IT"):
        // senza questo, kotlinx.serialization li OMETTE e Valhalla ripiega su EN.
        encodeDefaults = true
    }

    private val client: HttpClient = httpClient ?: HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(this@ValhallaRoutingClient.json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeout.inWholeMilliseconds
            connectTimeoutMillis = config.requestTimeout.inWholeMilliseconds
            socketTimeoutMillis = config.requestTimeout.inWholeMilliseconds
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, config.userAgent)
            accept(ContentType.Application.Json)
        }
    }

    override suspend fun computeRoute(
        waypoints: List<GeoPoint>,
        profile: TransportProfile,
        options: RouteOptions,
    ): RouteResult {
        if (waypoints.size < 2) {
            return RouteResult.Failure(
                RoutingError.InvalidRequest("servono almeno 2 waypoint, ricevuti ${waypoints.size}")
            )
        }

        val request = ValhallaRouteRequest(
            locations = waypoints.map { ValhallaLocation(lat = it.latitude, lon = it.longitude) },
            costing = profile.valhallaCosting,
            costingOptions = buildCostingOptions(profile, options),
            directionsOptions = ValhallaDirectionsOptions(
                units = options.units.valhallaValue,
                language = options.language,
            ),
            units = options.units.valhallaValue,
            alternates = options.numberOfAlternatives.coerceIn(0, 2),
        )

        val response: HttpResponse = try {
            client.post {
                url {
                    takeFrom(config.routingBaseUrl)
                    appendPathSegments("route")
                }
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        } catch (t: Throwable) {
            return RouteResult.Failure(RoutingError.NetworkError(t))
        }

        if (!response.status.isSuccess()) {
            val body = runCatching { response.bodyAsText() }.getOrNull()
            return RouteResult.Failure(
                if (response.status.value == 400 && body?.contains("No path") == true) {
                    RoutingError.NoRouteFound(body)
                } else {
                    RoutingError.ServerError(response.status.value, body)
                }
            )
        }

        return try {
            val parsed: ValhallaRouteResponse = response.body()
            RouteResult.Success(
                route = ValhallaMapper.toRoute(parsed),
                alternatives = ValhallaMapper.toAlternatives(parsed),
            )
        } catch (t: Throwable) {
            RouteResult.Failure(RoutingError.ParseError(t))
        }
    }

    /**
     * Costruisce il JSON `costing_options` da iniettare nella richiesta.
     * Valhalla annida i parametri sotto il nome del costing (auto, truck, ...).
     * Ritorna `null` se non c'è nulla da impostare (mantiene la request più snella).
     */
    private fun buildCostingOptions(profile: TransportProfile, options: RouteOptions): JsonObject? {
        val needsAvoidance = options.avoidTolls || options.avoidHighways || options.avoidFerries
        val needsDimensions = profile == TransportProfile.TRUCK && options.vehicleDimensions != null
        if (!needsAvoidance && !needsDimensions) return null

        val perProfile = buildJsonObject {
            if (options.avoidTolls) put("use_tolls", 0.0)
            if (options.avoidHighways) put("use_highways", 0.0)
            if (options.avoidFerries) put("use_ferry", 0.0)
            if (needsDimensions) {
                val d = options.vehicleDimensions ?: VehicleDimensions()
                d.heightMeters?.let { put("height", it) }
                d.widthMeters?.let { put("width", it) }
                d.lengthMeters?.let { put("length", it) }
                // Valhalla vuole peso in tonnellate metriche, noi accettiamo kg.
                d.weightKg?.let { put("weight", it / 1000.0) }
                d.axleLoadKg?.let { put("axle_load", it / 1000.0) }
                put("hazmat", d.hazmat)
            }
        }

        return buildJsonObject {
            put(profile.valhallaCosting, perProfile)
        }
    }

    override fun close() {
        client.close()
    }
}
