# ci — static analysis

`.github/workflows/ci.yml` runs on every push to `main` and every pull request, on Linux. Each step is judged by its
exit code, and within a job every check step runs even when an earlier one failed (`if: ${{ !cancelled() }}`; the
Gradle steps use `--continue`). Exactly these:

| Job | Steps |
|---|---|
| go (`backend/`) | `cd tools && ./gradlew installDist` (the rc tool, for the end-to-end test); `golangci-lint run` ([golangci-lint-action](https://github.com/golangci/golangci-lint-action)); `golangci-lint fmt --diff`; `go vet ./...`; `go test -race -count=1 ./...` with `BREW_REQUIRE_RC_TOOL=1`; `go tool govulncheck ./...` |
| kotlin | `cd tools && ./gradlew --continue test ktfmtCheck detekt`; `cd android && ./gradlew --continue :app:ktfmtCheck :shared:ktfmtCheck :app:lintDebug :shared:lintDebug :app:detekt :shared:detekt`; OSV-Scanner via `python3 ci/check.py --require-tools osv` |
| security | gitleaks on the commits reachable from `HEAD` (`--log-opts=HEAD`, full clone) |
| swift | SwiftLint's static Linux build, from `ios/` |

gitleaks, OSV-Scanner and SwiftLint are plain workflow steps: `curl --retry 3 --retry-all-errors`, `sha256sum -c`
against the SHA-256 written next to the version, unpack, run. Caches: Go's module and build cache (setup-go, keyed by
`backend/go.mod` + `backend/go.sum`), golangci-lint-action's analysis cache, and Gradle's (setup-gradle).

The `ios` job (macOS runner, the newest Xcode 26 on the image) builds the iOS app for the simulator and runs
swift-format. Not in CI: no Android APK is assembled; build it locally (the README's "Check everything").

## Versions

One place per version:

| Stack | Tool | Version | Pinned in | Config | Existing findings |
|---|---|---|---|---|---|
| Kotlin (android, shared, tools) | ktfmt (Gradle plugin) | 0.27.0 | `gradle/libs.versions.toml` | `build.gradle.kts` | 0 |
| Kotlin (android/app, shared, tools) | detekt (Gradle plugin `dev.detekt`) | 2.0.0-alpha.6 | `gradle/libs.versions.toml` | `config/detekt.yml` | in each module's `detekt-baseline.xml` (app 7, shared 66, tools 7) |
| Android (app, shared) | Android Lint (AGP) | 9.5.1 | `gradle/libs.versions.toml` | `build.gradle.kts`, `config/lint-volatile-checks.txt`, `android/app/lint-baseline.xml` | 2 in the baseline (app); shared has no baseline |
| Go (backend) | Go | 1.26.11 | `backend/go.mod` (`toolchain`) | — | — |
| Go | golangci-lint (fmt + run) | 2.15.0 | `.github/workflows/ci.yml` | `backend/.golangci.yml` | 0 |
| Go | govulncheck | 1.9.0 | `backend/go.mod` (`tool`), bytes in `go.sum` | — | 0 |
| Swift (ios) | SwiftLint | 0.65.1 | `.github/workflows/ci.yml` | `ios/.swiftlint.yml` | 22, in `ios/swiftlint-baseline.json` |
| Swift | swift-format (CI: the newest Xcode 26 on macos-26; locally your Xcode's) | Xcode | `.github/workflows/ci.yml` (`ios` job) | `ios/.swift-format` | 0 |
| Whole repo, every commit | gitleaks | 8.30.1 | `.github/workflows/ci.yml` | defaults | 0 |
| go.mod/go.sum, Package.resolved, shipped Gradle classpaths | OSV-Scanner | 2.7.1 | `.github/workflows/ci.yml` | `osv-scanner.toml` | 0, none accepted |

detekt is on the 2.0 line because detekt 1.23.x is built with Kotlin 2.0 and its Gradle plugin refuses to run with
this repo's Kotlin 2.4. It runs without type resolution (the `detekt` task on each module's `src/`).

## Locally

`ci/check.py` runs the checks with what is installed on this machine; it downloads nothing. Install the versions above
(macOS: `brew install gitleaks osv-scanner swiftlint`; golangci-lint:
`go install github.com/golangci/golangci-lint/v2/cmd/golangci-lint@v2.15.0`, since Homebrew's may be newer). Gradle
(detekt) finds Java itself, as for any build; govulncheck comes with the Go module.

```bash
python3 ci/check.py all          # or some of: gitleaks osv detekt go govulncheck swiftlint swift-format
```

A check whose program is not on the PATH, or that does not apply on this OS (swift-format off macOS), is reported as
SKIPPED with the reason; one that finds something or cannot run is FAILED. All requested checks run, the summary
lists each one, and the exit code is the number of failed checks. `--require-tools` turns a missing program into a
failure (CI's OSV step uses it). `go` is `go run ./cmd/demo lint -check` (golangci-lint fmt --diff and run, vet, tests);
`go` and `govulncheck` run on the Go release `backend/go.mod` pins. The other Gradle checks are plain Gradle tasks (the
README's "Check everything").

## Baselines

Findings that existed when an analyser was added are recorded; only new ones fail. Fix rather than re-baseline;
regenerate a baseline only on purpose and review its diff:

- detekt: `cd android && ./gradlew :app:detektBaseline :shared:detektBaseline`, `cd tools && ./gradlew detektBaseline`.
- SwiftLint, from `ios/`, on macOS (all rules run; on Linux the static build skips the rules that need SourceKit, so
  their findings would be missing from the baseline): `swiftlint lint --strict --write-baseline swiftlint-baseline.json`.
  The command exits non-zero, because it reports the violations it records; the file is written anyway.
- Android Lint, from `android/`: `./gradlew :app:updateLintBaseline` rewrites `app/lint-baseline.xml`. `:shared` has no
  baseline; fix its warnings.

Android Lint warnings are errors (`warningsAsErrors`). Checks that can fail with no code change — they depend on the
date, on Google's Play SDK Index (network) or on the installed SDK — are off in both modules; the one list, with the
reasons, is `config/lint-volatile-checks.txt`.

## OSV and Gradle

The Gradle builds keep no lockfiles. For the scan, `check.py osv` has Gradle write them with
`ci/dependency-locks.init.gradle.kts` for the configurations that ship, and only those: the Android app's debug and
release runtime classpaths (`:shared`'s dependencies included) and the rc tool's runtime (`runtimeClasspath` plus the
`aar` it unpacks), transitive dependencies included. Build tools in their own configurations (Android Lint, ktfmt,
detekt) are not scanned. The lockfiles go to a temporary directory outside the repo, are passed to OSV-Scanner with
`--lockfile` (`scan source -r` skips git-ignored files) and are deleted afterwards; the `settings-gradle.lockfile`
files `--write-locks` leaves in the builds' directories are deleted before and after. The init script is
configuration-cache safe (no `Task.project` while tasks run).

A new advisory against a shipped dependency fails the scan even when no code changed — that is the point. If one has
to be accepted, add a `[[PackageOverrides]]` entry scoped to the package and version, with `effectiveUntil` and the
reason (the template is in `osv-scanner.toml`); not `[[IgnoredVulns]]`, which ignores an id in every package.

## swift-format

swift-format comes with Xcode, so it runs on macOS only: in CI in the `ios` job with the newest Xcode 26 on the runner
image, and locally with your Xcode. The two versions can disagree on formatting; CI's is the reference. Run it before
pushing Swift changes: `python3 ci/check.py swift-format`;
format with `cd ios && xcrun swift-format format -i Brew/<file>.swift` (not `Catalog.generated.swift`, which `tools/`
writes).
