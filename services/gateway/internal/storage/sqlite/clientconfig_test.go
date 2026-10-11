package sqlite

import (
	"context"
	"database/sql"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"google.golang.org/protobuf/proto"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// A configuration as the operator saves one (SEE-185), with its accounts out of order: the store
// keeps the canonical form, so what is read back is sorted.
func clientConfig(bps uint32) *gatewayv1.ClientConfig {
	return &gatewayv1.ClientConfig{
		RpcEndpoints: []*gatewayv1.RpcEndpoint{{
			Network: serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET, Url: "https://rpc.example.com",
		}},
		SwapFee: &gatewayv1.SwapFee{
			Bps: bps, Network: serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET,
			Owner: "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM",
			Accounts: []*gatewayv1.SwapFeeAccount{
				{Mint: "So11111111111111111111111111111111111111112", TokenAccount: "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"},
				{Mint: "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", TokenAccount: "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"},
			},
		},
	}
}

func readConfig(t *testing.T, documents *Store) *storage.StoredClientConfig {
	t.Helper()
	held, err := documents.ClientConfig(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	return held
}

// Never configured is revision 0 with nothing in it, which is an answer rather than an absence —
// and saving that same nothing is not a change.
func TestAGatewayThatWasNeverConfiguredAnswersRevisionZero(t *testing.T) {
	documents := openStore(t)
	held := readConfig(t, documents)
	if !proto.Equal(held.Document, &gatewayv1.ClientConfig{}) || !held.UpdatedAt.IsZero() {
		t.Fatalf("an unconfigured gateway answered %v at %v", held.Document, held.UpdatedAt)
	}
	saved, changed, err := documents.SaveClientConfig(context.Background(),
		&gatewayv1.ClientConfig{SwapFee: &gatewayv1.SwapFee{}}, published)
	if err != nil || changed || saved.Document.GetRevision() != 0 {
		t.Fatalf("saving nothing answered %v changed=%v (%v)", saved, changed, err)
	}
}

// Every real change moves the revision by exactly one; the same configuration again — in any
// order — moves nothing, and what is read back is what was saved, canonically.
func TestTheRevisionMovesOnlyWhenTheConfigurationDoes(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	saved, changed, err := documents.SaveClientConfig(ctx, clientConfig(20), published)
	if err != nil || !changed || saved.Document.GetRevision() != 1 {
		t.Fatalf("the first save answered %v changed=%v (%v)", saved, changed, err)
	}
	held := readConfig(t, documents)
	if !proto.Equal(held.Document, saved.Document) || !held.UpdatedAt.Equal(published) {
		t.Fatalf("read back %v at %v, saved %v", held.Document, held.UpdatedAt, saved.Document)
	}
	if accounts := held.Document.GetSwapFee().GetAccounts(); accounts[0].GetMint() >= accounts[1].GetMint() {
		t.Fatalf("the accounts were stored out of order: %v", accounts)
	}

	// The same thing again, with a revision the caller made up: unchanged, still revision 1.
	again := clientConfig(20)
	again.Revision = 41
	saved, changed, err = documents.SaveClientConfig(ctx, again, published.Add(time.Hour))
	if err != nil || changed || saved.Document.GetRevision() != 1 {
		t.Fatalf("the same configuration answered %v changed=%v (%v)", saved, changed, err)
	}
	if !readConfig(t, documents).UpdatedAt.Equal(published) {
		t.Fatal("an unchanged save rewrote the row")
	}

	for revision, bps := range []uint32{30, 0} {
		saved, changed, err = documents.SaveClientConfig(ctx, clientConfig(bps), published)
		if err != nil || !changed || saved.Document.GetRevision() != uint64(revision+2) {
			t.Fatalf("a change to %d bps answered %v changed=%v (%v)", bps, saved, changed, err)
		}
	}
}

// An invalid configuration is refused whole by the store itself, whatever surface sent it: nothing
// is written, and the revision every client reads does not move.
func TestAnInvalidConfigurationIsNeverSaved(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	if _, _, err := documents.SaveClientConfig(ctx, clientConfig(20), published); err != nil {
		t.Fatal(err)
	}
	for name, invalid := range map[string]*gatewayv1.ClientConfig{
		"a rate above the bound": clientConfig(101),
		"a URL with a password": {RpcEndpoints: []*gatewayv1.RpcEndpoint{{
			Network: serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET, Url: "https://user:key@rpc.example.com",
		}}},
	} {
		saved, changed, err := documents.SaveClientConfig(ctx, invalid, published)
		if !errors.Is(err, storage.ErrInvalidClientConfig) || saved != nil || changed {
			t.Fatalf("%s answered %v changed=%v (%v)", name, saved, changed, err)
		}
	}
	if held := readConfig(t, documents); held.Document.GetRevision() != 1 ||
		held.Document.GetSwapFee().GetBps() != 20 {
		t.Fatalf("a refused configuration changed what is held: %v", held.Document)
	}
}

// The configuration is the file's, not the process's: a restart serves the same revision.
func TestTheConfigurationSurvivesARestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")
	documents, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	saved, _, err := documents.SaveClientConfig(context.Background(), clientConfig(20), published)
	if err != nil {
		t.Fatal(err)
	}
	if err := documents.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = reopened.Close() })
	if held := readConfig(t, reopened); !proto.Equal(held.Document, saved.Document) {
		t.Fatalf("after a restart the gateway serves %v, saved %v", held.Document, saved.Document)
	}
}

