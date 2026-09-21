// What the direct relay sends to one device, and what it refuses to (SEE-144).
//
// The same fake push endpoint as the topic relay's tests, for the same reason: what matters is the
// message. A wake-up that grew a request ID, a title, or a phone's own target in a data field
// would still be delivered, still be accepted by Firebase, and still look right in a log — and
// only a test that reads the body would notice.
package relay

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

const deviceTarget = "cX9nZXQtYS1yZWdpc3RyYXRpb24"

var relayed = time.Date(2026, 9, 21, 12, 0, 0, 0, time.UTC)

// The invalidation is the sidecar's own message from a different sender.
//
// A phone cannot tell whether a wake-up came from a server's own Firebase project or through this
// gateway, and it must not have to: it matches the whole data map and ignores anything else, so if
// these two constants drifted from the sidecar's the relay would deliver messages every phone
// silently discards. The cross-language pin is in the phone's own tests; this one holds the wire.
func TestTheInvalidationIsTwoConstantsAndATarget(t *testing.T) {
	push := pushEndpoint(t)
	token := tokenEndpoint(t)
	at := &clock{at: relayed}
	device := relaying(t, push, token, at).Device()

	if outcome, err := device.Send(context.Background(), deviceTarget, true); err != nil ||
		outcome != Delivered {
		t.Fatalf("the invalidation was not delivered: %v, %v", outcome, err)
	}
	sent := push.all()
	if len(sent) != 1 {
		t.Fatalf("%d message(s) were sent", len(sent))
	}
	one := sent[0]
	if one.path != "/v1/projects/seeker-broadcast-test/messages:send" {
		t.Fatalf("the invalidation went to %s", one.path)
	}
	if one.bearer != "Bearer "+token.token {
		t.Fatalf("the invalidation was sent with %q", one.bearer)
	}

	// The target field is `fid`, which is what the phone's registration actually is and what the
	// sidecar already sends to. firebase-admin treats fid, token, topic and condition as four
	// alternative target fields of the same v1 message and passes whichever is set through
	// unchanged, so the same opaque string means the same thing from either sender — and putting
	// it in `token` would be addressing a different kind of thing with the same characters.
	if one.Extra["fid"] != deviceTarget {
		t.Fatalf("the target is not in fid: %v", one.Extra)
	}
	for _, forbidden := range []string{"token", "topic", "condition", "notification"} {
		if _, present := one.Extra[forbidden]; present {
			t.Fatalf("the invalidation carries %q: %s", forbidden, one.raw)
		}
	}

	// The whole payload, matched as a whole the way the phone matches it.
	expected := map[string]string{"kind": RequestKind, "version": RequestVersion}
	if len(one.Data) != len(expected) {
		t.Fatalf("the invalidation carries %v", one.Data)
	}
	for key, value := range expected {
		if one.Data[key] != value {
			t.Fatalf("the invalidation carries %v", one.Data)
		}
	}
	if one.Andro.CollapseKey != RequestCollapseKey || one.Andro.TTL != "300s" {
		t.Fatalf("the delivery options are %+v", one.Andro)
	}
	// Nothing about the server that asked, the request that changed, or the owner, anywhere in the
	// bytes. A read of the raw body is cruder than reading fields and catches what reading fields
	// cannot: a field nobody thought to look for.
	for _, forbidden := range []string{"request_id", "approval", "amount", "wallet", serverA} {
		if strings.Contains(one.raw, forbidden) {
			t.Fatalf("the invalidation body contains %q: %s", forbidden, one.raw)
		}
	}
}

// The hint chooses Android's delivery priority and nothing else. It is never in the payload, so
// the phone cannot read it and does not act on it: what it decides is whether waking a sleeping
// device now is worth it, which is a question about the device rather than about the request.
func TestTheHintChoosesPriorityAndIsNotInThePayload(t *testing.T) {
	push := pushEndpoint(t)
	at := &clock{at: relayed}
	device := relaying(t, push, tokenEndpoint(t), at).Device()
	ctx := context.Background()

	if _, err := device.Send(ctx, deviceTarget, true); err != nil {
		t.Fatal(err)
	}
	if _, err := device.Send(ctx, deviceTarget, false); err != nil {
		t.Fatal(err)
	}
	sent := push.all()
	if sent[0].Andro.Priority != "HIGH" || sent[1].Andro.Priority != "NORMAL" {
		t.Fatalf("priorities are %q and %q", sent[0].Andro.Priority, sent[1].Andro.Priority)
	}
	for _, one := range sent {
		if len(one.Data) != 2 {
			t.Fatalf("the hint reached the payload: %v", one.Data)
		}
	}
}

