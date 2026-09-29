// Package config is the Prediction demo's own half of a deployment: the provider it reads, the
// filters its operator chose, and the deposit terms every signal it publishes carries.
//
// Everything both demos need — the publisher's identity, the gateway, the environment, the
// database, the API and its token — is the support library's Config, read by the same code in both
// demos so that a secret or a bad number is reported identically wherever it is set
// (packages/publisher-support/config). This package adds the settings only a publisher that discovers its
// own signals has, and nothing here is known to the CopyTrading demo.
package config

import (
	"strconv"
	"strings"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/demo-prediction/internal/discovery"
	"github.com/BrRenat/SeekerAgentWallet/demo-prediction/internal/jupiter"
	support "github.com/BrRenat/SeekerAgentWallet/publisher-support/config"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/environment"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/network"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// Prediction is the Prediction template's own deployment: the provider it reads, the filters its
// operator chose, and the deposit terms every signal it publishes carries (SEE-96,
// docs/wiki/prediction-template.md).
//
// It is beside [Config] rather than inside it because the two templates are two deployments. A
// CopyTrading publisher has no provider and no filters; a Prediction publisher has both, and no
// caller of its API may write a signal. Everything both need — the publisher's identity, the
// gateway, the environment, the database, the API and its token — is [Config]'s, and is read the
// same way by the same code.
type Prediction struct {
	// The provider's origin, and the key for it when a deployment has one. The key goes in one
	// request header and nowhere else: not in a manifest, not in a document, not in a log line,
	// and not in an answer this template gives (docs/security.md).
	Provider string
	Key      string
	// How long one call to the provider may take, and the least time between two of them.
	Timeout time.Duration
	Gap     time.Duration
	// What this template looks for, and what every signal it publishes says about a deposit.
	Filters discovery.Filters
	Deposit discovery.Deposit
	// The operator's own line, published above the generated one in every note. Optional.
	Note string
}

// The defaults. Each of them is a restraint rather than a limit of anything: a template that polls
// four pages every five minutes and holds twenty-five proposals is a publisher a person can read,
// and it is inside the provider's keyless allowance with room to spare
// (docs/integrations/jupiter.md#rate-limits).
const (
	DefaultSource          = "polymarket"
	DefaultPollSeconds     = 300
	DefaultPageSize        = 25
	DefaultMostPages       = 4
	DefaultMostOpen        = 25
	DefaultMostChecks      = 20
	DefaultLeastCloseIn    = 60           // minutes
	DefaultMostCloseIn     = 30 * 24 * 60 // minutes: a month
	DefaultLifetimeHours   = 7 * 24
	DefaultProviderTimeout = 15 * time.Second
	// MostNoteBytes is how much prose an operator may put above the generated line in every note.
	// The rest of the note is the provider's wording and the boundary, and all of it together has
	// to fit in what a proposal carries.
	MostNoteBytes = 400
)

// Runs is what this template's own code executes on (SEE-174): Jupiter's prediction markets settle
// on Solana Mainnet and nowhere else, so a deployment declares Mainnet unless its operator says
// none, and cannot declare a network the provider does not trade on
// (PUBLISHER_SUPPORTED_NETWORKS, docs/wiki/prediction-template.md).
var Runs = support.Template{Networks: []network.Network{network.Mainnet}}

// LoadPrediction reads the environment and returns both halves of a Prediction deployment, or every
// problem it found in either of them.
//
// Both halves at once, deliberately: a first start with the gateway's credential missing *and* a
// category the provider does not have should report both, because the alternative is an operator
// fixing one variable per restart.
func LoadPrediction(lookup support.Lookup) (*support.Config, *Prediction, []string) {
	config, problems := support.LoadTemplate(lookup, Runs)

	read := support.NewReader(lookup)
	held := &Prediction{
		Provider: read.Text("PREDICTION_PROVIDER_URL", jupiter.Endpoint),
		Key:      read.Secret("PREDICTION_API_KEY"),
		Note:     read.Text("PREDICTION_NOTE", ""),
	}
	if canonical, err := support.Reachable(held.Provider); err != nil {
		read.Note("PREDICTION_PROVIDER_URL %v", err)
	} else {
		held.Provider = canonical
	}
	if held.Key != "" && !support.HeaderSafe(held.Key) {
		read.Note("PREDICTION_API_KEY must be one word with no whitespace or control " +
			"characters: it is sent as a request header")
	}
	if held.Note != "" && !signals.Printable(held.Note, MostNoteBytes, false) {
		read.Note("PREDICTION_NOTE must be at most %d bytes of printable text on one line: it is "+
			"published above the line this template writes itself, in every signal's note",
			MostNoteBytes)
	}

	held.Timeout = time.Duration(read.Whole("PREDICTION_PROVIDER_TIMEOUT_SECONDS",
		int(DefaultProviderTimeout/time.Second), 1, 120,
		"it is how long one call to the provider may take")) * time.Second
	held.Gap = time.Duration(read.Whole("PREDICTION_CALL_GAP_MS",
		int(jupiter.DefaultGap/time.Millisecond), 0, 60_000,
		"it is the least time between two calls to the provider, and the keyless allowance is "+
			"one call every two seconds")) * time.Millisecond

	held.Filters = discovery.Filters{
		Source:     strings.ToLower(read.Text("PREDICTION_SOURCE", DefaultSource)),
		Categories: lowered(read.List("PREDICTION_CATEGORIES")),
		Filter:     strings.ToLower(read.Text("PREDICTION_FILTER", "")),
		Tags:       lowered(read.List("PREDICTION_TAGS")),
		Keywords:   read.List("PREDICTION_KEYWORDS"),
	}
	if !jupiter.Sources[held.Filters.Source] {
		read.Note("PREDICTION_SOURCE must be one of %s: it is the venue whose markets the "+
			"provider aggregates, and a listing asks for one at a time",
			strings.Join(sorted(jupiter.Sources), ", "))
	}
	for _, category := range held.Filters.Categories {
		if !known(jupiter.Categories, category) {
			read.Note("PREDICTION_CATEGORIES may only name buckets the provider has (%s); %q is "+
				"not one, and the provider refuses a listing that names it",
				strings.Join(jupiter.Categories, ", "), category)
		}
	}
	if held.Filters.Filter != "" && !known(jupiter.Filters, held.Filters.Filter) {
		read.Note("PREDICTION_FILTER must be one of %s, or empty for none: they are the "+
			"provider's own named filters", strings.Join(jupiter.Filters, ", "))
	}
	for _, tag := range held.Filters.Tags {
		if !isTag(tag) {
			read.Note("PREDICTION_TAGS may only name the provider's own tags — up to 64 "+
				"characters of letters, digits, '-', '_' or '.', such as nfl or fed-rates — and "+
				"%q is not one", tag)
		}
	}
	for _, keyword := range held.Filters.Keywords {
		if len([]rune(keyword)) < 2 || !signals.Printable(keyword, 64, false) {
			read.Note("PREDICTION_KEYWORDS must each be 2 to 64 characters of printable text on "+
				"one line; %q is not. They are matched against the title, the bucket, the tags "+
				"and the market's own title, case-insensitively, as substrings", keyword)
		}
	}

	held.Filters.LeastCloseIn = time.Duration(read.Whole("PREDICTION_LEAST_CLOSE_IN_MINUTES",
		DefaultLeastCloseIn, 1, 365*24*60,
		"it is how soon a market may close and still be published, and a market that closes "+
			"in a minute is one nobody can act on")) * time.Minute
	held.Filters.MostCloseIn = time.Duration(read.Whole("PREDICTION_MOST_CLOSE_IN_MINUTES",
		DefaultMostCloseIn, 1, 3*365*24*60,
		"it is how far ahead a market may close and still be published")) * time.Minute
	if held.Filters.MostCloseIn <= held.Filters.LeastCloseIn {
		read.Note("PREDICTION_MOST_CLOSE_IN_MINUTES must be above " +
			"PREDICTION_LEAST_CLOSE_IN_MINUTES: between them they are a window, and a window " +
			"that closes before it opens matches nothing")
	}
	held.Filters.Lifetime = time.Duration(read.Whole("PREDICTION_LIFETIME_HOURS",
		DefaultLifetimeHours, 1, 365*24,
		"it is how long a signal lasts for a market the provider gives no close time at all, "+
			"counted from when this template first saw it")) * time.Hour
	held.Filters.Every = time.Duration(read.Whole("PREDICTION_POLL_SECONDS",
		DefaultPollSeconds, 30, 24*60*60,
		"it is how often the listing is read, and the provider's prediction API has no stream "+
			"to subscribe to instead")) * time.Second
	held.Filters.PageSize = read.Whole("PREDICTION_PAGE_SIZE", DefaultPageSize, 1,
		jupiter.MostPageSize, "it is how many events one listing call asks for, and the provider "+
			"refuses a range of more than 100")
	held.Filters.MostPages = read.Whole("PREDICTION_MOST_PAGES", DefaultMostPages, 1, 40,
		"it is how many pages of the listing one cycle reads per bucket")
	held.Filters.MostOpen = read.Whole("PREDICTION_MOST_OPEN", DefaultMostOpen, 1, 200,
		"it is how many proposals this publisher will hold open at once; a phone walks a feed in "+
			"pages of fifty, and a person reads a list")
	held.Filters.MostChecks = read.Whole("PREDICTION_MOST_CHECKS", DefaultMostChecks, 0, 200,
		"it is how many tracked markets one cycle asks the provider about directly")

	// The one filter with a rule about the environment attached to it. Publishing a market the
	// provider will not take an order for is a sandbox exercise — the order is refused on the
	// phone — and the way it reaches production is a copied `.env`.
	switch state := strings.ToLower(read.Text("PREDICTION_STATE", "open")); state {
	case "open":
	case "any":
		held.Filters.Closed = true
		if config != nil && config.Environment == environment.Production {
			read.Note("PREDICTION_STATE=any is refused in production: it publishes markets the " +
				"provider will not take an order for, which every phone then refuses. Run it " +
				"with PUBLISHER_ENVIRONMENT=sandbox, as a deliberate exercise of the closure " +
				"path")
		}
	default:
		read.Note("PREDICTION_STATE must be open (only markets the provider would take an order " +
			"for) or any (also closed, cancelled and settled markets, in sandbox only)")
	}

	held.Deposit = discovery.Deposit{
		Mint: read.Text("PREDICTION_DEPOSIT_MINT", signals.USDCMint),
	}
	if _, known := signals.DepositMints[held.Deposit.Mint]; !known {
		read.Note("PREDICTION_DEPOSIT_MINT must be one of the two tokens the provider takes a "+
			"deposit in — %s (JupUSD) or %s (USDC) — because anything else names a token the "+
			"provider will not accept", signals.JupUSDMint, signals.USDCMint)
	} else {
		// The label is derived rather than configured: it is shown beside the mint, never instead
		// of it, and nobody should have to type one.
		held.Deposit.Symbol = signals.DepositLabels[held.Deposit.Mint]
	}
	held.Deposit.Least = units(read, "PREDICTION_LEAST_DEPOSIT", 0)
	held.Deposit.Most = units(read, "PREDICTION_MOST_DEPOSIT", 0)
	switch {
	case held.Deposit.Most != 0 && held.Deposit.Most < held.Deposit.Least:
		read.Note("PREDICTION_MOST_DEPOSIT must be at or above PREDICTION_LEAST_DEPOSIT, or " +
			"empty for no ceiling at all: between them they are what this publisher will have " +
			"its signals acted on with, and the amount inside them is each owner's own")
	case held.Deposit.Most != 0 && held.Deposit.Most < signals.LeastOrderDeposit:
		read.Note("PREDICTION_MOST_DEPOSIT must be at least %d, which is the smallest order the "+
			"provider accepts (five dollars in the deposit token's base units): a lower ceiling "+
			"cannot be satisfied by any order at all", signals.LeastOrderDeposit)
	}

	problems = append(problems, read.Problems()...)
	if len(problems) > 0 {
		return nil, nil, problems
	}
	return config, held, nil
}

// units reads an amount in a token's base units. It is a uint64 because base units are whole and
// can exceed what a smaller number holds: a dollar token's ceiling is in millionths.
//
// It is a function over the shared reader rather than a method on it, because the reader belongs
// to the support library and this is the one kind of setting only this demo has.
func units(read *support.Reader, name string, fallback uint64) uint64 {
	raw := read.Text(name, "")
	if raw == "" {
		return fallback
	}
	value, err := strconv.ParseUint(raw, 10, 64)
	if err != nil {
		read.Note("%s must be a whole number of the deposit token's base units, as text — five "+
			"dollars of a six-decimal token is 5000000 — and %q is not", name, raw)
		return fallback
	}
	return value
}

func lowered(items []string) []string {
	if items == nil {
		return nil
	}
	lower := make([]string, 0, len(items))
	for _, one := range items {
		lower = append(lower, strings.ToLower(one))
	}
	return lower
}

func known(among []string, value string) bool {
	for _, one := range among {
		if one == value {
			return true
		}
	}
	return false
}

func sorted(among map[string]bool) []string {
	names := make([]string, 0, len(among))
	for name := range among {
		names = append(names, name)
	}
	// A stable order, so the message is the same every time it is printed.
	for first := range names {
		for second := first + 1; second < len(names); second++ {
			if names[second] < names[first] {
				names[first], names[second] = names[second], names[first]
			}
		}
	}
	return names
}

// isTag is the shape of one of the provider's tags: a bounded token, the way its own answers spell
// them ("nfl", "fed-rates", "crypto-prices").
func isTag(value string) bool {
	if value == "" || len(value) > 64 {
		return false
	}
	for _, character := range value {
		switch {
		case character >= 'a' && character <= 'z',
			character >= '0' && character <= '9',
			character == '-' || character == '_' || character == '.':
		default:
			return false
		}
	}
	return true
}
