package access

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/gateway"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/manifest"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/publish"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/publishertest"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/store"
)

// A restricted feed end to end against the **real** feed gateway, as its own process (SEE-156):
// this library's proofs, decisions, invitation and grant sync on one side, the gateway's enforcement
// on the other, and a reader who is only ever a JSON client of the public read API.
//
// Opt-in, like the other real-gateway tests: `pnpm check:publisher-support` builds the gateway and
// runs it, and
//
//	SEEKERVAULT_FEED_GATEWAY=/tmp/feed-gateway go test ./access -run RealGateway
//
// runs it by hand.
func TestARestrictedFeedAgainstTheRealGateway(t *testing.T) {
	binary := os.Getenv("SEEKERVAULT_FEED_GATEWAY")
	if binary == "" {
		t.Skip("set SEEKERVAULT_FEED_GATEWAY to a built feed-gateway binary to run this")
	}
	running := publishertest.RunGateway(t, binary, "restricted copy trading",
		"--access", "restricted", "--auth-origin", authOrigin)
	bench := realBench(t, running, authOrigin)
	channel := signals.ChannelFor(publishertest.ServerID)
	ctx := context.Background()

	// The manifest goes out declaring the restriction, and the gateway serves it as restricted.
	if _, err := bench.drainer.PassManifest(ctx); err != nil {
		t.Fatal(err)
	}
	status, manifestBody := publishertest.Read(t, running.Origin, "GetServerManifest",
		map[string]any{"serverId": publishertest.ServerID})
	if status != http.StatusOK || !strings.Contains(manifestBody, "FEED_ACCESS_POLICY_RESTRICTED") ||
		!strings.Contains(manifestBody, authOrigin) {
		t.Fatalf("the gateway serves the manifest as %d %s", status, manifestBody)
	}

	// A signal is published, because the gateway confirms it enforces the restriction.
	created, _, err := bench.store.Create(ctx, "key-1", "request-1", unpublished(publishertest.Swap()))
	if err != nil {
		t.Fatal(err)
	}
	if refusal, err := bench.drainer.One(ctx, created); err != nil || refusal != nil {
		t.Fatalf("%+v (%v)", refusal, err)
	}

	// Nobody reads it anonymously — not the common view, not the legacy one, not a ticket.
	for _, method := range []string{"ListRequests", "ListProposals"} {
		status, body := publishertest.Read(t, running.Origin, method, map[string]any{"channel": channel})
		if status != http.StatusForbidden || !strings.Contains(body, "ACCESS_REQUIRED") &&
			!strings.Contains(body, "access_required") && !strings.Contains(body, "permission_denied") {
			t.Fatalf("%s answered an anonymous reader %d %s", method, status, body)
		}
		if strings.Contains(body, created.Signal.ProposalID) {
			t.Fatalf("%s leaked the signal to an anonymous reader", method)
		}
	}

	// A phone proves its wallet, the operator approves, the phone redeems, and reads.
	p := newPhone(t)
	device := bench.ask(p)
	token := bench.approve(device.ID)
	session, err := bench.redeem(p, token)
	if err != nil {
		t.Fatal(err)
	}
	if !session.Synced {
		t.Fatal("the real gateway did not confirm the grant")
	}
	for _, method := range []string{"ListRequests", "ListProposals"} {
		status, body := publishertest.Read(t, running.Origin, method,
			map[string]any{"channel": channel, "session": session.Session})
		if status != http.StatusOK || !strings.Contains(body, created.Signal.ProposalID) {
			t.Fatalf("%s answered the approved device %d %s", method, status, body)
		}
		if strings.Contains(body, p.address) {
			t.Fatal("the gateway holds the wallet address")
		}
	}

	// Another device of the same wallet has no access until it is approved itself.
	second := withWallet(t, p.wallet)
	pending := bench.ask(second)
	status, _ = publishertest.Read(t, running.Origin, "ListRequests",
		map[string]any{"channel": channel, "session": newSessionLike(t)})
	if status != http.StatusForbidden {
		t.Fatalf("a made-up session read the feed: %d", status)
	}
	if pending.State != store.DevicePending {
		t.Fatalf("the second device is %s", pending.State)
	}

	// Revocation: the syncer tells the gateway, and the session stops working.
	if err := bench.service.Revoke(ctx, device.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := bench.syncer.Pass(ctx); err != nil {
		t.Fatal(err)
	}
	if view := bench.view(device.ID); view.Access != "revoked" {
		t.Fatalf("after the gateway confirmed, the operator sees %q", view.Access)
	}
	status, body := publishertest.Read(t, running.Origin, "ListRequests",
		map[string]any{"channel": channel, "session": session.Session})
	if status != http.StatusForbidden || strings.Contains(body, created.Signal.ProposalID) {
		t.Fatalf("a revoked session read %d %s", status, body)
	}
}

// A restricted publisher pointed at a gateway that registered its feed as public publishes nothing:
// the gateway would serve it to anybody, so the guard holds every signal back.
func TestARestrictedPublisherPublishesNothingToAPublicRegistration(t *testing.T) {
	binary := os.Getenv("SEEKERVAULT_FEED_GATEWAY")
	if binary == "" {
		t.Skip("set SEEKERVAULT_FEED_GATEWAY to a built feed-gateway binary to run this")
	}
	running := publishertest.RunGateway(t, binary, "misregistered copy trading")
	bench := realBench(t, running, authOrigin)
	ctx := context.Background()
	// The gateway refuses the manifest itself: it claims a restriction nobody registered.
	if _, err := bench.drainer.PassManifest(ctx); err != nil {
		t.Fatal(err)
	}
	if _, state, _ := bench.store.Manifest(ctx); state.Problem != "access_mismatch" {
		t.Fatalf("the manifest's state is %+v", state)
	}
	created, _, err := bench.store.Create(ctx, "key-1", "request-1", unpublished(publishertest.Swap()))
	if err != nil {
		t.Fatal(err)
	}
	refusal, err := bench.drainer.One(ctx, created)
	if err != nil || refusal == nil || refusal.Problem != "access_unconfirmed" {
		t.Fatalf("a signal went to a public registration: %+v (%v)", refusal, err)
	}
	status, body := publishertest.Read(t, running.Origin, "ListRequests",
		map[string]any{"channel": signals.ChannelFor(publishertest.ServerID)})
	if status == http.StatusOK && strings.Contains(body, created.Signal.ProposalID) {
		t.Fatal("the signal is readable by anybody")
	}
}

type realFixture struct {
	*fixture
	drainer *publish.Drainer
}

func realBench(t *testing.T, running publishertest.Gateway, origin string) *realFixture {
	t.Helper()
	documents, err := store.Open(filepath.Join(running.Directory, "publisher.db"), store.Stamp{
		ServerID: publishertest.ServerID, Environment: "production", GatewayURL: running.Origin})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = documents.Close() })
	client, err := gateway.New(gateway.Options{URL: running.PublishTo,
		Credential: running.Credential, Timeout: 10 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	settings := manifest.Settings{
		ServerID: publishertest.ServerID, GatewayURL: running.Origin, Environment: "production",
		Requirement: signals.Swap{}.Requirement(), DisplayName: "Restricted desk", AuthOrigin: origin,
	}
	ctx := context.Background()
	if _, err := documents.ManifestRevision(ctx, manifest.Fingerprint(settings), time.Now()); err != nil {
		t.Fatal(err)
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	moment := &clock{at: time.Now()}
	syncer := NewSyncer(SyncPlan{Store: documents, Grants: client, Log: log, Now: moment.now,
		Channel: signals.ChannelFor(publishertest.ServerID)})
	service := New(Plan{Store: documents, Syncer: syncer, Log: log, Now: moment.now,
		Settings: Settings{ServerID: publishertest.ServerID, GatewayURL: running.Origin, AuthOrigin: origin}})
	drainer := publish.NewDrainer(publish.Plan{
		Documents: documents, Gateway: client, ServerID: publishertest.ServerID,
		Manifest: func(at uint64) *serverv1.ServerManifest { return manifest.Document(settings, at) },
		Log:      log, Now: time.Now, Guard: NewGuard(client, origin, time.Now).Check,
	})
	return &realFixture{
		fixture: &fixture{t: t, service: service, syncer: syncer, store: documents, clock: moment},
		drainer: drainer,
	}
}

func unpublished(signal signals.Signal) signals.Signal {
	signal.Revision = 0
	signal.Fingerprint = ""
	return signal
}

// newSessionLike is a well-formed session nobody was ever granted.
func newSessionLike(t *testing.T) string {
	t.Helper()
	return randomText(32)
}
