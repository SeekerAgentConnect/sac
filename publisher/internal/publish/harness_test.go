package publish

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/protobuf/proto"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/gateway/v1/gatewayv1connect"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/proposal/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/signals"
)

// A gateway of our own, served through the generated handler so that everything between this
// template and it is real: the Connect protocol, the codec, the headers, the error details.
//
// It emulates only the two rules a publisher's own behaviour depends on — the same document again
// is "unchanged", and a withdrawal of something it does not hold is "no such proposal" — and it is
// deliberately not a second implementation of the gateway's rules. The authority for those is the
// real binary, which `gateway_test.go` runs when it is available.
type fakeGateway struct {
	mutex sync.Mutex
	// Every credential presented, so a test can assert that one was and that it is the right one.
	credentials []string
	manifests   []*serverv1.ServerManifest
	proposals   []*proposalv1.Proposal
	withdrawals []*gatewayv1.CancelProposalRequest
	// What it holds, by identity, for the unchanged answer.
	heldManifest  *serverv1.ServerManifest
	heldProposals map[string]*proposalv1.Proposal
	// Every call, by procedure name, whether it was refused or not: an attempt is a thing a test
	// about retrying has to be able to count.
	attempts map[string]int
	// Injected refusals, by procedure name ("PublishProposal"). A nil error lets the call through.
	refuse func(procedure string) error
}

func (f *fakeGateway) PublishManifest(
	ctx context.Context,
	request *connect.Request[gatewayv1.PublishManifestRequest],
) (*connect.Response[gatewayv1.PublishManifestResponse], error) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	f.note("PublishManifest", request.Header().Get("Authorization"))
	if err := f.refusal("PublishManifest"); err != nil {
		return nil, err
	}
	manifest := request.Msg.GetManifest()
	f.manifests = append(f.manifests, proto.CloneOf(manifest))
	status := gatewayv1.PublishStatus_PUBLISH_STATUS_STORED
	if proto.Equal(f.heldManifest, manifest) {
		status = gatewayv1.PublishStatus_PUBLISH_STATUS_UNCHANGED
	} else {
		f.heldManifest = proto.CloneOf(manifest)
	}
	return connect.NewResponse(&gatewayv1.PublishManifestResponse{
		Status:           status,
		SettingsRevision: manifest.GetSettingsRevision(),
	}), nil
}

func (f *fakeGateway) PublishProposal(
	ctx context.Context,
	request *connect.Request[gatewayv1.PublishProposalRequest],
) (*connect.Response[gatewayv1.PublishProposalResponse], error) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	f.note("PublishProposal", request.Header().Get("Authorization"))
	if err := f.refusal("PublishProposal"); err != nil {
		return nil, err
	}
	proposal := request.Msg.GetProposal()
	// The gateway's own rule: a cancelled status is not a publication. Withdrawing is a transition
	// and CancelProposal is where it happens, so publishing a withdrawn document is refused rather
	// than stored (proto/seekervault/gateway/v1/publish.proto, rules.Proposal).
	if proposal.GetStatus() == proposalv1.ProposalStatus_PROPOSAL_STATUS_CANCELLED {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_CANCEL_ON_PUBLISH,
			connect.CodeInvalidArgument, "status")
	}
	f.proposals = append(f.proposals, proto.CloneOf(proposal))
	if f.heldProposals == nil {
		f.heldProposals = map[string]*proposalv1.Proposal{}
	}
	status := gatewayv1.PublishStatus_PUBLISH_STATUS_STORED
	if proto.Equal(f.heldProposals[proposal.GetProposalId()], proposal) {
		status = gatewayv1.PublishStatus_PUBLISH_STATUS_UNCHANGED
	} else {
		f.heldProposals[proposal.GetProposalId()] = proto.CloneOf(proposal)
	}
	return connect.NewResponse(&gatewayv1.PublishProposalResponse{
		Status:   status,
		Revision: proposal.GetRevision(),
	}), nil
}

