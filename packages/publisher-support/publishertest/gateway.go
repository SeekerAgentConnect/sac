// Package publishertest is the test support both demos and the support library share: a gateway
// of our own, served through the generated handler, and the harness that runs the real feed
// gateway when one is available (SEE-134).
//
// It is here, as ordinary code rather than a `_test.go` file, because a test file is not
// importable and three copies of one fake gateway would be three slightly different opinions about
// what the real one does. Nothing in this package is compiled into a demo's binary: no command
// imports it, and only tests do.
//
// It deliberately emulates only the rules a publisher's own behaviour depends on — the same
// document again is "unchanged", a withdrawal of something it does not hold is "no such proposal",
// and a cancelled status is not a publication. The authority for the gateway's rules is the real
// binary, which [RunGateway] starts when SEEKERVAULT_FEED_GATEWAY names one.
package publishertest

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

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/gateway/v1/gatewayv1connect"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/proposal/v1"
	requestv2 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/request/v2"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// A gateway of our own, served through the generated handler so that everything between this
// template and it is real: the Connect protocol, the codec, the headers, the error details.
//
// It emulates only the two rules a publisher's own behaviour depends on — the same document again
// is "unchanged", and a withdrawal of something it does not hold is "no such proposal" — and it is
// deliberately not a second implementation of the gateway's rules. The authority for those is the
// real binary, which [RunGateway] runs when it is available.
type FakeGateway struct {
	gatewayv1connect.UnimplementedPublisherServiceHandler
	mutex sync.Mutex
	// Every credential presented, so a test can assert that one was and that it is the right one.
	Credentials []string
	Manifests   []*serverv1.ServerManifest
	Proposals   []*proposalv1.Proposal
	Withdrawals []*gatewayv1.CancelProposalRequest
	// What it holds, by identity, for the unchanged answer.
	heldManifest  *serverv1.ServerManifest
	heldProposals map[string]*proposalv1.Proposal
	// Every call, by procedure name, whether it was refused or not: an attempt is a thing a test
	// about retrying has to be able to count.
	attempts map[string]int
	// Injected refusals, by procedure name ("PublishProposal"). A nil error lets the call through.
	Refuse func(procedure string) error
	// What Heartbeat answers with, in seconds (SEE-150). Zero means the gateway's own default, so
	// a test that does not care about the interval says nothing about it.
	HeartbeatSeconds uint32
}

// Heartbeat is a publisher saying it is running (SEE-150). It publishes nothing, and the fake
// records it exactly as it records a publication, so a test can count check-ins and assert the
// credential one carried.
//
// A fake that did not serve this would answer `unimplemented`, which is a real answer — an older
// gateway's — and one the presence loop treats as "stop asking". Both are worth being able to test,
// so this is here and [FakeGateway.Refuse] can still take it away.
func (f *FakeGateway) Heartbeat(
	_ context.Context,
	request *connect.Request[gatewayv1.HeartbeatRequest],
) (*connect.Response[gatewayv1.HeartbeatResponse], error) {
	f.mutex.Lock()
	f.note("Heartbeat", request.Header().Get("Authorization"))
	if err := f.refusal("Heartbeat"); err != nil {
		f.mutex.Unlock()
		return nil, err
	}
	seconds := f.HeartbeatSeconds
	f.mutex.Unlock()
	return connect.NewResponse(&gatewayv1.HeartbeatResponse{IntervalSeconds: seconds}), nil
}

func (f *FakeGateway) PublishRequest(
	ctx context.Context,
	request *connect.Request[gatewayv1.PublishRequestRequest],
) (*connect.Response[gatewayv1.PublishRequestResponse], error) {
	f.mutex.Lock()
	f.note("PublishRequest", request.Header().Get("Authorization"))
	if err := f.refusal("PublishRequest"); err != nil {
		f.mutex.Unlock()
		return nil, err
	}
	f.mutex.Unlock()
	legacy := connect.NewRequest(&gatewayv1.PublishProposalRequest{Proposal: ProposalFromRequest(request.Msg.GetRequest())})
	legacy.Header().Set("Authorization", request.Header().Get("Authorization"))
	answer, err := f.PublishProposal(ctx, legacy)
	if err != nil {
		return nil, err
	}
	return connect.NewResponse(&gatewayv1.PublishRequestResponse{
		Status: answer.Msg.GetStatus(), Revision: answer.Msg.GetRevision(), SnapshotSequence: answer.Msg.GetSnapshotSequence(),
	}), nil
}

func (f *FakeGateway) CancelRequest(
	ctx context.Context,
	request *connect.Request[gatewayv1.CancelRequestRequest],
) (*connect.Response[gatewayv1.CancelRequestResponse], error) {
	f.mutex.Lock()
	f.note("CancelRequest", request.Header().Get("Authorization"))
	if err := f.refusal("CancelRequest"); err != nil {
		f.mutex.Unlock()
		return nil, err
	}
	f.mutex.Unlock()
	legacy := connect.NewRequest(&gatewayv1.CancelProposalRequest{ProposalId: request.Msg.GetRequestId(), Revision: request.Msg.GetRevision()})
	legacy.Header().Set("Authorization", request.Header().Get("Authorization"))
	answer, err := f.CancelProposal(ctx, legacy)
	if err != nil {
		return nil, err
	}
	return connect.NewResponse(&gatewayv1.CancelRequestResponse{
		Status: answer.Msg.GetStatus(), SnapshotSequence: answer.Msg.GetSnapshotSequence(),
	}), nil
}

