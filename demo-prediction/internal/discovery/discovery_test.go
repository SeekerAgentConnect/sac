package discovery

import (
	"strings"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/demo-prediction/internal/jupiter"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/markets"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

var noon = time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)

// An event and a market that match everything, so that each case below can spoil exactly one thing.
func event() jupiter.Event {
	return jupiter.Event{
		EventID:     "POLY-606422",
		Title:       "Fed Decision in October?",
		Category:    "economics",
		Subcategory: "rates",
		Tags:        []string{"economics", "fed-rates", "macro-indicators"},
		Active:      true,
		SourceURL:   "https://jup.ag/prediction/fed-decision-in-october",
	}
}

func open() jupiter.Market {
	return jupiter.Market{
		MarketID:  "POLY-2589813",
		EventID:   "POLY-606422",
		Provider:  "polymarket",
		Title:     "25 bps increase",
		Status:    jupiter.Open,
		CloseTime: noon.Add(72 * time.Hour).Unix(),
	}
}

// The filters an operator would write, with a window either side.
func filters() Filters {
	return Filters{
		Source:       "polymarket",
		Categories:   []string{"economics"},
		Tags:         []string{"fed-rates"},
		Keywords:     []string{"fed"},
		LeastCloseIn: time.Hour,
		MostCloseIn:  30 * 24 * time.Hour,
		Lifetime:     7 * 24 * time.Hour,
		PageSize:     1,
		MostPages:    2,
		MostOpen:     10,
		MostChecks:   5,
		Every:        5 * time.Minute,
	}
}

