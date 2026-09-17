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
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/rules"
)

// The other acceptance: everything a phone needs about a feed comes from here, with no credential
// and without the publisher's own server being reachable — or existing.
func TestAPhoneReadsAFeedWithoutReachingAnyPublisher(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.publisher(gateway.register(publisherA))
	ctx := context.Background()
	gateway.publishManifest(publisher, manifestOf(publisherA, 3))
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 4))
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalB, 1))

	// The reader has no credential at all: a feed is a broadcast, and its reference can be printed
	// in a README.
	manifest, err := gateway.feed.GetServerManifest(ctx,
		connect.NewRequest(&gatewayv1.GetServerManifestRequest{ServerId: publisherA}))
	if err != nil {
		t.Fatal(err)
	}
	if !proto.Equal(manifest.Msg.GetManifest(), manifestOf(publisherA, 3)) {
		t.Fatalf("the manifest read back differently:\n%v", manifest.Msg.GetManifest())
	}
	// There is nothing in it to contact the publisher with: a feed manifest carries this gateway's
	// origin and the publisher's channel, and no address of the publisher's own.
	if manifest.Msg.GetManifest().GetDirect() != nil {
		t.Fatal("a feed manifest carried a server address")
	}
	if manifest.Msg.GetManifest().GetFeed().GetGatewayUrl() != gatewayURL {
		t.Fatalf("the manifest names %q", manifest.Msg.GetManifest().GetFeed().GetGatewayUrl())
	}

	page := gateway.list(rules.ChannelFor(publisherA))
	if len(page.GetProposals()) != 2 {
		t.Fatalf("the feed holds %d proposals", len(page.GetProposals()))
	}
	if page.GetNextPageToken() != "" {
		t.Fatal("a complete feed still offered another page")
	}
	if page.GetSnapshotSequence() != 3 {
		t.Fatalf("the snapshot is %d after three publications", page.GetSnapshotSequence())
	}

	detail, err := gateway.feed.GetProposal(ctx, connect.NewRequest(&gatewayv1.GetProposalRequest{
		Channel:    rules.ChannelFor(publisherA),
		ProposalId: proposalA,
	}))
	if err != nil {
		t.Fatal(err)
	}
	if !proto.Equal(detail.Msg.GetProposal(), proposalOf(publisherA, proposalA, 4)) {
		t.Fatalf("the proposal read back differently:\n%v", detail.Msg.GetProposal())
	}

	// And the answers say not to keep them: what is current is decided by the revision and the
	// sequence in the contract, not by something in between (docs/wiki/broadcast-gateway.md).
	if manifest.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("a read answered with Cache-Control %q",
			manifest.Header().Get("Cache-Control"))
	}
}

