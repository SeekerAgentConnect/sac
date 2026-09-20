package admin_test

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/admin"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/credential"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage/sqlite"
)

// What the operator's surface is held to. It is a browser-facing administrative API over the one
// operation in this service that grants the ability to publish, so the tests are mostly about who
// is refused: an anonymous visitor, a publisher holding a valid publishing credential, a form that
// came from somewhere else, and a session that has ended.

const (
	password  = "a-long-enough-operator-password"
	publisher = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
	other     = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
	at        = "/admin"
)

// openLimiter is a limiter that allows everything, for the tests that are not about rate limiting.
type openLimiter struct{}

func (openLimiter) Allow(string) bool { return true }

// countedLimiter allows a fixed number of attempts, so the login limit is a test rather than a wait.
type countedLimiter struct {
	mutex sync.Mutex
	left  int
}

func (c *countedLimiter) Allow(string) bool {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	if c.left <= 0 {
		return false
	}
	c.left--
	return true
}

type fixture struct {
	t         *testing.T
	server    *httptest.Server
	admin     *admin.Server
	documents *sqlite.Store
	client    *http.Client
	clock     time.Time
	mutex     sync.Mutex
}

func newFixture(t *testing.T, logins admin.Limiter) *fixture {
	t.Helper()
	documents, err := sqlite.Open(filepath.Join(t.TempDir(), "broadcast.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = documents.Close() })

	encoded, err := credential.HashPassword(password)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := credential.ParsePassword(encoded)
	if err != nil {
		t.Fatal(err)
	}
	one := &fixture{t: t, documents: documents, clock: time.Date(2026, 9, 21, 10, 0, 0, 0, time.UTC)}
	surface, err := admin.New(admin.Options{
		Path:            at,
		Password:        parsed,
		SessionLifetime: time.Hour,
		PublicURL:       "https://feeds.example.com",
		PublisherURL:    "https://feeds.example.com",
		Store:           documents,
		Logins:          logins,
		Caller:          func(*http.Request) string { return "test" },
		Log:             slog.New(slog.NewTextHandler(io.Discard, nil)),
		Now:             one.now,
	})
	if err != nil {
		t.Fatal(err)
	}
	one.admin = surface
	one.server = httptest.NewServer(surface)
	t.Cleanup(one.server.Close)

	jar, err := cookiejar.New(nil)
	if err != nil {
		t.Fatal(err)
	}
	one.client = &http.Client{
		Jar: jar,
		// Every redirect this surface makes is part of a flow a test wants to see, so nothing is
		// followed automatically: a test asserts the 303 and then asks for what it points at.
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	return one
}

func (f *fixture) now() time.Time {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	return f.clock
}

func (f *fixture) advance(by time.Duration) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	f.clock = f.clock.Add(by)
}

func (f *fixture) get(path string) *http.Response {
	f.t.Helper()
	response, err := f.client.Get(f.server.URL + path)
	if err != nil {
		f.t.Fatal(err)
	}
	return response
}

func (f *fixture) post(path string, form url.Values) *http.Response {
	f.t.Helper()
	request, err := http.NewRequest(http.MethodPost, f.server.URL+path,
		strings.NewReader(form.Encode()))
	if err != nil {
		f.t.Fatal(err)
	}
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	// What a browser sends when the form is on the page it is posting to.
	request.Header.Set("Sec-Fetch-Site", "same-origin")
	response, err := f.client.Do(request)
	if err != nil {
		f.t.Fatal(err)
	}
	return response
}

func (f *fixture) logIn() {
	f.t.Helper()
	response := f.post(at+"/login", url.Values{"password": {password}})
	defer func() { _ = response.Body.Close() }()
	if response.StatusCode != http.StatusSeeOther {
		f.t.Fatalf("logging in answered %s", response.Status)
	}
}

// token is the CSRF value the page currently carries, read out of the rendered form the way a
// browser would submit it.
func (f *fixture) token(path string) string {
	f.t.Helper()
	response := f.get(path)
	defer func() { _ = response.Body.Close() }()
	body := read(f.t, response)
	const marker = `name="csrf" value="`
	start := strings.Index(body, marker)
	if start < 0 {
		f.t.Fatalf("%s carries no CSRF token", path)
	}
	rest := body[start+len(marker):]
	return rest[:strings.Index(rest, `"`)]
}

func read(t *testing.T, response *http.Response) string {
	t.Helper()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	return string(body)
}

// --- who is refused ------------------------------------------------------------

// An anonymous visitor gets a login form and learns nothing else. Not the list, not a publisher's
// page, and no mutation: the surface administers the one operation that grants the ability to
// publish, so "not logged in" has to mean "not here at all".
func TestAnonymousVisitorsReachNothing(t *testing.T) {
	one := newFixture(t, openLimiter{})
	registerDirectly(t, one.documents, publisher, "before")

	for _, path := range []string{at + "/", at + "/servers/" + publisher, at + "/reveal"} {
		response := one.get(path)
		body := read(t, response)
		_ = response.Body.Close()
		if response.StatusCode != http.StatusSeeOther {
			t.Fatalf("%s answered %s for an anonymous visitor", path, response.Status)
		}
		if where := response.Header.Get("Location"); where != at+"/login" {
			t.Fatalf("%s sent an anonymous visitor to %q", path, where)
		}
		if strings.Contains(body, publisher) {
			t.Fatalf("%s named a publisher to an anonymous visitor", path)
		}
	}

	for _, path := range []string{
		at + "/servers",
		at + "/servers/" + publisher + "/rotate",
		at + "/servers/" + publisher + "/revoke",
		at + "/servers/" + publisher + "/forget",
	} {
		response := one.post(path, url.Values{"csrf": {"anything"}, "confirm": {publisher}})
		_ = response.Body.Close()
		if response.StatusCode != http.StatusUnauthorized {
			t.Fatalf("%s answered %s for an anonymous mutation", path, response.Status)
		}
	}
	credentials, err := one.documents.Credentials(context.Background(), publisher)
	if err != nil {
		t.Fatal(err)
	}
	if len(credentials) != 1 {
		t.Fatalf("an anonymous visitor changed the store: %d credential(s)", len(credentials))
	}
}

// A publishing credential is not an administrator credential. It is the one confusion that would
// turn every publisher into an operator, so it is checked by presenting a real, valid one.
func TestAPublishingCredentialIsNotAdministrativeAuthority(t *testing.T) {
	one := newFixture(t, openLimiter{})
	secret := registerDirectly(t, one.documents, publisher, "before")

	for _, header := range []string{"Authorization", "X-Api-Key"} {
		request, err := http.NewRequest(http.MethodGet, one.server.URL+at+"/", nil)
		if err != nil {
			t.Fatal(err)
		}
		request.Header.Set(header, "Bearer "+secret)
		response, err := one.client.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		body := read(t, response)
		_ = response.Body.Close()
		if response.StatusCode != http.StatusSeeOther || strings.Contains(body, publisher) {
			t.Fatalf("a publishing credential in %s reached the admin list: %s", header,
				response.Status)
		}
	}
	// And it is not the password either.
	response := one.post(at+"/login", url.Values{"password": {secret}})
	_ = response.Body.Close()
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("a publishing credential logged in: %s", response.Status)
	}
}

func TestTheWrongPasswordIsRefusedAndTheRightOneIsNot(t *testing.T) {
	one := newFixture(t, openLimiter{})
	response := one.post(at+"/login", url.Values{"password": {"not-the-password"}})
	_ = response.Body.Close()
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("the wrong password answered %s", response.Status)
	}
	if one.admin.Sessions() != 0 {
		t.Fatal("a refused login left a session behind")
	}
	one.logIn()
	if one.admin.Sessions() != 1 {
		t.Fatalf("a login left %d sessions", one.admin.Sessions())
	}
}