// Every rule a market is matched against, one spoiled thing at a time. The reasons are the contract
// with an operator whose filters match nothing: each of them is counted and reported.
func TestEveryReasonAMarketIsNotACandidate(t *testing.T) {
	for _, one := range []struct {
		name    string
		filters func(Filters) Filters
		event   func(jupiter.Event) jupiter.Event
		market  func(jupiter.Market) jupiter.Market
		reason  string
	}{
		{name: "everything the operator asked for"},
		{
			name:   "a market identifier the phone cannot read",
			market: func(m jupiter.Market) jupiter.Market { m.MarketID = "POLY/2589813"; return m },
			reason: Unreadable,
		},
		{
			name:   "a market with no identifier at all",
			market: func(m jupiter.Market) jupiter.Market { m.MarketID = ""; return m },
			reason: Unreadable,
		},
		{
			name:   "an event the provider no longer lists",
			event:  func(e jupiter.Event) jupiter.Event { e.Active = false; return e },
			reason: EventInactive,
		},
		{
			name:   "a market that has closed",
			market: func(m jupiter.Market) jupiter.Market { m.Status = jupiter.Closed; return m },
			reason: NotTradeable,
		},
		{
			name:   "a market that has settled",
			market: func(m jupiter.Market) jupiter.Market { m.Result = "yes"; return m },
			reason: NotTradeable,
		},
		{
			name:    "a market that has closed, where closed markets were asked for",
			filters: func(f Filters) Filters { f.Closed = true; return f },
			market:  func(m jupiter.Market) jupiter.Market { m.Status = jupiter.Closed; return m },
			reason:  "",
		},
		{
			name:   "an event in another bucket",
			event:  func(e jupiter.Event) jupiter.Event { e.Category = "sports"; return e },
			reason: OtherCategory,
		},
		{
			name:    "an event in another bucket, where every bucket was asked for",
			filters: func(f Filters) Filters { f.Categories = []string{"all"}; return f },
			event:   func(e jupiter.Event) jupiter.Event { e.Category = "sports"; return e },
			reason:  "",
		},
		{
			name:    "an event in another bucket, where no bucket was named",
			filters: func(f Filters) Filters { f.Categories = nil; return f },
			event:   func(e jupiter.Event) jupiter.Event { e.Category = "weather"; return e },
			reason:  "",
		},
		{
			name:   "an event without the tag",
			event:  func(e jupiter.Event) jupiter.Event { e.Tags = []string{"economy"}; return e },
			reason: NoTag,
		},
		{
			name:   "an event whose tag is spelled in another case",
			event:  func(e jupiter.Event) jupiter.Event { e.Tags = []string{"Fed-Rates"}; return e },
			reason: "",
		},
		{
			name:   "an event with no tags at all",
			event:  func(e jupiter.Event) jupiter.Event { e.Tags = nil; return e },
			reason: NoTag,
		},
		{
			name:    "an event with no tags, where no tag was asked for",
			filters: func(f Filters) Filters { f.Tags = nil; return f },
			event:   func(e jupiter.Event) jupiter.Event { e.Tags = nil; return e },
			reason:  "",
		},
		{
			name:    "nothing that mentions the keyword",
			filters: func(f Filters) Filters { f.Keywords = []string{"bitcoin"}; return f },
			reason:  NoKeyword,
		},
		{
			name:    "a keyword the market's own title mentions",
			filters: func(f Filters) Filters { f.Keywords = []string{"25 BPS"}; return f },
			reason:  "",
		},
		{
			name:    "a keyword a tag mentions",
			filters: func(f Filters) Filters { f.Keywords = []string{"macro"}; return f },
			reason:  "",
		},
		{
			name:    "a keyword the subcategory mentions",
			filters: func(f Filters) Filters { f.Keywords = []string{"rates"}; return f },
			reason:  "",
		},
		{
			name:    "one keyword of several",
			filters: func(f Filters) Filters { f.Keywords = []string{"btc", "fed", "eth"}; return f },
			reason:  "",
		},
		{
			name:   "a market with no close time, where a window was asked for",
			market: func(m jupiter.Market) jupiter.Market { m.CloseTime = 0; return m },
			reason: NoCloseTime,
		},
		{
			name: "a market with no close time, where no window was asked for",
			filters: func(f Filters) Filters {
				f.LeastCloseIn, f.MostCloseIn = 0, 0
				return f
			},
			market: func(m jupiter.Market) jupiter.Market { m.CloseTime = 0; return m },
			reason: "",
		},
		{
			name: "a market closing sooner than the window",
			market: func(m jupiter.Market) jupiter.Market {
				m.CloseTime = noon.Add(30 * time.Minute).Unix()
				return m
			},
			reason: TooSoon,
		},
		{
			name: "a market that has already closed but is still listed",
			market: func(m jupiter.Market) jupiter.Market {
				m.CloseTime = noon.Add(-time.Hour).Unix()
				return m
			},
			reason: TooSoon,
		},
		{
			name: "a market closing exactly at the near edge",
			market: func(m jupiter.Market) jupiter.Market {
				m.CloseTime = noon.Add(time.Hour).Unix()
				return m
			},
			reason: "",
		},
		{
			name: "a market closing later than the window",
			market: func(m jupiter.Market) jupiter.Market {
				m.CloseTime = noon.Add(60 * 24 * time.Hour).Unix()
				return m
			},
			reason: TooLate,
		},
		{
			name: "a market closing exactly at the far edge",
			market: func(m jupiter.Market) jupiter.Market {
				m.CloseTime = noon.Add(30 * 24 * time.Hour).Unix()
				return m
			},
			reason: "",
		},
	} {
		t.Run(one.name, func(t *testing.T) {
			held, listed, market := filters(), event(), open()
			if one.filters != nil {
				held = one.filters(held)
			}
			if one.event != nil {
				listed = one.event(listed)
			}
			if one.market != nil {
				market = one.market(market)
			}
			if reason := held.Match(listed, market, noon); reason != one.reason {
				t.Fatalf("reason %q, expected %q", reason, one.reason)
			}
		})
	}
}

// An expiry is the market's own close time, never the clock. An expiry derived from `now` would
// change on every cycle, which would move the revision, which would wake every subscribed phone —
// every few minutes, for ever.
func TestAnExpiryIsTheMarketsOwnCloseTime(t *testing.T) {
	held := filters()
	closes := held.Expiry(open(), noon)
	if !closes.Equal(noon.Add(72 * time.Hour)) {
		t.Fatalf("expiry %s", closes)
	}
	// The same market, several cycles later: the same expiry.
	later := held.Expiry(open(), noon)
	if !later.Equal(closes) {
		t.Fatalf("the expiry moved to %s", later)
	}

	// A market the provider gives no close time for expires from when this template first saw it,
	// which is a stored instant and therefore just as stable.
	none := open()
	none.CloseTime = 0
	first := held.Expiry(none, noon)
	if !first.Equal(noon.Add(7 * 24 * time.Hour)) {
		t.Fatalf("expiry %s", first)
	}
	if !held.Expiry(none, noon).Equal(first) {
		t.Fatal("the fallback expiry moved between cycles")
	}
}

