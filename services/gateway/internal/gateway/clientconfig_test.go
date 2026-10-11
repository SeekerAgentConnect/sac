package gateway_test

import (
	"context"
	"testing"

	"connectrpc.com/connect"
	"google.golang.org/protobuf/proto"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
)

// The client configuration every app reads (SEE-185), over the read listener with no credential:
// revision 0, no endpoints and no fee until the operator sets one, then exactly what the operator
// saved — the same for every caller, and never cached by anything in between.
func TestEveryAppReadsTheOperatorsClientConfiguration(t *testing.T) {
	gateway := newGateway(t)
	ctx := context.Background()
	read := func() *connect.Response[gatewayv1.GetClientConfigResponse] {
		t.Helper()
		answer, err := gateway.feed.GetClientConfig(ctx, connect.NewRequest(&gatewayv1.GetClientConfigRequest{}))
		if err != nil {
			t.Fatal(err)
		}
		return answer
	}

	unconfigured := read()
	if unconfigured.Msg.GetConfig() == nil ||
		!proto.Equal(unconfigured.Msg.GetConfig(), &gatewayv1.ClientConfig{}) {
		t.Fatalf("an unconfigured gateway answered %v", unconfigured.Msg)
	}
	if fee := unconfigured.Msg.GetConfig().GetSwapFee(); fee.GetBps() != 0 {
		t.Fatalf("an unconfigured gateway charges %d bps", fee.GetBps())
	}
	if cache := unconfigured.Header().Get("Cache-Control"); cache != "no-store" {
		t.Fatalf("the answer may be cached: %q", cache)
	}

	saved, changed, err := gateway.documents.SaveClientConfig(ctx, &gatewayv1.ClientConfig{
		RpcEndpoints: []*gatewayv1.RpcEndpoint{
			{Network: serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET, Url: "https://api.devnet.solana.com"},
			{Network: serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET, Url: "https://rpc.example.com/public"},
		},
		SwapFee: &gatewayv1.SwapFee{
			Bps: 20, Network: serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET,
			Owner: "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM",
			Accounts: []*gatewayv1.SwapFeeAccount{{
				Mint:         "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
				TokenAccount: "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
			}},
		},
	}, published)
	if err != nil || !changed {
		t.Fatalf("the operator's save answered changed=%v (%v)", changed, err)
	}
	configured := read().Msg.GetConfig()
	if !proto.Equal(configured, saved.Document) || configured.GetRevision() != 1 {
		t.Fatalf("every app reads %v, the operator saved %v", configured, saved.Document)
	}
	// Ascending by network, whatever order the operator gave.
	if endpoints := configured.GetRpcEndpoints(); len(endpoints) != 2 ||
		endpoints[0].GetNetwork() != serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET {
		t.Fatalf("the endpoints are served as %v", endpoints)
	}
}
