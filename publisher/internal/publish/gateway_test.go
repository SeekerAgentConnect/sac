package publish

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/discovery"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/jupiter"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/manifest"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/signals"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/store"
)

// This template against the **real** feed gateway, as a separate process, with its own
// database and its own credential — the only test here that proves the two agree rather than
// assuming it (SEE-95).
//
// Opt-in, like the phone's Centrifugo test and for the same reason: it needs a binary that is not
// this module's.
//
//	cd feed-gateway && go build -o /tmp/feed-gateway ./cmd/feed-gateway \
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
			"(see docs/development/publisher.md)")
	}
	running := runGateway(t, binary, "the publisher template's own test")
	origin, directory := running.origin, running.directory

	settings := manifest.Settings{
		ServerID:    server,
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

	client, err := New(Options{
		// The publisher API is its own listener, which is how a deployment keeps publishing off
		// the internet: the manifest still names the read origin, because that is where phones go.
		URL:        running.publishTo,
		Credential: running.credential,
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
	created, _, err := documents.Create(ctx, "key-1", "request-1", signalOf(swap()))
	if err != nil {
		t.Fatal(err)
	}
	if refusal, err := drain.One(ctx, created); err != nil || refusal != nil {
		t.Fatalf("%+v (%v)", refusal, err)
	}

	// Two readers, two connections, no credential: this is what two phones do, and what they get
	// has to be the same bytes. Neither of them can send anything about themselves, and neither
	// appears in what the other reads.
	first := readFeed(t, origin, signals.ChannelFor(server), nil)
	second := readFeed(t, origin, signals.ChannelFor(server), nil)
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
	status, err := client.Proposal(ctx, signals.Proposal(server, created.Signal))
	if err != nil {
		t.Fatal(err)
	}
	if status != Unchanged {
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
	after := readFeed(t, origin, signals.ChannelFor(server), nil)
	if !strings.Contains(after, "PROPOSAL_STATUS_CANCELLED") {
		t.Fatalf("the feed does not show the withdrawal:\n%s", after)
	}
	// And withdrawing again at the same revision is the retry, which is not an event.
	if status, err := client.Withdraw(ctx, created.Signal.ProposalID,
		withdrawn.Signal.Revision); err != nil || status != Unchanged {
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
	running := runGateway(t, binary, "a template pointed at the wrong gateway")

	client, err := New(Options{
		URL:        running.publishTo,
		Credential: running.credential,
		Timeout:    10 * time.Second,
	})
	if err != nil {
		t.Fatal(err)
	}
	// A template configured against the wrong gateway: it publishes to this one and names that
	// one.
	elsewhere := manifest.Settings{
		ServerID:    server,
		GatewayURL:  "https://feeds.example.com",
		Environment: "production",
		Requirement: signals.Swap{}.Requirement(),
	}
	_, err = client.Manifest(context.Background(), manifest.Document(elsewhere, 1))
	refusal, ok := err.(*Refusal)
	if !ok {
		t.Fatalf("expected a refusal, got %v", err)
	}
	if refusal.Problem != "other_gateway" || !refusal.Permanent {
		t.Fatalf("%+v", refusal)
	}
}

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
			"(see docs/development/publisher.md)")
	}
	running := runGateway(t, binary, "the prediction template's own test")

	kind := signals.Prediction{}
	settings := manifest.Settings{
		ServerID:    server,
		GatewayURL:  running.origin,
		Environment: "production",
		Requirement: kind.Requirement(),
		DisplayName: "Prediction desk",
	}
	documents, err := store.Open(filepath.Join(running.directory, "prediction.db"), store.Stamp{
		ServerID:    settings.ServerID,
		Environment: settings.Environment.String(),
		GatewayURL:  settings.GatewayURL,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = documents.Close() }()

	client, err := New(Options{
		URL:        running.publishTo,
		Credential: running.credential,
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
	// a listing this test scripts. Building the statement by hand here would test the gateway
	// against a document no cycle produces, which is the one thing this test exists not to do.
	provider := &listing{}
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
		Now:     func() time.Time { return now },
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
	first := readFeed(t, running.origin, signals.ChannelFor(server), nil)
	second := readFeed(t, running.origin, signals.ChannelFor(server), nil)
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
	for _, absent := range []string{"jup.ag", "http://", "https://"} {
		if strings.Contains(first, absent) {
			t.Fatalf("the document a phone reads carries %q, which no publisher may put on a "+
				"phone's screen:\n%s", absent, first)
		}
	}

	// The same document again, which is what a cycle that finds the same market sends.
	status, err := client.Proposal(ctx, signals.Proposal(server, created.Signal))
	if err != nil {
		t.Fatal(err)
	}
	if status != Unchanged {
		t.Fatalf("the real gateway answered %s to a republication of the identical document; a "+
			"cycle every five minutes would notify every subscriber every five minutes", status)
	}

	// And now the source ends the market: it leaves the listing, the reconciler asks the provider
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
	after := readFeed(t, running.origin, signals.ChannelFor(server), nil)
	if !strings.Contains(after, "PROPOSAL_STATUS_CANCELLED") {
		t.Fatalf("the feed does not show the withdrawal:\n%s", after)
	}
	if !strings.Contains(after, market.MarketID) {
		t.Fatalf("a withdrawn proposal is still readable, and this one is not:\n%s", after)
	}
}

// gateway is the real feed gateway, running as its own process with its own database and a
// credential it issued for this template.
type gateway struct {
	origin string
	// The publisher API, which is a second listener: a deployment that keeps publishing off the
	// internet points a template here and still names the read origin in its manifest.
	publishTo  string
	credential string
	directory  string
}

// runGateway starts one and registers this template with it. Every opt-in test here needs exactly
// this, so it is written once: a difference between two tests' gateways would be a difference
// nobody meant.
func runGateway(t *testing.T, binary, label string) gateway {
	t.Helper()
	control := os.Getenv("SEEKERVAULT_FEED_GATEWAYCTL")
	if control == "" {
		control = filepath.Join(filepath.Dir(binary), "feed-gatewayctl")
	}
	if _, err := os.Stat(control); err != nil {
		t.Skipf("feed-gatewayctl is not beside the gateway (%v); set SEEKERVAULT_FEED_GATEWAYCTL", err)
	}

	directory := t.TempDir()
	database := filepath.Join(directory, "broadcast.db")
	read, publisher := freePort(t), freePort(t)
	origin := fmt.Sprintf("http://127.0.0.1:%d", read)

	// Registering a publisher is a local act with no network surface at all, so the test does what
	// an operator does: it runs the tool.
	registered, err := exec.Command(control, "register", "--database", database,
		"--server", server, "--label", label).CombinedOutput()
	if err != nil {
		t.Fatalf("feed-gatewayctl register: %v\n%s", err, registered)
	}
	issued := credentialFrom(t, string(registered))

	process := exec.Command(binary)
	process.Env = append(os.Environ(),
		"BROADCAST_PUBLIC_URL="+origin,
		"BROADCAST_DATABASE_PATH="+database,
		fmt.Sprintf("BROADCAST_READ_ADDRESS=127.0.0.1:%d", read),
		fmt.Sprintf("BROADCAST_PUBLISHER_ADDRESS=127.0.0.1:%d", publisher),
		// No broker and no push relay: the gateway then holds the documents and answers every
		// read, which is all these tests are about.
		"BROADCAST_STREAM_URL=",
	)
	log := &bytes.Buffer{}
	process.Stdout, process.Stderr = log, log
	if err := process.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = process.Process.Signal(os.Interrupt)
		_, _ = process.Process.Wait()
		if t.Failed() {
			t.Logf("the gateway said:\n%s", log)
		}
	})
	waitFor(t, origin+"/healthz")
	return gateway{
		origin:     origin,
		publishTo:  fmt.Sprintf("http://127.0.0.1:%d", publisher),
		credential: issued,
		directory:  directory,
	}
}

// readFeed reads a channel's proposals the way a phone does: the read API, over plain JSON, with no
// credential at all. It deliberately uses no generated client, because none is compiled for this
// module — and it shows that any Connect client will do.
func readFeed(t *testing.T, origin, channel string, known map[string]any) string {
	t.Helper()
	body := map[string]any{"channel": channel}
	for name, value := range known {
		body[name] = value
	}
	encoded, err := json.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	request, err := http.NewRequest(http.MethodPost,
		origin+"/seekervault.gateway.v1.FeedService/ListProposals", bytes.NewReader(encoded))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Content-Type", "application/json")
	// A reader of its own, so the two readers in the test above share nothing: not a connection,
	// not a cookie jar, not a cache.
	answer, err := (&http.Client{Timeout: 10 * time.Second}).Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = answer.Body.Close() }()
	contents, err := io.ReadAll(answer.Body)
	if err != nil {
		t.Fatal(err)
	}
	if answer.StatusCode != http.StatusOK {
		t.Fatalf("the feed answered %s: %s", answer.Status, contents)
	}
	return string(contents)
}

// credentialFrom reads the credential `feed-gatewayctl register` printed. It is shown once and stored
// only as a hash, so this is the only chance to have it — which is exactly what an operator
// experiences.
func credentialFrom(t *testing.T, printed string) string {
	t.Helper()
	for _, line := range strings.Split(printed, "\n") {
		trimmed := strings.TrimSpace(line)
		// The credential is printed alone, on its own line, after the identifiers.
		if len(trimmed) == 43 && !strings.Contains(trimmed, " ") {
			return trimmed
		}
	}
	t.Fatalf("no credential in:\n%s", printed)
	return ""
}

func freePort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	if err := listener.Close(); err != nil {
		t.Fatal(err)
	}
	return port
}

func waitFor(t *testing.T, address string) {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		answer, err := (&http.Client{Timeout: time.Second}).Get(address)
		if err == nil {
			_ = answer.Body.Close()
			if answer.StatusCode == http.StatusOK {
				return
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("%s never answered", address)
}

// listing is the provider a discovered market comes from, scripted: one open market, and then the
// same market gone from the listing and closed when asked about directly.
type listing struct {
	closed bool
}

func (l *listing) Events(_ context.Context, query jupiter.Query) (jupiter.Page, error) {
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
			CloseTime: now.Add(72 * time.Hour).Unix(),
		}},
	}}}, nil
}

func (l *listing) Market(_ context.Context, id string) (jupiter.Market, error) {
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
		CloseTime: now.Add(72 * time.Hour).Unix(),
	}, nil
}
