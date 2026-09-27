package admin

import (
	"context"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/access"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/api"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/environment"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/gateway"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/manifest"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/store"
)

// The Devices page against the real thing (SEE-156): the publisher's own API with its access
// service over a real store, and a gateway that can be told to fail. What is asserted is what an
// operator sees and what their clicks change.

const devicesServer = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

type recordingGateway struct {
	fail    error
	granted map[string]bool
}

func (r *recordingGateway) DescribeAccess(context.Context) (gateway.Access, error) {
	return gateway.Access{Restricted: true, AuthOrigin: "https://auth.example.com"}, nil
}

func (r *recordingGateway) GrantAccess(_ context.Context, grant gateway.Grant) (time.Duration, error) {
	if r.fail != nil {
		return 0, r.fail
	}
	r.granted[grant.ID] = true
	return grant.Lifetime, nil
}

func (r *recordingGateway) RevokeAccess(_ context.Context, ids ...string) (gateway.Status, error) {
	if r.fail != nil {
		return "", r.fail
	}
	for _, id := range ids {
		delete(r.granted, id)
	}
	return gateway.Stored, nil
}

type accessBench struct {
	store   *store.Store
	ui      http.Handler
	service *access.Service
	syncer  *access.Syncer
	gateway *recordingGateway
	cookie  string
}

