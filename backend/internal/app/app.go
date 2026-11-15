// Package app is the Brew backend's program: flags, wiring, serving until Ctrl+C, graceful shutdown. Both commands
// use it: cmd/brew-backend (the server alone) and cmd/demo serve (after preparing the tool and adb).
package app

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"syscall"
	"time"

	"example.com/brew/backend/internal/convert"
	"example.com/brew/backend/internal/documents"
	"example.com/brew/backend/internal/server"
)

// Run serves until ctx ends (Ctrl+C), then shuts down gracefully; with -dump it writes the sections and returns.
// Paths are relative to backend/.
func Run(ctx context.Context, args []string, logger *slog.Logger) error {
	flags := flag.NewFlagSet("brew-backend", flag.ContinueOnError)
	port := flags.Int("port", 8080, "port to listen on (0: any free port)")
	lan := flags.Bool("lan", false, "listen on all interfaces (phones on the same Wi-Fi), not only 127.0.0.1")
	dump := flags.String("dump", "", "write every section into this directory and exit")
	docs := flags.String("documents", "documents", "directory of rendered documents (go run ./cmd/demo render)")
	stageFile := flags.String("stage", "stage.json", "the stage file")
	promoFile := flags.String("promo", "promo.json", "the JSON template of the promo")
	tool := flags.String("rc", DefaultTool(), "the rc tool, for JSON → .rc (env RC_TOOL)")
	if err := flags.Parse(args); err != nil {
		return err
	}

	if _, err := documents.LoadManifest(*docs); err != nil {
		return fmt.Errorf("no rendered documents in %s/ (%w) — render them first: go run ./cmd/demo render", *docs, err)
	}
	// The converter outlives ctx: conversions in flight at Ctrl+C finish (bounded by the shutdown timeout).
	convCtx, stopConversions := context.WithCancel(context.WithoutCancel(ctx))
	defer stopConversions()
	s := server.New(server.Config{
		Documents: *docs,
		Stage:     *stageFile,
		Promo:     *promoFile,
		Converter: convert.New(convCtx, *tool, 0),
		Log:       logger,
	})

	if *dump != "" {
		return s.Dump(ctx, *dump)
	}

	go func() {
		if err := s.WarmUp(ctx); err != nil && ctx.Err() == nil {
			logger.Warn("the JSON promo is unavailable", "err", err)
		}
	}()

	host := "127.0.0.1"
	if *lan {
		host = "0.0.0.0"
	}
	srv := &http.Server{
		Addr:              net.JoinHostPort(host, strconv.Itoa(*port)),
		Handler:           s.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      convert.DefaultTimeout + 10*time.Second, // a promo may wait for a conversion
		IdleTimeout:       2 * time.Minute,
	}
	var lc net.ListenConfig
	listener, err := lc.Listen(ctx, "tcp", srv.Addr)
	if errors.Is(err, syscall.EADDRINUSE) {
		return fmt.Errorf("%w — is another backend already running? Stop it, or start this one with -port <n>", err)
	}
	if err != nil {
		return err
	}
	addr := listener.Addr().String()
	logger.Info("Brew backend listening — Ctrl+C to stop", "url", "http://"+addr+"/sections/",
		"try", "curl http://"+addr+"/sections/promo.json")

	serveErr := make(chan error, 1)
	go func() { serveErr <- srv.Serve(listener) }()
	select {
	case err := <-serveErr:
		return err
	case <-ctx.Done():
	}
	logger.Info("shutting down: finishing the requests in flight")
	shutdownCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), shutdownTimeout)
	defer cancel()
	shutdownErr := srv.Shutdown(shutdownCtx) // waits for handlers, conversions included
	stopConversions()
	if err := <-serveErr; !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return shutdownErr
}

// shutdownTimeout bounds how long Ctrl+C waits for requests in flight.
const shutdownTimeout = 10 * time.Second

// DefaultTool is $RC_TOOL, else the rc tool tools/ builds (cd tools && ./gradlew installDist).
func DefaultTool() string {
	if t := os.Getenv("RC_TOOL"); t != "" {
		return t
	}
	name := "rc"
	if runtime.GOOS == "windows" {
		name = "rc.bat"
	}
	p, err := filepath.Abs(filepath.Join("..", "tools", "build", "install", "rc", "bin", name))
	if err != nil {
		return name
	}
	return p
}
