package admin

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

const token = "publisher-api-token-publisher-api-token"

func TestOperatorsLogInAndSeeTheFeedReference(t *testing.T) {
	ui, stub := startUI(t, nil)
	logged := login(t, ui, "judge1", "secret")
	home := get(t, ui, logged, "/trader")
	if home.status != http.StatusOK {
		t.Fatalf("home %d: %s", home.status, home.body)
	}
	if !strings.Contains(home.body, "seekervault://feed") {
		t.Fatalf("missing feed reference: %s", home.body)
	}
	if !strings.Contains(home.body, "POLY-2589813") {
		t.Fatalf("missing published market: %s", home.body)
	}
	if strings.Contains(home.body, token) || strings.Contains(home.body, "Authorization") {
		t.Fatalf("API token leaked into HTML: %s", home.body)
	}
	if !strings.Contains(home.header.Get("Content-Security-Policy"), "script-src 'unsafe-inline'") {
		t.Fatalf("csp %s", home.header.Get("Content-Security-Policy"))
	}
	if strings.Contains(home.body, `name="amount"`) || strings.Contains(strings.ToLower(home.body), "wallet") {
		t.Fatalf("amount or wallet field present: %s", home.body)
	}
	if stub.lastAuth != "Bearer "+token {
		t.Fatalf("publisher API was not called with the token: %q", stub.lastAuth)
	}
}

func TestTheHomeReferenceIsAlsoShownAsAScannableQRCode(t *testing.T) {
	ui, _ := startUI(t, nil)
	logged := login(t, ui, "judge1", "secret")
	home := get(t, ui, logged, "/trader")
	for _, expected := range []string{
		`aria-label="QR code of the feed reference"`,
		`<path fill="#000" d="`,
		"Or scan it with SAC — Add connection → Scan QR code.",
	} {
		if !strings.Contains(home.body, expected) {
			t.Fatalf("the reference is not drawn as a scannable code beside its text:\n%s", home.body)
		}
	}
	// The drawing is the encoder's own matrix of exactly the reference the input shows.
	if !matchesTheEncoder(t, qrSVG(t, home.body),
		"seekervault://feed?v=1&gateway=https%3A%2F%2Ffeeds.example.com&server=3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d") {
		t.Fatal("the drawn QR is not the feed reference")
	}
}

func TestTheSessionCookieIsHttpOnlySecureStrictAndPathScoped(t *testing.T) {
	ui, _ := startUI(t, nil)
	answered := post(t, ui, "", "/trader/login", url.Values{
		"name": {"judge1"}, "password": {"secret"},
	})
	cookie := answered.header.Get("Set-Cookie")
	if cookie == "" {
		t.Fatal("no session cookie")
	}
	for _, want := range []string{"HttpOnly", "Secure", "SameSite=Strict", "Path=/trader"} {
		if !strings.Contains(cookie, want) {
			t.Fatalf("cookie missing %s: %s", want, cookie)
		}
	}
	if strings.Contains(cookie, token) {
		t.Fatalf("token in cookie: %s", cookie)
	}
}

func TestUnknownAndWrongPasswordsShareAMessage(t *testing.T) {
	ui, _ := startUI(t, nil)
	unknown := post(t, ui, "", "/trader/login", url.Values{"name": {"nobody"}, "password": {"secret"}})
	wrong := post(t, ui, "", "/trader/login", url.Values{"name": {"judge1"}, "password": {"nope"}})
	if unknown.status != http.StatusUnauthorized || wrong.status != http.StatusUnauthorized {
		t.Fatalf("status unknown %d wrong %d", unknown.status, wrong.status)
	}
	if !strings.Contains(unknown.body, "name or password is wrong") ||
		!strings.Contains(wrong.body, "name or password is wrong") {
		t.Fatalf("messages differed:\n%s\n%s", unknown.body, wrong.body)
	}
}

