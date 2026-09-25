package gateway

import (
	"context"
	"time"

	"connectrpc.com/connect"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// Publisher presence (SEE-150): whether the server behind a channel is running, which is a
// different question from whether this gateway is.
//
// The two had been one answer, and the bug that made the difference visible is worth stating. A
// phone showed a feed as connected because it could reach the gateway; the gateway went on serving
// what the publisher had last published, because that is what it holds and a publisher stopping does
// not un-publish anything; and so an owner watched a feed that had been dead for hours and was told
// it was fine. Nothing failed. Reaching a gateway is evidence about a gateway.
//
// # Why presence is pushed and never polled
//
// The gateway does not contact a publisher's server — not here, not anywhere. A publisher's address
// is administrative metadata it supplied (storage.Publisher.Host), the gateway has never fetched it,
// and a gateway that connected to one would be a gateway a publisher could aim at somebody else.
// That is pinned by a boundary test, not just by intent.
//
// So the publisher says so, and the gateway believes the last thing it was told. Every authenticated
// call on the publisher listener is a check-in, because a publisher that is publishing is manifestly
// running; PublisherService.Heartbeat exists for the publisher that has nothing to publish, which is
// most publishers most of the time.
//
// # What a reader can learn from it
//
// Exactly what the publisher said about itself, and nothing about any reader. A check-in is written
// on the publisher listener, where every caller has already resolved to one registered server; the
// read is anonymous, answers the same for everyone, and writes nothing. Presence is therefore public
// in the same sense a feed's documents are: a publisher that would rather not say whether it is
// running is a publisher that does not call this, and its feed reads as unknown.

// MostStatusChannels is the most channels one presence read may ask about.
//
// It is this file's own bound rather than Grants.MostChannels, which is what the ticket and the
// topic methods use: those two are unreachable in a deployment that configured no broker or no
// relay, and presence has no such seam — a gateway with neither still answers it, from its own
// store. The number is the same as the stream's default for the same reason it was chosen there: it
// is enough for every feed a phone plausibly holds, on one call.
const MostStatusChannels = 32

// GetFeedStatus answers whether each channel's publisher is running.
//
// It is GetFeedTopics' shape, checked in the same order, with one step missing and one added. There
// is no "does this deployment do this at all" refusal, because every deployment does: presence is
// read from the store that already holds the registration. And there is no "nothing to answer" case
// — an answer naming no online feed is a useful answer, unlike a topic list nobody could subscribe
// to, so a phone that asked about channels this gateway does not host is told about none of them and
// treats them as unknown.
//
// A well-formed channel this gateway does not host is left out rather than refused, exactly as it is
// in a grant: one stale feed reference on a phone must not cost that phone the answer for its
// others.
func (f *Feed) GetFeedStatus(
	ctx context.Context,
	request *connect.Request[gatewayv1.GetFeedStatusRequest],
) (*connect.Response[gatewayv1.GetFeedStatusResponse], error) {
	asked := request.Msg.GetChannels()
	if len(asked) == 0 {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID, "channels")
	}
	if len(asked) > MostStatusChannels {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_CHANNELS, "channels")
	}

	// One instant for the whole answer. Reading the clock per channel would let two channels that
	// checked in at the same moment fall on opposite sides of the window.
	fresh := f.now().Add(-f.window)
	seen := make(map[string]bool, len(asked))
	statuses := make([]*gatewayv1.FeedStatus, 0, len(asked))
	sessions := sessionsOf(request.Msg.GetSessions())
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
		// Whether a restricted feed's publisher is running is part of the feed, and is answered
		// only to a device that may read it (SEE-156).
		if _, denied := f.admit(ctx, serverID, access, sessions[channel]); denied != nil {
			continue
		}
		at, err := f.storage.PublisherLastSeen(ctx, serverID)
		if err != nil {
			return nil, internal(err)
		}
		statuses = append(statuses, &gatewayv1.FeedStatus{
			Channel:      channel,
			Availability: availability(at, fresh),
		})
	}
	return uncached(connect.NewResponse(&gatewayv1.GetFeedStatusResponse{
		Statuses: statuses,
	})), nil
}

// availability is the one decision this whole feature makes: a publisher is online while its last
// check-in is inside the window, and offline otherwise.
//
// A publisher that has never checked in — the zero instant, which is also what a registration made
// before this schema version reads as — is offline rather than unspecified. It is the honest answer:
// this gateway has not been told that server is running, and a feed whose publisher has not said so
// is one nothing new will arrive on. UNSPECIFIED is never sent; it exists so that a phone reading a
// field it cannot understand lands on "unknown" instead of on "online".
func availability(at time.Time, fresh time.Time) gatewayv1.FeedAvailability {
	if at.IsZero() || at.Before(fresh) {
		return gatewayv1.FeedAvailability_FEED_AVAILABILITY_OFFLINE
	}
	return gatewayv1.FeedAvailability_FEED_AVAILABILITY_ONLINE
}

// Heartbeat is a publisher saying it is running, and being told how often to say it again.
//
// It stores nothing itself: the check-in is the interceptor's, and it has already happened by the
// time this runs, for this call exactly as for a publication. What is left is the interval, which is
// the gateway's to name and not a publisher's to assume — a publisher checking in on a schedule its
// gateway did not choose is a publisher shown offline while it is running.
func (p *Publisher) Heartbeat(
	_ context.Context,
	_ *connect.Request[gatewayv1.HeartbeatRequest],
) (*connect.Response[gatewayv1.HeartbeatResponse], error) {
	return connect.NewResponse(&gatewayv1.HeartbeatResponse{
		IntervalSeconds: uint32(p.heartbeat / time.Second),
	}), nil
}

// CheckingIn records that the authenticated caller's server is running (SEE-150).
//
// An interceptor rather than a line in each handler, for the reason Limiting is one: a method added
// later counts as a check-in by existing rather than by somebody remembering. It is also the only
// arrangement in which "every authenticated publisher call is a check-in" is a fact about the
// listener instead of a claim about five handlers.
//
// It runs after the credential has resolved — there is nothing to record about an unauthenticated
// caller — and after the rate limit, so a publisher being turned away is not thereby confirmed to be
// running. That ordering is also what bounds the write: a check-in cannot cost the store more often
// than the publish limit allows a call.
//
// A failed check-in never fails the call. What the caller came to do — publish, withdraw, or ask for
// the interval — succeeded or was refused on its own terms, and a publication that was rejected
// because presence could not be written would be a publication lost to bookkeeping. The consequence
// of a lost check-in is that phones see this feed as offline for a while, which the next call
// corrects.
func CheckingIn(
	presence storage.PresenceStore,
	now func() time.Time,
	log func(error),
) connect.UnaryInterceptorFunc {
	return func(next connect.UnaryFunc) connect.UnaryFunc {
		return func(ctx context.Context, request connect.AnyRequest) (connect.AnyResponse, error) {
			if serverID := publisherOf(ctx); serverID != "" {
				if err := presence.PublisherSeen(ctx, serverID, now()); err != nil && log != nil {
					log(err)
				}
			}
			return next(ctx, request)
		}
	}
}

// PresenceWindow is how long a publisher stays online after a check-in: three intervals.
//
// Three rather than one because a check-in is one HTTP request over somebody else's network, and a
// single lost one must not flip a running feed to offline on every phone reading it. It is derived
// here rather than configured beside the interval, so no deployment can be set up with a window
// shorter than the interval it asks for — which would show every publisher offline for ever, and
// would look exactly like a feature nobody configured.
func PresenceWindow(heartbeat time.Duration) time.Duration { return 3 * heartbeat }
