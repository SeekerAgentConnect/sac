package jupiter

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// The provider, as it actually answered. `testdata/captured.json` records seven real answers and
// the request that produced each of them; this serves them back on loopback, matching a request
// against the recorded one parameter for parameter.
//
// That exactness is the point. If this client's query ever changes — a parameter added, one
// renamed, one dropped — no fixture matches it and these tests fail, rather than passing against an
// answer to a different question (scripts/capture-jupiter.mjs --events).
type captured struct {
	Note       string `json:"note"`
	Endpoint   string `json:"endpoint"`
	CapturedAt string `json:"capturedAt"`
	Cases      []struct {
		Name        string `json:"name"`
		Description string `json:"description"`
		Path        string `json:"path"`
		Status      int    `json:"status"`
		Body        string `json:"body"`
	} `json:"cases"`
}

type provider struct {
	url string
	// Every path asked for, in order, so a test can say how many calls something made.
	asked []string
	mutex sync.Mutex
}

func (p *provider) calls() []string {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	return append([]string(nil), p.asked...)
}

func fixtures(t *testing.T) captured {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("testdata", "captured.json"))
	if err != nil {
		t.Fatal(err)
	}
	var held captured
	if err := json.Unmarshal(raw, &held); err != nil {
		t.Fatal(err)
	}
	if len(held.Cases) < 7 {
		t.Fatalf("only %d captured answers; testdata is not what this test is written against",
			len(held.Cases))
	}
	return held
}

// case returns one captured answer by name.
func answer(t *testing.T, name string) (string, int, []byte) {
	t.Helper()
	for _, one := range fixtures(t).Cases {
		if one.Name == name {
			body, err := os.ReadFile(filepath.Join("testdata", one.Body))
			if err != nil {
				t.Fatal(err)
			}
			return one.Path, one.Status, body
		}
	}
	t.Fatalf("there is no captured answer called %q", name)
	return "", 0, nil
}

func served(t *testing.T) *provider {
	t.Helper()
	held := fixtures(t)
	bodies := map[string]struct {
		status int
		body   []byte
	}{}
	for _, one := range held.Cases {
		body, err := os.ReadFile(filepath.Join("testdata", one.Body))
		if err != nil {
			t.Fatal(err)
		}
		bodies[one.Path] = struct {
			status int
			body   []byte
		}{one.Status, body}
	}
	answers := &provider{}
	server := httptest.NewServer(http.HandlerFunc(
		func(writer http.ResponseWriter, request *http.Request) {
			asked := request.URL.Path
			if request.URL.RawQuery != "" {
				// The recorded form: parameters sorted, which is what url.Values.Encode writes.
				asked += "?" + request.URL.Query().Encode()
			}
			answers.mutex.Lock()
			answers.asked = append(answers.asked, asked)
			answers.mutex.Unlock()
			held, found := bodies[asked]
			if !found {
				writer.Header().Set("Content-Type", "application/json")
				writer.WriteHeader(http.StatusTeapot)
				_, _ = writer.Write([]byte(`{"type":"api_error","message":"nothing was captured ` +
					`for this request","request_id":"none"}`))
				return
			}
			writer.Header().Set("Content-Type", "application/json")
			writer.WriteHeader(held.status)
			_, _ = writer.Write(held.body)
		}))
	t.Cleanup(server.Close)
	answers.url = server.URL
	return answers
}

// A client with no pacing, for the tests that are not about pacing.
func client(t *testing.T, url string) *Client {
	t.Helper()
	made, err := New(Options{URL: url, Gap: -1})
	if err != nil {
		t.Fatal(err)
	}
	return made
}

