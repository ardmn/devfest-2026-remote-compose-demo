// Package convert turns a Remote Compose document's JSON representation into the binary .rc the players read.
package convert

import (
	"bytes"
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"io/fs"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// ErrToolMissing means the rc tool (tools/, Kotlin on the JVM) has not been built yet.
var ErrToolMissing = errors.New("the rc tool is not built")

const (
	// DefaultTimeout bounds one conversion when New is given none.
	DefaultTimeout     = 30 * time.Second
	maxCachedDocuments = 32
	maxDocumentBytes   = 32 << 20 // the players' own limit for an unpacked document
	maxStderrBytes     = 16 << 10
)

// Converter turns a document's JSON representation into the binary .rc by running `rc json2rc` (tools/). The
// converter is Remote Compose's own Kotlin code, so it runs as a separate program.
//
// A JVM takes about half a second to start, so:
//   - results are cached by the SHA-256 of their JSON (the last maxCachedDocuments of them);
//   - concurrent requests for the same JSON share one run;
//   - a run belongs to the converter, not to a request: a client that disconnects stops waiting, the run goes on
//     for the others (bounded by Timeout).
//
// Safe for concurrent use. The returned bytes are shared: callers must not modify them.
type Converter struct {
	tool    string
	timeout time.Duration
	base    context.Context // cancelled when the server shuts down

	mu       sync.Mutex
	cache    map[[sha256.Size]byte][]byte
	order    [][sha256.Size]byte // cache keys, oldest first
	inflight map[[sha256.Size]byte]*conversion
}

type conversion struct {
	done chan struct{} // closed when rc and err are set
	rc   []byte
	err  error
}

// New returns a Converter running tool. Runs still going when base is cancelled are stopped.
func New(base context.Context, tool string, timeout time.Duration) *Converter {
	if timeout <= 0 {
		timeout = DefaultTimeout
	}
	return &Converter{
		tool:     tool,
		timeout:  timeout,
		base:     base,
		cache:    make(map[[sha256.Size]byte][]byte),
		inflight: make(map[[sha256.Size]byte]*conversion),
	}
}

// Convert returns the .rc for the JSON document doc. ctx only bounds the wait: the conversion itself is shared (see Converter).
func (c *Converter) Convert(ctx context.Context, doc []byte) ([]byte, error) {
	key := sha256.Sum256(doc)
	c.mu.Lock()
	if rc, ok := c.cache[key]; ok {
		c.mu.Unlock()
		return rc, nil
	}
	conv, running := c.inflight[key]
	if !running {
		conv = &conversion{done: make(chan struct{})}
		c.inflight[key] = conv
		// The run keeps the request's values (logging, tracing) but not its cancellation: other requests may be
		// waiting for it. It stops on its own timeout, or when the converter's base context ends (shutdown).
		runCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), c.timeout)
		stop := context.AfterFunc(c.base, cancel) //nolint:contextcheck // on purpose: base is the server's lifetime, not the request's
		go func() {
			defer cancel()
			defer stop()
			c.run(runCtx, key, bytes.Clone(doc), conv) // a copy: the caller may reuse its slice
		}()
	}
	c.mu.Unlock()

	select {
	case <-conv.done:
		return conv.rc, conv.err
	case <-ctx.Done():
		return nil, context.Cause(ctx)
	}
}

func (c *Converter) run(ctx context.Context, key [sha256.Size]byte, doc []byte, conv *conversion) {
	rc, err := c.exec(ctx, doc)

	c.mu.Lock()
	delete(c.inflight, key)
	if err == nil {
		c.store(key, rc)
	}
	c.mu.Unlock()

	conv.rc, conv.err = rc, err
	close(conv.done) // publishes rc and err to every waiter
}

// store caches rc, dropping the oldest entry when full. c.mu must be held.
func (c *Converter) store(key [sha256.Size]byte, rc []byte) {
	if len(c.order) == maxCachedDocuments {
		delete(c.cache, c.order[0])
		c.order = c.order[1:]
	}
	c.cache[key] = rc
	c.order = append(c.order, key)
}

func (c *Converter) exec(ctx context.Context, doc []byte) ([]byte, error) {
	cmd := exec.CommandContext(ctx, c.tool, "json2rc")
	cmd.Stdin = bytes.NewReader(doc)
	out := &cappedBuffer{limit: maxDocumentBytes}
	stderr := &cappedBuffer{limit: maxStderrBytes, truncate: true}
	cmd.Stdout, cmd.Stderr = out, stderr
	// If the tool is killed (timeout, shutdown) but a child keeps its pipes open, stop waiting for them.
	cmd.WaitDelay = time.Second

	if err := cmd.Run(); err != nil {
		switch {
		case errors.Is(err, exec.ErrNotFound), errors.Is(err, fs.ErrNotExist):
			return nil, fmt.Errorf("%w (%s): build it with  cd tools && ./gradlew installDist  (go run ./cmd/demo serve does it)", ErrToolMissing, c.tool)
		case ctx.Err() != nil:
			return nil, fmt.Errorf("rc json2rc: stopped: %w", context.Cause(ctx))
		case out.exceeded:
			// The tool may die of the closed pipe before Wait sees errTooLarge: check the buffer, not the error.
			return nil, fmt.Errorf("rc json2rc: document over %d bytes", maxDocumentBytes)
		default:
			return nil, fmt.Errorf("rc json2rc: %w: %s", err, strings.TrimSpace(stderr.String()))
		}
	}
	rc := out.Bytes()
	// A Remote Compose document starts with its HEADER operation (opcode 0).
	if len(rc) < 2 || rc[0] != 0 {
		return nil, fmt.Errorf("rc json2rc: output is not a Remote Compose document (%d bytes)", len(rc))
	}
	return rc, nil
}

var errTooLarge = errors.New("output too large")

// cappedBuffer holds at most limit bytes of what is written to it: past that, Write fails with errTooLarge — or,
// with truncate, silently drops the rest. The buffer is a field, not embedded: an embedded bytes.Buffer would bring
// its ReadFrom, which io.Copy (and so os/exec) calls instead of Write, bypassing the limit.
type cappedBuffer struct {
	buf      bytes.Buffer
	limit    int
	truncate bool
	exceeded bool
}

func (b *cappedBuffer) Write(p []byte) (int, error) {
	room := b.limit - b.buf.Len()
	if len(p) <= room {
		return b.buf.Write(p)
	}
	b.exceeded = true
	if !b.truncate {
		return 0, errTooLarge
	}
	if room > 0 {
		b.buf.Write(p[:room])
	}
	return len(p), nil
}

func (b *cappedBuffer) Bytes() []byte  { return b.buf.Bytes() }
func (b *cappedBuffer) String() string { return b.buf.String() }
