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
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

// Credentials is the deployment's service account, as the file Google hands out spells it.
//
// It is read once, at startup, by the process that sends hints, and it is the only credential in
// this service that is not a hash in the store: a push credential has to be usable, so it is
// mounted read-only into one container and never written to a log, an answer or an error. A
// publisher is never given one — a publisher publishes to the gateway, and the gateway is what
// holds this (docs/guides/firebase.md).
//
// Only the fields the grant needs are read. A file with more in it is fine; a file missing one of
// these is refused at startup, by name, so the deployment is fixed before anything is published.
type Credentials struct {
	Type        string `json:"type"`
	ProjectID   string `json:"project_id"`
	ClientEmail string `json:"client_email"`
	// PEM, PKCS#8. It is parsed at startup so a broken key is a startup failure rather than a
	// failure per hint, and the parsed form is kept beside it.
	PrivateKey string `json:"private_key"`
	// Where the grant is exchanged. It comes from the credential rather than from this package
	// because it is the document's own statement about where it is honoured, which is also why
	// there is no address compiled in here.
	TokenURI string `json:"token_uri"`

	key *rsa.PrivateKey
}

// Scope is what the grant asks for, and the least there is: sending messages. It is a name in
// Google's own vocabulary rather than somewhere this package connects to — the form value of one
// request, never a host that is dialled.
const Scope = "https://www.googleapis.com/auth/firebase.messaging"

// grantType is the assertion flow a service account uses, spelled as the specification does.
const grantType = "urn:ietf:params:oauth:grant-type:jwt-bearer"

// assertionLifetime is how long one signed assertion is good for. An hour is what the endpoint
// accepts at most, and the access token it is exchanged for expires on its own schedule anyway.
const assertionLifetime = time.Hour

// margin is how early a cached token is replaced. A token used at the moment it expires is a
// refusal and a retry; a minute of margin is the cheapest way not to have that conversation.
const margin = time.Minute

// ReadCredentials reads and validates a service account file.
//
// Every problem names the field rather than the value, and no error from here carries any part of
// the key: a startup message is read by whoever deployed this, and pasted into places they do not
// control.
func ReadCredentials(path string) (Credentials, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		// os.ReadFile's error names the path, which is the operator's own and holds no secret.
		return Credentials{}, fmt.Errorf("relay: the push credential cannot be read: %w", err)
	}
	var credentials Credentials
	if err := json.Unmarshal(raw, &credentials); err != nil {
		// Deliberately not wrapped: a JSON error from a credential file can quote the file.
		return Credentials{}, fmt.Errorf("relay: the push credential is not a JSON document")
	}
	if err := credentials.valid(); err != nil {
		return Credentials{}, err
	}
	return credentials, nil
}

// valid is every rule the credential is held to, and it parses the key rather than trusting it.
func (c *Credentials) valid() error {
	switch {
	case c.Type != "service_account":
		return fmt.Errorf(`relay: the push credential's "type" must be "service_account"`)
	case c.ProjectID == "":
		return fmt.Errorf(`relay: the push credential has no "project_id"`)
	case c.ClientEmail == "":
		return fmt.Errorf(`relay: the push credential has no "client_email"`)
	case c.PrivateKey == "":
		return fmt.Errorf(`relay: the push credential has no "private_key"`)
	case c.TokenURI == "":
		return fmt.Errorf(`relay: the push credential has no "token_uri"`)
	}
	if !exchangeable(c.TokenURI) {
		return fmt.Errorf(`relay: the push credential's "token_uri" must be an HTTPS URL, ` +
			`or a loopback HTTP one for development`)
	}
	if c.key == nil {
		key, err := privateKey(c.PrivateKey)
		if err != nil {
			return err
		}
		c.key = key
	}
	return nil
}

// exchangeable is where a grant may be exchanged: HTTPS, or plain HTTP on a loopback host.
//
// It is the same allowance the gateway's own origin rule makes for the same reason
// (config.Origin): a loopback endpoint is a development one, on a machine whose operator is the
// person who wrote the credential file. Anything else is a credential that would be sent in the
// clear, which is refused at startup rather than once per hint.
func exchangeable(raw string) bool {
	parsed, err := url.Parse(raw)
	if err != nil {
		return false
	}
	host := strings.ToLower(parsed.Hostname())
	switch strings.ToLower(parsed.Scheme) {
	case "https":
		return host != ""
	case "http":
		return host == "127.0.0.1" || host == "localhost" || host == "::1"
	default:
		return false
	}
}

// privateKey parses the PEM the file carries. The error says which step failed and nothing about
// the contents.
func privateKey(text string) (*rsa.PrivateKey, error) {
	block, _ := pem.Decode([]byte(text))
	if block == nil {
		return nil, fmt.Errorf(`relay: the push credential's "private_key" is not PEM`)
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf(`relay: the push credential's "private_key" is not a PKCS#8 key`)
	}
	key, ok := parsed.(*rsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf(`relay: the push credential's "private_key" is not an RSA key`)
	}
	return key, nil
}

