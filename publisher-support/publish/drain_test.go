package publish

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"path/filepath"
	"testing"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/protobuf/proto"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/gateway/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/publishertest"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/store"
)

// The drainer is tested against the real store rather than a stand-in for it, because the store is
// the outbox: "what is pending" is a question about two columns, and a fake that answered it some
// other way would be testing the fake.
func drainer(t *testing.T, fake *publishertest.FakeGateway, at func() time.Time) (*Drainer, *store.Store) {
	t.Helper()
	documents, err := store.Open(filepath.Join(t.TempDir(), "publisher.db"), store.Stamp{
		ServerID:    server,
		Environment: "production",
		GatewayURL:  "https://feeds.example.com",
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = documents.Close() })
	return NewDrainer(Plan{
		Documents: documents,
		Gateway:   serve(t, fake),
		ServerID:  server,
		Manifest:  manifestAt,
		Log:       slog.New(slog.NewTextHandler(io.Discard, nil)),
		Now:       at,
		// A fixed delay, so a deferred publication's next attempt is a number this test can assert
		// rather than a second it has to wait.
		Backoff: func(int) time.Duration { return time.Minute },
	}), documents
}

func fixed(at time.Time) func() time.Time { return func() time.Time { return at } }

// The manifest goes first, because it is what makes the channel readable: a phone cannot hold a
// feed at all without having validated the manifest, so a proposal published before one would sit
// in a feed nobody has added.
func TestAPassPublishesTheManifestBeforeAnySignal(t *testing.T) {
	fake := &publishertest.FakeGateway{}
	drain, documents := drainer(t, fake, fixed(now))
	ctx := context.Background()

	if _, err := documents.ManifestRevision(ctx, "fingerprint-a", now); err != nil {
		t.Fatal(err)
	}
	if _, _, err := documents.Create(ctx, "key-1", "request-1", signalOf(swap())); err != nil {
		t.Fatal(err)
	}
	stored, err := drain.Pass(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if stored != 2 {
		t.Fatalf("%d documents were stored", stored)
	}
	if manifests, proposals, _ := fake.Seen(); manifests != 1 || proposals != 1 {
		t.Fatalf("%d manifests and %d proposals", manifests, proposals)
	}
	// And both are recorded as published, so a second pass sends nothing.
	if stored, err := drain.Pass(ctx); err != nil || stored != 0 {
		t.Fatalf("a second pass stored %d (%v)", stored, err)
	}
	if manifests, proposals, _ := fake.Seen(); manifests != 1 || proposals != 1 {
		t.Fatalf("a second pass published again: %d manifests, %d proposals", manifests, proposals)
	}
}

// A restart republishes the identical document at the identical revision, which the gateway
// answers "unchanged": no duplicate proposal, and no second notification. This is the acceptance
// criterion about retries and restarts, at the level where it is decided.
func TestARestartRepublishesTheSameBytesAndIsToldNothingChanged(t *testing.T) {
	fake := &publishertest.FakeGateway{}
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "publisher.db")
	stamp := store.Stamp{
		ServerID:    server,
		Environment: "production",
		GatewayURL:  "https://feeds.example.com",
	}
	gateway := serve(t, fake)
	plan := func(documents *store.Store) *Drainer {
		return NewDrainer(Plan{
			Documents: documents,
			Gateway:   gateway,
			ServerID:  server,
			Manifest:  manifestAt,
			Log:       slog.New(slog.NewTextHandler(io.Discard, nil)),
			Now:       fixed(now),
			Backoff:   func(int) time.Duration { return time.Minute },
		})
	}

	// The first process stores a signal and is killed before the answer is recorded: the gateway
	// took the document, and this process never found out.
	first, err := store.Open(path, stamp)
	if err != nil {
		t.Fatal(err)
	}
	created, _, err := first.Create(ctx, "key-1", "request-1", signalOf(swap()))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := first.ManifestRevision(ctx, "fingerprint-a", now); err != nil {
		t.Fatal(err)
	}
	document := signals.Proposal(server, created.Signal)
	if _, err := gateway.Proposal(ctx, document); err != nil {
		t.Fatal(err)
	}
	if err := first.Close(); err != nil {
		t.Fatal(err)
	}

	// The next process finds the same two revisions and publishes the same document.
	second, err := store.Open(path, stamp)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = second.Close() }()
	if _, err := second.ManifestRevision(ctx, "fingerprint-a", now); err != nil {
		t.Fatal(err)
	}
	if _, err := plan(second).Pass(ctx); err != nil {
		t.Fatal(err)
	}

	_, proposals, _ := fake.Seen()
	if proposals != 2 {
		t.Fatalf("the gateway saw %d proposals", proposals)
	}
	if !proto.Equal(fake.Proposals[0], fake.Proposals[1]) {
		t.Fatalf("the republication was a different document:\n%v\n%v", fake.Proposals[0],
			fake.Proposals[1])
	}
	// One proposal is held, at the revision it was published at, and the second publication was
	// answered "unchanged" — which is why nobody was notified twice.
	if held := fake.Held(created.Signal.ProposalID); held.GetRevision() != 1 {
		t.Fatalf("the gateway holds revision %d", held.GetRevision())
	}
	record, err := second.Signal(ctx, created.Signal.ProposalID)
	if err != nil {
		t.Fatal(err)
	}
	if record.Publication.State(record.Signal) != "published" {
		t.Fatalf("publication %s", record.Publication.State(record.Signal))
	}
}

