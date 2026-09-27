// Package jupiter is the prediction provider, as this template reads it
// (docs/integrations/jupiter.md#prediction-discovery).
//
// Two calls, and both of them read public information: which events and markets exist, and what one
// market currently is. Nothing here is about anybody — no wallet, no order, no position, no history
// — because a publisher has nobody to ask about. The endpoints that would answer those exist in the
// provider's API and are deliberately not in this file: the phone places an order, from the owner's
// own device, and this template never learns that anything was placed at all
// (docs/wiki/prediction-template.md).
//
// # The provider's host is in this file and nowhere else
//
// [Endpoint] is the one address compiled into this module, and `boundary_test.go` fails if it
// appears in any other file. It is the same rule the phone applies to the same constant
// (`JUPITER_ENDPOINT`, `StageBoundaryTest`), and the reason is the same: a template is written
// against this provider's answers, so its host is a fact about the code rather than a deployment's
// choice — while the *gateway* a template publishes to is whoever runs one, and is configuration
// with no default at all.
//
// # It polls, because there is nothing to subscribe to
//
// The provider's prediction API is REST: its published schema has no stream, no webhook and no
// subscription of any kind, and the only "live" things in it are a `live` listing filter and score
// endpoints for sports events (checked against the published OpenAPI document on 2026-09-17). So
// discovery is bounded polling, with a minimum gap between calls that keeps a template inside the
// keyless allowance, and a rate limit is treated as an answer rather than as a reason to try harder.
//
// # The API is in beta
//
// The provider says so itself: "The Prediction Market API is currently in beta and subject to
// breaking changes." What that means here is that a field this package cannot read is a market this
// template skips, with a line in the log naming it — never a crash, and never a proposal built from
// half an answer.
package jupiter

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Endpoint is the provider's keyless host, which serves the prediction endpoints without a
// credential. An operator with an API key points [Options.URL] at the keyed host instead
// (docs/integrations/jupiter.md).
const Endpoint = "https://lite-api.jup.ag"

// The provider's own limits, as its documentation states them.
const (
	// MostPageSize is the most events one listing call may ask for: the provider answers
	// "Range cannot exceed 100 items" above it.
	MostPageSize = 100
	// MostBodyBytes is the most of an answer this package will read. A listing of live sports
	// events with every market is hundreds of kilobytes, so the bound is generous — but it is a
	// bound, because a template must not be made to hold an arbitrary amount of memory by whatever
	// answered.
	MostBodyBytes = 8 << 20
)

// Sources are the provider's own data providers, which its `provider` parameter names. Its
// prediction markets are other venues' markets, aggregated: a listing asks for one venue at a time.
var Sources = map[string]bool{"polymarket": true, "kalshi": true, "bisonfi": true}

// Categories are the buckets the provider sorts events into, as its own listing refuses anything
// else: this set is what `GET /events` answered for `category=nonsense` on 2026-09-17, and it is
// wider than the published schema's, which lists eight.
var Categories = []string{
	"all", "crypto", "sports", "politics", "esports", "culture", "economics", "tech", "finance",
	"climate & science", "weather", "mentions",
}

// Filters are the provider's named listing filters, with the meanings its documentation gives:
// `new` for events created in the last 24 hours, `live` for events that have begun, `trending` for
// events with recent trade activity, and `upcoming` for events that have not begun yet.
var Filters = []string{"new", "live", "trending", "upcoming"}

// The market statuses the provider uses.
const (
	Open      = "open"
	Closed    = "closed"
	Cancelled = "cancelled"
)

// Client is the provider, over one HTTP client.
type Client struct {
	endpoint string
	// The provider's API key, when a deployment has one. It goes in one header and nowhere else:
	// never in a log line, never in a document, never in an answer this template gives
	// (docs/security.md).
	key    string
	http   *http.Client
	now    func() time.Time
	sleep  func(ctx context.Context, wait time.Duration) error
	gap    time.Duration
	mutex  sync.Mutex
	latest time.Time
}

