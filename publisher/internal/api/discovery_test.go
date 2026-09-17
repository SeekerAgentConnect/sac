package api

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sort"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/discovery"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/jupiter"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/signals"
)

// The Prediction template's API: the same endpoints for reading, none for writing, and two of its
// own for what discovery is doing (SEE-96).

// provider is a listing a test scripts.
type provider struct {
	events func(jupiter.Query) (jupiter.Page, error)
	market func(string) (jupiter.Market, error)
}

func (p *provider) Events(_ context.Context, query jupiter.Query) (jupiter.Page, error) {
	if p.events == nil {
		return jupiter.Page{}, nil
	}
	return p.events(query)
}

func (p *provider) Market(_ context.Context, id string) (jupiter.Market, error) {
	if p.market == nil {
		return jupiter.Market{}, &jupiter.Fault{Problem: jupiter.NoSuchMarket}
	}
	return p.market(id)
}

// oneMarket is a listing with a single open market in it.
func oneMarket() func(jupiter.Query) (jupiter.Page, error) {
	return func(query jupiter.Query) (jupiter.Page, error) {
		if query.Start > 0 {
			return jupiter.Page{}, nil
		}
		return jupiter.Page{Events: []jupiter.Event{{
			EventID:   "POLY-606422",
			Title:     "Fed Decision in October?",
			Category:  "economics",
			Tags:      []string{"economics", "fed-rates"},
			Active:    true,
			SourceURL: "https://jup.ag/prediction/fed-decision-in-october",
			Markets: []jupiter.Market{{
				MarketID:  "POLY-2589813",
				EventID:   "POLY-606422",
				Provider:  "polymarket",
				Title:     "25 bps increase",
				Status:    jupiter.Open,
				CloseTime: now.Add(72 * time.Hour).Unix(),
			}},
		}}}, nil
	}
}

// predicting is a whole Prediction template: the real store, the real reconciler over a scripted
// provider, the real drainer, and the API in front of all of it.
func predicting(t *testing.T, fake *fakeGateway, listing *provider) *template {
	t.Helper()
	return startWith(t, fake, signals.Prediction{}, func(held *template, plan *Plan) {
		reconciler := discovery.New(discovery.Plan{
			Documents: held.store,
			Source:    listing,
			Kind:      signals.Prediction{},
			Filters: discovery.Filters{
				Source:       "polymarket",
				Categories:   []string{"economics"},
				Keywords:     []string{"fed"},
				LeastCloseIn: time.Hour,
				MostCloseIn:  30 * 24 * time.Hour,
				Lifetime:     7 * 24 * time.Hour,
				PageSize:     25,
				MostPages:    2,
				MostOpen:     10,
				MostChecks:   5,
				Every:        5 * time.Minute,
			},
			Deposit: discovery.Deposit{Mint: signals.USDCMint, Symbol: "USDC"},
			Log:     slog.New(slog.NewTextHandler(held.log, nil)),
			Now:     func() time.Time { return held.nowValue },
			NewID:   held.nextID,
			Wake:    held.drainer.Wake,
		})
		plan.Authorship = ByDiscovery
		plan.Markets = held.store
		plan.Cycles = reconciler
	})
}

// Nobody may write a signal here, and the refusal says why and where to look instead. It is 403
// rather than a missing route: the caller is authorized, the endpoint is there, and what it asked
// for is not something anybody may do on this template.
func TestNoCallerMayWriteAPredictionSignal(t *testing.T) {
	held := predicting(t, &fakeGateway{}, &provider{events: oneMarket()})

	for _, one := range []struct {
		method string
		path   string
	}{
		{http.MethodPost, "/v1/signals"},
		{http.MethodPut, "/v1/signals/" + proposal},
		{http.MethodPost, "/v1/signals/" + proposal + "/cancel"},
	} {
		answered := held.call(one.method, one.path, map[string]any{
			"expires_at": now.Add(time.Hour).Format(time.RFC3339),
			"terms":      map[string]string{signals.MarketID: "POLY-1"},
		}, "Idempotency-Key", "key-1")
		switch {
		case answered.status != http.StatusForbidden:
			t.Fatalf("%s %s answered %d: %s", one.method, one.path, answered.status, answered.raw)
		case answered.problem() != "written_by_discovery":
			t.Fatalf("problem %q", answered.problem())
		}
		detail, _ := answered.body["detail"].(string)
		if !strings.Contains(detail, "/v1/discovery") {
			t.Fatalf("the refusal does not say where to look: %s", detail)
		}
	}
	// Nothing was stored by any of that.
	listed := held.call(http.MethodGet, "/v1/signals", nil)
	signals, _ := listed.body["signals"].([]any)
	if listed.status != http.StatusOK || len(signals) != 0 {
		t.Fatalf("%d signals: %s", len(signals), listed.raw)
	}
}

