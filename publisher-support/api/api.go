// Package api is a publisher template's own API: how a trader, a script or a strategy engine tells
// it what to publish (SEE-95, docs/integrations/signal-api.md).
//
// It is plain JSON over HTTP on purpose. The document this template publishes is protobuf through
// Connect (publisher-support/publish), because that is the contract the phone reads; what a strategy engine
// sends *here* is this template's own affair, and something that can be written with `curl` in a
// line is something a trading system in any language can call without generating anything. The CLI
// in demo-copytrading/cmd/publishctl is a client of this API and nothing more, which is how the API stays the one
// path in: one place validates, mints an identity, settles a revision and publishes.
//
// # What it will not take
//
// There is no field here for a wallet address, an amount, a slippage somebody settled on, a
// decision or an execution result, and the decoder refuses a field the contract does not have
// rather than dropping it — so a caller that believes this template keeps execution records is
// told that it does not, instead of being answered 200 and quietly ignored. It is the same strict
// decoding the gateway uses for the same reason (feed-gateway/internal/gateway/codec.go), and
// `boundary_test.go` tries all five words.
//
// # Authorization
//
// One token, presented as `Authorization: Bearer <token>`, compared in constant time, required on
// everything but `/healthz`. It is the whole of the grant: a caller that holds it can say anything
// this publisher can say, which is why it has a floor under its length and why the API binds
// loopback unless a deployment deliberately moves it (docs/development/demos.md).
//
// # Who writes the signals
//
// Two templates use this API and they differ in one thing, which [Authorship] names. The
// CopyTrading template's signals are written by its callers, so it routes create, update and cancel
// (SEE-95). The Prediction template's are written by its own discovery, so those three answer 403
// and say why, and two endpoints are added that show what discovery is doing: a template whose
// proposals have two authors would be a template where a cycle silently undoes what somebody
// posted (SEE-96, demo-prediction/internal/discovery).
package api

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"google.golang.org/protobuf/encoding/protojson"

	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/ids"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/limit"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/manifest"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/markets"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/publish"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/store"
)

// MostBodyBytes is the most one request body may be. The largest legitimate one is a note and
// thirty-two bounded terms, which is a few kilobytes; this leaves room and still means a caller
// cannot make the template hold an arbitrary amount of memory.
const MostBodyBytes = 64 << 10

// Documents is the part of the store this API uses.
type Documents interface {
	Replay(ctx context.Context, key, request string) (signals.Record, bool, error)
	Create(ctx context.Context, key, request string, signal signals.Signal) (signals.Record, bool, error)
	Update(ctx context.Context, id string, next signals.Signal, now time.Time) (signals.Record, bool, error)
	Cancel(ctx context.Context, id string, now time.Time) (signals.Record, bool, error)
	Retry(ctx context.Context, id string, now time.Time) (signals.Record, bool, error)
	Signal(ctx context.Context, id string) (signals.Record, error)
	Signals(ctx context.Context) ([]signals.Record, error)
	Manifest(ctx context.Context) (uint64, signals.Publication, error)
	Pending(ctx context.Context) (int, error)
}

// Markets is the part of the store a template that discovers its own signals reads: what it is
// tracking, what its last cycle did, and how many proposals it is holding open. Nil for a template
// whose signals come from its callers.
type Markets interface {
	Markets(ctx context.Context) ([]markets.Tracked, error)
	Cycle(ctx context.Context) (markets.Cycle, error)
	Live(ctx context.Context) (int, error)
}

// Cycles is the discovering demo's reconciler, as this API uses it: the filters it is applying,
// already described, and a cycle now.
//
// Filters answers a described map rather than a filter type on purpose. What a publisher looks for
// is its own demo's affair — a provider, its buckets, its named filters — and this frame is shared
// by demos that have no provider at all, so it renders what the demo says it is looking for
// without knowing what any of it means (demo-prediction/internal/discovery).
type Cycles interface {
	Filters() map[string]any
	Pass(ctx context.Context) (markets.Cycle, error)
}

// Authorship is who writes a template's signals, which is the one way the two templates' APIs
// differ.
type Authorship int

const (
	// ByCallers: a trader, a script or a strategy engine posts signals, and this API routes
	// create, update and cancel (SEE-95).
	ByCallers Authorship = iota
	// ByDiscovery: the template writes its own from a provider's listing, so those three are
	// refused with 403 and the discovery endpoints are added (SEE-96).
	ByDiscovery
)

// Server is the API.
type Server struct {
	documents  Documents
	drainer    *publish.Drainer
	kind       signals.Kind
	settings   manifest.Settings
	token      string
	log        *slog.Logger
	now        func() time.Time
	authorship Authorship
	markets    Markets
	cycles     Cycles
	// The identity minted for a new signal, injected so a test can pin one.
	newID func() string
	// Optional cap on new creates in a rolling hour. Nil, or a limiter whose max is zero, is
	// unlimited (SEE-126).
	creates  *limit.Limiter
	createMu sync.Mutex
}