// tokens is one access token and its expiry, minted on demand and shared by every hint.
//
// The exchange is written by hand for the same reason the listener's ticket is (internal/stream):
// it is a signed JSON document and one form post, and the alternative was a dependency tree an
// order of magnitude larger than this whole service for the two things it does here. What is
// hand-written is pinned by tests — the claim set, the signature, the grant type, the caching and
// the one retry — rather than left to be discovered in production.
type tokens struct {
	credentials Credentials
	client      *http.Client
	now         func() time.Time

	mutex   sync.Mutex
	held    string
	expires time.Time
}

// access is the current token, minting one when there is none or the one there is about to expire.
//
// The lock is held across the exchange on purpose. Two publications arriving together would
// otherwise mint two tokens, and the endpoint is entitled to rate limit that; one call and a short
// wait is the better trade for a service that publishes a handful of times a minute.
func (t *tokens) access(ctx context.Context) (string, error) {
	t.mutex.Lock()
	defer t.mutex.Unlock()
	if t.held != "" && t.now().Before(t.expires.Add(-margin)) {
		return t.held, nil
	}
	token, lifetime, err := t.mint(ctx)
	if err != nil {
		return "", err
	}
	t.held = token
	t.expires = t.now().Add(lifetime)
	return token, nil
}

// forget drops a token the endpoint refused, and only if it is still the one held: a rotation can
// race a refusal, and clearing the newer token would throw away a token that works. It is the same
// compare-and-clear the private path does with a registration target (SAW-055).
func (t *tokens) forget(token string) {
	t.mutex.Lock()
	defer t.mutex.Unlock()
	if t.held == token {
		t.held = ""
		t.expires = time.Time{}
	}
}

// mint exchanges a signed assertion for an access token.
func (t *tokens) mint(ctx context.Context) (string, time.Duration, error) {
	signed, err := t.assertion(t.now())
	if err != nil {
		return "", 0, err
	}
	form := url.Values{"grant_type": {grantType}, "assertion": {signed}}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost,
		t.credentials.TokenURI, strings.NewReader(form.Encode()))
	if err != nil {
		return "", 0, fmt.Errorf("build the token request: %w", err)
	}
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	response, err := t.client.Do(request)
	if err != nil {
		// No wrapping: the transport error would carry the assertion when the failure happened
		// while the body was being written.
		return "", 0, fmt.Errorf("the token endpoint is not reachable")
	}
	defer response.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(response.Body, 8*1024))
	if err != nil {
		return "", 0, fmt.Errorf("read the token answer")
	}
	if response.StatusCode != http.StatusOK {
		// The status alone. A refusal's body is the endpoint's own prose and may quote the request.
		return "", 0, fmt.Errorf("the token endpoint answered %d", response.StatusCode)
	}
	var answer struct {
		Token   string `json:"access_token"`
		Seconds int    `json:"expires_in"`
	}
	if err := json.Unmarshal(raw, &answer); err != nil || answer.Token == "" {
		return "", 0, fmt.Errorf("the token answer was not a token")
	}
	lifetime := time.Duration(answer.Seconds) * time.Second
	if lifetime <= margin {
		// An endpoint that hands out a token with no useful life left is one this service will not
		// pretend to have a token from: it would mint one per hint for ever.
		return "", 0, fmt.Errorf("the token answer expires immediately")
	}
	return answer.Token, lifetime, nil
}

// assertion is the signed JWS the grant is made with: RS256, the account as issuer and subject's
// absence, the scope it needs, and the endpoint from the credential as audience.
//
// Nothing about a subscriber, a publisher or a publication is in it, because none of that is what
// it says: it says which service account this is, and for how long.
func (t *tokens) assertion(at time.Time) (string, error) {
	header := segment([]byte(`{"alg":"RS256","typ":"JWT"}`))
	claims, err := json.Marshal(map[string]any{
		"iss":   t.credentials.ClientEmail,
		"scope": Scope,
		"aud":   t.credentials.TokenURI,
		"iat":   at.Unix(),
		"exp":   at.Add(assertionLifetime).Unix(),
	})
	if err != nil {
		return "", fmt.Errorf("encode the assertion")
	}
	signing := header + "." + segment(claims)
	digest := sha256.Sum256([]byte(signing))
	signature, err := rsa.SignPKCS1v15(rand.Reader, t.credentials.key, crypto.SHA256, digest[:])
	if err != nil {
		return "", fmt.Errorf("sign the assertion")
	}
	return signing + "." + segment(signature), nil
}

// segment is base64url without padding, which is what a JWS is made of.
func segment(raw []byte) string { return base64.RawURLEncoding.EncodeToString(raw) }
