package rules

import (
	"testing"
	"time"

	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/reflect/protoreflect"
	"google.golang.org/protobuf/types/known/timestamppb"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	requestv2 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/request/v2"
)

func commonRequest(change ...func(*requestv2.Request)) *requestv2.Request {
	message := &requestv2.Request{
		ContractVersion: RequestContract,
		Identity: &requestv2.RequestIdentity{
			SourceId: publisher, Scope: ChannelFor(publisher), RequestId: proposalA,
		},
		Lifecycle: &requestv2.RequestLifecycle{
			Revision: 4, Status: requestv2.RequestStatus_REQUEST_STATUS_OPEN,
			CreatedAt: timestamppb.New(published), UpdatedAt: timestamppb.New(published.Add(time.Minute)),
			ExpiresAt: timestamppb.New(published.Add(time.Hour)),
		},
		Presentation: &requestv2.Presentation{
			Title: "Swap SOL for USDC", Description: "Follow the strategy allocation.",
			Category: requestv2.PresentationCategory_PRESENTATION_CATEGORY_SIGNAL,
		},
		Action: &requestv2.ActionCapability{
			CapabilityId: "swap", CapabilityVersion: 1, PluginId: "jupiter.swap",
			Parameters: []*requestv2.Value{{Key: "input_mint", Value: &requestv2.Value_Text{Text: "So111"}}},
		},
		OwnerInputs: []*requestv2.OwnerInput{{
			Key: "input_amount", Label: "Amount", Kind: requestv2.OwnerInputKind_OWNER_INPUT_KIND_AMOUNT,
			Required: true, Minimum: "1", Help: "Entered on this device",
		}},
		Audience: &requestv2.Audience{Audience: &requestv2.Audience_Feed{
			Feed: &requestv2.FeedAudience{Channel: ChannelFor(publisher)},
		}},
		ResultHandling: &requestv2.ResultHandling{Mode: requestv2.ResultMode_RESULT_MODE_DEVICE_LOCAL},
	}
	for _, apply := range change {
		apply(message)
	}
	return message
}

func TestACommonFeedRequestIsRebuiltFieldForField(t *testing.T) {
	message := commonRequest()
	message.ProtoReflect().SetUnknown([]byte{0x9a, 0x06, 0x03, 'b', 'a', 'd'})

	stored, fault := Request(message, expectation())
	if fault != nil {
		t.Fatalf("valid request refused: %v (%s)", fault.Problem, fault.Field)
	}
	if len(stored.ProtoReflect().GetUnknown()) != 0 {
		t.Fatal("unknown request fields were relayed")
	}
	expected := commonRequest()
	if !proto.Equal(stored, expected) {
		t.Fatalf("stored request differs:\n%s", stored)
	}
}

func TestAFeedCanNeverReturnAnOwnersState(t *testing.T) {
	for _, one := range []struct {
		name    string
		change  func(*requestv2.Request)
		problem gatewayv1.GatewayProblem
	}{
		{"private audience", func(r *requestv2.Request) {
			r.Audience = &requestv2.Audience{Audience: &requestv2.Audience_Private{
				Private: &requestv2.PrivateAudience{RecipientId: "device"},
			}}
		}, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_FOREIGN_CHANNEL},
		{"result returned to publisher", func(r *requestv2.Request) {
			r.ResultHandling.Mode = requestv2.ResultMode_RESULT_MODE_RETURN_TO_ORIGIN
		}, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_VALUE},
	} {
		t.Run(one.name, func(t *testing.T) {
			stored, fault := Request(commonRequest(one.change), expectation())
			if stored != nil || fault == nil || fault.Problem != one.problem {
				t.Fatalf("stored=%v fault=%v, expected %v", stored, fault, one.problem)
			}
		})
	}

	// Privacy is structural: the broadcast envelope has declarations, but no slot for an answer,
	// selected wallet, subscriber identity, decision, prepared bytes, or execution result.
	forbidden := map[protoreflect.Name]bool{
		"owner_value": true, "wallet": true, "subscriber": true, "decision": true,
		"prepared": true, "result": true,
	}
	fields := commonRequest().ProtoReflect().Descriptor().Fields()
	for index := 0; index < fields.Len(); index++ {
		if forbidden[fields.Get(index).Name()] {
			t.Fatalf("broadcast request exposes private field %q", fields.Get(index).Name())
		}
	}
}

func TestLegacyProposalAdapterKeepsIdentityRevisionAndTerms(t *testing.T) {
	legacy := proposal()
	adapted := RequestFromProposal(legacy)
	stored, fault := Request(adapted, expectation())
	if fault != nil {
		t.Fatalf("legacy proposal did not adapt: %v", fault)
	}
	roundTrip := ProposalFromRequest(stored)
	if !proto.Equal(roundTrip, legacy) {
		t.Fatalf("legacy migration changed the document:\n%s", roundTrip)
	}
}

func TestARequestCancellationIsFinalAndOrdered(t *testing.T) {
	held := commonRequest()
	cancelled, fault := CancelledRequest(held, 5, published.Add(2*time.Minute))
	if fault != nil || cancelled.GetLifecycle().GetStatus() != requestv2.RequestStatus_REQUEST_STATUS_CANCELLED {
		t.Fatalf("cancelled=%v fault=%v", cancelled, fault)
	}
	if _, fault := AdvanceRequest(cancelled, commonRequest(func(r *requestv2.Request) {
		r.Lifecycle.Revision = 6
	})); fault == nil || fault.Problem != gatewayv1.GatewayProblem_GATEWAY_PROBLEM_CANCELLED {
		t.Fatalf("publication over cancellation was accepted: %v", fault)
	}
	if _, fault := CancelledRequest(held, 0, published); fault == nil || fault.Problem != gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_REVISION {
		t.Fatalf("zero cancellation revision answered %v", fault)
	}
}
