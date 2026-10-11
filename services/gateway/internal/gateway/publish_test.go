package gateway_test

import (
	"context"
	"testing"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/config"
	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/proposal/v1"
	requestv2 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/request/v2"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
)

func TestAProtocolOneRowMigratesForwardWithoutARevisionConflictOrDowngrade(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.publisher(gateway.register(publisherA))
	gateway.publishManifest(publisher, manifestOf(publisherA, 1))
	legacy := proposalOf(publisherA, proposalA, 1)
	gateway.publishProposal(publisher, legacy)

	common := requestOf(publisherA, proposalA, 1, serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET)
	common.Presentation.Title = "Swap SOL for USDC"
	common.OwnerInputs = []*requestv2.OwnerInput{{
		Key: "input_amount", Label: "Amount",
		Kind: requestv2.OwnerInputKind_OWNER_INPUT_KIND_AMOUNT, Required: true, Minimum: "1",
	}}
	answer, err := publisher.PublishRequest(context.Background(),
		connect.NewRequest(&gatewayv1.PublishRequestRequest{Request: common}))
	if err != nil || answer.Msg.GetStatus() != gatewayv1.PublishStatus_PUBLISH_STATUS_UNCHANGED {
		t.Fatalf("same legacy revision did not migrate compatibly: answer=%v err=%v", answer, err)
	}
	// The old row stays byte-stable at the same revision, so a phone cannot see changed terms under
	// one revision — a network included, which is a change of terms (SEE-185). Its common read is
	// the documented protocol-1 adapter: the legacy contract, with no network, so not executable.
	held, err := gateway.feed.GetRequest(context.Background(), connect.NewRequest(
		&gatewayv1.GetRequestRequest{Channel: rules.ChannelFor(publisherA), RequestId: proposalA}))
	if err != nil || held.Msg.GetRequest().GetPresentation().GetTitle() != "swap" ||
		len(held.Msg.GetRequest().GetOwnerInputs()) != 0 ||
		held.Msg.GetRequest().GetContractVersion() != rules.LegacyRequestContract ||
		held.Msg.GetRequest().GetAction().GetNetwork() != serverv1.SolanaNetwork_SOLANA_NETWORK_UNSPECIFIED {
		t.Fatalf("legacy row changed under revision one: held=%v err=%v", held, err)
	}

	common.Lifecycle.Revision = 2
	common.Lifecycle.UpdatedAt = timestamppb.New(published.Add(time.Hour))
	answer, err = publisher.PublishRequest(context.Background(),
		connect.NewRequest(&gatewayv1.PublishRequestRequest{Request: common}))
	if err != nil || answer.Msg.GetStatus() != gatewayv1.PublishStatus_PUBLISH_STATUS_STORED {
		t.Fatalf("higher common revision was not stored: answer=%v err=%v", answer, err)
	}
	held, err = gateway.feed.GetRequest(context.Background(), connect.NewRequest(
		&gatewayv1.GetRequestRequest{Channel: rules.ChannelFor(publisherA), RequestId: proposalA}))
	if err != nil || held.Msg.GetRequest().GetPresentation().GetTitle() != "Swap SOL for USDC" ||
		len(held.Msg.GetRequest().GetOwnerInputs()) != 1 ||
		held.Msg.GetRequest().GetAction().GetNetwork() != serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET {
		t.Fatalf("common revision was not preserved: held=%v err=%v", held, err)
	}

	_, err = publisher.PublishProposal(context.Background(), connect.NewRequest(
		&gatewayv1.PublishProposalRequest{Proposal: proposalOf(publisherA, proposalA, 3)}))
	refused(t, err, connect.CodeInvalidArgument,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_PROTOCOL)
}

