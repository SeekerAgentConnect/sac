package api

import (
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// The stage boundary, on this side of it: what a publisher template is, said as tests over its own
// source rather than as prose in a README (SEE-95, AGENTS.md).
//
// A publisher publishes and stops. It does not deliver to a phone, does not know which phones
// exist, does not read the feed it publishes to, and holds nothing about anybody who reads it. Each
// of those is a thing that could be added in an afternoon by someone who did not know it was a
// boundary, so each of them is a test.

// root is this module, from this package.
const root = "../.."

// shipped is every Go file in the module that is not a test: the code that runs in the container.
func shipped(t *testing.T) map[string]string {
	t.Helper()
	files := map[string]string{}
	err := filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		switch {
		case err != nil:
			return err
		case info.IsDir():
			return nil
		case !strings.HasSuffix(path, ".go"):
			return nil
		case strings.HasSuffix(path, "_test.go"):
			return nil
		// Generated from proto/ and never edited by hand; what is in it is settled by
		// buf.gen.publisher.yaml, which the next test is about.
		case strings.Contains(path, filepath.Join("internal", "gen")):
			return nil
		}
		contents, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		files[filepath.ToSlash(strings.TrimPrefix(path, root+"/"))] = string(contents)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(files) < 8 {
		t.Fatalf("only %d shipped files were found; this test is not reading the module", len(files))
	}
	return files
}

// A template cannot read a feed, because no feed client is compiled for it. That is the boundary
// itself rather than a rule about it — the same argument that keeps the publisher API out of the
// phone's generated code (buf.gen.yaml).
func TestNoFeedClientIsCompiledForATemplate(t *testing.T) {
	generated := filepath.Join(root, "internal", "gen", "seekervault")
	for _, absent := range []string{
		"gateway/v1/feed.pb.go",
		"gateway/v1/event.pb.go",
		"gateway/v1/gatewayv1connect/feed.connect.go",
	} {
		if _, err := os.Stat(filepath.Join(generated, filepath.FromSlash(absent))); err == nil {
			t.Fatalf("%s is generated for this module: a publisher publishes and never reads a "+
				"feed, and the way that is true is that no client for one exists here "+
				"(buf.gen.publisher.yaml)", absent)
		}
	}
	// And what is generated is the publisher API, its onboarding types, and the documents it
	// carries. The SDK uses the publisher service for invitations; no DeviceService client is used
	// by a template.
	for _, present := range []string{
		"gateway/v1/onboarding.pb.go",
		"gateway/v1/gatewayv1connect/onboarding.connect.go",
		"gateway/v1/publish.pb.go",
		"gateway/v1/problem.pb.go",
		"gateway/v1/gatewayv1connect/publish.connect.go",
		"proposal/v1/proposal.pb.go",
		"server/v1/manifest.pb.go",
	} {
		if _, err := os.Stat(filepath.Join(generated, filepath.FromSlash(present))); err != nil {
			t.Fatalf("%s is not generated: %v", present, err)
		}
	}
}

// A running template has one publication path. SEE-109's developer-facing SDK is the one other
// package allowed to call the publisher service for private onboarding and requests. Nothing else
// opens a connection to a chain, provider, phone, or broker.
func TestOnlyTheTemplatePublisherAndServerSDKCallTheGateway(t *testing.T) {
	for path, source := range shipped(t) {
		if !strings.Contains(source, "gatewayv1connect") {
			continue
		}
		if path != "internal/publish/publish.go" && path != "sdk/gateway.go" {
			t.Fatalf("%s imports the gateway's generated client. Only the template publisher "+
				"and the developer-facing Server SDK may call it", path)
		}
	}
	// And the generated server handler is never mounted: a template serves none of the gateway's
	// procedures. (The tests mount it, which is what makes them a gateway.)
	for path, source := range shipped(t) {
		if strings.Contains(source, "NewPublisherServiceHandler") {
			t.Fatalf("%s mounts the publisher API. A template calls it and does not serve it",
				path)
		}
	}
}

// Delivery is the gateway's. A template submits one document and is done: it keeps no phone
// streams, no per-user rows and no Firebase credential, so none of these words belongs in it.
func TestATemplateDeliversNothingItself(t *testing.T) {
	// Machinery, not vocabulary: these are the names of the things that deliver, and a template
	// that named one would be doing delivery. Words like "subscriber" are deliberately not here —
	// this template's own messages talk about subscribers in order to say that it holds nothing
	// about them.
	for _, word := range []string{
		"firebase", "fcm", "centrifugo", "redis", "messaging", "websocket", "mcp",
	} {
		pattern := regexp.MustCompile(`(?i)` + word)
		for path, source := range shipped(t) {
			// Developer examples are not publisher-template runtime code. SEE-109 deliberately
			// includes an MCP-backed server example whose tool handler calls the SDK; the example
			// still delivers nothing and holds no broker or phone transport.
			if strings.HasPrefix(path, "examples/") {
				continue
			}
			for _, line := range strings.Split(source, "\n") {
				trimmed := strings.TrimSpace(line)
				// A comment may say what this template does not do; code may not do it.
				if strings.HasPrefix(trimmed, "//") {
					continue
				}
				if pattern.MatchString(line) {
					t.Fatalf("%s: %q appears in code (%s). Streaming and push delivery are the "+
						"gateway's (SEE-91, SEE-92); a publisher submits a document and stops",
						path, word, trimmed)
				}
			}
		}
	}
}

// No address of anybody else's service is compiled in — with one exception, which is a rule rather
// than a hole in one.
//
// Where this template *publishes* is configuration with no default at all, because a gateway is
// whoever runs one: a publisher with an address in its binary would be a publisher nobody else
// could run, and the gateway's origin is the one thing a phone compares a manifest against.
//
// The prediction provider is the other kind of address. The prediction template is written against
// that provider's answers — decoded field by field, with its error codes and its pagination in this
// module's own tests — so its host is a fact about the code rather than a deployment's choice, and
// pretending otherwise would be a setting that cannot be changed to anything that works. It is
// therefore allowed in exactly one file, and this test is what keeps it there. That is the rule the
// phone applies to the same constant (`JUPITER_ENDPOINT`, `StageBoundaryTest`), and for the same
// reason.
func TestNoServiceAddressIsCompiledInExceptTheProvidersOwn(t *testing.T) {
	// The one file: the provider's client. Its own package comment says why.
	const provider = "internal/jupiter/jupiter.go"
	named := false
	pattern := regexp.MustCompile(`https?://[^\s"'` + "`" + `]+`)
	for path, source := range shipped(t) {
		for _, line := range strings.Split(source, "\n") {
			if strings.HasPrefix(strings.TrimSpace(line), "//") {
				continue
			}
			for _, address := range pattern.FindAllString(line, -1) {
				switch {
				// A loopback default: reaches nothing but the machine it runs on.
				case strings.Contains(address, "127.0.0.1"),
					strings.Contains(address, "localhost"),
					// An example in a message, which is how an operator learns the shape of the
					// setting they have to fill in.
					strings.Contains(address, "example.com"),
					strings.Contains(address, "example.org"):
				case strings.Contains(address, "jup.ag"):
					if path != provider {
						t.Fatalf("%s names the prediction provider's host (%s). It belongs in "+
							"%s and nowhere else: one file is written against that provider, and "+
							"everything else in this module is written against a kind and a "+
							"gateway", path, address, provider)
					}
					named = true
				default:
					t.Fatalf("%s names %s in code. Where this template publishes is "+
						"configuration (PUBLISHER_GATEWAY_URL), never a compiled-in address",
						path, address)
				}
			}
		}
	}
	// And the exception is not vacuous: the file does name it, so the rule above is about something.
	if !named {
		t.Fatalf("%s no longer names the provider's host, so this test is not about anything",
			provider)
	}
}

// Neither credential ever reaches a log line. This is the runtime version of the rule: a series of
// calls, including refused ones, against a template whose log is a buffer.
func TestNoCredentialReachesALogLine(t *testing.T) {
	held := start(t, &fakeGateway{})
	held.call(http.MethodGet, "/v1/status", nil)
	held.call(http.MethodGet, "/v1/status", nil, "Authorization", "Bearer wrong")
	held.call(http.MethodGet, "/v1/status", nil, "Authorization", "-")
	held.create("key-1", swapStatement())
	held.create("key-1", swapStatement())
	held.raw(http.MethodPost, "/v1/signals", `{"wallet":"mine"}`)

	written := held.log.String()
	for _, secret := range []string{token, "bhnRxR4o5RPDqYKTJZfmfbv9OmGGKFNxBOebFHYGJuc"} {
		if strings.Contains(written, secret) {
			t.Fatalf("a credential is in the log:\n%s", written)
		}
	}
	// It did log something, so the absence above is not the absence of logging.
	if !strings.Contains(written, "refused") {
		t.Fatalf("nothing was logged at all:\n%s", written)
	}
}

// Nothing about a subscriber can be sent here, and the decoder says so rather than dropping it.
// A caller that believes this template keeps execution records is told that it does not.
func TestNothingAboutASubscriberCanBeSent(t *testing.T) {
	held := start(t, &fakeGateway{})
	for _, body := range []string{
		`{"expires_at":"2026-09-17T21:00:00Z","wallet":"9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9qWT"}`,
		`{"expires_at":"2026-09-17T21:00:00Z","amount":"1000000"}`,
		`{"expires_at":"2026-09-17T21:00:00Z","decision":"approved"}`,
		`{"expires_at":"2026-09-17T21:00:00Z","signature":"4RPDqYKTJZfmfbv9OmGGKFNxBOebFHYGJuc"}`,
		`{"expires_at":"2026-09-17T21:00:00Z","result":{"status":"confirmed"}}`,
		`{"expires_at":"2026-09-17T21:00:00Z","subscriber":"somebody"}`,
		`{"expires_at":"2026-09-17T21:00:00Z","fcm_token":"abc"}`,
	} {
		answered := held.raw(http.MethodPost, "/v1/signals", body)
		if answered.status != http.StatusBadRequest {
			t.Fatalf("%s answered %d: %s", body, answered.status, answered.raw)
		}
		if answered.problem() != "bad_request" {
			t.Fatalf("problem %q: %s", answered.problem(), answered.raw)
		}
		detail, _ := answered.body["detail"].(string)
		if !strings.Contains(detail, "there is no field") {
			t.Fatalf("the refusal does not say that there is no such field: %s", detail)
		}
	}
	// Nor through a term, which is the other way somebody might try it.
	for _, name := range []string{"wallet", "amount", "payer", "signature", "decision"} {
		body := swapStatement()
		body["terms"].(map[string]string)[name] = "something"
		answered := held.create("key-"+name, body)
		if answered.status != http.StatusBadRequest || answered.problem() != "unknown_term" {
			t.Fatalf("%s: %d %s", name, answered.status, answered.raw)
		}
	}
}

// A template holds one kind, registered in its own main, and the API cannot be talked into another
// operation: the operation and the plugin in every document come from the kind, and there is no
// field anywhere for a caller to name either.
func TestACallerCannotNameAnOperationOrAPlugin(t *testing.T) {
	held := start(t, &fakeGateway{})
	for _, body := range []string{
		`{"expires_at":"2026-09-17T21:00:00Z","operation":"transfer","terms":{}}`,
		`{"expires_at":"2026-09-17T21:00:00Z","plugin_id":"jupiter.prediction","terms":{}}`,
		`{"expires_at":"2026-09-17T21:00:00Z","server_id":"0e1f2a3b-4c5d-4e6f-8a7b-8c9d0e1f2a3b"}`,
		`{"expires_at":"2026-09-17T21:00:00Z","channel":"server/0e1f2a3b-4c5d-4e6f-8a7b-8c9d0e1f2a3b"}`,
		`{"expires_at":"2026-09-17T21:00:00Z","proposal_id":"0e1f2a3b-4c5d-4e6f-8a7b-8c9d0e1f2a3b"}`,
		`{"expires_at":"2026-09-17T21:00:00Z","revision":"7"}`,
		`{"expires_at":"2026-09-17T21:00:00Z","status":"cancelled"}`,
		`{"expires_at":"2026-09-17T21:00:00Z","created_at":"2020-01-01T00:00:00Z"}`,
		`{"expires_at":"2026-09-17T21:00:00Z","environment":"sandbox"}`,
	} {
		answered := held.raw(http.MethodPost, "/v1/signals", body)
		if answered.status != http.StatusBadRequest {
			t.Fatalf("%s answered %d: %s", body, answered.status, answered.raw)
		}
	}
}

// The API is the one path in. There is no second way to store a signal, so validation, the
// identity, the revision and the publication cannot be gone around — and the CLI is a client of
// this, with no privileged access of its own.
func TestTheCLIIsOnlyAClient(t *testing.T) {
	files := shipped(t)
	cli, found := files["cmd/publishctl/main.go"]
	if !found {
		t.Fatal("the CLI is not in the module")
	}
	for _, forbidden := range []string{
		"internal/store", "internal/publish", "internal/signals\"", "internal/config",
	} {
		if strings.Contains(cli, forbidden) {
			t.Fatalf("the CLI imports %s. It is a client of the API and nothing else, which is "+
				"what makes the API the one path in", forbidden)
		}
	}
	// It does reach the API over HTTP, like any other caller.
	if !strings.Contains(cli, "http.NewRequest") {
		t.Fatal("the CLI does not call the API over HTTP")
	}
}

// The two templates differ in one thing, and each of them says which it is in its own main.
//
// It is a test over the source because it is the wiring that decides it: a Prediction template
// whose API had been left writable would accept a caller's signal and then quietly undo it on the
// next cycle, which is the kind of bug that shows up as "my signal disappeared" three days later
// (internal/discovery).
func TestEachTemplateSaysWhoWritesItsSignals(t *testing.T) {
	files := shipped(t)
	prediction, found := files["cmd/prediction/main.go"]
	if !found {
		t.Fatal("the Prediction template is not in the module")
	}
	if !strings.Contains(prediction, "Authorship: api.ByDiscovery") {
		t.Fatal("cmd/prediction does not declare that its signals are written by its own " +
			"discovery, so its API would accept a caller's and a cycle would undo it")
	}
	copytrading, found := files["cmd/copytrading/main.go"]
	if !found {
		t.Fatal("the CopyTrading template is not in the module")
	}
	if strings.Contains(copytrading, "ByDiscovery") {
		t.Fatal("cmd/copytrading declares discovery authorship. Its signals are its callers': " +
			"it discovers nothing, and there is nothing for a cycle to reconcile them against")
	}
	// And the Prediction template registers the other kind, which is the rest of the difference.
	if !strings.Contains(prediction, "signals.Prediction{}") ||
		!strings.Contains(copytrading, "signals.Swap{}") {
		t.Fatal("a template no longer registers exactly one kind in its own main")
	}
}

// One place decides how the provider is reached: the client is built in the Prediction template's
// own main, from its own configuration, and nothing else in the module constructs one.
//
// It is the same rule as "one package calls the gateway", for the same reason — a second place
// would be a second set of timeouts, a second pacing, and a second answer to what a failure was.
func TestOnlyTheTemplatesOwnMainBuildsTheProvider(t *testing.T) {
	for path, source := range shipped(t) {
		if !strings.Contains(source, "jupiter.New(") {
			continue
		}
		if path != "cmd/prediction/main.go" {
			t.Fatalf("%s builds a provider client. It is built once, in the Prediction "+
				"template's own main, from the settings its deployment gave it", path)
		}
	}
	// And the provider is reached through that one package: nothing else imports it except the
	// reconciler it feeds, the configuration that describes it, and that main.
	for path, source := range shipped(t) {
		if !strings.Contains(source, "publisher/internal/jupiter") {
			continue
		}
		switch path {
		case "cmd/prediction/main.go", "internal/discovery/discovery.go",
			"internal/discovery/reconcile.go", "internal/config/prediction.go":
		default:
			t.Fatalf("%s imports the provider's package. The provider is read in one place and "+
				"turned into this template's own documents; everything else is written against "+
				"a kind and a gateway", path)
		}
	}
}
