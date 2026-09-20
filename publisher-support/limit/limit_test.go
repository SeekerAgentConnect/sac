package limit

import (
	"testing"
	"time"
)

func TestAZeroMaxIsUnlimited(t *testing.T) {
	now := time.Date(2026, 9, 19, 12, 0, 0, 0, time.UTC)
	limiter := New(0, time.Hour, func() time.Time { return now })
	for i := 0; i < 50; i++ {
		if !limiter.Allow("create") {
			t.Fatalf("unlimited limiter refused event %d", i)
		}
	}
}

func TestANilLimiterIsUnlimited(t *testing.T) {
	var limiter *Limiter
	if !limiter.Allow("create") {
		t.Fatal("a nil limiter must not refuse")
	}
	limiter.Undo("create")
}

func TestTheWindowRefusesTheNextEventAndUndoFreesASlot(t *testing.T) {
	now := time.Date(2026, 9, 19, 12, 0, 0, 0, time.UTC)
	limiter := New(2, time.Hour, func() time.Time { return now })
	if !limiter.Allow("judge") || !limiter.Allow("judge") {
		t.Fatal("the first two events must be allowed")
	}
	if limiter.Allow("judge") {
		t.Fatal("the third event in the window must be refused")
	}
	limiter.Undo("judge")
	if !limiter.Allow("judge") {
		t.Fatal("undoing a reservation must free a slot")
	}
}

func TestEventsFallOutOfTheWindow(t *testing.T) {
	now := time.Date(2026, 9, 19, 12, 0, 0, 0, time.UTC)
	limiter := New(1, time.Hour, func() time.Time { return now })
	if !limiter.Allow("ip") {
		t.Fatal("first event refused")
	}
	now = now.Add(time.Hour + time.Second)
	if !limiter.Allow("ip") {
		t.Fatal("an event outside the window must be allowed")
	}
}

func TestKeysAreIndependent(t *testing.T) {
	now := time.Date(2026, 9, 19, 12, 0, 0, 0, time.UTC)
	limiter := New(1, time.Hour, func() time.Time { return now })
	if !limiter.Allow("a") {
		t.Fatal("a refused")
	}
	if !limiter.Allow("b") {
		t.Fatal("b must not share a's slot")
	}
}