func TestCancelProposalKeepsACommonRowsPresentationAndInputs(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.publisher(gateway.register(publisherA))
	gateway.publishManifest(publisher, manifestOf(publisherA, 1))
	common := requestOf(publisherA, proposalA, 1, serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET)
	common.Presentation.Title = "Swap SOL for USDC"
	common.OwnerInputs = []*requestv2.OwnerInput{{
		Key: "input_amount", Label: "Amount",
		Kind: requestv2.OwnerInputKind_OWNER_INPUT_KIND_AMOUNT, Required: true, Minimum: "1",
	}}
	if _, err := publisher.PublishRequest(context.Background(),
		connect.NewRequest(&gatewayv1.PublishRequestRequest{Request: common})); err != nil {
		t.Fatal(err)
	}

	if _, err := publisher.CancelProposal(context.Background(),
		connect.NewRequest(&gatewayv1.CancelProposalRequest{ProposalId: proposalA, Revision: 2})); err != nil {
		t.Fatal(err)
	}
	held, err := gateway.feed.GetRequest(context.Background(), connect.NewRequest(
		&gatewayv1.GetRequestRequest{Channel: rules.ChannelFor(publisherA), RequestId: proposalA}))
	if err != nil {
		t.Fatal(err)
	}
	got := held.Msg.GetRequest()
	if got.GetPresentation().GetTitle() != "Swap SOL for USDC" ||
		len(got.GetOwnerInputs()) != 1 ||
		got.GetLifecycle().GetStatus() != requestv2.RequestStatus_REQUEST_STATUS_CANCELLED ||
		got.GetLifecycle().GetRevision() != 2 ||
		got.GetAction().GetNetwork() != serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET {
		t.Fatalf("common cancellation lost source fields: %+v", got)
	}
}

// The acceptance this whole service is for: two publishers publish to their own channels, and
// neither can touch the other's settings or proposals. Nothing about it depends on them behaving —
// the credential says which server the caller is, and every document is checked against that.
func TestTwoPublishersCannotReachEachOther(t *testing.T) {
	gateway := newGateway(t)
	first := gateway.publisher(gateway.register(publisherA))
	second := gateway.publisher(gateway.register(publisherB))
	ctx := context.Background()

	gateway.publishManifest(first, manifestOf(publisherA, 1))
	gateway.publishProposal(first, proposalOf(publisherA, proposalA, 1))
	gateway.publishManifest(second, manifestOf(publisherB, 1))

	t.Run("a manifest for another server", func(t *testing.T) {
		_, err := second.PublishManifest(ctx, connect.NewRequest(&gatewayv1.PublishManifestRequest{
			Manifest: manifestOf(publisherA, 2),
		}))
		refused(t, err, connect.CodePermissionDenied,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_SERVER)
	})

	t.Run("a proposal on another channel", func(t *testing.T) {
		_, err := second.PublishProposal(ctx, connect.NewRequest(&gatewayv1.PublishProposalRequest{
			Proposal: proposalOf(publisherA, proposalA, 2),
		}))
		refused(t, err, connect.CodePermissionDenied,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_SERVER)
	})

	t.Run("its own identity on another channel", func(t *testing.T) {
		// The clumsier version of the same attempt: the caller's own server ID, with the other
		// publisher's channel written in by hand.
		_, err := second.PublishProposal(ctx, connect.NewRequest(&gatewayv1.PublishProposalRequest{
			Proposal: proposalOf(publisherB, proposalA, 1, func(p *proposalv1.Proposal) {
				p.Channel = rules.ChannelFor(publisherA)
			}),
		}))
		refused(t, err, connect.CodePermissionDenied,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_FOREIGN_CHANNEL)
	})

	t.Run("withdrawing another publisher's proposal", func(t *testing.T) {
		// A withdrawal names no channel at all, so the closest a publisher can come is naming the
		// other's proposal ID — which is simply not on its own channel.
		_, err := second.CancelProposal(ctx, connect.NewRequest(&gatewayv1.CancelProposalRequest{
			ProposalId: proposalA,
			Revision:   9,
		}))
		refused(t, err, connect.CodeNotFound,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_PROPOSAL)
	})

	// And after all of that, the first publisher's documents are exactly as it left them.
	manifest, err := gateway.feed.GetServerManifest(ctx,
		connect.NewRequest(&gatewayv1.GetServerManifestRequest{ServerId: publisherA}))
	if err != nil {
		t.Fatal(err)
	}
	if !proto.Equal(manifest.Msg.GetManifest(), manifestOf(publisherA, 1)) {
		t.Fatalf("another publisher changed a manifest:\n%v", manifest.Msg.GetManifest())
	}
	page := gateway.list(rules.ChannelFor(publisherA))
	if len(page.GetProposals()) != 1 ||
		page.GetProposals()[0].GetStatus() != proposalv1.ProposalStatus_PROPOSAL_STATUS_OPEN ||
		page.GetProposals()[0].GetRevision() != 1 {
		t.Fatalf("another publisher changed a proposal:\n%v", page.GetProposals())
	}
}

