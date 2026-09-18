package drive

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/loadtest/internal/listen"
	"github.com/BrRenat/SeekerAgentWallet/loadtest/internal/measure"
)

// One simulated phone, from the ticket to the applied document (SEE-99).
//
// It makes the app's decisions, because they are the app's: `internal/listen/policy.go` is a port
// of `feeds/FeedRecovery.kt`, and this file is what calls it in the order `ForegroundFeedManager`
// calls it — ask for a ticket, open a stream, read what the connect answer says about continuity,
// read the authoritative snapshot when continuity was not proven, and apply a document only over an
// older revision of itself.
//
// What it adds is bookkeeping a phone has no reason to keep: when each document arrived, which
// offsets it saw, how often it reconnected and why, and what it ended up holding — which is the
// evidence for "clients converge without duplicate local execution".

// journal is when each document was published, keyed by its identity.
//
// It is how publish-to-receive is measured without a clock assumption: the publisher writes the
// moment it issued the call, every listener reads it when the bytes arrive, and the subtraction
// happens in one process on one monotonic clock. Entries are never removed during a run — a run
// publishing a hundred thousand documents keeps a hundred thousand timestamps, which is nothing
// beside the listeners.
type journal struct {
	mutex sync.RWMutex
	at    map[string]time.Time
}

func newJournal() *journal { return &journal{at: map[string]time.Time{}} }

// identity is a document's own name, and it is deliberately the outbox's: the channel, the kind,
// the document and the revision (internal/dispatch.IdempotencyKey). Two deliveries of the same
// document therefore look up the same send time, which is what makes a duplicate measurable
// instead of confusing.
func identity(channel, kind, id string, revision uint64) string {
	return fmt.Sprintf("%s/%s/%s/%d", channel, kind, id, revision)
}

func (j *journal) sent(key string, at time.Time) {
	j.mutex.Lock()
	defer j.mutex.Unlock()
	if _, already := j.at[key]; !already {
		j.at[key] = at
	}
}

func (j *journal) when(key string) (time.Time, bool) {
	j.mutex.RLock()
	defer j.mutex.RUnlock()
	at, known := j.at[key]
	return at, known
}

func (j *journal) size() int {
	j.mutex.RLock()
	defer j.mutex.RUnlock()
	return len(j.at)
}

// listener is one phone's worth of state.
type listener struct {
	run   *Run
	index int
	// The address the gateway's read limiter counts this listener as, or empty when the profile is
	// measuring what a shared address costs.
	forwarded string
	// The channels it holds feed references for, in the protocol's spelling, and the broker's name
	// for each once the gateway has granted them.
	channels []string
	granted  map[string]string
	// Which node's stream it consumes. Round-robin, which is what something in front of two nodes
	// would do.
	stream string

	reader *Reader
	client *listen.Client
	ticket string

	// Whether this listener is one of the ones that stop reading, and for how long. Not reading is
	// the whole of what a slow consumer is: the receive loop is the callback's caller, so blocking
	// in it fills the transport's window and then the broker's queue for this connection.
	stalls   bool
	stallFor time.Duration
	stalled  bool

	mutex sync.Mutex
	// Per broker channel: offsets, gaps and the cursor to resume from.
	cursors map[string]*measure.Channel
	// Per protocol channel and document: the highest revision applied. This is the "applied set"
	// the convergence check compares with what the gateway holds.
	applied map[string]map[string]uint64
	// Why this listener last had to read a snapshot, and how many times each reason happened.
	fallbacks map[listen.Why]int
	// The disconnect codes it saw, and whether it stopped for good.
	closes  map[uint32]int
	stopped bool
	failed  string
}

func (r *Run) newListener(index int, channels []string, stream string) *listener {
	forwarded := ""
	if !r.Profile.SharedAddress {
		// RFC 2544's benchmarking range: a hundred and thirty thousand addresses that mean
		// "this is a test", which is what they are.
		forwarded = fmt.Sprintf("198.18.%d.%d", (index/254)%256, index%254+1)
	}
	return &listener{
		run:       r,
		index:     index,
		forwarded: forwarded,
		channels:  channels,
		stream:    stream,
		reader:    NewReader(r.deployment.Origin, forwarded),
		cursors:   map[string]*measure.Channel{},
		applied:   map[string]map[string]uint64{},
		fallbacks: map[listen.Why]int{},
		closes:    map[uint32]int{},
	}
}

