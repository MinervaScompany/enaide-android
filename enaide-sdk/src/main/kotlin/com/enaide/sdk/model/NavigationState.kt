package com.enaide.sdk.model

/**
 * Stato osservabile della navigazione, emesso come [kotlinx.coroutines.flow.StateFlow]
 * dal [com.enaide.sdk.EnaideNavigator].
 *
 * Sealed class: un client esaustivo nel `when` ottiene un warning a compile-time
 * quando aggiungiamo nuovi casi (es. `Rerouting`). Evita di passare il branch a `else`.
 */
public sealed class NavigationState {

    /** Nessuna navigazione attiva. */
    public data object Idle : NavigationState()

    /** Navigazione in corso lungo un percorso. */
    public data class Navigating(
        public val route: Route,
        public val progress: RouteProgress,
        public val currentVisualInstruction: VisualInstruction? = null,
        public val pendingSpokenInstruction: SpokenInstruction? = null,
        public val deviation: Deviation = Deviation.OnRoute,
    ) : NavigationState()

    /** Ricalcolo percorso in corso (deviazione confermata). */
    public data class Rerouting(
        public val previousRoute: Route,
        public val triggeredAt: GeoPoint,
    ) : NavigationState()

    /** Utente arrivato a destinazione. */
    public data class Arrived(public val route: Route) : NavigationState()

    /** Errore non recuperabile. La navigazione è terminata. */
    public data class Failed(public val reason: NavigationError) : NavigationState()
}

/**
 * Stato di aderenza al percorso.
 */
public sealed class Deviation {
    public data object OnRoute : Deviation()
    public data class OffRoute(public val distanceFromRouteMeters: Double) : Deviation()
}

/** Cause possibili di terminazione anomala. */
public sealed class NavigationError {
    public data class RoutingFailed(public val message: String) : NavigationError()
    public data class LocationUnavailable(public val sinceMillis: Long) : NavigationError()
    public data class Other(public val message: String, public val cause: Throwable? = null) : NavigationError()
}
