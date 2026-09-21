// The gateway's push relay, end to end (SEE-144).
//
// These are the acceptance criteria as tests: a developer's server that holds no Firebase
// credential wakes a phone that agreed to it, nobody else can, and nothing about the device leaves
// the gateway. They run against the real handlers on the real listeners, with a fake push endpoint
// standing in for Firebase — so what is exercised is the service rather than a rehearsal of it.
package gateway_test

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/credential"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/pushrelay"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/relay"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

const phoneTarget = "fid-of-the-owners-phone"

// A third identity, for the tests that need a feed publisher beside two relay servers.
const thirdServer = "5d4c3b2a-1e0f-4a9b-8c7d-6e5f4a3b2c1d"

// phone is the app's half of the relay: an enrollment and the secret that proves it.
type phone struct {
	installation string
	secret       string
}

// call is one JSON request to whichever listener the relay's half is on.
func (h *harness) call(base, method, path, bearer string, body any) (int, map[string]any) {
	h.t.Helper()
	var reader *bytes.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			h.t.Fatal(err)
		}
		reader = bytes.NewReader(raw)
	} else {
		reader = bytes.NewReader(nil)
	}
	request, err := http.NewRequestWithContext(context.Background(), method, base+path, reader)
	if err != nil {
		h.t.Fatal(err)
	}
	request.Header.Set("Content-Type", "application/json")
	if bearer != "" {
		request.Header.Set("Authorization", "Bearer "+bearer)
	}
	response, err := h.read.Client().Do(request)
	if err != nil {
		h.t.Fatal(err)
	}
	defer func() { _ = response.Body.Close() }()
	answer := map[string]any{}
	_ = json.NewDecoder(response.Body).Decode(&answer)
	return response.StatusCode, answer
}

func (h *harness) phoneCall(method, path, bearer string, body any) (int, map[string]any) {
	h.t.Helper()
	return h.call(h.read.URL, method, pushrelay.Prefix+path, bearer, body)
}

func (h *harness) serverCall(bearer string, body any) (int, map[string]any) {
	h.t.Helper()
	return h.call(h.publish.URL, http.MethodPost, pushrelay.Prefix+"/notify", bearer, body)
}

// enroll is the app registering with the relay it is configured to trust.
func (h *harness) enroll(target string) phone {
	h.t.Helper()
	status, answer := h.phoneCall(http.MethodPost, "/installations", "", map[string]string{
		"version": pushrelay.Version, "target": target,
	})
	if status != http.StatusCreated {
		h.t.Fatalf("enrolling answered %d: %v", status, answer)
	}
	installation, _ := answer["installation"].(string)
	secret, _ := answer["secret"].(string)
	if installation == "" || !credential.Valid(secret) {
		h.t.Fatalf("enrolling answered %v", answer)
	}
	return phone{installation: installation, secret: secret}
}

// bind is the app authorizing one server, for one of the direct connections it holds.
func (h *harness) bind(one phone, serverID, connection string) (string, string) {
	h.t.Helper()
	status, answer := h.phoneCall(http.MethodPost, "/installations/"+one.installation+"/bindings",
		one.secret, map[string]string{
			"version": pushrelay.Version, "server": serverID, "connection": connection,
		})
	if status != http.StatusCreated {
		h.t.Fatalf("binding answered %d: %v", status, answer)
	}
	handle, _ := answer["handle"].(string)
	binding, _ := answer["binding"].(string)
	if handle == "" || binding == "" {
		h.t.Fatalf("binding answered %v", answer)
	}
	return binding, handle
}

// relayCredential registers a server the operator enabled the relay for and issues it one scoped
// credential — exactly what the admin page does, through the same store.
func (h *harness) relayCredential(serverID string) string {
	h.t.Helper()
	secret, hash := credential.New()
	_, err := h.documents.Register(context.Background(), storage.Registration{
		ServerID: serverID, Label: "direct server", Relaying: true,
	}, storage.Relaying, hash, h.now())
	if err != nil {
		h.t.Fatal(err)
	}
	return secret
}

func notify(handle, hint string) map[string]string {
	return map[string]string{"version": pushrelay.Version, "handle": handle, "hint": hint}
}

