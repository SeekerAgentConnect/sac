package gateway_test

import (
	"context"
	"database/sql"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"testing"

	"connectrpc.com/connect"
	"google.golang.org/protobuf/proto"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/proposal/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"

	_ "modernc.org/sqlite"
)

// The anonymous feed path still has no way to be told about a subscriber; none can be smuggled
// into a broadcast document.
func TestNothingAboutASubscriberCanBeSubmittedToAFeed(t *testing.T) {
	gateway := newGateway(t)
	credential := gateway.register(publisherA)
	publisher := gateway.publisher(credential)
	channel := rules.ChannelFor(publisherA)
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 1))

	t.Run("a field the contract does not have", func(t *testing.T) {
		// What a client would send if it believed the gateway kept execution records. There is no
		// such field, and the call is refused rather than partly applied.
		body := `{"proposal":{"serverId":"` + publisherA + `","channel":"` + channel + `",` +
			`"proposalId":"` + proposalB + `","revision":"1","operation":"swap",` +
			`"pluginId":"jupiter.swap","status":"PROPOSAL_STATUS_OPEN",` +
			`"createdAt":"2026-09-17T09:00:00Z","updatedAt":"2026-09-17T09:00:00Z",` +
			`"expiresAt":"2026-09-17T12:00:00Z",` +
			`"wallet":"6xJ8QGkQ6Qx1e8YpQ2CqZ9bJ7jY7N3tFh5T9Jr2vQ4dM",` +
			`"amount":"250000000","signature":"5vJ2","approved":true}}`
		response := gateway.post(gateway.publish,
			"/seekervault.gateway.v1.PublisherService/PublishProposal", body, credential)
		if response.StatusCode != http.StatusBadRequest {
			t.Fatalf("a document with a wallet and an amount in it answered %s", response.Status)
		}
		// And nothing was stored: the whole call was refused, not trimmed and applied.
		if _, err := gateway.feed.GetProposal(context.Background(),
			connect.NewRequest(&gatewayv1.GetProposalRequest{
				Channel: channel, ProposalId: proposalB,
			})); err == nil {
			t.Fatal("the document was stored anyway")
		}
	})

	t.Run("a field it does not understand", func(t *testing.T) {
		// Protobuf keeps what it cannot parse, so refusing is not an option here: the gateway
		// rebuilds every document from the fields it validated, which is what makes an unknown
		// field unable to reach a subscriber (internal/rules).
		smuggled := proposalOf(publisherA, proposalB, 1)
		smuggled.ProtoReflect().SetUnknown([]byte{
			0x9a, 0x06, 0x2d, // field 99, length-delimited, 45 bytes
		})
		smuggled.ProtoReflect().SetUnknown(append(smuggled.ProtoReflect().GetUnknown(),
			[]byte("6xJ8QGkQ6Qx1e8YpQ2CqZ9bJ7jY7N3tFh5T9Jr2vQ4dM ")...))
		gateway.publishProposal(publisher, smuggled)

		detail, err := gateway.feed.GetProposal(context.Background(),
			connect.NewRequest(&gatewayv1.GetProposalRequest{
				Channel: channel, ProposalId: proposalB,
			}))
		if err != nil {
			t.Fatal(err)
		}
		stored := detail.Msg.GetProposal()
		if len(stored.ProtoReflect().GetUnknown()) != 0 {
			t.Fatal("the gateway relayed a field it does not understand")
		}
		bytes, err := proto.Marshal(stored)
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(string(bytes), "6xJ8QGkQ") {
			t.Fatal("what was smuggled in reached a reader")
		}
		// It also never reached the disk, which is the same statement one layer down.
		if strings.Contains(databaseText(t, gateway.path), "6xJ8QGkQ") {
			t.Fatal("what was smuggled in reached the database")
		}
	})

	t.Run("an endpoint for a result", func(t *testing.T) {
		// There is no result endpoint on either public-feed or publisher listener.
		for _, procedure := range []string{
			"/seekervault.gateway.v1.PublisherService/SubmitResult",
			"/seekervault.gateway.v1.FeedService/SubmitResult",
			"/seekervault.request.v1.RequestService/SubmitResult",
			"/seekervault.request.v1.RequestService/PublishWallet",
		} {
			for _, server := range []string{"read", "publish"} {
				target := gateway.read
				if server == "publish" {
					target = gateway.publish
				}
				response := gateway.post(target, procedure, `{}`, credential)
				if response.StatusCode != http.StatusNotFound {
					t.Fatalf("%s on the %s listener answered %s", procedure, server, response.Status)
				}
			}
		}
	})
}

// The separation of the two APIs is a deployment fact and not only a code one: the read listener
// serves no handler that could change anything.
func TestThePublicListenerHasNoWayToWrite(t *testing.T) {
	gateway := newGateway(t)
	credential := gateway.register(publisherA)
	publisher := gateway.publisher(credential)
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 1))

	for _, procedure := range []string{
		"/seekervault.gateway.v1.PublisherService/PublishManifest",
		"/seekervault.gateway.v1.PublisherService/PublishProposal",
		"/seekervault.gateway.v1.PublisherService/CancelProposal",
	} {
		// Even with a valid credential: it is not that the read port refuses the call, it is that
		// there is nothing there to call.
		response := gateway.post(gateway.read, procedure, `{}`, credential)
		if response.StatusCode != http.StatusNotFound {
			t.Fatalf("%s on the read listener answered %s", procedure, response.Status)
		}
	}

	// The read listener does serve the read API, and its health endpoint, and says nothing else.
	for procedure, expected := range map[string]int{
		"/seekervault.gateway.v1.FeedService/ListProposals": http.StatusOK,
		"/seekervault.gateway.v1.FeedService/GetProposal":   http.StatusNotFound, // no such proposal
	} {
		body := `{"channel":"` + rules.ChannelFor(publisherA) + `"}`
		if strings.HasSuffix(procedure, "GetProposal") {
			body = `{"channel":"` + rules.ChannelFor(publisherA) +
				`","proposalId":"` + proposalB + `"}`
		}
		response := gateway.post(gateway.read, procedure, body, "")
		if response.StatusCode != expected {
			t.Fatalf("%s answered %s", procedure, response.Status)
		}
	}
	health, err := gateway.read.Client().Get(gateway.read.URL + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = health.Body.Close() }()
	if health.StatusCode != http.StatusOK {
		t.Fatalf("/healthz answered %s", health.Status)
	}
}

