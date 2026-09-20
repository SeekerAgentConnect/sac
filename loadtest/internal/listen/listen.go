// Package listen is one simulated phone on the broadcast transport (SEE-99).
//
// It consumes Centrifugo's unidirectional gRPC stream — the transport the app consumes, on the
// configuration this repository ships (feed-gateway/centrifugo.yaml) — and it makes the same decisions
// the app makes about what it receives (policy.go). What it adds is what a phone has no reason to
// keep: the arrival time of every publication, the offsets it saw, the gaps between them, and the
// reason each reconnect needed a snapshot read.
//
// # Why connect-go and not grpc-go
//
// The same argument the gateway makes for its side of the broker (feed-gateway/internal/stream): a
// gRPC client here would mean grpc-go and its transitive tree, for one server-streaming procedure
// with two message types. connect-go speaks the gRPC protocol over an ordinary net/http transport,
// and since Go 1.24 the standard library opens an unencrypted HTTP/2 connection by itself — so the
// dependency list is the two libraries the gateway already has, and what is on the wire is gRPC.
//
// # One transport per listener, on purpose
//
// A thousand listeners multiplexed onto one HTTP/2 connection would be a very efficient way to
// measure something no deployment ever sees. Each listener therefore owns its own [Client], which
// owns its own connection — and its own forwarded address, because the gateway's read limiter keys
// on the caller's address and a proxy in front of it is what sets that header (internal/gateway).
package listen

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/protobuf/proto"

	"github.com/BrRenat/SeekerAgentWallet/loadtest/internal/gen/centrifugal/centrifugo/unistream"
	"github.com/BrRenat/SeekerAgentWallet/loadtest/internal/gen/centrifugal/centrifugo/unistream/unistreamconnect"
	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/loadtest/internal/gen/seekervault/gateway/v1"
)

// What the broker is told about the client that connected. The app, never the phone: there is
// nothing here derived from a device, an installation or an owner, and a harness must not be the
// thing that introduces one (docs/security.md).
const (
	Name    = "seekervault"
	Version = "1"
)

// PingInterval is how often the HTTP/2 layer checks that the connection is still there. It is the
// only liveness this transport has — the pinned release sends no application pings on it — and the
// number is the phone's (CentrifugoFeedStream.PING_INTERVAL_SECONDS).
const PingInterval = 20 * time.Second

// Cursor is a position on one channel: what the listener would resume from.
type Cursor struct {
	Epoch  string
	Offset uint64
}

// Subscription is what the connect answer said about one channel.
type Subscription struct {
	Epoch         string
	Offset        uint64
	Recoverable   bool
	Recovered     bool
	WasRecovering bool
}

// Kind is what a push turned out to be.
type Kind int

const (
	// The stream opened: which channels were subscribed, and which node is serving it.
	Opened Kind = iota
	// One document, off the live stream or replayed into the connect answer.
	Published
	// A publication whose bytes are not a FeedEvent. Reported rather than dropped: the channel
	// moved either way.
	Unreadable
	// One channel was dropped while the connection stayed up.
	Dropped
	// The broker ended the stream, and said why.
	Closed
	// A frame this version does not know. Not a document, and never read as one.
	Alive
)

// Event is one thing that happened on a stream, with the moment it arrived.
type Event struct {
	Kind    Kind
	Channel string
	Offset  uint64
	// Set for Published: the gateway's own envelope, exactly as a read would have given it.
	Document *gatewayv1.FeedEvent
	// Set for Opened, keyed by the broker's channel name.
	Subscriptions map[string]Subscription
	// Set for Opened: which broker node answered, which is how a run with two of them can say
	// where its listeners landed.
	Node string
	// Set for Dropped and Closed.
	Code   uint32
	Reason string
	// When this process saw it. The same clock the publisher's send time is taken on, which is
	// what makes publish-to-receive a subtraction rather than an assumption about two clocks.
	At time.Time
}

// Client is one listener's own HTTP/2 connection to the broker.
type Client struct {
	stream unistreamconnect.CentrifugoUniStreamClient
	http   *http.Client
}

