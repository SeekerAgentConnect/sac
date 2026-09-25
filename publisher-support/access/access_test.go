package access

import (
	"context"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/gateway"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/store"
)

// The publisher's half of a restricted feed (SEE-156): every proof, decision, invitation and grant,
// driven through the real service over a real store, with the gateway replaced by a recorder so a
// test can make it fail and see what the operator is shown.

const (
	serverID   = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
	otherFeed  = "server/9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
	authOrigin = "https://auth.copytrading.example.com"
)

var start = time.Date(2026, 9, 25, 12, 0, 0, 0, time.UTC)

// clock is the injected time.
type clock struct {
	mutex sync.Mutex
	at    time.Time
}

func (c *clock) now() time.Time { c.mutex.Lock(); defer c.mutex.Unlock(); return c.at }
func (c *clock) advance(by time.Duration) {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	c.at = c.at.Add(by)
}

// fakeGateway records what the gateway was told and can be made to fail.
type fakeGateway struct {
	mutex    sync.Mutex
	granted  map[string]gateway.Grant
	revoked  map[string]bool
	grants   int
	revokes  int
	fail     error
	access   gateway.Access
	describe error
}

func newFakeGateway() *fakeGateway {
	return &fakeGateway{
		granted: map[string]gateway.Grant{}, revoked: map[string]bool{},
		access: gateway.Access{Restricted: true, AuthOrigin: authOrigin, MostGrant: 24 * time.Hour},
	}
}

func (f *fakeGateway) DescribeAccess(context.Context) (gateway.Access, error) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	return f.access, f.describe
}

func (f *fakeGateway) GrantAccess(_ context.Context, grant gateway.Grant) (time.Duration, error) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	if f.fail != nil {
		return 0, f.fail
	}
	if f.revoked[grant.ID] {
		return 0, &gateway.Refusal{Problem: "grant_revoked", Permanent: true}
	}
	f.grants++
	f.granted[grant.ID] = grant
	return grant.Lifetime, nil
}

func (f *fakeGateway) RevokeAccess(_ context.Context, ids ...string) (gateway.Status, error) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	if f.fail != nil {
		return "", f.fail
	}
	f.revokes++
	for _, id := range ids {
		f.revoked[id] = true
		delete(f.granted, id)
	}
	return gateway.Stored, nil
}

func (f *fakeGateway) holds(grantID string) bool {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	_, held := f.granted[grantID]
	return held
}

func (f *fakeGateway) failWith(err error) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	f.fail = err
}

type fixture struct {
	t       *testing.T
	service *Service
	syncer  *Syncer
	store   *store.Store
	gateway *fakeGateway
	clock   *clock
	path    string
}

func newFixture(t *testing.T, eligibility Eligibility) *fixture {
	t.Helper()
	return fixtureAt(t, filepath.Join(t.TempDir(), "publisher.db"), eligibility, newFakeGateway())
}

func fixtureAt(t *testing.T, path string, eligibility Eligibility, fake *fakeGateway) *fixture {
	t.Helper()
	documents, err := store.Open(path, store.Stamp{ServerID: serverID, Environment: "sandbox",
		GatewayURL: "https://feeds.example.com"})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = documents.Close() })
	moment := &clock{at: start}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	syncer := NewSyncer(SyncPlan{
		Store: documents, Grants: fake, Eligibility: eligibility, Lifetime: 6 * time.Hour,
		Channel: "server/" + serverID, Log: log, Now: moment.now,
		Backoff: func(int) time.Duration { return time.Second },
	})
	service := New(Plan{
		Store: documents, Eligibility: eligibility, Syncer: syncer, Log: log, Now: moment.now,
		Settings: Settings{ServerID: serverID, GatewayURL: "https://feeds.example.com",
			AuthOrigin: authOrigin, GrantLifetime: 6 * time.Hour},
	})
	return &fixture{t: t, service: service, syncer: syncer, store: documents, gateway: fake,
		clock: moment, path: path}
}

