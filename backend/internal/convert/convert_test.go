package convert

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeTool writes a shell script standing in for `rc json2rc`. Every run appends a line to the returned runs file.
// body runs after stdin is read; "$RUNS" is the runs file. Unix only.
func fakeTool(t *testing.T, body string) (tool, runs string) {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("the fake rc tools are shell scripts")
	}
	dir := t.TempDir()
	runs = filepath.Join(dir, "runs")
	tool = filepath.Join(dir, "rc")
	script := fmt.Sprintf("#!/bin/sh\nRUNS=%q\ncat > /dev/null\necho run >> \"$RUNS\"\n%s\n", runs, body)
	if err := os.WriteFile(tool, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	return tool, runs
}

// okTool answers a minimal "document": a HEADER opcode (0) and a few bytes.
const okTool = `printf '\000doc'`

func runCount(t *testing.T, runs string) int {
	t.Helper()
	b, err := os.ReadFile(runs)
	if errors.Is(err, os.ErrNotExist) {
		return 0
	}
	if err != nil {
		t.Fatal(err)
	}
	return strings.Count(string(b), "run")
}

func TestConvertCachesByContent(t *testing.T) {
	t.Parallel()
	tool, runs := fakeTool(t, okTool)
	c := New(t.Context(), tool, 0)
	for _, doc := range []string{`{"a":1}`, `{"a":1}`, `{"a":2}`, `{"a":1}`, `{"a":2}`} {
		rc, err := c.Convert(t.Context(), []byte(doc))
		if err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(rc, []byte("\x00doc")) {
			t.Fatalf("got %q", rc)
		}
	}
	if n := runCount(t, runs); n != 2 {
		t.Errorf("the tool ran %d times for 2 distinct documents", n)
	}
}

func TestConvertEvictsTheOldestWhenFull(t *testing.T) {
	t.Parallel()
	tool, runs := fakeTool(t, okTool)
	c := New(t.Context(), tool, 0)
	for i := range maxCachedDocuments + 1 { // one more than fits: {"n":0} is evicted
		if _, err := c.Convert(t.Context(), fmt.Appendf(nil, `{"n":%d}`, i)); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := c.Convert(t.Context(), []byte(`{"n":1}`)); err != nil { // still cached
		t.Fatal(err)
	}
	if n := runCount(t, runs); n != maxCachedDocuments+1 {
		t.Fatalf("runs = %d, want %d", n, maxCachedDocuments+1)
	}
	if _, err := c.Convert(t.Context(), []byte(`{"n":0}`)); err != nil { // evicted: runs again
		t.Fatal(err)
	}
	if n := runCount(t, runs); n != maxCachedDocuments+2 {
		t.Errorf("runs = %d, want %d", n, maxCachedDocuments+2)
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.cache) != maxCachedDocuments || len(c.order) != maxCachedDocuments {
		t.Errorf("cache holds %d (order %d), want %d", len(c.cache), len(c.order), maxCachedDocuments)
	}
}

func TestConcurrentRequestsShareOneRun(t *testing.T) {
	t.Parallel()
	tool, runs := fakeTool(t, "sleep 0.3\n"+okTool)
	c := New(t.Context(), tool, 0)
	const callers = 25
	var wg sync.WaitGroup
	results := make([][]byte, callers)
	errs := make([]error, callers)
	for i := range callers {
		wg.Go(func() { results[i], errs[i] = c.Convert(t.Context(), []byte(`{"same":true}`)) })
	}
	wg.Wait()
	for i := range callers {
		if errs[i] != nil || !bytes.Equal(results[i], []byte("\x00doc")) {
			t.Fatalf("caller %d: %q, %v", i, results[i], errs[i])
		}
	}
	if n := runCount(t, runs); n != 1 {
		t.Errorf("the tool ran %d times for %d concurrent requests of one document", n, callers)
	}
}

func TestADisconnectedClientDoesNotStopTheRunForOthers(t *testing.T) {
	t.Parallel()
	tool, runs := fakeTool(t, "sleep 0.4\n"+okTool)
	c := New(t.Context(), tool, 0)
	gone, leave := context.WithCancel(t.Context())
	errGone := make(chan error, 1)
	go func() {
		_, err := c.Convert(gone, []byte(`{}`))
		errGone <- err
	}()
	time.Sleep(100 * time.Millisecond) // the run has started
	leave()
	if err := <-errGone; !errors.Is(err, context.Canceled) {
		t.Fatalf("the leaving client got %v, want context.Canceled", err)
	}
	rc, err := c.Convert(t.Context(), []byte(`{}`)) // joins the same run
	if err != nil || !bytes.Equal(rc, []byte("\x00doc")) {
		t.Fatalf("the staying client got %q, %v", rc, err)
	}
	if n := runCount(t, runs); n != 1 {
		t.Errorf("runs = %d, want 1 (shared)", n)
	}
}

func TestAHangingToolIsStopped(t *testing.T) {
	t.Parallel()
	tool, _ := fakeTool(t, "exec sleep 30")
	c := New(t.Context(), tool, 200*time.Millisecond)
	start := time.Now()
	_, err := c.Convert(t.Context(), []byte(`{}`))
	if err == nil || !strings.Contains(err.Error(), "stopped") || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("err = %v, want a timeout", err)
	}
	if d := time.Since(start); d > 3*time.Second {
		t.Errorf("took %v to give up", d)
	}
}

