package api

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/protobuf/proto"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/gateway/v1/gatewayv1connect"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/proposal/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/manifest"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/publish"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/signals"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/store"
)

const (
	server   = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
	token    = "jN8nLXQx8yq5Q5xPLZkhHZ1kZ9Wd7oQb2XcFJ0mRtYs"
	proposal = "8c9d0e1f-2a3b-4c5d-8e6f-7a8b9c0d1e2f"
	usdc     = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
)

var now = time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)

// The same fake gateway internal/publish is tested against, in this package because a test file
// is not importable. It serves the generated handler, so everything between the template and it is
// real — the protocol, the codec, the headers, the error details — and it emulates only the one
// gateway rule this side depends on: the same document again is "unchanged".
type fakeGateway struct {
	mutex     sync.Mutex
	manifests []*serverv1.ServerManifest
	proposals []*proposalv1.Proposal
	held      map[string]*proposalv1.Proposal
	refuse    func(procedure string) error
}

func (f *fakeGateway) PublishManifest(
	ctx context.Context,
	request *connect.Request[gatewayv1.PublishManifestRequest],
) (*connect.Response[gatewayv1.PublishManifestResponse], error) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	if f.refuse != nil {
		if err := f.refuse("PublishManifest"); err != nil {
			return nil, err
		}
	}
	f.manifests = append(f.manifests, proto.CloneOf(request.Msg.GetManifest()))
	return connect.NewResponse(&gatewayv1.PublishManifestResponse{
		Status:           gatewayv1.PublishStatus_PUBLISH_STATUS_STORED,
		SettingsRevision: request.Msg.GetManifest().GetSettingsRevision(),
	}), nil
}

func (f *fakeGateway) PublishProposal(
	ctx context.Context,
	request *connect.Request[gatewayv1.PublishProposalRequest],
) (*connect.Response[gatewayv1.PublishProposalResponse], error) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	if f.refuse != nil {
		if err := f.refuse("PublishProposal"); err != nil {
			return nil, err
		}
	}
	document := request.Msg.GetProposal()
	// The gateway's own rule, emulated because a test about withdrawal depends on it: a cancelled
	// status is not a publication (rules.Proposal, GATEWAY_PROBLEM_CANCEL_ON_PUBLISH).
	if document.GetStatus() == proposalv1.ProposalStatus_PROPOSAL_STATUS_CANCELLED {
		failure := connect.NewError(connect.CodeInvalidArgument, errors.New("cancel_on_publish"))
		if detail, err := connect.NewErrorDetail(&gatewayv1.GatewayErrorDetail{
			Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_CANCEL_ON_PUBLISH,
			Field:   "status",
		}); err == nil {
			failure.AddDetail(detail)
		}
		return nil, failure
	}
	f.proposals = append(f.proposals, proto.CloneOf(document))
	if f.held == nil {
		f.held = map[string]*proposalv1.Proposal{}
	}
	status := gatewayv1.PublishStatus_PUBLISH_STATUS_STORED
	if proto.Equal(f.held[document.GetProposalId()], document) {
		status = gatewayv1.PublishStatus_PUBLISH_STATUS_UNCHANGED
	} else {
		f.held[document.GetProposalId()] = proto.CloneOf(document)
	}
	return connect.NewResponse(&gatewayv1.PublishProposalResponse{
		Status:   status,
		Revision: document.GetRevision(),
	}), nil
}