// phone is one installation: a wallet and the device key this app generated for the feed.
type phone struct {
	wallet    ed25519.PrivateKey
	address   string
	device    *ecdsa.PrivateKey
	deviceKey []byte
}

func newPhone(t *testing.T) *phone {
	t.Helper()
	_, wallet, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return withWallet(t, wallet)
}

// withWallet is a second device of the same wallet.
func withWallet(t *testing.T, wallet ed25519.PrivateKey) *phone {
	t.Helper()
	device, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&device.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	return &phone{wallet: wallet, address: encode58(wallet.Public().(ed25519.PublicKey)),
		device: device, deviceKey: der}
}

func (p *phone) sign(message []byte) []byte {
	sum := sha256.Sum256(message)
	signature, err := ecdsa.SignASN1(rand.Reader, p.device, sum[:])
	if err != nil {
		panic(err)
	}
	return signature
}

func (f *fixture) challenge(p *phone) *ChallengeAnswer {
	f.t.Helper()
	answer, err := f.service.Challenge(context.Background(), ChallengeRequest{
		Channel: f.service.Channel(), Wallet: p.address, DeviceKey: p.deviceKey, Label: "Pixel of Ana",
	})
	if err != nil {
		f.t.Fatal(err)
	}
	return answer
}

// ask proves the wallet for the phone's device and answers the recorded request.
func (f *fixture) ask(p *phone) *store.AccessDevice {
	f.t.Helper()
	challenge := f.challenge(p)
	message := []byte(challenge.Message)
	device, err := f.service.Request(context.Background(), Answer{
		Attempt:         challenge.Challenge.Attempt,
		WalletSignature: ed25519.Sign(p.wallet, message),
		DeviceSignature: p.sign(message),
	})
	if err != nil {
		f.t.Fatal(err)
	}
	return device
}

func (f *fixture) status(p *phone, requestID string) (*Status, error) {
	at := f.clock.now().UnixMilli()
	return f.service.Status(context.Background(), requestID,
		Signed{AtMillis: at, DeviceSignature: p.sign(StatusStatement(requestID, at))})
}

func (f *fixture) redeem(p *phone, invitation string) (*Session, error) {
	at := f.clock.now().UnixMilli()
	channel := f.service.Channel()
	return f.service.Redeem(context.Background(), Redemption{
		Channel: channel, Invitation: invitation,
		Signed: Signed{AtMillis: at, DeviceSignature: p.sign(RedeemStatement(channel, invitation, at))},
	})
}

func (f *fixture) approve(requestID string) string {
	f.t.Helper()
	device, err := f.service.Approve(context.Background(), requestID, "judge")
	if err != nil {
		f.t.Fatal(err)
	}
	return device.Invitation.Token
}

func (f *fixture) view(requestID string) DeviceView {
	f.t.Helper()
	device, err := f.store.Device(context.Background(), requestID)
	if err != nil {
		f.t.Fatal(err)
	}
	return f.service.View(*device)
}

func problemCode(t *testing.T, err error) string {
	t.Helper()
	var problem *Problem
	if !errors.As(err, &problem) {
		t.Fatalf("expected a refusal, got %v", err)
	}
	return problem.Code
}

