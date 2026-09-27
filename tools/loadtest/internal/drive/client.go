package drive

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"connectrpc.com/connect"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/loadtest/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/loadtest/internal/gen/seekervault/gateway/v1/gatewayv1connect"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/loadtest/internal/gen/seekervault/proposal/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/loadtest/internal/gen/seekervault/server/v1"
)

// The two ends of a feed, as their own callers (SEE-99).
//
// A [Reader] is what a phone calls: unauthenticated, rate limited per address, and carrying its own
// forwarded address because in a deployment a proxy sets one (internal/gateway/auth.go). A
// [Writer] is what a publisher's server calls: one credential, one server, and refused the moment a
// document claims anything else.
//
// Both are Connect clients over ordinary HTTP/1.1, which is what the gateway serves and what Caddy
// forwards. The stream is the other transport and the other package (internal/listen).

// Timeout is how long one unary call may take. It is generous on purpose: a run that is finding a
// bottleneck should see a slow answer as a slow answer, not as a client that gave up — and a
// refusal is what the gateway sends when it is the one saying no.
const Timeout = 30 * time.Second

// Reader is the client API, as one simulated phone calls it.
type Reader struct {
	feed      gatewayv1connect.FeedServiceClient
	forwarded string
}

// NewReader builds one. `forwarded` is the address the gateway's read limiter should count this
// caller as; empty means the connection's own, which on loopback is shared with every other reader
// in the run.
func NewReader(origin, forwarded string) *Reader {
	return &Reader{
		feed: gatewayv1connect.NewFeedServiceClient(
			&http.Client{Timeout: Timeout}, origin),
		forwarded: forwarded,
	}
}

// Ticket asks for permission to listen, and returns the grant with both names for every channel the
// gateway hosts: the protocol's and the transport's.
func (r *Reader) Ticket(
	ctx context.Context, channels []string,
) (string, map[string]string, time.Duration, error) {
	request := connect.NewRequest(&gatewayv1.GetStreamTicketRequest{Channels: channels})
	r.forward(request.Header())
	answer, err := r.feed.GetStreamTicket(ctx, request)
	if err != nil {
		return "", nil, 0, fmt.Errorf("drive: asking for a ticket: %w", err)
	}
	granted := make(map[string]string, len(answer.Msg.GetChannels()))
	for _, channel := range answer.Msg.GetChannels() {
		granted[channel.GetChannel()] = channel.GetStreamChannel()
	}
	return answer.Msg.GetTicket(), granted,
		time.Duration(answer.Msg.GetLifetimeSeconds()) * time.Second, nil
}

// Page is one answer from a snapshot walk.
type Page struct {
	Proposals []*proposalv1.Proposal
	NextToken string
	Sequence  uint64
	Unchanged bool
}

// Proposals reads one page. `known` is the snapshot sequence the caller already holds, which is how
// a phone that is up to date pays one round trip instead of a page (feed.proto).
func (r *Reader) Proposals(
	ctx context.Context, channel string, size uint32, token string, known uint64,
) (Page, error) {
	request := connect.NewRequest(&gatewayv1.ListProposalsRequest{
		Channel:               channel,
		PageSize:              size,
		PageToken:             token,
		KnownSnapshotSequence: known,
	})
	r.forward(request.Header())
	answer, err := r.feed.ListProposals(ctx, request)
	if err != nil {
		return Page{}, err
	}
	return Page{
		Proposals: answer.Msg.GetProposals(),
		NextToken: answer.Msg.GetNextPageToken(),
		Sequence:  answer.Msg.GetSnapshotSequence(),
		Unchanged: answer.Msg.GetUnchanged(),
	}, nil
}

// Manifest reads what a publisher says about itself.
func (r *Reader) Manifest(
	ctx context.Context, serverID string, known uint64,
) (*serverv1.ServerManifest, uint64, bool, error) {
	request := connect.NewRequest(&gatewayv1.GetServerManifestRequest{
		ServerId:              serverID,
		KnownSettingsRevision: known,
	})
	r.forward(request.Header())
	answer, err := r.feed.GetServerManifest(ctx, request)
	if err != nil {
		return nil, 0, false, err
	}
	return answer.Msg.GetManifest(), answer.Msg.GetSettingsRevision(),
		answer.Msg.GetUnchanged(), nil
}

