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

// This demo ships the operator CLI, and the command it ships is a main and nothing more. The
// implementation is the shared library's, because both demos answer the same API; what this module
// owns is the binary (publisher-support/publisherctl).
func TestTheCLICommandIsOnlyAMain(t *testing.T) {
	files := shipped(t)
	cli, found := files["cmd/publishctl/main.go"]
	if !found {
		t.Fatal("the CLI command is not in the module")
	}
	if !strings.Contains(cli, "publisher-support/publisherctl") {
		t.Fatal("cmd/publishctl no longer uses the shared client. Two copies of one client of " +
			"one API would be two clients of it")
	}
	// Quoted, so that "publisher-support/publisherctl" is not read as the drainer.
	for _, forbidden := range []string{
		`publisher-support/store"`, `publisher-support/publish"`, `publisher-support/api"`,
	} {
		if strings.Contains(cli, forbidden) {
			t.Fatalf("the CLI command imports %s. It is a client of the API over HTTP and "+
				"nothing else, which is what makes the API the one path in", forbidden)
		}
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

// Nothing here knows what a subscriber did. A phone reads this demo's documents from the gateway
// and never reports back, so the vocabulary of a returned outcome is vocabulary this module's code
// may not carry.
//
// Since SEE-156 this demo's feed is restricted, so it does know which wallets and devices it
// admitted — deciding that is its job, and the shared library (publisher-support/access) holds that
// state behind its own API. What stays forbidden here is the rest: an owner's decision about a
// signal, an execution result, and a device binding in the retired gateway-private sense.
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

// The shipped CopyTrading demo is the restricted-feed example (SEE-156), and that is compiled in
// rather than configured: its main builds the access service with the operator's manual approval,
// guards every publication on the gateway confirming the restriction, and serves the
// authentication endpoint. The Prediction demo is the public one and asserts the opposite.
func TestThisDemoIsARestrictedFeed(t *testing.T) {
	source, ok := shipped(t)["cmd/copytrading/main.go"]
	if !ok {
		t.Fatal("cmd/copytrading/main.go is missing")
	}
	for _, expected := range []string{
		"access.ManualApproval{}", "access.NewGuard(", "Guard: guard.Check",
		"AuthOrigin:  restricted.AuthOrigin", "devices.Handler(", "Access:      devices",
	} {
		if !strings.Contains(source, expected) {
			t.Fatalf("cmd/copytrading/main.go no longer contains %q: the CopyTrading demo must run "+
				"restricted (docs/wiki/restricted-feeds.md)", expected)
		}
	}
}
