package gateway

import (
	"database/sql"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/config"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/dispatch"
	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1/gatewayv1connect"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/relay"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage/sqlite"
	"google.golang.org/protobuf/reflect/protoreflect"
	"google.golang.org/protobuf/reflect/protoregistry"

	_ "modernc.org/sqlite"
)

// The boundary the feed gateway is held to, in the shape the phone's own StageBoundaryTest
// uses: read the source and the contract, and fail if either grows something it should not have.
//
// A guard here is not a substitute for the tests that exercise behaviour. It is for the changes
// that would look reasonable in review — a helper that fetches something from a publisher, a
// convenience field on a proposal, a column to remember who read what — and would quietly move
// what this service is.

// repo is the repository root, from the package directory the test runs in.
const repo = "../../.."

// shipped lists the Go files the gateway is built from: everything under feed-gateway/ except the
// tests and the generated code.
func shipped(t *testing.T) map[string]string {
	t.Helper()
	sources := map[string]string{}
	root := filepath.Join(repo, "feed-gateway")
	err := filepath.WalkDir(root, func(path string, entry fs.DirEntry, err error) error {
		switch {
		case err != nil:
			return err
		case entry.IsDir() && entry.Name() == "gen":
			return fs.SkipDir
		case entry.IsDir() || filepath.Ext(path) != ".go" || strings.HasSuffix(path, "_test.go"):
			return nil
		}
		content, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		relative, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		sources[relative] = string(content)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(sources) < 10 {
		t.Fatalf("only %d source files were read; the walk is wrong", len(sources))
	}
	return sources
}

// withoutComments is the code with its prose taken out. Every rule below is discussed in a comment
// somewhere, and a check that read the comments would fail on the explanation of itself.
func withoutComments(source string) string {
	blocks := regexp.MustCompile(`(?s)/\*.*?\*/`).ReplaceAllString(source, "")
	var kept []string
	for _, line := range strings.Split(blocks, "\n") {
		if strings.HasPrefix(strings.TrimSpace(line), "//") {
			continue
		}
		kept = append(kept, line)
	}
	return strings.Join(kept, "\n")
}

// The gateway is called; it calls nobody it serves. No publisher is ever contacted — that is the
// point of the mode rather than a detail of it, because a publisher that could be reached could be
// told which phones are interested in it — and no chain, provider or phone either.
//
// Two packages call out, and only two: internal/stream, to the broker that fans publications out
// (SEE-91), and internal/relay, to the push endpoint that hints to the phones which are not
// listening (SEE-92). Both are behind dispatch.Dispatcher, which is the seam that exists to keep
// them in one place each, and this check is what keeps them there.
//
// And a file that can dial may not carry an address: the one thing each of them opens a connection
// to comes from its operator — the broker's URL and the push endpoint from the environment, and the
// token endpoint from the credential document itself — so there is no hostname compiled into this
// service anywhere. One string is allowed by its exact spelling, because it looks like an address
// and is not one: the OAuth scope, which is a name in Google's own vocabulary and travels as a form
// value. Allowing it by spelling rather than by package means a second address cannot hide behind
// the same exception.
func TestOnlyTheBrokerAndRelayCallOut(t *testing.T) {
	forbidden := regexp.MustCompile(
		`\b(http\.Get|http\.Post|http\.Head|http\.PostForm|http\.DefaultClient|` +
			`http\.NewRequest|http\.Client\{|net\.Dial|url\.Parse\(.*publisher)`)
	callers := []string{
		filepath.Join("internal", "stream"),
		filepath.Join("internal", "relay"),
	}
	address := regexp.MustCompile(`https?://[a-z0-9\[]`)
	var offenders, addresses []string
	for name, source := range shipped(t) {
		code := withoutComments(source)
		if !forbidden.MatchString(code) {
			continue
		}
		if !slices.ContainsFunc(callers, func(one string) bool {
			return strings.HasPrefix(name, one)
		}) {
			offenders = append(offenders, name)
			continue
		}
		if address.MatchString(strings.ReplaceAll(code, relay.Scope, "")) {
			addresses = append(addresses, name)
		}
	}
	if len(offenders) > 0 {
		slices.Sort(offenders)
		t.Fatalf("these files acquired a way to call out: %v", offenders)
	}
	if len(addresses) > 0 {
		slices.Sort(addresses)
		t.Fatalf("these files carry an address of their own: %v", addresses)
	}
}

// SQL stays in the store implementations. The storage package is a contract, while schema,
// migrations, connections and transaction execution are implementation details — and there are two
// of them since SEE-145, so the exemption is the implementation directory rather than one named
// database. internal/storage itself is not exempt: the contract describes operations, and a
// statement appearing there would mean it had started describing a database instead.
func TestSqlLivesOnlyInTheStore(t *testing.T) {
	// Case-sensitive on purpose: every statement in the store is written in upper case, and `select`
	// in lower case is Go's own statement rather than a query.
	sql := regexp.MustCompile(`\b(SELECT|INSERT INTO|UPDATE|DELETE FROM|CREATE TABLE|PRAGMA)\b`)
	var offenders []string
	for name, source := range shipped(t) {
		if isStoreImplementation(name) {
			continue
		}
		if sql.MatchString(withoutComments(source)) {
			offenders = append(offenders, name)
		}
	}
	if len(offenders) > 0 {
		slices.Sort(offenders)
		t.Fatalf("these files outside internal/storage speak SQL: %v", offenders)
	}
}

// isStoreImplementation says whether a source file is part of a concrete store. The list is
// explicit rather than "anything under internal/storage", so adding a third one is a line in this
// test — which is the point of the guard.
func isStoreImplementation(name string) bool {
	for _, implementation := range []string{"sqlite", "postgres"} {
		if strings.HasPrefix(name, filepath.Join("internal", "storage", implementation)) {
			return true
		}
	}
	return false
}

// Business and delivery code know the storage contract and never a concrete store. The two process
// composition roots and tests may import one; which database is in use must not be a fact that
// validation, RPC, cursor or outbox-draining rules can reach for.
//
// This is what made SEE-145 a new package rather than an edit: nothing above the store had to
// change to gain a second database, because nothing above the store had ever been allowed to know
// about the first.
func TestBusinessDoesNotImportAStoreImplementation(t *testing.T) {
	implementations := []string{
		"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage/sqlite",
		"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage/postgres",
	}
	var offenders []string
	for name, source := range shipped(t) {
		if strings.HasPrefix(name, filepath.Join("cmd")) || isStoreImplementation(name) {
			continue
		}
		for _, implementation := range implementations {
			if slices.Contains(imports(source), implementation) {
				offenders = append(offenders, name+" -> "+implementation)
			}
		}
	}
	if len(offenders) > 0 {
		slices.Sort(offenders)
		t.Fatalf("business code imports a store implementation directly: %v", offenders)
	}
}

// The rules are pure: what a document must look like, and what a publication is given what is
// already held. They read no database, no request, no credential and no clock of their own, which
// is what makes them the same rules the phone applies to the same documents.
func TestTheRulesReachForNothing(t *testing.T) {
	var reaches []string
	for name, source := range shipped(t) {
		if !strings.HasPrefix(name, filepath.Join("internal", "rules")) {
			continue
		}
		for _, line := range imports(source) {
			if !slices.Contains(reaches, line) {
				reaches = append(reaches, line)
			}
		}
	}
	slices.Sort(reaches)
	expected := []string{
		// Encoding typed common parameters for the protocol-1 proposal adapter.
		"encoding/base64",
		// The contract, and the two documents the rules are about.
		"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1",
		"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/proposal/v1",
		"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/request/v2",
		"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1",
		// Comparing documents, and reading a timestamp as an instant.
		"google.golang.org/protobuf/proto",
		"google.golang.org/protobuf/types/known/timestamppb",
		// The shapes an identity, a name and a piece of text are held to.
		"regexp",
		// Comparing two manifests' environments as sets (SEE-97).
		"slices",
		"strconv",
		"strings",
		"time",
		"unicode",
	}
	if fmt.Sprint(reaches) != fmt.Sprint(expected) {
		t.Fatalf("internal/rules reaches for\n%v\nexpected\n%v", reaches, expected)
	}
}

// A provider's name has no business in this service. The gateway relays what a publisher published
// and matches nothing against a provider: a proposal names an operation at the protocol's own level
// and the plugin a phone should have, and both are the phone's business (SEE-86, SEE-89).
func TestNoProviderIsNamedInTheGateway(t *testing.T) {
	providers := regexp.MustCompile(`(?i)\b(jupiter|raydium|orca|pyth|birdeye)\b`)
	var offenders []string
	for name, source := range shipped(t) {
		if providers.MatchString(withoutComments(source)) {
			offenders = append(offenders, name)
		}
	}
	if len(offenders) > 0 {
		slices.Sort(offenders)
		t.Fatalf("these files name a provider: %v", offenders)
	}
}

// The live schema is public-feed state only. The retired v2 definitions remain in the migration
// chain, so this checks the database after all migrations rather than matching source text.
func TestTheStoreKeepsOnlyPublicFeedState(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")
	documents, err := sqlite.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := documents.Close(); err != nil {
		t.Fatal(err)
	}
	database, err := sql.Open("sqlite", "file:"+path+"?mode=ro")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = database.Close() }()
	rows, err := database.Query(
		`SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name`)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = rows.Close() }()
	var names []string
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			t.Fatal(err)
		}
		names = append(names, name)
	}
	// The live schema, in full. Nothing may be added to it without being argued for here, by name,
	// where someone would ask why — which is how the two relay tables got the paragraph they have.
	//
	// relay_installation and relay_binding are SEE-144's private push routing. They hold an
	// installation identity this gateway minted, the hash of the secret that proves ownership of
	// it, where to send a wake-up, and which registered servers one device agreed may wake it.
	// They are not a return of the gateway-private request routing version 3 removed: there is no
	// column here for a request, an approval, a signature, a result, a wallet, an amount or
	// anything an owner decided, and a phone woken through this relay goes and reads its own
	// server for all of that.
	expected := []string{
		"channel_sequence", "manifest", "notice", "proposal", "publisher", "publisher_credential",
		"relay_binding", "relay_installation",
	}
	if fmt.Sprint(names) != fmt.Sprint(expected) {
		t.Fatalf("the store holds %v, expected %v", names, expected)
	}
}

