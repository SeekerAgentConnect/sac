package signals

import (
	"math/big"
	"strconv"
	"strings"
	"unicode/utf16"
)

// Swap is the kind the CopyTrading template publishes: a spot swap, served on the phone by the
// bundled `jupiter.swap` plugin (SEE-93).
//
// The terms are the contract in docs/protocol.md#a-swap-signals-terms-see-93, and the rules are
// `jupiter/SwapTerms.kt`'s, on this side. Two of them are the whole shape of a signal:
//
//   - **An asset is a mint.** "BTC" names a dozen things on Solana and nothing off it, so a term
//     here is an exact base58 32-byte mint address and only that. A symbol, if it is given, is a
//     label the phone shows as the publisher's word beside the mint, never instead of it. There is
//     deliberately no way to publish "buy Bitcoin".
//   - **Direction is the pair, ordered.** The input mint is spent and the output mint is received.
//     There is no side field, because a field that could disagree with the pair eventually would.
//
// What is not here is the amount. A publisher may bound it and must cap the slippage it will have
// its signal acted on with; within that, the amount and the slippage are chosen on each owner's
// own phone and stay there. That is the difference between a signal and an order
// (docs/wiki/copytrading-template.md).
type Swap struct{}

// The term names, spelled once. They are `SwapTermNames` on the phone.
const (
	InputMint       = "input_mint"
	InputDecimals   = "input_decimals"
	InputSymbol     = "input_symbol"
	OutputMint      = "output_mint"
	OutputDecimals  = "output_decimals"
	OutputSymbol    = "output_symbol"
	MaxSlippageBps  = "max_slippage_bps"
	LeastInput      = "least_input"
	MostInput       = "most_input"
	mostSlippageBps = 10_000
	mostDecimals    = 18
	// A label is shown, so it is short and never believed. Counted in UTF-16 units because that is
	// what `String.length` counts on the phone, and a template that accepted a 16-emoji symbol
	// would publish a signal every phone refuses.
	mostSymbolUnits = 16
)

// WrappedSOL is how native SOL is named in a swap: as the mint that actually moves. It is here for
// the examples and the CLI's own help, so nobody has to look it up to publish their first signal.
const WrappedSOL = "So11111111111111111111111111111111111111112"

// Operation is the operation name at the protocol's own level: "swap", never the provider that
// serves it.
func (Swap) Operation() string { return "swap" }

// Requirement is the bundled plugin this kind's proposals are written for, and the plugin-boundary
// contract range it works with. `jupiter.swap` declares contract 1 (SEE-93,
// docs/wiki/client-plugins.md), and a range of exactly that is the honest thing to publish: a
// phone whose plugin is outside it reports the server as incompatible rather than being handed a
// document written against a contract neither side agreed on.
func (Swap) Requirement() Requirement {
	return Requirement{PluginID: "jupiter.swap", MinContract: 1, MostContract: 1}
}

