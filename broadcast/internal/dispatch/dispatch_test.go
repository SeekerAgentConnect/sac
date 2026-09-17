package dispatch

import (
	"context"
	"crypto/sha256"
	"errors"
	"io"
	"log/slog"
	"path/filepath"
	"testing"
	"time"

	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/proposal/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/store"
)

const (
	publisher = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
	proposalA = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
	channelA  = "server/" + publisher
)

var published = time.Date(2026, 9, 17, 9, 0, 0, 0, time.UTC)

// recorder is a dispatcher a test can watch, fail and interfere from.
type recorder struct {
	delivered []Delivery
	fail      error
	before    func()
}

func (r *recorder) Dispatch(_ context.Context, delivery Delivery) error {
	if r.before != nil {
		r.before()
	}
	if r.fail != nil {
		return r.fail
	}
	r.delivered = append(r.delivered, delivery)
	return nil
}

func quiet() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func openStore(t *testing.T) *store.Store {
	t.Helper()
	documents, err := store.Open(filepath.Join(t.TempDir(), "broadcast.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = documents.Close() })
	sum := sha256.Sum256([]byte("credential"))
	if err := documents.Register(context.Background(), publisher, "test", sum[:], published); err != nil {
		t.Fatal(err)
	}
	return documents
}

func proposal(revision uint64) *proposalv1.Proposal {
	return &proposalv1.Proposal{
		ServerId:   publisher,
		Channel:    channelA,
		ProposalId: proposalA,
		Revision:   revision,
		Operation:  "swap",
		PluginId:   "jupiter.swap",
		Status:     proposalv1.ProposalStatus_PROPOSAL_STATUS_OPEN,
		CreatedAt:  timestamppb.New(published),
		UpdatedAt:  timestamppb.New(published),
		ExpiresAt:  timestamppb.New(published.Add(time.Hour)),
	}
}

func publish(t *testing.T, documents *store.Store, revision uint64) {
	t.Helper()
	ctx := context.Background()
	err := documents.Write(ctx, func(tx *store.Tx) error {
		_, err := tx.PutProposal(ctx, proposal(revision), published)
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
}

func drainer(documents *store.Store, to Dispatcher) *Drainer {
	one := New(documents, to, quiet(), func() time.Time { return published })
	// No waiting in a test: what backoff does is its own test.
	one.backoff = func(int) time.Duration { return 0 }
	return one
}

func TestWhatWasCommittedIsSentAndThenForgotten(t *testing.T) {
	documents := openStore(t)
	publish(t, documents, 1)
	watched := &recorder{}

	sent, err := drainer(documents, watched).Drain(context.Background())
	if err != nil || sent != 1 {
		t.Fatalf("the pass sent %d (%v)", sent, err)
	}
	if len(watched.delivered) != 1 {
		t.Fatalf("%d deliveries were made", len(watched.delivered))
	}
	delivery := watched.delivered[0]
	if delivery.Channel != channelA || delivery.ProposalID != proposalA || delivery.Revision != 1 {
		t.Fatalf("the delivery is about something else: %+v", delivery)
	}
	// The document goes with the notice, as it stands now: a subscriber gets what the gateway
	// holds rather than a hint to come and ask. It arrives inside the event envelope every
	// subscriber reads, with the sequence the channel was at when it was accepted.
	event := &gatewayv1.FeedEvent{}
	if err := proto.Unmarshal(delivery.Event, event); err != nil {
		t.Fatal(err)
	}
	if !proto.Equal(event.GetProposal(), proposal(1)) {
		t.Fatalf("the delivery carried a different document:\n%v", event.GetProposal())
	}
	if event.GetSequence() != 1 {
		t.Fatalf("the event says sequence %d", event.GetSequence())
	}
	if pending, _ := documents.Pending(context.Background()); pending != 0 {
		t.Fatalf("%d notices are left after a delivery", pending)
	}
}

func TestADeliveryThatFailsIsKeptAndTriedAgain(t *testing.T) {
	documents := openStore(t)
	publish(t, documents, 1)
	broken := &recorder{fail: errors.New("the broker is not there")}
	one := drainer(documents, broken)

	if sent, err := one.Drain(context.Background()); err != nil || sent != 0 {
		t.Fatalf("a failed pass reported %d sent (%v)", sent, err)
	}
	// Nothing is lost by failing. That is what makes the fan-out at-least-once rather than
	// best-effort, and what a phone's idempotent apply path is for.
	if pending, _ := documents.Pending(context.Background()); pending != 1 {
		t.Fatal("a failed delivery dropped its notice")
	}
	notices, _ := documents.Notices(context.Background(), published, 10)
	if len(notices) != 1 || notices[0].Attempts != 1 {
		t.Fatalf("the failed attempt was not counted: %+v", notices)
	}

	broken.fail = nil
	if sent, err := one.Drain(context.Background()); err != nil || sent != 1 {
		t.Fatalf("the retry sent %d (%v)", sent, err)
	}
	if pending, _ := documents.Pending(context.Background()); pending != 0 {
		t.Fatal("the retry did not finish the notice")
	}
}

func TestAPublicationThatLandsDuringADeliveryIsNotLost(t *testing.T) {
	documents := openStore(t)
	publish(t, documents, 1)
	interfering := &recorder{}
	// A second publication commits while the first delivery is in flight — the race the
	// conditional delete exists for.
	interfering.before = func() {
		interfering.before = nil
		publish(t, documents, 2)
	}
	one := drainer(documents, interfering)

	if _, err := one.Drain(context.Background()); err != nil {
		t.Fatal(err)
	}
	// The notice stayed, because what was sent is no longer what the gateway holds.
	if pending, _ := documents.Pending(context.Background()); pending != 1 {
		t.Fatal("the newer revision's notice was removed by the older delivery")
	}
	if _, err := one.Drain(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(interfering.delivered) != 2 || interfering.delivered[1].Revision != 2 {
		t.Fatalf("the newer document was not delivered: %+v", interfering.delivered)
	}
	if pending, _ := documents.Pending(context.Background()); pending != 0 {
		t.Fatalf("%d notices are left", pending)
	}
}

func TestANoticeForADocumentThatIsGoneIsDropped(t *testing.T) {
	documents := openStore(t)
	publish(t, documents, 1)
	// Retention swept the proposal while the notice was waiting: there is nothing to tell anyone
	// about a document that no longer exists.
	if removed, err := documents.Sweep(context.Background(), published.Add(2*time.Hour)); err != nil ||
		removed != 1 {
		t.Fatalf("the sweep removed %d (%v)", removed, err)
	}
	watched := &recorder{}
	if sent, err := drainer(documents, watched).Drain(context.Background()); err != nil || sent != 0 {
		t.Fatalf("the pass sent %d (%v)", sent, err)
	}
	if len(watched.delivered) != 0 {
		t.Fatal("a document that is gone was delivered")
	}
	if pending, _ := documents.Pending(context.Background()); pending != 0 {
		t.Fatal("the notice for a document that is gone was kept for ever")
	}
}

func TestAManifestIsFannedOutToo(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	err := documents.Write(ctx, func(tx *store.Tx) error {
		_, err := tx.PutManifest(ctx, manifestDocument(), published)
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	watched := &recorder{}
	if sent, err := drainer(documents, watched).Drain(ctx); err != nil || sent != 1 {
		t.Fatalf("the pass sent %d (%v)", sent, err)
	}
	if watched.delivered[0].Kind != store.ManifestNotice ||
		watched.delivered[0].ProposalID != "" {
		t.Fatalf("the delivery is about something else: %+v", watched.delivered[0])
	}
}

func TestBackoffGrowsAndStops(t *testing.T) {
	// It doubles, with jitter, and never waits longer than a minute: a gateway whose fan-out is
	// down should keep trying without turning into a busy loop or a nightly job.
	for attempts, longest := 0, time.Duration(0); attempts < 12; attempts++ {
		delay := Backoff(attempts)
		if delay <= 0 || delay > time.Minute+time.Minute/4 {
			t.Fatalf("attempt %d waits %v", attempts, delay)
		}
		if attempts < 6 && delay < longest/4 {
			t.Fatalf("attempt %d waits %v, less than a quarter of %v", attempts, delay, longest)
		}
		longest = max(longest, delay)
	}
}

func TestADrainerNeedsSomewhereToDispatchTo(t *testing.T) {
	// A gateway with no fan-out would pile up notices in silence. Saying so at startup is better
	// than finding out from the queue.
	defer func() {
		if recovered := recover(); recovered == nil {
			t.Fatal("a drainer was built with no dispatcher")
		}
	}()
	New(nil, nil, quiet(), time.Now)
}

// A manifest of the shape the store keeps, for the fan-out test above. What it says is the rules'
// business (internal/rules); what matters here is that a settings change is a notice as much as a
// proposal is.
func manifestDocument() *serverv1.ServerManifest {
	return &serverv1.ServerManifest{
		ServerId:         publisher,
		ProtocolVersion:  1,
		SettingsRevision: 3,
		Mode:             serverv1.ConnectionMode_CONNECTION_MODE_GATEWAY_FEED,
		Environments: []serverv1.ServerEnvironment{
			serverv1.ServerEnvironment_SERVER_ENVIRONMENT_PRODUCTION,
		},
		Reference: &serverv1.ServerManifest_Feed{Feed: &serverv1.GatewayFeed{
			GatewayUrl: "https://feeds.example.com",
			Channel:    channelA,
		}},
	}
}
