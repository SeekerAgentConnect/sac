package postgres

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// The relay's records against a real Postgres (SEE-144, SEE-145). The three things the design
// rests on are checked here as they are in the SQLite store — ownership of an installation is a
// secret rather than knowledge of its target, a handle authorizes one server and no other, and
// nothing keeps working for ever on its own — because this is the half of the store the move to a
// durable database was for, and it has to mean the same thing in both.

const (
	targetA = "fid-of-the-first-phone"
	targetB = "fid-of-the-second-phone"
)

var (
	installed = time.Date(2026, 9, 21, 9, 0, 0, 0, time.UTC)
	expires   = installed.Add(30 * 24 * time.Hour)
)

// relayServer registers a server the operator has enabled the relay for, and returns its relay
// credential's hash.
func relayServer(t *testing.T, documents *Store, serverID string) []byte {
	t.Helper()
	hash := hashOf("relay " + serverID)
	if _, err := documents.Register(context.Background(), Registration{
		ServerID: serverID, Label: "relay", Relaying: true,
	}, storage.Relaying, hash, published); err != nil {
		t.Fatal(err)
	}
	return hash
}

// enrolled is one installation with one target, and the secret that proves it.
func enrolled(t *testing.T, documents *Store, id, target string) []byte {
	t.Helper()
	secret := hashOf("installation " + id)
	if err := documents.Enroll(context.Background(), id, secret, target, installed); err != nil {
		t.Fatal(err)
	}
	return secret
}

func bound(t *testing.T, documents *Store, installation string, secret []byte,
	serverID, connection string, handle []byte, binding string) {
	t.Helper()
	boundAt(t, documents, installation, secret, serverID, connection, handle, binding, installed)
}

// boundAt is the same authorization made at a given instant, which is what a renewal is: the same
// connection, a fresh handle, and an expiry counted from now.
func boundAt(t *testing.T, documents *Store, installation string, secret []byte,
	serverID, connection string, handle []byte, binding string, at time.Time) {
	t.Helper()
	if err := documents.Bind(context.Background(), storage.RelayBindingRequest{
		InstallationID: installation, SecretHash: secret, ServerID: serverID,
		Connection: connection, HandleID: binding, HandleHash: handle,
		CreatedAt: at, ExpiresAt: at.Add(30 * 24 * time.Hour),
	}); err != nil {
		t.Fatal(err)
	}
}