func (f *fakeGateway) CancelProposal(
	ctx context.Context,
	request *connect.Request[gatewayv1.CancelProposalRequest],
) (*connect.Response[gatewayv1.CancelProposalResponse], error) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	f.note("CancelProposal", request.Header().Get("Authorization"))
	if err := f.refusal("CancelProposal"); err != nil {
		return nil, err
	}
	f.withdrawals = append(f.withdrawals, proto.CloneOf(request.Msg))
	held := f.heldProposals[request.Msg.GetProposalId()]
	if held == nil {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_PROPOSAL,
			connect.CodeNotFound, "proposal_id")
	}
	withdrawn := proto.CloneOf(held)
	withdrawn.Revision = request.Msg.GetRevision()
	withdrawn.Status = proposalv1.ProposalStatus_PROPOSAL_STATUS_CANCELLED
	f.heldProposals[request.Msg.GetProposalId()] = withdrawn
	return connect.NewResponse(&gatewayv1.CancelProposalResponse{
		Status:   gatewayv1.PublishStatus_PUBLISH_STATUS_STORED,
		Proposal: withdrawn,
	}), nil
}

func (f *fakeGateway) note(procedure, credential string) {
	f.credentials = append(f.credentials, credential)
	if f.attempts == nil {
		f.attempts = map[string]int{}
	}
	f.attempts[procedure]++
}

// tried is how many times a procedure was called, refusals included.
func (f *fakeGateway) tried(procedure string) int {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	return f.attempts[procedure]
}

func (f *fakeGateway) refusal(procedure string) error {
	if f.refuse == nil {
		return nil
	}
	return f.refuse(procedure)
}

// held is what the fake currently holds for a proposal, for a test that asserts a withdrawal
// landed.
func (f *fakeGateway) held(id string) *proposalv1.Proposal {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	return f.heldProposals[id]
}

func (f *fakeGateway) seen() (manifests, proposals, withdrawals int) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	return len(f.manifests), len(f.proposals), len(f.withdrawals)
}

// problem is a refusal in the gateway's own shape: a Connect code, and a GatewayErrorDetail whose
// problem is the word a publisher branches on.
func problem(kind gatewayv1.GatewayProblem, code connect.Code, field string) *connect.Error {
	failure := connect.NewError(code, errors.New(strings.ToLower(
		strings.TrimPrefix(kind.String(), "GATEWAY_PROBLEM_"))+" ("+field+")"))
	if detail, err := connect.NewErrorDetail(&gatewayv1.GatewayErrorDetail{
		Problem: kind,
		Field:   field,
	}); err == nil {
		failure.AddDetail(detail)
	}
	return failure
}

// serve starts the fake and returns a client for it.
func serve(t *testing.T, fake *fakeGateway) *Gateway {
	t.Helper()
	mux := http.NewServeMux()
	mux.Handle(gatewayv1connect.NewPublisherServiceHandler(fake))
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	gateway, err := New(Options{
		URL:        server.URL,
		Credential: credential,
		Timeout:    5 * time.Second,
		HTTP:       server.Client(),
	})
	if err != nil {
		t.Fatal(err)
	}
	return gateway
}

const (
	credential = "bhnRxR4o5RPDqYKTJZfmfbv9OmGGKFNxBOebFHYGJuc"
	server     = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
)

var now = time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)

func swap() signals.Signal {
	return signals.Signal{
		ProposalID: "8c9d0e1f-2a3b-4c5d-8e6f-7a8b9c0d1e2f",
		Revision:   1,
		Status:     signals.Open,
		Operation:  "swap",
		PluginID:   "jupiter.swap",
		CreatedAt:  now,
		UpdatedAt:  now,
		ExpiresAt:  now.Add(time.Hour),
		Note:       "trimming SOL into USDC",
		Terms: map[string]string{
			signals.InputMint:      signals.WrappedSOL,
			signals.InputDecimals:  "9",
			signals.OutputMint:     "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
			signals.OutputDecimals: "6",
			signals.MaxSlippageBps: "50",
		},
	}
}

func manifestAt(revision uint64) *serverv1.ServerManifest {
	return &serverv1.ServerManifest{
		ServerId:         server,
		ProtocolVersion:  1,
		SettingsRevision: revision,
		Mode:             serverv1.ConnectionMode_CONNECTION_MODE_GATEWAY_FEED,
		RequiredPlugins: []*serverv1.PluginRequirement{{
			PluginId: "jupiter.swap", MinContract: 1, MaxContract: 1,
		}},
		Environments: []serverv1.ServerEnvironment{
			serverv1.ServerEnvironment_SERVER_ENVIRONMENT_PRODUCTION,
		},
		Reference: &serverv1.ServerManifest_Feed{Feed: &serverv1.GatewayFeed{
			GatewayUrl: "https://feeds.example.com",
			Channel:    signals.ChannelFor(server),
		}},
	}
}
