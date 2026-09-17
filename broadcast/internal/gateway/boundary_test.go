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

// The gateway is called; it calls nobody. No publisher is ever contacted — that is the point of the
// mode rather than a detail of it, because a publisher that could be reached could be told which
// phones are interested in it — and no chain, provider or phone either. A fan-out will one day
// speak to Centrifugo (SEE-91), and it will do it through dispatch.Dispatcher, which is a seam this
// check is meant to make people use.
func TestTheGatewayNeverCallsOut(t *testing.T) {
	forbidden := regexp.MustCompile(
		`\b(http\.Get|http\.Post|http\.Head|http\.PostForm|http\.DefaultClient|` +
			`http\.NewRequest|http\.Client\{|net\.Dial|url\.Parse\(.*publisher)`)
	var offenders []string
	for name, source := range shipped(t) {
		if forbidden.MatchString(withoutComments(source)) {
			offenders = append(offenders, name)
		}
	}
	if len(offenders) > 0 {
		slices.Sort(offenders)
		t.Fatalf("these files acquired a way to call out: %v", offenders)
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
		// The contract, and the two documents the rules are about.
		"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1",
		"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/proposal/v1",
		"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/server/v1",
		// Comparing documents, and reading a timestamp as an instant.
		"google.golang.org/protobuf/proto",
		"google.golang.org/protobuf/types/known/timestamppb",
		// The shapes an identity, a name and a piece of text are held to.
		"regexp",
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

// What the gateway keeps, as its own schema spells it. The list is pinned so that a column for a
// subscriber — an address, a chosen amount, a decision, a result — has to be argued for here
// first, and the forbidden words catch the same thing under another name.
func TestTheStoreHasNoColumnForASubscriber(t *testing.T) {
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
	}
	if fmt.Sprint(fields) != fmt.Sprint(pinned) {
		t.Fatalf("the store's columns are\n%v\nexpected\n%v", fields, pinned)
	}

	forbidden := regexp.MustCompile(`(?i)\b(wallet|amount|signature|approval|approved|decision|` +
		`dismissal|execution|outcome|balance|payout|subscriber|device|reader)\w*`)
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
			"channel", "page_size", "page_token", "known_snapshot_sequence",
			"proposals", "next_page_token", "snapshot_sequence", "unchanged",
			"channel", "proposal_id",
			"proposal",
		},
		"publish.proto": {
			"manifest",
			"status", "settings_revision",
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
		forbidden := regexp.MustCompile(`(?i)\b(wallet|amount|signature|approval|approved|` +
			`decision|dismissal|execution|result|outcome|balance|payout|prepared|transaction|` +
			`credential|secret|install|script)\w*`)
		if found := forbidden.FindAllString(proto, -1); len(found) > 0 {
			t.Fatalf("%s grew something about a person or a permission: %v", file, found)
		}
	}
}

// The two APIs are two listeners, and this is that statement at run time: every publisher procedure
// answers 404 on the read handler, and every read procedure answers 404 on the publisher handler.
// Not "is refused" — is not there at all, so no credential, mistake or routing rule in front can
// turn one into the other.
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
		slog.New(slog.NewTextHandler(io.Discard, nil)),
		time.Now,
	)
	read := httptest.NewServer(service.Read)
	defer read.Close()
	publish := httptest.NewServer(service.Publish)
	defer publish.Close()

	for _, one := range []struct {
		name       string
		server     *httptest.Server
		procedures []string
	}{
		{"the read listener", read, []string{
			gatewayv1connect.PublisherServicePublishManifestProcedure,
			gatewayv1connect.PublisherServicePublishProposalProcedure,
			gatewayv1connect.PublisherServiceCancelProposalProcedure,
		}},
		{"the publisher listener", publish, []string{
			gatewayv1connect.FeedServiceGetServerManifestProcedure,
			gatewayv1connect.FeedServiceListProposalsProcedure,
			gatewayv1connect.FeedServiceGetProposalProcedure,
		}},
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
