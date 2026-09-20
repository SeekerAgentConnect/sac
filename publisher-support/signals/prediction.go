package signals

import (
	"strconv"
	"strings"
	"unicode/utf16"
)

// Prediction is the kind the Prediction template publishes: one binary market, served on the phone
// by the bundled `jupiter.prediction` plugin (SEE-94).
//
// The terms are the contract in docs/protocol.md#a-prediction-markets-terms-see-94, and the rules
// are `jupiter/PredictionTerms.kt`'s, on this side. What is striking about them is how little a
// publisher gets to say:
//
//   - **A publisher names a market.** Whether it is open, what the two sides cost, what the rules
//     are and when it settles all come from the provider's own API at the moment the owner looks.
//     Nothing here can stand in for a fact the provider would have given, which is the whole reason
//     a publisher can be a stranger (docs/wiki/jupiter-prediction.md).
//   - **There is no side.** No term says YES or NO, because choosing one is the owner's — and a
//     template that published a side would be publishing a recommendation. The deposit terms bound
//     what a signal may be acted on with; within them, the side and the stake are chosen on each
//     owner's own phone and stay there (docs/wiki/prediction-template.md).
//
// The one place this is stricter than the phone is `deposit_decimals`, which has to agree with the
// mint it is about. A mint's decimals are immutable on Solana, so they are knowable rather than a
// claim — and a publisher that got them wrong would have every phone display every amount off by a
// factor of ten.
type Prediction struct{}

// The term names, spelled once. They are `PredictionTermNames` on the phone.
const (
	MarketID        = "market_id"
	EventID         = "event_id"
	SourceProvider  = "provider"
	DepositMint     = "deposit_mint"
	DepositDecimals = "deposit_decimals"
	DepositSymbol   = "deposit_symbol"
	LeastDeposit    = "least_deposit"
	MostDeposit     = "most_deposit"
)

// The two tokens the provider takes a deposit in: its own dollar token, and USDC. It is a closed
// set rather than a free parameter, because a publisher naming anything else would be naming a
// token the provider will not accept — and the honest moment to say so is when the signal is
// written rather than when somebody's order is refused.
//
// Their decimals are here for the same reason the set is: a mint's decimals cannot change, and both
// of these were read from mainnet on 2026-09-17 (docs/integrations/jupiter.md#prediction-orders).
const (
	JupUSDMint = "JuprjznTrTSp2UFa3ZBUFgwdAmtZCq4MQCwysN55USD"
	USDCMint   = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
)

// DepositMints is that set, with each mint's own decimals.
var DepositMints = map[string]uint64{JupUSDMint: 6, USDCMint: 6}

// DepositLabels is what each of them is usually called. A symbol is a label the phone shows beside
// the mint and never instead of it, so this is a convenience rather than a fact: it saves every
// deployment from typing one, and an owner is still looking at the mint (SEE-94).
var DepositLabels = map[string]string{JupUSDMint: "JupUSD", USDCMint: "USDC"}

// LeastOrderDeposit is the smallest order the provider accepts: five dollars, in the deposit
// token's base units.
//
// It is the provider's rule, not this template's and not the app's, and it is applied here so that
// what the document says is what will actually be enforced: the phone raises a publisher's floor to
// this (`maxOf(least, LEAST_ORDER_DEPOSIT)`), so a signal that named a smaller one would be a
// document that disagrees with every phone reading it.
const LeastOrderDeposit uint64 = 5_000_000

// Operation is the operation name at the protocol's own level: "prediction", never the provider
// that serves it.
func (Prediction) Operation() string { return "prediction" }

// Requirement is the bundled plugin this kind's proposals are written for, and the plugin-boundary
// contract range it works with. `jupiter.prediction` declares contract 1 (SEE-94,
// docs/wiki/client-plugins.md), and a range of exactly that is the honest thing to publish: a phone
// whose plugin is outside it reports the server as incompatible rather than being handed a document
// written against a contract neither side agreed on.
func (Prediction) Requirement() Requirement {
	return Requirement{PluginID: "jupiter.prediction", MinContract: 1, MostContract: 1}
}

