package publish

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/gateway"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/proposal/v1"
	requestv2 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/request/v2"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// Documents is the part of the store the drainer uses. It is an interface so that this package
// does not depend on SQL, and so that "what is pending" stays a question the store answers rather
// than a queue this package keeps: there is exactly one record of what has been published, and it
// is the file.
type Documents interface {
	Manifest(ctx context.Context) (uint64, signals.Publication, error)
	ManifestPublished(ctx context.Context, revision uint64) error
	ManifestDeferred(ctx context.Context, due time.Time, detail string) error
	ManifestRefused(ctx context.Context, problem, detail string) error
	Due(ctx context.Context, now time.Time, limit int) ([]signals.Record, error)
	Published(ctx context.Context, id string, revision uint64, detail string) error
	Deferred(ctx context.Context, id string, due time.Time, detail string) error
	Refused(ctx context.Context, id string, problem, detail string) error
}

// Drainer publishes what the store says is not published yet.
//
// It is the only thing that submits anything, and the API calls into it for the first attempt
// rather than publishing on its own: one path to the gateway means one place that decides what a
// failure was, and it means the answer a caller gets and the answer the drainer records are the
// same answer.
type Drainer struct {
	documents Documents
	gateway   *gateway.Gateway
	// The publisher's own ID, which every document it builds has to name.
	serverID string
	// The manifest at a given revision, rebuilt from the settings this process was started with.
	// Rebuilding is deterministic, which is what lets a retry send identical bytes without storing
	// them (internal/store).
	manifest func(revision uint64) *serverv1.ServerManifest
	log      *slog.Logger
	now      func() time.Time
	backoff  func(attempts int) time.Duration
	wake     chan struct{}
	// How often a pass runs with nothing to wake it: the net under a publication that failed while
	// nothing else was happening.
	idle  time.Duration
	batch int
}

// Plan is what a [Drainer] needs.
type Plan struct {
	Documents Documents
	Gateway   *gateway.Gateway
	ServerID  string
	Manifest  func(revision uint64) *serverv1.ServerManifest
	Log       *slog.Logger
	Now       func() time.Time
	// Backoff is injected so a test does not wait a real second. Nil means [gateway.Backoff].
	Backoff func(attempts int) time.Duration
	// Idle is how long a quiet pass waits. Nil (zero) means half a minute.
	Idle time.Duration
}

// NewDrainer builds one.
func NewDrainer(plan Plan) *Drainer {
	backoff := plan.Backoff
	if backoff == nil {
		backoff = gateway.Backoff
	}
	idle := plan.Idle
	if idle <= 0 {
		idle = 30 * time.Second
	}
	now := plan.Now
	if now == nil {
		now = time.Now
	}
	return &Drainer{
		documents: plan.Documents,
		gateway:   plan.Gateway,
		serverID:  plan.ServerID,
		manifest:  plan.Manifest,
		log:       plan.Log,
		now:       now,
		backoff:   backoff,
		wake:      make(chan struct{}, 1),
		idle:      idle,
		batch:     32,
	}
}

// Wake asks for a pass now. It never blocks: the channel holds one token, because two wake-ups
// before a pass are one pass.
func (d *Drainer) Wake() {
	select {
	case d.wake <- struct{}{}:
	default:
	}
}