// Plan is what a [Server] needs.
type Plan struct {
	Documents Documents
	Drainer   *publish.Drainer
	Kind      signals.Kind
	Settings  manifest.Settings
	Token     string
	Log       *slog.Logger
	Now       func() time.Time
	NewID     func() string
	// Who writes this template's signals. The zero value is [ByCallers], which is the CopyTrading
	// template.
	Authorship Authorship
	// Required when Authorship is [ByDiscovery], and meaningless otherwise.
	Markets Markets
	Cycles  Cycles
	// The most new creates this process will accept in one hour. Zero (the default) is unlimited.
	CreateLimit int
}

// New builds the API.
func New(plan Plan) *Server {
	now := plan.Now
	if now == nil {
		now = time.Now
	}
	newID := plan.NewID
	if newID == nil {
		newID = ids.New
	}
	if plan.Authorship == ByDiscovery && (plan.Markets == nil || plan.Cycles == nil) {
		// A wiring mistake in a template's own main, caught the first time it runs rather than
		// when somebody calls the endpoint that would have needed them.
		panic("api: a template whose signals are written by discovery has to supply its markets " +
			"and its reconciler")
	}
	var creates *limit.Limiter
	if plan.CreateLimit > 0 {
		creates = limit.New(plan.CreateLimit, time.Hour, now)
	}
	return &Server{
		documents:  plan.Documents,
		drainer:    plan.Drainer,
		kind:       plan.Kind,
		settings:   plan.Settings,
		token:      plan.Token,
		log:        plan.Log,
		now:        now,
		authorship: plan.Authorship,
		markets:    plan.Markets,
		cycles:     plan.Cycles,
		newID:      newID,
		creates:    creates,
	}
}

// Handler is the routed API. Every route names its method, so a wrong one is answered 405 by the
// router rather than by a handler that has to remember to check — and [answering] turns the
// router's own two refusals into this API's JSON, so that every answer from here is one shape.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.health)
	mux.HandleFunc("GET /v1/status", s.authorized(s.status))
	mux.HandleFunc("GET /v1/manifest", s.authorized(s.manifest))
	// The common request API is the documented surface. The signal routes remain compatibility
	// adapters for Stage 7.1 clients and call the same handlers, store and drainer.
	mux.HandleFunc("GET /v1/requests", s.authorized(s.list))
	mux.HandleFunc("GET /v1/requests/{id}", s.authorized(s.show))
	mux.HandleFunc("POST /v1/requests/{id}/retry", s.authorized(s.retry))
	mux.HandleFunc("GET /v1/signals", s.authorized(s.list))
	mux.HandleFunc("GET /v1/signals/{id}", s.authorized(s.show))
	// Retrying a refused publication is an operator's, not an author's: it is about the gateway
	// rather than about the statement, so both templates have it.
	mux.HandleFunc("POST /v1/signals/{id}/retry", s.authorized(s.retry))

	writing := map[string]http.HandlerFunc{
		"POST /v1/requests":             s.create,
		"PUT /v1/requests/{id}":         s.update,
		"POST /v1/requests/{id}/cancel": s.cancel,
		"POST /v1/signals":              s.create,
		"PUT /v1/signals/{id}":          s.update,
		"POST /v1/signals/{id}/cancel":  s.cancel,
	}
	if s.authorship == ByDiscovery {
		// Routed, and refused: a caller that posts a signal to this template is told that its
		// proposals come from a provider's listing and which filters decide them, rather than
		// being answered "no such route" and left to wonder.
		for route := range writing {
			writing[route] = s.byDiscovery
		}
		mux.HandleFunc("GET /v1/discovery", s.authorized(s.discovery))
		mux.HandleFunc("POST /v1/discovery/poll", s.authorized(s.poll))
	}
	for route, handler := range writing {
		mux.HandleFunc(route, s.authorized(handler))
	}
	return answering(mux)
}

// answering replaces the router's own 404 and 405 — which are plain text, because the router knows
// nothing about this API — with the same JSON shape every other refusal has. A caller parsing
// answers should not have to tell them apart by their content type.
func answering(next http.Handler) http.Handler {
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		next.ServeHTTP(&routerRefusals{ResponseWriter: writer}, request)
	})
}

// routerRefusals is a ResponseWriter that rewrites the router's two refusals and passes everything
// else through untouched.
type routerRefusals struct {
	http.ResponseWriter
	rewritten bool
}

