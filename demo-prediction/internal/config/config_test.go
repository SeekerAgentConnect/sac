package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/demo-prediction/internal/jupiter"
	support "github.com/BrRenat/SeekerAgentWallet/publisher-support/config"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/publishertest"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// A Prediction deployment needs nothing the CopyTrading one does not, which is the point of the
// defaults: the provider, the cadence, the bounds and the deposit token all have one.
func predicting(extra map[string]string) map[string]string {
	environment := publishertest.Environment()
	environment["PUBLISHER_ENVIRONMENT"] = "sandbox"
	for name, value := range extra {
		environment[name] = value
	}
	return environment
}

func loadPredicting(t *testing.T, environment map[string]string) (*support.Config, *Prediction) {
	t.Helper()
	config, held, problems := LoadPrediction(publishertest.LookupIn(environment))
	if len(problems) > 0 {
		t.Fatalf("unexpected problems: %v", problems)
	}
	return config, held
}

func refusedPredicting(t *testing.T, environment map[string]string, about string) {
	t.Helper()
	config, held, problems := LoadPrediction(publishertest.LookupIn(environment))
	if config != nil || held != nil {
		t.Fatal("a configuration was returned for a deployment that cannot start")
	}
	for _, problem := range problems {
		if strings.Contains(problem, about) {
			return
		}
	}
	t.Fatalf("no problem mentions %s: %v", about, problems)
}

func TestAPredictionDeploymentStartsWithItsDefaults(t *testing.T) {
	config, held := loadPredicting(t, predicting(nil))
	switch {
	case config.ServerID == "":
		t.Fatal("the publisher's own half was not read")
	case held.Provider != jupiter.Endpoint:
		t.Fatalf("provider %q", held.Provider)
	case held.Key != "":
		t.Fatal("a key was invented for the keyless host")
	case held.Timeout != DefaultProviderTimeout:
		t.Fatalf("timeout %s", held.Timeout)
	case held.Gap != jupiter.DefaultGap:
		t.Fatalf("gap %s", held.Gap)
	case held.Filters.Source != DefaultSource:
		t.Fatalf("venue %q", held.Filters.Source)
	case held.Filters.Categories != nil:
		t.Fatalf("buckets %v: none named means the provider's own listing", held.Filters.Categories)
	case held.Filters.Filter != "":
		t.Fatalf("filter %q", held.Filters.Filter)
	case held.Filters.Closed:
		t.Fatal("closed markets are published by default")
	case held.Filters.Every != DefaultPollSeconds*time.Second:
		t.Fatalf("cadence %s", held.Filters.Every)
	case held.Filters.PageSize != DefaultPageSize:
		t.Fatalf("page size %d", held.Filters.PageSize)
	case held.Filters.MostPages != DefaultMostPages:
		t.Fatalf("pages %d", held.Filters.MostPages)
	case held.Filters.MostOpen != DefaultMostOpen:
		t.Fatalf("ceiling %d", held.Filters.MostOpen)
	case held.Filters.MostChecks != DefaultMostChecks:
		t.Fatalf("checks %d", held.Filters.MostChecks)
	case held.Filters.LeastCloseIn != DefaultLeastCloseIn*time.Minute:
		t.Fatalf("near edge %s", held.Filters.LeastCloseIn)
	case held.Filters.MostCloseIn != DefaultMostCloseIn*time.Minute:
		t.Fatalf("far edge %s", held.Filters.MostCloseIn)
	case held.Filters.Lifetime != DefaultLifetimeHours*time.Hour:
		t.Fatalf("lifetime %s", held.Filters.Lifetime)
	case held.Deposit.Mint != signals.USDCMint:
		t.Fatalf("deposit mint %q", held.Deposit.Mint)
	case held.Deposit.Symbol != "USDC":
		t.Fatalf("deposit label %q: it is derived from the mint, not configured",
			held.Deposit.Symbol)
	case held.Deposit.Least != 0 || held.Deposit.Most != 0:
		t.Fatalf("bounds %d..%d: no floor means the provider's own, and no ceiling means none",
			held.Deposit.Least, held.Deposit.Most)
	case held.Note != "":
		t.Fatalf("note %q", held.Note)
	}
	// The cadence and the bounds together are inside the provider's keyless allowance: four pages
	// plus twenty checks, every five minutes, at one call every 2.1 seconds.
	calls := held.Filters.MostPages + held.Filters.MostChecks
	if spent := time.Duration(calls) * held.Gap; spent > held.Filters.Every {
		t.Fatalf("a cycle of %d calls at %s each cannot finish inside %s", calls, held.Gap,
			held.Filters.Every)
	}
}