// The note is the one part of a proposal a person certainly reads, so this is what it says: where
// the market came from, what the provider calls it, and where the publisher's part ends.
func TestTheNoteSaysWhereItCameFromAndWhereItEnds(t *testing.T) {
	written := note("Following the FOMC.", event(), open(), noon.Add(72*time.Hour))
	for _, expected := range []string{
		"Following the FOMC.",
		"Listed on Jupiter Prediction",
		"Fed Decision in October? — 25 bps increase",
		"(economics/rates)",
		"closing 2026-09-20T12:00:00Z",
		"read on your own phone",
		"which side to take, and how much, is yours",
	} {
		if !strings.Contains(written, expected) {
			t.Fatalf("the note does not say %q:\n%s", expected, written)
		}
	}
	// Nothing volatile *as a value*: the note says that prices are read on the phone, and it
	// quotes none. A number in here that moved with the market would move the document, and wake
	// every phone, every time it moved.
	for _, absent := range []string{"$", "¢", "volume", "probability", "odds", "% chance"} {
		if strings.Contains(strings.ToLower(written), absent) {
			t.Fatalf("the note mentions %q, which moves without the market moving:\n%s", absent,
				written)
		}
	}
	if !signals.Printable(written, signals.MaxNoteBytes, true) {
		t.Fatalf("the note is not publishable:\n%s", written)
	}
}

// A note is built from a provider's text, so the provider's text cannot be allowed to make one
// unpublishable: a title with a newline in it would otherwise become a signal the gateway refuses
// and nobody ever sees.
func TestAProvidersTextCannotSpoilANote(t *testing.T) {
	nasty := event()
	nasty.Title = "Fed\nDecision\r\n\twith\u0000control\u200bcharacters"
	nasty.Category = strings.Repeat("bucket ", 40)
	market := open()
	market.Title = strings.Repeat("a very long market title ", 60)

	written := note(strings.Repeat("operator prose ", 200), nasty, market,
		noon.Add(72*time.Hour))
	switch {
	case !signals.Printable(written, signals.MaxNoteBytes, true):
		t.Fatalf("the note is not publishable:\n%q", written)
	case len(written) > signals.MaxNoteBytes:
		t.Fatalf("the note is %d bytes", len(written))
	case strings.Contains(written, "\r"), strings.Contains(written, "\t"),
		strings.Contains(written, "\u0000"), strings.Contains(written, "\u200b"):
		t.Fatalf("a control character survived:\n%q", written)
	case strings.Count(written, "\n") != 1:
		// Exactly one: the operator's line, then the generated one.
		t.Fatalf("%d line breaks:\n%q", strings.Count(written, "\n"), written)
	case !strings.Contains(written, "Fed Decision withcontrolcharacters"):
		t.Fatalf("the provider's words did not survive folding:\n%s", written)
	}

	// And a market with no title at all, which the provider's schema allows.
	bare := note("", jupiter.Event{Active: true}, jupiter.Market{MarketID: "BISON-1"}, time.Time{})
	if !signals.Printable(bare, signals.MaxNoteBytes, true) || bare == "" {
		t.Fatalf("a market with nothing but an ID reads as %q", bare)
	}
}

