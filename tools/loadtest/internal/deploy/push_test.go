package deploy

import (
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"net/http"
	"net/url"
	"os"
	"strings"
	"testing"
)

// The controlled push stand-in (SEE-99).
//
// Two things are being checked, and the first matters more than it looks: the credential this writes
// has to be one the **gateway** accepts, or the relay refuses to start and the whole push leg of a
// run is silently absent. The gateway's rules are in `feed-gateway/internal/relay/token.go` and cannot
// be imported from here, so they are restated — and the end-to-end proof that the restatement is
// right is that `mixed` runs with the relay on and hints arrive.

func TestTheCredentialIsOneTheGatewayWillAccept(t *testing.T) {
	push := standIn(t)
	raw, err := os.ReadFile(push.CredentialPath)
	if err != nil {
		t.Fatal(err)
	}
	var credential struct {
		Type     string `json:"type"`
		Project  string `json:"project_id"`
		Email    string `json:"client_email"`
		Key      string `json:"private_key"`
		TokenURI string `json:"token_uri"`
		ClientID string `json:"client_id"`
	}
	if err := json.Unmarshal(raw, &credential); err != nil {
		t.Fatal(err)
	}
	if credential.Type != "service_account" {
		t.Errorf(`"type" is %q`, credential.Type)
	}
	for _, one := range []struct {
		field string
		value string
	}{
		{"project_id", credential.Project},
		{"client_email", credential.Email},
		{"private_key", credential.Key},
		{"token_uri", credential.TokenURI},
	} {
		if strings.TrimSpace(one.value) == "" {
			t.Errorf("%q is empty, and the gateway refuses to start without it", one.field)
		}
	}
	// PKCS#8, which is what the relay parses. A PKCS#1 key is refused by name.
	block, _ := pem.Decode([]byte(credential.Key))
	if block == nil {
		t.Fatal("the key is not PEM")
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		t.Fatalf("the key is not PKCS#8: %v", err)
	}
	if _, ok := parsed.(*rsa.PrivateKey); !ok {
		t.Fatalf("the key is a %T, and the relay signs RS256", parsed)
	}
	// Loopback HTTP, which is the one exception the gateway makes and the reason this stand-in can
	// exist at all: a development endpoint on the machine whose operator wrote the file.
	where, err := url.Parse(credential.TokenURI)
	if err != nil {
		t.Fatal(err)
	}
	if where.Scheme != "http" || where.Hostname() != "127.0.0.1" {
		t.Errorf("the token endpoint is %q, which the gateway would refuse", credential.TokenURI)
	}
	// And nothing in it is a real project or a real address.
	if !strings.HasSuffix(credential.Email, ".invalid") {
		t.Errorf("the account address is %q, which is not obviously nobody's",
			credential.Email)
	}
}

func TestItAnswersTheTwoRequestsTheRelayMakes(t *testing.T) {
	push := standIn(t)

	answer, err := http.Post(push.Endpoint+"/token",
		"application/x-www-form-urlencoded",
		strings.NewReader("grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=x"))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = answer.Body.Close() }()
	var granted struct {
		Token   string `json:"access_token"`
		Seconds int    `json:"expires_in"`
	}
	if err := json.NewDecoder(answer.Body).Decode(&granted); err != nil {
		t.Fatal(err)
	}
	if granted.Token == "" || granted.Seconds <= 0 {
		t.Fatalf("the grant was %+v", granted)
	}

	send(t, push, `{"message":{"topic":"feed.sandbox.abc","data":{"kind":"feed_invalidation"}}}`)
	send(t, push, `{"message":{"topic":"feed.sandbox.abc","data":{"kind":"feed_invalidation"}}}`)
	send(t, push, `{"message":{"topic":"feed.sandbox.def","data":{"kind":"feed_invalidation"}}}`)
	hints, exchanges := push.Hints()
	if hints["feed.sandbox.abc"] != 2 || hints["feed.sandbox.def"] != 1 {
		t.Fatalf("%v", hints)
	}
	if exchanges != 1 {
		t.Fatalf("%d token exchanges", exchanges)
	}
	if addressed := push.Addressed(); len(addressed) != 0 {
		t.Fatalf("%v", addressed)
	}
}

// A hint is addressed to a feed's topic and never to a device. If the relay ever sent one to a
// token, this deployment would know something about a subscriber — so the stand-in records it and
// every scenario's check fails on it.
func TestASendThatNamesADeviceIsRecorded(t *testing.T) {
	push := standIn(t)
	send(t, push, `{"message":{"token":"a-device-token","data":{}}}`)
	addressed := push.Addressed()
	if len(addressed) != 1 || !strings.Contains(addressed[0], "a-device-token") {
		t.Fatalf("%v", addressed)
	}
	if hints, _ := push.Hints(); len(hints) != 0 {
		t.Fatalf("it was counted as a hint: %v", hints)
	}
}

func TestItRefusesABodyThatIsNotASend(t *testing.T) {
	push := standIn(t)
	answer, err := http.Post(
		push.Endpoint+"/v1/projects/"+pushProject+"/messages:send",
		"application/json", strings.NewReader("not json"))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = answer.Body.Close() }()
	if answer.StatusCode != http.StatusBadRequest {
		t.Fatalf("it answered %s", answer.Status)
	}
}

func standIn(t *testing.T) *Push {
	t.Helper()
	push, err := StartPush(t.TempDir(), "sandbox")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(push.Stop)
	return push
}

func send(t *testing.T, push *Push, body string) {
	t.Helper()
	answer, err := http.Post(
		push.Endpoint+"/v1/projects/"+pushProject+"/messages:send",
		"application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = answer.Body.Close() }()
	if answer.StatusCode != http.StatusOK {
		t.Fatalf("it answered %s", answer.Status)
	}
}
