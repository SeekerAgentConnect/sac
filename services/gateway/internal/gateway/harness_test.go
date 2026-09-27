// The end-to-end tests of the feed gateway (SEE-90).
//
// They drive the real thing: the handlers the binary serves, over a real HTTP listener, with the
// generated Connect clients, against a real SQLite file. Nothing here stands in for a layer — the
// only things injected are the clock, so that expiry and retention are assertions rather than
// waits, and the fan-out: a broker is a service, and what these tests are about is what the gateway
// sends it and what it grants a listener, not whether Centrifugo works (internal/stream's own tests
// drive a real one).
//
// The tests are in the external test package on purpose: they may use only what a publisher or a
// phone could use, so a test cannot pass by reaching inside the service.
package gateway_test

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/config"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/dispatch"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gateway"
	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1/gatewayv1connect"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/proposal/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/pushrelay"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/relay"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage/sqlite"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/stream"
)

// The two publishers every test has available, and the proposals they publish. They are the
// identities the phone's own tests use, so a document from here is one the phone's validators can
// be pointed at without translating anything (packages/protocol/proto/fixtures/seekervault/gateway/v1).
const (
	publisherA = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
	publisherB = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
	proposalA  = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
	proposalB  = "a1b2c3d4-e5f6-4789-abcd-0123456789ab"
)

// The gateway's own origin in these tests: HTTPS, because that is what a deployment is, and not the
// loopback address the test listener happens to be on — a manifest names the origin phones use.
const gatewayURL = "https://feeds.example.com"

var published = time.Date(2026, 9, 17, 9, 0, 0, 0, time.UTC)

// captured collects what the gateway logged, so a test can assert what is *not* in it.
type captured struct {
	mutex sync.Mutex
	lines []byte
}

func (c *captured) Write(line []byte) (int, error) {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	c.lines = append(c.lines, line...)
	return len(line), nil
}

func (c *captured) text() string {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	return string(c.lines)
}

// recorder is the fan-out in these tests: it collects deliveries and can be made to fail.
type recorder struct {
	mutex     sync.Mutex
	delivered []dispatch.Delivery
	fail      error
}

func (r *recorder) Dispatch(_ context.Context, delivery dispatch.Delivery) error {
	r.mutex.Lock()
	defer r.mutex.Unlock()
	if r.fail != nil {
		return r.fail
	}
	r.delivered = append(r.delivered, delivery)
	return nil
}

func (r *recorder) all() []dispatch.Delivery {
	r.mutex.Lock()
	defer r.mutex.Unlock()
	return append([]dispatch.Delivery(nil), r.delivered...)
}

// grantor is the broker's ticket half in these tests: it names channels the way internal/stream
// does and signs nothing, so what is asserted here is the gateway's own behaviour — which channels
// it grants, which it leaves out, and what it refuses.
type grantor struct {
	mutex    sync.Mutex
	granted  [][]string
	lifetime time.Duration
	most     int
	fail     error
	// The bound each grant was asked for (SEE-156): zero for a ticket of public channels only.
	within []time.Duration
}

func (g *grantor) StreamChannel(channel string) string { return "feed:" + channel }

func (g *grantor) RestrictedStreamChannel(channel string, epoch uint64) string {
	return stream.RestrictedStreamChannel(channel, epoch)
}

func (g *grantor) GrantWithin(channels []string, at time.Time, most time.Duration) (string, time.Duration, error) {
	g.mutex.Lock()
	g.within = append(g.within, most)
	g.mutex.Unlock()
	token, lifetime, err := g.Grant(channels, at)
	if most > 0 && most < lifetime {
		lifetime = most
	}
	return token, lifetime, err
}

func (g *grantor) MostChannels() int { return g.most }

func (g *grantor) Grant(channels []string, at time.Time) (string, time.Duration, error) {
	g.mutex.Lock()
	defer g.mutex.Unlock()
	if g.fail != nil {
		return "", 0, g.fail
	}
	g.granted = append(g.granted, append([]string(nil), channels...))
	return "ticket-for-" + strings.Join(channels, ",") + "-at-" +
		at.UTC().Format(time.RFC3339), g.lifetime, nil
}

func (g *grantor) all() [][]string {
	g.mutex.Lock()
	defer g.mutex.Unlock()
	return append([][]string(nil), g.granted...)
}

