package publish

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"connectrpc.com/connect"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/signals"
)

func TestAPublicationCarriesTheCredentialAndNothingElse(t *testing.T) {
	fake := &fakeGateway{}
	gateway := serve(t, fake)
	if _, err := gateway.Manifest(context.Background(), manifestAt(1)); err != nil {
		t.Fatal(err)
	}
	if len(fake.credentials) != 1 || fake.credentials[0] != "Bearer "+credential {
		t.Fatalf("the gateway saw %v", fake.credentials)
	}
}

// Both answers mean the gateway holds what was sent. The difference is whether anything changed,
// which is exactly what a retry needs to know: a retried publication is not an event.
func TestTheSameDocumentAgainIsUnchanged(t *testing.T) {
	fake := &fakeGateway{}
	gateway := serve(t, fake)
	ctx := context.Background()
	document := signals.Proposal(server, swap())

	first, err := gateway.Proposal(ctx, document)
	if err != nil {
		t.Fatal(err)
	}
	if first != Stored {
		t.Fatalf("a first publication was %s", first)
	}
	second, err := gateway.Proposal(ctx, signals.Proposal(server, swap()))
	if err != nil {
		t.Fatal(err)
	}
	if second != Unchanged {
		t.Fatalf("the same document again was %s", second)
	}
}

// A withdrawal of something the gateway does not hold is not a failure: it means the publication
// it would have withdrawn never arrived, so there is nothing to withdraw and nothing was ever
// public. A template that treated it as an error would retry it for ever.
func TestAWithdrawalOfSomethingNeverPublishedIsAbsentAndNotAnError(t *testing.T) {
	fake := &fakeGateway{}
	gateway := serve(t, fake)
	ctx := context.Background()

	status, err := gateway.Withdraw(ctx, swap().ProposalID, 2)
	if err != nil {
		t.Fatalf("expected Absent, got %v", err)
	}
	if status != Absent {
		t.Fatalf("status %s", status)
	}

	// And a withdrawal of something it does hold takes it back.
	if _, err := gateway.Proposal(ctx, signals.Proposal(server, swap())); err != nil {
		t.Fatal(err)
	}
	if status, err := gateway.Withdraw(ctx, swap().ProposalID, 2); err != nil || status != Stored {
		t.Fatalf("%s (%v)", status, err)
	}
	if held := fake.held(swap().ProposalID); held.GetRevision() != 2 {
		t.Fatalf("the gateway holds revision %d", held.GetRevision())
	}
}

// Every refusal the gateway can answer with, and whether retrying the identical document could
// ever answer differently. The grouping is the gateway's own
// (feed-gateway/internal/gateway/errors.go), read from this side — and it is the whole of the retry
// policy, so each one is named rather than inferred.
func TestEveryRefusalIsClassified(t *testing.T) {
	for _, one := range []struct {
		problem   gatewayv1.GatewayProblem
		code      connect.Code
		permanent bool
		why       string
	}{
		{gatewayv1.GatewayProblem_GATEWAY_PROBLEM_UNAUTHENTICATED, connect.CodeUnauthenticated,
			true, "the credential is not a credential for this server; an operator has to change it"},
		{gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_SERVER, connect.CodePermissionDenied,
			true, "the document names a server this credential has no claim on"},
		{gatewayv1.GatewayProblem_GATEWAY_PROBLEM_FOREIGN_CHANNEL, connect.CodePermissionDenied,
			true, "the document names another publisher's channel"},
		{gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_VALUE, connect.CodeInvalidArgument,
			true, "the document is malformed, and will be next time too"},
		{gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NOT_A_FEED, connect.CodeInvalidArgument,
			true, "a direct manifest is never relayed"},
		{gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_GATEWAY, connect.CodeInvalidArgument,
			true, "the manifest names a gateway that is not this one"},
		{gatewayv1.GatewayProblem_GATEWAY_PROBLEM_STALE_REVISION, connect.CodeFailedPrecondition,
			true, "this template's view and the gateway's disagree; something has to resolve it"},
		{gatewayv1.GatewayProblem_GATEWAY_PROBLEM_REVISION_CONFLICT,
			connect.CodeFailedPrecondition, true, "the same revision with different content"},
		{gatewayv1.GatewayProblem_GATEWAY_PROBLEM_CANCELLED, connect.CodeFailedPrecondition,
			true, "a withdrawal is final"},
		{gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_PROPOSALS,
			connect.CodeFailedPrecondition, true, "the channel is full; withdraw something first"},
		{gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_SERVER, connect.CodeNotFound,
			true, "a deployment that needs looking at"},
		{gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_REQUESTS,
			connect.CodeResourceExhausted, false, "a rate limit refills"},
	} {
		t.Run(problemName(one.problem), func(t *testing.T) {
			fake := &fakeGateway{refuse: func(string) error {
				return problem(one.problem, one.code, "field")
			}}
			gateway := serve(t, fake)
			_, err := gateway.Proposal(context.Background(), signals.Proposal(server, swap()))
			var refusal *Refusal
			if !errors.As(err, &refusal) {
				t.Fatalf("expected a refusal, got %v", err)
			}
			if refusal.Problem != problemName(one.problem) {
				t.Fatalf("problem %q, expected %q", refusal.Problem, problemName(one.problem))
			}
			if refusal.Permanent != one.permanent {
				t.Fatalf("permanent %v, expected %v: %s", refusal.Permanent, one.permanent,
					one.why)
			}
		})
	}
}

