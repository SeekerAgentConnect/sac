package stream

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"time"
)

// Grant mints the credential a listener connects with, and says how long it is good for.
//
// # Why the gateway mints anything at all
//
// The transport the phone listens on is unidirectional: after it connects it can send nothing, so
// it cannot subscribe to a channel, ask for history, or refresh a token. Its channels are fixed by
// the credential it connected with — the broker takes them from the connection token and
// subscribes the connection itself. A client naming channels in its connect request is answered
// with a connection and no subscriptions at all, which is a thing worth knowing the shape of
// before designing around it (this was probed against the pinned release, not assumed).
//
// So the grant has to come from somewhere, and the gateway is the only place it can come from: it
// is the only party that knows which channels exist and holds the key the broker verifies.
//
// # What the grant says
//
// Which channels, and when it stops being valid. That is the whole of it. The subject is empty —
// an anonymous connection, which is what a broadcast's listener is — and there is no device
// identifier, no address, no session, and nothing derived from any of them. The gateway keeps no
// record of having minted it: the grant is in the answer, and there is nothing to keep, which is
// why a gateway that has been serving a stream for a year still knows nothing about its
// subscribers (docs/security.md). A test pins the claim set, so adding one is a deliberate act.
//
// It signs the broker's own channel names, as [Broker.StreamChannel] spells them, because the
// caller has already decided which channels it is granting: the gateway checked them against its
// publishers and answered with both names (internal/gateway/ticket.go). Mapping them here as well
// would be the same rule in two places, and one of them would eventually be the wrong one.
//
// A short lifetime is not a secrecy measure — the channels it admits a listener to are public
// broadcasts, and the ticket is not the thing protecting them. It is a bound on a grant: the
// broker closes the connection when it expires (`3005 connection expired`), the listener asks for
// another, and a ticket that leaked stops mattering by itself.
func (b *Broker) Grant(streamChannels []string, at time.Time) (string, time.Duration, error) {
	return b.GrantWithin(streamChannels, at, 0)
}

// GrantWithin is Grant for a ticket that must not outlive something else — a restricted channel's
// grant (SEE-156). A positive bound shorter than the broker's lifetime is the ticket's lifetime; the
// broker then closes the connection at that moment (`3005`), and the listener's renewal is checked
// against the grant again.
func (b *Broker) GrantWithin(streamChannels []string, at time.Time, most time.Duration) (string, time.Duration, error) {
	lifetime := b.lifetime
	if most > 0 && most < lifetime {
		// Whole seconds, because that is all a JWT expiry says; never zero, because a ticket that
		// has already expired is not one.
		lifetime = max(most.Truncate(time.Second), time.Second)
	}
	if len(streamChannels) == 0 {
		return "", 0, fmt.Errorf("stream: a grant needs at least one channel")
	}
	if len(streamChannels) > b.channels {
		return "", 0, fmt.Errorf("stream: %d channels, at most %d",
			len(streamChannels), b.channels)
	}
	token, err := sign(b.tokenKey, ticket{
		Subject:  "",
		Expires:  at.Add(lifetime).Unix(),
		Channels: streamChannels,
	})
	if err != nil {
		return "", 0, err
	}
	return token, lifetime, nil
}

// ticket is the whole claim set, and its field order is the order it is signed in.
type ticket struct {
	// Anonymous, always. The broker reads an empty subject as "no user", which is the only honest
	// thing to say about someone reading a broadcast.
	Subject string `json:"sub"`
	// Seconds since the epoch, which is the only format a JWT expiry has.
	Expires int64 `json:"exp"`
	// The channels the broker will subscribe this connection to.
	Channels []string `json:"channels"`
}

// sign writes a JWS compact serialization with HS256 by hand.
//
// A JWT library would be a dependency for two base64 segments, a JSON object and an HMAC, and it
// would bring a verifier this service has no use for: the gateway only ever signs, and the broker
// is the only thing that reads what it signed. Thirty lines of standard library keep the
// dependency list at three, and keep the whole of the token readable in one place — which matters
// more here than usual, because what must never be in it is the point.
func sign(key []byte, claims ticket) (string, error) {
	body, err := json.Marshal(claims)
	if err != nil {
		return "", fmt.Errorf("stream: encode ticket: %w", err)
	}
	// The only algorithm this signs with, so the header is a constant rather than a decision.
	const header = `{"alg":"HS256","typ":"JWT"}`
	signing := segment([]byte(header)) + "." + segment(body)
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(signing))
	return signing + "." + segment(mac.Sum(nil)), nil
}

// segment is base64url without padding, which is what a JWS segment is.
func segment(raw []byte) string {
	return base64.RawURLEncoding.EncodeToString(raw)
}