func TestLoginsAreRateLimited(t *testing.T) {
	one := newFixture(t, &countedLimiter{left: 2})
	for attempt := range 2 {
		response := one.post(at+"/login", url.Values{"password": {"wrong"}})
		_ = response.Body.Close()
		if response.StatusCode != http.StatusUnauthorized {
			t.Fatalf("attempt %d answered %s", attempt, response.Status)
		}
	}
	// The third is refused before the password is looked at — so even the right one is refused.
	response := one.post(at+"/login", url.Values{"password": {password}})
	_ = response.Body.Close()
	if response.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("the third attempt answered %s", response.Status)
	}
	if one.admin.Sessions() != 0 {
		t.Fatal("a rate-limited login created a session")
	}
}

func TestLogoutAndExpiryBothEndASession(t *testing.T) {
	one := newFixture(t, openLimiter{})
	one.logIn()
	token := one.token(at + "/")
	response := one.post(at+"/logout", url.Values{"csrf": {token}})
	_ = response.Body.Close()
	if one.admin.Sessions() != 0 {
		t.Fatal("logging out left the session behind")
	}

	one.logIn()
	if one.admin.Sessions() != 1 {
		t.Fatal("logging in again did not start a session")
	}
	one.advance(time.Hour + time.Minute)
	response = one.get(at + "/")
	_ = response.Body.Close()
	if response.StatusCode != http.StatusSeeOther {
		t.Fatalf("an expired session answered %s", response.Status)
	}
	if one.admin.Sessions() != 0 {
		t.Fatal("an expired session is still held")
	}
}

