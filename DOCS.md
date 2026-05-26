# enaide-sdk — Documentazione di riferimento

Documento di riferimento completo per integratori e contributori dell'SDK
`enaide-sdk` (Android). Tutta l'API pubblica è coperta, con esempi di codice
funzionanti, modello di dominio dettagliato, e note operative per ogni
sottosistema. Per la guida rapida vedi `README.md`.

> Versione: v0.3. Lo stato di ogni capacità è annotato nel testo. Le sezioni
> 1–14 descrivono il core v0.1; le sezioni 15–16 le aggiunte v0.2/v0.3
> (geocoding, eventi, GPS, TTS, mappa) e la mappa per il porting iOS.

---

## Indice

1. [Architettura](#1-architettura)
2. [Ciclo di vita dell'integrazione](#2-ciclo-di-vita-dellintegrazione)
3. [Configurazione (EnaideConfig)](#3-configurazione-enaideconfig)
4. [API pubblica: EnaideNavigator](#4-api-pubblica-enaidenavigator)
5. [Modello di dominio](#5-modello-di-dominio)
6. [Sottosistema di routing](#6-sottosistema-di-routing)
7. [State machine di navigazione](#7-state-machine-di-navigazione)
8. [Componenti di supporto](#8-componenti-di-supporto)
9. [Testing e simulazione](#9-testing-e-simulazione)
10. [Estendere l'SDK](#10-estendere-lsdk)
11. [Integrazione Android Auto / CarPlay](#11-integrazione-android-auto--carplay)
12. [Permission, threading, performance](#12-permission-threading-performance)
13. [Roadmap](#13-roadmap)
14. [Glossario](#14-glossario)
15. [Funzionalità v0.3 (geocoding, eventi, GPS, TTS, mappa)](#15-funzionalità-v03)
16. [Mappa per il porting iOS](#16-mappa-per-il-porting-ios)
17. [Idea futura: server MCP](#17-idea-futura-server-mcp-per-lsdk)
18. [API esterne: reference e note](#18-api-esterne-reference-e-note-operative)

---

## 1. Architettura

`enaide-sdk` è organizzato in cinque sottosistemi, accoppiati il meno possibile
fra loro:

- **API pubblica** (`com.enaide.sdk`): `EnaideNavigator` come entry point,
  `EnaideConfig` per la configurazione. Tutto ciò che vedono i clienti integratori.
- **Modello di dominio** (`com.enaide.sdk.model`): data class e sealed
  immutabili. Nessuna logica di business qui — solo strutture. È il "linguaggio"
  in cui gli altri sottosistemi parlano fra loro.
- **Routing** (`com.enaide.sdk.routing`): comunicazione con il motore di
  calcolo percorsi. Interfaccia `RoutingClient`, implementazione di default
  `ValhallaRoutingClient` puntata all'endpoint pubblico FOSSGIS.
- **Core di navigazione** (`com.enaide.sdk.core`): la state machine.
  `NavigationController` orchestra `LocationSnapper`, `DeviationDetector`,
  `InstructionTrigger`, `GeoUtils`.
- **Helpers** (`com.enaide.sdk.simulation`, `com.enaide.sdk.format`):
  `SimulatedLocationSource` (testing), `UnitFormatter` (UI).

Il punto chiave: la state machine **non sa nulla** di Android, GPS, mappe, TTS.
È pura logica Kotlin testabile su JVM. Tutta la "Androidità" sta nei moduli
periferici che il cliente integratore wira (FusedLocation, MapLibre, TextToSpeech).

Visivamente:

```
         ┌──────────────────────────────┐
         │     Cliente integratore      │
         │  (App Android, eventualmente │
         │   Android Auto, eventual.    │
         │   Wear OS, eventual. AAOS)   │
         └──────────────┬───────────────┘
                        │ usa
                        ▼
         ┌──────────────────────────────┐
         │       EnaideNavigator        │
         │   (interfaccia pubblica)     │
         └─┬──────────────────┬─────────┘
           │                  │
           ▼                  ▼
  ┌────────────────┐   ┌──────────────────┐
  │ RoutingClient  │   │ NavigationCtrl.  │
  │ (Valhalla HTTP)│   │  (state machine) │
  └────────────────┘   └────┬─────────────┘
                            │
                            ▼
              ┌────────────────────────────┐
              │ Snapper · Deviation · Trig │
              │       · GeoUtils           │
              └────────────────────────────┘
```

I clienti integratori che vogliono customizzare possono:

- Sostituire `RoutingClient` (es. usare OSRM invece di Valhalla, o un client
  cached, o un mock di test) — l'interfaccia è esposta pubblicamente.
- Scegliere una sorgente di posizione qualsiasi (FusedLocation di Google Play,
  `android.location.LocationManager`, GPS Bluetooth esterno, simulatore).
- Implementare la UI mappa con il framework preferito (MapLibre, Mapbox,
  custom OpenGL). L'SDK non impone alcuna scelta di rendering.

---

## 2. Ciclo di vita dell'integrazione

Sequenza tipica end-to-end:

1. L'app costruisce un `EnaideNavigator` una sola volta (di solito in un
   `ViewModel`, un singleton DI, o nell'`Application.onCreate`).
2. Quando l'utente richiede una navigazione, l'app chiama
   `navigator.computeRoute(...)`, sospensiva. Ottiene un `Route`.
3. L'app chiama `navigator.start(route)`. Lo stato passa a
   `NavigationState.Navigating`.
4. L'app collega la propria sorgente di posizione e ad ogni fix chiama
   `navigator.updateLocation(...)`. Lo stato si aggiorna su `navigator.state`.
5. L'app osserva `navigator.state` (StateFlow) e:
   - aggiorna la UI in base a `RouteProgress`,
   - mostra `currentVisualInstruction` nel banner,
   - invia `pendingSpokenInstruction` al TTS,
   - reagisce a `Rerouting` chiamando di nuovo `computeRoute` e `start`,
   - reagisce a `Arrived` chiudendo la sessione.
6. Quando l'utente termina o annulla, l'app chiama `navigator.stop()`.

L'SDK è progettato per essere safe da chiamare da qualsiasi thread: tutti i
metodi di mutazione sono serializzati internamente da un `Mutex`. `state` è uno
`StateFlow` hot collezionabile da Compose, Lifecycle, RxJava (con interop),
oppure ascoltabile imperativo via `state.value`.

---

## 3. Configurazione (EnaideConfig)

`EnaideConfig` è una `data class` con valori di default ragionevoli. Costruisci
un'istanza personalizzata solo se devi sovrascrivere qualcosa.

```kotlin
val config = EnaideConfig(
    routingBaseUrl = "https://valhalla.miaazienda.it",  // tuo self-hosted
    userAgent = "MiaApp/2.3 (it.miaazienda.app)",
    requestTimeout = 20.seconds,
    defaultProfile = TransportProfile.TRUCK,
    offRouteThresholdMeters = 35.0,
    offRouteConfirmationCount = 4,
)
val navigator = EnaideNavigator.create(config)
```

**Campi disponibili**

- `routingBaseUrl` — endpoint Valhalla. Default: `https://valhalla1.openstreetmap.de`
  (FOSSGIS pubblico). In produzione punta sempre al tuo self-hosted o a un
  provider commerciale (Stadia Maps, Mapbox tile, ecc.).
- `userAgent` — header HTTP `User-Agent` mandato a Valhalla. FOSSGIS richiede
  uno UA identificativo nelle policy: usa il nome dell'app + email di contatto.
- `requestTimeout` — timeout per ogni chiamata HTTP (connect/read/socket). Default 15s.
- `defaultProfile` — profilo di trasporto usato quando `computeRoute` non lo specifica.
- `offRouteThresholdMeters` — distanza dal percorso oltre la quale un fix è
  considerato "fuori". Default 30m. Sotto questa soglia il deviation detector
  resta inattivo.
- `offRouteConfirmationCount` — quanti fix consecutivi off-route servono per
  dichiarare deviazione. Default 3. Alza questo numero se il tuo GPS è
  particolarmente rumoroso (canyon urbani, copertura scarsa).

I valori di soglia di deviazione vanno tarati sul campo. Le ultime righe del
paragrafo sul deviation detector contengono una calibrazione di partenza.

---

## 4. API pubblica: EnaideNavigator

`EnaideNavigator` è l'interfaccia su cui ruota tutto. Si costruisce via
`EnaideNavigator.create(config)`.

### 4.1 Proprietà

- `state: StateFlow<NavigationState>` — flusso reattivo dello stato corrente.
  Hot, replay 1. Sicuro da osservare da Compose, ViewModel, Lifecycle.

### 4.2 Metodi

```kotlin
suspend fun computeRoute(
    waypoints: List<GeoPoint>,
    profile: TransportProfile? = null,
    options: RouteOptions = RouteOptions.Default,
): RouteResult

fun start(route: Route)
fun updateLocation(location: UserLocation)
fun stop()
```

**`computeRoute`** è una funzione sospensiva di rete. Accetta una lista di
waypoint (origine, intermedi opzionali, destinazione). Se `profile == null`,
usa `EnaideConfig.defaultProfile`. Ritorna un `RouteResult` sealed:
`Success(route, alternatives)` o `Failure(error)`.

**`start`** avvia la navigazione lungo il route. È idempotente: chiamarla due
volte con lo stesso route non causa effetti collaterali. Chiamarla con un altro
route sostituisce immediatamente quello in corso. Non lancia mai eccezioni
(operazione interna locale).

**`updateLocation`** spinge un fix nella state machine. Va chiamata ad ogni
posizione che ricevi dalla tua sorgente preferita. Sicura da chiamare ad alta
frequenza (l'SDK serializza internamente).

**`stop`** termina la navigazione e riporta lo stato a `Idle`. Da chiamare
quando l'utente conclude o annulla.

### 4.3 Esempio completo

```kotlin
class NavigationViewModel : ViewModel() {

    private val navigator = EnaideNavigator.create(
        EnaideConfig(userAgent = "MiaApp/1.0 (dev@miaapp.it)")
    )

    val state: StateFlow<NavigationState> = navigator.state

    fun planAndStart(origin: GeoPoint, destination: GeoPoint) {
        viewModelScope.launch {
            val result = navigator.computeRoute(
                waypoints = listOf(origin, destination),
                profile = TransportProfile.AUTO,
                options = RouteOptions(avoidTolls = true),
            )
            when (result) {
                is RouteResult.Success -> navigator.start(result.route)
                is RouteResult.Failure -> _errors.emit(result.error.toString())
            }
        }
    }

    fun onGpsFix(loc: android.location.Location) {
        navigator.updateLocation(
            UserLocation(
                point = GeoPoint(loc.latitude, loc.longitude),
                horizontalAccuracyMeters = loc.accuracy.toDouble(),
                courseDegrees = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                speedMetersPerSecond = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                timestampEpochMillis = loc.time,
            )
        )
    }

    override fun onCleared() {
        navigator.stop()
    }
}
```

---

## 5. Modello di dominio

Tutti i tipi del modello sono `data class` o `enum`/`sealed class` immutabili.
Nessuno espone setter; ogni cambiamento è una nuova istanza.

### 5.1 GeoPoint

Coordinate WGS84. Latitudine in `[-90, 90]`, longitudine in `[-180, 180]`. Il
costruttore valida e lancia `IllegalArgumentException` se fuori scala.

### 5.2 UserLocation

Fix di posizione utente. Disambiguato volutamente dal tipo
`android.location.Location` del SDK Android, così la state machine resta
testabile su JVM puro. Convertire da `android.location.Location` a
`UserLocation` è banale (vedi esempio sopra).

### 5.3 TransportProfile

`AUTO`, `TRUCK`, `BICYCLE`, `PEDESTRIAN`. Mappa 1:1 sui "costing" di Valhalla.
Per il `TRUCK` puoi specificare dimensioni veicolo via `VehicleDimensions` in
`RouteOptions`.

### 5.4 Maneuver, ManeuverType, ManeuverModifier

Una `Maneuver` è una svolta o transizione lungo il percorso. `ManeuverType`
classifica grossolanamente (`TURN`, `MERGE`, `ROUNDABOUT`, ecc.) e
`ManeuverModifier` precisa la direzione (`LEFT`, `SLIGHT_RIGHT`, `UTURN`,
ecc.). Il mapping dai codici interni Valhalla è in `ValhallaMapper`.

### 5.5 VisualInstruction, SpokenInstruction

`VisualInstruction` è ciò che mostri nel banner in alto (linea primaria +
secondaria opzionale). `SpokenInstruction` è ciò che mandi al TTS. Entrambi
portano `triggerDistanceBeforeManeuverMeters`: quando la distanza alla manovra
scende sotto questo valore, l'istruzione "diventa attiva".

In v0.1 generiamo per ogni step:

- una `VisualInstruction` (trigger = lunghezza dello step, sempre visibile).
- una o due `SpokenInstruction`: alert lontano (~800m, se lo step è abbastanza
  lungo) e pre-transition (~200m).

Vedi `ValhallaMapper.kt` per le soglie esatte; sono valori conservativi per
guida mista urbana+autostradale. Si possono tarare via override del mapper.

### 5.6 RouteStep

Un tratto di percorso fra due manovre consecutive. Contiene:

- `geometry: List<GeoPoint>` — polilinea decodificata.
- `maneuver: Maneuver` — la svolta all'inizio dello step.
- `distanceMeters`, `durationSeconds` — lunghezza e durata stimate.
- `roadName: String?` — nome strada principale.
- `visualInstructions`, `spokenInstructions` — istruzioni associate.

### 5.7 Route

Percorso completo. Contiene `id` (generato dall'SDK o restituito dal server),
geometria totale (concatenazione delle geometrie degli step, senza
duplicazioni alle giunture), lista di `RouteStep`, distanza/durata totali e
waypoint originari.

### 5.8 RouteOptions e VehicleDimensions

`RouteOptions` permette di:

- richiedere alternative (`numberOfAlternatives = 1` o `2`),
- evitare pedaggi, autostrade, traghetti (`avoidTolls`, `avoidHighways`, `avoidFerries`),
- impostare lingua delle istruzioni (`language = "it-IT"`),
- scegliere unità (km/mi),
- passare dimensioni veicolo per `TRUCK` (`vehicleDimensions: VehicleDimensions`).

`VehicleDimensions` ha campi opzionali: altezza, larghezza, lunghezza (metri),
peso totale e per asse (chilogrammi — l'SDK converte in tonnellate per
Valhalla), flag `hazmat`.

Esempio camion alimentari, 3.5m altezza, 12m lunghezza, 26t totali, ferries
permessi, no pedaggi:

```kotlin
val truckOptions = RouteOptions(
    avoidTolls = true,
    vehicleDimensions = VehicleDimensions(
        heightMeters = 3.5,
        lengthMeters = 12.0,
        weightKg = 26_000.0,
        axleLoadKg = 8_000.0,
    ),
)
navigator.computeRoute(waypoints, profile = TransportProfile.TRUCK, options = truckOptions)
```

### 5.9 RouteProgress

Snapshot dell'avanzamento corrente: indice dello step, distanza percorsa nello
step, distanza alla prossima manovra, totali residui, ETA residuo, posizione
"snappata" sul percorso. Tutti i valori sono coerenti fra loro
(distanceTraveled + distanceRemaining = distance totale).

### 5.10 NavigationState

Sealed class esaustiva con cinque varianti:

- `Idle` — nessuna navigazione attiva.
- `Navigating(route, progress, currentVisualInstruction?, pendingSpokenInstruction?, deviation)`
   — stato principale di guida. `pendingSpokenInstruction` è non-null solo nel
   tick in cui una nuova istruzione deve partire; subito dopo torna null.
- `Rerouting(previousRoute, triggeredAt)` — deviazione confermata; ricalcola.
- `Arrived(route)` — utente a destinazione.
- `Failed(reason: NavigationError)` — errore non recuperabile.

Il pattern raccomandato è `when` esaustivo nei consumatori — il compilatore ti
avvisa se aggiungiamo casi nuovi (es. `OffRouteAlerted` in v0.2).

### 5.11 Deviation, NavigationError

Sealed sussidiari di `NavigationState`. `Deviation` è `OnRoute` o
`OffRoute(distanceFromRouteMeters)`. `NavigationError` distingue
`RoutingFailed`, `LocationUnavailable`, `Other`.

---

## 6. Sottosistema di routing

### 6.1 Interfaccia RoutingClient

```kotlin
interface RoutingClient {
    suspend fun computeRoute(
        waypoints: List<GeoPoint>,
        profile: TransportProfile,
        options: RouteOptions = RouteOptions.Default,
    ): RouteResult
}
```

`RouteResult` è sealed: `Success(route, alternatives)` o `Failure(error)`.
`RoutingError` distingue cinque categorie: `NetworkError`, `ServerError`,
`NoRouteFound`, `InvalidRequest`, `ParseError`. Sempre `when` esaustivi.

### 6.2 ValhallaRoutingClient

Implementazione default. Parla con un Valhalla via HTTP (POST a `/route`)
usando Ktor 3.x + OkHttp engine. Caratteristiche tecniche:

- Timeout configurabile in `EnaideConfig.requestTimeout`.
- User-Agent custom in ogni request.
- JSON parsing con `kotlinx-serialization` (lenient, ignora campi sconosciuti).
- Shape polyline6 (precisione 6 decimali) decodificata internamente.
- Costing options serializzate come `JsonObject` annidato sotto il nome del
  costing (es. `costing_options.truck.height`).
- Supporta fino a 2 percorsi alternativi (`alternates` da 0 a 2; Valhalla in
  pratica supporta valori più alti ma li clampiamo).

### 6.3 Endpoint pubblico FOSSGIS

`https://valhalla1.openstreetmap.de` è l'endpoint pubblico operato da FOSSGIS
e.V., gratuito per uso non commerciale. Limiti pratici:

- Rate limit attorno a 1 req/s per IP (non documentato esattamente).
- Downtime occasionale (è community-operated).
- Richiede `User-Agent` identificativo e attribuzione `© OpenStreetMap
  contributors` nelle app pubbliche.
- Lingue istruzioni: en, de, fr, it, es, pt, ru, sl, hi, ja e altre.

Non usarlo in produzione. È perfetto per sviluppo, demo e proof-of-concept.
Quando hai bisogno di:
- garanzie SLA,
- volumi superiori a qualche centinaio di richieste/giorno,
- tile in offline,

passa a **self-hosting** (immagine Docker `ghcr.io/valhalla/valhalla:latest`,
serve ~100GB disco per il planet OSM globale, molto meno per una singola
nazione) oppure a provider commerciali come Stadia Maps o Mapbox.

### 6.4 Polyline encoding

Valhalla restituisce le geometrie come stringhe polyline con precisione 6
(formato Google Encoded Polyline esteso). `PolylineDecoder.decode(encoded,
precision)` decodifica in `List<GeoPoint>`. È testato contro l'esempio
canonico Google (`_p~iF~ps|U_ulLnnqC_mqNvxq`@`).

L'algoritmo è equivalente all'originale; il parametro `precision` controlla
solo il fattore di scala (10^precision). Per usare con polyline5 (Google
Directions API), `decode(s, precision = 5)`.

### 6.5 Mapping Valhalla → dominio

`ValhallaMapper` converte le risposte Valhalla in `Route`. Punti chiave:

- Le geometrie dei `leg` vengono concatenate evitando di duplicare il punto di
  giunzione.
- I codici manovra Valhalla (0..37 documentati nel proto `directions.proto`)
  vengono mappati su `ManeuverType` + `ManeuverModifier`.
- Le istruzioni vocali Valhalla offre tre versioni: `verbal_transition_alert`
  (lontano, ~800m+), `verbal_pre_transition` (~200m), `verbal_post_transition`
  (dopo la manovra). In v0.1 usiamo le prime due. La post_transition non è
  ancora attivata — la aggiungeremo in una release successiva quando avremo
  l'evento "manovra completata" nello state machine.

### 6.6 Errori

`Failure(error)` arriva nei seguenti casi:

- `NetworkError(cause)` — non sono riuscito a parlare col server (timeout, no
  connessione). `cause` è l'eccezione originale di Ktor.
- `ServerError(httpStatus, body)` — il server ha risposto con un codice ≥ 400
  diverso da "no path".
- `NoRouteFound(message)` — il server ha risposto 400 con messaggio "No path
  found". Tipico se i waypoint non sono raggiungibili (isole, errori di
  coordinate, ZTL totale).
- `InvalidRequest(message)` — errore lato SDK prima di chiamare il server (es.
  meno di 2 waypoint).
- `ParseError(cause)` — il body della risposta non era valido JSON o non
  matchava lo schema atteso. Tipico se la versione del server è incompatibile
  o se l'endpoint ritornato è un proxy/CDN che ha alterato il payload.

---

## 7. State machine di navigazione

Il `NavigationController` è il cuore dell'SDK. Riceve fix di posizione e
calcola uno stato derivato. Internamente delega a tre componenti.

### 7.1 LocationSnapper

Per ogni fix, calcola il punto del route più vicino e quanto percorso si è
fatto. Algoritmo:

- Pre-calcola le distanze cumulate per ogni vertice della geometria del route
  in costruzione (array `cumulative`, O(N) memoria).
- A ogni `snap(point)`, itera sui segmenti, proietta il punto su ciascuno,
  sceglie il segmento con distanza minima. Calcola la posizione percorsa come
  `cumulative[bestSegment] + t * segmentLength`.
- Complessità per fix: O(N) sui segmenti. Per route urbani tipici (centinaia
  di vertici) è < 0.5ms. Per route lunghi (>10k vertici) si introdurrà una
  finestra mobile o un indice spaziale.

Limitazioni note:

- Su strade parallele molto vicine (cavalcavia, complanari) può scegliere il
  segmento "sbagliato". Mitigazione futura: filtrare candidati per bearing del
  moto utente (richiede `courseDegrees` non null).
- Approssima la proiezione come planare locale: ottima a livello di città,
  buona fino a segmenti di ~5km, da raffinare per segmenti più lunghi.

### 7.2 DeviationDetector

Decide quando dichiarare l'utente "fuori percorso". Usa isteresi a contatore:

- Inizializzato con `thresholdMeters` (default 30m) e `confirmationCount`
  (default 3).
- A ogni fix: se distanza > soglia, incrementa contatore; se ≥ count, dichiara
  deviazione e segnala al `NavigationController` di passare a `Rerouting`.
- Singolo fix entro soglia → contatore resettato a 0.

Calibrazione di partenza:

- Città dense (Roma, Napoli, centro storico): soglia 35-40m, count 4.
- Autostrada: soglia 25-30m, count 3.
- Aree remote/sparse: soglia 50m, count 5.

Sintomi di mis-calibrazione:

- "Ricalcola" troppe volte → alza count o soglia.
- Mancato ricalcolo dopo deviazione reale → abbassa count.

### 7.3 InstructionTrigger

Decide quale `SpokenInstruction` partire e quale `VisualInstruction` mostrare.

- Per la visual: prende la prima della lista dello step la cui
  `triggerDistanceBeforeManeuverMeters` è ≥ alla distanza residua. Fallback
  all'ultima della lista.
- Per la spoken: scorre le istruzioni dello step; se una soddisfa
  `distanzaResidua ≤ trigger` E non è già stata pronunciata (set di "spoken
  already" identificato da `stepIndex|trigger|text`), la ritorna.
- La de-duplicazione previene doppi annunci se il GPS oscilla intorno alla
  soglia.

### 7.4 NavigationController state flow

In ogni `onLocation(fix)`:

1. Snap del fix al percorso.
2. Determina lo step corrente (`stepIndexForGeometryIndex`).
3. Calcola la distanza alla fine dello step corrente (= prossima manovra).
4. Calcola ETA residuo (proporzionale a distanza residua su durata totale —
   migliorerà con il traffico).
5. Se distanza residua < 20m E ultimo step → emette `Arrived`, fine.
6. Aggiorna il deviation detector; se confermato → emette `Rerouting`.
7. Chiede al `InstructionTrigger` se c'è una spoken da pronunciare e quale
   visual mostrare.
8. Emette `Navigating(progress, visual, spoken?, deviation)`.

`Rerouting` e `Arrived` sono terminali per quella sessione: il controller non
modifica più lo stato finché l'app non chiama `start(...)` di nuovo (con un
nuovo route ricalcolato) o `stop()`.

### 7.5 Re-routing automatico

In v0.1, quando si entra in `Rerouting`, l'SDK **non** ricalcola
automaticamente — espone solo lo stato. La motivazione è di prodotto: il
ricalcolo è una scelta UX che cambia da app a app (ricalcolo silenzioso,
prompt "vuoi ricalcolare?", attesa che l'utente fermi). L'app integratrice
deve chiamare `computeRoute(triggeredAt → destinazione)` e poi `start(...)`
con il nuovo route. Snippet idiomatico:

```kotlin
viewModelScope.launch {
    navigator.state.collect { state ->
        if (state is NavigationState.Rerouting) {
            val r = navigator.computeRoute(
                waypoints = listOf(state.triggeredAt, originalDestination),
                profile = TransportProfile.AUTO,
            )
            if (r is RouteResult.Success) navigator.start(r.route)
        }
    }
}
```

In v0.2 aggiungeremo un flag `EnaideConfig.automaticReroute = true` per
attivare il comportamento di ricalcolo silenzioso.

---

## 8. Componenti di supporto

### 8.1 SimulatedLocationSource

Sorgente di posizione "finta" per testing e demo. Vedi
`com.enaide.sdk.simulation.SimulatedLocationSource`.

Uso tipico in un test:

```kotlin
@Test
fun `simulated drive triggers arrived state`() = runTest {
    val navigator = EnaideNavigator.create()
    val result = navigator.computeRoute(listOf(origin, destination))
    val route = (result as RouteResult.Success).route
    navigator.start(route)

    SimulatedLocationSource.default(route).asFlow().collect {
        navigator.updateLocation(it)
    }

    assertTrue(navigator.state.value is NavigationState.Arrived)
}
```

Helper preconfezionati:

- `SimulatedLocationSource.urban(route)` — ~30 km/h, tick 1s.
- `SimulatedLocationSource.highway(route)` — ~110 km/h, tick 1s.
- `SimulatedLocationSource.default(route)` — ~50 km/h, tick 500ms.

Opzione `jitterMeters` aggiunge rumore gaussiano per simulare GPS reale.
Utile per testare la resilienza dello snapper e del deviation detector.

### 8.2 UnitFormatter

Helper per UI in italiano (e altre locale). Vedi
`com.enaide.sdk.format.UnitFormatter`.

```kotlin
UnitFormatter.formatDistance(meters = 1234.0)            // "1,2 km"
UnitFormatter.formatDistance(meters = 120.0)             // "120 m"
UnitFormatter.formatDuration(seconds = 5400.0)           // "1 h 30 min"
UnitFormatter.formatEta(durationSeconds = 1800.0)        // "Arrivo alle 14:35"
UnitFormatter.formatSpeedKmh(metersPerSecond = 13.89)    // "50 km/h"
```

Tutti accettano un `Locale` esplicito (default `it-IT`). L'italiano usa la
virgola come separatore decimale, l'inglese il punto — il formatter lo
gestisce automaticamente.

---

## 9. Testing e simulazione

### 9.1 Test unitari core

I componenti del core sono progettati per essere testabili su JVM senza
emulatore. Coverage attuale:

- `PolylineDecoderTest` — 3 casi (vuota, single point, esempio canonico Google).
- `GeoUtilsTest` — 6 casi (haversine, bearing N/E, proiezione, clamp, lunghezza polilinea).
- `DeviationDetectorTest` — 4 casi (isteresi up, reset on-route, reset manuale).
- `InstructionTriggerTest` — 6 casi (dedup, trigger soglie, reset).
- `LocationSnapperTest` — 4 casi (snap inizio/metà/fuori-percorso, step index).
- `UnitFormatterTest` — 7 casi (distance, duration, speed, locale italiano).

Esegui con:

```bash
./gradlew :enaide-sdk:testDebugUnitTest
```

I test non richiedono Android SDK, solo JDK 17.

### 9.2 Test di integrazione

Per testare la state machine end-to-end con un percorso vero, usa
`SimulatedLocationSource` come mostrato in §8.1. Pattern raccomandato per test
deterministici:

```kotlin
@Test
fun `route progress reaches arrived`() = runTest {
    val controller = NavigationController(EnaideConfig())
    val route = loadFixtureRoute() // route mockato o salvato come JSON di test
    controller.start(route)

    SimulatedLocationSource(route, speedMetersPerSecond = 14.0).asFlow()
        .collect { controller.onLocation(it) }

    assertTrue(controller.state.value is NavigationState.Arrived)
}
```

### 9.3 Test su device reale

I test su device reale sono **necessari** per validare:

- Qualità del snap con GPS reale (canyon, tunnel, multipath).
- Timing percepito delle istruzioni vocali.
- Stabilità della batteria/CPU con fix ad alta frequenza.

Non c'è scorciatoia: questi vanno fatti guidando davvero, con telemetria
attiva (logga ogni fix e ogni stato).

---

## 10. Estendere l'SDK

### 10.1 Sostituire il routing engine

Implementa `RoutingClient` e iniettalo:

```kotlin
class OsrmRoutingClient : RoutingClient {
    override suspend fun computeRoute(
        waypoints: List<GeoPoint>,
        profile: TransportProfile,
        options: RouteOptions,
    ): RouteResult {
        // chiamata HTTP a OSRM, mapping a Route, ritorno
    }
}

// Uso (richiede esposizione di un costruttore con DI in v0.2; per ora wrappa
// nello scope dell'app)
```

### 10.2 Sorgente di posizione custom

Non esiste una "interfaccia LocationSource" perché ogni app ha esigenze
diverse (GMS FusedLocation, LocationManager nativo, GPS Bluetooth esterno,
mock). Il pattern: collega la tua sorgente preferita a `navigator.updateLocation(...)`.

Esempio con FusedLocationProviderClient di Google Play:

```kotlin
val client = LocationServices.getFusedLocationProviderClient(context)
val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()

client.requestLocationUpdates(request, object : LocationCallback() {
    override fun onLocationResult(result: LocationResult) {
        result.lastLocation?.let { loc ->
            navigator.updateLocation(UserLocation(
                point = GeoPoint(loc.latitude, loc.longitude),
                horizontalAccuracyMeters = loc.accuracy.toDouble(),
                courseDegrees = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                speedMetersPerSecond = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                timestampEpochMillis = loc.time,
            ))
        }
    }
}, Looper.getMainLooper())
```

### 10.3 TTS adapter

Lo stesso pattern: ogni app gestisce TTS come crede. Pattern di base:

```kotlin
val tts = TextToSpeech(context) { /* ... */ }
viewModelScope.launch {
    navigator.state.collect { state ->
        (state as? NavigationState.Navigating)?.pendingSpokenInstruction?.let { instr ->
            tts.speak(instr.text, TextToSpeech.QUEUE_FLUSH, null, instr.text)
        }
    }
}
```

### 10.4 UI mappa custom

L'SDK non impone una scelta di rendering. Vedi roadmap (`enaide-sdk-ui`) per il
modulo opzionale Compose + MapLibre che stiamo per costruire. Nel frattempo
tu colleghi MapLibre (o altro) e ascolti `RouteProgress.snappedLocation` per
centrare la camera.

---

## 11. Integrazione Android Auto / CarPlay

L'architettura "logica platform-agnostic + UI sostituibile" è pensata
esplicitamente per rendere Android Auto e CarPlay un'iterazione e non una
riscrittura.

### 11.1 Android Auto (Car App Library)

Su Android, l'integrazione si fa tramite **Car App Library**
(`androidx.car.app:app`). L'app espone un `CarAppService` che il sistema
instanzia quando il telefono è connesso al display dell'auto. Tu non disegni
schermate libere: rispondi con `Templates` predefiniti (`NavigationTemplate`,
`RoutePreviewNavigationTemplate`, `MapTemplate`).

Cosa **del nostro SDK è già pronto** per Auto:

- Tutto il modulo `enaide-sdk` core (state machine, routing, snapper, trigger).
- `EnaideNavigator.state` è uno `StateFlow` osservabile da qualsiasi
  Lifecycle, incluso `CarSession`.
- `SimulatedLocationSource` per testare sul DHU (Desktop Head Unit) senza
  un'auto vera.

Cosa **va costruito ex novo** in un modulo `enaide-sdk-androidauto` (~1500-2500
righe Kotlin):

- `CarAppService` + `Session` + `Screen` che ascoltano `navigator.state`.
- Traduzione `NavigationState.Navigating` → `NavigationTemplate.Builder()
  .setNavigationInfo(routingInfo).setActionStrip(...)`.
- Mapping degli step a `RoutingInfo.Builder().setCurrentStep(step,
  distance).setLanes(lanes).setNextStep(nextStep)`.
- Gestione del `SurfaceCallback` se vuoi una mappa custom (rendering MapLibre
  su `Surface` off-screen — pezzo tecnico più impegnativo).
- Routing TTS allo stream audio dell'auto (`AudioManager.STREAM_NAVIGATION`).

Effort stimato per la prima integrazione: **2-4 settimane di un dev senior +
1-2 settimane di review Play Store**. Lo step di review è obbligatorio: il
Play Store ha un programma "Navigation apps for Android Auto" che richiede
enrollment + chiavi specifiche.

Approccio incrementale che raccomandiamo: prima release "solo testo +
frecce" senza mappa custom (usa `NavigationTemplate` standard, molto
semplice), poi v2 con mappa MapLibre on Surface.

### 11.2 Android Automotive OS (AAOS)

AAOS è l'OS embedded in alcune auto (Polestar, Volvo, Renault, Stellantis di
recente). Si sviluppa con la stessa Car App Library più qualche pezzo extra
(`CarPropertyManager` per leggere velocità reale, marcia, livello carburante).
Una volta fatta Auto, portare ad AAOS è incrementale.

### 11.3 CarPlay (iOS)

Sull'SDK iOS che faremo dopo, l'equivalente è il framework **CarPlay** di Apple
con `CPNavigationSession` e `CPMapTemplate`. Stesso pattern template-based.
Effort comparabile ad Android Auto.

---

## 12. Permission, threading, performance

### 12.1 Permission Android richiesti

L'SDK **non** dichiara permission proprie nel manifesto, così l'app
integratrice controlla esattamente cosa richiedere all'utente. Per
navigazione completa servono:

- `android.permission.INTERNET` — chiamate al routing.
- `android.permission.ACCESS_FINE_LOCATION` — GPS preciso.
- `android.permission.ACCESS_COARSE_LOCATION` — fallback.
- `android.permission.FOREGROUND_SERVICE` + sottotipo
  `FOREGROUND_SERVICE_LOCATION` (API 34+) — per continuare la navigazione in
  background.

### 12.2 Threading

`EnaideNavigatorImpl` mantiene un proprio `CoroutineScope(SupervisorJob() +
Dispatchers.Default)`. Tutte le mutazioni dello stato sono serializzate da un
`Mutex`. Effetto pratico: puoi chiamare `updateLocation`, `start`, `stop` da
qualsiasi thread (UI, IO, worker dedicato) senza precauzioni.

Le chiamate `computeRoute` sono sospensive: lanciate dentro
`Dispatchers.IO` internamente. Non bloccano il chiamante.

### 12.3 Costi performance

- Singolo `onLocation` su route urbano (100-300 punti): < 1ms su mid-range
  Android.
- Route lungo (10k+ punti): O(N) per fix → 5-10ms; in v0.2 introdurremo
  finestra mobile per O(1) ammortizzato.
- Memoria per route urbano: ~20-50KB. Per route extra-urbano lungo: 200-500KB.
- Chiamata `/route` a Valhalla self-hosted con CPU server moderno (8 core):
  100-400ms per route urbano, 1-3s per route lunghi extra-urbani.

### 12.4 Batteria

Nessuna ottimizzazione automatica in v0.1. Linea guida pratica:

- A 1Hz fix con `PRIORITY_HIGH_ACCURACY` di FusedLocation, consumo ~7-10%
  batteria/ora.
- A 0.5Hz: ~4-6%.
- Per fleet/logistica con guida continua di 8-10h, raccomando 0.5-1Hz max e
  abilitare il `BALANCED_POWER_ACCURACY` quando la velocità è stabile su
  autostrada (>80 km/h).

---

## 13. Roadmap

Coperto in v0.1 (questa release): routing + state machine completa + helper
testing/UI.

Roadmap iterazioni successive (ordine di priorità):

- **v0.2 — UI Compose + MapLibre**: modulo opzionale `enaide-sdk-ui` con
  componenti pronti: drive mode, banner istruzioni, lane guidance, mappa con
  follow camera e marker di route+driver. MapView basata su MapLibre Android.
- **v0.3 — Re-routing automatico**: flag `automaticReroute = true` che
  ricalcola in automatico alla deviazione confermata, mantenendo la
  destinazione originale.
- **v0.4 — FusedLocation adapter**: modulo opzionale
  `enaide-sdk-location-gms` con wrapper pronto per Google Play Services.
- **v0.5 — TTS adapter**: modulo opzionale `enaide-sdk-tts` con TTS pronto
  per voci di sistema, gestione audio focus, ducking musica.
- **v0.6 — Matrix routing (fleet)**: `MatrixClient` + `ValhallaMatrixClient`
  per `/sources_to_targets`. Essenziale per dispatch "qual è il driver più
  vicino al pickup".
- **v0.7 — Multi-stop ottimizzato (fleet)**: `OptimizedRouteClient` su
  `/optimized_route` (TSP) per consegne in sequenza ottima. Integrazione con
  VROOM per ottimizzazione con finestre temporali e capacità.
- **v0.8 — Isochrones**: `IsochroneClient` su `/isochrone` per zone di
  raggiungibilità (utile per pricing e radius selection).
- **v0.9 — Offline tiles**: integrazione con Mjolnir di Valhalla per routing
  100% offline sul device. Strategia: download tile per regione, storage
  pacchettizzato in SQLite, fallback automatico online↔offline.
- **v1.0 — Android Auto**: modulo `enaide-sdk-androidauto` (vedi §11).
- **v1.x — Porting iOS**: SDK gemello in Swift + SwiftUI, stesso modello di
  dominio concettuale, stesso shape API.

Spostamenti di priorità sono normali: la roadmap viene aggiornata in base a
test sul campo. Re-rerouting automatico potrebbe per esempio salire prima del
modulo UI se i test mostrano che le app integratrici lo vogliono subito.

---

## 15. Funzionalità v0.3

Questa sezione documenta le capacità aggiunte dopo la v0.1: **geocoding**, **bus
di eventi**, **sorgente GPS reale**, **guida vocale TTS** e **mappa MapLibre**.
Ognuna è progettata per essere **modulare e disaccoppiata dalla UI**, così un
integratore può triggerare azioni proprie. Per ogni capacità è indicata la
controparte iOS (vedi anche §16).

> Principio cardine: l'SDK resta logica pura platform-agnostic. Tutto ciò che
> tocca la piattaforma (GPS, TTS, mappa) vive nell'app o in moduli opzionali,
> NON nel core. Questo rende il porting iOS una traduzione 1:1 del modello.

### 15.1 Geocoding (`com.enaide.sdk.geocoding`)

Conversione indirizzo ⇄ coordinate. Speculare a `RoutingClient`: interfaccia +
implementazione di default + risultati sealed.

| Tipo | Ruolo |
|------|-------|
| `GeocodingClient` | interfaccia: `search(query, limit)` (forward) e `reverse(point)` (reverse) |
| `NominatimGeocodingClient` | implementazione su Nominatim (OSM). Costruttore pubblico solo-`config` |
| `GeocodedPlace` | risultato: `point`, `displayName`, `type` |
| `GeocodeResult` | sealed: `Success(places)` / `Failure(error)` |
| `GeocodingError` | sealed: `NetworkError`, `ServerError`, `InvalidRequest`, `ParseError` |

```kotlin
val geocoder = NominatimGeocodingClient(config)

// Forward: testo -> luoghi
when (val r = geocoder.search("Duomo, Milano", limit = 5)) {
    is GeocodeResult.Success -> r.places.forEach { println(it.displayName) }
    is GeocodeResult.Failure -> println("errore: ${r.error}")
}

// Reverse: coordinate -> indirizzo
val rev = geocoder.reverse(GeoPoint(45.4641, 9.1886))
```

**Endpoint** (`EnaideConfig.nominatimBaseUrl`, default
`https://nominatim.openstreetmap.org`). Usage policy Nominatim: **max ~1 req/s**,
`User-Agent` identificativo obbligatorio (preso da `EnaideConfig.userAgent`),
attribuzione OSM nelle app pubbliche. In produzione: self-host o provider a
pagamento.

**Controparte iOS:** stesso protocollo `GeocodingClient` in Swift; implementazione
`NominatimGeocodingClient` con `URLSession`. I tipi `GeocodedPlace`/`GeocodeResult`
sono `struct`/`enum`. NB: NON usare `CLGeocoder` di Apple — accoppierebbe l'SDK
ad Apple Maps e perderebbe la natura OSM/open.

### 15.2 Bus di eventi (`EnaideNavigator.events`)

Oltre allo `state` (stato corrente persistente), l'SDK espone
`events: SharedFlow<NavigationEvent>` — eventi **discreti one-shot** per
**triggerare azioni**. La differenza è fondamentale:

- **`state`** risponde a "com'è la situazione ADESSO?" → si ridisegna la UI.
- **`events`** risponde a "cos'è APPENA SUCCESSO?" → si fa partire un'azione una volta sola.

`NavigationEvent` (in `com.enaide.sdk.model`):

| Evento | Quando | Uso tipico |
|--------|--------|------------|
| `Started(route)` | la navigazione parte | reset stato TTS, analytics start |
| `StepAdvanced(stepIndex, step)` | si completa una manovra | aggiorna lane guidance, log |
| `SpokenInstructionTriggered(instruction, distanceToManeuverMeters)` | superata soglia voce | **TTS** |
| `OffRouteConfirmed(distanceFromRouteMeters, at)` | deviazione confermata | **reroute automatico**, avviso |
| `Arrived(route)` | arrivo a destinazione | apri schermata arrivo, automazione "a casa" |
| `Stopped` | navigazione terminata/sostituita | cleanup |

```kotlin
// Triggerare azioni in modo modulare, senza toccare la UI:
viewModelScope.launch {
    navigator.events.collect { event ->
        when (event) {
            is NavigationEvent.SpokenInstructionTriggered -> tts.speak(event.instruction.text)
            is NavigationEvent.OffRouteConfirmed -> reroute(event.at)   // ricalcola
            is NavigationEvent.Arrived -> homeAutomation.iAmHome()
            else -> Unit
        }
    }
}
```

Caratteristiche del flusso: `replay = 0` (chi si abbona tardi non rivede eventi
passati), buffer 16 con `DROP_OLDEST` (un consumer lento non blocca il motore).

**Controparte iOS:** `var events: AsyncStream<NavigationEvent>` (o un Combine
`PassthroughSubject<NavigationEvent, Never>`). `NavigationEvent` è una `enum`
Swift con valori associati. La semantica "one-shot, non ritrasmesso" combacia
con `AsyncStream`.

### 15.3 Sorgente di posizione GPS reale

L'SDK **non** include un location provider (vedi §10.2): l'app sceglie. La demo
usa `GpsLocationSource` basato su `LocationManager` nativo (NON GMS), che mappa i
fix in `UserLocation` ed espone un `Flow`.

```kotlin
val gps = GpsLocationSource(context)   // app-side, non SDK
viewModelScope.launch {
    gps.asFlow().collect { fix -> navigator.updateLocation(fix) }
}
```

**Permessi runtime** (demo): `ACCESS_FINE_LOCATION` richiesto via
`ActivityResultContracts.RequestPermission`; fallback a coarse. Dichiarati nel
manifest dell'app integratrice (l'SDK non dichiara permessi propri, §12).

**Controparte iOS:** `CLLocationManager` con `requestWhenInUseAuthorization()` /
`requestAlwaysAuthorization()`. Implementa un `GpsLocationSource` Swift che
produce `AsyncStream<UserLocation>` dai `CLLocation` del delegate. Chiavi
`Info.plist`: `NSLocationWhenInUseUsageDescription` (e `...Always...` per il
background). Per la navigazione in background: `UIBackgroundModes: location`.

### 15.4 Guida vocale (TTS)

Pattern "azione su evento": il TTS si aggancia a
`NavigationEvent.SpokenInstructionTriggered`, non alla UI. La demo usa
`VoiceGuidance` (wrapper su `android.speech.tts.TextToSpeech`) con dedup per
testo, coda finché il motore non è pronto, locale configurabile.

```kotlin
val voice = VoiceGuidance(context, locale = Locale.ITALIAN)
navigator.events.collect { e ->
    if (e is NavigationEvent.SpokenInstructionTriggered) voice.speak(e.instruction.text)
}
// onDestroy: voice.shutdown()
```

`SpokenInstruction` espone anche `ssml` (opzionale) per TTS che supportano SSML.

**Controparte iOS:** `AVSpeechSynthesizer` + `AVSpeechUtterance` (con
`AVSpeechSynthesisVoice(language:)`). Stessa logica di dedup. Configura
`AVAudioSession` con categoria `.playback` e opzione `.duckOthers` per abbassare
musica/altro durante l'annuncio. SSML: `AVSpeechUtterance(ssmlRepresentation:)`.

### 15.5 Mappa (MapLibre)

La demo monta `RouteMap` (Compose `AndroidView` su `org.maplibre:android-sdk`)
che disegna la polilinea `route.geometry` + un marker sulla posizione corrente e
segue la camera. Lo style è raster OSM in `assets/osm_raster_style.json`
(nessuna API key).

Punti chiave dell'integrazione:
- `MapLibre.getInstance(context)` prima di gonfiare il `MapView`.
- Lifecycle del `MapView` gestito a mano (`onStart/onResume/onPause/onStop/onDestroy`).
- Route come `GeoJsonSource` + `LineLayer`; posizione come `GeoJsonSource` + `CircleLayer`, aggiornata a ogni fix.

⚠️ Le **tile** sono un terzo servizio di rete (oltre routing e geocoding). Il
raster OSM standard ha usage policy stretta: per produzione usa vector tiles
(MapTiler/Protomaps) con relativo style/API key.

**Controparte iOS:** **MapLibre Native iOS** (`MapLibre` SwiftPM/CocoaPods),
API quasi identica: `MLNMapView`, `MLNShapeSource`, `MLNLineStyleLayer`,
`MLNCircleStyleLayer`. Lo style JSON è **lo stesso file** (formato MapLibre Style
Spec, cross-platform). La logica "disegna polyline + segui posizione" è
identica; cambia solo il binding UIKit/SwiftUI (`UIViewRepresentable`).

### 15.6 Architettura modulare (moduli escludibili)

L'SDK è diviso in **moduli Gradle separati**: un core sempre richiesto + moduli
opzionali. I moduli opzionali dipendono dal core; il core **non** dipende da
loro. Una fornitura include solo gli AAR che servono.

| Modulo Gradle | Package | Dipendenze | Contiene |
|---------------|---------|------------|----------|
| `:enaide-sdk` (core) | `com.enaide.sdk[.model/.routing/.core/.format]` | Ktor, coroutines | `EnaideNavigator`, routing Valhalla, state machine, modello, eventi, `GeoUtils` |
| `:enaide-sdk-geocoding` | `com.enaide.sdk.geocoding` | core + Ktor | `GeocodingClient`, `NominatimGeocodingClient` |
| `:enaide-sdk-simulation` | `com.enaide.sdk.simulation` | core | `SimulatedLocationSource` (+ `DrivingPhysics`) |
| `:enaide-sdk-map` | `com.enaide.sdk.map` | core + Compose + MapLibre | `RouteMap`, `MapCameraState`, design system (`EnaideTheme`) |
| `:enaide-sdk-tts` | `com.enaide.sdk.tts` | core | `VoiceGuidance` |
| `:enaide-sdk-vehicle` | `com.enaide.sdk.vehicle` | core | `VehicleProfile`, `VehicleKind` (profilo + sagoma) |

```kotlin
// build.gradle.kts di una fornitura "minimale" (solo navigazione + voce):
implementation(project(":enaide-sdk"))
implementation(project(":enaide-sdk-tts"))
// niente geocoding, mappa, simulazione.
```

**Regola di dipendenza:** il modello di dominio (`com.enaide.sdk.model`) e
`GeoUtils` vivono nel core perché sono il "linguaggio comune" interdipendente.
Tutto ciò che è platform-specific o sostituibile sta in un modulo a parte. I
moduli proprietari (futuri) seguiranno lo stesso schema: dipendono dal core,
restano fuori dalle forniture che non li acquistano.

**Controparte iOS:** Swift Package con più *target* (`EnaideSDK`,
`EnaideGeocoding`, `EnaideMap`, `EnaideTTS`) e stesse regole di dipendenza, o
moduli separati. La separazione "core vs opzionali" è identica.

### 15.7 Design system e colori semantici

La UI (modulo map) non usa colori hardcodati: legge **token semantici**
personalizzabili. Material 3 copre `primary`/`secondary`/`error`/…; i token
specifici della navigazione (linea percorso, puntino posizione, banner manovra)
stanno in `EnaideColors`, forniti via `EnaideTheme`.

```kotlin
// Ribrandizzare = passare un'altra palette, senza toccare i componenti:
EnaideTheme(colors = EnaideColors(
    routeLine = Color(0xFF00C853),
    routeCasing = Color(0xFF1B5E20),
    positionDot = Color(0xFF00C853),
    // ...
)) {
    // UI del navigatore
}
```

`RouteMap` converte i `Color` in stringhe hex per MapLibre internamente. I token
si leggono via `EnaideTheme.colors.routeLine` (composable) o dal
`CompositionLocal` `LocalEnaideColors`.

**Controparte iOS:** un `struct EnaideColors` in `Environment` SwiftUI (o un
`ObservableObject` di tema). MapLibre iOS accetta `UIColor`/hex come Android.

### 15.8 Mappa 3D e interazioni

`RouteMap` con `threeD = true` mette la camera in **prospettiva di guida**:
zoom ravvicinato, `tilt` ~55°, `bearing` orientato sul moto (dal
`courseDegrees` del fix). In anteprima resta 2D dall'alto e inquadra l'intero
percorso.

**Interazioni gestite** via `MapCameraState`:
- pan/zoom/rotate/tilt con le dita sono abilitati;
- al primo gesto utente il *follow-mode* si disattiva (la camera non "combatte"
  col tocco) — intercettato con `addOnCameraMoveStartedListener(REASON_API_GESTURE)`;
- `MapCameraState.recenter()` (dal FAB ◎) riattiva il follow e ricentra in 3D.

**Controparte iOS:** `MLNMapView` con `MLNMapCamera` (`pitch`, `heading`,
`centerCoordinate`). Il gesto utente si intercetta con i delegate
`mapView(_:regionWillChangeAnimated:)`; il follow-mode è la stessa macchina a
stati.

### 15.9 Simulazione GPS realistica

`SimulatedLocationSource` ha due modalità:
- **costante** (default/`urban`/`highway`): velocità fissa, deterministica, per CI.
- **fisica** (`SimulatedLocationSource.realistic(route, cruiseKmh)`): integra il
  moto con accelerazione/decelerazione limitate e un **cap di velocità in curva**
  calcolato dall'angolo di virata fra segmenti (modello di frenata `v²=2·a·s` con
  look-ahead), più jitter GPS gaussiano. I parametri stanno in `DrivingPhysics`.

Per test esterni si può anche usare la **mock location** di sistema Android
(opzioni sviluppatore → "app per posizioni fittizie") iniettando fix via adb;
l'app li riceve come GPS reale tramite `GpsLocationSource`, senza cambiare codice.

**Controparte iOS:** stessa logica di fisica (matematica pura, porta 1:1). Per la
mock location di sistema: file `.gpx` in Xcode (Simulate Location) o
`CLLocationManager` con location simulate.

### 15.10a Architettura event-centric: comandi in ingresso

L'SDK è una macchina **comando → stato → eventi**, simmetrica:

```
  NavigationCommand  ──dispatch()──▶  [motore]  ──▶  NavigationState (stato corrente)
                                              └──▶  NavigationEvent  (eventi discreti)
```

- **In ingresso**: `EnaideNavigator.dispatch(NavigationCommand)` cambia/aggiorna la
  navigazione *via evento-comando*. È il modo previsto per un'app event-centric.
- **In uscita**: `state` (StateFlow, situazione corrente) + `events` (SharedFlow,
  cose appena successe — §15.2).

`NavigationCommand` (sealed, `com.enaide.sdk.model`):

| Comando | Effetto | Note |
|---------|---------|------|
| `Start(route)` | avvia su un route già calcolato | `start()` ne è il wrapper |
| `Stop` | termina | `stop()` wrapper |
| `UpdateLocation(loc)` | nuovo fix | `updateLocation()` wrapper |
| `Recalculate` | ricalcola dalla posizione corrente verso la destinazione attuale | reroute |
| `SetDestination(dest)` | cambia destinazione, ricalcola | |
| `AddWaypoint(wp, index?)` | inserisce una tappa, ricalcola | |
| `ReplaceRoute(route)` | sostituisce il percorso attivo | |
| `UpdateProfile(profile?, options?)` | cambia mezzo/opzioni **in marcia** e ricalcola | es. camion che cambia peso, auto→piedi |

I comandi che ricalcolano sono asincroni: lo stato passa per `Rerouting` → `Navigating`
(o `Failed`). L'impl tiene traccia del piano di viaggio (waypoints, profilo, opzioni)
per poter ricalcolare. Tutto serializzato su `Mutex`.

```kotlin
// Reroute automatico, puramente event-centric:
navigator.events.collect { e ->
    if (e is NavigationEvent.OffRouteConfirmed) {
        navigator.dispatch(NavigationCommand.Recalculate)
    }
}

// Cambio mezzo in corsa (es. il camion ha scaricato, ora più leggero):
navigator.dispatch(NavigationCommand.UpdateProfile(
    options = RouteOptions(vehicleDimensions = VehicleDimensions(weightKg = 8000.0))
))
```

**Predisposizione traffico/messaggi:** entrambe le sealed class sono estensibili
senza rotture. Quando serviranno aggiornamenti di traffico/avvisi runtime, si
aggiungeranno casi tipo `NavigationCommand.ReportTraffic(...)` (in ingresso) e
`NavigationEvent.Message(...)` / `TrafficAlert(...)` (in uscita) — il `when`
esaustivo dei consumer segnalerà i punti da aggiornare a compile-time.

**Controparte iOS:** `func dispatch(_ command: NavigationCommand)` con
`enum NavigationCommand`. Stessa macchina comando→stato→evento. `AsyncStream` per
gli eventi, `@Published`/`AsyncStream` per lo stato.

### 15.10b Modulo veicolo (`:enaide-sdk-vehicle`)

Modulo opzionale che dà ergonomia alla scelta del mezzo + sagoma, mappando su
`TransportProfile` + `RouteOptions`/`VehicleDimensions` del core (costing Valhalla).

- `VehicleKind`: `CAR`, `PEDESTRIAN`, `BICYCLE`, `TRUCK` (→ `TransportProfile`).
- `VehicleProfile`: tipo + sagoma (per il camion) + preferenze (pedaggi, autostrade,
  traghetti) + lingua/unità. Preset: `VehicleProfile.car()`, `.pedestrian()`,
  `.bicycle()`, `.truck(dimensions)`.
- Produce `toProfile()` e `toRouteOptions()` pronti per `computeRoute(...)`.

```kotlin
val v = VehicleProfile.truck(VehicleDimensions(heightMeters = 4.0, weightKg = 18000.0))
navigator.computeRoute(waypoints, v.toProfile(), v.toRouteOptions())
```

**Navigazione a piedi**: `VehicleProfile.pedestrian()` → costing `pedestrian`.
La demo offre i chip Auto/Piedi/Bici/Camion (+ form sagoma se camion) e adatta la
velocità del simulatore al mezzo (auto 50, bici 18, piedi 5 km/h).

**Controparte iOS:** `struct VehicleProfile` + `enum VehicleKind`, stessa mappatura
sui costing Valhalla.

### 15.10 Tabella di corrispondenza Android → iOS

| Capacità | Android (questo SDK) | iOS (porting) |
|----------|----------------------|----------------|
| Stato reattivo | `StateFlow<NavigationState>` | `@Published` / `AsyncStream` |
| Eventi azione | `SharedFlow<NavigationEvent>` | `AsyncStream` / Combine `Subject` |
| Comandi ingresso | `dispatch(NavigationCommand)` | `dispatch(_ NavigationCommand)` |
| Coroutine | `suspend` + `Flow` | `async/await` + `AsyncSequence` |
| HTTP | Ktor + OkHttp | `URLSession` |
| JSON | kotlinx.serialization | `Codable` |
| GPS | `LocationManager` | `CLLocationManager` |
| Geocoding | Nominatim via Ktor | Nominatim via `URLSession` |
| TTS | `android.speech.tts.TextToSpeech` | `AVSpeechSynthesizer` |
| Mappa | MapLibre Android | MapLibre Native iOS (stesso style JSON) |
| Modello dominio | `data class` / `sealed class` | `struct` / `enum` |

---

## 16. Mappa per il porting iOS

Linee guida per costruire `enaide-sdk` Swift come **gemello speculare**, non una
riscrittura divergente.

**Cosa porta 1:1 (logica pura, niente piattaforma):**
- Modello di dominio (`GeoPoint`, `Route`, `RouteStep`, `Maneuver`,
  `NavigationState`, `NavigationEvent`, `RouteProgress`, `UserLocation`,
  `Instruction`, `RouteOptions`, `TransportProfile`) → `struct`/`enum` Swift.
- Algoritmi core: `GeoUtils` (haversine, bearing, proiezione su segmento),
  `LocationSnapper`, `DeviationDetector`, `InstructionTrigger`,
  `PolylineDecoder`, `UnitFormatter`. Sono matematica + stringhe: traduzione
  diretta, **stessi test** (i vettori di test in `src/test` valgono come spec).
- State machine `NavigationController`: la logica di transizione è identica;
  cambia solo il tipo di flusso (`AsyncStream` invece di `Flow`).

**Cosa richiede adapter di piattaforma (stessa interfaccia, impl diversa):**
- `RoutingClient` / `GeocodingClient`: protocolli identici; impl con `URLSession`.
- Sorgente posizione: `CLLocationManager` → `UserLocation`.
- TTS: `AVSpeechSynthesizer`.
- Mappa: MapLibre Native iOS, **riusando lo stesso style JSON**.

**Convenzioni da mantenere per la simmetria:**
- Stessi nomi di tipo e di campo (camelCase Swift, ma 1:1 semantico).
- Stesse sealed/enum esaustive: il `when` Kotlin ↔ `switch` Swift esaustivo.
- Stessa filosofia "no dipendenze occulte": niente Apple Maps/CLGeocoder, tutto
  OSM/Valhalla/Nominatim via HTTP standard.
- Stesso contratto di concorrenza: il motore è serializzato (in Kotlin via
  `Mutex`; in Swift via `actor` o una serial queue) e thread-safe verso l'esterno.

**Endpoint e policy identici:** Valhalla (`routingBaseUrl`), Nominatim
(`nominatimBaseUrl`), `userAgent` identificativo. Le stesse note operative di §6
e §15.1 valgono su iOS.

---

## 18. API esterne: reference e note operative

Tutti i servizi usati dall'SDK sono OSM/open. Endpoint configurabili in
`EnaideConfig`. Reference ufficiali e gotcha verificati sul campo:

### Routing — Valhalla
- **API reference**: https://valhalla.github.io/valhalla/api/
- Turn-by-turn `/route`: https://valhalla.github.io/valhalla/api/turn-by-turn/api-reference/
- Costing options (auto/truck/bicycle/pedestrian): stessa pagina, sezione "costing options"
- `trace_attributes` (per speed limit / map-matching): https://valhalla.github.io/valhalla/api/map-matching/api-reference/
- Demo pubblico FOSSGIS: `https://valhalla1.openstreetmap.de` — POST JSON a `/route`.
- ⚠️ `language` va passato in `directions_options.language` e **serializzato** anche
  se è il default (`encodeDefaults = true`), altrimenti Valhalla risponde in EN.

### Geocoding — Nominatim
- **API reference**: https://nominatim.org/release-docs/latest/api/Overview/
- `/search` (forward): https://nominatim.org/release-docs/latest/api/Search/
- `/reverse`: https://nominatim.org/release-docs/latest/api/Reverse/
- Usage policy: https://operations.osmfoundation.org/policies/nominatim/
- ⚠️ Max ~1 req/s, **User-Agent identificativo OBBLIGATORIO** col formato
  `app/ver (+contatto)`. Debounce lato client (vedi demo) per evitare 429.

### POI — Overpass API
- **API reference**: https://wiki.openstreetmap.org/wiki/Overpass_API
- Linguaggio query (Overpass QL): https://wiki.openstreetmap.org/wiki/Overpass_API/Overpass_QL
- Guida/Language guide: https://wiki.openstreetmap.org/wiki/Overpass_API/Language_Guide
- Test query: https://overpass-turbo.eu/
- Endpoint pubblico: `https://overpass-api.de/api/interpreter` — **POST
  `application/x-www-form-urlencoded`** col parametro `data=<query>` (Ktor
  `submitForm`).
- ⚠️ **Gotcha verificato**: `overpass-api.de` risponde **406 Not Acceptable** se lo
  `User-Agent` contiene un URL tra parentesi (`(+http...)`) — formato invece
  richiesto da Nominatim. Il POI client usa quindi un UA "semplice" (solo
  prodotto/versione). Senza UA → anche 406.
- Tag OSM per categoria: https://wiki.openstreetmap.org/wiki/Map_features
- Fair use: <10.000 query/giorno, <1 GB/giorno sull'istanza pubblica.

### Mappa — MapLibre + tiles
- MapLibre Native Android: https://maplibre.org/maplibre-native/docs/book/android/
- MapLibre Style Spec (stesso JSON cross-platform): https://maplibre.org/maplibre-style-spec/
- Tile raster OSM (`tile.openstreetmap.org`) usage policy:
  https://operations.osmfoundation.org/policies/tiles/
- ⚠️ Tile raster OSM solo per dev/volumi bassi; per produzione vector tiles
  (MapTiler/Protomaps) con relativa API key.

### Riepilogo endpoint in `EnaideConfig`
| Servizio | Campo | Default |
|----------|-------|---------|
| Valhalla | `routingBaseUrl` | `valhalla1.openstreetmap.de` |
| Nominatim | `nominatimBaseUrl` | `nominatim.openstreetmap.org` |
| Overpass | `overpassBaseUrl` | `overpass-api.de` |
| User-Agent | `userAgent` | `enaide-sdk/… (+contatto)` |

**Controparte iOS:** stessi endpoint e policy; il gotcha UA Overpass vale identico
(usare UA semplice per Overpass, completo per Nominatim).

---

## 17. Idea futura: server MCP per l'SDK

**Fattibile e sensato.** L'SDK ha già un'API a sottosistemi netti (routing,
geocoding, POI, navigazione) con tipi sealed serializzabili: esporla come server
**MCP** (Model Context Protocol) permetterebbe a un agente AI (Claude o altri) di
usarla come set di *tool*.

Forma ipotizzata:
- Un processo server (Kotlin/JVM con l'Anthropic/MCP SDK, oppure un wrapper
  Node/Python che chiama un binario) che monta il core enaide.
- Tool esposti, mappati 1:1 sui sottosistemi:
  - `compute_route(waypoints, profile, options)` → riepilogo + geometria
  - `geocode(query)` / `reverse_geocode(lat, lon)`
  - `search_poi(category, near|along_route)`
  - `plan_trip(stops[])` / `edit_trip(add|remove|move)`
  - `simulate_drive(route)` → stream di stati (per test/automazioni)
- Risorse MCP read-only: stato di navigazione corrente, piano di viaggio.

Vantaggi: un assistente può pianificare/modificare viaggi conversando ("aggiungi
una tappa al distributore più vicino sul percorso"), e il flusso comando→evento
dell'SDK (§15.10a) si presta perfettamente — i `NavigationCommand` diventano tool
call, i `NavigationEvent` notifiche.

Prerequisiti: estrarre il core in un artefatto JVM puro (già lo è, a parte i
moduli Android UI), definire lo schema JSON dei tool dai modelli `@Serializable`.
Non c'è nulla di bloccante: è lavoro di wrapping, non di riprogettazione.

Stato: **idea, non pianificata**. Annotata qui per non perderla.

---

## 14. Glossario

- **Costing** (Valhalla): l'algoritmo di costo che assegna un "peso" a ogni
  segmento del grafo OSM. Determina come il router preferisce una strada
  rispetto a un'altra. Profili supportati: `auto`, `truck`, `bicycle`,
  `pedestrian`, `motor_scooter`, `taxi` (gli ultimi due non ancora esposti
  dall'SDK).
- **Drive mode**: la schermata "principale" durante la guida con manovra
  grande, mappa che segue il driver, ETA in evidenza.
- **DHU** (Desktop Head Unit): emulatore di Android Auto che gira su
  Mac/Linux/Windows. Permette di sviluppare per Auto senza un'auto fisica.
- **Geometry** (di un route): la polilinea decodificata, lista ordinata di
  `GeoPoint` che descrive la traiettoria.
- **Isochrone**: zona di raggiungibilità entro N minuti da un punto (vedi
  endpoint `/isochrone` di Valhalla).
- **Leg** (Valhalla): tratta del percorso fra due waypoint consecutivi. Un
  route con N+1 waypoint ha N leg. Ogni leg ha la sua geometria e i suoi
  maneuver.
- **Polyline6**: stringa codificata di geometria, formato Google Encoded
  Polyline con precisione 6 decimali (1e-6 di grado, ~11cm a livello equatore).
  Più accurato del polyline5 originale.
- **Snap-to-route**: l'azione di "agganciare" un fix GPS rumoroso al punto
  più vicino del percorso. Necessario perché la visualizzazione e i calcoli
  di distanza residua devono partire da una posizione coerente col percorso,
  non dal fix grezzo.
- **State machine**: il pattern in cui lo stato del sistema è un valore di un
  set finito (qui: sealed `NavigationState`) e le transizioni avvengono in
  risposta a eventi (qui: `start`, `updateLocation`, `stop`). Garantisce
  coerenza e testabilità.
- **TSP** (Travelling Salesman Problem): trovare il giro ottimo che visita N
  punti e torna all'inizio. Vedi roadmap §13 per il supporto nativo previsto
  in v0.7.
- **Trip** (Valhalla): l'intera risposta di una `/route` request, contenente
  uno o più leg.
- **TTS** (Text-To-Speech): sintesi vocale. Su Android: `android.speech.tts.TextToSpeech`.
- **Waypoint**: un punto richiesto nel percorso. Tipi Valhalla: `break` (sosta
  effettiva, il router cerca un parcheggio plausibile) o `through` (semplice
  passaggio, il router non si ferma). L'SDK in v0.1 usa solo `break`.
- **VROOM**: solver open-source per Vehicle Routing Problem (VRP), si integra
  sopra Valhalla/OSRM per multi-veicolo con vincoli (finestre, capacità,
  shift). Pianificato come integrazione fleet in v0.7+.
