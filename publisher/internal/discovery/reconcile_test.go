// The reconciler, over the real store and — for one test — the real provider client answering with
// the answers the provider really gave (SEE-96).
//
// It is an external test package because the store is written against this one: `store` imports
// `discovery` for the two row types it keeps, so a test inside `discovery` could not open a
// database. What that costs is the unexported helpers, which `discovery_test.go` covers from
// inside; what it buys is that everything here is the real thing — real SQL, real transactions,
// real decoding — with the clock and the provider's answers as the only things held still.
package discovery_test

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/discovery"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/jupiter"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/signals"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/store"
)

const server = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

var noon = time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)

// source is a provider a test scripts: one function for the listing, one for a direct read, and a
// record of what was asked.
type source struct {
	events func(jupiter.Query) (jupiter.Page, error)
	market func(string) (jupiter.Market, error)
	mutex  sync.Mutex
	listed []jupiter.Query
	asked  []string
}

func (s *source) Events(_ context.Context, query jupiter.Query) (jupiter.Page, error) {
	s.mutex.Lock()
	s.listed = append(s.listed, query)
	s.mutex.Unlock()
	return s.events(query)
}

func (s *source) Market(_ context.Context, id string) (jupiter.Market, error) {
	s.mutex.Lock()
	s.asked = append(s.asked, id)
	s.mutex.Unlock()
	if s.market == nil {
		return jupiter.Market{}, &jupiter.Fault{Problem: jupiter.NoSuchMarket}
	}
	return s.market(id)
}

// listing answers one page of whatever events it is given, with no second page.
func listing(events ...jupiter.Event) func(jupiter.Query) (jupiter.Page, error) {
	return func(query jupiter.Query) (jupiter.Page, error) {
		if query.Start > 0 {
			return jupiter.Page{}, nil
		}
		return jupiter.Page{Events: events}, nil
	}
}

type harness struct {
	t          *testing.T
	documents  *store.Store
	source     *source
	reconciler *discovery.Reconciler
	clock      time.Time
	woken      int
	minted     int
	log        *strings.Builder
	path       string
	filters    discovery.Filters
	deposit    discovery.Deposit
}

func filters() discovery.Filters {
	return discovery.Filters{
		Source:       "polymarket",
		Categories:   []string{"economics"},
		LeastCloseIn: time.Hour,
		MostCloseIn:  90 * 24 * time.Hour,
		Lifetime:     7 * 24 * time.Hour,
		PageSize:     1,
		MostPages:    2,
		MostOpen:     20,
		MostChecks:   5,
		Every:        5 * time.Minute,
	}
}

func start(t *testing.T, provider *source, change ...func(*harness)) *harness {
	t.Helper()
	held := &harness{
		t:       t,
		source:  provider,
		clock:   noon,
		log:     &strings.Builder{},
		path:    filepath.Join(t.TempDir(), "publisher.db"),
		filters: filters(),
		deposit: discovery.Deposit{Mint: signals.USDCMint, Symbol: "USDC"},
	}
	for _, one := range change {
		one(held)
	}
	held.open()
	return held
}

// open builds the store and the reconciler. It is separate so a test can close them and open the
// same file again, which is what a restart is.
func (h *harness) open() {
	h.t.Helper()
	documents, err := store.Open(h.path, store.Stamp{
		ServerID:    server,
		Environment: "sandbox",
		GatewayURL:  "https://feeds.example.com",
	})
	if err != nil {
		h.t.Fatal(err)
	}
	h.documents = documents
	h.t.Cleanup(func() { _ = documents.Close() })
	h.reconciler = discovery.New(discovery.Plan{
		Documents: documents,
		Source:    h.source,
		Kind:      signals.Prediction{},
		Filters:   h.filters,
		Deposit:   h.deposit,
		Note:      "",
		Log:       slog.New(slog.NewTextHandler(h.log, nil)),
		Now:       func() time.Time { return h.clock },
		NewID:     h.mint,
		Wake:      func() { h.woken++ },
	})
}

// mint is a proposal identity a test can predict, in the shape the phone requires.
func (h *harness) mint() string {
	h.minted++
	return "00000000-0000-4000-8000-" + strings.Repeat("0", 11) + string(rune('0'+h.minted%10))
}

func (h *harness) pass() discovery.Cycle {
	h.t.Helper()
	cycle, err := h.reconciler.Pass(context.Background())
	if err != nil {
		h.t.Fatal(err)
	}
	return cycle
}

func (h *harness) signals() []signals.Record {
	h.t.Helper()
	held, err := h.documents.Signals(context.Background())
	if err != nil {
		h.t.Fatal(err)
	}
	return held
}

func (h *harness) tracked() []discovery.Tracked {
	h.t.Helper()
	held, err := h.documents.Markets(context.Background())
	if err != nil {
		h.t.Fatal(err)
	}
	return held
}