// The contract, pinned the way the phone pins the proposal's (StageBoundaryTest). A field that
// could carry a subscriber's address, the quantity they chose or anything they signed has to be
// added to this list first — and the forbidden words below mean it would have to be argued for
// under its own name, in this test, where someone would ask why.
func TestTheContractIsBoundedAndSaysNothingAboutAnyone(t *testing.T) {
	for file, expected := range map[string][]string{
		"feed.proto": {
			"server_id", "known_settings_revision",
			"manifest", "unchanged", "settings_revision",
			// The common snapshot and point read. They deliberately repeat the proposal methods'
			// cursor shape while clients migrate; both are read-only views of the same rows.
			"channel", "page_size", "page_token", "known_snapshot_sequence",
			"requests", "next_page_token", "snapshot_sequence", "unchanged",
			"channel", "request_id",
			"request",
			"channel", "page_size", "page_token", "known_snapshot_sequence",
			"proposals", "next_page_token", "snapshot_sequence", "unchanged",
			"channel", "proposal_id",
			"proposal",
			// A listener's grant (SEE-91): the channels asked for, the ticket, the channels
			// granted with the broker's name for each, and how long it lasts. Nothing that
			// identifies the listener, which is the whole reason this list is pinned.
			"channels",
			"ticket", "channels", "lifetime_seconds",
			"channel", "stream_channel",
			// Where hints about a channel arrive (SEE-92): the channels asked about, the topics
			// named, and the name of one. A topic is public and says nothing about who subscribes
			// to it — Firebase owns the membership, and this gateway is never told who joined.
			"channels",
			"topics",
			"channel", "topic",
		},
		// What a subscriber receives (SEE-91): a sequence the gateway counted and a document a
		// publisher published. A field here would be a field every listener on the channel sees.
		"event.proto": {"sequence", "manifest", "proposal", "request"},
		"publish.proto": {
			"manifest",
			"status", "settings_revision",
			"request",
			"status", "revision", "snapshot_sequence",
			"request_id", "revision",
			"status", "request", "snapshot_sequence",
			"proposal",
			"status", "revision", "snapshot_sequence",
			"proposal_id", "revision",
			"status", "proposal", "snapshot_sequence",
		},
		"problem.proto": {"problem", "field", "held_revision"},
	} {
		source, err := os.ReadFile(filepath.Join(repo, "proto", "seekervault", "gateway", "v1", file))
		if err != nil {
			t.Fatal(err)
		}
		proto := withoutComments(string(source))
		fields := regexp.MustCompile(`(?m)^\s*(?:repeated\s+)?[\w.]+\s+(\w+)\s*=\s*\d+;`).
			FindAllStringSubmatch(proto, -1)
		var names []string
		for _, one := range fields {
			names = append(names, one[1])
		}
		if fmt.Sprint(names) != fmt.Sprint(expected) {
			t.Fatalf("%s carries\n%v\nexpected\n%v", file, names, expected)
		}
		forbiddenWords := `wallet|amount|approval|approved|decision|dismissal|execution|outcome|balance|payout|prepared|transaction|secret|install|script`
		if file != "publish.proto" {
			forbiddenWords += `|signature|result|credential`
		}
		forbidden := regexp.MustCompile(`(?i)\b(` + forbiddenWords + `)\w*`)
		if found := forbidden.FindAllString(proto, -1); len(found) > 0 {
			t.Fatalf("%s grew something about a person or a permission: %v", file, found)
		}
	}
}