func TestACredentialIsTheWholeOfTheGrant(t *testing.T) {
	gateway := newGateway(t)
	credential := gateway.register(publisherA)
	ctx := context.Background()
	request := func() *connect.Request[gatewayv1.PublishManifestRequest] {
		return connect.NewRequest(&gatewayv1.PublishManifestRequest{
			Manifest: manifestOf(publisherA, 1),
		})
	}

	t.Run("no credential", func(t *testing.T) {
		_, err := gateway.anonymous().PublishManifest(ctx, request())
		refused(t, err, connect.CodeUnauthenticated,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_UNAUTHENTICATED)
	})

	t.Run("a credential that was never issued", func(t *testing.T) {
		_, err := gateway.publisher("not-a-credential").PublishManifest(ctx, request())
		// The same answer as no credential at all: a caller learns that it may not publish, and
		// never whether what it presented used to work.
		refused(t, err, connect.CodeUnauthenticated,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_UNAUTHENTICATED)
	})

	t.Run("the one that was", func(t *testing.T) {
		if answer := gateway.publishManifest(gateway.publisher(credential),
			manifestOf(publisherA, 1)); answer.GetStatus() !=
			gatewayv1.PublishStatus_PUBLISH_STATUS_STORED {
			t.Fatalf("a valid credential published %v", answer.GetStatus())
		}
	})

	t.Run("one that was rotated", func(t *testing.T) {
		second := gateway.register(publisherA) // rotation: a second credential for one publisher
		for _, one := range []string{credential, second} {
			if answer := gateway.publishManifest(gateway.publisher(one),
				manifestOf(publisherA, 1)); answer.GetStatus() !=
				gatewayv1.PublishStatus_PUBLISH_STATUS_UNCHANGED {
				t.Fatalf("a rotated credential answered %v", answer.GetStatus())
			}
		}
		// Retiring the first one leaves the second publishing, which is what makes rotation
		// something that can be done without an outage.
		if _, err := gateway.documents.Revoke(ctx, handleOf(credential),
			gateway.now()); err != nil {
			t.Fatal(err)
		}
		_, err := gateway.publisher(credential).PublishManifest(ctx, request())
		refused(t, err, connect.CodeUnauthenticated,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_UNAUTHENTICATED)
		if answer := gateway.publishManifest(gateway.publisher(second),
			manifestOf(publisherA, 2)); answer.GetStatus() !=
			gatewayv1.PublishStatus_PUBLISH_STATUS_STORED {
			t.Fatalf("revoking one credential stopped the other: %v", answer.GetStatus())
		}
	})
}

