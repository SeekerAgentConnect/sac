package rules

import (
	"strings"
	"testing"

	"google.golang.org/protobuf/proto"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
)

// Public keys to configure with. Two are the mints an operator lists most (USDC and wrapped SOL);
// the others stand in for the fee wallet and its token accounts, and are only ever read here.
const (
	usdc        = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
	wrappedSol  = "So11111111111111111111111111111111111111112"
	feeOwner    = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
	usdcAccount = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
	solAccount  = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
)

var (
	mainnet = serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET
	devnet  = serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET
	testnet = serverv1.SolanaNetwork_SOLANA_NETWORK_TESTNET
)

// A configuration as an operator fills one in: written out of order on purpose, because what is
// stored is canonical whatever order it was typed in.
func clientConfig(change ...func(*gatewayv1.ClientConfig)) *gatewayv1.ClientConfig {
	message := &gatewayv1.ClientConfig{
		Revision: 7,
		RpcEndpoints: []*gatewayv1.RpcEndpoint{
			{Network: devnet, Url: "https://api.devnet.solana.com"},
			{Network: mainnet, Url: "https://rpc.example.com/v1/public-key?cluster=mainnet"},
		},
		SwapFee: &gatewayv1.SwapFee{
			Bps: 20, Network: mainnet, Owner: feeOwner,
			Accounts: []*gatewayv1.SwapFeeAccount{
				{Mint: wrappedSol, TokenAccount: solAccount},
				{Mint: usdc, TokenAccount: usdcAccount},
			},
		},
	}
	for _, apply := range change {
		apply(message)
	}
	return message
}

func TestTheAddressesAreAddresses(t *testing.T) {
	for _, address := range []string{usdc, wrappedSol, feeOwner, usdcAccount, solAccount,
		"11111111111111111111111111111111"} {
		if !IsAddress(address) {
			t.Fatalf("%s is not read as a public key", address)
		}
	}
	for _, address := range []string{"", "So1111", usdc + "1", "0" + usdc[1:], "I" + usdc[1:],
		strings.Repeat("z", 44)} {
		if IsAddress(address) {
			t.Fatalf("%q is read as a public key", address)
		}
	}
}

// What is stored is the canonical form: endpoints by network, accounts by mint, no revision — the
// store counts revisions, an operator never chooses one.
func TestAClientConfigIsStoredInCanonicalForm(t *testing.T) {
	stored, problem := ClientConfig(clientConfig())
	if problem != nil {
		t.Fatalf("a valid configuration was refused: %v", problem)
	}
	expected := &gatewayv1.ClientConfig{
		RpcEndpoints: []*gatewayv1.RpcEndpoint{
			{Network: mainnet, Url: "https://rpc.example.com/v1/public-key?cluster=mainnet"},
			{Network: devnet, Url: "https://api.devnet.solana.com"},
		},
		SwapFee: &gatewayv1.SwapFee{
			Bps: 20, Network: mainnet, Owner: feeOwner,
			Accounts: []*gatewayv1.SwapFeeAccount{
				{Mint: usdc, TokenAccount: usdcAccount},
				{Mint: wrappedSol, TokenAccount: solAccount},
			},
		},
	}
	if !proto.Equal(stored, expected) {
		t.Fatalf("stored as\n%v\nexpected\n%v", stored, expected)
	}
}

// Nothing configured, and a fee that says nothing at all, are the same document: no endpoints and
// no fee. A fee at rate 0 that names a recipient is kept, so an operator can stage one.
func TestAnEmptyConfigIsNoEndpointsAndNoFee(t *testing.T) {
	for _, given := range []*gatewayv1.ClientConfig{
		nil, {}, {SwapFee: &gatewayv1.SwapFee{}},
	} {
		stored, problem := ClientConfig(given)
		if problem != nil || !proto.Equal(stored, &gatewayv1.ClientConfig{}) {
			t.Fatalf("%v became %v (%v)", given, stored, problem)
		}
	}
	staged, problem := ClientConfig(clientConfig(func(c *gatewayv1.ClientConfig) {
		c.SwapFee.Bps = 0
	}))
	if problem != nil || staged.GetSwapFee().GetOwner() != feeOwner || len(staged.GetSwapFee().GetAccounts()) != 2 {
		t.Fatalf("a staged fee became %v (%v)", staged.GetSwapFee(), problem)
	}
}

