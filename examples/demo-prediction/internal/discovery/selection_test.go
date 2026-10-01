package discovery_test

// One proposal per event and coverage of the configured buckets, over the real store (SEE-177).

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/demo-prediction/internal/discovery"
	"github.com/BrRenat/SeekerAgentWallet/demo-prediction/internal/jupiter"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/markets"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// valuation is the reported event: one question with three outcome markets.
func valuation(ids ...string) jupiter.Event {
	event := jupiter.Event{
		EventID:   "POLY-798787",
		Title:     "OpenAI’s valuation end of September 2026?",
		Category:  "tech",
		Active:    true,
		SourceURL: "https://jup.ag/prediction/openai-valuation-end-of-september-2026",
	}
	titles := map[string]string{
		"POLY-3350396": "$900B–$1.00T",
		"POLY-3350393": "$600–$700B",
		"POLY-3350399": "$1.20–$1.30T",
	}
	for _, id := range ids {
		event.Markets = append(event.Markets, jupiter.Market{
			MarketID: id, EventID: "POLY-798787", Provider: "polymarket", Title: titles[id],
			Status: jupiter.Open, CloseTime: noon.Add(14 * 24 * time.Hour).Unix(),
		})
	}
	return event
}

func demoFilters(h *harness) {
	h.filters.Categories = []string{"crypto", "tech"}
	h.filters.Keywords = []string{"solana", "zcash", "seeker", "solana mobile",
		"artificial intelligence", "openai", "anthropic", "chatgpt"}
	h.filters.LeastCloseIn = 24 * time.Hour
	h.filters.MostOpen = 8
}

func open(records []signals.Record) []signals.Record {
	held := []signals.Record{}
	for _, one := range records {
		if one.Signal.Status == signals.Open {
			held = append(held, one)
		}
	}
	return held
}

