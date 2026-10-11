// Package discovery turns a provider's listing into this template's own proposals (SEE-96,
// docs/wiki/prediction-template.md).
//
// The Prediction template does not wait to be told what to publish. It walks the provider's
// listing, applies the filters its operator configured, groups the markets that match by the event
// they belong to, and publishes one proposal per event it selects — choosing events so the configured
// categories and keywords are all represented (select.go, SEE-177) — then keeps each of those in step
// with the source until it closes. Nothing about a
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
//   - **keywords** — case-insensitive text at the start of a word of the event's title, category,
//     subcategory and tags together with the market's title. The start of a word, deliberately:
//     "eth" finds "Ethereum", which is what an operator writing a keyword list expects, while "nba"
//     does not find "unban" and "epl" does not find "replace" ([mentions]). Any one keyword
//     matching is enough.
//   - **time to close** — the market's own close time has to be at least [Filters.LeastCloseIn] and
//     at most [Filters.MostCloseIn] away. A market with no close time at all cannot be judged
//     against a window, so it is skipped whenever one is configured.
//   - **state** — a market has to be one the provider would take an order for, unless
//     [Filters.Closed] is set, which an operator does deliberately to exercise the closure path
//     (PREDICTION_STATE=any, internal/config).
//   - **liquidity** — both sides have to be quoted both ways, each within [Filters.MostSpread]:
//     someone offering it, so an owner can get in, and someone bidding for it, so they can get out
//     again before the market closes. A side nobody bids for is a position held to resolution
//     whether its owner wants that or not.
//   - **two outcomes** — with [Filters.TwoOutcomes], the event has to be one yes-or-no question:
//     a single market. An event of several markets — a ladder of price strikes, of valuation
//     ranges, of "by October / by December" deadlines — is skipped.
//   - **crypto prices** — with [Filters.NoCryptoPrices], a crypto event about a token's price is
//     skipped: up or down, above or below a strike, what price it hits, its FDV at launch. Read
//     from the provider's own tags and, because it does not tag every one, from the event's title
//     ([cryptoPrice]).
//
// Everything a filter is written against is in the answer the listing gave. There is deliberately
// no filter over anything the provider does not send with a market — no implied volume threshold,
// no computed probability, no model of any kind — because a filter that needed data the listing
// does not carry would be a filter that quietly matched nothing, or matched on a guess. The quoted
// prices the liquidity rule reads are in that answer, and are read only to decide whether to
// publish: they are never published.
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
	"unicode/utf8"

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
	// Whether a market the provider would not take an order for may still be published. It exists
	// so that the closure path can be exercised deliberately, and a deployment that sets it is
	// warned at startup (internal/config).
	Closed bool
	// The widest gap between buying a side and selling it back, in the provider's micro-dollars,
	// that still counts as a market an owner can leave. Zero means no liquidity rule at all;
	// a deployment always has one (PREDICTION_MOST_SPREAD_CENTS, internal/config).
	MostSpread int64
	// Only events that are one yes-or-no question (PREDICTION_OUTCOMES=two).
	TwoOutcomes bool
	// No crypto event about a token's price (PREDICTION_CRYPTO_PRICES=exclude).
	NoCryptoPrices bool
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
	// How many proposals this template will keep open at once — one per event, so this is also how
	// many events the feed shows — and how many markets it will ask about directly in one cycle. Both are this template's own restraint rather than a limit of
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
		"most_spread":    f.MostSpread,
		"two_outcomes":   f.TwoOutcomes,
		"crypto_prices":  !f.NoCryptoPrices,
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
	// Nobody quotes one of its sides both ways, or the gap between them is too wide to get out at.
	Illiquid = "no_liquidity"
	// Its event is a question of more than two outcomes.
	ManyOutcomes = "more_than_two_outcomes"
	// A crypto event about a token's price.
	CryptoPrice = "crypto_price"
	// An operator unpublished it by name; only selecting it again publishes it.
	Dismissed = "dismissed"
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
	case f.MostSpread > 0 && !Liquid(market, f.MostSpread):
		return Illiquid
	case f.TwoOutcomes && len(event.Markets) != 1:
		return ManyOutcomes
	case f.NoCryptoPrices && cryptoPrice(event, market):
		return CryptoPrice
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

// Liquid is whether an owner could both enter and leave this market right now: each side has an
// offer under a dollar and a bid above nothing, at most [most] micro-dollars apart. It reads the
// provider's quoted best prices, which say nothing of how much rests at them — a tight quote on
// a venue like this one is what a market with depth looks like, and a missing one is what a market
// you cannot get out of looks like.
func Liquid(market jupiter.Market, most int64) bool {
	for _, side := range [][2]int64{{market.BuyYes, market.SellYes}, {market.BuyNo, market.SellNo}} {
		buy, sell := side[0], side[1]
		if buy <= 0 || buy >= jupiter.OneDollar || sell <= 0 || buy-sell > most {
			return false
		}
	}
	return true
}

// The provider's own tags for an event about a token's price.
var priceTags = []string{"crypto-prices", "hit-price", "price-milestone", "price-comparison", "fdv"}

// The words a crypto event about a token's price is titled with, in [Normalize] form. The provider
// leaves the most frequent of them untagged — "Bitcoin Up or Down - October 11, 5PM ET", "Ethereum
// above ___ on October 18?", "What price will Solana hit on October 11?" — so the title is read too.
var pricePhrases = []string{
	"price", "up or down", " above ", " below ", "fdv", "market cap", "all time high",
	"all-time high", "outperform",
}

// cryptoPrice is whether an event is a crypto event about a token's price. A crypto event about
// anything else — a token launch, a law, an exchange listing — is not.
func cryptoPrice(event jupiter.Event, market jupiter.Market) bool {
	if !strings.EqualFold(event.Category, "crypto") && !tagged(event.Tags, []string{"crypto"}) {
		return false
	}
	if tagged(event.Tags, priceTags) {
		return true
	}
	title := " " + Normalize(event.Title+" "+market.Title) + " "
	for _, phrase := range pricePhrases {
		if strings.Contains(title, phrase) {
			return true
		}
	}
	return false
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
// would call a description, and nothing else — in the [Normalize] form a keyword is compared in, so
// a doubled space or a full-width letter in a title does not hide a match.
func haystack(event jupiter.Event, market jupiter.Market) string {
	parts := []string{event.Title, event.Category, event.Subcategory, market.Title}
	parts = append(parts, event.Tags...)
	return Normalize(strings.Join(parts, " "))
}

func mentioned(text string, keywords []string) bool {
	for _, keyword := range keywords {
		if mentions(text, Normalize(keyword)) {
			return true
		}
	}
	return false
}

// mentions is whether [wanted] appears in [text] at the start of a word: at the beginning, or after
// anything that is not a letter or a digit. Both are in [Normalize] form.
func mentions(text, wanted string) bool {
	if wanted == "" {
		return false
	}
	for from := 0; from < len(text); {
		at := strings.Index(text[from:], wanted)
		if at < 0 {
			return false
		}
		at += from
		before, _ := utf8.DecodeLastRuneInString(text[:at])
		if at == 0 || !unicode.IsLetter(before) && !unicode.IsDigit(before) {
			return true
		}
		_, size := utf8.DecodeRuneInString(text[at:])
		from = at + size
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

// ordered puts markets in their stable order: the soonest to close first, and the identifier as the
// tie-break.
//
// It decides which market represents an event, and — through each event's soonest market — which
// of two events that add the same coverage is chosen first (select.go). Either way the choice is the
// same on every cycle rather than whatever order the provider happened to answer in.
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
