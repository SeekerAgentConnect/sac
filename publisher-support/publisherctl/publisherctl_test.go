package publisherctl

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// The CLI is a client, so its tests are about what it sends: the method, the path, the headers and
// the body. What the template does with them is the API's own tests
// (publisher-support/api), and the two meeting for real is `publisher-support/publish/gateway_test.go`.
type recorded struct {
	method  string
	path    string
	token   string
	key     string
	kind    string
	body    map[string]any
	answers func(writer http.ResponseWriter, request *http.Request)
}

func serve(t *testing.T, held *recorded) string {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(
		func(writer http.ResponseWriter, request *http.Request) {
			held.method = request.Method
			held.path = request.URL.Path
			held.token = request.Header.Get("Authorization")
			held.key = request.Header.Get("Idempotency-Key")
			held.kind = request.Header.Get("Content-Type")
			if contents, err := io.ReadAll(request.Body); err == nil && len(contents) > 0 {
				_ = json.Unmarshal(contents, &held.body)
			}
			if held.answers != nil {
				held.answers(writer, request)
				return
			}
			writer.Header().Set("Content-Type", "application/json")
			writer.WriteHeader(http.StatusCreated)
			_, _ = writer.Write([]byte(`{"signal":{"proposal_id":"8c9d0e1f-2a3b-4c5d-8e6f-` +
				`7a8b9c0d1e2f","revision":"1","status":"open"},"publication":{"state":` +
				`"published"}}`))
		}))
	t.Cleanup(server.Close)
	return server.URL
}

const token = "jN8nLXQx8yq5Q5xPLZkhHZ1kZ9Wd7oQb2XcFJ0mRtYs"

func out(t *testing.T) (*strings.Builder, *strings.Builder) {
	t.Helper()
	return &strings.Builder{}, &strings.Builder{}
}

var swapTerms = []string{
	"--term", "input_mint=So11111111111111111111111111111111111111112",
	"--term", "input_decimals=9",
	"--term", "output_mint=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
	"--term", "output_decimals=6",
	"--term", "max_slippage_bps=50",
}

func TestCreateSendsTheStatementAndAnIdempotencyKey(t *testing.T) {
	held := &recorded{}
	address := serve(t, held)
	stdout, messages := out(t)

	arguments := append([]string{"create", "--url", address, "--token", token,
		"--in", "2h", "--note", "trimming SOL into USDC"}, swapTerms...)
	if err := Run(arguments, stdout, messages); err != nil {
		t.Fatal(err)
	}
	if held.method != http.MethodPost || held.path != "/v1/requests" {
		t.Fatalf("%s %s", held.method, held.path)
	}
	if held.token != "Bearer "+token {
		t.Fatalf("token %q", held.token)
	}
	if held.kind != "application/json" {
		t.Fatalf("content type %q", held.kind)
	}
	// A create with no key of its own gets one, and it is printed: a caller that needs to retry
	// has to be able to send the same one.
	if held.key == "" {
		t.Fatal("no idempotency key was sent")
	}
	if !strings.Contains(messages.String(), held.key) {
		t.Fatalf("the key was not printed:\n%s", messages)
	}
	// The expiry is absolute by the time it leaves here: this tool works out the instant, because
	// the API takes nothing else.
	expires, ok := held.body["expires_at"].(string)
	if !ok {
		t.Fatalf("body %v", held.body)
	}
	instant, err := time.Parse(time.RFC3339, expires)
	if err != nil {
		t.Fatalf("expires_at %q: %v", expires, err)
	}
	if instant.Before(time.Now().Add(time.Hour)) {
		t.Fatalf("expires_at %s is not two hours from now", expires)
	}
	terms, ok := held.body["terms"].(map[string]any)
	if !ok || len(terms) != 5 {
		t.Fatalf("terms %v", held.body["terms"])
	}
	if terms["input_mint"] != "So11111111111111111111111111111111111111112" {
		t.Fatalf("terms %v", terms)
	}
	// The answer is on stdout, so a script can read it, and the summary is not.
	if !strings.Contains(stdout.String(), `"proposal_id"`) {
		t.Fatalf("stdout:\n%s", stdout)
	}
	if strings.Contains(stdout.String(), "publication published") {
		t.Fatalf("the summary is on stdout:\n%s", stdout)
	}
	if !strings.Contains(messages.String(), "publication published") {
		t.Fatalf("messages:\n%s", messages)
	}
}