// The acceptance criterion, in one test. A developer's server holds a scoped relay credential and
// no Firebase credential at all; the owner's phone pairs with it directly and authorizes it; a
// request changes; the phone is woken, with the invalidation the app already knows.
func TestAServerWithNoFirebaseCredentialCanWakeAPhoneThatAuthorizedIt(t *testing.T) {
	one := newGateway(t)
	server := one.relayCredential(publisherA)
	owner := one.enroll(phoneTarget)
	_, handle := one.bind(owner, publisherA, "connection-1")

	status, answer := one.serverCall(server, notify(handle, pushrelay.Created))
	if status != http.StatusAccepted || answer["status"] != "accepted" {
		t.Fatalf("waking the phone answered %d: %v", status, answer)
	}
	sent := one.devices.all()
	if len(sent) != 1 || sent[0].target != phoneTarget || !sent[0].timeSensitive {
		t.Fatalf("the wake-up was %+v", sent)
	}

	// A state change on a request the owner has already seen is not worth waking a sleeping
	// device for, and says so to Android rather than to the app.
	one.at(one.now().Add(time.Minute))
	if status, _ := one.serverCall(server, notify(handle, pushrelay.Updated)); status != http.StatusAccepted {
		t.Fatalf("an update answered %d", status)
	}
	if sent = one.devices.all(); len(sent) != 2 || sent[1].timeSensitive {
		t.Fatalf("an update was sent as time-sensitive: %+v", sent)
	}

	// And the operator's page counts it, without being able to name anything about the device.
	held, err := one.documents.RelayStatus(context.Background(), publisherA)
	if err != nil || held.Bindings != 1 || held.Sends != 2 {
		t.Fatalf("the operator's view is %+v, %v", held, err)
	}
}

// Every way a handle can fail to authorize a send, and the one answer they share. A server learns
// that it may not send, and never whether the handle exists, was revoked, expired, or belongs to
// somebody else.
func TestOnlyTheServerABindingNamesCanUseIt(t *testing.T) {
	one := newGateway(t)
	mine := one.relayCredential(publisherA)
	theirs := one.relayCredential(publisherB)
	owner := one.enroll(phoneTarget)
	binding, handle := one.bind(owner, publisherA, "connection-1")

	for _, refused := range []struct {
		what       string
		credential string
		handle     string
	}{
		{"another registered server's use of this handle", theirs, handle},
		{"a handle nobody issued", mine, strings.Repeat("A", credential.Characters)},
		{"a credential that is not one", "not-a-credential", handle},
	} {
		status, answer := one.serverCall(refused.credential, notify(refused.handle, pushrelay.Created))
		if status != http.StatusForbidden && status != http.StatusUnauthorized {
			t.Fatalf("%s answered %d: %v", refused.what, status, answer)
		}
		if len(one.devices.all()) != 0 {
			t.Fatalf("%s woke a phone", refused.what)
		}
	}

	// A publishing credential presented to the relay, and a relay credential presented to the
	// publisher API. Neither is the other, and neither answer says which it was.
	publishing := one.register(thirdServer)
	if status, _ := one.serverCall(publishing, notify(handle, pushrelay.Created)); status != http.StatusUnauthorized {
		t.Fatalf("a publishing credential reached the relay: %d", status)
	}

	// The owner revokes the binding, and the same handle stops working at once.
	status, _ := one.phoneCall(http.MethodDelete,
		"/installations/"+owner.installation+"/bindings/"+binding, owner.secret, nil)
	if status != http.StatusNoContent {
		t.Fatalf("revoking answered %d", status)
	}
	if status, _ := one.serverCall(mine, notify(handle, pushrelay.Created)); status != http.StatusForbidden {
		t.Fatalf("a revoked handle was honoured: %d", status)
	}

	// And the server that was authorized all along still works, which is the other half of the
	// claim: refusing one caller does not break another.
	_, second := one.bind(owner, publisherA, "connection-1")
	if status, _ := one.serverCall(mine, notify(second, pushrelay.Created)); status != http.StatusAccepted {
		t.Fatalf("the authorized server stopped working: %d", status)
	}
}

