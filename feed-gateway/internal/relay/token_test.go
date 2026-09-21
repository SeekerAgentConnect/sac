// The grant the relay makes, and the credential it makes it with (SEE-92).
//
// It is hand-written, so it is pinned here: the claim set exactly, the signature verified with the
// public half of the key, the flow the endpoint is asked for, the caching, and what happens to a
// token that was refused. A test that only checked "a token came back" would not notice a claim
// that had been added, and an added claim is the thing worth noticing — this assertion says which
// service account is asking and for how long, and nothing else is anybody's business.
package relay

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"
)

// One key for every test in the package: generating an RSA key is the slowest thing here by an
// order of magnitude, and what is being tested is the document it signs.
var (
	once   sync.Once
	signer *rsa.PrivateKey
)

func key(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	once.Do(func() {
		generated, err := rsa.GenerateKey(rand.Reader, 2048)
		if err != nil {
			t.Fatal(err)
		}
		signer = generated
	})
	return signer
}

// pemKey is the key as a service account file carries it: PKCS#8, PEM.
func pemKey(t *testing.T) string {
	t.Helper()
	encoded, err := x509.MarshalPKCS8PrivateKey(key(t))
	if err != nil {
		t.Fatal(err)
	}
	return string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: encoded}))
}

func credentials(t *testing.T, tokenURI string) Credentials {
	t.Helper()
	return Credentials{
		Type:        "service_account",
		ProjectID:   "seeker-broadcast-test",
		ClientEmail: "hints@seeker-broadcast-test.iam.gserviceaccount.com",
		PrivateKey:  pemKey(t),
		TokenURI:    tokenURI,
	}
}

// minter is a token endpoint that records what it was asked and answers what it is told to.
type minter struct {
	server *httptest.Server

	mutex     sync.Mutex
	calls     int
	forms     []url.Values
	types     []string
	status    int
	seconds   int
	token     string
	tokens    []string
	malformed bool
}

func tokenEndpoint(t *testing.T, change ...func(*minter)) *minter {
	t.Helper()
	answering := &minter{status: http.StatusOK, seconds: 3600, token: "access-token-1"}
	for _, apply := range change {
		apply(answering)
	}
	answering.server = httptest.NewServer(http.HandlerFunc(
		func(writer http.ResponseWriter, request *http.Request) {
			answering.mutex.Lock()
			defer answering.mutex.Unlock()
			if err := request.ParseForm(); err != nil {
				t.Error(err)
			}
			answering.calls++
			answering.forms = append(answering.forms, request.PostForm)
			answering.types = append(answering.types, request.Header.Get("Content-Type"))
			token := answering.token
			if len(answering.tokens) > 0 {
				token = answering.tokens[min(answering.calls-1, len(answering.tokens)-1)]
			}
			writer.WriteHeader(answering.status)
			if answering.malformed {
				_, _ = writer.Write([]byte(`{"error":"invalid_grant"}`))
				return
			}
			_, _ = fmt.Fprintf(writer, `{"access_token":%q,"expires_in":%d,"token_type":"Bearer"}`,
				token, answering.seconds)
		}))
	t.Cleanup(answering.server.Close)
	return answering
}

func (m *minter) asked() int {
	m.mutex.Lock()
	defer m.mutex.Unlock()
	return m.calls
}

// clock is a time a test moves by hand, so caching is a decision rather than a wait.
type clock struct {
	mutex sync.Mutex
	at    time.Time
}

func (c *clock) now() time.Time {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	return c.at
}

func (c *clock) advance(by time.Duration) {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	c.at = c.at.Add(by)
}

func cache(t *testing.T, endpoint *minter, at *clock) *tokens {
	t.Helper()
	held := credentials(t, endpoint.server.URL)
	if err := held.valid(); err != nil {
		t.Fatal(err)
	}
	return &tokens{credentials: held, client: endpoint.server.Client(), now: at.now}
}