// Four outcomes rather than an error, because the caller does something different with each — and
// the one distinction that matters is whether this device's target is finished or the attempt was.
func TestEveryAnswerFromThePushEndpointIsClassified(t *testing.T) {
	for _, one := range []struct {
		status  int
		outcome Outcome
		why     string
	}{
		{http.StatusOK, Delivered, "accepted"},
		{http.StatusNotFound, TargetGone, "the registration is gone"},
		{http.StatusBadRequest, TargetGone, "the target is not a target"},
		{http.StatusTooManyRequests, Unavailable, "throttled"},
		{http.StatusServiceUnavailable, Unavailable, "an outage"},
		{http.StatusInternalServerError, Unavailable, "an outage"},
		{http.StatusForbidden, Refused, "this deployment's own credential"},
	} {
		push := pushEndpoint(t, func(p *pusher) { p.status = one.status })
		device := relaying(t, push, tokenEndpoint(t), &clock{at: relayed}).Device()
		outcome, err := device.Send(context.Background(), deviceTarget, false)
		if err != nil {
			t.Fatalf("%d (%s) errored: %v", one.status, one.why, err)
		}
		if outcome != one.outcome {
			t.Fatalf("%d (%s) is outcome %v, expected %v", one.status, one.why, outcome, one.outcome)
		}
	}
}

// One access token, minted once, shared by both kinds of push. Two caches would be two grants for
// one Firebase project and twice the exchanges, and the endpoint is entitled to rate limit that.
func TestBothKindsOfPushShareOneAccessToken(t *testing.T) {
	push := pushEndpoint(t)
	token := tokenEndpoint(t)
	at := &clock{at: relayed}
	hints := relaying(t, push, token, at)
	device := hints.Device()
	ctx := context.Background()

	if err := hints.Dispatch(ctx, delivery(channelA, storage.ProposalNotice)); err != nil {
		t.Fatal(err)
	}
	if _, err := device.Send(ctx, deviceTarget, false); err != nil {
		t.Fatal(err)
	}
	if token.asked() != 1 {
		t.Fatalf("the two senders minted %d tokens", token.asked())
	}
	sent := push.all()
	if len(sent) != 2 || sent[0].bearer != sent[1].bearer {
		t.Fatalf("the two senders used different tokens: %+v", sent)
	}
	// And a token that has expired is replaced once, on whichever path needs it next.
	at.advance(2 * time.Hour)
	if _, err := device.Send(ctx, deviceTarget, false); err != nil {
		t.Fatal(err)
	}
	if token.asked() != 2 {
		t.Fatalf("an expired token was not replaced: %d exchanges", token.asked())
	}
}

// A device sender exists only where a relay does, so there is no way to build one with a
// credential the deployment did not configure and validate at startup.
func TestTheDeviceSenderCarriesTheRelaysOwnCredential(t *testing.T) {
	push := pushEndpoint(t)
	hints := relaying(t, push, tokenEndpoint(t), &clock{at: relayed})
	device := hints.Device()
	if device.project != hints.project || device.endpoint != hints.endpoint {
		t.Fatal("the device sender does not send through this deployment's own project")
	}
	if device.tokens != hints.tokens {
		t.Fatal("the device sender mints its own tokens")
	}
	raw, err := json.Marshal(deviceMessage{Target: deviceTarget})
	if err != nil {
		t.Fatal(err)
	}
	// There is no notification member on the type, which is the enforcement rather than a
	// convention: an external server has no field to put display text in.
	if strings.Contains(string(raw), "notification") {
		t.Fatalf("the device message has somewhere to put a notification: %s", raw)
	}
}
