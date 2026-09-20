// Package publish is the one thing in a publisher template that reaches out of the process: it
// submits documents to the shared feed gateway and records what the gateway said (SEE-95,
// docs/wiki/copytrading-template.md).
//
// It calls exactly one service, `PublisherService`, at the address its operator configured. There
// is no client for the read API here and none is compiled for this module at all
// (buf.gen.publisher.yaml), so a template cannot read a feed, subscribe to one, or learn anything
// about who is subscribed: it publishes and stops. Streaming and push delivery are the gateway's
// (SEE-91, SEE-92), and a phone is never contacted from here.
//
// # Retrying is the ordinary case
//
// A publication is stored before it is submitted, at a settled revision, so every retry sends
// identical bytes and the gateway answers `UNCHANGED` rather than storing a second document
// (docs/protocol.md#publisherservice). What this package adds is the judgment about which failures
// are worth retrying: a gateway that cannot be reached will be there later, and a document it
// refused will be refused again in the same words.
package publish

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"connectrpc.com/connect"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/gateway/v1/gatewayv1connect"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/proposal/v1"
	requestv2 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/request/v2"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/server/v1"
)

// Status is what the gateway did with a document. Both answers mean it holds what was sent; they
// differ in whether anything changed, which is exactly what a retry needs to know.
type Status string

const (
	// Stored: the document is new, or its content moved to a higher revision. Subscribers are
	// notified.
	Stored Status = "stored"
	// Unchanged: the gateway already held exactly this. Nothing was written and nobody was
	// notified — a retry is not an event.
	Unchanged Status = "unchanged"
	// Absent: a withdrawal for a proposal the gateway does not hold. It means the publication it
	// would have withdrawn never arrived, so there is nothing to withdraw and nothing was ever
	// public.
	Absent Status = "absent"
)

// Refusal is a publication the gateway would not accept, with the problem code it named and
// whether trying the same thing again could ever answer differently.
//
// The distinction is the whole of the retry policy. A gateway that is down, rate-limiting or
// mid-restart will accept this document later; one that called it malformed, called the channel
// somebody else's, or said the credential is not a credential will say the same thing for ever,
// and a template that kept asking would be a template nobody could debug.
type Refusal struct {
	// The gateway's own problem code ("foreign_channel"); the Connect code where a refusal carried
	// no detail, which is what a proxy in front of it answers with ("unavailable"); or
	// "unreachable" for a failure that never became a Connect error at all.
	Problem string
	// The message, for an operator. It never contains a credential: nothing in this package
	// formats one, and the gateway's own refusals never echo what was refused.
	Detail string
	// Whether retrying the identical document could succeed.
	Permanent bool
}

func (r *Refusal) Error() string { return r.Problem + ": " + r.Detail }

// Gateway is the publisher API, as this template calls it.
type Gateway struct {
	client     gatewayv1connect.PublisherServiceClient
	credential string
	timeout    time.Duration
}

// Options are what a [Gateway] needs: where the gateway is, the credential its operator issued
// this publisher, and how long one publication may take.
type Options struct {
	// The gateway's canonical origin (config.Origin). It is where a phone reads the feed, and the
	// publisher API is usually behind the same proxy on a route of its own
	// (feed-gateway/Caddyfile) — but an operator who keeps publishing off the internet points this
	// at the private address instead, which is why it is its own setting and not derived.
	URL string
	// The credential the gateway issued, sent as `Authorization: Bearer <credential>` and never
	// logged.
	Credential string
	Timeout    time.Duration
	// The HTTP client, injected so the tests use a server of their own. Nil means one of this
	// package's own making, with no proxy of its own and no redirect following: a redirect on a
	// publication would be an instruction from the network about where this publisher's
	// documents go.
	HTTP connect.HTTPClient
}