// Two phones, each with its own authorization, and removing one leaves the other exactly as it
// was. This is the multiple-device criterion through the handlers rather than the store.
func TestTwoPhonesAreWokenIndependently(t *testing.T) {
	one := newGateway(t)
	server := one.relayCredential(publisherA)
	first := one.enroll("fid-of-the-first")
	second := one.enroll("fid-of-the-second")
	firstBinding, firstHandle := one.bind(first, publisherA, "connection-1")
	_, secondHandle := one.bind(second, publisherA, "connection-1")

	for _, handle := range []string{firstHandle, secondHandle} {
		one.at(one.now().Add(time.Minute))
		if status, _ := one.serverCall(server, notify(handle, pushrelay.Created)); status != http.StatusAccepted {
			t.Fatalf("one of two phones was not woken: %d", status)
		}
	}
	sent := one.devices.all()
	if len(sent) != 2 || sent[0].target == sent[1].target {
		t.Fatalf("the two phones got %+v", sent)
	}

	// One phone's owner disconnects. The other is untouched.
	if status, _ := one.phoneCall(http.MethodDelete,
		"/installations/"+first.installation+"/bindings/"+firstBinding,
		first.secret, nil); status != http.StatusNoContent {
		t.Fatal("revoking the first binding failed")
	}
	one.at(one.now().Add(time.Minute))
	if status, _ := one.serverCall(server, notify(firstHandle, pushrelay.Created)); status != http.StatusForbidden {
		t.Fatalf("a revoked phone was still woken: %d", status)
	}
	one.at(one.now().Add(time.Minute))
	if status, _ := one.serverCall(server, notify(secondHandle, pushrelay.Created)); status != http.StatusAccepted {
		t.Fatalf("removing one binding disabled another: %d", status)
	}
}

// A phone's installation is proved by a secret, not by knowing its identity or its target. This is
// the rule everything else rests on, at the surface a stranger would actually reach.
func TestAnInstallationCannotBeTakenOverFromOutside(t *testing.T) {
	one := newGateway(t)
	server := one.relayCredential(publisherA)
	owner := one.enroll(phoneTarget)
	_, handle := one.bind(owner, publisherA, "connection-1")

	// Everything a stranger could hold: the installation's identity, and the target its paired
	// server was given.
	for _, wrong := range []string{
		strings.Repeat("B", credential.Characters), phoneTarget, "",
	} {
		status, _ := one.phoneCall(http.MethodPost, "/installations/"+owner.installation+"/target",
			wrong, map[string]string{"version": pushrelay.Version, "target": "fid-of-the-attacker"})
		if status != http.StatusUnauthorized && status != http.StatusNotFound {
			t.Fatalf("a target was replaced with %q: %d", wrong, status)
		}
		status, _ = one.phoneCall(http.MethodPost, "/installations/"+owner.installation+"/bindings",
			wrong, map[string]string{
				"version": pushrelay.Version, "server": publisherB, "connection": "c",
			})
		if status != http.StatusUnauthorized && status != http.StatusNotFound {
			t.Fatalf("a binding was authorized with %q: %d", wrong, status)
		}
	}
	// The owner's phone still points where it did.
	if status, _ := one.serverCall(server, notify(handle, pushrelay.Created)); status != http.StatusAccepted {
		t.Fatal("the owner's own phone stopped being wakeable")
	}
	if sent := one.devices.all(); len(sent) != 1 || sent[0].target != phoneTarget {
		t.Fatalf("the wake-up went to %+v", sent)
	}
}

// A phone can only authorize a server the operator enabled, and the refusal does not say which of
// the two reasons it was.
func TestAPhoneCannotAuthorizeAServerTheOperatorDidNotEnable(t *testing.T) {
	one := newGateway(t)
	owner := one.enroll(phoneTarget)

	// A server this gateway has never heard of.
	status, _ := one.phoneCall(http.MethodPost, "/installations/"+owner.installation+"/bindings",
		owner.secret, map[string]string{
			"version": pushrelay.Version, "server": publisherB, "connection": "c",
		})
	if status != http.StatusForbidden {
		t.Fatalf("an unknown server was authorized: %d", status)
	}
	// And one registered to publish, which is not the same permission.
	one.register(publisherA)
	status, _ = one.phoneCall(http.MethodPost, "/installations/"+owner.installation+"/bindings",
		owner.secret, map[string]string{
			"version": pushrelay.Version, "server": publisherA, "connection": "c",
		})
	if status != http.StatusForbidden {
		t.Fatalf("a publish-only server was authorized to relay: %d", status)
	}

	// The operator enables it, and the same call works — with no restart and nothing reissued.
	if err := one.documents.SetCapabilities(context.Background(), publisherA, true, true); err != nil {
		t.Fatal(err)
	}
	one.bind(owner, publisherA, "c")
}