// The first page of a real listing, read the way this template reads it: the fields a filter is
// written against, the markets inside the event, and the event's own link for the operator.
func TestAListingIsReadAsTheProviderSentIt(t *testing.T) {
	answers := served(t)
	page, err := client(t, answers.url).Events(context.Background(), Query{
		Source:   "polymarket",
		Category: "economics",
		Start:    0,
		End:      1,
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Events) != 1 {
		t.Fatalf("%d events", len(page.Events))
	}
	event := page.Events[0]
	switch {
	case event.EventID != "POLY-606422":
		t.Fatalf("event %q", event.EventID)
	case event.Title != "Fed Decision in October?":
		t.Fatalf("title %q", event.Title)
	case event.Category != "economics":
		t.Fatalf("category %q", event.Category)
	case !event.Active:
		t.Fatal("the event is listed as active and was read as not")
	case event.Live:
		t.Fatal("the event has not begun and was read as live")
	case len(event.Tags) < 2:
		t.Fatalf("tags %v", event.Tags)
	case len(event.Markets) != 5:
		t.Fatalf("%d markets", len(event.Markets))
	}
	// The link is built from the provider's own slug, and it is for the operator: nothing published
	// carries it (docs/wiki/prediction-template.md).
	if !strings.HasPrefix(event.SourceURL, "https://jup.ag/prediction/") ||
		!strings.Contains(event.SourceURL, "fed-decision-in-october") {
		t.Fatalf("source %q", event.SourceURL)
	}
	market := event.Markets[0]
	switch {
	case market.MarketID != "POLY-2589813":
		t.Fatalf("market %q", market.MarketID)
	case market.EventID != "POLY-606422":
		t.Fatalf("the market does not name its event: %q", market.EventID)
	case market.Provider != "polymarket":
		t.Fatalf("provider %q", market.Provider)
	case market.Status != Open || market.Result != "":
		t.Fatalf("status %q, result %q", market.Status, market.Result)
	case market.CloseTime != 1793231940:
		t.Fatalf("close time %d", market.CloseTime)
	case !market.Tradeable():
		t.Fatal("an open, unresolved market was read as not tradeable")
	case market.State() != "open":
		t.Fatalf("state %q", market.State())
	}
	if !page.HasNext {
		t.Fatal("the page says there is more and was read as the last")
	}
}

// A walk of the listing: the next page, and then one past the end. An empty page is not an error and
// not a closure — it is how a walk finishes.
func TestAWalkEndsOnAPageWithNothingOnIt(t *testing.T) {
	answers := served(t)
	held := client(t, answers.url)
	second, err := held.Events(context.Background(),
		Query{Source: "polymarket", Category: "economics", Start: 1, End: 2})
	if err != nil {
		t.Fatal(err)
	}
	if len(second.Events) != 1 || second.Events[0].EventID == "POLY-606422" {
		t.Fatalf("the second page is %v", second.Events)
	}
	if !second.HasNext {
		t.Fatal("the second page says there is more")
	}
	past, err := held.Events(context.Background(),
		Query{Source: "polymarket", Start: 100000, End: 100002})
	if err != nil {
		t.Fatal(err)
	}
	if len(past.Events) != 0 {
		t.Fatalf("%d events past the end", len(past.Events))
	}
	if past.HasNext {
		t.Fatal("a page past the end says there is more, which would be a walk that never stops")
	}
}

// A market read directly, which is what a reconciliation does when a tracked market stops appearing
// in the listing. The closed one is the whole point: this is what a closure looks like on the wire.
func TestAMarketIsReadDirectly(t *testing.T) {
	answers := served(t)
	held := client(t, answers.url)

	open, err := held.Market(context.Background(), "POLY-2589813")
	if err != nil {
		t.Fatal(err)
	}
	if !open.Tradeable() || open.State() != "open" || open.Title == "" {
		t.Fatalf("the open market read as %+v", open)
	}

	closed, err := held.Market(context.Background(), "POLY-4052423")
	if err != nil {
		t.Fatal(err)
	}
	switch {
	case closed.Status != Closed:
		t.Fatalf("status %q", closed.Status)
	case closed.Result != "yes":
		t.Fatalf("result %q", closed.Result)
	case closed.Tradeable():
		t.Fatal("a closed, settled market was read as tradeable")
	case closed.State() != "resolved":
		t.Fatalf("state %q: a settled market is resolved, whatever its status says",
			closed.State())
	}
}

// Whether an order could be placed at all is the provider's own account of the market, and this is
// the one judgement this package makes about one. Jupiter Forecast's two extra fields are part of
// it: `tradable` false and a lifecycle past `open` both mean no.
func TestTradeableIsTheProvidersOwnAccount(t *testing.T) {
	no, yes := false, true
	for _, one := range []struct {
		name   string
		market Market
		ok     bool
		state  string
	}{
		{"open and unresolved", Market{Status: Open}, true, "open"},
		{"closed", Market{Status: Closed}, false, "closed"},
		{"cancelled", Market{Status: Cancelled}, false, "cancelled"},
		{"settled yes", Market{Status: Open, Result: "yes"}, false, "resolved"},
		{"settled no", Market{Status: Closed, Result: "no"}, false, "resolved"},
		{"a status the provider has not used before", Market{Status: "paused"}, false, "paused"},
		{"no status at all", Market{}, false, "unknown"},
		{"forecast, tradable", Market{Status: Open, Tradable: &yes, Lifecycle: "open"}, true,
			"open"},
		{"forecast, not tradable", Market{Status: Open, Tradable: &no, Lifecycle: "open"}, false,
			"open"},
		{"forecast, resolving", Market{Status: Open, Tradable: &yes, Lifecycle: "resolving"},
			false, "resolving"},
		{"forecast, settled", Market{Status: Open, Tradable: &yes, Lifecycle: "settled"}, false,
			"settled"},
	} {
		t.Run(one.name, func(t *testing.T) {
			if one.market.Tradeable() != one.ok {
				t.Fatalf("tradeable %v, expected %v", one.market.Tradeable(), one.ok)
			}
			if one.market.State() != one.state {
				t.Fatalf("state %q, expected %q", one.market.State(), one.state)
			}
		})
	}
}

// Every way the provider can fail, and whether asking again could answer differently. That last
// column is the one the reconciler acts on: a provider that is down must never withdraw anybody's
// proposal, because the market has not closed — this template merely cannot see it.
func TestEveryProviderFailureIsClassified(t *testing.T) {
	for _, one := range []struct {
		name      string
		handler   http.HandlerFunc
		problem   Problem
		temporary bool
		code      string
	}{
		{
			name:    "a parameter the provider refuses",
			handler: replay(t, "events-bad-parameter"),
			problem: Refused, temporary: false, code: "invalid_value",
		},
		{
			name:    "a market that does not exist",
			handler: replay(t, "market-missing"),
			problem: NoSuchMarket, temporary: false, code: "market_not_found",
		},
		{
			// Constructed, not captured: provoking a real rate limit on a shared keyless host is
			// rude. The body is the provider's own published error schema, with the `type` its
			// documentation lists for this case.
			name: "a rate limit",
			handler: func(writer http.ResponseWriter, _ *http.Request) {
				writer.Header().Set("Retry-After", "30")
				writer.WriteHeader(http.StatusTooManyRequests)
				_, _ = writer.Write([]byte(`{"type":"rate_limit_error","message":"Too many ` +
					`requests","code":"rate_limited","request_id":"7b1"}`))
			},
			problem: RateLimited, temporary: true, code: "rate_limited",
		},
		{
			name: "a rate limit with no body at all",
			handler: func(writer http.ResponseWriter, _ *http.Request) {
				writer.WriteHeader(http.StatusTooManyRequests)
			},
			problem: RateLimited, temporary: true,
		},
		{
			name: "the provider's own failure",
			handler: func(writer http.ResponseWriter, _ *http.Request) {
				writer.WriteHeader(http.StatusBadGateway)
				_, _ = writer.Write([]byte(`{"type":"api_error","message":"upstream","` +
					`request_id":"9c2"}`))
			},
			problem: Unreachable, temporary: true,
		},
		{
			name: "a key the provider does not accept",
			handler: func(writer http.ResponseWriter, _ *http.Request) {
				writer.WriteHeader(http.StatusUnauthorized)
				_, _ = writer.Write([]byte(`{"type":"authentication_error","message":"Invalid ` +
					`API key","code":"invalid_api_key","request_id":"1a4"}`))
			},
			problem: Refused, temporary: false, code: "invalid_api_key",
		},
		{
			name: "a key without permission for these endpoints",
			handler: func(writer http.ResponseWriter, _ *http.Request) {
				writer.WriteHeader(http.StatusForbidden)
				_, _ = writer.Write([]byte(`{"type":"permission_error","message":"API key does ` +
					`not have access to this endpoint","request_id":"2b5"}`))
			},
			problem: Refused, temporary: false,
		},
		{
			name: "an answer that is not JSON",
			handler: func(writer http.ResponseWriter, _ *http.Request) {
				writer.Header().Set("Content-Type", "text/html")
				_, _ = writer.Write([]byte("<html>a proxy's error page</html>"))
			},
			problem: Unusable, temporary: false,
		},
		{
			name: "an answer that is JSON but not a market",
			handler: func(writer http.ResponseWriter, _ *http.Request) {
				_, _ = writer.Write([]byte(`{"nothing":"here"}`))
			},
			problem: Unusable, temporary: false,
		},
		{
			name: "a redirect somewhere else",
			handler: func(writer http.ResponseWriter, request *http.Request) {
				http.Redirect(writer, request, "https://example.com/elsewhere",
					http.StatusFound)
			},
			problem: Unreachable, temporary: true,
		},
	} {
		t.Run(one.name, func(t *testing.T) {
			server := httptest.NewServer(one.handler)
			defer server.Close()
			_, err := client(t, server.URL).Market(context.Background(), "POLY-1")
			var fault *Fault
			if !errors.As(err, &fault) {
				t.Fatalf("%v is not a provider fault", err)
			}
			switch {
			case fault.Problem != one.problem:
				t.Fatalf("problem %q, expected %q (%v)", fault.Problem, one.problem, fault)
			case fault.Temporary != one.temporary:
				t.Fatalf("temporary %v, expected %v: a provider that is down must not withdraw "+
					"a proposal, and one that refuses must not be retried for ever",
					fault.Temporary, one.temporary)
			case one.code != "" && fault.Code != one.code:
				t.Fatalf("code %q, expected %q", fault.Code, one.code)
			}
			if fault.Error() == "" {
				t.Fatal("the fault says nothing")
			}
		})
	}
}

// A provider that is not there at all: the commonest failure, and one nothing can be concluded from
// about any market.
func TestAProviderThatIsNotThereIsTemporary(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(
		func(http.ResponseWriter, *http.Request) {}))
	url := server.URL
	server.Close()

	_, err := client(t, url).Events(context.Background(), Query{End: 1})
	var fault *Fault
	if !errors.As(err, &fault) {
		t.Fatalf("%v is not a provider fault", err)
	}
	if fault.Problem != Unreachable || !fault.Temporary {
		t.Fatalf("%v", fault)
	}
}

