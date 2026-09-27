package signals

import (
	"strings"
	"testing"
	"time"
)

// A market this template could have discovered: the shape the reconciler builds and the shape a
// hand-written prediction publisher would send.
func predictionTerms() map[string]string {
	return map[string]string{
		MarketID:        "POLY-3398287-0",
		EventID:         "POLY-810546",
		SourceProvider:  "polymarket",
		DepositMint:     USDCMint,
		DepositDecimals: "6",
		DepositSymbol:   "USDC",
	}
}

func predicting(name, value string) map[string]string {
	terms := predictionTerms()
	terms[name] = value
	return terms
}

func notPredicting(name string) map[string]string {
	terms := predictionTerms()
	delete(terms, name)
	return terms
}

// Every way a market's terms can be wrong, with the code the caller is told. The list is the
// contract in docs/protocol.md#a-prediction-markets-terms-see-94, and the rules are
// plugins/actions/PredictionAction.kt's: a template must not publish a document the phone would
// refuse.
func TestEveryWayAMarketsTermsCanBeWrong(t *testing.T) {
	for _, one := range []struct {
		name  string
		terms map[string]string
		code  string
		term  string
	}{
		{"no market", notPredicting(MarketID), "missing", MarketID},
		{"an empty market", predicting(MarketID, "  "), "missing", MarketID},
		{"a market named by a URL", predicting(MarketID, "https://example.com/m/1"),
			"not_an_identifier", MarketID},
		{"a market with a space in it", predicting(MarketID, "POLY 1"), "not_an_identifier",
			MarketID},
		{"a market beginning with a separator", predicting(MarketID, "-POLY-1"),
			"not_an_identifier", MarketID},
		{"a market longer than the phone reads", predicting(MarketID, strings.Repeat("a", 65)),
			"not_an_identifier", MarketID},
		{"a market with a character outside the pattern", predicting(MarketID, "POLY-1/2"),
			"not_an_identifier", MarketID},
		{"an event named by a URL", predicting(EventID, "https://example.com/e/1"),
			"not_an_identifier", EventID},
		{"a source label too long to show", predicting(SourceProvider, strings.Repeat("p", 17)),
			"bad_symbol", SourceProvider},
		{"no deposit mint", notPredicting(DepositMint), "missing", DepositMint},
		{"a ticker for the deposit mint", predicting(DepositMint, "USDC"), "not_a_mint",
			DepositMint},
		{"a mint the provider does not take", predicting(DepositMint, WrappedSOL),
			"unsupported_mint", DepositMint},
		{"no decimals", notPredicting(DepositDecimals), "missing", DepositDecimals},
		{"decimals that are not a number", predicting(DepositDecimals, "six"), "bad_number",
			DepositDecimals},
		{"decimals above what a mint can have", predicting(DepositDecimals, "19"), "bad_number",
			DepositDecimals},
		{"decimals that are not this mint's", predicting(DepositDecimals, "9"), "bad_decimals",
			DepositDecimals},
		{"a deposit label too long to show", predicting(DepositSymbol, strings.Repeat("a", 17)),
			"bad_symbol", DepositSymbol},
		{"a fractional bound", predicting(LeastDeposit, "5.5"), "bad_number", LeastDeposit},
		{"a negative bound", predicting(MostDeposit, "-1"), "bad_number", MostDeposit},
		{"a ceiling of zero", predicting(MostDeposit, "0"), "impossible_amounts", MostDeposit},
		{"a ceiling below the provider's own minimum", predicting(MostDeposit, "4000000"),
			"impossible_amounts", MostDeposit},
		{"a destination that is code", predicting(ProviderDeepLink, "javascript:alert(1)"),
			"not_a_link", ProviderDeepLink},
		{"a destination without the guarantee", predicting(ProviderWebURL,
			"http://jup.ag/prediction/x"), "not_a_link", ProviderWebURL},
		{"a destination that is somewhere on this phone",
			predicting(ProviderDeepLink, "file:///data/data/x"), "not_a_link", ProviderDeepLink},
		{"a term this kind does not know", predicting("side", "yes"), "unknown_term", "side"},
		{"a misspelled term", predicting("market", "POLY-1"), "unknown_term", "market"},
	} {
		t.Run(one.name, func(t *testing.T) {
			_, fault := Prediction{}.Terms(one.terms)
			switch {
			case fault == nil:
				t.Fatalf("expected %s (%s), got no fault", one.code, one.term)
			case fault.Code != one.code || fault.Term != one.term:
				t.Fatalf("got %s (%s), expected %s (%s)", fault.Code, fault.Term, one.code,
					one.term)
			}
		})
	}
	// A floor above the ceiling, which needs both bounds at once.
	terms := predictionTerms()
	terms[LeastDeposit], terms[MostDeposit] = "10000000", "9000000"
	_, fault := Prediction{}.Terms(terms)
	if fault == nil || fault.Code != "impossible_amounts" || fault.Term != MostDeposit {
		t.Fatalf("a floor above the ceiling: %v", fault)
	}
}

