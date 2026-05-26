package com.enaide.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Attractions
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalAtm
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enaide.demo.R
import com.enaide.sdk.format.UnitFormatter
import com.enaide.sdk.geocoding.GeocodedPlace
import com.enaide.sdk.map.EnaideTheme
import com.enaide.sdk.map.MapCameraState
import com.enaide.sdk.map.MapMarker
import com.enaide.sdk.map.MarkerKind
import com.enaide.sdk.map.RouteMap
import com.enaide.sdk.poi.PoiCategory
import com.enaide.sdk.model.Deviation
import com.enaide.sdk.model.LaneDirection
import com.enaide.sdk.model.NavigationEvent
import com.enaide.sdk.model.NavigationState
import com.enaide.sdk.tts.VoiceGuidance
import com.enaide.sdk.vehicle.VehicleKind

/**
 * Demo "navigatore" stile Google Maps sull'SDK enaide.
 *
 * Struttura: [Scaffold] con **bottom bar** (Mappa, Navigazione [solo se attiva],
 * Impostazioni — altre tab per moduli futuri) e contenuto per tab:
 *  - **Mappa**: mappa fullscreen + search bar in alto (cerca destinazione / aggiungi tappa),
 *    risultati a tendina, FAB "la mia posizione". Tap risultato → anteprima → avvia.
 *  - **Navigazione**: guida live 3D con banner manovra, controlli, voce, reroute.
 *  - **Impostazioni**: mezzo+sagoma, sorgente GPS+permessi, endpoint, voce/tema.
 */
class MainActivity : ComponentActivity() {

    private lateinit var voice: VoiceGuidance

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge: l'app disegna fino ai bordi (anche dietro la barra di
        // sistema in fondo). Lo Scaffold/NavigationBar gestiscono gli insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        voice = VoiceGuidance(this)
        // Permesso notifiche (API 33+): serve a mostrare la notifica del foreground
        // service di navigazione. Non bloccante: il service parte comunque.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            EnaideTheme {
                val vm: NavViewModel = viewModel()
                val voiceEnabled by vm.voiceEnabled.collectAsState()
                val context = LocalContext.current

                LaunchedEffect(Unit) {
                    vm.events.collect { event ->
                        when (event) {
                            is NavigationEvent.SpokenInstructionTriggered ->
                                if (voiceEnabled) voice.speak(event.instruction.text)
                            is NavigationEvent.Started -> voice.reset()
                            is NavigationEvent.Arrived ->
                                if (voiceEnabled) voice.speak(context.getString(R.string.voice_arrived))
                            is NavigationEvent.OffRouteConfirmed -> {
                                if (voiceEnabled) voice.speak(context.getString(R.string.voice_recalculating))
                                vm.recalculate()
                            }
                            else -> Unit
                        }
                    }
                }

