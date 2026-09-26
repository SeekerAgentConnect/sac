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

func TestJudgesLogInWithANamedPasswordAndSeeTheFeedReference(t *testing.T) {
	ui, stub := startUI(t, nil)
	logged := login(t, ui, "judge1", "secret")
	home := get(t, ui, logged, "/trader")
	if home.status != http.StatusOK {
		t.Fatalf("home %d: %s", home.status, home.body)
	}
	if !strings.Contains(home.body, "seekervault://feed") {
		t.Fatalf("missing feed reference: %s", home.body)
	}
	if strings.Contains(home.body, token) || strings.Contains(home.body, "Authorization") {
		t.Fatalf("API token leaked into HTML: %s", home.body)
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
	if strings.Contains(unknown.body, "nobody") && strings.Contains(unknown.body, "does not exist") {
		t.Fatal("unknown name was distinguished")
	}
}

func TestDeletingAPasswordLineLogsTheJudgeOut(t *testing.T) {
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

func TestCreateListsAndCancelGoThroughTheRequestsAPI(t *testing.T) {
	ui, stub := startUI(t, nil)
	logged := login(t, ui, "judge1", "secret")
	created := post(t, ui, logged, "/trader/create", url.Values{
		"pair": {"usdc-sol"}, "slippage": {"50"}, "expires": {"1h"}, "note": {"demo"},
	})
	if created.status != http.StatusSeeOther {
		t.Fatalf("create %d: %s", created.status, created.body)
	}
	if stub.lastMethod != http.MethodPost || stub.lastPath != "/v1/requests" {
		t.Fatalf("create called %s %s", stub.lastMethod, stub.lastPath)
	}
	if !strings.Contains(string(stub.lastBody), `"input_mint":"`+usdcMint+`"`) {
		t.Fatalf("create body %s", stub.lastBody)
	}
	if strings.Contains(string(stub.lastBody), "amount") {
		t.Fatalf("amount sent to API: %s", stub.lastBody)
	}
	home := get(t, ui, logged, "/trader")
	if !strings.Contains(home.body, "USDC → SOL") || !strings.Contains(home.body, "published") {
		t.Fatalf("list missing signal: %s", home.body)
	}
	cancelled := post(t, ui, logged, "/trader/signals/8c9d0e1f-2a3b-4c5d-8e6f-7a8b9c0d1e2f/cancel", nil)
	if cancelled.status != http.StatusSeeOther {
		t.Fatalf("cancel %d: %s", cancelled.status, cancelled.body)
	}
	if stub.lastPath != "/v1/requests/8c9d0e1f-2a3b-4c5d-8e6f-7a8b9c0d1e2f/cancel" {
		t.Fatalf("cancel path %s", stub.lastPath)
	}
}

func TestAPostWithoutThisOriginIsRefused(t *testing.T) {
	ui, stub := startUI(t, nil)
	logged := login(t, ui, "judge1", "secret")
	request := httptest.NewRequest(http.MethodPost, "http://ui.example/trader/create", strings.NewReader(
		"pair=usdc-sol&slippage=50&expires=1h"))
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	request.Header.Set("Origin", "https://evil.example")
	request.Header.Set("Cookie", logged)
	answered := httptest.NewRecorder()
	ui.ServeHTTP(answered, request)
	if answered.Code != http.StatusForbidden {
		t.Fatalf("status %d: %s", answered.Code, answered.Body.String())
	}
	if stub.creates != 0 {
		t.Fatal("cross-origin create reached the API")
	}
}

func TestAChromeSameOriginFormPostWithANullOriginIsAccepted(t *testing.T) {
	ui, _ := startUI(t, nil)
	request := httptest.NewRequest(http.MethodPost, "https://feeds.example.com/trader/login",
		strings.NewReader(url.Values{"name": {"judge1"}, "password": {"secret"}}.Encode()))
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	request.Header.Set("Origin", "null")
	request.Header.Set("Sec-Fetch-Site", "same-origin")
	answered := httptest.NewRecorder()
	ui.ServeHTTP(answered, request)
	if answered.Code != http.StatusSeeOther || answered.Header().Get("Set-Cookie") == "" {
		t.Fatalf("chrome null origin login answered %d: %s", answered.Code, answered.Body.String())
	}
}

func TestANullOriginWithoutASameOriginFetchIsRefused(t *testing.T) {
	ui, _ := startUI(t, nil)
	request := httptest.NewRequest(http.MethodPost, "https://feeds.example.com/trader/login",
		strings.NewReader(url.Values{"name": {"judge1"}, "password": {"secret"}}.Encode()))
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	request.Header.Set("Origin", "null")
	answered := httptest.NewRecorder()
	ui.ServeHTTP(answered, request)
	if answered.Code != http.StatusForbidden {
		t.Fatalf("status %d: %s", answered.Code, answered.Body.String())
	}
}

func TestAPostFromAForwardedPublicPortIsAccepted(t *testing.T) {
	ui, _ := startUI(t, nil)
	request := httptest.NewRequest(http.MethodPost, "http://copytrading:8096/trader/login",
		strings.NewReader(url.Values{"name": {"judge1"}, "password": {"secret"}}.Encode()))
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	request.Header.Set("Origin", "https://node.ts.net:8443")
	request.Header.Set("X-Forwarded-Proto", "https")
	request.Header.Set("X-Forwarded-Host", "node.ts.net:8443")
	answered := httptest.NewRecorder()
	ui.ServeHTTP(answered, request)
	if answered.Code != http.StatusSeeOther || answered.Header().Get("Set-Cookie") == "" {
		t.Fatalf("login from the forwarded public port answered %d: %s",
			answered.Code, answered.Body.String())
	}
}

func TestExcessLoginFailuresAreRefused(t *testing.T) {
	ui, _ := startUI(t, func(plan *Plan) {
		plan.LoginPerIP = 2
		plan.LoginPerName = 2
	})
	for i := 0; i < 2; i++ {
		answered := post(t, ui, "", "/trader/login", url.Values{"name": {"judge1"}, "password": {"nope"}})
		if answered.status != http.StatusUnauthorized {
			t.Fatalf("attempt %d status %d", i, answered.status)
		}
	}
	answered := post(t, ui, "", "/trader/login", url.Values{"name": {"judge1"}, "password": {"nope"}})
	if answered.status != http.StatusTooManyRequests {
		t.Fatalf("status %d: %s", answered.status, answered.body)
	}
}

func TestSuccessfulLoginsDoNotConsumeFailureLimits(t *testing.T) {
	for _, one := range []struct {
		name         string
		loginPerIP   int
		loginPerName int
	}{
		{name: "per IP", loginPerIP: 1, loginPerName: 10},
		{name: "per name", loginPerIP: 10, loginPerName: 1},
	} {
		t.Run(one.name, func(t *testing.T) {
			ui, _ := startUI(t, func(plan *Plan) {
				plan.LoginPerIP = one.loginPerIP
				plan.LoginPerName = one.loginPerName
			})
			for attempt := 0; attempt < 2; attempt++ {
				answered := post(t, ui, "", "/trader/login", url.Values{
					"name": {"judge1"}, "password": {"secret"},
				})
				if answered.status != http.StatusSeeOther {
					t.Fatalf("successful login %d answered %d: %s", attempt, answered.status, answered.body)
				}
			}
			failed := post(t, ui, "", "/trader/login", url.Values{
				"name": {"judge1"}, "password": {"wrong"},
			})
			if failed.status != http.StatusUnauthorized {
				t.Fatalf("first failure answered %d: %s", failed.status, failed.body)
			}
			limited := post(t, ui, "", "/trader/login", url.Values{
				"name": {"judge1"}, "password": {"wrong"},
			})
			if limited.status != http.StatusTooManyRequests {
				t.Fatalf("failure limit answered %d: %s", limited.status, limited.body)
			}
		})
	}
}

func TestTailscaleProxyPreservesThePublicPortForOriginChecks(t *testing.T) {
	path := filepath.Join("..", "..", "..", "deploy", "server", "Caddyfile.tailscale")
	contents, err := os.ReadFile(path)
	if err != nil {
		t.Skipf("the server deployment is not beside this copied-out publisher module: %v", err)
	}
	config := string(contents)
	forwarded := "header_up X-Forwarded-Host {http.request.hostport}"
	if !strings.Contains(config, forwarded) {
		t.Fatalf("%s does not preserve the browser's original host and public port", forwarded)
	}
}

func TestExcessCreatesAreRefusedWithoutCallingTheAPI(t *testing.T) {
	ui, stub := startUI(t, func(plan *Plan) {
		plan.CreatePerUser = 1
	})
	logged := login(t, ui, "judge1", "secret")
	first := post(t, ui, logged, "/trader/create", url.Values{
		"pair": {"usdc-sol"}, "slippage": {"50"}, "expires": {"1h"},
	})
	if first.status != http.StatusSeeOther {
		t.Fatalf("first create %d", first.status)
	}
	before := stub.creates
	second := post(t, ui, logged, "/trader/create", url.Values{
		"pair": {"sol-usdc"}, "slippage": {"50"}, "expires": {"1h"},
	})
	if second.status != http.StatusSeeOther {
		t.Fatalf("second create %d %s", second.status, second.body)
	}
	if !strings.Contains(second.header.Get("Location"), "error=") {
		t.Fatalf("rate-limited create did not redirect with an error: %s", second.header.Get("Location"))
	}
	if stub.creates != before {
		t.Fatalf("rate-limited create still called the API (%d -> %d)", before, stub.creates)
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
	creates    int
}

func (s *stubAPI) ServeHTTP(writer http.ResponseWriter, request *http.Request) {
	s.mu.Lock()
	s.lastAuth = request.Header.Get("Authorization")
	s.lastPath = request.URL.Path
	s.lastMethod = request.Method
	s.lastBody, _ = io.ReadAll(io.LimitReader(request.Body, 1<<16))
	if request.Method == http.MethodPost && request.URL.Path == "/v1/requests" {
		s.creates++
	}
	s.mu.Unlock()

	writer.Header().Set("Content-Type", "application/json")
	id := "8c9d0e1f-2a3b-4c5d-8e6f-7a8b9c0d1e2f"
	item := map[string]any{
		"request": map[string]any{
			"identity":     map[string]any{"request_id": id},
			"lifecycle":    map[string]any{"status": "REQUEST_STATUS_OPEN", "expires_at": "2026-09-19T13:00:00Z"},
			"presentation": map[string]any{"description": "demo"},
			"action": map[string]any{
				"parameters": []map[string]string{
					{"key": "input_symbol", "text": "USDC"},
					{"key": "output_symbol", "text": "SOL"},
					{"key": "max_slippage_bps", "text": "50"},
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
	case request.URL.Path == "/v1/requests" && request.Method == http.MethodGet:
		_ = json.NewEncoder(writer).Encode(map[string]any{"requests": []any{item}})
	case request.URL.Path == "/v1/requests" && request.Method == http.MethodPost:
		writer.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(writer).Encode(item)
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
		Now:       func() time.Time { return time.Date(2026, 9, 19, 12, 0, 0, 0, time.UTC) },
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