// There is no side. A publisher names a market and the owner chooses YES or NO on their own phone,
// so no term can carry an opinion about which side to take — and none can be invented, because a
// term this kind does not know is refused rather than carried.
func TestNoTermCanCarryASide(t *testing.T) {
	for _, name := range []string{
		"side", "is_yes", "outcome", "choice", "direction", "recommendation", "confidence",
	} {
		_, fault := Prediction{}.Terms(predicting(name, "yes"))
		if fault == nil || fault.Code != "unknown_term" || fault.Term != name {
			t.Fatalf("%s: %v", name, fault)
		}
	}
}

// The provider's own floor is published rather than assumed. The phone raises a publisher's floor
// to it, so a signal that named a smaller one would be a document that disagrees with every phone
// reading it.
func TestTheProvidersOwnFloorIsPublished(t *testing.T) {
	for _, one := range []struct {
		name  string
		least string
		want  string
	}{
		{"no floor at all", "", "5000000"},
		{"a floor of zero", "0", "5000000"},
		{"a floor below the provider's", "1000000", "5000000"},
		{"a floor at the provider's", "5000000", "5000000"},
		{"a floor above the provider's", "25000000", "25000000"},
	} {
		t.Run(one.name, func(t *testing.T) {
			terms := notPredicting(LeastDeposit)
			if one.least != "" {
				terms[LeastDeposit] = one.least
			}
			published, fault := Prediction{}.Terms(terms)
			if fault != nil {
				t.Fatal(fault)
			}
			if published[LeastDeposit] != one.want {
				t.Fatalf("the floor is %q, expected %q", published[LeastDeposit], one.want)
			}
		})
	}
}

// Both tokens the provider takes, with the decimals each of them actually has. The set is closed
// because a deposit mint is not a free parameter: anything else names a token the provider will not
// accept.
func TestBothDepositMintsAreTakenWithTheirOwnDecimals(t *testing.T) {
	if len(DepositMints) != 2 {
		t.Fatalf("the deposit mints are %v", DepositMints)
	}
	for mint, decimals := range DepositMints {
		if !IsMint(mint) {
			t.Fatalf("%s is not a 32-byte base58 address", mint)
		}
		if decimals != 6 {
			t.Fatalf("%s is recorded with %d decimals; both were read from mainnet as 6", mint,
				decimals)
		}
		terms := predicting(DepositMint, mint)
		published, fault := Prediction{}.Terms(terms)
		if fault != nil {
			t.Fatalf("%s: %v", mint, fault)
		}
		if published[DepositMint] != mint || published[DepositDecimals] != "6" {
			t.Fatalf("%s came back as %v", mint, published)
		}
	}
}

