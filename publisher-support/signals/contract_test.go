package signals

import (
	"os"
	"regexp"
	"strconv"
	"strings"
	"testing"
)

// The gateway's own copy of these rules, which this module deliberately does not import: a
// template is something a developer copies out, and one that compiled against the gateway's
// internals would not be copyable. What that costs is the risk of drift, so this test pays it by
// reading the gateway's source.
//
// It is the same technique as the phone's `push/FeedHintContractTest`, which reads the relay's Go
// source, and for the same reason: a disagreement here would not be an error anywhere. It would be
// a template that publishes documents the gateway refuses, or — worse — accepts documents the
// phone refuses, and the first sign of either would be a signal that nobody can see.
//
// It skips when the file is not there, which is what a copied-out template looks like.
const gatewayRules = "../../feed-gateway/internal/rules/rules.go"

func TestTheBoundsAreTheGatewaysOwn(t *testing.T) {
	source, err := os.ReadFile(gatewayRules)
	if err != nil {
		t.Skipf("the gateway's source is not here (%v), which is what a copied-out template "+
			"looks like", err)
	}
	for name, held := range map[string]int{
		"MaxValues":         MaxValues,
		"MaxValueTextBytes": MaxValueTextBytes,
		"MaxNoteBytes":      MaxNoteBytes,
		"MaxNameBytes":      MaxNameBytes,
	} {
		pattern := regexp.MustCompile(`(?m)^\s*` + name + `\s*=\s*(\d+)\s*$`)
		match := pattern.FindSubmatch(source)
		if match == nil {
			t.Fatalf("%s is no longer declared in %s: the gateway's bound moved or was renamed, "+
				"and this template's copy of it is now a guess", name, gatewayRules)
		}
		theirs, err := strconv.Atoi(string(match[1]))
		if err != nil {
			t.Fatal(err)
		}
		if theirs != held {
			t.Fatalf("%s is %d here and %d in the gateway (%s): a template that accepts more "+
				"than the gateway does publishes signals nobody sees", name, held, theirs,
				gatewayRules)
		}
	}
}

// The highest revision either side will order. It is a uint64 on the wire and a signed 64-bit Long
// on the phone, so the bound is not an opinion: above it, a revision arrives on the phone as a
// negative number.
func TestTheRevisionCeilingIsTheGatewaysOwn(t *testing.T) {
	source, err := os.ReadFile(gatewayRules)
	if err != nil {
		t.Skipf("the gateway's source is not here (%v)", err)
	}
	if !regexp.MustCompile(`MaxRevision uint64 = 1<<63 - 1`).Match(source) {
		t.Fatalf("the gateway's MaxRevision is no longer 1<<63 - 1; this template publishes up "+
			"to %d", uint64(MaxRevision))
	}
	if MaxRevision != 1<<63-1 {
		t.Fatalf("MaxRevision is %d here", uint64(MaxRevision))
	}
}

// The protocol version and the channel's shape, which are the two things a manifest and every
// proposal have to agree with the gateway about.
func TestTheChannelIsTheGatewaysOwn(t *testing.T) {
	source, err := os.ReadFile(gatewayRules)
	if err != nil {
		t.Skipf("the gateway's source is not here (%v)", err)
	}
	if !regexp.MustCompile(`channelPrefix = "server/"`).Match(source) {
		t.Fatal("the gateway's channel prefix is no longer \"server/\"")
	}
	if ChannelFor("3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d") !=
		"server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d" {
		t.Fatal("this template's channel is not server/<server_id>")
	}
}

// The phone's own copy of a kind's rules, which is the other side this module must not drift from.
// The gateway refuses a document no phone could use; the phone refuses one that reached it anyway —
// and a term rule that disagreed here would be a signal that publishes cleanly and is then thrown
// away on every device, which is the failure with no error message anywhere.
//
// It reads the phone's source for the same reason the gateway's is read above, and skips the same
// way when the source is not there.
//
// The rules are in two files each, and deliberately so since SEE-145: an action's own schema — its
// term names and what a publisher may write — belongs to the action, in `plugins/actions/`, and
// what the venue will actually accept — which mints, what its smallest order is — belongs to
// whoever executes it, in `jupiter/`. This side has to agree with both, so both are read.
const phoneSource = "../../android/app/src/main/java/io/github/brrenat/seekervault/"

var phoneSwapTerms = []string{
	phoneSource + "plugins/actions/SwapAction.kt",
	phoneSource + "jupiter/JupiterExecutionProvider.kt",
}

var phonePredictionTerms = []string{
	phoneSource + "plugins/actions/PredictionAction.kt",
	phoneSource + "jupiter/JupiterExecutionProvider.kt",
}

