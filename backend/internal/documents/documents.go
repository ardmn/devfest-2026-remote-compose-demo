// Package documents reads what go run ./cmd/demo render writes next to the rendered documents: the manifest (which request
// path is which file) and the catalog (the drinks the server knows).
package documents

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Manifest is documents/manifest.json, written by go run ./cmd/demo render next to the rendered documents.
type Manifest struct {
	// Banners is the size of the banner pool: hero?i=0 … hero?i=Banners-1 are rendered.
	Banners int `json:"banners"`
	// StageSHA256 is the hash of the stage.json the documents were rendered from.
	StageSHA256 string `json:"stageSha256"`
	// Documents maps a request path ("hero?i=0", "details?id=caramel") to its file name.
	Documents map[string]string `json:"documents"`
}

// Drink is one entry of documents/catalog.json (generated from shared/…/BrewCatalog.kt).
type Drink struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	Emoji      string `json:"emoji"`
	PriceCents int    `json:"priceCents"`
}

// Catalog is documents/catalog.json: the drinks the server knows, in menu order.
type Catalog struct {
	Drinks []Drink `json:"drinks"`
}

// Find returns the drink with that id. Ids come from clients and stage.json: unknown ones are refused.
func (c Catalog) Find(id string) (Drink, bool) {
	for _, d := range c.Drinks {
		if d.ID == id {
			return d, true
		}
	}
	return Drink{}, false
}

func readJSON(path string, v any) error {
	b, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	if err := json.Unmarshal(b, v); err != nil {
		return fmt.Errorf("%s: %w", path, err)
	}
	return nil
}

// LoadManifest reads dir/manifest.json and checks it: every document must be a plain *.rc file name in dir.
func LoadManifest(dir string) (Manifest, error) {
	var m Manifest
	if err := readJSON(filepath.Join(dir, "manifest.json"), &m); err != nil {
		return Manifest{}, err
	}
	path := filepath.Join(dir, "manifest.json")
	if m.Banners < 1 || len(m.Documents) == 0 {
		return Manifest{}, fmt.Errorf("%s: empty manifest", path)
	}
	for i := range m.Banners {
		if _, ok := m.Documents["hero?i="+strconv.Itoa(i)]; !ok {
			return Manifest{}, fmt.Errorf("%s: %d banners, but hero?i=%d is missing", path, m.Banners, i)
		}
	}
	// Only plain *.rc file names inside dir: the manifest decides which files the server reads and copies.
	for key, file := range m.Documents {
		if file != filepath.Base(file) || filepath.Ext(file) != ".rc" || strings.HasPrefix(file, ".") {
			return Manifest{}, fmt.Errorf("%s: %q maps to %q, not a document file name", path, key, file)
		}
	}
	return m, nil
}

// LoadCatalog reads dir/catalog.json.
func LoadCatalog(dir string) (Catalog, error) {
	var c Catalog
	err := readJSON(filepath.Join(dir, "catalog.json"), &c)
	return c, err
}
