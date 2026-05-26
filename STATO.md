# STATO del progetto — recovery & appunti

Doc operativo per riprendere il lavoro / ripartire dopo un crash. Per la
reference tecnica completa vedi `DOCS.md`; per le convenzioni `CLAUDE.md`.

## Cos'è
SDK Android (Kotlin) di navigazione turn-by-turn, modulare, event-centric, +
demo app stile Google Maps. Tutto OSM/open (Valhalla, Nominatim, Overpass,
MapLibre). Pensato per porting iOS speculare.

## Moduli (Gradle)
- `enaide-sdk` — core: routing Valhalla, state machine, modello, eventi/comandi (`dispatch`/`events`), `TripPlan`, `GeoUtils`. Sempre richiesto.
- `enaide-sdk-geocoding` — Nominatim (forward/reverse). Opzionale.
- `enaide-sdk-poi` — Overpass (POI, provider intercambiabile). Opzionale.
- `enaide-sdk-simulation` — GPS simulato con fisica + WrongTurn. Opzionale.
- `enaide-sdk-vehicle` — profilo mezzo + sagoma camion. Opzionale.
- `enaide-sdk-map` — MapLibre, RouteMap, MapMarker, EnaideTheme/colori. Opzionale (UI).
- `enaide-sdk-tts` — guida vocale TextToSpeech. Opzionale.
- `demo-app` — app demo che usa tutto.

## Build / run
```bash
./gradlew :enaide-sdk:testDebugUnitTest          # test core (no emulatore)
./gradlew :demo-app:assembleDebug                # APK
~/Library/Android/sdk/platform-tools/adb install -r demo-app/build/outputs/apk/debug/demo-app-debug.apk
~/Library/Android/sdk/platform-tools/adb shell monkey -p com.enaide.demo -c android.intent.category.LAUNCHER 1
```
Gradle wrapper: 8.11.1. JDK 17. compileSdk 36, minSdk 26.

## Flusso app
- **Tab Mappa**: mappa edge-to-edge + search bar in alto + chip POI sotto. Tap risultato/POI/long-press → anteprima percorso. Se in navigazione mostra il tragitto attivo.
- **Tab Naviga** (solo se attiva): guida 3D, banner manovra, ETA/distanza/velocità, bottoni (prossimo step se manuale, salta tappa se multitappa, termina).
- **Tab Impostazioni**: mezzo+sagoma, sorgente posizione (GPS/Simulato/Manuale), origine manuale, simula-errore, voce, endpoint.

## Gotcha / bug noti già risolti (NON reintrodurre)
- **Valhalla lingua**: `Json { encodeDefaults = true }` nel routing client, altrimenti `language` (default) non viene serializzato → risponde in inglese.
- **Nominatim**: max ~1 req/s, UA `app/ver (+contatto)` obbligatorio. Ricerca con debounce 600ms, sennò 429.
- **Overpass 406**: usare POST form-urlencoded (Ktor `submitForm`) E uno User-Agent SEMPLICE senza `(+url)` — overpass-api.de dà 406 con UA "a parentesi". (Nominatim invece lo vuole.)
- **POI marker invisibili**: lo style raster OSM NON ha `glyphs` → un SymbolLayer con `textField` non viene renderizzato (icona inclusa). I pin POI mostrano solo l'icona, niente testo.
- **Simulatore realistico**: serve `delay()` nel loop, sennò emette tutti i fix in un colpo e il puntatore "salta".
- **explicit-api**: usare `kotlin { explicitApi() }`, non `-Xexplicit-api` in freeCompilerArgs (rompe i test).

## Stato corrente (task)
Fatti: routing, geocoding, POI, simulazione, mappa 3D, TTS, vehicle, TripPlan,
event-centric (dispatch/events), reroute auto, GPS-less (avanza a mano), skip
tappa, bussola, edge-to-edge, i18n IT/EN, snapping per profilo.

Da fare: annunci vocali multi-distanza (#28), ETA da velocità reali (#29),
lane guidance (#30), limite velocità via Valhalla trace_attributes (#31).
Idea futura: server MCP (DOCS §17).

## Endpoint pubblici (dev, in EnaideConfig)
Valhalla `valhalla1.openstreetmap.de` (a volte tiles solo per alcune regioni —
es. CH ok), Nominatim `nominatim.openstreetmap.org`, Overpass `overpass-api.de`.
Rate-limitati: in produzione self-host.
