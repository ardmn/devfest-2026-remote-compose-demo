package documents

import (
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestLoadManifestRefusesUnsafeFileNames(t *testing.T) {
	t.Parallel()
	tests := map[string]string{
		"ok":               `{"banners": 1, "documents": {"hero?i=0": "hero_i-0.rc"}}`,
		"parent directory": `{"banners": 1, "documents": {"hero?i=0": "../stage.json"}}`,
		"absolute path":    `{"banners": 1, "documents": {"hero?i=0": "/etc/passwd"}}`,
		"subdirectory":     `{"banners": 1, "documents": {"hero?i=0": "a/hero.rc"}}`,
		"not a document":   `{"banners": 1, "documents": {"hero?i=0": "manifest.json"}}`,
		"hidden file":      `{"banners": 1, "documents": {"hero?i=0": ".rc"}}`,
		"no documents":     `{"banners": 1, "documents": {}}`,
		"no banners":       `{"banners": 0, "documents": {"hero?i=0": "hero_i-0.rc"}}`,
		"a banner missing": `{"banners": 2, "documents": {"hero?i=0": "hero_i-0.rc"}}`,
		"not JSON":         `{`,
	}
	for name, content := range tests {
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			dir := t.TempDir()
			writeFile(t, filepath.Join(dir, "manifest.json"), content)
			_, err := LoadManifest(dir)
			if (err == nil) != (name == "ok") {
				t.Errorf("err = %v", err)
			}
		})
	}
}

// renderedDir is backend/documents, what go run ./cmd/demo render wrote.
const renderedDir = "../../documents"

func TestTheRenderedDocumentsAreConsistent(t *testing.T) {
	t.Parallel()
	m, err := LoadManifest(renderedDir)
	if err != nil {
		t.Fatal(err)
	}
	cat, err := LoadCatalog(renderedDir)
	if err != nil {
		t.Fatal(err)
	}
	for i := range m.Banners {
		if _, ok := m.Documents["hero?i="+strconv.Itoa(i)]; !ok {
			t.Errorf("banner %d of %d is not rendered", i, m.Banners)
		}
	}
	for _, d := range cat.Drinks {
		for _, key := range []string{"details?id=" + d.ID, "row?id=" + d.ID} {
			if _, ok := m.Documents[key]; !ok {
				t.Errorf("%s is not rendered", key)
			}
		}
	}
	for key, file := range m.Documents {
		b, err := os.ReadFile(filepath.Join(renderedDir, file))
		if err != nil || len(b) < 16 || b[0] != 0 {
			t.Errorf("%s → %s is not a document (%d bytes, %v)", key, file, len(b), err)
		}
	}
}
