package gateway_test

import (
	"context"
	"testing"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/config"
	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/proposal/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/rules"
)

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

// The other rule that is the gateway's own: a document read from here can never change what a
// server promises when the owner approves (SEE-97). A phone caches a manifest by revision, so a
// publisher that could raise its own environment could move every subscriber from a demonstration
// to real money with a document nobody looked at.
func TestTheGatewayWillNotRelayAPromotionToProduction(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.publisher(gateway.register(publisherA))
	ctx := context.Background()
	sandbox := func(m *serverv1.ServerManifest) {
		m.Environments = []serverv1.ServerEnvironment{
			serverv1.ServerEnvironment_SERVER_ENVIRONMENT_SANDBOX,
		}
	}
	if answer := gateway.publishManifest(publisher,
		manifestOf(publisherA, 1, sandbox)); answer.GetStatus() !=
		gatewayv1.PublishStatus_PUBLISH_STATUS_STORED {
		t.Fatalf("a sandbox manifest answered %v", answer.GetStatus())
	}

	// Everything else about the document may move with a revision, and does here: the name
	// changes too, so what is refused is the environment and not the settings.
	_, err := publisher.PublishManifest(ctx, connect.NewRequest(&gatewayv1.PublishManifestRequest{
		Manifest: manifestOf(publisherA, 2, func(m *serverv1.ServerManifest) {
			m.DisplayName = "Copy trading (live)"
		}),
	}))
	detail := refused(t, err, connect.CodeFailedPrecondition,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_ENVIRONMENT)
	// The held revision, as for the other refusals about what the gateway already holds: a
	// publisher that lost its own state gets one number to catch up with.
	if detail.GetHeldRevision() != 1 {
		t.Fatalf("the refusal held %d, expected 1", detail.GetHeldRevision())
	}

	// And nothing was written: what a subscriber reads is still the sandbox document at revision 1.
	answer, err := gateway.feed.GetServerManifest(ctx,
		connect.NewRequest(&gatewayv1.GetServerManifestRequest{ServerId: publisherA}))
	if err != nil {
		t.Fatal(err)
	}
	held := answer.Msg.GetManifest()
	if held.GetSettingsRevision() != 1 || held.GetDisplayName() != "Copy trading" {
		t.Fatalf("the refused publication was stored: %v", held)
	}
	if len(held.GetEnvironments()) != 1 || held.GetEnvironments()[0] !=
		serverv1.ServerEnvironment_SERVER_ENVIRONMENT_SANDBOX {
		t.Fatalf("a subscriber would read %v", held.GetEnvironments())
	}

	// The way it is actually done: the same environment at a higher revision is an ordinary
	// change, and a second environment is a second deployment with its own server ID.
	if answer := gateway.publishManifest(publisher,
		manifestOf(publisherA, 2, sandbox, func(m *serverv1.ServerManifest) {
			m.DisplayName = "Copy trading (demo)"
		})); answer.GetStatus() != gatewayv1.PublishStatus_PUBLISH_STATUS_STORED {
		t.Fatalf("a sandbox manifest at a higher revision answered %v", answer.GetStatus())
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
