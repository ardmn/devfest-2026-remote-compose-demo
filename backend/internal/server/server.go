// Package server is the Brew backend's HTTP side: it hands out the rendered documents, picks the banners, and
// builds the JSON promo.
package server

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log/slog"
	"math/rand/v2"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"

	"example.com/brew/backend/internal/convert"
	"example.com/brew/backend/internal/documents"
	"example.com/brew/backend/internal/promo"
	"example.com/brew/backend/internal/stage"
)

const rcContentType = "application/vnd.remotecompose"

// Server hands out Brew's sections:
//
//	GET /sections/banners       which banners the carousel shows, as a JSON array of pool indices, e.g. [3,0,4]:
//	                            a random choice and order on every request (every pull to refresh)
//	GET /sections/{section}     hero?i=N, categories, favorites, for-you, details?id=ID, row?id=ID — documents
//	                            rendered ahead of time from the Kotlin @RemoteComposable sections (go run ./cmd/demo render)
//	GET /sections/promo         the JSON way: promo.json filled from stage.json, converted to .rc
//	GET /sections/promo.json    that JSON, to look at
//
// Every file is read per request: run `go run ./cmd/demo render`, or edit stage.json / promo.json, then pull to refresh.
// Safe for concurrent use.
type Server struct {
	cfg  Config
	perm func(n int) []int // a random permutation of 0…n-1

	mu        sync.Mutex
	warnedFor string // the stage.json hash the "render again" warning was logged for
}

// Config is where a Server finds its files and helpers.
type Config struct {
	Documents string             // directory of rendered documents (go run ./cmd/demo render)
	Stage     string             // stage.json
	Promo     string             // promo.json, the template
	Converter *convert.Converter // JSON → .rc
	Log       *slog.Logger
}

// New returns a Server. A nil Config.Log discards the log.
func New(cfg Config) *Server {
	if cfg.Log == nil {
		cfg.Log = slog.New(slog.DiscardHandler)
	}
	return &Server{
		cfg:  cfg,
		perm: rand.Perm, // math/rand/v2's top-level functions are safe for concurrent use
	}
}

// Handler routes the requests and logs each one.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	// GET patterns also match HEAD; other methods get 405 from the mux.
	mux.HandleFunc("GET /sections/banners", s.banners)
	mux.HandleFunc("GET /sections/promo", s.promoDocument)
	mux.HandleFunc("GET /sections/promo.json", s.promoJSON)
	mux.HandleFunc("GET /sections/{section}", s.document)
	return s.logRequests(mux)
}

func (s *Server) banners(w http.ResponseWriter, r *http.Request) {
	m, st, ok := s.load(w, r)
	if !ok {
		return
	}
	shown := DefaultBannersShown
	if st.BannersShown != nil {
		shown = *st.BannersShown
	}
	body, err := json.Marshal(pickBanners(m.Banners, shown, s.perm))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(body)
}

func (s *Server) document(w http.ResponseWriter, r *http.Request) {
	m, _, ok := s.load(w, r)
	if !ok {
		return
	}
	file, ok := m.Documents[documentKey(r.PathValue("section"), r.URL.Query())]
	if !ok {
		http.NotFound(w, r) // unknown section, unknown id, banner out of range
		return
	}
	rc, err := os.ReadFile(filepath.Join(s.cfg.Documents, file)) // a plain file name: LoadManifest checked it
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeDocument(w, rc)
}

