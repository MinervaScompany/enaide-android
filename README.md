# enaide-sdk

SDK nativo Android per la navigazione turn-by-turn. Pensato per essere
ribrandizzato e integrato in app di terze parti.

> **Stato:** v0.1 — scaffolding e MVP del routing + state machine. Non ancora
> pronto per la produzione. La UI di mappa (MapLibre) e l'integrazione con il
> provider di posizione (FusedLocation) verranno in iterazioni successive.

## Struttura del repository

```
.
├── enaide-sdk/             Core: routing + state machine + modello (AAR, sempre richiesto)
├── enaide-sdk-geocoding/   Opzionale: geocoding Nominatim (forward/reverse)
├── enaide-sdk-simulation/  Opzionale: sorgente GPS simulata con fisica di guida
├── enaide-sdk-map/         Opzionale: mappa MapLibre + theme/colori semantici
├── enaide-sdk-tts/         Opzionale: guida vocale (TextToSpeech)
├── enaide-sdk-vehicle/     Opzionale: profilo mezzo + sagoma (auto/piedi/bici/camion)
├── demo-app/               App Android d'esempio che usa core + tutti i moduli
├── gradle/
│   └── libs.versions.toml   Catalogo versioni dipendenze
├── build.gradle.kts   Build root
└── settings.gradle.kts
```

I moduli `enaide-sdk-*` sono **opzionali ed escludibili**: dipendono dal core
ma il core non dipende da loro. Una fornitura proprietaria include solo gli AAR
che le servono (es. solo core + geocoding, senza mappa/TTS).

## Requisiti

- JDK 17 (Embedded JDK di Android Studio Ladybug+ va bene)
- Android SDK Platform 36
- Min Android API: 26 (Android 8.0)
- Gradle 8.11.x — il `gradle-wrapper.properties` è già nel repo, alla sync
  Android Studio scarica il wrapper automaticamente. In alternativa, dal
  terminale: `gradle wrapper --gradle-version 8.11.1`.

## Build e test

```bash
# Build dell'SDK e dell'app demo
./gradlew :enaide-sdk:assembleRelease
./gradlew :demo-app:assembleDebug

# Test unitari dell'SDK (pura logica JVM, nessun emulatore)
./gradlew :enaide-sdk:testDebugUnitTest
```

## Architettura

Le scelte di alto livello, in due righe ciascuna.

- **No dipendenze occulte.** L'SDK non chiama servizi proprietari (Google Play,
  Firebase). Tutto passa per HTTP standard verso un endpoint Valhalla.
- **No location provider interno.** L'integratore decide come ottenere i fix
  GPS (FusedLocationProviderClient di GMS, LocationManager nativo, mock per
  test) e li passa a `EnaideNavigator.updateLocation(...)`. Questo tiene
  l'SDK testabile in puro JVM e libero da GMS.
- **Stato come `StateFlow`.** `EnaideNavigator.state` è hot, collezionabile da
  Compose, Lifecycle, RxJava (con interop). Sealed class esaustiva.
- **Routing intercambiabile.** `RoutingClient` è un'interfaccia. Default
  `ValhallaRoutingClient`. Puoi iniettarne un altro (OSRM, mock di test)
  costruendo `EnaideNavigatorImpl` direttamente, oppure aspettando il
  costruttore con DI nella v0.2.

## Quickstart (Kotlin)

