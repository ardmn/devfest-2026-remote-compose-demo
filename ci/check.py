#!/usr/bin/env python3
"""Static analysis for the whole repo, the same on a laptop and in CI (.github/workflows/ci.yml).

    python3 ci/check.py <check> [<check> ...]     # or: all

Checks: gitleaks, osv, detekt, go, swiftlint, swift-format (macOS only), all.
Every tool is pinned to an exact version below. Downloaded tools are checked against their SHA-256 and kept in
.cache/ci-tools/ (git-ignored). Each check fails (non-zero exit) on findings that are not in its baseline; the
script exits with the number of failed checks. Standard library only, Python 3.9+.
"""

from __future__ import annotations

import hashlib
import os
import platform
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CACHE = REPO / ".cache" / "ci-tools"

# name -> version, {(os, arch): (url, sha256, archive kind, path of the executable inside the archive)}
TOOLS = {
    "gitleaks": (
        "8.30.1",
        {
            ("darwin", "arm64"): (
                "https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_darwin_arm64.tar.gz",
                "b40ab0ae55c505963e365f271a8d3846efbc170aa17f2607f13df610a9aeb6a5",
                "tar.gz",
                "gitleaks",
            ),
            ("linux", "x86_64"): (
                "https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_linux_x64.tar.gz",
                "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb",
                "tar.gz",
                "gitleaks",
            ),
        },
    ),
    "osv-scanner": (
        "2.7.1",
        {
            ("darwin", "arm64"): (
                "https://github.com/google/osv-scanner/releases/download/v2.7.1/osv-scanner_darwin_arm64",
                "b22c02877a811c8f78956e8643efa554932b7378f590d13f8e97d4e9448a29f3",
                "bin",
                "osv-scanner",
            ),
            ("linux", "x86_64"): (
                "https://github.com/google/osv-scanner/releases/download/v2.7.1/osv-scanner_linux_amd64",
                "fb7dab19a8bd9bbe5c3189f09d4b7ee1820c9553a23bac202648a56efd2f6ee8",
                "bin",
                "osv-scanner",
            ),
        },
    ),
    "golangci-lint": (
        "2.15.0",
        {
            ("darwin", "arm64"): (
                "https://github.com/golangci/golangci-lint/releases/download/v2.15.0/golangci-lint-2.15.0-darwin-arm64.tar.gz",
                "d1e409481a8ca846c4bc97e781eb5cb1bd5c23353a0b3977a1cbda1b1b6447d5",
                "tar.gz",
                "golangci-lint-2.15.0-darwin-arm64/golangci-lint",
            ),
            ("linux", "x86_64"): (
                "https://github.com/golangci/golangci-lint/releases/download/v2.15.0/golangci-lint-2.15.0-linux-amd64.tar.gz",
                "9b7fe6a6bf6334b98decf8d86b11415ea7889afdabdd1959fdce23af2a9ebcea",
                "tar.gz",
                "golangci-lint-2.15.0-linux-amd64/golangci-lint",
            ),
        },
    ),
    # A JVM program: the same file everywhere.
    "detekt": (
        "1.23.8",
        {
            ("any", "any"): (
                "https://github.com/detekt/detekt/releases/download/v1.23.8/detekt-cli-1.23.8-all.jar",
                "2ce2ff952e150baf28a29cda70a363b0340b3e81a55f43e51ec5edffc3d066c1",
                "bin",
                "detekt-cli-1.23.8-all.jar",
            ),
        },
    ),
    "swiftlint": (
        "0.65.1",
        {
            ("darwin", "arm64"): (
                "https://github.com/realm/SwiftLint/releases/download/0.65.1/portable_swiftlint.zip",
                "c1e429b0599cf1b516f369a2d9ec04eaf0e436f3c12b637df8851fa52ff694d0",
                "zip",
                "swiftlint",
            ),
            # The fully static build: no Swift toolchain needed. The few rules that need SourceKit are skipped
            # (it says which); none of them is in the baseline, they still run on macOS.
            ("linux", "x86_64"): (
                "https://github.com/realm/SwiftLint/releases/download/0.65.1/swiftlint_linux_amd64.zip",
                "caeed6f4a679c35539ffaf124f6c4ab4a8416917f7d8796279dc52b74026059d",
                "zip",
                "swiftlint-static",
            ),
        },
    ),
}