// A gateway that is not answering is not a failed signal: the document is stored, the publication
// is deferred with a time to try again, and the attempt is counted.
func TestAGatewayThatIsNotAnsweringDefersThePublication(t *testing.T) {
	failing := true
	fake := &publishertest.FakeGateway{Refuse: func(string) error {
		if failing {
			return connect.NewError(connect.CodeUnavailable, io.ErrUnexpectedEOF)
		}
		return nil
	}}
	drain, documents := drainer(t, fake, fixed(now))
	ctx := context.Background()
	created, _, err := documents.Create(ctx, "key-1", "request-1", signalOf(swap()))
	if err != nil {
		t.Fatal(err)
	}

	refusal, err := drain.One(ctx, created)
	if err != nil {
		t.Fatal(err)
	}
	if refusal == nil || refusal.Permanent {
		t.Fatalf("%+v", refusal)
	}
	record, err := documents.Signal(ctx, created.Signal.ProposalID)
	if err != nil {
		t.Fatal(err)
	}
	if record.Publication.State(record.Signal) != "pending" {
		t.Fatalf("publication %s", record.Publication.State(record.Signal))
	}
	if record.Publication.Attempts != 1 ||
		!record.Publication.DueAt.Equal(now.Add(time.Minute)) {
		t.Fatalf("%+v", record.Publication)
	}
	// Nothing is due until then, and the signal is still counted as waiting.
	if due, err := documents.Due(ctx, now, 10); err != nil || len(due) != 0 {
		t.Fatalf("%d due (%v)", len(due), err)
	}
	if pending, err := documents.Pending(ctx); err != nil || pending != 1 {
		t.Fatalf("pending %d (%v)", pending, err)
	}

	// And when the gateway comes back, the next pass publishes it.
	failing = false
	later := NewDrainer(Plan{
		Documents: documents,
		Gateway:   drain.gateway,
		ServerID:  server,
		Manifest:  manifestAt,
		Log:       slog.New(slog.NewTextHandler(io.Discard, nil)),
		Now:       fixed(now.Add(2 * time.Minute)),
		Backoff:   func(int) time.Duration { return time.Minute },
	})
	if _, err := later.Pass(ctx); err != nil {
		t.Fatal(err)
	}
	record, _ = documents.Signal(ctx, created.Signal.ProposalID)
	if record.Publication.State(record.Signal) != "published" {
		t.Fatalf("publication %s", record.Publication.State(record.Signal))
	}
}

// A refusal that retrying cannot fix stops the retries and says which one it was. A template that
// kept asking would be a template nobody could debug, and the gateway would be answering the same
// words for ever.
func TestARefusalThatCannotBeFixedByRetryingStops(t *testing.T) {
	fake := &publishertest.FakeGateway{Refuse: func(string) error {
		return publishertest.Problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_FOREIGN_CHANNEL,
			connect.CodePermissionDenied, "channel")
	}}
	drain, documents := drainer(t, fake, fixed(now))
	ctx := context.Background()
	created, _, err := documents.Create(ctx, "key-1", "request-1", signalOf(swap()))
	if err != nil {
		t.Fatal(err)
	}

	refusal, err := drain.One(ctx, created)
	if err != nil {
		t.Fatal(err)
	}
	if refusal == nil || !refusal.Permanent || refusal.Problem != "foreign_channel" {
		t.Fatalf("%+v", refusal)
	}
	record, _ := documents.Signal(ctx, created.Signal.ProposalID)
	if record.Publication.State(record.Signal) != "refused" {
		t.Fatalf("publication %s", record.Publication.State(record.Signal))
	}
	if attempts := fake.Tried("PublishRequest"); attempts != 1 {
		t.Fatalf("%d publications were attempted", attempts)
	}
	// Later passes leave it alone.
	if _, err := drain.Pass(ctx); err != nil {
		t.Fatal(err)
	}
	if attempts := fake.Tried("PublishRequest"); attempts != 1 {
		t.Fatalf("a refused signal was published again: %d attempts", attempts)
	}
}