func TestNoCredentialReachesALogLine(t *testing.T) {
	gateway := newGateway(t)
	credential := gateway.register(publisherA)
	publisher := gateway.publisher(credential)
	ctx := context.Background()

	gateway.publishManifest(publisher, manifestOf(publisherA, 1))
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 1))
	gateway.drain()
	// A refused call as well, because that is the one that writes the most about what happened.
	if _, err := publisher.PublishProposal(ctx,
		connect.NewRequest(&gatewayv1.PublishProposalRequest{
			Proposal: proposalOf(publisherA, proposalA, 1, func(p *proposalv1.Proposal) {
				p.Values[1].Text = "999999999"
			}),
		})); err == nil {
		t.Fatal("a contradiction was accepted")
	}
	// And one with a credential that was never issued: the thing that was presented must not be
	// written down either.
	_, _ = gateway.publisher("Bearer-looking-but-not-issued-9aa7").
		PublishManifest(ctx, connect.NewRequest(&gatewayv1.PublishManifestRequest{
			Manifest: manifestOf(publisherA, 2),
		}))

	logs := gateway.logs.text()
	if logs == "" {
		t.Fatal("nothing was logged at all")
	}
	for _, secret := range []string{credential, "Bearer-looking-but-not-issued-9aa7"} {
		if strings.Contains(logs, secret) {
			t.Fatal("a credential was written to the log")
		}
	}
	if !strings.Contains(logs, "revision_conflict") {
		t.Fatalf("the refusal was not logged at all:\n%s", logs)
	}
}

// What the gateway keeps about the people reading a feed: nothing. Not a session, not a
// subscription record, not a count — and the way to show it is to read the file after a few reads
// and see that nothing in it moved.
func TestReadingAFeedWritesNothingDown(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.publisher(gateway.register(publisherA))
	channel := rules.ChannelFor(publisherA)
	ctx := context.Background()
	gateway.publishManifest(publisher, manifestOf(publisherA, 1))
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 1))
	gateway.drain()

	before := rowCounts(t, gateway.path)
	for range 3 {
		gateway.list(channel)
		if _, err := gateway.feed.GetServerManifest(ctx,
			connect.NewRequest(&gatewayv1.GetServerManifestRequest{
				ServerId: publisherA,
			})); err != nil {
			t.Fatal(err)
		}
		if _, err := gateway.feed.GetProposal(ctx,
			connect.NewRequest(&gatewayv1.GetProposalRequest{
				Channel: channel, ProposalId: proposalA,
			})); err != nil {
			t.Fatal(err)
		}
	}
	after := rowCounts(t, gateway.path)
	if fmt.Sprint(before) != fmt.Sprint(after) {
		t.Fatalf("reading changed the store:\n%v\n%v", before, after)
	}
	tables := make([]string, 0, len(after))
	for name := range after {
		tables = append(tables, name)
	}
	sort.Strings(tables)
	expected := "[channel_sequence manifest notice proposal publisher publisher_credential]"
	if fmt.Sprint(tables) != expected {
		t.Fatalf("the store holds %v, expected %s", tables, expected)
	}
}

// rowCounts reads the shape of the database directly, which a test may do and shipped code may
// not (SQL lives only in internal/storage).
func rowCounts(t *testing.T, path string) map[string]int {
	t.Helper()
	database, err := sql.Open("sqlite", "file:"+path+"?mode=ro")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = database.Close() }()
	rows, err := database.Query(
		`SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite_%'`)
	if err != nil {
		t.Fatal(err)
	}
	names := []string{}
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			t.Fatal(err)
		}
		names = append(names, name)
	}
	_ = rows.Close()
	counts := map[string]int{}
	for _, name := range names {
		var count int
		if err := database.QueryRow(`SELECT COUNT(*) FROM "` + name + `"`).Scan(&count); err != nil {
			t.Fatal(err)
		}
		counts[name] = count
	}
	return counts
}

// databaseText is the file's own bytes, for the assertions about what never reached it.
func databaseText(t *testing.T, path string) string {
	t.Helper()
	database, err := sql.Open("sqlite", "file:"+path+"?mode=ro")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = database.Close() }()
	var text strings.Builder
	for _, query := range []string{
		`SELECT CAST(document AS TEXT) FROM proposal`,
		`SELECT CAST(document AS TEXT) FROM manifest`,
	} {
		rows, err := database.Query(query)
		if err != nil {
			t.Fatal(err)
		}
		for rows.Next() {
			var document string
			if err := rows.Scan(&document); err != nil {
				t.Fatal(err)
			}
			text.WriteString(document)
		}
		_ = rows.Close()
	}
	return text.String()
}