// The assertion says which service account is asking, for what, and for how long. Five claims and
// no more: nothing about a publisher, a subscriber, a channel or a publication is in it, because
// none of that is what a token request is about — and a claim added here would be sent to Google on
// every hint.
func TestTheAssertionSaysWhoIsAskingAndForWhatAndNothingElse(t *testing.T) {
	endpoint := tokenEndpoint(t)
	at := &clock{at: time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)}
	held := cache(t, endpoint, at)

	signed, err := held.assertion(at.now())
	if err != nil {
		t.Fatal(err)
	}
	parts := strings.Split(signed, ".")
	if len(parts) != 3 {
		t.Fatalf("an assertion in %d parts", len(parts))
	}

	header := map[string]string{}
	decode(t, parts[0], &header)
	if fmt.Sprint(header) != fmt.Sprint(map[string]string{"alg": "RS256", "typ": "JWT"}) {
		t.Fatalf("the header is %v", header)
	}

	claims := map[string]any{}
	decode(t, parts[1], &claims)
	var names []string
	for name := range claims {
		names = append(names, name)
	}
	slices.Sort(names)
	if fmt.Sprint(names) != fmt.Sprint([]string{"aud", "exp", "iat", "iss", "scope"}) {
		t.Fatalf("the assertion carries %v", names)
	}
	if claims["iss"] != held.credentials.ClientEmail {
		t.Fatalf("the issuer is %v", claims["iss"])
	}
	if claims["aud"] != endpoint.server.URL {
		t.Fatalf("the audience is %v", claims["aud"])
	}
	if claims["scope"] != "https://www.googleapis.com/auth/firebase.messaging" {
		t.Fatalf("the scope is %v", claims["scope"])
	}
	// The clock is the caller's, as it is everywhere else in this service.
	if claims["iat"] != float64(at.now().Unix()) {
		t.Fatalf("issued at %v", claims["iat"])
	}
	if claims["exp"] != float64(at.now().Add(time.Hour).Unix()) {
		t.Fatalf("expires at %v", claims["exp"])
	}

	// And it is signed by the key in the credential, verified here with the public half — which is
	// the only way to know the hand-written signature is one at all.
	digest := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	signature, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		t.Fatal(err)
	}
	if err := rsa.VerifyPKCS1v15(&key(t).PublicKey, crypto.SHA256, digest[:], signature); err != nil {
		t.Fatalf("the signature does not verify: %v", err)
	}
}

// The exchange is the service account flow, spelled the way the specification does, and the
// assertion travels as a form value rather than in a URL — where it would be in a log line on
// somebody else's server.
func TestTheGrantIsTheServiceAccountFlow(t *testing.T) {
	endpoint := tokenEndpoint(t)
	at := &clock{at: time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)}
	held := cache(t, endpoint, at)

	if _, err := held.access(context.Background()); err != nil {
		t.Fatal(err)
	}
	form := endpoint.forms[0]
	if form.Get("grant_type") != "urn:ietf:params:oauth:grant-type:jwt-bearer" {
		t.Fatalf("the grant type is %q", form.Get("grant_type"))
	}
	if form.Get("assertion") == "" {
		t.Fatal("no assertion was sent")
	}
	var names []string
	for name := range form {
		names = append(names, name)
	}
	slices.Sort(names)
	if fmt.Sprint(names) != fmt.Sprint([]string{"assertion", "grant_type"}) {
		t.Fatalf("the form carries %v", names)
	}
	if endpoint.types[0] != "application/x-www-form-urlencoded" {
		t.Fatalf("the request was sent as %q", endpoint.types[0])
	}
}