                AppShell(vm)
            }
        }
    }

    override fun onDestroy() {
        voice.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun AppShell(vm: NavViewModel) {
    val tab by vm.tab.collectAsState()
    val screen by vm.screen.collectAsState()
    // La tab "Naviga" appare SOLO con navigazione attiva (Driving), non in anteprima.
    val navigating = screen is Screen.Driving
    val snackbarHostState = remember { SnackbarHostState() }

    // Gli errori one-shot dell'SDK diventano snackbar.
    LaunchedEffect(Unit) {
        vm.errors.collect { snackbarHostState.showSnackbar(it) }
    }

    // All'avvio, se c'è un viaggio interrotto (kill/crash), offri di riprenderlo.
    val resumeMsg = stringResource(R.string.resume_trip)
    val resumeAction = stringResource(R.string.resume)
    LaunchedEffect(Unit) {
        if (vm.hasRecoverableTrip) {
            val res = snackbarHostState.showSnackbar(
                message = resumeMsg,
                actionLabel = resumeAction,
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            if (res == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.restoreTrip()
            else vm.discardRecoverableTrip()
        }
    }

    // Durante la guida (tab Naviga attiva) la bottom bar scompare: vista immersiva.
    val immersive = navigating && tab == AppTab.NAV

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (immersive) return@Scaffold
            NavigationBar {
                NavigationBarItem(
                    selected = tab == AppTab.MAP,
                    onClick = { vm.selectTab(AppTab.MAP) },
                    icon = { Icon(Icons.Filled.Map, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_map)) },
                )
                // La tab Navigazione appare solo durante un viaggio attivo.
                if (navigating) {
                    NavigationBarItem(
                        selected = tab == AppTab.NAV,
                        onClick = { vm.selectTab(AppTab.NAV) },
                        icon = { Icon(Icons.Filled.Navigation, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_navigate)) },
                    )
                }
                NavigationBarItem(
                    selected = tab == AppTab.SETTINGS,
                    onClick = { vm.selectTab(AppTab.SETTINGS) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        }
    ) { padding ->
        // La mappa va EDGE-TO-EDGE (anche sotto la status bar): NON applichiamo il
        // top inset al container. Riserviamo solo lo spazio per la bottom bar; gli
        // overlay in alto (search/banner) usano statusBarsPadding per conto loro.
        // Back di sistema gestito gerarchicamente, così non si esce dall'app per
        // sbaglio durante la guida e si torna sempre alla mappa.
        when {
            // Su tab Settings/Nav: il back riporta alla mappa.
            tab != AppTab.MAP -> BackHandler { vm.selectTab(AppTab.MAP) }
            // In anteprima percorso: il back annulla il piano e torna alla mappa libera.
            screen is Screen.Preview -> BackHandler { vm.clearPlan(); vm.backToMap() }
            // In navigazione: il back ferma la guida (non chiude l'app).
            screen is Screen.Driving -> BackHandler { vm.stopDriving() }
            // Mappa libera: nessun handler → il back esce dall'app (comportamento atteso).
        }

        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            when (tab) {
                AppTab.MAP -> MapTab(vm)
                AppTab.NAV -> NavTab(vm)
                AppTab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}

// --- Tab Mappa --------------------------------------------------------------

@Composable
private fun MapTab(vm: NavViewModel) {
    val screen by vm.screen.collectAsState()
    when (val s = screen) {
        is Screen.Preview -> PreviewScreen(vm, s)
        else -> MapScreen(vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapScreen(vm: NavViewModel) {
    val position by vm.currentPosition.collectAsState()
    val bearing by vm.currentBearing.collectAsState()
    val results by vm.searchResults.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.planningMessage.collectAsState()
    val pois by vm.pois.collectAsState()
    val poiCategory by vm.poiCategory.collectAsState()
    val navState by vm.navState.collectAsState()
    val cameraState = remember { MapCameraState() }
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }

    // Se in navigazione, mostriamo il tragitto anche sulla mappa libera, così si
    // possono fare modifiche da qui.
    val navigating = navState is NavigationState.Navigating
    val activeRoute = (navState as? NavigationState.Navigating)?.route
    val plan by vm.tripPlan.collectAsState()

    // Marker mostrati: le tappe del viaggio (destinazione rossa, tappe arancio) +
    // i POI (solo su mappa libera). L'origine non si marca (c'è l'indicatore utente).
    val markers = buildList {
        plan.stops.forEachIndexed { i, stop ->
            if (i == 0) return@forEachIndexed // origine = indicatore utente
            val kind = if (i == plan.stops.lastIndex) MarkerKind.DESTINATION else MarkerKind.WAYPOINT
            add(MapMarker("stop-$i", stop.point, stop.label, kind))
        }
        if (!navigating) {
            pois.forEach { add(MapMarker(it.point.poiId(), it.point, it.name ?: "POI", MarkerKind.POI)) }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { vm.setLocationMode(LocationMode.GPS); vm.startLiveLocation() }
    }

    fun locate() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) { vm.setLocationMode(LocationMode.GPS); vm.startLiveLocation(); cameraState.recenter() }
        else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    Box(Modifier.fillMaxSize()) {
        // Mappa edge-to-edge. route = il tragitto attivo se in navigazione.
        val mapStyle by vm.mapStyle.collectAsState()
        RouteMap(
            route = activeRoute,
            position = position,
            bearing = bearing,
            threeD = false,
            cameraState = cameraState,
            onLongPress = { vm.selectPointOnMap(it) },
            markers = markers,
            onMarkerClick = { vm.selectPoi(it) },
            styleUri = mapStyle,
            modifier = Modifier.fillMaxSize(),
        )

        // Bottom sheet dettagli del luogo selezionato (tap POI / long-press).
        val selected by vm.selectedPlace.collectAsState()
        selected?.let { place ->
            PlaceSheet(
                place = place,
                onNavigate = { vm.navigateToSelected() },
                onAddStop = { vm.addSelectedAsStop() },
                onDismiss = { vm.dismissSelectedPlace() },
            )
        }

        FloatingActionButton(
            onClick = { locate() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.my_location)) }

        // In alto: search bar + sotto la riga delle categorie POI.
        // Quando la SearchBar è COLLASSATA la teniamo sotto la status bar; quando è
        // ESPANSA va a tutto schermo (copre anche la barra notifiche), quindi niente
        // padding superiore.
        var expanded by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .then(if (expanded) Modifier else Modifier.statusBarsPadding())
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = { query = it; vm.search(it) },
                        onSearch = { vm.search(it) },
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        placeholder = { Text(stringResource(R.string.search_destination)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = ""; vm.clearSearch() }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                                }
                            }
                        },
                    )
                },
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                if (busy) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.searching)) },
                        leadingContent = { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                    )
                }
                LazyColumn {
                    items(results) { place ->
                        // La ricerca NON avvia la navigazione: mostra il punto nel
                        // bottom sheet (con azioni Naviga / Aggiungi tappa).
                        ResultRow(place) { expanded = false; vm.selectSearchResult(place) }
                    }
                }
                message?.let {
                    ListItem(headlineContent = { Text(it) })
                }
            }

            // Categorie POI sotto la search bar (solo mappa libera, non in navigazione).
            if (!navigating && !expanded) {
                PoiCategoryBar(
                    selected = poiCategory,
                    onPick = { vm.togglePoiCategory(it) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ResultRow(place: GeocodedPlace, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(place.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// --- Preview ----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewScreen(vm: NavViewModel, screen: Screen.Preview) {
    val route = screen.route
    val plan by vm.tripPlan.collectAsState()
    val results by vm.searchResults.collectAsState()
    val busy by vm.busy.collectAsState()
    val cameraState = remember { MapCameraState() }
    var addQuery by remember { mutableStateOf("") }
    var addExpanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        val mapStyle by vm.mapStyle.collectAsState()
        RouteMap(route = route, position = null, cameraState = cameraState,
            markers = tripMarkers(route),
            onLongPress = { vm.selectPointOnMap(it) }, // anche in preview: aggiungi tappa dalla mappa
            onMarkerClick = { vm.selectPoi(it) },
            styleUri = mapStyle, modifier = Modifier.fillMaxSize())

        // Sheet dettagli anche in preview (per "Aggiungi tappa").
        val selectedPv by vm.selectedPlace.collectAsState()
        selectedPv?.let { place ->
            PlaceSheet(place,
                onNavigate = { vm.navigateToSelected() },
                onAddStop = { vm.addSelectedAsStop() },
                onDismiss = { vm.dismissSelectedPlace() })
        }

        FloatingActionButton(
            onClick = { vm.clearPlan(); vm.backToMap() },
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }

        Card(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Metriche
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Metric(UnitFormatter.formatDistance(route.distanceMeters), stringResource(R.string.metric_distance))
                    Metric(UnitFormatter.formatDuration(route.durationSeconds), stringResource(R.string.metric_duration))
                    Metric(UnitFormatter.formatClock(route.durationSeconds), stringResource(R.string.metric_arrival))
                }

                HorizontalDivider()
                Text(stringResource(R.string.stops_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                // Lista tappe modificabile
                plan.stops.forEachIndexed { i, stop ->
                    val role = when (i) {
                        0 -> stringResource(R.string.stop_origin)
                        plan.stops.lastIndex -> stringResource(R.string.stop_destination)
                        else -> "${i}."
                    }
                    StopRow(
                        role = role,
                        label = stop.label,
                        canMoveUp = i > 0,
                        canMoveDown = i < plan.stops.lastIndex,
                        canRemove = plan.stops.size > 2,
                        onUp = { vm.moveStop(i, i - 1) },
                        onDown = { vm.moveStop(i, i + 1) },
                        onRemove = { vm.removeStop(i) },
                    )
                }

                // Aggiungi tappa (ricerca dedicata)
                SearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = addQuery,
                            onQueryChange = { addQuery = it; vm.search(it) },
                            onSearch = { vm.search(it) },
                            expanded = addExpanded,
                            onExpandedChange = { addExpanded = it },
                            placeholder = { Text(stringResource(R.string.add_stop)) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        )
                    },
                    expanded = addExpanded,
                    onExpandedChange = { addExpanded = it },
                ) {
                    if (busy) ListItem(headlineContent = { Text(stringResource(R.string.searching)) })
                    LazyColumn {
                        items(results) { place ->
                            ResultRow(place) { addExpanded = false; addQuery = ""; vm.addStop(place) }
                        }
                    }
                }

                Button(onClick = vm::startDriving, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Navigation, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.start_navigation))
                }
            }
        }
    }
}