// Terms checks a caller's swap terms and returns them as they will be published.
//
// Numbers come back canonical — "007" is published as "7" — so that the document does not depend
// on how a caller spelled a number, and a retry of the same signal is the same bytes. A term this
// kind does not know is refused rather than carried: the phone ignores an extra term, because a
// publisher may say more than a plugin reads, but a template that minted one would be publishing a
// word nothing will ever read, and the usual cause is a misspelling of one that matters.
func (s Swap) Terms(raw map[string]string) (map[string]string, *Fault) {
	for key := range raw {
		switch key {
		case InputMint, InputDecimals, InputSymbol, OutputMint, OutputDecimals, OutputSymbol,
			MaxSlippageBps, LeastInput, MostInput:
		default:
			return nil, &Fault{Code: "unknown_term", Term: key}
		}
	}
	terms := map[string]string{}

	input, fault := mint(raw, InputMint)
	if fault != nil {
		return nil, fault
	}
	output, fault := mint(raw, OutputMint)
	if fault != nil {
		return nil, fault
	}
	if input == output {
		return nil, &Fault{Code: "one_asset", Term: OutputMint}
	}
	terms[InputMint], terms[OutputMint] = input, output

	for _, name := range []string{InputDecimals, OutputDecimals} {
		value, fault := whole(raw, name, 0, mostDecimals, true)
		if fault != nil {
			return nil, fault
		}
		terms[name] = strconv.FormatUint(value, 10)
	}

	slippage, fault := whole(raw, MaxSlippageBps, 1, mostSlippageBps, true)
	if fault != nil {
		return nil, fault
	}
	terms[MaxSlippageBps] = strconv.FormatUint(slippage, 10)

	// The bounds are optional, and absent is not zero: a signal with no floor has no floor, and one
	// with no ceiling is bounded by the owner's own wallet.
	var least uint64
	if _, given := raw[LeastInput]; given {
		least, fault = whole(raw, LeastInput, 0, MaxRevision, false)
		if fault != nil {
			return nil, fault
		}
		terms[LeastInput] = strconv.FormatUint(least, 10)
	}
	if _, given := raw[MostInput]; given {
		most, fault := whole(raw, MostInput, 0, MaxRevision, false)
		if fault != nil {
			return nil, fault
		}
		if most == 0 || most < least {
			return nil, &Fault{Code: "impossible_amounts", Term: MostInput}
		}
		terms[MostInput] = strconv.FormatUint(most, 10)
	}

	for _, name := range []string{InputSymbol, OutputSymbol} {
		label, given := raw[name]
		if !given {
			continue
		}
		if len(utf16.Encode([]rune(label))) > mostSymbolUnits ||
			!Printable(label, MaxValueTextBytes, false) {
			return nil, &Fault{Code: "bad_symbol", Term: name}
		}
		if label != "" {
			terms[name] = label
		}
	}
	return terms, nil
}

// mint reads a term that has to be an exact base58 32-byte mint address.
func mint(raw map[string]string, name string) (string, *Fault) {
	value := strings.TrimSpace(raw[name])
	if value == "" {
		return "", &Fault{Code: "missing", Term: name}
	}
	if !IsMint(value) {
		return "", &Fault{Code: "not_a_mint", Term: name}
	}
	return value, nil
}

// whole reads a term that has to be a whole number in a range. `required` tells a term that was
// left out apart from one that was given and cannot be read, because "the publisher said nothing"
// and "the publisher said something this template cannot read" are different things to answer
// with.
func whole(raw map[string]string, name string, least, most uint64, required bool) (uint64, *Fault) {
	text, given := raw[name]
	text = strings.TrimSpace(text)
	if !given || text == "" {
		if required {
			return 0, &Fault{Code: "missing", Term: name}
		}
		return 0, nil
	}
	value, err := strconv.ParseUint(text, 10, 64)
	if err != nil || value < least || value > most {
		return 0, &Fault{Code: "bad_number", Term: name}
	}
	return value, nil
}

// base58 is the alphabet Solana writes addresses in.
const base58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

// IsMint is whether text is a Solana address: base58 for exactly 32 bytes. It is the phone's
// `isSolanaAddress` and the sidecar's `isAddress`, so a mint this template publishes is one the
// phone can read back — and a ticker, a name or a typo is refused here rather than broadcast to
// everybody's phone as an asset nobody can resolve.
func IsMint(text string) bool { return len(decodeBase58(text)) == 32 }

// decodeBase58 returns the bytes text decodes to, or nil for an empty string or a character
// outside the alphabet. It is `decodeBase58` in wallet/Base58.kt, including the leading-zero rule:
// each leading "1" is one zero byte, which is how a 32-byte key beginning with zero is written.
func decodeBase58(text string) []byte {
	if text == "" {
		return nil
	}
	value := new(big.Int)
	base := big.NewInt(58)
	for _, character := range text {
		digit := strings.IndexRune(base58, character)
		if digit < 0 {
			return nil
		}
		value.Mul(value, base)
		value.Add(value, big.NewInt(int64(digit)))
	}
	body := value.Bytes()
	leading := 0
	for _, character := range text {
		if character != rune(base58[0]) {
			break
		}
		leading++
	}
	return append(make([]byte, leading), body...)
}