// Options is what a [Client] needs. Everything but the endpoint has a default.
type Options struct {
	// The provider's origin. Empty means [Endpoint].
	URL string
	// The provider's API key, or empty for the keyless host.
	Key string
	// How long one call may take. Zero means ten seconds.
	Timeout time.Duration
	// The least time between two calls. Zero means [DefaultGap].
	Gap time.Duration
	// Injected for the tests, which must not wait.
	Now   func() time.Time
	Sleep func(ctx context.Context, wait time.Duration) error
}

// DefaultGap is the least time between two calls: a little over two seconds, which is inside the
// keyless allowance of one call every two (0.5 requests a second, in a 60-second window).
//
// It is enforced in the client rather than in the caller so that every path out of this template is
// paced, including the direct market reads a reconciliation makes — the way to exceed a provider's
// limit is to have two places that each think they are the only one calling.
const DefaultGap = 2100 * time.Millisecond

// New builds a client.
func New(options Options) (*Client, error) {
	endpoint := strings.TrimSuffix(options.URL, "/")
	if endpoint == "" {
		endpoint = Endpoint
	}
	if _, err := url.Parse(endpoint); err != nil {
		return nil, fmt.Errorf("the provider's URL is not a URL: %w", err)
	}
	timeout := options.Timeout
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	gap := options.Gap
	if gap == 0 {
		gap = DefaultGap
	}
	now := options.Now
	if now == nil {
		now = time.Now
	}
	sleep := options.Sleep
	if sleep == nil {
		sleep = wait
	}
	return &Client{
		endpoint: endpoint,
		key:      options.Key,
		now:      now,
		sleep:    sleep,
		gap:      gap,
		http: &http.Client{
			Timeout: timeout,
			// A provider that answered with a redirect is not answering: this client asks two
			// documented endpoints and follows nobody anywhere.
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return errors.New("the provider answered with a redirect")
			},
		},
	}, nil
}