// namer is the relay's half in these tests (SEE-92): it derives a topic the way internal/relay
// does and sends nothing, so what is asserted is the gateway's own behaviour — which channels it
// names, which it leaves out, and what it refuses.
type namer struct {
	environment string
	most        int
	// A channel this relay has no topic for, to prove the handler leaves one out rather than
	// answering with an empty name.
	silent string
}

func (n *namer) Topic(channel string) string {
	serverID := rules.ServerOf(channel)
	if serverID == "" || channel == n.silent {
		return ""
	}
	return "feed." + n.environment + "." + serverID
}

func (n *namer) MostTopics() int { return n.most }

// pusher is the relay's device sender in these tests (SEE-144): it records what would have been
// sent and answers whatever outcome the test wants, so what is asserted is the gateway's own
// behaviour — who is allowed to ask, what is bounded, and what happens to a target the endpoint
// rejects — rather than Firebase's.
type pusher struct {
	mutex   sync.Mutex
	sent    []push
	outcome relay.Outcome
	fail    error
}

type push struct {
	target        string
	timeSensitive bool
	// feed is a restricted feed's hint (SEE-156) rather than a direct server's invalidation.
	feed bool
}

func (p *pusher) Send(_ context.Context, target string, timeSensitive bool) (relay.Outcome, error) {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	p.sent = append(p.sent, push{target: target, timeSensitive: timeSensitive})
	return p.outcome, p.fail
}

func (p *pusher) SendFeedHint(_ context.Context, target string, timeSensitive bool) (relay.Outcome, error) {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	p.sent = append(p.sent, push{target: target, timeSensitive: timeSensitive, feed: true})
	return p.outcome, p.fail
}

func (p *pusher) all() []push {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	return append([]push(nil), p.sent...)
}

func (p *pusher) answer(outcome relay.Outcome) {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	p.outcome = outcome
}

type harness struct {
	t          *testing.T
	documents  *sqlite.Store
	settings   *config.Config
	dispatcher *recorder
	grants     *grantor
	topics     *namer
	devices    *pusher
	logs       *captured
	read       *httptest.Server
	publish    *httptest.Server
	admin      *httptest.Server
	feed       gatewayv1connect.FeedServiceClient
	drainer    *dispatch.Drainer
	path       string

	mutex sync.Mutex
	clock time.Time
}

// newGateway builds the service on a temporary database and serves both handlers over loopback.
func newGateway(t *testing.T, change ...func(*config.Config)) *harness {
	t.Helper()
	return gatewayOn(t, filepath.Join(t.TempDir(), "broadcast.db"), change...)
}

func gatewayOn(t *testing.T, path string, change ...func(*config.Config)) *harness {
	t.Helper()
	return built(t, path, true, true, change...)
}

// newGatewayWithoutStream is the deployment that configures no broker: it holds documents, answers
// reads, drains its outbox to a log line, and has nothing to admit a listener to.
func newGatewayWithoutStream(t *testing.T) *harness {
	t.Helper()
	return built(t, filepath.Join(t.TempDir(), "broadcast.db"), false, true)
}

// newGatewayWithoutPush is the deployment that configures no relay: everything above works, and a
// phone asking where hints arrive is told none are sent here (SEE-92).
func newGatewayWithoutPush(t *testing.T) *harness {
	t.Helper()
	return built(t, filepath.Join(t.TempDir(), "broadcast.db"), true, false)
}