func TestThePredictionRulesAreThePhonesOwn(t *testing.T) {
	source := phoneRules(t, phonePredictionTerms)

	// The two tokens the provider takes, and the set being exactly those two.
	for name, held := range map[string]string{
		"JUP_USD_MINT": JupUSDMint,
		"USDC_MINT":    USDCMint,
	} {
		pattern := regexp.MustCompile(`const val ` + name + `: String = "([1-9A-HJ-NP-Za-km-z]+)"`)
		match := pattern.FindStringSubmatch(source)
		if match == nil {
			t.Fatalf("%s is no longer declared in %s", name, strings.Join(phonePredictionTerms, ", "))
		}
		if match[1] != held {
			t.Fatalf("%s is %s on the phone and %s here: a template that published a deposit "+
				"mint the plugin does not take would publish a signal every phone refuses",
				name, match[1], held)
		}
	}
	if !regexp.MustCompile(`DEPOSIT_MINTS: Set<String> = setOf\(JUP_USD_MINT, USDC_MINT\)`).
		MatchString(source) {
		t.Fatalf("the phone's set of deposit mints is no longer exactly those two (%s)",
			strings.Join(phonePredictionTerms, ", "))
	}

	// The provider's own smallest order, which this side publishes rather than assumes.
	floor := regexp.MustCompile(`const val LEAST_ORDER_DEPOSIT: ULong = ([0-9_]+)UL`).
		FindStringSubmatch(source)
	if floor == nil {
		t.Fatalf("LEAST_ORDER_DEPOSIT is no longer declared in %s", strings.Join(phonePredictionTerms, ", "))
	}
	theirs, err := strconv.ParseUint(strings.ReplaceAll(floor[1], "_", ""), 10, 64)
	if err != nil {
		t.Fatal(err)
	}
	if theirs != LeastOrderDeposit {
		t.Fatalf("the provider's floor is %d on the phone and %d here; the phone raises a "+
			"publisher's floor to its own, so the two have to be one number", theirs,
			LeastOrderDeposit)
	}

	// The identifier a market or an event is named by, which is the one rule this side reimplements
	// rather than shares. The pattern is quoted here so that a change to it fails a test rather
	// than passing silently.
	// And the longest destination either side will read, which is the protocol's own cap on one
	// value said in both readers (SEE-157).
	bound := regexp.MustCompile(`const val MOST_LINK_LENGTH: Int = (\d+)`).FindStringSubmatch(
		phoneRules(t, []string{phoneSource + "plugins/actions/ProviderLink.kt"}))
	if bound == nil {
		t.Fatalf("MOST_LINK_LENGTH is no longer declared in the phone's link rule")
	}
	if theirLink, err := strconv.Atoi(bound[1]); err != nil {
		t.Fatal(err)
	} else if theirLink != MostLinkBytes {
		t.Fatalf("a destination may be %d on the phone and %d here", theirLink, MostLinkBytes)
	}

	if !regexp.MustCompile(`Regex\("""\[A-Za-z0-9\]\[A-Za-z0-9\._:-\]\{0,63\}"""\)`).
		MatchString(source) {
		t.Fatalf("the phone's market-identifier pattern has changed (%s); IsMarketID here is "+
			"written against [A-Za-z0-9][A-Za-z0-9._:-]{0,63}", strings.Join(phonePredictionTerms, ", "))
	}

	// And the term names themselves: every one the phone reads, spelled the same on this side.
	names := map[string]string{}
	for _, match := range regexp.MustCompile(`const val ([A-Z_]+) = "([a-z_]+)"`).
		FindAllStringSubmatch(source, -1) {
		names[match[1]] = match[2]
	}
	for phone, held := range map[string]string{
		"MARKET_ID":        MarketID,
		"EVENT_ID":         EventID,
		"PROVIDER":         SourceProvider,
		"DEPOSIT_MINT":     DepositMint,
		"DEPOSIT_DECIMALS": DepositDecimals,
		"DEPOSIT_SYMBOL":   DepositSymbol,
		"LEAST_DEPOSIT":    LeastDeposit,
		"MOST_DEPOSIT":     MostDeposit,
		// Where the owner carries on, which is the one pair of terms that leaves the phone: a
		// misspelling here is a destination published and never offered (SEE-157).
		"PROVIDER_DEEP_LINK": ProviderDeepLink,
		"PROVIDER_WEB_URL":   ProviderWebURL,
	} {
		if names[phone] != held {
			t.Fatalf("the phone reads %s as %q and this template publishes %q", phone,
				names[phone], held)
		}
	}
}

// The two bounds both kinds share, which live in the phone's swap file and are applied to a
// prediction market's labels as well.
func TestTheLabelBoundsAreThePhonesOwn(t *testing.T) {
	source := phoneRules(t, phoneSwapTerms)
	for name, held := range map[string]int{
		"MOST_DECIMALS":      mostDecimals,
		"MOST_SYMBOL_LENGTH": mostSymbolUnits,
	} {
		pattern := regexp.MustCompile(`const val ` + name + `: Int = (\d+)`)
		match := pattern.FindStringSubmatch(source)
		if match == nil {
			t.Fatalf("%s is no longer declared in %s", name, strings.Join(phoneSwapTerms, ", "))
		}
		theirs, err := strconv.Atoi(match[1])
		if err != nil {
			t.Fatal(err)
		}
		if theirs != held {
			t.Fatalf("%s is %d on the phone and %d here", name, theirs, held)
		}
	}
}

func phoneRules(t *testing.T, paths []string) string {
	t.Helper()
	read := make([]string, 0, len(paths))
	for _, path := range paths {
		source, err := os.ReadFile(path)
		if err != nil {
			t.Skipf("the phone's source is not here (%v), which is what a copied-out template "+
				"looks like", err)
		}
		read = append(read, string(source))
	}
	return strings.Join(read, "\n")
}