// Every mutation carries the session's own token, and a cross-site form post is refused before the
// token is even considered.
func TestMutationsNeedTheSessionsOwnToken(t *testing.T) {
	one := newFixture(t, openLimiter{})
	one.logIn()
	token := one.token(at + "/")

	for _, wrong := range []url.Values{
		{},
		{"csrf": {"not-the-token"}},
		{"csrf": {token + "x"}},
	} {
		form := url.Values{"label": {"attempt"}, "generate": {"on"}}
		for key, value := range wrong {
			form[key] = value
		}
		response := one.post(at+"/servers", form)
		_ = response.Body.Close()
		if response.StatusCode != http.StatusForbidden {
			t.Fatalf("a form with %v answered %s", wrong, response.Status)
		}
	}

	// A real token, from a page opened in another site's frame or posted from another origin.
	request, err := http.NewRequest(http.MethodPost, one.server.URL+at+"/servers",
		strings.NewReader(url.Values{"csrf": {token}, "label": {"x"}, "generate": {"on"}}.Encode()))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	request.Header.Set("Sec-Fetch-Site", "cross-site")
	response, err := one.client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	_ = response.Body.Close()
	if response.StatusCode != http.StatusForbidden {
		t.Fatalf("a cross-site post answered %s", response.Status)
	}

	publishers, err := one.documents.Publishers(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(publishers) != 0 {
		t.Fatalf("a refused form registered %d publisher(s)", len(publishers))
	}
}

// --- what an operator can do ----------------------------------------------------

func TestRegisteringThroughTheUIGrantsPublishingImmediately(t *testing.T) {
	one := newFixture(t, openLimiter{})
	one.logIn()

	response := one.post(at+"/servers", url.Values{
		"csrf":   {one.token(at + "/")},
		"server": {publisher},
		"label":  {"copy trading"},
		"host":   {"https://Example.com/pub/"},
	})
	_ = response.Body.Close()
	if response.StatusCode != http.StatusSeeOther ||
		response.Header.Get("Location") != at+"/reveal" {
		t.Fatalf("registering answered %s to %q", response.Status, response.Header.Get("Location"))
	}

	shown := one.get(at + "/reveal")
	body := read(t, shown)
	_ = shown.Body.Close()
	secret := secretIn(t, body)
	if !credential.Valid(secret) {
		t.Fatalf("the credential shown is not one: %q", secret)
	}
	for _, expected := range []string{
		publisher, rules.ChannelFor(publisher), "https://feeds.example.com",
		"PublishManifest", "shown once",
	} {
		if !strings.Contains(body, expected) {
			t.Fatalf("the connection information does not mention %q", expected)
		}
	}

	// The store holds the registration, the host is canonical, and the credential resolves —
	// which is the whole of what the running gateway needs to accept a publication. Nothing was
	// restarted and no environment variable changed.
	ctx := context.Background()
	held, err := one.documents.Publisher(ctx, publisher)
	if err != nil || held == nil {
		t.Fatalf("the publisher was not registered: %v", err)
	}
	if held.Host != "https://example.com/pub" {
		t.Fatalf("the host was recorded as %q", held.Host)
	}
	resolved, err := one.documents.PublisherFor(ctx, credential.Hash(secret))
	if err != nil || resolved != publisher {
		t.Fatalf("the credential resolved to %q: %v", resolved, err)
	}
}

// The raw credential is shown exactly once and appears nowhere afterwards. A reload of the page it
// was shown on gets the list instead, and neither the list nor the detail page carries it.
func TestARawCredentialIsShownOnceAndNeverAgain(t *testing.T) {
	one := newFixture(t, openLimiter{})
	one.logIn()
	response := one.post(at+"/servers", url.Values{
		"csrf": {one.token(at + "/")}, "server": {publisher}, "label": {"once"},
	})
	_ = response.Body.Close()

	first := one.get(at + "/reveal")
	secret := secretIn(t, read(t, first))
	_ = first.Body.Close()

	again := one.get(at + "/reveal")
	_ = again.Body.Close()
	if again.StatusCode != http.StatusSeeOther {
		t.Fatalf("the credential page answered %s a second time", again.Status)
	}

	for _, path := range []string{at + "/", at + "/servers/" + publisher} {
		page := one.get(path)
		body := read(t, page)
		_ = page.Body.Close()
		if strings.Contains(body, secret) {
			t.Fatalf("%s carries the raw credential", path)
		}
	}
}

func TestRegisteringAnIdentityThatExistsIsRefusedWithoutChangingIt(t *testing.T) {
	one := newFixture(t, openLimiter{})
	before := registerDirectly(t, one.documents, publisher, "first")
	one.logIn()

	response := one.post(at+"/servers", url.Values{
		"csrf": {one.token(at + "/")}, "server": {publisher}, "label": {"second"},
	})
	body := read(t, response)
	_ = response.Body.Close()
	if response.StatusCode != http.StatusConflict {
		t.Fatalf("registering an existing identity answered %s", response.Status)
	}
	if !strings.Contains(body, "already registered") {
		t.Fatal("the refusal does not say why")
	}

	ctx := context.Background()
	credentials, err := one.documents.Credentials(ctx, publisher)
	if err != nil {
		t.Fatal(err)
	}
	if len(credentials) != 1 {
		t.Fatalf("the refused registration left %d credential(s)", len(credentials))
	}
	held, err := one.documents.Publisher(ctx, publisher)
	if err != nil || held.Label != "first" {
		t.Fatalf("the existing registration was replaced: %+v", held)
	}
	if resolved, _ := one.documents.PublisherFor(ctx, credential.Hash(before)); resolved != publisher {
		t.Fatal("the existing credential stopped working")
	}
}

func TestAnInvalidRegistrationIsRefusedAndNothingIsWritten(t *testing.T) {
	one := newFixture(t, openLimiter{})
	one.logIn()
	for _, form := range []url.Values{
		{"server": {"not-a-uuid"}, "label": {"x"}},
		{"server": {publisher}},
		{"server": {publisher}, "label": {"x"}, "host": {"javascript:alert(1)"}},
		{"server": {publisher}, "label": {"x"}, "generate": {"on"}},
		{"server": {publisher}, "label": {strings.Repeat("x", admin.MaxLabelBytes+1)}},
	} {
		form["csrf"] = []string{one.token(at + "/")}
		response := one.post(at+"/servers", form)
		_ = response.Body.Close()
		if response.StatusCode != http.StatusBadRequest {
			t.Fatalf("%v answered %s", form, response.Status)
		}
	}
	publishers, err := one.documents.Publishers(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(publishers) != 0 {
		t.Fatalf("a refused registration wrote %d publisher(s)", len(publishers))
	}
}

// A generated ID is a real one, and the page says the publisher has to use exactly it.
func TestAGeneratedServerIdIsSaidToBeBinding(t *testing.T) {
	one := newFixture(t, openLimiter{})
	one.logIn()
	response := one.post(at+"/servers", url.Values{
		"csrf": {one.token(at + "/")}, "generate": {"on"}, "label": {"new publisher"},
	})
	_ = response.Body.Close()

	shown := one.get(at + "/reveal")
	body := read(t, shown)
	_ = shown.Body.Close()
	if !strings.Contains(body, "exactly") {
		t.Fatal("a generated ID is not said to be binding on the publisher")
	}
	publishers, err := one.documents.Publishers(context.Background())
	if err != nil || len(publishers) != 1 {
		t.Fatalf("a generated registration wrote %d publisher(s): %v", len(publishers), err)
	}
	if !rules.IsID(publishers[0].ServerID) {
		t.Fatalf("the generated ID is not one: %q", publishers[0].ServerID)
	}
}

// Rotation is additive: the existing credential keeps working until it is revoked, which is what
// lets a publisher switch without an outage.
func TestRotationLeavesTheOldCredentialWorkingUntilItIsRevoked(t *testing.T) {
	one := newFixture(t, openLimiter{})
	first := registerDirectly(t, one.documents, publisher, "first")
	untouched := registerDirectly(t, one.documents, other, "another publisher")
	one.logIn()

	response := one.post(at+"/servers/"+publisher+"/rotate", url.Values{
		"csrf": {one.token(at + "/servers/" + publisher)}, "label": {"second"},
	})
	_ = response.Body.Close()
	shown := one.get(at + "/reveal")
	second := secretIn(t, read(t, shown))
	_ = shown.Body.Close()

	ctx := context.Background()
	for _, secret := range []string{first, second} {
		if resolved, _ := one.documents.PublisherFor(ctx, credential.Hash(secret)); resolved != publisher {
			t.Fatal("both credentials must work during a rotation")
		}
	}

	response = one.post(at+"/servers/"+publisher+"/revoke", url.Values{
		"csrf":       {one.token(at + "/servers/" + publisher)},
		"credential": {credential.ID(credential.Hash(first))},
	})
	_ = response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("revoking answered %s", response.Status)
	}
	if resolved, _ := one.documents.PublisherFor(ctx, credential.Hash(first)); resolved != "" {
		t.Fatal("a revoked credential still resolves")
	}
	if resolved, _ := one.documents.PublisherFor(ctx, credential.Hash(second)); resolved != publisher {
		t.Fatal("the new credential stopped working when the old one was revoked")
	}
	// And another publisher is untouched throughout.
	if resolved, _ := one.documents.PublisherFor(ctx, credential.Hash(untouched)); resolved != other {
		t.Fatal("revoking one publisher's credential affected another's")
	}
}

// A credential ID is a prefix of a hash, and the store revokes whatever it uniquely names. A
// revocation therefore has to name a credential of the publisher whose page it came from, or the
// record of what happened would name the wrong publisher.
func TestARevocationOnlyEndsThisPublishersCredential(t *testing.T) {
	one := newFixture(t, openLimiter{})
	registerDirectly(t, one.documents, publisher, "first")
	elsewhere := registerDirectly(t, one.documents, other, "another publisher")
	one.logIn()

	response := one.post(at+"/servers/"+publisher+"/revoke", url.Values{
		"csrf":       {one.token(at + "/servers/" + publisher)},
		"credential": {credential.ID(credential.Hash(elsewhere))},
	})
	body := read(t, response)
	_ = response.Body.Close()
	if response.StatusCode != http.StatusBadRequest {
		t.Fatalf("revoking another publisher's credential answered %s", response.Status)
	}
	if !strings.Contains(body, "does not belong to this publisher") {
		t.Fatal("the refusal does not say why")
	}
	if resolved, _ := one.documents.PublisherFor(
		context.Background(), credential.Hash(elsewhere)); resolved != other {
		t.Fatal("another publisher's credential was revoked from this page")
	}
}

func TestRevokingEveryCredentialLeavesWhatWasPublished(t *testing.T) {
	one := newFixture(t, openLimiter{})
	secret := registerDirectly(t, one.documents, publisher, "first")
	one.logIn()
	response := one.post(at+"/servers/"+publisher+"/revoke", url.Values{
		"csrf": {one.token(at + "/servers/" + publisher)}, "all": {"on"},
	})
	body := read(t, response)
	_ = response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("revoking all answered %s", response.Status)
	}
	if !strings.Contains(body, "still served") {
		t.Fatal("revocation does not say that publications stay")
	}
	ctx := context.Background()
	if resolved, _ := one.documents.PublisherFor(ctx, credential.Hash(secret)); resolved != "" {
		t.Fatal("a revoked credential still resolves")
	}
	if held, err := one.documents.Publisher(ctx, publisher); err != nil || held == nil {
		t.Fatal("revoking every credential removed the registration")
	}
}