// An event the tests can spoil one thing in.
func event(id string, markets ...jupiter.Market) jupiter.Event {
	return jupiter.Event{
		EventID:   id,
		Title:     "Fed Decision in October?",
		Category:  "economics",
		Tags:      []string{"economics", "fed-rates"},
		Active:    true,
		SourceURL: "https://jup.ag/prediction/fed-decision-in-october",
		Markets:   markets,
	}
}

func market(id string, closes time.Time) jupiter.Market {
	return jupiter.Market{
		MarketID:  id,
		EventID:   "POLY-606422",
		Provider:  "polymarket",
		Title:     "25 bps increase",
		Status:    jupiter.Open,
		CloseTime: closes.Unix(),
	}
}

// The whole of the ordinary path, against the answers the provider really gave: two pages of a real
// listing become proposals, with the terms the phone reads and the expiry the market itself set.
func TestARealListingBecomesProposals(t *testing.T) {
	provider := &source{}
	held := start(t, provider)
	client := realProvider(t)
	provider.events = func(query jupiter.Query) (jupiter.Page, error) {
		return client.Events(context.Background(), query)
	}
	provider.market = func(id string) (jupiter.Market, error) {
		return client.Market(context.Background(), id)
	}

	cycle := held.pass()
	switch {
	case cycle.Outcome != discovery.OK:
		t.Fatalf("outcome %q (%s)", cycle.Outcome, cycle.Detail)
	case cycle.Number != 1:
		t.Fatalf("cycle %d", cycle.Number)
	case cycle.Pages != 2:
		t.Fatalf("%d pages: the walk is two calls of one event each", cycle.Pages)
	case cycle.Events != 2:
		t.Fatalf("%d events", cycle.Events)
	case cycle.Considered != 10:
		t.Fatalf("%d markets considered: the two events carry five each", cycle.Considered)
	case cycle.Matched != 10 || cycle.Created != 10:
		t.Fatalf("matched %d, created %d", cycle.Matched, cycle.Created)
	case held.woken != 1:
		t.Fatalf("the drainer was woken %d times", held.woken)
	}

	// The two pages the walk asked for are the two the provider was asked for.
	if len(provider.listed) != 2 || provider.listed[0].Start != 0 || provider.listed[1].Start != 1 {
		t.Fatalf("the walk asked for %+v", provider.listed)
	}

	tracked := held.tracked()
	if len(tracked) != 10 {
		t.Fatalf("%d markets tracked", len(tracked))
	}
	// Soonest to close first, which is the order they were published in and the order they are
	// read back in: the Bank of Japan markets close tomorrow, the Fed's in October.
	first := tracked[0]
	switch {
	case first.Market.EventID != "POLY-606452":
		t.Fatalf("the first market is %s of %s", first.Market.MarketID, first.Market.EventID)
	case first.Market.State != "open":
		t.Fatalf("state %q", first.Market.State)
	case first.Market.Generation != 1:
		t.Fatalf("generation %d", first.Market.Generation)
	case !first.Market.CloseAt.Equal(time.Unix(1789747140, 0).UTC()):
		t.Fatalf("close time %s", first.Market.CloseAt)
	case first.Market.SourceURL == "":
		t.Fatal("the row kept no link to the provider's own page")
	}

	signal := first.Record.Signal
	switch {
	case signal.Operation != "prediction" || signal.PluginID != "jupiter.prediction":
		t.Fatalf("%s/%s", signal.Operation, signal.PluginID)
	case signal.Revision != 1:
		t.Fatalf("revision %d", signal.Revision)
	case signal.Status != signals.Open:
		t.Fatalf("status %s", signal.Status)
	case !signal.ExpiresAt.Equal(first.Market.CloseAt):
		t.Fatalf("the expiry is %s and the market closes at %s", signal.ExpiresAt,
			first.Market.CloseAt)
	case signal.Terms[signals.MarketID] != first.Market.MarketID:
		t.Fatalf("terms %v", signal.Terms)
	case signal.Terms[signals.EventID] != "POLY-606452":
		t.Fatalf("terms %v", signal.Terms)
	case signal.Terms[signals.SourceProvider] != "polymarket":
		t.Fatalf("terms %v", signal.Terms)
	case signal.Terms[signals.DepositMint] != signals.USDCMint:
		t.Fatalf("terms %v", signal.Terms)
	case signal.Terms[signals.DepositDecimals] != "6":
		t.Fatalf("terms %v", signal.Terms)
	case signal.Terms[signals.LeastDeposit] != "5000000":
		t.Fatalf("the provider's own floor is not in the terms: %v", signal.Terms)
	}
	// Nothing about a side, a stake or anybody: the terms are the market and the token, and that
	// is the whole of what a prediction signal says.
	for _, absent := range []string{"side", "is_yes", "amount", "wallet", "decision"} {
		if _, present := signal.Terms[absent]; present {
			t.Fatalf("%q is in the terms: %v", absent, signal.Terms)
		}
	}
	if !strings.Contains(signal.Note, "Bank of Japan Decision in September?") {
		t.Fatalf("the note does not say what the provider called it:\n%s", signal.Note)
	}

	// Nothing is published yet: publication is the drainer's, and this is the outbox.
	for _, record := range held.signals() {
		if record.Publication.ConfirmedRevision != 0 {
			t.Fatalf("%s reads as published", record.Signal.ProposalID)
		}
	}
}

