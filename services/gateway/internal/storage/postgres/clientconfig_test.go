package postgres

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"google.golang.org/protobuf/proto"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// The client configuration (SEE-185) behaves here exactly as in the SQLite store: revision 0 when
// never configured, one more for every real change and none for the same thing again, and an
// invalid configuration refused whole.
func TestTheClientConfigurationIsRevisionedAndRefusedWhole(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	held, err := documents.ClientConfig(ctx)
	if err != nil || !proto.Equal(held.Document, &gatewayv1.ClientConfig{}) {
		t.Fatalf("an unconfigured gateway answered %v (%v)", held, err)
	}
	config := func(url string) *gatewayv1.ClientConfig {
		return &gatewayv1.ClientConfig{RpcEndpoints: []*gatewayv1.RpcEndpoint{{
			Network: serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET, Url: url,
		}}}
	}
	saved, changed, err := documents.SaveClientConfig(ctx, config("https://rpc.example.com"), published)
	if err != nil || !changed || saved.Document.GetRevision() != 1 {
		t.Fatalf("the first save answered %v changed=%v (%v)", saved, changed, err)
	}
	saved, changed, err = documents.SaveClientConfig(ctx, config("https://rpc.example.com"),
		published.Add(time.Hour))
	if err != nil || changed || saved.Document.GetRevision() != 1 {
		t.Fatalf("the same configuration answered %v changed=%v (%v)", saved, changed, err)
	}
	if _, _, err := documents.SaveClientConfig(ctx, config("https://user:key@rpc.example.com"),
		published); !errors.Is(err, storage.ErrInvalidClientConfig) {
		t.Fatalf("a URL with a password answered %v", err)
	}
	saved, changed, err = documents.SaveClientConfig(ctx, config("https://other.example.com"), published)
	if err != nil || !changed || saved.Document.GetRevision() != 2 {
		t.Fatalf("a change answered %v changed=%v (%v)", saved, changed, err)
	}
	held, err = documents.ClientConfig(ctx)
	if err != nil || !proto.Equal(held.Document, saved.Document) || !held.UpdatedAt.Equal(published) {
		t.Fatalf("read back %v (%v)", held, err)
	}
}

// Changes to different halves saved at once — each network's endpoint and the fee — each keep the
// others', because each is applied to what is held under the write lock, not to a copy read
// before it.
func TestOverlappingChangesToTheClientConfigurationKeepEveryOne(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	changes := []func(*gatewayv1.ClientConfig){
		func(config *gatewayv1.ClientConfig) {
			config.SwapFee = &gatewayv1.SwapFee{
				Bps: 20, Network: serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET,
				Owner: "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM",
				Accounts: []*gatewayv1.SwapFeeAccount{{
					Mint:         "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
					TokenAccount: "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
				}},
			}
		},
	}
	for network, url := range map[serverv1.SolanaNetwork]string{
		serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET: "https://rpc.example.com",
		serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET:  "https://api.devnet.solana.com",
		serverv1.SolanaNetwork_SOLANA_NETWORK_TESTNET: "https://api.testnet.solana.com",
	} {
		changes = append(changes, func(config *gatewayv1.ClientConfig) {
			config.RpcEndpoints = append(config.RpcEndpoints, &gatewayv1.RpcEndpoint{Network: network, Url: url})
		})
	}
	failures := make(chan error, len(changes))
	var group sync.WaitGroup
	for _, change := range changes {
		group.Go(func() {
			_, _, err := documents.UpdateClientConfig(ctx, change, published)
			failures <- err
		})
	}
	group.Wait()
	close(failures)
	for err := range failures {
		if err != nil {
			t.Fatal(err)
		}
	}
	held, err := documents.ClientConfig(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if held.Document.GetRevision() != 4 || len(held.Document.GetRpcEndpoints()) != 3 ||
		held.Document.GetSwapFee().GetBps() != 20 {
		t.Fatalf("an overlapping change was lost: %v", held.Document)
	}
}
