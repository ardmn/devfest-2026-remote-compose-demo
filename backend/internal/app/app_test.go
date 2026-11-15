package app

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestDefaultTool(t *testing.T) {
	t.Setenv("RC_TOOL", "/opt/rc")
	if got := DefaultTool(); got != "/opt/rc" {
		t.Errorf("with RC_TOOL: %q", got)
	}
	t.Setenv("RC_TOOL", "")
	got := DefaultTool()
	if !filepath.IsAbs(got) || !strings.HasSuffix(filepath.ToSlash(got), "tools/build/install/rc/bin/"+rcName()) {
		t.Errorf("default: %q, want the tool tools/ builds", got)
	}
}

// The committed backend files, from cmd/brew-backend.
var backendFiles = []string{"-documents", "../../documents", "-stage", "../../stage.json", "-promo", "../../promo.json"}

func TestServesAndShutsDownGracefully(t *testing.T) {
	t.Parallel()
	logs := &syncBuffer{}
	ctx, stop := context.WithCancel(t.Context())
	done := make(chan error, 1)
	go func() {
		// -port 0: any free port; -rc to a missing tool: the promo is not needed here.
		args := append([]string{"-port", "0", "-rc", filepath.Join(t.TempDir(), "rc")}, backendFiles...)
		done <- Run(ctx, args, slog.New(slog.NewTextHandler(logs, nil)))
	}()

	url := waitForURL(t, logs)
	req, err := http.NewRequestWithContext(t.Context(), http.MethodGet, url+"categories", http.NoBody)
	if err != nil {
		t.Fatal(err)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(res.Body)
	_ = res.Body.Close()
	if res.StatusCode != http.StatusOK || len(body) == 0 || body[0] != 0 {
		t.Fatalf("categories: %d, %d bytes", res.StatusCode, len(body))
	}

	stop() // Ctrl+C
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("run after Ctrl+C: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the server did not shut down")
	}
	if !strings.Contains(logs.String(), "shutting down") {
		t.Error("no shutdown in the log")
	}
}

func TestRefusesToStartWithoutDocuments(t *testing.T) {
	t.Parallel()
	err := Run(t.Context(), []string{"-documents", t.TempDir()}, slog.New(slog.DiscardHandler))
	if err == nil || !strings.Contains(err.Error(), "go run ./cmd/demo render") {
		t.Errorf("err = %v", err)
	}
}

func TestRejectsUnknownFlags(t *testing.T) {
	t.Parallel()
	if err := Run(t.Context(), []string{"-nope"}, slog.New(slog.DiscardHandler)); err == nil {
		t.Error("an unknown flag was accepted")
	}
}

func TestTheBusyPortIsExplained(t *testing.T) {
	t.Parallel()
	logs := &syncBuffer{}
	ctx, stop := context.WithCancel(t.Context())
	first := make(chan error, 1)
	missingTool := []string{"-rc", filepath.Join(t.TempDir(), "rc")}
	go func() {
		first <- Run(ctx, slices.Concat([]string{"-port", "0"}, missingTool, backendFiles), slog.New(slog.NewTextHandler(logs, nil)))
	}()
	url := waitForURL(t, logs)
	port := regexp.MustCompile(`:(\d+)/`).FindStringSubmatch(url)[1]
	err := Run(t.Context(), slices.Concat([]string{"-port", port}, missingTool, backendFiles), slog.New(slog.DiscardHandler))
	if err == nil || !strings.Contains(err.Error(), "another backend") {
		t.Errorf("err = %v", err)
	}
	stop()
	if err := <-first; err != nil {
		t.Errorf("the first server: %v", err)
	}
}

func TestABadPortIsNotBlamedOnAnotherBackend(t *testing.T) {
	t.Parallel()
	err := Run(t.Context(), slices.Concat([]string{"-port", "99999"}, backendFiles), slog.New(slog.DiscardHandler))
	if err == nil || strings.Contains(err.Error(), "another backend") {
		t.Errorf("err = %v", err)
	}
}

// Ctrl+C while a promo is being converted: the request still gets its document.
func TestShutdownFinishesTheRequestsInFlight(t *testing.T) {
	t.Parallel()
	tool := filepath.Join(t.TempDir(), "rc")
	if err := os.WriteFile(tool, []byte("#!/bin/sh\ncat > /dev/null\nsleep 1\nprintf '\\000promo'\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	logs := &syncBuffer{}
	ctx, stop := context.WithCancel(t.Context())
	done := make(chan error, 1)
	go func() {
		done <- Run(ctx, slices.Concat([]string{"-port", "0", "-rc", tool}, backendFiles), slog.New(slog.NewTextHandler(logs, nil)))
	}()
	url := waitForURL(t, logs)

	type answer struct {
		status int
		body   string
		err    error
	}
	got := make(chan answer, 1)
	go func() {
		req, err := http.NewRequestWithContext(t.Context(), http.MethodGet, url+"promo", http.NoBody)
		if err != nil {
			got <- answer{err: err}
			return
		}
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			got <- answer{err: err}
			return
		}
		defer func() { _ = res.Body.Close() }()
		b, err := io.ReadAll(res.Body)
		got <- answer{res.StatusCode, string(b), err}
	}()
	time.Sleep(300 * time.Millisecond) // the promo request is waiting for the conversion
	stop()                             // Ctrl+C

	a := <-got
	if a.err != nil || a.status != http.StatusOK || a.body != "\x00promo" {
		t.Errorf("the request in flight got %d %q, %v", a.status, a.body, a.err)
	}
	if err := <-done; err != nil {
		t.Errorf("run: %v", err)
	}
}

func TestDumpMode(t *testing.T) {
	t.Parallel()
	tool, err := filepath.Abs("../../../tools/build/install/rc/bin/" + rcName())
	if err != nil {
		t.Fatal(err)
	}
	if _, statErr := os.Stat(tool); statErr != nil {
		t.Skip("the rc tool is not built (cd tools && ./gradlew installDist)")
	}
	out := t.TempDir()
	if err := Run(t.Context(), append([]string{"-dump", out, "-rc", tool}, backendFiles...), slog.New(slog.DiscardHandler)); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"promo.rc", "hero_i-0.rc", "details_id-caramel.rc", "row_id-beans.rc"} {
		b, err := os.ReadFile(filepath.Join(out, name))
		if err != nil || len(b) == 0 || b[0] != 0 {
			t.Errorf("%s: %d bytes, %v", name, len(b), err)
		}
	}
}

// waitForURL waits for the "listening" log line and returns the sections URL in it.
func waitForURL(t *testing.T, logs *syncBuffer) string {
	t.Helper()
	re := regexp.MustCompile(`url=(http://\S+/sections/)`)
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if m := re.FindStringSubmatch(logs.String()); m != nil {
			return m[1]
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("the server did not start:\n%s", logs.String())
	return ""
}

// syncBuffer is a bytes.Buffer safe for a logger writing while the test reads.
type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (b *syncBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.b.Write(p)
}

func (b *syncBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.b.String()
}

// rcName is the rc tool's start script: rc, or rc.bat on Windows.
func rcName() string {
	if runtime.GOOS == "windows" {
		return "rc.bat"
	}
	return "rc"
}