// listen runs one phone until the context ends.
//
// The loop is `ForegroundFeedManager`'s: a grant, a stream, and on its end the policy's verdict and
// the policy's backoff. Nothing here decides when to come back — that is `listen.AfterClose` and
// `listen.Backoff`, which are the app's.
func (l *listener) listen(ctx context.Context) {
	l.client = listen.Dial(l.stream)
	defer l.client.Close()
	attempt := 0
	for ctx.Err() == nil {
		if l.ticket == "" {
			if err := l.mint(ctx); err != nil {
				attempt++
				l.run.Counters.Count("listener.ticket.refused")
				if !sleepFor(ctx, listen.Backoff(attempt, listen.Spread)) {
					return
				}
				continue
			}
		}
		closed := uint32(0)
		opened := false
		err := l.client.Consume(ctx, listen.Options{
			Ticket:    l.ticket,
			Resume:    l.resume(),
			Forwarded: l.forwarded,
		}, func(event listen.Event) {
			if event.Kind == listen.Opened {
				opened = true
			}
			if code, ended := l.saw(ctx, event); ended {
				closed = code
			}
		})
		if ctx.Err() != nil {
			return
		}
		if err != nil {
			// Two different things, and a report that called them both "the stream failed" would
			// hide the interesting one: a node that is not there yet is a reconnect attempt
			// against a drained node, and a stream that broke after it opened is the transport
			// losing a connection it had.
			if opened {
				l.run.Counters.Count("listener.stream.failed")
			} else {
				l.run.Counters.Count("listener.connect.failed")
			}
			l.note(err.Error())
		}
		attempt++
		l.run.Counters.Count("listener.reconnect")
		switch listen.AfterClose(closed) {
		case listen.Stop:
			l.mutex.Lock()
			l.stopped = true
			l.mutex.Unlock()
			l.run.Counters.Count("listener.stopped")
			return
		case listen.Reticket:
			l.ticket = ""
			l.run.Counters.Count("listener.reticket")
		case listen.Reconnect:
		}
		if !sleepFor(ctx, listen.Backoff(attempt, listen.Spread)) {
			return
		}
	}
}

// mint asks the gateway for a grant, and times the call: a mass reconnect is a thousand of these
// arriving at once, which is load on the unary API rather than on the stream.
func (l *listener) mint(ctx context.Context) error {
	asked := time.Now()
	ticket, granted, _, err := l.reader.Ticket(ctx, l.channels)
	if err != nil {
		return err
	}
	l.run.Tickets.Add(time.Since(asked))
	if len(granted) != len(l.channels) {
		return fmt.Errorf("the gateway granted %d of %d channels", len(granted), len(l.channels))
	}
	l.ticket = ticket
	l.mutex.Lock()
	l.granted = granted
	for _, stream := range granted {
		if _, known := l.cursors[stream]; !known {
			l.cursors[stream] = &measure.Channel{}
		}
	}
	l.mutex.Unlock()
	return nil
}

// resume is the cursor per channel, which is the only thing this transport lets a client say.
func (l *listener) resume() map[string]listen.Cursor {
	l.mutex.Lock()
	defer l.mutex.Unlock()
	resume := make(map[string]listen.Cursor, len(l.cursors))
	for stream, channel := range l.cursors {
		epoch, offset := channel.Cursor()
		if epoch == "" && offset == 0 {
			continue
		}
		resume[stream] = listen.Cursor{Epoch: epoch, Offset: offset}
	}
	return resume
}

