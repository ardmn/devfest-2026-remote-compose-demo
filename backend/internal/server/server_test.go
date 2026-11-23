package server

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"example.com/brew/backend/internal/convert"
)

// fixture is a backend directory of its own: rendered documents (fake bytes), stage.json, the real promo template
// and a fake rc tool — so every test controls its files.
type fixture struct {
	dir  string
	logs *syncBuffer
	s    *Server
}

const fixtureStage = `{"bannersShown": 3, "promo": {"title": "Fixture", "discount": 50, "drinks": ["mocha", "nope"]}}`

func newFixture(t *testing.T) *fixture {
	t.Helper()
	dir := t.TempDir()
	docs := filepath.Join(dir, "documents")
	files := map[string]string{}
	for i := range 5 {
		files[fmt.Sprintf("hero?i=%d", i)] = fmt.Sprintf("hero_i-%d.rc", i)
	}
	for _, key := range []string{"categories", "favorites", "for-you", "details?id=mocha", "row?id=mocha"} {
		files[key] = strings.NewReplacer("?", "_", "=", "-").Replace(key) + ".rc"
	}
	for key, file := range files {
		write(t, filepath.Join(docs, file), "\x00rendered "+key)
	}
	m, err := json.Marshal(map[string]any{"banners": 5, "stageSha256": sha(fixtureStage), "documents": files})
	if err != nil {
		t.Fatal(err)
	}
	write(t, filepath.Join(docs, "manifest.json"), string(m))
	write(t, filepath.Join(docs, "catalog.json"), `{"drinks": [{"id": "mocha", "name": "Iced Mocha", "emoji": "🍫", "priceCents": 490}]}`)
	write(t, filepath.Join(dir, "stage.json"), fixtureStage)
	template, err := os.ReadFile("../../promo.json")
	if err != nil {
		t.Fatal(err)
	}
	write(t, filepath.Join(dir, "promo.json"), string(template))

	f := &fixture{dir: dir, logs: &syncBuffer{}}
	f.s = New(Config{
		Documents: docs,
		Stage:     filepath.Join(dir, "stage.json"),
		Promo:     filepath.Join(dir, "promo.json"),
		Converter: convert.New(t.Context(), fakeTool(t, dir), 0),
		Log:       slog.New(slog.NewTextHandler(f.logs, nil)),
	})
	return f
}

// fakeTool stands in for `rc json2rc`: it answers a fixed "document". Unix only.
func fakeTool(t *testing.T, dir string) string {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("the fake rc tool is a shell script")
	}
	tool := filepath.Join(dir, "rc")
	write(t, tool, "#!/bin/sh\ncat > /dev/null\nprintf '\\000converted promo'\n")
	if err := os.Chmod(tool, 0o755); err != nil { //nolint:gosec // an executable test script
		t.Fatal(err)
	}
	return tool
}

func write(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil { //nolint:gosec // test fixture
		t.Fatal(err)
	}
}

func sha(s string) string { return stageHash([]byte(s)) }

type response struct {
	status int
	header http.Header
	body   string
}

// do sends a real HTTP request through the full handler (mux, logging).
func (f *fixture) do(t *testing.T, method, path string) response {
	t.Helper()
	srv := httptest.NewServer(f.s.Handler())
	defer srv.Close()
	req, err := http.NewRequestWithContext(t.Context(), method, srv.URL+path, http.NoBody)
	if err != nil {
		t.Fatal(err)
	}
	res, err := srv.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = res.Body.Close() }()
	body, err := io.ReadAll(res.Body)
	if err != nil {
		t.Fatal(err)
	}
	return response{res.StatusCode, res.Header, string(body)}
}