@Composable
private fun StopRow(
    role: String,
    label: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    ListItem(
        leadingContent = { Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        overlineContent = { Text(role) },
        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row {
                if (canMoveUp) IconButton(onClick = onUp) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up))
                }
                if (canMoveDown) IconButton(onClick = onDown) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down))
                }
                if (canRemove) IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove_stop))
                }
            }
        },
    )
}

// --- Tab Navigazione --------------------------------------------------------

@Composable
private fun NavTab(vm: NavViewModel) {
    val navState by vm.navState.collectAsState()
    val screen by vm.screen.collectAsState()
    val destinationLabel = (screen as? Screen.Driving)?.destinationLabel ?: ""
    when (val ns = navState) {
        is NavigationState.Arrived -> ArrivedScreen(destinationLabel, onClose = vm::stopDriving)
        is NavigationState.Navigating -> DrivingScreen(vm, ns, onStop = vm::stopDriving)
        else -> RecalculatingScreen(onStop = vm::stopDriving)
    }
}

@Composable
private fun DrivingScreen(vm: NavViewModel, state: NavigationState.Navigating, onStop: () -> Unit) {
    val progress = state.progress
    val route = state.route
    val step = route.steps.getOrNull(progress.currentStepIndex)
    val nextStep = route.steps.getOrNull(progress.currentStepIndex + 1) ?: step
    val gpsBearing by vm.currentBearing.collectAsState()
    val speedMps by vm.currentSpeedMps.collectAsState()
    val cameraState = remember { MapCameraState() }
    // In navigazione preferiamo il bearing della STRADA (snappato, stabile);
    // fallback su GPS/bussola se non determinabile.
    val bearing = progress.snappedBearingDegrees ?: gpsBearing

    Box(Modifier.fillMaxSize()) {
        val mapStyle by vm.mapStyle.collectAsState()
        RouteMap(route = route, position = progress.snappedLocation, bearing = bearing,
            threeD = true, cameraState = cameraState, markers = tripMarkers(route),
            styleUri = mapStyle, modifier = Modifier.fillMaxSize())

        // Banner manovra unico in alto: freccia + distanza + frase, nome strada e
        // corsie tutto dentro la stessa card.
        ManeuverBanner(
            maneuver = nextStep?.maneuver,
            distanceToManeuverMeters = progress.distanceToNextManeuverMeters,
            roadName = nextStep?.roadName,
            lanes = nextStep?.lanes.orEmpty(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).fillMaxWidth(),
        )

        // Cartello limite velocità (se noto per lo step corrente).
        step?.speedLimitKmh?.let { limit ->
            SpeedLimitSign(
                limit,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp, bottom = 160.dp),
            )
        }

        FloatingActionButton(
            onClick = { cameraState.recenter() },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp, bottom = 160.dp),
        ) { Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.recenter)) }

        // Card inferiore compatta: una riga di metriche + riga azioni.
        Card(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Metric(UnitFormatter.formatDuration(progress.durationRemainingSeconds), stringResource(R.string.metric_eta))
                    Metric(UnitFormatter.formatDistance(progress.distanceRemainingMeters), stringResource(R.string.metric_remaining))
                    Metric(UnitFormatter.formatSpeedKmh(speedMps), stringResource(R.string.metric_current_speed))
                }
                (state.deviation as? Deviation.OffRoute)?.let { off ->
                    Text(stringResource(R.string.off_route, UnitFormatter.formatDistance(off.distanceFromRouteMeters)),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                // Azioni su una riga, compatte: prossimo step (manuale) / salta tappa / termina.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vm.isManualMode) {
                        Button(onClick = { vm.advanceStep() }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.next_step))
                        }
                    }
                    if (vm.hasIntermediateStops) {
                        OutlinedButton(onClick = { vm.skipNextStop() }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.skip_stop))
                        }
                    }
                    FilledTonalButton(onClick = onStop, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Close, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.terminate))
                    }
                }
            }
        }
    }
}

