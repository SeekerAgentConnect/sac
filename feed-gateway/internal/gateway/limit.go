package gateway

import (
	"context"
	"sync"
	"time"

	"connectrpc.com/connect"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
)

// MostKeys is how many callers a limiter remembers. Beyond it the limiter forgets the ones that
// have gone quiet, and if none has, it refuses: a gateway would rather be briefly unavailable to
// new callers than let one of them spend its memory. It is a bound on this process, not a security
// boundary — the network and the reverse proxy in front are that.
const MostKeys = 16384

// Limiter is a token bucket per caller, with the clock injected so its behaviour is a test rather
// than a wait.
//
// One bucket holds `burst` tokens and refills at `rate` a second. A call takes one. That shape is
// chosen over a fixed window because a publisher that republishes a handful of proposals at once is
// ordinary and a publisher that does it every second is not, and a window cannot tell those apart.
type Limiter struct {
	rate  float64
	burst float64
	now   func() time.Time

	mutex   sync.Mutex
	buckets map[string]*bucket
}

type bucket struct {
	tokens float64
	seen   time.Time
}

func NewLimiter(rate float64, burst int, now func() time.Time) *Limiter {
	return &Limiter{
		rate:    rate,
		burst:   float64(burst),
		now:     now,
		buckets: make(map[string]*bucket),
	}
}

// Allow takes a token for key, or says there was none.
func (l *Limiter) Allow(key string) bool {
	l.mutex.Lock()
	defer l.mutex.Unlock()
	at := l.now()
	held, known := l.buckets[key]
	if !known {
		if len(l.buckets) >= MostKeys && !l.forget(at) {
			return false
		}
		held = &bucket{tokens: l.burst, seen: at}
		l.buckets[key] = held
	}
	// Refill for the time that passed, up to the burst.
	if elapsed := at.Sub(held.seen).Seconds(); elapsed > 0 {
		held.tokens = min(l.burst, held.tokens+elapsed*l.rate)
		held.seen = at
	}
	if held.tokens < 1 {
		return false
	}
	held.tokens--
	return true
}

// forget drops the buckets that have refilled completely, which behave exactly as a new one would,
// and says whether it made room. It is called only when the limiter is full.
func (l *Limiter) forget(at time.Time) bool {
	full := l.burst / l.rate
	before := len(l.buckets)
	for key, held := range l.buckets {
		if at.Sub(held.seen).Seconds() >= full {
			delete(l.buckets, key)
		}
	}
	return len(l.buckets) < before
}

// Keys is how many callers the limiter is tracking, for the tests that prove it stays bounded.
func (l *Limiter) Keys() int {
	l.mutex.Lock()
	defer l.mutex.Unlock()
	return len(l.buckets)
}

// Limiting counts a call against whatever `key` says the caller is: the address for a read, the
// publisher for a publication. It is an interceptor rather than a check inside each method so a
// method added later is limited by existing, not by remembering.
func Limiting(limiter *Limiter, key func(ctx context.Context, request connect.AnyRequest) string) connect.UnaryInterceptorFunc {
	return func(next connect.UnaryFunc) connect.UnaryFunc {
		return func(ctx context.Context, request connect.AnyRequest) (connect.AnyResponse, error) {
			if !limiter.Allow(key(ctx, request)) {
				return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_REQUESTS, "")
			}
			return next(ctx, request)
		}
	}
}