// An external server can name a handle and one of two words. There is no field for text, a title,
// a topic, a target, a priority or a TTL — so the most it can cause is that a phone wakes up and
// reads that server's own authenticated API for itself.
func TestAServerCannotPutAnythingIntoTheMessage(t *testing.T) {
	one := newGateway(t)
	server := one.relayCredential(publisherA)
	owner := one.enroll(phoneTarget)
	_, handle := one.bind(owner, publisherA, "connection-1")

	for _, refused := range []map[string]any{
		{"version": "1", "handle": handle, "hint": "created", "notification": "You owe me money"},
		{"version": "1", "handle": handle, "hint": "created", "data": map[string]string{"x": "y"}},
		{"version": "1", "handle": handle, "hint": "created", "topic": "feed.production.anything"},
		{"version": "1", "handle": handle, "hint": "created", "fid": "someone-elses-phone"},
		{"version": "1", "handle": handle, "hint": "created", "ttl": 86400},
		{"version": "1", "handle": handle, "hint": "created", "priority": "HIGH"},
		{"version": "1", "handle": handle, "hint": "wake-up-every-second"},
		{"version": "2", "handle": handle, "hint": "created"},
	} {
		status, _ := one.serverCall(server, refused)
		if status != http.StatusBadRequest {
			t.Fatalf("%v answered %d", refused, status)
		}
	}
	if len(one.devices.all()) != 0 {
		t.Fatal("a refused request still sent something")
	}
}

// A burst about one connection is one thing for the phone to do — read that server — so above the
// per-binding rate the answer is that the device is already being woken. It is an answer rather
// than an error, because there is nothing for the caller to retry.
func TestABurstAboutOneConnectionIsCoalesced(t *testing.T) {
	one := newGateway(t)
	server := one.relayCredential(publisherA)
	owner := one.enroll(phoneTarget)
	_, handle := one.bind(owner, publisherA, "connection-1")

	accepted, collapsed := 0, 0
	for range 20 {
		status, answer := one.serverCall(server, notify(handle, pushrelay.Updated))
		switch {
		case status == http.StatusAccepted:
			accepted++
		case status == http.StatusOK && answer["status"] == "coalesced":
			collapsed++
		default:
			t.Fatalf("a burst answered %d: %v", status, answer)
		}
	}
	if collapsed == 0 {
		t.Fatal("a burst was not coalesced at all")
	}
	if accepted != len(one.devices.all()) {
		t.Fatalf("%d accepted but %d sent", accepted, len(one.devices.all()))
	}
	// Time passes and the bucket refills, so coalescing is a delay rather than a ban.
	one.at(one.now().Add(time.Minute))
	if status, _ := one.serverCall(server, notify(handle, pushrelay.Updated)); status != http.StatusAccepted {
		t.Fatalf("the binding never recovered: %d", status)
	}
}

// A target the push endpoint rejects is cleared, compared against the one that failed, and the
// caller is told to come back rather than that anything is final: the binding is still an
// authorization, and the phone re-registers on its own.
func TestARejectedTargetIsClearedWithoutEndingTheAuthorization(t *testing.T) {
	one := newGateway(t)
	server := one.relayCredential(publisherA)
	owner := one.enroll(phoneTarget)
	_, handle := one.bind(owner, publisherA, "connection-1")

	one.devices.answer(relay.TargetGone)
	status, _ := one.serverCall(server, notify(handle, pushrelay.Created))
	if status != http.StatusServiceUnavailable {
		t.Fatalf("a rejected target answered %d", status)
	}
	// The binding survives; what is gone is somewhere to send.
	held, err := one.documents.Installation(context.Background(), owner.installation,
		credential.Hash(owner.secret))
	if err != nil || held.HasTarget {
		t.Fatalf("the target was not cleared: %+v, %v", held, err)
	}
	status, answer := one.phoneCall(http.MethodGet, "/installations/"+owner.installation,
		owner.secret, nil)
	if status != http.StatusOK || answer["has_target"] != false {
		t.Fatalf("the phone is not told to register again: %d %v", status, answer)
	}
	bindings, _ := answer["bindings"].([]any)
	if len(bindings) != 1 {
		t.Fatalf("the authorization was lost with the target: %v", answer)
	}

	// The phone registers again, and the same authorization starts working.
	one.devices.answer(relay.Delivered)
	if status, _ := one.phoneCall(http.MethodPost, "/installations/"+owner.installation+"/target",
		owner.secret, map[string]string{
			"version": pushrelay.Version, "target": "fid-after-reinstall",
		}); status != http.StatusNoContent {
		t.Fatal("the phone could not register again")
	}
	one.at(one.now().Add(time.Minute))
	if status, _ := one.serverCall(server, notify(handle, pushrelay.Created)); status != http.StatusAccepted {
		t.Fatalf("the same authorization did not resume: %d", status)
	}
	if sent := one.devices.all(); sent[len(sent)-1].target != "fid-after-reinstall" {
		t.Fatalf("the wake-up went to the old target: %+v", sent)
	}
}

