// Package discovery turns a provider's listing into this template's own proposals
// (docs/wiki/prediction-template.md).
//
// The Prediction template does not wait to be told what to publish. It walks the provider's
// listing, applies the filters its operator configured, and publishes one proposal per market that
// matches — then keeps each of those in step with the source until it closes. Nothing about a
// subscriber reaches this package either: it reads public markets and writes this publisher's own
// documents, and the choice of a side and a stake happens on each owner's phone (docs/security.md).
//
// # What matching means, exactly
//
// Two of the filters are the provider's own, sent as parameters, because they decide which events
// the listing returns at all: the venue (`provider`) and the bucket (`category`), plus its named
// `filter` (new, live, trending, upcoming). Everything else is applied here, to the records that
// came back, and only to fields those records actually carry:
//
//   - **tags** — the event's own tags, compared case-insensitively and whole. A tag is a token the
//     provider assigns ("nfl", "fed-rates"), so half of one is not a match.
//   - **keywords** — a case-insensitive substring of the event's title, category, subcategory and
//     tags together with the market's title. A substring, deliberately: "eth" finds "Ethereum",
//     which is what an operator writing a keyword list expects, and the cost of it is that "eth"
//     also finds "Bethesda". Any one keyword matching is enough.
//   - **time to close** — the market's own close time has to be at least [Filters.LeastCloseIn] and
//     at most [Filters.MostCloseIn] away. A market with no close time at all cannot be judged
//     against a window, so it is skipped whenever one is configured.
//   - **state** — a market has to be one the provider would take an order for, unless
//     [Filters.Closed] is set, which only a sandbox deployment may do (internal/config).
//
// Everything a filter is written against is in the answer the listing gave. There is deliberately
// no filter over anything the provider does not send with a market — no implied volume threshold,
// no computed probability, no model of any kind — because a filter that needed data the listing
// does not carry would be a filter that quietly matched nothing, or matched on a guess.
//
// # A filter is discovery, not withdrawal
//
// A market that no longer matches keeps its proposal until the source itself ends it. Withdrawing a
// signal because an operator's filter moved would take back a statement for a reason no subscriber
// can see, and a `trending` market that drifts in and out of the trending set would publish and
// withdraw itself for ever. What ends a proposal is the provider: closed, cancelled, settled, or
// gone (see [Reconciler.Pass]).
package discovery

