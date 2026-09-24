package gateway

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"connectrpc.com/connect"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage/sqlite"
)

var errCommitRefused = errors.New("commit refused")

// commitFailure runs the business callback against a real SQLite transaction, then fails before
// that transaction may commit. It models the important half of a commit failure: the handler has
// made every decision and asked for every write, but none of it is durable.
type commitFailure struct{ *sqlite.Store }

func (s commitFailure) Write(
	ctx context.Context,
	apply func(storage.PublicationTx) error,
) error {
	return s.Store.Write(ctx, func(tx storage.PublicationTx) error {
		if err := apply(tx); err != nil {
			return err
		}
		return errCommitRefused
	})
}

func TestAPublicationDoesNotSucceedOrWakeWhenTheCommitFails(t *testing.T) {
	const (
		serverID   = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
		gatewayURL = "https://feeds.example.com"
	)
	documents, err := sqlite.Open(filepath.Join(t.TempDir(), "broadcast.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = documents.Close() })

	wakes := 0
	publisher := NewPublisher(commitFailure{documents}, gatewayURL, 200, 30*time.Second, time.Now,
		func() { wakes++ })
	ctx := context.WithValue(context.Background(), publisherKey{}, serverID)
	_, err = publisher.PublishManifest(ctx, connect.NewRequest(&gatewayv1.PublishManifestRequest{
		Manifest: &serverv1.ServerManifest{
			ServerId:         serverID,
			ProtocolVersion:  rules.Protocol,
			SettingsRevision: 1,
			Mode:             serverv1.ConnectionMode_CONNECTION_MODE_GATEWAY_FEED,
			Environments: []serverv1.ServerEnvironment{
				serverv1.ServerEnvironment_SERVER_ENVIRONMENT_PRODUCTION,
			},
			DisplayName: "Commit failure fixture",
			Reference: &serverv1.ServerManifest_Feed{Feed: &serverv1.GatewayFeed{
				GatewayUrl: gatewayURL,
				Channel:    rules.ChannelFor(serverID),
			}},
		},
	}))
	if connect.CodeOf(err) != connect.CodeInternal {
		t.Fatalf("a failed commit answered %v", err)
	}
	if wakes != 0 {
		t.Fatalf("a failed commit woke fan-out %d times", wakes)
	}
	held, readErr := documents.Manifest(context.Background(), serverID)
	if readErr != nil {
		t.Fatal(readErr)
	}
	if held != nil {
		t.Fatal("a failed commit left a manifest behind")
	}
	if pending, pendingErr := documents.Pending(context.Background()); pendingErr != nil || pending != 0 {
		t.Fatalf("a failed commit left %d notices: %v", pending, pendingErr)
	}
}
