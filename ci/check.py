#!/usr/bin/env python3
"""Runs the repo's static analysis locally, with the tools already installed on this machine.

    python3 ci/check.py <check> [<check> ...]     # or: all

Checks: gitleaks, osv, detekt, go, govulncheck, swiftlint, swift-format, all.

Nothing is downloaded. Each check runs a program from the PATH (gitleaks, osv-scanner, golangci-lint, swiftlint), the
Gradle wrapper (detekt; Gradle finds Java itself) or the Go command (`go run ./cmd/demo lint -check`,
`go tool govulncheck`). The versions of the programs from the PATH are pinned once, in .github/workflows/ci.yml (the
VERSION of the step that downloads each one); this script reads them from there and warns, in the check's output and in
the summary, when the local version differs (its findings can differ from CI's). A check whose program is missing
FAILS with how to install it: CI runs that check, so a green run here must not hide it. A check that does not apply on
this OS (swift-format off macOS) is SKIPPED with the reason. A check that finds something, or cannot run, FAILS. Each
requested check runs once, even when named twice; the script prints a summary and exits with the number of failed
checks. Standard library only, Python 3.9+.

CI (.github/workflows/ci.yml) runs most of these as its own steps and uses this script for `osv`, which first has
Gradle write lockfiles for the shipped dependencies, and for `swift-format` (the `ios` job, on macOS).
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import tempfile
import traceback
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
IOS_PACKAGE_RESOLVED = REPO / "ios/Brew.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved"
# The Gradle builds whose directories --write-locks may leave lockfiles in (shared is included by android).
GRADLE_BUILDS = ("android", "tools", "shared")


class Skipped(Exception):
    """The check does not run here; the message says why."""


class Failed(Exception):
    """The check cannot run (it counts as failed); the message says why."""


WORKFLOW = REPO / ".github" / "workflows" / "ci.yml"
# The programs CI downloads in a workflow step: the GitHub repository it downloads from (how the step is found in
# ci.yml) and the arguments that print the program's version.
PINNED = {
    "gitleaks": ("gitleaks/gitleaks", ["version"]),
    "golangci-lint": ("golangci/golangci-lint", ["version"]),
    "osv-scanner": ("google/osv-scanner", ["--version"]),
    "swiftlint": ("realm/SwiftLint", ["version"]),
}
# The warnings of the check that is running (a version that differs from CI's); main() shows them in the summary.
warnings: list[str] = []


def ci_pin(name: str) -> str:
    """The version of `name` CI uses: the VERSION env of the ci.yml step that downloads it from its GitHub releases."""
    repo = PINNED[name][0]
    for step in re.split(r"^\s*- (?=name:|uses:)", WORKFLOW.read_text(), flags=re.MULTILINE):
        version = re.search(r"^\s*VERSION:\s*\"?([^\s\"]+)", step, re.MULTILINE)
        if version and f"github.com/{repo}/releases/download/" in step:
            return version.group(1)
    raise Failed(f"no step in {WORKFLOW.relative_to(REPO)} downloads {name} from github.com/{repo} with a VERSION")


def program(name: str, install: str) -> str:
    """The program `name` from the PATH; missing: FAILED. A pinned program whose version is not CI's adds a warning."""
    found = shutil.which(name)
    if found is None:
        raise Failed(f"{name} is not on the PATH ({install})")
    if name in PINNED:
        pin = ci_pin(name)
        out = subprocess.run([found, *PINNED[name][1]], capture_output=True, text=True, check=False)
        text = out.stdout + out.stderr
        if not re.search(r"(?<![\w.])v?" + re.escape(pin) + r"(?![\w.])", text):
            local = (text.strip().splitlines() or ["no version printed"])[0]
            warning = f"{name} here is not CI's {pin} ({local!r}; pinned in {WORKFLOW.relative_to(REPO)})"
            print(f"WARNING: {warning}: its findings can differ from CI's", flush=True)
            warnings.append(warning)
    return found


