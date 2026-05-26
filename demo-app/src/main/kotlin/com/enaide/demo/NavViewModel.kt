package com.enaide.demo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.enaide.sdk.EnaideConfig
import com.enaide.sdk.EnaideNavigator
import com.enaide.sdk.geocoding.GeocodeResult
import com.enaide.sdk.geocoding.GeocodedPlace
import com.enaide.sdk.geocoding.NominatimGeocodingClient
import com.enaide.sdk.model.GeoPoint
import com.enaide.sdk.model.NavigationEvent
import com.enaide.sdk.model.NavigationState
import com.enaide.sdk.model.Route
import com.enaide.sdk.routing.RouteResult
import com.enaide.sdk.routing.RoutingError
import com.enaide.sdk.model.TripPlan
import com.enaide.sdk.model.TripStop
import com.enaide.sdk.model.VehicleDimensions
import com.enaide.sdk.poi.OverpassPoiProvider
import com.enaide.sdk.poi.Poi
import com.enaide.sdk.poi.PoiCategory
import com.enaide.sdk.poi.PoiResult
import com.enaide.sdk.simulation.SimulatedLocationSource
import com.enaide.sdk.simulation.WrongTurn
import com.enaide.sdk.vehicle.VehicleKind
import com.enaide.sdk.vehicle.VehicleProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Fasi della UI del navigatore demo.
 */
internal sealed interface Screen {
    /** Mappa libera (schermata iniziale): l'utente vede la mappa e cerca la destinazione. */
    data object Map : Screen

    /** Anteprima del percorso calcolato, pronto a partire. */
    data class Preview(
        val route: Route,
        val originLabel: String,
        val destinationLabel: String,
    ) : Screen

    /** Guida attiva: lo stato live arriva da [NavViewModel.navState]. */
    data class Driving(
        val originLabel: String,
        val destinationLabel: String,
    ) : Screen
}

/**
 * Sorgente di avanzamento durante la guida.
 * - [GPS]: fix reali dal device.
 * - [SIMULATED]: simulatore con fisica.
 * - [MANUAL]: nessun GPS; l'utente avanza a mano allo step successivo (a piedi).
 */
internal enum class LocationMode { GPS, SIMULATED, MANUAL }

/** Tab della bottom bar. NAV appare solo quando la navigazione è attiva. */
internal enum class AppTab { MAP, NAV, SETTINGS }

/** Id stabile di un POI a partire dalle sue coordinate (per il match del marker). */
internal fun GeoPoint.poiId(): String = "%.6f,%.6f".format(latitude, longitude)

/**
 * Orchestratore della demo: tiene insieme tutto l'SDK enaide — geocoding
 * (Nominatim), routing (Valhalla), motore di navigazione, bus di eventi — e la
 * sorgente di posizione (GPS reale via [GpsLocationSource] o [SimulatedLocationSource]).
 *
 * Architettura disaccoppiata: la UI osserva [navState] (stato) mentre azioni
 * collaterali (TTS, log) si agganciano a [events] (eventi discreti dell'SDK). In
 * un'app reale basta sostituire la sorgente di posizione: il resto non cambia.
 */
internal class NavViewModel(app: Application) : AndroidViewModel(app) {

    private val config = EnaideConfig(
        // Cambia con un contatto reale prima di pubblicare: Nominatim e Valhalla
        // pubblici richiedono uno User-Agent identificativo.
        userAgent = "enaide-demo/0.3 (+https://enaide.example/contact)",
    )
    private val navigator: EnaideNavigator = EnaideNavigator.create(config)
    private val geocoder = NominatimGeocodingClient(config)
    private val poiProvider = OverpassPoiProvider(config)
    private val gpsSource = GpsLocationSource(app)
    private val compass = CompassSource(app)
    private val tripStore = TripStore(app)

    /** Stato live della navigazione esposto dall'SDK. */
    val navState: StateFlow<NavigationState> = navigator.state

    /** Bus di eventi discreti dell'SDK: lo espone alla UI per agganciare TTS & co. */
    val events: SharedFlow<NavigationEvent> = navigator.events