// The documented consistency boundary, exercised: a walk is stable while the feed moves under it.
func TestAWalkIsStableWhileTheFeedMoves(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.publisher(gateway.register(publisherA))
	channel := rules.ChannelFor(publisherA)
	ids := []string{
		"11111111-1111-4111-8111-111111111111",
		"22222222-2222-4222-8222-222222222222",
		"33333333-3333-4333-8333-333333333333",
		"44444444-4444-4444-8444-444444444444",
	}
	for _, id := range ids {
		gateway.publishProposal(publisher, proposalOf(publisherA, id, 1))
	}

	first := gateway.list(channel, func(request *gatewayv1.ListProposalsRequest) {
		request.PageSize = 2
	})
	if len(first.GetProposals()) != 2 || first.GetNextPageToken() == "" {
		t.Fatalf("the first page is %v", first.GetProposals())
	}
	snapshot := first.GetSnapshotSequence()

	// Between the pages: one proposal is republished at a higher revision, and a new one appears.
	gateway.publishProposal(publisher, proposalOf(publisherA, ids[3], 2,
		func(p *proposalv1.Proposal) { p.Values[1].Text = "141000000" }))
	gateway.publishProposal(publisher, proposalOf(publisherA,
		"55555555-5555-4555-8555-555555555555", 1))

	second := gateway.list(channel, func(request *gatewayv1.ListProposalsRequest) {
		request.PageSize = 2
		request.PageToken = first.GetNextPageToken()
	})
	// Every page of one walk reports the boundary the walk began at, which is what a client needs
	// to order a snapshot against a live stream (SEE-91).
	if second.GetSnapshotSequence() != snapshot {
		t.Fatalf("the second page reports snapshot %d, the first %d",
			second.GetSnapshotSequence(), snapshot)
	}
	seen := map[string]uint64{}
	for _, page := range []*gatewayv1.ListProposalsResponse{first, second} {
		for _, proposal := range page.GetProposals() {
			if _, twice := seen[proposal.GetProposalId()]; twice {
				t.Fatalf("%s was returned twice in one walk", proposal.GetProposalId())
			}
			seen[proposal.GetProposalId()] = proposal.GetRevision()
		}
	}
	// The four that existed when the walk began are all there, exactly once — the walk is ordered
	// by identity, so nothing slipped between pages as the feed moved.
	for _, id := range ids {
		if _, found := seen[id]; !found {
			t.Fatalf("%s was published before the walk and is missing from it", id)
		}
	}
	// The one republished mid-walk came back at its newer revision, which is the boundary being
	// honest rather than a transaction: a phone applies a document only over an older revision of
	// itself, so a page set from mixed moments converges (SEE-89).
	if seen[ids[3]] != 2 {
		t.Fatalf("%s came back at revision %d", ids[3], seen[ids[3]])
	}
	// The one published mid-walk is not in it, and is in the next read.
	if _, found := seen["55555555-5555-4555-8555-555555555555"]; found {
		t.Fatal("a proposal published during the walk appeared inside it")
	}
	again := gateway.list(channel, func(request *gatewayv1.ListProposalsRequest) {
		request.PageSize = 10
	})
	if len(again.GetProposals()) != 5 {
		t.Fatalf("a fresh walk holds %d proposals", len(again.GetProposals()))
	}
	if again.GetSnapshotSequence() <= snapshot {
		t.Fatalf("the feed moved but the snapshot is still %d", again.GetSnapshotSequence())
	}
}

func TestAReaderThatIsUpToDateIsToldSoInOneAnswer(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.publisher(gateway.register(publisherA))
	channel := rules.ChannelFor(publisherA)
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 1))

	current := gateway.list(channel).GetSnapshotSequence()
	unchanged := gateway.list(channel, func(request *gatewayv1.ListProposalsRequest) {
		request.KnownSnapshotSequence = current
	})
	if !unchanged.GetUnchanged() || len(unchanged.GetProposals()) != 0 {
		t.Fatalf("a reader that was up to date got %d proposals", len(unchanged.GetProposals()))
	}
	if unchanged.GetSnapshotSequence() != current {
		t.Fatalf("the answer reports snapshot %d", unchanged.GetSnapshotSequence())
	}

	// One publication later it is not up to date, and gets the feed.
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalB, 1))
	moved := gateway.list(channel, func(request *gatewayv1.ListProposalsRequest) {
		request.KnownSnapshotSequence = current
	})
	if moved.GetUnchanged() || len(moved.GetProposals()) != 2 {
		t.Fatalf("a reader that was behind got %d proposals", len(moved.GetProposals()))
	}

	// A channel that has published nothing is not "unchanged" for a reader claiming to hold
	// nothing: there is no sequence to have been up to date with.
	gateway.register(publisherB)
	empty := gateway.list(rules.ChannelFor(publisherB))
	if empty.GetUnchanged() || len(empty.GetProposals()) != 0 || empty.GetSnapshotSequence() != 0 {
		t.Fatalf("an empty feed answered %v", empty)
	}
}

func TestAManifestThatHasNotChangedIsNotSentAgain(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.publisher(gateway.register(publisherA))
	ctx := context.Background()
	gateway.publishManifest(publisher, manifestOf(publisherA, 3))

	unchanged, err := gateway.feed.GetServerManifest(ctx,
		connect.NewRequest(&gatewayv1.GetServerManifestRequest{
			ServerId:              publisherA,
			KnownSettingsRevision: 3,
		}))
	if err != nil {
		t.Fatal(err)
	}
	if !unchanged.Msg.GetUnchanged() || unchanged.Msg.GetManifest() != nil {
		t.Fatal("a reader that held the current revision was sent the manifest again")
	}
	if unchanged.Msg.GetSettingsRevision() != 3 {
		t.Fatalf("the answer reports revision %d", unchanged.Msg.GetSettingsRevision())
	}

	// A reader holding the wrong revision — older or newer — is told which one is current and
	// given the document, because a phone caching by revision has to be able to catch up.
	for _, known := range []uint64{0, 2, 4} {
		answer, err := gateway.feed.GetServerManifest(ctx,
			connect.NewRequest(&gatewayv1.GetServerManifestRequest{
				ServerId:              publisherA,
				KnownSettingsRevision: known,
			}))
		if err != nil {
			t.Fatal(err)
		}
		if answer.Msg.GetUnchanged() || answer.Msg.GetManifest() == nil {
			t.Fatalf("a reader holding revision %d was told nothing changed", known)
		}
		if answer.Msg.GetSettingsRevision() != 3 {
			t.Fatalf("the answer reports revision %d", answer.Msg.GetSettingsRevision())
		}
	}
}

