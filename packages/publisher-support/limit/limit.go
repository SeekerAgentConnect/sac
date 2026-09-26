// Package limit is a small in-memory sliding window. It is used by the publisher API's optional
// global create cap and by the CopyTrading trader UI (SEE-126). A limit of zero is unlimited, which
// is the default so existing tests and deployments keep their previous behaviour.
package limit

import (
	"sync"
	"time"
)

// Limiter counts events per key inside a sliding window.
type Limiter struct {
	mu     sync.Mutex
	hits   map[string][]time.Time
	max    int
	window time.Duration
	now    func() time.Time
}

// New returns a limiter. max <= 0 means every call is allowed.
func New(max int, window time.Duration, now func() time.Time) *Limiter {
	if now == nil {
		now = time.Now
	}
	return &Limiter{hits: map[string][]time.Time{}, max: max, window: window, now: now}
}

// Allow records one event for key when the window still has room. A zero max always allows and
// records nothing.
func (l *Limiter) Allow(key string) bool {
	if l == nil || l.max <= 0 {
		return true
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	held := l.prune(key, now)
	if len(held) >= l.max {
		l.hits[key] = held
		return false
	}
	l.hits[key] = append(held, now)
	return true
}

// Undo forgets the most recent event for key, so a reservation that did not become a new create
// does not consume the cap.
func (l *Limiter) Undo(key string) {
	if l == nil || l.max <= 0 {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	held := l.hits[key]
	if len(held) == 0 {
		return
	}
	l.hits[key] = held[:len(held)-1]
}

func (l *Limiter) prune(key string, now time.Time) []time.Time {
	cutoff := now.Add(-l.window)
	held := l.hits[key]
	kept := held[:0]
	for _, at := range held {
		if at.After(cutoff) {
			kept = append(kept, at)
		}
	}
	return kept
}
