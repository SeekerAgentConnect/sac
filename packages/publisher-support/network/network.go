// Package network is the Solana networks a deployment's wallet operations are configured for
// (SEE-174, docs/wiki/server-manifests.md#supported-networks).
//
// It is what the server actually runs against, never every network the protocol can name. A
// template that swaps through Jupiter says Mainnet, because Jupiter has no other network to point
// it at; a publisher whose proposals never reach a wallet says nothing at all rather than claim a
// network it does not use. The phone offers only wallet profiles on one of these networks when a
// connection is set up, and refuses to sign for a connection whose network the manifest does not
// list — so a network named here that the template does not run is an owner signing a transaction
// that cannot land, and a network missing from here is one no owner can use.
//
// It is a different question from the environment (packages/publisher-support/environment).
// Production is not Mainnet and sandbox is not Devnet: a sandbox deployment simulates against
// Mainnet data, and a production one could execute on Devnet. The two are configured, published
// and validated separately so that neither can be read as the other.
//
// Like the environment it is one type in one package, because the configuration that reads it,
// the manifest that publishes it and the API that answers it must spell each network the same way,
// and like the environment a value that did not come from [Parse] has no [Network.Wire] value: it
// is published as unspecified, which the gateway refuses (GATEWAY_PROBLEM_BAD_NETWORK), rather
// than as Mainnet.
package network

import (
	"fmt"
	"slices"
	"strings"

	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/server/v1"
)

// Network is one Solana cluster, spelled as the configuration and every answer spell it.
type Network string

const (
	// Mainnet is Mobile Wallet Adapter chain solana:mainnet, where real money is.
	Mainnet Network = "mainnet"
	// Devnet is solana:devnet.
	Devnet Network = "devnet"
	// Testnet is solana:testnet.
	Testnet Network = "testnet"
)

// None is how a configuration says, on purpose, that this deployment declares no network: its
// proposals never reach a wallet. It is a word rather than an empty value because an empty
// variable is indistinguishable from one nobody set.
const None = "none"

// Parse reads one network's name, and says whether it is one of the three.
func Parse(text string) (Network, bool) {
	switch Network(text) {
	case Mainnet:
		return Mainnet, true
	case Devnet:
		return Devnet, true
	case Testnet:
		return Testnet, true
	default:
		return "", false
	}
}

// ParseList reads a comma-separated list of network names — `mainnet`, `mainnet,devnet` — or the
// word [None], and returns the networks in canonical order.
//
// It refuses what the gateway would refuse, so a deployment finds out at startup rather than from
// a manifest that never publishes: a name that is not one of the three, and a name given twice. It
// does not refuse an order, because the list is a set; what comes back is sorted the way the
// manifest publishes it (see [Canonical]), so that two deployments configured with the same
// networks in different orders publish the same document and fingerprint the same settings.
func ParseList(text string) ([]Network, error) {
	text = strings.ToLower(strings.TrimSpace(text))
	if text == None {
		return nil, nil
	}
	var networks []Network
	for _, word := range strings.Split(text, ",") {
		word = strings.TrimSpace(word)
		named, ok := Parse(word)
		if !ok {
			return nil, fmt.Errorf("names %q, which is not mainnet, devnet or testnet", word)
		}
		if slices.Contains(networks, named) {
			return nil, fmt.Errorf("names %s twice", named)
		}
		networks = append(networks, named)
	}
	return Canonical(networks), nil
}

// Canonical is a copy of these networks in the manifest's order: ascending by wire value, which is
// Mainnet, Devnet, Testnet. It is not alphabetical on purpose — the order is the protocol's, and
// every runtime that writes a manifest writes the same one.
func Canonical(networks []Network) []Network {
	sorted := slices.Clone(networks)
	slices.SortStableFunc(sorted, func(a, b Network) int { return int(a.Wire()) - int(b.Wire()) })
	return sorted
}

// Wire is the manifest's own value for this network (`ServerManifest.supported_networks`).
//
// Anything that is not one of the three is `UNSPECIFIED`, which the gateway refuses and no phone
// would sign for. That is the fail-closed direction: a value nobody validated must not become the
// network with the money on it.
func (n Network) Wire() serverv1.SolanaNetwork {
	switch n {
	case Mainnet:
		return serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET
	case Devnet:
		return serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET
	case Testnet:
		return serverv1.SolanaNetwork_SOLANA_NETWORK_TESTNET
	default:
		return serverv1.SolanaNetwork_SOLANA_NETWORK_UNSPECIFIED
	}
}

// String is the name itself, for a log line and a JSON answer.
func (n Network) String() string { return string(n) }

// Wire is these networks as a manifest carries them, in canonical order. Nothing is removed on the
// way: a repeated or unknown network is published as it is and refused by the gateway, because a
// document quietly narrower than its settings is a deployment nobody can tell is misconfigured.
func Wire(networks []Network) []serverv1.SolanaNetwork {
	if len(networks) == 0 {
		return nil
	}
	wire := make([]serverv1.SolanaNetwork, 0, len(networks))
	for _, one := range networks {
		wire = append(wire, one.Wire())
	}
	slices.Sort(wire)
	return wire
}
