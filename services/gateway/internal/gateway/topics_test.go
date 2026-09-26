// Where the gateway says a channel's hints arrive, and what it refuses to say (SEE-92).
//
// The topic is derived by the relay and stated by this method, because a name that both sides
// derived would be a mismatch that shows up as silence. These tests drive the real handler over the
// real read listener with the generated client, as the ticket's do.
package gateway_test

import (
	"context"
	"fmt"
	"strings"
	"testing"

	"connectrpc.com/connect"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
)

func TestAPhoneIsToldWhereHintsAboutItsFeedsArrive(t *testing.T) {
	gateway := newGateway(t)
	first, second := rules.ChannelFor(publisherA), rules.ChannelFor(publisherB)
	gateway.publishManifest(gateway.publisher(gateway.register(publisherA)),
		manifestOf(publisherA, 1))
	gateway.publishManifest(gateway.publisher(gateway.register(publisherB)),
		manifestOf(publisherB, 1))

	answer := gateway.namedTopics(first, second)

	if len(answer.GetTopics()) != 2 {
		t.Fatalf("the answer names %v", answer.GetTopics())
	}
	// In the order they were asked for, each with the channel the caller knows beside the topic it
	// does not: the caller stores neither and subscribes to the second.
	for index, expected := range []string{publisherA, publisherB} {
		one := answer.GetTopics()[index]
		if one.GetChannel() != rules.ChannelFor(expected) {
			t.Fatalf("topic %d is about %q", index, one.GetChannel())
		}
		if one.GetTopic() != "feed.production."+expected {
			t.Fatalf("topic %d is %q", index, one.GetTopic())
		}
	}
	// Two phones asking about the same feed are told the same thing, which is what makes this a
	// public name rather than a grant: there is nothing here that is about the caller.
	if fmt.Sprint(gateway.namedTopics(first)) != fmt.Sprint(gateway.namedTopics(first)) {
		t.Fatal("two callers were told different topics for one channel")
	}
}

// The same softness as a grant's, for the same reason: one stale feed reference must not cost a
// phone the hints for its other feeds.
func TestAChannelThisGatewayDoesNotHostIsLeftOutOfTheTopics(t *testing.T) {
	gateway := newGateway(t)
	hosted := rules.ChannelFor(publisherA)
	gateway.publishManifest(gateway.publisher(gateway.register(publisherA)),
		manifestOf(publisherA, 1))

	answer := gateway.namedTopics(hosted, rules.ChannelFor(publisherB))

	if len(answer.GetTopics()) != 1 || answer.GetTopics()[0].GetChannel() != hosted {
		t.Fatalf("the answer names %v", answer.GetTopics())
	}
}

// A channel the relay itself has no topic for is left out too, rather than answered with an empty
// name. A phone that subscribed to "" would be subscribing to nothing and would have no way to
// tell that from a quiet feed.
func TestAChannelTheRelayHasNoTopicForIsLeftOut(t *testing.T) {
	gateway := newGateway(t)
	first, second := rules.ChannelFor(publisherA), rules.ChannelFor(publisherB)
	gateway.publishManifest(gateway.publisher(gateway.register(publisherA)),
		manifestOf(publisherA, 1))
	gateway.publishManifest(gateway.publisher(gateway.register(publisherB)),
		manifestOf(publisherB, 1))
	gateway.topics.silent = second

	answer := gateway.namedTopics(first, second)

	if len(answer.GetTopics()) != 1 || answer.GetTopics()[0].GetChannel() != first {
		t.Fatalf("the answer names %v", answer.GetTopics())
	}
}

func TestATopicRequestThatIsNotARequestIsRefused(t *testing.T) {
	gateway := newGateway(t)
	gateway.publishManifest(gateway.publisher(gateway.register(publisherA)),
		manifestOf(publisherA, 1))
	channel := rules.ChannelFor(publisherA)

	for _, one := range []struct {
		name     string
		channels []string
		code     connect.Code
		problem  gatewayv1.GatewayProblem
	}{
		{
			"no channels at all", nil,
			connect.CodeInvalidArgument, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID,
		},
		{
			"a channel that is not one", []string{"not-a-channel"},
			connect.CodeInvalidArgument, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID,
		},
		{
			// The harness names four at a time, matching the stream's own bound.
			"more channels than are named at once",
			[]string{
				channel, channel + "x", "server/" + publisherB,
				"server/" + publisherA + "y", channel + "z",
			},
			connect.CodeInvalidArgument,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_CHANNELS,
		},
		{
			"nothing this gateway hosts", []string{rules.ChannelFor(publisherB)},
			connect.CodeNotFound, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_SERVER,
		},
	} {
		t.Run(one.name, func(t *testing.T) {
			_, err := gateway.feed.GetFeedTopics(context.Background(),
				connect.NewRequest(&gatewayv1.GetFeedTopicsRequest{Channels: one.channels}))
			refused(t, err, one.code, one.problem)
		})
	}
}

