package com.enaide.sdk.model

/**
 * Comando-evento in ingresso al motore di navigazione.
 *
 * L'SDK è **event-centric**: oltre a *emettere* [NavigationEvent], *riceve*
 * istruzioni come [NavigationCommand] tramite `EnaideNavigator.dispatch(...)`.
 * Questo permette a qualsiasi parte dell'app (o a un modulo) di **cambiare o
 * aggiornare** percorso e navigazione emettendo un comando, senza accoppiarsi
 * all'implementazione.
 *
 * Ciclo completo, simmetrico:
 *
 * ```
 *   NavigationCommand  ──dispatch──▶  [motore]  ──▶  NavigationState (stato)
 *                                              └──▶  NavigationEvent  (eventi)
 * ```
 *
 * I comandi che richiedono un ricalcolo ([Recalculate], [SetDestination],
 * [AddWaypoint], [UpdateProfile]) sono operazioni di rete: il motore emette
 * [NavigationState.Rerouting] mentre calcola, poi [NavigationState.Navigating]
 * col nuovo percorso (o [NavigationState.Failed] se il routing fallisce).
 *
 * Sealed: esaustivo nel `when`, speculare in iOS come `enum NavigationCommand`.
 */
public sealed class NavigationCommand {

    /** Avvia la navigazione lungo un percorso già calcolato. */
    public data class Start(public val route: Route) : NavigationCommand()

    /** Termina la navigazione corrente. */
    public data object Stop : NavigationCommand()

    /** Spinge un nuovo fix di posizione (equivalente a `updateLocation`). */
    public data class UpdateLocation(public val location: UserLocation) : NavigationCommand()

    /**
     * Avanza **manualmente** allo step successivo, senza GPS (navigazione
     * "GPS-less": l'utente conferma di aver raggiunto la prossima manovra, tipico
     * a piedi). All'ultimo step dichiara l'arrivo.
     */
    public data object AdvanceStep : NavigationCommand()

    /**
     * Ricalcola il percorso dalla posizione corrente verso la destinazione
     * attuale, mantenendo profilo e opzioni. Tipico in reazione a una deviazione.
     */
    public data object Recalculate : NavigationCommand()

    /**
     * Cambia la destinazione mantenendo l'origine (posizione) corrente e ricalcola.
     */
    public data class SetDestination(public val destination: GeoPoint) : NavigationCommand()

    /**
     * Inserisce una tappa intermedia prima della destinazione e ricalcola.
     *
     * @property waypoint il punto da inserire.
     * @property index posizione nella lista waypoint; `null` = prima della destinazione finale.
     */
    public data class AddWaypoint(
        public val waypoint: GeoPoint,
        public val index: Int? = null,
    ) : NavigationCommand()

    /** Sostituisce interamente il percorso attivo con uno già calcolato. */
    public data class ReplaceRoute(public val route: Route) : NavigationCommand()

    /**
     * Aggiorna il profilo di trasporto e/o le opzioni di routing e ricalcola al
     * volo. Utile quando il mezzo cambia *in marcia* (es. un camion che scarica e
     * cambia peso/sagoma, o il passaggio auto → a piedi nell'ultimo tratto).
     *
     * I campi `null` lasciano invariato il valore corrente.
     */
    public data class UpdateProfile(
        public val profile: TransportProfile? = null,
        public val options: RouteOptions? = null,
    ) : NavigationCommand()
}