func TestRenderedSectionsAreServedAsIs(t *testing.T) {
	t.Parallel()
	f := newFixture(t)
	for path, key := range map[string]string{
		"/sections/hero?i=4":           "hero?i=4",
		"/sections/hero":               "hero?i=0",
		"/sections/categories":         "categories",
		"/sections/favorites":          "favorites",
		"/sections/for-you":            "for-you",
		"/sections/details?id=mocha":   "details?id=mocha",
		"/sections/row?id=mocha":       "row?id=mocha",
		"/sections/row?id=mocha&x=1":   "row?id=mocha",
		"/sections/categories?i=extra": "categories",
	} {
		r := f.do(t, http.MethodGet, path)
		if r.status != http.StatusOK || r.body != "\x00rendered "+key {
			t.Errorf("%s: %d %q", path, r.status, r.body)
		}
		if r.header.Get("Content-Type") != rcContentType || r.header.Get("Cache-Control") != "no-store" {
			t.Errorf("%s: headers %v", path, r.header)
		}
	}
}

func TestEverythingElseIsNotFound(t *testing.T) {
	t.Parallel()
	f := newFixture(t)
	for _, path := range []string{
		"/sections/details?id=espresso-tonic",
		"/sections/details?id=../stage.json",
		"/sections/details?id=mocha%2F..%2F..%2Fstage.json",
		"/sections/row",
		"/sections/hero?i=5",
		"/sections/hero?i=-1",
		"/sections/manifest.json",
		"/sections/catalog.json",
		"/sections/../stage.json",
		"/sections/",
		"/stage.json",
		"/",
	} {
		if r := f.do(t, http.MethodGet, path); r.status != http.StatusNotFound {
			t.Errorf("%s: %d, want 404", path, r.status)
		}
	}
}

func TestMethods(t *testing.T) {
	t.Parallel()
	f := newFixture(t)
	if r := f.do(t, http.MethodPost, "/sections/categories"); r.status != http.StatusMethodNotAllowed {
		t.Errorf("POST: %d, want 405", r.status)
	}
	r := f.do(t, http.MethodHead, "/sections/categories")
	if r.status != http.StatusOK || r.body != "" || r.header.Get("Content-Type") != rcContentType {
		t.Errorf("HEAD: %d %q %v", r.status, r.body, r.header)
	}
}

func TestBanners(t *testing.T) {
	t.Parallel()
	reverse := func(n int) []int {
		p := make([]int, n)
		for i := range p {
			p[i] = n - 1 - i
		}
		return p
	}
	tests := []struct {
		name, stage string
		want        []int
	}{
		{"the stage's count", `{"bannersShown": 2}`, []int{4, 3}},
		{"the default count", `{}`, []int{4, 3, 2}},
		{"no more than the pool", `{"bannersShown": 99}`, []int{4, 3, 2, 1, 0}},
		{"at least one", `{"bannersShown": 0}`, []int{4}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Parallel()
			f := newFixture(t)
			f.s.perm = reverse
			write(t, f.s.cfg.Stage, tt.stage)
			r := f.do(t, http.MethodGet, "/sections/banners")
			var got []int
			if err := json.Unmarshal([]byte(r.body), &got); err != nil || r.status != http.StatusOK {
				t.Fatalf("%d %q: %v", r.status, r.body, err)
			}
			if !slices.Equal(got, tt.want) || r.header.Get("Content-Type") != "application/json" {
				t.Errorf("banners = %v (%s), want %v", got, r.header.Get("Content-Type"), tt.want)
			}
		})
	}
}

func TestEveryRefreshCanShowOtherBanners(t *testing.T) {
	t.Parallel()
	f := newFixture(t)
	answers := map[string]bool{}
	for range 30 {
		answers[f.do(t, http.MethodGet, "/sections/banners").body] = true
	}
	if len(answers) < 5 {
		t.Errorf("only %d different answers in 30 requests: %v", len(answers), answers)
	}
}