    private val _screen = MutableStateFlow<Screen>(Screen.Map)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    /** Destinazione in anteprima, per i risultati di ricerca dalla mappa. */
    private val _searchResults = MutableStateFlow<List<GeocodedPlace>>(emptyList())
    val searchResults: StateFlow<List<GeocodedPlace>> = _searchResults.asStateFlow()

    /** Job del GPS "live" attivo sulla schermata mappa (mostra l'utente in tempo reale). */
    private var liveGpsJob: Job? = null

    /** Messaggio di stato/errore mostrato in fase di planning. `null` = nessuno. */
    private val _planningMessage = MutableStateFlow<String?>(null)
    val planningMessage: StateFlow<String?> = _planningMessage.asStateFlow()

    /** Errori "one-shot" da mostrare come snackbar/toast. */
    private val _errors = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors

    private fun showError(message: String) { _errors.tryEmit(message) }

    /** True mentre geocoding+routing sono in corso (mostra spinner, disabilita bottone). */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Modalita' sorgente posizione scelta dall'utente. */
    private val _locationMode = MutableStateFlow(LocationMode.SIMULATED)
    val locationMode: StateFlow<LocationMode> = _locationMode.asStateFlow()

    /** Se true, la simulazione "sbaglia strada" per testare il reroute. */
    private val _simulateWrongTurn = MutableStateFlow(false)
    val simulateWrongTurn: StateFlow<Boolean> = _simulateWrongTurn.asStateFlow()

    fun setSimulateWrongTurn(enabled: Boolean) { _simulateWrongTurn.value = enabled }

    // --- Impostazioni utente -------------------------------------------------

    /** Guida vocale TTS attiva. */
    private val _voiceEnabled = MutableStateFlow(true)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled.asStateFlow()
    fun setVoiceEnabled(on: Boolean) { _voiceEnabled.value = on }

    /** Endpoint configurati (sola lettura nella demo: cambiarli richiede riavvio). */
    val routingEndpoint: String get() = config.routingBaseUrl
    val geocodingEndpoint: String get() = config.nominatimBaseUrl

    /**
     * Origine manuale impostata dall'utente (modalità senza GPS reale). Se settata,
     * ha priorità sul GPS come punto di partenza dei percorsi.
     */
    private val _customOrigin = MutableStateFlow<GeocodedPlace?>(null)
    val customOrigin: StateFlow<GeocodedPlace?> = _customOrigin.asStateFlow()

    /** Risultati ricerca per impostare l'origine manuale. */
    private val _originResults = MutableStateFlow<List<GeocodedPlace>>(emptyList())
    val originResults: StateFlow<List<GeocodedPlace>> = _originResults.asStateFlow()

    private var originSearchJob: Job? = null