// Everything an operator can ask for, read back as the reconciler will apply it.
func TestEveryFilterIsRead(t *testing.T) {
	_, held := loadPredicting(t, predicting(map[string]string{
		"PREDICTION_SOURCE":                   "Kalshi",
		"PREDICTION_CATEGORIES":               "Crypto, economics ,",
		"PREDICTION_FILTER":                   "Trending",
		"PREDICTION_TAGS":                     "BTC,fed-rates",
		"PREDICTION_KEYWORDS":                 "Bitcoin, Ethereum",
		"PREDICTION_STATE":                    "any",
		"PREDICTION_LEAST_CLOSE_IN_MINUTES":   "30",
		"PREDICTION_MOST_CLOSE_IN_MINUTES":    "1440",
		"PREDICTION_LIFETIME_HOURS":           "48",
		"PREDICTION_POLL_SECONDS":             "600",
		"PREDICTION_PAGE_SIZE":                "50",
		"PREDICTION_MOST_PAGES":               "2",
		"PREDICTION_MOST_OPEN":                "5",
		"PREDICTION_MOST_CHECKS":              "3",
		"PREDICTION_DEPOSIT_MINT":             signals.JupUSDMint,
		"PREDICTION_LEAST_DEPOSIT":            "10000000",
		"PREDICTION_MOST_DEPOSIT":             "250000000",
		"PREDICTION_NOTE":                     "Markets I follow. Not advice.",
		"PREDICTION_PROVIDER_URL":             "http://127.0.0.1:9999",
		"PREDICTION_API_KEY":                  "jup_0123456789abcdefghijklmnop",
		"PREDICTION_CALL_GAP_MS":              "1500",
		"PREDICTION_PROVIDER_TIMEOUT_SECONDS": "30",
	}))
	switch {
	case held.Filters.Source != "kalshi":
		t.Fatalf("venue %q: it is compared with the provider's own set, lower case",
			held.Filters.Source)
	case strings.Join(held.Filters.Categories, ",") != "crypto,economics":
		t.Fatalf("buckets %v: lower case, and an empty item is not a bucket",
			held.Filters.Categories)
	case held.Filters.Filter != "trending":
		t.Fatalf("filter %q", held.Filters.Filter)
	case strings.Join(held.Filters.Tags, ",") != "btc,fed-rates":
		t.Fatalf("tags %v", held.Filters.Tags)
	case strings.Join(held.Filters.Keywords, ",") != "Bitcoin,Ethereum":
		// Keywords keep their case, because the match lowers both sides and an operator reading
		// the status answer should see what they wrote.
		t.Fatalf("keywords %v", held.Filters.Keywords)
	case !held.Filters.Closed:
		t.Fatal("PREDICTION_STATE=any was not read")
	case held.Filters.LeastCloseIn != 30*time.Minute:
		t.Fatalf("near edge %s", held.Filters.LeastCloseIn)
	case held.Filters.MostCloseIn != 24*time.Hour:
		t.Fatalf("far edge %s", held.Filters.MostCloseIn)
	case held.Filters.Lifetime != 48*time.Hour:
		t.Fatalf("lifetime %s", held.Filters.Lifetime)
	case held.Filters.Every != 10*time.Minute:
		t.Fatalf("cadence %s", held.Filters.Every)
	case held.Filters.PageSize != 50 || held.Filters.MostPages != 2:
		t.Fatalf("walk %d x %d", held.Filters.MostPages, held.Filters.PageSize)
	case held.Filters.MostOpen != 5 || held.Filters.MostChecks != 3:
		t.Fatalf("bounds %d, %d", held.Filters.MostOpen, held.Filters.MostChecks)
	case held.Deposit.Mint != signals.JupUSDMint || held.Deposit.Symbol != "JupUSD":
		t.Fatalf("deposit %q (%q)", held.Deposit.Mint, held.Deposit.Symbol)
	case held.Deposit.Least != 10_000_000 || held.Deposit.Most != 250_000_000:
		t.Fatalf("bounds %d..%d", held.Deposit.Least, held.Deposit.Most)
	case held.Note != "Markets I follow. Not advice.":
		t.Fatalf("note %q", held.Note)
	case held.Provider != "http://127.0.0.1:9999":
		t.Fatalf("provider %q", held.Provider)
	case held.Key != "jup_0123456789abcdefghijklmnop":
		t.Fatalf("key %q", held.Key)
	case held.Gap != 1500*time.Millisecond:
		t.Fatalf("gap %s", held.Gap)
	case held.Timeout != 30*time.Second:
		t.Fatalf("timeout %s", held.Timeout)
	}
}

