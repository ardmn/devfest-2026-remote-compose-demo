// demo runs the Brew demo's everyday tasks the same way on macOS, Windows and Linux — no bash, no make. Run it from
// backend/:
//
//	go run ./cmd/demo serve [-lan] [-port N]  build the rc tool if needed, adb reverse, start the server
//	go run ./cmd/demo render                  turn the Kotlin sections into documents + the iOS app's bundled copies
//	go run ./cmd/demo lint [-check]           format (-check: only report a diff and fail), lint, vet and test the
//	                                          backend, with golangci-lint v2 from the PATH; every step runs
//
// It needs Go and, for anything Kotlin (the rc tool), a JDK 17+: JAVA_HOME, java on the PATH, or the JDK bundled with
// Android Studio — found by itself.
package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
)

const usage = `go run ./cmd/demo <command>

  serve [-lan] [-port N]  build the rc tool if needed, adb reverse, start the server (Ctrl+C stops it)
  render                  turn the Kotlin sections into documents for stage.json, plus the iOS catalog and copies
  lint [-check]           golangci-lint fmt + run, go vet, go test -race (golangci-lint v2 from the PATH; every step
                          runs); -check: fmt --diff, report unformatted files and fail instead of formatting them`

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	context.AfterFunc(ctx, stop) // after the first Ctrl+C, a second one quits at once
	err := run(ctx, os.Args[1:], os.Stdout)
	stop()
	if errors.Is(err, errUsage) {
		fmt.Fprintln(os.Stderr, usage)
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "demo:", err)
		os.Exit(1)
	}
}

var errUsage = errors.New("usage")

func run(ctx context.Context, args []string, out io.Writer) error {
	if len(args) == 0 {
		return errUsage
	}
	e := realEnv()
	switch args[0] {
	case "serve":
		return serve(ctx, e, args[1:], out)
	case "render":
		return render(ctx, e, out)
	case "lint":
		return lint(ctx, e, runTool, args[1:], out)
	default:
		return errUsage
	}
}

func newLogger(out io.Writer) *slog.Logger { return slog.New(slog.NewTextHandler(out, nil)) }
