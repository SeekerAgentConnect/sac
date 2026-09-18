package rules

import (
	"encoding/base64"
	"strconv"
	"time"

	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/proposal/v1"
	requestv2 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/request/v2"
)

const (
	RequestContract uint32 = 1
	MaxOwnerInputs         = 16
	MaxInputOptions        = 16
)

// Request validates the common envelope at the broadcast boundary. Feed documents must contain
// only source-authored intent and declarations: an owner's answers and outcome have no field in
// the contract, and DEVICE_LOCAL is the only result policy accepted here.
func Request(message *requestv2.Request, expect Expectation) (*requestv2.Request, *Fault) {
	if message == nil || message.GetContractVersion() != RequestContract {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_PROTOCOL, "contract_version")
	}
	identity := message.GetIdentity()
	if identity == nil || !IsID(identity.GetSourceId()) || !IsID(identity.GetRequestId()) {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID, "identity")
	}
	if identity.GetSourceId() != expect.ServerID {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_SERVER, "identity.source_id")
	}
	channel := ChannelFor(identity.GetSourceId())
	if identity.GetScope() != channel || message.GetAudience().GetFeed().GetChannel() != channel {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_FOREIGN_CHANNEL, "audience.feed.channel")
	}
	if message.GetResultHandling().GetMode() != requestv2.ResultMode_RESULT_MODE_DEVICE_LOCAL {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_VALUE, "result_handling.mode")
	}
	lifecycle := message.GetLifecycle()
	if lifecycle == nil || lifecycle.GetRevision() == 0 || lifecycle.GetRevision() > MaxRevision {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_REVISION, "lifecycle.revision")
	}
	switch lifecycle.GetStatus() {
	case requestv2.RequestStatus_REQUEST_STATUS_OPEN:
	case requestv2.RequestStatus_REQUEST_STATUS_CANCELLED:
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_CANCEL_ON_PUBLISH, "lifecycle.status")
	default:
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_STATUS, "lifecycle.status")
	}
	created, ok := instant(lifecycle.GetCreatedAt())
	if !ok {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_TIMES, "lifecycle.created_at")
	}
	updated, ok := instant(lifecycle.GetUpdatedAt())
	if !ok || updated.Before(created) {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_TIMES, "lifecycle.updated_at")
	}
	expires, ok := instant(lifecycle.GetExpiresAt())
	if !ok || !expires.After(created) {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_TIMES, "lifecycle.expires_at")
	}
	presentation := message.GetPresentation()
	if presentation == nil || presentation.GetCategory() != requestv2.PresentationCategory_PRESENTATION_CATEGORY_SIGNAL ||
		!printable(presentation.GetTitle(), MaxNameBytes, false) || presentation.GetTitle() == "" {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_NAME, "presentation")
	}
	if !printable(presentation.GetDescription(), MaxNoteBytes, true) {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_NOTE, "presentation.description")
	}
	action := message.GetAction()
	if action == nil || !IsOperation(action.GetCapabilityId()) || action.GetCapabilityVersion() == 0 {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_OPERATION, "action")
	}
	if !IsPluginID(action.GetPluginId()) {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_PLUGIN, "action.plugin_id")
	}
	parameters, failed := values(action.GetParameters())
	if failed != nil {
		return nil, failed
	}
	inputs, failed := ownerInputs(message.GetOwnerInputs())
	if failed != nil {
		return nil, failed
	}
	return &requestv2.Request{
		ContractVersion: RequestContract,
		Identity: &requestv2.RequestIdentity{
			SourceId: identity.GetSourceId(), Scope: channel, RequestId: identity.GetRequestId(),
		},
		Lifecycle: &requestv2.RequestLifecycle{
			Revision: lifecycle.GetRevision(), Status: requestv2.RequestStatus_REQUEST_STATUS_OPEN,
			CreatedAt: timestamppb.New(created), UpdatedAt: timestamppb.New(updated), ExpiresAt: timestamppb.New(expires),
		},
		Presentation: &requestv2.Presentation{
			Title: presentation.GetTitle(), Description: presentation.GetDescription(),
			Category: requestv2.PresentationCategory_PRESENTATION_CATEGORY_SIGNAL,
		},
		Action: &requestv2.ActionCapability{
			CapabilityId: action.GetCapabilityId(), CapabilityVersion: action.GetCapabilityVersion(),
			PluginId: action.GetPluginId(), Parameters: parameters,
		},
		OwnerInputs: inputs,
		Audience: &requestv2.Audience{Audience: &requestv2.Audience_Feed{
			Feed: &requestv2.FeedAudience{Channel: channel},
		}},
		ResultHandling: &requestv2.ResultHandling{Mode: requestv2.ResultMode_RESULT_MODE_DEVICE_LOCAL},
	}, nil
}