func (r *routerRefusals) WriteHeader(status int) {
	// A refusal this API wrote has already set the JSON content type; the router's own sets a
	// plain-text one. That is what tells "there is no such signal" apart from "there is no such
	// route", which are both 404 and are not the same answer.
	if strings.HasPrefix(r.ResponseWriter.Header().Get("Content-Type"), "application/json") {
		r.ResponseWriter.WriteHeader(status)
		return
	}
	var about *problem
	switch status {
	case http.StatusNotFound:
		about = &problem{
			Error:  "no_such_route",
			Detail: "there is no such endpoint; see docs/integrations/signal-api.md",
		}
	case http.StatusMethodNotAllowed:
		about = &problem{
			Error:  "method_not_allowed",
			Detail: "that endpoint does not take this method; the `Allow` header says which",
		}
	default:
		r.ResponseWriter.WriteHeader(status)
		return
	}
	r.rewritten = true
	send(r.ResponseWriter, status, about)
}

// Write drops the router's plain-text body once its status has been rewritten, because the JSON
// one has already been written.
func (r *routerRefusals) Write(data []byte) (int, error) {
	if r.rewritten {
		return len(data), nil
	}
	return r.ResponseWriter.Write(data)
}

// health says the process is up and nothing else. It is what a proxy or a container probe calls,
// so it is the one route with no credential — and therefore the one route that must say nothing: a
// publisher's settings, its pending count and its environment are all behind the token.
func (s *Server) health(writer http.ResponseWriter, _ *http.Request) {
	send(writer, http.StatusOK, map[string]string{"status": "ok"})
}

// authorized is the one place a credential is checked.
//
// Every failure answers the same way — no header, a header that is not exactly `Bearer <token>`,
// and a wrong token are one answer — so a caller learns that it may not publish and never whether
// the thing it presented used to work. The comparison is constant time, because the alternative
// leaks the token one byte at a time to anybody who can measure it.
func (s *Server) authorized(next http.HandlerFunc) http.HandlerFunc {
	return func(writer http.ResponseWriter, request *http.Request) {
		presented, found := strings.CutPrefix(request.Header.Get("Authorization"), "Bearer ")
		if !found || subtle.ConstantTimeCompare([]byte(presented), []byte(s.token)) != 1 {
			// A refusal is logged without the credential, the path or anything presented: what is
			// useful is that one happened and from where.
			s.log.Warn("a call was refused", "from", request.RemoteAddr)
			refuse(writer, http.StatusUnauthorized, &problem{
				Error:  "unauthorized",
				Detail: "present the template's API token as `Authorization: Bearer <token>`",
			})
			return
		}
		next(writer, request)
	}
}

// status is what an operator wants to know: which publisher this is, which gateway it publishes
// through, what it promises, and whether anything is waiting to be published.
func (s *Server) status(writer http.ResponseWriter, request *http.Request) {
	revision, state, err := s.documents.Manifest(request.Context())
	if err != nil {
		s.fail(writer, err)
		return
	}
	pending, err := s.documents.Pending(request.Context())
	if err != nil {
		s.fail(writer, err)
		return
	}
	answer := map[string]any{
		"server_id":   s.settings.ServerID,
		"channel":     signals.ChannelFor(s.settings.ServerID),
		"gateway_url": s.settings.GatewayURL,
		"environment": s.settings.Environment,
		"operation":   s.kind.Operation(),
		"plugin_id":   s.kind.Requirement().PluginID,
		"reference":   manifest.Reference(s.settings.GatewayURL, s.settings.ServerID),
		"manifest": map[string]any{
			"settings_revision": number(revision),
			"publication":       publicationOf(state, revision),
		},
		"pending": pending,
		// Whether a caller may write a signal here at all, so a client learns it from the status
		// rather than from a 403 on its first attempt.
		"writable": s.authorship == ByCallers,
	}
	if s.authorship == ByDiscovery {
		cycle, err := s.markets.Cycle(request.Context())
		if err != nil {
			s.fail(writer, err)
			return
		}
		live, err := s.markets.Live(request.Context())
		if err != nil {
			s.fail(writer, err)
			return
		}
		answer["discovery"] = map[string]any{
			"markets":    live,
			"last_cycle": cycle.Describe(),
			"working":    cycle.Working(),
		}
	}
	send(writer, http.StatusOK, answer)
}

// byDiscovery is what the three writing endpoints answer on a template whose signals are its own.
//
// It is 403 rather than 404 or 405: the caller is authorized, the endpoint exists, and what it
// asked for is not something anybody may do here. The answer names the filters, because the way to
// change what this publisher says is to change what it looks for.
func (s *Server) byDiscovery(writer http.ResponseWriter, _ *http.Request) {
	refuse(writer, http.StatusForbidden, &problem{
		Error: "written_by_discovery",
		Detail: "this template's signals are written by its own discovery of " +
			s.kind.Operation() + " markets, not by callers. What it publishes is decided by the " +
			"filters its deployment configured (GET /v1/discovery), and a cycle would undo " +
			"anything posted here",
	})
}