```kotlin
import com.enaide.sdk.EnaideConfig
import com.enaide.sdk.EnaideNavigator
import com.enaide.sdk.model.GeoPoint
import com.enaide.sdk.model.NavigationState
import com.enaide.sdk.model.UserLocation
import com.enaide.sdk.routing.RouteResult

// 1) Crea l'SDK. Endpoint default: Valhalla pubblico FOSSGIS (solo dev).
val navigator = EnaideNavigator.create(EnaideConfig(
    routingBaseUrl = "https://valhalla1.openstreetmap.de",
    userAgent = "MyApp/1.0 (contatto@miaapp.it)",
))

// 2) Calcola un percorso (operazione di rete sospensiva).
val result = navigator.computeRoute(
    waypoints = listOf(
        GeoPoint(45.4642, 9.1900),  // Milano Duomo
        GeoPoint(45.4773, 9.1815),  // Cadorna
    ),
)
val route = (result as? RouteResult.Success)?.route ?: error("routing fallito: $result")

// 3) Avvia navigazione.
navigator.start(route)

// 4) Per ogni fix GPS in arrivo dalla tua sorgente di posizione preferita:
navigator.updateLocation(UserLocation(
    point = GeoPoint(45.4642, 9.1900),
    timestampEpochMillis = System.currentTimeMillis(),
    speedMetersPerSecond = 8.3,
))

// 5) Osserva lo stato (es. da una ViewModel):
navigator.state.collect { state ->
    when (state) {
        is NavigationState.Navigating -> {
            // aggiorna UI: state.progress, state.currentVisualInstruction
            state.pendingSpokenInstruction?.let { /* invia a TTS */ }
        }
        is NavigationState.Rerouting -> {
            // ricalcola route da state.triggeredAt verso la destinazione originale
        }
        is NavigationState.Arrived -> {
            // utente a destinazione
        }
        else -> Unit
    }
}
```

## Permission Android richiesti dall'app integratrice

L'SDK **non** dichiara permission proprie nel manifesto, così controlli tu
esattamente cosa chiedere all'utente. Per la navigazione reale ti servono:

- `android.permission.INTERNET` — chiamate al routing.
- `android.permission.ACCESS_FINE_LOCATION` — posizione GPS precisa.
- `android.permission.ACCESS_COARSE_LOCATION` — fallback.
- `android.permission.FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION`
  (API 34+) — per continuare la navigazione quando l'app è in background.

## Endpoint Valhalla

Per default `EnaideConfig.routingBaseUrl = "https://valhalla1.openstreetmap.de"`,
ovvero l'istanza pubblica di FOSSGIS. È rate-limitata, soggetta a downtime e
richiede attribuzione OSM nelle app pubbliche.

Per produzione: self-hosting (Docker image ufficiale di Valhalla, ~100GB su
disco per il planet OSM globale, molto meno per una singola nazione). Cambia
solo la stringa di `EnaideConfig`.

## Roadmap

- [x] Routing + state machine + unit test
- [x] Geocoding Nominatim (forward + reverse) — `com.enaide.sdk.geocoding`
- [x] Architettura event-centric: `events` (uscita) + `dispatch(NavigationCommand)` (ingresso)
- [x] Comandi: Recalculate, SetDestination, AddWaypoint, ReplaceRoute, UpdateProfile (mezzo in marcia)
- [x] Re-routing automatico su deviazione confermata (evento → comando Recalculate)
- [x] Mappa MapLibre 3D nella demo (polyline + freccia posizione + camera prospettica + interazioni)
- [x] Guida vocale TTS (agganciata al bus di eventi)
- [x] GPS reale (`LocationManager`) + permessi runtime, switch reale/simulato
- [x] Simulatore con fisica di guida realistica + opzione errore-percorso (test reroute)
- [x] Profilo mezzo + sagoma + navigazione a piedi/bici/camion (`enaide-sdk-vehicle`)
- [x] POI con provider intercambiabile (Overpass/OSM) — `enaide-sdk-poi`
- [x] Pianificazione viaggio multi-tappa (`TripPlan`) + editing in mappa
- [x] Localizzazione testi (strings.xml IT/EN, lingua Valhalla = locale)
- [ ] Navigazione "GPS-less" (avanzamento manuale step, utile a piedi)
- [ ] Aggiornamenti traffico/messaggi runtime (sealed command/event già estensibili)
- [ ] **Server MCP** per pilotare l'SDK da agenti AI (vedi `DOCS.md` §17)
- [ ] Estrazione di mappa/TTS/location in moduli opzionali (`enaide-sdk-ui`, `-tts`, `-location`)
- [ ] Componenti Compose pronti all'uso: lane guidance, banner istruzioni
- [ ] Multi-stop con ottimizzazione VROOM (parte fleet)
- [ ] Offline tile management + Valhalla offline (Tilezen Mjolnir tiles locali)
- [ ] Porting iOS (Swift, struttura speculare) — vedi `DOCS.md` §16
