package signals

import (
	"strings"
	"testing"
)

const usdc = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

// Every way a swap's terms can be wrong, with the code the caller is told. The list is the contract
// in docs/protocol.md#a-swap-signals-terms-see-93, and the rules are
// plugins/actions/SwapAction.kt's: a template must not publish a document the phone would then
// refuse.
func TestEveryWayASwapsTermsCanBeWrong(t *testing.T) {
	for _, one := range []struct {
		name  string
		terms map[string]string
		code  string
		term  string
	}{
		{"no input mint", without(InputMint), "missing", InputMint},
		{"no output mint", without(OutputMint), "missing", OutputMint},
		{"no input decimals", without(InputDecimals), "missing", InputDecimals},
		{"no output decimals", without(OutputDecimals), "missing", OutputDecimals},
		{"no slippage ceiling", without(MaxSlippageBps), "missing", MaxSlippageBps},
		{"an empty mint", with(InputMint, ""), "missing", InputMint},
		{"a ticker for a mint", with(InputMint, "SOL"), "not_a_mint", InputMint},
		{"a name for a mint", with(InputMint, "Wrapped SOL"), "not_a_mint", InputMint},
		{"a mint with a character outside base58", with(InputMint, strings.Repeat("0", 44)),
			"not_a_mint", InputMint},
		{"a mint that decodes to the wrong length", with(InputMint, "abc"), "not_a_mint",
			InputMint},
		{"the same mint on both sides", with(OutputMint, WrappedSOL), "one_asset", OutputMint},
		{"decimals that are not a number", with(InputDecimals, "nine"), "bad_number",
			InputDecimals},
		{"decimals above what a mint can have", with(InputDecimals, "19"), "bad_number",
			InputDecimals},
		{"negative decimals", with(InputDecimals, "-1"), "bad_number", InputDecimals},
		{"a slippage of zero", with(MaxSlippageBps, "0"), "bad_number", MaxSlippageBps},
		{"a slippage above a whole", with(MaxSlippageBps, "10001"), "bad_number", MaxSlippageBps},
		{"a fractional amount bound", with(LeastInput, "1.5"), "bad_number", LeastInput},
		{"a negative amount bound", with(MostInput, "-1"), "bad_number", MostInput},
		{"a ceiling of zero", with(MostInput, "0"), "impossible_amounts", MostInput},
		{"a floor above the ceiling", withBoth(LeastInput, "1000", MostInput, "999"),
			"impossible_amounts", MostInput},
		{"a label too long to show", with(InputSymbol, strings.Repeat("a", 17)), "bad_symbol",
			InputSymbol},
		{"a term this kind does not know", with("wallet", "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrp"),
			"unknown_term", "wallet"},
		{"a misspelled term", with("input_mints", usdc), "unknown_term", "input_mints"},
	} {
		t.Run(one.name, func(t *testing.T) {
			_, fault := Swap{}.Terms(one.terms)
			switch {
			case fault == nil:
				t.Fatalf("expected %s (%s), got no fault", one.code, one.term)
			case fault.Code != one.code || fault.Term != one.term:
				t.Fatalf("got %s (%s), expected %s (%s)", fault.Code, fault.Term, one.code,
					one.term)
			}
		})
	}
}

// Direction is the pair, ordered: the input mint is spent and the output mint is received, and
// there is no side field at all. A publisher that means the other way round publishes the other
// pair — so a term that could disagree with the pair cannot be expressed.
func TestDirectionIsThePairAndThereIsNoSideField(t *testing.T) {
	for _, name := range []string{"side", "direction", "buy", "sell"} {
		_, fault := Swap{}.Terms(with(name, "buy"))
		if fault == nil || fault.Code != "unknown_term" {
			t.Fatalf("%s: %v", name, fault)
		}
	}
	terms, fault := Swap{}.Terms(swapTerms())
	if fault != nil {
		t.Fatal(fault)
	}
	if terms[InputMint] != WrappedSOL || terms[OutputMint] != usdc {
		t.Fatalf("the pair did not survive: %v", terms)
	}
}

// Numbers come back canonical, so the document does not depend on how a caller spelled one and a
// retry of the same signal is the same bytes.
func TestNumbersArePublishedCanonically(t *testing.T) {
	terms, fault := Swap{}.Terms(map[string]string{
		InputMint:      WrappedSOL,
		InputDecimals:  "09",
		OutputMint:     usdc,
		OutputDecimals: " 6 ",
		MaxSlippageBps: "0050",
		LeastInput:     "00",
		MostInput:      "1000000000",
	})
	if fault != nil {
		t.Fatal(fault)
	}
	for name, expected := range map[string]string{
		InputDecimals:  "9",
		OutputDecimals: "6",
		MaxSlippageBps: "50",
		LeastInput:     "0",
		MostInput:      "1000000000",
	} {
		if terms[name] != expected {
			t.Fatalf("%s is %q, expected %q", name, terms[name], expected)
		}
	}
}