// discovery is what this template is looking for and what it has found: the filters in force, the
// last cycle, and every market it is tracking with the link to the provider's own page.
//
// The links are here and nowhere else. A proposal carries the provider's identifiers, which is what
// lets a phone look a market up for itself; a URL a publisher chose is the thing the manifest rules
// exist to prevent, so it stays on this side of the boundary, for this template's operator
// (docs/wiki/prediction-template.md).
func (s *Server) discovery(writer http.ResponseWriter, request *http.Request) {
	cycle, err := s.markets.Cycle(request.Context())
	if err != nil {
		s.fail(writer, err)
		return
	}
	tracked, err := s.markets.Markets(request.Context())
	if err != nil {
		s.fail(writer, err)
		return
	}
	rows := make([]map[string]any, 0, len(tracked))
	for _, one := range tracked {
		rows = append(rows, s.tracking(one))
	}
	send(writer, http.StatusOK, map[string]any{
		"filters":    s.cycles.Filters(),
		"last_cycle": cycle.Describe(),
		"working":    cycle.Working(),
		"markets":    rows,
	})
}

// poll runs a cycle now and answers with what it did.
//
// It exists for the first five minutes of a deployment — an operator who has just changed a filter
// should not have to wait for the timer — and for the acceptance run. Two cycles cannot overlap, so
// a call while one is running is answered 409 rather than queued.
func (s *Server) poll(writer http.ResponseWriter, request *http.Request) {
	cycle, err := s.cycles.Pass(request.Context())
	switch {
	case errors.Is(err, markets.ErrBusy):
		refuse(writer, http.StatusConflict, &problem{
			Error:  "busy",
			Detail: "a discovery cycle is already running; ask again when it has finished",
		})
		return
	case err != nil:
		s.fail(writer, err)
		return
	}
	// Whatever the cycle produced is published before the answer, so that a caller polling by hand
	// is told the state of the publication rather than "pending" on everything it just created.
	if _, err := s.drainer.Pass(request.Context()); err != nil {
		s.fail(writer, err)
		return
	}
	pending, err := s.documents.Pending(request.Context())
	if err != nil {
		s.fail(writer, err)
		return
	}
	send(writer, http.StatusOK, map[string]any{
		"cycle":   cycle.Describe(),
		"working": cycle.Working(),
		"pending": pending,
	})
}

// tracking is one tracked market as an answer reads it.
func (s *Server) tracking(one markets.Tracked) map[string]any {
	market := map[string]any{
		"provider":    one.Market.Provider,
		"market_id":   one.Market.MarketID,
		"event_id":    one.Market.EventID,
		"title":       one.Market.Title,
		"state":       one.Market.State,
		"generation":  one.Market.Generation,
		"source_url":  one.Market.SourceURL,
		"signal":      s.view(one.Record.Signal),
		"publication": publicationOf(one.Record.Publication, one.Record.Signal.Revision),
	}
	for name, at := range map[string]time.Time{
		"close_at":        one.Market.CloseAt,
		"first_seen_at":   one.Market.FirstSeenAt,
		"last_seen_at":    one.Market.LastSeenAt,
		"last_checked_at": one.Market.LastCheckedAt,
	} {
		if !at.IsZero() {
			market[name] = instant(at)
		}
	}
	return market
}

// manifest is the document this template publishes about itself, and the reference a phone adds
// the feed from. Both are public in the sense that matters — the gateway serves the manifest to
// anyone, and a reference carries no secret — but they are behind the token here because this
// endpoint also says what has and has not been published yet.
func (s *Server) manifest(writer http.ResponseWriter, request *http.Request) {
	revision, state, err := s.documents.Manifest(request.Context())
	if err != nil {
		s.fail(writer, err)
		return
	}
	document := manifest.Document(s.settings, revision)
	send(writer, http.StatusOK, map[string]any{
		"manifest": map[string]any{
			"server_id":         document.GetServerId(),
			"protocol_version":  document.GetProtocolVersion(),
			"settings_revision": number(revision),
			"mode":              "gateway_feed",
			"environments":      environmentsOf(document),
			"display_name":      document.GetDisplayName(),
			"required_plugins": []map[string]any{{
				"plugin_id":    s.kind.Requirement().PluginID,
				"min_contract": s.kind.Requirement().MinContract,
				"max_contract": s.kind.Requirement().MostContract,
			}},
			"feed": map[string]string{
				"gateway_url": s.settings.GatewayURL,
				"channel":     signals.ChannelFor(s.settings.ServerID),
			},
		},
		"reference":   manifest.Reference(s.settings.GatewayURL, s.settings.ServerID),
		"publication": publicationOf(state, revision),
	})
}

