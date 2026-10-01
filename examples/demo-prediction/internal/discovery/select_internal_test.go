package discovery

import (
	"reflect"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/demo-prediction/internal/jupiter"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/markets"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// The filters the ticket reported (SEE-177): two provider buckets, eight keywords, eight slots.
func demo() Filters {
	return Filters{
		Source:       "polymarket",
		Categories:   []string{"crypto", "tech"},
		Keywords:     []string{"solana", "zcash", "seeker", "solana mobile", "artificial intelligence", "openai", "anthropic", "chatgpt"},
		LeastCloseIn: 24 * time.Hour,
		MostCloseIn:  180 * 24 * time.Hour,
		PageSize:     100,
		MostPages:    4,
		MostOpen:     8,
		MostChecks:   5,
		Every:        5 * time.Minute,
	}
}

// question is one event with one market per id, all closing [days] from noon.
func question(eventID, title, category string, days int, ids ...string) []candidate {
	event := jupiter.Event{EventID: eventID, Title: title, Category: category, Active: true}
	found := []candidate{}
	for _, id := range ids {
		event.Markets = append(event.Markets, jupiter.Market{
			MarketID: id, EventID: eventID, Provider: "polymarket", Title: "outcome " + id,
			Status: jupiter.Open, CloseTime: noon.Add(time.Duration(days) * 24 * time.Hour).Unix(),
		})
	}
	for _, market := range event.Markets {
		found = append(found, candidate{event: event, market: market})
	}
	return found
}

func pool(questions ...[]candidate) []candidate {
	found := []candidate{}
	for _, one := range questions {
		found = append(found, one...)
	}
	ordered(found)
	return found
}

// published is what a plan publishes, as market identifiers in plan order.
func published(chosen plan) []string {
	ids := []string{}
	for _, one := range chosen.publish {
		ids = append(ids, one.market.MarketID)
	}
	return ids
}

func coverage(chosen plan) (covered, uncovered map[string]string) {
	covered, uncovered = map[string]string{}, map[string]string{}
	for _, bucket := range chosen.selection.Buckets {
		name := bucket.Kind + ":" + bucket.Name
		if bucket.Uncovered == "" {
			covered[name] = ""
			continue
		}
		uncovered[name] = bucket.Uncovered
	}
	return covered, uncovered
}

// heldFrom turns a plan's publications into the open proposals the next cycle would hold.
func heldFrom(chosen plan, at time.Time) []markets.Tracked {
	held := []markets.Tracked{}
	for _, one := range chosen.publish {
		held = append(held, markets.Tracked{
			Market: markets.Market{Provider: one.market.Provider, MarketID: one.market.MarketID,
				EventID: one.market.EventID, FirstSeenAt: at,
				CloseAt: time.Unix(one.market.CloseTime, 0).UTC()},
			Record: signals.Record{Signal: signals.Signal{Status: signals.Open,
				ExpiresAt: time.Unix(one.market.CloseTime, 0).UTC()}},
		})
	}
	for _, one := range chosen.refresh {
		held = append(held, one.tracked)
	}
	for _, one := range chosen.unseen {
		held = append(held, one)
	}
	return held
}

func TestNormalizeFoldsCaseSpaceAndUnicodeButKeepsMeaning(t *testing.T) {
	for raw, want := range map[string]string{
		"  OpenAI  ":                           "openai",
		"Solana\u00a0\tMobile":                 "solana mobile",
		"\uff2f\uff50\uff45\uff4e\uff21\uff29": "openai",  // full-width letters
		"Cafe\u0301":                           "café",    // a decomposed accent composes
		"Chat\u200bGPT":                        "chatgpt", // nothing invisible survives
		"Fed decision in October?":             "fed decision in october?",
		"$600–$700B":                           "$600–$700b",
	} {
		if got := Normalize(raw); got != want {
			t.Errorf("Normalize(%q) = %q, expected %q", raw, got, want)
		}
	}
	got := Distinct([]string{"OpenAI", " openai", "", "Solana  Mobile", "solana mobile", "Zcash"})
	if !reflect.DeepEqual(got, []string{"openai", "solana mobile", "zcash"}) {
		t.Fatalf("Distinct = %v", got)
	}
}

func TestAnEventIsItsProviderIdentityAndOnlyAConservativeTitleWithoutOne(t *testing.T) {
	market := jupiter.Market{MarketID: "POLY-3350396", EventID: "POLY-798787", Provider: "polymarket"}
	sibling := market
	sibling.MarketID = "POLY-3350393"
	september := jupiter.Event{EventID: "POLY-798787", Title: "OpenAI’s valuation end of September 2026?"}
	if identity(september, market) != identity(september, sibling) {
		t.Fatal("two markets of one event are two events")
	}
	// Without an identifier the title stands in, compared normalized and nothing more.
	anonymous := market
	anonymous.EventID = ""
	other := sibling
	other.EventID = ""
	spaced := jupiter.Event{Title: "  OpenAI’s  valuation end of September 2026? "}
	if identity(jupiter.Event{Title: september.Title}, anonymous) != identity(spaced, other) {
		t.Fatal("the same title, spaced differently, is two events")
	}
	december := jupiter.Event{Title: "OpenAI’s valuation end of December 2026?"}
	if identity(jupiter.Event{Title: september.Title}, anonymous) == identity(december, other) {
		t.Fatal("September and December were merged into one event")
	}
	// Different identifiers are different events, whatever their titles say.
	twin := market
	twin.EventID = "POLY-798788"
	if identity(september, market) == identity(september, twin) {
		t.Fatal("two event identifiers with one title were merged")
	}
	// Neither an identifier nor a title: the market is its own event.
	if got := identity(jupiter.Event{}, anonymous); got != "market:polymarket/POLY-3350396" {
		t.Fatalf("identity %q", got)
	}
}

// The reported screenshot: three outcome markets of one event take one slot, represented by one
// market whose identity is kept.
func TestTheThreeValuationMarketsAreOneEvent(t *testing.T) {
	found := pool(question("POLY-798787", "OpenAI’s valuation end of September 2026?", "tech", 3,
		"POLY-3350396", "POLY-3350393", "POLY-3350399"))
	chosen := demo().choose(found, nil, noon, true)
	if got := published(chosen); !reflect.DeepEqual(got, []string{"POLY-3350393"}) {
		t.Fatalf("published %v", got)
	}
	if chosen.selection.Candidates != 3 || chosen.selection.Events != 1 ||
		chosen.selection.Selected != 1 {
		t.Fatalf("selection %+v", chosen.selection)
	}
}

// Every configured bucket is covered within eight events, although the eight soonest-closing
// markets are all about one keyword — taking the first eight before balancing would have shown six
// OpenAI questions and nothing about Zcash.
func TestEveryBucketIsCoveredWhenEightEventsCan(t *testing.T) {
	found := pool(
		question("E-OPENAI-1", "OpenAI announces GPT-6 by October?", "tech", 2, "M-1"),
		question("E-OPENAI-2", "OpenAI valuation above $1T?", "tech", 3, "M-2a", "M-2b", "M-2c"),
		question("E-OPENAI-3", "OpenAI CEO change?", "tech", 4, "M-3"),
		question("E-OPENAI-4", "OpenAI IPO in 2026?", "tech", 5, "M-4"),
		question("E-OPENAI-5", "OpenAI device launch?", "tech", 6, "M-5"),
		question("E-OPENAI-6", "OpenAI hardware chip?", "tech", 7, "M-6"),
		question("E-OPENAI-7", "OpenAI lawsuit settled?", "tech", 8, "M-7"),
		question("E-OPENAI-8", "OpenAI board vote?", "tech", 9, "M-8"),
		question("E-SEEKER", "Solana Mobile Seeker shipments above 150k?", "crypto", 40, "M-S"),
		question("E-SOL", "Solana above $300 in October?", "crypto", 41, "M-SOL"),
		question("E-ZEC", "Zcash above $100?", "crypto", 42, "M-Z"),
		question("E-ANTHROPIC", "Anthropic raises at $300B?", "tech", 43, "M-A"),
		question("E-CHATGPT", "ChatGPT weekly users above 1B?", "tech", 44, "M-C"),
		question("E-AI", "Artificial intelligence act passes?", "tech", 45, "M-AI"),
	)
	filters := demo()
	chosen := filters.choose(found, nil, noon, true)

	if len(chosen.publish) != 8 || chosen.selection.Selected != 8 {
		t.Fatalf("published %d, selected %d", len(chosen.publish), chosen.selection.Selected)
	}
	covered, uncovered := coverage(chosen)
	if len(uncovered) != 0 || len(covered) != 10 {
		t.Fatalf("covered %v, uncovered %v", covered, uncovered)
	}
	// One slot per event, and the event covering four buckets is in it once.
	events := map[string]int{}
	for _, one := range chosen.publish {
		events[one.market.EventID]++
	}
	for event, count := range events {
		if count != 1 {
			t.Fatalf("%s holds %d slots", event, count)
		}
	}
	if events["E-SEEKER"] != 1 {
		t.Fatalf("the Seeker question is not in the selection: %v", published(chosen))
	}
	// Its coverage is counted against each of its buckets.
	for _, bucket := range chosen.selection.Buckets {
		if bucket.Name == "seeker" || bucket.Name == "solana mobile" {
			if bucket.Selected != 1 || bucket.Eligible != 1 {
				t.Fatalf("%s: %+v", bucket.Name, bucket)
			}
		}
		if bucket.Name == "solana" && (bucket.Eligible != 2 || bucket.Selected < 1) {
			t.Fatalf("solana: %+v", bucket)
		}
	}
	// The same pool is the same choice, in the same order.
	again := filters.choose(found, nil, noon, true)
	if !reflect.DeepEqual(published(again), published(chosen)) {
		t.Fatalf("first %v, then %v", published(chosen), published(again))
	}
	// And the next cycle, holding what this one published, changes nothing.
	next := filters.choose(found, heldFrom(chosen, noon), noon.Add(5*time.Minute), true)
	if len(next.publish) != 0 || len(next.retire) != 0 || len(next.refresh) != 8 {
		t.Fatalf("the next cycle published %v and retired %d", published(next), len(next.retire))
	}
}

// Too few slots for every bucket: the most coverage that fits, a stable choice, the ceiling held,
// and every uncovered bucket says why.
func TestScarceSlotsAndMissingTopicsAreExplained(t *testing.T) {
	found := pool(
		question("E-OPENAI", "OpenAI valuation above $1T?", "tech", 3, "M-O"),
		question("E-SEEKER", "Solana Mobile Seeker shipments above 150k?", "crypto", 40, "M-S"),
		question("E-ANTHROPIC", "Anthropic raises at $300B?", "tech", 43, "M-A"),
		question("E-CHATGPT", "ChatGPT weekly users above 1B?", "tech", 44, "M-C"),
	)
	filters := demo()
	filters.MostOpen = 2
	chosen := filters.choose(found, nil, noon, true)
	if len(chosen.publish) != 2 {
		t.Fatalf("published %v", published(chosen))
	}
	// Seeker covers four buckets; then the soonest of the three tech questions that each add two.
	if got := published(chosen); !reflect.DeepEqual(got, []string{"M-S", "M-O"}) {
		t.Fatalf("published %v", got)
	}
	_, uncovered := coverage(chosen)
	want := map[string]string{
		"keyword:zcash":                   markets.NoCandidate,
		"keyword:artificial intelligence": markets.NoCandidate,
		"keyword:anthropic":               markets.NoRoom,
		"keyword:chatgpt":                 markets.NoRoom,
	}
	if !reflect.DeepEqual(uncovered, want) {
		t.Fatalf("uncovered %v", uncovered)
	}
	if chosen.skipped != 2 {
		t.Fatalf("skipped %d", chosen.skipped)
	}
	// A walk that stopped early says so rather than that nothing exists.
	partial := filters.choose(found, nil, noon, false)
	_, uncovered = coverage(partial)
	if uncovered["keyword:zcash"] != markets.NotReached {
		t.Fatalf("uncovered %v", uncovered)
	}
	// No eligible event at all leaves every bucket uncovered and publishes nothing.
	empty := filters.choose(nil, nil, noon, true)
	if len(empty.publish) != 0 || len(empty.selection.Buckets) != 10 {
		t.Fatalf("an empty pool published %v", published(empty))
	}
}

// Keywords are buckets, not identities: distinct events about one keyword all stay eligible, and
// fill the slots coverage leaves.
func TestDistinctEventsSharingAKeywordFillTheRest(t *testing.T) {
	found := pool(
		question("E-SEP", "OpenAI’s valuation end of September 2026?", "tech", 3, "M-SEP"),
		question("E-DEC", "OpenAI’s valuation end of December 2026?", "tech", 90, "M-DEC"),
		question("E-IPO", "OpenAI IPO in 2026?", "tech", 60, "M-IPO"),
	)
	chosen := demo().choose(found, nil, noon, true)
	if got := published(chosen); len(got) != 3 {
		t.Fatalf("published %v", got)
	}
}

// A new event covering a bucket nothing held covers takes the slot of a held event whose buckets
// are all covered twice.
func TestANewEventCoveringAMissingBucketRebalances(t *testing.T) {
	filters := demo()
	filters.MostOpen = 2
	first := pool(
		question("E-1", "OpenAI IPO in 2026?", "tech", 3, "M-1"),
		question("E-2", "OpenAI device launch?", "tech", 4, "M-2"),
	)
	chosen := filters.choose(first, nil, noon, true)
	held := heldFrom(chosen, noon)

	second := pool(append(first, question("E-3", "Anthropic raises at $300B?", "tech", 50, "M-3")...))
	next := filters.choose(second, held, noon.Add(5*time.Minute), true)
	if got := published(next); !reflect.DeepEqual(got, []string{"M-3"}) {
		t.Fatalf("published %v", got)
	}
	if len(next.retire) != 1 || next.retire[0].reason != Rebalanced ||
		next.retire[0].row.Market.MarketID != "M-2" {
		t.Fatalf("retired %+v", next.retire)
	}
	if next.skipped != 1 || next.selection.Selected != 2 {
		t.Fatalf("skipped %d, selected %d", next.skipped, next.selection.Selected)
	}
	// On a walk that failed half way nothing held is given up.
	partial := filters.choose(second, held, noon.Add(5*time.Minute), false)
	if len(partial.retire) != 0 || len(partial.publish) != 0 {
		t.Fatalf("a partial walk retired %+v and published %v", partial.retire, published(partial))
	}
}

// A held event the walk no longer finds eligible is replaced when an eligible event wants its slot,
// and an expired proposal holds no slot at all.
func TestIneligibleAndExpiredEventsAreReplaced(t *testing.T) {
	filters := demo()
	filters.MostOpen = 1
	old := question("E-OLD", "Fed decision in October?", "economics", 3, "M-OLD")
	held := heldFrom(plan{publish: old}, noon)
	found := pool(question("E-NEW", "OpenAI IPO in 2026?", "tech", 30, "M-NEW"))

	chosen := filters.choose(found, held, noon, true)
	if !reflect.DeepEqual(published(chosen), []string{"M-NEW"}) || len(chosen.retire) != 1 ||
		chosen.retire[0].reason != Replaced {
		t.Fatalf("published %v, retired %+v", published(chosen), chosen.retire)
	}
	// With no eligible event wanting it, it keeps its slot and is asked about directly.
	kept := filters.choose(nil, held, noon, true)
	if len(kept.retire) != 0 || len(kept.unseen) != 1 {
		t.Fatalf("retired %+v, unseen %d", kept.retire, len(kept.unseen))
	}
	// Once expired it is not holding a slot, and nothing withdraws it either.
	expired := filters.choose(found, held, noon.Add(4*24*time.Hour), true)
	if !reflect.DeepEqual(published(expired), []string{"M-NEW"}) || len(expired.retire) != 0 {
		t.Fatalf("published %v, retired %+v", published(expired), expired.retire)
	}
}

// A held market of an eligible event that is itself no longer eligible gives the slot to another
// market of the same event — on a complete walk only.
func TestAnIneligibleRepresentativeIsSwappedForASibling(t *testing.T) {
	both := question("E-1", "OpenAI’s valuation end of September 2026?", "tech", 3, "M-A", "M-B")
	held := heldFrom(plan{publish: both[:1]}, noon)
	found := pool(both[1:])

	chosen := demo().choose(found, held, noon, true)
	if !reflect.DeepEqual(published(chosen), []string{"M-B"}) || len(chosen.retire) != 1 ||
		chosen.retire[0].row.Market.MarketID != "M-A" {
		t.Fatalf("published %v, retired %+v", published(chosen), chosen.retire)
	}
	partial := demo().choose(found, held, noon, false)
	if len(partial.publish) != 0 || len(partial.retire) != 0 || len(partial.unseen) != 1 {
		t.Fatalf("a partial walk published %v and retired %+v", published(partial), partial.retire)
	}
}

// An operator's market keeps its slot through every rebalance.
func TestAPinnedMarketIsNeverRetired(t *testing.T) {
	filters := demo()
	filters.MostOpen = 1
	pinned := heldFrom(plan{publish: question("E-PIN", "Fed decision in October?", "economics", 3,
		"M-PIN")}, noon)
	pinned[0].Market.Pinned = true
	found := pool(question("E-NEW", "OpenAI IPO in 2026?", "tech", 30, "M-NEW"))

	chosen := filters.choose(found, pinned, noon, true)
	if len(chosen.retire) != 0 || len(chosen.publish) != 0 || len(chosen.unseen) != 1 {
		t.Fatalf("published %v, retired %+v", published(chosen), chosen.retire)
	}
}

// Keyword and category values are buckets once each, whatever their spelling.
func TestBucketsAreNormalizedAndDistinct(t *testing.T) {
	filters := Filters{Categories: []string{"Crypto", "crypto "}, Keywords: []string{"OpenAI", "openai", " Solana  Mobile"}}
	names := []string{}
	for _, one := range filters.buckets() {
		names = append(names, one.kind+":"+one.name)
	}
	sort.Strings(names)
	if strings.Join(names, ",") != "category:crypto,keyword:openai,keyword:solana mobile" {
		t.Fatalf("buckets %v", names)
	}
}