func TestShutdownStopsRunningConversions(t *testing.T) {
	t.Parallel()
	tool, _ := fakeTool(t, "exec sleep 30")
	base, shutdown := context.WithCancel(t.Context())
	c := New(base, tool, time.Minute)
	go func() {
		time.Sleep(150 * time.Millisecond)
		shutdown()
	}()
	start := time.Now()
	if _, err := c.Convert(t.Context(), []byte(`{}`)); !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled", err)
	}
	if d := time.Since(start); d > 3*time.Second {
		t.Errorf("took %v to stop", d)
	}
}

func TestFailuresAreReportedAndNotCached(t *testing.T) {
	t.Parallel()
	// Fails the first time (with a message on stderr), works afterwards.
	tool, runs := fakeTool(t, `if [ "$(wc -l < "$RUNS")" -eq 1 ]; then echo "boom: bad JSON" >&2; exit 3; fi
`+okTool)
	c := New(t.Context(), tool, 0)
	_, err := c.Convert(t.Context(), []byte(`{}`))
	if err == nil || !strings.Contains(err.Error(), "boom: bad JSON") || !strings.Contains(err.Error(), "exit status 3") {
		t.Fatalf("err = %v, want the tool's exit status and stderr", err)
	}
	if _, err := c.Convert(t.Context(), []byte(`{}`)); err != nil {
		t.Fatalf("second try: %v (a failure must not be cached)", err)
	}
	if n := runCount(t, runs); n != 2 {
		t.Errorf("runs = %d, want 2", n)
	}
}

func TestOutputThatIsNotADocumentIsRefused(t *testing.T) {
	t.Parallel()
	for name, body := range map[string]string{
		"text":  `echo "<html>error page</html>"`,
		"empty": `true`,
		"one":   `printf '\000'`,
	} {
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			tool, _ := fakeTool(t, body)
			_, err := New(t.Context(), tool, 0).Convert(t.Context(), []byte(`{}`))
			if err == nil || !strings.Contains(err.Error(), "not a Remote Compose document") {
				t.Errorf("err = %v", err)
			}
		})
	}
}

func TestOutputIsCapped(t *testing.T) {
	t.Parallel()
	tool, _ := fakeTool(t, fmt.Sprintf("printf '\\000'; head -c %d /dev/zero", maxDocumentBytes+1))
	_, err := New(t.Context(), tool, 0).Convert(t.Context(), []byte(`{}`))
	if err == nil || !strings.Contains(err.Error(), "document over") {
		t.Errorf("err = %v, want the size limit", err)
	}
}

func TestStderrIsTruncated(t *testing.T) {
	t.Parallel()
	tool, _ := fakeTool(t, fmt.Sprintf("head -c %d /dev/zero | tr '\\000' x >&2; exit 1", 4*maxStderrBytes))
	_, err := New(t.Context(), tool, 0).Convert(t.Context(), []byte(`{}`))
	if err == nil || len(err.Error()) > maxStderrBytes+200 {
		t.Errorf("error of %d bytes: stderr not truncated", len(fmt.Sprint(err)))
	}
}

func TestAMissingToolSaysHowToBuildIt(t *testing.T) {
	t.Parallel()
	c := New(t.Context(), filepath.Join(t.TempDir(), "rc"), 0)
	_, err := c.Convert(t.Context(), []byte(`{}`))
	if !errors.Is(err, ErrToolMissing) || !strings.Contains(err.Error(), "./gradlew installDist") {
		t.Errorf("err = %v", err)
	}
}

func TestCappedBuffer(t *testing.T) {
	t.Parallel()
	// io.Copy (and so os/exec) would call ReadFrom instead of Write, bypassing the limit.
	if _, ok := any(&cappedBuffer{}).(io.ReaderFrom); ok {
		t.Fatal("cappedBuffer must not implement io.ReaderFrom")
	}
	strict := &cappedBuffer{limit: 4}
	if _, err := strict.Write([]byte("abc")); err != nil {
		t.Fatal(err)
	}
	if _, err := strict.Write([]byte("de")); !errors.Is(err, errTooLarge) || !strict.exceeded {
		t.Errorf("err = %v, exceeded = %v", err, strict.exceeded)
	}
	loose := &cappedBuffer{limit: 4, truncate: true}
	for _, s := range []string{"abc", "def", "ghi"} {
		if n, err := loose.Write([]byte(s)); n != len(s) || err != nil {
			t.Fatalf("Write(%q) = %d, %v", s, n, err)
		}
	}
	if loose.String() != "abcd" {
		t.Errorf("kept %q, want %q", loose.String(), "abcd")
	}
}
