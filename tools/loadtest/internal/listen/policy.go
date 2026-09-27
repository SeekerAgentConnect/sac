package listen

import (
	"math/rand/v2"
	"time"
)

// The decisions a listener makes, ported from the phone's own (SEE-99).
//
// `apps/android/.../feeds/FeedRecovery.kt` is the original, and this is deliberately a translation of it
// rather than a second opinion: the same disconnect-code ranges, the same order of tests for
// whether continuity was proven, and the same backoff. A harness with a reconnect policy of its own
// would measure a client nobody ships — its recovery numbers would be about the harness, and its
// retry storm would be a storm this deployment never sees.
//
// Anything that changes here has to change there too, and the numbers below are pinned by
// policy_test.go against the Kotlin's own constants so the drift is a failure rather than a
// surprise.

// After is what to do when a stream ends.
type After int

const (
	// Open another one with the ticket in hand.
	Reconnect After = iota
	// Open another one, but mint a ticket first: the broker distinguished "come back" from "come
	// back with a fresh grant", and reusing the old one would only be refused again.
	Reticket
	// Do not come back as we are. Something about this listener is wrong rather than late.
	Stop
)

// The codes the broker documents, and the two facts about them worth writing down: the `reconnect`
// field beside them is not usable (a graceful shutdown arrives with `reconnect: false`, and a node
// restarting is the most ordinary reason to come straight back), and 3500–3505 is the terminal
// range.
const (
	ConnectionExpired = 3005
	StateInvalidated  = 3014
	InvalidToken      = 3500
	// A graceful drain, confirmed by hand against the pinned release: `3001 shutdown`, with
	// `reconnect: false` beside it (docs/testing/stage-7-1.md).
	Shutdown = 3001

	terminalFirst = 3500
	terminalLast  = 3505
)

// AfterClose reads a disconnect code the way the phone reads one.
func AfterClose(code uint32) After {
	switch {
	case code == ConnectionExpired || code == StateInvalidated || code == InvalidToken:
		return Reticket
	case code >= terminalFirst && code <= terminalLast:
		return Stop
	default:
		return Reconnect
	}
}

// Why a channel's continuity could not be proven, so the listener has to read the authoritative
// snapshot instead. Recovered is the absence of all of these.
type Why int

const (
	// The broker replayed everything this listener missed: nothing is needed from the gateway.
	Recovered Why = iota
	// The stream opened without this channel, so nothing will ever arrive on it.
	NotSubscribed
	// Nothing was held for the channel: a feed just added, or a listener that forgot.
	NothingHeld
	// The broker is not keeping history for it at all.
	NotRecoverable
	// The broker's history was replaced, so a position in the old one means nothing.
	EpochChanged
	// Further behind than the broker keeps, or than it will replay in one go.
	TooFarBehind
)

// Names for the report, which is read by people rather than parsed.
var whyNames = map[Why]string{
	Recovered:      "recovered",
	NotSubscribed:  "not subscribed",
	NothingHeld:    "nothing held",
	NotRecoverable: "not recoverable",
	EpochChanged:   "epoch changed",
	TooFarBehind:   "too far behind",
}

func (w Why) String() string {
	if name, known := whyNames[w]; known {
		return name
	}
	return "unknown"
}

// Continuity says what the connect answer means for one channel.
//
// The order matters, and it is the phone's: a missing subscription first, because a channel that
// was not subscribed will never deliver anything; then whether anything was held at all; then
// recoverability; then a changed epoch, which invalidates the cursor itself; then the broker's own
// verdict.
//
// The answer's offset is deliberately not compared with the held one. A successful recovery echoes
// back the *requested* offset, so a listener reading its new position from that field would go
// backwards.
func Continuity(held *Cursor, opened *Subscription) Why {
	switch {
	case opened == nil:
		return NotSubscribed
	case held == nil:
		return NothingHeld
	case !opened.Recoverable:
		return NotRecoverable
	case opened.Epoch != held.Epoch:
		return EpochChanged
	case opened.Recovered:
		return Recovered
	default:
		return TooFarBehind
	}
}

// The backoff, which is the phone's: doubling from a second to half a minute.
const (
	BaseBackoff = time.Second
	MostBackoff = 30 * time.Second
	// (attempt - 1) is clamped to this, so the ceiling is reached rather than overflowed.
	mostExponent = 5
)

// Backoff is how long to wait before opening another stream.
//
// Jitter is injected so a test can make it the identity, and the production shape can spread the
// herd: a gateway that is down is down for every phone subscribed to it, and they must not come
// back in step. That matters more here than on one phone — the `reconnect` scenario cuts every
// listener at once, and what it measures is whether this function is what bounds the storm.
func Backoff(attempt int, jitter func(time.Duration) time.Duration) time.Duration {
	exponent := min(max(attempt-1, 0), mostExponent)
	delay := min(BaseBackoff<<exponent, MostBackoff)
	return min(max(jitter(delay), 0), MostBackoff)
}

// Spread is the jitter a run uses. It is the phone's own — `ForegroundFeedManager`'s default is
// `it * Random.nextDouble(0.75, 1.25)`, and this is the same multiplier written the other way round
// — so a thousand listeners cut in the same millisecond do not come back in the same one.
func Spread(delay time.Duration) time.Duration {
	return time.Duration(float64(delay) * (0.75 + rand.Float64()*0.5))
}
