// Package dispatch is the fan-out half of a publication (SEE-90,
// docs/wiki/feed-gateway.md#persist-first-fan-out-second).
//
// A publication is two things that must not be one: the gateway commits the document, and then
// tells subscribers. Doing them in a single step is not possible — one of them is a database
// transaction and the other is a message to another system — so the only question is which failure
// mode a crash between them leaves behind. Sending first would mean a notice about a document the
// gateway does not hold. Committing first leaves a notice that has not gone out, which is work to
// redo: the notice is written inside the same transaction as the document (internal/storage), the
// drainer here picks it up, and a process that died between the two finds it again on the next
// start.
//
// That makes fan-out **at-least-once**, and it says so rather than pretending otherwise. A
// subscriber may be told twice about the same document; the phone's apply path is idempotent and
// revision-ordered precisely because delivery is not trustworthy about repetition (SEE-89). What
// cannot happen is a second *logical* proposal: identity is (channel, proposal_id), and a repeat is
// the same document arriving again.
//
// # What it fans out to
//
// Whatever implements [Dispatcher]. Centrifugo does, since SEE-91 (internal/stream); the push relay
// does, since SEE-92 (internal/relay); a deployment that configures neither gets [Logger], which
// records what would have been sent so that the machinery making a crash harmless is exercised and
// nothing pretends a subscriber heard anything. A deployment that configures both gets [Fan], and
// the difference between the two is worth stating: the broker carries the document and may defer a
// notice, while the relay carries a hint and never does (internal/relay).
//
// # What a delivery carries
//
// A serialized seekervault.gateway.v1.FeedEvent: the document as the gateway rebuilt it, wrapped
// with the channel sequence it was accepted at. The envelope is built here rather than in a
// dispatcher, because it is the contract every subscriber reads (packages/protocol/proto/.../event.proto) and not one
// transport's framing — a second dispatcher would carry the same bytes.
package dispatch

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"math/rand/v2"
	"time"

	"google.golang.org/protobuf/proto"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// Delivery is one document on its way to everyone subscribed to a channel.
//
// The document is read when the delivery is made rather than when the notice was written, so what
// a subscriber receives is what the gateway holds now. Two publications that have not gone out yet
// collapse into the later one, which is the same coalescing the private workflow's push
// invalidations already do under one collapse key (SAW-056): a subscriber wants the current
// document, not a history of how it got here.
type Delivery struct {
	Channel    string
	Kind       storage.NoticeKind
	ProposalID string
	Revision   uint64
	Sequence   uint64
	// The serialized seekervault.gateway.v1.FeedEvent. Public by construction: it is what every
	// subscriber to this channel receives, and it holds a document the publisher published and a
	// number the gateway counted — nothing about anyone reading it.
	Event []byte
	// Restricted says the channel is a restricted feed's (SEE-156), and Epoch which of its stream
	// names this delivery goes to: the current one for a document, the retired one for the
	// AccessNotice that retires it. A restricted delivery is never sent to a public topic.
	Restricted bool
	Epoch      uint64
}

// Dispatcher delivers to whatever is fanning out. SEE-91 implements it over Centrifugo; a test
// implements it to fail on purpose.
//
// It must be safe to call twice with the same delivery, because it will be: a process that stops
// after delivering and before recording the delivery will deliver again.
type Dispatcher interface {
	Dispatch(ctx context.Context, delivery Delivery) error
}

// Logger is the dispatcher this build ships: it writes one line per delivery and nothing leaves the
// process. It logs the identity and the revision, never the document — a log is not a feed, and a
// gateway that printed every proposal it relayed would make its operator's disk the one place a
// publisher's whole history is kept.
type Logger struct{ Log *slog.Logger }

func (l Logger) Dispatch(_ context.Context, delivery Delivery) error {
	l.Log.Info("fan-out",
		"channel", delivery.Channel,
		"kind", string(delivery.Kind),
		"proposal", delivery.ProposalID,
		"revision", delivery.Revision,
		"sequence", delivery.Sequence,
		"bytes", len(delivery.Event))
	return nil
}

// Fan is more than one dispatcher, in order, for a deployment that both streams and relays.
//
// Every one of them is called for every delivery, even after one fails, because they are different
// audiences rather than steps in a pipeline: a broker that is down must not cost a hint, and a hint
// that could not be sent must not cost the stream. The errors are joined, so a notice is deferred
// if any dispatcher that reports failures had one — which today is the broker alone, since the
// relay reports none by design.
type Fan []Dispatcher

