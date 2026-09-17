package gateway_test

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/config"
	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/proposal/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/rules"
)

// The cross-runtime fixtures for the gateway's own contract (proto/fixtures/seekervault/gateway/v1,
// docs/protocol.md#cross-runtime-fixtures).
//
// They are not written by hand and then hoped about: this test runs the scenario they describe
// through the real service and requires each committed fixture to be exactly what the gateway
// answered. The phone's own tests then read the same files and require its validators to accept
// what is in them (GatewayProtocolFixturesTest). So the loop is closed at both ends — the gateway
// serves these documents, and the phone accepts these documents — without the two ever having to
// talk to each other, which is what SEE-91 will be for.
//
// The gateway origin here is the one the phone's manifest fixtures already use
// (https://gateway.example.com), and the documents are the ones its proposal fixtures already hold,
// so nothing has to be translated between the two sides.
const fixtureGateway = "https://gateway.example.com"

var (
	predictionCreated = time.Date(2026, 9, 17, 8, 0, 0, 0, time.UTC)
	predictionEnded   = time.Date(2026, 9, 17, 8, 45, 0, 0, time.UTC)
)

func TestTheFixturesAreWhatTheGatewayServes(t *testing.T) {
	gateway := newGateway(t, func(settings *config.Config) {
		settings.PublicURL = fixtureGateway
	})
	publisher := gateway.publisher(gateway.register(publisherA))
	channel := rules.ChannelFor(publisherA)
	ctx := context.Background()

	// The publisher's own settings: the manifest the phone's feed fixture holds.
	gateway.at(predictionCreated)
	gateway.publishManifest(publisher, &serverv1.ServerManifest{
		ServerId:         publisherA,
		ProtocolVersion:  rules.Protocol,
		SettingsRevision: 12,
		Mode:             serverv1.ConnectionMode_CONNECTION_MODE_GATEWAY_FEED,
		RequiredPlugins: []*serverv1.PluginRequirement{
			{PluginId: "jupiter.swap", MinContract: 1, MaxContract: 1},
			{PluginId: "jupiter.prediction", MinContract: 1, MaxContract: 2},
		},
		Environments: []serverv1.ServerEnvironment{
			serverv1.ServerEnvironment_SERVER_ENVIRONMENT_PRODUCTION,
			serverv1.ServerEnvironment_SERVER_ENVIRONMENT_SANDBOX,
		},
		DisplayName: "Копи-трейдинг 📈",
		Reference: &serverv1.ServerManifest_Feed{Feed: &serverv1.GatewayFeed{
			GatewayUrl: fixtureGateway,
			Channel:    channel,
		}},
	})

	// A swap, exactly as the phone's Proposal/open fixture holds it.
	gateway.publishProposal(publisher, &proposalv1.Proposal{
		ServerId:      publisherA,
		Channel:       channel,
		ProposalId:    proposalA,
		Revision:      4,
		Operation:     "swap",
		PluginId:      "jupiter.swap",
		Status:        proposalv1.ProposalStatus_PROPOSAL_STATUS_OPEN,
		CreatedAt:     timestamppb.New(published),
		UpdatedAt:     timestamppb.New(published.Add(30 * time.Minute)),
		ExpiresAt:     timestamppb.New(published.Add(time.Hour)),
		PublisherNote: "Ротация в USDC 📉",
		Values: []*proposalv1.ProposalValue{
			{Key: "input_mint", Text: "So11111111111111111111111111111111111111112"},
			{Key: "output_mint", Text: "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"},
			{Key: "published_price", Text: "139420000"},
			{Key: "slippage_bps", Text: "50"},
		},
	})

	// A prediction the publisher then withdraws. The gateway writes the update time on a
	// withdrawal, so this is also where the phone's Proposal/cancelled fixture comes from: the
	// same document, produced by the thing that actually produces it.
	gateway.publishProposal(publisher, &proposalv1.Proposal{
		ServerId:   publisherA,
		Channel:    channel,
		ProposalId: proposalB,
		Revision:   8,
		Operation:  "prediction",
		PluginId:   "jupiter.prediction",
		Status:     proposalv1.ProposalStatus_PROPOSAL_STATUS_OPEN,
		CreatedAt:  timestamppb.New(predictionCreated),
		UpdatedAt:  timestamppb.New(predictionCreated),
		ExpiresAt:  timestamppb.New(published.Add(3 * time.Hour)),
		Values: []*proposalv1.ProposalValue{
			{Key: "market", Text: "SOL above 200 on 2026-10-01"},
		},
	})
	// A third proposal, so that a page of two has somewhere to continue from. Its ID sorts after
	// both of the others, which is why it is not in the page itself.
	gateway.publishProposal(publisher, proposalOf(publisherA,
		"d4c3b2a1-f6e5-4897-9dcb-ba9876543210", 1))

	gateway.at(predictionEnded)
	if _, err := publisher.CancelProposal(ctx,
		connect.NewRequest(&gatewayv1.CancelProposalRequest{
			ProposalId: proposalB,
			Revision:   9,
		})); err != nil {
		t.Fatal(err)
	}

	manifest, err := gateway.feed.GetServerManifest(ctx,
		connect.NewRequest(&gatewayv1.GetServerManifestRequest{ServerId: publisherA}))
	if err != nil {
		t.Fatal(err)
	}
	sameAsFixture(t, "GetServerManifestResponse", "manifest", manifest.Msg)

	cached, err := gateway.feed.GetServerManifest(ctx,
		connect.NewRequest(&gatewayv1.GetServerManifestRequest{
			ServerId:              publisherA,
			KnownSettingsRevision: 12,
		}))
	if err != nil {
		t.Fatal(err)
	}
	sameAsFixture(t, "GetServerManifestResponse", "unchanged", cached.Msg)

	page := gateway.list(channel, func(request *gatewayv1.ListProposalsRequest) {
		request.PageSize = 2
	})
	sameAsFixture(t, "ListProposalsResponse", "page", page)

	current := gateway.list(channel, func(request *gatewayv1.ListProposalsRequest) {
		request.KnownSnapshotSequence = page.GetSnapshotSequence()
	})
	sameAsFixture(t, "ListProposalsResponse", "unchanged", current)

	detail, err := gateway.feed.GetProposal(ctx, connect.NewRequest(&gatewayv1.GetProposalRequest{
		Channel:    channel,
		ProposalId: proposalB,
	}))
	if err != nil {
		t.Fatal(err)
	}
	sameAsFixture(t, "GetProposalResponse", "cancelled", detail.Msg)

	// And the withdrawn document is the one the proposal package's own fixture holds, byte for
	// byte: what the gateway writes on a withdrawal is what the phone's tests already read.
	withdrawn := &proposalv1.Proposal{}
	if err := proto.Unmarshal(fixtureOf(t,
		filepath.Join("proposal", "v1", "Proposal", "cancelled.binpb")), withdrawn); err != nil {
		t.Fatal(err)
	}
	if !proto.Equal(detail.Msg.GetProposal(), withdrawn) {
		t.Fatalf("the gateway's withdrawal is not the document the phone's fixture holds:\n%v",
			detail.Msg.GetProposal())
	}
}