// The same thing for the manifest: a manifest the gateway refuses is not retried in a loop, and
// the reason is on the record for whoever restarts the process.
func TestAManifestTheGatewayRefusesStopsAndSaysWhy(t *testing.T) {
	fake := &publishertest.FakeGateway{Refuse: func(procedure string) error {
		if procedure == "PublishManifest" {
			return publishertest.Problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_GATEWAY,
				connect.CodeInvalidArgument, "gateway_url")
		}
		return nil
	}}
	drain, documents := drainer(t, fake, fixed(now))
	ctx := context.Background()
	if _, err := documents.ManifestRevision(ctx, "fingerprint-a", now); err != nil {
		t.Fatal(err)
	}

	if _, err := drain.Pass(ctx); err != nil {
		t.Fatal(err)
	}
	_, state, err := documents.Manifest(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if state.Problem != "other_gateway" {
		t.Fatalf("%+v", state)
	}
	if _, err := drain.Pass(ctx); err != nil {
		t.Fatal(err)
	}
	if attempts := fake.Tried("PublishManifest"); attempts != 1 {
		t.Fatalf("the manifest was published %d times", attempts)
	}
}

// A signal withdrawn before anything was published is settled here rather than retried: there is
// nothing at the gateway to take back, nobody ever read it, and the record says so in words.
func TestAWithdrawalOfSomethingNeverPublishedIsSettled(t *testing.T) {
	fake := &publishertest.FakeGateway{}
	drain, documents := drainer(t, fake, fixed(now))
	ctx := context.Background()
	created, _, err := documents.Create(ctx, "key-1", "request-1", signalOf(swap()))
	if err != nil {
		t.Fatal(err)
	}
	withdrawn, changed, err := documents.Cancel(ctx, created.Signal.ProposalID, now)
	if err != nil || !changed {
		t.Fatalf("changed %v (%v)", changed, err)
	}

	refusal, err := drain.One(ctx, withdrawn)
	if err != nil || refusal != nil {
		t.Fatalf("%+v (%v)", refusal, err)
	}
	record, _ := documents.Signal(ctx, created.Signal.ProposalID)
	if record.Publication.State(record.Signal) != "published" {
		t.Fatalf("publication %s", record.Publication.State(record.Signal))
	}
	if record.Publication.Detail == "" {
		t.Fatal("nothing says that there was never anything to withdraw")
	}
	// Nothing was ever published, which is the honest reading of it.
	if _, proposals, withdrawals := fake.Seen(); proposals != 0 || withdrawals != 1 {
		t.Fatalf("%d proposals, %d withdrawals", proposals, withdrawals)
	}
}

