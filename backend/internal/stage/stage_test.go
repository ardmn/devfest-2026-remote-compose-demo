package stage

import (
	"os"
	"path/filepath"
	"testing"
)

func TestRead(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()

	s, raw, err := Read(filepath.Join(dir, "missing.json"))
	if err != nil || raw != nil || s.BannersShown != nil || s.Promo.Title != nil {
		t.Errorf("a missing stage.json: %+v, %q, %v — want defaults", s, raw, err)
	}

	path := filepath.Join(dir, "stage.json")
	content := `{"bannersShown": 2, "banners": [{"headline": "ignored here"}],
	             "promo": {"title": "T", "discount": 15, "drinks": ["mocha"]}}`
	if werr := os.WriteFile(path, []byte(content), 0o600); werr != nil {
		t.Fatal(werr)
	}
	s, raw, err = Read(path)
	if err != nil || string(raw) != content {
		t.Fatalf("%v, %q", err, raw)
	}
	if *s.BannersShown != 2 || *s.Promo.Title != "T" || *s.Promo.Discount != 15 || len(s.Promo.Drinks) != 1 || s.Promo.Badge != nil {
		t.Errorf("parsed %+v", s)
	}

	if err := os.WriteFile(path, []byte(`{"promo": [`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, _, err := Read(path); err == nil {
		t.Error("a broken stage.json was accepted")
	}
	if _, _, err := Read(dir); err == nil {
		t.Error("a directory was read as stage.json")
	}
}