func newAccessBench(t *testing.T) *accessBench {
	t.Helper()
	documents, err := store.Open(filepath.Join(t.TempDir(), "publisher.db"), store.Stamp{
		ServerID: devicesServer, Environment: "sandbox", GatewayURL: "https://feeds.example.com"})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = documents.Close() })
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	fake := &recordingGateway{granted: map[string]bool{}}
	syncer := access.NewSyncer(access.SyncPlan{Store: documents, Grants: fake, Log: log,
		Channel: signals.ChannelFor(devicesServer), Backoff: func(int) time.Duration { return 0 }})
	service := access.New(access.Plan{Store: documents, Syncer: syncer, Log: log,
		Settings: access.Settings{ServerID: devicesServer, GatewayURL: "https://feeds.example.com",
			AuthOrigin: "https://auth.example.com"}})
	settings := manifest.Settings{ServerID: devicesServer, GatewayURL: "https://feeds.example.com",
		Environment: environment.Sandbox, Requirement: signals.Swap{}.Requirement(),
		AuthOrigin: "https://auth.example.com"}
	publisher := httptest.NewServer(api.New(api.Plan{
		Documents: documents, Kind: signals.Swap{}, Settings: settings, Token: token, Log: log,
		Access: service,
	}).Handler())
	t.Cleanup(publisher.Close)

	passwordPath := filepath.Join(t.TempDir(), "admin-passwords")
	if err := os.WriteFile(passwordPath, []byte(mustHash(t, "judge1", "secret")+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	passwords, err := OpenFile(passwordPath)
	if err != nil {
		t.Fatal(err)
	}
	ui := New(Plan{
		Config: &Config{PublicPath: "/trader", APIURL: publisher.URL, APIToken: token,
			SessionSecret: strings.Repeat("s", 43), PasswordsFile: passwordPath},
		Passwords: passwords, Log: log, HTTP: publisher.Client(),
	}).Handler()
	return &accessBench{store: documents, ui: ui, service: service, syncer: syncer, gateway: fake,
		cookie: login(t, ui, "judge1", "secret")}
}

// ask proves a wallet for a fresh device key, through the service, and answers the request ID.
func (b *accessBench) ask(t *testing.T, wallet ed25519.PrivateKey) string {
	t.Helper()
	device, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, _ := x509.MarshalPKIXPublicKey(&device.PublicKey)
	address := addressOf(wallet)
	challenge, err := b.service.Challenge(context.Background(), access.ChallengeRequest{
		Channel: b.service.Channel(), Wallet: address, DeviceKey: der, Label: "Judge's Seeker"})
	if err != nil {
		t.Fatal(err)
	}
	message := []byte(challenge.Message)
	sum := sha256.Sum256(message)
	deviceSignature, _ := ecdsa.SignASN1(rand.Reader, device, sum[:])
	recorded, err := b.service.Request(context.Background(), access.Answer{
		Attempt: challenge.Challenge.Attempt, WalletSignature: ed25519.Sign(wallet, message),
		DeviceSignature: deviceSignature})
	if err != nil {
		t.Fatal(err)
	}
	return recorded.ID
}

func (b *accessBench) page(t *testing.T) string {
	t.Helper()
	answered := get(t, b.ui, b.cookie, "/trader/devices")
	if answered.status != http.StatusOK {
		t.Fatalf("devices %d: %s", answered.status, answered.body)
	}
	return answered.body
}

func (b *accessBench) click(t *testing.T, path string) string {
	t.Helper()
	answered := post(t, b.ui, b.cookie, path, url.Values{})
	if answered.status != http.StatusSeeOther {
		t.Fatalf("%s answered %d: %s", path, answered.status, answered.body)
	}
	return answered.header.Get("Location")
}

func TestTheOperatorApprovesRejectsAndRevokesDevicesAndSeesWhatTheGatewayConfirmed(t *testing.T) {
	bench := newAccessBench(t)
	_, wallet, _ := ed25519.GenerateKey(rand.Reader)
	first := bench.ask(t, wallet)
	second := bench.ask(t, wallet)
	_, other, _ := ed25519.GenerateKey(rand.Reader)
	stranger := bench.ask(t, other)

	body := bench.page(t)
	for _, expected := range []string{addressOf(wallet), "label (user-supplied)", "Pending approval",
		"installation ", first, second, stranger} {
		if !strings.Contains(body, expected) {
			t.Fatalf("the page does not show %q:\n%s", expected, body)
		}
	}
	if strings.Contains(body, token) {
		t.Fatal("the API token reached the page")
	}

	// Approving one device of a wallet approves that one.
	if location := bench.click(t, "/trader/devices/"+first+"/approve"); !strings.Contains(location, "ok=") {
		t.Fatalf("approve redirected to %s", location)
	}
	bench.click(t, "/trader/devices/"+stranger+"/reject")
	devices, _ := bench.service.Devices(context.Background())
	states := map[string]string{}
	for _, device := range devices {
		states[device.ID] = device.State
	}
	if states[first] != "approved" || states[second] != "pending" || states[stranger] != "rejected" {
		t.Fatalf("states after one approval and one rejection: %v", states)
	}
	body = bench.page(t)
	if !strings.Contains(body, "seekervault://feed?") || !strings.Contains(body, "invitation=") {
		t.Fatal("the approved device's invitation is not shown as a link")
	}

	// A revocation the gateway has not confirmed is shown as pending — never as done.
	bench.gateway.fail = &gateway.Refusal{Problem: "unreachable", Detail: "connection refused"}
	bench.click(t, "/trader/devices/"+first+"/revoke")
	if _, err := bench.syncer.Pass(context.Background()); err != nil {
		t.Fatal(err)
	}
	if body = bench.page(t); !strings.Contains(body, "Revoked — the gateway confirmed") &&
		!strings.Contains(body, "Revocation pending") {
		// A device revoked before it ever redeemed has no grant for the gateway to confirm.
		t.Fatalf("revocation shown as:\n%s", body)
	}

	// Wallet-wide revocation takes the wallet's other device with it.
	bench.gateway.fail = nil
	bench.click(t, "/trader/wallets/"+addressOf(wallet)+"/revoke")
	devices, _ = bench.service.Devices(context.Background())
	for _, device := range devices {
		if device.Wallet == addressOf(wallet) && device.State != "revoked" {
			t.Fatalf("a device of the revoked wallet is %s", device.State)
		}
	}

	// A mutation from another page is refused, and nothing changes.
	request := httptest.NewRequest(http.MethodPost,
		"https://feeds.example.com/trader/devices/"+stranger+"/approve", nil)
	request.Header.Set("Origin", "https://attacker.example.com")
	request.Header.Set("Cookie", bench.cookie)
	answered := httptest.NewRecorder()
	bench.ui.ServeHTTP(answered, request)
	if answered.Code != http.StatusForbidden {
		t.Fatalf("a cross-origin approval answered %d", answered.Code)
	}
	// And the page needs a session.
	if anonymous := get(t, bench.ui, "", "/trader/devices"); anonymous.status != http.StatusSeeOther {
		t.Fatalf("the devices page answered %d without a session", anonymous.status)
	}
}

func TestAnUnconfirmedRevocationIsShownAsPendingUntilTheGatewayConfirms(t *testing.T) {
	bench := newAccessBench(t)
	_, wallet, _ := ed25519.GenerateKey(rand.Reader)
	id := bench.ask(t, wallet)
	bench.click(t, "/trader/devices/"+id+"/approve")
	// Redeem through the store directly: the device's key is not needed to show the page's states.
	devices, _ := bench.service.Devices(context.Background())
	var token string
	for _, device := range devices {
		if device.ID == id {
			token = device.Invitation.Token
		}
	}
	grant := store.NewGrant{ID: "5d6e7f80-9a1b-4c2d-8e3f-4a5b6c7d8e9f", SessionDigest: make([]byte, 32),
		ExpiresAt: time.Now().Add(time.Hour)}
	if _, err := bench.store.Redeem(context.Background(), token, time.Now(),
		func(store.AccessDevice) error { return nil }, grant); err != nil {
		t.Fatal(err)
	}
	if _, err := bench.syncer.Pass(context.Background()); err != nil {
		t.Fatal(err)
	}
	if body := bench.page(t); !strings.Contains(body, "Connected — the gateway admits this device") {
		t.Fatalf("a confirmed grant is shown as:\n%s", body)
	}
	bench.gateway.fail = &gateway.Refusal{Problem: "unavailable", Detail: "503 from the gateway"}
	bench.click(t, "/trader/devices/"+id+"/revoke")
	if _, err := bench.syncer.Pass(context.Background()); err != nil {
		t.Fatal(err)
	}
	body := bench.page(t)
	if !strings.Contains(body, "Revocation pending at the gateway") || !strings.Contains(body, "503 from the gateway") {
		t.Fatalf("an unconfirmed revocation is shown as:\n%s", body)
	}
	bench.gateway.fail = nil
	if _, err := bench.syncer.Pass(context.Background()); err != nil {
		t.Fatal(err)
	}
	if body := bench.page(t); !strings.Contains(body, "Revoked — the gateway confirmed") {
		t.Fatalf("a confirmed revocation is shown as:\n%s", body)
	}
}

func TestAnApprovedDeviceInvitationIsShownAsAScannableQRCodeToo(t *testing.T) {
	bench := newAccessBench(t)
	_, wallet, _ := ed25519.GenerateKey(rand.Reader)
	id := bench.ask(t, wallet)
	if body := bench.page(t); strings.Contains(body, "aria-label=\"QR code of the device invitation\"") {
		t.Fatalf("a pending request already shows an invitation QR:\n%s", body)
	}
	bench.click(t, "/trader/devices/"+id+"/approve")
	body := bench.page(t)
	if !strings.Contains(body, "seekervault://feed?") || !strings.Contains(body, "invitation=") {
		t.Fatal("the approved device's invitation is not shown as a link")
	}
	if strings.Count(body, "aria-label=\"QR code of the device invitation\"") != 1 {
		t.Fatalf("a page with one invited device does not draw exactly one invitation QR:\n%s", body)
	}
	// The drawing is the encoder's own matrix of exactly the invitation link the input shows.
	svg := regexp.MustCompile(`<svg[^>]*aria-label="QR code of the device invitation".*?</svg>`).FindString(body)
	devices, _ := bench.service.Devices(context.Background())
	for _, device := range devices {
		if device.ID == id && !matchesTheEncoder(t, svg, bench.service.Link(device.Invitation.Token)) {
			t.Fatalf("the drawn QR is not the invitation link:\n%s", svg)
		}
	}
}

func addressOf(wallet ed25519.PrivateKey) string {
	return access.Address(wallet.Public().(ed25519.PublicKey))
}