// A gateway that lost its database is recoverable without re-pairing anything, because the direct
// connections were never the gateway's. The phone is told its enrollment is gone, enrolls again,
// and re-authorizes what it holds.
func TestAGatewayThatLostItsStateIsReEnrolledWithoutRePairing(t *testing.T) {
	one := newGateway(t)
	one.relayCredential(publisherA)
	owner := one.enroll(phoneTarget)
	one.bind(owner, publisherA, "connection-1")

	// The gateway's file is gone, and the operator registered the server again — which is the
	// documented recovery, since the operator's password is configuration rather than state.
	if err := one.documents.ForgetInstallation(context.Background(), owner.installation,
		credential.Hash(owner.secret)); err != nil {
		t.Fatal(err)
	}
	status, _ := one.phoneCall(http.MethodGet, "/installations/"+owner.installation,
		owner.secret, nil)
	if status != http.StatusNotFound {
		t.Fatalf("the phone was not told its enrollment is gone: %d", status)
	}
	again := one.enroll(phoneTarget)
	if again.installation == owner.installation {
		t.Fatal("re-enrolling reused an identity that no longer exists")
	}
	one.bind(again, publisherA, "connection-1")

	// Nothing about the direct connection changed: the phone's connection-1 is the same pairing it
	// always was, and it is the gateway's records that were rebuilt around it.
	reconciled, answer := one.phoneCall(http.MethodGet, "/installations/"+again.installation,
		again.secret, nil)
	if reconciled != http.StatusOK {
		t.Fatalf("reconciliation answered %d", reconciled)
	}
	bindings, _ := answer["bindings"].([]any)
	if len(bindings) != 1 {
		t.Fatalf("the rebuilt installation holds %v", answer)
	}
	first, _ := bindings[0].(map[string]any)
	if first["connection"] != "connection-1" || first["server"] != publisherA {
		t.Fatalf("the rebuilt authorization is %v", first)
	}
}

// Nothing about a device leaves this gateway: not to the server that wakes it, not to the
// operator's page, and not to the log.
func TestTheRelayNeverRevealsATargetAHandleOrAnInstallation(t *testing.T) {
	one := administered(t)
	server := one.relayCredential(publisherA)
	owner := one.enroll(phoneTarget)
	binding, handle := one.bind(owner, publisherA, "connection-1")
	if status, _ := one.serverCall(server, notify(handle, pushrelay.Created)); status != http.StatusAccepted {
		t.Fatal("the wake-up failed")
	}
	// A refusal too, because that is the answer most tempted to explain itself.
	one.at(one.now().Add(time.Minute))
	one.serverCall(server, notify(strings.Repeat("C", credential.Characters), pushrelay.Created))

	logged := one.logs.text()
	for _, forbidden := range []string{phoneTarget, handle, owner.secret, server, owner.installation} {
		if strings.Contains(logged, forbidden) {
			t.Fatalf("the log contains %q:\n%s", forbidden, logged)
		}
	}
	// The binding's own ID is not a secret and cannot be turned into one, so it may be logged:
	// that is what lets an operator follow one authorization through a day of lines.
	if !strings.Contains(logged, binding) {
		t.Fatal("nothing identifies a binding in the log, so nothing can be traced")
	}

	// The operator's page counts and never names.
	browser := one.operator()

	page := browser.page("/servers/" + publisherA)
	for _, forbidden := range []string{phoneTarget, handle, owner.secret, owner.installation} {
		if strings.Contains(page, forbidden) {
			t.Fatalf("the operator's page shows %q", forbidden)
		}
	}
	for _, expected := range []string{"device authorization", "Push relay"} {
		if !strings.Contains(page, expected) {
			t.Fatalf("the operator's page does not say %q", expected)
		}
	}
	// And it does not claim anything it cannot know.
	for _, forbidden := range []string{"Online", "Connected", "Reachable"} {
		if strings.Contains(page, forbidden) {
			t.Fatalf("the operator's page claims %q, which this service cannot know", forbidden)
		}
	}
}