// Removal is permanent protocol state, not merely an absent handler. Every deleted descriptor is
// denied by full name, while the numbers and field/enum names older serialized values carry stay
// reserved so a future schema cannot reinterpret them.
func TestRetiredPrivateProtocolNamesCannotReturn(t *testing.T) {
	retired := []protoreflect.FullName{
		"seekervault.gateway.v1.InvitationService",
		"seekervault.gateway.v1.DeviceService",
		"seekervault.gateway.v1.InvitationStatus",
		"seekervault.gateway.v1.Invitation",
		"seekervault.gateway.v1.ResolveInvitationRequest",
		"seekervault.gateway.v1.ResolveInvitationResponse",
		"seekervault.gateway.v1.RedeemInvitationRequest",
		"seekervault.gateway.v1.RedeemInvitationResponse",
		"seekervault.gateway.v1.DeviceServiceGetServerManifestRequest",
		"seekervault.gateway.v1.DeviceServiceGetServerManifestResponse",
		"seekervault.gateway.v1.DeviceServiceListRequestsRequest",
		"seekervault.gateway.v1.DeviceServiceListRequestsResponse",
		"seekervault.gateway.v1.DeviceResultStatus",
		"seekervault.gateway.v1.DeviceResult",
		"seekervault.gateway.v1.DeviceServiceSubmitResultRequest",
		"seekervault.gateway.v1.DeviceServiceSubmitResultResponse",
		"seekervault.gateway.v1.DeviceServiceRevokeConnectionRequest",
		"seekervault.gateway.v1.DeviceServiceRevokeConnectionResponse",
		"seekervault.gateway.v1.PrivateRequestRecord",
		"seekervault.gateway.v1.CreateInvitationRequest",
		"seekervault.gateway.v1.CreateInvitationResponse",
		"seekervault.gateway.v1.GetInvitationRequest",
		"seekervault.gateway.v1.GetInvitationResponse",
		"seekervault.gateway.v1.RevokeInvitationRequest",
		"seekervault.gateway.v1.RevokeInvitationResponse",
		"seekervault.gateway.v1.CreatePrivateRequestRequest",
		"seekervault.gateway.v1.CreatePrivateRequestResponse",
		"seekervault.gateway.v1.GetPrivateRequestRequest",
		"seekervault.gateway.v1.GetPrivateRequestResponse",
		"seekervault.gateway.v1.CancelPrivateRequestRequest",
		"seekervault.gateway.v1.CancelPrivateRequestResponse",
		"seekervault.gateway.v1.RevokePrivateConnectionRequest",
		"seekervault.gateway.v1.RevokePrivateConnectionResponse",
		"seekervault.server.v1.GatewayPrivate",
	}
	for _, name := range retired {
		if descriptor, err := protoregistry.GlobalFiles.FindDescriptorByName(name); err == nil {
			t.Fatalf("retired descriptor %s returned as %T", name, descriptor)
		}
	}

	manifest := serverv1.File_seekervault_server_v1_manifest_proto.Messages().ByName("ServerManifest")
	if !manifest.ReservedNames().Has("gateway_private") ||
		!manifest.ReservedRanges().Has(protoreflect.FieldNumber(10)) {
		t.Fatal("ServerManifest no longer reserves gateway_private field 10")
	}
	mode := serverv1.File_seekervault_server_v1_manifest_proto.Enums().ByName("ConnectionMode")
	if !mode.ReservedNames().Has("CONNECTION_MODE_GATEWAY_PRIVATE") ||
		!mode.ReservedRanges().Has(protoreflect.EnumNumber(3)) {
		t.Fatal("ConnectionMode no longer reserves gateway-private value 3")
	}
	problems := gatewayv1.File_seekervault_gateway_v1_problem_proto.Enums().ByName("GatewayProblem")
	for number := protoreflect.EnumNumber(35); number <= 46; number++ {
		if !problems.ReservedRanges().Has(number) {
			t.Fatalf("GatewayProblem no longer reserves %d", number)
		}
	}
	for _, name := range []protoreflect.Name{
		"GATEWAY_PROBLEM_BAD_USER_REF",
		"GATEWAY_PROBLEM_BAD_LIFETIME",
		"GATEWAY_PROBLEM_INVALID_INVITATION",
		"GATEWAY_PROBLEM_INVITATION_EXPIRED",
		"GATEWAY_PROBLEM_INVITATION_USED",
		"GATEWAY_PROBLEM_NO_BINDING",
		"GATEWAY_PROBLEM_BINDING_EXISTS",
		"GATEWAY_PROBLEM_WRONG_RECIPIENT",
		"GATEWAY_PROBLEM_NO_SUCH_REQUEST",
		"GATEWAY_PROBLEM_RESULT_CONFLICT",
		"GATEWAY_PROBLEM_REQUEST_SETTLED",
		"GATEWAY_PROBLEM_NOT_PRIVATE",
	} {
		if !problems.ReservedNames().Has(name) {
			t.Fatalf("GatewayProblem no longer reserves %s", name)
		}
	}
}