func TestPromo(t *testing.T) {
	t.Parallel()
	f := newFixture(t)
	r := f.do(t, http.MethodGet, "/sections/promo.json")
	if r.status != http.StatusOK || r.header.Get("Content-Type") != "application/json" {
		t.Fatalf("promo.json: %d %v", r.status, r.header)
	}
	if !strings.Contains(r.body, `"Fixture"`) || !strings.Contains(r.body, "$4.90 → $2.45") || strings.Contains(r.body, "nope") {
		t.Errorf("promo.json not built from the fixture's stage.json:\n%s", r.body)
	}
	r = f.do(t, http.MethodGet, "/sections/promo")
	if r.status != http.StatusOK || r.body != "\x00converted promo" || r.header.Get("Content-Type") != rcContentType {
		t.Errorf("promo: %d %q", r.status, r.body)
	}
}

func TestErrorsDoNotLeakDetails(t *testing.T) {
	t.Parallel()
	f := newFixture(t)
	f.s.cfg.Converter = convert.New(t.Context(), filepath.Join(f.dir, "no-such-tool"), 0)
	r := f.do(t, http.MethodGet, "/sections/promo")
	if r.status != http.StatusServiceUnavailable || strings.Contains(r.body, f.dir) || strings.Contains(r.body, "gradlew") {
		t.Errorf("missing tool: %d %q", r.status, r.body)
	}
	if !strings.Contains(f.logs.String(), "gradlew installDist") {
		t.Error("the log does not say how to build the tool")
	}

	write(t, f.s.cfg.Stage, `{"promo": `)
	for _, path := range []string{"/sections/promo.json", "/sections/banners"} {
		r = f.do(t, http.MethodGet, path)
		if r.status != http.StatusInternalServerError || strings.Contains(r.body, f.dir) || strings.Contains(r.body, "stage.json") {
			t.Errorf("%s with a broken stage.json: %d %q", path, r.status, r.body)
		}
	}
}

func TestWithoutRenderedDocuments(t *testing.T) {
	t.Parallel()
	f := newFixture(t)
	if err := os.Remove(filepath.Join(f.s.cfg.Documents, "manifest.json")); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"/sections/categories", "/sections/banners"} {
		if r := f.do(t, http.MethodGet, path); r.status != http.StatusServiceUnavailable {
			t.Errorf("%s: %d, want 503", path, r.status)
		}
	}
	if !strings.Contains(f.logs.String(), "go run ./cmd/demo render") {
		t.Error("the log does not say how to render the documents")
	}
}

func TestAnEditedStageAsksForARenderOncePerEdit(t *testing.T) {
	t.Parallel()
	f := newFixture(t)
	srv := httptest.NewServer(f.s.Handler())
	defer srv.Close()
	hammer := func() { // concurrent requests: the warning must still come once
		var wg sync.WaitGroup
		for range 40 {
			wg.Go(func() {
				req, err := http.NewRequestWithContext(t.Context(), http.MethodGet, srv.URL+"/sections/categories", http.NoBody)
				if err != nil {
					t.Error(err)
					return
				}
				res, err := srv.Client().Do(req)
				if err != nil {
					t.Error(err)
					return
				}
				_ = res.Body.Close()
			})
		}
		wg.Wait()
	}
	warnings := func() int { return strings.Count(f.logs.String(), "stage.json changed") }

	hammer()
	if n := warnings(); n != 0 {
		t.Fatalf("%d warnings while stage.json matches the documents", n)
	}
	write(t, f.s.cfg.Stage, `{"bannersShown": 2}`)
	hammer()
	if n := warnings(); n != 1 {
		t.Fatalf("%d warnings after one edit, want 1", n)
	}
	write(t, f.s.cfg.Stage, `{"bannersShown": 1}`)
	hammer()
	if n := warnings(); n != 2 {
		t.Errorf("%d warnings after two edits, want 2", n)
	}
}

