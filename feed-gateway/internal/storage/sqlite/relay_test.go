package sqlite

import (
	"context"
	"database/sql"
	"errors"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// The relay's records, tested for the three things the design rests on (SEE-144): that ownership
// of an installation is a secret rather than knowledge of its target, that a handle authorizes one
// server and no other, and that nothing keeps working for ever on its own.

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

// This is the rule the whole thing rests on. A device's FCM target is not a secret — the server it
// paired with holds one, and so does anyone who ever saw it — so if knowing a target were enough
// to change where it points, seeing one would be enough to steal a phone's wake-ups.
func TestKnowingATargetIsNotAuthorityOverTheInstallationItBelongsTo(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	secret := enrolled(t, documents, "one", targetA)

	// Everything an attacker could plausibly hold: the installation's identity, which is in every
	// call the phone makes, and its target, which its paired server was given.
	for _, wrong := range [][]byte{hashOf("installation two"), hashOf(targetA), nil} {
		if err := documents.SetTarget(ctx, "one", wrong, "fid-of-the-attacker",
			installed); !errors.Is(err, ErrNoInstallation) {
			t.Fatalf("a target was replaced without the installation's secret: %v", err)
		}
		if _, err := documents.Installation(ctx, "one", wrong); !errors.Is(err, ErrNoInstallation) {
			t.Fatalf("an installation was read without its secret: %v", err)
		}
		if err := documents.ForgetInstallation(ctx, "one", wrong); !errors.Is(err, ErrNoInstallation) {
			t.Fatalf("an installation was forgotten without its secret: %v", err)
		}
	}
	// And the real one still points where it did.
	held, err := documents.Installation(ctx, "one", secret)
	if err != nil || !held.HasTarget {
		t.Fatalf("the installation lost its target: %+v, %v", held, err)
	}
	if err := documents.SetTarget(ctx, "one", secret, targetB, installed.Add(time.Hour)); err != nil {
		t.Fatalf("the owner could not replace its own target: %v", err)
	}
}

// A binding is authorized by the phone and used by the server, and the two halves are separate
// secrets. A server that holds someone else's handle — or one it made up, or one that has been
// revoked — resolves to nothing, with one answer for all of them.
func TestAHandleAuthorizesOneServerAndNoOther(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	relayServer(t, documents, publisher)
	relayServer(t, documents, stranger)
	secret := enrolled(t, documents, "one", targetA)
	handle := hashOf("handle one")
	bound(t, documents, "one", secret, publisher, "connection-a", handle, "binding-a")

	target, err := documents.TargetFor(ctx, publisher, handle, installed)
	if err != nil || target.Target != targetA {
		t.Fatalf("the server that was authorized cannot send: %+v, %v", target, err)
	}

	// The same handle, presented by the other registered server.
	if _, err := documents.TargetFor(ctx, stranger, handle, installed); !errors.Is(err, ErrNoBinding) {
		t.Fatalf("another server used this handle: %v", err)
	}
	// A handle nobody issued.
	if _, err := documents.TargetFor(ctx, publisher, hashOf("invented"),
		installed); !errors.Is(err, ErrNoBinding) {
		t.Fatalf("a fabricated handle resolved: %v", err)
	}
	// One that has expired.
	if _, err := documents.TargetFor(ctx, publisher, handle,
		expires.Add(time.Second)); !errors.Is(err, ErrNoBinding) {
		t.Fatalf("an expired handle resolved: %v", err)
	}
	// And one the phone revoked.
	revoked, err := documents.Unbind(ctx, "one", secret, "binding-a", installed.Add(time.Hour))
	if err != nil || !revoked {
		t.Fatalf("the owner could not revoke its own binding: %v, %v", revoked, err)
	}
	if _, err := documents.TargetFor(ctx, publisher, handle, installed.Add(2*time.Hour)); !errors.Is(err, ErrNoBinding) {
		t.Fatalf("a revoked handle resolved: %v", err)
	}
}

// A phone cannot authorize a server the operator never enabled, and the refusal does not say which
// of the two reasons it was: a phone may learn that this binding was refused, and the difference
// between "no such server" and "relay is off for it" is the operator's business.
func TestOnlyAServerTheOperatorEnabledCanBeAuthorized(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	secret := enrolled(t, documents, "one", targetA)

	// A server that is not registered at all.
	err := documents.Bind(ctx, storage.RelayBindingRequest{
		InstallationID: "one", SecretHash: secret, ServerID: stranger, Connection: "c",
		HandleID: "b", HandleHash: hashOf("h"), CreatedAt: installed, ExpiresAt: expires,
	})
	if !errors.Is(err, ErrNotPermitted) {
		t.Fatalf("an unregistered server was authorized: %v", err)
	}

	// And one that is registered to publish but not to relay.
	register(t, documents, publisher)
	err = documents.Bind(ctx, storage.RelayBindingRequest{
		InstallationID: "one", SecretHash: secret, ServerID: publisher, Connection: "c",
		HandleID: "b", HandleHash: hashOf("h"), CreatedAt: installed, ExpiresAt: expires,
	})
	if !errors.Is(err, ErrNotPermitted) {
		t.Fatalf("a publish-only server was authorized to relay: %v", err)
	}

	// The operator enables it, and the same call works — without anything being revoked or
	// reissued, and without a restart.
	if err := documents.SetCapabilities(ctx, publisher, true, true); err != nil {
		t.Fatal(err)
	}
	if err := documents.Bind(ctx, storage.RelayBindingRequest{
		InstallationID: "one", SecretHash: secret, ServerID: publisher, Connection: "c",
		HandleID: "b", HandleHash: hashOf("h"), CreatedAt: installed, ExpiresAt: expires,
	}); err != nil {
		t.Fatalf("an enabled server could not be authorized: %v", err)
	}
}

// The two grants are separate queries over separate columns, so neither can be reached with the
// other's credential however the call is made.
func TestAPublishingCredentialIsNotARelayCredentialAndViceVersa(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	if _, err := documents.Register(ctx, Registration{
		ServerID: publisher, Label: "both", Publishing: true, Relaying: true,
	}, storage.Publishing, hashOf("publishing"), published); err != nil {
		t.Fatal(err)
	}
	if _, err := documents.AddCredential(ctx, publisher, "relay", storage.Relaying,
		hashOf("relaying"), published); err != nil {
		t.Fatal(err)
	}

	if server, err := documents.PublisherFor(ctx, hashOf("publishing")); err != nil || server != publisher {
		t.Fatalf("the publishing credential does not publish: %q, %v", server, err)
	}
	if server, err := documents.RelayServerFor(ctx, hashOf("relaying")); err != nil || server != publisher {
		t.Fatalf("the relay credential does not relay: %q, %v", server, err)
	}
	if server, err := documents.PublisherFor(ctx, hashOf("relaying")); err != nil || server != "" {
		t.Fatalf("a relay credential published: %q, %v", server, err)
	}
	if server, err := documents.RelayServerFor(ctx, hashOf("publishing")); err != nil || server != "" {
		t.Fatalf("a publishing credential relayed: %q, %v", server, err)
	}

	// Disabling a capability refuses its credentials without revoking them, and enabling it again
	// finds the same ones working. That is what makes it a switch rather than an ending.
	if err := documents.SetCapabilities(ctx, publisher, true, false); err != nil {
		t.Fatal(err)
	}
	if server, _ := documents.RelayServerFor(ctx, hashOf("relaying")); server != "" {
		t.Fatal("a relay credential worked while relay was disabled")
	}
	if server, _ := documents.PublisherFor(ctx, hashOf("publishing")); server != publisher {
		t.Fatal("disabling relay stopped publishing")
	}
	if err := documents.SetCapabilities(ctx, publisher, true, true); err != nil {
		t.Fatal(err)
	}
	if server, _ := documents.RelayServerFor(ctx, hashOf("relaying")); server != publisher {
		t.Fatal("the same relay credential did not work again")
	}
}

// Two phones, two servers, and none of the four combinations leaking into another. This is the
// acceptance criterion about multiple devices, at the layer that decides it.
func TestTwoPhonesAndTwoServersStayApart(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	relayServer(t, documents, publisher)
	relayServer(t, documents, stranger)
	first := enrolled(t, documents, "first", targetA)
	second := enrolled(t, documents, "second", targetB)
	bound(t, documents, "first", first, publisher, "c1", hashOf("h1"), "b1")
	bound(t, documents, "second", second, publisher, "c2", hashOf("h2"), "b2")
	bound(t, documents, "second", second, stranger, "c3", hashOf("h3"), "b3")

	for _, want := range []struct {
		server string
		handle []byte
		target string
	}{
		{publisher, hashOf("h1"), targetA},
		{publisher, hashOf("h2"), targetB},
		{stranger, hashOf("h3"), targetB},
	} {
		got, err := documents.TargetFor(ctx, want.server, want.handle, installed)
		if err != nil || got.Target != want.target {
			t.Fatalf("%s could not wake %s: %+v, %v", want.server, want.target, got, err)
		}
	}

	// Removing one binding leaves the others exactly as they were.
	if _, err := documents.Unbind(ctx, "second", second, "b2", installed.Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	if _, err := documents.TargetFor(ctx, publisher, hashOf("h2"), installed.Add(2*time.Hour)); !errors.Is(err, ErrNoBinding) {
		t.Fatal("the revoked binding still resolves")
	}
	for _, still := range []struct {
		server string
		handle []byte
	}{{publisher, hashOf("h1")}, {stranger, hashOf("h3")}} {
		if _, err := documents.TargetFor(ctx, still.server, still.handle,
			installed.Add(2*time.Hour)); err != nil {
			t.Fatalf("revoking one binding disabled another: %v", err)
		}
	}
}

// A rebinding of the same connection replaces rather than accumulates, so a phone that reconnects
// a hundred times does not leave a hundred live handles for one connection.
func TestRebindingOneConnectionReplacesItsAuthorization(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	relayServer(t, documents, publisher)
	secret := enrolled(t, documents, "one", targetA)
	bound(t, documents, "one", secret, publisher, "c1", hashOf("h1"), "b1")
	bound(t, documents, "one", secret, publisher, "c1", hashOf("h2"), "b2")

	if _, err := documents.TargetFor(ctx, publisher, hashOf("h1"), installed); !errors.Is(err, ErrNoBinding) {
		t.Fatal("the replaced handle still works")
	}
	if _, err := documents.TargetFor(ctx, publisher, hashOf("h2"), installed); err != nil {
		t.Fatalf("the new handle does not work: %v", err)
	}
	bindings, err := documents.Bindings(ctx, "one", secret)
	if err != nil {
		t.Fatal(err)
	}
	active := 0
	for _, binding := range bindings {
		if binding.RevokedAt == nil {
			active++
		}
	}
	if active != 1 {
		t.Fatalf("one connection holds %d live authorizations", active)
	}
}

// A rejection is news about a send that started some time ago. Clearing unconditionally would
// unregister a device that had just registered, and the phone would find out by never being woken.
func TestARejectedTargetIsClearedOnlyWhileItIsStillTheTarget(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	secret := enrolled(t, documents, "one", targetA)

	// The phone rotates while a send to the old target is in flight.
	if err := documents.SetTarget(ctx, "one", secret, targetB, installed.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	cleared, err := documents.TargetRejected(ctx, "one", targetA)
	if err != nil || cleared {
		t.Fatalf("a stale rejection erased a newer registration: %v, %v", cleared, err)
	}
	held, err := documents.Installation(ctx, "one", secret)
	if err != nil || !held.HasTarget {
		t.Fatalf("the newer registration was lost: %+v, %v", held, err)
	}

	// A rejection of the current one does clear it, and the installation stays: the phone
	// re-registers, and its bindings are still its bindings.
	cleared, err = documents.TargetRejected(ctx, "one", targetB)
	if err != nil || !cleared {
		t.Fatalf("the current target was not cleared: %v, %v", cleared, err)
	}
	if held, err := documents.Installation(ctx, "one", secret); err != nil || held.HasTarget {
		t.Fatalf("the installation is wrong after a rejection: %+v, %v", held, err)
	}
}

// A binding with nowhere to send resolves to nothing rather than to an empty target, and the
// refusal is the same one a fabricated handle gets: a server learns that it may not send, and
// nothing about the device.
func TestABindingWithNoTargetDoesNotResolve(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	relayServer(t, documents, publisher)
	secret := enrolled(t, documents, "one", targetA)
	bound(t, documents, "one", secret, publisher, "c1", hashOf("h1"), "b1")
	if _, err := documents.TargetRejected(ctx, "one", targetA); err != nil {
		t.Fatal(err)
	}
	if _, err := documents.TargetFor(ctx, publisher, hashOf("h1"), installed); !errors.Is(err, ErrNoBinding) {
		t.Fatalf("a binding with no target resolved: %v", err)
	}
}

// Nothing here keeps working on its own. A grant nobody renewed ends, a device nobody has heard
// from is forgotten, and an enrollment that authorized nothing is not kept for weeks.
func TestAbandonedGrantsAreBounded(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	relayServer(t, documents, publisher)
	live := enrolled(t, documents, "live", targetA)
	bound(t, documents, "live", live, publisher, "c1", hashOf("h1"), "b1")
	enrolled(t, documents, "never-bound", targetB)
	gone := enrolled(t, documents, "gone", targetB)
	bound(t, documents, "gone", gone, publisher, "c2", hashOf("h2"), "b2")

	// A day later: the enrollment that authorized nothing is gone, and everything else is intact.
	later := installed.Add(25 * time.Hour)
	if _, err := documents.SweepRelay(ctx, storage.RelayRetention{
		Bindings: later, Idle: later.Add(-60 * 24 * time.Hour), Unbound: later.Add(-24 * time.Hour),
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := documents.Installation(ctx, "never-bound",
		hashOf("installation never-bound")); !errors.Is(err, ErrNoInstallation) {
		t.Fatal("an enrollment that authorized nothing was kept")
	}
	if _, err := documents.TargetFor(ctx, publisher, hashOf("h1"), later); err != nil {
		t.Fatalf("the sweep ended a live authorization: %v", err)
	}

	// Two months on, with the live phone still renewing and the other never heard from again.
	much := installed.Add(70 * 24 * time.Hour)
	if err := documents.SetTarget(ctx, "live", live, targetA, much); err != nil {
		t.Fatal(err)
	}
	boundAt(t, documents, "live", live, publisher, "c1", hashOf("h3"), "b3", much)
	if _, err := documents.SweepRelay(ctx, storage.RelayRetention{
		Bindings: much, Idle: much.Add(-60 * 24 * time.Hour), Unbound: much.Add(-24 * time.Hour),
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := documents.Installation(ctx, "gone", gone); !errors.Is(err, ErrNoInstallation) {
		t.Fatal("a device nothing has been heard from since is still able to be woken")
	}
	if _, err := documents.TargetFor(ctx, publisher, hashOf("h2"), much); !errors.Is(err, ErrNoBinding) {
		t.Fatal("a forgotten installation's binding survived it")
	}
	if _, err := documents.TargetFor(ctx, publisher, hashOf("h3"), much); err != nil {
		t.Fatalf("the renewed authorization was swept: %v", err)
	}
}

// Forgetting a server takes its authorizations with it, through the foreign key rather than
// through a second statement somebody has to remember.
func TestForgettingAServerEndsWhatItCouldWake(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	relayServer(t, documents, publisher)
	secret := enrolled(t, documents, "one", targetA)
	bound(t, documents, "one", secret, publisher, "c1", hashOf("h1"), "b1")

	if err := documents.Forget(ctx, publisher, channelA); err != nil {
		t.Fatal(err)
	}
	if _, err := documents.TargetFor(ctx, publisher, hashOf("h1"), installed); !errors.Is(err, ErrNoBinding) {
		t.Fatalf("a forgotten server can still wake a phone: %v", err)
	}
	// The installation itself is not the server's to remove: this phone is still enrolled, still
	// holds its other bindings, and its owner is the only one who can end it.
	if _, err := documents.Installation(ctx, "one", secret); err != nil {
		t.Fatalf("forgetting a server forgot a phone: %v", err)
	}
}

// The operator's view is counts and instants, and there is no query behind it that could return a
// target, a handle or an installation identity.
func TestTheOperatorsRelayViewIsAggregateOnly(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	relayServer(t, documents, publisher)
	secret := enrolled(t, documents, "one", targetA)
	bound(t, documents, "one", secret, publisher, "c1", hashOf("h1"), "b1")
	bound(t, documents, "one", secret, publisher, "c2", hashOf("h2"), "b2")
	if _, err := documents.Unbind(ctx, "one", secret, "b2", installed.Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	if err := documents.Sent(ctx, "b1", installed.Add(2*time.Hour)); err != nil {
		t.Fatal(err)
	}

	status, err := documents.RelayStatus(ctx, publisher)
	if err != nil {
		t.Fatal(err)
	}
	if status.Bindings != 1 || status.Revoked != 1 || status.Sends != 1 {
		t.Fatalf("the operator's view is wrong: %+v", status)
	}
	if status.LastSentAt == nil || !status.LastSentAt.Equal(installed.Add(2*time.Hour)) {
		t.Fatalf("the last accepted send is wrong: %+v", status.LastSentAt)
	}
	// A server nobody has authorized reads as nothing rather than as an error.
	relayServer(t, documents, stranger)
	empty, err := documents.RelayStatus(ctx, stranger)
	if err != nil || empty.Bindings != 0 || empty.Sends != 0 {
		t.Fatalf("an unauthorized server's view is wrong: %+v, %v", empty, err)
	}
}

// Version 5 is applied to a database written before it, and everything in that database keeps
// behaving exactly as it did: a registration publishes, its credential resolves, and neither has
// acquired a relay grant by being migrated.
func TestVersionFiveLeavesAnExistingRegistrationExactlyAsItWas(t *testing.T) {
	path := t.TempDir() + "/broadcast.db"
	raw, err := sql.Open("sqlite", "file:"+path+"?_pragma=foreign_keys(1)")
	if err != nil {
		t.Fatal(err)
	}
	for _, statement := range []string{schemaV1, schemaV2, schemaV3, schemaV4,
		`PRAGMA user_version = 4`} {
		if _, err := raw.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := raw.Exec(
		`INSERT INTO publisher (server_id, label, host, created_at_ms) VALUES (?, ?, '', ?)`,
		publisher, "before", published.UnixMilli()); err != nil {
		t.Fatal(err)
	}
	if _, err := raw.Exec(
		`INSERT INTO publisher_credential (credential_hash, server_id, label, created_at_ms)
		 VALUES (?, ?, 'old', ?)`, hashOf(publisher), publisher, published.UnixMilli()); err != nil {
		t.Fatal(err)
	}
	if err := raw.Close(); err != nil {
		t.Fatal(err)
	}

	documents, err := Open(path)
	if err != nil {
		t.Fatalf("version 5 could not be applied: %v", err)
	}
	t.Cleanup(func() { _ = documents.Close() })
	ctx := context.Background()
	if server, err := documents.PublisherFor(ctx, hashOf(publisher)); err != nil || server != publisher {
		t.Fatalf("a registration made before SEE-144 stopped publishing: %q, %v", server, err)
	}
	if server, err := documents.RelayServerFor(ctx, hashOf(publisher)); err != nil || server != "" {
		t.Fatalf("a migration granted a relay nobody asked for: %q, %v", server, err)
	}
	held, err := documents.Publisher(ctx, publisher)
	if err != nil || held == nil || !held.Publishing || held.Relaying {
		t.Fatalf("the migrated registration has the wrong capabilities: %+v, %v", held, err)
	}
}