// A device's FCM target is not a secret — the server it paired with holds one, and so does anyone
// who ever saw it — so if knowing a target were enough to change where it points, seeing one would
// be enough to steal a phone's wake-ups.
func TestKnowingATargetIsNotAuthorityOverTheInstallationItBelongsTo(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	secret := enrolled(t, documents, "installation-a", targetA)

	wrong := hashOf("a guess")
	if err := documents.SetTarget(ctx, "installation-a", wrong, "somewhere-else", installed); !errors.Is(err, ErrNoInstallation) {
		t.Fatalf("a target was replaced without the secret: %v", err)
	}
	if err := documents.ForgetInstallation(ctx, "installation-a", wrong); !errors.Is(err, ErrNoInstallation) {
		t.Fatalf("an installation was forgotten without the secret: %v", err)
	}
	// An installation that does not exist and one whose secret did not match are one answer.
	if _, err := documents.Installation(ctx, "installation-z", wrong); !errors.Is(err, ErrNoInstallation) {
		t.Fatalf("an unknown installation answered %v", err)
	}
	held, err := documents.Installation(ctx, "installation-a", secret)
	if err != nil || held == nil || !held.HasTarget {
		t.Fatalf("the owner could not read its own installation: %+v (%v)", held, err)
	}
	if err := documents.SetTarget(ctx, "installation-a", secret, targetB, installed.Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	renewed, err := documents.Installation(ctx, "installation-a", secret)
	if err != nil || !renewed.SeenAt.After(held.SeenAt) {
		t.Fatalf("replacing the target did not renew the installation: %+v (%v)", renewed, err)
	}
}

func TestAHandleAuthorizesOneServerAndNoOther(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	relayServer(t, documents, publisher)
	relayServer(t, documents, stranger)
	secret := enrolled(t, documents, "installation-a", targetA)
	handle := hashOf("handle one")
	bound(t, documents, "installation-a", secret, publisher, "connection-1", handle, "b-1")

	target, err := documents.TargetFor(ctx, publisher, handle, installed.Add(time.Hour))
	if err != nil || target == nil || target.Target != targetA {
		t.Fatalf("the authorized server could not resolve its handle: %+v (%v)", target, err)
	}
	if target.BindingID != "b-1" || target.InstallationID != "installation-a" {
		t.Fatalf("the resolution names %+v", *target)
	}
	// Every other way of presenting a handle is one refusal with one shape.
	for name, attempt := range map[string]func() (*storage.RelayTarget, error){
		"another server": func() (*storage.RelayTarget, error) {
			return documents.TargetFor(ctx, stranger, handle, installed.Add(time.Hour))
		},
		"a fabricated handle": func() (*storage.RelayTarget, error) {
			return documents.TargetFor(ctx, publisher, hashOf("invented"), installed.Add(time.Hour))
		},
		"after it expired": func() (*storage.RelayTarget, error) {
			return documents.TargetFor(ctx, publisher, handle, expires.Add(time.Hour))
		},
	} {
		if _, err := attempt(); !errors.Is(err, ErrNoBinding) {
			t.Fatalf("%s resolved with %v", name, err)
		}
	}
	// And a revoked one.
	revoked, err := documents.Unbind(ctx, "installation-a", secret, "b-1", installed.Add(2*time.Hour))
	if err != nil || !revoked {
		t.Fatalf("the binding was not revoked (%v)", err)
	}
	if _, err := documents.TargetFor(ctx, publisher, handle, installed.Add(3*time.Hour)); !errors.Is(err, ErrNoBinding) {
		t.Fatalf("a revoked handle resolved with %v", err)
	}
	// Revoking again is not an error: the phone retries this call.
	again, err := documents.Unbind(ctx, "installation-a", secret, "b-1", installed.Add(3*time.Hour))
	if err != nil || again {
		t.Fatalf("revoking twice said %v (%v)", again, err)
	}
}

func TestOnlyAServerTheOperatorEnabledCanBeAuthorized(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	secret := enrolled(t, documents, "installation-a", targetA)

	// A server nobody registered, and a registered one whose relay is off, are the same refusal:
	// a phone may learn that the binding was refused, never whether the gateway knows the server.
	err := documents.Bind(ctx, storage.RelayBindingRequest{
		InstallationID: "installation-a", SecretHash: secret, ServerID: stranger,
		Connection: "connection-1", HandleID: "b-1", HandleHash: hashOf("h"),
		CreatedAt: installed, ExpiresAt: expires,
	})
	if !errors.Is(err, ErrNotPermitted) {
		t.Fatalf("binding to an unregistered server gave %v", err)
	}
	register(t, documents, publisher) // publishing only
	err = documents.Bind(ctx, storage.RelayBindingRequest{
		InstallationID: "installation-a", SecretHash: secret, ServerID: publisher,
		Connection: "connection-1", HandleID: "b-1", HandleHash: hashOf("h"),
		CreatedAt: installed, ExpiresAt: expires,
	})
	if !errors.Is(err, ErrNotPermitted) {
		t.Fatalf("binding to a server with relay disabled gave %v", err)
	}
	// Enabling it is a switch, and the same call then works.
	if err := documents.SetCapabilities(ctx, publisher, true, true); err != nil {
		t.Fatal(err)
	}
	bound(t, documents, "installation-a", secret, publisher, "connection-1", hashOf("h"), "b-1")
}

func TestAPublishingCredentialIsNotARelayCredentialAndViceVersa(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher) // publishing
	relay, err := documents.AddCredential(ctx, publisher, "relay", storage.Relaying, hashOf("relay one"), published)
	if err != nil {
		t.Fatal(err)
	}
	_ = relay
	if err := documents.SetCapabilities(ctx, publisher, true, true); err != nil {
		t.Fatal(err)
	}
	// Each resolves through its own grant and through no other.
	if who, err := documents.PublisherFor(ctx, hashOf("relay one")); err != nil || who != "" {
		t.Fatalf("a relay credential published as %q (%v)", who, err)
	}
	if who, err := documents.RelayServerFor(ctx, hashOf(publisher)); err != nil || who != "" {
		t.Fatalf("a publishing credential relayed as %q (%v)", who, err)
	}
	if who, err := documents.RelayServerFor(ctx, hashOf("relay one")); err != nil || who != publisher {
		t.Fatalf("the relay credential resolved to %q (%v)", who, err)
	}
	// Disabling relay refuses the credential without revoking it.
	if err := documents.SetCapabilities(ctx, publisher, true, false); err != nil {
		t.Fatal(err)
	}
	if who, err := documents.RelayServerFor(ctx, hashOf("relay one")); err != nil || who != "" {
		t.Fatalf("a disabled relay credential resolved to %q (%v)", who, err)
	}
}

