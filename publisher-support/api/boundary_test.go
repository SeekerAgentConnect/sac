package api_test

import (
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// The stage boundary, on this side of it: what a public-feed publisher is, said as tests over the
// source rather than as prose in a README (SEE-95, SEE-134, AGENTS.md).
//
// A publisher publishes and stops. It does not deliver to a phone, does not know which phones
// exist, does not read the feed it publishes to, and holds nothing about anybody who reads it. Each
// of those is a thing that could be added in an afternoon by someone who did not know it was a
// boundary, so each of them is a test.
//
// These are the rules about the shared library. Each demo states the rules about itself over its
// own module, because each is built, imaged and deployed on its own
// (demo-copytrading/internal/boundary, demo-prediction/internal/boundary).

// root is this module, from this package.
const root = ".."

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
		// buf.gen.publisher-support.yaml, which the next test is about.
		case strings.Contains(path, filepath.FromSlash("/gen/")):
			return nil
		// Test support. It is ordinary code so that a test in another module can import it, and it
		// is not shipped: it serves the gateway's own procedures, which is the whole point of it
		// and exactly what a publisher may never do. TestNoCommandImportsTestSupport is what keeps
		// it out of a binary.
		case strings.HasPrefix(filepath.ToSlash(path), "../publishertest/"),
			strings.HasPrefix(filepath.ToSlash(path), "../demotest/"):
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
	generated := filepath.Join(root, "gen", "seekervault")
	for _, absent := range []string{
		"gateway/v1/feed.pb.go",
		"gateway/v1/event.pb.go",
		"gateway/v1/gatewayv1connect/feed.connect.go",
	} {
		if _, err := os.Stat(filepath.Join(generated, filepath.FromSlash(absent))); err == nil {
			t.Fatalf("%s is generated for this module: a publisher publishes and never reads a "+
				"feed, and the way that is true is that no client for one exists here "+
				"(buf.gen.publisher-support.yaml)", absent)
		}
	}
	// And what is generated is the public publisher API and the documents it carries.
	for _, present := range []string{
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

// A running template has one publication path. Nothing else opens a connection to a chain,
// provider, phone, or broker.
func TestOnlyTheTemplatePublisherCallsTheGateway(t *testing.T) {
	for path, source := range shipped(t) {
		if !strings.Contains(source, "gatewayv1connect") {
			continue
		}
		if path != "gateway/gateway.go" {
			t.Fatalf("%s imports the gateway's generated client. Only the template publisher may call it", path)
		}
	}
	// And the generated server handler is never mounted: a publisher serves none of the gateway's
	// procedures. (The test doubles mount it, which is what makes them a gateway, and no command
	// imports those.)
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

// No address of anybody else's service is compiled into the shared library at all.
//
// Where a demo publishes is configuration with no default, because a gateway is whoever runs one: a
// publisher with an address in its binary would be a publisher nobody else could run, and the
// gateway's origin is the one thing a phone compares a manifest against.
//
// A provider's own host is the other kind of address, and it is not here: it belongs to the one
// demo written against that provider, which states that rule over its own module
// (demo-prediction/internal/boundary).
func TestNoServiceAddressIsCompiledIn(t *testing.T) {
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
				default:
					t.Fatalf("%s names %s in code. Where a demo publishes is configuration "+
						"(PUBLISHER_GATEWAY_URL), never a compiled-in address", path, address)
				}
			}
		}
	}
}

// The operator CLI is a client of the business API and nothing else. There is no second way to
// store a signal, so validation, the identity, the revision and the publication cannot be gone
// around — and this tool has no privileged access of its own to go around them with.
func TestTheCLIIsOnlyAClient(t *testing.T) {
	cli, found := shipped(t)["publisherctl/publisherctl.go"]
	if !found {
		t.Fatal("the operator CLI is not in the library")
	}
	for _, forbidden := range []string{
		"publisher-support/store", "publisher-support/publish", "publisher-support/gateway",
		"publisher-support/signals\"", "publisher-support/config",
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

// The test support is not shipped. It serves the gateway's own procedures and starts real
// processes, so a command that imported it would be a publisher that can answer a publication — and
// the reason it is ordinary code rather than a `_test.go` file is only that another module's tests
// have to import it (publisher-support/publishertest).
func TestNoCommandImportsTestSupport(t *testing.T) {
	for path, source := range shipped(t) {
		for _, support := range []string{"publisher-support/publishertest", "publisher-support/demotest"} {
			if strings.Contains(source, support) {
				t.Fatalf("%s imports %s. It is test support: it mounts the gateway's own handler "+
					"and starts processes, and nothing that ships may do either", path, support)
			}
		}
	}
}

// Neither credential ever reaches a log line. This is the runtime version of the rule: a series of
// calls, including refused ones, against a template whose log is a buffer.
func TestNoCredentialReachesALogLine(t *testing.T) {
	held := start(t, &fakeGateway{})
	held.Call(http.MethodGet, "/v1/status", nil)
	held.Call(http.MethodGet, "/v1/status", nil, "Authorization", "Bearer wrong")
	held.Call(http.MethodGet, "/v1/status", nil, "Authorization", "-")
	held.Create("key-1", swapStatement())
	held.Create("key-1", swapStatement())
	held.Raw(http.MethodPost, "/v1/signals", `{"wallet":"mine"}`)

	written := held.Log.String()
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
		answered := held.Raw(http.MethodPost, "/v1/signals", body)
		if answered.Status != http.StatusBadRequest {
			t.Fatalf("%s answered %d: %s", body, answered.Status, answered.Raw)
		}
		if answered.Problem() != "bad_request" {
			t.Fatalf("problem %q: %s", answered.Problem(), answered.Raw)
		}
		detail, _ := answered.Body["detail"].(string)
		if !strings.Contains(detail, "there is no field") {
			t.Fatalf("the refusal does not say that there is no such field: %s", detail)
		}
	}
	// Nor through a term, which is the other way somebody might try it.
	for _, name := range []string{"wallet", "amount", "payer", "signature", "decision"} {
		body := swapStatement()
		body["terms"].(map[string]string)[name] = "something"
		answered := held.Create("key-"+name, body)
		if answered.Status != http.StatusBadRequest || answered.Problem() != "unknown_term" {
			t.Fatalf("%s: %d %s", name, answered.Status, answered.Raw)
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
		answered := held.Raw(http.MethodPost, "/v1/signals", body)
		if answered.Status != http.StatusBadRequest {
			t.Fatalf("%s answered %d: %s", body, answered.Status, answered.Raw)
		}
	}
}
