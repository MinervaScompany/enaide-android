package com.enaide.sdk.core

import com.enaide.sdk.EnaideConfig
import com.enaide.sdk.model.Deviation
import com.enaide.sdk.model.NavigationEvent
import com.enaide.sdk.model.NavigationState
import com.enaide.sdk.model.Route
import com.enaide.sdk.model.RouteProgress
import com.enaide.sdk.model.UserLocation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State machine che lega snapper + deviation detector + instruction trigger.
 *
 * Stateful e *non* thread-safe: assume di essere invocato da un singolo
 * dispatcher coroutine. L'`EnaideNavigatorImpl` esterno serializza le chiamate.
 *
 * Eventi accettati:
 *  - [start] avvia la navigazione lungo un nuovo route.
 *  - [onLocation] processa un fix utente.
 *  - [stop] termina.
 *
 * Lo stato derivato è esposto come [state].
 */
internal class NavigationController(private val config: EnaideConfig) {

    private val _state = MutableStateFlow<NavigationState>(NavigationState.Idle)
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    // Bus di eventi discreti. Buffer ampio + DROP_OLDEST: i consumer lenti non
    // bloccano il motore di navigazione (back-pressure non desiderata qui).
    private val _events = MutableSharedFlow<NavigationEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<NavigationEvent> = _events.asSharedFlow()

    private var activeRoute: Route? = null
    private var activeProfile: com.enaide.sdk.model.TransportProfile = com.enaide.sdk.model.TransportProfile.AUTO
    private var snapper: LocationSnapper? = null
    private var deviationDetector: DeviationDetector? = null
    private var instructionTrigger: InstructionTrigger? = null
    private var lastFixTimestamp: Long = 0L
    private var lastStepIndex: Int = -1

    fun start(route: Route, profile: com.enaide.sdk.model.TransportProfile = com.enaide.sdk.model.TransportProfile.AUTO) {
        activeRoute = route
        activeProfile = profile
        snapper = LocationSnapper(route)
        deviationDetector = DeviationDetector(
            // Soglia calibrata sul profilo: più larga a piedi (vedi EnaideConfig).
            thresholdMeters = config.offRouteThresholdFor(profile),
            confirmationCount = config.offRouteConfirmationCount,
        )
        instructionTrigger = InstructionTrigger()
        lastStepIndex = 0
        _state.value = NavigationState.Navigating(
            route = route,
            progress = RouteProgress(
                currentStepIndex = 0,
                distanceAlongStepMeters = 0.0,
                distanceToNextManeuverMeters = route.steps.firstOrNull()?.distanceMeters ?: 0.0,
                distanceTraveledMeters = 0.0,
                distanceRemainingMeters = route.distanceMeters,
                durationRemainingSeconds = route.durationSeconds,
                snappedLocation = route.geometry.firstOrNull() ?: route.waypoints.first(),
            ),
        )
        _events.tryEmit(NavigationEvent.Started(route))
    }

    fun stop() {
        val wasActive = activeRoute != null
        activeRoute = null
        snapper = null
        deviationDetector = null
        instructionTrigger = null
        lastStepIndex = -1
        _state.value = NavigationState.Idle
        if (wasActive) _events.tryEmit(NavigationEvent.Stopped)
    }

    /**
     * Entra nello stato di ricalcolo (es. su comando di reroute esterno). Lo stato
     * passa a [NavigationState.Rerouting]; il wrapper poi chiamerà [start] col nuovo
     * percorso o [fail] se il routing non riesce.
     */
    fun enterRerouting(at: com.enaide.sdk.model.GeoPoint) {
        val route = activeRoute ?: return
        _state.value = NavigationState.Rerouting(previousRoute = route, triggeredAt = at)
    }

    /**
     * Avanzamento **manuale** allo step successivo, senza GPS (navigazione
     * "GPS-less", utile a piedi: l'utente conferma di aver raggiunto la prossima
     * manovra). Posiziona il puntatore all'inizio dello step successivo, aggiorna
     * progresso ed eventi. All'ultimo step dichiara l'arrivo.
     */
    fun advanceStepManually() {
        val route = activeRoute ?: return
        val current = (_state.value as? NavigationState.Navigating)?.progress?.currentStepIndex ?: return
        val next = current + 1

        if (next > route.steps.lastIndex) {
            _state.value = NavigationState.Arrived(route)
            _events.tryEmit(NavigationEvent.Arrived(route))
            return
        }

        lastStepIndex = next
        val step = route.steps[next]
        // Distanza cumulata fino all'INIZIO dello step `next`.
        var traveled = 0.0
        for (i in 0 until next) traveled += route.steps[i].distanceMeters
        val remaining = (route.distanceMeters - traveled).coerceAtLeast(0.0)
        val durationRemaining = if (route.distanceMeters > 0)
            route.durationSeconds * (remaining / route.distanceMeters) else 0.0

        _events.tryEmit(NavigationEvent.StepAdvanced(next, step))
        _state.value = NavigationState.Navigating(
            route = route,
            progress = RouteProgress(
                currentStepIndex = next,
                distanceAlongStepMeters = 0.0,
                distanceToNextManeuverMeters = step.distanceMeters,
                distanceTraveledMeters = traveled,
                distanceRemainingMeters = remaining,
                durationRemainingSeconds = durationRemaining,
                snappedLocation = step.geometry.firstOrNull() ?: route.geometry.first(),
            ),
            currentVisualInstruction = step.visualInstructions.firstOrNull(),
            pendingSpokenInstruction = step.spokenInstructions.firstOrNull(),
        )
    }

