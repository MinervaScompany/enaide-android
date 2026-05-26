# enaide — note per Claude

SDK di navigazione Android (Kotlin) + demo app. Documentazione completa in `DOCS.md`.

## Build / test

```bash
./gradlew :enaide-sdk:testDebugUnitTest    # test logica core (JVM, no emulatore)
./gradlew :demo-app:assembleDebug          # APK demo
```

## Convenzioni non negoziabili

- **Localizzazione obbligatoria.** Ogni testo mostrato all'utente DEVE essere
  localizzato — mai stringhe hardcoded nel codice. In Android: `strings.xml`
  (default `res/values`, lingue in `res/values-<lang>`) + `stringResource(...)`
  nei Composable, `context.getString(...)` fuori. Lingue attuali: italiano
  (default) + inglese. Le istruzioni turn-by-turn (testo + voce) arrivano già
  localizzate da Valhalla: la lingua nella request va allineata al locale
  dell'app. Quando il porting iOS partirà, stessa regola con `Localizable.strings`.

- **Architettura modulare.** Core (`:enaide-sdk`) + moduli opzionali escludibili
  (`-geocoding`, `-simulation`, `-map`, `-tts`, `-vehicle`, `-poi`). I moduli
  dipendono dal core, mai il contrario. Ogni nuova capacità sostituibile o
  platform-specific va in un modulo a sé, dietro un'interfaccia provider.

- **Event-centric.** L'SDK è una macchina comando→stato→evento:
  `dispatch(NavigationCommand)` in ingresso, `state` + `events` in uscita.
  Aggiornamenti runtime (reroute, traffico, messaggi, cambio mezzo) passano da lì.

- **Componenti UI nativi.** Usare i componenti Material 3 standard coi loro
  default, niente Surface/forme custom che li storpiano — così il porting iOS
  con SwiftUI nativo è speculare.

- **Niente dipendenze occulte.** Solo HTTP standard verso OSM/Valhalla/Nominatim/
  Overpass. Nessun Google Play Services / Apple Maps / CLGeocoder.

- **Porting iOS speculare.** Tutto va documentato in `DOCS.md` con la controparte
  iOS, perché lo stesso sarà implementato in Swift con struttura speculare.

## Endpoint pubblici (solo dev)

Valhalla `valhalla1.openstreetmap.de`, Nominatim `nominatim.openstreetmap.org`,
Overpass `overpass-api.de`. Rate-limitati (debounce/throttle lato client).
Configurabili via `EnaideConfig`. In produzione: self-host.
