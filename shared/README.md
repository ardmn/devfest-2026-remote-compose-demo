# shared — the Remote Compose UI

The server-driven UI of Brew, in Kotlin. Not opened on its own: `android/` includes it as a Gradle composite build
(`includeBuild("../shared")`, dependency `brew:shared`) to build sections on the device when the server is away, and
`tools/` compiles the same sources on a plain JVM to render them for the server.

| Package | What | API |
|---|---|---|
| `brew.remote` | the sections (`sections/`) and their building blocks (`components/`), catalog, colours | public `@RemoteComposable` Compose API only, no `android.*` — it also runs on a plain JVM (`tools/`) |
| `brew.capture` | on Android: turns a section into document bytes (`captureSingleRemoteDocument` with a `Context`) | public |
| `brew.document` | `DocumentValidator`, the structural check the Android app runs on every download | `@RestrictTo` core parser |
| `brew.lab` | the Lab's GPU shader documents (AGSL); `LabCards` is plain data | `@RestrictTo` creation DSL — shaders have no public authoring API in alpha23 |

`RestrictedApi` is a lint **error** here (`build.gradle.kts`), with one exemption, `brew.lab` (`lint.xml`). Check:

```bash
cd android
./gradlew :shared:lintDebug
```

Code style: ktfmt (kotlinlang, 140 columns) — `../tools/gradlew -p . ktfmtFormat`.
