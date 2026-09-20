// Package boundary_test states what this demo is, as tests over its own source rather than as
// prose in a README (SEE-95, SEE-134, AGENTS.md).
//
// The rules every public-feed publisher obeys are the shared library's, said once over the library
// (publisher-support/api/boundary_test.go). These are the rules about *this* module: it is one of
// two independent demonstrations, it builds and runs without the other, and it holds no provider,
// no discovery and no phone.
package boundary_test

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// root is this module, from this package.
const root = "../.."

// shipped is every Go file in this module that is not a test: the code that runs in the image.
func shipped(t *testing.T) map[string]string {
	t.Helper()
	files := map[string]string{}
	err := filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		switch {
		case err != nil:
			return err
		case info.IsDir():
			return nil
		case !strings.HasSuffix(path, ".go"), strings.HasSuffix(path, "_test.go"):
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

// This demo does not know the other one exists.
//
// It is the whole of what "two independent demonstrations" means, and it is a test because an
// import is one line: a shared helper reached for in a hurry would make one demo's build, image and
// release depend on the other's, and nothing else would notice (SEE-134).
func TestThisDemoDoesNotImportTheOtherDemo(t *testing.T) {
	for path, source := range shipped(t) {
		if strings.Contains(source, "SeekerAgentWallet/demo-prediction") {
			t.Fatalf("%s imports the Prediction demo. Each demo builds, images and runs on its "+
				"own; what they genuinely share is the support library and nothing else", path)
		}
	}
}

// And it needs neither the Direct SDK nor the MCP server. A public-feed publisher sends one
// document to the gateway; it pairs with no phone, serves no agent and holds no key.
func TestThisDemoNeedsNoDirectServer(t *testing.T) {
	for path, source := range shipped(t) {
		for _, forbidden := range []string{
			"SeekerAgentWallet/server-sdk", "SeekerAgentWallet/mcp-server",
			"SeekerAgentWallet/feed-gateway",
		} {
			if strings.Contains(source, forbidden) {
				t.Fatalf("%s imports %s. This demo publishes through the gateway's documented "+
					"HTTP API and needs no direct server, no agent adapter and none of the "+
					"gateway's internals", path, forbidden)
			}
		}
	}
}

// No provider and no discovery is compiled into this demo at all. Its signals are its callers'.
//
// It is the source-level half of "the CopyTrading image contains no prediction provider code": the
// other half is the Dockerfile, which builds only this module.
func TestNoProviderOrDiscoveryIsCompiledIn(t *testing.T) {
	for path, source := range shipped(t) {
		for _, line := range strings.Split(source, "\n") {
			if strings.HasPrefix(strings.TrimSpace(line), "//") {
				continue
			}
			for _, word := range []string{"jupiter.", "discovery.", "markets."} {
				if strings.Contains(line, word) {
					t.Fatalf("%s names %s in code. Discovering markets from a provider is the "+
						"other demo's whole subject; here a signal is something a caller posted",
						path, word)
				}
			}
		}
	}
}

// Delivery is the gateway's. This demo submits one document and is done: it keeps no phone streams,
// no per-subscriber rows and no Firebase credential, so none of these words belongs in it.
func TestThisDemoDeliversNothingItself(t *testing.T) {
	for _, word := range []string{
		"firebase", "fcm", "centrifugo", "redis", "messaging", "websocket", "mcp",
	} {
		pattern := regexp.MustCompile(`(?i)` + word)
		for path, source := range shipped(t) {
			for _, line := range strings.Split(source, "\n") {
				trimmed := strings.TrimSpace(line)
				// A comment may say what this demo does not do; code may not do it.
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

// No address of anybody else's service is compiled in. Where this demo publishes is configuration
// with no default at all, because a gateway is whoever runs one.
func TestNoServiceAddressIsCompiledIn(t *testing.T) {
	pattern := regexp.MustCompile(`https?://[^\s"'` + "`" + `]+`)
	for path, source := range shipped(t) {
		for _, line := range strings.Split(source, "\n") {
			if strings.HasPrefix(strings.TrimSpace(line), "//") {
				continue
			}
			for _, address := range pattern.FindAllString(line, -1) {
				switch {
				case strings.Contains(address, "127.0.0.1"),
					strings.Contains(address, "localhost"),
					strings.Contains(address, "example.com"),
					strings.Contains(address, "example.org"),
					// The XML namespace an HTML page declares: a name, not an address.
					strings.Contains(address, "www.w3.org"):
				default:
					t.Fatalf("%s names %s in code. Where this demo publishes is configuration "+
						"(PUBLISHER_GATEWAY_URL), never a compiled-in address", path, address)
				}
			}
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
		"publisher-support/store", "publisher-support/publish", "publisher-support/signals\"",
		"publisher-support/config",
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

// This demo says in its own main that its signals are its callers'.
//
// It is a test over the source because it is the wiring that decides it: a demo whose authorship
// had been left as discovery would refuse every signal a caller posted, and the first sign of it
// would be a 403 nobody expected.
func TestThisDemoSaysItsSignalsAreItsCallers(t *testing.T) {
	main, found := shipped(t)["cmd/copytrading/main.go"]
	if !found {
		t.Fatal("the CopyTrading command is not in the module")
	}
	if strings.Contains(main, "api.ByDiscovery") {
		t.Fatal("cmd/copytrading declares discovery authorship. Its signals are its callers': " +
			"it discovers nothing, and there is nothing for a cycle to reconcile them against")
	}
	// And it registers exactly one kind, which is the rest of what makes it this demo.
	if !strings.Contains(main, "signals.Swap{}") {
		t.Fatal("cmd/copytrading no longer registers exactly one kind in its own main")
	}
}

// Nothing here knows a subscriber. A phone reads this demo's documents from the gateway and never
// reaches this process at all, so the vocabulary of a bound device or a returned outcome is
// vocabulary this module's code may not carry.
//
// The words are deliberately the unambiguous ones. "amount" and "signature" are not among them,
// because a trader types an amount into this publisher's own API and the operator UI signs its own
// session cookie; neither is anything about a subscriber. What would be a boundary failure is this
// process holding a device, or a decision that came back (docs/security.md).
func TestNoSubscriberIsKnownHere(t *testing.T) {
	for path, source := range shipped(t) {
		for _, line := range strings.Split(source, "\n") {
			trimmed := strings.TrimSpace(line)
			if strings.HasPrefix(trimmed, "//") {
				continue
			}
			for _, word := range []string{"subscriber", "deviceBinding", "DeviceBinding",
				"device_binding", "ownerDecision", "OwnerDecision", "walletAddress",
				"WalletAddress", "executionResult", "ExecutionResult"} {
				if strings.Contains(line, word) {
					t.Fatalf("%s names %q in code (%s). A publisher never learns who is "+
						"subscribed, what they chose, or what came of it (docs/security.md)",
						path, word, trimmed)
				}
			}
		}
	}
}