    /** Termina con errore non recuperabile. */
    fun fail(reason: com.enaide.sdk.model.NavigationError) {
        activeRoute = null
        snapper = null
        deviationDetector = null
        instructionTrigger = null
        lastStepIndex = -1
        _state.value = NavigationState.Failed(reason)
    }

    fun onLocation(loc: UserLocation): ControllerOutcome {
        val route = activeRoute ?: return ControllerOutcome.Ignored
        val snapper = snapper ?: return ControllerOutcome.Ignored
        val deviationDetector = deviationDetector ?: return ControllerOutcome.Ignored
        val instructionTrigger = instructionTrigger ?: return ControllerOutcome.Ignored

        lastFixTimestamp = loc.timestampEpochMillis

        val snap = snapper.snap(loc.point)
        val stepIndex = snapper.stepIndexForGeometryIndex(snap.segmentIndex)
        val currentStep = route.steps[stepIndex]

        // Evento pubblico: avanzamento di step (manovra completata).
        if (stepIndex != lastStepIndex) {
            lastStepIndex = stepIndex
            _events.tryEmit(NavigationEvent.StepAdvanced(stepIndex, currentStep))
        }

        // Quanto si è progrediti dentro lo step corrente:
        val distanceToEndOfStep = computeDistanceToEndOfStep(route, stepIndex, snap.distanceTraveledMeters)
        val distanceAlongStep = (currentStep.distanceMeters - distanceToEndOfStep).coerceAtLeast(0.0)

        // ETA residuo: stima rolling. Per ora proporzionale alla distanza residua
        // sulla durata totale. Quando avremo traffico/velocità rilevate, miglioreremo.
        val durationRemaining = if (route.distanceMeters > 0) {
            route.durationSeconds * (snap.distanceRemainingMeters / route.distanceMeters)
        } else 0.0

        val arrived = snap.distanceRemainingMeters < ARRIVAL_THRESHOLD_METERS &&
                stepIndex == route.steps.lastIndex

        if (arrived) {
            _state.value = NavigationState.Arrived(route)
            _events.tryEmit(NavigationEvent.Arrived(route))
            return ControllerOutcome.Arrived
        }

        val deviated = deviationDetector.onLocation(snap.distanceFromRouteMeters)
        if (deviated) {
            _state.value = NavigationState.Rerouting(
                previousRoute = route,
                triggeredAt = loc.point,
            )
            _events.tryEmit(
                NavigationEvent.OffRouteConfirmed(snap.distanceFromRouteMeters, loc.point)
            )
            return ControllerOutcome.DeviationConfirmed(snap.distanceFromRouteMeters)
        }

        val pendingSpoken = instructionTrigger.consumePendingSpoken(
            stepIndex = stepIndex,
            step = currentStep,
            distanceToManeuverMeters = distanceToEndOfStep,
        )
        if (pendingSpoken != null) {
            _events.tryEmit(
                NavigationEvent.SpokenInstructionTriggered(pendingSpoken, distanceToEndOfStep)
            )
        }
        val visual = instructionTrigger.currentVisual(currentStep, distanceToEndOfStep)

        _state.value = NavigationState.Navigating(
            route = route,
            progress = RouteProgress(
                currentStepIndex = stepIndex,
                distanceAlongStepMeters = distanceAlongStep,
                distanceToNextManeuverMeters = distanceToEndOfStep,
                distanceTraveledMeters = snap.distanceTraveledMeters,
                distanceRemainingMeters = snap.distanceRemainingMeters,
                durationRemainingSeconds = durationRemaining,
                snappedLocation = snap.snappedPoint,
            ),
            currentVisualInstruction = visual,
            pendingSpokenInstruction = pendingSpoken,
            deviation = if (snap.distanceFromRouteMeters > config.offRouteThresholdFor(activeProfile))
                Deviation.OffRoute(snap.distanceFromRouteMeters) else Deviation.OnRoute,
        )
        return ControllerOutcome.ProgressUpdated(pendingSpoken)
    }

    /**
     * Distanza residua alla fine dello step corrente (= prossima manovra).
     *
     * La calcoliamo per differenza fra il punto di fine dello step (espresso in
     * distanza cumulata dall'inizio del route) e la distanza percorsa snapped.
     */
    private fun computeDistanceToEndOfStep(
        route: Route,
        stepIndex: Int,
        distanceTraveled: Double,
    ): Double {
        var cumulative = 0.0
        for (i in 0..stepIndex) cumulative += route.steps[i].distanceMeters
        return (cumulative - distanceTraveled).coerceAtLeast(0.0)
    }

    companion object {
        /** Sotto questa distanza alla destinazione consideriamo l'arrivo. */
        private const val ARRIVAL_THRESHOLD_METERS = 20.0
    }
}

/**
 * Esito interno di [NavigationController.onLocation], usato dal wrapper per
 * decidere reazioni immediate. Distinto dal [NavigationEvent] pubblico (bus di
 * eventi per gli integratori): questo non esce mai dall'SDK.
 */
internal sealed class ControllerOutcome {
    object Ignored : ControllerOutcome()
    data class ProgressUpdated(val spokenToPlay: com.enaide.sdk.model.SpokenInstruction?) : ControllerOutcome()
    data class DeviationConfirmed(val distanceFromRouteMeters: Double) : ControllerOutcome()
    object Arrived : ControllerOutcome()
}