import (
	"sort"
	"strings"
	"time"
	"unicode"

	"github.com/BrRenat/SeekerAgentWallet/demo-prediction/internal/jupiter"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// Filters are what an operator asked for. Every field comes from the environment and is bounded
// there (internal/config); this package applies them and never widens one.
type Filters struct {
	// The provider's venue, sent as its `provider` parameter.
	Source string
	// The provider's buckets, sent as its `category` parameter — one listing walk each, because
	// the parameter takes one. Empty means one walk with no bucket at all.
	Categories []string
	// The provider's own named filter: new, live, trending or upcoming. Empty means none.
	Filter string
	// Applied here, to what came back.
	Tags     []string
	Keywords []string
	// Whether a market the provider would not take an order for may still be published. Sandbox
	// only, and it exists so that the closure path can be exercised deliberately.
	Closed bool
	// The window a market's close time has to fall in.
	LeastCloseIn time.Duration
	MostCloseIn  time.Duration
	// How long a signal lasts when the provider gives the market no close time at all. Counted from
	// when this template first saw the market, never from now, so the expiry does not move every
	// cycle (see [Filters.Expiry]).
	Lifetime time.Duration
	// How much of the listing one cycle reads: events per call, and calls per bucket.
	PageSize  int
	MostPages int
	// How many proposals this template will keep open at once, and how many markets it will ask
	// about directly in one cycle. Both are this template's own restraint rather than a limit of
	// the protocol: a phone walks a feed in pages of fifty and a person reads a list.
	MostOpen   int
	MostChecks int
	// How often a cycle runs.
	Every time.Duration
}

// Describe is the filters as an operator reads them back: the startup log line, the status answer
// and `publishctl discovery` all show this, because "what is this publisher actually looking for"
// should not require reading a compose file.
func (f Filters) Describe() map[string]any {
	described := map[string]any{
		"source":         f.Source,
		"categories":     f.Categories,
		"tags":           f.Tags,
		"keywords":       f.Keywords,
		"closed_markets": f.Closed,
		"closes_between": f.LeastCloseIn.String() + " and " + f.MostCloseIn.String(),
		"page_size":      f.PageSize,
		"most_pages":     f.MostPages,
		"most_open":      f.MostOpen,
		"most_checks":    f.MostChecks,
		"every":          f.Every.String(),
		"lifetime":       f.Lifetime.String(),
	}
	if f.Filter != "" {
		described["filter"] = f.Filter
	}
	if f.Categories == nil {
		described["categories"] = []string{}
	}
	if f.Tags == nil {
		described["tags"] = []string{}
	}
	if f.Keywords == nil {
		described["keywords"] = []string{}
	}
	return described
}

// The reasons a market is not a candidate. They are stable words rather than sentences because they
// are counted, logged and read back through the API: an operator whose filters match nothing wants
// to know which rule did it.
const (
	Unreadable    = "unreadable_id"
	EventInactive = "event_inactive"
	NotTradeable  = "not_tradeable"
	OtherCategory = "other_category"
	NoTag         = "no_tag"
	NoKeyword     = "no_keyword"
	NoCloseTime   = "no_close_time"
	TooSoon       = "closes_too_soon"
	TooLate       = "closes_too_late"
)

// Match is why a market is not a candidate, or "" when it is one. The order of the rules is the
// order of the reasons an operator would want to hear: what the market is, then what they asked
// for.
func (f Filters) Match(event jupiter.Event, market jupiter.Market, now time.Time) string {
	switch {
	case !signals.IsMarketID(market.MarketID):
		// A market whose identifier the phone cannot read is a market no phone could act on: it
		// would be published and then refused on every device.
		return Unreadable
	case !event.Active:
		return EventInactive
	case !market.Tradeable() && !f.Closed:
		return NotTradeable
	}
	if !f.wanted(event.Category) {
		return OtherCategory
	}
	if len(f.Tags) > 0 && !tagged(event.Tags, f.Tags) {
		return NoTag
	}
	if len(f.Keywords) > 0 && !mentioned(haystack(event, market), f.Keywords) {
		return NoKeyword
	}
	return f.closes(market, now)
}

// closes is the time-to-close rule on its own, because it is the one that depends on the clock.
func (f Filters) closes(market jupiter.Market, now time.Time) string {
	windowed := f.LeastCloseIn > 0 || f.MostCloseIn > 0
	if market.CloseTime <= 0 {
		if windowed {
			return NoCloseTime
		}
		return ""
	}
	away := time.Unix(market.CloseTime, 0).UTC().Sub(now.UTC())
	switch {
	case f.LeastCloseIn > 0 && away < f.LeastCloseIn:
		// Including a market that has already closed, which a listing can still carry.
		return TooSoon
	case f.MostCloseIn > 0 && away > f.MostCloseIn:
		return TooLate
	default:
		return ""
	}
}

// wanted is whether an event's bucket is one of the configured ones. The provider filters by bucket
// itself, so this is a second reading of the same rule — and it is here because a provider that
// ignored the parameter would otherwise have this template publish everything it returned.
func (f Filters) wanted(category string) bool {
	if len(f.Categories) == 0 {
		return true
	}
	for _, one := range f.Categories {
		// "all" is the provider's own word for every bucket, so a deployment that asked for it has
		// asked for this event too.
		if one == "all" || strings.EqualFold(one, category) {
			return true
		}
	}
	return false
}

// Expiry is when a signal for this market stops being actionable: the market's own close time.
//
// It is never "now plus something", and that is the whole point. An expiry derived from the clock
// would change on every cycle, which would move the revision, which would wake every subscribed
// phone — every five minutes, for ever. A market the provider gives no close time for expires
// [Filters.Lifetime] after this template *first saw* it, which is a stored instant and therefore
// just as stable.
func (f Filters) Expiry(market jupiter.Market, first time.Time) time.Time {
	if market.CloseTime > 0 {
		return time.Unix(market.CloseTime, 0).UTC().Truncate(time.Second)
	}
	return first.UTC().Add(f.Lifetime).Truncate(time.Second)
}

// haystack is the text a keyword is matched against: everything the records carry that a person
// would call a description, and nothing else.
func haystack(event jupiter.Event, market jupiter.Market) string {
	parts := []string{event.Title, event.Category, event.Subcategory, market.Title}
	parts = append(parts, event.Tags...)
	return strings.ToLower(strings.Join(parts, " "))
}

func mentioned(text string, keywords []string) bool {
	for _, keyword := range keywords {
		if keyword != "" && strings.Contains(text, strings.ToLower(keyword)) {
			return true
		}
	}
	return false
}

func tagged(held, wanted []string) bool {
	for _, one := range held {
		for _, want := range wanted {
			if strings.EqualFold(strings.TrimSpace(one), want) {
				return true
			}
		}
	}
	return false
}

// note is the prose published with a market: where it came from, what the provider calls it, and
// where the publisher's part ends.
//
// Nothing in it moves unless the market does. The titles, the bucket and the close time are all
// stable properties of a market; its price and its volume are deliberately absent, because a note
// that carried a price would move the document — and wake every phone — every time the price
// moved.
func note(operator string, event jupiter.Event, market jupiter.Market, closes time.Time) string {
	bucket := fold(event.Category, 40)
	if event.Subcategory != "" {
		bucket += "/" + fold(event.Subcategory, 40)
	}
	named := fold(market.Title, 160)
	if event.Title != "" {
		named = fold(event.Title, 160) + " — " + named
	}
	line := "Listed on Jupiter Prediction as \"" + named + "\""
	if bucket != "" {
		line += " (" + bucket + ")"
	}
	if !closes.IsZero() {
		line += ", closing " + closes.UTC().Format(time.RFC3339)
	}
	// The boundary, in the note itself, because the note is the one part of a proposal a person
	// certainly reads (docs/wiki/prediction-template.md).
	line += ". Its state, prices and rules are read on your own phone; which side to take, and how " +
		"much, is yours."
	if operator != "" {
		line = fold(operator, 400) + "\n" + line
	}
	if len(line) > signals.MaxNoteBytes {
		line = clip(line, signals.MaxNoteBytes)
	}
	return line
}

// fold makes a provider's text into something publishable: one line, no control characters, single
// spaces, and short enough that the whole note fits.
//
// It is not trust. The words are still the provider's — the note says so — and the phone reads the
// market's own title from the provider itself. What this prevents is a title with a newline or a
// zero-width run in it turning into a note the gateway refuses and a signal nobody ever sees.
func fold(text string, mostBytes int) string {
	var built strings.Builder
	space := false
	for _, character := range strings.TrimSpace(text) {
		switch {
		case character == '\n' || character == '\r' || character == '\t' ||
			unicode.IsSpace(character):
			space = true
		case character <= 0x1f, character >= 0x7f && character <= 0x9f,
			!unicode.IsGraphic(character):
			// Dropped: a control character, and anything that is not a glyph a person could see.
		default:
			if space && built.Len() > 0 {
				built.WriteByte(' ')
			}
			space = false
			built.WriteRune(character)
		}
	}
	return clip(strings.TrimSpace(built.String()), mostBytes)
}

// clip cuts text to a byte budget without cutting a character in half.
func clip(text string, mostBytes int) string {
	if len(text) <= mostBytes {
		return text
	}
	cut := mostBytes
	for cut > 0 && !isBoundary(text, cut) {
		cut--
	}
	return strings.TrimSpace(text[:cut])
}

func isBoundary(text string, at int) bool {
	return at >= len(text) || text[at]&0xc0 != 0x80
}

// candidates puts the markets a cycle matched in the order they are published in: the soonest to
// close first, and the identifier as the tie-break.
//
// The order matters because of [Filters.MostOpen]. When more markets match than this template will
// hold proposals for, the ones it takes are the ones closing soonest — which are the ones a
// subscriber has the least time to act on — and the choice is the same on every cycle rather than
// whatever order the provider happened to answer in.
func ordered(found []candidate) {
	sort.SliceStable(found, func(first, second int) bool {
		left, right := found[first], found[second]
		switch {
		case left.market.CloseTime != right.market.CloseTime:
			// A market with no close time goes last, not first.
			if left.market.CloseTime == 0 || right.market.CloseTime == 0 {
				return right.market.CloseTime == 0
			}
			return left.market.CloseTime < right.market.CloseTime
		default:
			return left.market.MarketID < right.market.MarketID
		}
	})
}