// The challenge text is built from fields, and the phone builds the same bytes from the same fields
// (fixtures/restricted-feeds/challenge.json). A wallet signature over the fixture verifies here.
func TestTheChallengeIsTheSharedFixtureByteForByte(t *testing.T) {
	raw, err := os.ReadFile("../../fixtures/restricted-feeds/challenge.json")
	if err != nil {
		t.Fatal(err)
	}
	var pinned map[string]any
	if err := json.Unmarshal(raw, &pinned); err != nil {
		t.Fatal(err)
	}
	text := func(name string) string { return pinned[name].(string) }
	issued, _ := time.Parse(time.RFC3339, text("issued_at"))
	expires, _ := time.Parse(time.RFC3339, text("expires_at"))
	challenge := Challenge{
		AuthOrigin: text("auth_origin"), Channel: text("channel"), Wallet: text("wallet"),
		Installation: text("installation"), Attempt: text("attempt"), Nonce: text("nonce"),
		IssuedAt: issued, ExpiresAt: expires,
	}
	if string(challenge.Message()) != text("message") {
		t.Fatalf("the challenge moved:\n%q\n%q", challenge.Message(), text("message"))
	}
	signature, _ := base64.StdEncoding.DecodeString(text("wallet_signature"))
	if !VerifyWallet(text("wallet"), challenge.Message(), signature) {
		t.Fatal("the fixture's wallet signature does not verify")
	}
	if VerifyWallet(text("wallet"), append(challenge.Message(), ' '), signature) {
		t.Fatal("a signature verified over other bytes")
	}
	at := int64(pinned["status_at"].(float64))
	if string(StatusStatement(text("status_request_id"), at)) != text("status_statement") {
		t.Fatal("the status statement moved")
	}
	at = int64(pinned["redeem_at"].(float64))
	if string(RedeemStatement(text("channel"), text("redeem_invitation"), at)) != text("redeem_statement") {
		t.Fatal("the redeem statement moved")
	}
}

// The whole path a device takes: prove the wallet, wait for the operator, receive the invitation,
// redeem it once, and hold a grant the gateway confirmed.
func TestAnApprovedDeviceRedeemsItsInvitationOnce(t *testing.T) {
	f := newFixture(t, nil)
	p := newPhone(t)
	device := f.ask(p)
	if device.State != store.DevicePending || device.Installation != Installation(p.deviceKey) {
		t.Fatalf("recorded %+v", device)
	}
	status, err := f.status(p, device.ID)
	if err != nil || status.State != store.DevicePending || status.Invitation != nil {
		t.Fatalf("a pending device was told %+v (%v)", status, err)
	}
	if view := f.view(device.ID); view.Access != "pending_approval" || view.Wallet != p.address ||
		view.Label != "Pixel of Ana" {
		t.Fatalf("the operator sees %+v", view)
	}

	token := f.approve(device.ID)
	status, err = f.status(p, device.ID)
	if err != nil || status.State != store.DeviceApproved || status.Invitation == nil ||
		status.Invitation.Token != token || status.Link == "" {
		t.Fatalf("an approved device was told %+v (%v)", status, err)
	}
	session, err := f.redeem(p, token)
	if err != nil {
		t.Fatal(err)
	}
	if len(session.Session) != 43 || !session.Synced || !f.gateway.holds(session.GrantID) {
		t.Fatalf("redeemed %+v; the gateway holds it: %v", session, f.gateway.holds(session.GrantID))
	}
	digest := sha256.Sum256([]byte(session.Session))
	if string(f.gateway.granted[session.GrantID].SessionDigest) != string(digest[:]) {
		t.Fatal("the gateway was not given the session's digest")
	}
	if ref := f.gateway.granted[session.GrantID].SubscriberRef; ref == p.address || ref == "" {
		t.Fatalf("the gateway was given %q as the subscriber", ref)
	}
	if _, err := f.redeem(p, token); problemCode(t, err) != "invitation_used" {
		t.Fatalf("a second redemption answered %v", err)
	}
	if view := f.view(device.ID); view.Access != "connected" || view.Grant.Gateway != "confirmed" {
		t.Fatalf("the operator sees %+v", view)
	}
	status, _ = f.status(p, device.ID)
	if !status.Connected || status.Invitation != nil {
		t.Fatalf("a connected device was told %+v", status)
	}
}