func TestConcurrentRequests(t *testing.T) {
	t.Parallel()
	f := newFixture(t)
	srv := httptest.NewServer(f.s.Handler())
	defer srv.Close()
	paths := []string{"/sections/banners", "/sections/promo", "/sections/promo.json", "/sections/hero?i=3", "/sections/details?id=mocha"}
	// 16 clients at once, 200 requests in all (more parallel connections only test the OS's listen backlog).
	const workers, requests = 16, 200
	jobs := make(chan string)
	var wg sync.WaitGroup
	for range workers {
		wg.Go(func() {
			for path := range jobs {
				req, err := http.NewRequestWithContext(t.Context(), http.MethodGet, srv.URL+path, http.NoBody)
				if err != nil {
					t.Error(err)
					continue
				}
				res, err := srv.Client().Do(req)
				if err != nil {
					t.Error(err)
					continue
				}
				_, _ = io.Copy(io.Discard, res.Body)
				_ = res.Body.Close()
				if res.StatusCode != http.StatusOK {
					t.Errorf("%s: %d", path, res.StatusCode)
				}
			}
		})
	}
	for i := range requests {
		jobs <- paths[i%len(paths)]
	}
	close(jobs)
	wg.Wait()
}

func TestDump(t *testing.T) {
	t.Parallel()
	f := newFixture(t)
	out := filepath.Join(t.TempDir(), "sections")
	write(t, filepath.Join(out, "list_ids-a+b.rc"), "an old section") // gone after the dump
	write(t, filepath.Join(out, "lab_i-0.rc"), "a Lab document")      // kept: the Android app writes these
	write(t, filepath.Join(out, "notes.txt"), "not a document")       // kept

	if err := f.s.Dump(t.Context(), out); err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(out)
	if err != nil {
		t.Fatal(err)
	}
	names := make([]string, 0, len(entries))
	for _, e := range entries {
		names = append(names, e.Name())
	}
	want := []string{
		"categories.rc", "details_id-mocha.rc", "favorites.rc", "for-you.rc", "hero_i-0.rc", "hero_i-1.rc", "hero_i-2.rc",
		"hero_i-3.rc", "hero_i-4.rc", "lab_i-0.rc", "notes.txt", "promo.rc", "row_id-mocha.rc",
	}
	if !slices.Equal(names, want) {
		t.Errorf("dumped %v\nwant      %v", names, want)
	}
	if b, _ := os.ReadFile(filepath.Join(out, "promo.rc")); string(b) != "\x00converted promo" {
		t.Errorf("promo.rc = %q", b)
	}
	if b, _ := os.ReadFile(filepath.Join(out, "hero_i-2.rc")); string(b) != "\x00rendered hero?i=2" {
		t.Errorf("hero_i-2.rc = %q", b)
	}
}

func TestDumpNeverTouchesTheDocumentsDirectory(t *testing.T) {
	t.Parallel()
	f := newFixture(t)
	before, err := os.ReadDir(f.s.cfg.Documents)
	if err != nil {
		t.Fatal(err)
	}
	if dumpErr := f.s.Dump(t.Context(), f.s.cfg.Documents); dumpErr == nil || !strings.Contains(dumpErr.Error(), "documents directory") {
		t.Fatalf("err = %v", dumpErr)
	}
	if f.s.Dump(t.Context(), f.s.cfg.Documents+"/.") == nil {
		t.Fatal("the same directory under another name was accepted")
	}
	after, err := os.ReadDir(f.s.cfg.Documents)
	if err != nil || len(after) != len(before) {
		t.Errorf("documents/ went from %d to %d files (%v)", len(before), len(after), err)
	}
}

func TestAFailedDumpLeavesTheDirectoryAlone(t *testing.T) {
	t.Parallel()
	f := newFixture(t)
	f.s.cfg.Converter = convert.New(t.Context(), filepath.Join(f.dir, "no-such-tool"), 0)
	out := t.TempDir()
	write(t, filepath.Join(out, "hero_i-0.rc"), "the previous copy")
	if err := f.s.Dump(t.Context(), out); err == nil {
		t.Fatal("a dump without a converter succeeded")
	}
	if b, _ := os.ReadFile(filepath.Join(out, "hero_i-0.rc")); string(b) != "the previous copy" {
		t.Errorf("the previous copy was replaced or removed: %q", b)
	}
}