// A manifest names no environment since SEE-185 — preview or execute is the owner's choice on the
// phone — and one feed may publish signals for every network it declares, one after another. Each
// signal names its own network, and a network the held manifest does not declare is refused, as is
// any signal from a publisher with no manifest here at all.
func TestASignalRunsOnANetworkItsManifestDeclares(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.publisher(gateway.register(publisherA))
	ctx := context.Background()
	mainnet := serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET
	devnet := serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET
	testnet := serverv1.SolanaNetwork_SOLANA_NETWORK_TESTNET
	publish := func(request *requestv2.Request) (*connect.Response[gatewayv1.PublishRequestResponse], error) {
		return publisher.PublishRequest(ctx, connect.NewRequest(&gatewayv1.PublishRequestRequest{Request: request}))
	}

	// No manifest yet: no network has been declared, so none is accepted.
	_, err := publish(requestOf(publisherA, proposalA, 1, mainnet))
	detail := refused(t, err, connect.CodeFailedPrecondition,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_NETWORK)
	if detail.GetField() != "action.network" {
		t.Fatalf("the refusal named %q", detail.GetField())
	}

	if answer := gateway.publishManifest(publisher, manifestOf(publisherA, 1,
		func(m *serverv1.ServerManifest) {
			m.GetFeed().SupportedNetworks = []serverv1.SolanaNetwork{devnet, mainnet}
		})); answer.GetStatus() != gatewayv1.PublishStatus_PUBLISH_STATUS_STORED {
		t.Fatalf("a manifest with no environment answered %v", answer.GetStatus())
	}

	// Two signals, one per declared network, both accepted and each served with its own.
	for requestID, network := range map[string]serverv1.SolanaNetwork{proposalA: mainnet, proposalB: devnet} {
		answer, err := publish(requestOf(publisherA, requestID, 1, network))
		if err != nil || answer.Msg.GetStatus() != gatewayv1.PublishStatus_PUBLISH_STATUS_STORED {
			t.Fatalf("a %v signal answered %v (%v)", network, answer, err)
		}
		held, err := gateway.feed.GetRequest(ctx, connect.NewRequest(&gatewayv1.GetRequestRequest{
			Channel: rules.ChannelFor(publisherA), RequestId: requestID,
		}))
		if err != nil || held.Msg.GetRequest().GetAction().GetNetwork() != network ||
			held.Msg.GetRequest().GetContractVersion() != rules.RequestContract {
			t.Fatalf("a %v signal is served as %v (%v)", network, held, err)
		}
	}

	// A network the manifest does not declare is refused, and nothing is written.
	undeclared := "d4c3b2a1-f6e5-4897-9dcb-ba9876543210"
	_, err = publish(requestOf(publisherA, undeclared, 1, testnet))
	refused(t, err, connect.CodeFailedPrecondition,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_NETWORK)
	_, err = gateway.feed.GetRequest(ctx, connect.NewRequest(&gatewayv1.GetRequestRequest{
		Channel: rules.ChannelFor(publisherA), RequestId: undeclared,
	}))
	refused(t, err, connect.CodeNotFound, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_PROPOSAL)

	// A signal that names no network, and one in the legacy contract, are not publications at all.
	_, err = publish(requestOf(publisherA, undeclared, 1, serverv1.SolanaNetwork_SOLANA_NETWORK_UNSPECIFIED))
	refused(t, err, connect.CodeInvalidArgument, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_NETWORK)
	_, err = publish(requestOf(publisherA, undeclared, 1, mainnet, func(r *requestv2.Request) {
		r.ContractVersion = rules.LegacyRequestContract
	}))
	refused(t, err, connect.CodeInvalidArgument, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_PROTOCOL)

	// Dropping a network at a higher revision takes effect for the next signal: it is decided
	// against the manifest held when the signal is stored.
	gateway.publishManifest(publisher, manifestOf(publisherA, 2))
	_, err = publish(requestOf(publisherA, proposalB, 2, devnet, func(r *requestv2.Request) {
		r.Lifecycle.UpdatedAt = timestamppb.New(published.Add(time.Hour))
	}))
	refused(t, err, connect.CodeFailedPrecondition,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_NETWORK)
}

// The protocol-1 views cannot carry a network, and a protocol-1 client executes what it reads on
// whichever network its connection is bound to — so a network-declaring signal is not in them at
// all, while a proposal published through the legacy method is in both views, network-less and
// readable, exactly as before (SEE-185).
func TestTheLegacyViewsNeverOfferANetworkDeclaringSignal(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.publisher(gateway.register(publisherA))
	ctx := context.Background()
	channel := rules.ChannelFor(publisherA)
	gateway.publishManifest(publisher, manifestOf(publisherA, 1))
	if _, err := publisher.PublishRequest(ctx, connect.NewRequest(&gatewayv1.PublishRequestRequest{
		Request: requestOf(publisherA, proposalA, 1, serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET),
	})); err != nil {
		t.Fatal(err)
	}
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalB, 1))

	// One row per page, so the left-out signal falls on a page of its own and the walk still
	// reaches the proposal after it.
	var listed []string
	token := ""
	for {
		page := gateway.list(channel, func(request *gatewayv1.ListProposalsRequest) {
			request.PageSize, request.PageToken = 1, token
		})
		for _, proposal := range page.GetProposals() {
			listed = append(listed, proposal.GetProposalId())
		}
		if token = page.GetNextPageToken(); token == "" {
			break
		}
	}
	if len(listed) != 1 || listed[0] != proposalB {
		t.Fatalf("the protocol-1 view listed %v", listed)
	}
	_, err := gateway.feed.GetProposal(ctx, connect.NewRequest(&gatewayv1.GetProposalRequest{
		Channel: channel, ProposalId: proposalA,
	}))
	refused(t, err, connect.CodeNotFound, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_PROPOSAL)

	// The common view holds both: the signal with its network, and the legacy proposal as the
	// legacy contract with none — readable, and never executable on a phone.
	all, err := gateway.feed.ListRequests(ctx, connect.NewRequest(&gatewayv1.ListRequestsRequest{Channel: channel}))
	if err != nil {
		t.Fatal(err)
	}
	networks := map[string]serverv1.SolanaNetwork{}
	contracts := map[string]uint32{}
	for _, request := range all.Msg.GetRequests() {
		networks[request.GetIdentity().GetRequestId()] = request.GetAction().GetNetwork()
		contracts[request.GetIdentity().GetRequestId()] = request.GetContractVersion()
	}
	if len(networks) != 2 || networks[proposalA] != serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET ||
		networks[proposalB] != serverv1.SolanaNetwork_SOLANA_NETWORK_UNSPECIFIED ||
		contracts[proposalA] != rules.RequestContract || contracts[proposalB] != rules.LegacyRequestContract {
		t.Fatalf("the common view holds %v at contracts %v", networks, contracts)
	}
}

