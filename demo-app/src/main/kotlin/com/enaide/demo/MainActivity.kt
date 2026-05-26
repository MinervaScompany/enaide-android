package com.enaide.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enaide.sdk.format.UnitFormatter
import com.enaide.sdk.geocoding.GeocodedPlace
import com.enaide.sdk.map.EnaideTheme
import com.enaide.sdk.map.MapCameraState
import com.enaide.sdk.map.RouteMap
import com.enaide.sdk.model.Deviation
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
        super.onCreate(savedInstanceState)
        voice = VoiceGuidance(this)
        setContent {
            EnaideTheme {
                val vm: NavViewModel = viewModel()
                val voiceEnabled by vm.voiceEnabled.collectAsState()

                LaunchedEffect(Unit) {
                    vm.events.collect { event ->
                        when (event) {
                            is NavigationEvent.SpokenInstructionTriggered ->
                                if (voiceEnabled) voice.speak(event.instruction.text)
                            is NavigationEvent.Started -> voice.reset()
                            is NavigationEvent.Arrived ->
                                if (voiceEnabled) voice.speak("Sei arrivato a destinazione.")
                            is NavigationEvent.OffRouteConfirmed -> {
                                if (voiceEnabled) voice.speak("Ricalcolo del percorso.")
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
    val navigating = screen is Screen.Driving || screen is Screen.Preview

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == AppTab.MAP,
                    onClick = { vm.selectTab(AppTab.MAP) },
                    icon = { Icon(Icons.Filled.Map, contentDescription = null) },
                    label = { Text("Mappa") },
                )
                // La tab Navigazione appare solo durante un viaggio attivo.
                if (navigating) {
                    NavigationBarItem(
                        selected = tab == AppTab.NAV,
                        onClick = { vm.selectTab(AppTab.NAV) },
                        icon = { Icon(Icons.Filled.Navigation, contentDescription = null) },
                        label = { Text("Naviga") },
                    )
                }
                NavigationBarItem(
                    selected = tab == AppTab.SETTINGS,
                    onClick = { vm.selectTab(AppTab.SETTINGS) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Impostazioni") },
                )
            }
        }
    ) { padding ->
        // Un solo punto in cui applichiamo il padding dello Scaffold: il container.
        // Le schermate dentro usano fillMaxSize senza padding manuali.
        Box(Modifier.fillMaxSize().padding(padding)) {
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
    val cameraState = remember { MapCameraState() }
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }

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
        // Mappa edge-to-edge: riempie tutto lo schermo, anche dietro la bottom bar.
        RouteMap(
            route = null,
            position = position,
            bearing = bearing,
            threeD = false,
            cameraState = cameraState,
            onLongPress = { vm.selectPointOnMap(it) },
            modifier = Modifier.fillMaxSize(),
        )

        FloatingActionButton(
            onClick = { locate() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.MyLocation, contentDescription = "La mia posizione") }

        var expanded by remember { mutableStateOf(false) }
        SearchBar(
            modifier = Modifier.align(Alignment.TopCenter),
            inputField = {
                SearchBarDefaults.InputField(
                    query = query,
                    onQueryChange = { query = it; vm.search(it) },
                    onSearch = { vm.search(it) },
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    placeholder = { Text("Cerca destinazione") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; vm.clearSearch() }) {
                                Icon(Icons.Filled.Close, contentDescription = "Pulisci")
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
                    headlineContent = { Text("Cerco…") },
                    leadingContent = { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                )
            }
            LazyColumn {
                items(results) { place ->
                    ResultRow(place) { expanded = false; vm.planTo(place) }
                }
            }
            message?.let {
                ListItem(headlineContent = { Text(it, color = MaterialTheme.colorScheme.error) })
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

@Composable
private fun PreviewScreen(vm: NavViewModel, screen: Screen.Preview) {
    val route = screen.route
    val cameraState = remember { MapCameraState() }

    Box(Modifier.fillMaxSize()) {
        RouteMap(route = route, position = null, cameraState = cameraState, modifier = Modifier.fillMaxSize())

        FloatingActionButton(
            onClick = vm::backToMap,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }

        Card(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(screen.destinationLabel, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Metric(UnitFormatter.formatDistance(route.distanceMeters), "distanza")
                    Metric(UnitFormatter.formatDuration(route.durationSeconds), "durata")
                    Metric(UnitFormatter.formatEta(route.durationSeconds).removePrefix("Arrivo alle "), "arrivo")
                }
                Button(onClick = vm::startDriving, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Navigation, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Avvia")
                }
            }
        }
    }
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
    val bearing by vm.currentBearing.collectAsState()
    val speedMps by vm.currentSpeedMps.collectAsState()
    val cameraState = remember { MapCameraState() }

    Box(Modifier.fillMaxSize()) {
        RouteMap(route = route, position = progress.snappedLocation, bearing = bearing,
            threeD = true, cameraState = cameraState, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ManeuverBanner(nextStep?.maneuver, progress.distanceToNextManeuverMeters)
            nextStep?.roadName?.let { road ->
                Card {
                    Text(road, Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }

        FloatingActionButton(
            onClick = { cameraState.recenter() },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp, bottom = 160.dp),
        ) { Icon(Icons.Filled.MyLocation, contentDescription = "Ricentra") }

        Card(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric(UnitFormatter.formatDuration(progress.durationRemainingSeconds), "all'arrivo")
                    Metric(UnitFormatter.formatDistance(progress.distanceRemainingMeters), "rimanenti")
                    Metric(UnitFormatter.formatSpeedKmh(speedMps), "attuale")
                }
                if (state.deviation is Deviation.OffRoute) {
                    val off = state.deviation as Deviation.OffRoute
                    Text("Fuori percorso (${UnitFormatter.formatDistance(off.distanceFromRouteMeters)})",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Close, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Termina")
                }
            }
        }
    }
}

@Composable
private fun ManeuverBanner(maneuver: com.enaide.sdk.model.Maneuver?, distanceToManeuverMeters: Double) {
    // Card nativa colorata con l'API ufficiale CardDefaults (colore = primary,
    // brand del navigatore). Nessuna forma/elevazione custom.
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(maneuver?.let { ManeuverText.glyph(it) } ?: "↑", fontSize = 44.sp)
            Column {
                Text(UnitFormatter.formatDistance(distanceToManeuverMeters),
                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(maneuver?.let { ManeuverText.phrase(it, null) } ?: "Prosegui",
                    style = MaterialTheme.typography.titleMedium)
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
        Text("Sei arrivato", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(destinationLabel, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onClose) { Text("Nuovo viaggio") }
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
        Text("Ricalcolo del percorso…", style = MaterialTheme.typography.titleMedium)
        FilledTonalButton(onClick = onStop) { Text("Termina") }
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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Impostazioni", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        // Mezzo
        SettingsSection("Mezzo") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VehicleKind.entries.forEach { kind ->
                    FilterChip(
                        selected = vehicle == kind, onClick = { vm.setVehicleKind(kind) },
                        leadingIcon = { Icon(kind.icon(), contentDescription = null, Modifier.size(18.dp)) },
                        label = { Text(kind.displayName) },
                    )
                }
            }
            if (vehicle == VehicleKind.TRUCK) TruckForm(vm)
        }

        // Posizione
        SettingsSection("Posizione") {
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
                    label = { Text("GPS reale") },
                )
                FilterChip(
                    selected = mode == LocationMode.SIMULATED,
                    onClick = { vm.setLocationMode(LocationMode.SIMULATED); vm.stopLiveLocation() },
                    label = { Text("Simulato") },
                )
            }
            if (mode == LocationMode.SIMULATED) {
                SettingRow("Simula errore (test reroute)", wrongTurn) { vm.setSimulateWrongTurn(it) }
                OriginPicker(vm)
            }
        }

        // Voce
        SettingsSection("Guida vocale") {
            SettingRow("Annunci vocali (TTS)", voice) { vm.setVoiceEnabled(it) }
        }

        // Server
        SettingsSection("Server (sola lettura)") {
            LabeledValue("Routing (Valhalla)", vm.routingEndpoint)
            LabeledValue("Geocoding (Nominatim)", vm.geocodingEndpoint)
        }
    }
}

@Composable
private fun OriginPicker(vm: NavViewModel) {
    val origin by vm.customOrigin.collectAsState()
    val results by vm.originResults.collectAsState()
    var query by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Origine di partenza", style = MaterialTheme.typography.bodyMedium)
        origin?.let {
            ListItem(
                headlineContent = { Text(it.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Icon(Icons.Filled.Place, contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = { query = ""; vm.clearCustomOrigin() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Rimuovi origine")
                    }
                },
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; vm.searchOrigin(it) },
            placeholder = { Text("Cerca un punto di partenza") },
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
        NumberField("Altezza (m)", dim.heightMeters) { v -> update { it.copy(heightMeters = v) } }
        NumberField("Peso (kg)", dim.weightKg) { v -> update { it.copy(weightKg = v) } }
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
private fun Metric(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