// The rate limit the provider asked for, when it asks in a header. The keyless host sends none,
// which is why the gap between calls is this template's own — this is for the keyed host and for
// whatever a proxy in between adds.
func TestTheProvidersOwnRetryAfterIsRead(t *testing.T) {
	for _, one := range []struct {
		header string
		after  time.Duration
	}{
		{"", 0},
		{"30", 30 * time.Second},
		{"soon", 0},
		{"-5", 0},
		{"0", 0},
		{"86400", time.Minute}, // never longer than a minute, whatever it says
	} {
		server := httptest.NewServer(http.HandlerFunc(
			func(writer http.ResponseWriter, _ *http.Request) {
				if one.header != "" {
					writer.Header().Set("Retry-After", one.header)
				}
				writer.WriteHeader(http.StatusTooManyRequests)
			}))
		_, err := client(t, server.URL).Market(context.Background(), "POLY-1")
		server.Close()
		var fault *Fault
		if !errors.As(err, &fault) {
			t.Fatalf("%q: %v", one.header, err)
		}
		if fault.After != one.after {
			t.Fatalf("Retry-After %q was read as %s, expected %s", one.header, fault.After,
				one.after)
		}
	}
}

// Calls are paced, and the pacing is in the client rather than in its callers: the way to exceed a
// provider's allowance is to have two places that each think they are the only one calling.
func TestCallsArePacedForTheKeylessAllowance(t *testing.T) {
	answers := served(t)
	clock := time.Date(2026, 9, 17, 21, 0, 0, 0, time.UTC)
	waited := []time.Duration{}
	held, err := New(Options{
		URL: answers.url,
		Gap: 2 * time.Second,
		Now: func() time.Time { return clock },
		Sleep: func(_ context.Context, wait time.Duration) error {
			waited = append(waited, wait)
			clock = clock.Add(wait)
			return nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	for range 3 {
		if _, err := held.Events(context.Background(),
			Query{Source: "polymarket", Category: "economics", Start: 0, End: 1}); err != nil {
			t.Fatal(err)
		}
	}
	// The first call goes at once; the two after it wait the gap.
	if len(waited) != 2 || waited[0] != 2*time.Second || waited[1] != 2*time.Second {
		t.Fatalf("waits: %v", waited)
	}
	if len(answers.calls()) != 3 {
		t.Fatalf("%d calls", len(answers.calls()))
	}
}

// Pacing gives up when the process is shutting down, rather than holding a shutdown open for a wait
// nobody is waiting for.
func TestPacingStopsWhenTheContextIsDone(t *testing.T) {
	answers := served(t)
	held, err := New(Options{URL: answers.url, Gap: time.Hour})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	if _, err := held.Events(ctx,
		Query{Source: "polymarket", Category: "economics", Start: 0, End: 1}); err != nil {
		t.Fatal(err)
	}
	cancel()
	_, err = held.Events(ctx, Query{Source: "polymarket", Category: "economics", Start: 0, End: 1})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("%v", err)
	}
}

// The provider's key goes in one header and nowhere else. It is not in a URL, not in a log line,
// and not in the text of a transport failure — which is where it would end up, because net/http
// puts the whole request URL in its error and a key is a query parameter on some deployments.
func TestTheProvidersKeyIsInOneHeaderAndNoMessage(t *testing.T) {
	const key = "jup_0123456789abcdefghijklmnopqrstuv"
	presented := make(chan string, 1)
	server := httptest.NewServer(http.HandlerFunc(
		func(writer http.ResponseWriter, request *http.Request) {
			presented <- request.Header.Get("x-api-key")
			if strings.Contains(request.URL.RawQuery, key) {
				t.Error("the key is in the query string")
			}
			_, _ = writer.Write([]byte(`{"marketId":"POLY-1","status":"open","result":null}`))
		}))
	defer server.Close()

	held, err := New(Options{URL: server.URL, Key: key, Gap: -1})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := held.Market(context.Background(), "POLY-1"); err != nil {
		t.Fatal(err)
	}
	if got := <-presented; got != key {
		t.Fatalf("the provider was presented %q", got)
	}

	// And now the same client against something that is not listening.
	closed := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	url := closed.URL
	closed.Close()
	gone, err := New(Options{URL: url, Key: key, Gap: -1})
	if err != nil {
		t.Fatal(err)
	}
	_, err = gone.Market(context.Background(), "POLY-1")
	if err == nil {
		t.Fatal("a closed port answered")
	}
	if strings.Contains(err.Error(), key) || strings.Contains(err.Error(), url) {
		t.Fatalf("a failed call quotes what it sent: %v", err)
	}
}

// An answer is bounded. Whatever answered, a template must not be made to hold an arbitrary amount
// of memory by it.
func TestAnAnswerIsBounded(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(
		func(writer http.ResponseWriter, _ *http.Request) {
			writer.Header().Set("Content-Type", "application/json")
			// Valid JSON for as long as it lasts, and longer than this package will read.
			_, _ = writer.Write([]byte(`{"marketId":"POLY-1","status":"open","filler":"`))
			_, _ = io.CopyN(writer, filler{}, MostBodyBytes)
			_, _ = writer.Write([]byte(`"}`))
		}))
	defer server.Close()

	_, err := client(t, server.URL).Market(context.Background(), "POLY-1")
	var fault *Fault
	if !errors.As(err, &fault) || fault.Problem != Unusable {
		t.Fatalf("%v: an answer past the bound is unusable, not a market", err)
	}
}

type filler struct{}

func (filler) Read(into []byte) (int, error) {
	for at := range into {
		into[at] = 'a'
	}
	return len(into), nil
}

// The sets this template offers an operator are the provider's own, and the evidence for each of
// them is in this repository: the category list came from the provider's own refusal, which is
// committed as a fixture.
func TestTheOfferedSetsAreTheProvidersOwn(t *testing.T) {
	_, status, body := answer(t, "events-bad-parameter")
	if status != http.StatusBadRequest {
		t.Fatalf("the captured refusal is HTTP %d", status)
	}
	var stated struct {
		Message string `json:"message"`
		Param   string `json:"param"`
	}
	if err := json.Unmarshal(body, &stated); err != nil {
		t.Fatal(err)
	}
	if stated.Param != "category" {
		t.Fatalf("the captured refusal is about %q", stated.Param)
	}
	for _, category := range Categories {
		if !strings.Contains(stated.Message, `"`+category+`"`) {
			t.Fatalf("the provider's own refusal does not list %q: %s", category, stated.Message)
		}
	}
	// The other two sets are the published schema's, which is not committed here, so they are
	// pinned as themselves: a change to either is a change somebody has to make deliberately.
	if len(Sources) != 3 || !Sources["polymarket"] || !Sources["kalshi"] || !Sources["bisonfi"] {
		t.Fatalf("the venues are %v", Sources)
	}
	if strings.Join(Filters, ",") != "new,live,trending,upcoming" {
		t.Fatalf("the filters are %v", Filters)
	}
	if MostPageSize != 100 {
		t.Fatalf("the page ceiling is %d; the provider answers \"Range cannot exceed 100 items\"",
			MostPageSize)
	}
}

// The live provider, opt-in. It is the one test that can notice the provider changing under this
// template — a beta API, by its own documentation — so it checks the fields discovery depends on
// rather than any particular market:
//
//	SEEKERVAULT_JUPITER=1 go test ./internal/jupiter/ -run Live -v
func TestTheLiveProviderStillAnswersThisWay(t *testing.T) {
	if os.Getenv("SEEKERVAULT_JUPITER") == "" {
		t.Skip("set SEEKERVAULT_JUPITER=1 to read the real provider")
	}
	held, err := New(Options{})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	page, err := held.Events(ctx, Query{Source: "polymarket", Category: "economics", End: 2})
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Events) == 0 {
		t.Fatal("the live listing is empty")
	}
	var candidate Market
	for _, event := range page.Events {
		switch {
		case event.EventID == "":
			t.Fatalf("an event with no ID: %+v", event)
		case event.Category == "":
			t.Fatalf("%s has no category", event.EventID)
		case event.Title == "":
			t.Fatalf("%s has no title", event.EventID)
		case len(event.Markets) == 0:
			t.Fatalf("%s carries no markets although includeMarkets was asked for", event.EventID)
		}
		for _, market := range event.Markets {
			if market.MarketID == "" || market.Status == "" {
				t.Fatalf("a market with no ID or status: %+v", market)
			}
			if market.Tradeable() && candidate.MarketID == "" {
				candidate = market
			}
		}
	}
	if candidate.MarketID == "" {
		t.Skip("no open market in the live listing right now, so there is nothing to read back")
	}
	read, err := held.Market(ctx, candidate.MarketID)
	if err != nil {
		t.Fatal(err)
	}
	switch {
	case read.MarketID != candidate.MarketID:
		t.Fatalf("asked about %s and was told about %s", candidate.MarketID, read.MarketID)
	case read.EventID == "":
		t.Fatal("a market read directly does not name its event")
	case read.CloseTime == 0:
		t.Fatalf("%s has no close time, which is what a signal's expiry is", read.MarketID)
	}
	t.Logf("live: %s %q closes %s, tradeable %v", read.MarketID, read.Title,
		time.Unix(read.CloseTime, 0).UTC().Format(time.RFC3339), read.Tradeable())

	// And a market that does not exist is still answered the way this template reads.
	_, err = held.Market(ctx, "SEEKER-NO-SUCH-MARKET")
	var fault *Fault
	if !errors.As(err, &fault) || fault.Problem != NoSuchMarket {
		t.Fatalf("a market that does not exist answered %v", err)
	}
}

// replay serves one captured answer whatever is asked for, for the tests that are about the answer
// rather than about the request.
func replay(t *testing.T, name string) http.HandlerFunc {
	t.Helper()
	_, status, body := answer(t, name)
	return func(writer http.ResponseWriter, _ *http.Request) {
		writer.Header().Set("Content-Type", "application/json")
		writer.WriteHeader(status)
		_, _ = writer.Write(body)
	}
}
