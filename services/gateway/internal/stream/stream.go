// Package stream is the gateway's side of the fan-out (SEE-91,
// docs/wiki/feed-gateway.md#the-stream).
//
// SEE-90 left one seam: a publication commits its outbox notice in the same transaction as the
// document, a drainer picks the notice up, and [dispatch.Dispatcher] is where the delivery happens.
// This package fills that seam with Centrifugo, and grants the listeners that read from it.
//
// # Why the broker's HTTP API and not its gRPC one
//
// Publishing is one POST with a JSON body, so the standard library does it. The gRPC API would mean
// grpc-go, its transitive tree, and a second protocol in a service that has three dependencies —
// for a call that carries a channel name and a base64 payload. The gateway is the only thing that
// ever talks to the broker's API, on a loopback or private network, a handful of times a minute.
//
// # Why the payload is binary
//
// A publication carries a serialized [gatewayv1.FeedEvent], base64-encoded into the API's `b64data`
// field. The alternative was protojson, which would have been readable in the broker's own history
// API — but protobuf-lite on Android cannot parse protojson at all, so the phone would have needed
// a hand-written JSON reader for documents it already has a validator for. Binary it is, and the
// consequence is written down rather than discovered: the broker's **JSON** history API cannot
// render these channels (it answers 500 on binary data). The stream itself is protobuf and carries
// them exactly.
//
// # What this package never sends
//
// The channel, the payload, and an idempotency key derived from the document's own identity.
// No address, no subscriber count, no note of who is listening — the broker learns which channels
// exist, which is what a broker is for, and nothing else (docs/security.md).
package stream

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/dispatch"
)

// Namespace is the broker channel namespace every feed channel lives in, and the only one
// configured: `feed:server/<server_id>`.
//
// It is a constant rather than a setting because it has to match the broker's own configuration
// exactly, and a mismatch between the two would be silent — a publication into an unconfigured
// namespace is accepted and kept without history, so recovery would quietly stop working. The
// broker config this repository ships (services/gateway/centrifugo.json) declares this name, and a test
// on each side pins it.
//
// The prefix is what scopes history, recovery and permissions to feed channels and leaves every
// other channel name default-denied. It is deliberately not part of the protocol: the document's
// channel is "server/<server_id>" (SEE-88), which is what the phone validates, and the transport's
// naming stays the transport's business.
const Namespace = "feed"

const channelPrefix = Namespace + ":"

// MostPayloadBytes is the largest event this package will send. A manifest and a proposal are
// bounded documents an order of magnitude smaller; the check is here so that a document which
// somehow is not one fails at the gateway rather than at the broker's request limit, where the
// error would be about a byte count instead of about a document.
const MostPayloadBytes = 64 * 1024

// Options is what a deployment configures. An empty URL means no stream: the gateway keeps the log
// dispatcher SEE-90 shipped, and answers ticket requests with one refusal.
type Options struct {
	// The broker's API origin, e.g. http://127.0.0.1:8000 — private, never the public URL.
	URL string
	// The broker's API key (X-API-Key).
	APIKey string
	// The HMAC key the broker verifies connection tokens with. The gateway signs with it and never
	// sends it anywhere.
	TokenKey string
	// How long a stream ticket is good for.
	Lifetime time.Duration
	// The most channels one ticket may grant.
	MostChannels int
	// How long a publish may take before it is a failure to retry.
	Timeout time.Duration
}

// Broker publishes events and grants listeners. It implements [dispatch.Dispatcher] and the
// gateway's ticket interface.
type Broker struct {
	url      string
	key      string
	tokenKey []byte
	lifetime time.Duration
	channels int
	client   *http.Client
}

// New builds a broker client, or says what the deployment left out. Every problem is a refusal
// rather than a default: a stream configured half way is a stream that looks like it works.
func New(options Options) (*Broker, error) {
	url := strings.TrimSuffix(strings.TrimSpace(options.URL), "/")
	switch {
	case url == "":
		return nil, fmt.Errorf("stream: no broker URL")
	case options.APIKey == "":
		return nil, fmt.Errorf("stream: no broker API key")
	case options.TokenKey == "":
		return nil, fmt.Errorf("stream: no token key")
	case options.Lifetime <= 0:
		return nil, fmt.Errorf("stream: no ticket lifetime")
	case options.MostChannels <= 0:
		return nil, fmt.Errorf("stream: no channel bound")
	}
	timeout := options.Timeout
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	return &Broker{
		url:      url,
		key:      options.APIKey,
		tokenKey: []byte(options.TokenKey),
		lifetime: options.Lifetime,
		channels: options.MostChannels,
		client:   &http.Client{Timeout: timeout},
	}, nil
}

// StreamChannel is the broker's name for a channel of the protocol's.
func (b *Broker) StreamChannel(channel string) string { return channelPrefix + channel }