// A deployment with no Firebase credential still lets a phone enroll and authorize — neither of
// those sends anything — and tells a server that asks for a wake-up to come back later.
func TestWithoutAPushCredentialTheRelayAcceptsAuthorizationsAndSendsNothing(t *testing.T) {
	one := newGatewayWithoutPush(t)
	server := one.relayCredential(publisherA)
	owner := one.enroll(phoneTarget)
	_, handle := one.bind(owner, publisherA, "connection-1")

	status, answer := one.serverCall(server, notify(handle, pushrelay.Created))
	if status != http.StatusServiceUnavailable {
		t.Fatalf("a gateway with no credential answered %d: %v", status, answer)
	}
}

// The relay's two halves are on the two listeners their callers already use, and neither route
// exists on the other's. A routing mistake therefore cannot let a phone send an invalidation or a
// server enroll an installation.
func TestEachHalfOfTheRelayIsOnlyOnItsOwnListener(t *testing.T) {
	one := newGateway(t)
	server := one.relayCredential(publisherA)
	owner := one.enroll(phoneTarget)
	_, handle := one.bind(owner, publisherA, "connection-1")

	// The send, on the phones' listener.
	status, _ := one.call(one.read.URL, http.MethodPost, pushrelay.Prefix+"/notify", server,
		notify(handle, pushrelay.Created))
	if status != http.StatusNotFound && status != http.StatusMethodNotAllowed {
		t.Fatalf("the send is reachable from the read listener: %d", status)
	}
	// And enrolling, on the servers'.
	status, _ = one.call(one.publish.URL, http.MethodPost, pushrelay.Prefix+"/installations", "",
		map[string]string{"version": pushrelay.Version, "target": phoneTarget})
	if status != http.StatusNotFound && status != http.StatusMethodNotAllowed {
		t.Fatalf("enrolling is reachable from the publisher listener: %d", status)
	}
	if len(one.devices.all()) != 0 {
		t.Fatal("a wake-up was sent through the wrong listener")
	}
}

// The relay is a hint and is never the reason a publication did not happen. This is the same claim
// the topic relay makes, restated for the half an external server drives: the two are independent
// senders, and a broken one does not touch the other.
func TestTheDirectRelayDoesNotAffectTheFeed(t *testing.T) {
	one := newGateway(t)
	server := one.relayCredential(publisherA)
	owner := one.enroll(phoneTarget)
	_, handle := one.bind(owner, publisherA, "connection-1")
	one.devices.answer(relay.Unavailable)
	if status, _ := one.serverCall(server, notify(handle, pushrelay.Created)); status != http.StatusServiceUnavailable {
		t.Fatalf("an unavailable relay answered %d", status)
	}

	// A publication on the same gateway, by a different server, is untouched.
	publishing := one.register(publisherB)
	one.publishManifest(one.publisher(publishing), manifestOf(publisherB, 1))
	one.publishProposal(one.publisher(publishing), proposalOf(publisherB, proposalA, 1))
	one.drain()
	if delivered := one.dispatcher.all(); len(delivered) != 2 {
		t.Fatalf("the feed fanned out %d notices", len(delivered))
	}
}

// The relay's own retention runs on the gateway's sweep, so an abandoned grant ends without
// anybody deciding anything.
func TestTheSweepEndsGrantsNobodyRenewed(t *testing.T) {
	one := newGateway(t)
	server := one.relayCredential(publisherA)
	owner := one.enroll(phoneTarget)
	_, handle := one.bind(owner, publisherA, "connection-1")

	ctx := context.Background()
	bounds := one.settings.Relay.Direct
	long := one.now().Add(bounds.BindingLifetime + time.Hour)
	if _, err := one.documents.SweepRelay(ctx, storage.RelayRetention{
		Bindings: long,
		Idle:     long.Add(-bounds.InstallationIdle),
		Unbound:  long.Add(-bounds.UnboundGrace),
	}); err != nil {
		t.Fatal(err)
	}
	one.at(long)
	if status, _ := one.serverCall(server, notify(handle, pushrelay.Created)); status != http.StatusForbidden {
		t.Fatalf("an authorization nobody renewed still works: %d", status)
	}
}