func wait(ctx context.Context, duration time.Duration) error {
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

// Query is one listing call: which venue, which bucket, which named filter, and which slice of the
// result.
//
// Start and End are the provider's own pagination — an offset and an exclusive bound, not a page
// number — and the answer says whether there is more (Page.HasNext).
type Query struct {
	Source   string
	Category string
	Filter   string
	Start    int
	End      int
}

// Page is one answer to a listing call.
type Page struct {
	Events  []Event
	Start   int
	End     int
	HasNext bool
}

// Event is an event as the provider states it, with the fields a filter can be written against and
// nothing else. Volume, images, scores and rules documents are deliberately not read: nothing this
// template publishes depends on them, and a field it does not read is a field it cannot leak.
type Event struct {
	EventID     string
	Title       string
	Category    string
	Subcategory string
	Tags        []string
	// Whether the provider lists the event as active, and whether it has begun.
	Active bool
	Live   bool
	// The link to the event on the provider's own site, when its answer carries a slug. It is kept
	// for the template's operator and is never published (docs/wiki/prediction-template.md).
	SourceURL string
	Markets   []Market
}

// Market is a market as the provider states it. Every field is the provider's claim, and the phone
// reads them all again for itself before anything is prepared: what is published is which market,
// not what the market currently is.
type Market struct {
	MarketID string
	EventID  string
	Provider string
	Title    string
	// `open`, `closed` or `cancelled`, as the provider spells it.
	Status string
	// The resolution: empty while unresolved, otherwise `yes` or `no`.
	Result string
	// Epoch seconds. Zero when the provider gives none.
	OpenTime  int64
	CloseTime int64
	// Jupiter Forecast's two extra fields (`provider=bisonfi`): whether this side can be traded
	// right now, and where it is in its lifecycle. Absent for every other venue, which is why the
	// first is a pointer — "the provider did not say" and "the provider said no" are different
	// things to filter on.
	Tradable  *bool
	Lifecycle string
}

// Tradeable is whether an order could be placed on this market at all, by the provider's own
// account of it. It is the phone's `PredictionMarket.open` with Forecast's two fields added, and it
// is the one judgement this package makes about a market: a template that published a market the
// provider will not take an order for would be publishing a proposal that fails on every phone.
func (m Market) Tradeable() bool {
	switch {
	case m.Status != Open || m.Result != "":
		return false
	case m.Tradable != nil && !*m.Tradable:
		return false
	case m.Lifecycle != "" && m.Lifecycle != Open:
		return false
	default:
		return true
	}
}

// State folds the provider's two fields into the one word this template records: what it says
// about a market it is tracking, and what it tells its operator.
func (m Market) State() string {
	switch {
	case m.Result != "":
		return "resolved"
	case m.Status == "":
		return "unknown"
	case m.Status == Open && m.Lifecycle != "" && m.Lifecycle != Open:
		return m.Lifecycle
	default:
		return m.Status
	}
}

// Problem is why the provider produced nothing usable. The codes are the phone's own
// (`PredictionProblem`), because an operator reading this template's log and an owner reading their
// phone should be told the same thing in the same words.
type Problem string

const (
	// The provider could not be reached at all.
	Unreachable Problem = "provider_unreachable"
	// The provider said this template is asking too often.
	RateLimited Problem = "provider_rate_limited"
	// The provider has no such market. A tracked market can stop existing.
	NoSuchMarket Problem = "no_such_market"
	// The provider refused the call and said so with a status this template can report: a bad
	// parameter, a key it does not accept, a key without permission for these endpoints.
	Refused Problem = "provider_refused"
	// The answer arrived and could not be read.
	Unusable Problem = "provider_unusable"
)

// Fault is one failed call: which of the five it was, what the provider said about it, and whether
// asking again could answer differently.
//
// That last field is the one the reconciler acts on. A provider that is down must not withdraw
// anybody's proposal — the market has not closed, the template merely cannot see it — so
// "temporary" and "settled" are decided here, once, rather than guessed at by each caller
// (internal/discovery).
type Fault struct {
	Problem Problem
	// The HTTP status, when there was one.
	Status int
	// The provider's own error code and message, when its answer carried them. The message is the
	// provider's words and is quoted as such; it never contains anything this template sent.
	Code   string
	Detail string
	// Whether a later attempt could succeed.
	Temporary bool
	// How long the provider asked to be left alone for, when it said so in a header. Nothing here
	// trusts it beyond a minute: a header is the provider's request, and the backoff between
	// cycles is this template's own (internal/discovery).
	After time.Duration
}

func (f *Fault) Error() string {
	parts := []string{string(f.Problem)}
	if f.Status != 0 {
		parts = append(parts, "HTTP "+strconv.Itoa(f.Status))
	}
	if f.Code != "" {
		parts = append(parts, f.Code)
	}
	if f.Detail != "" {
		parts = append(parts, f.Detail)
	}
	return strings.Join(parts, ": ")
}

// Events reads one page of the provider's listing.
func (c *Client) Events(ctx context.Context, query Query) (Page, error) {
	values := url.Values{}
	if query.Source != "" {
		values.Set("provider", query.Source)
	}
	if query.Category != "" {
		values.Set("category", query.Category)
	}
	if query.Filter != "" {
		values.Set("filter", query.Filter)
	}
	// Always sent, even at zero: it is the offset of the slice being asked for, and a walk that
	// left it out of the first call would be asking a different question with every page.
	values.Set("start", strconv.Itoa(max(query.Start, 0)))
	if query.End > 0 {
		values.Set("end", strconv.Itoa(query.End))
	}
	// The markets are the point: a listing without them would name events this template cannot
	// propose anything from, and would cost a call each to fill in.
	values.Set("includeMarkets", "true")

	var answer struct {
		Data       []event `json:"data"`
		Pagination *struct {
			Start   int  `json:"start"`
			End     int  `json:"end"`
			HasNext bool `json:"hasNext"`
		} `json:"pagination"`
	}
	if err := c.call(ctx, "/prediction/v1/events?"+values.Encode(), &answer); err != nil {
		return Page{}, err
	}
	page := Page{Events: make([]Event, 0, len(answer.Data)), Start: query.Start, End: query.End}
	for _, one := range answer.Data {
		page.Events = append(page.Events, one.read())
	}
	if answer.Pagination != nil {
		page.Start, page.End = answer.Pagination.Start, answer.Pagination.End
		page.HasNext = answer.Pagination.HasNext
	}
	return page, nil
}

// Market reads one market by the provider's own identifier. It is what a reconciliation asks when a
// tracked market has stopped appearing in the listing: absence from a filtered page is not a
// closure, and only this answer is (internal/discovery).
func (c *Client) Market(ctx context.Context, id string) (Market, error) {
	var answer market
	if err := c.call(ctx, "/prediction/v1/markets/"+url.PathEscape(id), &answer); err != nil {
		return Market{}, err
	}
	read := answer.read()
	if read.MarketID == "" {
		// An answer with no identifier in it is not a market, whatever its status was.
		return Market{}, &Fault{Problem: Unusable, Status: http.StatusOK,
			Detail: "the answer carried no marketId"}
	}
	return read, nil
}

// call makes one paced request and decodes the answer.
func (c *Client) call(ctx context.Context, path string, into any) error {
	if err := c.pace(ctx); err != nil {
		return err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, c.endpoint+path, nil)
	if err != nil {
		return &Fault{Problem: Refused, Detail: err.Error()}
	}
	request.Header.Set("Accept", "application/json")
	if c.key != "" {
		request.Header.Set("x-api-key", c.key)
	}
	response, err := c.http.Do(request)
	if err != nil {
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			return err
		}
		// A refused connection, a name that does not resolve, a TLS failure, a timeout: the
		// provider is not reachable from here, which is a thing that stops being true.
		return &Fault{Problem: Unreachable, Temporary: true, Detail: reason(err)}
	}
	// Closed last, drained first: the drain is what lets the connection be reused when an answer
	// was larger than this template will read.
	defer func() { _ = response.Body.Close() }()
	defer func() { _, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 1<<16)) }()

	body, err := io.ReadAll(io.LimitReader(response.Body, MostBodyBytes))
	if err != nil {
		return &Fault{Problem: Unreachable, Temporary: true, Status: response.StatusCode,
			Detail: reason(err)}
	}
	if response.StatusCode != http.StatusOK {
		return refusal(response, body)
	}
	if err := json.Unmarshal(body, into); err != nil {
		return &Fault{Problem: Unusable, Status: response.StatusCode,
			Detail: "the answer could not be read as this endpoint's JSON: " + err.Error()}
	}
	return nil
}

