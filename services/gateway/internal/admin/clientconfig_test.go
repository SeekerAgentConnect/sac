package admin_test

import (
	"context"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/admin"
	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

const (
	feeOwner    = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
	usdc        = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
	usdcAccount = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
	wrappedSol  = "So11111111111111111111111111111111111111112"
	solAccount  = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
)

func heldConfig(t *testing.T, one *fixture) *storage.StoredClientConfig {
	t.Helper()
	held, err := one.documents.ClientConfig(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	return held
}

// The client configuration page (SEE-185) is behind the same session and CSRF guard as every other
// page: an anonymous visitor is sent to the login, and a form without the session's token changes
// nothing.
func TestTheClientConfigurationIsTheOperatorsAlone(t *testing.T) {
	one := newFixture(t, openLimiter{})
	page := at + "/client-config"

	response := one.get(page)
	_ = response.Body.Close()
	if response.StatusCode != http.StatusSeeOther || response.Header.Get("Location") != at+"/login" {
		t.Fatalf("an anonymous visitor got %s → %s", response.Status, response.Header.Get("Location"))
	}
	response = one.post(page+"/fee", url.Values{"bps": {"100"}})
	_ = response.Body.Close()
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("an anonymous save answered %s", response.Status)
	}

	one.logIn()
	response = one.post(page+"/rpc", url.Values{"mainnet": {"https://rpc.example.com"}})
	_ = response.Body.Close()
	if response.StatusCode != http.StatusForbidden {
		t.Fatalf("a save without the session's token answered %s", response.Status)
	}
	if held := heldConfig(t, one); held.Document.GetRevision() != 0 {
		t.Fatalf("a refused form saved %v", held.Document)
	}
}

// Saving endpoints and a fee through the page, each half keeping the other; the same form again is
// unchanged and leaves the revision where it was.
func TestTheOperatorSetsEndpointsAndAFeeThroughThePage(t *testing.T) {
	one := newFixture(t, openLimiter{})
	one.logIn()
	page := at + "/client-config"

	response := one.get(page)
	body := read(t, response)
	_ = response.Body.Close()
	if response.StatusCode != http.StatusOK || !strings.Contains(body, "never configured (revision 0)") ||
		!strings.Contains(body, "No service fee is charged.") ||
		!strings.Contains(body, "Everything on this page is public.") {
		t.Fatalf("the unconfigured page answered %s:\n%s", response.Status, body)
	}

	rpc := url.Values{"mainnet": {"https://rpc.example.com/v1"}, "devnet": {" https://api.devnet.solana.com "},
		"testnet": {""}}
	rpc.Set("csrf", one.token(page))
	response = one.post(page+"/rpc", rpc)
	_ = response.Body.Close()
	if response.StatusCode != http.StatusSeeOther || response.Header.Get("Location") != page {
		t.Fatalf("saving endpoints answered %s", response.Status)
	}
	held := heldConfig(t, one)
	if held.Document.GetRevision() != 1 || len(held.Document.GetRpcEndpoints()) != 2 ||
		held.Document.GetRpcEndpoints()[1].GetUrl() != "https://api.devnet.solana.com" {
		t.Fatalf("the store holds %v", held.Document)
	}
	response = one.get(page)
	body = read(t, response)
	_ = response.Body.Close()
	if !strings.Contains(body, "Saved as revision 1.") || !strings.Contains(body, "revision 1") {
		t.Fatalf("the page after saving says:\n%s", body)
	}

	rpc.Set("csrf", one.token(page))
	response = one.post(page+"/rpc", rpc)
	_ = response.Body.Close()
	if heldConfig(t, one).Document.GetRevision() != 1 {
		t.Fatal("the same endpoints again moved the revision")
	}
	response = one.get(page)
	body = read(t, response)
	_ = response.Body.Close()
	if !strings.Contains(body, "Nothing changed") {
		t.Fatalf("an unchanged save was not said to be one:\n%s", body)
	}

	fee := url.Values{"csrf": {one.token(page)}, "bps": {"20"}, "network": {"mainnet"},
		"owner": {feeOwner}, "accounts": {wrappedSol + "=" + solAccount + "\r\n\r\n" + usdc + " = " + usdcAccount}}
	response = one.post(page+"/fee", fee)
	_ = response.Body.Close()
	if response.StatusCode != http.StatusSeeOther {
		t.Fatalf("saving a fee answered %s", response.Status)
	}
	held = heldConfig(t, one)
	saved := held.Document.GetSwapFee()
	if held.Document.GetRevision() != 2 || len(held.Document.GetRpcEndpoints()) != 2 ||
		saved.GetBps() != 20 || saved.GetNetwork() != serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET ||
		saved.GetOwner() != feeOwner || len(saved.GetAccounts()) != 2 ||
		saved.GetAccounts()[0].GetMint() != usdc || saved.GetAccounts()[0].GetTokenAccount() != usdcAccount {
		t.Fatalf("the store holds %v", held.Document)
	}
	response = one.get(page)
	body = read(t, response)
	_ = response.Body.Close()
	if !strings.Contains(body, "20 bps (0.20%)") || !strings.Contains(body, usdc+"="+usdcAccount) {
		t.Fatalf("the page does not show the fee:\n%s", body)
	}
}

// interleaved is a store on which another operator's fee is saved while the page is handling a
// form: after the page's first read of the client configuration, or before its write if it reads
// nothing first. That is the overlap in which a page that saved a copy it had read would write the
// old fee back over the new one.
type interleaved struct {
	admin.Store
	competing func()
}

func (s *interleaved) compete() {
	if s.competing != nil {
		competing := s.competing
		s.competing = nil
		competing()
	}
}

func (s *interleaved) ClientConfig(ctx context.Context) (*storage.StoredClientConfig, error) {
	held, err := s.Store.ClientConfig(ctx)
	s.compete()
	return held, err
}

func (s *interleaved) UpdateClientConfig(
	ctx context.Context,
	change func(*gatewayv1.ClientConfig),
	at time.Time,
) (*storage.StoredClientConfig, bool, error) {
	s.compete()
	return s.Store.UpdateClientConfig(ctx, change, at)
}

// Saving the endpoints while someone else saves the fee keeps both: the endpoint form changes the
// endpoints of what is held when it is written, not of a copy read before the fee was saved.
func TestSavingEndpointsKeepsAFeeSavedMeanwhile(t *testing.T) {
	var store *interleaved
	one := newFixtureOver(t, openLimiter{}, func(documents admin.Store) admin.Store {
		store = &interleaved{Store: documents}
		return store
	})
	one.logIn()
	page := at + "/client-config"

	rpc := url.Values{"csrf": {one.token(page)}, "mainnet": {"https://rpc.example.com"}}
	store.competing = func() {
		if _, _, err := one.documents.UpdateClientConfig(context.Background(), func(config *gatewayv1.ClientConfig) {
			config.SwapFee = &gatewayv1.SwapFee{Bps: 20, Network: serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET,
				Owner: feeOwner, Accounts: []*gatewayv1.SwapFeeAccount{{Mint: usdc, TokenAccount: usdcAccount}}}
		}, time.Now()); err != nil {
			t.Error(err)
		}
	}
	response := one.post(page+"/rpc", rpc)
	_ = response.Body.Close()
	if response.StatusCode != http.StatusSeeOther {
		t.Fatalf("saving endpoints answered %s", response.Status)
	}
	held := heldConfig(t, one).Document
	if held.GetSwapFee().GetBps() != 20 || len(held.GetRpcEndpoints()) != 1 || held.GetRevision() != 2 {
		t.Fatalf("one of the two saves was lost: %v", held)
	}
}

// A form that breaks a rule is refused whole: the page says which rule, keeps what was typed, and
// nothing is saved — no rate is clamped and no recipient dropped.
func TestAnInvalidClientConfigurationIsRefusedWhole(t *testing.T) {
	one := newFixture(t, openLimiter{})
	one.logIn()
	page := at + "/client-config"
	response := one.post(page+"/fee", url.Values{"csrf": {one.token(page)}, "bps": {"20"},
		"network": {"mainnet"}, "owner": {feeOwner}, "accounts": {usdc + "=" + usdcAccount}})
	_ = response.Body.Close()
	if heldConfig(t, one).Document.GetRevision() != 1 {
		t.Fatal("the valid fee was not saved")
	}

	for name, c := range map[string]struct {
		half string
		form url.Values
		says string
	}{
		"a rate above the bound": {"fee", url.Values{"bps": {"101"}, "network": {"mainnet"},
			"owner": {feeOwner}, "accounts": {usdc + "=" + usdcAccount}}, "whole basis points from 0 to 100"},
		"a rate that is not whole": {"fee", url.Values{"bps": {"2.5"}, "network": {"mainnet"},
			"owner": {feeOwner}, "accounts": {usdc + "=" + usdcAccount}}, "whole basis points from 0 to 100"},
		"an account that is the owner": {"fee", url.Values{"bps": {"20"}, "network": {"mainnet"},
			"owner": {feeOwner}, "accounts": {usdc + "=" + feeOwner}}, "the owner wallet itself"},
		"an entry that is not a pair": {"fee", url.Values{"bps": {"20"}, "network": {"mainnet"},
			"owner": {feeOwner}, "accounts": {usdc}}, "is not mint=token_account"},
		"a URL with a password": {"rpc", url.Values{"mainnet": {"https://user:key@rpc.example.com"}},
			"may not carry a user name or password"},
		"plain http to a host": {"rpc", url.Values{"devnet": {"http://rpc.example.com"}},
			"must be https"},
	} {
		t.Run(name, func(t *testing.T) {
			form := c.form
			form.Set("csrf", one.token(page))
			response := one.post(page+"/"+c.half, form)
			body := read(t, response)
			_ = response.Body.Close()
			if response.StatusCode != http.StatusBadRequest || !strings.Contains(body, c.says) ||
				!strings.Contains(body, `class="flash problem"`) {
				t.Fatalf("answered %s:\n%s", response.Status, body)
			}
			// What was typed is still in the form, so one mistake does not cost the rest.
			for _, typed := range form {
				if value := typed[0]; value != form.Get("csrf") && !strings.Contains(body, value) {
					t.Fatalf("the refusal lost %q", value)
				}
			}
			held := heldConfig(t, one)
			if held.Document.GetRevision() != 1 || held.Document.GetSwapFee().GetBps() != 20 ||
				len(held.Document.GetRpcEndpoints()) != 0 {
				t.Fatalf("a refused form changed what is held: %v", held.Document)
			}
		})
	}
}
