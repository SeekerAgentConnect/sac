// What the gateway grants a listener, and what it refuses (SEE-91).
//
// The ticket is the whole subscription, because the transport it is for cannot ask for one: these
// tests are therefore about which channels a caller ends up admitted to, and they drive the real
// handler over the real read listener with the generated client.
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

func TestAListenerIsGrantedTheChannelsThisGatewayHosts(t *testing.T) {
	gateway := newGateway(t)
	first, second := rules.ChannelFor(publisherA), rules.ChannelFor(publisherB)
	gateway.publishManifest(gateway.publisher(gateway.register(publisherA)),
		manifestOf(publisherA, 1))
	gateway.publishManifest(gateway.publisher(gateway.register(publisherB)),
		manifestOf(publisherB, 1))

	answer := gateway.ticket(first, second)

	if answer.GetTicket() == "" {
		t.Fatal("the grant is empty")
	}
	if answer.GetLifetimeSeconds() != 3600 {
		t.Fatalf("the grant lasts %d seconds", answer.GetLifetimeSeconds())
	}
	// Both names, in the order they were asked for: the one the caller knows and the one the same
	// documents arrive under on the stream. The caller needs the second only as a key to match
	// events against, and needs never to know how it is spelled.
	if len(answer.GetChannels()) != 2 {
		t.Fatalf("the grant covers %v", answer.GetChannels())
	}
	for index, expected := range []string{first, second} {
		one := answer.GetChannels()[index]
		if one.GetChannel() != expected || one.GetStreamChannel() != "feed:"+expected {
			t.Fatalf("channel %d is %v", index, one)
		}
	}
	// And the grant itself was minted for the transport's names, not the protocol's.
	granted := gateway.grants.all()
	if len(granted) != 1 || strings.Join(granted[0], " ") != "feed:"+first+" feed:"+second {
		t.Fatalf("the ticket was minted for %v", granted)
	}
}

// A phone that still holds a feed reference for a server this gateway no longer hosts keeps the
// stream for its other feeds. This is the one place the read API answers a missing server softly,
// and the softness is the point: the alternative takes every feed off the stream because of one.
func TestAChannelThisGatewayDoesNotHostIsLeftOutRatherThanFatal(t *testing.T) {
	gateway := newGateway(t)
	hosted := rules.ChannelFor(publisherA)
	gateway.publishManifest(gateway.publisher(gateway.register(publisherA)),
		manifestOf(publisherA, 1))

	answer := gateway.ticket(hosted, rules.ChannelFor(publisherB))

	if len(answer.GetChannels()) != 1 || answer.GetChannels()[0].GetChannel() != hosted {
		t.Fatalf("the grant covers %v", answer.GetChannels())
	}
}

// Nothing to listen to is not a grant: a connection that receives nothing for as long as it is
// held open is worse for a caller than being told so.
func TestAGrantForNothingIsRefused(t *testing.T) {
	gateway := newGateway(t)

	_, err := gateway.feed.GetStreamTicket(context.Background(),
		connect.NewRequest(&gatewayv1.GetStreamTicketRequest{
			Channels: []string{rules.ChannelFor(publisherB)},
		}))

	refused(t, err, connect.CodeNotFound,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_SERVER)
}

func TestARequestThatIsNotARequestIsRefused(t *testing.T) {
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
			// The harness grants four at a time, and a bound nobody can see is a bound nobody can
			// respect: the answer says which rule it is.
			"more channels than are granted at once",
			[]string{channel, channel + "x", "server/" + publisherB, "server/" + publisherA + "y", channel + "z"},
			connect.CodeInvalidArgument,
			gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_CHANNELS,
		},
	} {
		t.Run(one.name, func(t *testing.T) {
			_, err := gateway.feed.GetStreamTicket(context.Background(),
				connect.NewRequest(&gatewayv1.GetStreamTicketRequest{Channels: one.channels}))
			refused(t, err, one.code, one.problem)
		})
	}
}

// A grant is a set of channels, so asking for one twice asks for nothing more. It is dropped rather
// than refused, and the answer says what was granted so a caller that did it can see what happened.
func TestAskingTwiceForOneChannelGrantsItOnce(t *testing.T) {
	gateway := newGateway(t)
	gateway.publishManifest(gateway.publisher(gateway.register(publisherA)),
		manifestOf(publisherA, 1))
	channel := rules.ChannelFor(publisherA)

	answer := gateway.ticket(channel, channel, channel)

	if len(answer.GetChannels()) != 1 {
		t.Fatalf("the grant covers %v", answer.GetChannels())
	}
	if granted := gateway.grants.all(); len(granted) != 1 || len(granted[0]) != 1 {
		t.Fatalf("the ticket was minted for %v", granted)
	}
}

// A deployment with no broker is a working deployment. It holds the documents, answers every read,
// and says once — with the code the phone already reads as "this one does not do that" — that there
// is nothing here to listen to.
func TestAGatewayWithNoBrokerSaysSoInsteadOfPretending(t *testing.T) {
	gateway := newGatewayWithoutStream(t)
	gateway.publishManifest(gateway.publisher(gateway.register(publisherA)),
		manifestOf(publisherA, 1))

	_, err := gateway.feed.GetStreamTicket(context.Background(),
		connect.NewRequest(&gatewayv1.GetStreamTicketRequest{
			Channels: []string{rules.ChannelFor(publisherA)},
		}))

	refused(t, err, connect.CodeUnimplemented,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_STREAM)
	// And the reads it does serve are unaffected.
	if page := gateway.list(rules.ChannelFor(publisherA)); page.GetUnchanged() {
		t.Fatal("a gateway without a broker stopped answering reads")
	}
}

// Asking to listen leaves no trace: no row, and nothing in the log that would let an operator
// reconstruct who is listening to what. The grant exists in the answer and nowhere else.
func TestAskingToListenIsNotRecordedAnywhere(t *testing.T) {
	gateway := newGateway(t)
	gateway.publishManifest(gateway.publisher(gateway.register(publisherA)),
		manifestOf(publisherA, 1))
	before := rowCounts(t, gateway.path)

	answer := gateway.ticket(rules.ChannelFor(publisherA))

	if after := rowCounts(t, gateway.path); fmt.Sprint(after) != fmt.Sprint(before) {
		t.Fatalf("asking to listen wrote something:\n%v\n%v", before, after)
	}
	if logged := gateway.logs.text(); strings.Contains(logged, answer.GetTicket()) {
		t.Fatalf("the grant was logged")
	}
}