// A challenge is fresh, bound, one-use and expiring, and a signature over anything else — a forged
// one, the wrong wallet's, a device key the wallet did not bind — proves nothing.
func TestForgedReplayedExpiredAndMismatchedProofsAreRefused(t *testing.T) {
	f := newFixture(t, nil)
	p, stranger := newPhone(t), newPhone(t)
	ctx := context.Background()

	// A forged wallet signature spends the challenge: it was the one attempt.
	challenge := f.challenge(p)
	message := []byte(challenge.Message)
	_, err := f.service.Request(ctx, Answer{Attempt: challenge.Challenge.Attempt,
		WalletSignature: ed25519.Sign(stranger.wallet, message), DeviceSignature: p.sign(message)})
	if problemCode(t, err) != "bad_signature" {
		t.Fatal(err)
	}
	_, err = f.service.Request(ctx, Answer{Attempt: challenge.Challenge.Attempt,
		WalletSignature: ed25519.Sign(p.wallet, message), DeviceSignature: p.sign(message)})
	if problemCode(t, err) != "challenge_used" {
		t.Fatalf("a spent challenge answered %v", err)
	}

	// The device signature must come from the key the challenge bound.
	challenge = f.challenge(p)
	message = []byte(challenge.Message)
	_, err = f.service.Request(ctx, Answer{Attempt: challenge.Challenge.Attempt,
		WalletSignature: ed25519.Sign(p.wallet, message), DeviceSignature: stranger.sign(message)})
	if problemCode(t, err) != "bad_signature" {
		t.Fatal(err)
	}

	// A signature over another challenge's text is not an answer to this one.
	first, second := f.challenge(p), f.challenge(p)
	old := []byte(first.Message)
	_, err = f.service.Request(ctx, Answer{Attempt: second.Challenge.Attempt,
		WalletSignature: ed25519.Sign(p.wallet, old), DeviceSignature: p.sign(old)})
	if problemCode(t, err) != "bad_signature" {
		t.Fatal(err)
	}

	// A correct answer, replayed.
	challenge = f.challenge(p)
	message = []byte(challenge.Message)
	answer := Answer{Attempt: challenge.Challenge.Attempt,
		WalletSignature: ed25519.Sign(p.wallet, message), DeviceSignature: p.sign(message)}
	if _, err := f.service.Request(ctx, answer); err != nil {
		t.Fatal(err)
	}
	if _, err := f.service.Request(ctx, answer); problemCode(t, err) != "challenge_used" {
		t.Fatalf("a replayed answer answered %v", err)
	}

	// An expired one.
	challenge = f.challenge(stranger)
	f.clock.advance(DefaultChallengeLifetime + time.Second)
	message = []byte(challenge.Message)
	_, err = f.service.Request(ctx, Answer{Attempt: challenge.Challenge.Attempt,
		WalletSignature: ed25519.Sign(stranger.wallet, message), DeviceSignature: stranger.sign(message)})
	if problemCode(t, err) != "challenge_expired" {
		t.Fatal(err)
	}

	// Another feed, an address that is not a wallet, and a key that is not P-256.
	_, err = f.service.Challenge(ctx, ChallengeRequest{Channel: otherFeed, Wallet: p.address,
		DeviceKey: p.deviceKey, Label: "x"})
	if problemCode(t, err) != "unknown_feed" {
		t.Fatal(err)
	}
	_, err = f.service.Challenge(ctx, ChallengeRequest{Channel: f.service.Channel(),
		Wallet: "0OIl-not-base58", DeviceKey: p.deviceKey, Label: "x"})
	if problemCode(t, err) != "bad_wallet" {
		t.Fatal(err)
	}
	_, err = f.service.Challenge(ctx, ChallengeRequest{Channel: f.service.Channel(),
		Wallet: p.address, DeviceKey: []byte("not a key"), Label: "x"})
	if problemCode(t, err) != "bad_device_key" {
		t.Fatal(err)
	}
}