// Forgetting is destructive and is not a button next to the others: the publisher's own ID has to
// be typed back before anything happens.
func TestForgettingNeedsThePublishersOwnIdTypedBack(t *testing.T) {
	one := newFixture(t, openLimiter{})
	registerDirectly(t, one.documents, publisher, "first")
	one.logIn()
	ctx := context.Background()

	for _, confirm := range []string{"", "yes", other} {
		response := one.post(at+"/servers/"+publisher+"/forget", url.Values{
			"csrf": {one.token(at + "/servers/" + publisher)}, "confirm": {confirm},
		})
		_ = response.Body.Close()
		if response.StatusCode != http.StatusBadRequest {
			t.Fatalf("confirming with %q answered %s", confirm, response.Status)
		}
		if held, _ := one.documents.Publisher(ctx, publisher); held == nil {
			t.Fatalf("confirming with %q removed the publisher", confirm)
		}
	}

	response := one.post(at+"/servers/"+publisher+"/forget", url.Values{
		"csrf": {one.token(at + "/servers/" + publisher)}, "confirm": {publisher},
	})
	_ = response.Body.Close()
	if response.StatusCode != http.StatusSeeOther {
		t.Fatalf("forgetting answered %s", response.Status)
	}
	if held, _ := one.documents.Publisher(ctx, publisher); held != nil {
		t.Fatal("the publisher is still registered")
	}
	// And the page it returns to says what was and was not removed.
	page := one.get(at + "/")
	body := read(t, page)
	_ = page.Body.Close()
	if !strings.Contains(body, "keep their own copies") {
		t.Fatal("forgetting does not say that phones keep what they read")
	}
}

