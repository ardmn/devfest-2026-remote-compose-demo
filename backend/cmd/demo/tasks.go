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

// lint formats, lints, vets and tests the backend (golangci-lint v2, config .golangci.yml). It is the one list of Go
// checks: ci/check.py go runs it too, with -check and its pinned golangci-lint.
//
//	-check               only report unformatted files (golangci-lint fmt --diff, fails on a diff) instead of
//	                     formatting them in place, as CI does
//	-golangci-lint PATH  this golangci-lint instead of the one on the PATH
func lint(ctx context.Context, e env, args []string, out io.Writer) error {
	flags := flag.NewFlagSet("lint", flag.ContinueOnError)
	flags.SetOutput(out)
	check := flags.Bool("check", false, "report unformatted files and fail instead of formatting them")
	linter := flags.String("golangci-lint", "", "golangci-lint to use (default: the one on the PATH)")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if *linter == "" {
		found, err := e.lookPath("golangci-lint")
		if err != nil {
			return errors.New("golangci-lint not found: https://golangci-lint.run/welcome/install (macOS: brew install golangci-lint)")
		}
		*linter = found
	}
	var version strings.Builder
	if err := runTool(ctx, &version, ".", "", *linter, "version"); err != nil {
		return err
	}
	if !isV2(version.String()) {
		return fmt.Errorf("golangci-lint v2 is needed (.golangci.yml is a v2 config); found: %s", strings.TrimSpace(version.String()))
	}
	for _, step := range lintSteps(*linter, *check) {
		if err := runTool(ctx, out, ".", "", step[0], step[1:]...); err != nil {
			return err
		}
	}
	say(out, "lint: all clean")
	return nil
}

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