func TestRebindingOneConnectionReplacesItsAuthorization(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	relayServer(t, documents, publisher)
	secret := enrolled(t, documents, "installation-a", targetA)

	first, second := hashOf("handle one"), hashOf("handle two")
	bound(t, documents, "installation-a", secret, publisher, "connection-1", first, "b-1")
	boundAt(t, documents, "installation-a", secret, publisher, "connection-1", second, "b-2",
		installed.Add(time.Hour))

	// The partial unique index is what makes this a replacement rather than a second live grant.
	if _, err := documents.TargetFor(ctx, publisher, first, installed.Add(2*time.Hour)); !errors.Is(err, ErrNoBinding) {
		t.Fatalf("the replaced handle still resolves: %v", err)
	}
	if _, err := documents.TargetFor(ctx, publisher, second, installed.Add(2*time.Hour)); err != nil {
		t.Fatalf("the current handle does not resolve: %v", err)
	}
	bindings, err := documents.Bindings(ctx, "installation-a", secret)
	if err != nil || len(bindings) != 2 {
		t.Fatalf("the installation lists %d bindings (%v), expected the revoked one to be kept", len(bindings), err)
	}
	live := 0
	for _, binding := range bindings {
		if binding.Active(installed.Add(2 * time.Hour)) {
			live++
		}
	}
	if live != 1 {
		t.Fatalf("%d bindings are live for one connection", live)
	}
	if _, err := documents.Bindings(ctx, "installation-a", hashOf("a guess")); !errors.Is(err, ErrNoInstallation) {
		t.Fatal("bindings were listed without the secret")
	}
}