// pace holds a call back until the configured gap since the last one has passed.
func (c *Client) pace(ctx context.Context) error {
	c.mutex.Lock()
	earliest := c.latest.Add(c.gap)
	now := c.now()
	// The stamp is taken before the wait, so two callers arriving together are spaced from each
	// other rather than both from the last call.
	if earliest.After(now) {
		c.latest = earliest
	} else {
		c.latest = now
	}
	c.mutex.Unlock()
	if !earliest.After(now) {
		return nil
	}
	return c.sleep(ctx, earliest.Sub(now))
}

// refusal is a non-200 answer as a [Fault]. The provider's own error shape is read when it is
// there, because its code says which of these a 400 actually was.
func refusal(response *http.Response, body []byte) error {
	var stated struct {
		Type    string `json:"type"`
		Message string `json:"message"`
		Code    string `json:"code"`
		Param   string `json:"param"`
	}
	_ = json.Unmarshal(body, &stated)
	detail := strings.TrimSpace(stated.Message)
	if stated.Param != "" {
		detail += " (" + stated.Param + ")"
	}
	if detail == "" {
		detail = strings.TrimSpace(string(body[:min(len(body), 200)]))
	}
	fault := &Fault{Status: response.StatusCode, Code: stated.Code, Detail: detail,
		After: retryAfter(response)}
	switch {
	case response.StatusCode == http.StatusTooManyRequests || stated.Type == "rate_limit_error":
		fault.Problem, fault.Temporary = RateLimited, true
	case response.StatusCode == http.StatusNotFound:
		// The listing endpoint cannot answer 404; a market that is gone can.
		fault.Problem = NoSuchMarket
	case response.StatusCode >= 500:
		fault.Problem, fault.Temporary = Unreachable, true
	case response.StatusCode == http.StatusRequestTimeout:
		fault.Problem, fault.Temporary = Unreachable, true
	default:
		// 400, 401, 403: the call itself is wrong, and it will be wrong next time too. An operator
		// has to change something — a parameter, a key, that key's permissions.
		fault.Problem = Refused
	}
	return fault
}

