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
        routingClient.computeRoute(waypoints, effectiveProfile, options)
    }

    override fun dispatch(command: NavigationCommand) {
        scope.launch { handle(command) }
    }

    // Wrapper retrocompatibili: tutto passa per dispatch.
    override fun start(route: Route): Unit = dispatch(NavigationCommand.Start(route))
    override fun updateLocation(location: UserLocation): Unit =
        dispatch(NavigationCommand.UpdateLocation(location))
    override fun stop(): Unit = dispatch(NavigationCommand.Stop)

    private suspend fun handle(command: NavigationCommand) {
        when (command) {
            is NavigationCommand.Start -> mutex.withLock {
                rememberPlanFrom(command.route)
                controller.start(command.route)
            }

            is NavigationCommand.ReplaceRoute -> mutex.withLock {
                rememberPlanFrom(command.route)
                controller.start(command.route)
            }

            is NavigationCommand.UpdateLocation -> mutex.withLock {
                controller.onLocation(command.location)
            }

            NavigationCommand.Stop -> mutex.withLock { controller.stop() }

            NavigationCommand.AdvanceStep -> mutex.withLock { controller.advanceStepManually() }

            NavigationCommand.Recalculate ->
                recalculate(waypoints)

            is NavigationCommand.SetDestination ->
                recalculate(waypointsWithNewDestination(command.destination))

            is NavigationCommand.AddWaypoint ->
                recalculate(waypointsWithInserted(command.waypoint, command.index))

            is NavigationCommand.UpdateProfile -> {
                command.profile?.let { profile = it }
                command.options?.let { options = it }
                recalculate(waypoints)
            }
        }
    }

    /**
     * Ricalcola il percorso lungo [points] col profilo/opzioni correnti, partendo
     * dalla posizione corrente come origine. Emette Rerouting → Navigating (o Failed).
     */
    private suspend fun recalculate(points: List<GeoPoint>) {
        if (points.size < 2) return
        val origin = currentPosition() ?: points.first()
        val effective = listOf(origin) + points.drop(1)

        mutex.withLock { controller.enterRerouting(origin) }

        val result = withContext(Dispatchers.IO) {
            routingClient.computeRoute(effective, profile, options)
        }
        mutex.withLock {
            when (result) {
                is RouteResult.Success -> {
                    rememberPlanFrom(result.route)
                    controller.start(result.route)
                }
                is RouteResult.Failure -> {
                    controller.fail(NavigationError.RoutingFailed(result.error.toString()))
                }
            }
        }
    }

    /** Posizione corrente nota (snapped) se in navigazione, altrimenti null. */
    private fun currentPosition(): GeoPoint? =
        (controller.state.value as? NavigationState.Navigating)?.progress?.snappedLocation

    /** Memorizza waypoints (origine→destinazione) dal route attivo. */
    private fun rememberPlanFrom(route: Route) {
        if (route.waypoints.size >= 2) waypoints = route.waypoints
    }

    private fun waypointsWithNewDestination(dest: GeoPoint): List<GeoPoint> {
        val origin = currentPosition() ?: waypoints.firstOrNull() ?: dest
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