// Run drains until ctx is done, on a wake-up or on the idle timer.
//
// It makes no last pass on the way out, and that is deliberate: what is pending is in the file, and
// a shutdown that tried to publish would be a shutdown that hangs on a gateway that is down. The
// next start finds the same two revisions and publishes the same document.
func (d *Drainer) Run(ctx context.Context) {
	ticker := time.NewTicker(d.idle)
	defer ticker.Stop()
	for {
		if _, err := d.Pass(ctx); err != nil && !errors.Is(err, context.Canceled) {
			d.log.Error("a publication pass stopped early", "error", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-d.wake:
		case <-ticker.C:
		}
	}
}

// Pass publishes the manifest if it is behind, and then every signal that is due. It returns how
// many documents the gateway stored.
//
// The manifest goes first because it is what makes the channel readable: a phone cannot hold a feed
// at all without having validated the manifest, so a proposal published before one would sit in a
// feed nobody has added.
func (d *Drainer) Pass(ctx context.Context) (int, error) {
	stored := 0
	published, err := d.PassManifest(ctx)
	if err != nil {
		return stored, err
	}
	if published {
		stored++
	}
	due, err := d.documents.Due(ctx, d.now(), d.batch)
	if err != nil {
		return stored, err
	}
	for _, record := range due {
		refusal, err := d.One(ctx, record)
		if err != nil {
			return stored, err
		}
		if refusal == nil {
			stored++
		}
	}
	return stored, nil
}

// PassManifest publishes the manifest when the gateway has not confirmed the revision this
// template is at. It answers whether anything was stored.
func (d *Drainer) PassManifest(ctx context.Context) (bool, error) {
	revision, state, err := d.documents.Manifest(ctx)
	if err != nil || revision == 0 {
		return false, err
	}
	if state.ConfirmedRevision >= revision || state.Problem != "" ||
		state.DueAt.After(d.now()) {
		return false, nil
	}
	status, err := d.gateway.Manifest(ctx, d.manifest(revision))
	if err != nil {
		var refusal *gateway.Refusal
		if !errors.As(err, &refusal) {
			return false, err
		}
		if refusal.Permanent {
			d.log.Error("the gateway refused this publisher's manifest",
				append([]any{"revision", revision}, gateway.Describe(refusal)...)...)
			return false, d.documents.ManifestRefused(ctx, refusal.Problem, refusal.Detail)
		}
		d.log.Warn("the manifest will be published again",
			append([]any{"revision", revision, "in", d.backoff(state.Attempts).String()},
				gateway.Describe(refusal)...)...)
		return false, d.documents.ManifestDeferred(ctx,
			d.now().Add(d.backoff(state.Attempts)), refusal.Detail)
	}
	// Stored or unchanged, the gateway holds this revision, which is the only thing the store
	// records: a retry that was answered "unchanged" is as published as the first one that was
	// answered "stored".
	d.log.Info("the manifest is published", "revision", revision, "status", string(status))
	return status == gateway.Stored, d.documents.ManifestPublished(ctx, revision)
}

// One publishes one signal and records what the gateway said. A refusal comes back as a [gateway.Refusal]
// and is already recorded; an error is the store failing, which is the caller's problem to report.
//
// A withdrawn signal is withdrawn rather than published, and a withdrawal the gateway has nothing
// to apply is recorded as settled with a line saying so: nothing was ever public, so there is
// nothing to take back and nothing to keep retrying.
func (d *Drainer) One(ctx context.Context, record signals.Record) (*gateway.Refusal, error) {
	signal := record.Signal
	var (
		status gateway.Status
		err    error
	)
	if signal.Status == signals.Cancelled {
		status, err = d.gateway.WithdrawRequest(ctx, signal.ProposalID, signal.Revision)
		if unimplemented(err) {
			status, err = d.gateway.Withdraw(ctx, signal.ProposalID, signal.Revision)
		}
	} else {
		status, err = d.gateway.Request(ctx, d.RequestDocument(signal))
		if unimplemented(err) {
			status, err = d.gateway.Proposal(ctx, d.Document(signal))
		}
	}
	if err != nil {
		var refusal *gateway.Refusal
		if !errors.As(err, &refusal) {
			return nil, err
		}
		if refusal.Permanent {
			d.log.Error("the gateway refused this signal",
				append([]any{"signal", signal.ProposalID, "revision", signal.Revision},
					gateway.Describe(refusal)...)...)
			return refusal, d.documents.Refused(ctx, signal.ProposalID, refusal.Problem,
				refusal.Detail)
		}
		d.log.Warn("this signal will be published again",
			append([]any{"signal", signal.ProposalID, "revision", signal.Revision,
				"in", d.backoff(record.Publication.Attempts).String()}, gateway.Describe(refusal)...)...)
		return refusal, d.documents.Deferred(ctx, signal.ProposalID,
			d.now().Add(d.backoff(record.Publication.Attempts)), refusal.Detail)
	}
	if status == gateway.Absent {
		d.log.Info("this signal was withdrawn before anything was published",
			"signal", signal.ProposalID, "revision", signal.Revision)
		return nil, d.documents.Published(ctx, signal.ProposalID, signal.Revision,
			"nothing had been published, so there was nothing to withdraw")
	}
	d.log.Info("this signal is published", "signal", signal.ProposalID,
		"revision", signal.Revision, "status", string(status),
		"withdrawn", signal.Status == signals.Cancelled)
	return nil, d.documents.Published(ctx, signal.ProposalID, signal.Revision, "")
}

// unimplemented is a gateway that does not yet serve PublishRequest/CancelRequest. Those RPCs
// were added in SEE-108; PublishProposal/CancelProposal still carry the same signal, so a
// template upgraded first keeps publishing instead of marking the row refused.
func unimplemented(err error) bool {
	var refusal *gateway.Refusal
	return errors.As(err, &refusal) && refusal.Problem == "unimplemented"
}

// Document is the proposal a signal becomes: the same bytes every time, for the same signal at the
// same revision.
func (d *Drainer) Document(signal signals.Signal) *proposalv1.Proposal {
	return signals.Proposal(d.serverID, signal)
}

// RequestDocument is the primary document. Document above remains a compatibility helper for
// code and fixtures written against Stage 7.1's Proposal name.
func (d *Drainer) RequestDocument(signal signals.Signal) *requestv2.Request {
	return signals.Request(d.serverID, signal)
}