func built(
	t *testing.T,
	path string,
	streaming bool,
	relaying bool,
	change ...func(*config.Config),
) *harness {
	t.Helper()
	documents, err := sqlite.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	settings := &config.Config{
		ReadAddress:      "127.0.0.1:0",
		PublisherAddress: "127.0.0.1:0",
		PublicURL:        gatewayURL,
		DatabasePath:     path,
		Retention:        config.DefaultRetention,
		MaxProposals:     config.DefaultMaxProposals,
		ReadRate:         config.DefaultReadRate,
		ReadBurst:        config.DefaultReadBurst,
		PublishRate:      config.DefaultPublishRate,
		PublishBurst:     config.DefaultPublishBurst,
		// The presence interval (SEE-150), at its default and not left zero for the same reason the
		// relay's bounds are not: a zero interval is a zero window, every feed would read as offline
		// however recently its publisher called, and a test asserting that would pass for the wrong
		// reason.
		Heartbeat: config.DefaultHeartbeat,
		// The relay's own bounds, at their defaults (SEE-144). They are here rather than left zero
		// because a zero binding lifetime is a relay that cannot be built, and a test that silently
		// got no relay routes would pass for the wrong reason.
		Relay: config.Relay{Direct: config.Direct{
			ServerRate:       config.DefaultRelayServerRate,
			ServerBurst:      config.DefaultRelayServerBurst,
			DeviceRate:       config.DefaultRelayDeviceRate,
			DeviceBurst:      config.DefaultRelayDeviceBurst,
			GlobalRate:       config.DefaultRelayGlobalRate,
			GlobalBurst:      config.DefaultRelayGlobalBurst,
			EnrollRate:       config.DefaultRelayEnrollRate,
			EnrollBurst:      config.DefaultRelayEnrollBurst,
			BindingLifetime:  config.DefaultRelayBindingLifetime,
			InstallationIdle: config.DefaultRelayInstallationIdle,
			UnboundGrace:     config.DefaultRelayUnboundGrace,
		}},
	}
	for _, apply := range change {
		apply(settings)
	}
	one := &harness{
		t:          t,
		documents:  documents,
		settings:   settings,
		dispatcher: &recorder{},
		grants:     &grantor{lifetime: time.Hour, most: 4},
		topics:     &namer{environment: "production", most: 4},
		devices:    &pusher{},
		logs:       &captured{},
		clock:      published,
		path:       path,
	}
	log := slog.New(slog.NewJSONHandler(one.logs, nil))
	var (
		grants  gateway.Grants
		topics  gateway.Topics
		devices pushrelay.Sender
	)
	if streaming {
		grants = one.grants
	}
	if relaying {
		// Both halves of push come from one credential in a real deployment, so a harness without
		// push has neither: no topic to name and nothing to send to a device.
		topics, devices = one.topics, one.devices
	}
	service := gateway.Build(settings, documents, documents, one.dispatcher, grants, topics,
		devices, log, one.now)
	one.drainer = service.Drainer
	one.read = httptest.NewServer(service.Read)
	one.publish = httptest.NewServer(service.Publish)
	one.feed = gatewayv1connect.NewFeedServiceClient(one.read.Client(), one.read.URL)
	// The operator's surface, on its own server the way it is on its own listener, and only when
	// this deployment configured a password (SEE-141).
	if service.Admin != nil {
		one.admin = httptest.NewServer(service.Admin)
	}
	t.Cleanup(func() {
		one.read.Close()
		one.publish.Close()
		if one.admin != nil {
			one.admin.Close()
		}
		_ = documents.Close()
	})
	return one
}

func (h *harness) now() time.Time {
	h.mutex.Lock()
	defer h.mutex.Unlock()
	return h.clock
}

// at moves the gateway's clock, which is how expiry and retention are tested without waiting.
func (h *harness) at(moment time.Time) {
	h.mutex.Lock()
	defer h.mutex.Unlock()
	h.clock = moment
}

// register grants a publisher the ability to publish, the way feed-gatewayctl and the operator's
// admin page both do: a credential is created, and only its hash is stored.
//
// Called twice for the same server it rotates rather than registering again, which is what both
// surfaces do — registering an identity that exists is refused, and adding a credential to one is
// rotation. Several tests want a publisher holding two credentials at once.
func (h *harness) register(serverID string) string {
	h.t.Helper()
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		h.t.Fatal(err)
	}
	credential := base64.RawURLEncoding.EncodeToString(raw)
	sum := sha256.Sum256([]byte(credential))
	_, err := h.documents.Register(context.Background(),
		sqlite.Registration{ServerID: serverID, Label: "test", Publishing: true},
		storage.Publishing, sum[:], h.now())
	if errors.Is(err, storage.ErrPublisherExists) {
		_, err = h.documents.AddCredential(context.Background(), serverID, "test",
			storage.Publishing, sum[:], h.now())
	}
	if err != nil {
		h.t.Fatal(err)
	}
	return credential
}

// publisher is a client that presents one credential on every call, the way a Go publisher template
// will (SEE-95, SEE-96).
func (h *harness) publisher(credential string) gatewayv1connect.PublisherServiceClient {
	return gatewayv1connect.NewPublisherServiceClient(h.publish.Client(), h.publish.URL,
		connect.WithInterceptors(presenting(credential)))
}

// anonymous is a publisher client with no credential at all.
func (h *harness) anonymous() gatewayv1connect.PublisherServiceClient {
	return gatewayv1connect.NewPublisherServiceClient(h.publish.Client(), h.publish.URL)
}