// statement is the whole of what a caller may say about a signal: when it stops being actionable,
// the prose a person will read, and the operation's terms.
//
// What is not in it is the point. There is no amount, no wallet, no slippage a subscriber settled
// on, no decision and no result — a publisher has nobody to say those about, and the decoder
// refuses them rather than dropping them (boundary_test.go).
//
// An update carries the same shape as a create, and it is the whole statement rather than the
// parts that changed. The gateway stores a publisher's complete current statement and never merges
// a partial one, so a field-level merge here would be this template inventing a document nobody
// wrote.
type statement struct {
	// The instant after which nothing is executed from this signal: RFC 3339, absolute, and in the
	// future. Absolute rather than a duration because a phone that was switched off for a day has
	// to reach the same conclusion as one that was not.
	ExpiresAt string `json:"expires_at"`
	// The publisher's own prose. Optional, at most 1024 bytes, shown as theirs and believed by
	// nothing.
	Note string `json:"note"`
	// The operation's terms, as text. Text and not numbers because a quantity in base units
	// exceeds what a JSON number holds exactly, and because this is what the document carries
	// (`ProposalValue.text`).
	Terms map[string]string `json:"terms"`
}

func (s *Server) create(writer http.ResponseWriter, request *http.Request) {
	var asked statement
	if !s.read(writer, request, &asked) {
		return
	}
	key := strings.TrimSpace(request.Header.Get("Idempotency-Key"))
	if !isKey(key) {
		refuse(writer, http.StatusBadRequest, &problem{
			Error: "bad_idempotency_key",
			Detail: "send an `Idempotency-Key` header: 1 to 200 printable characters, unique to " +
				"this signal. A create that is retried without one publishes twice",
		})
		return
	}
	signal, ok := s.statement(writer, asked, signals.Signal{ProposalID: s.newID()})
	if !ok {
		return
	}
	signal.CreatedAt = s.now().UTC().Truncate(time.Second)
	signal.UpdatedAt = signal.CreatedAt
	signal.Operation = s.kind.Operation()
	signal.PluginID = s.kind.Requirement().PluginID

	record, held, err := s.createRecord(request.Context(), key, signals.Statement(signal), signal)
	switch {
	case errors.Is(err, errCreateLimited):
		refuse(writer, http.StatusTooManyRequests, &problem{
			Error:  "rate_limited",
			Detail: "this publisher is not accepting more new signals right now; try later",
		})
		return
	case errors.Is(err, store.ErrKeyReused):
		refuse(writer, http.StatusConflict, &problem{
			Error: "key_reused",
			Detail: "that `Idempotency-Key` was used for a different signal. A key belongs to " +
				"one signal: use a new one, or send the same request again",
		})
		return
	case err != nil:
		s.fail(writer, err)
		return
	}
	// A replay is answered with the signal the first call created, and nothing is published twice.
	// It still gets a publication attempt if it is waiting for one, because a caller retrying a
	// create is usually a caller whose first answer never arrived — and the gateway being back is
	// the likeliest reason it is asking again.
	s.answer(writer, request, record, held, http.StatusCreated,
		map[string]any{"idempotent": held})
}

var errCreateLimited = errors.New("publisher create limit reached")

// createRecord keeps replay classification and admission in one process-local critical section.
// The create cap is process-local too, so this prevents two concurrent HTTP requests for one key
// from racing between the replay check and the reservation. Publication happens after it unlocks.
func (s *Server) createRecord(
	ctx context.Context,
	key, request string,
	signal signals.Signal,
) (signals.Record, bool, error) {
	if s.creates == nil {
		return s.documents.Create(ctx, key, request, signal)
	}
	s.createMu.Lock()
	defer s.createMu.Unlock()
	record, held, err := s.documents.Replay(ctx, key, request)
	if err != nil || held {
		return record, held, err
	}
	if !s.creates.Allow("create") {
		return signals.Record{}, false, errCreateLimited
	}
	record, held, err = s.documents.Create(ctx, key, request, signal)
	if err != nil || held {
		s.creates.Undo("create")
	}
	return record, held, err
}

