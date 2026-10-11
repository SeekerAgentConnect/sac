package rules

import (
	"fmt"
	"net/url"
	"slices"
	"strings"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
)

// The client configuration an operator sets for every app that trusts this gateway (SEE-185,
// FeedService.GetClientConfig): one Solana RPC endpoint per network, and the SAC swap service fee.
//
// It is the one document here that no publisher writes. It comes from the operator — the admin page
// or feed-gatewayctl, both through [ClientConfig] — and it is served to anyone who asks, so the rules
// are about two things: that a phone can act on what it reads without guessing, and that nothing in
// it is a secret. Everything is refused whole rather than repaired: a configuration with one bad
// account is not saved without that account, and a rate above the bound is not saved at the bound,
// because either would put a different fee or a different recipient in front of every owner than
// the one the operator typed.

// MostSwapFeeBps is the highest service fee, in whole basis points (SEE-173's MOST_SWAP_FEE_BPS):
// 1% of a swap's output. The swap program's own field would take up to 255; the app's bound is
// lower, and the gateway holds the app's.
const MostSwapFeeBps = 100

// MaxRPCURLBytes is how long an endpoint URL may be. It is an address with perhaps a public key in
// its path or query, not a document.
const MaxRPCURLBytes = 512

// MostSwapFeeAccounts is how many receiving accounts one fee may list. An operator lists the output
// tokens their users receive most; this bounds what every phone reads and checks before a quote.
const MostSwapFeeAccounts = 16

// ConfigProblem is the one rule a client configuration broke: the field it was about, and what is
// wrong with it, in words an operator reads on the admin page or in a terminal. It is not a
// GatewayProblem, because no caller of the feed or publisher API can ever send one: the operator's
// two surfaces are the only writers, and they show this text to the operator who typed the value.
type ConfigProblem struct {
	Field string
	Why   string
}

func (p *ConfigProblem) Error() string { return p.Field + ": " + p.Why }

func refusal(field, why string, arguments ...any) *ConfigProblem {
	return &ConfigProblem{Field: field, Why: fmt.Sprintf(why, arguments...)}
}

// ClientConfig checks a whole client configuration and returns it in canonical form, or the first
// rule it broke. The revision it carries is ignored and the answer's is zero: a revision is the
// store's count of saved changes, never something an operator chooses.
//
// The canonical form is what makes "save the same thing again" a no-op the store can recognize by
// comparing documents: endpoints ascending by network, fee accounts ascending by mint, and a fee
// that says nothing at all — no rate, no network, no owner, no account — stored as no fee.
func ClientConfig(given *gatewayv1.ClientConfig) (*gatewayv1.ClientConfig, *ConfigProblem) {
	endpoints, problem := rpcEndpoints(given.GetRpcEndpoints())
	if problem != nil {
		return nil, problem
	}
	fee, problem := swapFee(given.GetSwapFee())
	if problem != nil {
		return nil, problem
	}
	return &gatewayv1.ClientConfig{RpcEndpoints: endpoints, SwapFee: fee}, nil
}

// rpcEndpoints checks the endpoints: a known network each, at most one per network, and a URL a
// phone can use and anyone may read.
func rpcEndpoints(given []*gatewayv1.RpcEndpoint) ([]*gatewayv1.RpcEndpoint, *ConfigProblem) {
	endpoints := make([]*gatewayv1.RpcEndpoint, 0, len(given))
	for _, endpoint := range given {
		network := endpoint.GetNetwork()
		if !KnownNetwork(network) {
			return nil, refusal("rpc_endpoints.network", "an endpoint must name mainnet, devnet or testnet")
		}
		if slices.ContainsFunc(endpoints, func(held *gatewayv1.RpcEndpoint) bool {
			return held.GetNetwork() == network
		}) {
			return nil, refusal("rpc_endpoints.network", "%s has more than one endpoint", NetworkName(network))
		}
		if problem := rpcURL(endpoint.GetUrl()); problem != nil {
			problem.Why = NetworkName(network) + ": " + problem.Why
			return nil, problem
		}
		endpoints = append(endpoints, &gatewayv1.RpcEndpoint{Network: network, Url: endpoint.GetUrl()})
	}
	slices.SortFunc(endpoints, func(a, b *gatewayv1.RpcEndpoint) int {
		return int(a.GetNetwork()) - int(b.GetNetwork())
	})
	if len(endpoints) == 0 {
		return nil, nil
	}
	return endpoints, nil
}