// The list says what the store can prove and nothing more: whether a credential exists and whether
// anything has been published. There is no subscriber to count and no connection to report.
func TestTheListDescribesOnlyWhatIsDurablyKnown(t *testing.T) {
	one := newFixture(t, openLimiter{})
	one.logIn()

	empty := one.get(at + "/")
	body := read(t, empty)
	_ = empty.Body.Close()
	if !strings.Contains(body, "No publisher servers are registered") ||
		!strings.Contains(body, "Add server") {
		t.Fatal("the empty state does not offer to add a server")
	}

	registerDirectly(t, one.documents, publisher, "copy trading")
	page := one.get(at + "/")
	body = read(t, page)
	_ = page.Body.Close()
	for _, expected := range []string{"Publishing enabled", "No publications yet", publisher} {
		if !strings.Contains(body, expected) {
			t.Fatalf("the list does not say %q", expected)
		}
	}
	for _, forbidden := range []string{"Connected", "Online", "subscriber", "Subscribers"} {
		if strings.Contains(body, forbidden) {
			t.Fatalf("the list claims %q, which this service cannot know", forbidden)
		}
	}

	if _, err := one.documents.RevokeAll(context.Background(), publisher, one.now()); err != nil {
		t.Fatal(err)
	}
	page = one.get(at + "/")
	body = read(t, page)
	_ = page.Body.Close()
	if !strings.Contains(body, "No active credentials") {
		t.Fatal("the list does not say that a publisher has no active credentials")
	}
}