// New builds a client for the gateway at Options.URL.
func New(options Options) (*Gateway, error) {
	if options.URL == "" {
		return nil, errors.New("publish: a gateway needs an address")
	}
	if options.Credential == "" {
		return nil, errors.New("publish: a gateway needs the credential it issued this publisher")
	}
	timeout := options.Timeout
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	client := options.HTTP
	if client == nil {
		client = &http.Client{
			Timeout: timeout,
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return errors.New("a publication is not redirected")
			},
		}
	}
	return &Gateway{
		client:     gatewayv1connect.NewPublisherServiceClient(client, options.URL),
		credential: options.Credential,
		timeout:    timeout,
	}, nil
}

// Manifest publishes what this server says about itself.
func (g *Gateway) Manifest(ctx context.Context, document *serverv1.ServerManifest) (Status, error) {
	ctx, cancel := context.WithTimeout(ctx, g.timeout)
	defer cancel()
	answer, err := g.client.PublishManifest(ctx,
		carrying(g.credential, &gatewayv1.PublishManifestRequest{Manifest: document}))
	if err != nil {
		return "", classify(err)
	}
	return statusOf(answer.Msg.GetStatus()), nil
}

// Request publishes through the common contract. Proposal remains as a compatibility method for
// callers built against the Stage 7.1 template, but the shipped drainer uses this operation.
func (g *Gateway) Request(ctx context.Context, document *requestv2.Request) (Status, error) {
	ctx, cancel := context.WithTimeout(ctx, g.timeout)
	defer cancel()
	answer, err := g.client.PublishRequest(ctx,
		carrying(g.credential, &gatewayv1.PublishRequestRequest{Request: document}))
	if err != nil {
		return "", classify(err)
	}
	return statusOf(answer.Msg.GetStatus()), nil
}

func (g *Gateway) WithdrawRequest(ctx context.Context, requestID string, revision uint64) (Status, error) {
	ctx, cancel := context.WithTimeout(ctx, g.timeout)
	defer cancel()
	answer, err := g.client.CancelRequest(ctx,
		carrying(g.credential, &gatewayv1.CancelRequestRequest{RequestId: requestID, Revision: revision}))
	if err != nil {
		refusal := classify(err)
		if refusal.Problem == "no_such_proposal" {
			return Absent, nil
		}
		return "", refusal
	}
	return statusOf(answer.Msg.GetStatus()), nil
}

// Proposal publishes one signal: a first publication, or a higher revision of one already held.
func (g *Gateway) Proposal(ctx context.Context, document *proposalv1.Proposal) (Status, error) {
	ctx, cancel := context.WithTimeout(ctx, g.timeout)
	defer cancel()
	answer, err := g.client.PublishProposal(ctx,
		carrying(g.credential, &gatewayv1.PublishProposalRequest{Proposal: document}))
	if err != nil {
		return "", classify(err)
	}
	return statusOf(answer.Msg.GetStatus()), nil
}

// Withdraw takes a signal back. It sends an ID and a revision rather than a document, because that
// is what the contract takes: a template that was redeployed may no longer hold what it published,
// and the gateway is then the only thing that writes the transition.
//
// A proposal the gateway does not hold answers [Absent] rather than an error. It is the honest
// reading of "withdraw something that was never published": there is nothing to withdraw, nobody
// ever read it, and a template that treated it as a failure would retry it for ever.
func (g *Gateway) Withdraw(ctx context.Context, proposalID string, revision uint64) (Status, error) {
	ctx, cancel := context.WithTimeout(ctx, g.timeout)
	defer cancel()
	answer, err := g.client.CancelProposal(ctx,
		carrying(g.credential, &gatewayv1.CancelProposalRequest{
			ProposalId: proposalID,
			Revision:   revision,
		}))
	if err != nil {
		refusal := classify(err)
		if refusal.Problem == "no_such_proposal" {
			return Absent, nil
		}
		return "", refusal
	}
	return statusOf(answer.Msg.GetStatus()), nil
}

// carrying wraps a message with the credential. It is the only place the credential is read, and
// it goes into a header and nowhere else. A free function rather than a method because a method
// cannot take a type parameter of its own.
func carrying[M any](credential string, message *M) *connect.Request[M] {
	request := connect.NewRequest(message)
	request.Header().Set("Authorization", "Bearer "+credential)
	return request
}

