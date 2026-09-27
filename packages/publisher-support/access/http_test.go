package access

import (
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// The wire, as a phone and the operator's page speak it: the same path as the tests above, through
// the real handlers and JSON.
func TestTheAuthenticationEndpointOverHTTP(t *testing.T) {
	f := newFixture(t, nil)
	public := httptest.NewServer(f.service.Handler(Limits{}))
	defer public.Close()
	mux := http.NewServeMux()
	for route, handler := range f.service.AdminRoutes() {
		mux.HandleFunc(route, handler)
	}
	admin := httptest.NewServer(mux)
	defer admin.Close()
	p := newPhone(t)
	b64 := base64.RawURLEncoding.EncodeToString

	call := func(server *httptest.Server, path string, body any) (int, map[string]any) {
		t.Helper()
		raw, _ := json.Marshal(body)
		response, err := http.Post(server.URL+path, "application/json", bytes.NewReader(raw))
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		var answer map[string]any
		_ = json.NewDecoder(response.Body).Decode(&answer)
		return response.StatusCode, answer
	}

	status, challenge := call(public, "/access/v1/challenges", map[string]string{
		"feed": f.service.Channel(), "wallet": p.address, "device_key": b64(p.deviceKey),
		"label": "Pixel of Ana",
	})
	if status != http.StatusCreated || challenge["auth_origin"] != authOrigin ||
		challenge["installation"] != Installation(p.deviceKey) {
		t.Fatalf("challenge answered %d %v", status, challenge)
	}
	message := []byte(challenge["message"].(string))
	status, requested := call(public, "/access/v1/requests", map[string]string{
		"attempt":          challenge["attempt"].(string),
		"wallet_signature": base64.StdEncoding.EncodeToString(ed25519.Sign(p.wallet, message)),
		"device_signature": b64(p.sign(message)),
	})
	if status != http.StatusCreated || requested["state"] != "pending" {
		t.Fatalf("request answered %d %v", status, requested)
	}
	requestID := requested["request_id"].(string)

	at := f.clock.now().UnixMilli()
	status, pending := call(public, "/access/v1/requests/"+requestID+"/status", map[string]any{
		"at": at, "device_signature": b64(p.sign(StatusStatement(requestID, at))),
	})
	if status != http.StatusOK || pending["state"] != "pending" || pending["invitation"] != nil {
		t.Fatalf("status answered %d %v", status, pending)
	}

	status, listed := call(admin, "/v1/access/devices/"+requestID+"/approve", map[string]string{"by": "judge"})
	if status != http.StatusOK || listed["access"] != "invited" {
		t.Fatalf("approve answered %d %v", status, listed)
	}
	status, approved := call(public, "/access/v1/requests/"+requestID+"/status", map[string]any{
		"at": at, "device_signature": b64(p.sign(StatusStatement(requestID, at))),
	})
	invitation, _ := approved["invitation"].(map[string]any)
	if status != http.StatusOK || invitation == nil ||
		!strings.Contains(invitation["link"].(string), "invitation=") {
		t.Fatalf("an approved status answered %d %v", status, approved)
	}
	token := invitation["token"].(string)
	status, redeemed := call(public, "/access/v1/redeem", map[string]any{
		"feed": f.service.Channel(), "invitation": token, "at": at,
		"device_signature": b64(p.sign(RedeemStatement(f.service.Channel(), token, at))),
	})
	if status != http.StatusOK || redeemed["gateway"] != "synced" || len(redeemed["session"].(string)) != 43 {
		t.Fatalf("redeem answered %d %v", status, redeemed)
	}
	status, again := call(public, "/access/v1/redeem", map[string]any{
		"feed": f.service.Channel(), "invitation": token, "at": at,
		"device_signature": b64(p.sign(RedeemStatement(f.service.Channel(), token, at))),
	})
	if status != http.StatusConflict || again["error"] != "invitation_used" {
		t.Fatalf("a second redemption answered %d %v", status, again)
	}

	// A field the contract does not have is refused, not dropped.
	status, refused := call(public, "/access/v1/challenges", map[string]string{
		"feed": f.service.Channel(), "wallet": p.address, "device_key": b64(p.deviceKey),
		"label": "x", "password": "hunter2",
	})
	if status != http.StatusBadRequest || refused["error"] != "bad_request" {
		t.Fatalf("an unknown field answered %d %v", status, refused)
	}

	response, err := http.Get(admin.URL + "/v1/access/devices")
	if err != nil {
		t.Fatal(err)
	}
	var devices struct {
		Devices []DeviceView `json:"devices"`
	}
	_ = json.NewDecoder(response.Body).Decode(&devices)
	_ = response.Body.Close()
	if len(devices.Devices) != 1 || devices.Devices[0].Access != "connected" ||
		devices.Devices[0].Grant.Gateway != "confirmed" {
		t.Fatalf("the operator lists %+v", devices.Devices)
	}
	status, wallet := call(admin, "/v1/access/wallets/"+p.address+"/revoke", map[string]string{})
	if status != http.StatusOK || wallet["revoked"] != float64(1) {
		t.Fatalf("wallet revocation answered %d %v", status, wallet)
	}
}
