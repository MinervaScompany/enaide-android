package com.enaide.sdk.routing

import com.enaide.sdk.model.GeoPoint
import com.enaide.sdk.model.Route
import com.enaide.sdk.model.RouteOptions
import com.enaide.sdk.model.TransportProfile

/**
 * Astrazione del motore di routing. Implementata da [ValhallaRoutingClient]
 * ma l'interfaccia consente di sostituirla in test e in futuro con un client
 * diverso (es. OSRM) senza toccare il core di navigazione.
 */
public interface RoutingClient {
    /**
     * Calcola un percorso tra i [waypoints] usando il [profile] richiesto.
     *
     * @param waypoints lista ordinata di almeno due punti: origine, intermedi, destinazione.
     * @param profile profilo di trasporto.
     * @param options opzioni di costing/alternative/dimensioni veicolo.
     * @return [RouteResult.Success] o [RouteResult.Failure].
     */
    public suspend fun computeRoute(
        waypoints: List<GeoPoint>,
        profile: TransportProfile,
        options: RouteOptions = RouteOptions.Default,
    ): RouteResult

    /**
     * Arricchisce [route] con i limiti di velocità per step (`RouteStep.speedLimitKmh`),
     * quando il backend li espone. Operazione di rete **separata e opzionale**: il
     * routing base resta veloce. Default: ritorna il route invariato.
     */
    public suspend fun enrichWithSpeedLimits(route: Route, profile: TransportProfile): Route = route
}

/**
 * Esito del calcolo di un percorso. Sealed class al posto di eccezioni così
 * il chiamante è forzato a gestire entrambi i casi nel `when`.
 *
 * Il campo [Success.alternatives] è non vuoto solo se nella richiesta
 * [com.enaide.sdk.model.RouteOptions.numberOfAlternatives] era > 0 e il server
 * è stato in grado di calcolare alternative.
 */
public sealed class RouteResult {
    public data class Success(
        public val route: Route,
        public val alternatives: List<Route> = emptyList(),
    ) : RouteResult()
    public data class Failure(public val error: RoutingError) : RouteResult()
}

/** Categorie di errore lato routing. Manteniamo il dettaglio della causa originale per logging. */
public sealed class RoutingError {
    public data class NetworkError(public val cause: Throwable) : RoutingError()
    public data class ServerError(public val httpStatus: Int, public val body: String?) : RoutingError()
    public data class NoRouteFound(public val message: String) : RoutingError()
    public data class InvalidRequest(public val message: String) : RoutingError()
    public data class ParseError(public val cause: Throwable) : RoutingError()
}
