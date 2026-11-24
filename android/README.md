# Brew for Android

A regular Jetpack Compose app — tabs, navigation, cart with Undo, pull to refresh — whose content sections are
Remote Compose documents, played by the androidx player (`RemoteDocumentPlayer`).

## Run

**Android Studio:** open the `android/` folder (not the repository root), let Gradle sync, run the `app`
configuration on an emulator or a phone (Android 10+; the Lab's shaders need Android 13+).

**Command line** (the build finds the Android SDK by itself — `ANDROID_HOME` or the usual install place):

```bash
cd android
./gradlew :app:installDebug
```

No server needed: without one the app builds the same sections on the device (from `shared/`), and the footer on
Home says `offline · 5 of 5 sections built on the device`. Only the JSON-built promo card needs the server.

## Live sections from the server (optional)

```bash
cd backend
go run ./cmd/demo serve
```

It also runs `adb reverse tcp:8080 tcp:8080`, so the app's `http://127.0.0.1:8080` reaches your computer from
an emulator or a USB phone. Pull to refresh on Home: every pull shows a new choice of banners, and the footer turns
green: `N sections from the server · … KB` (N counts the banners loaded so far, too).

If the server is not reachable, the app logs once (Logcat, tag `Brew`):

```
Backend not reachable at http://127.0.0.1:8080 (ConnectException: Failed to connect to /127.0.0.1:8080).
The app keeps working: it builds the sections on the device; the JSON promo card is hidden.
To get live sections from the server:
  1. Start it:  cd backend && go run ./cmd/demo serve   …
  2. Emulator or USB phone:  adb reverse tcp:8080 tcp:8080   (repeat after an emulator restart)
  3. Phone over Wi-Fi: build with  ./gradlew :app:installDebug -Pbrew.baseUrl=http://<your-computer-ip>:8080
  4. Check on the computer:  curl http://127.0.0.1:8080/sections/promo.json
```

A document the server sends that fails validation is not played; Logcat says
`Rejected /sections/<path> from the backend: …` and the app uses the copy built on the device.

### Phone over Wi-Fi

`adb reverse` covers emulators and USB phones. For a phone on the same Wi-Fi as your computer:

```bash
cd backend && go run ./cmd/demo serve -lan
```

```bash
cd android && ./gradlew :app:installDebug -Pbrew.baseUrl=http://<your-computer-ip>:8080
```

Debug builds allow plain HTTP to any host for this (`app/src/debug/res/xml/network_security_config.xml`); release
builds only to `127.0.0.1`, `localhost` and the emulator's `10.0.2.2`.

## What's inside (`app/src/main/kotlin/brew/app`)

| File | What it does |
|---|---|
| `ShowcaseApp.kt` | the native shell: tabs, Home (banner pager, sections), Menu and Favorites (native lists of row documents), cart, product page, app state |
| `Sections.kt` | `SectionSource` (server, else built on the device; the server's banner choice) and `RemoteSection` (hosts the player) |
| `LabTab.kt` | the Lab: GPU shader documents, an fps meter, shaders allowed by an allow-list |

`DocumentValidator` (in `shared/…/brew/document`) checks every document's structure before a player sees it — the
library itself accepts truncated documents. The cart and the open screen survive rotation (`AppState` is a
ViewModel). The price the app charges comes from its catalog, not from the document.

Two things learned the hard way, both in `Sections.kt`:
- **Don't let the player recompose.** Every recomposition re-runs `setDocument()`, which resets the document's
  component sizes: circles turn square, icons vanish. Values are pushed from an effect instead.
- **Players are `@RestrictTo` in alpha24** — there is no public way to play a document yet; this is deliberate here.

Code style: ktfmt (kotlinlang, 140 columns) — `./gradlew :app:ktfmtFormat`; named arguments wherever Kotlin allows.
