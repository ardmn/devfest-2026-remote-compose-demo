# ci — static analysis

`.github/workflows/ci.yml` runs the build, the tests and the analysers below on every push to `main` and every pull
request. Each step is judged by its exit code. The same checks run locally:

```bash
python3 ci/check.py all          # or some of: gitleaks osv detekt go swiftlint swift-format
```

`check.py` needs Python 3.9+ (standard library only), Java 17+ (detekt; Gradle for `osv`) and Go (`go`). It downloads
each tool once into `.cache/ci-tools/` (git-ignored) and checks its SHA-256. Versions are pinned in `check.py`
(`TOOLS`); to update one, change the URL and the SHA-256 together.

| Stack | Tool | Version | Config | Existing findings |
|---|---|---|---|---|
| Kotlin (android, shared, tools) | ktfmt (Gradle plugin, already used) | 0.27.0 plugin | `build.gradle.kts` | 0 |
| Kotlin | detekt CLI | 1.23.8 | `config/detekt.yml` | 90, in `config/detekt-baseline.xml` |
| Android (app, shared) | Android Lint (AGP) | 9.5.1 | `build.gradle.kts`, `android/app/lint-baseline.xml` | 2 in the baseline (app) |
| Go (backend) | golangci-lint fmt + run (already used), go vet | 2.15.0 | `backend/.golangci.yml` | 0 |
| Go | govulncheck | 1.9.0 | — | 0 |
| Swift (ios) | SwiftLint | 0.65.1 | `ios/.swiftlint.yml` | 22, in `ios/swiftlint-baseline.json` |
| Swift | swift-format (Xcode 27.2's, 6.4.4) | Xcode | `ios/.swift-format` | 0 |
| Whole repo, every commit | gitleaks | 8.30.1 | defaults | 0 |
| go.mod, Package.resolved, shipped Gradle classpaths | OSV-Scanner | 2.7.1 | `osv-scanner.toml` | 0, none accepted |

**Baselines.** Findings that existed when an analyser was added are recorded; only new ones fail. Fix rather than
re-baseline; regenerate a baseline only on purpose and review its diff:

- detekt: `java -jar .cache/ci-tools/detekt-1.23.8/detekt-cli-1.23.8-all.jar --input android/app/src,shared/src,tools/src --config config/detekt.yml --build-upon-default-config --baseline config/detekt-baseline.xml --create-baseline`
- SwiftLint: `cd ios && ../.cache/ci-tools/swiftlint-0.65.1/swiftlint lint --strict --write-baseline swiftlint-baseline.json`
- Android Lint: delete `android/app/lint-baseline.xml`, run `./gradlew :app:lintDebug` once (it writes the file).
  Warnings are errors (`warningsAsErrors`); lint's "a newer version is available" checks are off, they change with
  the date, not the code.

**OSV and Gradle.** The Gradle builds keep no lockfiles. For the scan, `check.py osv` has Gradle write them with
`ci/dependency-locks.init.gradle.kts` for the configurations that ship, and only those: the Android app's debug and
release runtime classpaths (`:shared`'s dependencies included) and the rc tool's runtime (`runtimeClasspath` plus the
`aar` it unpacks), transitive dependencies included. Build tools in their own configurations (Android Lint, ktfmt) are
not scanned. The lockfiles go to a temporary directory outside the repo, are passed to OSV-Scanner with `--lockfile`
(`scan source -r` skips git-ignored files) and are deleted afterwards. A new advisory against a shipped dependency
fails the scan even when no code changed — that is the point. If one has to be accepted, add a `[[PackageOverrides]]`
entry scoped to the package and version, with `effectiveUntil` and the reason (the template is in
`osv-scanner.toml`); not `[[IgnoredVulns]]`, which ignores an id in every package.

**swift-format** comes with Xcode, so it runs on macOS only and is not in CI (a macOS runner for one formatter check
is not worth it; SwiftLint runs on Linux). Run it before pushing Swift changes: `python3 ci/check.py swift-format`;
format with `cd ios && xcrun swift-format format -i Brew/<file>.swift` (not `Catalog.generated.swift`, which
`tools/` writes). On Linux, SwiftLint's static build skips the few rules that need SourceKit; they run on macOS.