func statusOf(status gatewayv1.PublishStatus) Status {
	if status == gatewayv1.PublishStatus_PUBLISH_STATUS_UNCHANGED {
		return Unchanged
	}
	return Stored
}

// classify turns a failed publication into a [Refusal]: the gateway's own problem code where there
// is one, and whether the identical document could ever be accepted.
//
// The grouping is the gateway's own (feed-gateway/internal/gateway/errors.go), read from the other
// side:
//
//   - **unavailable, aborted, internal, unknown, deadline** — the gateway, a proxy or the network,
//     none of which is about this document. Retried.
//   - **resource_exhausted** — a rate limit, which refills. Retried, and the backoff is what makes
//     that polite.
//   - **unauthenticated, permission_denied** — the credential is not a credential for this server,
//     or the document names a server or channel it has no claim on. An operator has to change
//     something; asking again cannot.
//   - **invalid_argument** — the document is malformed by the gateway's rules. It will be malformed
//     next time too, and a template that publishes one has a bug worth seeing.
//   - **failed_precondition** — true only while the gateway holds what it holds: a stale revision,
//     a conflict, a withdrawn proposal, a full channel. Not retried automatically, because every
//     one of them means this template's view and the gateway's disagree and something has to
//     resolve that. `POST /v1/signals/<id>/retry` is how an operator says they have.
//   - **not_found** — the manifest or proposal is not there. For a withdrawal that is [Absent] and
//     not a refusal at all; anywhere else it is a deployment that needs looking at.
func classify(err error) *Refusal {
	var connectErr *connect.Error
	if !errors.As(err, &connectErr) {
		// Nothing reached the gateway: a dial failure, a TLS failure, a timeout of our own. The
		// message is the transport's, and it names an address rather than a credential.
		return &Refusal{Problem: "unreachable", Detail: err.Error(), Permanent: false}
	}
	problem := problemOf(connectErr)
	switch connectErr.Code() {
	case connect.CodeUnavailable, connect.CodeAborted, connect.CodeInternal,
		connect.CodeUnknown, connect.CodeDeadlineExceeded, connect.CodeResourceExhausted:
		return &Refusal{Problem: problem, Detail: connectErr.Message(), Permanent: false}
	default:
		return &Refusal{Problem: problem, Detail: connectErr.Message(), Permanent: true}
	}
}

// problemOf is the gateway's own problem code for a refusal, from the GatewayErrorDetail it
// carries. A refusal with no detail — a proxy's 503, say — is named by its Connect code instead, so
// every failure has one word an operator can search for.
func problemOf(err *connect.Error) string {
	for _, detail := range err.Details() {
		value, decodeErr := detail.Value()
		if decodeErr != nil {
			continue
		}
		if problem, ok := value.(*gatewayv1.GatewayErrorDetail); ok {
			return strings.ToLower(strings.TrimPrefix(problem.GetProblem().String(),
				"GATEWAY_PROBLEM_"))
		}
	}
	return err.Code().String()
}

// Backoff is the delay before a publication is tried again: doubling from a second to a minute,
// which is the gateway's own fan-out backoff (feed-gateway/internal/dispatch.Backoff).
//
// It is capped rather than unbounded because the thing being waited for is a service coming back,
// and a minute is short enough that a signal published during an outage is broadcast promptly
// afterwards. There is no jitter: one template publishing its own documents is not a thundering
// herd, and a deterministic delay is one an operator can predict and a test can assert.
func Backoff(attempts int) time.Duration {
	const base = time.Second
	const most = time.Minute
	delay := base << min(max(attempts, 0), 6)
	if delay > most {
		delay = most
	}
	return delay
}

// Describe is a refusal as a log line's fields, for a template that wants to say what happened
// without saying what it was carrying.
func Describe(refusal *Refusal) []any {
	return []any{"problem", refusal.Problem, "permanent", refusal.Permanent,
		"detail", fmt.Sprintf("%.200s", refusal.Detail)}
}
