package network

import (
	"slices"
	"strings"
	"testing"

	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/server/v1"
)

// The three networks and nothing else, each spelled one way, each with the manifest value the
// request contract gives it (seekervault.request.v1.Network uses the same numbers on purpose).
func TestEachNetworkHasOneNameAndOneWireValue(t *testing.T) {
	for name, wire := range map[string]serverv1.SolanaNetwork{
		"mainnet": serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET,
		"devnet":  serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET,
		"testnet": serverv1.SolanaNetwork_SOLANA_NETWORK_TESTNET,
	} {
		named, ok := Parse(name)
		if !ok || named.String() != name || named.Wire() != wire {
			t.Fatalf("%s parsed as %q (%v), wire %v", name, named, ok, named.Wire())
		}
	}
	for _, name := range []string{"", "Mainnet", "mainnet-beta", "localnet", "solana:mainnet"} {
		if _, ok := Parse(name); ok {
			t.Fatalf("%q was read as a network", name)
		}
	}
}

// A value that did not come from Parse is published as unspecified, which the gateway refuses,
// rather than as Mainnet: the fail-closed direction.
func TestANetworkFromNowhereIsNotPublishedAsMainnet(t *testing.T) {
	if wire := Network("mainnet-beta").Wire(); wire != serverv1.SolanaNetwork_SOLANA_NETWORK_UNSPECIFIED {
		t.Fatalf("an unknown network was published as %v", wire)
	}
}

// A list is read as a set and returned in the manifest's order, which is the protocol's (by wire
// value) and not the alphabet's.
func TestAListIsASetInCanonicalOrder(t *testing.T) {
	for text, expected := range map[string][]Network{
		"mainnet":                 {Mainnet},
		"devnet, mainnet":         {Mainnet, Devnet},
		"TESTNET,devnet,mainnet":  {Mainnet, Devnet, Testnet},
		" testnet , mainnet ":     {Mainnet, Testnet},
		"none":                    nil,
		" None ":                  nil,
		"mainnet,devnet,testnet":  {Mainnet, Devnet, Testnet},
		"devnet,testnet":          {Devnet, Testnet},
		"testnet,devnet,mainnet ": {Mainnet, Devnet, Testnet},
	} {
		networks, err := ParseList(text)
		if err != nil {
			t.Fatalf("%q: %v", text, err)
		}
		if !slices.Equal(networks, expected) {
			t.Fatalf("%q read as %v, expected %v", text, networks, expected)
		}
	}
}

// What the gateway would refuse is refused here first, so a deployment finds out at startup.
func TestAListNamesOnlyKnownNetworksAndEachOnce(t *testing.T) {
	for text, says := range map[string]string{
		"mainnet,mainnet":  "twice",
		"devnet,Devnet":    "twice",
		"mainnet-beta":     "mainnet-beta",
		"mainnet,,devnet":  `""`,
		"mainnet,none":     "none",
		"mainnet;devnet":   "mainnet;devnet",
		"solana:mainnet":   "solana:mainnet",
		"mainnet,localnet": "localnet",
	} {
		networks, err := ParseList(text)
		if err == nil {
			t.Fatalf("%q was read as %v", text, networks)
		}
		if !strings.Contains(err.Error(), says) {
			t.Fatalf("%q was refused as %q, which does not say %s", text, err, says)
		}
	}
}

// The wire form is sorted too, so a hand-built list publishes the same document as a parsed one —
// and it drops nothing: a repeated or unknown network is left for the gateway to refuse rather than
// quietly narrowed away.
func TestTheWireFormIsCanonicalAndDropsNothing(t *testing.T) {
	if wire := Wire(nil); wire != nil {
		t.Fatalf("no networks were published as %v", wire)
	}
	wire := Wire([]Network{Testnet, Mainnet, Devnet})
	if !slices.Equal(wire, []serverv1.SolanaNetwork{
		serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET,
		serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET,
		serverv1.SolanaNetwork_SOLANA_NETWORK_TESTNET,
	}) {
		t.Fatalf("published as %v", wire)
	}
	if wire := Wire([]Network{Mainnet, Mainnet, "localnet"}); len(wire) != 3 {
		t.Fatalf("a malformed list was narrowed to %v", wire)
	}
}