// Only the device holding the key can ask about its request or redeem its invitation, and a copied
// invitation — to another wallet's phone or another installation — activates nothing and is not
// spent by trying.
func TestAnInvitationIsBoundToItsDevice(t *testing.T) {
	f := newFixture(t, nil)
	p := newPhone(t)
	sameWalletOtherPhone := withWallet(t, p.wallet)
	stranger := newPhone(t)
	device := f.ask(p)
	token := f.approve(device.ID)

	if _, err := f.status(stranger, device.ID); problemCode(t, err) != "unknown_request" {
		t.Fatalf("another key read the decision: %v", err)
	}
	for _, other := range []*phone{sameWalletOtherPhone, stranger} {
		if _, err := f.redeem(other, token); problemCode(t, err) != "unknown_invitation" {
			t.Fatalf("a copied invitation answered %v", err)
		}
	}
	// A signed moment far from now is not accepted either.
	at := f.clock.now().Add(-time.Hour).UnixMilli()
	_, err := f.service.Status(context.Background(), device.ID,
		Signed{AtMillis: at, DeviceSignature: p.sign(StatusStatement(device.ID, at))})
	if problemCode(t, err) != "stale_proof" {
		t.Fatal(err)
	}
	if _, err := f.redeem(p, token); err != nil {
		t.Fatalf("the device the invitation is for could not redeem it after the attempts: %v", err)
	}
}

