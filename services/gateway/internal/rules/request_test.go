package rules

import (
	"testing"
	"time"

	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/reflect/protoreflect"
	"google.golang.org/protobuf/types/known/timestamppb"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	requestv2 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/request/v2"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
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
			Network:    serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET,
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

// A protocol-1 proposal names no network, so the request made from it is the legacy contract with
// none (SEE-185): readable, never executable, and never accepted as a new publication. The adapter
// keeps everything else, and the way back is exact.
func TestLegacyProposalAdapterKeepsIdentityRevisionAndTerms(t *testing.T) {
	legacy := proposal()
	adapted := RequestFromProposal(legacy)
	if adapted.GetContractVersion() != LegacyRequestContract ||
		adapted.GetAction().GetNetwork() != serverv1.SolanaNetwork_SOLANA_NETWORK_UNSPECIFIED {
		t.Fatalf("a protocol-1 proposal became contract %d on %v", adapted.GetContractVersion(),
			adapted.GetAction().GetNetwork())
	}
	if _, fault := Request(adapted, expectation()); fault == nil ||
		fault.Problem != gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_PROTOCOL {
		t.Fatalf("a network-less legacy document was accepted as a new publication: %v", fault)
	}
	if roundTrip := ProposalFromRequest(adapted); !proto.Equal(roundTrip, legacy) {
		t.Fatalf("legacy migration changed the document:\n%s", roundTrip)
	}
}

// Only contract 2 is published (SEE-185): version 1 says nothing about a network, and a version
// from the future is one this gateway cannot vouch for.
func TestOnlyTheCurrentContractIsPublished(t *testing.T) {
	for _, version := range []uint32{0, LegacyRequestContract, RequestContract + 1} {
		stored, fault := Request(commonRequest(func(r *requestv2.Request) {
			r.ContractVersion = version
		}), expectation())
		if stored != nil || fault == nil ||
			fault.Problem != gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_PROTOCOL ||
			fault.Field != "contract_version" {
			t.Fatalf("contract %d: stored=%v fault=%v", version, stored, fault)
		}
	}
}

// The network is required and never inferred (SEE-185): an action that names none, or one this
// gateway does not know, is refused rather than stored as Mainnet. One it knows is kept exactly.
func TestASignalNamesItsOwnNetwork(t *testing.T) {
	for _, network := range []serverv1.SolanaNetwork{
		serverv1.SolanaNetwork_SOLANA_NETWORK_UNSPECIFIED, serverv1.SolanaNetwork(4),
	} {
		stored, fault := Request(commonRequest(func(r *requestv2.Request) {
			r.Action.Network = network
		}), expectation())
		if stored != nil || fault == nil ||
			fault.Problem != gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_NETWORK ||
			fault.Field != "action.network" {
			t.Fatalf("%v: stored=%v fault=%v", network, stored, fault)
		}
	}
	for _, network := range []serverv1.SolanaNetwork{
		serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET,
		serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET,
		serverv1.SolanaNetwork_SOLANA_NETWORK_TESTNET,
	} {
		stored, fault := Request(commonRequest(func(r *requestv2.Request) {
			r.Action.Network = network
		}), expectation())
		if fault != nil {
			t.Fatalf("%v was refused: %v", network, fault.Problem)
		}
		// The rebuild copies it: a field-by-field copy that forgot it would publish a document that
		// no phone could ever execute.
		if stored.GetAction().GetNetwork() != network {
			t.Fatalf("%v was stored as %v", network, stored.GetAction().GetNetwork())
		}
	}
}

// A signal's network must be one its publisher's manifest declares, and a publisher with no
// manifest has declared none (SEE-185).
func TestASignalsNetworkMustBeDeclared(t *testing.T) {
	declared, fault := Manifest(manifest(func(m *serverv1.ServerManifest) {
		m.GetFeed().SupportedNetworks = []serverv1.SolanaNetwork{
			serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET, serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET,
		}
	}), expectation())
	if fault != nil {
		t.Fatal(fault.Problem)
	}
	for _, one := range []struct {
		name     string
		manifest *serverv1.ServerManifest
		network  serverv1.SolanaNetwork
		accepted bool
	}{
		{"mainnet, declared", declared, serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET, true},
		{"devnet, declared", declared, serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET, true},
		{"testnet, not declared", declared, serverv1.SolanaNetwork_SOLANA_NETWORK_TESTNET, false},
		{"no manifest at all", nil, serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET, false},
	} {
		t.Run(one.name, func(t *testing.T) {
			fault := NetworkDeclared(one.manifest, one.network)
			if one.accepted != (fault == nil) {
				t.Fatalf("answered %v", fault)
			}
			if fault != nil && (fault.Problem != gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_NETWORK ||
				fault.Field != "action.network") {
				t.Fatalf("refused as %v (%s)", fault.Problem, fault.Field)
			}
		})
	}
	if !ProtocolOneMayShow(serverv1.SolanaNetwork_SOLANA_NETWORK_UNSPECIFIED) ||
		ProtocolOneMayShow(serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET) {
		t.Fatal("the protocol-1 view would show a network-declaring document")
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