// RestrictedStreamChannel is the broker's name for a restricted channel at one access epoch
// (SEE-156). The suffix is what a revocation moves: the gateway publishes only on the current
// epoch's name, so a listener attached under an earlier one — however it got there — receives
// nothing more, and the broker never has to be asked to close anybody's connection.
func (b *Broker) RestrictedStreamChannel(channel string, epoch uint64) string {
	return RestrictedStreamChannel(channel, epoch)
}

// RestrictedStreamChannel is the naming rule itself, for a caller that has no broker.
func RestrictedStreamChannel(channel string, epoch uint64) string {
	return channelPrefix + channel + ".e" + strconv.FormatUint(epoch, 10)
}

// streamFor is where one delivery is published: the channel's public name, or its restricted name
// at the epoch the delivery was built for.
func (b *Broker) streamFor(delivery dispatch.Delivery) string {
	if delivery.Restricted {
		return RestrictedStreamChannel(delivery.Channel, delivery.Epoch)
	}
	return b.StreamChannel(delivery.Channel)
}

// MostChannels is how many channels one ticket may grant.
func (b *Broker) MostChannels() int { return b.channels }

// Dispatch publishes one event to the channel it belongs to.
//
// Every failure is returned as an error, including the ones an operator has to fix — a wrong API
// key, a broker that is not there. The drainer then defers the notice with backoff and the document
// waits, which is the right outcome for both kinds: a transient failure resolves itself, and a
// configuration mistake leaves the fan-out to catch up once it is corrected rather than a hole
// where a publication should have been.
func (b *Broker) Dispatch(ctx context.Context, delivery dispatch.Delivery) error {
	if len(delivery.Event) > MostPayloadBytes {
		return fmt.Errorf("stream: event for %s is %d bytes, over the %d the gateway sends",
			delivery.Channel, len(delivery.Event), MostPayloadBytes)
	}
	body, err := json.Marshal(publication{
		Channel: b.streamFor(delivery),
		Data:    base64.StdEncoding.EncodeToString(delivery.Event),
		Key:     IdempotencyKey(delivery),
	})
	if err != nil {
		return fmt.Errorf("stream: encode publication: %w", err)
	}
	request, err := http.NewRequestWithContext(ctx,
		http.MethodPost, b.url+"/api/publish", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("stream: publish request: %w", err)
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("X-API-Key", b.key)
	response, err := b.client.Do(request)
	if err != nil {
		return fmt.Errorf("stream: publish to %s: %w", delivery.Channel, err)
	}
	defer response.Body.Close()
	// Bounded, because an answer that is not the broker's is not worth reading in full.
	raw, err := io.ReadAll(io.LimitReader(response.Body, 8*1024))
	if err != nil {
		return fmt.Errorf("stream: read publish answer: %w", err)
	}
	if response.StatusCode != http.StatusOK {
		// The status alone: the body of a refusal can be anything at all, and a gateway that
		// pasted it into its own logs would be logging something it did not write.
		return fmt.Errorf("stream: publish to %s answered %s",
			delivery.Channel, response.Status)
	}
	var answer publishAnswer
	if err := json.Unmarshal(raw, &answer); err != nil {
		return fmt.Errorf("stream: publish answer was not the broker's: %w", err)
	}
	if answer.Error != nil {
		return fmt.Errorf("stream: publish to %s refused: code %d",
			delivery.Channel, answer.Error.Code)
	}
	return nil
}

// IdempotencyKey is what makes a retried delivery one publication instead of two.
//
// The outbox is at-least-once: a process that stops after delivering and before clearing the notice
// delivers again (SEE-90). The document's own identity and revision is therefore the key — the same
// document at the same revision is the same publication, whoever sends it and however often. The
// broker deduplicates within its own window, so this is a reduction in duplicates and not a
// guarantee of uniqueness; what makes a duplicate harmless is the phone's revision-ordered apply
// (SEE-89), and that is the part nothing depends on configuration for.
func IdempotencyKey(delivery dispatch.Delivery) string {
	if delivery.Restricted {
		// A restricted channel's publication at another epoch is on another stream name, and is a
		// different publication however similar its document.
		return fmt.Sprintf("%s/%s/%s/%d/e%d",
			delivery.Channel, delivery.Kind, delivery.ProposalID, delivery.Revision, delivery.Epoch)
	}
	return fmt.Sprintf("%s/%s/%s/%d",
		delivery.Channel, delivery.Kind, delivery.ProposalID, delivery.Revision)
}

type publication struct {
	Channel string `json:"channel"`
	Data    string `json:"b64data"`
	Key     string `json:"idempotency_key"`
}

type publishAnswer struct {
	Error *struct {
		Code    uint32 `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
	Result *struct {
		Offset uint64 `json:"offset"`
		Epoch  string `json:"epoch"`
	} `json:"result"`
}