// An invitation runs out, a reissue supersedes it, and a reissue is only for a device that is still
// approved.
func TestExpiredAndSupersededInvitationsAreRefusedAndReissueNeedsApproval(t *testing.T) {
	f := newFixture(t, nil)
	p := newPhone(t)
	device := f.ask(p)
	if _, err := f.service.Reissue(context.Background(), device.ID); problemCode(t, err) != "wrong_state" {
		t.Fatalf("a pending device was given an invitation: %v", err)
	}
	first := f.approve(device.ID)
	f.clock.advance(DefaultInvitationLifetime + time.Second)
	if _, err := f.redeem(p, first); problemCode(t, err) != "invitation_expired" {
		t.Fatal(err)
	}
	if view := f.view(device.ID); view.Access != "invitation_expired" {
		t.Fatalf("the operator sees %q", view.Access)
	}
	reissued, err := f.service.Reissue(context.Background(), device.ID)
	if err != nil {
		t.Fatal(err)
	}
	second := reissued.Invitation.Token
	// A second reissue supersedes the second invitation too.
	third, err := f.service.Reissue(context.Background(), device.ID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.redeem(p, second); problemCode(t, err) != "invitation_superseded" {
		t.Fatal(err)
	}
	if _, err := f.redeem(p, third.Invitation.Token); err != nil {
		t.Fatal(err)
	}
	if err := f.service.Revoke(context.Background(), device.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.service.Reissue(context.Background(), device.ID); problemCode(t, err) != "wrong_state" {
		t.Fatalf("a revoked device was given an invitation: %v", err)
	}
}

// An approval revoked before the invitation is redeemed carries nothing, and a rejected device
// receives no invitation at all.
func TestApprovalRevokedBeforeRedemptionAndRejectionGrantNothing(t *testing.T) {
	f := newFixture(t, nil)
	p, q := newPhone(t), newPhone(t)
	device := f.ask(p)
	token := f.approve(device.ID)
	if err := f.service.Revoke(context.Background(), device.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.redeem(p, token); err == nil {
		t.Fatal("an invitation was redeemed after its approval was revoked")
	}

	rejected := f.ask(q)
	decided, err := f.service.Reject(context.Background(), rejected.ID, "judge")
	if err != nil || decided.State != store.DeviceRejected || decided.Invitation != nil {
		t.Fatalf("rejected %+v (%v)", decided, err)
	}
	status, err := f.status(q, rejected.ID)
	if err != nil || status.State != store.DeviceRejected || status.Invitation != nil {
		t.Fatalf("a rejected device was told %+v (%v)", status, err)
	}
	if f.gateway.grants != 0 {
		t.Fatalf("the gateway was granted %d grants", f.gateway.grants)
	}
	// A decided request cannot be decided again.
	if _, err := f.service.Approve(context.Background(), rejected.ID, "judge"); problemCode(t, err) != "wrong_state" {
		t.Fatal(err)
	}
}

// Of many simultaneous redemptions of one invitation, exactly one succeeds.
func TestConcurrentRedemptionSucceedsAtMostOnce(t *testing.T) {
	f := newFixture(t, nil)
	p := newPhone(t)
	device := f.ask(p)
	token := f.approve(device.ID)
	var (
		succeeded atomic.Int32
		group     sync.WaitGroup
	)
	for range 16 {
		group.Add(1)
		go func() {
			defer group.Done()
			if _, err := f.redeem(p, token); err == nil {
				succeeded.Add(1)
			}
		}()
	}
	group.Wait()
	if succeeded.Load() != 1 {
		t.Fatalf("%d redemptions succeeded", succeeded.Load())
	}
}

// A second device of the same wallet is its own request with its own decision; revoking one leaves
// the other, and revoking the wallet revokes both.
func TestEveryDeviceNeedsItsOwnApprovalAndWalletRevocationEndsAll(t *testing.T) {
	f := newFixture(t, nil)
	first := newPhone(t)
	second := withWallet(t, first.wallet)
	one, two := f.ask(first), f.ask(second)
	if one.ID == two.ID || one.SubscriberRef != two.SubscriberRef {
		t.Fatalf("two devices of one wallet: %+v %+v", one, two)
	}
	f.approve(one.ID)
	if view := f.view(two.ID); view.Access != "pending_approval" {
		t.Fatalf("approving one device changed the other: %q", view.Access)
	}
	// Asking again with the same device key is the same request, not a second one.
	if again := f.ask(first); again.ID != one.ID {
		t.Fatal("the same device key made a second request")
	}

	sessionOne, err := f.redeem(first, f.view(one.ID).invitationToken(t, f))
	if err != nil {
		t.Fatal(err)
	}
	twoToken := f.approve(two.ID)
	sessionTwo, err := f.redeem(second, twoToken)
	if err != nil {
		t.Fatal(err)
	}
	if err := f.service.Revoke(context.Background(), one.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.syncer.Pass(context.Background()); err != nil {
		t.Fatal(err)
	}
	if f.gateway.holds(sessionOne.GrantID) || !f.gateway.holds(sessionTwo.GrantID) {
		t.Fatal("revoking one device did not revoke exactly that device")
	}

	third := withWallet(t, first.wallet)
	pending := f.ask(third)
	revoked, err := f.service.RevokeWallet(context.Background(), first.address)
	if err != nil || revoked != 2 {
		t.Fatalf("revoked %d (%v)", revoked, err)
	}
	if _, err := f.syncer.Pass(context.Background()); err != nil {
		t.Fatal(err)
	}
	if f.gateway.holds(sessionTwo.GrantID) {
		t.Fatal("wallet-wide revocation left a device's grant at the gateway")
	}
	if view := f.view(pending.ID); view.State != store.DeviceRevoked {
		t.Fatalf("a pending device of the revoked wallet is %q", view.State)
	}
}

// invitationToken reads a device's live invitation out of the store.
func (v DeviceView) invitationToken(t *testing.T, f *fixture) string {
	t.Helper()
	device, err := f.store.Device(context.Background(), v.RequestID)
	if err != nil || device.Invitation == nil {
		t.Fatalf("no invitation for %s (%v)", v.RequestID, err)
	}
	return device.Invitation.Token
}

// A gateway that cannot be reached is shown honestly: a grant is pending, a revocation is pending
// with its error and attempt count, and neither is reported as done until the gateway confirms.
func TestFailedGatewayDeliveryIsVisibleAndRetriedUntilConfirmed(t *testing.T) {
	f := newFixture(t, nil)
	p := newPhone(t)
	device := f.ask(p)
	token := f.approve(device.ID)
	f.gateway.failWith(&gateway.Refusal{Problem: "unreachable", Detail: "dial tcp: refused"})
	session, err := f.redeem(p, token)
	if err != nil {
		t.Fatal(err)
	}
	if session.Synced {
		t.Fatal("a grant the gateway never received was reported as synced")
	}
	if view := f.view(device.ID); view.Access != "grant_pending" || view.Grant.Error == "" ||
		view.Grant.Attempts != 1 {
		t.Fatalf("the operator sees %+v", view.Grant)
	}
	f.gateway.failWith(nil)
	f.clock.advance(2 * time.Second)
	if confirmed, err := f.syncer.Pass(context.Background()); err != nil || confirmed != 1 {
		t.Fatalf("confirmed %d (%v)", confirmed, err)
	}

	f.gateway.failWith(&gateway.Refusal{Problem: "unavailable", Detail: "503"})
	if err := f.service.Revoke(context.Background(), device.ID); err != nil {
		t.Fatal(err)
	}
	for range 3 {
		f.clock.advance(2 * time.Second)
		if _, err := f.syncer.Pass(context.Background()); err != nil {
			t.Fatal(err)
		}
	}
	view := f.view(device.ID)
	if view.Access != "revocation_pending" || view.Grant.Attempts != 3 || view.Grant.Error == "" {
		t.Fatalf("an unconfirmed revocation is shown as %+v", view)
	}
	if !f.gateway.holds(session.GrantID) {
		t.Fatal("the test's gateway lost the grant")
	}
	f.gateway.failWith(nil)
	f.clock.advance(2 * time.Second)
	if _, err := f.syncer.Pass(context.Background()); err != nil {
		t.Fatal(err)
	}
	if view := f.view(device.ID); view.Access != "revoked" || f.gateway.holds(session.GrantID) {
		t.Fatalf("after the gateway recovered: %q, still held %v", view.Access, f.gateway.holds(session.GrantID))
	}
}

// A grant is renewed while the device stays approved and never after it is revoked, so the offline
// bound holds: without renewal a grant runs out one lifetime after it was last confirmed.
func TestGrantsAreRenewedOnlyWhileApproved(t *testing.T) {
	f := newFixture(t, nil)
	p := newPhone(t)
	device := f.ask(p)
	session, err := f.redeem(p, f.approve(device.ID))
	if err != nil {
		t.Fatal(err)
	}
	firstGrants := f.gateway.grants
	f.clock.advance(4*time.Hour + time.Minute) // less than a third of six hours left
	if _, err := f.syncer.Pass(context.Background()); err != nil {
		t.Fatal(err)
	}
	if f.gateway.grants != firstGrants+1 {
		t.Fatalf("the grant was sent %d times", f.gateway.grants)
	}
	renewed, _ := f.store.Device(context.Background(), device.ID)
	if !renewed.Grant.ExpiresAt.Equal(f.clock.now().Add(6 * time.Hour)) {
		t.Fatalf("renewed until %v", renewed.Grant.ExpiresAt)
	}
	if err := f.service.Revoke(context.Background(), device.ID); err != nil {
		t.Fatal(err)
	}
	f.clock.advance(5 * time.Hour)
	if _, err := f.syncer.Pass(context.Background()); err != nil {
		t.Fatal(err)
	}
	if f.gateway.holds(session.GrantID) || f.gateway.grants != firstGrants+1 {
		t.Fatalf("a revoked grant was renewed: %d grants", f.gateway.grants)
	}
}

// The eligibility hook: a rule that decides at once approves or rejects without the operator, and a
// rule that stops admitting a device revokes it at redemption and at renewal.
type rule struct {
	mutex    sync.Mutex
	decision Decision
}

func (r *rule) Decide(context.Context, Subject) (Decision, error) {
	r.mutex.Lock()
	defer r.mutex.Unlock()
	return r.decision, nil
}

func (r *rule) set(decision Decision) { r.mutex.Lock(); r.decision = decision; r.mutex.Unlock() }

func TestAnEligibilityRuleDecidesInsteadOfTheOperator(t *testing.T) {
	entitled := &rule{decision: Eligible}
	f := newFixture(t, entitled)
	p := newPhone(t)
	device := f.ask(p)
	if device.State != store.DeviceApproved || device.Invitation == nil || device.DecidedBy != "eligibility rule" {
		t.Fatalf("an eligible wallet was recorded as %+v", device)
	}
	session, err := f.redeem(p, device.Invitation.Token)
	if err != nil {
		t.Fatal(err)
	}
	// The subscription lapses: the next renewal revokes the device.
	entitled.set(Ineligible)
	f.clock.advance(4*time.Hour + time.Minute)
	if _, err := f.syncer.Pass(context.Background()); err != nil {
		t.Fatal(err)
	}
	if _, err := f.syncer.Pass(context.Background()); err != nil {
		t.Fatal(err)
	}
	if view := f.view(device.ID); view.State != store.DeviceRevoked || f.gateway.holds(session.GrantID) {
		t.Fatalf("an ineligible device kept access: %+v", view)
	}

	q := newPhone(t)
	if rejected := f.ask(q); rejected.State != store.DeviceRejected || rejected.Invitation != nil {
		t.Fatalf("an ineligible wallet was recorded as %+v", rejected)
	}

	// Eligible when approved, ineligible by the time it redeems.
	entitled.set(Eligible)
	r := newPhone(t)
	late := f.ask(r)
	entitled.set(Ineligible)
	if _, err := f.redeem(r, late.Invitation.Token); problemCode(t, err) != "not_approved" {
		t.Fatalf("an ineligible device redeemed: %v", err)
	}
}

// The publication guard: a restricted publisher publishes nothing until the gateway says it enforces
// the feed as restricted, at this origin.
func TestThePublicationGuardFailsClosed(t *testing.T) {
	fake := newFakeGateway()
	moment := &clock{at: start}
	guard := NewGuard(fake, authOrigin, moment.now)
	if refusal := guard.Check(context.Background()); refusal != nil {
		t.Fatalf("a confirmed gateway was refused: %v", refusal)
	}
	for name, change := range map[string]func(){
		"public":         func() { fake.access = gateway.Access{} },
		"another origin": func() { fake.access = gateway.Access{Restricted: true, AuthOrigin: "https://x.example.com"} },
		"old gateway": func() {
			fake.describe = &gateway.Refusal{Problem: "unimplemented", Permanent: true}
		},
	} {
		fake.access = gateway.Access{Restricted: true, AuthOrigin: authOrigin}
		fake.describe = nil
		change()
		moment.advance(2 * time.Minute)
		refusal := guard.Check(context.Background())
		if refusal == nil || refusal.Permanent || refusal.Problem != "access_unconfirmed" {
			t.Fatalf("%s: the guard answered %v", name, refusal)
		}
	}
}

// Decisions are in the file: a restart keeps every device's state, its invitation and its grants.
func TestAccessDecisionsSurviveARestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "publisher.db")
	fake := newFakeGateway()
	f := fixtureAt(t, path, nil, fake)
	approved, pending, revoked := newPhone(t), newPhone(t), newPhone(t)
	one, two, three := f.ask(approved), f.ask(pending), f.ask(revoked)
	f.redeem(approved, f.approve(one.ID))
	f.approve(three.ID)
	if err := f.service.Revoke(context.Background(), three.ID); err != nil {
		t.Fatal(err)
	}
	_ = f.store.Close()

	g := fixtureAt(t, path, nil, fake)
	for id, expected := range map[string]string{one.ID: "connected", two.ID: "pending_approval", three.ID: "revoked"} {
		if view := g.view(id); view.Access != expected {
			t.Fatalf("%s is %q after a restart, expected %q", id, view.Access, expected)
		}
	}
}