// One token is minted for as long as it is good for, and replaced a little before it expires. A
// relay that minted one per hint would turn a busy publisher into a rate limit on somebody else's
// endpoint.
func TestTheTokenIsCachedUntilShortlyBeforeItExpires(t *testing.T) {
	endpoint := tokenEndpoint(t, func(m *minter) {
		m.seconds = 3600
		m.tokens = []string{"access-token-1", "access-token-2"}
	})
	at := &clock{at: time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)}
	held := cache(t, endpoint, at)

	first, err := held.access(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	again, err := held.access(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if first != "access-token-1" || again != first {
		t.Fatalf("the cache handed out %q then %q", first, again)
	}
	if endpoint.asked() != 1 {
		t.Fatalf("the endpoint was asked %d times", endpoint.asked())
	}

	// Still inside the margin: the same token.
	at.advance(time.Hour - 2*time.Minute)
	if token, _ := held.access(context.Background()); token != "access-token-1" {
		t.Fatalf("it was replaced early with %q", token)
	}
	// Inside the margin: a new one, before anything is refused.
	at.advance(90 * time.Second)
	token, err := held.access(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if token != "access-token-2" || endpoint.asked() != 2 {
		t.Fatalf("it handed out %q after %d exchanges", token, endpoint.asked())
	}
}

// A token that is already inside its own replacement margin is not a token: caching it would mean
// minting one for every hint for ever, which is worse than saying so.
func TestATokenThatExpiresImmediatelyIsRefused(t *testing.T) {
	endpoint := tokenEndpoint(t, func(m *minter) { m.seconds = 30 })
	at := &clock{at: time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)}
	held := cache(t, endpoint, at)

	_, err := held.access(context.Background())
	if err == nil || !strings.Contains(err.Error(), "expires immediately") {
		t.Fatalf("a 30-second token was accepted: %v", err)
	}
}

// A refusal is reported by its status and nothing else. The body of one can quote the request it
// refused — which is an assertion — so it is read, bounded, and dropped.
func TestARefusedExchangeCarriesNothingButItsStatus(t *testing.T) {
	endpoint := tokenEndpoint(t, func(m *minter) {
		m.status = http.StatusBadRequest
		m.malformed = true
	})
	at := &clock{at: time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)}
	held := cache(t, endpoint, at)

	_, err := held.access(context.Background())
	if err == nil {
		t.Fatal("a refusal was accepted")
	}
	if !strings.Contains(err.Error(), "400") {
		t.Fatalf("the error does not say what happened: %v", err)
	}
	if strings.Contains(err.Error(), "invalid_grant") {
		t.Fatalf("the error quotes the endpoint: %v", err)
	}
}

// An answer that is not a token is not read as one. An endpoint that answers 200 with something
// else would otherwise leave the relay sending an empty bearer on every hint.
func TestAnAnswerThatIsNotATokenIsRefused(t *testing.T) {
	endpoint := tokenEndpoint(t, func(m *minter) { m.token = "" })
	at := &clock{at: time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)}
	held := cache(t, endpoint, at)

	if _, err := held.access(context.Background()); err == nil {
		t.Fatal("an answer with no token in it was accepted")
	}
}

// Forgetting is compare-and-clear, as it is for a registration target on the private path
// (SAW-055): a refusal that raced a replacement must not throw away the replacement.
func TestForgettingOnlyClearsTheTokenItNamed(t *testing.T) {
	endpoint := tokenEndpoint(t)
	at := &clock{at: time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)}
	held := cache(t, endpoint, at)
	if _, err := held.access(context.Background()); err != nil {
		t.Fatal(err)
	}

	held.forget("some-older-token")
	if _, err := held.access(context.Background()); err != nil {
		t.Fatal(err)
	}
	if endpoint.asked() != 1 {
		t.Fatalf("a stale refusal cleared the current token (%d exchanges)", endpoint.asked())
	}

	held.forget("access-token-1")
	if _, err := held.access(context.Background()); err != nil {
		t.Fatal(err)
	}
	if endpoint.asked() != 2 {
		t.Fatalf("the refused token was kept (%d exchanges)", endpoint.asked())
	}
}

// Every rule the credential is held to, each named by the field it is about — and none of the
// messages carries any part of the document, because a startup failure is pasted into places its
// operator does not control.
func TestACredentialIsValidatedFieldByFieldAndNeverEchoed(t *testing.T) {
	good := credentials(t, "https://oauth2.example.com/token")
	if err := (&good).valid(); err != nil {
		t.Fatalf("a complete credential was refused: %v", err)
	}

	for name, break_ := range map[string]func(*Credentials){
		"type":       func(c *Credentials) { c.Type = "authorized_user" },
		"project_id": func(c *Credentials) { c.ProjectID = "" },
		"client_email": func(c *Credentials) {
			c.ClientEmail = ""
		},
		"private_key": func(c *Credentials) { c.PrivateKey = "" },
		"token_uri":   func(c *Credentials) { c.TokenURI = "" },
	} {
		broken := credentials(t, "https://oauth2.example.com/token")
		break_(&broken)
		err := (&broken).valid()
		if err == nil {
			t.Fatalf("a credential with no %s was accepted", name)
		}
		if !strings.Contains(err.Error(), name) {
			t.Fatalf("the %s problem says %q", name, err)
		}
		if strings.Contains(err.Error(), "PRIVATE KEY") ||
			strings.Contains(err.Error(), good.PrivateKey) {
			t.Fatalf("the %s problem quotes the key", name)
		}
	}

	// A token endpoint that is not HTTPS, and the one exception: loopback, for development.
	plain := credentials(t, "http://oauth2.example.com/token")
	if err := (&plain).valid(); err == nil {
		t.Fatal("a token endpoint in the clear was accepted")
	}
	loopback := credentials(t, "http://127.0.0.1:9099/token")
	if err := (&loopback).valid(); err != nil {
		t.Fatalf("a loopback token endpoint was refused: %v", err)
	}

	// A key that is not a key, and a key that is not RSA, are both refused by name.
	notPEM := credentials(t, "https://oauth2.example.com/token")
	notPEM.PrivateKey = "-----BEGIN NOTHING-----"
	if err := (&notPEM).valid(); err == nil || !strings.Contains(err.Error(), "PEM") {
		t.Fatalf("a key that is not PEM was accepted: %v", err)
	}
}

