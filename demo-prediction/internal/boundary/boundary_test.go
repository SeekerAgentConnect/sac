// Package boundary_test states what this demo is, as tests over its own source rather than as
// prose in a README (SEE-96, SEE-134, AGENTS.md).
//
// The rules every public-feed publisher obeys are the shared library's, said once over the library
// (publisher-support/api/boundary_test.go). These are the rules about *this* module: it is one of
// two independent demonstrations, it builds and runs without the other, it reads one provider in
// one place, and it holds no phone.
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
	// Six packages ship here: the command, the API wiring, the configuration, the two halves of
	// discovery and the provider client. The floor is what says this test read the module rather
	// than an empty directory.
	if len(files) < 6 {
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
		if strings.Contains(source, "SeekerAgentWallet/demo-copytrading") {
			t.Fatalf("%s imports the CopyTrading demo. Each demo builds, images and runs on its "+
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

// One place decides how the provider is reached: the client is built in this demo's own main, from
// its own configuration, and nothing else in the module constructs one.
//
// It is the same rule as "one package calls the gateway", for the same reason — a second place
// would be a second set of timeouts, a second pacing, and a second answer to what a failure was.
func TestOnlyThisDemosOwnMainBuildsTheProvider(t *testing.T) {
	for path, source := range shipped(t) {
		if !strings.Contains(source, "jupiter.New(") {
			continue
		}
		if path != "cmd/prediction/main.go" {
			t.Fatalf("%s builds a provider client. It is built once, in this demo's own main, "+
				"from the settings its deployment gave it", path)
		}
	}
	// And the provider is reached through that one package: nothing else imports it except the
	// reconciler it feeds, the configuration that describes it, and that main.
	for path, source := range shipped(t) {
		if !strings.Contains(source, "demo-prediction/internal/jupiter") {
			continue
		}
		switch path {
		case "cmd/prediction/main.go", "internal/discovery/discovery.go",
			"internal/discovery/reconcile.go", "internal/config/config.go":
		default:
			t.Fatalf("%s imports the provider's package. The provider is read in one place and "+
				"turned into this demo's own documents; everything else is written against a "+
				"kind and a gateway", path)
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

// No address of anybody else's service is compiled in — with one exception, which is a rule rather
// than a hole in one.
//
// Where this demo *publishes* is configuration with no default at all, because a gateway is whoever
// runs one: a publisher with an address in its binary would be a publisher nobody else could run,
// and the gateway's origin is the one thing a phone compares a manifest against.
//
// The prediction provider is the other kind of address. This demo is written against that
// provider's answers — decoded field by field, with its error codes and its pagination in this
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
				case strings.Contains(address, "127.0.0.1"),
					strings.Contains(address, "localhost"),
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
					t.Fatalf("%s names %s in code. Where this demo publishes is configuration "+
						"(PUBLISHER_GATEWAY_URL), never a compiled-in address", path, address)
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

// This demo says in its own main that its signals are its own discovery's.
//
// It is a test over the source because it is the wiring that decides it: a demo whose API had been
// left writable would accept a caller's signal and then quietly undo it on the next cycle, which is
// the kind of bug that shows up as "my signal disappeared" three days later
// (internal/discovery).
func TestThisDemoSaysItsSignalsAreItsOwnDiscovery(t *testing.T) {
	main, found := shipped(t)["cmd/prediction/main.go"]
	if !found {
		t.Fatal("the Prediction command is not in the module")
	}
	if !strings.Contains(main, "Authorship: api.ByDiscovery") {
		t.Fatal("cmd/prediction does not declare that its signals are written by its own " +
			"discovery, so its API would accept a caller's and a cycle would undo it")
	}
	// And it registers exactly one kind, which is the rest of what makes it this demo.
	if !strings.Contains(main, "signals.Prediction{}") {
		t.Fatal("cmd/prediction no longer registers exactly one kind in its own main")
	}
}

// Nothing here knows a subscriber. A phone reads this demo's documents from the gateway and never
// reaches this process at all, so the vocabulary of a bound device or a returned outcome is
// vocabulary this module's code may not carry.
//
// The words are deliberately the unambiguous ones: a deposit bound is this demo's own statement
// about every market, not anybody's stake. What would be a boundary failure is this process holding
// a device, or a side and an amount that came back (docs/security.md).
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