// --- the surface itself ----------------------------------------------------------

func TestEveryAnswerCarriesItsSecurityHeaders(t *testing.T) {
	one := newFixture(t, openLimiter{})
	response := one.get(at + "/login")
	defer func() { _ = response.Body.Close() }()
	for header, expected := range map[string]string{
		"X-Frame-Options":        "DENY",
		"Referrer-Policy":        "no-referrer",
		"X-Content-Type-Options": "nosniff",
		"Cache-Control":          "no-store, max-age=0",
	} {
		if got := response.Header.Get(header); got != expected {
			t.Fatalf("%s is %q", header, got)
		}
	}
	policy := response.Header.Get("Content-Security-Policy")
	for _, expected := range []string{
		"default-src 'none'", "script-src 'self'", "style-src 'self'", "frame-ancestors 'none'",
	} {
		if !strings.Contains(policy, expected) {
			t.Fatalf("the content policy is %q", policy)
		}
	}
}

// The stylesheet and the script come out of the image, and nothing else does.
func TestOnlyTheGatewaysOwnAssetsAreServed(t *testing.T) {
	one := newFixture(t, openLimiter{})
	for _, name := range []string{"admin.css", "admin.js"} {
		response := one.get(at + "/assets/" + name)
		body := read(t, response)
		_ = response.Body.Close()
		if response.StatusCode != http.StatusOK || body == "" {
			t.Fatalf("%s answered %s", name, response.Status)
		}
	}
	for _, name := range []string{"../admin.go", "admin.html", "..%2Fadmin.go"} {
		response := one.get(at + "/assets/" + name)
		_ = response.Body.Close()
		if response.StatusCode == http.StatusOK {
			t.Fatalf("%s was served", name)
		}
	}
}

