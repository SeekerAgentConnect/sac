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
	timing, _ := loopSaying(t, fake, stopAfter)
	return timing
}

// loopSaying is loop, and also what the loop wrote to its log, for the tests that are about how
// often an operator is told something rather than about when the next call goes out.
func loopSaying(
	t *testing.T,
	fake *publishertest.FakeGateway,
	stopAfter int,
) (*waits, *lines) {
	t.Helper()
	timing := &waits{most: stopAfter}
	said := &lines{}
	NewPresence(PresencePlan{
		Gateway: serve(t, fake),
		Log:     slog.New(said),
		Sleep:   timing.sleep,
		Backoff: func(attempts int) time.Duration { return time.Duration(attempts) * time.Second },
		Unsupported: func(attempts int) time.Duration {
			return time.Duration(attempts) * time.Minute
		},
	}).Run(context.Background())
	return timing, said
}

// lines is a slog.Handler that keeps every message, so a test can count them.
type lines struct{ said []string }

func (l *lines) Enabled(context.Context, slog.Level) bool { return true }

func (l *lines) Handle(_ context.Context, record slog.Record) error {
	l.said = append(l.said, record.Message)
	return nil
}

func (l *lines) WithAttrs([]slog.Attr) slog.Handler { return l }

func (l *lines) WithGroup(string) slog.Handler { return l }

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

// A gateway older than SEE-150 has no such RPC, and its phones show the feed as unknown, which is a
// working deployment and not a problem to shout about. So the loop keeps asking — an operator
// upgrades the gateway before the publishers, and a publisher that had given up would go on being
// shown offline for the life of its process (SEE-155) — but slowly, and saying so once.
func TestAGatewayThatDoesNotAnswerCheckInsIsAskedAgainMuchLater(t *testing.T) {
	fake := &publishertest.FakeGateway{Refuse: func(procedure string) error {
		if procedure != "Heartbeat" {
			return nil
		}
		return connect.NewError(connect.CodeUnimplemented, errors.New("no such method"))
	}}

	timing, said := loopSaying(t, fake, 4)

	if fake.Tried("Heartbeat") != 4 {
		t.Fatalf("the loop asked %d time(s)", fake.Tried("Heartbeat"))
	}
	// The unsupported backoff, which grows — not the unreachable one, and not a tight loop.
	expected := []time.Duration{time.Minute, 2 * time.Minute, 3 * time.Minute, 4 * time.Minute}
	for index, waited := range timing.asked {
		if waited != expected[index] {
			t.Fatalf("wait %d was %v, expected %v", index, waited, expected[index])
		}
	}
	// One line for the whole episode, however long it lasts. That is the difference between
	// telling an operator something and filling their log with it.
	if len(said.said) != 1 {
		t.Fatalf("the loop wrote %d line(s): %v", len(said.said), said.said)
	}
}

// The upgrade this exists for: the gateway gains the RPC while the publisher is still running, and
// check-ins resume in the same process, on the interval the gateway now names. Nothing is restarted
// and nothing was published to provoke it.
func TestAnUpgradedGatewayResumesCheckInsWithoutARestart(t *testing.T) {
	old := 2
	fake := &publishertest.FakeGateway{HeartbeatSeconds: 30, Refuse: func(procedure string) error {
		if procedure != "Heartbeat" {
			return nil
		}
		if old > 0 {
			old--
			return connect.NewError(connect.CodeUnimplemented, errors.New("no such method"))
		}
		return nil
	}}

	timing, said := loopSaying(t, fake, 4)

	if fake.Tried("Heartbeat") != 4 {
		t.Fatalf("the loop asked %d time(s)", fake.Tried("Heartbeat"))
	}
	// Two long waits while the gateway was old, then the gateway's own interval, which is how a
	// resumed loop stops being patient.
	expected := []time.Duration{
		time.Minute, 2 * time.Minute, 30 * time.Second, 30 * time.Second,
	}
	for index, waited := range timing.asked {
		if waited != expected[index] {
			t.Fatalf("wait %d was %v, expected %v", index, waited, expected[index])
		}
	}
	// Said once on the way down and once on the way back up, and not again on the check-in after.
	if len(said.said) != 2 {
		t.Fatalf("the loop wrote %d line(s): %v", len(said.said), said.said)
	}
}

// A shutdown during the long unsupported wait ends the loop there, without another call: waiting an
// hour is a policy about gateways, never about how long a process takes to stop.
func TestCancellationDuringTheUnsupportedWaitStopsTheLoop(t *testing.T) {
	fake := &publishertest.FakeGateway{Refuse: func(procedure string) error {
		if procedure != "Heartbeat" {
			return nil
		}
		return connect.NewError(connect.CodeUnimplemented, errors.New("no such method"))
	}}

	timing := loop(t, fake, 1)

	if fake.Tried("Heartbeat") != 1 {
		t.Fatalf("the loop asked %d time(s) after being stopped", fake.Tried("Heartbeat"))
	}
	if len(timing.asked) != 1 {
		t.Fatalf("the loop waited %v", timing.asked)
	}
}

// The unsupported delay doubles from a minute and stops at an hour, so a deployment that is never
// upgraded costs a day's worth of small calls and nothing else.
func TestTheUnsupportedBackoffIsBounded(t *testing.T) {
	if first := UnsupportedBackoff(1); first != time.Minute {
		t.Fatalf("the first unsupported wait was %v", first)
	}
	if second := UnsupportedBackoff(2); second != 2*time.Minute {
		t.Fatalf("the second unsupported wait was %v", second)
	}
	for _, attempts := range []int{7, 8, 100, 10_000} {
		if waited := UnsupportedBackoff(attempts); waited != time.Hour {
			t.Fatalf("unsupported wait %d was %v", attempts, waited)
		}
	}
	// A nonsensical count is still a delay, not a spin.
	if waited := UnsupportedBackoff(0); waited != time.Minute {
		t.Fatalf("unsupported wait 0 was %v", waited)
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