func (s *Server) update(writer http.ResponseWriter, request *http.Request) {
	var asked statement
	if !s.read(writer, request, &asked) {
		return
	}
	next, ok := s.statement(writer, asked, signals.Signal{})
	if !ok {
		return
	}
	record, changed, err := s.documents.Update(request.Context(), request.PathValue("id"), next,
		s.now().UTC().Truncate(time.Second))
	switch {
	case errors.Is(err, store.ErrNoSignal):
		s.missing(writer)
		return
	case errors.Is(err, store.ErrCancelled):
		refuse(writer, http.StatusConflict, &problem{
			Error: "cancelled",
			Detail: "that signal was withdrawn, and a withdrawal is final: a phone that acted " +
				"on it keeps its own record for ever. Publish a new signal instead",
		})
		return
	case err != nil:
		s.fail(writer, err)
		return
	}
	// An update that changed nothing publishes nothing: the same revision with the same content is
	// not an event, and nobody's phone is woken by a strategy engine restating its view.
	s.answer(writer, request, record, !changed, http.StatusOK, map[string]any{"changed": changed})
}

func (s *Server) cancel(writer http.ResponseWriter, request *http.Request) {
	record, changed, err := s.documents.Cancel(request.Context(), request.PathValue("id"),
		s.now().UTC().Truncate(time.Second))
	switch {
	case errors.Is(err, store.ErrNoSignal):
		s.missing(writer)
		return
	case err != nil:
		s.fail(writer, err)
		return
	}
	s.answer(writer, request, record, !changed, http.StatusOK, map[string]any{"changed": changed})
}

// retry asks for a refused publication to be tried again. It is the only way out of a permanent
// refusal, and it exists because the gateway's permanent refusals are the ones an operator has to
// fix: a credential, a channel that was full, a deployment pointed at the wrong gateway.
func (s *Server) retry(writer http.ResponseWriter, request *http.Request) {
	record, cleared, err := s.documents.Retry(request.Context(), request.PathValue("id"),
		s.now().UTC().Truncate(time.Second))
	switch {
	case errors.Is(err, store.ErrNoSignal):
		s.missing(writer)
		return
	case err != nil:
		s.fail(writer, err)
		return
	}
	s.answer(writer, request, record, false, http.StatusOK, map[string]any{"cleared": cleared})
}

func (s *Server) show(writer http.ResponseWriter, request *http.Request) {
	record, err := s.documents.Signal(request.Context(), request.PathValue("id"))
	switch {
	case errors.Is(err, store.ErrNoSignal):
		s.missing(writer)
		return
	case err != nil:
		s.fail(writer, err)
		return
	}
	name, document := s.documentView(request, record.Signal)
	send(writer, http.StatusOK, map[string]any{
		name:          document,
		"publication": publicationOf(record.Publication, record.Signal.Revision),
	})
}

func (s *Server) list(writer http.ResponseWriter, request *http.Request) {
	records, err := s.documents.Signals(request.Context())
	if err != nil {
		s.fail(writer, err)
		return
	}
	held := make([]map[string]any, 0, len(records))
	for _, record := range records {
		name, document := s.documentView(request, record.Signal)
		held = append(held, map[string]any{
			name:          document,
			"publication": publicationOf(record.Publication, record.Signal.Revision),
		})
	}
	collection := "signals"
	if isRequestPath(request) {
		collection = "requests"
	}
	send(writer, http.StatusOK, map[string]any{collection: held})
}

// statement reads what a caller said into the parts of a signal that are theirs, or answers with
// the one rule it broke.
func (s *Server) statement(writer http.ResponseWriter, asked statement, into signals.Signal) (
	signals.Signal, bool,
) {
	expires, err := time.Parse(time.RFC3339, strings.TrimSpace(asked.ExpiresAt))
	if asked.ExpiresAt == "" || err != nil {
		refuse(writer, http.StatusBadRequest, &problem{
			Error: "bad_expiry",
			Detail: "`expires_at` must be an absolute RFC 3339 instant, for example " +
				"2026-09-17T21:00:00Z",
		})
		return signals.Signal{}, false
	}
	if asked.Terms == nil {
		refuse(writer, http.StatusBadRequest, &problem{
			Error:  "missing",
			Term:   "terms",
			Detail: "`terms` must be an object of the operation's terms, as text",
		})
		return signals.Signal{}, false
	}
	note, expiry, terms, fault := signals.Check(s.kind, asked.Note, expires, s.now(), asked.Terms)
	if fault != nil {
		refuse(writer, http.StatusBadRequest, &problem{
			Error:  fault.Code,
			Term:   fault.Term,
			Detail: explain(fault),
		})
		return signals.Signal{}, false
	}
	into.Note = note
	into.ExpiresAt = expiry
	into.Terms = terms
	return into, true
}