# Fetched by `go run` through the Go checksum database, so pinning the version pins the bytes.
GOVULNCHECK = "golang.org/x/vuln/cmd/govulncheck@v1.9.0"


def platform_key() -> tuple[str, str]:
    machine = platform.machine().lower()
    arch = {"amd64": "x86_64", "aarch64": "arm64"}.get(machine, machine)
    return sys.platform, arch  # "darwin" or "linux"


def tool(name: str) -> Path:
    """Returns the pinned executable (or jar) for `name`, downloading and verifying it on first use."""
    version, assets = TOOLS[name]
    key = ("any", "any") if ("any", "any") in assets else platform_key()
    if key not in assets:
        raise SystemExit(f"{name} {version}: no pinned build for {key[0]}/{key[1]}; add one to TOOLS in ci/check.py")
    url, sha256, kind, inner = assets[key]
    home = CACHE / f"{name}-{version}"
    exe = home / inner
    if exe.exists():
        return exe
    home.mkdir(parents=True, exist_ok=True)
    download = home / ("download." + kind)
    print(f"Downloading {name} {version}: {url}", flush=True)
    with urllib.request.urlopen(url, timeout=120) as response, open(download, "wb") as out:
        shutil.copyfileobj(response, out)
    digest = hashlib.sha256(download.read_bytes()).hexdigest()
    if digest != sha256:
        download.unlink()
        raise SystemExit(f"{name} {version}: SHA-256 mismatch for {url}: got {digest}, pinned {sha256}")
    if kind == "bin":
        download.rename(exe)
    elif kind == "tar.gz":
        with tarfile.open(download) as archive:
            archive.extract(archive.getmember(inner), home)
        download.unlink()
    else:
        with zipfile.ZipFile(download) as archive:
            archive.extract(inner, home)
        download.unlink()
    exe.chmod(exe.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    return exe


def run(args: list[str | Path], cwd: Path = REPO, env: dict[str, str] | None = None) -> int:
    print(f"$ (cd {cwd.relative_to(REPO) if cwd != REPO else '.'}) {' '.join(str(a) for a in args)}", flush=True)
    return subprocess.run([str(a) for a in args], cwd=cwd, env=env, check=False).returncode


def gradlew(build: str) -> Path:
    return REPO / build / ("gradlew.bat" if os.name == "nt" else "gradlew")


# --- checks: each returns the exit code of its first failing step (0 = clean) -------------------------------------


def check_gitleaks() -> int:
    # Every commit of the history, not only the working tree. Known false positives would go to .gitleaksignore.
    return run([tool("gitleaks"), "git", "--redact", "--no-banner", "--exit-code", "1", "."])


def check_osv() -> int:
    """Known vulnerabilities in go.mod, Package.resolved and the resolved Gradle dependency graphs.

    The Gradle builds keep no lockfiles, so this writes them for the scan (ci/dependency-locks.init.gradle.kts) and
    deletes them afterwards. Accepted findings are listed, with reasons, in osv-scanner.toml.
    """
    init = REPO / "ci" / "dependency-locks.init.gradle.kts"
    locks = [
        REPO / "android" / "app" / "gradle.lockfile",
        REPO / "android" / "settings-gradle.lockfile",
        REPO / "shared" / "gradle.lockfile",
        REPO / "tools" / "gradle.lockfile",
        REPO / "tools" / "settings-gradle.lockfile",
    ]
    common = ["--console=plain", "-q", "--init-script", init, "--write-locks"]
    try:
        for build, tasks in (("android", [":app:dependencies", ":shared:dependencies"]), ("tools", ["dependencies"])):
            code = subprocess.run(
                [str(a) for a in [gradlew(build), *common, *tasks]], cwd=REPO / build, stdout=subprocess.DEVNULL, check=False
            ).returncode
            if code != 0:
                print(f"osv: writing the {build} dependency locks failed (exit {code})")
                return code
        return run([tool("osv-scanner"), "scan", "source", "--recursive", "--config", REPO / "osv-scanner.toml", "."])
    finally:
        for lock in locks:
            lock.unlink(missing_ok=True)


def check_detekt() -> int:
    java = shutil.which("java")
    if java is None:
        raise SystemExit("detekt needs Java 17+ on the PATH")
    sources = ",".join(["android/app/src", "shared/src", "tools/src"])
    return run(
        [
            java,
            "-jar",
            tool("detekt"),
            "--input",
            sources,
            "--config",
            "config/detekt.yml",
            "--build-upon-default-config",
            "--baseline",
            "config/detekt-baseline.xml",
        ]
    )


def go_toolchain() -> str:
    """The exact Go release backend/go.mod pins (its toolchain line), e.g. go1.26.11."""
    match = re.search(r"^toolchain (go\S+)", (REPO / "backend" / "go.mod").read_text(), re.MULTILINE)
    if match is None:
        raise SystemExit("backend/go.mod has no toolchain line: check.py go runs on exactly that Go release")
    return match.group(1)


def check_go() -> int:
    """`go run ./cmd/demo lint -check` (the one list of Go checks: golangci-lint fmt --diff + run, vet, tests) with the
    pinned golangci-lint, then govulncheck.

    Every step runs on the Go release go.mod pins. GOTOOLCHAIN=auto would keep a newer Go installed here, and
    govulncheck would then check that release's standard library, not the one the backend is built and tested with.
    """
    backend = REPO / "backend"
    env = {**os.environ, "GOTOOLCHAIN": go_toolchain()}
    for step in (
        ["go", "run", "./cmd/demo", "lint", "-check", "-golangci-lint", tool("golangci-lint")],
        ["go", "run", GOVULNCHECK, "./..."],
    ):
        code = run(step, cwd=backend, env=env)
        if code != 0:
            return code
    return 0


def check_swiftlint() -> int:
    # Run from ios/: .swiftlint.yml and the baseline's paths are relative to it.
    return run([tool("swiftlint"), "lint", "--strict", "--quiet", "--baseline", "swiftlint-baseline.json"], cwd=REPO / "ios")


def check_swift_format() -> int:
    """swift-format from the selected Xcode (6.4.4 in Xcode 27.2); no baseline, the code is clean."""
    if sys.platform != "darwin":
        print("swift-format: skipped, it runs with Xcode's toolchain on macOS (see ci/README.md)")
        return 0
    files = sorted(p.name for p in (REPO / "ios" / "Brew").glob("*.swift") if not p.name.endswith(".generated.swift"))
    return run(["xcrun", "swift-format", "lint", "--strict", *[f"Brew/{f}" for f in files]], cwd=REPO / "ios")


CHECKS = {
    "gitleaks": check_gitleaks,
    "osv": check_osv,
    "detekt": check_detekt,
    "go": check_go,
    "swiftlint": check_swiftlint,
    "swift-format": check_swift_format,
}


def main(argv: list[str]) -> int:
    names = list(CHECKS) if argv == ["all"] else argv
    unknown = [n for n in names if n not in CHECKS]
    if not names or unknown:
        print(f"usage: python3 ci/check.py <{'|'.join(CHECKS)}|all> ...", file=sys.stderr)
        return 2
    failed = []
    for name in names:
        print(f"\n=== {name}", flush=True)
        code = CHECKS[name]()
        print(f"=== {name}: {'ok' if code == 0 else f'FAILED (exit {code})'}", flush=True)
        if code != 0:
            failed.append(name)
    if failed:
        print(f"\nFailed: {', '.join(failed)}")
    return len(failed)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
