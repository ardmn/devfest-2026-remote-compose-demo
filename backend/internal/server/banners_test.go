package server

import (
	"fmt"
	"math/rand/v2"
	"slices"
	"testing"
)

func identity(n int) []int {
	p := make([]int, n)
	for i := range p {
		p[i] = i
	}
	return p
}

func TestPickBanners(t *testing.T) {
	t.Parallel()
	tests := []struct {
		name        string
		pool, shown int
		want        []int
	}{
		{"shows the asked number", 5, 3, []int{0, 1, 2}},
		{"never more than the pool", 2, 3, []int{0, 1}},
		{"at least one", 5, 0, []int{0}},
		{"a negative count is one", 5, -4, []int{0}},
		{"a pool of one", 1, 3, []int{0}},
		{"no pool, no banners", 0, 3, nil},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Parallel()
			if got := pickBanners(tt.pool, tt.shown, identity); !slices.Equal(got, tt.want) {
				t.Errorf("pickBanners(%d, %d) = %v, want %v", tt.pool, tt.shown, got, tt.want)
			}
		})
	}
}

// With the real shuffle: always distinct indices inside the pool, and every banner shows up sooner or later.
func TestPickBannersIsARandomChoice(t *testing.T) {
	t.Parallel()
	seen := map[int]bool{}
	orders := map[string]bool{}
	for range 500 {
		got := pickBanners(5, 3, rand.Perm)
		if len(got) != 3 {
			t.Fatalf("%v: want 3 banners", got)
		}
		for i, id := range got {
			if id < 0 || id >= 5 || slices.Contains(got[:i], id) {
				t.Fatalf("%v: ids must be distinct and in 0…4", got)
			}
			seen[id] = true
		}
		orders[fmt.Sprint(got)] = true
	}
	if len(seen) != 5 {
		t.Errorf("only banners %v ever shown", seen)
	}
	if len(orders) < 30 { // 60 possible orders; 500 draws see far more than 30 of them
		t.Errorf("only %d different orders in 500 draws", len(orders))
	}
}