// The three valuation markets are one entry, and stay one through repeated pages, repeated cycles
// and a restart; the entry names the market it is, and the terms carry that market.
func TestTheReportedEventIsOneEntryThroughPagesCyclesAndRestarts(t *testing.T) {
	provider := &source{events: func(jupiter.Query) (jupiter.Page, error) {
		// Every page repeats the event, in a different market order, and says there is more.
		return jupiter.Page{Events: []jupiter.Event{
			valuation("POLY-3350396", "POLY-3350393", "POLY-3350399")}, HasNext: true}, nil
	}}
	held := start(t, provider, demoFilters)

	cycle := held.pass()
	if cycle.Created != 1 || cycle.Selection.Events != 1 || cycle.Selection.Candidates != 3 {
		t.Fatalf("created %d of %d events from %d markets", cycle.Created,
			cycle.Selection.Events, cycle.Selection.Candidates)
	}
	records := held.signals()
	if len(records) != 1 {
		t.Fatalf("%d signals", len(records))
	}
	signal := records[0].Signal
	switch {
	case signal.Terms[signals.MarketID] != "POLY-3350393":
		t.Fatalf("the entry trades %s", signal.Terms[signals.MarketID])
	case signal.Terms[signals.EventID] != "POLY-798787":
		t.Fatalf("the entry's event is %s", signal.Terms[signals.EventID])
	case signal.Title != "OpenAI’s valuation end of September 2026? · $600–$700B":
		t.Fatalf("the entry is titled %q", signal.Title)
	}

	held.clock = noon.Add(5 * time.Minute)
	if again := held.pass(); again.Created != 0 || again.Updated != 0 || again.Cancelled != 0 {
		t.Fatalf("a second cycle created %d, updated %d, withdrew %d", again.Created,
			again.Updated, again.Cancelled)
	}
	if err := held.documents.Close(); err != nil {
		t.Fatal(err)
	}
	held.clock = noon.Add(time.Hour)
	held.open()
	if after := held.pass(); after.Created != 0 || after.Updated != 0 || after.Cancelled != 0 {
		t.Fatalf("a restart created %d, updated %d, withdrew %d", after.Created, after.Updated,
			after.Cancelled)
	}
	if after := held.signals(); len(after) != 1 || after[0].Signal.Revision != signal.Revision {
		t.Fatalf("after a restart the signals are %+v", after)
	}
	// The selection is recorded with the cycle, for the operator to read back.
	recorded, err := held.documents.Cycle(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if recorded.Selection.Selected != 1 || len(recorded.Selection.Buckets) != 10 {
		t.Fatalf("the recorded selection is %+v", recorded.Selection)
	}
}

// A feed published before SEE-177 holds one proposal per market. The first cycle keeps the oldest
// entry for the event and withdraws the others — which a phone shows as withdrawn, in its history,
// with whatever it did about them intact — and nothing is republished afterwards.
func TestDuplicatesPublishedEarlierAreWithdrawnOnce(t *testing.T) {
	listed := valuation("POLY-3350396")
	provider := &source{events: func(query jupiter.Query) (jupiter.Page, error) {
		if query.Start > 0 {
			return jupiter.Page{}, nil
		}
		return jupiter.Page{Events: []jupiter.Event{listed}}, nil
	}}
	held := start(t, provider, demoFilters)
	held.pass()
	first := held.tracked()[0]

	// The two other markets, as the old reconciler published them: an open proposal each.
	for at, id := range []string{"POLY-3350393", "POLY-3350399"} {
		row := first.Market
		row.MarketID = id
		row.ProposalID = held.mint()
		row.FirstSeenAt = noon.Add(time.Duration(at+1) * time.Minute)
		signal := first.Record.Signal
		signal.ProposalID = row.ProposalID
		signal.Terms = map[string]string{}
		for key, value := range first.Record.Signal.Terms {
			signal.Terms[key] = value
		}
		signal.Terms[signals.MarketID] = id
		if _, err := held.documents.Discover(context.Background(), row, row.Key(),
			signals.Statement(signal), signal); err != nil {
			t.Fatal(err)
		}
	}
	if len(open(held.signals())) != 3 {
		t.Fatal("the duplicates were not seeded")
	}

	listed = valuation("POLY-3350396", "POLY-3350393", "POLY-3350399")
	held.clock = noon.Add(10 * time.Minute)
	cycle := held.pass()
	switch {
	case cycle.Cancelled != 2 || cycle.Selection.Retired[discovery.DuplicateEvent] != 2:
		t.Fatalf("withdrew %d, retired %v", cycle.Cancelled, cycle.Selection.Retired)
	case cycle.Created != 0:
		t.Fatalf("created %d", cycle.Created)
	}
	kept := open(held.signals())
	if len(kept) != 1 || kept[0].Signal.ProposalID != first.Market.ProposalID {
		t.Fatalf("kept %+v", kept)
	}
	// The withdrawn ones are still there, as withdrawals: a phone reads the cancellation rather
	// than losing the proposal.
	if all := held.signals(); len(all) != 3 {
		t.Fatalf("%d signals remain", len(all))
	}

	held.clock = noon.Add(20 * time.Minute)
	if again := held.pass(); again.Created != 0 || again.Cancelled != 0 || again.Updated != 0 {
		t.Fatalf("the next cycle created %d, withdrew %d, updated %d", again.Created,
			again.Cancelled, again.Updated)
	}
}

// An operator's choice of another market of a held event replaces the event's entry, and no cycle
// takes it back for coverage.
func TestAnOperatorsMarketReplacesItsEventsEntryAndStays(t *testing.T) {
	listed := []jupiter.Event{valuation("POLY-3350396", "POLY-3350393", "POLY-3350399")}
	chosen := valuation("POLY-3350399").Markets[0]
	provider := &source{
		events: func(query jupiter.Query) (jupiter.Page, error) {
			if query.Start > 0 {
				return jupiter.Page{}, nil
			}
			return jupiter.Page{Events: listed}, nil
		},
		market: func(id string) (jupiter.Market, error) { return chosen, nil },
	}
	held := start(t, provider, func(h *harness) {
		demoFilters(h)
		h.filters.MostOpen = 1
	})
	held.pass()

	held.clock = noon.Add(time.Minute)
	tracked, err := held.reconciler.Select(context.Background(), chosen.MarketID)
	if err != nil {
		t.Fatal(err)
	}
	if !tracked.Market.Pinned {
		t.Fatal("an operator's market is not pinned")
	}
	kept := open(held.signals())
	if len(kept) != 1 || kept[0].Signal.Terms[signals.MarketID] != "POLY-3350399" {
		t.Fatalf("open after the choice: %+v", kept)
	}

	// An event covering a bucket nothing held covers does not take the operator's slot.
	anthropic := jupiter.Event{EventID: "POLY-9", Title: "Anthropic raises at $300B?",
		Category: "tech", Active: true, Markets: []jupiter.Market{{MarketID: "POLY-90",
			EventID: "POLY-9", Provider: "polymarket", Title: "Yes", Status: jupiter.Open,
			CloseTime: noon.Add(20 * 24 * time.Hour).Unix()}}}
	listed = append(listed, anthropic)
	held.clock = noon.Add(10 * time.Minute)
	cycle := held.pass()
	if cycle.Cancelled != 0 || cycle.Created != 0 {
		t.Fatalf("withdrew %d, created %d", cycle.Cancelled, cycle.Created)
	}
}

// A held event whose proposal expired holds no slot: the next eligible event takes it.
func TestAnExpiredEventIsReplaced(t *testing.T) {
	soon := jupiter.Event{EventID: "POLY-1", Title: "OpenAI device launch?", Category: "tech",
		Active: true, Markets: []jupiter.Market{{MarketID: "POLY-10", EventID: "POLY-1",
			Provider: "polymarket", Title: "Yes", Status: jupiter.Open,
			CloseTime: noon.Add(2 * 24 * time.Hour).Unix()}}}
	later := jupiter.Event{EventID: "POLY-2", Title: "OpenAI IPO in 2026?", Category: "tech",
		Active: true, Markets: []jupiter.Market{{MarketID: "POLY-20", EventID: "POLY-2",
			Provider: "polymarket", Title: "Yes", Status: jupiter.Open,
			CloseTime: noon.Add(30 * 24 * time.Hour).Unix()}}}
	provider := &source{events: listing(soon, later)}
	held := start(t, provider, func(h *harness) {
		demoFilters(h)
		h.filters.MostOpen = 1
	})
	held.pass()
	if kept := open(held.signals()); len(kept) != 1 ||
		kept[0].Signal.Terms[signals.MarketID] != "POLY-10" {
		t.Fatalf("held %+v", kept)
	}

	held.clock = noon.Add(3 * 24 * time.Hour)
	cycle := held.pass()
	if cycle.Created != 1 || cycle.Selection.Selected != 1 {
		t.Fatalf("created %d, selected %d", cycle.Created, cycle.Selection.Selected)
	}
	tracked := held.tracked()
	if len(tracked) != 2 || tracked[1].Market.MarketID != "POLY-20" {
		t.Fatalf("tracked %+v", tracked)
	}
	if cycle.Outcome != markets.OK {
		t.Fatalf("outcome %s", cycle.Outcome)
	}
}

// The reported configuration against the real provider (SEE-177). Opt-in, because it reads the
// live listing — up to eight pages, paced inside the keyless allowance — and prints what the feed
// would hold and which buckets the provider currently has anything for:
//
//	SEEKERVAULT_JUPITER=1 go test ./internal/discovery/ -run Live -v
func TestLiveSelectionWithTheReportedConfiguration(t *testing.T) {
	if os.Getenv("SEEKERVAULT_JUPITER") == "" {
		t.Skip("set SEEKERVAULT_JUPITER=1 to read the real provider")
	}
	client, err := jupiter.New(jupiter.Options{Timeout: 30 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	provider := &source{
		events: func(query jupiter.Query) (jupiter.Page, error) {
			return client.Events(context.Background(), query)
		},
		market: func(id string) (jupiter.Market, error) {
			return client.Market(context.Background(), id)
		},
	}
	held := start(t, provider, func(h *harness) {
		demoFilters(h)
		h.clock = time.Now().UTC()
		h.filters.LeastCloseIn = 1440 * time.Minute
		h.filters.MostCloseIn = 259200 * time.Minute
		h.filters.PageSize = 100
		h.filters.MostPages = 4
	})

	cycle := held.pass()
	t.Logf("outcome %s (%s %s): %d pages, %d events, %d markets considered, %d matched",
		cycle.Outcome, cycle.Problem, cycle.Detail, cycle.Pages, cycle.Events, cycle.Considered,
		cycle.Matched)
	t.Logf("skipped because %v", cycle.Reasons)
	t.Logf("selection: %d candidates in %d events, %d selected", cycle.Selection.Candidates,
		cycle.Selection.Events, cycle.Selection.Selected)
	for _, bucket := range cycle.Selection.Buckets {
		t.Logf("  %-8s %-24q eligible %3d  selected %d  %s", bucket.Kind, bucket.Name,
			bucket.Eligible, bucket.Selected, bucket.Uncovered)
	}
	events := map[string]bool{}
	live := 0
	for _, row := range held.tracked() {
		if row.Record.Signal.Status != signals.Open {
			continue
		}
		live++
		if events[row.Market.EventID] {
			t.Fatalf("event %s holds two entries", row.Market.EventID)
		}
		events[row.Market.EventID] = true
		t.Logf("  %s %s  %q  closes %s", row.Market.EventID, row.Market.MarketID,
			row.Record.Signal.Title, row.Market.CloseAt.Format(time.RFC3339))
	}
	if live > 8 || live != cycle.Selection.Selected {
		t.Fatalf("%d entries, %d selected", live, cycle.Selection.Selected)
	}

	// The next cycle over the same listing keeps the same entries.
	held.clock = held.clock.Add(time.Minute)
	again := held.pass()
	t.Logf("the next cycle: created %d, updated %d, withdrew %d", again.Created, again.Updated,
		again.Cancelled)
	if again.Created != 0 || again.Cancelled != 0 {
		t.Fatalf("the next cycle created %d and withdrew %d", again.Created, again.Cancelled)
	}
}