// answer publishes the record if there is anything to publish, then answers with it as it stands.
//
// The status code is the state of the publication, because that is what a caller has to act on:
// `success` (201 for a create, 200 for a change to something that exists) for a signal the gateway
// now holds, 202 for one this template holds and will keep trying to publish, and 502 for one the
// gateway refused in a way that retrying cannot change. A call that changed nothing is 200.
func (s *Server) answer(
	writer http.ResponseWriter,
	request *http.Request,
	record signals.Record,
	unchanged bool,
	success int,
	extra map[string]any,
) {
	if record.Publication.ConfirmedRevision < record.Signal.Revision &&
		record.Publication.Problem == "" {
		if _, err := s.drainer.One(request.Context(), record); err != nil {
			s.fail(writer, err)
			return
		}
		// Re-read rather than guess: what is answered is what is stored, including the attempt
		// count and the reason a publication is still pending.
		updated, err := s.documents.Signal(request.Context(), record.Signal.ProposalID)
		if err != nil {
			s.fail(writer, err)
			return
		}
		record = updated
	}
	state := publicationOf(record.Publication, record.Signal.Revision)
	name, document := s.documentView(request, record.Signal)
	body := map[string]any{name: document, "publication": state}
	for name, value := range extra {
		body[name] = value
	}
	status := http.StatusOK
	if !unchanged {
		switch state["state"] {
		case "published":
			status = success
		case "pending":
			status = http.StatusAccepted
		case "refused":
			status = http.StatusBadGateway
		}
	}
	send(writer, status, body)
}

func (s *Server) view(signal signals.Signal) map[string]any {
	terms := make(map[string]string, len(signal.Terms))
	for key, text := range signal.Terms {
		terms[key] = text
	}
	return map[string]any{
		"server_id":   s.settings.ServerID,
		"channel":     signals.ChannelFor(s.settings.ServerID),
		"proposal_id": signal.ProposalID,
		"revision":    number(signal.Revision),
		"status":      string(signal.Status),
		"operation":   signal.Operation,
		"plugin_id":   signal.PluginID,
		"created_at":  instant(signal.CreatedAt),
		"updated_at":  instant(signal.UpdatedAt),
		"expires_at":  instant(signal.ExpiresAt),
		"note":        signal.Note,
		"terms":       terms,
		"environment": s.settings.Environment,
	}
}

func isRequestPath(request *http.Request) bool {
	return strings.HasPrefix(request.URL.Path, "/v1/requests")
}

func (s *Server) documentView(request *http.Request, signal signals.Signal) (string, any) {
	if !isRequestPath(request) {
		return "signal", s.view(signal)
	}
	encoded, err := protojson.MarshalOptions{UseProtoNames: true}.Marshal(signals.Request(s.settings.ServerID, signal))
	if err != nil {
		panic("api: a request built from a stored signal did not marshal: " + err.Error())
	}
	var common map[string]any
	if err := json.Unmarshal(encoded, &common); err != nil {
		panic("api: a marshalled request was not JSON: " + err.Error())
	}
	return "request", common
}

// read decodes a request body strictly: the declared content type, a bounded size, exactly one
// JSON object, and no field the contract does not have.
func (s *Server) read(writer http.ResponseWriter, request *http.Request, into any) bool {
	if kind := request.Header.Get("Content-Type"); kind != "" &&
		!strings.HasPrefix(kind, "application/json") {
		refuse(writer, http.StatusUnsupportedMediaType, &problem{
			Error:  "not_json",
			Detail: "send `Content-Type: application/json`",
		})
		return false
	}
	decoder := json.NewDecoder(http.MaxBytesReader(writer, request.Body, MostBodyBytes))
	// The whole point of this API's decoding: a field that is not in the contract is an error, not
	// something to drop. A caller sending a wallet or an amount is told there is no such field.
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(into); err != nil {
		refuse(writer, http.StatusBadRequest, &problem{
			Error:  "bad_request",
			Detail: readable(err),
		})
		return false
	}
	// One object, and nothing after it: a body with a second document is a body somebody built
	// wrong, and answering about the first would hide it.
	if decoder.More() {
		refuse(writer, http.StatusBadRequest, &problem{
			Error:  "bad_request",
			Detail: "send one JSON object",
		})
		return false
	}
	return true
}

func (s *Server) missing(writer http.ResponseWriter) {
	refuse(writer, http.StatusNotFound, &problem{
		Error:  "no_such_signal",
		Detail: "this template holds no signal of that ID",
	})
}

// fail is the store or the drainer failing, which is this template's problem and not the caller's.
// The message goes to the log and a sentence goes to the caller: an error from here can quote a
// file path, and a caller of this API is not necessarily the operator of it.
func (s *Server) fail(writer http.ResponseWriter, err error) {
	s.log.Error("a call could not be served", "error", err)
	refuse(writer, http.StatusInternalServerError, &problem{
		Error:  "internal",
		Detail: "this template could not serve that; its log says why",
	})
}

// problem is every refusal's shape: a stable code a caller can branch on, the term it was about
// when it was about one, and a sentence for whoever is reading.
type problem struct {
	Error  string `json:"error"`
	Term   string `json:"term,omitempty"`
	Detail string `json:"detail"`
}

