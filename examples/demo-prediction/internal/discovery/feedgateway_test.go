package discovery_test

import (
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/demo-prediction/internal/discovery"
	"github.com/BrRenat/SeekerAgentWallet/demo-prediction/internal/jupiter"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/gateway"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/manifest"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/publish"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/publishertest"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/store"
)

// The Prediction template against the same real gateway (SEE-96).
//
// It is a second test rather than a second case in the first one, because what it proves is
// different: the document a *discovered* market becomes — built by the reconciler's own path,
// through the store's market rows — is one the real gateway accepts, and one that two independent
// readers get identically. That is the automated half of "two phones see the same market while
// each of them chooses a side and a stake"; the device half is the owner's run
// (docs/testing/stage-7-1.md).
//
// It also proves the thing the ticket asks for last: after the source ends a market, the proposal
// a phone can read is cancelled, so nothing new is executed from it.
func TestGatewayAcceptsWhatThePredictionTemplatePublishes(t *testing.T) {
	binary := os.Getenv("SEEKERVAULT_FEED_GATEWAY")
	if binary == "" {
		t.Skip("set SEEKERVAULT_FEED_GATEWAY to a built feed-gateway binary to run this " +
			"(see docs/development/demos.md)")
	}
	running := publishertest.RunGateway(t, binary, "the prediction template's own test")

	kind := signals.Prediction{}
	settings := manifest.Settings{
		ServerID:    publishertest.ServerID,
		GatewayURL:  running.Origin,
		Environment: "production",
		Requirement: kind.Requirement(),
		DisplayName: "Prediction desk",
	}
	documents, err := store.Open(filepath.Join(running.Directory, "prediction.db"), store.Stamp{
		ServerID:    settings.ServerID,
		Environment: settings.Environment.String(),
		GatewayURL:  settings.GatewayURL,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = documents.Close() }()

	client, err := gateway.New(gateway.Options{
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
	drain := publish.NewDrainer(publish.Plan{
		Documents: documents,
		Gateway:   client,
		ServerID:  settings.ServerID,
		Manifest: func(at uint64) *serverv1.ServerManifest {
			return manifest.Document(settings, at)
		},
		Log: slog.New(slog.NewTextHandler(io.Discard, nil)),
		Now: time.Now,
	})

	// The manifest, which requires the other bundled plugin: the gateway validates a requirement's
	// shape and its contract range, so a prediction manifest is not the same document with a word
	// changed.
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

	// A market, discovered the way a cycle discovers one — through the reconciler's own path, over
	// a feedListing this test scripts. Building the statement by hand here would test the gateway
	// against a document no cycle produces, which is the one thing this test exists not to do.
	provider := &feedListing{}
	reconciler := discovery.New(discovery.Plan{
		Documents: documents,
		Source:    provider,
		Kind:      kind,
		Filters: discovery.Filters{
			Source:       "polymarket",
			Categories:   []string{"economics"},
			Keywords:     []string{"fed"},
			LeastCloseIn: time.Hour,
			MostCloseIn:  90 * 24 * time.Hour,
			Lifetime:     7 * 24 * time.Hour,
			PageSize:     25,
			MostPages:    1,
			MostOpen:     5,
			MostChecks:   5,
			Every:        time.Minute,
		},
		Deposit: discovery.Deposit{Mint: signals.USDCMint, Symbol: "USDC"},
		Note:    "Markets I follow. Not advice.",
		Log:     slog.New(slog.NewTextHandler(io.Discard, nil)),
		Now:     func() time.Time { return noon },
		Wake:    func() {},
	})
	cycle, err := reconciler.Pass(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if cycle.Created != 1 {
		t.Fatalf("the cycle created %d proposals: %+v", cycle.Created, cycle)
	}
	if _, err := drain.Pass(ctx); err != nil {
		t.Fatal(err)
	}
	held, err := documents.Markets(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(held) != 1 {
		t.Fatalf("%d markets tracked", len(held))
	}
	created := held[0].Record
	market := held[0].Market

	// Two readers, two connections, no credential.
	first := publishertest.ReadFeed(t, running.Origin, signals.ChannelFor(publishertest.ServerID), nil)
	second := publishertest.ReadFeed(t, running.Origin, signals.ChannelFor(publishertest.ServerID), nil)
	if first != second {
		t.Fatalf("two readers got different documents:\n%s\n%s", first, second)
	}
	for _, expected := range []string{
		market.MarketID, market.EventID, "jupiter.prediction", "prediction",
		signals.USDCMint, "5000000",
	} {
		if !strings.Contains(first, expected) {
			t.Fatalf("the document a phone reads does not carry %q:\n%s", expected, first)
		}
	}
	// What the document does not carry: the provider's own page, and any field about a side, an
	// amount or a person. The link stays on the publisher's side of the boundary.
	//
	// The words are looked for quoted, because this document's own prose is a market title the
	// provider wrote — "Fed Decision in October?" is a market, and "decision" as a *field* would
	// be this template keeping somebody's.
	for _, absent := range []string{
		"wallet", "amount", "is_yes", "side", "decision", "signature", "subscriber", "result",
	} {
		if strings.Contains(strings.ToLower(first), `"`+absent+`"`) {
			t.Fatalf("the document a phone reads has a %q field:\n%s", absent, first)
		}
	}
	// Since SEE-157 the document does carry two addresses, and only those two: where the
	// provider's own app and site keep this market. Anything else that looked like a link would
	// be a publisher putting one on a phone's screen, which is still refused — so the two are
	// counted rather than searched past.
	page := `"https://jup.ag/prediction/fed-decision-in-october"`
	for _, expected := range []string{
		`{"key":"` + signals.ProviderDeepLink + `","text":` + page + `}`,
		`{"key":"` + signals.ProviderWebURL + `","text":` + page + `}`,
	} {
		if !strings.Contains(first, expected) {
			t.Fatalf("the document a phone reads does not carry %q:\n%s", expected, first)
		}
	}
	if strings.Count(first, "http") != 2 || strings.Count(first, "jup.ag") != 2 {
		t.Fatalf("the document a phone reads carries an address that is not the market's own "+
			"page, which no publisher may put on a phone's screen:\n%s", first)
	}

	// The same document again, which is what a cycle that finds the same market sends.
	status, err := client.Proposal(ctx, signals.Proposal(server, created.Signal))
	if err != nil {
		t.Fatal(err)
	}
	if status != gateway.Unchanged {
		t.Fatalf("the real gateway answered %s to a republication of the identical document; a "+
			"cycle every five minutes would notify every subscriber every five minutes", status)
	}

	// And now the source ends the market: it leaves the feedListing, the reconciler asks the provider
	// about it directly, and the answer — closed — is what withdraws the proposal. The document a
	// phone can read becomes cancelled, so nothing new is executed from it after that.
	provider.closed = true
	ended, err := reconciler.Pass(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if ended.Checked != 1 || ended.Cancelled != 1 {
		t.Fatalf("the cycle checked %d and withdrew %d: %+v", ended.Checked, ended.Cancelled,
			ended)
	}
	if _, err := drain.Pass(ctx); err != nil {
		t.Fatal(err)
	}
	after := publishertest.ReadFeed(t, running.Origin, signals.ChannelFor(publishertest.ServerID), nil)
	if !strings.Contains(after, "PROPOSAL_STATUS_CANCELLED") {
		t.Fatalf("the feed does not show the withdrawal:\n%s", after)
	}
	if !strings.Contains(after, market.MarketID) {
		t.Fatalf("a withdrawn proposal is still readable, and this one is not:\n%s", after)
	}
}

// feedListing is the provider a discovered market comes from, scripted: one open market, and then the
// same market gone from the feedListing and closed when asked about directly.
type feedListing struct {
	closed bool
}

func (l *feedListing) Events(_ context.Context, query jupiter.Query) (jupiter.Page, error) {
	if query.Start > 0 || l.closed {
		return jupiter.Page{}, nil
	}
	return jupiter.Page{Events: []jupiter.Event{{
		EventID:   "POLY-606422",
		Title:     "Fed Decision in October?",
		Category:  "economics",
		Tags:      []string{"economics", "fed-rates"},
		Active:    true,
		SourceURL: "https://jup.ag/prediction/fed-decision-in-october",
		Markets: []jupiter.Market{{
			MarketID:  "POLY-2589813",
			EventID:   "POLY-606422",
			Provider:  "polymarket",
			Title:     "25 bps increase",
			Status:    jupiter.Open,
			CloseTime: noon.Add(72 * time.Hour).Unix(),
		}},
	}}}, nil
}

func (l *feedListing) Market(_ context.Context, id string) (jupiter.Market, error) {
	status := jupiter.Open
	if l.closed {
		status = jupiter.Closed
	}
	return jupiter.Market{
		MarketID:  id,
		EventID:   "POLY-606422",
		Provider:  "polymarket",
		Title:     "25 bps increase",
		Status:    status,
		CloseTime: noon.Add(72 * time.Hour).Unix(),
	}, nil
}
