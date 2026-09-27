package gateway

import (
	"encoding/base64"
	"fmt"
	"net/netip"
	"testing"
	"time"

	"connectrpc.com/connect"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
)

const channel = "server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

const otherChannel = "server/9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"

const proposalA = "7c9e6679-7425-40de-944b-e07fc1f90ae7"

func TestAPageTokenSaysWhereAWalkWas(t *testing.T) {
	token := encodeCursor(channel, 42, proposalA)
	snapshot, after, fault := decodeCursor(token, channel)
	if fault != nil {
		t.Fatalf("a token this gateway wrote was refused: %v", fault.Problem)
	}
	if snapshot != 42 || after != proposalA {
		t.Fatalf("the token reads back as %d after %q", snapshot, after)
	}
	// It is opaque, so nothing a client can read out of it should look like an invitation to write
	// one: the point of the check below is that making one up does not work.
	if token == channel {
		t.Fatal("the token is the channel")
	}
}

func TestWhatIsNotAPageToken(t *testing.T) {
	for _, one := range []struct {
		name  string
		token string
	}{
		{"not base64", "page 2 please"},
		{"base64 of something else", "cGFnZSAyIHBsZWFzZQ"},
		{"a token from another version of the format", encodeVersion("2", channel, 1, proposalA)},
		{"too few parts", encodeRaw("1\x1f" + channel)},
		{"a snapshot that is not a number", encodeRaw("1\x1f" + channel + "\x1fsoon\x1f" + proposalA)},
		{"a position that is not an identity", encodeRaw("1\x1f" + channel + "\x1f1\x1flast")},
	} {
		t.Run(one.name, func(t *testing.T) {
			if _, _, fault := decodeCursor(one.token, channel); fault == nil ||
				fault.Problem != gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_CURSOR {
				t.Fatalf("%s was accepted", one.name)
			}
		})
	}
}

// The rule that makes an opaque token safe: it names the channel it belongs to, and a request for
// another channel cannot use it. A token is the only part of a read a client did not have to say
// out loud, so it must not be a way to read something it did not ask about.
func TestAPageTokenBelongsToOneChannel(t *testing.T) {
	token := encodeCursor(otherChannel, 1, proposalA)
	if _, _, fault := decodeCursor(token, channel); fault == nil ||
		fault.Problem != gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_CURSOR {
		t.Fatal("one channel's token was accepted for another")
	}
	// A walk that begins at the start is a token-less request, and an empty position is the start.
	if _, after, fault := decodeCursor(encodeCursor(channel, 1, ""), channel); fault != nil ||
		after != "" {
		t.Fatalf("the start of a walk did not read back: %q %v", after, fault)
	}
}

func TestABucketHoldsItsBurstAndRefillsAtItsRate(t *testing.T) {
	at := time.Date(2026, 9, 17, 9, 0, 0, 0, time.UTC)
	limiter := NewLimiter(1, 3, func() time.Time { return at })

	for i := range 3 {
		if !limiter.Allow("one") {
			t.Fatalf("call %d was inside the burst", i+1)
		}
	}
	if limiter.Allow("one") {
		t.Fatal("the burst did not run out")
	}
	// Another caller has its own bucket: a limit counts against whoever is asking, not against the
	// gateway.
	if !limiter.Allow("another") {
		t.Fatal("one caller's rate stopped another's")
	}

	at = at.Add(2 * time.Second)
	for i := range 2 {
		if !limiter.Allow("one") {
			t.Fatalf("the bucket did not refill by call %d", i+1)
		}
	}
	if limiter.Allow("one") {
		t.Fatal("the bucket refilled by more than the time that passed")
	}
	// And never above the burst, however long it waits.
	at = at.Add(time.Hour)
	for i := range 3 {
		if !limiter.Allow("one") {
			t.Fatalf("call %d after an hour was inside the burst", i+1)
		}
	}
	if limiter.Allow("one") {
		t.Fatal("an hour of waiting bought more than one burst")
	}
}