// Terms checks a caller's market terms and returns them as they will be published.
//
// Numbers come back canonical and the floor comes back raised, so the document does not depend on
// how a number was spelled and a re-publication of the same market is the same bytes. A term this
// kind does not know is refused rather than carried, for the reason [Swap.Terms] gives: the phone
// ignores an extra term, but a template that minted one would be broadcasting a word nothing will
// ever read, and the usual cause is a misspelling of one that matters.
func (Prediction) Terms(raw map[string]string) (map[string]string, *Fault) {
	for key := range raw {
		switch key {
		case MarketID, EventID, SourceProvider, DepositMint, DepositDecimals, DepositSymbol,
			LeastDeposit, MostDeposit:
		default:
			return nil, &Fault{Code: "unknown_term", Term: key}
		}
	}
	terms := map[string]string{}

	market := strings.TrimSpace(raw[MarketID])
	switch {
	case market == "":
		return nil, &Fault{Code: "missing", Term: MarketID}
	case !IsMarketID(market):
		return nil, &Fault{Code: "not_an_identifier", Term: MarketID}
	}
	terms[MarketID] = market

	// The event is optional, and the plugin cross-checks the one given against the event the
	// provider names for the market — so a signal cannot point at a market inside an event it was
	// not describing.
	if event := strings.TrimSpace(raw[EventID]); event != "" {
		if !IsMarketID(event) {
			return nil, &Fault{Code: "not_an_identifier", Term: EventID}
		}
		terms[EventID] = event
	}
	// The market's own source, cross-checked the same way. It is a short label, not a URL: nothing
	// a publisher writes is ever loaded (docs/security.md).
	if source := strings.TrimSpace(raw[SourceProvider]); source != "" {
		if !IsLabel(source) {
			return nil, &Fault{Code: "bad_symbol", Term: SourceProvider}
		}
		terms[SourceProvider] = source
	}

	deposit := strings.TrimSpace(raw[DepositMint])
	decimals, known := DepositMints[deposit]
	switch {
	case deposit == "":
		return nil, &Fault{Code: "missing", Term: DepositMint}
	case !IsMint(deposit):
		return nil, &Fault{Code: "not_a_mint", Term: DepositMint}
	case !known:
		return nil, &Fault{Code: "unsupported_mint", Term: DepositMint}
	}
	terms[DepositMint] = deposit

	// Required, bounded like every other decimals term, and then held to the mint's own: see the
	// type's comment for why this one rule is stricter here than on the phone.
	given, fault := whole(raw, DepositDecimals, 0, mostDecimals, true)
	if fault != nil {
		return nil, fault
	}
	if given != decimals {
		return nil, &Fault{Code: "bad_decimals", Term: DepositDecimals}
	}
	terms[DepositDecimals] = strconv.FormatUint(decimals, 10)

	if symbol, present := raw[DepositSymbol]; present && symbol != "" {
		if !IsLabel(symbol) {
			return nil, &Fault{Code: "bad_symbol", Term: DepositSymbol}
		}
		terms[DepositSymbol] = symbol
	}

	// The bounds are optional, and absent is not zero: a signal with no ceiling is bounded by the
	// owner's own wallet. The floor, though, is never absent in the end — the provider has one.
	var least uint64
	if _, present := raw[LeastDeposit]; present {
		least, fault = whole(raw, LeastDeposit, 0, MaxRevision, false)
		if fault != nil {
			return nil, fault
		}
	}
	if _, present := raw[MostDeposit]; present {
		most, fault := whole(raw, MostDeposit, 0, MaxRevision, false)
		if fault != nil {
			return nil, fault
		}
		// The phone's own pair of rules, and then the one the provider's minimum adds: a ceiling
		// below five dollars cannot be satisfied by an order the provider would accept, so it is
		// refused here rather than published as a signal nobody can act on.
		if most == 0 || most < least || most < LeastOrderDeposit {
			return nil, &Fault{Code: "impossible_amounts", Term: MostDeposit}
		}
		terms[MostDeposit] = strconv.FormatUint(most, 10)
	}
	if least < LeastOrderDeposit {
		least = LeastOrderDeposit
	}
	terms[LeastDeposit] = strconv.FormatUint(least, 10)
	return terms, nil
}

// marketIDBytes is the longest a market or event identifier may be, which is the phone's own
// pattern read as a length: one character and up to sixty-three more.
const marketIDBytes = 64

// IsMarketID is the shape of a provider's market or event identifier: a bounded token of letters,
// digits and a few separators, and never a URL or anything loadable. It is the phone's
// `isMarketIdentifier`, and `contract_test.go` reads the phone's source to keep the two together.
func IsMarketID(value string) bool {
	if value == "" || len(value) > marketIDBytes {
		return false
	}
	for position, character := range value {
		switch {
		case character >= 'a' && character <= 'z',
			character >= 'A' && character <= 'Z',
			character >= '0' && character <= '9':
		case position > 0 && (character == '.' || character == '_' || character == ':' ||
			character == '-'):
		default:
			return false
		}
	}
	return true
}

// IsLabel is a short piece of text a person is shown beside something the phone established for
// itself: at most sixteen UTF-16 units, because that is what `String.length` counts on the phone,
// and printable on one line. A template that accepted a sixteen-emoji symbol would publish a signal
// every phone refuses.
//
// It is exported because the reconciler decides whether to publish a label it did not write — the
// provider's own name for a venue — and a term it cannot publish should be left out rather than
// cost the whole market (internal/discovery).
func IsLabel(text string) bool {
	return len(utf16.Encode([]rune(text))) <= mostSymbolUnits &&
		Printable(text, MaxValueTextBytes, false)
}
