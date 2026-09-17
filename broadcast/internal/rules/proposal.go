package rules

import (
	"time"

	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/proposal/v1"
)

// Proposal checks one proposal and returns the document the gateway will store, or the one rule it
// broke.
//
// The rules are `proposals/ProposalValidation.kt`'s, in the same order, with two of the gateway's
// own: the document must name the server the caller publishes as, and a status of cancelled is not
// a publication (withdrawing is a transition, and CancelProposal is where it happens).
//
// A cancelled status is refused here rather than accepted as a shortcut because the two paths
// answer different questions. A publication says "this is what I propose"; a withdrawal says "stop
// treating what I proposed as open", and it has a state to come from. One way in means one place
// that checks the transition.
//
// What comes back is rebuilt field by field, so no unknown field a publisher sent is ever stored or
// relayed, and the times are normalized: two encodings of the same instant become one document, and
// "the same revision with the same content" stays a question about content.
func Proposal(message *proposalv1.Proposal, expect Expectation) (*proposalv1.Proposal, *Fault) {
	if message == nil {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID, "proposal")
	}
	if !IsID(message.GetServerId()) {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID, "server_id")
	}
	if message.GetServerId() != expect.ServerID {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_SERVER, "server_id")
	}
	if message.GetChannel() != ChannelFor(message.GetServerId()) {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_FOREIGN_CHANNEL, "channel")
	}
	if !IsID(message.GetProposalId()) {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID, "proposal_id")
	}
	revision := message.GetRevision()
	if revision == 0 || revision > MaxRevision {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_REVISION, "revision")
	}
	switch message.GetStatus() {
	case proposalv1.ProposalStatus_PROPOSAL_STATUS_OPEN:
	case proposalv1.ProposalStatus_PROPOSAL_STATUS_CANCELLED:
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_CANCEL_ON_PUBLISH, "status")
	default:
		// Unspecified, or a status from a later version of the format. A missing one is never read
		// as open, here or on the phone.
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_STATUS, "status")
	}
	if !IsOperation(message.GetOperation()) {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_OPERATION, "operation")
	}
	if !IsPluginID(message.GetPluginId()) {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_PLUGIN, "plugin_id")
	}
	created, ok := instant(message.GetCreatedAt())
	if !ok {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_TIMES, "created_at")
	}
	updated, ok := instant(message.GetUpdatedAt())
	if !ok {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_TIMES, "updated_at")
	}
	expires, ok := instant(message.GetExpiresAt())
	if !ok {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_TIMES, "expires_at")
	}
	if updated.Before(created) {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_TIMES, "updated_at")
	}
	if !expires.After(created) {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_TIMES, "expires_at")
	}
	if len(message.GetValues()) > MaxValues {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_VALUES, "values")
	}
	values := make([]*proposalv1.ProposalValue, 0, len(message.GetValues()))
	keys := make(map[string]bool, len(message.GetValues()))
	for _, value := range message.GetValues() {
		key := value.GetKey()
		if !IsOperation(key) || !printable(value.GetText(), MaxValueTextBytes, true) {
			return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_VALUE, "values")
		}
		if keys[key] {
			return nil, &Fault{
				Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_DUPLICATE_VALUE,
				Field:   key,
			}
		}
		keys[key] = true
		values = append(values, &proposalv1.ProposalValue{Key: key, Text: value.GetText()})
	}
	// The note is prose a person reads, so a line break is text in it.
	if !printable(message.GetPublisherNote(), MaxNoteBytes, true) {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_NOTE, "publisher_note")
	}
	return &proposalv1.Proposal{
		ServerId:      message.GetServerId(),
		Channel:       ChannelFor(message.GetServerId()),
		ProposalId:    message.GetProposalId(),
		Revision:      revision,
		Operation:     message.GetOperation(),
		PluginId:      message.GetPluginId(),
		Status:        proposalv1.ProposalStatus_PROPOSAL_STATUS_OPEN,
		CreatedAt:     timestamppb.New(created),
		UpdatedAt:     timestamppb.New(updated),
		ExpiresAt:     timestamppb.New(expires),
		PublisherNote: message.GetPublisherNote(),
		Values:        values,
	}, nil
}

// AdvanceProposal says what a publication is, given what the gateway already holds for that
// identity: something new, the same thing again, or a document it will not hold.
//
// Three rules beyond the manifest's, all about the same thing — a proposal's identity is the
// publisher's promise that it is one proposal:
//
//   - **A cancelled proposal is never published again.** A withdrawal is final. A publisher with
//     something else to propose publishes another proposal, which is another identity, because a
//     phone that already acted on this one keeps its record for ever and must never be shown the
//     same identity as something open again (SEE-89).
//   - **The creation time cannot move.** It is the one field that says when this proposal began,
//     and a publication that changes it is describing a different proposal under the same ID.
//   - **Revisions only go up.** As for a manifest, and for the same reason.
func AdvanceProposal(held, next *proposalv1.Proposal) (Decision, *Fault) {
	if held == nil {
		return Stored, nil
	}
	if held.GetStatus() == proposalv1.ProposalStatus_PROPOSAL_STATUS_CANCELLED {
		return Stored, &Fault{
			Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_CANCELLED,
			Field:   "proposal_id",
			Held:    held.GetRevision(),
		}
	}
	switch {
	case next.GetRevision() < held.GetRevision():
		return Stored, &Fault{
			Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_STALE_REVISION,
			Field:   "revision",
			Held:    held.GetRevision(),
		}
	case next.GetRevision() == held.GetRevision():
		if proto.Equal(held, next) {
			return Unchanged, nil
		}
		return Stored, &Fault{
			Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_REVISION_CONFLICT,
			Field:   "revision",
			Held:    held.GetRevision(),
		}
	}
	if !held.GetCreatedAt().AsTime().Equal(next.GetCreatedAt().AsTime()) {
		return Stored, &Fault{
			Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_TIMES,
			Field:   "created_at",
			Held:    held.GetRevision(),
		}
	}
	return Stored, nil
}

// Cancelled is the document a withdrawal leaves behind: the proposal exactly as the publisher
// published it, at the revision they chose for the withdrawal, with the status closed and the
// update time set to when it happened.
//
// That update time is the only field of a proposal the gateway ever writes, and it writes it
// because the event is here: the publisher asked for a transition rather than sending a document.
// Ordering does not depend on it — that is the revision's job on both sides — so a clock that
// disagrees with the publisher's own changes nothing a phone reads.
func Cancelled(held *proposalv1.Proposal, revision uint64, at time.Time) (*proposalv1.Proposal, *Fault) {
	if revision == 0 || revision > MaxRevision {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_REVISION, "revision")
	}
	if revision <= held.GetRevision() {
		return nil, &Fault{
			Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_STALE_REVISION,
			Field:   "revision",
			Held:    held.GetRevision(),
		}
	}
	withdrawn := proto.CloneOf(held)
	withdrawn.Revision = revision
	withdrawn.Status = proposalv1.ProposalStatus_PROPOSAL_STATUS_CANCELLED
	withdrawn.UpdatedAt = timestamppb.New(at.UTC().Truncate(time.Second))
	return withdrawn, nil
}

// instant reads a timestamp the way the phone does: it must be there, and it must be a moment the
// runtime can order. A timestamp outside the range protobuf defines arrives as a fault rather than
// as a wrapped-around date.
func instant(stamp *timestamppb.Timestamp) (time.Time, bool) {
	if stamp == nil || !stamp.IsValid() {
		return time.Time{}, false
	}
	return stamp.AsTime(), true
}
