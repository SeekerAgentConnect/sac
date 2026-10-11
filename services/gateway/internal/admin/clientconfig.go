package admin

import (
	"context"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"google.golang.org/protobuf/proto"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
)

// The gateway-wide client configuration (SEE-185): the Solana RPC endpoint each network is read
// through, and the SAC swap service fee, for every app whose build names this gateway.
//
// It is feed-gatewayctl client-config on this page. Both read the configuration, change one half of
// it, and save the whole through storage.PublisherAdminStore.SaveClientConfig, which validates it
// with rules.ClientConfig — so the page and the CLI accept and refuse exactly the same things, and
// a refusal saves nothing at all. Saving what is already held is answered as unchanged and does not
// move the revision.
//
// Nothing here is a secret, and the page says so where the values are typed: every client may read
// all of it. An endpoint that needs a credential is not configured here; an operator puts a proxy of
// their own in front of such a provider and gives this page the proxy's URL.

// ClientConfigView is the client configuration page: the revision every client is reading, and the
// two forms, filled with what is held — or, after a refusal, with what was typed, so a mistake in
// one field does not cost the operator the others.
type ClientConfigView struct {
	Common
	Revision  uint64
	UpdatedAt time.Time
	// The three endpoint fields, by network; empty is no endpoint.
	Mainnet string
	Devnet  string
	Testnet string
	// The fee form: the rate, the network's name ("" for none), the owner, and the accounts as one
	// "mint=token_account" per line.
	Bps      string
	Network  string
	Owner    string
	Accounts string
	// FeeSentence says what every app is charging now, in one line.
	FeeSentence string
}

// Networks are the fee form's choices, in the order the select lists them.
func (ClientConfigView) Networks() []string { return []string{"mainnet", "devnet", "testnet"} }

func (s *Server) clientConfigView(ctx context.Context, at *visit) (ClientConfigView, error) {
	common, err := s.commonWithServerCount(ctx, at)
	if err != nil {
		return ClientConfigView{}, err
	}
	common.ClientConfigActive = true
	held, err := s.options.Store.ClientConfig(ctx)
	if err != nil {
		return ClientConfigView{}, err
	}
	config := held.Document
	view := ClientConfigView{
		Common:      common,
		Revision:    config.GetRevision(),
		UpdatedAt:   held.UpdatedAt,
		FeeSentence: FeeSentence(config.GetSwapFee()),
	}
	for _, endpoint := range config.GetRpcEndpoints() {
		switch endpoint.GetNetwork() {
		case serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET:
			view.Mainnet = endpoint.GetUrl()
		case serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET:
			view.Devnet = endpoint.GetUrl()
		case serverv1.SolanaNetwork_SOLANA_NETWORK_TESTNET:
			view.Testnet = endpoint.GetUrl()
		}
	}
	fee := config.GetSwapFee()
	view.Bps = strconv.FormatUint(uint64(fee.GetBps()), 10)
	if rules.KnownNetwork(fee.GetNetwork()) {
		view.Network = rules.NetworkName(fee.GetNetwork())
	}
	view.Owner = fee.GetOwner()
	lines := make([]string, 0, len(fee.GetAccounts()))
	for _, account := range fee.GetAccounts() {
		lines = append(lines, account.GetMint()+"="+account.GetTokenAccount())
	}
	view.Accounts = strings.Join(lines, "\n")
	return view, nil
}

func (s *Server) showClientConfig(writer http.ResponseWriter, request *http.Request, at *visit) {
	view, err := s.clientConfigView(request.Context(), at)
	if err != nil {
		s.failed(writer, "read client configuration", err)
		return
	}
	s.pages.render(writer, http.StatusOK, "clientconfig", view)
}

// saveRPC replaces the three endpoints and keeps the fee as it is held.
func (s *Server) saveRPC(writer http.ResponseWriter, request *http.Request, at *visit) {
	typed := map[serverv1.SolanaNetwork]string{
		serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET: strings.TrimSpace(request.PostFormValue("mainnet")),
		serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET:  strings.TrimSpace(request.PostFormValue("devnet")),
		serverv1.SolanaNetwork_SOLANA_NETWORK_TESTNET: strings.TrimSpace(request.PostFormValue("testnet")),
	}
	s.saveClientConfig(writer, request, at, "rpc", func(config *gatewayv1.ClientConfig) string {
		config.RpcEndpoints = RPCEndpoints(typed)
		return ""
	}, func(view *ClientConfigView) {
		view.Mainnet = typed[serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET]
		view.Devnet = typed[serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET]
		view.Testnet = typed[serverv1.SolanaNetwork_SOLANA_NETWORK_TESTNET]
	})
}

// saveFee replaces the swap fee and keeps the endpoints as they are held.
func (s *Server) saveFee(writer http.ResponseWriter, request *http.Request, at *visit) {
	bps := strings.TrimSpace(request.PostFormValue("bps"))
	network := strings.TrimSpace(request.PostFormValue("network"))
	owner := strings.TrimSpace(request.PostFormValue("owner"))
	accounts := strings.TrimSpace(strings.ReplaceAll(request.PostFormValue("accounts"), "\r\n", "\n"))
	s.saveClientConfig(writer, request, at, "fee", func(config *gatewayv1.ClientConfig) string {
		fee, err := SwapFee(bps, network, owner, strings.Split(accounts, "\n"))
		if err != nil {
			return sentence(err.Error())
		}
		config.SwapFee = fee
		return ""
	}, func(view *ClientConfigView) {
		view.Bps, view.Network, view.Owner, view.Accounts = bps, network, owner, accounts
	})
}