// realProvider is the provider's own client, against the answers it really gave
// (internal/jupiter/testdata, scripts/capture-jupiter.mjs --events).
func realProvider(t *testing.T) *jupiter.Client {
	t.Helper()
	const testdata = "../jupiter/testdata"
	raw, err := os.ReadFile(filepath.Join(testdata, "captured.json"))
	if err != nil {
		t.Fatal(err)
	}
	var held struct {
		Cases []struct {
			Path   string `json:"path"`
			Status int    `json:"status"`
			Body   string `json:"body"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(raw, &held); err != nil {
		t.Fatal(err)
	}
	answers := map[string]struct {
		status int
		body   []byte
	}{}
	for _, one := range held.Cases {
		body, err := os.ReadFile(filepath.Join(testdata, one.Body))
		if err != nil {
			t.Fatal(err)
		}
		answers[one.Path] = struct {
			status int
			body   []byte
		}{one.Status, body}
	}
	provider := httptest.NewServer(http.HandlerFunc(
		func(writer http.ResponseWriter, request *http.Request) {
			asked := request.URL.Path
			if request.URL.RawQuery != "" {
				asked += "?" + request.URL.Query().Encode()
			}
			answer, found := answers[asked]
			writer.Header().Set("Content-Type", "application/json")
			if !found {
				writer.WriteHeader(http.StatusTeapot)
				_, _ = writer.Write([]byte(`{"type":"api_error","message":"not captured",` +
					`"request_id":"none"}`))
				return
			}
			writer.WriteHeader(answer.status)
			_, _ = writer.Write(answer.body)
		}))
	t.Cleanup(provider.Close)
	client, err := jupiter.New(jupiter.Options{URL: provider.URL, Gap: -1})
	if err != nil {
		t.Fatal(err)
	}
	return client
}

// A cycle that finds the same markets again publishes nothing. This is the ordinary case — one
// cycle every few minutes, for ever — and it is why a subscribed phone is not woken by polling.
func TestASecondCycleWithTheSameMarketsPublishesNothing(t *testing.T) {
	provider := &source{events: listing(event("POLY-606422",
		market("POLY-2589813", noon.Add(72*time.Hour))))}
	held := start(t, provider)

	first := held.pass()
	if first.Created != 1 {
		t.Fatalf("created %d", first.Created)
	}
	before := held.signals()[0].Signal

	held.clock = noon.Add(5 * time.Minute)
	second := held.pass()
	switch {
	case second.Created != 0 || second.Updated != 0:
		t.Fatalf("a second cycle created %d and updated %d", second.Created, second.Updated)
	case second.Matched != 1:
		t.Fatalf("matched %d", second.Matched)
	case held.woken != 1:
		t.Fatalf("the drainer was woken %d times; the second cycle changed nothing", held.woken)
	}
	after := held.signals()[0].Signal
	switch {
	case after.Revision != before.Revision:
		t.Fatalf("the revision moved from %d to %d", before.Revision, after.Revision)
	case !after.UpdatedAt.Equal(before.UpdatedAt):
		t.Fatalf("the update time moved to %s", after.UpdatedAt)
	case after.Fingerprint != before.Fingerprint:
		t.Fatal("the document changed although the market did not")
	}
	// The row still moved: what the provider says is recorded either way.
	if !held.tracked()[0].Market.LastSeenAt.Equal(held.clock) {
		t.Fatalf("last seen %s", held.tracked()[0].Market.LastSeenAt)
	}
}

// A restart is not an event either. Everything is derived from the source and the file, so the
// cycle after a restart reaches the same conclusions and publishes nothing new.
func TestARestartPublishesNothingNew(t *testing.T) {
	provider := &source{events: listing(event("POLY-606422",
		market("POLY-2589813", noon.Add(72*time.Hour))))}
	held := start(t, provider)
	held.pass()
	before := held.signals()[0].Signal

	if err := held.documents.Close(); err != nil {
		t.Fatal(err)
	}
	held.minted = 5 // a restart mints different identities; it must not need to
	held.clock = noon.Add(time.Hour)
	held.open()

	cycle := held.pass()
	if cycle.Created != 0 || cycle.Updated != 0 || cycle.Cancelled != 0 {
		t.Fatalf("a restart created %d, updated %d and withdrew %d", cycle.Created, cycle.Updated,
			cycle.Cancelled)
	}
	if cycle.Number != 2 {
		t.Fatalf("the cycle number restarted at %d", cycle.Number)
	}
	after := held.signals()
	if len(after) != 1 || after[0].Signal.Revision != before.Revision ||
		after[0].Signal.ProposalID != before.ProposalID {
		t.Fatalf("after a restart the signals are %+v", after)
	}
}

// A market that moves moves the document. A postponed close time is the case that matters: the
// expiry is part of what a phone was told, so it is a revision and every subscriber re-reads it.
func TestAPostponedMarketMovesTheRevision(t *testing.T) {
	closes := noon.Add(72 * time.Hour)
	one := market("POLY-2589813", closes)
	provider := &source{events: func(query jupiter.Query) (jupiter.Page, error) {
		if query.Start > 0 {
			return jupiter.Page{}, nil
		}
		return jupiter.Page{Events: []jupiter.Event{event("POLY-606422", one)}}, nil
	}}
	held := start(t, provider)
	held.pass()

	one.CloseTime = closes.Add(24 * time.Hour).Unix()
	held.clock = noon.Add(10 * time.Minute)
	cycle := held.pass()
	if cycle.Updated != 1 || cycle.Created != 0 {
		t.Fatalf("updated %d, created %d", cycle.Updated, cycle.Created)
	}
	signal := held.signals()[0].Signal
	switch {
	case signal.Revision != 2:
		t.Fatalf("revision %d", signal.Revision)
	case !signal.ExpiresAt.Equal(closes.Add(24 * time.Hour)):
		t.Fatalf("expiry %s", signal.ExpiresAt)
	case !signal.UpdatedAt.Equal(held.clock):
		t.Fatalf("updated at %s", signal.UpdatedAt)
	case signal.ProposalID != held.tracked()[0].Market.ProposalID:
		t.Fatal("an update changed the proposal's identity")
	}
	if held.woken != 2 {
		t.Fatalf("the drainer was woken %d times", held.woken)
	}
}

// A market that stops appearing in the listing is asked about, and an answer that it is still open
// ends nothing. **Absence is not closure**: a market can leave a filter without leaving the
// provider.
func TestAMarketThatMerelyLeftTheFilterKeepsItsProposal(t *testing.T) {
	one := market("POLY-2589813", noon.Add(72*time.Hour))
	listed := true
	provider := &source{
		events: func(query jupiter.Query) (jupiter.Page, error) {
			if query.Start > 0 || !listed {
				return jupiter.Page{}, nil
			}
			return jupiter.Page{Events: []jupiter.Event{event("POLY-606422", one)}}, nil
		},
		market: func(string) (jupiter.Market, error) { return one, nil },
	}
	held := start(t, provider)
	held.pass()

	listed = false
	held.clock = noon.Add(10 * time.Minute)
	cycle := held.pass()
	switch {
	case cycle.Checked != 1:
		t.Fatalf("%d markets were asked about directly", cycle.Checked)
	case cycle.Cancelled != 0:
		t.Fatal("a market that is still open was withdrawn because a listing did not carry it")
	case cycle.Outcome != discovery.OK:
		t.Fatalf("outcome %q", cycle.Outcome)
	}
	record := held.signals()[0]
	if record.Signal.Status != signals.Open || record.Signal.Revision != 1 {
		t.Fatalf("the signal is %s at revision %d", record.Signal.Status, record.Signal.Revision)
	}
	if !held.tracked()[0].Market.LastCheckedAt.Equal(held.clock) {
		t.Fatalf("last checked %s", held.tracked()[0].Market.LastCheckedAt)
	}
	if held.woken != 1 {
		t.Fatalf("the drainer was woken %d times", held.woken)
	}
}

// Every way the source can end a market, and the one way it cannot. The last case is the important
// one: a provider that cannot be reached withdraws nothing, because the markets have not changed —
// this template merely cannot see them.
func TestOnlyTheSourceEndsAProposal(t *testing.T) {
	yes := true
	for _, one := range []struct {
		name      string
		answer    func(string) (jupiter.Market, error)
		withdrawn bool
		state     string
		outcome   string
	}{
		{
			name: "the market has closed",
			answer: func(id string) (jupiter.Market, error) {
				held := market(id, noon.Add(72*time.Hour))
				held.Status = jupiter.Closed
				return held, nil
			},
			withdrawn: true, state: "closed", outcome: discovery.OK,
		},
		{
			name: "the market was cancelled",
			answer: func(id string) (jupiter.Market, error) {
				held := market(id, noon.Add(72*time.Hour))
				held.Status = jupiter.Cancelled
				return held, nil
			},
			withdrawn: true, state: "cancelled", outcome: discovery.OK,
		},
		{
			name: "the market has settled",
			answer: func(id string) (jupiter.Market, error) {
				held := market(id, noon.Add(72*time.Hour))
				held.Result = "yes"
				return held, nil
			},
			withdrawn: true, state: "resolved", outcome: discovery.OK,
		},
		{
			name: "the provider has no such market any more",
			answer: func(string) (jupiter.Market, error) {
				return jupiter.Market{}, &jupiter.Fault{
					Problem: jupiter.NoSuchMarket, Status: 404, Code: "market_not_found",
					Detail: "Market not found",
				}
			},
			withdrawn: true, state: "gone", outcome: discovery.OK,
		},
		{
			name: "a Forecast side that can no longer be traded",
			answer: func(id string) (jupiter.Market, error) {
				held := market(id, noon.Add(72*time.Hour))
				held.Tradable, held.Lifecycle = &yes, "resolving"
				return held, nil
			},
			withdrawn: true, state: "resolving", outcome: discovery.OK,
		},
		{
			name: "the provider cannot be reached",
			answer: func(string) (jupiter.Market, error) {
				return jupiter.Market{}, &jupiter.Fault{
					Problem: jupiter.Unreachable, Temporary: true,
					Detail: "connection refused",
				}
			},
			withdrawn: false, state: "open", outcome: discovery.Partial,
		},
		{
			name: "the provider is rate limiting this template",
			answer: func(string) (jupiter.Market, error) {
				return jupiter.Market{}, &jupiter.Fault{
					Problem: jupiter.RateLimited, Temporary: true, Status: 429,
				}
			},
			withdrawn: false, state: "open", outcome: discovery.Partial,
		},
		{
			name: "the provider refuses this template's key",
			answer: func(string) (jupiter.Market, error) {
				return jupiter.Market{}, &jupiter.Fault{
					Problem: jupiter.Refused, Status: 401, Code: "invalid_api_key",
				}
			},
			withdrawn: false, state: "open", outcome: discovery.Partial,
		},
	} {
		t.Run(one.name, func(t *testing.T) {
			listed := true
			provider := &source{
				events: func(query jupiter.Query) (jupiter.Page, error) {
					if query.Start > 0 || !listed {
						return jupiter.Page{}, nil
					}
					return jupiter.Page{Events: []jupiter.Event{event("POLY-606422",
						market("POLY-2589813", noon.Add(72*time.Hour)))}}, nil
				},
				market: one.answer,
			}
			held := start(t, provider)
			held.pass()

			listed = false
			held.clock = noon.Add(10 * time.Minute)
			cycle := held.pass()
			record := held.signals()[0]
			switch {
			case cycle.Outcome != one.outcome:
				t.Fatalf("outcome %q, expected %q", cycle.Outcome, one.outcome)
			case (cycle.Cancelled == 1) != one.withdrawn:
				t.Fatalf("%d withdrawn, expected %v", cycle.Cancelled, one.withdrawn)
			case (record.Signal.Status == signals.Cancelled) != one.withdrawn:
				t.Fatalf("the signal is %s", record.Signal.Status)
			case held.tracked()[0].Market.State != one.state:
				t.Fatalf("state %q, expected %q", held.tracked()[0].Market.State, one.state)
			}
			if one.withdrawn && record.Signal.Revision != 2 {
				t.Fatalf("a withdrawal is a revision: %d", record.Signal.Revision)
			}
			if !one.withdrawn && record.Signal.Revision != 1 {
				t.Fatalf("revision %d", record.Signal.Revision)
			}
			// A second cycle does not withdraw it again, whichever happened.
			held.clock = noon.Add(20 * time.Minute)
			again := held.pass()
			if again.Cancelled != 0 {
				t.Fatalf("a second cycle withdrew %d", again.Cancelled)
			}
		})
	}
}

// A market that closes and then re-opens — a postponed game, which the provider's own rules text
// describes — comes back as a new proposal. A withdrawal is final for every phone that read it, so
// the identity cannot be reused.
func TestAReopenedMarketComesBackAsANewProposal(t *testing.T) {
	one := market("POLY-2589813", noon.Add(72*time.Hour))
	listed := true
	provider := &source{
		events: func(query jupiter.Query) (jupiter.Page, error) {
			if query.Start > 0 || !listed {
				return jupiter.Page{}, nil
			}
			return jupiter.Page{Events: []jupiter.Event{event("POLY-606422", one)}}, nil
		},
		market: func(id string) (jupiter.Market, error) {
			closed := one
			closed.Status = jupiter.Closed
			return closed, nil
		},
	}
	held := start(t, provider)
	held.pass()
	first := held.signals()[0].Signal

	// Closed.
	listed = false
	held.clock = noon.Add(10 * time.Minute)
	if cycle := held.pass(); cycle.Cancelled != 1 {
		t.Fatalf("withdrew %d", cycle.Cancelled)
	}

	// And listed again, open.
	listed = true
	held.clock = noon.Add(20 * time.Minute)
	cycle := held.pass()
	if cycle.Created != 1 {
		t.Fatalf("a re-opened market created %d proposals", cycle.Created)
	}
	tracked := held.tracked()
	if len(tracked) != 1 || tracked[0].Market.Generation != 2 {
		t.Fatalf("the row is %+v", tracked[0].Market)
	}
	if tracked[0].Market.ProposalID == first.ProposalID {
		t.Fatal("the withdrawn proposal was revived")
	}
	if !tracked[0].Market.FirstSeenAt.Equal(held.clock) {
		t.Fatalf("a new proposal's clock starts when it is minted: %s",
			tracked[0].Market.FirstSeenAt)
	}
	all := held.signals()
	if len(all) != 2 {
		t.Fatalf("%d signals", len(all))
	}
}

// A listing that fails before anything is read is a failed cycle, and it changes nothing at all.
func TestAListingThatFailsChangesNothing(t *testing.T) {
	provider := &source{events: listing(event("POLY-606422",
		market("POLY-2589813", noon.Add(72*time.Hour))))}
	held := start(t, provider)
	held.pass()
	before := held.signals()[0].Signal

	provider.events = func(jupiter.Query) (jupiter.Page, error) {
		return jupiter.Page{}, &jupiter.Fault{
			Problem: jupiter.Unreachable, Temporary: true, Detail: "connection refused",
		}
	}
	// Nothing to ask about either, so the outage is the whole cycle.
	provider.market = func(string) (jupiter.Market, error) {
		return jupiter.Market{}, &jupiter.Fault{Problem: jupiter.Unreachable, Temporary: true}
	}
	held.clock = noon.Add(10 * time.Minute)
	cycle := held.pass()
	switch {
	case cycle.Outcome != discovery.Failed:
		t.Fatalf("outcome %q", cycle.Outcome)
	case cycle.Problem != string(jupiter.Unreachable):
		t.Fatalf("problem %q", cycle.Problem)
	case cycle.Detail == "":
		t.Fatal("the cycle does not say what happened")
	case cycle.Created != 0 || cycle.Updated != 0 || cycle.Cancelled != 0:
		t.Fatalf("an outage changed something: %+v", cycle)
	}
	after := held.signals()[0].Signal
	if after.Revision != before.Revision || after.Status != signals.Open {
		t.Fatalf("the signal is %s at revision %d", after.Status, after.Revision)
	}
	// And the cycle is on the record, so an operator can see it without reading the log.
	held2, err := held.documents.Cycle(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if held2.Outcome != discovery.Failed || held2.Number != 2 {
		t.Fatalf("the stored cycle is %+v", held2)
	}
	if held2.Working() {
		t.Fatal("a cycle that read nothing reads as working")
	}
}

// A walk that fails half way keeps what it read and says so. The markets from the first page are
// published; the cycle is partial, and the next one starts again from the beginning.
func TestAWalkThatFailsHalfWayKeepsWhatItRead(t *testing.T) {
	provider := &source{events: func(query jupiter.Query) (jupiter.Page, error) {
		if query.Start == 0 {
			return jupiter.Page{
				Events:  []jupiter.Event{event("POLY-606422", market("POLY-1", noon.Add(72*time.Hour)))},
				HasNext: true,
			}, nil
		}
		return jupiter.Page{}, &jupiter.Fault{
			Problem: jupiter.RateLimited, Temporary: true, Status: 429, Code: "rate_limited",
		}
	}}
	held := start(t, provider)

	cycle := held.pass()
	switch {
	case cycle.Outcome != discovery.Partial:
		t.Fatalf("outcome %q", cycle.Outcome)
	case cycle.Pages != 1:
		t.Fatalf("%d pages", cycle.Pages)
	case cycle.Created != 1:
		t.Fatalf("created %d: what was read is published", cycle.Created)
	case cycle.Problem != string(jupiter.RateLimited):
		t.Fatalf("problem %q", cycle.Problem)
	}
	if !cycle.Working() {
		t.Fatal("a partial cycle published something and reads as not working")
	}
}

// An empty listing is not an error and not a closure. It is what a filter that matches nothing
// looks like, and what the end of a walk looks like.
func TestAnEmptyListingIsNotAnError(t *testing.T) {
	provider := &source{events: func(jupiter.Query) (jupiter.Page, error) {
		return jupiter.Page{Events: []jupiter.Event{}}, nil
	}}
	held := start(t, provider)

	cycle := held.pass()
	switch {
	case cycle.Outcome != discovery.OK:
		t.Fatalf("outcome %q", cycle.Outcome)
	case cycle.Pages != 1:
		t.Fatalf("%d pages: a page with nothing on it ends the walk", cycle.Pages)
	case cycle.Considered != 0 || cycle.Matched != 0 || cycle.Created != 0:
		t.Fatalf("%+v", cycle)
	case held.woken != 0:
		t.Fatal("the drainer was woken by a cycle that changed nothing")
	}
	if len(held.signals()) != 0 {
		t.Fatal("something was published from an empty listing")
	}
}

// The ceiling is how many proposals this template will hold at once, and the ones it takes are the
// ones closing soonest — the ones a subscriber has the least time to act on.
func TestTheCeilingBoundsWhatIsHeld(t *testing.T) {
	soon := market("POLY-SOON", noon.Add(2*time.Hour))
	later := market("POLY-LATER", noon.Add(48*time.Hour))
	latest := market("POLY-LATEST", noon.Add(96*time.Hour))
	provider := &source{
		events: listing(event("POLY-606422", latest, soon, later)),
		market: func(id string) (jupiter.Market, error) {
			return market(id, noon.Add(96*time.Hour)), nil
		},
	}
	held := start(t, provider, func(h *harness) { h.filters.MostOpen = 2 })

	cycle := held.pass()
	switch {
	case cycle.Matched != 3:
		t.Fatalf("matched %d", cycle.Matched)
	case cycle.Created != 2:
		t.Fatalf("created %d", cycle.Created)
	case cycle.Skipped != 1:
		t.Fatalf("skipped %d", cycle.Skipped)
	}
	tracked := held.tracked()
	if len(tracked) != 2 {
		t.Fatalf("%d markets", len(tracked))
	}
	if tracked[0].Market.MarketID != "POLY-SOON" || tracked[1].Market.MarketID != "POLY-LATER" {
		t.Fatalf("it kept %s and %s", tracked[0].Market.MarketID, tracked[1].Market.MarketID)
	}
	// And a market that was skipped is not tracked at all, so a later cycle with room publishes it.
	held.filters.MostOpen = 3
	held.open()
	held.clock = noon.Add(10 * time.Minute)
	if cycle := held.pass(); cycle.Created != 1 {
		t.Fatalf("with room, the skipped market created %d proposals", cycle.Created)
	}
}

// The direct checks are bounded and in a round robin: a template tracking more markets than it will
// ask about in one cycle works through them rather than asking about the same one for ever.
func TestTheDirectChecksAreBoundedAndTakeTurns(t *testing.T) {
	first := market("POLY-1", noon.Add(72*time.Hour))
	second := market("POLY-2", noon.Add(73*time.Hour))
	listed := true
	provider := &source{
		events: func(query jupiter.Query) (jupiter.Page, error) {
			if query.Start > 0 || !listed {
				return jupiter.Page{}, nil
			}
			return jupiter.Page{Events: []jupiter.Event{event("POLY-606422", first, second)}}, nil
		},
		market: func(id string) (jupiter.Market, error) {
			return market(id, noon.Add(72*time.Hour)), nil
		},
	}
	held := start(t, provider, func(h *harness) { h.filters.MostChecks = 1 })
	held.pass()

	listed = false
	held.clock = noon.Add(10 * time.Minute)
	if cycle := held.pass(); cycle.Checked != 1 {
		t.Fatalf("%d checks", cycle.Checked)
	}
	held.clock = noon.Add(20 * time.Minute)
	if cycle := held.pass(); cycle.Checked != 1 {
		t.Fatalf("%d checks", cycle.Checked)
	}
	if len(provider.asked) != 2 || provider.asked[0] == provider.asked[1] {
		t.Fatalf("it asked about %v", provider.asked)
	}
}

// An expired signal is left alone. Nothing new is executed from one whatever the provider says, so
// asking about it would be a call spent on a market nobody can act on.
func TestAnExpiredSignalIsNotAskedAbout(t *testing.T) {
	one := market("POLY-2589813", noon.Add(2*time.Hour))
	listed := true
	provider := &source{
		events: func(query jupiter.Query) (jupiter.Page, error) {
			if query.Start > 0 || !listed {
				return jupiter.Page{}, nil
			}
			return jupiter.Page{Events: []jupiter.Event{event("POLY-606422", one)}}, nil
		},
		market: func(id string) (jupiter.Market, error) { return one, nil },
	}
	held := start(t, provider)
	held.pass()

	listed = false
	held.clock = noon.Add(3 * time.Hour) // past the market's own close time
	cycle := held.pass()
	if cycle.Checked != 0 {
		t.Fatalf("%d checks about an expired signal", cycle.Checked)
	}
	if len(provider.asked) != 0 {
		t.Fatalf("it asked about %v", provider.asked)
	}
}

// A market the kind refuses is counted and named, not published and not silently dropped. The
// commonest cause is a deployment naming a deposit token the provider does not take.
func TestAMarketTheKindRefusesIsCountedAndNamed(t *testing.T) {
	provider := &source{events: listing(event("POLY-606422",
		market("POLY-2589813", noon.Add(72*time.Hour))))}
	held := start(t, provider, func(h *harness) {
		h.deposit = discovery.Deposit{Mint: signals.WrappedSOL}
	})

	cycle := held.pass()
	switch {
	case cycle.Matched != 1:
		t.Fatalf("matched %d", cycle.Matched)
	case cycle.Created != 0:
		t.Fatal("a market the kind refuses was published")
	case cycle.Reasons[discovery.Unpublishable] != 1:
		t.Fatalf("the reasons are %v", cycle.Reasons)
	}
	if len(held.signals()) != 0 {
		t.Fatal("something was stored")
	}
	if !strings.Contains(held.log.String(), "unsupported_mint") {
		t.Fatalf("the log does not name the term that stopped it:\n%s", held.log.String())
	}
}

// Two cycles cannot run at once: they would read the same listing and write the same rows, so the
// second is refused rather than queued. It is what the API's own poll endpoint answers 409 from.
func TestTwoCyclesCannotRunAtOnce(t *testing.T) {
	inside := make(chan struct{})
	release := make(chan struct{})
	// The first call blocks; the calls after it do not, because the third cycle below has to
	// finish.
	var first atomic.Bool
	provider := &source{events: func(query jupiter.Query) (jupiter.Page, error) {
		if query.Start == 0 && first.CompareAndSwap(false, true) {
			inside <- struct{}{}
			<-release
		}
		return jupiter.Page{}, nil
	}}
	held := start(t, provider)

	finished := make(chan error, 1)
	go func() {
		_, err := held.reconciler.Pass(context.Background())
		finished <- err
	}()
	<-inside
	if _, err := held.reconciler.Pass(context.Background()); !errors.Is(err, discovery.ErrBusy) {
		t.Fatalf("a second cycle answered %v", err)
	}
	close(release)
	if err := <-finished; err != nil {
		t.Fatal(err)
	}
	// And once it is done, another one runs.
	if _, err := held.reconciler.Pass(context.Background()); err != nil {
		t.Fatal(err)
	}
}

// The filters are the provider's own parameters where they have to be, and this template's
// everywhere else. This is what the walk actually asks for.
func TestTheProvidersOwnParametersAreSent(t *testing.T) {
	provider := &source{events: func(jupiter.Query) (jupiter.Page, error) {
		return jupiter.Page{}, nil
	}}
	held := start(t, provider, func(h *harness) {
		h.filters.Categories = []string{"crypto", "economics"}
		h.filters.Filter = "trending"
		h.filters.PageSize = 25
	})
	held.pass()

	if len(provider.listed) != 2 {
		t.Fatalf("%d listing calls: one walk per bucket", len(provider.listed))
	}
	for at, query := range provider.listed {
		switch {
		case query.Source != "polymarket":
			t.Fatalf("venue %q", query.Source)
		case query.Filter != "trending":
			t.Fatalf("filter %q", query.Filter)
		case query.Start != 0 || query.End != 25:
			t.Fatalf("the slice asked for is %d..%d", query.Start, query.End)
		}
		if expected := []string{"crypto", "economics"}[at]; query.Category != expected {
			t.Fatalf("bucket %q, expected %q", query.Category, expected)
		}
	}
}

// One market seen twice in one walk is one proposal. A listing that shifts between two calls can
// put the same event on both pages, and that must not publish it twice.
func TestAMarketSeenTwiceInOneWalkIsOneProposal(t *testing.T) {
	one := market("POLY-2589813", noon.Add(72*time.Hour))
	provider := &source{events: func(jupiter.Query) (jupiter.Page, error) {
		// Every page carries the same event, and says there is another page.
		return jupiter.Page{Events: []jupiter.Event{event("POLY-606422", one)}, HasNext: true}, nil
	}}
	held := start(t, provider)

	cycle := held.pass()
	switch {
	case cycle.Pages != 2:
		t.Fatalf("%d pages", cycle.Pages)
	case cycle.Considered != 1:
		t.Fatalf("%d markets considered: the second sighting is the same market", cycle.Considered)
	case cycle.Created != 1:
		t.Fatalf("created %d", cycle.Created)
	}
	if len(held.signals()) != 1 {
		t.Fatalf("%d signals", len(held.signals()))
	}
}
