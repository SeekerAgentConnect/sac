package gateway

import (
	"context"
	"time"

	"connectrpc.com/connect"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/proposal/v1"
	requestv2 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/request/v2"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/store"
)

// Publisher is the write API: PublisherService (proto/seekervault/gateway/v1/publish.proto).
//
// Every method reads the caller's server from the credential and the document's claim separately,
// and refuses them when they disagree. That is the one rule the whole service rests on: a
// credential says which server its holder publishes as, and no field anywhere can widen it.
//
// Each method is one transaction: read what the gateway holds, ask the rules what this publication
// is, and write the document together with the notice that fans it out. Nothing is written when the
// rules refuse, and nothing is fanned out that was not written.
type Publisher struct {
	store   *store.Store
	gateway string
	most    int
	now     func() time.Time
	// Called after a publication commits, to ask the drainer for a pass. It is deliberately a
	// hint: the notice is already durable, so a wake-up that is lost costs a delay and never a
	// delivery (internal/dispatch).
	wake func()
}

// PublishRequest is the primary developer publication operation. The older proposal RPC below is
// a compatibility adapter over the same row, revision gate and outbox.
func (p *Publisher) PublishRequest(
	ctx context.Context,
	request *connect.Request[gatewayv1.PublishRequestRequest],
) (*connect.Response[gatewayv1.PublishRequestResponse], error) {
	document, fault := rules.Request(request.Msg.GetRequest(), p.expectation(ctx))
	if fault != nil {
		return nil, refuse(fault)
	}
	identity, lifecycle := document.GetIdentity(), document.GetLifecycle()
	answer := &gatewayv1.PublishRequestResponse{Revision: lifecycle.GetRevision()}
	var refused *rules.Fault
	err := p.store.Write(ctx, func(tx *store.Tx) error {
		held, err := tx.Request(ctx, identity.GetScope(), identity.GetRequestId())
		if err != nil {
			return err
		}
		var current *requestv2.Request
		if held != nil {
			current = held.Document
			answer.SnapshotSequence = held.Sequence
		}
		var decision rules.Decision
		var fault *rules.Fault
		if held != nil && held.Legacy &&
			document.GetLifecycle().GetRevision() == current.GetLifecycle().GetRevision() {
			// A publisher upgraded while the gateway still holds its protocol-1 row. The common
			// title and owner-input declarations did not exist in that contract, so compare the
			// common document's legacy projection. Equal means the old row already represents this
			// revision; it is left in place until a real higher revision upgrades it.
			decision, fault = rules.AdvanceProposal(
				rules.ProposalFromRequest(current), rules.ProposalFromRequest(document))
		} else {
			decision, fault = rules.AdvanceRequest(current, document)
		}
		if fault != nil {
			refused = fault
			return nil
		}
		if decision == rules.Unchanged {
			answer.Status = gatewayv1.PublishStatus_PUBLISH_STATUS_UNCHANGED
			return nil
		}
		if held == nil {
			count, err := tx.Count(ctx, identity.GetScope())
			if err != nil {
				return err
			}
			if count >= p.most {
				refused = &rules.Fault{Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_PROPOSALS, Field: "identity.request_id"}
				return nil
			}
		}
		sequence, err := tx.PutRequest(ctx, document, p.now())
		if err != nil {
			return err
		}
		answer.Status = gatewayv1.PublishStatus_PUBLISH_STATUS_STORED
		answer.SnapshotSequence = sequence
		return nil
	})
	if err != nil {
		return nil, internal(err)
	}
	if refused != nil {
		return nil, refuse(refused)
	}
	if answer.GetStatus() == gatewayv1.PublishStatus_PUBLISH_STATUS_STORED {
		p.wake()
	}
	return connect.NewResponse(answer), nil
}