// A market's idempotency key is derived from the market and its generation, so it is the same key
// on every cycle — and a different one after a withdrawal, because a withdrawal is final.
func TestAMarketsKeyIsDerivedAndChangesOnlyWithAGeneration(t *testing.T) {
	first := markets.Market{Provider: "polymarket", MarketID: "POLY-2589813", Generation: 1}
	if first.Key() != "market:polymarket:POLY-2589813:1" {
		t.Fatalf("key %q", first.Key())
	}
	again := markets.Market{Provider: "polymarket", MarketID: "POLY-2589813", Generation: 1}
	if again.Key() != first.Key() {
		t.Fatal("the same market produced two keys")
	}
	next := first
	next.Generation = 2
	if next.Key() == first.Key() {
		t.Fatal("a new generation reuses the withdrawn proposal's key")
	}
	// It is a key the API's own rule would accept, since it is stored beside the ones callers send.
	for _, key := range []string{first.Key(), next.Key(), markets.Market{Generation: 10}.Key()} {
		if len(key) > 200 {
			t.Fatalf("%q is too long to be an idempotency key", key)
		}
		for _, character := range key {
			if character < 0x21 || character > 0x7e {
				t.Fatalf("%q has a character an idempotency key may not have", key)
			}
		}
	}
}

// When more markets match than this template will hold proposals for, the ones it takes are the
// ones closing soonest — and the order is the same on every cycle rather than whatever order the
// provider answered in.
func TestTheSoonestToCloseArePublishedFirst(t *testing.T) {
	far, near, none, tie := open(), open(), open(), open()
	far.MarketID, far.CloseTime = "POLY-3", noon.Add(90*time.Hour).Unix()
	near.MarketID, near.CloseTime = "POLY-2", noon.Add(2*time.Hour).Unix()
	none.MarketID, none.CloseTime = "POLY-4", 0
	tie.MarketID, tie.CloseTime = "POLY-1", near.CloseTime

	found := []candidate{{market: none}, {market: far}, {market: tie}, {market: near}}
	ordered(found)
	got := []string{}
	for _, one := range found {
		got = append(got, one.market.MarketID)
	}
	if strings.Join(got, ",") != "POLY-1,POLY-2,POLY-3,POLY-4" {
		t.Fatalf("the order is %v", got)
	}
}

// What an operator reads back: the filters in force, and what the last cycle did.
func TestTheFiltersAndTheCycleReadBack(t *testing.T) {
	described := filters().Describe()
	for _, name := range []string{
		"source", "categories", "tags", "keywords", "closed_markets", "closes_between",
		"page_size", "most_pages", "most_open", "most_checks", "every", "lifetime",
	} {
		if _, present := described[name]; !present {
			t.Fatalf("the filters do not describe %q: %v", name, described)
		}
	}
	if described["closes_between"] != "1h0m0s and 720h0m0s" {
		t.Fatalf("closes_between is %v", described["closes_between"])
	}
	// An empty filter reads as an empty list rather than as null, because an operator reading the
	// answer should not have to tell those apart.
	bare := Filters{}.Describe()
	for _, name := range []string{"categories", "tags", "keywords"} {
		if _, ok := bare[name].([]string); !ok {
			t.Fatalf("%q is %#v", name, bare[name])
		}
	}
	if _, present := bare["filter"]; present {
		t.Fatal("a filter nobody set is described as set")
	}

	cycle := markets.Cycle{
		Number: 4, StartedAt: noon, FinishedAt: noon.Add(time.Second), Outcome: markets.Partial,
		Problem: "provider_rate_limited", Detail: "Too many requests", Pages: 1, Events: 1,
		Considered: 5, Matched: 2, Created: 1, Updated: 1, Checked: 1, Skipped: 0,
		Reasons: map[string]int{NoKeyword: 3},
	}
	answer := cycle.Describe()
	switch {
	case answer["number"] != 4:
		t.Fatalf("number %v", answer["number"])
	case answer["outcome"] != markets.Partial:
		t.Fatalf("outcome %v", answer["outcome"])
	case answer["started_at"] != "2026-09-17T12:00:00Z":
		t.Fatalf("started_at %v", answer["started_at"])
	case answer["problem"] != "provider_rate_limited":
		t.Fatalf("problem %v", answer["problem"])
	}
	reasons, ok := answer["skipped_because"].(map[string]int)
	if !ok || reasons[NoKeyword] != 3 {
		t.Fatalf("skipped_because is %#v", answer["skipped_because"])
	}
	if !cycle.Working() {
		t.Fatal("a partial cycle is still a working one: what it read, it published")
	}
	if (markets.Cycle{Outcome: markets.Failed}).Working() {
		t.Fatal("a cycle that read nothing is not working")
	}
}
