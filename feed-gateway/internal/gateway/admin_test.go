package gateway_test

import (
	"context"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"connectrpc.com/connect"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/config"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/credential"
	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
)

// The operator's administration, against the running service rather than the package on its own
// (SEE-141). What matters here is the wiring: that a registration made in a browser is the same
// registration the publisher API resolves, immediately, in the same process, with nothing
// restarted and no environment variable changed — and that the surface exists on its own listener
// and nowhere else.

const operatorPassword = "the-operators-own-password"

// administered is a gateway with an operator password configured, which is the only thing that
// brings the administrative surface into existence.
func administered(t *testing.T) *harness {
	t.Helper()
	encoded, err := credential.HashPassword(operatorPassword)
	if err != nil {
		t.Fatal(err)
	}
	one := newGateway(t, func(settings *config.Config) {
		settings.Admin = config.Admin{
			Address:         "127.0.0.1:0",
			Path:            config.DefaultAdminPath,
			PasswordHash:    encoded,
			SessionLifetime: config.DefaultAdminLifetime,
			LoginRate:       1000,
			LoginBurst:      1000,
			PublisherURL:    gatewayURL,
		}
	})
	if one.admin == nil {
		t.Fatal("a configured password did not bring the admin surface into existence")
	}
	return one
}

// operator is a logged-in browser: a cookie jar, no automatic redirects, and the CSRF token read
// out of whatever page it is on.
type operator struct {
	t      *testing.T
	url    string
	client *http.Client
}

func (h *harness) operator() *operator {
	h.t.Helper()
	jar, err := cookiejar.New(nil)
	if err != nil {
		h.t.Fatal(err)
	}
	one := &operator{t: h.t, url: h.admin.URL, client: &http.Client{
		Jar:           jar,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}}
	response := one.post("/login", url.Values{"password": {operatorPassword}})
	_ = response.Body.Close()
	if response.StatusCode != http.StatusSeeOther {
		h.t.Fatalf("the operator could not log in: %s", response.Status)
	}
	return one
}

func (o *operator) post(path string, form url.Values) *http.Response {
	o.t.Helper()
	request, err := http.NewRequest(http.MethodPost,
		o.url+config.DefaultAdminPath+path, strings.NewReader(form.Encode()))
	if err != nil {
		o.t.Fatal(err)
	}
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	request.Header.Set("Sec-Fetch-Site", "same-origin")
	response, err := o.client.Do(request)
	if err != nil {
		o.t.Fatal(err)
	}
	return response
}

func (o *operator) page(path string) string {
	o.t.Helper()
	response, err := o.client.Get(o.url + config.DefaultAdminPath + path)
	if err != nil {
		o.t.Fatal(err)
	}
	defer func() { _ = response.Body.Close() }()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		o.t.Fatal(err)
	}
	return string(body)
}

func (o *operator) csrf(path string) string {
	o.t.Helper()
	body := o.page(path)
	const marker = `name="csrf" value="`
	start := strings.Index(body, marker)
	if start < 0 {
		o.t.Fatalf("%s carries no CSRF token", path)
	}
	rest := body[start+len(marker):]
	return rest[:strings.Index(rest, `"`)]
}

// register does the whole flow a person does, and returns the one credential the gateway showed.
func (o *operator) register(publisherA, label string) string {
	o.t.Helper()
	response := o.post("/servers", url.Values{
		"csrf": {o.csrf("/")}, "server": {publisherA}, "label": {label},
	})
	_ = response.Body.Close()
	if response.StatusCode != http.StatusSeeOther {
		o.t.Fatalf("registering %s answered %s", publisherA, response.Status)
	}
	return secretShown(o.t, o.page("/reveal"))
}

func secretShown(t *testing.T, body string) string {
	t.Helper()
	const marker = `<code id="secret">`
	start := strings.Index(body, marker)
	if start < 0 {
		t.Fatalf("no credential was shown:\n%s", body)
	}
	rest := body[start+len(marker):]
	return rest[:strings.Index(rest, "<")]
}

// A publisher registered in the browser can publish on its next request. Nothing is restarted, no
// environment variable changes, and the credential the page showed is the one the publisher API
// resolves — which is the whole point of the ticket.
func TestAPublisherRegisteredInTheBrowserCanPublishImmediately(t *testing.T) {
	one := administered(t)
	secret := one.operator().register(publisherA, "copy trading")

	publishing := one.publisher(secret)
	accepted, err := publishing.PublishManifest(context.Background(),
		connect.NewRequest(&gatewayv1.PublishManifestRequest{Manifest: manifestOf(publisherA, 1)}))
	if err != nil {
		t.Fatalf("a publisher registered through the admin page could not publish: %v", err)
	}
	if accepted.Msg.GetStatus() != gatewayv1.PublishStatus_PUBLISH_STATUS_STORED {
		t.Fatalf("the manifest was %v", accepted.Msg.GetStatus())
	}

	// And the operator's page now says so, from the same durable state the feed is served from.
	if page := one.operator().page("/servers/" + publisherA); !strings.Contains(page, "Manifest received") {
		t.Fatal("the publisher's page does not say that a manifest arrived")
	}
}