// Nothing outside the configured prefix is answered, so an ingress can forward one path and be sure
// that is the whole surface.
func TestNothingOutsideThePrefixExists(t *testing.T) {
	one := newFixture(t, openLimiter{})
	one.logIn()
	for _, path := range []string{"/", "/login", "/servers", "/healthz", "/adminx/"} {
		response := one.get(path)
		_ = response.Body.Close()
		if response.StatusCode != http.StatusNotFound {
			t.Fatalf("%s answered %s", path, response.Status)
		}
	}
}

func TestAHostIsMetadataAndIsHeldToWhatIsSafeToShow(t *testing.T) {
	for _, one := range []struct{ raw, want string }{
		{"", ""},
		{"  ", ""},
		{"https://Example.COM/", "https://example.com"},
		{"https://example.com:443/pub/", "https://example.com/pub"},
		{"http://localhost:8080", "http://localhost:8080"},
	} {
		got, err := admin.Host(one.raw)
		if err != nil || got != one.want {
			t.Fatalf("Host(%q) = %q, %v", one.raw, got, err)
		}
	}
	for _, raw := range []string{
		"javascript:alert(1)", "data:text/html,x", "file:///etc/passwd", "example.com",
		"https://user:secret@example.com", "https://example.com?query=1", "https://example.com#x",
		"https://" + strings.Repeat("x", admin.MaxHostBytes) + ".com",
	} {
		if got, err := admin.Host(raw); err == nil {
			t.Fatalf("Host(%q) was accepted as %q", raw, got)
		}
	}
}

func TestAPasswordHashCannotBeTurnedBackIntoAPassword(t *testing.T) {
	encoded, err := credential.HashPassword(password)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(encoded, password) {
		t.Fatal("the encoded hash contains the password")
	}
	parsed, err := credential.ParsePassword(encoded)
	if err != nil {
		t.Fatal(err)
	}
	if !parsed.Verify(password) {
		t.Fatal("the password does not verify against its own hash")
	}
	for _, wrong := range []string{password + "x", "", strings.ToUpper(password)} {
		if parsed.Verify(wrong) {
			t.Fatalf("%q verified", wrong)
		}
	}
	// A second hash of the same password differs, because the salt does.
	twice, err := credential.HashPassword(password)
	if err != nil {
		t.Fatal(err)
	}
	if twice == encoded {
		t.Fatal("two hashes of one password are identical: the salt is not random")
	}
	for _, malformed := range []string{
		"", "plain", "pbkdf2-sha256.1.c2FsdA.aGFzaA", "bcrypt.1.a.b",
		"pbkdf2-sha256.600000.!!!.aGFzaA",
	} {
		if _, err := credential.ParsePassword(malformed); err == nil {
			t.Fatalf("%q parsed as a password hash", malformed)
		}
	}
	if _, err := credential.HashPassword("short"); err == nil {
		t.Fatal("a short password was accepted")
	}
}

// --- helpers --------------------------------------------------------------------

// registerDirectly is what feed-gatewayctl does, so the tests that are about the UI reading or
// changing existing state start from state the CLI could have made.
func registerDirectly(t *testing.T, documents *sqlite.Store, serverID, label string) string {
	t.Helper()
	secret, hash := credential.New()
	if _, err := documents.Register(context.Background(),
		storage.Registration{ServerID: serverID, Label: label}, hash,
		time.Date(2026, 9, 20, 9, 0, 0, 0, time.UTC)); err != nil {
		t.Fatal(err)
	}
	return secret
}

// secretIn reads the one credential a reveal page shows, out of the element it is rendered in.
func secretIn(t *testing.T, body string) string {
	t.Helper()
	const marker = `<code id="secret">`
	start := strings.Index(body, marker)
	if start < 0 {
		t.Fatalf("no credential was shown:\n%s", body)
	}
	rest := body[start+len(marker):]
	return rest[:strings.Index(rest, "<")]
}