func values(given []*requestv2.Value) ([]*requestv2.Value, *Fault) {
	if len(given) > MaxValues {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_VALUES, "action.parameters")
	}
	seen := make(map[string]bool, len(given))
	result := make([]*requestv2.Value, 0, len(given))
	for _, value := range given {
		if value == nil || !IsOperation(value.GetKey()) || seen[value.GetKey()] {
			problem := gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_VALUE
			if value != nil && seen[value.GetKey()] {
				problem = gatewayv1.GatewayProblem_GATEWAY_PROBLEM_DUPLICATE_VALUE
			}
			return nil, fault(problem, "action.parameters")
		}
		seen[value.GetKey()] = true
		copy := &requestv2.Value{Key: value.GetKey()}
		switch one := value.GetValue().(type) {
		case *requestv2.Value_Text:
			if !printable(one.Text, MaxValueTextBytes, true) {
				return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_VALUE, "action.parameters")
			}
			copy.Value = &requestv2.Value_Text{Text: one.Text}
		case *requestv2.Value_Integer:
			if !decimal(one.Integer) {
				return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_VALUE, "action.parameters")
			}
			copy.Value = &requestv2.Value_Integer{Integer: one.Integer}
		case *requestv2.Value_Flag:
			copy.Value = &requestv2.Value_Flag{Flag: one.Flag}
		case *requestv2.Value_Opaque:
			if len(one.Opaque) > MaxValueTextBytes {
				return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_VALUE, "action.parameters")
			}
			copy.Value = &requestv2.Value_Opaque{Opaque: append([]byte(nil), one.Opaque...)}
		default:
			return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_VALUE, "action.parameters")
		}
		result = append(result, copy)
	}
	return result, nil
}

func ownerInputs(given []*requestv2.OwnerInput) ([]*requestv2.OwnerInput, *Fault) {
	if len(given) > MaxOwnerInputs {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_VALUES, "owner_inputs")
	}
	seen := make(map[string]bool, len(given))
	result := make([]*requestv2.OwnerInput, 0, len(given))
	for _, input := range given {
		if input == nil || !IsOperation(input.GetKey()) || seen[input.GetKey()] ||
			input.GetKind() == requestv2.OwnerInputKind_OWNER_INPUT_KIND_UNSPECIFIED ||
			!printable(input.GetLabel(), MaxNameBytes, false) || input.GetLabel() == "" ||
			!printable(input.GetHelp(), MaxValueTextBytes, true) ||
			(input.GetMinimum() != "" && !decimal(input.GetMinimum())) ||
			(input.GetMaximum() != "" && !decimal(input.GetMaximum())) ||
			len(input.GetOptions()) > MaxInputOptions {
			return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_VALUE, "owner_inputs")
		}
		seen[input.GetKey()] = true
		options := make([]*requestv2.InputOption, 0, len(input.GetOptions()))
		optionKeys := map[string]bool{}
		for _, option := range input.GetOptions() {
			if option == nil || !IsOperation(option.GetValue()) || optionKeys[option.GetValue()] ||
				!printable(option.GetLabel(), MaxNameBytes, false) || option.GetLabel() == "" {
				return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_VALUE, "owner_inputs.options")
			}
			optionKeys[option.GetValue()] = true
			options = append(options, &requestv2.InputOption{Value: option.GetValue(), Label: option.GetLabel()})
		}
		if input.GetKind() == requestv2.OwnerInputKind_OWNER_INPUT_KIND_CHOICE && len(options) == 0 {
			return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_VALUE, "owner_inputs.options")
		}
		result = append(result, &requestv2.OwnerInput{
			Key: input.GetKey(), Label: input.GetLabel(), Kind: input.GetKind(), Required: input.GetRequired(),
			Minimum: input.GetMinimum(), Maximum: input.GetMaximum(), Options: options, Help: input.GetHelp(),
		})
	}
	return result, nil
}