// rpcURL is an endpoint URL every phone may be handed:
//
//   - **Absolute HTTPS.** A phone reads balances and accounts through it, and what it reads decides
//     what an owner is shown before they sign. Plain HTTP is allowed only to a loopback host, which
//     is a developer's own machine and an emulator's, never a deployment.
//   - **No user info.** This document is served to anyone who asks, so a URL carrying a user name
//     or password would publish them. An operator whose provider needs a secret puts a proxy of
//     their own in front of it and configures that proxy's URL here.
//   - **No fragment.** A fragment is never sent to the server, so one here is either a mistake or
//     somewhere a secret was hoped to be hidden; neither belongs in a public document.
//
// A path and a query are kept exactly as given, and are as public as everything else in it — an
// operator who puts a provider key there is distributing that key to every client.
func rpcURL(raw string) *ConfigProblem {
	switch {
	case raw == "":
		return refusal("rpc_endpoints.url", "an endpoint needs a URL")
	case len(raw) > MaxRPCURLBytes:
		return refusal("rpc_endpoints.url", "a URL may be at most %d bytes", MaxRPCURLBytes)
	}
	for _, letter := range raw {
		// Whitespace and control characters are never part of an address a client should send to.
		if letter <= ' ' || letter == 0x7f {
			return refusal("rpc_endpoints.url", "a URL may not contain spaces or control characters")
		}
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Opaque != "" || parsed.Host == "" || parsed.Hostname() == "" {
		return refusal("rpc_endpoints.url", "not an absolute URL")
	}
	if parsed.User != nil {
		return refusal("rpc_endpoints.url",
			"a URL may not carry a user name or password: everything here is public")
	}
	if parsed.Fragment != "" || strings.Contains(raw, "#") {
		return refusal("rpc_endpoints.url", "a URL may not have a fragment")
	}
	// Compared with what was written rather than with the parsed scheme, which url.Parse lowercases:
	// the URL is served exactly as stored, so the stored one is the one that has to be right.
	switch {
	case strings.HasPrefix(raw, "https://"):
	case strings.HasPrefix(raw, "http://") && loopback(parsed.Hostname()):
	default:
		return refusal("rpc_endpoints.url",
			"a URL must be https (plain http only to localhost, 127.0.0.1 or [::1])")
	}
	return nil
}

func loopback(host string) bool {
	return host == "localhost" || host == "127.0.0.1" || host == "::1"
}

// swapFee checks the SAC swap service fee by the rules SEE-173's build-time check applied, so a
// configuration that used to fail the build fails here (docs/development/swap-fee-config.md):
//
//   - **A whole rate from 0 to [MostSwapFeeBps].** Never clamped: a rate above the bound is refused,
//     not saved as the bound.
//   - **A nonzero rate needs somewhere to go**: a known network, an owner and at least one account.
//   - **Anything set is checked even at rate 0**, so a half-finished setting is caught when it is
//     typed rather than when the rate is raised.
//   - **Addresses are base58 public keys** (32 bytes), each mint is listed once, and an account is
//     neither the owner wallet itself nor its own mint — either is a recipient that cannot receive
//     the fee, and the swap would fail or the fee would be lost.
//
// The accounts come back ascending by mint, and a fee that says nothing at all comes back as none.
func swapFee(given *gatewayv1.SwapFee) (*gatewayv1.SwapFee, *ConfigProblem) {
	if given == nil {
		return nil, nil
	}
	bps := given.GetBps()
	if bps > MostSwapFeeBps {
		return nil, refusal("swap_fee.bps", "the fee is whole basis points from 0 to %d", MostSwapFeeBps)
	}
	network := given.GetNetwork()
	if network != serverv1.SolanaNetwork_SOLANA_NETWORK_UNSPECIFIED && !KnownNetwork(network) {
		return nil, refusal("swap_fee.network", "the network must be mainnet, devnet or testnet")
	}
	owner := given.GetOwner()
	if owner != "" && !IsAddress(owner) {
		return nil, refusal("swap_fee.owner", "the owner is not a base58 public key")
	}
	if len(given.GetAccounts()) > MostSwapFeeAccounts {
		return nil, refusal("swap_fee.accounts", "at most %d accounts may be listed", MostSwapFeeAccounts)
	}
	accounts := make([]*gatewayv1.SwapFeeAccount, 0, len(given.GetAccounts()))
	for _, account := range given.GetAccounts() {
		mint, receiving := account.GetMint(), account.GetTokenAccount()
		switch {
		case !IsAddress(mint):
			return nil, refusal("swap_fee.accounts", "the mint %q is not a base58 public key", mint)
		case !IsAddress(receiving):
			return nil, refusal("swap_fee.accounts",
				"the account for %s is not a base58 public key", mint)
		case receiving == mint:
			return nil, refusal("swap_fee.accounts", "the account for %s is the mint itself", mint)
		case owner != "" && receiving == owner:
			return nil, refusal("swap_fee.accounts",
				"the account for %s is the owner wallet itself, not a token account", mint)
		case slices.ContainsFunc(accounts, func(held *gatewayv1.SwapFeeAccount) bool {
			return held.GetMint() == mint
		}):
			return nil, refusal("swap_fee.accounts", "the mint %s is listed twice", mint)
		}
		accounts = append(accounts, &gatewayv1.SwapFeeAccount{Mint: mint, TokenAccount: receiving})
	}
	slices.SortFunc(accounts, func(a, b *gatewayv1.SwapFeeAccount) int {
		return strings.Compare(a.GetMint(), b.GetMint())
	})
	if bps > 0 {
		switch {
		case !KnownNetwork(network):
			return nil, refusal("swap_fee.network", "a fee needs the network its accounts are on")
		case owner == "":
			return nil, refusal("swap_fee.owner", "a fee needs the owner of its accounts")
		case len(accounts) == 0:
			return nil, refusal("swap_fee.accounts", "a fee needs at least one receiving account")
		}
	}
	if bps == 0 && network == serverv1.SolanaNetwork_SOLANA_NETWORK_UNSPECIFIED && owner == "" &&
		len(accounts) == 0 {
		return nil, nil
	}
	if len(accounts) == 0 {
		accounts = nil
	}
	return &gatewayv1.SwapFee{Bps: bps, Network: network, Owner: owner, Accounts: accounts}, nil
}

// NetworkName is a network as an operator writes it: "mainnet", "devnet", "testnet", or
// "unspecified" for anything else.
func NetworkName(network serverv1.SolanaNetwork) string {
	switch network {
	case serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET:
		return "mainnet"
	case serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET:
		return "devnet"
	case serverv1.SolanaNetwork_SOLANA_NETWORK_TESTNET:
		return "testnet"
	}
	return "unspecified"
}

// NetworkNamed is [NetworkName] read backwards, for the operator's two surfaces. Anything that is not
// one of the three names is SOLANA_NETWORK_UNSPECIFIED.
func NetworkNamed(name string) serverv1.SolanaNetwork {
	switch strings.ToLower(strings.TrimSpace(name)) {
	case "mainnet":
		return serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET
	case "devnet":
		return serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET
	case "testnet":
		return serverv1.SolanaNetwork_SOLANA_NETWORK_TESTNET
	}
	return serverv1.SolanaNetwork_SOLANA_NETWORK_UNSPECIFIED
}

// IsAddress is whether a string is a Solana public key as the phone reads one: base58 (Bitcoin's
// alphabet) that decodes to exactly 32 bytes. A 32-byte key is at most 44 characters, so anything
// longer is refused before any arithmetic.
func IsAddress(value string) bool {
	if len(value) < 32 || len(value) > 44 {
		return false
	}
	decoded, ok := base58(value)
	return ok && len(decoded) == 32
}

const base58Alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

// base58 decodes Bitcoin-alphabet base58: each leading '1' is a leading zero byte, and the rest is a
// big-endian number in base 58. It is the textbook quadratic algorithm, which is all a string of at
// most 44 characters needs.
func base58(value string) ([]byte, bool) {
	zeros := 0
	for zeros < len(value) && value[zeros] == '1' {
		zeros++
	}
	// The number, little-endian, one byte per element, multiplied by 58 and added to digit by digit.
	var number []byte
	for index := zeros; index < len(value); index++ {
		digit := strings.IndexByte(base58Alphabet, value[index])
		if digit < 0 {
			return nil, false
		}
		carry := digit
		for at := range number {
			carry += int(number[at]) * 58
			number[at] = byte(carry)
			carry >>= 8
		}
		for carry > 0 {
			number = append(number, byte(carry))
			carry >>= 8
		}
	}
	decoded := make([]byte, zeros, zeros+len(number))
	for at := len(number) - 1; at >= 0; at-- {
		decoded = append(decoded, number[at])
	}
	return decoded, true
}