@Composable
private fun ManeuverBanner(
    maneuver: com.enaide.sdk.model.Maneuver?,
    distanceToManeuverMeters: Double,
    roadName: String? = null,
    lanes: List<com.enaide.sdk.model.Lane> = emptyList(),
    modifier: Modifier = Modifier,
) {
    // Tutto dentro un'unica card colorata (primary): freccia + distanza + frase,
    // nome strada e corsie. Niente riquadri separati.
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = modifier,
    ) {
        val ctx = LocalContext.current
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(maneuver?.let { ManeuverText.glyph(it) } ?: "↑", fontSize = 40.sp)
                Column(Modifier.weight(1f)) {
                    Text(UnitFormatter.formatDistance(distanceToManeuverMeters),
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(maneuver?.let { ManeuverText.phrase(ctx, it, roadName) } ?: stringResource(R.string.proceed),
                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // Lane assistant: corsie CENTRATE nella card, separate da divisori.
            if (lanes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f))
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    lanes.forEachIndexed { i, lane ->
                        if (i > 0) {
                            // Striscia tratteggiata verticale tra le corsie.
                            Text("┊", fontSize = 22.sp,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.35f),
                                modifier = Modifier.padding(horizontal = 6.dp))
                        }
                        Text(laneGlyph(lane.directions), fontSize = 24.sp,
                            color = if (lane.active || lane.valid) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f),
                            fontWeight = if (lane.active) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
private fun ArrivedScreen(destinationLabel: String, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Place, contentDescription = null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.arrived_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(destinationLabel, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onClose) { Text(stringResource(R.string.new_trip)) }
    }
}

@Composable
private fun RecalculatingScreen(onStop: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.recalculating), style = MaterialTheme.typography.titleMedium)
        FilledTonalButton(onClick = onStop) { Text(stringResource(R.string.terminate)) }
    }
}

