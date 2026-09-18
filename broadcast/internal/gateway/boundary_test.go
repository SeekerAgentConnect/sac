package gateway

import (
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

	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/config"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/dispatch"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1/gatewayv1connect"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/relay"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/store"
)

// The boundary the broadcast gateway is held to, in the shape the phone's own StageBoundaryTest
// uses: read the source and the contract, and fail if either grows something it should not have.
//
// A guard here is not a substitute for the tests that exercise behaviour. It is for the changes
// that would look reasonable in review — a helper that fetches something from a publisher, a
// convenience field on a proposal, a column to remember who read what — and would quietly move
// what this service is.

// repo is the repository root, from the package directory the test runs in.
const repo = "../../.."

// shipped lists the Go files the gateway is built from: everything under broadcast/ except the
// tests and the generated code.
func shipped(t *testing.T) map[string]string {
	t.Helper()
	sources := map[string]string{}
	root := filepath.Join(repo, "broadcast")
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

// Storage stays in one place, as it does in the sidecar and on the phone. Everything durable is
// behind internal/store, so the rules are pure, the handlers are one transaction each, and there is
// exactly one file to read to know everything the gateway keeps.
func TestSqlLivesOnlyInTheStore(t *testing.T) {
	// Case-sensitive on purpose: every statement in the store is written in upper case, and `select`
	// in lower case is Go's own statement rather than a query.
	sql := regexp.MustCompile(`\b(SELECT|INSERT INTO|UPDATE|DELETE FROM|CREATE TABLE|PRAGMA)\b`)
	var offenders []string
	for name, source := range shipped(t) {
		if strings.HasPrefix(name, filepath.Join("internal", "store")) {
			continue
		}
		if sql.MatchString(withoutComments(source)) {
			offenders = append(offenders, name)
		}
	}
	if len(offenders) > 0 {
		slices.Sort(offenders)
		t.Fatalf("these files outside internal/store speak SQL: %v", offenders)
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
		"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1",
		"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/proposal/v1",
		"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/request/v2",
		"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/server/v1",
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

// What the gateway keeps, as its own schema spells it. SEE-109 adds only a server-scoped opaque
// recipient, a device binding and the common request/result route. The list is pinned so this can
// never drift into a central account, wallet profile or financial history.
func TestTheStoreKeepsOnlyTheGatewayPrivateAssociation(t *testing.T) {
	source, err := os.ReadFile(filepath.Join(repo, "broadcast", "internal", "store", "store.go"))
	if err != nil {
		t.Fatal(err)
	}
	// The SQL's own comments go too: they explain the schema, and a check that read them would
	// fail on the explanation of itself.
	schema := regexp.MustCompile(`(?m)^\s*--.*$`).
		ReplaceAllString(withoutComments(string(source)), "")
	start := strings.Index(schema, "CREATE TABLE")
	if start < 0 {
		t.Fatal("the schema is not in internal/store/store.go any more")
	}
	schema = schema[start:]

	tables := regexp.MustCompile(`CREATE TABLE (\w+)`).FindAllStringSubmatch(schema, -1)
	var names []string
	for _, one := range tables {
		names = append(names, one[1])
	}
	expected := []string{
		"publisher", "publisher_credential", "manifest", "proposal", "channel_sequence", "notice",
		"invitation", "device_binding", "private_request",
	}
	if fmt.Sprint(names) != fmt.Sprint(expected) {
		t.Fatalf("the store holds %v, expected %v", names, expected)
	}

	// Columns are lowercase and indented by two; the constraints that follow them are shouted, so
	// the case is what tells one from the other.
	columns := regexp.MustCompile(`(?m)^\s{2}([a-z]\w+)\s`).FindAllStringSubmatch(schema, -1)
	var fields []string
	for _, one := range columns {
		fields = append(fields, one[1])
	}
	pinned := []string{
		// publisher
		"server_id", "label", "created_at_ms",
		// publisher_credential
		"credential_hash", "server_id", "label", "created_at_ms", "revoked_at_ms",
		// manifest
		"server_id", "settings_revision", "document", "updated_at_ms",
		// proposal
		"channel", "proposal_id", "server_id", "revision", "cancelled", "expires_at_ms",
		"sequence", "document", "updated_at_ms",
		// channel_sequence
		"channel", "sequence",
		// notice
		"id", "channel", "kind", "proposal_id", "revision", "sequence", "created_at_ms",
		"attempts", "ready_at_ms",
		// invitation
		"invitation_id", "token_hash", "server_id", "user_ref", "created_at_ms",
		"expires_at_ms", "revoked_at_ms", "redeemed_at_ms", "connection_id",
		// device_binding
		"connection_id", "server_id", "user_ref", "credential_hash", "device_name",
		"created_at_ms", "revoked_at_ms", "sequence",
		// private_request
		"server_id", "request_id", "user_ref", "connection_id", "revision", "cancelled",
		"expires_at_ms", "sequence", "document", "result", "updated_at_ms",
	}
	if fmt.Sprint(fields) != fmt.Sprint(pinned) {
		t.Fatalf("the store's columns are\n%v\nexpected\n%v", fields, pinned)
	}

	forbidden := regexp.MustCompile(`(?i)\b(wallet|amount|approval|approved|decision|` +
		`dismissal|execution|balance|payout|subscriber|central_account|email|phone)\w*`)
	if found := forbidden.FindAllString(schema, -1); len(found) > 0 {
		t.Fatalf("the schema grew something about a person: %v", found)
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
			"user_ref", "lifetime_seconds",
			"invitation",
			"invitation_id",
			"invitation",
			"invitation_id",
			"request", "connection_id",
			"record", "unchanged",
			"request_id",
			"record",
			"request_id", "revision",
			"record", "unchanged",
			"connection_id",
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
		"onboarding.proto": {
			"invitation_id", "invitation_url", "app_uri", "expires_at", "status", "connection_id", "connected_at",
			"token",
			"invitation_id", "server_id", "display_name", "expires_at", "status", "manifest",
			"token", "device_name",
			"connection_id", "device_token", "server_id", "manifest",
			"connection_id", "known_settings_revision",
			"manifest", "unchanged", "settings_revision",
			"connection_id", "page_size", "page_token", "known_sequence",
			"requests", "next_page_token", "sequence", "unchanged",
			"request_id", "request_revision", "status", "owner_inputs", "signature", "detail", "completed_at",
			"connection_id", "result",
			"result", "unchanged",
			"connection_id",
			"request", "connection_id", "result",
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
		if file != "onboarding.proto" && file != "publish.proto" {
			forbiddenWords += `|signature|result|credential`
		}
		forbidden := regexp.MustCompile(`(?i)\b(` + forbiddenWords + `)\w*`)
		if found := forbidden.FindAllString(proto, -1); len(found) > 0 {
			t.Fatalf("%s grew something about a person or a permission: %v", file, found)
		}
	}
}

// The three APIs are three listeners, and this is that statement at run time: every procedure is
// absent from the other two handlers. Not "is refused" — is not there at all, so no credential,
// mistake or routing rule in front can turn one boundary into another.
func TestNeitherListenerServesTheOthersProcedures(t *testing.T) {
	documents, err := store.Open(filepath.Join(t.TempDir(), "broadcast.db"))
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
		dispatch.Logger{Log: slog.New(slog.NewTextHandler(io.Discard, nil))},
		nil,
		nil,
		slog.New(slog.NewTextHandler(io.Discard, nil)),
		time.Now,
	)
	read := httptest.NewServer(service.Read)
	defer read.Close()
	publish := httptest.NewServer(service.Publish)
	defer publish.Close()
	client := httptest.NewServer(service.Client)
	defer client.Close()
	publisherProcedures := []string{
		gatewayv1connect.PublisherServicePublishManifestProcedure,
		gatewayv1connect.PublisherServicePublishRequestProcedure,
		gatewayv1connect.PublisherServiceCancelRequestProcedure,
		gatewayv1connect.PublisherServicePublishProposalProcedure,
		gatewayv1connect.PublisherServiceCancelProposalProcedure,
		gatewayv1connect.PublisherServiceCreateInvitationProcedure,
		gatewayv1connect.PublisherServiceGetInvitationProcedure,
		gatewayv1connect.PublisherServiceRevokeInvitationProcedure,
		gatewayv1connect.PublisherServiceCreatePrivateRequestProcedure,
		gatewayv1connect.PublisherServiceGetPrivateRequestProcedure,
		gatewayv1connect.PublisherServiceCancelPrivateRequestProcedure,
	}
	feedProcedures := []string{
		gatewayv1connect.FeedServiceGetServerManifestProcedure,
		gatewayv1connect.FeedServiceListRequestsProcedure,
		gatewayv1connect.FeedServiceGetRequestProcedure,
		gatewayv1connect.FeedServiceListProposalsProcedure,
		gatewayv1connect.FeedServiceGetProposalProcedure,
		gatewayv1connect.FeedServiceGetFeedTopicsProcedure,
	}
	clientProcedures := []string{
		gatewayv1connect.InvitationServiceResolveInvitationProcedure,
		gatewayv1connect.InvitationServiceRedeemInvitationProcedure,
		gatewayv1connect.DeviceServiceGetServerManifestProcedure,
		gatewayv1connect.DeviceServiceListRequestsProcedure,
		gatewayv1connect.DeviceServiceSubmitResultProcedure,
		gatewayv1connect.DeviceServiceRevokeConnectionProcedure,
	}

	for _, one := range []struct {
		name       string
		server     *httptest.Server
		procedures []string
	}{
		{"the read listener", read, append(append([]string{}, publisherProcedures...), clientProcedures...)},
		{"the publisher listener", publish, append(append([]string{}, feedProcedures...), clientProcedures...)},
		{"the client listener", client, append(append([]string{}, feedProcedures...), publisherProcedures...)},
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
