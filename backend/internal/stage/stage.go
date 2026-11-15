// Package stage reads stage.json, the file you edit on stage. go run ./cmd/demo render bakes most of it into the documents
// (banner texts, favorites, for-you); the server reads the rest at request time: how many banners to show and the
// JSON promo.
package stage

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
)

// Stage is the part of stage.json the server reads at request time. Nil fields were not set.
type Stage struct {
	// BannersShown is how many banners of the pool the carousel shows per visit.
	BannersShown *int  `json:"bannersShown"`
	Promo        Promo `json:"promo"`
}

// Promo is stage.json's "promo": the texts and drinks of the JSON-built promo card.
type Promo struct {
	Title    *string  `json:"title"`
	Badge    *string  `json:"badge"`
	Color    *string  `json:"color"`
	Discount *int     `json:"discount"`
	Drinks   []string `json:"drinks"`
}

// Read reads stage.json and returns it parsed and as bytes; a missing file means all defaults (and nil bytes).
func Read(path string) (Stage, []byte, error) {
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return Stage{}, nil, nil
	}
	if err != nil {
		return Stage{}, nil, err
	}
	var s Stage
	if err := json.Unmarshal(b, &s); err != nil {
		return Stage{}, nil, fmt.Errorf("%s: %w", path, err)
	}
	return s, b, nil
}
