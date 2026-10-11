package postgres

import (
	"context"
	"errors"
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
