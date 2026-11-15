# backend — the server (Go)

Hands out Brew's sections as Remote Compose documents. The Android and iOS apps get the very same bytes.

## Run

You need [Go](https://go.dev/dl) 1.25+ (macOS: `brew install go`) and a JDK 17+ for the JSON converter (Android Studio
bundles one; it is found by itself).

```bash
go run ./cmd/demo serve        # 127.0.0.1:8080 — this computer, the iOS simulator, Android emulators/USB phones
go run ./cmd/demo serve -lan   # also phones on the same Wi-Fi (listens on 0.0.0.0)
```

It builds the `rc` tool the first time (about a minute) and runs `adb reverse` when an Android device is connected.
Then:

```bash
curl http://127.0.0.1:8080/sections/banners      # e.g. [3,0,4] — a new random choice every time
curl http://127.0.0.1:8080/sections/promo.json   # the promo as JSON, before it becomes a document
```

Ctrl+C stops it (gracefully: requests in flight finish).

## Commands

`cmd/demo` is the project's task runner — a Go program, so the same on macOS, Linux and Windows (no bash, no make):

| `go run ./cmd/demo …` | Does |
|---|---|
| `serve [-lan] [-port N]` | builds the rc tool if needed, `adb reverse`, starts the server (Ctrl+C stops it gracefully) |
| `render` | renders the Kotlin sections for `stage.json` (tools/), writes the iOS catalog and the iOS app's offline copies |
| `lint` | `golangci-lint fmt` + `golangci-lint run` (`.golangci.yml`), `go vet`, `go test -race` |

The server alone: `go run ./cmd/brew-backend` (flags: `-port`, `-lan`, `-rc`, `-documents`, `-dump <dir>`).

## The pipeline

A document is made in one of two ways — both are normal for a backend:

```
 shared/ (Kotlin @RemoteComposable)  ──cmd/demo render──▶  documents/*.rc  ──▶  this server  ──▶  apps
 stage.json (banners, favourites)  ─┘   (plain JVM, at build time)               (serves files)

 promo.json (a JSON document) + stage.json "promo"  ──this server fills it──▶  rc json2rc  ──▶  apps
                                                      (per request, cached)     (tools/, JVM)
```

1. **Rendered ahead of time.** The screens are Kotlin `@RemoteComposable` functions in `shared/`.
   `go run ./cmd/demo render` ([tools/](../tools/README.md)) turns them into `documents/*.rc` on a plain JVM — no Android, no emulator — and
   writes `documents/manifest.json` (request path → file) and `documents/catalog.json` (the drinks). This server only
   serves those files. The rendered documents are committed, so the server runs without rendering first.
2. **Built per request from JSON.** The *Happy hour* card is a JSON document ([`promo.json`](promo.json)) the server fills
   from `stage.json` (title, colour, discount, one row per drink) and converts with `rc json2rc`. Conversions are
   cached; the same JSON never runs the converter twice.

**On stage:**

| You change | Then | Live after |
|---|---|---|
| `stage.json` → `"promo"`, or `promo.json` | nothing | pull to refresh |
| `stage.json` → `"bannersShown"` | nothing | pull to refresh |
| `stage.json` → banners, favorites, for-you | `go run ./cmd/demo render` | pull to refresh |
| a Kotlin section in `shared/` | `go run ./cmd/demo render` | pull to refresh |

The server notices when `stage.json` no longer matches the rendered documents and says so in its log.

**Banners:** `stage.json` holds a pool of banners (all of them rendered); `/sections/banners` answers which of them the
carousel shows and in which order — a random choice of `bannersShown` on every request, so every pull to refresh
can show different banners. The apps page through `hero?i=<id>` for the ids they get.

## API

| `GET /sections/…` | Answer |
|---|---|
| `banners` | JSON array of banner ids, e.g. `[3,0,4]` |
| `hero?i=N`, `categories`, `favorites`, `for-you`, `details?id=ID`, `row?id=ID` | a rendered document |
| `promo` | the JSON promo, converted |
| `promo.json` | the JSON promo |

Documents are `application/vnd.remotecompose`, never cached (`no-store`). Unknown sections and ids are 404; ids
from clients are only looked up in the manifest, never used as file names. Errors are logged in full and answered
without details.

## Code

```
cmd/brew-backend/    the server's main
cmd/demo/            the task runner (serve, render, lint)
internal/app/        the server program: flags, wiring, graceful shutdown
internal/server/     HTTP handlers, the banner choice, -dump for the iOS app
internal/convert/    JSON → .rc through the rc tool: cache, one run for concurrent identical requests, timeouts
internal/promo/      fills promo.json from stage.json
internal/documents/  documents/manifest.json and catalog.json
internal/stage/      stage.json
```

Standard library only. Logging with `log/slog`, routing with `http.ServeMux` patterns.

## Test and lint

```bash
go test -race ./...       # unit tests + end-to-end tests with the real rc tool (skipped if it is not built)
go run ./cmd/demo lint    # golangci-lint fmt (gofumpt, goimports) + run (.golangci.yml) + vet + tests
```

`lint` needs [golangci-lint](https://golangci-lint.run/welcome/install) v2 (`brew install golangci-lint`). The tests that
fake the rc tool are shell scripts and are skipped on Windows.