// saveClientConfig is the half both forms share: read what is held, change one half, check the
// whole with the rules, and save it. A refusal re-renders the page with what was typed and the
// rule it broke, and writes nothing.
func (s *Server) saveClientConfig(
	writer http.ResponseWriter,
	request *http.Request,
	at *visit,
	half string,
	change func(*gatewayv1.ClientConfig) string,
	typed func(*ClientConfigView),
) {
	ctx := request.Context()
	held, err := s.options.Store.ClientConfig(ctx)
	if err != nil {
		s.failed(writer, "read client configuration", err)
		return
	}
	next := proto.CloneOf(held.Document)
	problem := change(next)
	if problem == "" {
		if _, refused := rules.ClientConfig(next); refused != nil {
			problem = ConfigProblem(refused)
		}
	}
	if problem != "" {
		s.record("client-config", half, "refused")
		view, err := s.clientConfigView(ctx, at)
		if err != nil {
			s.failed(writer, "read client configuration", err)
			return
		}
		typed(&view)
		view.Problem = problem
		s.pages.render(writer, http.StatusBadRequest, "clientconfig", view)
		return
	}
	saved, changed, err := s.options.Store.SaveClientConfig(ctx, next, s.options.Now())
	// The store checks again with the same rules, so a refusal from it here would be the two
	// disagreeing — a programming error, reported as a failure rather than as the operator's
	// mistake, and with nothing saved either way.
	if err != nil {
		s.record("client-config", half, "failed")
		s.failed(writer, "save client configuration", err)
		return
	}
	revision := saved.Document.GetRevision()
	if changed {
		s.record("client-config", half, fmt.Sprintf("revision %d", revision))
		s.flash(at, fmt.Sprintf("Saved as revision %d. Every app that trusts this gateway reads it "+
			"the next time it asks.", revision))
	} else {
		s.record("client-config", half, "unchanged")
		s.flash(at, fmt.Sprintf("Nothing changed: that is the configuration already held, so it is "+
			"still revision %d.", revision))
	}
	http.Redirect(writer, request, s.options.Path+"/client-config", http.StatusSeeOther)
}

// RPCEndpoints is the endpoints the three typed URLs describe: one per network that has a URL, and
// none for a network left empty. The rules check them; this only reads the form.
func RPCEndpoints(typed map[serverv1.SolanaNetwork]string) []*gatewayv1.RpcEndpoint {
	var endpoints []*gatewayv1.RpcEndpoint
	for _, network := range []serverv1.SolanaNetwork{
		serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET,
		serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET,
		serverv1.SolanaNetwork_SOLANA_NETWORK_TESTNET,
	} {
		if url := typed[network]; url != "" {
			endpoints = append(endpoints, &gatewayv1.RpcEndpoint{Network: network, Url: url})
		}
	}
	return endpoints
}

// SwapFee reads a fee as an operator types it, on this page or to feed-gatewayctl: the rate in
// whole basis points (empty is 0), the network by name (empty is none), the owner, and the accounts
// as "mint=token_account" entries, blank ones ignored. It reads; rules.ClientConfig decides. A rate
// that is not a whole number is refused here rather than rounded, because a rounded rate is a
// different fee from the one typed.
func SwapFee(bps, network, owner string, accounts []string) (*gatewayv1.SwapFee, error) {
	fee := &gatewayv1.SwapFee{Owner: strings.TrimSpace(owner)}
	if written := strings.TrimSpace(bps); written != "" {
		rate, err := strconv.ParseUint(written, 10, 32)
		if err != nil {
			return nil, fmt.Errorf("the fee is whole basis points from 0 to %d", rules.MostSwapFeeBps)
		}
		fee.Bps = uint32(rate)
	}
	if name := strings.TrimSpace(network); name != "" {
		fee.Network = rules.NetworkNamed(name)
		if !rules.KnownNetwork(fee.Network) {
			return nil, fmt.Errorf("the network must be mainnet, devnet or testnet")
		}
	}
	for _, entry := range accounts {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		mint, account, found := strings.Cut(entry, "=")
		if !found {
			return nil, fmt.Errorf("%q is not mint=token_account", entry)
		}
		fee.Accounts = append(fee.Accounts, &gatewayv1.SwapFeeAccount{
			Mint: strings.TrimSpace(mint), TokenAccount: strings.TrimSpace(account),
		})
	}
	return fee, nil
}

// ConfigProblem is a rule's refusal as a sentence for the operator.
func ConfigProblem(problem *rules.ConfigProblem) string { return sentence(problem.Why) }

func sentence(text string) string {
	if text == "" {
		return text
	}
	return strings.ToUpper(text[:1]) + text[1:] + "."
}

// FeeSentence says what a fee charges, in one line, for the page and the CLI.
func FeeSentence(fee *gatewayv1.SwapFee) string {
	if fee.GetBps() == 0 {
		if len(fee.GetAccounts()) > 0 || fee.GetOwner() != "" {
			return "No service fee is charged (a recipient is configured, at 0 bps)."
		}
		return "No service fee is charged."
	}
	return fmt.Sprintf("%d bps (%s%%) of a swap's output on %s, into %d listed mint(s), credited to "+
		"token accounts owned by %s.", fee.GetBps(), strconv.FormatFloat(float64(fee.GetBps())/100, 'f', 2, 64),
		rules.NetworkName(fee.GetNetwork()), len(fee.GetAccounts()), fee.GetOwner())
}