// The two APIs are two listeners, and every retired gateway-private procedure is absent from both.
func TestNeitherListenerServesTheOthersProcedures(t *testing.T) {
	documents, err := sqlite.Open(filepath.Join(t.TempDir(), "broadcast.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = documents.Close() }()
	service := Build(
		&config.Config{
			PublicURL:    "https://feeds.example.com",
			MaxProposals: 1,
			ReadRate:     1000,
			ReadBurst:    1000,
			PublishRate:  1000,
			PublishBurst: 1000,
		},
		documents,
		documents,
		dispatch.Logger{Log: slog.New(slog.NewTextHandler(io.Discard, nil))},
		nil,
		nil,
		nil,
		slog.New(slog.NewTextHandler(io.Discard, nil)),
		time.Now,
	)
	read := httptest.NewServer(service.Read)
	defer read.Close()
	publish := httptest.NewServer(service.Publish)
	defer publish.Close()
	publisherProcedures := []string{
		gatewayv1connect.PublisherServicePublishManifestProcedure,
		gatewayv1connect.PublisherServicePublishRequestProcedure,
		gatewayv1connect.PublisherServiceCancelRequestProcedure,
		gatewayv1connect.PublisherServicePublishProposalProcedure,
		gatewayv1connect.PublisherServiceCancelProposalProcedure,
	}
	feedProcedures := []string{
		gatewayv1connect.FeedServiceGetServerManifestProcedure,
		gatewayv1connect.FeedServiceListRequestsProcedure,
		gatewayv1connect.FeedServiceGetRequestProcedure,
		gatewayv1connect.FeedServiceListProposalsProcedure,
		gatewayv1connect.FeedServiceGetProposalProcedure,
		gatewayv1connect.FeedServiceGetStreamTicketProcedure,
		gatewayv1connect.FeedServiceGetFeedTopicsProcedure,
	}
	retiredProcedures := []string{
		"/seekervault.gateway.v1.InvitationService/ResolveInvitation",
		"/seekervault.gateway.v1.InvitationService/RedeemInvitation",
		"/seekervault.gateway.v1.DeviceService/GetServerManifest",
		"/seekervault.gateway.v1.DeviceService/ListRequests",
		"/seekervault.gateway.v1.DeviceService/SubmitResult",
		"/seekervault.gateway.v1.DeviceService/RevokeConnection",
		"/seekervault.gateway.v1.PublisherService/CreateInvitation",
		"/seekervault.gateway.v1.PublisherService/GetInvitation",
		"/seekervault.gateway.v1.PublisherService/RevokeInvitation",
		"/seekervault.gateway.v1.PublisherService/CreatePrivateRequest",
		"/seekervault.gateway.v1.PublisherService/GetPrivateRequest",
		"/seekervault.gateway.v1.PublisherService/CancelPrivateRequest",
		"/seekervault.gateway.v1.PublisherService/RevokePrivateConnection",
	}

	for _, one := range []struct {
		name       string
		server     *httptest.Server
		procedures []string
	}{
		{"the read listener", read, append(append([]string{}, publisherProcedures...), retiredProcedures...)},
		{"the publisher listener", publish, append(append([]string{}, feedProcedures...), retiredProcedures...)},
	} {
		for _, procedure := range one.procedures {
			response, err := one.server.Client().Post(
				one.server.URL+procedure, "application/json", strings.NewReader("{}"))
			if err != nil {
				t.Fatal(err)
			}
			_ = response.Body.Close()
			if response.StatusCode != http.StatusNotFound {
				t.Fatalf("%s answered %s for %s", one.name, response.Status, procedure)
			}
		}
	}
	for _, server := range []*httptest.Server{read, publish} {
		for _, path := range []string{"/invite/stale-token", "/invite/stale-token/qr.png"} {
			response, err := server.Client().Get(server.URL + path)
			if err != nil {
				t.Fatal(err)
			}
			_ = response.Body.Close()
			if response.StatusCode != http.StatusNotFound {
				t.Fatalf("retired onboarding path %s answered %s", path, response.Status)
			}
		}
	}
}

// imports lists the import paths of one Go file, with the quotes and any alias removed.
func imports(source string) []string {
	block := regexp.MustCompile(`(?s)\nimport \(\n(.*?)\n\)`).FindStringSubmatch(source)
	if block == nil {
		single := regexp.MustCompile(`(?m)^import "(.*)"$`).FindStringSubmatch(source)
		if single == nil {
			return nil
		}
		return []string{single[1]}
	}
	var paths []string
	for _, line := range strings.Split(block[1], "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "//") {
			continue
		}
		if index := strings.Index(line, `"`); index >= 0 {
			line = line[index+1:]
		}
		paths = append(paths, strings.TrimSuffix(line, `"`))
	}
	return paths
}