func TestAnRPCEndpointIsPublicAndUsable(t *testing.T) {
	for _, accepted := range []string{
		"https://api.mainnet-beta.solana.com",
		"https://rpc.example.com:8443/path?api-key=public",
		"http://localhost:8899",
		"http://127.0.0.1:8899/",
		"http://[::1]:8899",
	} {
		if _, problem := ClientConfig(&gatewayv1.ClientConfig{RpcEndpoints: []*gatewayv1.RpcEndpoint{
			{Network: mainnet, Url: accepted},
		}}); problem != nil {
			t.Fatalf("%s was refused: %v", accepted, problem)
		}
	}
	for name, refused := range map[string]string{
		"empty":                    "",
		"relative":                 "/rpc",
		"no host":                  "https://",
		"opaque":                   "https:rpc.example.com",
		"plain http to a host":     "http://rpc.example.com",
		"another scheme":           "wss://rpc.example.com",
		"an upper-case scheme":     "HTTPS://rpc.example.com",
		"a user name and password": "https://user:secret@rpc.example.com",
		"a user name alone":        "https://key@rpc.example.com",
		"a fragment":               "https://rpc.example.com/#key",
		"an empty fragment":        "https://rpc.example.com/#",
		"a space":                  "https://rpc.example.com/a b",
		"too long":                 "https://rpc.example.com/" + strings.Repeat("a", MaxRPCURLBytes),
	} {
		t.Run(name, func(t *testing.T) {
			stored, problem := ClientConfig(&gatewayv1.ClientConfig{RpcEndpoints: []*gatewayv1.RpcEndpoint{
				{Network: mainnet, Url: "https://api.mainnet-beta.solana.com"},
				{Network: devnet, Url: refused},
			}})
			if stored != nil || problem == nil || problem.Field != "rpc_endpoints.url" {
				t.Fatalf("stored=%v problem=%v", stored, problem)
			}
		})
	}
	for name, endpoints := range map[string][]*gatewayv1.RpcEndpoint{
		"an unspecified network": {{Url: "https://rpc.example.com"}},
		"an unknown network":     {{Network: serverv1.SolanaNetwork(4), Url: "https://rpc.example.com"}},
		"two for one network": {
			{Network: testnet, Url: "https://one.example.com"},
			{Network: testnet, Url: "https://two.example.com"},
		},
	} {
		t.Run(name, func(t *testing.T) {
			stored, problem := ClientConfig(&gatewayv1.ClientConfig{RpcEndpoints: endpoints})
			if stored != nil || problem == nil || problem.Field != "rpc_endpoints.network" {
				t.Fatalf("stored=%v problem=%v", stored, problem)
			}
		})
	}
}

// The fee's rules are SEE-173's build-time check, and a refusal is of the whole configuration: no
// rate is clamped and no account is dropped to make the rest fit.
func TestASwapFeeIsRefusedWholeRatherThanRepaired(t *testing.T) {
	for _, one := range []struct {
		name   string
		change func(*gatewayv1.SwapFee)
		field  string
	}{
		{"a rate above the bound", func(f *gatewayv1.SwapFee) { f.Bps = MostSwapFeeBps + 1 }, "swap_fee.bps"},
		{"a rate with no network", func(f *gatewayv1.SwapFee) {
			f.Network = serverv1.SolanaNetwork_SOLANA_NETWORK_UNSPECIFIED
		}, "swap_fee.network"},
		{"an unknown network, even at rate 0", func(f *gatewayv1.SwapFee) {
			f.Bps, f.Network = 0, serverv1.SolanaNetwork(4)
		}, "swap_fee.network"},
		{"a rate with no owner", func(f *gatewayv1.SwapFee) { f.Owner = "" }, "swap_fee.owner"},
		{"an owner that is not an address, even at rate 0", func(f *gatewayv1.SwapFee) {
			f.Bps, f.Owner = 0, "not-an-address"
		}, "swap_fee.owner"},
		{"a rate with no account", func(f *gatewayv1.SwapFee) { f.Accounts = nil }, "swap_fee.accounts"},
		{"a mint that is not an address, even at rate 0", func(f *gatewayv1.SwapFee) {
			f.Bps = 0
			f.Accounts[0].Mint = "So111"
		}, "swap_fee.accounts"},
		{"an account that is not an address", func(f *gatewayv1.SwapFee) {
			f.Accounts[1].TokenAccount = usdcAccount + "x"
		}, "swap_fee.accounts"},
		{"a mint listed twice", func(f *gatewayv1.SwapFee) {
			f.Accounts[1].Mint = wrappedSol
		}, "swap_fee.accounts"},
		{"an account that is its own mint", func(f *gatewayv1.SwapFee) {
			f.Accounts[1].TokenAccount = usdc
		}, "swap_fee.accounts"},
		{"an account that is the owner wallet", func(f *gatewayv1.SwapFee) {
			f.Accounts[0].TokenAccount = feeOwner
		}, "swap_fee.accounts"},
		{"more accounts than one fee may list", func(f *gatewayv1.SwapFee) {
			for len(f.Accounts) <= MostSwapFeeAccounts {
				f.Accounts = append(f.Accounts, f.Accounts[0])
			}
		}, "swap_fee.accounts"},
	} {
		t.Run(one.name, func(t *testing.T) {
			stored, problem := ClientConfig(clientConfig(func(c *gatewayv1.ClientConfig) {
				one.change(c.SwapFee)
			}))
			if stored != nil || problem == nil || problem.Field != one.field {
				t.Fatalf("stored=%v problem=%v, expected a refusal of %s", stored, problem, one.field)
			}
		})
	}
}