// Dial builds a client for one listener.
//
// `streamURL` is where the transport is: the broker's own unidirectional gRPC port in a native run,
// or a deployment's public origin, where the proxy forwards exactly this one procedure and nothing
// else (feed-gateway/Caddyfile). Plain HTTP means prior-knowledge HTTP/2, because gRPC needs HTTP/2
// and without TLS there is no negotiation to discover it with — the same pair the phone keeps.
func Dial(streamURL string) *Client {
	transport := &http.Transport{
		// One connection, and no sharing: this client is one phone.
		MaxConnsPerHost:     1,
		MaxIdleConnsPerHost: 1,
		DialContext: (&net.Dialer{
			Timeout:   10 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		HTTP2: &http.HTTP2Config{
			SendPingTimeout: PingInterval,
			PingTimeout:     15 * time.Second,
		},
	}
	protocols := &http.Protocols{}
	protocols.SetUnencryptedHTTP2(true)
	protocols.SetHTTP2(true)
	transport.Protocols = protocols
	// No client timeout at all: a listener stays open for as long as the screen is on, and a
	// deadline here would end every stream in the middle of a measurement.
	client := &http.Client{Transport: transport}
	return &Client{
		http: client,
		stream: unistreamconnect.NewCentrifugoUniStreamClient(
			client, streamURL,
			// gRPC, not Connect: this is the broker's own contract, and the broker speaks gRPC.
			connect.WithGRPC(),
		),
	}
}

// Close releases the connection. A listener that is finished with holds nothing.
func (c *Client) Close() {
	c.http.CloseIdleConnections()
}

// Options is one stream: the grant, where to resume from, and who to look like.
type Options struct {
	// The ticket the gateway minted. It carries the channels; the listener cannot ask for any.
	Ticket string
	// A cursor per broker channel name, or nothing for a first connection.
	Resume map[string]Cursor
	// What the gateway's read limiter should see as this listener's address, as the proxy in front
	// of a deployment would set it. Empty means the connection's own, which is loopback — and
	// therefore one bucket shared by every listener in the run.
	Forwarded string
}

// Consume opens one stream and hands every event to `hand` until it ends.
//
// It returns nil when the broker closed the stream cleanly — a disconnect push has already said
// why, or the node simply went away, which the caller treats as a reconnect either way — and the
// error when the transport failed. A cancelled context is not a failure: it is how a run stops.
func (c *Client) Consume(ctx context.Context, options Options, hand func(Event)) error {
	request := connect.NewRequest(&unistream.ConnectRequest{
		Token:   options.Ticket,
		Name:    Name,
		Version: Version,
		Subs:    resume(options.Resume),
	})
	if options.Forwarded != "" {
		request.Header().Set("X-Forwarded-For", options.Forwarded)
	}
	stream, err := c.stream.Consume(ctx, request)
	if err != nil {
		return fmt.Errorf("listen: opening the stream: %w", err)
	}
	defer func() { _ = stream.Close() }()
	for stream.Receive() {
		for _, event := range eventsOf(stream.Msg(), time.Now()) {
			hand(event)
		}
	}
	switch err := stream.Err(); {
	case err == nil, errors.Is(err, context.Canceled), errors.Is(err, context.DeadlineExceeded):
		return nil
	default:
		return err
	}
}

// resume is the `subs` map, which looks like a subscription request and is not one: the broker
// reads only the recovery position from it and takes the channels from the ticket. A request naming
// a channel the ticket does not grant is answered with a connection that receives nothing from it
// (probed against the pinned release, docs/testing/stage-7-1.md).
func resume(cursors map[string]Cursor) map[string]*unistream.SubscribeRequest {
	if len(cursors) == 0 {
		return nil
	}
	subs := make(map[string]*unistream.SubscribeRequest, len(cursors))
	for channel, cursor := range cursors {
		subs[channel] = &unistream.SubscribeRequest{
			Recover: true,
			Epoch:   cursor.Epoch,
			Offset:  cursor.Offset,
		}
	}
	return subs
}

// eventsOf turns one push into zero or more events, which is the phone's own mapping
// (CentrifugoFeedStream.eventsOf): a connect push becomes the opening plus every document the
// broker replayed, in order, so that catching up and keeping up are one code path in the caller.
func eventsOf(push *unistream.Push, at time.Time) []Event {
	switch {
	case push.GetConnect() != nil:
		answer := push.GetConnect()
		opened := make(map[string]Subscription, len(answer.GetSubs()))
		events := make([]Event, 1, 1+len(answer.GetSubs()))
		for channel, result := range answer.GetSubs() {
			opened[channel] = Subscription{
				Epoch:         result.GetEpoch(),
				Offset:        result.GetOffset(),
				Recoverable:   result.GetRecoverable(),
				Recovered:     result.GetRecovered(),
				WasRecovering: result.GetWasRecovering(),
			}
			for _, publication := range result.GetPublications() {
				events = append(events, published(channel, publication, at))
			}
		}
		events[0] = Event{Kind: Opened, Subscriptions: opened, Node: answer.GetNode(), At: at}
		return events
	case push.GetPub() != nil:
		return []Event{published(push.GetChannel(), push.GetPub(), at)}
	case push.GetUnsubscribe() != nil:
		return []Event{{
			Kind:    Dropped,
			Channel: push.GetChannel(),
			Code:    push.GetUnsubscribe().GetCode(),
			Reason:  push.GetUnsubscribe().GetReason(),
			At:      at,
		}}
	case push.GetDisconnect() != nil:
		return []Event{{
			Kind:   Closed,
			Code:   push.GetDisconnect().GetCode(),
			Reason: push.GetDisconnect().GetReason(),
			At:     at,
		}}
	default:
		return []Event{{Kind: Alive, At: at}}
	}
}

// published is one publication, as a document or as something unreadable. The payload is a
// FeedEvent — the gateway's own envelope, the same one a read would have given — so what comes off
// the stream is the document, not a notification to go and ask.
func published(channel string, publication *unistream.Publication, at time.Time) Event {
	document := &gatewayv1.FeedEvent{}
	if err := proto.Unmarshal(publication.GetData(), document); err != nil {
		return Event{
			Kind:    Unreadable,
			Channel: channel,
			Offset:  publication.GetOffset(),
			At:      at,
		}
	}
	return Event{
		Kind:     Published,
		Channel:  channel,
		Offset:   publication.GetOffset(),
		Document: document,
		At:       at,
	}
}