def run(args: list[str | Path], cwd: Path = REPO, env: dict[str, str] | None = None) -> int:
    where = "." if cwd == REPO else cwd.relative_to(REPO)
    print(f"$ (cd {where}) {' '.join(str(a) for a in args)}", flush=True)
    return subprocess.run([str(a) for a in args], cwd=cwd, env=env, check=False).returncode


def gradlew(build: str) -> Path:
    return REPO / build / ("gradlew.bat" if os.name == "nt" else "gradlew")


def first_failure(*codes: int) -> int:
    return next((c for c in codes if c != 0), 0)


# --- checks: each returns an exit code (0 = clean) or raises Skipped / Failed --------------------------------------


def check_gitleaks() -> int:
    # The commits reachable from HEAD (not other fetched branches), redacted. False positives go to .gitleaksignore.
    exe = program("gitleaks", "brew install gitleaks")
    return run([exe, "git", "--redact", "--no-banner", "--exit-code", "1", "--log-opts=HEAD", "."])


def check_osv() -> int:
    """Known vulnerabilities in go.mod/go.sum, Package.resolved and what the Gradle builds ship.

    The Gradle builds keep no lockfiles. Gradle writes them for this scan (ci/dependency-locks.init.gradle.kts) for the
    shipped configurations only, into a temporary directory outside the repo, and they are passed to OSV-Scanner with
    --lockfile (`scan source -r` would skip git-ignored files). --write-locks also leaves a settings-gradle.lockfile
    next to each settings file; lockfiles in the Gradle builds' directories are deleted before (a previous run that
    was killed) and after. Accepted findings, if any, are in osv-scanner.toml.
    """
    exe = program("osv-scanner", "brew install osv-scanner")
    init = REPO / "ci" / "dependency-locks.init.gradle.kts"
    remove_stray_lockfiles()
    try:
        with tempfile.TemporaryDirectory(prefix="osv-locks-") as locks:
            for build in ("android", "tools"):
                code = run(
                    [
                        gradlew(build),
                        "--console=plain",
                        "-q",
                        "--init-script",
                        init,
                        f"-PosvLockDir={locks}",
                        "--write-locks",
                        "osvLocks",
                    ],
                    cwd=REPO / build,
                )
                if code != 0:
                    raise Failed(f"writing the {build} dependency locks failed (exit {code})")
            lockfiles = sorted(Path(locks).glob("**/gradle.lockfile"))
            if not lockfiles:
                raise Failed(f"Gradle wrote no lockfiles into {locks}")
            # Only what ships: the Gradle runtime locks and the iOS app's Package.resolved. Not backend/go.mod: it
            # also lists Go tools (govulncheck's dependencies) that never link into the server; govulncheck covers
            # the Go code with reachability.
            scan: list[str | Path] = [exe, "scan", "source", "--config", REPO / "osv-scanner.toml"]
            for lockfile in [*lockfiles, IOS_PACKAGE_RESOLVED]:
                scan += ["--lockfile", lockfile]
            return run(scan)
    finally:
        remove_stray_lockfiles()


def remove_stray_lockfiles() -> None:
    """Deletes the untracked lockfiles --write-locks leaves in the Gradle builds' directories. A lockfile git tracks is
    never touched, so turning dependency locking on later cannot make this delete committed files."""
    tracked = set(subprocess.run(["git", "ls-files"], cwd=REPO, capture_output=True, text=True).stdout.split())
    for build in GRADLE_BUILDS:
        for directory in ("", "*/"):
            for name in ("gradle.lockfile", "settings-gradle.lockfile"):
                for stray in (REPO / build).glob(directory + name):
                    if stray.relative_to(REPO).as_posix() not in tracked:
                        stray.unlink(missing_ok=True)


def check_detekt() -> int:
    """The detekt Gradle plugin in every Kotlin build (config/detekt.yml, each module's detekt-baseline.xml)."""
    return first_failure(
        run([gradlew("android"), "--console=plain", "--continue", ":app:detekt", ":shared:detekt"], REPO / "android"),
        run([gradlew("tools"), "--console=plain", "detekt"], cwd=REPO / "tools"),
    )


def go() -> str:
    return program("go", "https://go.dev/dl")


