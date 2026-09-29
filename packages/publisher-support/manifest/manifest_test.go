package manifest

import (
	"net/url"
	"slices"
	"strings"
	"testing"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/environment"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/network"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

const server = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

func settings() Settings {
	return Settings{
		ServerID:    server,
		GatewayURL:  "https://feeds.example.com",
		Environment: "production",
		Networks:    []network.Network{network.Mainnet},
		Requirement: signals.Swap{}.Requirement(),
		DisplayName: "Copy trading desk",
	}
}

// A template publishes a gateway-feed manifest and nothing else. A direct manifest carries a URL,
// and the gateway refuses to relay one: relaying it would let a publisher point a phone at an
// address of its choosing.
func TestAManifestIsAlwaysAGatewayFeed(t *testing.T) {
	document := Document(settings(), 3)
	if document.GetMode() != serverv1.ConnectionMode_CONNECTION_MODE_GATEWAY_FEED {
		t.Fatalf("mode %s", document.GetMode())
	}
	if document.GetDirect() != nil {
		t.Fatal("a template published a direct reference")
	}
	feed := document.GetFeed()
	if feed.GetGatewayUrl() != "https://feeds.example.com" {
		t.Fatalf("gateway %q", feed.GetGatewayUrl())
	}
	if feed.GetChannel() != "server/"+server {
		t.Fatalf("channel %q: a publisher may name only its own", feed.GetChannel())
	}
	if document.GetProtocolVersion() != Protocol || Protocol != 1 {
		t.Fatalf("protocol %d", document.GetProtocolVersion())
	}
	if document.GetSettingsRevision() != 3 {
		t.Fatalf("revision %d", document.GetSettingsRevision())
	}
}

// One deployment serves one environment, and the manifest names exactly that one: a phone refuses
// to treat a server as supported in an environment the server does not name, because sandbox and
// production are different promises about what happens when the owner approves.
func TestAManifestNamesExactlyOneEnvironment(t *testing.T) {
	for name, expected := range map[environment.Environment]serverv1.ServerEnvironment{
		environment.Production: serverv1.ServerEnvironment_SERVER_ENVIRONMENT_PRODUCTION,
		environment.Sandbox:    serverv1.ServerEnvironment_SERVER_ENVIRONMENT_SANDBOX,
	} {
		held := settings()
		held.Environment = name
		environments := Document(held, 1).GetEnvironments()
		if len(environments) != 1 || environments[0] != expected {
			t.Fatalf("%s: %v", name, environments)
		}
	}
}

// And an environment that came from anywhere but the configuration is published as unspecified
// rather than as production (SEE-97). The gateway refuses that document
// (GATEWAY_PROBLEM_BAD_ENVIRONMENT), which is a deployment that does not publish; the alternative
// is a deployment that publishes the promise with the money attached to it because a string was
// misspelled somewhere.
func TestAnEnvironmentFromNowhereIsNotPublishedAsProduction(t *testing.T) {
	held := settings()
	held.Environment = "staging"
	environments := Document(held, 1).GetEnvironments()
	if len(environments) != 1 ||
		environments[0] != serverv1.ServerEnvironment_SERVER_ENVIRONMENT_UNSPECIFIED {
		t.Fatalf("%v", environments)
	}
}

// The plugin requirement is a name and a contract range, from the kind the template registered.
func TestTheRequiredPluginComesFromTheKind(t *testing.T) {
	plugins := Document(settings(), 1).GetRequiredPlugins()
	if len(plugins) != 1 {
		t.Fatalf("%d required plugins", len(plugins))
	}
	if plugins[0].GetPluginId() != "jupiter.swap" {
		t.Fatalf("plugin %q", plugins[0].GetPluginId())
	}
	if plugins[0].GetMinContract() != 1 || plugins[0].GetMaxContract() != 1 {
		t.Fatalf("contract %d..%d", plugins[0].GetMinContract(), plugins[0].GetMaxContract())
	}
}

// The fingerprint is the settings and not the revision, which is what lets the revision be what it
// is for: a number that moves when the settings move, and stays put across a restart.
func TestTheFingerprintIsTheSettingsAndNotTheRevision(t *testing.T) {
	held := Fingerprint(settings())
	if Fingerprint(settings()) != held {
		t.Fatal("the same settings produced two fingerprints")
	}
	for name, apply := range map[string]func(*Settings){
		"the gateway":     func(s *Settings) { s.GatewayURL = "https://feeds.example.org" },
		"the environment": func(s *Settings) { s.Environment = "sandbox" },
		"the name":        func(s *Settings) { s.DisplayName = "Another desk" },
		// A network added is a new revision every phone re-reads (SEE-174), and so is one taken
		// away — including the last one.
		"a network added": func(s *Settings) {
			s.Networks = []network.Network{network.Mainnet, network.Devnet}
		},
		"no networks":     func(s *Settings) { s.Networks = nil },
		"another network": func(s *Settings) { s.Networks = []network.Network{network.Devnet} },
		"the plugin": func(s *Settings) {
			s.Requirement = signals.Requirement{PluginID: "jupiter.prediction", MinContract: 1,
				MostContract: 1}
		},
		"the contract range": func(s *Settings) {
			s.Requirement.MostContract = 2
		},
		"the publisher": func(s *Settings) {
			s.ServerID = "0e1f2a3b-4c5d-4e6f-8a7b-8c9d0e1f2a3b"
		},
	} {
		t.Run(name, func(t *testing.T) {
			moved := settings()
			apply(&moved)
			if Fingerprint(moved) == held {
				t.Fatalf("%s did not change the fingerprint", name)
			}
		})
	}
}

// The networks are published in canonical order whatever order they were configured in, so the
// same networks make the same document and the same fingerprint — and a reorder is not a revision.
func TestTheNetworksArePublishedInCanonicalOrder(t *testing.T) {
	held := settings()
	held.Networks = []network.Network{network.Testnet, network.Mainnet, network.Devnet}
	published := Document(held, 1).GetFeed().GetSupportedNetworks()
	expected := []serverv1.SolanaNetwork{
		serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET,
		serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET,
		serverv1.SolanaNetwork_SOLANA_NETWORK_TESTNET,
	}
	if !slices.Equal(published, expected) {
		t.Fatalf("published %v", published)
	}
	reordered := settings()
	reordered.Networks = []network.Network{network.Devnet, network.Testnet, network.Mainnet}
	if Fingerprint(held) != Fingerprint(reordered) {
		t.Fatal("the same networks in another order moved the fingerprint")
	}
}

// No networks is published as none (SEE-174): never as Mainnet, which is what a phone would sign on.
func TestNoNetworksIsPublishedAsNone(t *testing.T) {
	held := settings()
	held.Networks = nil
	if published := Document(held, 1).GetFeed().GetSupportedNetworks(); len(published) != 0 {
		t.Fatalf("a deployment that declared no network published %v", published)
	}
}

// The reference is how a phone adds this feed, and it carries no secret: a feed is a broadcast, so
// a reference can be printed in a README or a QR code and holding one grants nothing.
func TestTheReferenceCarriesNoSecret(t *testing.T) {
	reference := Reference("https://feeds.example.com", server)
	expected := "seekervault://feed?v=1&gateway=https%3A%2F%2Ffeeds.example.com&server=" + server
	if reference != expected {
		t.Fatalf("%s\nexpected %s", reference, expected)
	}

	// It parses by the phone's rules: a seekervault://feed URI with exactly three parameters.
	parsed, err := url.Parse(reference)
	if err != nil {
		t.Fatal(err)
	}
	if parsed.Scheme != "seekervault" || parsed.Host != "feed" {
		t.Fatalf("%s://%s", parsed.Scheme, parsed.Host)
	}
	query := parsed.Query()
	if len(query) != 3 {
		t.Fatalf("%d parameters: %v", len(query), query)
	}
	if query.Get("v") != "1" || query.Get("server") != server ||
		query.Get("gateway") != "https://feeds.example.com" {
		t.Fatalf("%v", query)
	}
	// And a loopback gateway, which is how a development feed is added from a debug build.
	local := Reference("http://127.0.0.1:8080", server)
	if !strings.Contains(local, "gateway=http%3A%2F%2F127.0.0.1%3A8080") {
		t.Fatalf("%s", local)
	}
}
