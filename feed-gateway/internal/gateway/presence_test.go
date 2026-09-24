// Whether the server behind a channel is running, as distinct from whether this gateway is
// (SEE-150).
//
// The bug these pin is one of conflation: a phone that could reach the gateway was shown its feeds
// as connected, and the gateway went on serving what a publisher had last published long after that
// publisher's process had gone. Every test here therefore keeps the gateway up throughout — it is
// always reachable, always answering — and moves only the publisher and the clock.
package gateway_test

import (
	"context"
	"fmt"
	"testing"
	"time"

	"connectrpc.com/connect"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/config"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gateway"
	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
)

const (
	online  = gatewayv1.FeedAvailability_FEED_AVAILABILITY_ONLINE
	offline = gatewayv1.FeedAvailability_FEED_AVAILABILITY_OFFLINE
	unknown = gatewayv1.FeedAvailability_FEED_AVAILABILITY_UNSPECIFIED
)

// The reported bug, in one test: the gateway is reachable and holds this publisher's documents, the
// publisher's own server has stopped, and the phone is told so.
func TestAFeedGoesOfflineWhileTheGatewayStaysUp(t *testing.T) {
	gateway := newGateway(t)
	channel := rules.ChannelFor(publisherA)
	publisher := gateway.publisher(gateway.register(publisherA))
	gateway.publishManifest(publisher, manifestOf(publisherA, 1))
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 1))

	if got := availabilityOf(gateway.status(channel), channel); got != online {
		t.Fatalf("a publisher that just published reads as %v", got)
	}

	// The publisher's process stops. Nothing else changes: the gateway is not restarted, the
	// documents are not withdrawn, and a phone reading the feed still gets the same proposal.
	gateway.at(published.Add(4 * config.DefaultHeartbeat))

	if got := availabilityOf(gateway.status(channel), channel); got != offline {
		t.Fatalf("a publisher that stopped reads as %v", got)
	}
	// Which is the point of separating the two answers: the feed is still readable, and what the
	// owner is now told is that nothing new will arrive on it.
	if len(gateway.list(channel).GetProposals()) != 1 {
		t.Fatal("an offline feed stopped being readable")
	}
}

// And back, without anybody intervening: the publisher starts again, checks in, and the next read
// says so.
func TestAFeedComesBackOnlineWhenItsPublisherChecksInAgain(t *testing.T) {
	gateway := newGateway(t)
	channel := rules.ChannelFor(publisherA)
	credential := gateway.register(publisherA)
	gateway.publishManifest(gateway.publisher(credential), manifestOf(publisherA, 1))
	gateway.at(published.Add(4 * config.DefaultHeartbeat))
	if got := availabilityOf(gateway.status(channel), channel); got != offline {
		t.Fatalf("the feed reads as %v before its publisher returns", got)
	}

	// A publisher with nothing to publish, saying only that it is running.
	answer := gateway.heartbeat(gateway.publisher(credential))

	if got := availabilityOf(gateway.status(channel), channel); got != online {
		t.Fatalf("a publisher that checked in reads as %v", got)
	}
	// It is also told how often to do that, because the schedule is the gateway's to choose: a
	// publisher checking in on one it invented would be shown offline while running.
	if got := answer.GetIntervalSeconds(); got != uint32(config.DefaultHeartbeat/time.Second) {
		t.Fatalf("the gateway asked to be checked in with every %ds", got)
	}
}

// One feed's publisher stopping says nothing about another's. The reported symptom was the reverse
// error — one connection's evidence answering for every feed — so this is the asymmetry that matters.
func TestOneOfflineFeedLeavesTheOthersAlone(t *testing.T) {
	gateway := newGateway(t)
	quiet, busy := rules.ChannelFor(publisherA), rules.ChannelFor(publisherB)
	gateway.publishManifest(gateway.publisher(gateway.register(publisherA)),
		manifestOf(publisherA, 1))
	second := gateway.register(publisherB)
	gateway.publishManifest(gateway.publisher(second), manifestOf(publisherB, 1))

	// Only the second publisher is still running when the window has passed.
	gateway.at(published.Add(4 * config.DefaultHeartbeat))
	gateway.heartbeat(gateway.publisher(second))

	answer := gateway.status(quiet, busy)
	if got := availabilityOf(answer, quiet); got != offline {
		t.Fatalf("the stopped publisher reads as %v", got)
	}
	if got := availabilityOf(answer, busy); got != online {
		t.Fatalf("the running publisher reads as %v", got)
	}
	// In the order they were asked about, so a caller can pair an answer with its own list.
	if got := answer.GetStatuses()[0].GetChannel(); got != quiet {
		t.Fatalf("the first answer is about %q", got)
	}
}