// sameAsFixture requires the committed fixture to be the message the gateway answered with.
//
// The comparison is on messages rather than on bytes, because the fixture's JSON is a source file
// that Prettier formats and protojson would write differently. The bytes are checked elsewhere, by
// the two runtimes that read the .binpb beside it: `buf convert` writes it with Go's protobuf, and
// the phone's unit tests parse it with Android's.
func sameAsFixture(t *testing.T, message, name string, served proto.Message) {
	t.Helper()
	path := filepath.Join("fixtures", message, name+".json")
	expected := served.ProtoReflect().New().Interface()
	if err := protojson.Unmarshal(fixtureBytes(t, filepath.Join(message, name+".json")),
		expected); err != nil {
		t.Fatalf("%s: %v", path, err)
	}
	if !proto.Equal(served, expected) {
		answered, _ := protojson.MarshalOptions{Multiline: true, Indent: "  "}.Marshal(served)
		t.Fatalf("proto/fixtures/seekervault/gateway/v1/%s/%s.json is not what the gateway "+
			"answered with:\n%s", message, name, answered)
	}
}

func fixtureBytes(t *testing.T, relative string) []byte {
	t.Helper()
	return fixtureOf(t, filepath.Join("gateway", "v1", relative))
}

// fixtureOf reads any package's fixture, from the repository root.
func fixtureOf(t *testing.T, relative string) []byte {
	t.Helper()
	content, err := os.ReadFile(filepath.Join(
		"..", "..", "..", "proto", "fixtures", "seekervault", relative))
	if err != nil {
		t.Fatal(err)
	}
	return content
}