// Reading is the same API as the other template's, and retrying a refused publication is still an
// operator's to ask for: it is about the gateway rather than about the statement.
func TestReadingAndRetryingAreTheSameOnBothTemplates(t *testing.T) {
	held := predicting(t, &fakeGateway{}, &provider{events: oneMarket()})

	for _, path := range []string{"/v1/status", "/v1/manifest", "/v1/signals"} {
		if answered := held.call(http.MethodGet, path, nil); answered.status != http.StatusOK {
			t.Fatalf("GET %s answered %d: %s", path, answered.status, answered.raw)
		}
	}
	// A retry names a signal, and there is none yet.
	answered := held.call(http.MethodPost, "/v1/signals/"+proposal+"/retry", nil)
	if answered.status != http.StatusNotFound || answered.problem() != "no_such_signal" {
		t.Fatalf("%d %s", answered.status, answered.raw)
	}
	// And the credential is still the whole of the grant, on the endpoints of its own too.
	for _, path := range []string{"/v1/discovery", "/v1/status"} {
		answered := held.call(http.MethodGet, path, nil, "Authorization", "")
		if answered.status != http.StatusUnauthorized {
			t.Fatalf("GET %s without a token answered %d", path, answered.status)
		}
	}
	if answered := held.call(http.MethodPost, "/v1/discovery/poll", nil,
		"Authorization", "Bearer wrong"); answered.status != http.StatusUnauthorized {
		t.Fatalf("a poll with the wrong token answered %d", answered.status)
	}
}

// A poll runs a cycle and publishes what it produced, so an operator who has just changed a filter
// sees the result rather than "pending" on everything.
func TestAPollRunsACycleAndPublishesWhatItFound(t *testing.T) {
	fake := &fakeGateway{}
	held := predicting(t, fake, &provider{events: oneMarket()})

	answered := held.call(http.MethodPost, "/v1/discovery/poll", nil)
	if answered.status != http.StatusOK {
		t.Fatalf("%d %s", answered.status, answered.raw)
	}
	cycle, _ := answered.body["cycle"].(map[string]any)
	switch {
	case cycle["outcome"] != discovery.OK:
		t.Fatalf("outcome %v: %s", cycle["outcome"], answered.raw)
	case cycle["created"] != float64(1):
		t.Fatalf("created %v", cycle["created"])
	case cycle["number"] != float64(1):
		t.Fatalf("cycle %v", cycle["number"])
	case answered.body["pending"] != float64(0):
		t.Fatalf("pending %v: a poll publishes what it found", answered.body["pending"])
	}

	// The gateway holds it, as the document a phone will read.
	documents := fake.documents()
	if len(documents) != 1 {
		t.Fatalf("%d documents reached the gateway", len(documents))
	}
	document := documents[0]
	switch {
	case document.GetOperation() != "prediction":
		t.Fatalf("operation %q", document.GetOperation())
	case document.GetPluginId() != "jupiter.prediction":
		t.Fatalf("plugin %q", document.GetPluginId())
	case document.GetServerId() != server:
		t.Fatalf("server %q", document.GetServerId())
	}
	terms := map[string]string{}
	for _, value := range document.GetValues() {
		terms[value.GetKey()] = value.GetText()
	}
	if terms[signals.MarketID] != "POLY-2589813" || terms[signals.DepositMint] != usdc {
		t.Fatalf("the document's terms are %v", terms)
	}

	// And the signal reads as published through the API.
	listed := held.call(http.MethodGet, "/v1/signals", nil)
	held.expectPublished(listed)

	// A second poll finds the same market and publishes nothing.
	again := held.call(http.MethodPost, "/v1/discovery/poll", nil)
	cycle, _ = again.body["cycle"].(map[string]any)
	if cycle["created"] != float64(0) || cycle["updated"] != float64(0) {
		t.Fatalf("a second poll changed something: %s", again.raw)
	}
	if len(fake.documents()) != 1 {
		t.Fatalf("%d documents after two polls", len(fake.documents()))
	}
}

