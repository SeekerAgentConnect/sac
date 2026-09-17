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

	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/manifest"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/signals"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/store"
)

// This template against the **real** broadcast gateway, as a separate process, with its own
// database and its own credential — the only test here that proves the two agree rather than
// assuming it (SEE-95).
//
// Opt-in, like the phone's Centrifugo test and for the same reason: it needs a binary that is not
// this module's.
//
//	cd broadcast && go build -o /tmp/broadcast ./cmd/broadcast \
//	                        && go build -o /tmp/broadcastctl ./cmd/broadcastctl
//	cd publisher && SEEKERVAULT_BROADCAST=/tmp/broadcast go test ./internal/publish/ -run Gateway
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
	binary := os.Getenv("SEEKERVAULT_BROADCAST")
	if binary == "" {
		t.Skip("set SEEKERVAULT_BROADCAST to a built broadcast binary to run this " +
			"(see docs/development/publisher.md)")
	}
	control := os.Getenv("SEEKERVAULT_BROADCASTCTL")
	if control == "" {
		control = filepath.Join(filepath.Dir(binary), "broadcastctl")
	}
	if _, err := os.Stat(control); err != nil {
		t.Skipf("broadcastctl is not beside the gateway (%v); set SEEKERVAULT_BROADCASTCTL", err)
	}

	directory := t.TempDir()
	database := filepath.Join(directory, "broadcast.db")
	read, publisher := freePort(t), freePort(t)
	origin := fmt.Sprintf("http://127.0.0.1:%d", read)

	// Registering a publisher is a local act with no network surface at all, so the test does what
	// an operator does: it runs the tool.
	registered, err := exec.Command(control, "register", "--database", database,
		"--server", server, "--label", "the publisher template's own test").CombinedOutput()
	if err != nil {
		t.Fatalf("broadcastctl register: %v\n%s", err, registered)
	}
	issued := credentialFrom(t, string(registered))

	gateway := exec.Command(binary)
	gateway.Env = append(os.Environ(),
		"BROADCAST_PUBLIC_URL="+origin,
		"BROADCAST_DATABASE_PATH="+database,
		fmt.Sprintf("BROADCAST_READ_ADDRESS=127.0.0.1:%d", read),
		fmt.Sprintf("BROADCAST_PUBLISHER_ADDRESS=127.0.0.1:%d", publisher),
		// No broker and no push relay: the gateway then holds the documents and answers every
		// read, which is all this test is about.
		"BROADCAST_STREAM_URL=",
	)
	log := &bytes.Buffer{}
	gateway.Stdout, gateway.Stderr = log, log
	if err := gateway.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = gateway.Process.Signal(os.Interrupt)
		_, _ = gateway.Process.Wait()
		if t.Failed() {
			t.Logf("the gateway said:\n%s", log)
		}
	})
	waitFor(t, origin+"/healthz")

	settings := manifest.Settings{
		ServerID:    server,
		GatewayURL:  origin,
		Environment: "production",
		Requirement: signals.Swap{}.Requirement(),
		DisplayName: "Copy trading desk",
	}
	documents, err := store.Open(filepath.Join(directory, "publisher.db"), store.Stamp{
		ServerID:    settings.ServerID,
		Environment: settings.Environment,
		GatewayURL:  settings.GatewayURL,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = documents.Close() }()

	client, err := New(Options{
		// The publisher API is its own listener, which is how a deployment keeps publishing off
		// the internet: the manifest still names the read origin, because that is where phones go.
		URL:        fmt.Sprintf("http://127.0.0.1:%d", publisher),
		Credential: issued,
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
	binary := os.Getenv("SEEKERVAULT_BROADCAST")
	if binary == "" {
		t.Skip("set SEEKERVAULT_BROADCAST to a built broadcast binary to run this")
	}
	control := os.Getenv("SEEKERVAULT_BROADCASTCTL")
	if control == "" {
		control = filepath.Join(filepath.Dir(binary), "broadcastctl")
	}
	directory := t.TempDir()
	database := filepath.Join(directory, "broadcast.db")
	read, publisher := freePort(t), freePort(t)
	origin := fmt.Sprintf("http://127.0.0.1:%d", read)

	registered, err := exec.Command(control, "register", "--database", database,
		"--server", server).CombinedOutput()
	if err != nil {
		t.Fatalf("broadcastctl register: %v\n%s", err, registered)
	}
	issued := credentialFrom(t, string(registered))

	gateway := exec.Command(binary)
	gateway.Env = append(os.Environ(),
		"BROADCAST_PUBLIC_URL="+origin,
		"BROADCAST_DATABASE_PATH="+database,
		fmt.Sprintf("BROADCAST_READ_ADDRESS=127.0.0.1:%d", read),
		fmt.Sprintf("BROADCAST_PUBLISHER_ADDRESS=127.0.0.1:%d", publisher),
		"BROADCAST_STREAM_URL=",
	)
	if err := gateway.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = gateway.Process.Signal(os.Interrupt)
		_, _ = gateway.Process.Wait()
	})
	waitFor(t, origin+"/healthz")

	client, err := New(Options{
		URL:        fmt.Sprintf("http://127.0.0.1:%d", publisher),
		Credential: issued,
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

// credentialFrom reads the credential `broadcastctl register` printed. It is shown once and stored
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
