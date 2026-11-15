// The Brew backend: hands out Remote Compose documents to the Android and iOS apps.
//
// Most screens are Kotlin @RemoteComposable functions (shared/) rendered into .rc files ahead of time on a plain JVM
// (go run ./cmd/demo render); this server serves those files and picks which banners to show. The promo card is
// built per request: a JSON template (promo.json) filled from stage.json, then converted to .rc by the rc tool.
//
// Run it from backend/ — or use go run ./cmd/demo serve, which also builds the rc tool and sets up adb:
//
//	go run ./cmd/brew-backend              serve on 127.0.0.1:8080
//	go run ./cmd/brew-backend -lan         also phones on the same Wi-Fi (0.0.0.0)
//	go run ./cmd/brew-backend -dump <dir>  write every section into <dir> and exit
package main

import (
	"context"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"example.com/brew/backend/internal/app"
)

func main() {
	logger := slog.New(slog.NewTextHandler(os.Stdout, nil))
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	context.AfterFunc(ctx, stop) // after the first Ctrl+C, a second one quits at once
	err := app.Run(ctx, os.Args[1:], logger)
	stop()
	if err != nil {
		logger.Error("backend stopped", "err", err)
		os.Exit(1)
	}
}