// retryAfter reads the header, when there is one. `lite-api.jup.ag` sends none — it returns no
// rate-limit headers at all, which is why the gap between calls is the template's own — so this is
// for the keyed host and for whatever a proxy in between adds.
func retryAfter(response *http.Response) time.Duration {
	seconds, err := strconv.Atoi(strings.TrimSpace(response.Header.Get("Retry-After")))
	if err != nil || seconds <= 0 {
		return 0
	}
	return min(time.Duration(seconds)*time.Second, time.Minute)
}

// reason is a transport error with the URL taken out. net/http puts the whole request URL in its
// error, and an API key is a query parameter on some deployments: a log line about a failed call
// must not be where one ends up.
func reason(err error) string {
	var urlError *url.Error
	if errors.As(err, &urlError) && urlError.Err != nil {
		return urlError.Err.Error()
	}
	return err.Error()
}

// The two answers as the provider writes them. They are separate from the types above because the
// wire shape is the provider's and may change under this template — a beta API, by its own
// documentation — while what the rest of the module reads is this package's.
type event struct {
	EventID     string   `json:"eventId"`
	IsActive    bool     `json:"isActive"`
	IsLive      bool     `json:"isLive"`
	Category    string   `json:"category"`
	Subcategory string   `json:"subcategory"`
	Tags        []string `json:"tags"`
	Metadata    struct {
		Title string `json:"title"`
		Slug  string `json:"slug"`
	} `json:"metadata"`
	Markets []market `json:"markets"`
}

func (e event) read() Event {
	read := Event{
		EventID:     strings.TrimSpace(e.EventID),
		Title:       strings.TrimSpace(e.Metadata.Title),
		Category:    strings.TrimSpace(e.Category),
		Subcategory: strings.TrimSpace(e.Subcategory),
		Tags:        e.Tags,
		Active:      e.IsActive,
		Live:        e.IsLive,
		Markets:     make([]Market, 0, len(e.Markets)),
	}
	if slug := strings.TrimSpace(e.Metadata.Slug); slug != "" {
		read.SourceURL = "https://jup.ag/prediction/" + url.PathEscape(slug)
	}
	for _, one := range e.Markets {
		market := one.read()
		if market.EventID == "" {
			// A market inside an event belongs to it, whether or not the answer repeated the ID.
			market.EventID = read.EventID
		}
		read.Markets = append(read.Markets, market)
	}
	return read
}

type market struct {
	MarketID  string      `json:"marketId"`
	EventID   string      `json:"eventId"`
	Provider  string      `json:"provider"`
	Title     string      `json:"title"`
	Status    string      `json:"status"`
	Result    *string     `json:"result"`
	OpenTime  json.Number `json:"openTime"`
	CloseTime json.Number `json:"closeTime"`
	Tradable  *bool       `json:"tradable"`
	Lifecycle string      `json:"lifecycleStatus"`
}

func (m market) read() Market {
	read := Market{
		MarketID:  strings.TrimSpace(m.MarketID),
		EventID:   strings.TrimSpace(m.EventID),
		Provider:  strings.TrimSpace(m.Provider),
		Title:     strings.TrimSpace(m.Title),
		Status:    strings.ToLower(strings.TrimSpace(m.Status)),
		OpenTime:  seconds(m.OpenTime),
		CloseTime: seconds(m.CloseTime),
		Tradable:  m.Tradable,
		Lifecycle: strings.ToLower(strings.TrimSpace(m.Lifecycle)),
	}
	if m.Result != nil {
		read.Result = strings.ToLower(strings.TrimSpace(*m.Result))
	}
	return read
}

// seconds reads an epoch-seconds field the provider writes as a number. It is a json.Number because
// the provider's schema says "number" rather than "integer", and a market whose time cannot be read
// is a market with no time rather than an error: the filters decide what to do about that, and they
// say so (internal/discovery).
func seconds(value json.Number) int64 {
	text := strings.TrimSpace(value.String())
	if text == "" {
		return 0
	}
	if whole, err := strconv.ParseInt(text, 10, 64); err == nil {
		return whole
	}
	if fractional, err := value.Float64(); err == nil && fractional > 0 {
		return int64(fractional)
	}
	return 0
}
