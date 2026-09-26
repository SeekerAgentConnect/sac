package gateway

import (
	"context"

	"connectrpc.com/connect"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
)

// Topics is what the read API needs from the relay, and all of it (SEE-92).
//
// It is declared here for the same reason [Grants] is: this package is called and calls nothing, so
// it holds no credential, opens no connection and does not know what a topic is made of. It asks
// whoever relays to name one. internal/relay implements this; a test implements it to count calls;
// a deployment with no relay passes nil.
type Topics interface {
	// Where this deployment's hints about a channel are sent, or "" when the name is not a channel.
	Topic(channel string) string
	// The most channels one answer names at once.
	MostTopics() int
}

// GetFeedTopics answers where hints about the channels this gateway serves arrive.
//
// It is the ticket method's shape, checked in the same order and for the same reasons: whether this
// deployment relays at all, whether the request is a request, and which of the channels this
// gateway hosts. A well-formed channel that is unknown here is left out rather than refused,
// because one stale feed reference must not cost a phone the hints for its others.
//
// Nothing is written and nothing is remembered. The gateway is not part of what happens next: the
// phone subscribes with Firebase, Firebase owns the membership, and this method's answer is a name
// that was derived from the channel it was asked about. Two phones asking about the same feed get
// the same answer, which is what makes it public rather than a grant (docs/security.md).
func (f *Feed) GetFeedTopics(
	ctx context.Context,
	request *connect.Request[gatewayv1.GetFeedTopicsRequest],
) (*connect.Response[gatewayv1.GetFeedTopicsResponse], error) {
	if f.topics == nil {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_PUSH, "")
	}
	asked := request.Msg.GetChannels()
	if len(asked) == 0 {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID, "channels")
	}
	if len(asked) > f.topics.MostTopics() {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_CHANNELS, "channels")
	}

	seen := make(map[string]bool, len(asked))
	named := make([]*gatewayv1.FeedTopic, 0, len(asked))
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
		// A restricted channel has no public topic (SEE-156): a topic anyone may join would tell
		// devices the publisher never approved that it published. Its hints go to each approved
		// device's own target instead (SetFeedPushTarget).
		if !known || access.Restricted() {
			continue
		}
		topic := f.topics.Topic(channel)
		if topic == "" {
			continue
		}
		named = append(named, &gatewayv1.FeedTopic{Channel: channel, Topic: topic})
	}
	// Nothing to subscribe to is not an answer to build on: a phone that subscribed to no topic
	// would be waiting for a hint that cannot arrive, and would have no way to tell that from a
	// quiet feed. It is told instead, and keeps to the stream and its own recovery.
	if len(named) == 0 {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_SERVER, "channels")
	}
	return uncached(connect.NewResponse(&gatewayv1.GetFeedTopicsResponse{Topics: named})), nil
}