// Every authenticated call is a check-in, not only Heartbeat. A publisher that is publishing is
// manifestly running, and a gateway that needed a separate call to believe it would show a busy feed
// offline for lack of a formality.
func TestPublishingIsItselfACheckIn(t *testing.T) {
	for _, one := range []struct {
		name string
		call func(*harness, string)
	}{
		{"a manifest", func(h *harness, credential string) {
			h.publishManifest(h.publisher(credential), manifestOf(publisherA, 2))
		}},
		{"a proposal", func(h *harness, credential string) {
			h.publishProposal(h.publisher(credential), proposalOf(publisherA, proposalA, 1))
		}},
		{"a withdrawal", func(h *harness, credential string) {
			if _, err := h.publisher(credential).CancelProposal(context.Background(),
				connect.NewRequest(&gatewayv1.CancelProposalRequest{
					ProposalId: proposalA, Revision: 2,
				})); err != nil {
				t.Fatalf("withdrawing failed: %v", err)
			}
		}},
	} {
		t.Run(one.name, func(t *testing.T) {
			gateway := newGateway(t)
			channel := rules.ChannelFor(publisherA)
			credential := gateway.register(publisherA)
			publisher := gateway.publisher(credential)
			gateway.publishManifest(publisher, manifestOf(publisherA, 1))
			gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 1))

			// Past the window, and then the call under test and nothing else.
			gateway.at(published.Add(4 * config.DefaultHeartbeat))
			if got := availabilityOf(gateway.status(channel), channel); got != offline {
				t.Fatalf("the feed reads as %v before the call", got)
			}
			one.call(gateway, credential)

			if got := availabilityOf(gateway.status(channel), channel); got != online {
				t.Fatalf("after %s the feed reads as %v", one.name, got)
			}
		})
	}
}

// A publisher that has never said anything is offline rather than unknown: this gateway has not been
// told that server is running, and a registration made before there was anything to tell reads the
// same way. UNSPECIFIED is never sent — it is what a phone lands on when it cannot read the field,
// so that an answer it does not understand is not mistaken for "online".
func TestAPublisherThatHasNeverCheckedInIsOffline(t *testing.T) {
	gateway := newGateway(t)
	channel := rules.ChannelFor(publisherA)
	gateway.register(publisherA)

	answer := gateway.status(channel)

	if got := availabilityOf(answer, channel); got != offline {
		t.Fatalf("a publisher that never checked in reads as %v", got)
	}
	for _, status := range answer.GetStatuses() {
		if status.GetAvailability() == unknown {
			t.Fatal("the gateway sent an unspecified availability")
		}
	}
}

// The same softness a grant and a topic list have, and for the same reason: one stale feed reference
// on a phone must not cost that phone the answer about its other feeds. A channel this gateway does
// not host is simply absent, which the phone reads as unknown.
func TestAChannelThisGatewayDoesNotHostIsLeftOutOfThePresenceAnswer(t *testing.T) {
	gateway := newGateway(t)
	hosted, elsewhere := rules.ChannelFor(publisherA), rules.ChannelFor(publisherB)
	gateway.publishManifest(gateway.publisher(gateway.register(publisherA)),
		manifestOf(publisherA, 1))

	answer := gateway.status(hosted, elsewhere)

	if len(answer.GetStatuses()) != 1 {
		t.Fatalf("the answer names %v", answer.GetStatuses())
	}
	if got := availabilityOf(answer, elsewhere); got != unknown {
		t.Fatalf("a channel hosted elsewhere reads as %v", got)
	}
}

// What is refused, and it is the request rather than the state: nothing to ask about, more than one
// answer may carry, and a name that is not a channel.
func TestPresenceRefusesARequestThatIsNotOne(t *testing.T) {
	// Named for what it is rather than `gateway`, so the bound this asserts can be read off the
	// package that declares it instead of being written out a second time here.
	service := newGateway(t)
	ctx := context.Background()
	ask := func(channels ...string) error {
		_, err := service.feed.GetFeedStatus(ctx,
			connect.NewRequest(&gatewayv1.GetFeedStatusRequest{Channels: channels}))
		return err
	}

	refused(t, ask(), connect.CodeInvalidArgument,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID)
	refused(t, ask("not-a-channel"), connect.CodeInvalidArgument,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID)

	many := make([]string, 0, gateway.MostStatusChannels+1)
	for index := range gateway.MostStatusChannels + 1 {
		many = append(many, fmt.Sprintf("server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c%02d", index))
	}
	refused(t, ask(many...), connect.CodeInvalidArgument,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_CHANNELS)
}

