package publish

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/gateway"
)

// Presence keeps the gateway told that this publisher's server is running (SEE-150).
//
// It exists because of an asymmetry the phone cannot resolve on its own. A phone reads a feed from
// the gateway, and the gateway keeps serving what a publisher last published after that publisher's
// process is gone — so reaching the gateway is evidence about the gateway and about nothing else.
// The gateway never dials a publisher to find out, and it must not: a publisher's address is the
// publisher's business, and a gateway that connected to one would be a gateway a publisher could
// point at somebody else.
//
// So presence is pushed, and this is the loop that pushes it. Every authenticated publisher call is
// already a check-in, which means a busy publisher needs nothing from this type; what this covers
// is the quiet one, which is most publishers most of the time.
//
// It publishes nothing, carries nothing, and is not part of the publication path: a check-in that
// fails is logged and tried again, and it never touches the store, a revision, or a document.
type Presence struct {
	gateway *gateway.Gateway
	log     *slog.Logger
	// Injected so a test does not wait a real interval. It returns false when ctx ended first.
	sleep   func(ctx context.Context, d time.Duration) bool
	backoff func(attempts int) time.Duration
}

// PresencePlan is what a [Presence] needs.
type PresencePlan struct {
	Gateway *gateway.Gateway
	Log     *slog.Logger
	// Sleep is injected so a test does not wait. Nil means a real timer.
	Sleep func(ctx context.Context, d time.Duration) bool
	// Backoff is the delay after a check-in that did not reach the gateway. Nil means
	// [gateway.Backoff].
	Backoff func(attempts int) time.Duration
}

// NewPresence builds one.
func NewPresence(plan PresencePlan) *Presence {
	sleep := plan.Sleep
	if sleep == nil {
		sleep = waitFor
	}
	backoff := plan.Backoff
	if backoff == nil {
		backoff = gateway.Backoff
	}
	return &Presence{
		gateway: plan.Gateway,
		log:     plan.Log,
		sleep:   sleep,
		backoff: backoff,
	}
}

// Run checks in until ctx is done, or until the gateway says something that asking again cannot
// change.
//
// The first check-in happens straight away rather than after an interval: a publisher that has just
// started is running, and an owner looking at the app while it starts should not be shown a feed as
// offline for the length of one interval because nothing has been published yet.
//
// Two answers stop the loop for good, and both are logged once rather than every interval:
//
//   - **unimplemented** — a gateway older than SEE-150, which has no such RPC. Its phones show the
//     feed as unknown, which is what they do for anything they cannot read, and asking repeatedly
//     would fill an operator's log with a line about a gateway that is working as designed.
//   - any other **permanent** refusal — the credential is not a credential for this server, which
//     an operator has to fix. The drainer says so about publishing, in the same words; a second
//     voice on a timer adds nothing.
//
// Everything else is the gateway, a proxy or the network, and is retried on [gateway.Backoff].
func (p *Presence) Run(ctx context.Context) {
	attempts := 0
	for {
		interval, err := p.gateway.Heartbeat(ctx)
		switch {
		case err == nil:
			attempts = 0
		case errors.Is(err, context.Canceled), ctx.Err() != nil:
			return
		default:
			var refusal *gateway.Refusal
			if errors.As(err, &refusal) && refusal.Problem == "unimplemented" {
				p.log.Info("this gateway does not answer check-ins, so phones cannot be shown " +
					"whether this feed is online; publishing is unaffected")
				return
			}
			if errors.As(err, &refusal) && refusal.Permanent {
				p.log.Error("the gateway refused this publisher's check-in",
					gateway.Describe(refusal)...)
				return
			}
			attempts++
			p.log.Warn("a check-in did not reach the gateway", "error", err,
				"attempt", attempts)
			interval = p.backoff(attempts)
		}
		if !p.sleep(ctx, interval) {
			return
		}
	}
}

// waitFor sleeps, and reports whether it got to the end rather than being cancelled.
func waitFor(ctx context.Context, d time.Duration) bool {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}