// saw handles one event, and says whether the stream ended and with what code.
func (l *listener) saw(ctx context.Context, event listen.Event) (uint32, bool) {
	if l.stalls && !l.stalled && l.run.stalled() {
		// What the broker does about this is the measurement, and it is the one thing in the run
		// that is deliberately antisocial.
		l.stalled = true
		l.run.Counters.Count("listener.stalled")
		sleepFor(ctx, l.stallFor)
	}
	switch event.Kind {
	case listen.Opened:
		l.opened(ctx, event)
	case listen.Published:
		l.published(event)
	case listen.Unreadable:
		l.run.Counters.Count("listener.unreadable")
	case listen.Dropped:
		l.run.Counters.Add(fmt.Sprintf("listener.dropped.%d", event.Code), 1)
	case listen.Closed:
		l.mutex.Lock()
		l.closes[event.Code]++
		l.mutex.Unlock()
		l.run.Counters.Add(fmt.Sprintf("listener.closed.%d.%s", event.Code, event.Reason), 1)
		return event.Code, true
	case listen.Alive:
		l.run.Counters.Count("listener.alive")
	}
	return 0, false
}

// opened reads the connect answer the way the phone reads it, and reads a snapshot for every
// channel whose continuity it could not prove.
func (l *listener) opened(ctx context.Context, event listen.Event) {
	l.run.Counters.Count("listener.opened")
	l.mutex.Lock()
	granted := l.granted
	l.mutex.Unlock()
	for channel, stream := range granted {
		l.mutex.Lock()
		state, known := l.cursors[stream]
		l.mutex.Unlock()
		if !known {
			continue
		}
		epoch, offset := state.Cursor()
		var held *listen.Cursor
		if epoch != "" || offset != 0 {
			held = &listen.Cursor{Epoch: epoch, Offset: offset}
		}
		var opened *listen.Subscription
		if subscription, subscribed := event.Subscriptions[stream]; subscribed {
			opened = &subscription
		}
		why := listen.Continuity(held, opened)
		if opened != nil {
			state.Opened(opened.Epoch, opened.Offset, opened.Recovered)
		}
		if why == listen.Recovered {
			l.run.Counters.Count("continuity.recovered")
			continue
		}
		l.mutex.Lock()
		l.fallbacks[why]++
		l.mutex.Unlock()
		l.run.Counters.Add("continuity.snapshot."+why.String(), 1)
		l.snapshot(ctx, channel)
	}
}

// snapshot is the authoritative read: the same walk the phone runs when the broker could not prove
// it replayed everything. It is also the recovery load a report has to account for — a thousand
// listeners reconnecting is a thousand of these.
func (l *listener) snapshot(ctx context.Context, channel string) {
	started := time.Now()
	token := ""
	pages := 0
	for {
		page, err := l.reader.Proposals(ctx, channel, uint32(l.run.Profile.PageSize), token, 0)
		if err != nil {
			if problem := Problem(err); problem != "" {
				l.run.Counters.Add("snapshot.refused."+problem, 1)
			} else {
				l.run.Counters.Count("snapshot.failed")
			}
			return
		}
		pages++
		for _, proposal := range page.Proposals {
			l.apply(channel, proposal.GetProposalId(), proposal.GetRevision(), false)
		}
		token = page.NextToken
		if token == "" {
			break
		}
	}
	l.run.Snapshots.Add(time.Since(started))
	l.run.Counters.Add("snapshot.pages", int64(pages))
}

