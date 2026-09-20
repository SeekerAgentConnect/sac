// Package demotest is a whole demo as a test drives it: a real store, the real drainer, the real
// business API, and a gateway of our own at the end of it (SEE-134).
//
// It is exported, and here rather than in one package's test files, because both the support
// library's own API tests and the Prediction demo's discovery tests drive exactly this. The
// Prediction demo composes the same frame with its reconciler, so a driver only the library could
// use would mean the demo testing a second, similar setup instead of the one it ships.
//
// It is a package of its own rather than part of publishertest because it assembles the store, the
// drainer, the client and the API, and those packages' own tests use the gateway doubles in
// publishertest — one package would put an import cycle under all of them.
package demotest

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"connectrpc.com/connect"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/api"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/environment"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/gateway"
	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/gateway/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/manifest"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/publish"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/publishertest"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/store"
)

// Token is the business-API grant every test presents, Proposal the identity the first signal is
// minted with, and USDC the mint a swap's terms name.
const (
	Token    = "jN8nLXQx8yq5Q5xPLZkhHZ1kZ9Wd7oQb2XcFJ0mRtYs"
	Proposal = "8c9d0e1f-2a3b-4c5d-8e6f-7a8b9c0d1e2f"
	USDC     = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
)

// Template is a whole demo as a test drives it.
type Template struct {
	t *testing.T
	// API is the demo's business API, running.
	API     *httptest.Server
	Store   *store.Store
	Gateway *publishertest.FakeGateway
	Drainer *publish.Drainer
	// Log is everything the demo wrote, so a test can assert that a credential never reached a
	// line of it.
	Log       *strings.Builder
	Settings  manifest.Settings
	identity  int
	ForceIDs  []string
	NowValue  time.Time
	PathValue string
	// Wrap, if set during the discovering callback, is applied around the API handler before the
	// test server starts. The Prediction demo uses it to overlay discovery search and select.
	Wrap func(http.Handler) http.Handler
}

// Start is a CopyTrading-shaped demo: its signals are written by its callers.
func Start(t *testing.T, fake *publishertest.FakeGateway) *Template {
	t.Helper()
	return StartWith(t, fake, signals.Swap{}, nil)
}

// StartIn is [Start] in the environment the deployment was configured with (SEE-97), for the tests
// that are about what a sandbox deployment answers.
func StartIn(t *testing.T, fake *publishertest.FakeGateway, named environment.Environment) *Template {
	t.Helper()
	return StartWith(t, fake, signals.Swap{}, func(held *Template, plan *api.Plan) {
		// Both, because the plan took its copy before this ran: the template's own settings are
		// what its assertions read, and the plan's are what the API answers from.
		held.Settings.Environment = named
		plan.Settings.Environment = named
	})
}

// StartWith is [Start] with the kind the demo registered, and a chance to change the plan
// before the API is built — which is what the Prediction demo's own tests need, because that is
// where its reconciler and its market rows are supplied.
func StartWith(
	t *testing.T,
	fake *publishertest.FakeGateway,
	kind signals.Kind,
	discovering func(*Template, *api.Plan),
) *Template {
	t.Helper()
	path := filepath.Join(t.TempDir(), "publisher.db")
	settings := manifest.Settings{
		ServerID:    publishertest.ServerID,
		GatewayURL:  "https://feeds.example.com",
		Environment: "production",
		Requirement: kind.Requirement(),
		DisplayName: "Copy trading desk",
	}
	documents, err := store.Open(path, store.Stamp{
		ServerID:    settings.ServerID,
		Environment: settings.Environment.String(),
		GatewayURL:  settings.GatewayURL,
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = documents.Close() })

	upstream := publishertest.Serve(t, fake)
	client, err := gateway.New(gateway.Options{
		URL:        upstream.URL,
		Credential: publishertest.Credential,
		Timeout:    5 * time.Second,
		HTTP:       upstream.Client(),
	})
	if err != nil {
		t.Fatal(err)
	}

	// The log is a buffer, so a test can assert that a credential never reaches a line of it.
	written := &strings.Builder{}
	log := slog.New(slog.NewTextHandler(written, nil))
	held := &Template{
		t:        t,
		Store:    documents,
		Gateway:  fake,
		Log:      written,
		Settings: settings,
		NowValue: publishertest.Now,
	}
	held.Drainer = publish.NewDrainer(publish.Plan{
		Documents: documents,
		Gateway:   client,
		ServerID:  settings.ServerID,
		Manifest: func(revision uint64) *serverv1.ServerManifest {
			return manifest.Document(settings, revision)
		},
		Log:     log,
		Now:     func() time.Time { return held.NowValue },
		Backoff: func(int) time.Duration { return time.Minute },
	})
	plan := api.Plan{
		Documents: documents,
		Drainer:   held.Drainer,
		Kind:      kind,
		Settings:  settings,
		Token:     Token,
		Log:       log,
		Now:       func() time.Time { return held.NowValue },
		NewID:     held.NextID,
	}
	held.PathValue = path
	if discovering != nil {
		discovering(held, &plan)
	}
	handler := api.New(plan).Handler()
	if held.Wrap != nil {
		handler = held.Wrap(handler)
	}
	held.API = httptest.NewServer(handler)
	t.Cleanup(held.API.Close)
	return held
}

