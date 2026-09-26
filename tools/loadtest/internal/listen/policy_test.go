package listen

import (
	"testing"
	"time"
)

// The ported policy, against the cases the Kotlin's own test asserts (SEE-99).
//
// `apps/android/.../feeds/FeedRecoveryTest.kt` is the original, and these are its cases in the same
// order with the same numbers. That is the point of the file: the harness's client has to make the
// app's decisions, and the way to keep a port honest is to port its test as well. A change on
// either side that is not made on both fails here.

func TestAShutdownIsSomethingToComeBackFromAndALimitIsNot(t *testing.T) {
	for _, code := range []uint32{3001, 3004, 3013} {
		if after := AfterClose(code); after != Reconnect {
			t.Errorf("%d: %v, and the phone comes straight back", code, after)
		}
	}
	for _, code := range []uint32{3005, 3014, 3500} {
		if after := AfterClose(code); after != Reticket {
			t.Errorf("%d: %v, and the phone needs a fresh grant", code, after)
		}
	}
	for _, code := range []uint32{3501, 3502, 3504, 3505} {
		if after := AfterClose(code); after != Stop {
			t.Errorf("%d: %v, and the phone does not come back", code, after)
		}
	}
	// A stream that ended without a disconnect push at all: the node went away, which is the most
	// ordinary reason to reconnect.
	if after := AfterClose(0); after != Reconnect {
		t.Errorf("no code: %v", after)
	}
}

func TestTheBrokerHavingReplayedEverythingIsTheOnlyProofOfContinuity(t *testing.T) {
	held := &Cursor{Epoch: "e1", Offset: 7}
	recovered := &Subscription{Epoch: "e1", Offset: 7, Recoverable: true, Recovered: true}
	if why := Continuity(held, recovered); why != Recovered {
		t.Fatalf("a proven recovery came back as %v", why)
	}
}

func TestEveryOtherAnswerSendsTheListenerToTheSnapshot(t *testing.T) {
	held := &Cursor{Epoch: "e1", Offset: 7}
	for _, one := range []struct {
		what     string
		held     *Cursor
		opened   *Subscription
		expected Why
	}{
		{
			"the stream opened without the channel", held, nil, NotSubscribed,
		},
		{
			"nothing was held for it", nil,
			&Subscription{Epoch: "e1", Recoverable: true}, NothingHeld,
		},
		{
			"the broker keeps no history for it", held,
			&Subscription{Epoch: "e1", Offset: 9}, NotRecoverable,
		},
		{
			"the history was replaced", held,
			&Subscription{Epoch: "e2", Offset: 9, Recoverable: true, Recovered: true},
			EpochChanged,
		},
		{
			"it is further behind than the broker replays", held,
			&Subscription{Epoch: "e1", Offset: 900, Recoverable: true, WasRecovering: true},
			TooFarBehind,
		},
	} {
		if why := Continuity(one.held, one.opened); why != one.expected {
			t.Errorf("%s: %v, and it should be %v", one.what, why, one.expected)
		}
	}
	// The order matters: a missing subscription is answered before anything else, because a
	// channel that was not subscribed will never deliver anything whatever else is true of it.
	if why := Continuity(nil, nil); why != NotSubscribed {
		t.Errorf("nothing held and nothing subscribed: %v", why)
	}
}

func TestBackoffGrowsToACeilingAndIsSpreadOut(t *testing.T) {
	plain := func(delay time.Duration) time.Duration { return delay }
	for _, one := range []struct {
		attempt  int
		expected time.Duration
	}{
		{1, time.Second},
		{2, 2 * time.Second},
		{5, 16 * time.Second},
		{6, MostBackoff},
		{60, MostBackoff},
	} {
		if got := Backoff(one.attempt, plain); got != one.expected {
			t.Errorf("attempt %d waits %s, and the phone waits %s",
				one.attempt, got, one.expected)
		}
	}
	// An attempt count below one is the caller's mistake and must not become a negative shift.
	if got := Backoff(0, plain); got != BaseBackoff {
		t.Errorf("attempt 0 waits %s", got)
	}
	// However it is spread, it stays inside the bound: a listener that waited longer than the
	// ceiling would be a listener that stopped coming back.
	for attempt := 1; attempt <= 60; attempt++ {
		for range 20 {
			got := Backoff(attempt, Spread)
			if got < 0 || got > MostBackoff {
				t.Fatalf("attempt %d waited %s", attempt, got)
			}
		}
	}
}

func TestSpreadIsAQuarterEitherWay(t *testing.T) {
	// The phone's own jitter is `it * Random.nextDouble(0.75, 1.25)`, so a hundred draws must all
	// land in that band and must not all land on the same number.
	const delay = 4 * time.Second
	seen := map[time.Duration]bool{}
	for range 100 {
		got := Spread(delay)
		if got < delay*3/4 || got > delay*5/4 {
			t.Fatalf("%s is outside a quarter of %s", got, delay)
		}
		seen[got] = true
	}
	if len(seen) < 10 {
		t.Fatalf("a hundred draws produced %d different waits, which is not a spread", len(seen))
	}
}

func TestWhyReadsAsSomethingAPersonCanReport(t *testing.T) {
	for why, expected := range map[Why]string{
		Recovered:      "recovered",
		NotSubscribed:  "not subscribed",
		NothingHeld:    "nothing held",
		NotRecoverable: "not recoverable",
		EpochChanged:   "epoch changed",
		TooFarBehind:   "too far behind",
	} {
		if got := why.String(); got != expected {
			t.Errorf("%d reads as %q", why, got)
		}
	}
	if got := Why(99).String(); got != "unknown" {
		t.Errorf("an unknown reason reads as %q", got)
	}
}
