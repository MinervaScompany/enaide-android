package com.enaide.sdk.internal

import com.enaide.sdk.EnaideConfig
import com.enaide.sdk.EnaideNavigator
import com.enaide.sdk.core.NavigationController
import com.enaide.sdk.model.GeoPoint
import com.enaide.sdk.model.NavigationCommand
import com.enaide.sdk.model.NavigationError
import com.enaide.sdk.model.NavigationEvent
import com.enaide.sdk.model.NavigationState
import com.enaide.sdk.model.Route
import com.enaide.sdk.model.RouteOptions
import com.enaide.sdk.model.TransportProfile
import com.enaide.sdk.model.UserLocation
import com.enaide.sdk.routing.RouteResult
import com.enaide.sdk.routing.RoutingClient
import com.enaide.sdk.routing.ValhallaRoutingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Implementazione del [EnaideNavigator] pubblico.
 *
 * Macchina **comando → stato/eventi**: ogni mutazione passa per [dispatch], che
 * serializza tutto su un [Mutex] (la state machine resta single-threaded anche
 * se l'app chiama da thread diversi: UI + worker GPS + comandi esterni).
 *
 * Tiene traccia del "piano di viaggio" corrente (waypoints, profilo, opzioni) per
 * poter ricalcolare in risposta ai comandi event-centric (reroute, cambio
 * destinazione/tappa, cambio mezzo in marcia).
 */
internal class EnaideNavigatorImpl(
    private val config: EnaideConfig,
    private val routingClient: RoutingClient = ValhallaRoutingClient(config),
) : EnaideNavigator {

    private val controller = NavigationController(config)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Piano di viaggio corrente: serve a ricalcolare quando arriva un comando.
    private var waypoints: List<GeoPoint> = emptyList()
    private var profile: TransportProfile = config.defaultProfile
    private var options: RouteOptions = RouteOptions.Default

    override val state: StateFlow<NavigationState>
        get() = controller.state

    override val events: kotlinx.coroutines.flow.SharedFlow<NavigationEvent>
        get() = controller.events

    override suspend fun computeRoute(
        waypoints: List<GeoPoint>,
        profile: TransportProfile?,
        options: RouteOptions,
    ): RouteResult = withContext(Dispatchers.IO) {
        val effectiveProfile = profile ?: config.defaultProfile
        // Ricorda profilo/opzioni (sotto lock) così lo start/reroute successivo usa
        // soglia off-route e costing corretti. computeRoute resta una query: la
        // mutazione di stato è esplicita e serializzata.
        mutex.withLock {
            this@EnaideNavigatorImpl.profile = effectiveProfile
            this@EnaideNavigatorImpl.options = options
        }
        routingClient.computeRoute(waypoints, effectiveProfile, options)
    }

    override suspend fun fetchSpeedLimits(route: Route): Route = withContext(Dispatchers.IO) {
        val p = mutex.withLock { profile }
        routingClient.enrichWithSpeedLimits(route, p)
    }

    override fun dispatch(command: NavigationCommand) {
        scope.launch { handle(command) }
    }

    // Wrapper retrocompatibili: tutto passa per dispatch.
    override fun start(route: Route): Unit = dispatch(NavigationCommand.Start(route))
    override fun updateLocation(location: UserLocation): Unit =
        dispatch(NavigationCommand.UpdateLocation(location))
    override fun stop(): Unit = dispatch(NavigationCommand.Stop)

    override fun shutdown() {
        scope.cancel()
        (routingClient as? AutoCloseable)?.let { runCatching { it.close() } }
    }

    private suspend fun handle(command: NavigationCommand) {
        when (command) {
            is NavigationCommand.Start -> mutex.withLock {
                rememberPlanFrom(command.route)
                controller.start(command.route, profile)
            }

            is NavigationCommand.ReplaceRoute -> mutex.withLock {
                rememberPlanFrom(command.route)
                controller.start(command.route, profile)
            }

            is NavigationCommand.UpdateLocation -> mutex.withLock {
                controller.onLocation(command.location)
            }

            NavigationCommand.Stop -> mutex.withLock { controller.stop() }

            NavigationCommand.AdvanceStep -> mutex.withLock { controller.advanceStepManually() }

            NavigationCommand.Recalculate ->
                recalculate { it }

            is NavigationCommand.SetDestination ->
                recalculate { waypointsWithNewDestination(command.destination) }

            is NavigationCommand.AddWaypoint ->
                recalculate { waypointsWithInserted(command.waypoint, command.index) }

            is NavigationCommand.UpdateProfile -> {
                mutex.withLock {
                    command.profile?.let { profile = it }
                    command.options?.let { options = it }
                }
                recalculate { it }
            }
        }
    }

    /**
     * Ricalcola il percorso. [transform] riceve i waypoint correnti (sotto lock) e
     * ritorna quelli da usare. Snapshotta profilo/opzioni/origine sotto lock prima
     * della rete, evitando torn-read se nel frattempo arriva un altro comando.
     */
    private suspend fun recalculate(transform: (List<GeoPoint>) -> List<GeoPoint>) {
        // Snapshot atomico dello stato del piano.
        data class Plan(val points: List<GeoPoint>, val profile: TransportProfile, val options: RouteOptions, val origin: GeoPoint?)
        val plan = mutex.withLock {
            Plan(transform(waypoints), profile, options, currentPositionLocked())
        }
        if (plan.points.size < 2) return
        val origin = plan.origin ?: plan.points.first()
        val effective = listOf(origin) + plan.points.drop(1)

        mutex.withLock { controller.enterRerouting(origin) }

        val result = withContext(Dispatchers.IO) {
            routingClient.computeRoute(effective, plan.profile, plan.options)
        }
        mutex.withLock {
            when (result) {
                is RouteResult.Success -> {
                    rememberPlanFrom(result.route)
                    controller.start(result.route, plan.profile)
                }
                is RouteResult.Failure -> {
                    controller.fail(NavigationError.RoutingFailed(result.error.toString()))
                }
            }
        }
    }

    /**
     * Posizione corrente nota (snapped) se in navigazione. Va chiamata SOLO
     * mentre si tiene [mutex] (legge un `StateFlow`, sicuro, ma il nome rende
     * esplicito il contratto col resto dello stato del piano).
     */
    private fun currentPositionLocked(): GeoPoint? =
        (controller.state.value as? NavigationState.Navigating)?.progress?.snappedLocation

    /** Memorizza waypoints (origine→destinazione) dal route attivo. Chiamato sotto lock. */
    private fun rememberPlanFrom(route: Route) {
        if (route.waypoints.size >= 2) waypoints = route.waypoints
    }

    private fun waypointsWithNewDestination(dest: GeoPoint): List<GeoPoint> {
        val origin = currentPositionLocked() ?: waypoints.firstOrNull() ?: dest
        return listOf(origin, dest)
    }

    private fun waypointsWithInserted(wp: GeoPoint, index: Int?): List<GeoPoint> {
        if (waypoints.size < 2) return waypoints
        val mutable = waypoints.toMutableList()
        val insertAt = index ?: (mutable.size - 1) // prima della destinazione finale
        mutable.add(insertAt.coerceIn(1, mutable.size - 1), wp)
        return mutable
    }
}