// Every way a Prediction deployment can be wrong, with the variable it names. A problem that named
// no variable would leave an operator guessing.
func TestEveryWayAPredictionDeploymentIsRefused(t *testing.T) {
	for _, one := range []struct {
		name  string
		set   map[string]string
		about string
	}{
		{
			name:  "a venue the provider does not aggregate",
			set:   map[string]string{"PREDICTION_SOURCE": "betfair"},
			about: "PREDICTION_SOURCE",
		},
		{
			name:  "a bucket the provider does not have",
			set:   map[string]string{"PREDICTION_CATEGORIES": "crypto,horoscopes"},
			about: "PREDICTION_CATEGORIES",
		},
		{
			name:  "a named filter the provider does not have",
			set:   map[string]string{"PREDICTION_FILTER": "hot"},
			about: "PREDICTION_FILTER",
		},
		{
			name:  "a tag with a space in it",
			set:   map[string]string{"PREDICTION_TAGS": "fed rates"},
			about: "PREDICTION_TAGS",
		},
		{
			name:  "a keyword of one character",
			set:   map[string]string{"PREDICTION_KEYWORDS": "a"},
			about: "PREDICTION_KEYWORDS",
		},
		{
			name:  "a keyword with a line break in it",
			set:   map[string]string{"PREDICTION_KEYWORDS": "bitcoin\nethereum"},
			about: "PREDICTION_KEYWORDS",
		},
		{
			name:  "a state that is neither",
			set:   map[string]string{"PREDICTION_STATE": "closed"},
			about: "PREDICTION_STATE",
		},
		{
			name: "closed markets in production",
			set: map[string]string{
				"PREDICTION_STATE":      "any",
				"PUBLISHER_ENVIRONMENT": "production",
			},
			about: "PREDICTION_STATE=any is refused in production",
		},
		{
			name: "a window that closes before it opens",
			set: map[string]string{
				"PREDICTION_LEAST_CLOSE_IN_MINUTES": "1440",
				"PREDICTION_MOST_CLOSE_IN_MINUTES":  "60",
			},
			about: "PREDICTION_MOST_CLOSE_IN_MINUTES",
		},
		{
			name:  "a cadence faster than the provider's allowance was written for",
			set:   map[string]string{"PREDICTION_POLL_SECONDS": "5"},
			about: "PREDICTION_POLL_SECONDS",
		},
		{
			name:  "a page larger than the provider will answer",
			set:   map[string]string{"PREDICTION_PAGE_SIZE": "500"},
			about: "PREDICTION_PAGE_SIZE",
		},
		{
			name:  "a page size that is not a number",
			set:   map[string]string{"PREDICTION_PAGE_SIZE": "twenty"},
			about: "PREDICTION_PAGE_SIZE",
		},
		{
			name:  "no pages at all",
			set:   map[string]string{"PREDICTION_MOST_PAGES": "0"},
			about: "PREDICTION_MOST_PAGES",
		},
		{
			name:  "no proposals at all",
			set:   map[string]string{"PREDICTION_MOST_OPEN": "0"},
			about: "PREDICTION_MOST_OPEN",
		},
		{
			name:  "a deposit token the provider does not take",
			set:   map[string]string{"PREDICTION_DEPOSIT_MINT": signals.WrappedSOL},
			about: "PREDICTION_DEPOSIT_MINT",
		},
		{
			name:  "a deposit token that is not even a mint",
			set:   map[string]string{"PREDICTION_DEPOSIT_MINT": "USDC"},
			about: "PREDICTION_DEPOSIT_MINT",
		},
		{
			name: "a ceiling below the floor",
			set: map[string]string{
				"PREDICTION_LEAST_DEPOSIT": "100000000",
				"PREDICTION_MOST_DEPOSIT":  "50000000",
			},
			about: "PREDICTION_MOST_DEPOSIT",
		},
		{
			name:  "a ceiling below the provider's own minimum order",
			set:   map[string]string{"PREDICTION_MOST_DEPOSIT": "1000000"},
			about: "PREDICTION_MOST_DEPOSIT",
		},
		{
			name:  "an amount that is not a whole number of base units",
			set:   map[string]string{"PREDICTION_LEAST_DEPOSIT": "5.5"},
			about: "PREDICTION_LEAST_DEPOSIT",
		},
		{
			name:  "a provider URL with a path on it",
			set:   map[string]string{"PREDICTION_PROVIDER_URL": "https://lite.example.com/api/v1"},
			about: "PREDICTION_PROVIDER_URL",
		},
		{
			name:  "a provider URL that is not a URL",
			set:   map[string]string{"PREDICTION_PROVIDER_URL": "lite-api"},
			about: "PREDICTION_PROVIDER_URL",
		},
		{
			name:  "a key with whitespace in it",
			set:   map[string]string{"PREDICTION_API_KEY": "jup_one two"},
			about: "PREDICTION_API_KEY",
		},
		{
			name:  "prose with a line break in it",
			set:   map[string]string{"PREDICTION_NOTE": "First line\nsecond line"},
			about: "PREDICTION_NOTE",
		},
		{
			name:  "prose longer than a note has room for",
			set:   map[string]string{"PREDICTION_NOTE": strings.Repeat("a", MostNoteBytes+1)},
			about: "PREDICTION_NOTE",
		},
	} {
		t.Run(one.name, func(t *testing.T) {
			refusedPredicting(t, predicting(one.set), one.about)
		})
	}
}