// The CLI and the UI are one authority. What either does, the other sees, because both go through
// the same store in the same process space.
func TestTheCliAndTheBrowserSeeTheSameRegistrations(t *testing.T) {
	one := administered(t)
	// Registered the way feed-gatewayctl does.
	fromCLI := one.register(publisherA)
	if page := one.operator().page("/"); !strings.Contains(page, publisherA) {
		t.Fatal("the admin page does not list a publisher the CLI registered")
	}

	// Registered in the browser, then read back through the store the CLI uses.
	browser := one.operator()
	secret := browser.register(publisherB, "the other one")
	publishers, err := one.documents.Publishers(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(publishers) != 2 {
		t.Fatalf("the store holds %d publisher(s)", len(publishers))
	}
	for _, held := range []struct{ secret, of string }{{fromCLI, publisherA}, {secret, publisherB}} {
		resolved, err := one.documents.PublisherFor(context.Background(), credential.Hash(held.secret))
		if err != nil || resolved != held.of {
			t.Fatalf("a credential resolved to %q rather than %q", resolved, held.of)
		}
	}
}

// Revocation is enforced on the next request, and it is one publisher's revocation: another goes
// on publishing while it happens.
func TestRevokingInTheBrowserIsEnforcedOnTheNextPublication(t *testing.T) {
	one := administered(t)
	browser := one.operator()
	revoked := browser.register(publisherA, "first")
	untouched := browser.register(publisherB, "second")

	for _, held := range []struct{ secret, of string }{{revoked, publisherA}, {untouched, publisherB}} {
		if _, err := one.publisher(held.secret).PublishManifest(context.Background(),
			connect.NewRequest(&gatewayv1.PublishManifestRequest{
				Manifest: manifestOf(held.of, 1)})); err != nil {
			t.Fatalf("%s could not publish: %v", held.of, err)
		}
	}

	response := browser.post("/servers/"+publisherA+"/revoke",
		url.Values{"csrf": {browser.csrf("/servers/" + publisherA)}, "all": {"on"}})
	_ = response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("revoking answered %s", response.Status)
	}

	_, err := one.publisher(revoked).PublishManifest(context.Background(),
		connect.NewRequest(&gatewayv1.PublishManifestRequest{Manifest: manifestOf(publisherA, 2)}))
	if connect.CodeOf(err) != connect.CodeUnauthenticated {
		t.Fatalf("a revoked credential published: %v", err)
	}
	// The other publisher is unaffected, and what the revoked one published is still served.
	if _, err := one.publisher(untouched).PublishManifest(context.Background(),
		connect.NewRequest(&gatewayv1.PublishManifestRequest{
			Manifest: manifestOf(publisherB, 2)})); err != nil {
		t.Fatalf("another publisher was affected by a revocation: %v", err)
	}
	if held, err := one.documents.Manifest(context.Background(), publisherA); err != nil || held == nil {
		t.Fatal("revoking a credential withdrew what was published")
	}
}

// The administrative surface is on its own listener and is absent from the other two. A feed read
// and a publication reach nothing administrative however they are addressed.
func TestTheAdminSurfaceIsAbsentFromTheFeedAndPublisherListeners(t *testing.T) {
	one := administered(t)
	paths := []string{
		config.DefaultAdminPath, config.DefaultAdminPath + "/", config.DefaultAdminPath + "/login",
		config.DefaultAdminPath + "/servers", config.DefaultAdminPath + "/assets/admin.css",
	}
	for _, server := range []struct {
		name string
		url  string
		do   func(string) (*http.Response, error)
	}{
		{"the read listener", one.read.URL, one.read.Client().Get},
		{"the publisher listener", one.publish.URL, one.publish.Client().Get},
	} {
		for _, path := range paths {
			response, err := server.do(server.url + path)
			if err != nil {
				t.Fatal(err)
			}
			_ = response.Body.Close()
			if response.StatusCode != http.StatusNotFound {
				t.Fatalf("%s answered %s for %s", server.name, response.Status, path)
			}
		}
	}
	// And the publisher API still has no procedure that could register anything.
	for _, procedure := range []string{
		"/seekervault.gateway.v1.PublisherService/RegisterPublisher",
		"/seekervault.gateway.v1.PublisherService/ListPublishers",
		"/seekervault.gateway.v1.FeedService/ListPublishers",
	} {
		response, err := one.publish.Client().Post(one.publish.URL+procedure,
			"application/json", strings.NewReader("{}"))
		if err != nil {
			t.Fatal(err)
		}
		_ = response.Body.Close()
		if response.StatusCode != http.StatusNotFound {
			t.Fatalf("the publisher listener answered %s for %s", response.Status, procedure)
		}
	}
}

// Without a configured password there is no administrative surface at all: not a disabled route,
// not a setup page, nothing to reach.
func TestWithoutAPasswordThereIsNoAdministrativeSurface(t *testing.T) {
	one := newGateway(t)
	if one.admin != nil {
		t.Fatal("an unconfigured gateway built an administrative surface")
	}
	for _, server := range []*httptest.Server{one.read, one.publish} {
		for _, path := range []string{"/admin", "/admin/", "/admin/login", "/setup"} {
			response, err := server.Client().Get(server.URL + path)
			if err != nil {
				t.Fatal(err)
			}
			_ = response.Body.Close()
			if response.StatusCode != http.StatusNotFound {
				t.Fatalf("%s answered %s with no password configured", path, response.Status)
			}
		}
	}
	if !strings.Contains(one.logs.text(), "no operator password is configured") {
		t.Fatal("the gateway does not say that it has no administrative surface")
	}
}

// A credential the operator's page issued is resolved by exactly the same rule every other one is,
// and its handle is the same handle the CLI prints.
func TestOneCredentialAlgorithmServesBothSurfaces(t *testing.T) {
	one := administered(t)
	secret := one.operator().register(publisherA, "one")
	if !credential.Valid(secret) {
		t.Fatalf("the admin page issued something that is not a credential: %q", secret)
	}
	if page := one.operator().page("/servers/" + publisherA); !strings.Contains(page, handleOf(secret)) {
		t.Fatal("the credential's handle is not the one the CLI would print")
	}
	if channel := rules.ChannelFor(publisherA); !strings.Contains(
		one.operator().page("/servers/"+publisherA), channel) {
		t.Fatal("the publisher's page does not show its channel")
	}
}
