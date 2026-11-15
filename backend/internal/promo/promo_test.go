package promo

import (
	"bytes"
	"encoding/json"
	"os"
	"regexp"
	"strings"
	"testing"

	"example.com/brew/backend/internal/documents"
	"example.com/brew/backend/internal/stage"
)

var testCatalog = documents.Catalog{Drinks: []documents.Drink{
	{ID: "americano", Name: "Americano", Emoji: "☕", PriceCents: 350},
	{ID: "mocha", Name: "Iced Mocha", Emoji: "🍫", PriceCents: 490},
	{ID: "beans", Name: "House Blend Beans", Emoji: "🫘", PriceCents: 1200},
	{ID: "chai", Name: "Chai Latte", Emoji: "🫖", PriceCents: 420},
}}

const testTemplate = `{
  "header": { "apiLevel": 8, "width": 400, "height": 330, "densityBehavior": 2 },
  "root": { "column": { "modifiers": [{ "background": "{{color}}" }], "children": [
    { "text": { "value": "{{badge}}", "fontSize": 13 } },
    { "text": { "value": "{{title}}", "fontSize": 30.5 } },
    { "$rows": "generated" }
  ] } }
}`

func ptr[T any](v T) *T { return &v }

func promoStage(title, badge, color *string, discount *int, drinks []string) stage.Promo {
	return stage.Promo{Title: title, Badge: badge, Color: color, Discount: discount, Drinks: drinks}
}

var rowIDs = regexp.MustCompile(`open_details:(\w+)`)

func rows(t *testing.T, doc []byte) []string {
	t.Helper()
	matches := rowIDs.FindAllSubmatch(doc, -1)
	ids := make([]string, 0, len(matches))
	for _, m := range matches {
		ids = append(ids, string(m[1]))
	}
	return ids
}

func TestBuildPromo(t *testing.T) {
	t.Parallel()
	tests := []struct {
		name      string
		stage     stage.Promo
		wantRows  []string
		wantTexts []string // must appear in the JSON
	}{
		{
			name:      "defaults",
			stage:     stage.Promo{},
			wantRows:  []string{"mocha"}, // the default drinks the catalog knows (coldbrew and cookie are not in it)
			wantTexts: []string{`"Happy hour"`, `"−20%"`, `"#FF2E5E4E"`, `$4.90 → $3.92`},
		},
		{
			name:      "stage values",
			stage:     promoStage(ptr("Live"), ptr("BADGE"), ptr("#FF000000"), ptr(50), []string{"americano", "beans"}),
			wantRows:  []string{"americano", "beans"},
			wantTexts: []string{`"Live"`, `"BADGE"`, `"#FF000000"`, `$3.50 → $1.75`, `$12.00 → $6.00`},
		},
		{
			name:      "unknown drinks are dropped, at most three rows",
			stage:     promoStage(nil, nil, nil, nil, []string{"nope", "chai", "../x", "mocha", "beans", "americano"}),
			wantRows:  []string{"chai", "mocha", "beans"},
			wantTexts: []string{},
		},
		{
			name:      "discount above 90 is capped",
			stage:     promoStage(nil, nil, nil, ptr(500), []string{"mocha"}),
			wantRows:  []string{"mocha"},
			wantTexts: []string{`"−90%"`, `$4.90 → $0.49`},
		},
		{
			name:      "negative discount is 0",
			stage:     promoStage(nil, nil, nil, ptr(-30), []string{"mocha"}),
			wantRows:  []string{"mocha"},
			wantTexts: []string{`"−0%"`, `$4.90 → $4.90`},
		},
		{
			name:      "an empty list means no rows",
			stage:     promoStage(nil, nil, nil, nil, []string{}),
			wantRows:  nil,
			wantTexts: []string{},
		},
		{
			name:      "a value containing a placeholder is not expanded again",
			stage:     promoStage(ptr("{{badge}} {{color}}"), ptr("B"), ptr("C"), nil, []string{}),
			wantRows:  nil,
			wantTexts: []string{`"{{badge}} {{color}}"`},
		},
		{
			name:      "JSON-special characters stay valid JSON",
			stage:     promoStage(ptr(`"quoted" \ <b>&`), nil, nil, nil, []string{}),
			wantRows:  nil,
			wantTexts: []string{`"\"quoted\" \\ <b>&"`},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Parallel()
			got, err := Build([]byte(testTemplate), tt.stage, testCatalog)
			if err != nil {
				t.Fatal(err)
			}
			if !json.Valid(got) {
				t.Fatalf("not valid JSON:\n%s", got)
			}
			if r := rows(t, got); strings.Join(r, ",") != strings.Join(tt.wantRows, ",") {
				t.Errorf("rows = %v, want %v", r, tt.wantRows)
			}
			for _, want := range tt.wantTexts {
				if !strings.Contains(string(got), want) {
					t.Errorf("missing %s in\n%s", want, got)
				}
			}
			if strings.Contains(string(got), rowsMarker) {
				t.Error("the $rows marker is left in the JSON")
			}
		})
	}
}

func TestBuildPromoKeepsNumbersAndIsDeterministic(t *testing.T) {
	t.Parallel()
	s := promoStage(ptr("T"), nil, nil, nil, []string{"mocha", "chai"})
	first, err := Build([]byte(testTemplate), s, testCatalog)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(first), `"fontSize": 30.5`) || !strings.Contains(string(first), `"apiLevel": 8`) {
		t.Errorf("template numbers changed:\n%s", first)
	}
	// The converter caches by these bytes: map order must not leak into the output.
	for range 50 {
		again, err := Build([]byte(testTemplate), s, testCatalog)
		if err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(again, first) {
			t.Fatal("two builds of the same promo differ")
		}
	}
}

func TestBuildPromoRejectsBrokenTemplates(t *testing.T) {
	t.Parallel()
	for _, template := range []string{``, `{`, `[1, 2]`, `"text"`, `{"a": 1} trailing`} {
		if _, err := Build([]byte(template), stage.Promo{}, testCatalog); err == nil {
			t.Errorf("template %q accepted", template)
		}
	}
}

func TestTheRealTemplate(t *testing.T) {
	t.Parallel()
	template, err := os.ReadFile("../../promo.json")
	if err != nil {
		t.Fatal(err)
	}
	got, err := Build(template, promoStage(nil, nil, nil, nil, []string{"mocha", "chai"}), testCatalog)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(got), "{{") || len(rows(t, got)) != 2 {
		t.Errorf("promo.json not filled:\n%s", got)
	}
}

func TestDiscounted(t *testing.T) {
	t.Parallel()
	tests := []struct{ cents, discount, want int }{
		{490, 20, 392},
		{490, 0, 490},
		{490, 90, 49},
		{350, 50, 175},
		{999, 33, 669}, // 669.33
		{5, 50, 3},     // 2.5 rounds half up
		{1, 50, 1},     // 0.5 rounds half up
		{0, 20, 0},
	}
	for _, tt := range tests {
		if got := discounted(tt.cents, tt.discount); got != tt.want {
			t.Errorf("discounted(%d, %d) = %d, want %d", tt.cents, tt.discount, got, tt.want)
		}
	}
}

func TestFormatPrice(t *testing.T) {
	t.Parallel()
	for cents, want := range map[int]string{0: "$0.00", 5: "$0.05", 99: "$0.99", 100: "$1.00", 350: "$3.50", 1200: "$12.00", 123456: "$1234.56"} {
		if got := formatPrice(cents); got != want {
			t.Errorf("formatPrice(%d) = %q, want %q", cents, got, want)
		}
	}
}
