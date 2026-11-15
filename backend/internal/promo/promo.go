// Package promo builds the promo card the JSON way. Remote Compose has a JSON representation of a document: the
// card starts as a JSON template (promo.json), the server edits it as plain data — fills in the texts and colour
// from stage.json and adds one row per drink — and only then the JSON becomes the binary document the apps play
// (package convert).
package promo

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"

	"example.com/brew/backend/internal/documents"
	"example.com/brew/backend/internal/stage"
)

const (
	defaultDiscount = 20
	maxDiscount     = 90
	maxPromoRows    = 3
	rowsMarker      = "$rows"
)

var defaultPromoDrinks = []string{"mocha", "coldbrew", "cookie"}

// Build fills the template with stage.json's "promo": {{title}}, {{badge}} and {{color}} in any string, and
// the {"$rows": …} child becomes one row per drink (known drinks only, at most three). The discount is kept within
// 0…90 %. The output is deterministic: the same inputs give the same bytes (the converter caches by them).
func Build(template []byte, p stage.Promo, cat documents.Catalog) ([]byte, error) {
	discount := defaultDiscount
	if p.Discount != nil {
		discount = min(max(*p.Discount, 0), maxDiscount)
	}
	// One pass, so a value that itself contains "{{…}}" is never expanded again.
	placeholders := strings.NewReplacer(
		"{{title}}", valueOr(p.Title, "Happy hour"),
		"{{badge}}", valueOr(p.Badge, fmt.Sprintf("−%d%%", discount)),
		"{{color}}", valueOr(p.Color, "#FF2E5E4E"),
	)
	ids := p.Drinks
	if ids == nil {
		ids = defaultPromoDrinks
	}
	rows := make([]any, 0, maxPromoRows)
	for _, id := range ids {
		if d, ok := cat.Find(id); ok && len(rows) < maxPromoRows {
			rows = append(rows, promoRow(d, discount))
		}
	}

	dec := json.NewDecoder(bytes.NewReader(template))
	dec.UseNumber() // keep the template's numbers exactly as written
	var tree any
	if err := dec.Decode(&tree); err != nil {
		return nil, fmt.Errorf("promo template: %w", err)
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		return nil, errors.New("promo template: more than one JSON value")
	}
	if _, ok := tree.(map[string]any); !ok {
		return nil, errors.New("promo template: not a JSON object")
	}
	return marshalJSON(fill(tree, placeholders, rows))
}

// promoRow is one drink: emoji, name, the old price struck by the new one; a tap opens the product page.
func promoRow(d documents.Drink, discount int) map[string]any {
	return node("row", map[string]any{
		"verticalAlignment": "center",
		"modifiers": []any{
			"fillMaxWidth",
			map[string]any{"padding": map[string]any{"top": 10}},
			map[string]any{"height": 60},
			map[string]any{"clip": map[string]any{"roundedrect": 18}},
			map[string]any{"background": "#26FFFFFF"},
			map[string]any{"padding": []any{12, 0}},
			map[string]any{"onclick": map[string]any{"type": "hostAction", "name": "open_details:" + d.ID}},
		},
		"children": []any{
			text(d.Emoji, 28, "#FFFFFFFF", 400),
			node("column", map[string]any{
				"modifiers": []any{map[string]any{"padding": map[string]any{"start": 12}}},
				"children": []any{
					text(d.Name, 17, "#FFFFFFFF", 600),
					text(formatPrice(d.PriceCents)+" → "+formatPrice(discounted(d.PriceCents, discount)), 14, "#FFFFE7A3", 400),
				},
			}),
		},
	})
}

// discounted is cents less discount percent, rounded half up — in integers, so no float rounding surprises.
func discounted(cents, discount int) int {
	return (cents*(100-discount) + 50) / 100
}

// formatPrice is "$3.50" — the same format as BrewCatalog.priceCents in the Kotlin sections.
func formatPrice(cents int) string {
	return fmt.Sprintf("$%d.%02d", cents/100, cents%100)
}

func text(value string, size int, color string, weight int) map[string]any {
	return node("text", map[string]any{"value": value, "fontSize": size, "fontWeight": weight, "color": color})
}

func node(kind string, body map[string]any) map[string]any { return map[string]any{kind: body} }

// fill walks the JSON tree: placeholders in strings are replaced; a {"$rows": …} child becomes the rows.
func fill(v any, placeholders *strings.Replacer, rows []any) any {
	switch v := v.(type) {
	case map[string]any:
		out := make(map[string]any, len(v))
		for k, child := range v {
			out[k] = fill(child, placeholders, rows)
		}
		return out
	case []any:
		out := make([]any, 0, len(v))
		for _, child := range v {
			if m, ok := child.(map[string]any); ok {
				if _, isRows := m[rowsMarker]; isRows {
					out = append(out, rows...)
					continue
				}
			}
			out = append(out, fill(child, placeholders, rows))
		}
		return out
	case string:
		return placeholders.Replace(v)
	default:
		return v
	}
}

// marshalJSON is json.Marshal, indented and without escaping "<", ">" and "&" (it is shown to people).
func marshalJSON(v any) ([]byte, error) {
	var b bytes.Buffer
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "  ")
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return b.Bytes(), nil
}

func valueOr(s *string, fallback string) string {
	if s == nil {
		return fallback
	}
	return *s
}