func (f *fakeGateway) CancelProposal(
	ctx context.Context,
	request *connect.Request[gatewayv1.CancelProposalRequest],
) (*connect.Response[gatewayv1.CancelProposalResponse], error) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	if f.refuse != nil {
		if err := f.refuse("CancelProposal"); err != nil {
			return nil, err
		}
	}
	held := f.held[request.Msg.GetProposalId()]
	if held == nil {
		failure := connect.NewError(connect.CodeNotFound, errors.New("no_such_proposal"))
		if detail, err := connect.NewErrorDetail(&gatewayv1.GatewayErrorDetail{
			Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_PROPOSAL,
			Field:   "proposal_id",
		}); err == nil {
			failure.AddDetail(detail)
		}
		return nil, failure
	}
	withdrawn := proto.CloneOf(held)
	withdrawn.Revision = request.Msg.GetRevision()
	withdrawn.Status = proposalv1.ProposalStatus_PROPOSAL_STATUS_CANCELLED
	f.held[request.Msg.GetProposalId()] = withdrawn
	return connect.NewResponse(&gatewayv1.CancelProposalResponse{
		Status:   gatewayv1.PublishStatus_PUBLISH_STATUS_STORED,
		Proposal: withdrawn,
	}), nil
}

func (f *fakeGateway) documents() []*proposalv1.Proposal {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	return append([]*proposalv1.Proposal{}, f.proposals...)
}

func (f *fakeGateway) holding(id string) *proposalv1.Proposal {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	return f.held[id]
}

// template is a whole template as a test drives it: a real store, the real drainer, the real API,
// and a gateway of our own at the end of it.
type template struct {
	t         *testing.T
	api       *httptest.Server
	store     *store.Store
	gateway   *fakeGateway
	drainer   *publish.Drainer
	log       *strings.Builder
	settings  manifest.Settings
	identity  int
	forceIDs  []string
	nowValue  time.Time
	pathValue string
}

func start(t *testing.T, fake *fakeGateway) *template {
	t.Helper()
	return startWith(t, fake, signals.Swap{}, nil)
}

// startWith is start with the kind the template registered, and a chance to change the plan before
// the API is built — which is what the Prediction template's own tests need (discovery_test.go).
func startWith(
	t *testing.T,
	fake *fakeGateway,
	kind signals.Kind,
	discovering func(*template, *Plan),
) *template {
	t.Helper()
	path := filepath.Join(t.TempDir(), "publisher.db")
	settings := manifest.Settings{
		ServerID:    server,
		GatewayURL:  "https://feeds.example.com",
		Environment: "production",
		Requirement: kind.Requirement(),
		DisplayName: "Copy trading desk",
	}
	documents, err := store.Open(path, store.Stamp{
		ServerID:    settings.ServerID,
		Environment: settings.Environment,
		GatewayURL:  settings.GatewayURL,
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = documents.Close() })

	mux := http.NewServeMux()
	mux.Handle(gatewayv1connect.NewPublisherServiceHandler(fake))
	upstream := httptest.NewServer(mux)
	t.Cleanup(upstream.Close)
	gateway, err := publish.New(publish.Options{
		URL:        upstream.URL,
		Credential: "bhnRxR4o5RPDqYKTJZfmfbv9OmGGKFNxBOebFHYGJuc",
		Timeout:    5 * time.Second,
		HTTP:       upstream.Client(),
	})
	if err != nil {
		t.Fatal(err)
	}

	// The log is a buffer, so a test can assert that a credential never reaches a line of it.
	written := &strings.Builder{}
	log := slog.New(slog.NewTextHandler(written, nil))
	held := &template{
		t:        t,
		store:    documents,
		gateway:  fake,
		log:      written,
		settings: settings,
		nowValue: now,
	}
	held.drainer = publish.NewDrainer(publish.Plan{
		Documents: documents,
		Gateway:   gateway,
		ServerID:  settings.ServerID,
		Manifest: func(revision uint64) *serverv1.ServerManifest {
			return manifest.Document(settings, revision)
		},
		Log:     log,
		Now:     func() time.Time { return held.nowValue },
		Backoff: func(int) time.Duration { return time.Minute },
	})
	plan := Plan{
		Documents: documents,
		Drainer:   held.drainer,
		Kind:      kind,
		Settings:  settings,
		Token:     token,
		Log:       log,
		Now:       func() time.Time { return held.nowValue },
		NewID:     held.nextID,
	}
	held.pathValue = path
	if discovering != nil {
		discovering(held, &plan)
	}
	held.api = httptest.NewServer(New(plan).Handler())
	t.Cleanup(held.api.Close)
	return held
}