func TestAKeyThatWasGivenIsTheKeyThatIsSent(t *testing.T) {
	held := &recorded{}
	address := serve(t, held)
	stdout, messages := out(t)
	arguments := append([]string{"create", "--url", address, "--token", token,
		"--expires", "2026-09-17T21:00:00Z", "--key", "desk-1-sol-usdc"}, swapTerms...)
	if err := Run(arguments, stdout, messages); err != nil {
		t.Fatal(err)
	}
	if held.key != "desk-1-sol-usdc" {
		t.Fatalf("key %q", held.key)
	}
	if held.body["expires_at"] != "2026-09-17T21:00:00Z" {
		t.Fatalf("expires_at %v", held.body["expires_at"])
	}
}

// Terms may come from a file, which is what a program that already has them does, and a --term
// wins over the file so a value can be overridden on the command line.
func TestTermsMayComeFromAFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "terms.json")
	if err := os.WriteFile(path, []byte(`{
	  "input_mint": "So11111111111111111111111111111111111111112",
	  "input_decimals": "9",
	  "output_mint": "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
	  "output_decimals": "6",
	  "max_slippage_bps": "50"
	}`), 0o600); err != nil {
		t.Fatal(err)
	}
	held := &recorded{}
	address := serve(t, held)
	stdout, messages := out(t)
	if err := Run([]string{"create", "--url", address, "--token", token, "--in", "30m",
		"--terms-file", path, "--term", "max_slippage_bps=80"}, stdout, messages); err != nil {
		t.Fatal(err)
	}
	terms := held.body["terms"].(map[string]any)
	if len(terms) != 5 || terms["max_slippage_bps"] != "80" {
		t.Fatalf("terms %v", terms)
	}
}

func TestEveryCommandIsOneCall(t *testing.T) {
	const id = "8c9d0e1f-2a3b-4c5d-8e6f-7a8b9c0d1e2f"
	for _, one := range []struct {
		arguments []string
		method    string
		path      string
	}{
		{[]string{"status"}, http.MethodGet, "/v1/status"},
		{[]string{"list"}, http.MethodGet, "/v1/requests"},
		{[]string{"show", id}, http.MethodGet, "/v1/requests/" + id},
		{[]string{"cancel", id}, http.MethodPost, "/v1/requests/" + id + "/cancel"},
		{[]string{"retry", id}, http.MethodPost, "/v1/requests/" + id + "/retry"},
		{append([]string{"update", "--in", "1h"}, append(swapTerms, id)...), http.MethodPut,
			"/v1/requests/" + id},
		// The two a template that discovers its own signals has (SEE-96). They are the same kind
		// of thing as the rest: one call, no privileged path.
		{[]string{"discovery"}, http.MethodGet, "/v1/discovery"},
		{[]string{"poll"}, http.MethodPost, "/v1/discovery/poll"},
	} {
		t.Run(one.arguments[0], func(t *testing.T) {
			held := &recorded{}
			address := serve(t, held)
			stdout, messages := out(t)
			arguments := append(one.arguments, "--url", address, "--token", token)
			if err := Run(arguments, stdout, messages); err != nil {
				t.Fatal(err)
			}
			if held.method != one.method || held.path != one.path {
				t.Fatalf("%s %s, expected %s %s", held.method, held.path, one.method, one.path)
			}
			// Only a create mints a key: an update, a withdrawal and a retry are idempotent by
			// what they are.
			if held.key != "" {
				t.Fatalf("%s sent an idempotency key", one.arguments[0])
			}
		})
	}
}

// The reference is printed alone, because it is the one string an operator passes on: into a
// README, a QR code, or a message to whoever is subscribing.
func TestTheReferenceIsPrintedAlone(t *testing.T) {
	const reference = "seekervault://feed?v=1&gateway=https%3A%2F%2Ffeeds.example.com" +
		"&server=3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
	held := &recorded{answers: func(writer http.ResponseWriter, _ *http.Request) {
		writer.Header().Set("Content-Type", "application/json")
		_, _ = writer.Write([]byte(`{"manifest":{"server_id":"x"},"reference":"` + reference +
			`"}`))
	}}
	address := serve(t, held)
	stdout, messages := out(t)
	if err := Run([]string{"reference", "--url", address, "--token", token}, stdout,
		messages); err != nil {
		t.Fatal(err)
	}
	if held.path != "/v1/manifest" {
		t.Fatalf("path %s", held.path)
	}
	if strings.TrimSpace(stdout.String()) != reference {
		t.Fatalf("stdout %q", stdout.String())
	}
}