// The Solana networks a server runs on (SEE-174), end to end: a malformed list is refused as a
// malformed document, a reordered one at the same revision is a retry, and a higher revision may
// add a network.
func TestAPublisherMayAddANetworkAtAHigherRevision(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.publisher(gateway.register(publisherA))
	ctx := context.Background()
	mainnet := serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET
	devnet := serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET
	networks := func(named ...serverv1.SolanaNetwork) func(*serverv1.ServerManifest) {
		return func(m *serverv1.ServerManifest) { m.GetFeed().SupportedNetworks = named }
	}

	_, err := publisher.PublishManifest(ctx, connect.NewRequest(&gatewayv1.PublishManifestRequest{
		Manifest: manifestOf(publisherA, 1, networks(mainnet, mainnet)),
	}))
	detail := refused(t, err, connect.CodeInvalidArgument,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_NETWORK)
	if detail.GetField() != "feed.supported_networks" {
		t.Fatalf("the refusal named %q", detail.GetField())
	}

	if answer := gateway.publishManifest(publisher,
		manifestOf(publisherA, 1, networks(mainnet))); answer.GetStatus() !=
		gatewayv1.PublishStatus_PUBLISH_STATUS_STORED {
		t.Fatalf("a Mainnet manifest answered %v", answer.GetStatus())
	}
	_, err = publisher.PublishManifest(ctx, connect.NewRequest(&gatewayv1.PublishManifestRequest{
		Manifest: manifestOf(publisherA, 1, networks(mainnet, devnet)),
	}))
	refused(t, err, connect.CodeFailedPrecondition,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_REVISION_CONFLICT)

	if answer := gateway.publishManifest(publisher,
		manifestOf(publisherA, 2, networks(devnet, mainnet))); answer.GetStatus() !=
		gatewayv1.PublishStatus_PUBLISH_STATUS_STORED {
		t.Fatalf("adding Devnet at a higher revision answered %v", answer.GetStatus())
	}
	if answer := gateway.publishManifest(publisher,
		manifestOf(publisherA, 2, networks(mainnet, devnet))); answer.GetStatus() !=
		gatewayv1.PublishStatus_PUBLISH_STATUS_UNCHANGED {
		t.Fatalf("the same networks in another order answered %v", answer.GetStatus())
	}
	answer, err := gateway.feed.GetServerManifest(ctx,
		connect.NewRequest(&gatewayv1.GetServerManifestRequest{ServerId: publisherA}))
	if err != nil {
		t.Fatal(err)
	}
	if held := answer.Msg.GetManifest().GetFeed().GetSupportedNetworks(); len(held) != 2 ||
		held[0] != mainnet || held[1] != devnet {
		t.Fatalf("a subscriber would read %v", held)
	}
}