func (p *Publisher) CancelRequest(
	ctx context.Context,
	request *connect.Request[gatewayv1.CancelRequestRequest],
) (*connect.Response[gatewayv1.CancelRequestResponse], error) {
	requestID := request.Msg.GetRequestId()
	if !rules.IsID(requestID) {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID, "request_id")
	}
	channel := rules.ChannelFor(publisherOf(ctx))
	revision := request.Msg.GetRevision()
	answer := &gatewayv1.CancelRequestResponse{}
	var refused *rules.Fault
	err := p.store.Write(ctx, func(tx *store.Tx) error {
		held, err := tx.Request(ctx, channel, requestID)
		if err != nil {
			return err
		}
		if held == nil {
			refused = &rules.Fault{Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_PROPOSAL, Field: "request_id"}
			return nil
		}
		current := held.Document
		if current.GetLifecycle().GetStatus() == requestv2.RequestStatus_REQUEST_STATUS_CANCELLED {
			switch {
			case revision == current.GetLifecycle().GetRevision():
				answer.Status, answer.Request, answer.SnapshotSequence = gatewayv1.PublishStatus_PUBLISH_STATUS_UNCHANGED, current, held.Sequence
			case revision < current.GetLifecycle().GetRevision():
				refused = &rules.Fault{Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_STALE_REVISION, Field: "revision", Held: current.GetLifecycle().GetRevision()}
			default:
				refused = &rules.Fault{Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_CANCELLED, Field: "request_id", Held: current.GetLifecycle().GetRevision()}
			}
			return nil
		}
		withdrawn, fault := rules.CancelledRequest(current, revision, p.now())
		if fault != nil {
			refused = fault
			return nil
		}
		sequence, err := tx.PutRequest(ctx, withdrawn, p.now())
		if err != nil {
			return err
		}
		answer.Status, answer.Request, answer.SnapshotSequence = gatewayv1.PublishStatus_PUBLISH_STATUS_STORED, withdrawn, sequence
		return nil
	})
	if err != nil {
		return nil, internal(err)
	}
	if refused != nil {
		return nil, refuse(refused)
	}
	if answer.GetStatus() == gatewayv1.PublishStatus_PUBLISH_STATUS_STORED {
		p.wake()
	}
	return connect.NewResponse(answer), nil
}

func NewPublisher(
	into *store.Store,
	gatewayURL string,
	mostProposals int,
	now func() time.Time,
	wake func(),
) *Publisher {
	if wake == nil {
		wake = func() {}
	}
	return &Publisher{store: into, gateway: gatewayURL, most: mostProposals, now: now, wake: wake}
}

func (p *Publisher) expectation(ctx context.Context) rules.Expectation {
	return rules.Expectation{ServerID: publisherOf(ctx), GatewayURL: p.gateway}
}

// PublishManifest stores what a publisher says about itself.
func (p *Publisher) PublishManifest(
	ctx context.Context,
	request *connect.Request[gatewayv1.PublishManifestRequest],
) (*connect.Response[gatewayv1.PublishManifestResponse], error) {
	manifest, fault := rules.Manifest(request.Msg.GetManifest(), p.expectation(ctx))
	if fault != nil {
		return nil, refuse(fault)
	}
	answer := &gatewayv1.PublishManifestResponse{
		SettingsRevision: manifest.GetSettingsRevision(),
	}
	var refused *rules.Fault
	err := p.store.Write(ctx, func(tx *store.Tx) error {
		held, err := tx.Manifest(ctx, manifest.GetServerId())
		if err != nil {
			return err
		}
		var current *serverv1.ServerManifest
		if held != nil {
			current = held.Document
		}
		decision, fault := rules.AdvanceManifest(current, manifest)
		if fault != nil {
			refused = fault
			return nil
		}
		if decision == rules.Unchanged {
			answer.Status = gatewayv1.PublishStatus_PUBLISH_STATUS_UNCHANGED
			return nil
		}
		if _, err := tx.PutManifest(ctx, manifest, p.now()); err != nil {
			return err
		}
		answer.Status = gatewayv1.PublishStatus_PUBLISH_STATUS_STORED
		return nil
	})
	if err != nil {
		return nil, internal(err)
	}
	if refused != nil {
		return nil, refuse(refused)
	}
	if answer.GetStatus() == gatewayv1.PublishStatus_PUBLISH_STATUS_STORED {
		p.wake()
	}
	return connect.NewResponse(answer), nil
}