// expectPublished is the one assertion the poll test needs from a listing.
func (h *template) expectPublished(listed answer) {
	h.t.Helper()
	records, _ := listed.body["signals"].([]any)
	if len(records) != 1 {
		h.t.Fatalf("%d signals: %s", len(records), listed.raw)
	}
	record, _ := records[0].(map[string]any)
	publication, _ := record["publication"].(map[string]any)
	if publication["state"] != "published" {
		h.t.Fatalf("the signal is %v: %s", publication["state"], listed.raw)
	}
}

// What this template is looking for and what it has found, which is the answer to "why is my feed
// empty" without reading a compose file or a log.
func TestTheDiscoveryAnswerSaysWhatItLooksForAndWhatItFound(t *testing.T) {
	held := predicting(t, &fakeGateway{}, &provider{events: oneMarket()})
	held.call(http.MethodPost, "/v1/discovery/poll", nil)

	answered := held.call(http.MethodGet, "/v1/discovery", nil)
	if answered.status != http.StatusOK {
		t.Fatalf("%d %s", answered.status, answered.raw)
	}
	filters, _ := answered.body["filters"].(map[string]any)
	switch {
	case filters["source"] != "polymarket":
		t.Fatalf("filters %v", filters)
	case filters["closes_between"] != "1h0m0s and 720h0m0s":
		t.Fatalf("window %v", filters["closes_between"])
	case answered.body["working"] != true:
		t.Fatalf("working %v", answered.body["working"])
	}
	keywords, _ := filters["keywords"].([]any)
	if len(keywords) != 1 || keywords[0] != "fed" {
		t.Fatalf("keywords %v", filters["keywords"])
	}

	markets, _ := answered.body["markets"].([]any)
	if len(markets) != 1 {
		t.Fatalf("%d markets: %s", len(markets), answered.raw)
	}
	market, _ := markets[0].(map[string]any)
	for _, name := range []string{
		"provider", "market_id", "event_id", "title", "state", "generation", "source_url",
		"signal", "publication", "close_at", "first_seen_at", "last_seen_at",
	} {
		if _, present := market[name]; !present {
			t.Fatalf("the market does not say %q: %v", name, market)
		}
	}
	switch {
	case market["market_id"] != "POLY-2589813":
		t.Fatalf("market %v", market["market_id"])
	case market["state"] != "open":
		t.Fatalf("state %v", market["state"])
	case market["source_url"] != "https://jup.ag/prediction/fed-decision-in-october":
		t.Fatalf("source %v", market["source_url"])
	}
	// It was seen in a listing and never asked about directly, so there is no such instant to
	// report rather than a zero one.
	if _, present := market["last_checked_at"]; present {
		t.Fatalf("last_checked_at is %v although nothing has been asked about directly",
			market["last_checked_at"])
	}
	// The link is here and not in the document: what a phone gets is the identifiers, which is
	// what lets it look the market up for itself.
	signal, _ := market["signal"].(map[string]any)
	encoded, err := json.Marshal(signal)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(encoded), "jup.ag") || strings.Contains(string(encoded), "http") {
		t.Fatalf("the published signal carries a URL:\n%s", encoded)
	}
	terms, _ := signal["terms"].(map[string]any)
	published := []string{}
	for name := range terms {
		published = append(published, name)
	}
	sort.Strings(published)
	if strings.Join(published, ",") != "deposit_decimals,deposit_mint,deposit_symbol,event_id,"+
		"least_deposit,market_id,provider" {
		t.Fatalf("the published terms are %v", published)
	}
}