// The publisher's own half and the prediction half are reported together, so a first start is
// fixed in one pass.
func TestBothHalvesAreReportedAtOnce(t *testing.T) {
	_, _, problems := LoadPrediction(publishertest.LookupIn(map[string]string{
		"PREDICTION_CATEGORIES": "horoscopes",
	}))
	named := strings.Join(problems, "\n")
	for _, name := range []string{
		"PUBLISHER_SERVER_ID", "PUBLISHER_GATEWAY_URL", "PUBLISHER_ENVIRONMENT",
		"PUBLISHER_DATABASE_PATH", "PUBLISHER_API_TOKEN", "BROADCAST_CREDENTIAL",
		"PREDICTION_CATEGORIES",
	} {
		if !strings.Contains(named, name) {
			t.Fatalf("nothing said about %s:\n%s", name, named)
		}
	}
}

// The provider's key may be a file, like the other two secrets, and naming both a value and a file
// is a configuration error rather than a preference.
func TestTheProvidersKeyMayBeAFile(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "jupiter.key")
	if err := os.WriteFile(path, []byte("jup_abcdefghijklmnopqrstuvwx\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	_, held := loadPredicting(t, predicting(map[string]string{"PREDICTION_API_KEY_FILE": path}))
	if held.Key != "jup_abcdefghijklmnopqrstuvwx" {
		t.Fatalf("key %q", held.Key)
	}

	refusedPredicting(t, predicting(map[string]string{
		"PREDICTION_API_KEY":      "jup_one",
		"PREDICTION_API_KEY_FILE": path,
	}), "PREDICTION_API_KEY_FILE")
	refusedPredicting(t, predicting(map[string]string{
		"PREDICTION_API_KEY_FILE": filepath.Join(directory, "absent"),
	}), "cannot be read")
}

// A refusal never quotes the key, whichever way it arrived — the same rule the other two secrets
// follow, and the reason a message names a variable rather than a value.
func TestNoRefusalQuotesTheProvidersKey(t *testing.T) {
	const key = "jup_secret_0123456789abcdefghij"
	_, _, problems := LoadPrediction(publishertest.LookupIn(predicting(map[string]string{
		"PREDICTION_API_KEY":    key,
		"PREDICTION_CATEGORIES": "horoscopes",
		"PREDICTION_SOURCE":     "betfair",
	})))
	for _, problem := range problems {
		if strings.Contains(problem, key) {
			t.Fatalf("a problem quotes the key: %s", problem)
		}
	}
	if len(problems) < 2 {
		t.Fatalf("only %d problems, so this test proved little: %v", len(problems), problems)
	}
}
