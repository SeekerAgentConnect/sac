package gateway

import (
	"context"
	"time"

	"connectrpc.com/connect"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
)

// Grants is what the read API needs from the stream, and all of it (SEE-91).
//
// It is declared here, by the code that uses it, so this package stays what its own documentation
// says it is: a gateway that calls nothing. It holds no client, opens no connection and does not
// know what a ticket is made of — it validates channels against the store and asks whoever is
// fanning out to name and grant them. internal/stream implements this; a test implements it to
// count calls; a deployment with no broker passes nil.
type Grants interface {
	// The broker's name for a channel of the protocol's.
	StreamChannel(channel string) string
	// The broker's name for a restricted channel at one access epoch (SEE-156). It is never the
	// public name, and it changes with the epoch, so a revocation retires every name issued before it.
	RestrictedStreamChannel(channel string, epoch uint64) string
	// A credential admitting a listener to exactly these broker channels, lasting at most `most`
	// when that is positive and the broker's own lifetime otherwise, and its lifetime.
	GrantWithin(streamChannels []string, at time.Time, most time.Duration) (string, time.Duration, error)
	// The most channels one ticket may grant.
	MostChannels() int
}

// GetStreamTicket grants a listener the channels this gateway serves, out of the ones it was asked
// about.
//
// Three things are checked, in this order, because each says something different to the caller:
// whether this deployment streams at all, whether the request is a request (channels that are
// channels, and not more of them than are granted at once), and which of them this gateway hosts.
//
// A channel that is well formed but unknown here is **left out of the grant rather than refused**.
// That is the one place the read API answers a missing server softly, and it is deliberate: a
// ticket is about several channels at once, so refusing the request would take the stream away from
// every feed on a phone because one of them names a server this gateway no longer hosts. A read is
// about one channel, so it says not-found and costs nothing else. The caller compares what it asked
// with what it got, and reads the odd one out over unary calls.
func (f *Feed) GetStreamTicket(
	ctx context.Context,
	request *connect.Request[gatewayv1.GetStreamTicketRequest],
) (*connect.Response[gatewayv1.GetStreamTicketResponse], error) {
	if f.grants == nil {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_STREAM, "")
	}
	asked := request.Msg.GetChannels()
	if len(asked) == 0 {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID, "channels")
	}
	if len(asked) > f.grants.MostChannels() {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_CHANNELS, "channels")
	}

	// A repeat is not a mistake worth an error: a grant is a set of channels, and asking for one
	// twice asks for nothing more. It is dropped rather than refused, and the answer says which
	// channels were granted, so a caller that did it can see what happened.
	//
	// A restricted channel (SEE-156) is granted only for a live grant's session, and is left out
	// otherwise exactly as an unknown channel is: the other feeds on the phone keep their stream,
	// and the phone learns why this one is missing by reading it. The ticket then lasts no longer
	// than the shortest grant it carries.
	seen := make(map[string]bool, len(asked))
	granted := make([]*gatewayv1.StreamChannel, 0, len(asked))
	streams := make([]string, 0, len(asked))
	sessions := sessionsOf(request.Msg.GetSessions())
	var (
		most    time.Duration
		refused *connect.Error
	)
	for _, channel := range asked {
		serverID := rules.ServerOf(channel)
		if serverID == "" {
			return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID, "channels")
		}
		if seen[channel] {
			continue
		}
		seen[channel] = true
		access, known, err := f.accessOf(ctx, serverID)
		if err != nil {
			return nil, internal(err)
		}
		if !known {
			continue
		}
		stream := f.grants.StreamChannel(channel)
		if access.Restricted() {
			grant, denied := f.admit(ctx, serverID, access, sessions[channel])
			if denied != nil {
				if refused == nil {
					refused = denied
				}
				continue
			}
			left := grant.ExpiresAt.Sub(f.now())
			if most == 0 || left < most {
				most = left
			}
			stream = f.grants.RestrictedStreamChannel(channel, access.Epoch)
		}
		granted = append(granted, &gatewayv1.StreamChannel{
			Channel:       channel,
			StreamChannel: stream,
		})
		streams = append(streams, stream)
	}
	// Nothing to listen to is not a ticket. A grant admitting a listener to no channels would be a
	// connection that receives nothing for as long as it is held open, which is worse for the
	// caller than being told so.
	if len(streams) == 0 {
		// When the only channels asked for were restricted ones this caller may not read, the
		// reason is the access, and the phone acts on it rather than on "not here".
		if refused != nil {
			return nil, refused
		}
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_SERVER, "channels")
	}

	ticket, lifetime, err := f.grants.GrantWithin(streams, f.now(), most)
	if err != nil {
		return nil, internal(err)
	}
	return uncached(connect.NewResponse(&gatewayv1.GetStreamTicketResponse{
		Ticket:          ticket,
		Channels:        granted,
		LifetimeSeconds: uint32(lifetime.Seconds()),
	})), nil
}
