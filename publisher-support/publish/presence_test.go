// Telling the gateway that this publisher's server is running (SEE-150).
//
// The loop publishes nothing, so what these tests are about is entirely when it calls, when it stops
// calling, and what it does with an answer it cannot act on. The gateway is the shared fake, served
// through the generated handler, so the Connect protocol, the credential header and the error
// details between the two are real.
package publish

import (
	"context"
	"errors"
	"log/slog"
	"testing"
	"time"

	"connectrpc.com/connect"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/gateway"
	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/publishertest"
)

// waits records every sleep the loop asked for and stops it after a fixed number of them, so a test
// asserts a schedule rather than waiting for one.
type waits struct {
	asked []time.Duration
	most  int
}

func (w *waits) sleep(_ context.Context, d time.Duration) bool {
	w.asked = append(w.asked, d)
	return len(w.asked) < w.most
}

func loop(t *testing.T, fake *publishertest.FakeGateway, stopAfter int) *waits {
	t.Helper()
	timing := &waits{most: stopAfter}
	NewPresence(PresencePlan{
		Gateway: serve(t, fake),
		Log:     slog.New(slog.DiscardHandler),
		Sleep:   timing.sleep,
		Backoff: func(attempts int) time.Duration { return time.Duration(attempts) * time.Second },
	}).Run(context.Background())
	return timing
}

// The first check-in is immediate, and then on the interval the gateway named — not one this
// template chose. A publisher checking in on its own schedule is a publisher shown offline while it
// is running.
func TestTheGatewayNamesTheInterval(t *testing.T) {
	fake := &publishertest.FakeGateway{HeartbeatSeconds: 45}

	timing := loop(t, fake, 3)

	if fake.Tried("Heartbeat") != 3 {
		t.Fatalf("the loop checked in %d time(s)", fake.Tried("Heartbeat"))
	}
	for index, waited := range timing.asked {
		if waited != 45*time.Second {
			t.Fatalf("wait %d was %v", index, waited)
		}
	}
	// And it carried the credential, on every call, in a header and nowhere else.
	for _, presented := range fake.Credentials {
		if presented != "Bearer "+credential {
			t.Fatalf("a check-in presented %q", presented)
		}
	}
}

// A gateway that answers with no interval at all — a future one that dropped the field — still
// leaves a working loop rather than one that checks in as fast as it can.
func TestAnAnswerWithNoIntervalFallsBackToTheDefault(t *testing.T) {
	timing := loop(t, &publishertest.FakeGateway{}, 2)

	for index, waited := range timing.asked {
		if waited != gateway.DefaultHeartbeat {
			t.Fatalf("wait %d was %v", index, waited)
		}
	}
}

// A gateway older than SEE-150 has no such RPC. Its phones show a feed as unknown, which is what they
// do for anything they cannot read, and that is a working deployment — so the loop stops asking
// instead of filling an operator's log with a line about a gateway behaving as designed.
func TestAGatewayThatDoesNotAnswerCheckInsIsNotAskedAgain(t *testing.T) {
	fake := &publishertest.FakeGateway{Refuse: func(procedure string) error {
		if procedure != "Heartbeat" {
			return nil
		}
		return connect.NewError(connect.CodeUnimplemented, errors.New("no such method"))
	}}

	timing := loop(t, fake, 10)

	if fake.Tried("Heartbeat") != 1 {
		t.Fatalf("the loop asked %d time(s)", fake.Tried("Heartbeat"))
	}
	if len(timing.asked) != 0 {
		t.Fatalf("the loop waited %v before giving up", timing.asked)
	}
}

// A credential that is not a credential for this server is something an operator has to change, and
// the drainer already says so about publishing. A second voice on a timer adds nothing.
func TestAPermanentRefusalStopsTheLoop(t *testing.T) {
	fake := &publishertest.FakeGateway{Refuse: func(string) error {
		return connect.NewError(connect.CodeUnauthenticated, errors.New("not a credential"))
	}}

	loop(t, fake, 10)

	if fake.Tried("Heartbeat") != 1 {
		t.Fatalf("the loop asked %d time(s)", fake.Tried("Heartbeat"))
	}
}

// A gateway that is down, restarting or behind a proxy answering 503 will be there later. The loop
// retries on the backoff and keeps going, because the consequence of giving up is a feed shown
// offline for as long as the process runs.
func TestAnUnreachableGatewayIsRetriedOnTheBackoff(t *testing.T) {
	attempts := 0
	fake := &publishertest.FakeGateway{HeartbeatSeconds: 30, Refuse: func(procedure string) error {
		if procedure != "Heartbeat" {
			return nil
		}
		attempts++
		if attempts > 2 {
			return nil
		}
		return connect.NewError(connect.CodeUnavailable, errors.New("restarting"))
	}}

	timing := loop(t, fake, 3)

	if fake.Tried("Heartbeat") != 3 {
		t.Fatalf("the loop asked %d time(s)", fake.Tried("Heartbeat"))
	}
	// The two refusals waited the growing backoff; the one that succeeded waited the gateway's
	// interval, which is how a recovered loop stops being in a hurry.
	expected := []time.Duration{time.Second, 2 * time.Second, 30 * time.Second}
	for index, waited := range timing.asked {
		if waited != expected[index] {
			t.Fatalf("wait %d was %v, expected %v", index, waited, expected[index])
		}
	}
}

// A cancelled context ends the loop without another call and without another wait, so a shutdown
// does not hang on a gateway that is not answering.
func TestAStoppedContextEndsTheLoop(t *testing.T) {
	fake := &publishertest.FakeGateway{HeartbeatSeconds: 30}
	ctx, stop := context.WithCancel(context.Background())
	stop()

	NewPresence(PresencePlan{
		Gateway: serve(t, fake),
		Log:     slog.New(slog.DiscardHandler),
		Sleep:   func(context.Context, time.Duration) bool { t.Fatal("it waited"); return false },
	}).Run(ctx)

	if fake.Tried("Heartbeat") != 0 {
		t.Fatalf("a cancelled loop still called %d time(s)", fake.Tried("Heartbeat"))
	}
}

// The client's own reading of the answer, independent of the loop: the interval comes back as a
// duration, and a check-in carries nothing but the credential.
func TestHeartbeatAnswersWithADuration(t *testing.T) {
	fake := &publishertest.FakeGateway{HeartbeatSeconds: 90}
	client := serve(t, fake)

	interval, err := client.Heartbeat(context.Background())

	if err != nil {
		t.Fatal(err)
	}
	if interval != 90*time.Second {
		t.Fatalf("the interval read as %v", interval)
	}
	// Nothing to say but that the caller is running: the request has no field at all, so there is
	// nowhere for a publisher to name a host this gateway might then try to reach.
	if got := (&gatewayv1.HeartbeatRequest{}).ProtoReflect().Descriptor().Fields().Len(); got != 0 {
		t.Fatalf("a check-in carries %d field(s)", got)
	}
}