func TestAClientThatLeavesIsNotAnError(t *testing.T) {
	t.Parallel()
	f := newFixture(t)
	f.s.cfg.Converter = convert.New(t.Context(), slowTool(t, f.dir, "2"), 0)
	srv := httptest.NewServer(f.s.Handler())
	defer srv.Close()
	ctx, leave := context.WithTimeout(t.Context(), 200*time.Millisecond)
	defer leave()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, srv.URL+"/sections/promo", http.NoBody)
	if err != nil {
		t.Fatal(err)
	}
	if res, err := srv.Client().Do(req); err == nil {
		_ = res.Body.Close()
		t.Fatal("the request finished although the client left")
	}
	srv.Close() // waits for the handler
	if strings.Contains(f.logs.String(), "level=ERROR") {
		t.Errorf("a client leaving was logged as an error:\n%s", f.logs.String())
	}
}

func TestAConversionTimeoutIs504(t *testing.T) {
	t.Parallel()
	f := newFixture(t)
	f.s.cfg.Converter = convert.New(t.Context(), slowTool(t, f.dir, "5"), 100*time.Millisecond)
	if r := f.do(t, http.MethodGet, "/sections/promo"); r.status != http.StatusGatewayTimeout {
		t.Errorf("%d %q, want 504", r.status, r.body)
	}
}

// slowTool answers like fakeTool, after the given number of seconds.
func slowTool(t *testing.T, dir, seconds string) string {
	t.Helper()
	tool := filepath.Join(dir, "slow-rc")
	write(t, tool, "#!/bin/sh\ncat > /dev/null\nsleep "+seconds+"\nprintf '\\000slow promo'\n")
	if err := os.Chmod(tool, 0o755); err != nil { //nolint:gosec // an executable test script
		t.Fatal(err)
	}
	return tool
}

func TestDocumentKey(t *testing.T) {
	t.Parallel()
	tests := []struct{ section, query, want string }{
		{"hero", "i=2", "hero?i=2"},
		{"hero", "", "hero?i=0"},
		{"hero", "i=abc", "hero?i=0"},
		{"hero", "i=02", "hero?i=2"},
		{"hero", "i=-1", "hero?i=-1"}, // not in the manifest: 404
		{"details", "id=caramel", "details?id=caramel"},
		{"details", "id=caramel&id=beans", "details?id=caramel"},
		{"details", "", "details?id="},
		{"row", "id=../../stage.json", "row?id=../../stage.json"}, // only a key: never a file name
		{"categories", "i=7", "categories"},
	}
	for _, tt := range tests {
		q, err := url.ParseQuery(tt.query)
		if err != nil {
			t.Fatal(err)
		}
		if got := documentKey(tt.section, q); got != tt.want {
			t.Errorf("documentKey(%q, %q) = %q, want %q", tt.section, tt.query, got, tt.want)
		}
	}
}