// The rule that is the gateway's own: a document read from here can never point a phone somewhere
// else.
func TestTheGatewayWillNotRelayARedirection(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.publisher(gateway.register(publisherA))
	ctx := context.Background()

	t.Run("a direct server", func(t *testing.T) {
		_, err := publisher.PublishManifest(ctx, connect.NewRequest(&gatewayv1.PublishManifestRequest{
			Manifest: manifestOf(publisherA, 1, func(m *serverv1.ServerManifest) {
				m.Mode = serverv1.ConnectionMode_CONNECTION_MODE_DIRECT
				m.Reference = &serverv1.ServerManifest_Direct{
					Direct: &serverv1.DirectServer{Url: "https://collect-wallets.example.com"},
				}
			}),
		}))
		refused(t, err, connect.CodeInvalidArgument,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NOT_A_FEED)
	})

	t.Run("another gateway's origin", func(t *testing.T) {
		_, err := publisher.PublishManifest(ctx, connect.NewRequest(&gatewayv1.PublishManifestRequest{
			Manifest: manifestOf(publisherA, 1, func(m *serverv1.ServerManifest) {
				m.GetFeed().GatewayUrl = "https://collect-wallets.example.com"
			}),
		}))
		refused(t, err, connect.CodeInvalidArgument,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_GATEWAY)
	})

	t.Run("a contract this gateway does not serve", func(t *testing.T) {
		_, err := publisher.PublishManifest(ctx, connect.NewRequest(&gatewayv1.PublishManifestRequest{
			Manifest: manifestOf(publisherA, 1, func(m *serverv1.ServerManifest) {
				m.ProtocolVersion = 2
			}),
		}))
		refused(t, err, connect.CodeInvalidArgument,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_PROTOCOL)
	})
}

// Retrying is the ordinary case, and the revision is the only key it needs.
func TestARetryIsQuietAndAContradictionIsRefused(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.publisher(gateway.register(publisherA))
	ctx := context.Background()

	stored := gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 4))
	if stored.GetStatus() != gatewayv1.PublishStatus_PUBLISH_STATUS_STORED ||
		stored.GetSnapshotSequence() != 1 {
		t.Fatalf("the first publication answered %v at %d",
			stored.GetStatus(), stored.GetSnapshotSequence())
	}
	if sent := gateway.drain(); sent != 1 {
		t.Fatalf("the publication fanned out %d times", sent)
	}

	t.Run("the same thing again", func(t *testing.T) {
		again := gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 4))
		if again.GetStatus() != gatewayv1.PublishStatus_PUBLISH_STATUS_UNCHANGED {
			t.Fatalf("a retry answered %v", again.GetStatus())
		}
		// A retry is not an event: nothing was written, so there is nothing to tell anyone.
		if sent := gateway.drain(); sent != 0 {
			t.Fatalf("a retry fanned out %d times", sent)
		}
		if pending, _ := gateway.documents.Pending(ctx); pending != 0 {
			t.Fatalf("a retry left %d notices", pending)
		}
	})

	t.Run("something else at the same revision", func(t *testing.T) {
		_, err := publisher.PublishProposal(ctx, connect.NewRequest(&gatewayv1.PublishProposalRequest{
			Proposal: proposalOf(publisherA, proposalA, 4, func(p *proposalv1.Proposal) {
				p.Values[1].Text = "999999999"
			}),
		}))
		detail := refused(t, err, connect.CodeFailedPrecondition,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_REVISION_CONFLICT)
		// The answer says what the gateway holds, so a publisher that lost its own state has one
		// number to catch up with.
		if detail.GetHeldRevision() != 4 {
			t.Fatalf("the answer says %d is held", detail.GetHeldRevision())
		}
	})

	t.Run("a revision that has been overtaken", func(t *testing.T) {
		gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 5,
			func(p *proposalv1.Proposal) { p.Values[1].Text = "141000000" }))
		_, err := publisher.PublishProposal(ctx, connect.NewRequest(&gatewayv1.PublishProposalRequest{
			Proposal: proposalOf(publisherA, proposalA, 4),
		}))
		detail := refused(t, err, connect.CodeFailedPrecondition,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_STALE_REVISION)
		if detail.GetHeldRevision() != 5 {
			t.Fatalf("the answer says %d is held", detail.GetHeldRevision())
		}
	})

	t.Run("the terms moving", func(t *testing.T) {
		page := gateway.list(rules.ChannelFor(publisherA))
		if len(page.GetProposals()) != 1 || page.GetProposals()[0].GetRevision() != 5 {
			t.Fatalf("the feed holds %v", page.GetProposals())
		}
		if page.GetProposals()[0].GetValues()[1].GetText() != "141000000" {
			t.Fatal("the new revision did not replace the terms")
		}
	})
}

