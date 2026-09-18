package drive

import (
	"context"
	"os"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/loadtest/internal/deploy"
	"github.com/BrRenat/SeekerAgentWallet/loadtest/internal/listen"
)

// The two patterns the gateway holds every document to, copied from `internal/rules/rules.go`
// deliberately rather than imported: that package is the gateway's, and a harness that imported its
// regexes could not notice the two disagreeing. A gateway that tightened either rule would fail
// TestTransport… with `bad_value`, which is how this copy is kept honest.
var (
	operation = regexp.MustCompile(`^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$`)
	uuid      = regexp.MustCompile(
		`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
)

// The transport, end to end, on the smallest run there is (SEE-99).
//
// It is the test that says the harness is measuring the real path: the shipped gateway, the pinned
// broker on the shipped configuration, a publisher `broadcastctl` registered, a ticket the gateway
// minted, and one document arriving over the unidirectional gRPC stream. Every number in
// `docs/testing/see-99.md` rests on this working, so it is a test rather than a note.
//
// It is opt-in on the binaries, like every other check in this repository that needs a service:
//
//	SEEKERVAULT_BROADCAST=… SEEKERVAULT_BROADCASTCTL=… SEEKERVAULT_CENTRIFUGO=… \
//	  go test ./internal/drive/ -run Transport -v
func TestTransportDeliversWhatWasPublished(t *testing.T) {
	binaries := deploy.FromEnvironment()
	if binaries.Gateway == "" || binaries.Control == "" {
		t.Skip("set SEEKERVAULT_BROADCAST and SEEKERVAULT_BROADCASTCTL to the built binaries")
	}
	if binaries.Broker == "" {
		t.Skip("set SEEKERVAULT_CENTRIFUGO to the pinned broker to run the stream")
	}
	ctx, stop := context.WithTimeout(context.Background(), 90*time.Second)
	defer stop()

	deployment, err := deploy.Start(ctx, deploy.Options{Binaries: binaries, BrokerNodes: 1})
	if err != nil {
		t.Fatal(err)
	}
	defer deployment.Close()

	server := ID("transport-test")
	credential, err := deployment.Register(server, "the transport test")
	if err != nil {
		t.Fatal(err)
	}
	documents := NewSynthetic(server, deployment.Origin, 512, 1)
	writer := NewWriter(deployment.Publish, server, credential)
	if _, err := writer.Manifest(ctx, documents.Manifest(1, "load test")); err != nil {
		t.Fatalf("publishing a manifest: %v (%s)\n%s",
			err, Problem(err), deployment.Gateway.Output())
	}

	// The ticket names both channels, and the harness never derives the transport's name: a
	// listener that built it itself and got it wrong would sit on a silent connection.
	reader := NewReader(deployment.Origin, "198.51.100.1")
	ticket, granted, lifetime, err := reader.Ticket(ctx, []string{documents.Channel()})
	if err != nil {
		t.Fatalf("asking for a ticket: %v\n%s", err, deployment.Gateway.Output())
	}
	if ticket == "" || lifetime <= 0 {
		t.Fatalf("the gateway granted %q for %s", ticket, lifetime)
	}
	streamChannel, ok := granted[documents.Channel()]
	if !ok {
		t.Fatalf("the grant does not cover %s: %v", documents.Channel(), granted)
	}
	if expected := deploy.StreamChannelName(documents.Channel()); streamChannel != expected {
		t.Fatalf("the broker's name for the channel is %q, and this harness expected %q",
			streamChannel, expected)
	}

	events := make(chan listen.Event, 16)
	client := listen.Dial(deployment.Nodes[0].Stream)
	defer client.Close()
	listening, done := context.WithCancel(ctx)
	defer done()
	failed := make(chan error, 1)
	go func() {
		failed <- client.Consume(listening, listen.Options{Ticket: ticket}, func(event listen.Event) {
			events <- event
		})
	}()

	opened := next(t, events, listen.Opened)
	// The connect answer's `node` field is empty on the pinned release's unidirectional transport,
	// which is why a run with two nodes asks the brokers themselves where their clients are
	// (deploy.Clients) instead of asking the listeners. Recorded here so the next person does not
	// spend an afternoon on it.
	if opened.Node != "" {
		t.Logf("the connect answer named node %q", opened.Node)
	}
	subscription, subscribed := opened.Subscriptions[streamChannel]
	if !subscribed {
		t.Fatalf("the stream opened without %s: %v", streamChannel, opened.Subscriptions)
	}
	if !subscription.Recoverable {
		t.Fatal("the channel is not recoverable: the shipped configuration says it must be")
	}

	// Published after the stream is open, so what arrives is the live fan-out rather than a replay.
	proposal := documents.Proposal(ID("transport-test/1"), 1, time.Now(), time.Hour)
	if _, _, err := writer.Publish(ctx, proposal); err != nil {
		t.Fatalf("publishing: %v (%s)\n%s", err, Problem(err), deployment.Gateway.Output())
	}
	published := next(t, events, listen.Published)
	if published.Document.GetProposal().GetProposalId() != proposal.GetProposalId() {
		t.Fatalf("the stream carried %q", published.Document.GetProposal().GetProposalId())
	}
	if published.Offset == 0 {
		t.Fatal("the publication has no offset, so nothing could recover from it")
	}

	done()
	if err := <-failed; err != nil {
		t.Fatalf("the stream failed: %v\n%s", err, deployment.Nodes[0].Output())
	}
}

// next is the first event of a kind, or a failure that says what did arrive.
func next(t *testing.T, events <-chan listen.Event, kind listen.Kind) listen.Event {
	t.Helper()
	deadline := time.After(30 * time.Second)
	var seen []listen.Event
	for {
		select {
		case event := <-events:
			if event.Kind == kind {
				return event
			}
			seen = append(seen, event)
			if event.Kind == listen.Closed {
				t.Fatalf("the broker closed the stream: %d %s (after %d events)",
					event.Code, event.Reason, len(seen))
			}
		case <-deadline:
			t.Fatalf("no event of kind %d arrived; %d others did", kind, len(seen))
		}
	}
}

// Everything above needs the binaries. This one needs nothing, and it is the check that the
// documents a run publishes are documents the gateway's own rules accept — without a gateway, so
// that a broken profile fails in `pnpm check:loadtest` rather than at scale.
func TestSyntheticFitsTheRules(t *testing.T) {
	documents := NewSynthetic(ID("rules"), "http://127.0.0.1:8090", MostPayload*2, 7)
	proposal := documents.Proposal(ID("rules/1"), 3, time.Now(), time.Hour)
	if got := len(proposal.GetPublisherNote()); got > mostNoteBytes {
		t.Fatalf("the note is %d bytes", got)
	}
	if got := len(proposal.GetValues()); got > mostValues {
		t.Fatalf("there are %d values", got)
	}
	keys := map[string]bool{}
	for _, value := range proposal.GetValues() {
		if len(value.GetText()) > mostValueTextBytes {
			t.Fatalf("%s carries %d bytes", value.GetKey(), len(value.GetText()))
		}
		if keys[value.GetKey()] {
			t.Fatalf("%s appears twice", value.GetKey())
		}
		keys[value.GetKey()] = true
		// A term key is an operation name, and this is the gateway's own rule for one
		// (internal/rules.IsOperation, and the phone's isOperationId). It is checked here rather
		// than only against a running gateway because the run that found it the other way round
		// had every single document refused with `bad_value`.
		if !operation.MatchString(value.GetKey()) {
			t.Fatalf("%q is not an operation name", value.GetKey())
		}
		if strings.TrimSpace(value.GetText()) != value.GetText() {
			t.Fatalf("%s has text with whitespace at its edges", value.GetKey())
		}
	}
	for _, name := range []string{Operation, Plugin} {
		if !operation.MatchString(name) {
			t.Fatalf("%q is not an operation name", name)
		}
	}
	if !strings.Contains(Plugin, ".") {
		t.Fatalf("%q is not a plugin id: one has to have a dot in it", Plugin)
	}
	if !uuid.MatchString(documents.Channel()[len("server/"):]) {
		t.Fatalf("%q is not a server id", documents.Channel())
	}
	if !proposal.GetExpiresAt().AsTime().After(proposal.GetCreatedAt().AsTime()) {
		t.Fatal("it expires before it was created")
	}
	if proposal.GetUpdatedAt().AsTime().Before(proposal.GetCreatedAt().AsTime()) {
		t.Fatal("it was updated before it was created")
	}
	if _, set := os.LookupEnv("SEEKERVAULT_BROADCAST"); !set {
		t.Log("the gateway's own acceptance of these documents is TestTransport…, which is opt-in")
	}
}