// What a caller is told when it has not said enough, or has said two things that mean one.
func TestWhatTheToolRefusesToSend(t *testing.T) {
	held := &recorded{}
	address := serve(t, held)
	for _, one := range []struct {
		name      string
		arguments []string
		says      string
	}{
		{"no command", []string{}, "name a command"},
		{"an unknown command", []string{"publish"}, "unknown command"},
		{"no expiry", append([]string{"create"}, swapTerms...), "--expires"},
		{"both expiries", append([]string{"create", "--in", "1h", "--expires",
			"2026-09-17T21:00:00Z"}, swapTerms...), "pass one"},
		{"an expiry that is not an instant", append([]string{"create", "--expires", "tomorrow"},
			swapTerms...), "RFC 3339"},
		{"no terms", []string{"create", "--in", "1h"}, "--term"},
		{"a term that is not key=value", []string{"create", "--in", "1h", "--term", "input_mint"},
			"key=value"},
		{"no signal ID", []string{"show"}, "one signal ID"},
		{"two signal IDs", []string{"show", "one", "two"}, "one signal ID"},
	} {
		t.Run(one.name, func(t *testing.T) {
			stdout, messages := out(t)
			arguments := one.arguments
			if len(arguments) > 0 {
				arguments = append(arguments, "--url", address, "--token", token)
			}
			err := Run(arguments, stdout, messages)
			if err == nil {
				t.Fatalf("it was sent: %s", stdout)
			}
			if !strings.Contains(err.Error()+messages.String(), one.says) {
				t.Fatalf("%v / %s, expected to mention %q", err, messages, one.says)
			}
			if held.method != "" {
				t.Fatalf("a %s reached the template", held.method)
			}
		})
	}
}

// The token is required, because the API requires it: a tool that called without one would only
// ever be answered 401, and saying so here is a better error.
func TestTheTokenIsRequired(t *testing.T) {
	stdout, messages := out(t)
	t.Setenv("PUBLISHER_API_TOKEN", "")
	if err := Run([]string{"status", "--url", "http://127.0.0.1:8092"}, stdout,
		messages); err == nil || !strings.Contains(err.Error(), "PUBLISHER_API_TOKEN") {
		t.Fatalf("%v", err)
	}
}

// A refusal is passed through as it came — the answer on stdout, the sentence for a person on
// stderr — and the tool exits non-zero, so a script does not have to parse anything to know.
func TestARefusalIsReportedAndIsNotSuccess(t *testing.T) {
	held := &recorded{answers: func(writer http.ResponseWriter, _ *http.Request) {
		writer.Header().Set("Content-Type", "application/json")
		writer.WriteHeader(http.StatusBadRequest)
		_, _ = writer.Write([]byte(`{"error":"not_a_mint","term":"input_mint",` +
			`"detail":"must be an exact base58 mint address"}`))
	}}
	address := serve(t, held)
	stdout, messages := out(t)
	arguments := append([]string{"create", "--url", address, "--token", token, "--in", "1h"},
		swapTerms...)
	err := Run(arguments, stdout, messages)
	if err == nil {
		t.Fatal("a refused create was reported as success")
	}
	if !strings.Contains(stdout.String(), "not_a_mint") {
		t.Fatalf("stdout:\n%s", stdout)
	}
	if !strings.Contains(messages.String(), "not_a_mint (input_mint)") {
		t.Fatalf("messages:\n%s", messages)
	}
}

// A signal the template is holding but has not published yet is said out loud, because "stored
// here" and "everybody can read it" are different things to a trader.
func TestAPendingPublicationIsSaidOutLoud(t *testing.T) {
	held := &recorded{answers: func(writer http.ResponseWriter, _ *http.Request) {
		writer.Header().Set("Content-Type", "application/json")
		writer.WriteHeader(http.StatusAccepted)
		_, _ = writer.Write([]byte(`{"signal":{"proposal_id":"8c9d0e1f-2a3b-4c5d-8e6f-` +
			`7a8b9c0d1e2f","revision":"1","status":"open"},"publication":{"state":"pending"}}`))
	}}
	address := serve(t, held)
	stdout, messages := out(t)
	arguments := append([]string{"create", "--url", address, "--token", token, "--in", "1h"},
		swapTerms...)
	if err := Run(arguments, stdout, messages); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(messages.String(), "will be published when the gateway answers") {
		t.Fatalf("messages:\n%s", messages)
	}
}

// A template that is not there is a message about the address, not a stack trace.
func TestATemplateThatIsNotThereIsNamed(t *testing.T) {
	stdout, messages := out(t)
	err := Run([]string{"status", "--url", "http://127.0.0.1:1", "--token", token}, stdout,
		messages)
	if err == nil || !strings.Contains(err.Error(), "127.0.0.1:1") {
		t.Fatalf("%v", err)
	}
}