func decimal(value string) bool {
	if value == "" || len(value) > 128 {
		return false
	}
	for _, r := range value {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

// RequestFromProposal and ProposalFromRequest are the protocol-1 adapters. They contain no
// lifecycle decision: both representations still pass through their native validators.
func RequestFromProposal(proposal *proposalv1.Proposal) *requestv2.Request {
	status := requestv2.RequestStatus_REQUEST_STATUS_OPEN
	if proposal.GetStatus() == proposalv1.ProposalStatus_PROPOSAL_STATUS_CANCELLED {
		status = requestv2.RequestStatus_REQUEST_STATUS_CANCELLED
	}
	parameters := make([]*requestv2.Value, 0, len(proposal.GetValues()))
	for _, value := range proposal.GetValues() {
		parameters = append(parameters, &requestv2.Value{Key: value.GetKey(), Value: &requestv2.Value_Text{Text: value.GetText()}})
	}
	return &requestv2.Request{
		ContractVersion: RequestContract,
		Identity:        &requestv2.RequestIdentity{SourceId: proposal.GetServerId(), Scope: proposal.GetChannel(), RequestId: proposal.GetProposalId()},
		Lifecycle:       &requestv2.RequestLifecycle{Revision: proposal.GetRevision(), Status: status, CreatedAt: proposal.GetCreatedAt(), UpdatedAt: proposal.GetUpdatedAt(), ExpiresAt: proposal.GetExpiresAt()},
		Presentation:    &requestv2.Presentation{Title: proposal.GetOperation(), Description: proposal.GetPublisherNote(), Category: requestv2.PresentationCategory_PRESENTATION_CATEGORY_SIGNAL},
		Action:          &requestv2.ActionCapability{CapabilityId: proposal.GetOperation(), CapabilityVersion: 1, PluginId: proposal.GetPluginId(), Parameters: parameters},
		Audience:        &requestv2.Audience{Audience: &requestv2.Audience_Feed{Feed: &requestv2.FeedAudience{Channel: proposal.GetChannel()}}},
		ResultHandling:  &requestv2.ResultHandling{Mode: requestv2.ResultMode_RESULT_MODE_DEVICE_LOCAL},
	}
}

func ProposalFromRequest(request *requestv2.Request) *proposalv1.Proposal {
	status := proposalv1.ProposalStatus_PROPOSAL_STATUS_OPEN
	if request.GetLifecycle().GetStatus() == requestv2.RequestStatus_REQUEST_STATUS_CANCELLED {
		status = proposalv1.ProposalStatus_PROPOSAL_STATUS_CANCELLED
	}
	values := make([]*proposalv1.ProposalValue, 0, len(request.GetAction().GetParameters()))
	for _, value := range request.GetAction().GetParameters() {
		text := value.GetText()
		switch one := value.GetValue().(type) {
		case *requestv2.Value_Integer:
			text = one.Integer
		case *requestv2.Value_Flag:
			text = strconv.FormatBool(one.Flag)
		case *requestv2.Value_Opaque:
			text = base64.StdEncoding.EncodeToString(one.Opaque)
		}
		values = append(values, &proposalv1.ProposalValue{Key: value.GetKey(), Text: text})
	}
	return &proposalv1.Proposal{
		ServerId: request.GetIdentity().GetSourceId(), Channel: request.GetIdentity().GetScope(), ProposalId: request.GetIdentity().GetRequestId(),
		Revision: request.GetLifecycle().GetRevision(), Operation: request.GetAction().GetCapabilityId(), PluginId: request.GetAction().GetPluginId(), Status: status,
		CreatedAt: request.GetLifecycle().GetCreatedAt(), UpdatedAt: request.GetLifecycle().GetUpdatedAt(), ExpiresAt: request.GetLifecycle().GetExpiresAt(),
		PublisherNote: request.GetPresentation().GetDescription(), Values: values,
	}
}

func AdvanceRequest(held, next *requestv2.Request) (Decision, *Fault) {
	if held == nil {
		return Stored, nil
	}
	if held.GetLifecycle().GetStatus() == requestv2.RequestStatus_REQUEST_STATUS_CANCELLED {
		return Stored, &Fault{Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_CANCELLED, Field: "identity.request_id", Held: held.GetLifecycle().GetRevision()}
	}
	switch {
	case next.GetLifecycle().GetRevision() < held.GetLifecycle().GetRevision():
		return Stored, &Fault{Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_STALE_REVISION, Field: "lifecycle.revision", Held: held.GetLifecycle().GetRevision()}
	case next.GetLifecycle().GetRevision() == held.GetLifecycle().GetRevision():
		if proto.Equal(held, next) {
			return Unchanged, nil
		}
		return Stored, &Fault{Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_REVISION_CONFLICT, Field: "lifecycle.revision", Held: held.GetLifecycle().GetRevision()}
	}
	if !held.GetLifecycle().GetCreatedAt().AsTime().Equal(next.GetLifecycle().GetCreatedAt().AsTime()) {
		return Stored, &Fault{Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_TIMES, Field: "lifecycle.created_at", Held: held.GetLifecycle().GetRevision()}
	}
	return Stored, nil
}

func CancelledRequest(held *requestv2.Request, revision uint64, at time.Time) (*requestv2.Request, *Fault) {
	if revision == 0 || revision > MaxRevision {
		return nil, &Fault{Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_REVISION, Field: "lifecycle.revision", Held: held.GetLifecycle().GetRevision()}
	}
	if revision <= held.GetLifecycle().GetRevision() {
		return nil, &Fault{Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_STALE_REVISION, Field: "lifecycle.revision", Held: held.GetLifecycle().GetRevision()}
	}
	withdrawn := proto.CloneOf(held)
	withdrawn.Lifecycle.Revision = revision
	withdrawn.Lifecycle.Status = requestv2.RequestStatus_REQUEST_STATUS_CANCELLED
	withdrawn.Lifecycle.UpdatedAt = timestamppb.New(at.UTC().Truncate(time.Second))
	return withdrawn, nil
}