func (f Fan) Dispatch(ctx context.Context, delivery Delivery) error {
	var failures []error
	for _, to := range f {
		if err := to.Dispatch(ctx, delivery); err != nil {
			failures = append(failures, err)
		}
	}
	return errors.Join(failures...)
}

// Drainer sends the notices the store holds. One runs in the gateway process; the tests drive
// [Drainer.Drain] directly, which is one pass over what is due.
type Drainer struct {
	storage    storage.OutboxStore
	dispatcher Dispatcher
	log        *slog.Logger
	now        func() time.Time
	// How long a pass may take at most, and how many notices it may send.
	batch int
	// The delay before a failed notice is due again, by attempt count. Injected so a test can
	// make it zero and the production one can add jitter.
	backoff func(attempts int) time.Duration
	wake    chan struct{}
	// How often a pass runs even when nothing woke it: the safety net for a notice that was
	// written by another process (feed-gatewayctl does not write any, but a second gateway node
	// would) and for a delivery that failed while nothing else was being published.
	idle time.Duration
}

// New builds a drainer. A nil dispatcher is not accepted: a gateway with no fan-out would
// accumulate notices silently, and saying so at startup is better than discovering it in a queue.
func New(from storage.OutboxStore, to Dispatcher, log *slog.Logger, now func() time.Time) *Drainer {
	if to == nil {
		panic("dispatch: a drainer needs somewhere to dispatch to")
	}
	return &Drainer{
		storage:    from,
		dispatcher: to,
		log:        log,
		now:        now,
		batch:      64,
		backoff:    Backoff,
		wake:       make(chan struct{}, 1),
		idle:       30 * time.Second,
	}
}

// Backoff is the production delay: doubling from a second to a minute, with up to a quarter of
// jitter either way so a gateway that lost its fan-out for a while does not reconnect to it with
// every pending notice at once.
func Backoff(attempts int) time.Duration {
	const base = time.Second
	const most = time.Minute
	delay := float64(base) * math.Pow(2, float64(min(attempts, 6)))
	if delay > float64(most) {
		delay = float64(most)
	}
	jitter := (rand.Float64() - 0.5) / 2
	return time.Duration(delay * (1 + jitter))
}

// Wake asks for a pass now. It is called after a publication commits, and it never blocks: the
// channel holds one token, because two wake-ups before a pass are one pass.
func (d *Drainer) Wake() {
	select {
	case d.wake <- struct{}{}:
	default:
	}
}

// Run drains until ctx is done, on a wake-up or on the idle timer. It makes one last pass on the
// way out so an orderly shutdown does not leave a notice that was already due — a graceful drain,
// and not a promise: what is still pending is still in the storage.
func (d *Drainer) Run(ctx context.Context) {
	ticker := time.NewTicker(d.idle)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			// A short window on the way out, with a context that is not the cancelled one.
			last, stop := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
			defer stop()
			if _, err := d.Drain(last); err != nil {
				d.log.Warn("fan-out did not finish before shutdown", "error", err)
			}
			return
		case <-d.wake:
		case <-ticker.C:
		}
		if _, err := d.Drain(ctx); err != nil && !errors.Is(err, context.Canceled) {
			d.log.Warn("fan-out failed", "error", err)
		}
	}
}