// The operator's own surface manages both capabilities, in the same process, with nothing
// restarted — which is the other half of SEE-141's claim, now that there are two things to enable.
func TestTheAdminSurfaceManagesRelayOnlyAndCombinedServers(t *testing.T) {
	one := administered(t)
	browser := one.operator()
	ctx := context.Background()

	// A relay-only registration. It needs no manifest, publishes no feed, and its first credential
	// is a relay credential because that is the only thing it is for.
	response := browser.post("/servers", url.Values{
		"csrf": {browser.csrf("/")}, "server": {publisherA},
		"label": {"an independently hosted MCP server"}, "relaying": {"on"},
	})
	_ = response.Body.Close()
	if response.StatusCode != http.StatusSeeOther {
		t.Fatalf("registering a relay-only server answered %s", response.Status)
	}
	relayCredential := secretOn(t, browser.page("/reveal"))

	// It relays and does not publish, from the next call, with nothing restarted.
	owner := one.enroll(phoneTarget)
	_, handle := one.bind(owner, publisherA, "connection-1")
	if status, _ := one.serverCall(relayCredential, notify(handle, pushrelay.Created)); status != http.StatusAccepted {
		t.Fatalf("a relay-only server could not wake a phone: %d", status)
	}
	if server, _ := one.documents.PublisherFor(ctx, credential.Hash(relayCredential)); server != "" {
		t.Fatal("a relay-only registration can publish")
	}

	// A combined one: registered to publish, then enabled for relay and issued a second,
	// separately scoped credential. Neither does the other's work.
	publishing := browser.register(publisherB, "a feed publisher that also relays")
	response = browser.post("/servers/"+publisherB+"/capabilities", url.Values{
		"csrf":       {browser.csrf("/servers/" + publisherB)},
		"publishing": {"on"}, "relaying": {"on"},
	})
	_ = response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("enabling relay answered %s", response.Status)
	}
	response = browser.post("/servers/"+publisherB+"/rotate", url.Values{
		"csrf": {browser.csrf("/servers/" + publisherB)}, "capability": {"relay"},
		"label": {"its relay credential"},
	})
	_ = response.Body.Close()
	relaying := secretOn(t, browser.page("/reveal"))

	if server, _ := one.documents.PublisherFor(ctx, credential.Hash(publishing)); server != publisherB {
		t.Fatal("the publishing credential stopped publishing")
	}
	if server, _ := one.documents.RelayServerFor(ctx, credential.Hash(publishing)); server != "" {
		t.Fatal("a publishing credential gained relay permission")
	}
	if server, _ := one.documents.RelayServerFor(ctx, credential.Hash(relaying)); server != publisherB {
		t.Fatal("the relay credential does not relay")
	}
	if server, _ := one.documents.PublisherFor(ctx, credential.Hash(relaying)); server != "" {
		t.Fatal("a relay credential gained publishing permission")
	}

	// And disabling is a switch rather than an ending: the same credential is refused while it is
	// off and works again when it is on, with nothing reissued.
	_, second := one.bind(owner, publisherB, "connection-2")
	response = browser.post("/servers/"+publisherB+"/capabilities", url.Values{
		"csrf": {browser.csrf("/servers/" + publisherB)}, "publishing": {"on"},
	})
	_ = response.Body.Close()
	one.at(one.now().Add(time.Minute))
	if status, _ := one.serverCall(relaying, notify(second, pushrelay.Created)); status != http.StatusUnauthorized {
		t.Fatalf("a disabled relay still sent: %d", status)
	}
	response = browser.post("/servers/"+publisherB+"/capabilities", url.Values{
		"csrf":       {browser.csrf("/servers/" + publisherB)},
		"publishing": {"on"}, "relaying": {"on"},
	})
	_ = response.Body.Close()
	one.at(one.now().Add(time.Minute))
	if status, _ := one.serverCall(relaying, notify(second, pushrelay.Created)); status != http.StatusAccepted {
		t.Fatalf("the same credential did not work again: %d", status)
	}
}

// secretOn reads the one credential a reveal page shows, out of the element it is rendered in.
func secretOn(t *testing.T, body string) string {
	t.Helper()
	const marker = `<code id="secret">`
	start := strings.Index(body, marker)
	if start < 0 {
		t.Fatalf("no credential was shown:\n%s", body)
	}
	rest := body[start+len(marker):]
	end := strings.Index(rest, "</code>")
	if end < 0 {
		t.Fatal("the credential is not closed")
	}
	return rest[:end]
}