// handleOf is the operator's handle for a credential, which is what a revocation names.
func handleOf(credential string) string {
	sum := sha256.Sum256([]byte(credential))
	return sqlite.CredentialID(sum[:])
}

func presenting(credential string) connect.UnaryInterceptorFunc {
	return func(next connect.UnaryFunc) connect.UnaryFunc {
		return func(ctx context.Context, request connect.AnyRequest) (connect.AnyResponse, error) {
			request.Header().Set("Authorization", "Bearer "+credential)
			return next(ctx, request)
		}
	}
}

// drain makes one fan-out pass, which the running gateway does on its own.
func (h *harness) drain() int {
	h.t.Helper()
	sent, err := h.drainer.Drain(context.Background())
	if err != nil {
		h.t.Fatal(err)
	}
	return sent
}

// A manifest and a proposal as a well-behaved publisher sends them. The gateway's own origin is
// filled in, because that is the one field a publisher cannot choose freely.
func manifestOf(serverID string, revision uint64, change ...func(*serverv1.ServerManifest)) *serverv1.ServerManifest {
	message := &serverv1.ServerManifest{
		ServerId:         serverID,
		ProtocolVersion:  rules.Protocol,
		SettingsRevision: revision,
		Mode:             serverv1.ConnectionMode_CONNECTION_MODE_GATEWAY_FEED,
		RequiredPlugins: []*serverv1.PluginRequirement{
			{PluginId: "jupiter.swap", MinContract: 1, MaxContract: 1},
		},
		Environments: []serverv1.ServerEnvironment{
			serverv1.ServerEnvironment_SERVER_ENVIRONMENT_PRODUCTION,
		},
		DisplayName: "Copy trading",
		Reference: &serverv1.ServerManifest_Feed{Feed: &serverv1.GatewayFeed{
			GatewayUrl: gatewayURL,
			Channel:    rules.ChannelFor(serverID),
		}},
	}
	for _, apply := range change {
		apply(message)
	}
	return message
}

func proposalOf(
	serverID, proposalID string,
	revision uint64,
	change ...func(*proposalv1.Proposal),
) *proposalv1.Proposal {
	message := &proposalv1.Proposal{
		ServerId:      serverID,
		Channel:       rules.ChannelFor(serverID),
		ProposalId:    proposalID,
		Revision:      revision,
		Operation:     "swap",
		PluginId:      "jupiter.swap",
		Status:        proposalv1.ProposalStatus_PROPOSAL_STATUS_OPEN,
		CreatedAt:     timestamppb.New(published),
		UpdatedAt:     timestamppb.New(published.Add(30 * time.Minute)),
		ExpiresAt:     timestamppb.New(published.Add(3 * time.Hour)),
		PublisherNote: "Ротация в USDC 📉",
		Values: []*proposalv1.ProposalValue{
			{Key: "input_mint", Text: "So11111111111111111111111111111111111111112"},
			{Key: "published_price", Text: "139420000"},
		},
	}
	for _, apply := range change {
		apply(message)
	}
	return message
}

// publishManifest and publishProposal are the two calls most tests start with.
func (h *harness) publishManifest(
	as gatewayv1connect.PublisherServiceClient,
	manifest *serverv1.ServerManifest,
) *gatewayv1.PublishManifestResponse {
	h.t.Helper()
	response, err := as.PublishManifest(context.Background(),
		connect.NewRequest(&gatewayv1.PublishManifestRequest{Manifest: manifest}))
	if err != nil {
		h.t.Fatalf("publishing a manifest failed: %v", err)
	}
	return response.Msg
}

func (h *harness) publishProposal(
	as gatewayv1connect.PublisherServiceClient,
	proposal *proposalv1.Proposal,
) *gatewayv1.PublishProposalResponse {
	h.t.Helper()
	response, err := as.PublishProposal(context.Background(),
		connect.NewRequest(&gatewayv1.PublishProposalRequest{Proposal: proposal}))
	if err != nil {
		h.t.Fatalf("publishing a proposal failed: %v", err)
	}
	return response.Msg
}

func (h *harness) list(channel string, change ...func(*gatewayv1.ListProposalsRequest)) *gatewayv1.ListProposalsResponse {
	h.t.Helper()
	message := &gatewayv1.ListProposalsRequest{Channel: channel}
	for _, apply := range change {
		apply(message)
	}
	response, err := h.feed.ListProposals(context.Background(), connect.NewRequest(message))
	if err != nil {
		h.t.Fatalf("reading a feed failed: %v", err)
	}
	return response.Msg
}