// Numbers come back canonical and an absent bound stays absent, so re-publishing the same market is
// the same bytes and a ceiling nobody set is not published as zero.
func TestAMarketsTermsArePublishedCanonically(t *testing.T) {
	terms := predictionTerms()
	terms[DepositDecimals] = " 06 "
	terms[MostDeposit] = "0250000000"
	published, fault := Prediction{}.Terms(terms)
	if fault != nil {
		t.Fatal(fault)
	}
	for name, expected := range map[string]string{
		MarketID:        "POLY-3398287-0",
		EventID:         "POLY-810546",
		SourceProvider:  "polymarket",
		DepositMint:     USDCMint,
		DepositDecimals: "6",
		DepositSymbol:   "USDC",
		LeastDeposit:    "5000000",
		MostDeposit:     "250000000",
	} {
		if published[name] != expected {
			t.Fatalf("%s is %q, expected %q", name, published[name], expected)
		}
	}
	if len(published) != 8 {
		t.Fatalf("eight terms were published, not %d: %v", len(published), published)
	}
	// Absent is absent: no event, no source label, no deposit label and no ceiling.
	bare, fault := Prediction{}.Terms(map[string]string{
		MarketID:        "BISON-1",
		DepositMint:     JupUSDMint,
		DepositDecimals: "6",
	})
	if fault != nil {
		t.Fatal(fault)
	}
	for _, name := range []string{EventID, SourceProvider, DepositSymbol, MostDeposit} {
		if _, present := bare[name]; present {
			t.Fatalf("%s was published as %q although nobody sent it", name, bare[name])
		}
	}
	// The floor is the exception, because the provider has one whether a publisher named it or not.
	if bare[LeastDeposit] != "5000000" {
		t.Fatalf("the floor is %q", bare[LeastDeposit])
	}
}

// The terms the plugin requires are the terms a valid statement always carries, checked through the
// same path the API and the reconciler use.
func TestAMarketsStatementPassesTheWholeCheck(t *testing.T) {
	now := time.Date(2026, 9, 17, 21, 0, 0, 0, time.UTC)
	note, expires, terms, fault := Check(Prediction{},
		"Listed by Jupiter Prediction as \"Lions vs. Bills — Lions\".",
		now.Add(3*time.Hour), now, predictionTerms())
	if fault != nil {
		t.Fatal(fault)
	}
	if note == "" || !expires.After(now) {
		t.Fatalf("note %q, expires %s", note, expires)
	}
	for _, name := range []string{MarketID, DepositMint, DepositDecimals, LeastDeposit} {
		if terms[name] == "" {
			t.Fatalf("%s is not in a checked statement: %v", name, terms)
		}
	}
}

// The identifier rule, which is the phone's `isMarketIdentifier`: a bounded token, and never a URL
// or anything loadable.
func TestAMarketIdentifierIsABoundedToken(t *testing.T) {
	for _, one := range []struct {
		value string
		ok    bool
	}{
		{"POLY-3398287-0", true},
		{"BISON-7Yd2kM-UP", true},
		{"a", true},
		{"kalshi:INXD-26SEP17", true},
		{"market.one_two", true},
		{strings.Repeat("a", 64), true},
		{strings.Repeat("a", 65), false},
		{"", false},
		{"-leading", false},
		{".leading", false},
		{"has space", false},
		{"has/slash", false},
		{"https://example.com", false},
		{"café", false},
		{"POLY-1\n", false},
	} {
		if IsMarketID(one.value) != one.ok {
			t.Fatalf("%q: expected %v", one.value, one.ok)
		}
	}
}

// The kind names the operation at the protocol's own level and the plugin that serves it, and
// neither is a provider's name or anything loadable.
func TestThePredictionKindNamesAnOperationAndAPlugin(t *testing.T) {
	kind := Prediction{}
	if kind.Operation() != "prediction" {
		t.Fatalf("operation %q: it is the protocol's own word, never the provider's",
			kind.Operation())
	}
	if !IsOperation(kind.Operation()) {
		t.Fatalf("operation %q is not a shape the gateway accepts", kind.Operation())
	}
	requirement := kind.Requirement()
	if requirement.PluginID != "jupiter.prediction" || !IsPluginID(requirement.PluginID) {
		t.Fatalf("plugin %q", requirement.PluginID)
	}
	if requirement.MinContract != 1 || requirement.MostContract != 1 {
		t.Fatalf("contract %d..%d: jupiter.prediction declares 1 (docs/wiki/client-plugins.md)",
			requirement.MinContract, requirement.MostContract)
	}
	// Two kinds, two plugins, two operations: a template registers one and cannot be turned into
	// the other by configuration.
	if kind.Operation() == (Swap{}).Operation() ||
		requirement.PluginID == (Swap{}).Requirement().PluginID {
		t.Fatal("the two kinds are not distinguishable")
	}
}