// A withdrawal of something that was published takes it back at the next revision, and the
// document the gateway holds is the one the publisher published with two fields changed.
func TestAWithdrawalIsPublishedAsATransition(t *testing.T) {
	fake := &publishertest.FakeGateway{}
	drain, documents := drainer(t, fake, fixed(now))
	ctx := context.Background()
	created, _, err := documents.Create(ctx, "key-1", "request-1", signalOf(swap()))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := drain.One(ctx, created); err != nil {
		t.Fatal(err)
	}
	withdrawn, _, err := documents.Cancel(ctx, created.Signal.ProposalID, now.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := drain.One(ctx, withdrawn); err != nil {
		t.Fatal(err)
	}

	// It went through CancelProposal and not through a second publication: a withdrawal is a
	// transition the gateway writes, and the gateway refuses a cancelled document anyway.
	if withdrawals := fake.Tried("CancelRequest"); withdrawals != 1 {
		t.Fatalf("%d withdrawals were sent", withdrawals)
	}
	if publications := fake.Tried("PublishRequest"); publications != 1 {
		t.Fatalf("%d publications were sent; the withdrawal was published as a document",
			publications)
	}
	held := fake.Held(created.Signal.ProposalID)
	if held.GetRevision() != 2 {
		t.Fatalf("the gateway holds revision %d", held.GetRevision())
	}
	if held.GetStatus().String() != "PROPOSAL_STATUS_CANCELLED" {
		t.Fatalf("status %s", held.GetStatus())
	}
	// The withdrawal was a transition and not a document: the terms the gateway holds are still
	// the ones the publisher published.
	if len(held.GetValues()) != len(swap().Terms) {
		t.Fatalf("the withdrawn document has %d terms", len(held.GetValues()))
	}
	record, _ := documents.Signal(ctx, created.Signal.ProposalID)
	if record.Publication.State(record.Signal) != "published" {
		t.Fatalf("publication %s", record.Publication.State(record.Signal))
	}
}

// A publisher upgraded before the independently deployed gateway still publishes: the new RPCs
// answer unimplemented, and the Stage 7.1 proposal methods carry the same signal.
func TestAnOlderGatewayReceivesTheCompatibilityProposalRpcs(t *testing.T) {
	fake := &publishertest.FakeGateway{Refuse: func(procedure string) error {
		if procedure == "PublishRequest" || procedure == "CancelRequest" {
			return connect.NewError(connect.CodeUnimplemented, errors.New("unknown procedure"))
		}
		return nil
	}}
	drain, documents := drainer(t, fake, fixed(now))
	ctx := context.Background()
	created, _, err := documents.Create(ctx, "key-1", "request-1", signalOf(swap()))
	if err != nil {
		t.Fatal(err)
	}

	if refusal, err := drain.One(ctx, created); err != nil || refusal != nil {
		t.Fatalf("%+v (%v)", refusal, err)
	}
	if fake.Tried("PublishRequest") != 1 || fake.Tried("PublishProposal") != 1 {
		t.Fatalf("request %d proposal %d", fake.Tried("PublishRequest"), fake.Tried("PublishProposal"))
	}
	record, err := documents.Signal(ctx, created.Signal.ProposalID)
	if err != nil || record.Publication.State(record.Signal) != "published" {
		t.Fatalf("publication %+v (%v)", record.Publication, err)
	}

	withdrawn, _, err := documents.Cancel(ctx, created.Signal.ProposalID, now.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if refusal, err := drain.One(ctx, withdrawn); err != nil || refusal != nil {
		t.Fatalf("%+v (%v)", refusal, err)
	}
	if fake.Tried("CancelRequest") != 1 || fake.Tried("CancelProposal") != 1 {
		t.Fatalf("cancel-request %d cancel-proposal %d",
			fake.Tried("CancelRequest"), fake.Tried("CancelProposal"))
	}
	if held := fake.Held(created.Signal.ProposalID); held.GetStatus().String() != "PROPOSAL_STATUS_CANCELLED" {
		t.Fatalf("status %s", held.GetStatus())
	}
}

// A pass runs the whole outbox, and the batch is a bound on one pass rather than on the outbox.
func TestAPassPublishesEverythingThatIsDue(t *testing.T) {
	fake := &publishertest.FakeGateway{}
	drain, documents := drainer(t, fake, fixed(now))
	ctx := context.Background()
	for index := range 5 {
		signal := signalOf(swap())
		signal.ProposalID = proposalID(index)
		if _, _, err := documents.Create(ctx, "key-"+signal.ProposalID, "request", signal); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := drain.Pass(ctx); err != nil {
		t.Fatal(err)
	}
	if _, proposals, _ := fake.Seen(); proposals != 5 {
		t.Fatalf("%d proposals", proposals)
	}
	if pending, err := documents.Pending(ctx); err != nil || pending != 0 {
		t.Fatalf("pending %d (%v)", pending, err)
	}
}

// Run drains on a wake-up, which is what the API calls after it stores something, and stops when
// its context is done.
func TestRunDrainsOnAWakeUpAndStops(t *testing.T) {
	fake := &publishertest.FakeGateway{}
	drain, documents := drainer(t, fake, time.Now)
	ctx, stop := context.WithCancel(context.Background())
	defer stop()

	finished := make(chan struct{})
	go func() {
		defer close(finished)
		drain.Run(ctx)
	}()

	if _, _, err := documents.Create(ctx, "key-1", "request-1", signalOf(swap())); err != nil {
		t.Fatal(err)
	}
	drain.Wake()
	deadline := time.Now().Add(5 * time.Second)
	for {
		pending, err := documents.Pending(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if pending == 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("the drainer did not publish on a wake-up")
		}
		time.Sleep(5 * time.Millisecond)
	}
	stop()
	select {
	case <-finished:
	case <-time.After(5 * time.Second):
		t.Fatal("the drainer did not stop with its context")
	}
}

// signalOf is a signal as the API would hand one to the store: the identity and the statement,
// with the revision and the fingerprint left to the store.
func signalOf(signal signals.Signal) signals.Signal {
	signal.Revision = 0
	signal.Fingerprint = ""
	return signal
}

func proposalID(index int) string {
	return "8c9d0e1f-2a3b-4c5d-8e6f-7a8b9c0d1e2" + string(rune('a'+index))
}

// The manifest a test publishes, which has to be the same bytes for the same revision.
var _ func(uint64) *serverv1.ServerManifest = manifestAt