func ProposalFromRequest(request *requestv2.Request) *proposalv1.Proposal {
	values := make([]*proposalv1.ProposalValue, 0, len(request.GetAction().GetParameters()))
	for _, value := range request.GetAction().GetParameters() {
		values = append(values, &proposalv1.ProposalValue{Key: value.GetKey(), Text: value.GetText()})
	}
	return &proposalv1.Proposal{
		ServerId: request.GetIdentity().GetSourceId(), Channel: request.GetIdentity().GetScope(), ProposalId: request.GetIdentity().GetRequestId(),
		Revision: request.GetLifecycle().GetRevision(), Operation: request.GetAction().GetCapabilityId(), PluginId: request.GetAction().GetPluginId(),
		Status: proposalv1.ProposalStatus_PROPOSAL_STATUS_OPEN, CreatedAt: request.GetLifecycle().GetCreatedAt(),
		UpdatedAt: request.GetLifecycle().GetUpdatedAt(), ExpiresAt: request.GetLifecycle().GetExpiresAt(),
		PublisherNote: request.GetPresentation().GetDescription(), Values: values,
	}
}

func (f *FakeGateway) PublishManifest(
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
	f.Manifests = append(f.Manifests, proto.CloneOf(manifest))
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

func (f *FakeGateway) PublishProposal(
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
	// than stored (packages/protocol/proto/seekervault/gateway/v1/publish.proto, rules.Proposal).
	if proposal.GetStatus() == proposalv1.ProposalStatus_PROPOSAL_STATUS_CANCELLED {
		return nil, Problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_CANCEL_ON_PUBLISH,
			connect.CodeInvalidArgument, "status")
	}
	f.Proposals = append(f.Proposals, proto.CloneOf(proposal))
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

func (f *FakeGateway) CancelProposal(
	ctx context.Context,
	request *connect.Request[gatewayv1.CancelProposalRequest],
) (*connect.Response[gatewayv1.CancelProposalResponse], error) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	f.note("CancelProposal", request.Header().Get("Authorization"))
	if err := f.refusal("CancelProposal"); err != nil {
		return nil, err
	}
	f.Withdrawals = append(f.Withdrawals, proto.CloneOf(request.Msg))
	held := f.heldProposals[request.Msg.GetProposalId()]
	if held == nil {
		return nil, Problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_PROPOSAL,
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

func (f *FakeGateway) note(procedure, credential string) {
	f.Credentials = append(f.Credentials, credential)
	if f.attempts == nil {
		f.attempts = map[string]int{}
	}
	f.attempts[procedure]++
}

// Tried is how many times a procedure was called, refusals included.
func (f *FakeGateway) Tried(procedure string) int {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	return f.attempts[procedure]
}

func (f *FakeGateway) refusal(procedure string) error {
	if f.Refuse == nil {
		return nil
	}
	return f.Refuse(procedure)
}

// Held is what the fake currently holds for a proposal, for a test that asserts a withdrawal
// landed.
func (f *FakeGateway) Held(id string) *proposalv1.Proposal {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	return f.heldProposals[id]
}

func (f *FakeGateway) Seen() (manifests, proposals, withdrawals int) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	return len(f.Manifests), len(f.Proposals), len(f.Withdrawals)
}

// Problem is a refusal in the gateway's own shape: a Connect code, and a GatewayErrorDetail whose
// problem is the word a publisher branches on.
func Problem(kind gatewayv1.GatewayProblem, code connect.Code, field string) *connect.Error {
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

// Holding is [Problem] for a refusal that also names the revision the gateway holds, which is how
// the gateway answers a stale revision and a revision conflict (GatewayErrorDetail.held_revision).
func Holding(kind gatewayv1.GatewayProblem, field string, held uint64) *connect.Error {
	failure := connect.NewError(connect.CodeFailedPrecondition, errors.New(strings.ToLower(
		strings.TrimPrefix(kind.String(), "GATEWAY_PROBLEM_"))+" ("+field+")"))
	if detail, err := connect.NewErrorDetail(&gatewayv1.GatewayErrorDetail{
		Problem:      kind,
		Field:        field,
		HeldRevision: held,
	}); err == nil {
		failure.AddDetail(detail)
	}
	return failure
}

// Serve serves the fake through the generated handler and returns the running server.
//
// It stops short of building a publication client on purpose: the client is the support library's
// `gateway` package, whose own tests use this fake, and a package that both imports it and is
// imported by it could not compile.
func Serve(t *testing.T, fake *FakeGateway) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	mux.Handle(gatewayv1connect.NewPublisherServiceHandler(fake))
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	return server
}

const (
	// Credential is the publisher grant every test presents, and ServerID the publisher every
	// test is. They are constants so that an assertion about which credential reached the gateway
	// reads the same wherever it is written.
	Credential = "bhnRxR4o5RPDqYKTJZfmfbv9OmGGKFNxBOebFHYGJuc"
	ServerID   = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
)

// Now is the instant every test starts from.
var Now = time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)

// Swap is one ordinary CopyTrading signal, for a test that needs a valid document and does not
// care what is in it.
func Swap() signals.Signal {
	return signals.Signal{
		ProposalID: "8c9d0e1f-2a3b-4c5d-8e6f-7a8b9c0d1e2f",
		Revision:   1,
		Status:     signals.Open,
		Operation:  "swap",
		PluginID:   "jupiter.swap",
		CreatedAt:  Now,
		UpdatedAt:  Now,
		ExpiresAt:  Now.Add(time.Hour),
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

// ManifestAt is the manifest this publisher publishes about itself at one revision.
func ManifestAt(revision uint64) *serverv1.ServerManifest {
	return &serverv1.ServerManifest{
		ServerId:         ServerID,
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
			Channel:    signals.ChannelFor(ServerID),
		}},
	}
}
