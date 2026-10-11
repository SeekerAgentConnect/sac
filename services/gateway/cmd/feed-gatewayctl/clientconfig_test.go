package main

import (
	"context"
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage/sqlite"
)

const (
	feeOwner    = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
	usdc        = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
	usdcAccount = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
)

func configOf(t *testing.T, path string) *gatewayv1.ClientConfig {
	t.Helper()
	documents, err := sqlite.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = documents.Close() }()
	held, err := documents.ClientConfig(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	return held.Document
}

// The CLI does what the admin page does (SEE-185): one network's endpoint or the whole fee at a
// time, through the same rules and the same store method, and the same thing again is unchanged.
func TestTheClientConfigurationFromTheCommandLine(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")

	var shown map[string]any
	if err := json.Unmarshal([]byte(command(t, "client-config", "show", "--database", path)), &shown); err != nil {
		t.Fatalf("show is not JSON: %v", err)
	}
	if shown["revision"] != "0" {
		t.Fatalf("an unconfigured gateway shows %v", shown)
	}

	printed := command(t, "client-config", "set-rpc", "--database", path,
		"--network", "mainnet", "--url", "https://rpc.example.com")
	if !strings.Contains(printed, "saved as revision 1") || !strings.Contains(printed, "public") {
		t.Fatalf("set-rpc said:\n%s", printed)
	}
	if !strings.Contains(command(t, "client-config", "set-rpc", "--database", path,
		"--network", "mainnet", "--url", "https://rpc.example.com"), "unchanged") {
		t.Fatal("the same endpoint again was not unchanged")
	}
	command(t, "client-config", "set-rpc", "--database", path,
		"--network", "devnet", "--url", "https://api.devnet.solana.com")

	printed = command(t, "client-config", "set-fee", "--database", path, "--bps", "20",
		"--network", "mainnet", "--owner", feeOwner, "--account", usdc+"="+usdcAccount)
	if !strings.Contains(printed, "saved as revision 3") || !strings.Contains(printed, "20 bps") {
		t.Fatalf("set-fee said:\n%s", printed)
	}
	held := configOf(t, path)
	if held.GetRevision() != 3 || len(held.GetRpcEndpoints()) != 2 ||
		held.GetSwapFee().GetBps() != 20 || held.GetSwapFee().GetAccounts()[0].GetTokenAccount() != usdcAccount {
		t.Fatalf("the store holds %v", held)
	}

	// Removing one endpoint leaves the other and the fee; --bps 0 alone is no fee at all.
	command(t, "client-config", "set-rpc", "--database", path, "--network", "devnet")
	command(t, "client-config", "set-fee", "--database", path, "--bps", "0")
	held = configOf(t, path)
	if held.GetRevision() != 5 || len(held.GetRpcEndpoints()) != 1 ||
		held.GetRpcEndpoints()[0].GetNetwork() != serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET ||
		held.GetSwapFee() != nil {
		t.Fatalf("the store holds %v", held)
	}

	// Every refusal saves nothing.
	refuses(t, "client-config", "--database", path)
	refuses(t, "client-config", "wipe", "--database", path)
	refuses(t, "client-config", "set-rpc", "--database", path, "--url", "https://rpc.example.com")
	refuses(t, "client-config", "set-rpc", "--database", path, "--network", "mainnet",
		"--url", "https://user:key@rpc.example.com")
	refuses(t, "client-config", "set-fee", "--database", path, "--network", "mainnet")
	refuses(t, "client-config", "set-fee", "--database", path, "--bps", "101", "--network", "mainnet",
		"--owner", feeOwner, "--account", usdc+"="+usdcAccount)
	refuses(t, "client-config", "set-fee", "--database", path, "--bps", "20", "--network", "mainnet",
		"--owner", feeOwner, "--account", usdc)
	refuses(t, "client-config", "set-fee", "--database", path, "--bps", "20", "--network", "mainnet",
		"--owner", feeOwner)
	if held := configOf(t, path); held.GetRevision() != 5 {
		t.Fatalf("a refusal moved the revision to %d", held.GetRevision())
	}
}
