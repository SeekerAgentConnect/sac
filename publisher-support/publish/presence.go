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
	sleep       func(ctx context.Context, d time.Duration) bool
	backoff     func(attempts int) time.Duration
	unsupported func(attempts int) time.Duration
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
	// Unsupported is the delay after a gateway said it has no such RPC. Nil means
	// [UnsupportedBackoff].
	Unsupported func(attempts int) time.Duration
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
	unsupported := plan.Unsupported
	if unsupported == nil {
		unsupported = UnsupportedBackoff
	}
	return &Presence{
		gateway:     plan.Gateway,
		log:         plan.Log,
		sleep:       sleep,
		backoff:     backoff,
		unsupported: unsupported,
	}
}

// Run checks in until ctx is done, or until the gateway says something that asking again cannot
// change.
//
// The first check-in happens straight away rather than after an interval: a publisher that has just
// started is running, and an owner looking at the app while it starts should not be shown a feed as
// offline for the length of one interval because nothing has been published yet.
//
// Three kinds of answer, and only one of them ends the loop:
//
//   - **unimplemented** — a gateway older than SEE-150, which has no such RPC. Its phones show the
//     feed as unknown, which is what they do for anything they cannot read, so nothing is wrong
//     and nothing is urgent. It is retried anyway, on [UnsupportedBackoff], because the gateway is
//     the half of the pair an operator upgrades first: a publisher that gave up on the old one
//     would go on being shown offline for the whole life of its process, and the operator's only
//     remedy would be restarting every publisher they run (SEE-155). The wait is long and the line
//     is written once, so an old gateway costs a call an hour and one log entry, not a flood.
//   - any other **permanent** refusal — the credential is not a credential for this server, which
//     an operator has to fix. The drainer says so about publishing, in the same words; a second
//     voice on a timer adds nothing, and retrying a rejected credential is how an operator ends up
//     locked out. This still stops for good.
//   - everything else — the gateway, a proxy or the network, retried on [gateway.Backoff].
func (p *Presence) Run(ctx context.Context) {
	attempts := 0
	unsupported := 0
	for {
		interval, err := p.gateway.Heartbeat(ctx)
		switch {
		case err == nil:
			// Saying so only after an episode, rather than on every successful check-in, which
			// is the same restraint the warning is written with.
			if unsupported > 0 {
				p.log.Info("this gateway answers check-ins again, so phones can be shown " +
					"whether this feed is online")
			}
			attempts = 0
			unsupported = 0
		case errors.Is(err, context.Canceled), ctx.Err() != nil:
			return
		default:
			var refusal *gateway.Refusal
			switch {
			case errors.As(err, &refusal) && refusal.Problem == "unimplemented":
				unsupported++
				if unsupported == 1 {
					p.log.Info("this gateway does not answer check-ins, so phones cannot be " +
						"shown whether this feed is online; publishing is unaffected, and " +
						"this publisher will keep asking in case the gateway is upgraded")
				}
				attempts = 0
				interval = p.unsupported(unsupported)
			case errors.As(err, &refusal) && refusal.Permanent:
				p.log.Error("the gateway refused this publisher's check-in",
					gateway.Describe(refusal)...)
				return
			default:
				attempts++
				unsupported = 0
				p.log.Warn("a check-in did not reach the gateway", "error", err,
					"attempt", attempts)
				interval = p.backoff(attempts)
			}
		}
		if !p.sleep(ctx, interval) {
			return
		}
	}
}

// UnsupportedBackoff is the delay before asking a gateway that has no Heartbeat RPC again: doubling
// from a minute to an hour.
//
// It is separate from [gateway.Backoff], and much slower, because it waits for a different thing. A
// backoff for an unreachable gateway waits for a restart, measured in seconds, and a publication is
// held up meanwhile. Nothing is held up here — presence is not on the publication path — and what
// is being waited for is somebody deploying a newer gateway, which is measured in days. An hour is
// short enough that an upgraded deployment starts reporting its quiet publishers as online within
// one window, and long enough that a deployment that is never upgraded costs twenty-four calls a
// day and no log lines at all after the first.
func UnsupportedBackoff(attempts int) time.Duration {
	const base = time.Minute
	const most = time.Hour
	delay := base << min(max(attempts-1, 0), 6)
	if delay > most {
		delay = most
	}
	return delay
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
