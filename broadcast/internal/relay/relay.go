// Package relay is the gateway's other half of a publication: the hint (SEE-92,
// docs/wiki/broadcast-gateway.md#the-push-relay).
//
// internal/stream carries the document to phones that are being looked at. This package carries
// nothing at all to the rest of them: one content-free message per changed channel, on a public
// topic, so a phone in someone's pocket learns that a feed moved and reads the feed itself. Both
// are dispatchers behind the same outbox ([dispatch.Dispatcher]), because both are "tell the
// subscribers" and neither is allowed to be the reason a document is not stored.
//
// # What a publisher is not given
//
// The credential. It belongs to this deployment, is read once at startup from a file only this
// process can see, and never appears in an answer, an error or a log line. A publisher cannot name
// a topic either: the topic is derived from the channel of a publication the gateway itself
// accepted and wrote, and the publisher API already refuses a document that claims another
// server's channel (internal/gateway/publisher.go). There is no field, anywhere, that a topic
// could be put into.
//
// # What a subscriber is not given
//
// Anything. The message carries two constant fields and no document: not the proposal's ID, not its
// revision, not the publisher's name. Which feed changed is the **topic**, which is a routing field
// rather than payload — the same line the private path drew between a target and its data
// (SAW-056). A topic is public and holding its name grants nothing: what it admits someone to is
// the news that a broadcast changed, and the broadcast is readable by anyone anyway.
//
// # Why it never fails a notice
//
// A hint is best-effort by nature, and saying so in code is better than implying otherwise:
// [Relay.Dispatch] logs a classified failure and returns nil, so a Firebase outage cannot stall the
// fan-out, hold the outbox open, or make the broker re-publish documents it already delivered. What
// makes a missed hint harmless is on the phone: the stream it holds while the app is open, and the
// bounded read it runs when it comes back.
package relay

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/dispatch"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/store"
)

// The hint, in full. It is two constants, and the phone matches the whole map rather than reading
// fields out of it (push/SeekerVaultMessagingService.kt), so a message with anything else in it —
// an ID, a revision, a note — is ignored on arrival rather than partly trusted.
//
// The version is the payload's own, not the protocol's: it changes when what these two fields mean
// changes, and a phone that does not know a version ignores the message.
const (
	Kind    = "feed_invalidation"
	Version = "1"
)

// CollapseKey is what Firebase replaces an undelivered hint with the next one under. It is one key
// for every feed on purpose: a phone that was offline wakes once and reads every feed it holds
// (sync/FeedSynchronization.kt), so two hints waiting for it are one thing to do.
const CollapseKey = "seeker-vault-feed-invalidation-v1"

// Lifetime is how long Firebase keeps trying. A hint older than this has been overtaken by the read
// the owner's next glance runs, and delivering it then would wake a phone to learn nothing.
const Lifetime = 5 * time.Minute

// Environments a topic may be scoped to. They are the two the manifest already names (SEE-97's
// ServerEnvironment), because a deployment that serves sandbox publishers and one that serves
// production publishers must not share a topic — the same publisher ID in both would otherwise be
// the same topic, and a sandbox publication would wake a production subscriber.
//
// It is **not** SEE-97's environment model. Nothing here decides what a server promises when the
// owner approves; this is one label in a topic name, chosen by the operator of the deployment.
const (
	Production = "production"
	Sandbox    = "sandbox"
)

// MostTopics is how many channels one answer maps at once, matching the stream's channel bound: a
// phone asks about the feeds it holds, and the two calls it makes about them should not disagree
// about how many it may hold.
const MostTopics = 32

// Options is what a deployment configures. Everything here comes from the operator; nothing has a
// default that would reach out on its own.
type Options struct {
	// Where the FCM v1 API is, as an origin the relay appends its own path to. There is no default
	// and no address compiled into this package: the one thing in this service that opens a
	// connection takes where to open it from its operator, which is also what lets the tests point
	// it at a local server.
	Endpoint string
	// The deployment's service account, already read and validated ([ReadCredentials]).
	Credentials Credentials
	// Which topics this deployment publishes hints on: [Production] or [Sandbox].
	Environment string
	// Hints per topic per second, and the burst above it. A publication is already rate limited per
	// publisher; this bounds something else — how often a feed's subscribers are woken.
	Rate  float64
	Burst int
	// How long one hint may take before it is a failure to log.
	Timeout time.Duration
	// Injected so the quota and the token cache are a test rather than a wait.
	Now func() time.Time
	Log *slog.Logger
}

