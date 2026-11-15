package server

// DefaultBannersShown is how many banners the carousel shows when stage.json does not say.
const DefaultBannersShown = 3

// pickBanners chooses which banners of the pool the carousel shows, and in which order: shown of them (at least
// one, at most the whole pool), distinct, in the order of perm. perm(n) must return a permutation of 0…n-1 —
// rand.Perm in the server, a fixed one in tests.
func pickBanners(pool, shown int, perm func(n int) []int) []int {
	if pool < 1 {
		return nil
	}
	shown = min(max(shown, 1), pool)
	return perm(pool)[:shown]
}
