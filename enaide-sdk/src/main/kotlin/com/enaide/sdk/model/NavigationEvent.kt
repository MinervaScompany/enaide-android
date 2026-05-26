package com.enaide.sdk.model

/**
 * Evento discreto ("one-shot") emesso dal motore di navigazione.
 *
 * A differenza di [NavigationState] — che descrive lo *stato corrente* ed e'
 * sempre presente — un [NavigationEvent] rappresenta qualcosa che *accade in un
 * istante* e va gestito una volta sola: il momento esatto in cui scatta una
 * manovra, una deviazione viene confermata, si avanza di step, si arriva.
 *
 * E' il canale pensato per **triggerare azioni** lato integratore senza
 * accoppiarsi alla UI: TTS, analytics, automazioni domotiche, avvisi, logging.
 * Ci si abbona via [com.enaide.sdk.EnaideNavigator.events].
 *
 * Modulare per costruzione: ogni consumer (modulo TTS, modulo telemetria, app)
 * osserva lo stesso flusso in modo indipendente. Speculare in iOS come
 * `AsyncStream<NavigationEvent>` / Combine `Publisher`.
 */
public sealed class NavigationEvent {

    /** La navigazione e' partita lungo [route]. */
    public data class Started(public val route: Route) : NavigationEvent()

    /**
     * Si e' avanzati a un nuovo step del percorso (manovra completata).
     *
     * @property stepIndex indice del nuovo step corrente.
     * @property step lo step in cui si entra.
     */
    public data class StepAdvanced(
        public val stepIndex: Int,
        public val step: RouteStep,
    ) : NavigationEvent()

    /**
     * Una soglia di distanza prima della prossima manovra e' stata superata:
     * momento tipico per annunciare vocalmente l'istruzione.
     *
     * @property instruction istruzione vocale da pronunciare.
     * @property distanceToManeuverMeters distanza residua alla manovra all'atto del trigger.
     */
    public data class SpokenInstructionTriggered(
        public val instruction: SpokenInstruction,
        public val distanceToManeuverMeters: Double,
    ) : NavigationEvent()

    /**
     * Deviazione dal percorso confermata (dopo N fix consecutivi off-route).
     * Tipicamente l'integratore reagisce ricalcolando il percorso.
     *
     * @property distanceFromRouteMeters quanto l'utente e' lontano dal percorso.
     * @property at posizione utente al momento della conferma.
     */
    public data class OffRouteConfirmed(
        public val distanceFromRouteMeters: Double,
        public val at: GeoPoint,
    ) : NavigationEvent()

    /** L'utente e' arrivato a destinazione lungo [route]. */
    public data class Arrived(public val route: Route) : NavigationEvent()

    /** La navigazione e' stata terminata manualmente (stop) o sostituita. */
    public data object Stopped : NavigationEvent()
}