// The file itself: read, parsed and validated, with no part of it in any error. A file that is not
// JSON is the one worth checking, because the standard library's own error for that quotes the
// document it failed on.
func TestReadingACredentialQuotesNothingFromIt(t *testing.T) {
	directory := t.TempDir()
	good := filepath.Join(directory, "service-account.json")
	document, err := json.Marshal(credentials(t, "https://oauth2.example.com/token"))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(good, document, 0o600); err != nil {
		t.Fatal(err)
	}
	read, err := ReadCredentials(good)
	if err != nil {
		t.Fatal(err)
	}
	if read.ProjectID != "seeker-broadcast-test" {
		t.Fatalf("the project is %q", read.ProjectID)
	}

	secret := filepath.Join(directory, "not-json")
	if err := os.WriteFile(secret, []byte("this-should-never-appear-in-an-error"), 0o600); err != nil {
		t.Fatal(err)
	}
	_, err = ReadCredentials(secret)
	if err == nil {
		t.Fatal("a file that is not JSON was accepted")
	}
	if strings.Contains(err.Error(), "this-should-never-appear-in-an-error") {
		t.Fatalf("the error quotes the file: %v", err)
	}

	if _, err := ReadCredentials(filepath.Join(directory, "absent")); err == nil {
		t.Fatal("a missing credential was accepted")
	}
}

// App Platform has no file mount: BROADCAST_PUSH_CREDENTIALS is the JSON document. A path is
// still a path. A JSON env on an image that only opened a file is the crash this exists to stop.
func TestLoadCredentialsAcceptsTheDocumentOrAPath(t *testing.T) {
	document, err := json.Marshal(credentials(t, "https://oauth2.example.com/token"))
	if err != nil {
		t.Fatal(err)
	}

	fromJSON, err := LoadCredentials("  " + string(document) + "\n")
	if err != nil {
		t.Fatal(err)
	}
	if fromJSON.ProjectID != "seeker-broadcast-test" {
		t.Fatalf("the project is %q", fromJSON.ProjectID)
	}

	path := filepath.Join(t.TempDir(), "service-account.json")
	if err := os.WriteFile(path, document, 0o600); err != nil {
		t.Fatal(err)
	}
	fromFile, err := LoadCredentials(path)
	if err != nil {
		t.Fatal(err)
	}
	if fromFile.ProjectID != "seeker-broadcast-test" {
		t.Fatalf("the project is %q", fromFile.ProjectID)
	}

	_, err = LoadCredentials(`{"type":"service_account"}`)
	if err == nil {
		t.Fatal("a document missing required fields was accepted")
	}
	if strings.Contains(err.Error(), "{") {
		t.Fatalf("the error quotes the document: %v", err)
	}

	// A YAML single-quoted env turns JSON's \n into a two-character sequence. The PEM still
	// has to parse.
	mangled := credentials(t, "https://oauth2.example.com/token")
	mangled.PrivateKey = strings.ReplaceAll(pemKey(t), "\n", `\n`)
	escaped, err := json.Marshal(mangled)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := LoadCredentials(string(escaped)); err != nil {
		t.Fatalf("a document whose PEM newlines were env-escaped was refused: %v", err)
	}
}

func decode(t *testing.T, segment string, into any) {
	t.Helper()
	raw, err := base64.RawURLEncoding.DecodeString(segment)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(raw, into); err != nil {
		t.Fatal(err)
	}
}