func TestALimiterStaysBoundedAndForgetsTheQuietOnes(t *testing.T) {
	at := time.Date(2026, 9, 17, 9, 0, 0, 0, time.UTC)
	limiter := NewLimiter(1, 1, func() time.Time { return at })

	// A spray of callers, which is what an address-keyed limiter has to survive.
	for i := range MostKeys {
		limiter.Allow(address(i))
	}
	if limiter.Keys() != MostKeys {
		t.Fatalf("the limiter is tracking %d callers", limiter.Keys())
	}
	// Full, and nothing has gone quiet yet: it refuses rather than growing. That is a deliberate
	// choice — the gateway would rather be briefly unavailable to a new caller than spend its
	// memory on whoever asks for the most of it.
	if limiter.Allow("someone-new") {
		t.Fatal("a full limiter kept growing")
	}
	// A moment later every bucket has refilled, so they are indistinguishable from new ones and
	// can be forgotten.
	at = at.Add(time.Minute)
	if !limiter.Allow("someone-new") {
		t.Fatal("a limiter with nothing but idle callers in it refused a new one")
	}
	if limiter.Keys() > MostKeys {
		t.Fatalf("the limiter grew to %d callers", limiter.Keys())
	}
}

func TestWhoACallIsCountedAgainst(t *testing.T) {
	trusted := []netip.Prefix{netip.MustParsePrefix("172.30.135.0/24")}
	for _, one := range []struct {
		name      string
		peer      string
		forwarded string
		expected  string
	}{
		{"a caller of its own", "203.0.113.7:51234", "", "203.0.113.7"},
		{"a proxy on this machine", "127.0.0.1:51234", "203.0.113.7", "203.0.113.7"},
		{"a chain of proxies", "127.0.0.1:51234", "198.51.100.1, 203.0.113.7", "203.0.113.7"},
		{"a forwarded address with a port", "127.0.0.1:51234", "203.0.113.7:443", "203.0.113.7"},
		{"a proxy on this machine over IPv6", "[::1]:51234", "203.0.113.7", "203.0.113.7"},
		{"an allowed container proxy", "172.30.135.10:51234", "203.0.113.7", "203.0.113.7"},
		{"an unlisted container claiming to be a proxy", "172.30.136.10:51234", "198.51.100.9", "172.30.136.10"},
		// The rule that makes trusting the header safe at all: a remote caller cannot make its own
		// connection appear to come from loopback, so its claim about who it is is ignored.
		{"a remote caller claiming to be a proxy", "203.0.113.7:51234", "198.51.100.9", "203.0.113.7"},
		{"a proxy that forwarded nothing", "127.0.0.1:51234", "", "127.0.0.1"},
		{"a proxy that forwarded an empty header", "127.0.0.1:51234", " ", "127.0.0.1"},
		{"something that is not an address at all", "unix", "", "unix"},
	} {
		t.Run(one.name, func(t *testing.T) {
			if got := caller(one.peer, one.forwarded, trusted); got != one.expected {
				t.Fatalf("%s was counted against %q, expected %q", one.name, got, one.expected)
			}
		})
	}
}

func TestEveryProblemHasACodeAndAName(t *testing.T) {
	// The unspecified problem is never sent, and every other one has to answer both questions a
	// caller asks: what kind of failure is this, and which rule was it.
	values := gatewayv1.GatewayProblem(0).Descriptor().Values()
	for i := range values.Len() {
		problem := gatewayv1.GatewayProblem(values.Get(i).Number())
		if problem == gatewayv1.GatewayProblem_GATEWAY_PROBLEM_UNSPECIFIED {
			continue
		}
		name := problemCode(problem)
		if name == "" || name != lower(name) {
			t.Fatalf("%v reads as %q", problem, name)
		}
		failure := problem2Error(problem)
		if failure.Code().String() == "" {
			t.Fatalf("%v has no code", problem)
		}
	}
}

func lower(value string) string {
	for _, r := range value {
		if r >= 'A' && r <= 'Z' {
			return ""
		}
	}
	return value
}

// The helpers the token tests need to write something that is almost a token.
func encodeVersion(version, channel string, snapshot uint64, after string) string {
	return encodeRaw(version + cursorSeparator + channel + cursorSeparator +
		"1" + cursorSeparator + after)
}

func encodeRaw(text string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(text))
}

func address(i int) string {
	return fmt.Sprintf("198.51.%d.%d", i/256, i%256)
}

// problem2Error is the mapping a caller sees, for the check that every problem has one.
func problem2Error(code gatewayv1.GatewayProblem) *connect.Error {
	return problem(code, "")
}