func TestDeletingAPasswordLineLogsTheOperatorOut(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "admin-passwords")
	if err := os.WriteFile(path, []byte(mustHash(t, "judge1", "secret")+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	ui, _ := startUIAt(t, path, nil)
	logged := login(t, ui, "judge1", "secret")
	if get(t, ui, logged, "/trader").status != http.StatusOK {
		t.Fatal("logged-in home failed")
	}
	time.Sleep(time.Millisecond)
	if err := os.WriteFile(path, []byte(""), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(path, time.Now(), time.Now()); err != nil {
		t.Fatal(err)
	}
	home := get(t, ui, logged, "/trader")
	if home.status != http.StatusSeeOther || home.header.Get("Location") != "/trader/login" {
		t.Fatalf("revoked session still served the console: %d %s", home.status, home.header.Get("Location"))
	}
}

func TestSearchAndSelectGoThroughDiscovery(t *testing.T) {
	ui, stub := startUI(t, nil)
	logged := login(t, ui, "judge1", "secret")
	searched := get(t, ui, logged, "/trader?search=1&category=crypto&keywords=eth")
	if searched.status != http.StatusOK {
		t.Fatalf("search %d: %s", searched.status, searched.body)
	}
	if stub.lastPath != "/v1/discovery/markets?category=crypto&keywords=eth" &&
		!strings.HasPrefix(stub.lastPath, "/v1/discovery/markets?") {
		t.Fatalf("search path %s", stub.lastPath)
	}
	if !strings.Contains(searched.body, "Will ETH hit 10k") {
		t.Fatalf("search missing listing: %s", searched.body)
	}
	if !strings.Contains(searched.body, "Publish to the feed") {
		t.Fatalf("missing select: %s", searched.body)
	}
	if !strings.Contains(searched.body, "Showing up to 10 matching markets") {
		t.Fatalf("missing list cap: %s", searched.body)
	}
	if !strings.Contains(searched.body, `data-busy="Searching…"`) ||
		!strings.Contains(searched.body, "busy-mask") {
		t.Fatalf("missing loading state: %s", searched.body)
	}
	selected := post(t, ui, logged, "/trader/select", url.Values{"market_id": {"POLY-999"}})
	if selected.status != http.StatusSeeOther {
		t.Fatalf("select %d: %s", selected.status, selected.body)
	}
	if stub.lastMethod != http.MethodPost || stub.lastPath != "/v1/discovery/select" {
		t.Fatalf("select called %s %s", stub.lastMethod, stub.lastPath)
	}
	if !strings.Contains(string(stub.lastBody), `"market_id":"POLY-999"`) {
		t.Fatalf("select body %s", stub.lastBody)
	}
}

func TestAPostWithoutThisOriginIsRefused(t *testing.T) {
	ui, stub := startUI(t, nil)
	logged := login(t, ui, "judge1", "secret")
	request := httptest.NewRequest(http.MethodPost, "http://ui.example/trader/select", strings.NewReader(
		"market_id=POLY-999"))
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	request.Header.Set("Origin", "https://evil.example")
	request.Header.Set("Cookie", logged)
	answered := httptest.NewRecorder()
	ui.ServeHTTP(answered, request)
	if answered.Code != http.StatusForbidden {
		t.Fatalf("status %d: %s", answered.Code, answered.Body.String())
	}
	if stub.selects != 0 {
		t.Fatal("cross-origin select reached the API")
	}
}

func TestExcessSelectsAreRefusedWithoutCallingTheAPI(t *testing.T) {
	ui, stub := startUI(t, func(plan *Plan) {
		plan.SelectPerUser = 1
	})
	logged := login(t, ui, "judge1", "secret")
	first := post(t, ui, logged, "/trader/select", url.Values{"market_id": {"POLY-999"}})
	if first.status != http.StatusSeeOther {
		t.Fatalf("first select %d", first.status)
	}
	before := stub.selects
	second := post(t, ui, logged, "/trader/select", url.Values{"market_id": {"POLY-998"}})
	if second.status != http.StatusSeeOther {
		t.Fatalf("second select %d %s", second.status, second.body)
	}
	if !strings.Contains(second.header.Get("Location"), "error=") {
		t.Fatalf("rate-limited select did not redirect with an error: %s", second.header.Get("Location"))
	}
	if stub.selects != before {
		t.Fatalf("rate-limited select still called the API (%d -> %d)", before, stub.selects)
	}
}

func TestHealthzIsNotThePublicTraderPage(t *testing.T) {
	ui, _ := startUI(t, nil)
	answered := get(t, ui, "", "/healthz")
	if answered.status != http.StatusOK || strings.TrimSpace(answered.body) != "ok" {
		t.Fatalf("health %d %q", answered.status, answered.body)
	}
}

type stubAPI struct {
	mu         sync.Mutex
	lastAuth   string
	lastPath   string
	lastMethod string
	lastBody   []byte
	selects    int
}

func (s *stubAPI) ServeHTTP(writer http.ResponseWriter, request *http.Request) {
	s.mu.Lock()
	s.lastAuth = request.Header.Get("Authorization")
	s.lastPath = request.URL.Path
	if request.URL.RawQuery != "" {
		s.lastPath += "?" + request.URL.RawQuery
	}
	s.lastMethod = request.Method
	s.lastBody, _ = io.ReadAll(io.LimitReader(request.Body, 1<<16))
	if request.Method == http.MethodPost && request.URL.Path == "/v1/discovery/select" {
		s.selects++
	}
	s.mu.Unlock()

	writer.Header().Set("Content-Type", "application/json")
	id := "8c9d0e1f-2a3b-4c5d-8e6f-7a8b9c0d1e2f"
	item := map[string]any{
		"request": map[string]any{
			"identity":     map[string]any{"request_id": id},
			"lifecycle":    map[string]any{"status": "REQUEST_STATUS_OPEN", "expires_at": "2026-09-20T13:00:00Z"},
			"presentation": map[string]any{"description": "Listed on Jupiter Prediction"},
			"action": map[string]any{
				"parameters": []map[string]string{
					{"key": "market_id", "text": "POLY-2589813"},
					{"key": "event_id", "text": "POLY-606422"},
					{"key": "provider", "text": "polymarket"},
				},
			},
		},
		"publication": map[string]any{"state": "published"},
	}
	switch {
	case request.URL.Path == "/v1/manifest":
		_ = json.NewEncoder(writer).Encode(map[string]any{
			"reference": "seekervault://feed?v=1&gateway=https%3A%2F%2Ffeeds.example.com&server=3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
			"manifest":  map[string]any{"environments": []string{"sandbox"}},
		})
	case request.URL.Path == "/v1/discovery":
		_ = json.NewEncoder(writer).Encode(map[string]any{
			"filters": map[string]any{"source": "polymarket", "categories": []string{"crypto"}},
			"markets": []any{},
		})
	case request.URL.Path == "/v1/discovery/markets":
		_ = json.NewEncoder(writer).Encode(map[string]any{
			"markets": []any{map[string]any{
				"market_id":   "POLY-999",
				"event_id":    "POLY-100",
				"title":       "Yes",
				"event_title": "Will ETH hit 10k",
				"category":    "crypto",
				"state":       "open",
				"published":   false,
			}},
		})
	case request.URL.Path == "/v1/requests" && request.Method == http.MethodGet:
		_ = json.NewEncoder(writer).Encode(map[string]any{"requests": []any{item}})
	case request.URL.Path == "/v1/discovery/select":
		writer.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(writer).Encode(map[string]any{
			"market_id": "POLY-999", "title": "Yes", "state": "open", "publication": "published",
		})
	default:
		_ = json.NewEncoder(writer).Encode(item)
	}
}

func startUI(t *testing.T, tweak func(*Plan)) (http.Handler, *stubAPI) {
	t.Helper()
	return startUIAt(t, "", tweak)
}

func startUIAt(t *testing.T, passwordPath string, tweak func(*Plan)) (http.Handler, *stubAPI) {
	t.Helper()
	if passwordPath == "" {
		passwordPath = filepath.Join(t.TempDir(), "admin-passwords")
		if err := os.WriteFile(passwordPath, []byte(mustHash(t, "judge1", "secret")+"\n"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	passwords, err := OpenFile(passwordPath)
	if err != nil {
		t.Fatal(err)
	}
	stub := &stubAPI{}
	upstream := httptest.NewServer(stub)
	t.Cleanup(upstream.Close)
	plan := Plan{
		Config: &Config{
			PublicPath:    "/trader",
			APIURL:        upstream.URL,
			APIToken:      token,
			SessionSecret: strings.Repeat("s", 43),
			PasswordsFile: passwordPath,
		},
		Passwords: passwords,
		Log:       slog.New(slog.NewTextHandler(io.Discard, nil)),
		Now:       func() time.Time { return time.Date(2026, 9, 20, 12, 0, 0, 0, time.UTC) },
		HTTP:      upstream.Client(),
	}
	if tweak != nil {
		tweak(&plan)
	}
	return New(plan).Handler(), stub
}

type result struct {
	status int
	header http.Header
	body   string
}

func get(t *testing.T, handler http.Handler, cookie, path string) result {
	t.Helper()
	request := httptest.NewRequest(http.MethodGet, "https://feeds.example.com"+path, nil)
	if cookie != "" {
		request.Header.Set("Cookie", cookie)
	}
	answered := httptest.NewRecorder()
	handler.ServeHTTP(answered, request)
	return result{status: answered.Code, header: answered.Header(), body: answered.Body.String()}
}

func post(t *testing.T, handler http.Handler, cookie, path string, form url.Values) result {
	t.Helper()
	var body io.Reader
	if form != nil {
		body = strings.NewReader(form.Encode())
	}
	request := httptest.NewRequest(http.MethodPost, "https://feeds.example.com"+path, body)
	request.Header.Set("Origin", "https://feeds.example.com")
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	if cookie != "" {
		request.Header.Set("Cookie", cookie)
	}
	answered := httptest.NewRecorder()
	handler.ServeHTTP(answered, request)
	return result{status: answered.Code, header: answered.Header(), body: answered.Body.String()}
}

func login(t *testing.T, handler http.Handler, name, password string) string {
	t.Helper()
	answered := post(t, handler, "", "/trader/login", url.Values{
		"name": {name}, "password": {password},
	})
	if answered.status != http.StatusSeeOther {
		t.Fatalf("login %d: %s", answered.status, answered.body)
	}
	cookie := answered.header.Get("Set-Cookie")
	if cookie == "" {
		t.Fatal("login set no cookie")
	}
	parts := strings.SplitN(cookie, ";", 2)
	return parts[0]
}