// --- Tab Impostazioni -------------------------------------------------------

@Composable
private fun SettingsScreen(vm: NavViewModel) {
    val vehicle by vm.vehicleKind.collectAsState()
    val mode by vm.locationMode.collectAsState()
    val wrongTurn by vm.simulateWrongTurn.collectAsState()
    val voice by vm.voiceEnabled.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) { vm.setLocationMode(LocationMode.GPS); vm.startLiveLocation() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding() // il container non applica più il top inset
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        // Mezzo
        SettingsSection(stringResource(R.string.settings_vehicle)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VehicleKind.entries.forEach { kind ->
                    FilterChip(
                        selected = vehicle == kind, onClick = { vm.setVehicleKind(kind) },
                        leadingIcon = { Icon(kind.icon(), contentDescription = null, Modifier.size(18.dp)) },
                        label = { Text(kind.label()) },
                    )
                }
            }
            if (vehicle == VehicleKind.TRUCK) TruckForm(vm)
        }

        // Posizione
        SettingsSection(stringResource(R.string.settings_position)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == LocationMode.GPS,
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) { vm.setLocationMode(LocationMode.GPS); vm.startLiveLocation() }
                        else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    },
                    label = { Text(stringResource(R.string.gps_real)) },
                )
                FilterChip(
                    selected = mode == LocationMode.SIMULATED,
                    onClick = { vm.setLocationMode(LocationMode.SIMULATED) },
                    label = { Text(stringResource(R.string.simulated)) },
                )
                FilterChip(
                    selected = mode == LocationMode.MANUAL,
                    onClick = { vm.setLocationMode(LocationMode.MANUAL) },
                    label = { Text(stringResource(R.string.manual_mode)) },
                )
            }
            if (mode == LocationMode.SIMULATED) {
                SettingRow(stringResource(R.string.simulate_error), wrongTurn) { vm.setSimulateWrongTurn(it) }
            }
            // L'origine manuale è impostabile fuori dal GPS reale (simulato o manuale).
            if (mode != LocationMode.GPS) OriginPicker(vm)
        }

        // Stile mappa
        val mapStyle by vm.mapStyle.collectAsState()
        SettingsSection(stringResource(R.string.settings_map)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mapStyle == com.enaide.sdk.map.MapStyles.VECTOR_LIBERTY,
                    onClick = { vm.setMapStyle(com.enaide.sdk.map.MapStyles.VECTOR_LIBERTY) },
                    label = { Text(stringResource(R.string.map_vector_light)) },
                )
                FilterChip(
                    selected = mapStyle == com.enaide.sdk.map.MapStyles.VECTOR_DARK,
                    onClick = { vm.setMapStyle(com.enaide.sdk.map.MapStyles.VECTOR_DARK) },
                    label = { Text(stringResource(R.string.map_vector_dark)) },
                )
                FilterChip(
                    selected = mapStyle == com.enaide.sdk.map.MapStyles.RASTER_OSM,
                    onClick = { vm.setMapStyle(com.enaide.sdk.map.MapStyles.RASTER_OSM) },
                    label = { Text(stringResource(R.string.map_raster)) },
                )
            }
        }

        // Mappe offline
        val offlineRegions by vm.offlineRegions.collectAsState()
        val offlineProgress by vm.offlineProgress.collectAsState()
        LaunchedEffect(Unit) { vm.refreshOfflineRegions() }
        SettingsSection(stringResource(R.string.settings_offline)) {
            if (offlineProgress != null) {
                Text(stringResource(R.string.offline_downloading, offlineProgress ?: 0))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { (offlineProgress ?: 0) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Button(onClick = { vm.downloadCurrentArea() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.offline_download_area))
                }
            }
            if (offlineRegions.isEmpty()) {
                Text(stringResource(R.string.offline_no_regions), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            } else {
                offlineRegions.forEach { region ->
                    ListItem(
                        headlineContent = { Text(region.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Filled.Map, contentDescription = null) },
                        trailingContent = {
                            IconButton(onClick = { vm.deleteOfflineRegion(region.id) }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.offline_delete))
                            }
                        },
                    )
                }
            }
        }

        // Voce
        SettingsSection(stringResource(R.string.settings_voice)) {
            SettingRow(stringResource(R.string.settings_voice_toggle), voice) { vm.setVoiceEnabled(it) }
        }

        // Server
        SettingsSection(stringResource(R.string.settings_server)) {
            LabeledValue(stringResource(R.string.server_routing), vm.routingEndpoint)
            LabeledValue(stringResource(R.string.server_geocoding), vm.geocodingEndpoint)
        }
    }
}