// published is one document off the stream.
func (l *listener) published(event listen.Event) {
	l.mutex.Lock()
	state, known := l.cursors[event.Channel]
	l.mutex.Unlock()
	if !known {
		// A publication on a channel this listener was not granted. It would be a serious finding
		// — the isolation scenario is what looks for it — so it is counted by name.
		l.run.Counters.Add("listener.unexpected."+event.Channel, 1)
		return
	}
	if !state.Received(event.Offset) {
		l.run.Counters.Count("delivery.duplicate")
		return
	}
	document := event.Document
	var channel, id, key string
	var revision uint64
	switch {
	case document.GetProposal() != nil:
		proposal := document.GetProposal()
		channel, id, revision = proposal.GetChannel(), proposal.GetProposalId(),
			proposal.GetRevision()
		key = identity(channel, "proposal", id, revision)
	case document.GetManifest() != nil:
		manifest := document.GetManifest()
		channel, id, revision = "server/"+manifest.GetServerId(), "manifest",
			manifest.GetSettingsRevision()
		key = identity(channel, "manifest", "", revision)
	default:
		// A document of a kind this version does not know. Not an error, and not something to drop
		// quietly either: the channel moved, so it is counted.
		l.run.Counters.Count("delivery.unknown")
		return
	}
	// A delivery, whatever the apply rule then decides. The two are counted separately on purpose:
	// a document that arrived and was ignored because this listener already held that revision was
	// still delivered, and a stage's delivered share would be wrong if it were not counted.
	l.run.Counters.Count("delivery.arrived")
	if sent, timed := l.run.journal.when(key); timed {
		l.run.Delivery.Add(event.At.Sub(sent))
	} else {
		// Published before the measured window, or by a scenario that does not time its
		// publications. Delivered, and not part of the latency series.
		l.run.Counters.Count("delivery.untimed")
	}
	l.apply(channel, id, revision, true)
}

// apply is the phone's rule: a document is kept only over an older revision of itself.
//
// `delivered` says whether it came off the stream. A snapshot's documents are not deliveries and
// must never be counted or timed as ones.
func (l *listener) apply(channel, id string, revision uint64, delivered bool) {
	l.mutex.Lock()
	held, known := l.applied[channel]
	if !known {
		held = map[string]uint64{}
		l.applied[channel] = held
	}
	current, seen := held[id]
	fresh := !seen || revision > current
	if fresh {
		held[id] = revision
	}
	l.mutex.Unlock()
	switch {
	case !delivered:
		l.run.Counters.Count("snapshot.applied")
	case fresh:
		l.run.Counters.Count("delivery.applied")
	default:
		// At-least-once delivery, working as documented: the same document again, an older
		// revision arriving after a newer one, or a revision this listener already read from a
		// snapshot. The rule above is what makes all three harmless, and this counter is what
		// proves the rule was exercised rather than merely present.
		l.run.Counters.Count("delivery.ignored")
	}
}

// note keeps the first failure this listener saw, for the report.
func (l *listener) note(what string) {
	l.mutex.Lock()
	defer l.mutex.Unlock()
	if l.failed == "" {
		l.failed = what
	}
}

// holdings is what this listener ended up holding, per channel.
func (l *listener) holdings() map[string]map[string]uint64 {
	l.mutex.Lock()
	defer l.mutex.Unlock()
	copied := make(map[string]map[string]uint64, len(l.applied))
	for channel, documents := range l.applied {
		held := make(map[string]uint64, len(documents))
		for id, revision := range documents {
			held[id] = revision
		}
		copied[channel] = held
	}
	return copied
}

// continuity is what happened to this listener's streams.
func (l *listener) continuity() (fallbacks map[listen.Why]int, closes map[uint32]int, stopped bool) {
	l.mutex.Lock()
	defer l.mutex.Unlock()
	fallbacks = make(map[listen.Why]int, len(l.fallbacks))
	for why, count := range l.fallbacks {
		fallbacks[why] = count
	}
	closes = make(map[uint32]int, len(l.closes))
	for code, count := range l.closes {
		closes[code] = count
	}
	return fallbacks, closes, l.stopped
}

// gaps is what this listener saw on the transport, across its channels.
func (l *listener) gaps() (gaps int64, missing uint64, duplicates int64, backwards int64, epochs int64) {
	l.mutex.Lock()
	states := make([]*measure.Channel, 0, len(l.cursors))
	for _, state := range l.cursors {
		states = append(states, state)
	}
	l.mutex.Unlock()
	for _, state := range states {
		g, m, d, b := state.Gaps()
		gaps, missing, duplicates, backwards = gaps+g, missing+m, duplicates+d, backwards+b
		epochs += state.Epochs()
	}
	return gaps, missing, duplicates, backwards, epochs
}

// sleepFor waits, and says whether the wait finished rather than the run ending.
func sleepFor(ctx context.Context, how time.Duration) bool {
	if how <= 0 {
		return ctx.Err() == nil
	}
	timer := time.NewTimer(how)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}