// ticket asks for a listener's grant, the way the phone's session does before it opens a stream.
func (h *harness) ticket(channels ...string) *gatewayv1.GetStreamTicketResponse {
	h.t.Helper()
	response, err := h.feed.GetStreamTicket(context.Background(),
		connect.NewRequest(&gatewayv1.GetStreamTicketRequest{Channels: channels}))
	if err != nil {
		h.t.Fatalf("asking for a ticket failed: %v", err)
	}
	return response.Msg
}

// topics asks where hints about these channels arrive, the way the phone's topic manager does
// before it subscribes (SEE-92).
func (h *harness) namedTopics(channels ...string) *gatewayv1.GetFeedTopicsResponse {
	h.t.Helper()
	response, err := h.feed.GetFeedTopics(context.Background(),
		connect.NewRequest(&gatewayv1.GetFeedTopicsRequest{Channels: channels}))
	if err != nil {
		h.t.Fatalf("asking where hints arrive failed: %v", err)
	}
	return response.Msg
}

// heartbeat is a publisher saying it is running, with nothing to publish (SEE-150).
func (h *harness) heartbeat(
	as gatewayv1connect.PublisherServiceClient,
) *gatewayv1.HeartbeatResponse {
	h.t.Helper()
	response, err := as.Heartbeat(context.Background(),
		connect.NewRequest(&gatewayv1.HeartbeatRequest{}))
	if err != nil {
		h.t.Fatalf("checking in failed: %v", err)
	}
	return response.Msg
}

// status asks whether these channels' publishers are running, the way a phone in the foreground
// does (SEE-150).
func (h *harness) status(channels ...string) *gatewayv1.GetFeedStatusResponse {
	h.t.Helper()
	response, err := h.feed.GetFeedStatus(context.Background(),
		connect.NewRequest(&gatewayv1.GetFeedStatusRequest{Channels: channels}))
	if err != nil {
		h.t.Fatalf("asking whether a feed is online failed: %v", err)
	}
	return response.Msg
}

// availabilityOf is one channel's verdict out of a presence answer, or UNSPECIFIED when the answer
// does not mention that channel at all — which is what a phone reads as unknown.
func availabilityOf(answer *gatewayv1.GetFeedStatusResponse, channel string) gatewayv1.FeedAvailability {
	for _, status := range answer.GetStatuses() {
		if status.GetChannel() == channel {
			return status.GetAvailability()
		}
	}
	return gatewayv1.FeedAvailability_FEED_AVAILABILITY_UNSPECIFIED
}

// refused asserts that a call failed with one code and one problem, and returns the detail so a
// test can also check what the gateway said it holds.
func refused(t *testing.T, err error, code connect.Code, expected gatewayv1.GatewayProblem) *gatewayv1.GatewayErrorDetail {
	t.Helper()
	if err == nil {
		t.Fatalf("the call was accepted; expected %v", expected)
	}
	var failure *connect.Error
	if !errors.As(err, &failure) {
		t.Fatalf("not a Connect error: %v", err)
	}
	if failure.Code() != code {
		t.Fatalf("the call failed with %v, expected %v (%v)", failure.Code(), code, err)
	}
	for _, carried := range failure.Details() {
		value, err := carried.Value()
		if err != nil {
			continue
		}
		detail, ok := value.(*gatewayv1.GatewayErrorDetail)
		if !ok {
			continue
		}
		if detail.GetProblem() != expected {
			t.Fatalf("the problem is %v, expected %v", detail.GetProblem(), expected)
		}
		return detail
	}
	t.Fatalf("the error carried no problem detail: %v", err)
	return nil
}

// post sends a request body to one procedure as raw JSON, for the cases a generated client cannot
// express — a field the contract does not have, for instance.
func (h *harness) post(server *httptest.Server, procedure, body, credential string) *http.Response {
	h.t.Helper()
	request, err := http.NewRequest(http.MethodPost, server.URL+procedure,
		strings.NewReader(body))
	if err != nil {
		h.t.Fatal(err)
	}
	request.Header.Set("Content-Type", "application/json")
	if credential != "" {
		request.Header.Set("Authorization", "Bearer "+credential)
	}
	response, err := server.Client().Do(request)
	if err != nil {
		h.t.Fatal(err)
	}
	h.t.Cleanup(func() { _ = response.Body.Close() })
	return response
}