// NextID mints the identities a test can then name, so an assertion does not have to read one out
// of an answer before it can make the next call.
func (h *Template) NextID() string {
	h.identity++
	if h.identity <= len(h.ForceIDs) {
		return h.ForceIDs[h.identity-1]
	}
	return "8c9d0e1f-2a3b-4c5d-8e6f-7a8b9c0d1e2" + string(rune('a'+h.identity-1))
}

// answer is one call's outcome as a test reads it.
// Answer is one call's outcome as a test reads it.
type Answer struct {
	Status int
	Body   map[string]any
	Raw    string
}

func (a Answer) Signal() map[string]any {
	held, _ := a.Body["signal"].(map[string]any)
	return held
}

func (a Answer) Publication() map[string]any {
	held, _ := a.Body["publication"].(map[string]any)
	return held
}

func (a Answer) State() string {
	held, _ := a.Publication()["state"].(string)
	return held
}

func (a Answer) Problem() string {
	held, _ := a.Body["error"].(string)
	return held
}

func (a Answer) Term() string {
	held, _ := a.Body["term"].(string)
	return held
}

// call is one HTTP request to the template, with the token unless a test says otherwise.
// Call makes one authorized request against the demo's business API.
func (h *Template) Call(method, path string, body any, headers ...string) Answer {
	h.t.Helper()
	var payload io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			h.t.Fatal(err)
		}
		payload = bytes.NewReader(encoded)
	}
	request, err := http.NewRequest(method, h.API.URL+path, payload)
	if err != nil {
		h.t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+Token)
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	for index := 0; index+1 < len(headers); index += 2 {
		if headers[index+1] == "" {
			request.Header.Del(headers[index])
			continue
		}
		request.Header.Set(headers[index], headers[index+1])
	}
	response, err := h.API.Client().Do(request)
	if err != nil {
		h.t.Fatal(err)
	}
	defer func() { _ = response.Body.Close() }()
	contents, err := io.ReadAll(response.Body)
	if err != nil {
		h.t.Fatal(err)
	}
	read := Answer{Status: response.StatusCode, Raw: string(contents)}
	if len(contents) > 0 {
		if err := json.Unmarshal(contents, &read.Body); err != nil {
			h.t.Fatalf("%s %s answered something that is not JSON: %s", method, path, contents)
		}
	}
	return read
}

// raw is a request with a body this package's own types could not produce, for the decoding rules.
// Raw is [Template.Call] with a body that is sent exactly as written.
func (h *Template) Raw(method, path, body string, headers ...string) Answer {
	h.t.Helper()
	request, err := http.NewRequest(method, h.API.URL+path, strings.NewReader(body))
	if err != nil {
		h.t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+Token)
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Idempotency-Key", "key-raw")
	for index := 0; index+1 < len(headers); index += 2 {
		request.Header.Set(headers[index], headers[index+1])
	}
	response, err := h.API.Client().Do(request)
	if err != nil {
		h.t.Fatal(err)
	}
	defer func() { _ = response.Body.Close() }()
	contents, _ := io.ReadAll(response.Body)
	read := Answer{Status: response.StatusCode, Raw: string(contents)}
	if len(contents) > 0 {
		_ = json.Unmarshal(contents, &read.Body)
	}
	return read
}

// SwapStatement is a swap signal as a caller sends one.
func SwapStatement() map[string]any {
	return map[string]any{
		"expires_at": publishertest.Now.Add(time.Hour).Format(time.RFC3339),
		"note":       "trimming SOL into USDC",
		"terms": map[string]string{
			signals.InputMint:      signals.WrappedSOL,
			signals.InputDecimals:  "9",
			signals.OutputMint:     USDC,
			signals.OutputDecimals: "6",
			signals.MaxSlippageBps: "50",
		},
	}
}

// Create posts one signal under an idempotency key.
func (h *Template) Create(key string, body map[string]any) Answer {
	h.t.Helper()
	return h.Call(http.MethodPost, "/v1/signals", body, "Idempotency-Key", key)
}

// Refusal is a gateway that says no, in the gateway's own shape.
func Refusal(kind gatewayv1.GatewayProblem, code connect.Code) func(string) error {
	return func(string) error {
		failure := connect.NewError(code, errors.New(strings.ToLower(
			strings.TrimPrefix(kind.String(), "GATEWAY_PROBLEM_"))))
		if detail, err := connect.NewErrorDetail(&gatewayv1.GatewayErrorDetail{
			Problem: kind,
			Field:   "channel",
		}); err == nil {
			failure.AddDetail(detail)
		}
		return failure
	}
}