// The real pipeline: backend/documents, stage.json and promo.json as committed, the real rc tool.
func TestTheRealBackend(t *testing.T) {
	t.Parallel()
	tool, err := filepath.Abs("../../../tools/build/install/rc/bin/" + rcName())
	if err != nil {
		t.Fatal(err)
	}
	if _, statErr := os.Stat(tool); statErr != nil {
		// CI builds the tool first and sets BREW_REQUIRE_RC_TOOL=1: a skip there would hide a broken end-to-end path.
		if os.Getenv("BREW_REQUIRE_RC_TOOL") == "1" {
			t.Fatalf("BREW_REQUIRE_RC_TOOL=1 but the rc tool is not built (%v): build it first (cd tools && ./gradlew installDist)", statErr)
		}
		t.Skip("the rc tool is not built (cd tools && ./gradlew installDist): skipping the end-to-end test")
	}
	logs := &syncBuffer{}
	s := New(Config{
		Documents: "../../documents",
		Stage:     "../../stage.json",
		Promo:     "../../promo.json",
		Converter: convert.New(t.Context(), tool, 0),
		Log:       slog.New(slog.NewTextHandler(logs, nil)),
	})
	f := &fixture{logs: logs, s: s}
	r := f.do(t, http.MethodGet, "/sections/promo")
	if r.status != http.StatusOK || len(r.body) < 16 || r.body[0] != 0 {
		t.Fatalf("promo: %d, %d bytes", r.status, len(r.body))
	}
	bundled, err := os.ReadFile("../../../ios/Brew/Resources/sections/promo.rc")
	if err != nil || !bytes.Equal(bundled, []byte(r.body)) {
		t.Error("ios/Brew/Resources/sections/promo.rc is out of date — run `go run ./cmd/demo render`")
	}
	if strings.Contains(logs.String(), "stage.json changed") {
		t.Error("backend/documents was rendered from another stage.json — run `go run ./cmd/demo render`")
	}
	var ids []int
	if err := json.Unmarshal([]byte(f.do(t, http.MethodGet, "/sections/banners").body), &ids); err != nil || len(ids) == 0 {
		t.Fatalf("banners: %v %v", ids, err)
	}
	for _, id := range ids {
		if r := f.do(t, http.MethodGet, fmt.Sprintf("/sections/hero?i=%d", id)); r.status != http.StatusOK {
			t.Errorf("banner %d: %d", id, r.status)
		}
	}
}

// syncBuffer is a bytes.Buffer safe for the concurrent writes of a logger.
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

// A real-sized document goes out gzip-compressed to a client that asks for it, and plain to one that does not.
func TestBigBodiesAreGzippedForClientsThatAskForIt(t *testing.T) {
	t.Parallel()
	f := newFixture(t)
	doc := "\x00" + strings.Repeat("a remote compose document ", 100)
	write(t, filepath.Join(f.dir, "documents", "favorites.rc"), doc)
	srv := httptest.NewServer(f.s.Handler())
	defer srv.Close()
	// DisableCompression: see the bytes on the wire, as Go's client would otherwise unpack them silently.
	client := &http.Client{Transport: &http.Transport{DisableCompression: true}}
	get := func(acceptEncoding string) (http.Header, []byte) {
		t.Helper()
		req, err := http.NewRequestWithContext(t.Context(), http.MethodGet, srv.URL+"/sections/favorites", http.NoBody)
		if err != nil {
			t.Fatal(err)
		}
		if acceptEncoding != "" {
			req.Header.Set("Accept-Encoding", acceptEncoding)
		}
		res, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = res.Body.Close() }()
		body, err := io.ReadAll(res.Body)
		if err != nil {
			t.Fatal(err)
		}
		return res.Header, body
	}

	h, body := get("gzip, deflate, br")
	if h.Get("Content-Encoding") != "gzip" || !strings.Contains(h.Get("Vary"), "Accept-Encoding") {
		t.Fatalf("headers %v", h)
	}
	if len(body) >= len(doc) {
		t.Errorf("gzip body %d B, plain %d B", len(body), len(doc))
	}
	zr, err := gzip.NewReader(bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	plain, err := io.ReadAll(zr)
	if err != nil || string(plain) != doc {
		t.Errorf("unpacked %d B, err %v", len(plain), err)
	}

	for _, ae := range []string{"", "identity", "gzip;q=0", "br"} {
		h, body := get(ae)
		if h.Get("Content-Encoding") != "" || string(body) != doc {
			t.Errorf("Accept-Encoding %q: Content-Encoding %q, %d B", ae, h.Get("Content-Encoding"), len(body))
		}
	}
}

func TestAcceptsGzip(t *testing.T) {
	t.Parallel()
	for header, want := range map[string]bool{
		"": false, "gzip": true, "GZIP": true, "br, gzip": true, "gzip;q=0.5": true, "*": true,
		"gzip;q=0": false, "gzip; q=0.000": false, "identity": false, "deflate, br": false,
	} {
		if got := acceptsGzip(header); got != want {
			t.Errorf("%q: %v, want %v", header, got, want)
		}
	}
}
