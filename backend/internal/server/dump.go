package server

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"

	"example.com/brew/backend/internal/documents"
)

// Dump writes what the server hands out — every rendered section plus the converted promo — into dir, under
// the file names the iOS app looks up in its bundle (hero?i=0 → hero_i-0.rc). Sections no longer rendered are
// removed from dir; the Lab's documents (lab_i-*.rc, written by the Android app) are kept. Everything is read and
// converted before dir is touched, so a failure leaves dir as it was.
func (s *Server) Dump(ctx context.Context, dir string) error {
	if same, err := sameDir(dir, s.cfg.Documents); err != nil || same {
		if err != nil {
			return err
		}
		return fmt.Errorf("dump into %s: that is the documents directory itself", dir)
	}
	m, err := documents.LoadManifest(s.cfg.Documents)
	if err != nil {
		return err
	}
	files := make(map[string][]byte, len(m.Documents)+1)
	for _, file := range m.Documents { // plain file names: LoadManifest checked them
		b, readErr := os.ReadFile(filepath.Join(s.cfg.Documents, file))
		if readErr != nil {
			return readErr
		}
		files[file] = b
	}
	body, err := s.buildPromo()
	if err != nil {
		return err
	}
	if files["promo.rc"], err = s.cfg.Converter.Convert(ctx, body); err != nil {
		return err
	}

	if err := clearSections(dir); err != nil {
		return err
	}
	for name, b := range files {
		if err := os.WriteFile(filepath.Join(dir, name), b, 0o644); err != nil {
			return err
		}
	}
	s.cfg.Log.Info("wrote the sections and promo.rc", "count", len(m.Documents), "dir", dir)
	return nil
}

// sameDir reports whether a and b are the same existing directory (a missing a is not b).
func sameDir(a, b string) (bool, error) {
	ia, err := os.Stat(a)
	if errors.Is(err, fs.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	ib, err := os.Stat(b)
	if err != nil {
		return false, err
	}
	return os.SameFile(ia, ib), nil
}

// clearSections makes sure dir exists and removes the section documents in it, keeping the Lab's (lab_*.rc).
func clearSections(dir string) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	old, err := filepath.Glob(filepath.Join(dir, "*.rc"))
	if err != nil {
		return err
	}
	for _, f := range old {
		if strings.HasPrefix(filepath.Base(f), "lab_") {
			continue
		}
		if err := os.Remove(f); err != nil {
			return err
		}
	}
	return nil
}