// Nothing about a subscriber is in any of it, which is the same rule the store's schema has and
// this is the answer a person actually reads.
func TestNothingAboutASubscriberIsInTheDiscoveryAnswer(t *testing.T) {
	held := predicting(t, &fakeGateway{}, &provider{events: oneMarket()})
	held.call(http.MethodPost, "/v1/discovery/poll", nil)

	for _, path := range []string{"/v1/discovery", "/v1/status", "/v1/signals"} {
		answered := held.call(http.MethodGet, path, nil)
		lowered := strings.ToLower(answered.raw)
		for _, word := range []string{
			"wallet", "subscriber", "fcm", "\"decision\"", "\"signature\"", "\"amount\"",
		} {
			if strings.Contains(lowered, word) {
				t.Fatalf("GET %s says %q:\n%s", path, word, answered.raw)
			}
		}
	}
}

// The status says which template this is, and that nobody may write to it.
func TestTheStatusSaysItIsNotWritable(t *testing.T) {
	held := predicting(t, &fakeGateway{}, &provider{events: oneMarket()})
	held.call(http.MethodPost, "/v1/discovery/poll", nil)

	answered := held.call(http.MethodGet, "/v1/status", nil)
	switch {
	case answered.body["writable"] != false:
		t.Fatalf("writable %v", answered.body["writable"])
	case answered.body["operation"] != "prediction":
		t.Fatalf("operation %v", answered.body["operation"])
	case answered.body["plugin_id"] != "jupiter.prediction":
		t.Fatalf("plugin %v", answered.body["plugin_id"])
	}
	held2, _ := answered.body["discovery"].(map[string]any)
	if held2["markets"] != float64(1) || held2["working"] != true {
		t.Fatalf("discovery %v", held2)
	}
	cycle, _ := held2["last_cycle"].(map[string]any)
	if cycle["number"] != float64(1) || cycle["outcome"] != discovery.OK {
		t.Fatalf("the last cycle is %v", cycle)
	}
}

// A poll while a cycle is running is refused rather than queued: two cycles would read the same
// listing and write the same rows.
func TestAPollWhileACycleIsRunningIsRefused(t *testing.T) {
	inside := make(chan struct{})
	release := make(chan struct{})
	var first atomic.Bool
	listing := &provider{events: func(query jupiter.Query) (jupiter.Page, error) {
		if query.Start == 0 && first.CompareAndSwap(false, true) {
			inside <- struct{}{}
			<-release
		}
		return jupiter.Page{}, nil
	}}
	held := predicting(t, &fakeGateway{}, listing)

	started := make(chan answer, 1)
	go func() { started <- held.call(http.MethodPost, "/v1/discovery/poll", nil) }()
	<-inside
	answered := held.call(http.MethodPost, "/v1/discovery/poll", nil)
	if answered.status != http.StatusConflict || answered.problem() != "busy" {
		t.Fatalf("%d %s", answered.status, answered.raw)
	}
	close(release)
	if held2 := <-started; held2.status != http.StatusOK {
		t.Fatalf("the first poll answered %d: %s", held2.status, held2.raw)
	}
}

// And the other way round: the CopyTrading template has no discovery endpoints at all, because it
// discovers nothing. They are absent rather than refused.
func TestTheCopyTradingTemplateHasNoDiscoveryEndpoints(t *testing.T) {
	held := start(t, &fakeGateway{})
	for _, one := range []struct {
		method string
		path   string
	}{
		{http.MethodGet, "/v1/discovery"},
		{http.MethodPost, "/v1/discovery/poll"},
	} {
		answered := held.call(one.method, one.path, nil)
		if answered.status != http.StatusNotFound || answered.problem() != "no_such_route" {
			t.Fatalf("%s %s answered %d: %s", one.method, one.path, answered.status, answered.raw)
		}
	}
	// Its own three writing endpoints still work, which is what makes the absence above a
	// difference between the templates rather than a change to both.
	answered := held.create("key-1", swapStatement())
	if answered.status != http.StatusCreated {
		t.Fatalf("%d %s", answered.status, answered.raw)
	}
	if answered.body["writable"] != nil {
		t.Fatal("a create answers with a `writable` flag")
	}
	status := held.call(http.MethodGet, "/v1/status", nil)
	if status.body["writable"] != true {
		t.Fatalf("writable %v", status.body["writable"])
	}
}

