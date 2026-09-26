package api_test

import (
	"testing"

	"connectrpc.com/connect"

	support "github.com/BrRenat/SeekerAgentWallet/publisher-support/api"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/demotest"
	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/gateway/v1"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/proposal/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/publishertest"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// This demo's API is the shared frame composed with this demo's discovery, so it is tested the way
// it is deployed: the real reconciler, the real store, the real drainer and the shared harness
// (publisher-support/publishertest).

type (
	fakeGateway = publishertest.FakeGateway
	template    = demotest.Template
	answer      = demotest.Answer
)

const (
	server   = publishertest.ServerID
	token    = demotest.Token
	proposal = demotest.Proposal
	usdc     = demotest.USDC
)

var now = publishertest.Now

func start(t *testing.T, fake *fakeGateway) *template {
	t.Helper()
	return demotest.Start(t, fake)
}

func startWith(
	t *testing.T,
	fake *fakeGateway,
	kind signals.Kind,
	discovering func(*template, *support.Plan),
) *template {
	t.Helper()
	return demotest.StartWith(t, fake, kind, discovering)
}

func swapStatement() map[string]any { return demotest.SwapStatement() }

// documents is every proposal the fake was sent, in order.
func documents(fake *fakeGateway) []*proposalv1.Proposal {
	return append([]*proposalv1.Proposal{}, fake.Proposals...)
}

func refusal(kind gatewayv1.GatewayProblem, code connect.Code) func(string) error {
	return demotest.Refusal(kind, code)
}
