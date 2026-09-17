package gateway

import (
	"context"
	"time"

	"connectrpc.com/connect"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/proposal/v1"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/store"
)

// How many proposals a page holds when the caller does not say, and the most it may ask for. The
// upper bound is what keeps one read from costing the store an unbounded amount of work; the
// default is what a phone gets by asking for nothing.
const (
	DefaultPageSize = 50
	MostPerPage     = 200
)

// Feed is the read-only client API: FeedService (proto/seekervault/gateway/v1/feed.proto).
//
// Everything it serves is a document a publisher published, and every answer is derived from the
// store at the moment it is read. It keeps no per-caller state of any kind — no session, no
// subscription record, no count of who read what — because there is none to keep: a read tells the
// gateway which channel someone is interested in, and the answer is the same for everyone who asks.
type Feed struct {
	store *store.Store
	now   func() time.Time
}

func NewFeed(from *store.Store, now func() time.Time) *Feed {
	return &Feed{store: from, now: now}
}

// GetServerManifest answers with what the publisher registered, or says it has nothing.
//
// A caller that already holds the current revision gets `unchanged` and no document. That is the
// same caching rule the phone applies to its own cached manifest (SEE-88) — an unchanged revision
// means what it holds is current — and it is in the contract rather than in an HTTP header so that
// nothing between the two decides what a phone believes.
func (f *Feed) GetServerManifest(
	ctx context.Context,
	request *connect.Request[gatewayv1.GetServerManifestRequest],
) (*connect.Response[gatewayv1.GetServerManifestResponse], error) {
	serverID := request.Msg.GetServerId()
	if !rules.IsID(serverID) {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID, "server_id")
	}
	manifest, err := f.store.Manifest(ctx, serverID)
	if err != nil {
		return nil, internal(err)
	}
	// A publisher that has been registered but has published nothing is the same answer as one
	// that does not exist. There is no reason for a reader to be able to tell those apart, and
	// there is a reason not to: the difference is the operator's business.
	if manifest == nil {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_SERVER, "server_id")
	}
	revision := manifest.Document.GetSettingsRevision()
	answer := &gatewayv1.GetServerManifestResponse{SettingsRevision: revision}
	if request.Msg.GetKnownSettingsRevision() == revision {
		answer.Unchanged = true
	} else {
		answer.Manifest = manifest.Document
	}
	return uncached(connect.NewResponse(answer)), nil
}

// ListProposals answers with a page of the channel's current proposals.
//
// The walk's boundary is documented in the proto and implemented here: the first page reads the
// channel's sequence and every page of the walk reports that number, while the rows themselves are
// always current. So a completed walk holds every proposal that existed when it began and still
// exists when it ends — some possibly at a newer revision than they had then — plus any published
// during it. Nothing is lost by that, because a phone applies a document only over an older
// revision of itself (SEE-89), which is also what lets a live stream's events be buffered during a
// walk and applied after it (SEE-91).
func (f *Feed) ListProposals(
	ctx context.Context,
	request *connect.Request[gatewayv1.ListProposalsRequest],
) (*connect.Response[gatewayv1.ListProposalsResponse], error) {
	channel := request.Msg.GetChannel()
	serverID := rules.ServerOf(channel)
	if serverID == "" {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID, "channel")
	}
	size := int(request.Msg.GetPageSize())
	switch {
	case size == 0:
		size = DefaultPageSize
	case size < 0 || size > MostPerPage:
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_PAGE_SIZE, "page_size")
	}
	known, err := f.store.PublisherExists(ctx, serverID)
	if err != nil {
		return nil, internal(err)
	}
	// An unknown channel is not an empty feed. A phone told "nothing here" would show the owner a
	// publisher with nothing to propose, which is a different fact from one this gateway has never
	// heard of.
	if !known {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_SERVER, "channel")
	}

	var (
		snapshot uint64
		after    string
	)
	if token := request.Msg.GetPageToken(); token != "" {
		// Deliberately not assigned through an `error` variable: a typed nil in an interface is
		// not nil, and this is exactly the shape that mistake takes.
		var fault *rules.Fault
		snapshot, after, fault = decodeCursor(token, channel)
		if fault != nil {
			return nil, refuse(fault)
		}
	} else {
		snapshot, err = f.store.Sequence(ctx, channel)
		if err != nil {
			return nil, internal(err)
		}
		// Version-aware: a caller that is up to date gets one small answer instead of the whole
		// feed. Only ever on a first page — a sequence is about the channel, not about a position
		// in a walk — and never when the channel has published nothing, because then there is no
		// sequence to have been up to date with.
		if snapshot > 0 && request.Msg.GetKnownSnapshotSequence() == snapshot {
			return uncached(connect.NewResponse(&gatewayv1.ListProposalsResponse{
				SnapshotSequence: snapshot,
				Unchanged:        true,
			})), nil
		}
	}

	// One more than the page, so the answer knows whether there is another page without asking a
	// second question.
	page, err := f.store.Page(ctx, channel, after, size+1)
	if err != nil {
		return nil, internal(err)
	}
	answer := &gatewayv1.ListProposalsResponse{SnapshotSequence: snapshot}
	more := len(page) > size
	if more {
		page = page[:size]
	}
	answer.Proposals = make([]*proposalv1.Proposal, 0, len(page))
	for _, stored := range page {
		answer.Proposals = append(answer.Proposals, stored.Document)
	}
	if more && len(page) > 0 {
		answer.NextPageToken = encodeCursor(channel, snapshot,
			page[len(page)-1].Document.GetProposalId())
	}
	return uncached(connect.NewResponse(answer)), nil
}

// GetProposal answers with one document, for a caller that learned its ID from a notification or
// from a page it read before the document moved.
func (f *Feed) GetProposal(
	ctx context.Context,
	request *connect.Request[gatewayv1.GetProposalRequest],
) (*connect.Response[gatewayv1.GetProposalResponse], error) {
	channel := request.Msg.GetChannel()
	if rules.ServerOf(channel) == "" {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID, "channel")
	}
	proposalID := request.Msg.GetProposalId()
	if !rules.IsID(proposalID) {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID, "proposal_id")
	}
	proposal, err := f.store.Proposal(ctx, channel, proposalID)
	if err != nil {
		return nil, internal(err)
	}
	if proposal == nil {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_PROPOSAL, "proposal_id")
	}
	return uncached(connect.NewResponse(&gatewayv1.GetProposalResponse{
		Proposal: proposal.Document,
	})), nil
}

// uncached asks anything in between not to keep the answer. Caching is in the contract instead —
// a revision, and a sequence — because a cache that decided for itself how long a feed stays
// current would be a second opinion about what a publisher is proposing, and the phone would have
// no way to tell that it was reading one.
func uncached[T any](response *connect.Response[T]) *connect.Response[T] {
	response.Header().Set("Cache-Control", "no-store")
	return response
}

// internal is what a caller is told when the store failed: that something here went wrong, and
// nothing else. The error itself is logged by the interceptor, where it belongs.
func internal(err error) *connect.Error {
	return connect.NewError(connect.CodeInternal, err)
}
