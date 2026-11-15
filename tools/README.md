# tools — `rc`, the Kotlin half of the pipeline

A command-line program on a plain JVM — no Android SDK, no emulator, no Robolectric.

```bash
cd backend && go run ./cmd/demo render   # every section for stage.json → backend/documents, + the iOS app's copies
cd tools && ./gradlew render             # the same without the iOS copies (gradlew.bat on Windows)
```

| Command | What it does |
|---|---|
| `rc render --stage <stage.json> --out <dir> [--swift <file>]` | the `@RemoteComposable` sections → `<dir>/*.rc`, `manifest.json`, `catalog.json`; with `--swift`, the iOS app's generated catalog |
| `rc json2rc` | a document's JSON representation (stdin) → binary `.rc` (stdout), validated. The Go server calls this |
| `rc validate <file.rc>…` | checks documents with the validator the Android app runs |

`./gradlew installDist` builds it into `build/install/rc/bin/rc` (`rc.bat` on Windows); `go run ./cmd/demo serve` and
`render` in `backend/` do it for you. The start script runs the JVM with `-Dfile.encoding=UTF-8`: the library's JSON
parser encodes text with the default charset, which is ASCII or cp1252 on some machines (`−` and `→` became `?`);
`rc` refuses to run with any other default.

## Same sources as the app

This build compiles the very same Kotlin files as the Android app — `shared/…/brew/remote` (the sections),
`brew/document` (the validator), the Lab's card list — not a copy. The output is byte for byte what the Android app's
own capture (`brew.capture`, with a `Context`) writes; `GeneratedFilesTest` fails when the committed
`backend/documents` or the iOS catalog no longer match the Kotlin.

## How it runs without Android

The authoring API (`remote-creation-compose`) is published only as an Android AAR in alpha23, but writing a document
needs no Android. This build:

- takes the classes out of the AAR and runs them on the JVM, with Compose's JVM build (JetBrains Compose — androidx
  publishes only throwing stubs of `compose-ui` for the JVM);
- captures with the `Context`-free `captureSingleRemoteDocument` and a platform-free writer profile (both
  `@RestrictTo` in alpha23 — the public capture takes a `Context`);
- replaces `androidx.tracing.Trace` with a no-op (its JVM build is not binary compatible with the Android one the AAR
  was compiled against).

The sections themselves stay on the public API. One thing they avoid for this: `RemoteInt.toRemoteString()`, which pulls
in `android.icu` (the quantity on the product page is drawn another way, see `DetailsSection.kt`).

## Test

```bash
./gradlew test     # renders and validates every section, checks the committed files are current, JSON → .rc
```
