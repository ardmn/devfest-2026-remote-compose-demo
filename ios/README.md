# Brew for iOS

SwiftUI app: tabs, navigation, cart, banner pager, pull to refresh, a toast with Undo — plain SwiftUI. The content
sections are Remote Compose documents, played by the community player
[rc-players](https://github.com/yschimke/rc-players) (3.5.2, Swift package `RcComposePlayerSwiftUI`).

## Run

1. Open `ios/Brew.xcodeproj` in Xcode (built with Xcode 27.2; iOS 17+).
2. Pick an iPhone **simulator** and press **Run**. Xcode downloads rc-players by itself on the first build.

That's it — no server needed: the app plays copies of every section bundled in `Brew/Resources/sections`.
No signing is needed for the simulator. (rc-players ships Apple-Silicon slices only, so Intel Macs can't run the
simulator build.)

## Live sections from the server (optional)

1. Start the backend: `cd backend && go run ./cmd/demo serve` (see [backend/README.md](../backend/README.md)).
2. Pull to refresh on Home — every pull shows a new choice of banners. The simulator reaches the Mac at
   `127.0.0.1:8080` directly.

If the server can't be reached, the app keeps going on the bundled copies and prints once in Xcode's console:

```
Brew: backend not reachable at http://127.0.0.1:8080/sections/ (Could not connect to the server.).
The app keeps working with the copies bundled in the app (Brew/Resources/sections).
To get live sections from the server:
  1. Start it:  cd backend && go run ./cmd/demo serve  …
```

On a **real iPhone** the Mac is not `127.0.0.1`: start the backend with `go run ./cmd/demo serve -lan`, set
`SectionSource.baseURL` in `Brew/Sections.swift` to `http://<your-mac-ip>:8080/sections/`, and run on the device with
your own signing team.

The app checks a document's header before playing it (a full structural check like Android's `DocumentValidator`
has no Swift port yet). The price it charges comes from its own catalog — the product page only reports the options.
That catalog (`Brew/Catalog.generated.swift`: drinks, prices, toppings, section heights, palette, Lab cards) is
generated from the Kotlin in `shared/` — don't edit it; change the Kotlin and run `go run ./cmd/demo render` in `backend/`.
The Lab plays bundled documents only (the backend doesn't serve them). Note that rc-players runs shaders without an
allow-list, unlike the androidx player.

**The Lab's documents** (`lab_i-*.rc`) are written by the Android app: its shader cards use Android's `Canvas` for the
lenticular card's pictures, so they are not rendered by `tools/`. A debug build saves each card when it first appears —
open the Lab tab on an emulator, scroll to the last card, then:

```bash
adb pull /sdcard/Android/data/brew.app/files/lab/. ios/Brew/Resources/sections/
```

## What's inside

| File | What it does |
|---|---|
| `Brew/BrewApp.swift` | the app, tabs, cart button, toast |
| `Brew/Screens.swift` | Home (banner pager, sections), Menu with filter chips and Favorites (native lists of row documents), product page, cart, Lab |
| `Brew/Sections.swift` | loads a section (server, else bundle) and the server's banner choice; hosts the player and pushes named colours into it |
| `Brew/AppState.swift` | the app's state (cart, favourites, filter) and how it handles the documents' actions |
| `Brew/Resources/sections` | bundled documents — refreshed by `go run ./cmd/demo render` (in `backend/`) |
| `Brew/Catalog.generated.swift` | catalog generated from `shared/` by the same script; a `tools/` test fails when it is stale |

The documents ask through host actions (`add_to_cart:<id>`, `toggle_favorite:<id>`, `category:<key>`,
`open_details:<id>`; the product page sends `qty`, `large` and the `toppings` bit mask as numbers; the app prices them from its catalog) and show app state through named
colours the app pushes (`fav_<id>`, `cat_<key>`) — colours because this player's controller sets floats, strings and
colours, not integers.

## The project file

`Brew.xcodeproj` is generated from `project.yml` with [XcodeGen](https://github.com/yonaskolb/XcodeGen) and committed,
so you don't need XcodeGen to run the app. After changing `project.yml`: `xcodegen generate`, and commit both.
Strict concurrency checking is on and warnings are errors.

## Differences found between the two players

The same bytes play on both, but rc-players is stricter in places; each was fixed in the shared document code:

| On rc-players | Fix |
|---|---|
| `paint style 2 is not implemented` (fill + stroke at once) | the heart is two icons, a fill and an outline |
| `TouchUpModifierOperation: nested opcode 81 is not an action` | no expression is declared inside an action; the press fade's clock is declared before the touch handlers |
| `visibility()` does not follow a document-local integer change | radio dots and topping rings take their colour from a float |
| the controller sets floats, strings and colours, not integers | favourites and the category highlight are named colours |
| the touch time is NaN before the first touch | shader checks are NaN-proof: `!(age >= 0 && age <= 4)` |
