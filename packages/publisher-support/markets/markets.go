// Package markets is the market records a publisher that discovers its own signals keeps: what it
// is tracking, and what its last discovery cycle did (SEE-134).
//
// They are here, in the shared support library, because they are rows of the store's schema — the
// market row and the signal it is published as are written in one transaction by the same durable
// engine (packages/publisher-support/store), and a second copy of these types would be a second opinion
// about what that transaction wrote.
//
// Nothing in this package knows a provider. Which markets are worth publishing, how they are
// discovered and who they are discovered from is the Prediction demo's own affair
// (examples/demo-prediction/internal/discovery); what is here is only what the store persists and what an
// operator's API answers with.
package markets

import (
	"errors"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// ErrBusy is returned when a discovery cycle is asked for while one is already running. It is in
// this package rather than in the demo's reconciler because the shared API frame answers it 409
// without knowing whose reconciler refused (packages/publisher-support/api).
var ErrBusy = errors.New("a discovery cycle is already running")

// Market is one market this template is tracking: what the provider last said about it, the
// proposal it is published as, and the link to it on the provider's own site.
//
// That link is kept and never published. What the document carries is the provider's market and
// event identifiers, which is what lets the phone look the market up for itself; a URL a publisher
// chose, arriving on somebody's phone, is the thing the manifest rules exist to prevent
// (docs/wiki/server-manifests.md). This one is for the operator of this template, in its own API
// and its own logs.
type Market struct {
	// The provider's venue and its identifiers, exactly as it spelled them.
	Provider string
	MarketID string
	EventID  string
	// What the provider last said: its title, and the one word this template folds its status and
	// its resolution into (jupiter.Market.State).
	Title string
	State string
	// Its close time, or the zero instant when the provider gives none.
	CloseAt time.Time
	// The provider's own page for the event, when its answer carried a slug.
	SourceURL string
	// Which signal this market is published as, and how many signals it has had. A withdrawal is
	// final, so a market that closes and then re-opens — a postponed game, which the provider's own
	// rules text describes — gets a new proposal rather than reviving a withdrawn one. The
	// generation is what makes the idempotency key of that new one different (see [Key]).
	ProposalID string
	Generation int
	// When this template first saw it, when it last appeared in a listing, and when it was last
	// asked about directly. The first is what an expiry is counted from when the provider gives no
	// close time; the last is what makes the direct checks a round robin rather than a repeat of
	// the same few.
	FirstSeenAt   time.Time
	LastSeenAt    time.Time
	LastCheckedAt time.Time
}

// Key is the idempotency key a market's signal is created with: the venue, the identifier and the
// generation.
//
// It is derived rather than random on purpose. A cycle that is interrupted between reading the
// listing and storing the signal leaves nothing behind, and the next cycle derives the same key —
// so a market cannot become two proposals, whatever happens in between (packages/publisher-support/store).
func (m Market) Key() string {
	return "market:" + m.Provider + ":" + m.MarketID + ":" + itoa(m.Generation)
}

// Tracked is a market and the signal published for it: one row of this template's own view of the
// source.
type Tracked struct {
	Market Market
	Record signals.Record
}

// Cycle is what one pass did. It is kept as one row — the last cycle, not a history — because it
// exists to answer "is this publisher working", and a publisher that has to keep a log of its own
// polling has a second thing to operate.
type Cycle struct {
	// How many cycles have run, which is the only number here that survives a cycle.
	Number     int
	StartedAt  time.Time
	FinishedAt time.Time
	// ok when the whole walk finished and every tracked market that needed asking about was asked;
	// partial when something failed and some of it was done; failed when nothing could be read.
	Outcome string
	// The provider's own problem code and message, when a cycle ran into one.
	Problem string
	Detail  string
	// What the walk saw: pages read, events in them, markets considered, and markets that matched.
	Pages      int
	Events     int
	Considered int
	Matched    int
	// What it did: proposals published for the first time, proposals whose statement moved, and
	// proposals withdrawn because the source ended.
	Created   int
	Updated   int
	Cancelled int
	// Markets asked about directly, and candidates left for the next cycle because this template
	// is already holding as many proposals as it will.
	Checked int
	Skipped int
	// Why each considered market was not a candidate, counted by reason. It is the answer to "my
	// filters match nothing and I do not know which one did it".
	Reasons map[string]int
}

// The three outcomes.
const (
	OK      = "ok"
	Partial = "partial"
	Failed  = "failed"
)

// Working is whether the last cycle got far enough to be believed. It is what a status answer
// reports, and it is deliberately true for a partial cycle: a provider that rate-limited half a
// walk has not stopped this template from publishing what it did read.
func (c Cycle) Working() bool { return c.Outcome == OK || c.Outcome == Partial }

// Describe is a cycle as an answer reads it.
func (c Cycle) Describe() map[string]any {
	described := map[string]any{
		"number":     c.Number,
		"outcome":    c.Outcome,
		"pages":      c.Pages,
		"events":     c.Events,
		"considered": c.Considered,
		"matched":    c.Matched,
		"created":    c.Created,
		"updated":    c.Updated,
		"cancelled":  c.Cancelled,
		"checked":    c.Checked,
		"skipped":    c.Skipped,
	}
	if !c.StartedAt.IsZero() {
		described["started_at"] = c.StartedAt.UTC().Format(time.RFC3339)
	}
	if !c.FinishedAt.IsZero() {
		described["finished_at"] = c.FinishedAt.UTC().Format(time.RFC3339)
	}
	if c.Problem != "" {
		described["problem"] = c.Problem
	}
	if c.Detail != "" {
		described["detail"] = c.Detail
	}
	reasons := map[string]int{}
	for reason, count := range c.Reasons {
		reasons[reason] = count
	}
	described["skipped_because"] = reasons
	return described
}

// itoa is strconv.Itoa, spelled here so that the one place a key is built does not pull a second
// import into a file about matching.
func itoa(value int) string {
	if value == 0 {
		return "0"
	}
	digits := []byte{}
	for value > 0 {
		digits = append([]byte{byte('0' + value%10)}, digits...)
		value /= 10
	}
	return string(digits)
}