// PublishProposal stores one proposal: a first publication, or a new revision of one the channel
// already holds.
func (p *Publisher) PublishProposal(
	ctx context.Context,
	request *connect.Request[gatewayv1.PublishProposalRequest],
) (*connect.Response[gatewayv1.PublishProposalResponse], error) {
	proposal, fault := rules.Proposal(request.Msg.GetProposal(), p.expectation(ctx))
	if fault != nil {
		return nil, refuse(fault)
	}
	answer := &gatewayv1.PublishProposalResponse{Revision: proposal.GetRevision()}
	var refused *rules.Fault
	err := p.store.Write(ctx, func(tx *store.Tx) error {
		held, err := tx.Proposal(ctx, proposal.GetChannel(), proposal.GetProposalId())
		if err != nil {
			return err
		}
		var current *proposalv1.Proposal
		if held != nil {
			current = held.Document
			answer.SnapshotSequence = held.Sequence
			if !held.Legacy && proposal.GetRevision() > current.GetRevision() {
				// Once an identity has moved to the common contract, an old publisher cannot
				// erase its presentation and local-input declarations with a higher revision.
				refused = &rules.Fault{
					Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_PROTOCOL,
					Field:   "proposal",
					Held:    current.GetRevision(),
				}
				return nil
			}
		}
		decision, fault := rules.AdvanceProposal(current, proposal)
		if fault != nil {
			refused = fault
			return nil
		}
		if decision == rules.Unchanged {
			answer.Status = gatewayv1.PublishStatus_PUBLISH_STATUS_UNCHANGED
			return nil
		}
		if held == nil {
			// The channel's bound applies to new proposals only: a publisher that is at its limit
			// can still update and withdraw what it has, which is what it needs to be able to do
			// to get back under it.
			count, err := tx.Count(ctx, proposal.GetChannel())
			if err != nil {
				return err
			}
			if count >= p.most {
				refused = &rules.Fault{
					Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_PROPOSALS,
					Field:   "proposal_id",
				}
				return nil
			}
		}
		sequence, err := tx.PutProposal(ctx, proposal, p.now())
		if err != nil {
			return err
		}
		answer.Status = gatewayv1.PublishStatus_PUBLISH_STATUS_STORED
		answer.SnapshotSequence = sequence
		return nil
	})
	if err != nil {
		return nil, internal(err)
	}
	if refused != nil {
		return nil, refuse(refused)
	}
	if answer.GetStatus() == gatewayv1.PublishStatus_PUBLISH_STATUS_STORED {
		p.wake()
	}
	return connect.NewResponse(answer), nil
}

// CancelProposal withdraws one.
//
// It takes an ID and a revision rather than a document, so a publisher that no longer holds what it
// published can still withdraw it — a template that was redeployed, for instance. The gateway keeps
// every other field exactly as the publisher published it and writes two: the status, and the
// update time, because the withdrawal happened here.
//
// Withdrawing again at the same revision is the retry, and it is answered as unchanged. Withdrawing
// at a *higher* revision is not: a proposal is withdrawn once, and a publisher that wants to
// propose something else publishes another proposal, which is another identity. That matters
// downstream — a phone keeps its record of a proposal it acted on for ever, and must never be shown
// the same identity as open again (SEE-89).
func (p *Publisher) CancelProposal(
	ctx context.Context,
	request *connect.Request[gatewayv1.CancelProposalRequest],
) (*connect.Response[gatewayv1.CancelProposalResponse], error) {
	proposalID := request.Msg.GetProposalId()
	if !rules.IsID(proposalID) {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID, "proposal_id")
	}
	channel := rules.ChannelFor(publisherOf(ctx))
	revision := request.Msg.GetRevision()
	answer := &gatewayv1.CancelProposalResponse{}
	var refused *rules.Fault
	err := p.store.Write(ctx, func(tx *store.Tx) error {
		held, err := tx.Proposal(ctx, channel, proposalID)
		if err != nil {
			return err
		}
		if held == nil {
			refused = &rules.Fault{
				Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_PROPOSAL,
				Field:   "proposal_id",
			}
			return nil
		}
		if held.Document.GetStatus() == proposalv1.ProposalStatus_PROPOSAL_STATUS_CANCELLED {
			switch {
			case revision == held.Document.GetRevision():
				answer.Status = gatewayv1.PublishStatus_PUBLISH_STATUS_UNCHANGED
				answer.Proposal = held.Document
				answer.SnapshotSequence = held.Sequence
			case revision < held.Document.GetRevision():
				refused = &rules.Fault{
					Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_STALE_REVISION,
					Field:   "revision",
					Held:    held.Document.GetRevision(),
				}
			default:
				refused = &rules.Fault{
					Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_CANCELLED,
					Field:   "proposal_id",
					Held:    held.Document.GetRevision(),
				}
			}
			return nil
		}
		withdrawn, fault := rules.Cancelled(held.Document, revision, p.now())
		if fault != nil {
			refused = fault
			return nil
		}
		sequence, err := tx.PutProposal(ctx, withdrawn, p.now())
		if err != nil {
			return err
		}
		answer.Status = gatewayv1.PublishStatus_PUBLISH_STATUS_STORED
		answer.Proposal = withdrawn
		answer.SnapshotSequence = sequence
		return nil
	})
	if err != nil {
		return nil, internal(err)
	}
	if refused != nil {
		return nil, refuse(refused)
	}
	if answer.GetStatus() == gatewayv1.PublishStatus_PUBLISH_STATUS_STORED {
		p.wake()
	}
	return connect.NewResponse(answer), nil
}