func (s *Server) promoJSON(w http.ResponseWriter, r *http.Request) {
	body, err := s.buildPromo()
	if err != nil {
		s.fail(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(body)
}

func (s *Server) promoDocument(w http.ResponseWriter, r *http.Request) {
	body, err := s.buildPromo()
	if err != nil {
		s.fail(w, r, err)
		return
	}
	rc, err := s.cfg.Converter.Convert(r.Context(), body)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeDocument(w, rc)
}

// WarmUp converts the current promo, so the first app request does not wait for the JVM.
func (s *Server) WarmUp(ctx context.Context) error {
	body, err := s.buildPromo()
	if err != nil {
		return err
	}
	_, err = s.cfg.Converter.Convert(ctx, body)
	return err
}

// buildPromo is the promo's JSON from the current promo.json, stage.json and catalog.
func (s *Server) buildPromo() ([]byte, error) {
	template, err := os.ReadFile(s.cfg.Promo)
	if err != nil {
		return nil, err
	}
	st, _, err := stage.Read(s.cfg.Stage)
	if err != nil {
		return nil, err
	}
	cat, err := documents.LoadCatalog(s.cfg.Documents)
	if err != nil {
		return nil, err
	}
	return promo.Build(template, st.Promo, cat)
}

// load reads the manifest and stage.json for a request: 503 when nothing is rendered, 500 when stage.json is
// broken. It also warns once per edit when stage.json no longer matches the rendered documents.
func (s *Server) load(w http.ResponseWriter, r *http.Request) (documents.Manifest, stage.Stage, bool) {
	m, err := documents.LoadManifest(s.cfg.Documents)
	if err != nil {
		s.cfg.Log.Error("no rendered documents: run go run ./cmd/demo render", "dir", s.cfg.Documents, "err", err)
		http.Error(w, "documents not rendered", http.StatusServiceUnavailable)
		return documents.Manifest{}, stage.Stage{}, false
	}
	st, raw, err := stage.Read(s.cfg.Stage)
	if err != nil {
		s.fail(w, r, err)
		return documents.Manifest{}, stage.Stage{}, false
	}
	s.checkStage(m, raw)
	return m, st, true
}

// checkStage logs once per edit when stage.json no longer matches the documents: banners, favorites and for-you
// are rendered ahead of time, so they show the old stage until go run ./cmd/demo render runs again (the promo is live).
func (s *Server) checkStage(m documents.Manifest, raw []byte) {
	if raw == nil {
		return
	}
	hash := stageHash(raw)
	if hash == m.StageSHA256 {
		return
	}
	s.mu.Lock()
	first := hash != s.warnedFor
	s.warnedFor = hash
	s.mu.Unlock()
	if first {
		s.cfg.Log.Warn("stage.json changed since the documents were rendered: banners, favorites and for-you show the " +
			"previous version until you run `go run ./cmd/demo render` (the JSON promo and the banner choice are live)")
	}
}

// fail logs the error and answers without its details (paths, tool output stay in the server's log).
func (s *Server) fail(w http.ResponseWriter, r *http.Request, err error) {
	if r.Context().Err() != nil { // the client went away: nobody reads an answer, and it is not a server error
		s.cfg.Log.Debug("client gone", "path", r.URL.RequestURI(), "err", err)
		return
	}
	s.cfg.Log.Error("request failed", "path", r.URL.RequestURI(), "err", err)
	switch {
	case errors.Is(err, convert.ErrToolMissing):
		http.Error(w, "the JSON converter is not available", http.StatusServiceUnavailable)
	case errors.Is(err, context.DeadlineExceeded):
		http.Error(w, http.StatusText(http.StatusGatewayTimeout), http.StatusGatewayTimeout)
	default:
		http.Error(w, http.StatusText(http.StatusInternalServerError), http.StatusInternalServerError)
	}
}

func writeDocument(w http.ResponseWriter, rc []byte) {
	w.Header().Set("Content-Type", rcContentType)
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Cache-Control", "no-store") // every pull shows the current files
	_, _ = w.Write(rc)
}

// documentKey is the manifest key for a request: "hero?i=0", "details?id=caramel", "categories". Query values
// come from the client: they are only ever looked up in the manifest, never used as file names.
func documentKey(section string, q url.Values) string {
	switch section {
	case "hero":
		i, err := strconv.Atoi(q.Get("i"))
		if err != nil {
			i = 0
		}
		return "hero?i=" + strconv.Itoa(i)
	case "details", "row":
		return section + "?id=" + q.Get("id")
	default:
		return section
	}
}

func (s *Server) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		s.cfg.Log.Info("request", "method", r.Method, "path", r.URL.RequestURI(), "status", rec.status,
			"took", time.Since(start).Round(time.Microsecond))
	})
}

// statusRecorder remembers the status code for the request log.
type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

// Unwrap lets http.ResponseController reach the underlying writer.
func (r *statusRecorder) Unwrap() http.ResponseWriter { return r.ResponseWriter }

// stageHash is stage.json's SHA-256 in hex, as go run ./cmd/demo render writes it into the manifest.
func stageHash(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}