// Relay sends hints. It implements [dispatch.Dispatcher] and the gateway's topic seam.
type Relay struct {
	endpoint    string
	project     string
	environment string
	quota       *quota
	tokens      *tokens
	client      *http.Client
	log         *slog.Logger
}

// New builds a relay, or says what the deployment left out. As with the broker, a half
// configuration is refused rather than defaulted: a relay that cannot mint a token is a relay that
// looks like it works until the first publication.
func New(options Options) (*Relay, error) {
	endpoint := strings.TrimSuffix(strings.TrimSpace(options.Endpoint), "/")
	switch {
	case endpoint == "":
		return nil, fmt.Errorf("relay: no push endpoint")
	case options.Environment != Production && options.Environment != Sandbox:
		return nil, fmt.Errorf("relay: environment must be %q or %q", Production, Sandbox)
	case options.Rate <= 0:
		return nil, fmt.Errorf("relay: no rate")
	case options.Burst <= 0:
		return nil, fmt.Errorf("relay: no burst")
	}
	if err := options.Credentials.valid(); err != nil {
		return nil, err
	}
	timeout := options.Timeout
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	now := options.Now
	if now == nil {
		now = time.Now
	}
	log := options.Log
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}
	client := &http.Client{Timeout: timeout}
	return &Relay{
		endpoint:    endpoint,
		project:     options.Credentials.ProjectID,
		environment: options.Environment,
		quota:       &quota{rate: options.Rate, burst: float64(options.Burst), now: now},
		tokens:      &tokens{credentials: options.Credentials, client: client, now: now},
		client:      client,
		log:         log,
	}, nil
}

// Topic is where a channel's hints are sent, or "" when the name is not one of this gateway's
// channels.
//
// It is derived and never stored, from the one string in a publication the gateway wrote itself.
// The shape is `feed.<environment>.<server_id>`: a constant that says what these topics are, the
// deployment's own scope, and the publisher's ID — which is a lowercase UUID, so the result is
// always within what Firebase accepts as a topic name without any escaping.
func (r *Relay) Topic(channel string) string {
	serverID := rules.ServerOf(channel)
	if serverID == "" {
		return ""
	}
	return "feed." + r.environment + "." + serverID
}

// MostTopics is how many channels one answer maps at once.
func (r *Relay) MostTopics() int { return MostTopics }

// Dispatch sends one hint, and never reports a failure to the drainer.
//
// The document has already been committed and the broker has already been told; a hint that could
// defer the notice would mean the broker re-publishing documents it delivered, for as long as
// somebody else's service was down. So every outcome here is logged and swallowed, and the one
// thing a hint is allowed to affect is whether a phone wakes up slightly sooner.
func (r *Relay) Dispatch(ctx context.Context, delivery dispatch.Delivery) error {
	topic := r.Topic(delivery.Channel)
	if topic == "" {
		// Not reachable from a publication the gateway accepted, and not silent if it ever is: a
		// notice with a channel this gateway cannot parse is a bug here rather than a publisher's
		// mistake.
		r.log.Error("hint not sent: the notice's channel is not a channel")
		return nil
	}
	if !r.quota.allow(topic) {
		// Not a failure and not a lost publication: the document is stored, the stream carried it,
		// and the next hint on this topic wakes a phone that reads everything (event.proto).
		r.log.Info("hint coalesced by the quota", "channel", delivery.Channel)
		return nil
	}
	if err := r.send(ctx, topic, delivery.Kind); err != nil {
		// Classified, never quoted. A message from Google can name a project, and an error is not
		// the place to publish someone's deployment.
		r.log.Warn("hint not delivered", "channel", delivery.Channel, "reason", err)
	}
	return nil
}

// send is the call itself, with its failures typed. Dispatch swallows them; the tests read them.
func (r *Relay) send(ctx context.Context, topic string, kind store.NoticeKind) error {
	body, err := json.Marshal(envelope{Message: message{
		Topic: topic,
		Data:  map[string]string{"kind": Kind, "version": Version},
		Android: android{
			CollapseKey: CollapseKey,
			Priority:    priority(kind),
			Lifetime:    fmt.Sprintf("%ds", int(Lifetime.Seconds())),
		},
	}})
	if err != nil {
		return fmt.Errorf("encode hint: %w", err)
	}
	token, err := r.tokens.access(ctx)
	if err != nil {
		return err
	}
	status, err := r.post(ctx, body, token)
	if err != nil {
		return err
	}
	// One retry, and only for the one condition that is ours: a cached token the endpoint no longer
	// accepts. Anything else is somebody else's state, and trying it twice in a row would not
	// change it — the next publication is the retry.
	if status == http.StatusUnauthorized {
		r.tokens.forget(token)
		fresh, err := r.tokens.access(ctx)
		if err != nil {
			return err
		}
		status, err = r.post(ctx, body, fresh)
		if err != nil {
			return err
		}
	}
	if status != http.StatusOK {
		return fmt.Errorf("the push endpoint answered %d", status)
	}
	return nil
}