// nextID mints the identities a test can then name, so an assertion does not have to read one out
// of an answer before it can make the next call.
func (h *template) nextID() string {
	h.identity++
	if h.identity <= len(h.forceIDs) {
		return h.forceIDs[h.identity-1]
	}
	return "8c9d0e1f-2a3b-4c5d-8e6f-7a8b9c0d1e2" + string(rune('a'+h.identity-1))
}

// answer is one call's outcome as a test reads it.
type answer struct {
	status int
	body   map[string]any
	raw    string
}

func (a answer) signal() map[string]any {
	held, _ := a.body["signal"].(map[string]any)
	return held
}

func (a answer) publication() map[string]any {
	held, _ := a.body["publication"].(map[string]any)
	return held
}

func (a answer) state() string {
	held, _ := a.publication()["state"].(string)
	return held
}

func (a answer) problem() string {
	held, _ := a.body["error"].(string)
	return held
}

func (a answer) term() string {
	held, _ := a.body["term"].(string)
	return held
}

// call is one HTTP request to the template, with the token unless a test says otherwise.
func (h *template) call(method, path string, body any, headers ...string) answer {
	h.t.Helper()
	var payload io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			h.t.Fatal(err)
		}
		payload = bytes.NewReader(encoded)
	}
	request, err := http.NewRequest(method, h.api.URL+path, payload)
	if err != nil {
		h.t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+token)
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
	response, err := h.api.Client().Do(request)
	if err != nil {
		h.t.Fatal(err)
	}
	defer func() { _ = response.Body.Close() }()
	contents, err := io.ReadAll(response.Body)
	if err != nil {
		h.t.Fatal(err)
	}
	read := answer{status: response.StatusCode, raw: string(contents)}
	if len(contents) > 0 {
		if err := json.Unmarshal(contents, &read.body); err != nil {
			h.t.Fatalf("%s %s answered something that is not JSON: %s", method, path, contents)
		}
	}
	return read
}

// raw is a request with a body this package's own types could not produce, for the decoding rules.
func (h *template) raw(method, path, body string, headers ...string) answer {
	h.t.Helper()
	request, err := http.NewRequest(method, h.api.URL+path, strings.NewReader(body))
	if err != nil {
		h.t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Idempotency-Key", "key-raw")
	for index := 0; index+1 < len(headers); index += 2 {
		request.Header.Set(headers[index], headers[index+1])
	}
	response, err := h.api.Client().Do(request)
	if err != nil {
		h.t.Fatal(err)
	}
	defer func() { _ = response.Body.Close() }()
	contents, _ := io.ReadAll(response.Body)
	read := answer{status: response.StatusCode, raw: string(contents)}
	if len(contents) > 0 {
		_ = json.Unmarshal(contents, &read.body)
	}
	return read
}

// swapStatement is a swap signal as a caller sends one.
func swapStatement() map[string]any {
	return map[string]any{
		"expires_at": now.Add(time.Hour).Format(time.RFC3339),
		"note":       "trimming SOL into USDC",
		"terms": map[string]string{
			signals.InputMint:      signals.WrappedSOL,
			signals.InputDecimals:  "9",
			signals.OutputMint:     usdc,
			signals.OutputDecimals: "6",
			signals.MaxSlippageBps: "50",
		},
	}
}

func (h *template) create(key string, body map[string]any) answer {
	h.t.Helper()
	return h.call(http.MethodPost, "/v1/signals", body, "Idempotency-Key", key)
}

// refusal is a gateway that says no, in the gateway's own shape.
func refusal(kind gatewayv1.GatewayProblem, code connect.Code) func(string) error {
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