    fun searchOrigin(query: String) {
        originSearchJob?.cancel()
        if (query.isBlank()) { _originResults.value = emptyList(); return }
        originSearchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val r = geocoder.search(query, limit = 6)
            _originResults.value = (r as? GeocodeResult.Success)?.places ?: emptyList()
        }
    }

    fun setCustomOrigin(place: GeocodedPlace) {
        _customOrigin.value = place
        _originResults.value = emptyList()
        // Mostra subito l'origine scelta sulla mappa.
        _currentPosition.value = place.point
    }

    fun clearCustomOrigin() { _customOrigin.value = null }

    /** Tab correntemente selezionata. */
    private val _tab = MutableStateFlow(AppTab.MAP)
    val tab: StateFlow<AppTab> = _tab.asStateFlow()
    fun selectTab(t: AppTab) { _tab.value = t }

    // --- POI -----------------------------------------------------------------

    /** Categoria POI attiva (null = nessuna, marker nascosti). */
    private val _poiCategory = MutableStateFlow<PoiCategory?>(null)
    val poiCategory: StateFlow<PoiCategory?> = _poiCategory.asStateFlow()

    /** POI correntemente mostrati sulla mappa. */
    private val _pois = MutableStateFlow<List<Poi>>(emptyList())
    val pois: StateFlow<List<Poi>> = _pois.asStateFlow()

    private var poiJob: Job? = null

    /**
     * Mostra/nasconde i POI di una categoria. Cerca "vicino" sulla mappa libera,
     * "lungo il percorso" in anteprima/guida (se c'è un route attivo).
     */
    fun togglePoiCategory(category: PoiCategory) {
        poiJob?.cancel() // evita che una risposta vecchia sovrascriva la nuova categoria
        if (_poiCategory.value == category) {
            _poiCategory.value = null
            _pois.value = emptyList()
            return
        }
        _poiCategory.value = category
        poiJob = viewModelScope.launch {
            _busy.value = true
            val activeRoute = (_screen.value as? Screen.Preview)?.route
                ?: (navState.value as? NavigationState.Navigating)?.route
            val result = if (activeRoute != null) {
                poiProvider.alongRoute(activeRoute, category)
            } else {
                val center = _currentPosition.value ?: DEFAULT_ORIGIN
                poiProvider.nearby(center, category)
            }
            _pois.value = (result as? PoiResult.Success)?.pois ?: emptyList()
            if (result is PoiResult.Failure) showError(str(R.string.poi_unavailable))
            _busy.value = false
        }
    }

    /** Naviga verso un POI (tap sul marker): lo imposta come destinazione. */
    fun navigateToPoi(poiId: String) {
        val poi = _pois.value.firstOrNull { it.point.poiId() == poiId } ?: return
        planTo(GeocodedPlace(poi.point, poi.name ?: str(R.string.poi_default_name)))
    }

    // --- Pianificazione percorso (TripPlan) ----------------------------------

    /** Piano di viaggio corrente (origine → tappe → destinazione). */
    private val _tripPlan = MutableStateFlow(TripPlan())
    val tripPlan: StateFlow<TripPlan> = _tripPlan.asStateFlow()

    /** Percorso calcolato dal piano corrente (per anteprima/mappa). `null` se non calcolato. */
    private val _previewRoute = MutableStateFlow<Route?>(null)
    val previewRoute: StateFlow<Route?> = _previewRoute.asStateFlow()

    private var recomputeJob: Job? = null

    /** Coordinate da usare come origine di default (GPS live o posizione corrente). */
    private fun defaultOriginStop(): TripStop {
        val p = _currentPosition.value ?: DEFAULT_ORIGIN
        return TripStop(p, applicationGetString(R.string.origin_label))
    }

    private fun applicationGetString(id: Int) = getApplication<Application>().getString(id)

    /** Inizia un piano verso [destination] dall'origine corrente, e calcola. */
    fun planTo(destination: GeocodedPlace) {
        _searchResults.value = emptyList()
        _tripPlan.value = TripPlan.of(
            origin = defaultOriginStop(),
            destination = TripStop(destination.point, destination.displayName),
        )
        recomputePlan()
    }

    /**
     * Aggiunge una tappa. Se siamo **in navigazione**, parte dal percorso attivo:
     * inserisce il waypoint via comando event-centric (l'SDK ricalcola la
     * navigazione in corso). Altrimenti modifica il piano in pianificazione.
     */
    fun addStop(place: GeocodedPlace) {
        _searchResults.value = emptyList()
        if (navState.value is NavigationState.Navigating) {
            // Tappa aggiunta al viaggio in corso: prima della destinazione, reroute live.
            navigator.dispatch(com.enaide.sdk.model.NavigationCommand.AddWaypoint(place.point))
            _tripPlan.value = _tripPlan.value.addStop(TripStop(place.point, place.displayName))
            return
        }
        val plan = _tripPlan.value
        _tripPlan.value = if (plan.isRoutable) {
            plan.addStop(TripStop(place.point, place.displayName))
        } else {
            if (plan.stops.isEmpty()) TripPlan.of(defaultOriginStop(), TripStop(place.point, place.displayName))
            else plan.withDestination(TripStop(place.point, place.displayName))
        }
        recomputePlan()
    }

    fun removeStop(index: Int) {
        _tripPlan.value = _tripPlan.value.removeStop(index)
        recomputePlan()
    }

    fun moveStop(from: Int, to: Int) {
        _tripPlan.value = _tripPlan.value.moveStop(from, to)
        recomputePlan()
    }

    /** Cambia origine (consentito solo fuori dalla navigazione assistita da GPS). */
    fun setOrigin(place: GeocodedPlace) {
        _tripPlan.value = _tripPlan.value.withOrigin(TripStop(place.point, place.displayName))
        recomputePlan()
    }

    fun clearPlan() {
        recomputeJob?.cancel()
        _tripPlan.value = TripPlan()
        _previewRoute.value = null
    }

    /** Ricalcola il percorso dal piano corrente, con debounce (ad ogni modifica). */
    private fun recomputePlan() {
        recomputeJob?.cancel()
        val plan = _tripPlan.value
        if (!plan.isRoutable) { _previewRoute.value = null; return }
        recomputeJob = viewModelScope.launch {
            delay(ROUTE_DEBOUNCE_MS)
            _busy.value = true
            val vehicle = currentVehicleProfile()
            when (val result = navigator.computeRoute(
                waypoints = plan.waypoints,
                profile = vehicle.toProfile(),
                options = vehicle.toRouteOptions(),
            )) {
                is RouteResult.Success -> {
                    // Arricchisce con i limiti di velocità (best-effort, non blocca).
                    val enriched = runCatching { navigator.fetchSpeedLimits(result.route) }
                        .getOrDefault(result.route)
                    _previewRoute.value = enriched
                    _screen.value = Screen.Preview(
                        route = enriched,
                        originLabel = plan.origin?.label ?: "",
                        destinationLabel = plan.destination?.label ?: "",
                    )
                }
                is RouteResult.Failure -> showError(str(R.string.err_route_failed, result.error.toString()))
            }
            _busy.value = false
        }
    }

    /** Tipo di mezzo scelto (auto/piedi/bici/camion). */
    private val _vehicleKind = MutableStateFlow(VehicleKind.CAR)
    val vehicleKind: StateFlow<VehicleKind> = _vehicleKind.asStateFlow()

    /** Sagoma camion (usata solo se [vehicleKind] == TRUCK). */
    private val _truckDimensions = MutableStateFlow(VehicleProfile.DefaultTruck)
    val truckDimensions: StateFlow<VehicleDimensions> = _truckDimensions.asStateFlow()

    fun setVehicleKind(kind: VehicleKind) { _vehicleKind.value = kind }
    fun setTruckDimensions(dim: VehicleDimensions) { _truckDimensions.value = dim }

    /** Velocità di crociera tipica per la simulazione, per tipo di mezzo. */
    private fun cruiseKmhFor(kind: VehicleKind): Double = when (kind) {
        VehicleKind.CAR -> 50.0
        VehicleKind.TRUCK -> 45.0
        VehicleKind.BICYCLE -> 18.0
        VehicleKind.PEDESTRIAN -> 5.0
    }

    private fun currentVehicleProfile(): VehicleProfile {
        // Lingua delle istruzioni allineata al locale dell'app: Valhalla restituisce
        // le frasi turn-by-turn (testo + voce) già localizzate in quella lingua.
        val lang = java.util.Locale.getDefault().toLanguageTag()
        val base = when (_vehicleKind.value) {
            VehicleKind.CAR -> VehicleProfile.car()
            VehicleKind.PEDESTRIAN -> VehicleProfile.pedestrian()
            VehicleKind.BICYCLE -> VehicleProfile.bicycle()
            VehicleKind.TRUCK -> VehicleProfile.truck(_truckDimensions.value)
        }
        return base.copy(language = lang)
    }

    /**
     * Posizione corrente mostrata sulla mappa. Default = Zurigo (siamo in
     * simulazione all'avvio). Aggiornata dai fix GPS, dall'origine manuale o dal
     * tap sulla mappa.
     */
    private val _currentPosition = MutableStateFlow<GeoPoint?>(DEFAULT_ORIGIN)
    val currentPosition: StateFlow<GeoPoint?> = _currentPosition.asStateFlow()

    /**
     * Direzione mostrata (camera/freccia). Combina, come i veri navigatori:
     * - in **movimento** (>[COMPASS_SPEED_THRESHOLD]) usa il bearing GPS (stabile);
     * - da **fermo/lento** usa la **bussola** del device (orientamento reale).
     */
    private val _currentBearing = MutableStateFlow<Double?>(null)
    val currentBearing: StateFlow<Double?> = _currentBearing.asStateFlow()

    private var gpsBearing: Double? = null
    private var compassBearing: Double? = null
    private var movingFast = false

    init {
        // La bussola alimenta il bearing quando non ci si muove abbastanza.
        if (compass.isAvailable) viewModelScope.launch {
            compass.asFlow().collect { az ->
                compassBearing = az
                if (!movingFast) _currentBearing.value = az
            }
        }
    }

    private fun updateBearingFromFix(courseDegrees: Double?, speedMps: Double) {
        movingFast = speedMps >= COMPASS_SPEED_THRESHOLD
        courseDegrees?.let { gpsBearing = it }
        _currentBearing.value = if (movingFast) (gpsBearing ?: compassBearing) else (compassBearing ?: gpsBearing)
    }

    /** Velocità corrente in m/s (dal fix di posizione); UnitFormatter la converte in km/h. */
    private val _currentSpeedMps = MutableStateFlow(0.0)
    val currentSpeedMps: StateFlow<Double> = _currentSpeedMps.asStateFlow()

    private var locationJob: Job? = null

    fun setLocationMode(mode: LocationMode) {
        if (_locationMode.value == mode) return
        _locationMode.value = mode
        // Lo switch NON azzera mai il puntatore: si mantiene la posizione corrente.
        // GPS → avvia la sorgente live (il primo fix la aggiornerà);
        // Simulato → ferma il GPS e tiene l'ultima posizione (o l'origine manuale).
        when (mode) {
            LocationMode.GPS -> startLiveLocation()
            LocationMode.SIMULATED, LocationMode.MANUAL -> {
                stopLiveLocation()
                _customOrigin.value?.let { _currentPosition.value = it.point }
            }
        }
    }

    /**
     * Avvia la guida lungo il percorso in anteprima e collega la sorgente di
     * posizione scelta. Con [LocationMode.GPS] usa il GPS reale (il permesso deve
     * essere gia' concesso); con [LocationMode.SIMULATED] usa il simulatore.
     */
    init {
        // Reroute event-centric col simulatore: quando l'SDK emette un nuovo
        // Started (dopo un ricalcolo) mentre stiamo guidando in simulazione, si
        // riaggancia la sorgente al NUOVO percorso (senza ripetere l'errore).
        viewModelScope.launch {
            navigator.events.collect { e ->
                if (e is NavigationEvent.Started &&
                    _screen.value is Screen.Driving &&
                    _locationMode.value == LocationMode.SIMULATED &&
                    locationJob?.isActive != true
                ) {
                    driveSimulated(e.route, withError = false)
                }
            }
        }
    }

    fun startDriving() {
        val preview = _screen.value as? Screen.Preview ?: return
        stopLiveLocation() // la guida usa la propria sorgente
        navigator.start(preview.route)
        _screen.value = Screen.Driving(preview.originLabel, preview.destinationLabel)
        _tab.value = AppTab.NAV
        // Persisti il viaggio per poterlo recuperare dopo un kill/crash.
        tripStore.save(_tripPlan.value, _vehicleKind.value)

        locationJob?.cancel()
        // In MANUAL (GPS-less) non c'è sorgente: si avanza con advanceStep().
        if (_locationMode.value == LocationMode.MANUAL) return

        locationJob = viewModelScope.launch {
            val flow = when (_locationMode.value) {
                LocationMode.GPS -> gpsSource.asFlow()
                LocationMode.SIMULATED -> SimulatedLocationSource.realistic(
                    route = preview.route,
                    cruiseKmh = cruiseKmhFor(_vehicleKind.value),
                    wrongTurn = if (_simulateWrongTurn.value) WrongTurn() else null,
                ).asFlow()
                LocationMode.MANUAL -> return@launch
            }
            flow.collect { fix -> pushFix(fix) }
        }
    }

    /** True se la guida è in modalità manuale (GPS-less): mostra il bottone "prossimo step". */
    val isManualMode: Boolean get() = _locationMode.value == LocationMode.MANUAL

    /** Avanza manualmente allo step successivo (navigazione GPS-less). */
    fun advanceStep() {
        navigator.dispatch(com.enaide.sdk.model.NavigationCommand.AdvanceStep)
    }

    /** True se il viaggio ha tappe intermedie (mostra il bottone "salta tappa"). */
    val hasIntermediateStops: Boolean get() = _tripPlan.value.intermediateStops.isNotEmpty()

    /**
     * Salta la prossima tappa intermedia di un viaggio multitappa: la rimuove dal
     * piano e ricalcola la navigazione in corso verso la tappa successiva.
     */
    fun skipNextStop() {
        val plan = _tripPlan.value
        if (plan.intermediateStops.isEmpty()) return
        // Rimuovi la prima tappa intermedia (indice 1, dopo l'origine).
        val updated = plan.removeStop(1)
        _tripPlan.value = updated
        // Ricalcola dalla posizione corrente verso i waypoint rimasti e sostituisci.
        viewModelScope.launch {
            val origin = _currentPosition.value ?: updated.waypoints.first()
            val rest = updated.waypoints.drop(1)
            val vehicle = currentVehicleProfile()
            val result = navigator.computeRoute(
                waypoints = listOf(origin) + rest,
                profile = vehicle.toProfile(),
                options = vehicle.toRouteOptions(),
            )
            when (result) {
                is RouteResult.Success ->
                    navigator.dispatch(com.enaide.sdk.model.NavigationCommand.ReplaceRoute(result.route))
                is RouteResult.Failure ->
                    showError(str(R.string.err_route_failed, result.error.toString()))
            }
        }
    }

    /** (Ri)avvia la sorgente simulata su [route] — usato dopo un reroute. */
    private fun driveSimulated(route: com.enaide.sdk.model.Route, withError: Boolean) {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            SimulatedLocationSource.realistic(
                route = route,
                cruiseKmh = cruiseKmhFor(_vehicleKind.value),
                wrongTurn = if (withError) WrongTurn() else null,
            ).asFlow().collect { fix -> pushFix(fix) }
        }
    }

    private fun pushFix(fix: com.enaide.sdk.model.UserLocation) {
        _currentPosition.value = fix.point
        _currentSpeedMps.value = fix.speedMetersPerSecond ?: 0.0
        updateBearingFromFix(fix.courseDegrees, fix.speedMetersPerSecond ?: 0.0)
        navigator.updateLocation(fix)
    }

    /**
     * Attiva il GPS "live" per mostrare l'utente sulla mappa libera (non in guida).
     * Richiede permesso già concesso. Idempotente.
     */
    fun startLiveLocation() {
        if (liveGpsJob?.isActive == true) return
        liveGpsJob = viewModelScope.launch {
            // Prova più volte a recuperare l'ultima posizione nota, così la mappa
            // si centra subito su uno zoom sensato mentre arriva il primo fix vero.
            repeat(LOCATE_RETRIES) { attempt ->
                if (attempt > 0 || _currentPosition.value == DEFAULT_ORIGIN) {
                    gpsSource.lastKnown()?.let { _currentPosition.value = it.point }
                }
                if (gpsSource.lastKnown() != null) return@repeat
                delay(LOCATE_RETRY_DELAY_MS)
            }
            runCatching {
                gpsSource.asFlow().collect { fix ->
                    _currentPosition.value = fix.point
                    updateBearingFromFix(fix.courseDegrees, fix.speedMetersPerSecond ?: 0.0)
                }
            }
        }
    }

    fun stopLiveLocation() {
        liveGpsJob?.cancel()
        liveGpsJob = null
    }

    private var searchJob: Job? = null

    /**
     * Ricerca destinazioni con **debounce**: aspetta che l'utente smetta di
     * digitare prima di chiamare Nominatim. Senza, una query a ogni tasto manda
     * decine di richieste e fa scattare il rate-limit 429 del server pubblico.
     */
    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) { _searchResults.value = emptyList(); _busy.value = false; return }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _busy.value = true
            when (val res = geocoder.search(query, limit = 6)) {
                is GeocodeResult.Success -> {
                    _searchResults.value = res.places
                    _planningMessage.value = if (res.places.isEmpty()) str(R.string.no_results) else null
                }
                is GeocodeResult.Failure -> {
                    _searchResults.value = emptyList()
                    showError(geocodingErrorMessage(res.error))
                }
            }
            _busy.value = false
        }
    }

    fun clearSearch() { _searchResults.value = emptyList() }

    /**
     * Punto scelto col long-press sulla mappa: reverse geocoding (OSM) per dargli
     * un nome, poi calcola il percorso verso di esso.
     */
    fun selectPointOnMap(point: GeoPoint) {
        viewModelScope.launch {
            _busy.value = true
            val label = (geocoder.reverse(point) as? GeocodeResult.Success)
                ?.places?.firstOrNull()?.displayName
                ?: "%.5f, %.5f".format(point.latitude, point.longitude)
            _busy.value = false
            planTo(GeocodedPlace(point, label))
        }
    }

    fun backToMap() {
        _screen.value = Screen.Map
        _planningMessage.value = null
    }

    /** Comando event-centric: ricalcola il percorso (es. su deviazione). */
    fun recalculate() {
        navigator.dispatch(com.enaide.sdk.model.NavigationCommand.Recalculate)
    }

    /** True se esiste un viaggio salvato da recuperare (dopo kill/crash). */
    val hasRecoverableTrip: Boolean get() = tripStore.load() != null

    /**
     * Recupera il viaggio salvato: ripristina mezzo e piano, RICALCOLA il percorso
     * e va in anteprima pronto a ripartire. No-op se non c'è nulla da recuperare.
     */
    fun restoreTrip() {
        val saved = tripStore.load() ?: return
        _vehicleKind.value = saved.vehicle
        _tripPlan.value = saved.plan
        recomputePlan()
    }

    /** Scarta il viaggio recuperabile (l'utente non vuole riprenderlo). */
    fun discardRecoverableTrip() = tripStore.clear()

    /** Ferma la guida e torna alla mappa libera. */
    fun stopDriving() {
        locationJob?.cancel()
        locationJob = null
        navigator.stop()
        tripStore.clear() // viaggio terminato: niente da recuperare
        _currentSpeedMps.value = 0.0
        _screen.value = Screen.Map
        _tab.value = AppTab.MAP
        _planningMessage.value = null
        // Riattiva il GPS live se era attivo (per continuare a vedersi sulla mappa).
        if (_locationMode.value == LocationMode.GPS) startLiveLocation()
    }


    override fun onCleared() {
        locationJob?.cancel()
        liveGpsJob?.cancel()
        // Rilascia le risorse di rete: senza, OkHttp tiene vivi pool e thread.
        navigator.shutdown()
        runCatching { geocoder.close() }
        runCatching { poiProvider.close() }
    }

    private fun geocodingErrorMessage(error: com.enaide.sdk.geocoding.GeocodingError): String = when (error) {
        is com.enaide.sdk.geocoding.GeocodingError.ServerError ->
            if (error.httpStatus == 429) str(R.string.err_rate_limit)
            else str(R.string.err_geocoding_server, error.httpStatus)
        is com.enaide.sdk.geocoding.GeocodingError.NetworkError -> str(R.string.err_network_search)
        is com.enaide.sdk.geocoding.GeocodingError.InvalidRequest -> str(R.string.err_search_invalid)
        is com.enaide.sdk.geocoding.GeocodingError.ParseError -> str(R.string.err_parse)
    }

    /** Helper per leggere una stringa localizzata dall'Application context. */
    private fun str(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    private companion object {
        /** Origine di fallback se la posizione utente è ignota (centro Zurigo). */
        val DEFAULT_ORIGIN = GeoPoint(47.3769, 8.5417)

        /** Attesa prima di lanciare la ricerca, per non spammare Nominatim (429). */
        const val SEARCH_DEBOUNCE_MS = 600L

        /** Tentativi di lettura posizione iniziale + attesa fra l'uno e l'altro. */
        const val LOCATE_RETRIES = 5
        const val LOCATE_RETRY_DELAY_MS = 800L

        /** Debounce del ricalcolo percorso ad ogni modifica del piano. */
        const val ROUTE_DEBOUNCE_MS = 400L

        /** Sopra questa velocità (m/s, ~3.6 km/h) si usa il bearing GPS; sotto, la bussola. */
        const val COMPASS_SPEED_THRESHOLD = 1.0
    }
}