func TestAWithdrawalIsFinalAndItsRetryIsQuiet(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.publisher(gateway.register(publisherA))
	ctx := context.Background()
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 4))

	withdrawn, err := publisher.CancelProposal(ctx,
		connect.NewRequest(&gatewayv1.CancelProposalRequest{ProposalId: proposalA, Revision: 5}))
	if err != nil {
		t.Fatal(err)
	}
	if withdrawn.Msg.GetStatus() != gatewayv1.PublishStatus_PUBLISH_STATUS_STORED {
		t.Fatalf("the withdrawal answered %v", withdrawn.Msg.GetStatus())
	}
	document := withdrawn.Msg.GetProposal()
	if document.GetStatus() != proposalv1.ProposalStatus_PROPOSAL_STATUS_CANCELLED ||
		document.GetRevision() != 5 {
		t.Fatalf("the withdrawal left %v at revision %d", document.GetStatus(), document.GetRevision())
	}
	// Everything but the status and the update time is still the publisher's own.
	if document.GetPublisherNote() != proposalOf(publisherA, proposalA, 4).GetPublisherNote() ||
		len(document.GetValues()) != 2 {
		t.Fatalf("the withdrawal rewrote the document:\n%v", document)
	}

	t.Run("withdrawing again at the same revision", func(t *testing.T) {
		again, err := publisher.CancelProposal(ctx,
			connect.NewRequest(&gatewayv1.CancelProposalRequest{ProposalId: proposalA, Revision: 5}))
		if err != nil {
			t.Fatal(err)
		}
		if again.Msg.GetStatus() != gatewayv1.PublishStatus_PUBLISH_STATUS_UNCHANGED {
			t.Fatalf("a repeated withdrawal answered %v", again.Msg.GetStatus())
		}
	})

	t.Run("withdrawing again at a new revision", func(t *testing.T) {
		_, err := publisher.CancelProposal(ctx,
			connect.NewRequest(&gatewayv1.CancelProposalRequest{ProposalId: proposalA, Revision: 6}))
		refused(t, err, connect.CodeFailedPrecondition,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_CANCELLED)
	})

	t.Run("publishing over a withdrawal", func(t *testing.T) {
		// The rule that matters downstream: a phone keeps its record of a proposal it acted on for
		// ever, so the identity must never come back as something open (SEE-89).
		_, err := publisher.PublishProposal(ctx, connect.NewRequest(&gatewayv1.PublishProposalRequest{
			Proposal: proposalOf(publisherA, proposalA, 7),
		}))
		refused(t, err, connect.CodeFailedPrecondition,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_CANCELLED)
	})

	t.Run("withdrawing with a revision that has already been used", func(t *testing.T) {
		gateway.publishProposal(publisher, proposalOf(publisherA, proposalB, 2))
		_, err := publisher.CancelProposal(ctx,
			connect.NewRequest(&gatewayv1.CancelProposalRequest{ProposalId: proposalB, Revision: 2}))
		refused(t, err, connect.CodeFailedPrecondition,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_STALE_REVISION)
	})

	t.Run("withdrawing something that was never published", func(t *testing.T) {
		_, err := publisher.CancelProposal(ctx, connect.NewRequest(&gatewayv1.CancelProposalRequest{
			ProposalId: "11111111-1111-4111-8111-111111111111",
			Revision:   1,
		}))
		refused(t, err, connect.CodeNotFound,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_PROPOSAL)
	})

	t.Run("withdrawing something that is not an identity", func(t *testing.T) {
		_, err := publisher.CancelProposal(ctx, connect.NewRequest(&gatewayv1.CancelProposalRequest{
			ProposalId: "todays-swap",
			Revision:   1,
		}))
		refused(t, err, connect.CodeInvalidArgument,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID)
	})
}