// A refusal with no detail — a proxy's own 503, an intermediary that knows nothing about this
// contract — still has one word an operator can search for, and it is still retried.
func TestARefusalWithNoDetailIsNamedByItsCode(t *testing.T) {
	fake := &fakeGateway{refuse: func(string) error {
		return connect.NewError(connect.CodeUnavailable, errors.New("no upstream"))
	}}
	gateway := serve(t, fake)
	_, err := gateway.Proposal(context.Background(), signals.Proposal(server, swap()))
	var refusal *Refusal
	if !errors.As(err, &refusal) {
		t.Fatalf("expected a refusal, got %v", err)
	}
	if refusal.Problem != "unavailable" || refusal.Permanent {
		t.Fatalf("%+v", refusal)
	}
}

// A gateway that is not there at all: nothing reached it, so there is nothing about the document to
// conclude and the publication is retried. Connect reports a dial failure as `unavailable`, which
// is already in the group that is retried — the "unreachable" word is for a failure that never
// became a Connect error at all.
func TestAGatewayThatIsNotThereIsRetried(t *testing.T) {
	gateway, err := New(Options{
		URL:        "http://127.0.0.1:1",
		Credential: credential,
		Timeout:    time.Second,
	})
	if err != nil {
		t.Fatal(err)
	}
	_, err = gateway.Proposal(context.Background(), signals.Proposal(server, swap()))
	var refusal *Refusal
	if !errors.As(err, &refusal) {
		t.Fatalf("expected a refusal, got %v", err)
	}
	if refusal.Problem != "unavailable" || refusal.Permanent {
		t.Fatalf("%+v", refusal)
	}
	// Nothing about the credential is in the message, because a template's log is read by people
	// who are not necessarily its operator.
	if strings.Contains(refusal.Detail, credential) {
		t.Fatal("the message quotes the credential")
	}
}

// A publication is not redirected. A redirect is an instruction from the network about where this
// publisher's documents go, and a template that followed one would publish somewhere its operator
// did not configure — carrying the credential with it.
func TestAPublicationIsNotRedirected(t *testing.T) {
	elsewhere := httptest.NewServer(http.HandlerFunc(
		func(writer http.ResponseWriter, request *http.Request) {
			http.Redirect(writer, request, "https://somewhere.example/"+request.URL.Path,
				http.StatusTemporaryRedirect)
		}))
	t.Cleanup(elsewhere.Close)

	gateway, err := New(Options{
		URL:        elsewhere.URL,
		Credential: credential,
		Timeout:    5 * time.Second,
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := gateway.Proposal(context.Background(),
		signals.Proposal(server, swap())); err == nil {
		t.Fatal("a redirected publication was followed")
	}
}

// A gateway needs an address and a credential. Neither has a default: a template with no
// credential cannot publish, and one with no address would have to have an address compiled into
// it.
func TestAGatewayNeedsAnAddressAndACredential(t *testing.T) {
	if _, err := New(Options{Credential: credential}); err == nil {
		t.Fatal("a gateway with no address was built")
	}
	if _, err := New(Options{URL: "https://feeds.example.com"}); err == nil {
		t.Fatal("a gateway with no credential was built")
	}
}

// The backoff doubles from a second to a minute and stops there: what is being waited for is a
// service coming back, and a minute is short enough that a signal published during an outage is
// broadcast promptly afterwards.
func TestTheBackoffDoublesAndIsCapped(t *testing.T) {
	for attempts, expected := range map[int]time.Duration{
		0:  time.Second,
		1:  2 * time.Second,
		2:  4 * time.Second,
		3:  8 * time.Second,
		4:  16 * time.Second,
		5:  32 * time.Second,
		6:  time.Minute,
		7:  time.Minute,
		50: time.Minute,
	} {
		if got := Backoff(attempts); got != expected {
			t.Fatalf("%d attempts waited %s, expected %s", attempts, got, expected)
		}
	}
	if Backoff(-1) != time.Second {
		t.Fatalf("a negative attempt count waited %s", Backoff(-1))
	}
}

func problemName(problem gatewayv1.GatewayProblem) string {
	return strings.ToLower(strings.TrimPrefix(problem.String(), "GATEWAY_PROBLEM_"))
}