// Drain makes one pass over the notices that are due and returns how many were delivered.
//
// A notice whose document is gone — retention swept the proposal while the notice waited — is
// dropped rather than retried: there is nothing to say about a document that no longer exists, and
// the phones that hold it keep their own copy and their own decisions either way.
func (d *Drainer) Drain(ctx context.Context) (int, error) {
	notices, err := d.storage.Notices(ctx, d.now(), d.batch)
	if err != nil {
		return 0, err
	}
	sent := 0
	for _, notice := range notices {
		if err := ctx.Err(); err != nil {
			return sent, err
		}
		event, found, err := d.event(ctx, notice)
		if err != nil {
			return sent, err
		}
		if !found {
			if err := d.storage.NoticeDropped(ctx, notice.ID); err != nil {
				return sent, err
			}
			d.log.Info("fan-out dropped: the document is gone",
				"channel", notice.Channel, "proposal", notice.ProposalID)
			continue
		}
		delivery := Delivery{
			Channel:    notice.Channel,
			Kind:       notice.Kind,
			ProposalID: notice.ProposalID,
			Revision:   notice.Revision,
			Sequence:   notice.Sequence,
			Event:      event,
		}
		// Where it goes is read now, like the document: a publication that waited through a
		// revocation goes out under the channel's new stream name, never the retired one.
		access, err := d.storage.Access(ctx, rules.ServerOf(notice.Channel))
		if errors.Is(err, storage.ErrNoPublisher) {
			if err := d.storage.NoticeDropped(ctx, notice.ID); err != nil {
				return sent, err
			}
			continue
		}
		if err != nil {
			return sent, err
		}
		if access.Restricted() {
			delivery.Restricted = true
			delivery.Epoch = access.Epoch
			if notice.Kind == storage.AccessNotice {
				delivery.Epoch = notice.Revision
			}
		} else if notice.Kind == storage.AccessNotice {
			// The feed stopped being restricted before the notice went out. Its restricted names
			// carry nothing any more either way.
			if err := d.storage.NoticeDropped(ctx, notice.ID); err != nil {
				return sent, err
			}
			continue
		}
		if err := d.dispatcher.Dispatch(ctx, delivery); err != nil {
			due := d.now().Add(d.backoff(notice.Attempts))
			if deferred := d.storage.NoticeDeferred(ctx, notice.ID, due); deferred != nil {
				return sent, deferred
			}
			d.log.Warn("fan-out will be retried",
				"channel", notice.Channel, "kind", string(notice.Kind),
				"proposal", notice.ProposalID, "attempts", notice.Attempts+1, "error", err)
			continue
		}
		// Conditional on the revision: a publication that landed while this was in flight leaves
		// the notice in place, and the newer document goes out on the next pass.
		if _, err := d.storage.NoticeSent(ctx, notice.ID, notice.Revision); err != nil {
			return sent, err
		}
		sent++
	}
	return sent, nil
}

// event reads what a notice is about, as it stands now, and wraps it for its subscribers.
//
// The document is read here rather than when the notice was written, so what goes out is what the
// gateway holds — and two publications that have not been fanned out yet collapse into the later
// one. The sequence comes from the stored row for a proposal, which is exact. A manifest has no
// sequence of its own in the store, so the notice's is used; a publication landing between reading
// the notice and reading the document could make that number one behind, and the conditional clear
// then leaves the notice pending and the next pass sends the current one. Understating it is safe
// in the one direction that matters: the sequence is a hint about what a snapshot already covers,
// never a reason for a subscriber to skip an event (event.proto).
func (d *Drainer) event(ctx context.Context, notice storage.Notice) ([]byte, bool, error) {
	var wrapper *gatewayv1.FeedEvent
	switch notice.Kind {
	case storage.ManifestNotice:
		manifest, err := d.storage.Manifest(ctx, rules.ServerOf(notice.Channel))
		if err != nil || manifest == nil {
			return nil, false, err
		}
		wrapper = &gatewayv1.FeedEvent{
			Sequence: notice.Sequence,
			Document: &gatewayv1.FeedEvent_Manifest{Manifest: manifest.Document},
		}
	case storage.ProposalNotice:
		request, err := d.storage.Request(ctx, notice.Channel, notice.ProposalID)
		if err != nil || request == nil {
			return nil, false, err
		}
		wrapper = &gatewayv1.FeedEvent{Sequence: request.Sequence}
		if request.Legacy {
			wrapper.Document = &gatewayv1.FeedEvent_Proposal{Proposal: rules.ProposalFromRequest(request.Document)}
		} else {
			wrapper.Document = &gatewayv1.FeedEvent_Request{Request: request.Document}
		}
	case storage.AccessNotice:
		// Not a document: the retired stream name's last word, telling whoever is still attached
		// to ask for a new ticket (SEE-156). Nothing about which grant ended is in it.
		wrapper = &gatewayv1.FeedEvent{
			Sequence: notice.Sequence,
			Document: &gatewayv1.FeedEvent_AccessChanged{AccessChanged: &gatewayv1.AccessChanged{}},
		}
	default:
		return nil, false, fmt.Errorf("dispatch: unknown notice kind %q", notice.Kind)
	}
	bytes, err := proto.Marshal(wrapper)
	return bytes, err == nil, err
}