func TestWhatAReaderCannotAskFor(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.publisher(gateway.register(publisherA))
	channel := rules.ChannelFor(publisherA)
	ctx := context.Background()
	gateway.publishManifest(publisher, manifestOf(publisherA, 1))
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 1))

	t.Run("a server that is not an identity", func(t *testing.T) {
		_, err := gateway.feed.GetServerManifest(ctx,
			connect.NewRequest(&gatewayv1.GetServerManifestRequest{ServerId: "copytrading"}))
		refused(t, err, connect.CodeInvalidArgument,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID)
	})

	t.Run("a server this gateway has never heard of", func(t *testing.T) {
		_, err := gateway.feed.GetServerManifest(ctx,
			connect.NewRequest(&gatewayv1.GetServerManifestRequest{ServerId: publisherB}))
		refused(t, err, connect.CodeNotFound,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_SERVER)
	})

	t.Run("a channel this gateway has never heard of", func(t *testing.T) {
		// Not an empty feed: a phone told "nothing here" would show its owner a publisher with
		// nothing to propose, which is a different fact.
		_, err := gateway.feed.ListProposals(ctx,
			connect.NewRequest(&gatewayv1.ListProposalsRequest{
				Channel: rules.ChannelFor("11111111-1111-4111-8111-111111111111"),
			}))
		refused(t, err, connect.CodeNotFound,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_SERVER)
	})

	t.Run("a channel that is not one", func(t *testing.T) {
		for _, name := range []string{"", "everyone", "server/nope", publisherA} {
			_, err := gateway.feed.ListProposals(ctx,
				connect.NewRequest(&gatewayv1.ListProposalsRequest{Channel: name}))
			refused(t, err, connect.CodeInvalidArgument,
				gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID)
		}
	})

	t.Run("a proposal that is not there", func(t *testing.T) {
		_, err := gateway.feed.GetProposal(ctx, connect.NewRequest(&gatewayv1.GetProposalRequest{
			Channel:    channel,
			ProposalId: proposalB,
		}))
		refused(t, err, connect.CodeNotFound,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_PROPOSAL)
	})

	t.Run("more of a feed than the gateway serves at once", func(t *testing.T) {
		_, err := gateway.feed.ListProposals(ctx,
			connect.NewRequest(&gatewayv1.ListProposalsRequest{
				Channel:  channel,
				PageSize: 10_000,
			}))
		refused(t, err, connect.CodeInvalidArgument,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_PAGE_SIZE)
	})

	t.Run("a page token it made up", func(t *testing.T) {
		_, err := gateway.feed.ListProposals(ctx,
			connect.NewRequest(&gatewayv1.ListProposalsRequest{
				Channel:   channel,
				PageToken: "cGFnZSAyIHBsZWFzZQ",
			}))
		refused(t, err, connect.CodeInvalidArgument,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_CURSOR)
	})

	t.Run("another channel's page token", func(t *testing.T) {
		// A token is the only part of a read that a client did not have to say out loud, so it
		// must not be a way to read a channel it did not ask about.
		gateway.register(publisherB)
		second := gateway.publisher(gateway.register(publisherB))
		for _, id := range []string{proposalA, proposalB} {
			gateway.publishProposal(second, proposalOf(publisherB, id, 1))
		}
		theirs := gateway.list(rules.ChannelFor(publisherB),
			func(request *gatewayv1.ListProposalsRequest) { request.PageSize = 1 })
		if theirs.GetNextPageToken() == "" {
			t.Fatal("the other channel has only one page")
		}
		_, err := gateway.feed.ListProposals(ctx,
			connect.NewRequest(&gatewayv1.ListProposalsRequest{
				Channel:   channel,
				PageToken: theirs.GetNextPageToken(),
			}))
		refused(t, err, connect.CodeInvalidArgument,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_CURSOR)
	})
}