// The provider's key reaches one request header and nothing else: not a log line, not an answer,
// not a document. It is the runtime version of the rule, with the real client against a provider
// that checks the header arrived.
//
// This is the third credential in the module and the one easiest to leak, because unlike the other
// two it is sent to somebody else's service — which means it is in a URL's neighbourhood, in a
// transport error's text, and in whatever a client library decides to log
// (internal/jupiter, docs/security.md).
func TestTheProvidersKeyNeverReachesAnAnswerOrALogLine(t *testing.T) {
	const key = "jup_secret_0123456789abcdefghijkl"
	presented := make(chan string, 4)
	upstream := httptest.NewServer(http.HandlerFunc(
		func(writer http.ResponseWriter, request *http.Request) {
			presented <- request.Header.Get("x-api-key")
			writer.Header().Set("Content-Type", "application/json")
			_, _ = writer.Write([]byte(`{"data":[{"eventId":"POLY-606422","isActive":true,` +
				`"isLive":false,"category":"economics","subcategory":"",` +
				`"metadata":{"title":"Fed Decision in October?","slug":"fed-decision"},` +
				`"markets":[{"marketId":"POLY-2589813","eventId":"POLY-606422",` +
				`"provider":"polymarket","title":"25 bps increase","status":"open",` +
				`"result":null,"openTime":0,"closeTime":` +
				strconv.FormatInt(now.Add(72*time.Hour).Unix(), 10) +
				`}]}],"pagination":{"start":0,"end":1,"hasNext":false}}`))
		}))
	defer upstream.Close()

	client, err := jupiter.New(jupiter.Options{URL: upstream.URL, Key: key, Gap: -1})
	if err != nil {
		t.Fatal(err)
	}
	held := startWith(t, &fakeGateway{}, signals.Prediction{}, func(held *template, plan *Plan) {
		reconciler := discovery.New(discovery.Plan{
			Documents: held.store,
			Source:    client,
			Kind:      signals.Prediction{},
			Filters: discovery.Filters{
				Source: "polymarket", Categories: []string{"economics"},
				LeastCloseIn: time.Hour, MostCloseIn: 30 * 24 * time.Hour,
				Lifetime: 7 * 24 * time.Hour, PageSize: 25, MostPages: 1, MostOpen: 10,
				MostChecks: 5, Every: 5 * time.Minute,
			},
			Deposit: discovery.Deposit{Mint: signals.USDCMint, Symbol: "USDC"},
			Log:     slog.New(slog.NewTextHandler(held.log, nil)),
			Now:     func() time.Time { return held.nowValue },
			NewID:   held.nextID,
			Wake:    held.drainer.Wake,
		})
		plan.Authorship = ByDiscovery
		plan.Markets = held.store
		plan.Cycles = reconciler
	})

	polled := held.call(http.MethodPost, "/v1/discovery/poll", nil)
	if polled.status != http.StatusOK {
		t.Fatalf("%d %s", polled.status, polled.raw)
	}
	if got := <-presented; got != key {
		t.Fatalf("the provider was presented %q", got)
	}
	// It published what it found, so the calls below are about a template that really used the key.
	cycle, _ := polled.body["cycle"].(map[string]any)
	if cycle["created"] != float64(1) {
		t.Fatalf("created %v: %s", cycle["created"], polled.raw)
	}

	answers := []string{polled.raw}
	for _, path := range []string{"/v1/discovery", "/v1/status", "/v1/signals", "/v1/manifest"} {
		answers = append(answers, held.call(http.MethodGet, path, nil).raw)
	}
	for _, answered := range answers {
		if strings.Contains(answered, key) || strings.Contains(answered, "x-api-key") {
			t.Fatalf("an answer carries the provider's key:\n%s", answered)
		}
	}
	if strings.Contains(held.log.String(), key) {
		t.Fatalf("the provider's key is in the log:\n%s", held.log.String())
	}
	if !strings.Contains(held.log.String(), "a market is published") {
		t.Fatalf("nothing was logged at all, so the absence above is not an absence:\n%s",
			held.log.String())
	}
}