// Asking twice about one channel names it once, as a ticket grants it once.
func TestAskingTwiceAboutOneChannelNamesItOnce(t *testing.T) {
	gateway := newGateway(t)
	gateway.publishManifest(gateway.publisher(gateway.register(publisherA)),
		manifestOf(publisherA, 1))
	channel := rules.ChannelFor(publisherA)

	answer := gateway.namedTopics(channel, channel, channel)

	if len(answer.GetTopics()) != 1 {
		t.Fatalf("the answer names %v", answer.GetTopics())
	}
}

// A deployment with no relay is a working deployment, and the two halves are independent: this one
// streams. It says once — with the code the phone reads as "this one does not do that" — that no
// hints are sent here, and everything else is unaffected.
func TestAGatewayWithNoRelaySaysSoInsteadOfPretending(t *testing.T) {
	gateway := newGatewayWithoutPush(t)
	gateway.publishManifest(gateway.publisher(gateway.register(publisherA)),
		manifestOf(publisherA, 1))
	channel := rules.ChannelFor(publisherA)

	_, err := gateway.feed.GetFeedTopics(context.Background(),
		connect.NewRequest(&gatewayv1.GetFeedTopicsRequest{Channels: []string{channel}}))

	refused(t, err, connect.CodeUnimplemented,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_PUSH)
	// The stream is a separate deployment decision, and this one has it.
	if gateway.ticket(channel).GetTicket() == "" {
		t.Fatal("a gateway without a relay stopped granting listeners")
	}
	// And so are the reads.
	if page := gateway.list(channel); page.GetUnchanged() {
		t.Fatal("a gateway without a relay stopped answering reads")
	}
}

// Asking where hints arrive leaves no trace, exactly as asking to listen does: the gateway is not
// part of what happens next, and topic membership is Firebase's — it is never told who subscribed.
func TestAskingWhereHintsArriveIsNotRecordedAnywhere(t *testing.T) {
	gateway := newGateway(t)
	gateway.publishManifest(gateway.publisher(gateway.register(publisherA)),
		manifestOf(publisherA, 1))
	before := rowCounts(t, gateway.path)

	answer := gateway.namedTopics(rules.ChannelFor(publisherA))

	if after := rowCounts(t, gateway.path); fmt.Sprint(after) != fmt.Sprint(before) {
		t.Fatalf("asking where hints arrive wrote something:\n%v\n%v", before, after)
	}
	if logged := gateway.logs.text(); strings.Contains(logged, answer.GetTopics()[0].GetTopic()) {
		t.Fatal("the topic was logged")
	}
}

// A publisher cannot cause a hint about another publisher's feed, and the reason is structural
// rather than a check somewhere: the only thing a topic is derived from is the channel in a notice,
// and a notice is written by the gateway from the credential the publication was authenticated
// with.
//
// This is the chain, end to end: a publication that claims another channel is refused at the door
// (publish_test.go has every shape of that attempt), a publication that is accepted produces a
// notice on the caller's own channel, and the name derived from that channel is the caller's own.
// What the derivation *is* belongs to the relay and is pinned there (internal/relay).
func TestAPublisherCannotCauseAHintAboutAnotherFeed(t *testing.T) {
	gateway := newGateway(t)
	first := gateway.publisher(gateway.register(publisherA))
	second := gateway.publisher(gateway.register(publisherB))
	gateway.publishManifest(first, manifestOf(publisherA, 1))
	gateway.publishManifest(second, manifestOf(publisherB, 1))
	gateway.drain()

	// B, publishing on A's channel, every way it can be written.
	_, err := second.PublishProposal(context.Background(),
		connect.NewRequest(&gatewayv1.PublishProposalRequest{
			Proposal: proposalOf(publisherA, proposalA, 1),
		}))
	refused(t, err, connect.CodePermissionDenied,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_SERVER)

	// And B publishing properly: the notice, and therefore the topic, is B's own.
	gateway.publishProposal(second, proposalOf(publisherB, proposalA, 1))
	if sent := gateway.drain(); sent != 1 {
		t.Fatalf("%d deliveries were made", sent)
	}
	delivered := gateway.dispatcher.all()
	last := delivered[len(delivered)-1]
	if last.Channel != rules.ChannelFor(publisherB) {
		t.Fatalf("the delivery is on %q", last.Channel)
	}
	topic := gateway.topics.Topic(last.Channel)
	if topic != "feed.production."+publisherB {
		t.Fatalf("the hint would go to %q", topic)
	}
	if topic == gateway.topics.Topic(rules.ChannelFor(publisherA)) {
		t.Fatal("the two publishers share a topic")
	}
}
