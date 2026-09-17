// What a listener's grant says, and what it must never say (SEE-91).
//
// The claim set is pinned exactly, in both directions: a claim that disappeared would break every
// listener, and a claim that appeared would be something the gateway started telling the broker
// about the person holding it. The signature is verified here the way the broker verifies it, so
// the thirty lines of JWS in ticket.go are checked against the algorithm rather than against
// themselves.
package stream

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"slices"
	"strings"
	"testing"
	"time"
)

const channelB = "server/9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"

var minted = time.Date(2026, 9, 17, 9, 0, 0, 0, time.UTC)

func grantor(t *testing.T, change ...func(*Options)) *Broker {
	t.Helper()
	options := Options{
		URL:          "http://broker.invalid:8000",
		APIKey:       "key",
		TokenKey:     "the-token-key",
		Lifetime:     30 * time.Minute,
		MostChannels: 2,
	}
	for _, apply := range change {
		apply(&options)
	}
	client, err := New(options)
	if err != nil {
		t.Fatal(err)
	}
	return client
}

// parts splits a grant and checks its signature with the key the broker would use.
func parts(t *testing.T, key, token string) (map[string]any, map[string]any) {
	t.Helper()
	segments := strings.Split(token, ".")
	if len(segments) != 3 {
		t.Fatalf("a grant has %d segments: %q", len(segments), token)
	}
	mac := hmac.New(sha256.New, []byte(key))
	mac.Write([]byte(segments[0] + "." + segments[1]))
	expected := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	if segments[2] != expected {
		t.Fatalf("the signature does not verify with the broker's key")
	}
	read := func(segment string) map[string]any {
		raw, err := base64.RawURLEncoding.DecodeString(segment)
		if err != nil {
			t.Fatalf("a segment is not base64url: %v", err)
		}
		var fields map[string]any
		if err := json.Unmarshal(raw, &fields); err != nil {
			t.Fatalf("a segment is not JSON: %v", err)
		}
		return fields
	}
	return read(segments[0]), read(segments[1])
}

func TestAGrantSaysWhichChannelsAndWhenItStopsAndNothingElse(t *testing.T) {
	broker := grantor(t)

	token, lifetime, err := broker.Grant(
		[]string{broker.StreamChannel(channelA), broker.StreamChannel(channelB)}, minted)
	if err != nil {
		t.Fatal(err)
	}
	if lifetime != 30*time.Minute {
		t.Fatalf("the grant lasts %v", lifetime)
	}

	header, claims := parts(t, "the-token-key", token)
	if header["alg"] != "HS256" || header["typ"] != "JWT" {
		t.Fatalf("the header is %v", header)
	}
	// Exactly three claims. Not "at least": a subject, an expiry, and the channels. Anything else
	// would be something about the listener, and there is nothing about the listener to say — the
	// gateway does not know who asked and keeps no record that anyone did (docs/security.md).
	named := make([]string, 0, len(claims))
	for name := range claims {
		named = append(named, name)
	}
	slices.Sort(named)
	if strings.Join(named, ",") != "channels,exp,sub" {
		t.Fatalf("a grant carries %v", named)
	}
	// Anonymous, always: the broker reads an empty subject as "no user", which is the only honest
	// thing to say about someone reading a broadcast.
	if claims["sub"] != "" {
		t.Fatalf("the grant names a subject: %q", claims["sub"])
	}
	if expires, ok := claims["exp"].(float64); !ok || int64(expires) != minted.Add(30*time.Minute).Unix() {
		t.Fatalf("the grant expires at %v", claims["exp"])
	}
	channels, ok := claims["channels"].([]any)
	if !ok || len(channels) != 2 ||
		channels[0] != "feed:"+channelA || channels[1] != "feed:"+channelB {
		t.Fatalf("the grant is for %v", claims["channels"])
	}
}

// The clock is the caller's, so a gateway under test mints the grant its test expects, and two
// grants a second apart are not the same string by accident.
func TestTheExpiryComesFromTheMomentItWasAskedFor(t *testing.T) {
	broker := grantor(t)

	early, _, err := broker.Grant([]string{"feed:" + channelA}, minted)
	if err != nil {
		t.Fatal(err)
	}
	later, _, err := broker.Grant([]string{"feed:" + channelA}, minted.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if early == later {
		t.Fatal("two moments minted the same grant")
	}
	_, earlyClaims := parts(t, "the-token-key", early)
	_, laterClaims := parts(t, "the-token-key", later)
	if earlyClaims["exp"].(float64) >= laterClaims["exp"].(float64) {
		t.Fatal("the later grant does not expire later")
	}
}

func TestAGrantIsBoundedAndNeverEmpty(t *testing.T) {
	broker := grantor(t)

	if _, _, err := broker.Grant(nil, minted); err == nil {
		t.Fatal("a grant for no channels was minted")
	}
	if _, _, err := broker.Grant([]string{"a", "b", "c"}, minted); err == nil {
		t.Fatal("a grant past the bound was minted")
	}
	if broker.MostChannels() != 2 {
		t.Fatalf("the bound is %d", broker.MostChannels())
	}
}

// A grant only opens what the deployment's own key opens. This is the check that the key is used
// at all: a signature that verified with the wrong key would mean it was not signed with the right
// one either.
func TestAGrantSignedWithAnotherKeyIsNotTheSameGrant(t *testing.T) {
	ours := grantor(t)
	theirs := grantor(t, func(o *Options) { o.TokenKey = "someone-elses-key" })

	mine, _, err := ours.Grant([]string{"feed:" + channelA}, minted)
	if err != nil {
		t.Fatal(err)
	}
	other, _, err := theirs.Grant([]string{"feed:" + channelA}, minted)
	if err != nil {
		t.Fatal(err)
	}
	if mine == other {
		t.Fatal("the key made no difference to the grant")
	}
	segments := strings.Split(mine, ".")
	mac := hmac.New(sha256.New, []byte("someone-elses-key"))
	mac.Write([]byte(segments[0] + "." + segments[1]))
	if segments[2] == base64.RawURLEncoding.EncodeToString(mac.Sum(nil)) {
		t.Fatal("our grant verifies with another deployment's key")
	}
}

// The transport's namespace is a constant on purpose: it has to match the broker's configuration,
// and a mismatch is silent — a publication into an unconfigured namespace is kept without history,
// so recovery would stop working with nothing to see. This is one half of the pin; the phone's
// FeedStreamContractTest is the other, and broadcast/centrifugo.json is what both describe.
func TestTheNamespaceIsTheOneTheBrokerIsConfiguredWith(t *testing.T) {
	if Namespace != "feed" {
		t.Fatalf("the namespace is %q", Namespace)
	}
	broker := grantor(t)
	if got := broker.StreamChannel(channelA); got != "feed:server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d" {
		t.Fatalf("a channel is named %q", got)
	}
}