@Composable
private fun OriginPicker(vm: NavViewModel) {
    val origin by vm.customOrigin.collectAsState()
    val results by vm.originResults.collectAsState()
    var query by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.origin_label), style = MaterialTheme.typography.bodyMedium)
        origin?.let {
            ListItem(
                headlineContent = { Text(it.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Icon(Icons.Filled.Place, contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = { query = ""; vm.clearCustomOrigin() }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.origin_remove))
                    }
                },
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; vm.searchOrigin(it) },
            placeholder = { Text(stringResource(R.string.origin_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        results.forEach { place ->
            ListItem(
                headlineContent = { Text(place.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.clickable { query = ""; vm.setCustomOrigin(place) },
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
        HorizontalDivider()
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// --- Componenti riusabili ---------------------------------------------------

@Composable
private fun VehicleKind.label(): String = stringResource(when (this) {
    VehicleKind.CAR -> R.string.vehicle_car
    VehicleKind.PEDESTRIAN -> R.string.vehicle_pedestrian
    VehicleKind.BICYCLE -> R.string.vehicle_bicycle
    VehicleKind.TRUCK -> R.string.vehicle_truck
})

private fun VehicleKind.icon(): ImageVector = when (this) {
    VehicleKind.CAR -> Icons.Filled.DirectionsCar
    VehicleKind.PEDESTRIAN -> Icons.AutoMirrored.Filled.DirectionsWalk
    VehicleKind.BICYCLE -> Icons.AutoMirrored.Filled.DirectionsBike
    VehicleKind.TRUCK -> Icons.Filled.LocalShipping
}

@Composable
private fun TruckForm(vm: NavViewModel) {
    val dim by vm.truckDimensions.collectAsState()
    fun update(t: (com.enaide.sdk.model.VehicleDimensions) -> com.enaide.sdk.model.VehicleDimensions) {
        vm.setTruckDimensions(t(dim))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(stringResource(R.string.truck_height), dim.heightMeters) { v -> update { it.copy(heightMeters = v) } }
        NumberField(stringResource(R.string.truck_weight), dim.weightKg) { v -> update { it.copy(weightKg = v) } }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NumberField(
    label: String, value: Double?, onValue: (Double?) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text, onValueChange = { text = it; onValue(it.toDoubleOrNull()) },
        label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f),
    )
}

@Composable
private fun PoiCategoryBar(
    selected: PoiCategory?,
    onPick: (PoiCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
    ) {
        items(PoiCategory.entries) { cat ->
            // ElevatedFilterChip con icona: più leggibile e "pillola" stile Maps.
            androidx.compose.material3.ElevatedFilterChip(
                selected = selected == cat,
                onClick = { onPick(cat) },
                leadingIcon = { Icon(cat.icon(), contentDescription = null, Modifier.size(18.dp)) },
                label = { Text(cat.label()) },
            )
        }
    }
}

private fun PoiCategory.icon(): ImageVector = when (this) {
    PoiCategory.FUEL -> Icons.Filled.LocalGasStation
    PoiCategory.CHARGING -> Icons.Filled.EvStation
    PoiCategory.PARKING -> Icons.Filled.LocalParking
    PoiCategory.FOOD -> Icons.Filled.Restaurant
    PoiCategory.SUPERMARKET -> Icons.Filled.ShoppingCart
    PoiCategory.ATM -> Icons.Filled.LocalAtm
    PoiCategory.PHARMACY -> Icons.Filled.LocalPharmacy
    PoiCategory.HOSPITAL -> Icons.Filled.LocalHospital
    PoiCategory.HOTEL -> Icons.Filled.Hotel
    PoiCategory.TOILETS -> Icons.Filled.Wc
    PoiCategory.ATTRACTION -> Icons.Filled.Attractions
}

@Composable
private fun PoiCategory.label(): String = stringResource(when (this) {
    PoiCategory.FUEL -> R.string.poi_fuel
    PoiCategory.CHARGING -> R.string.poi_charging
    PoiCategory.PARKING -> R.string.poi_parking
    PoiCategory.FOOD -> R.string.poi_food
    PoiCategory.SUPERMARKET -> R.string.poi_supermarket
    PoiCategory.ATM -> R.string.poi_atm
    PoiCategory.PHARMACY -> R.string.poi_pharmacy
    PoiCategory.HOSPITAL -> R.string.poi_hospital
    PoiCategory.HOTEL -> R.string.poi_hotel
    PoiCategory.TOILETS -> R.string.poi_toilets
    PoiCategory.ATTRACTION -> R.string.poi_attraction
})

private fun laneGlyph(dirs: Set<LaneDirection>): String = when {
    LaneDirection.THROUGH in dirs -> "↑"
    LaneDirection.SLIGHT_LEFT in dirs -> "↖"
    LaneDirection.LEFT in dirs || LaneDirection.SHARP_LEFT in dirs -> "←"
    LaneDirection.SLIGHT_RIGHT in dirs -> "↗"
    LaneDirection.RIGHT in dirs || LaneDirection.SHARP_RIGHT in dirs -> "→"
    LaneDirection.REVERSE in dirs -> "↓"
    else -> "•"
}

/** Bottom sheet con i dettagli di un luogo selezionato + azioni di navigazione. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceSheet(
    place: NavViewModel.SelectedPlace,
    onNavigate: () -> Unit,
    onAddStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(place.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            place.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onNavigate, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Navigation, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.navigate_here))
            }
            OutlinedButton(onClick = onAddStop, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.add_as_stop))
            }
        }
    }
}

/** Marker tappa/destinazione dai waypoint del route (l'origine non si marca). */
private fun tripMarkers(route: com.enaide.sdk.model.Route): List<MapMarker> =
    route.waypoints.mapIndexedNotNull { i, p ->
        when {
            i == 0 -> null // origine = indicatore utente
            i == route.waypoints.lastIndex -> MapMarker("dest", p, "", MarkerKind.DESTINATION)
            else -> MapMarker("wp-$i", p, "", MarkerKind.WAYPOINT)
        }
    }

/** Cartello limite velocità stile europeo: cerchio bianco, bordo rosso, numero nero. */
@Composable
private fun SpeedLimitSign(limitKmh: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(56.dp)
            .background(Color.White, CircleShape)
            .border(width = 5.dp, color = Color(0xFFE53935), shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$limitKmh", color = Color.Black, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