def go_env() -> dict[str, str]:
    """Runs on the exact Go release backend/go.mod pins (its toolchain line), as CI does: with GOTOOLCHAIN=auto a newer
    local Go would be used instead, and govulncheck would check that release's standard library."""
    match = re.search(r"^toolchain (go\S+)", (REPO / "backend" / "go.mod").read_text(), re.MULTILINE)
    if match is None:
        raise Failed("backend/go.mod has no toolchain line")
    return {**os.environ, "GOTOOLCHAIN": match.group(1)}


def check_go() -> int:
    """`go run ./cmd/demo lint -check`: golangci-lint fmt --diff + run, go vet, go test -race (each step runs)."""
    exe = go()
    program("golangci-lint", f"go install github.com/golangci/golangci-lint/v2/cmd/golangci-lint@v{ci_pin('golangci-lint')}")
    return run([exe, "run", "./cmd/demo", "lint", "-check"], cwd=REPO / "backend", env=go_env())


def check_govulncheck() -> int:
    # A Go tool of the backend module: go.mod pins its version, go.sum its bytes.
    return run([go(), "tool", "govulncheck", "./..."], cwd=REPO / "backend", env=go_env())


def check_swiftlint() -> int:
    # From ios/: .swiftlint.yml and the baseline's paths are relative to it.
    exe = program("swiftlint", "brew install swiftlint; on Linux the static build from SwiftLint's releases")
    return run([exe, "lint", "--strict", "--quiet", "--baseline", "swiftlint-baseline.json"], cwd=REPO / "ios")


def check_swift_format() -> int:
    """swift-format from the selected Xcode; no baseline, the code is clean."""
    if sys.platform != "darwin":
        raise Skipped("swift-format runs with Xcode's toolchain, on macOS only")
    xcrun = program("xcrun", "install Xcode")
    files = sorted(p.name for p in (REPO / "ios" / "Brew").glob("*.swift") if not p.name.endswith(".generated.swift"))
    return run([xcrun, "swift-format", "lint", "--strict", *[f"Brew/{f}" for f in files]], cwd=REPO / "ios")


CHECKS = {
    "gitleaks": check_gitleaks,
    "osv": check_osv,
    "detekt": check_detekt,
    "go": check_go,
    "govulncheck": check_govulncheck,
    "swiftlint": check_swiftlint,
    "swift-format": check_swift_format,
}


def main(argv: list[str]) -> int:
    # Each check once, in the order first named: `go go` would otherwise run it twice and count it twice.
    names = list(dict.fromkeys(list(CHECKS) if argv == ["all"] else argv))
    unknown = [n for n in names if n not in CHECKS]
    if not names or unknown:
        print(f"usage: python3 ci/check.py <{'|'.join(CHECKS)}|all> ...", file=sys.stderr)
        return 2
    results: dict[str, str] = {}
    failed: list[str] = []
    warned: list[str] = []
    for name in names:
        print(f"\n=== {name}", flush=True)
        warnings.clear()
        try:
            code = CHECKS[name]()
            result = "ok" if code == 0 else f"FAILED (exit {code})"
        except Skipped as e:
            code, result = 0, f"SKIPPED: {e}"
        except Failed as e:
            code, result = 1, f"FAILED: {e}"
        except Exception as e:  # a bug here, or an OSError starting a program: this check fails, the rest run
            traceback.print_exc()
            code, result = 1, f"FAILED: {type(e).__name__}: {e}"
        if warnings:
            result += "".join(f"; WARNING: {w}" for w in warnings)
            warned.append(name)
        print(f"=== {name}: {result}", flush=True)
        results[name] = result
        if code != 0:
            failed.append(name)
    print("\nSummary:")
    for name, result in results.items():
        print(f"  {name}: {result}")
    skipped = [n for n, r in results.items() if r.startswith("SKIPPED")]
    print(
        f"{len(failed)} failed, {len(skipped)} skipped, {len(results) - len(failed) - len(skipped)} ok"
        f", {len(warned)} with a warning"
    )
    return len(failed)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