// Topics asks where hints about these channels arrive.
func (r *Reader) Topics(ctx context.Context, channels []string) (map[string]string, error) {
	request := connect.NewRequest(&gatewayv1.GetFeedTopicsRequest{Channels: channels})
	r.forward(request.Header())
	answer, err := r.feed.GetFeedTopics(ctx, request)
	if err != nil {
		return nil, err
	}
	topics := make(map[string]string, len(answer.Msg.GetTopics()))
	for _, topic := range answer.Msg.GetTopics() {
		topics[topic.GetChannel()] = topic.GetTopic()
	}
	return topics, nil
}

func (r *Reader) forward(header http.Header) {
	if r.forwarded != "" {
		header.Set("X-Forwarded-For", r.forwarded)
	}
}

// Writer is the publisher API, as one registered publisher calls it.
type Writer struct {
	publish    gatewayv1connect.PublisherServiceClient
	serverID   string
	credential string
}

// NewWriter builds one for a publisher `feed-gatewayctl` has already registered.
func NewWriter(publishTo, serverID, credential string) *Writer {
	return &Writer{
		publish: gatewayv1connect.NewPublisherServiceClient(
			&http.Client{Timeout: Timeout}, publishTo),
		serverID:   serverID,
		credential: credential,
	}
}

// ServerID is who this writer publishes as.
func (w *Writer) ServerID() string { return w.serverID }

// Channel is the one channel it owns.
func (w *Writer) Channel() string { return "server/" + w.serverID }

// As returns the same writer with another publisher's credential, which is how the isolation
// scenario asks the question it exists to ask: a document claiming one server, sent with another's
// grant.
func (w *Writer) As(credential string) *Writer {
	other := *w
	other.credential = credential
	return &other
}

// Manifest publishes the settings document. It is the first thing a publisher does: until it is
// published, nobody can subscribe.
func (w *Writer) Manifest(
	ctx context.Context, manifest *serverv1.ServerManifest,
) (gatewayv1.PublishStatus, error) {
	request := connect.NewRequest(&gatewayv1.PublishManifestRequest{Manifest: manifest})
	request.Header().Set("Authorization", "Bearer "+w.credential)
	answer, err := w.publish.PublishManifest(ctx, request)
	if err != nil {
		return 0, err
	}
	return answer.Msg.GetStatus(), nil
}

// Publish sends one proposal and returns the channel sequence it was accepted at.
func (w *Writer) Publish(
	ctx context.Context, proposal *proposalv1.Proposal,
) (uint64, gatewayv1.PublishStatus, error) {
	request := connect.NewRequest(&gatewayv1.PublishProposalRequest{Proposal: proposal})
	request.Header().Set("Authorization", "Bearer "+w.credential)
	answer, err := w.publish.PublishProposal(ctx, request)
	if err != nil {
		return 0, 0, err
	}
	return answer.Msg.GetSnapshotSequence(), answer.Msg.GetStatus(), nil
}

// Cancel withdraws one, at a revision above the one it holds.
func (w *Writer) Cancel(
	ctx context.Context, proposalID string, revision uint64,
) (uint64, gatewayv1.PublishStatus, error) {
	request := connect.NewRequest(&gatewayv1.CancelProposalRequest{
		ProposalId: proposalID,
		Revision:   revision,
	})
	request.Header().Set("Authorization", "Bearer "+w.credential)
	answer, err := w.publish.CancelProposal(ctx, request)
	if err != nil {
		return 0, 0, err
	}
	return answer.Msg.GetSnapshotSequence(), answer.Msg.GetStatus(), nil
}

// Problem is the gateway's own refusal name, or the empty string when the failure was not one.
//
// A load run has to tell three things apart that all look like "it did not work": the gateway
// refusing on purpose (a rate limit, a foreign channel), a transport failure, and a timeout. Only
// the first is a measurement.
func Problem(err error) string {
	var failure *connect.Error
	if err == nil || !errors.As(err, &failure) {
		return ""
	}
	for _, detail := range failure.Details() {
		value, decoded := detail.Value()
		if decoded != nil {
			continue
		}
		if problem, ok := value.(*gatewayv1.GatewayErrorDetail); ok {
			// The spelling the gateway itself puts in the message, and the one the phone's own
			// problem codes use: "too_many_requests" rather than the enum's full name
			// (internal/gateway/errors.go).
			return strings.ToLower(
				strings.TrimPrefix(problem.GetProblem().String(), "GATEWAY_PROBLEM_"))
		}
	}
	return failure.Code().String()
}