// post sends the message and returns the status. The answer is read and discarded: there is nothing
// in it this service acts on, and its body can name a project.
func (r *Relay) post(ctx context.Context, body []byte, token string) (int, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodPost,
		r.endpoint+"/v1/projects/"+r.project+"/messages:send", bytes.NewReader(body))
	if err != nil {
		return 0, fmt.Errorf("build the hint request: %w", err)
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer "+token)
	response, err := r.client.Do(request)
	if err != nil {
		// The URL is in this error, and the URL is the operator's own endpoint and project. That is
		// the one address worth naming in a log this operator reads, and it holds no credential.
		return 0, fmt.Errorf("the push endpoint is not reachable")
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 8*1024))
	return response.StatusCode, nil
}

// priority is the one thing a hint says about what changed, and it says it to Android rather than
// to the app: a proposal may expire, so it is worth waking a device for, and a settings change is
// not. Neither is a promise about when — a topic message is not a delivery guarantee, and the
// documentation says so in both places (docs/guides/firebase.md).
func priority(kind store.NoticeKind) string {
	if kind == store.ProposalNotice {
		return "HIGH"
	}
	return "NORMAL"
}

type envelope struct {
	Message message `json:"message"`
}

type message struct {
	Topic   string            `json:"topic"`
	Data    map[string]string `json:"data"`
	Android android           `json:"android"`
}

type android struct {
	CollapseKey string `json:"collapse_key"`
	Priority    string `json:"priority"`
	Lifetime    string `json:"ttl"`
}

// quota is a token bucket per topic: one bucket of `burst`, refilling at `rate` a second, and a
// hint takes one.
//
// It is the same shape as the API's limiter (internal/gateway/limit.go) and a separate thing on
// purpose, because it bounds something else. That one bounds what a caller can cost this service;
// this one bounds how often a feed's subscribers are woken, which is a cost paid on other people's
// phones. A burst is allowed because publishing three proposals at once is ordinary; a sustained
// rate above it is not, and the hints above it are dropped rather than queued — a hint is news that
// something changed, and the next one carries the same news.
type quota struct {
	rate  float64
	burst float64
	now   func() time.Time

	mutex   sync.Mutex
	buckets map[string]*bucket
}

type bucket struct {
	tokens float64
	seen   time.Time
}

// mostTopicsRemembered bounds the map. A gateway hosting more publishers than this still relays for
// all of them: a bucket that has refilled completely behaves exactly as a new one, so the full ones
// are forgotten to make room, and only if none is full does a hint go out unmetered — which is the
// right way round, because dropping a hint to protect a map would be protecting the wrong thing.
const mostTopicsRemembered = 4096

func (q *quota) allow(topic string) bool {
	q.mutex.Lock()
	defer q.mutex.Unlock()
	at := q.now()
	if q.buckets == nil {
		q.buckets = make(map[string]*bucket)
	}
	held, known := q.buckets[topic]
	if !known {
		if len(q.buckets) >= mostTopicsRemembered {
			q.forget(at)
		}
		if len(q.buckets) >= mostTopicsRemembered {
			return true
		}
		held = &bucket{tokens: q.burst, seen: at}
		q.buckets[topic] = held
	}
	if elapsed := at.Sub(held.seen).Seconds(); elapsed > 0 {
		held.tokens = min(q.burst, held.tokens+elapsed*q.rate)
		held.seen = at
	}
	if held.tokens < 1 {
		return false
	}
	held.tokens--
	return true
}

// forget drops the buckets that have refilled completely. Called only when the map is full.
func (q *quota) forget(at time.Time) {
	full := q.burst / q.rate
	for topic, held := range q.buckets {
		if at.Sub(held.seen).Seconds() >= full {
			delete(q.buckets, topic)
		}
	}
}
