package api_test

import (
	"testing"

	"connectrpc.com/connect"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/api"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/demotest"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/environment"
	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/gateway/v1"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/proposal/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/publishertest"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// These tests drive the API from outside, through the shared harness, because that harness is also
// what the Prediction demo's own tests use: one driver, one fake gateway, one opinion of what the
// real one does (publisher-support/publishertest).

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

func startIn(t *testing.T, fake *fakeGateway, named environment.Environment) *template {
	t.Helper()
	return demotest.StartIn(t, fake, named)
}

func startWith(
	t *testing.T,
	fake *fakeGateway,
	kind signals.Kind,
	discovering func(*template, *api.Plan),
) *template {
	t.Helper()
	return demotest.StartWith(t, fake, kind, discovering)
}

func swapStatement() map[string]any { return demotest.SwapStatement() }

func refusal(kind gatewayv1.GatewayProblem, code connect.Code) func(string) error {
	return demotest.Refusal(kind, code)
}

// documents is every proposal the fake was sent, in order.
func documents(fake *fakeGateway) []*proposalv1.Proposal {
	return append([]*proposalv1.Proposal{}, fake.Proposals...)
}
