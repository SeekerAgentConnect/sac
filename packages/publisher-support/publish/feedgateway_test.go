package publish

import (
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/gateway"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/manifest"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/network"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/publishertest"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/store"
)

// This template against the **real** feed gateway, as a separate process, with its own
// database and its own credential — the only test here that proves the two agree rather than
// assuming it (SEE-95).
//
// Opt-in, like the phone's Centrifugo test and for the same reason: it needs a binary that is not
// this module's.
//
//	cd services/gateway && go build -o /tmp/feed-gateway ./cmd/feed-gateway \
//	                        && go build -o /tmp/feed-gatewayctl ./cmd/feed-gatewayctl
//	cd publisher && SEEKERVAULT_FEED_GATEWAY=/tmp/feed-gateway go test ./internal/publish/ -run Gateway
//
// What it proves, and what nothing else here can:
//
//   - the gateway accepts the manifest this template builds — the mode, the channel, the origin,
//     the plugin requirement and the environment, all of which it validates separately;
//   - **two independent readers get byte-identical documents**, which is the automated half of
//     "two phones see the same proposal while choosing their own amounts" (the device half is the
//     owner's run, docs/testing/stage-7-1.md);
//   - a republication of the identical document is answered `UNCHANGED` by the real rules, so a
//     retry or a restart notifies nobody;
//   - a withdrawal is a transition the gateway writes, and the feed then serves it as cancelled.
func TestGatewayAcceptsWhatThisTemplatePublishes(t *testing.T) {
	binary := os.Getenv("SEEKERVAULT_FEED_GATEWAY")
	if binary == "" {
		t.Skip("set SEEKERVAULT_FEED_GATEWAY to a built feed-gateway binary to run this " +
			"(see docs/development/demos.md)")
	}
	running := publishertest.RunGateway(t, binary, "the publisher template's own test")
	origin, directory := running.Origin, running.Directory

	settings := manifest.Settings{
		ServerID:    publishertest.ServerID,
		GatewayURL:  origin,
		Environment: "production",
		Requirement: signals.Swap{}.Requirement(),
		DisplayName: "Copy trading desk",
	}
	documents, err := store.Open(filepath.Join(directory, "publisher.db"), store.Stamp{
		ServerID:    settings.ServerID,
		Environment: settings.Environment.String(),
		GatewayURL:  settings.GatewayURL,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = documents.Close() }()

	client, err := gateway.New(gateway.Options{
		// The publisher API is its own listener, which is how a deployment keeps publishing off
		// the internet: the manifest still names the read origin, because that is where phones go.
		URL:        running.PublishTo,
		Credential: running.Credential,
		Timeout:    10 * time.Second,
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	revision, err := documents.ManifestRevision(ctx, manifest.Fingerprint(settings), time.Now())
	if err != nil {
		t.Fatal(err)
	}
	drain := NewDrainer(Plan{
		Documents: documents,
		Gateway:   client,
		ServerID:  settings.ServerID,
		Manifest: func(at uint64) *serverv1.ServerManifest {
			return manifest.Document(settings, at)
		},
		Log: slog.New(slog.NewTextHandler(io.Discard, nil)),
		Now: time.Now,
	})

	// The manifest, which the gateway validates as strictly as the phone does.
	if _, err := drain.PassManifest(ctx); err != nil {
		t.Fatal(err)
	}
	_, state, err := documents.Manifest(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if state.Problem != "" || state.ConfirmedRevision != revision {
		t.Fatalf("the real gateway would not hold this template's manifest: %+v", state)
	}

	// A signal.
	created, _, err := documents.Create(ctx, "key-1", "request-1", signalOf(publishertest.Swap()))
	if err != nil {
		t.Fatal(err)
	}
	if refusal, err := drain.One(ctx, created); err != nil || refusal != nil {
		t.Fatalf("%+v (%v)", refusal, err)
	}

	// Two readers, two connections, no credential: this is what two phones do, and what they get
	// has to be the same bytes. Neither of them can send anything about themselves, and neither
	// appears in what the other reads.
	first := publishertest.ReadFeed(t, origin, signals.ChannelFor(publishertest.ServerID), nil)
	second := publishertest.ReadFeed(t, origin, signals.ChannelFor(publishertest.ServerID), nil)
	if first != second {
		t.Fatalf("two readers got different documents:\n%s\n%s", first, second)
	}
	if !strings.Contains(first, created.Signal.ProposalID) {
		t.Fatalf("the feed does not hold the signal:\n%s", first)
	}
	for _, absent := range []string{"wallet", "amount", "payer", "decision", "signature",
		"result"} {
		if strings.Contains(strings.ToLower(first), absent) {
			t.Fatalf("the document a phone reads mentions %q:\n%s", absent, first)
		}
	}

	// The same document again, which is what a retry and a restart both send.
	status, err := client.Proposal(ctx, signals.Proposal(publishertest.ServerID, created.Signal))
	if err != nil {
		t.Fatal(err)
	}
	if status != gateway.Unchanged {
		t.Fatalf("the real gateway answered %s to a republication of the identical document; a "+
			"retry or a restart would notify every subscriber again", status)
	}

	// A withdrawal, which is a transition the gateway writes rather than a document this template
	// sends.
	withdrawn, changed, err := documents.Cancel(ctx, created.Signal.ProposalID, time.Now())
	if err != nil || !changed {
		t.Fatalf("changed %v (%v)", changed, err)
	}
	if refusal, err := drain.One(ctx, withdrawn); err != nil || refusal != nil {
		t.Fatalf("%+v (%v)", refusal, err)
	}
	after := publishertest.ReadFeed(t, origin, signals.ChannelFor(publishertest.ServerID), nil)
	if !strings.Contains(after, "PROPOSAL_STATUS_CANCELLED") {
		t.Fatalf("the feed does not show the withdrawal:\n%s", after)
	}
	// And withdrawing again at the same revision is the retry, which is not an event.
	if status, err := client.Withdraw(ctx, created.Signal.ProposalID,
		withdrawn.Signal.Revision); err != nil || status != gateway.Unchanged {
		t.Fatalf("%s (%v)", status, err)
	}
}

// The gateway refuses a manifest that names another gateway, which is the deployment mistake with
// no error message anywhere else: the phone would refuse the feed and the template would look
// fine. Here it is the template that is told.
func TestGatewayRefusesAManifestForAnotherGateway(t *testing.T) {
	binary := os.Getenv("SEEKERVAULT_FEED_GATEWAY")
	if binary == "" {
		t.Skip("set SEEKERVAULT_FEED_GATEWAY to a built feed-gateway binary to run this")
	}
	running := publishertest.RunGateway(t, binary, "a template pointed at the wrong gateway")

	client, err := gateway.New(gateway.Options{
		URL:        running.PublishTo,
		Credential: running.Credential,
		Timeout:    10 * time.Second,
	})
	if err != nil {
		t.Fatal(err)
	}
	// A template configured against the wrong gateway: it publishes to this one and names that
	// one.
	elsewhere := manifest.Settings{
		ServerID:    publishertest.ServerID,
		GatewayURL:  "https://feeds.example.com",
		Environment: "production",
		Requirement: signals.Swap{}.Requirement(),
	}
	_, err = client.Manifest(context.Background(), manifest.Document(elsewhere, 1))
	refusal, ok := err.(*gateway.Refusal)
	if !ok {
		t.Fatalf("expected a refusal, got %v", err)
	}
	if refusal.Problem != "other_gateway" || !refusal.Permanent {
		t.Fatalf("%+v", refusal)
	}
}

// The demos' outage (SEE-179), against the real gateway: the gateway holds revision 1 of the
// manifest with no Solana networks — what a pre-SEE-174 gateway confirmed — and the publisher
// starts again with a fresh database, so it is at revision 1 too, now declaring Mainnet. The gateway
// refuses the same revision with other content and names what it holds; the publisher publishes
// past it, and the manifest a phone reads declares the network.
func TestGatewayServesTheNetworksOfAPublisherThatLostItsState(t *testing.T) {
	binary := os.Getenv("SEEKERVAULT_FEED_GATEWAY")
	if binary == "" {
		t.Skip("set SEEKERVAULT_FEED_GATEWAY to a built feed-gateway binary to run this")
	}
	running := publishertest.RunGateway(t, binary, "a publisher redeployed without its database")
	client, err := gateway.New(gateway.Options{
		URL:        running.PublishTo,
		Credential: running.Credential,
		Timeout:    10 * time.Second,
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	before := manifest.Settings{
		ServerID:    publishertest.ServerID,
		GatewayURL:  running.Origin,
		Environment: "production",
		Requirement: signals.Swap{}.Requirement(),
		DisplayName: "Copy trading desk",
	}
	if _, err := client.Manifest(ctx, manifest.Document(before, 1)); err != nil {
		t.Fatal(err)
	}

	after := before
	after.Networks = []network.Network{network.Mainnet}
	documents, err := store.Open(filepath.Join(running.Directory, "redeployed.db"), store.Stamp{
		ServerID:    after.ServerID,
		Environment: after.Environment.String(),
		GatewayURL:  after.GatewayURL,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = documents.Close() }()
	if revision, err := documents.ManifestRevision(ctx, manifest.Fingerprint(after),
		time.Now()); err != nil || revision != 1 {
		t.Fatalf("a fresh database starts at revision %d (%v)", revision, err)
	}
	drain := NewDrainer(Plan{
		Documents: documents,
		Gateway:   client,
		ServerID:  after.ServerID,
		Manifest: func(at uint64) *serverv1.ServerManifest {
			return manifest.Document(after, at)
		},
		Log: slog.New(slog.NewTextHandler(io.Discard, nil)),
		Now: time.Now,
	})
	if _, err := drain.PassManifest(ctx); err != nil {
		t.Fatal(err)
	}
	revision, state, err := documents.Manifest(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if revision != 2 || state.ConfirmedRevision != 2 || state.Problem != "" {
		t.Fatalf("revision %d, %+v", revision, state)
	}

	status, served := publishertest.Read(t, running.Origin, "GetServerManifest",
		map[string]any{"serverId": publishertest.ServerID})
	if status != 200 || !strings.Contains(served, `"settingsRevision":"2"`) ||
		!strings.Contains(served, "SOLANA_NETWORK_MAINNET") {
		t.Fatalf("the gateway serves %d: %s", status, served)
	}
	// And the catalog a phone's Discover tab reads says the same.
	if status, listed := publishertest.Read(t, running.Origin, "ListRecommendedFeeds",
		map[string]any{}); status == 200 && strings.Contains(listed, publishertest.ServerID) &&
		!strings.Contains(listed, "SOLANA_NETWORK_MAINNET") {
		t.Fatalf("the catalog lists the feed without its network: %s", listed)
	}
}