func refuse(writer http.ResponseWriter, status int, about *problem) {
	send(writer, status, about)
}

func send(writer http.ResponseWriter, status int, body any) {
	writer.Header().Set("Content-Type", "application/json")
	// Nothing here is cacheable: what a publisher currently proposes is the answer, and a proxy
	// deciding how long that stays true would be a second opinion about it.
	writer.Header().Set("Cache-Control", "no-store")
	writer.WriteHeader(status)
	encoder := json.NewEncoder(writer)
	encoder.SetIndent("", "  ")
	_ = encoder.Encode(body)
}

// environmentsOf is what a manifest answer says a deployment promises, read out of the document
// that will be published rather than out of the setting beside it (SEE-97).
//
// One fact, one source. The setting and the document cannot disagree if only one of them is ever
// rendered, and the document is the one a phone will read.
func environmentsOf(document *serverv1.ServerManifest) []string {
	named := make([]string, 0, len(document.GetEnvironments()))
	for _, environment := range document.GetEnvironments() {
		named = append(named, strings.ToLower(
			strings.TrimPrefix(environment.String(), "SERVER_ENVIRONMENT_")))
	}
	return named
}

// publicationOf is what an answer says about a publication.
func publicationOf(state signals.Publication, revision uint64) map[string]any {
	answer := map[string]any{
		"state":              state.State(signals.Signal{Revision: revision}),
		"confirmed_revision": number(state.ConfirmedRevision),
		"attempts":           state.Attempts,
	}
	if state.Problem != "" {
		answer["problem"] = state.Problem
	}
	if state.Detail != "" {
		answer["detail"] = state.Detail
	}
	if !state.DueAt.IsZero() && state.Problem == "" && state.ConfirmedRevision < revision {
		answer["next_attempt_at"] = instant(state.DueAt)
	}
	return answer
}

// number writes a uint64 as text. A revision can exceed what a JSON number holds exactly, and
// protojson writes a 64-bit integer as a string for the same reason: a caller that parsed one as a
// float would compare revisions wrongly at the top of the range.
func number(value uint64) string { return fmt.Sprintf("%d", value) }

func instant(at time.Time) string { return at.UTC().Format(time.RFC3339) }

// isKey is the shape of an idempotency key: short, printable, and one word. It is the caller's own
// string — a strategy engine's order ID, a timestamp and a pair, whatever it can reproduce — so
// the only rules are the ones that keep it storable and readable in an answer.
func isKey(key string) bool {
	if key == "" || len(key) > 200 {
		return false
	}
	for _, character := range key {
		if character < 0x21 || character > 0x7e {
			return false
		}
	}
	return true
}

// readable is a decoding failure as a sentence. encoding/json's own messages are good, except that
// the unknown-field one is the one a caller most needs to understand, so it is answered in this
// API's own words.
func readable(err error) string {
	message := err.Error()
	if field, found := strings.CutPrefix(message, "json: unknown field "); found {
		return "there is no field " + field + " in a signal. A signal says what is proposed and " +
			"until when; the amount, the wallet and the decision are each subscriber's own and " +
			"are never sent here"
	}
	return message
}

// explain is a term rule as a sentence, so that a person reading a refusal in a terminal is told
// what to do about it. The codes are the contract; these words are not.
func explain(fault *signals.Fault) string {
	switch fault.Code {
	case "missing":
		return "`" + fault.Term + "` is required"
	case "unknown_term":
		return "`" + fault.Term + "` is not a term of this operation. Prose belongs in `note`, " +
			"which is shown to the owner as the publisher's own words"
	case "not_a_mint":
		return "`" + fault.Term + "` must be an exact base58 mint address, never a ticker: a " +
			"symbol names several things on this chain and nothing off it. Native SOL is the " +
			"wrapped mint, " + signals.WrappedSOL
	case "one_asset":
		return "both sides name the same mint, which proposes nothing"
	case "bad_number":
		return "`" + fault.Term + "` must be a whole number, as text, in the asset's own base units"
	case "impossible_amounts":
		return "the floor is above the ceiling, so no amount satisfies both"
	case "bad_symbol":
		return "`" + fault.Term + "` must be at most 16 characters: it is a label the owner is " +
			"shown beside the mint, and it is never believed"
	case "bad_note":
		return "`note` must be at most 1024 bytes of printable text"
	case "past_expiry", "no_expiry":
		return "`expires_at` must be an instant in the future: a signal that has already expired " +
			"proposes nothing"
	case "bad_term", "bad_term_name", "too_many_terms":
		return "the terms must be at most 32 named text values, each name a short lowercase word " +
			"and each value at most 512 bytes of printable text"
	default:
		return fault.Error()
	}
}