func TestAWithdrawnOrExpiredProposalIsStillReadUntilRetentionTakesIt(t *testing.T) {
	gateway := newGateway(t, func(settings *config.Config) {
		settings.Retention = 24 * time.Hour
	})
	publisher := gateway.publisher(gateway.register(publisherA))
	channel := rules.ChannelFor(publisherA)
	ctx := context.Background()
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 1))
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalB, 1,
		func(p *proposalv1.Proposal) {
			p.ExpiresAt = timestamppb.New(published.Add(time.Hour))
		}))
	if _, err := publisher.CancelProposal(ctx,
		connect.NewRequest(&gatewayv1.CancelProposalRequest{
			ProposalId: proposalA, Revision: 2,
		})); err != nil {
		t.Fatal(err)
	}

	// A day later both have expired, one was withdrawn, and both are still served: expiry and
	// cancellation are facts in a document rather than reasons to hide it, and a phone that was
	// switched off has to be able to learn which of the two happened.
	gateway.at(published.Add(25 * time.Hour))
	page := gateway.list(channel)
	if len(page.GetProposals()) != 2 {
		t.Fatalf("the feed holds %d proposals a day later", len(page.GetProposals()))
	}
	held := map[string]proposalv1.ProposalStatus{}
	for _, one := range page.GetProposals() {
		held[one.GetProposalId()] = one.GetStatus()
	}
	if held[proposalA] != proposalv1.ProposalStatus_PROPOSAL_STATUS_CANCELLED {
		t.Fatalf("the withdrawn proposal reads as %v", held[proposalA])
	}
	if held[proposalB] != proposalv1.ProposalStatus_PROPOSAL_STATUS_OPEN {
		t.Fatalf("an expired proposal reads as %v rather than as what its publisher said",
			held[proposalB])
	}

	// Retention is about how long the gateway keeps serving one. It is the proposal's own expiry
	// plus the window, so the one that expired first goes first: an hour later, the one that
	// expired at published+1h is a day past its expiry and the other is not.
	gateway.at(published.Add(26 * time.Hour))
	removed, err := gateway.documents.Sweep(ctx, gateway.now().Add(-24*time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	if removed != 1 {
		t.Fatalf("the sweep removed %d", removed)
	}
	if left := gateway.list(channel).GetProposals(); len(left) != 1 ||
		left[0].GetProposalId() != proposalA {
		t.Fatalf("the sweep left %v", left)
	}
	_, err = gateway.feed.GetProposal(ctx, connect.NewRequest(&gatewayv1.GetProposalRequest{
		Channel:    channel,
		ProposalId: proposalB,
	}))
	refused(t, err, connect.CodeNotFound,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_PROPOSAL)
}

func TestAReadIsLimitedPerCallerAndNotPerGateway(t *testing.T) {
	gateway := newGateway(t, func(settings *config.Config) {
		settings.ReadRate = 1
		settings.ReadBurst = 2
	})
	publisher := gateway.publisher(gateway.register(publisherA))
	channel := rules.ChannelFor(publisherA)
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 1))

	// The test listener is on loopback, which is exactly the deployment this gateway ships with:
	// Caddy in front on the same host. So the forwarded address is the caller, and two phones
	// behind one proxy are two callers rather than one (internal/gateway/auth.go).
	ask := func(from string) error {
		request := connect.NewRequest(&gatewayv1.ListProposalsRequest{Channel: channel})
		request.Header().Set("X-Forwarded-For", from)
		_, err := gateway.feed.ListProposals(context.Background(), request)
		return err
	}
	for range 2 {
		if err := ask("203.0.113.7"); err != nil {
			t.Fatalf("a read inside the burst failed: %v", err)
		}
	}
	refused(t, ask("203.0.113.7"), connect.CodeResourceExhausted,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_REQUESTS)
	if err := ask("203.0.113.8"); err != nil {
		t.Fatalf("one caller's rate stopped another's read: %v", err)
	}
	gateway.at(published.Add(10 * time.Second))
	if err := ask("203.0.113.7"); err != nil {
		t.Fatalf("a refilled bucket still refused: %v", err)
	}
}