func TestARejectedTargetIsClearedOnlyWhileItIsStillTheTarget(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	secret := enrolled(t, documents, "installation-a", targetA)

	// A rejection about a target the phone has already replaced must not unregister the new one.
	if err := documents.SetTarget(ctx, "installation-a", secret, targetB, installed.Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	cleared, err := documents.TargetRejected(ctx, "installation-a", targetA)
	if err != nil || cleared {
		t.Fatalf("a stale rejection cleared the current target (%v)", err)
	}
	held, err := documents.Installation(ctx, "installation-a", secret)
	if err != nil || !held.HasTarget {
		t.Fatalf("the current target was lost: %+v (%v)", held, err)
	}
	cleared, err = documents.TargetRejected(ctx, "installation-a", targetB)
	if err != nil || !cleared {
		t.Fatalf("the rejection that was about the current target did nothing (%v)", err)
	}
	if held, err := documents.Installation(ctx, "installation-a", secret); err != nil || held.HasTarget {
		t.Fatalf("the rejected target is still held: %+v (%v)", held, err)
	}
}

func TestABindingWithNoTargetDoesNotResolve(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	relayServer(t, documents, publisher)
	secret := enrolled(t, documents, "installation-a", targetA)
	handle := hashOf("handle one")
	bound(t, documents, "installation-a", secret, publisher, "connection-1", handle, "b-1")

	if _, err := documents.TargetRejected(ctx, "installation-a", targetA); err != nil {
		t.Fatal(err)
	}
	// There is nothing to wake, and saying which of the two was missing would tell a server
	// something about a device.
	if _, err := documents.TargetFor(ctx, publisher, handle, installed.Add(time.Hour)); !errors.Is(err, ErrNoBinding) {
		t.Fatalf("a binding with no target resolved with %v", err)
	}
}

func TestAbandonedGrantsAreBounded(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	relayServer(t, documents, publisher)

	// One that bound and stopped renewing, and one that enrolled and never bound at all.
	bindingSecret := enrolled(t, documents, "installation-a", targetA)
	bound(t, documents, "installation-a", bindingSecret, publisher, "connection-1", hashOf("h1"), "b-1")
	quietSecret := enrolled(t, documents, "installation-b", targetB)

	removed, err := documents.SweepRelay(ctx, storage.RelayRetention{
		Bindings: expires.Add(time.Hour),    // the binding is past its expiry
		Idle:     installed.Add(-time.Hour), // neither installation is idle yet
		Unbound:  installed.Add(time.Hour),  // but an unbound one has used up its grace
	})
	if err != nil {
		t.Fatal(err)
	}
	// The binding, and the installation that never bound. The order matters: bindings go first, so
	// the installation that has just lost its last one is unbound when the second statement asks.
	if removed != 3 {
		t.Fatalf("the sweep removed %d rows, expected 3", removed)
	}
	if _, err := documents.Installation(ctx, "installation-b", quietSecret); !errors.Is(err, ErrNoInstallation) {
		t.Fatalf("an installation that never bound survived its grace: %v", err)
	}
	if _, err := documents.Installation(ctx, "installation-a", bindingSecret); !errors.Is(err, ErrNoInstallation) {
		t.Fatalf("an installation whose last binding expired survived: %v", err)
	}
}

func TestForgettingAServerEndsWhatItCouldWake(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	relayServer(t, documents, publisher)
	secret := enrolled(t, documents, "installation-a", targetA)
	handle := hashOf("handle one")
	bound(t, documents, "installation-a", secret, publisher, "connection-1", handle, "b-1")

	if err := documents.Forget(ctx, publisher, channelA); err != nil {
		t.Fatal(err)
	}
	if _, err := documents.TargetFor(ctx, publisher, handle, installed.Add(time.Hour)); !errors.Is(err, ErrNoBinding) {
		t.Fatalf("a forgotten server can still wake a phone: %v", err)
	}
	// The installation is the phone's and stays: forgetting a server is not forgetting a device.
	if held, err := documents.Installation(ctx, "installation-a", secret); err != nil || held == nil {
		t.Fatalf("forgetting a server took the installation with it (%v)", err)
	}
}

func TestTheOperatorsRelayViewIsAggregateOnly(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	relayServer(t, documents, publisher)
	secretA := enrolled(t, documents, "installation-a", targetA)
	secretB := enrolled(t, documents, "installation-b", targetB)
	bound(t, documents, "installation-a", secretA, publisher, "connection-1", hashOf("h1"), "b-1")
	bound(t, documents, "installation-b", secretB, publisher, "connection-1", hashOf("h2"), "b-2")

	sent := installed.Add(time.Hour)
	if err := documents.Sent(ctx, "b-1", sent); err != nil {
		t.Fatal(err)
	}
	if err := documents.Sent(ctx, "b-1", sent.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if _, err := documents.Unbind(ctx, "installation-b", secretB, "b-2", sent); err != nil {
		t.Fatal(err)
	}
	status, err := documents.RelayStatus(ctx, publisher)
	if err != nil {
		t.Fatal(err)
	}
	if status.Bindings != 1 || status.Revoked != 1 || status.Sends != 2 {
		t.Fatalf("the status reads %+v", status)
	}
	if status.LastSentAt == nil || !status.LastSentAt.Equal(sent.Add(time.Minute)) {
		t.Fatalf("the last send is %v", status.LastSentAt)
	}
	if status.LastBindAt == nil || !status.LastBindAt.Equal(installed) {
		t.Fatalf("the last binding is %v", status.LastBindAt)
	}
	// A server nobody authorized reads as nothing rather than as an error.
	register(t, documents, stranger)
	empty, err := documents.RelayStatus(ctx, stranger)
	if err != nil || empty.Bindings != 0 || empty.Sends != 0 || empty.LastSentAt != nil {
		t.Fatalf("an unauthorized server reads %+v (%v)", empty, err)
	}
}

func TestTwoPhonesAndTwoServersStayApart(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	relayServer(t, documents, publisher)
	relayServer(t, documents, stranger)
	secretA := enrolled(t, documents, "installation-a", targetA)
	secretB := enrolled(t, documents, "installation-b", targetB)

	handleAA, handleBB := hashOf("a to first"), hashOf("b to second")
	bound(t, documents, "installation-a", secretA, publisher, "connection-1", handleAA, "b-aa")
	bound(t, documents, "installation-b", secretB, stranger, "connection-1", handleBB, "b-bb")

	first, err := documents.TargetFor(ctx, publisher, handleAA, installed.Add(time.Hour))
	if err != nil || first.Target != targetA {
		t.Fatalf("the first server resolved to %+v (%v)", first, err)
	}
	second, err := documents.TargetFor(ctx, stranger, handleBB, installed.Add(time.Hour))
	if err != nil || second.Target != targetB {
		t.Fatalf("the second server resolved to %+v (%v)", second, err)
	}
	// Neither can present the other's handle, and neither installation can list the other's grants.
	if _, err := documents.TargetFor(ctx, publisher, handleBB, installed.Add(time.Hour)); !errors.Is(err, ErrNoBinding) {
		t.Fatalf("a server resolved another's handle: %v", err)
	}
	bindings, err := documents.Bindings(ctx, "installation-a", secretA)
	if err != nil || len(bindings) != 1 || bindings[0].ServerID != publisher {
		t.Fatalf("one phone sees %+v (%v)", bindings, err)
	}
}