// A deployment with no broker and no relay still answers presence. The ticket and the topic methods
// each have a seam that can be nil and each says so once; this one has none, because it is answered
// from the store that already holds the registration — and a smaller deployment's phones should not
// be the ones told a running feed is unknown.
func TestPresenceIsAnsweredWithoutABrokerOrARelay(t *testing.T) {
	for _, one := range []struct {
		name  string
		build func(*testing.T) *harness
	}{
		{"no broker", newGatewayWithoutStream},
		{"no relay", newGatewayWithoutPush},
	} {
		t.Run(one.name, func(t *testing.T) {
			gateway := one.build(t)
			channel := rules.ChannelFor(publisherA)
			gateway.publishManifest(gateway.publisher(gateway.register(publisherA)),
				manifestOf(publisherA, 1))

			if got := availabilityOf(gateway.status(channel), channel); got != online {
				t.Fatalf("the feed reads as %v", got)
			}
		})
	}
}

// Asking whether a feed is online is a read, and a read of this gateway records nothing about the
// caller. It is the same claim the other read methods are held to; presence is worth stating
// separately because it is the first answer here that is about a liveness somebody might expect to
// be tracked per subscriber.
func TestAskingWhetherAFeedIsOnlineIsNotRecordedAnywhere(t *testing.T) {
	gateway := newGateway(t)
	channel := rules.ChannelFor(publisherA)
	gateway.publishManifest(gateway.publisher(gateway.register(publisherA)),
		manifestOf(publisherA, 1))

	before := rowCounts(t, gateway.path)
	for range 3 {
		gateway.status(channel)
	}
	after := rowCounts(t, gateway.path)

	if fmt.Sprint(before) != fmt.Sprint(after) {
		t.Fatalf("asking changed the store:\n%v\n%v", before, after)
	}
}

// The window is three intervals, not one: a check-in is one HTTP request over somebody else's
// network, and a single lost one must not flip a running feed to offline on every phone reading it.
func TestAFeedSurvivesTwoMissedCheckIns(t *testing.T) {
	gateway := newGateway(t)
	channel := rules.ChannelFor(publisherA)
	gateway.publishManifest(gateway.publisher(gateway.register(publisherA)),
		manifestOf(publisherA, 1))

	for _, missed := range []int{1, 2} {
		gateway.at(published.Add(time.Duration(missed) * config.DefaultHeartbeat))
		if got := availabilityOf(gateway.status(channel), channel); got != online {
			t.Fatalf("after %d missed check-ins the feed reads as %v", missed, got)
		}
	}
	// The third is past the window.
	gateway.at(published.Add(3*config.DefaultHeartbeat + time.Second))
	if got := availabilityOf(gateway.status(channel), channel); got != offline {
		t.Fatalf("after three missed check-ins the feed reads as %v", got)
	}
}

// A check-in only ever moves forward. Two publishers' calls racing, or a clock that went backwards
// across a restart, must not make a server that is known to be running look older than that.
func TestACheckInNeverMovesAPublisherBackwards(t *testing.T) {
	gateway := newGateway(t)
	channel := rules.ChannelFor(publisherA)
	credential := gateway.register(publisherA)
	gateway.at(published.Add(10 * time.Minute))
	gateway.heartbeat(gateway.publisher(credential))

	// The clock goes back to where it was, and a check-in arrives at the earlier instant.
	gateway.at(published)
	gateway.heartbeat(gateway.publisher(credential))

	// Read from the later instant: the earlier check-in did not overwrite the later one.
	gateway.at(published.Add(10*time.Minute + config.DefaultHeartbeat))
	if got := availabilityOf(gateway.status(channel), channel); got != online {
		t.Fatalf("a backwards check-in made a running publisher read as %v", got)
	}
}

// An unauthenticated caller cannot check in, and cannot reach the check-in at all: the interceptor
// that records one runs after the credential resolves, so there is no server for an anonymous call
// to be recorded against.
func TestAnAnonymousCallerCannotSayAFeedIsOnline(t *testing.T) {
	gateway := newGateway(t)
	channel := rules.ChannelFor(publisherA)
	gateway.register(publisherA)

	_, err := gateway.anonymous().Heartbeat(context.Background(),
		connect.NewRequest(&gatewayv1.HeartbeatRequest{}))

	refused(t, err, connect.CodeUnauthenticated,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_UNAUTHENTICATED)
	if got := availabilityOf(gateway.status(channel), channel); got != offline {
		t.Fatalf("an anonymous call made the feed read as %v", got)
	}
}
