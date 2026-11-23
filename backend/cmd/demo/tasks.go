package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"path/filepath"
	"strconv"
	"strings"

	"example.com/brew/backend/internal/app"
)

// serve builds the rc tool when it is missing, forwards the Android device's port to this computer, and runs the
// server in this process (so Ctrl+C reaches it directly, on every OS).
func serve(ctx context.Context, e env, args []string, out io.Writer) error {
	flags := flag.NewFlagSet("serve", flag.ContinueOnError)
	flags.SetOutput(out)
	lan := flags.Bool("lan", false, "also phones on the same Wi-Fi (listen on 0.0.0.0)")
	port := flags.Int("port", 8080, "port to listen on")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if err := ensureTool(ctx, e, out); err != nil {
		return err
	}
	adbReverse(ctx, e, out, *port)
	serverArgs := []string{"-port", strconv.Itoa(*port), "-rc", e.rcTool()}
	if *lan {
		serverArgs = append(serverArgs, "-lan")
	}
	return app.Run(ctx, serverArgs, newLogger(out))
}

// render renders the Kotlin sections for stage.json (tools/: documents, manifest, catalog, the iOS catalog), then
// writes the iOS app's bundled offline copies.
func render(ctx context.Context, e env, out io.Writer) error {
	java, err := e.javaHome()
	if err != nil {
		return err
	}
	say(out, "Rendering the Kotlin sections (the first run downloads dependencies, about a minute)…")
	if err := runTool(ctx, out, filepath.Join(e.repo, "tools"), java, e.gradlew(), "--console=plain", "-q", "installDist", "render"); err != nil {
		return err
	}
	ios := filepath.Join(e.repo, "ios", "Brew", "Resources", "sections")
	return app.Run(ctx, []string{"-dump", ios, "-rc", e.rcTool()}, newLogger(out))
}

// lint formats, lints, vets and tests the backend (golangci-lint v2 from the PATH, config .golangci.yml). Every step
// runs even when an earlier one fails, and the error lists each failure. CI runs the same steps as separate workflow
// steps (.github/workflows/ci.yml); this is the local equivalent.
//
//	-check  only report unformatted files (golangci-lint fmt --diff, fails on a diff) instead of formatting them in
//	        place
//
// It takes no positional arguments: `lint check` is refused (usage), so a mistyped -check never formats in place.
func lint(ctx context.Context, e env, run runner, args []string, out io.Writer) error {
	flags := flag.NewFlagSet("lint", flag.ContinueOnError)
	flags.SetOutput(out)
	check := flags.Bool("check", false, "report unformatted files and fail instead of formatting them")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() > 0 {
		say(out, fmt.Sprintf("lint takes no arguments, got %q (did you mean -check?)", flags.Args()))
		return errUsage
	}
	linter, err := e.lookPath("golangci-lint")
	if err != nil {
		return errors.New("golangci-lint not found on the PATH: install the version CI uses (ci/README.md), " +
			"e.g. go install github.com/golangci/golangci-lint/v2/cmd/golangci-lint@v2.15.0")
	}
	var version strings.Builder
	if err := run(ctx, &version, ".", "", linter, "version"); err != nil {
		return err
	}
	if !isV2(version.String()) {
		return fmt.Errorf("golangci-lint v2 is needed (.golangci.yml is a v2 config); found: %s", strings.TrimSpace(version.String()))
	}
	say(out, strings.TrimSpace(version.String()))
	var failed []error
	for _, step := range lintSteps(linter, *check) {
		if err := run(ctx, out, ".", "", step[0], step[1:]...); err != nil {
			say(out, "lint: FAILED: "+err.Error())
			failed = append(failed, err)
		}
	}
	if len(failed) > 0 {
		return errors.Join(failed...)
	}
	say(out, "lint: all clean")
	return nil
}

// runner runs a program the way runTool does; tests pass one that records the calls instead.
type runner func(ctx context.Context, out io.Writer, dir, javaHome, name string, args ...string) error

// lintSteps are the commands lint runs, in order; check makes the format step fail on a diff instead of formatting.
func lintSteps(linter string, check bool) [][]string {
	format := []string{linter, "fmt"}
	if check {
		format = append(format, "--diff")
	}
	return [][]string{
		format,
		{linter, "run"},
		{"go", "vet", "./..."},
		{"go", "test", "-race", "-count=1", "./..."},
	}
}

// isV2 tells golangci-lint 2.x from older versions by its `version` output.
func isV2(version string) bool { return strings.Contains(version, "version 2.") }

// ensureTool builds the rc tool (tools/, JSON → .rc) when it is not there yet.
func ensureTool(ctx context.Context, e env, out io.Writer) error {
	if e.exists(e.rcTool()) {
		return nil
	}
	java, err := e.javaHome()
	if err != nil {
		return fmt.Errorf("the JSON promo needs the rc tool, and building it needs Java: %w", err)
	}
	say(out, "Building the rc tool once (JSON → .rc; the first run downloads dependencies, about a minute)…")
	return runTool(ctx, out, filepath.Join(e.repo, "tools"), java, e.gradlew(), "--console=plain", "-q", "installDist")
}

// adbReverse makes the server reachable as 127.0.0.1 from Android emulators and USB phones. Without a device that is
// fine (the iOS simulator and this computer need nothing).
func adbReverse(ctx context.Context, e env, out io.Writer, port int) {
	adb, ok := e.adb()
	if !ok {
		say(out, "adb not found: skipping adb reverse (fine for the iOS simulator)")
		return
	}
	p := "tcp:" + strconv.Itoa(port)
	if err := runTool(ctx, io.Discard, ".", "", adb, "reverse", p, p); err != nil {
		say(out, "no Android device connected: skipping adb reverse (fine for the iOS simulator)")
		return
	}
	say(out, "adb reverse "+p+" "+p+" — ok")
}

// say writes a line for the person at the terminal (a failed write to the terminal is not worth stopping for).
func say(out io.Writer, line string) { _, _ = fmt.Fprintln(out, line) }