func TestAChannelsBoundIsItsPublishersOwn(t *testing.T) {
	gateway := newGateway(t, func(settings *config.Config) { settings.MaxProposals = 2 })
	publisher := gateway.publisher(gateway.register(publisherA))
	ctx := context.Background()

	gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 1))
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalB, 1))

	_, err := publisher.PublishProposal(ctx, connect.NewRequest(&gatewayv1.PublishProposalRequest{
		Proposal: proposalOf(publisherA, "11111111-1111-4111-8111-111111111111", 1),
	}))
	refused(t, err, connect.CodeFailedPrecondition,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_PROPOSALS)

	// Nothing is evicted to make room: what a publisher already published stays, and it can still
	// update and withdraw — which is how it gets back under its own bound.
	if len(gateway.list(rules.ChannelFor(publisherA)).GetProposals()) != 2 {
		t.Fatal("a refused publication changed the feed")
	}
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 2,
		func(p *proposalv1.Proposal) { p.Values[1].Text = "1" }))
	if _, err := publisher.CancelProposal(ctx,
		connect.NewRequest(&gatewayv1.CancelProposalRequest{
			ProposalId: proposalB, Revision: 2,
		})); err != nil {
		t.Fatalf("a publisher at its bound could not withdraw: %v", err)
	}
}

func TestAPublisherIsRateLimitedAndTheLimitSaysNothingAboutTheCall(t *testing.T) {
	gateway := newGateway(t, func(settings *config.Config) {
		settings.PublishRate = 1
		settings.PublishBurst = 2
	})
	publisher := gateway.publisher(gateway.register(publisherA))
	ctx := context.Background()

	gateway.publishManifest(publisher, manifestOf(publisherA, 1))
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 1))
	_, err := publisher.PublishProposal(ctx, connect.NewRequest(&gatewayv1.PublishProposalRequest{
		Proposal: proposalOf(publisherA, proposalB, 1),
	}))
	refused(t, err, connect.CodeResourceExhausted,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_REQUESTS)

	// It says nothing about the call, so the same one works once the bucket has refilled.
	gateway.at(published.Add(10 * time.Second))
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalB, 1))

	// And another publisher is not affected by the first one's rate: the limit counts against the
	// credential, not against the gateway.
	second := gateway.publisher(gateway.register(publisherB))
	gateway.publishProposal(second, proposalOf(publisherB, proposalA, 1))
}

func TestAPublicationSurvivesARestartAndItsFanOutIsStillOwed(t *testing.T) {
	first := newGateway(t)
	publisher := first.publisher(first.register(publisherA))
	first.publishManifest(publisher, manifestOf(publisherA, 3))
	first.publishProposal(publisher, proposalOf(publisherA, proposalA, 4))
	// The fan-out has not run: this is the crash the outbox exists for.
	if pending, _ := first.documents.Pending(context.Background()); pending != 2 {
		t.Fatalf("%d notices are waiting", pending)
	}
	path := first.path
	if err := first.documents.Close(); err != nil {
		t.Fatal(err)
	}
	first.read.Close()
	first.publish.Close()

	// A second gateway on the same file: what it serves and what it owes are both still there.
	second := gatewayOn(t, path)
	page := second.list(rules.ChannelFor(publisherA))
	if len(page.GetProposals()) != 1 || page.GetProposals()[0].GetRevision() != 4 {
		t.Fatalf("the feed did not survive the restart: %v", page.GetProposals())
	}
	if sent := second.drain(); sent != 2 {
		t.Fatalf("the restart fanned out %d of the two notices it owed", sent)
	}
	if pending, _ := second.documents.Pending(context.Background()); pending != 0 {
		t.Fatalf("%d notices are still waiting", pending)
	}
}

func TestAPublicationIsFannedOutWithTheDocumentAsItStands(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.publisher(gateway.register(publisherA))
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 1))
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 2,
		func(p *proposalv1.Proposal) {
			p.Values[1].Text = "141000000"
			p.UpdatedAt = timestamppb.New(published.Add(time.Hour))
		}))

	// Two publications, one delivery, at the later revision: a subscriber wants the document as it
	// stands, not the story of how it got there (SAW-056 collapses push invalidations the same way).
	if sent := gateway.drain(); sent != 1 {
		t.Fatalf("two publications fanned out %d times", sent)
	}
	deliveries := gateway.dispatcher.all()
	if len(deliveries) != 1 || deliveries[0].Revision != 2 {
		t.Fatalf("the delivery is %+v", deliveries)
	}
	event := &gatewayv1.FeedEvent{}
	if err := proto.Unmarshal(deliveries[0].Event, event); err != nil {
		t.Fatal(err)
	}
	carried := event.GetProposal()
	if carried.GetValues()[1].GetText() != "141000000" {
		t.Fatalf("the delivery carried revision %d's terms", carried.GetRevision())
	}
}