// An absent bound is absent, not zero, and an empty label is left out of the document rather than
// published as an empty term.
func TestAbsentIsAbsent(t *testing.T) {
	terms, fault := Swap{}.Terms(swapTerms())
	if fault != nil {
		t.Fatal(fault)
	}
	for _, name := range []string{LeastInput, MostInput, InputSymbol, OutputSymbol} {
		if _, present := terms[name]; present {
			t.Fatalf("%s was published as %q although the caller did not send it", name,
				terms[name])
		}
	}
	terms, fault = Swap{}.Terms(with(InputSymbol, ""))
	if fault != nil {
		t.Fatal(fault)
	}
	if _, present := terms[InputSymbol]; present {
		t.Fatal("an empty label was published as a term")
	}
}

// A label is at most sixteen characters as the phone counts them, which is UTF-16 units. Counting
// runes here would accept a symbol every phone then refuses.
func TestALabelIsCountedTheWayThePhoneCountsIt(t *testing.T) {
	for _, one := range []struct {
		name   string
		symbol string
		ok     bool
	}{
		{"sixteen ASCII", strings.Repeat("a", 16), true},
		{"seventeen ASCII", strings.Repeat("a", 17), false},
		{"sixteen accented letters", strings.Repeat("é", 16), true},
		{"eight emoji, which are sixteen UTF-16 units", strings.Repeat("🪙", 8), true},
		{"nine emoji, which are eighteen", strings.Repeat("🪙", 9), false},
	} {
		t.Run(one.name, func(t *testing.T) {
			_, fault := Swap{}.Terms(with(InputSymbol, one.symbol))
			if (fault == nil) != one.ok {
				t.Fatalf("%q: %v", one.symbol, fault)
			}
		})
	}
}

// The wrapped-SOL mint is how native SOL is named in a swap, and it has to be a mint like any
// other: this pins the constant the docs and the CLI quote.
func TestTheWrappedSolMintIsAMint(t *testing.T) {
	if !IsMint(WrappedSOL) {
		t.Fatalf("%s is not a 32-byte base58 address", WrappedSOL)
	}
	if len(decodeBase58(WrappedSOL)) != 32 {
		t.Fatal("the wrapped SOL mint does not decode to 32 bytes")
	}
}

// Base58 the way the phone reads it (wallet/Base58.kt), including the leading-zero rule: each
// leading "1" is one zero byte, which is how a key that begins with zero is written.
func TestBase58ReadsWhatThePhoneReads(t *testing.T) {
	for _, one := range []struct {
		text  string
		bytes int
	}{
		{"", 0},
		{"1", 1},
		{strings.Repeat("1", 32), 32},
		{WrappedSOL, 32},
		{usdc, 32},
		{"11111111111111111111111111111111", 32}, // the system program: thirty-two zero bytes
	} {
		if got := len(decodeBase58(one.text)); got != one.bytes {
			t.Fatalf("%q decoded to %d bytes, expected %d", one.text, got, one.bytes)
		}
	}
	for _, text := range []string{"0", "O", "I", "l", "hello world", "abc+def"} {
		if decodeBase58(text) != nil {
			t.Fatalf("%q is not base58", text)
		}
	}
}

// The kind names the operation at the protocol's own level and the plugin that serves it, and
// neither is a provider's name or anything loadable.
func TestTheKindNamesAnOperationAndAPlugin(t *testing.T) {
	kind := Swap{}
	if kind.Operation() != "swap" {
		t.Fatalf("operation %q: it is the protocol's own word, never the provider's",
			kind.Operation())
	}
	requirement := kind.Requirement()
	if requirement.PluginID != "jupiter.swap" || !IsPluginID(requirement.PluginID) {
		t.Fatalf("plugin %q", requirement.PluginID)
	}
	if requirement.MinContract != 1 || requirement.MostContract != 1 {
		t.Fatalf("contract %d..%d: jupiter.swap declares 1 (docs/wiki/client-plugins.md)",
			requirement.MinContract, requirement.MostContract)
	}
}

func with(name, value string) map[string]string {
	terms := swapTerms()
	terms[name] = value
	return terms
}

func withBoth(first, firstValue, second, secondValue string) map[string]string {
	terms := swapTerms()
	terms[first], terms[second] = firstValue, secondValue
	return terms
}

func without(name string) map[string]string {
	terms := swapTerms()
	delete(terms, name)
	return terms
}