// Version 9 retires the environments field a manifest stored before SEE-185 still carries, so the
// gateway never serves it and a publisher's identical republication is a retry rather than a
// conflict. Nothing else in the manifest moves.
func TestVersionNineRetiresTheEnvironmentsStoredManifestsCarry(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")
	older, err := sql.Open("sqlite", "file:"+path+"?_pragma=foreign_keys(1)")
	if err != nil {
		t.Fatal(err)
	}
	for _, statement := range []string{
		schemaV1, schemaV2, schemaV3, schemaV4, schemaV5, schemaV6, schemaV7, schemaV8,
		`PRAGMA user_version = 8`,
	} {
		if _, err := older.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := older.Exec(
		`INSERT INTO publisher (server_id, label, created_at_ms) VALUES (?, ?, ?)`,
		publisher, "from an older gateway", milliseconds(published)); err != nil {
		t.Fatal(err)
	}
	current := manifest(publisher, 1)
	// ServerManifest field 6, SERVER_ENVIRONMENT_PRODUCTION, as a gateway before SEE-185 stored it.
	stored := append(mustMarshal(t, current), 0x32, 0x01, 0x01)
	if _, err := older.Exec(
		`INSERT INTO manifest (server_id, settings_revision, document, updated_at_ms)
		 VALUES (?, 1, ?, ?)`, publisher, stored, milliseconds(published)); err != nil {
		t.Fatal(err)
	}
	if err := older.Close(); err != nil {
		t.Fatal(err)
	}

	documents, err := Open(path)
	if err != nil {
		t.Fatalf("a version 8 database could not be opened: %v", err)
	}
	t.Cleanup(func() { _ = documents.Close() })
	held, err := documents.Manifest(context.Background(), publisher)
	if err != nil || held == nil {
		t.Fatalf("the manifest is gone: %v", err)
	}
	if len(held.Document.ProtoReflect().GetUnknown()) != 0 {
		t.Fatal("the retired environments field is still stored")
	}
	if !proto.Equal(held.Document, current) {
		t.Fatalf("the migration changed the manifest:\n%v", held.Document)
	}
	if configured := readConfig(t, documents); configured.Document.GetRevision() != 0 {
		t.Fatalf("an upgrade configured %v", configured.Document)
	}
}
