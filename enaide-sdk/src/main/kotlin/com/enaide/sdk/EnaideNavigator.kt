package com.enaide.sdk

import com.enaide.sdk.model.GeoPoint
import com.enaide.sdk.model.NavigationCommand
import com.enaide.sdk.model.NavigationEvent
import com.enaide.sdk.model.NavigationState
import com.enaide.sdk.model.Route
import com.enaide.sdk.model.RouteOptions
import com.enaide.sdk.model.TransportProfile
import com.enaide.sdk.model.UserLocation
import com.enaide.sdk.routing.RouteResult
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Entry point pubblico dell'SDK enaide.
 *
 * Ciclo di vita atteso:
 *  1. `computeRoute(...)` → ottieni un [Route].
 *  2. `start(route)` → la navigazione passa a [NavigationState.Navigating].
 *  3. A ogni fix GPS chiama [updateLocation]; lo stato si aggiorna su [state].
 *  4. `stop()` quando l'utente termina o annulla.
 *
 * L'implementazione è thread-safe: i metodi possono essere chiamati da qualsiasi
 * coroutine context. Lo [state] è uno [StateFlow] hot, sicuro da collezionare da
 * Compose / Lifecycle senza precauzioni.
 *
 * Non lega l'SDK ad alcun fornitore di posizione (FusedLocation, GMS, GPS grezzo).
 * Il client decide come ottenere i fix e li passa via [updateLocation]. Questo
 * tiene la libreria neutra rispetto a Google Play Services e testabile in JVM.
 */
public interface EnaideNavigator {

    /** Stato corrente, osservabile in modo reattivo. */
    public val state: StateFlow<NavigationState>

    /**
     * Bus di eventi discreti ("one-shot") della navigazione: avvio, avanzamento
     * di step, trigger di istruzione vocale, deviazione confermata, arrivo, stop.
     *
     * Pensato per **triggerare azioni** lato integratore in modo modulare e
     * disaccoppiato dalla UI: collega un modulo TTS, telemetria, automazioni, o
     * il reroute automatico osservando questo flusso. Hot e condivisibile da piu'
     * collettori. A differenza di [state] non ritrasmette l'ultimo valore: un
     * collettore che si abbona tardi non rivede eventi gia' passati.
     */
    public val events: SharedFlow<NavigationEvent>

    /**
     * Calcola un percorso. Operazione di rete sospensiva.
     *
     * @param waypoints almeno origine e destinazione. Punti intermedi opzionali.
     * @param profile se `null`, usa [EnaideConfig.defaultProfile].
     * @param options opzioni di costing (default: nessuna restrizione, nessuna alternativa).
     */
    public suspend fun computeRoute(
        waypoints: List<GeoPoint>,
        profile: TransportProfile? = null,
        options: RouteOptions = RouteOptions.Default,
    ): RouteResult

    /**
     * Punto d'ingresso **event-centric**: invia un [NavigationCommand] al motore.
     *
     * È il modo per *cambiare o aggiornare* la navigazione via evento — ricalcolo,
     * cambio destinazione, tappa intermedia, sostituzione percorso, cambio mezzo
     * in marcia. I metodi [start]/[stop]/[updateLocation] sono comodi wrapper su
     * questo. I comandi che richiedono routing sono asincroni: lo stato passa per
     * [NavigationState.Rerouting] e poi [NavigationState.Navigating].
     */
    public fun dispatch(command: NavigationCommand)

    /**
     * Avvia la navigazione lungo [route]. Idempotente se chiamata con lo stesso
     * route già attivo. Se è già in corso un'altra navigazione, viene sostituita.
     * Equivale a `dispatch(NavigationCommand.Start(route))`.
     */
    public fun start(route: Route)

    /** Spinge un fix di posizione. Equivale a `dispatch(NavigationCommand.UpdateLocation(...))`. */
    public fun updateLocation(location: UserLocation)

    /** Termina la navigazione. Equivale a `dispatch(NavigationCommand.Stop)`. */
    public fun stop()

    public companion object {
        /**
         * Crea un'istanza di default dell'SDK con la [config] fornita.
         *
         * @return istanza pronta all'uso. Le risorse interne (coroutine scope,
         *   HTTP client) sono ownate dall'SDK e rilasciate al primo [stop] dopo
         *   che il processo entra in background — vedi `EnaideNavigatorImpl` per dettagli.
         */
        @JvmStatic
        public fun create(config: EnaideConfig = EnaideConfig()): EnaideNavigator =
            com.enaide.sdk.internal.EnaideNavigatorImpl(config)
    }
}
