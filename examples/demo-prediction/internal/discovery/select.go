package discovery

// Which events hold the feed's slots (SEE-177, docs/wiki/prediction-template.md#selection).
//
// A provider's event is a question with several outcome markets — "OpenAI's valuation end of
// September 2026?" lists one market per valuation range. Each market is a different thing to buy,
// but they are one question, and a feed of eight slots that spent three of them on one question
// would be a feed of six. So a cycle chooses *events*, at most one proposal per event, and only then
// a market to represent each one.
//
// What it chooses for is coverage. The categories and keywords an operator configured are buckets:
// a feed asked for "solana, openai, anthropic" should show something about each of them before it
// shows a second thing about any of them. The selection here is a greedy maximum coverage over the
// whole candidate pool the walk read — never the first eight markets the provider happened to list —
// with the events already published preferred whenever the choice is otherwise even, so a cycle that
// finds the same markets keeps the same cards.

import (
	"sort"
	"strings"
	"time"

	"golang.org/x/text/unicode/norm"

	"github.com/BrRenat/SeekerAgentWallet/demo-prediction/internal/jupiter"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/markets"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// The two kinds of bucket.
const (
	CategoryBucket = "category"
	KeywordBucket  = "keyword"
)

// The reasons a proposal is withdrawn by the selection rather than by the source. They are counted
// in [markets.Selection.Retired] and named in the log line for each one.
const (
	// A second open proposal for an event that already holds one.
	DuplicateEvent = "duplicate_event"
	// An event whose every bucket another held event also covers, dropped for an event that covers
	// a bucket nothing held did.
	Rebalanced = "rebalanced"
	// An event the walk no longer finds eligible, or a market of an event that is no longer its
	// eligible representative, when an eligible one wants the slot.
	Replaced = "replaced"
	// More events than the ceiling, which only a lowered ceiling can leave behind.
	OverCeiling = "over_ceiling"
)

// Normalize is the one form a keyword, a category or an event title is compared in: Unicode
// compatibility-composed (so a full-width or decomposed letter is the letter), lower case, one space
// between words, and nothing invisible. It does not strip anything a person can read: dates, prices
// and qualifiers stay, because "Fed decision in October?" and "Fed decision in December?" are two
// questions.
func Normalize(text string) string {
	return strings.ToLower(fold(norm.NFKC.String(text), 1<<16))
}

// Distinct is a configured list normalized, without empty or repeated entries, in its first order.
func Distinct(items []string) []string {
	if items == nil {
		return nil
	}
	seen := map[string]bool{}
	kept := make([]string, 0, len(items))
	for _, one := range items {
		value := Normalize(one)
		if value == "" || seen[value] {
			continue
		}
		seen[value] = true
		kept = append(kept, value)
	}
	return kept
}

// bucket is one configured category or keyword.
type bucket struct {
	kind string
	name string
}

// buckets are the configured categories and then the configured keywords, normalized and distinct.
func (f Filters) buckets() []bucket {
	seen := map[bucket]bool{}
	all := []bucket{}
	add := func(kind string, values []string) {
		for _, value := range Distinct(values) {
			one := bucket{kind: kind, name: value}
			if !seen[one] {
				seen[one] = true
				all = append(all, one)
			}
		}
	}
	add(CategoryBucket, f.Categories)
	add(KeywordBucket, f.Keywords)
	return all
}

// covers is whether a market of an event falls in a bucket, by the same rules [Filters.Match] uses:
// the event's own category, or a keyword in its text.
func (b bucket) covers(event jupiter.Event, market jupiter.Market) bool {
	if b.kind == CategoryBucket {
		// "all" is the provider's word for every bucket.
		return b.name == "all" || Normalize(event.Category) == b.name
	}
	return strings.Contains(haystack(event, market), b.name)
}

// identity is the event a market belongs to, as the selection groups it: the venue and the
// provider's own event identifier. Without an identifier the event's normalized title stands in —
// conservatively, so only the same words name the same event — and without either the market is an
// event of its own.
func identity(event jupiter.Event, market jupiter.Market) string {
	if id := strings.TrimSpace(market.EventID); id != "" {
		return "event:" + market.Provider + "/" + id
	}
	if title := Normalize(event.Title); title != "" {
		return "title:" + market.Provider + "/" + title
	}
	return "market:" + market.Provider + "/" + market.MarketID
}

// heldIdentity is [identity] for a market already tracked: the event the walk found it in, which is
// the provider's current answer, or else its stored event identifier, or else itself.
func heldIdentity(row markets.Market, found map[string]string) string {
	if key, ok := found[row.Provider+"/"+row.MarketID]; ok {
		return key
	}
	if id := strings.TrimSpace(row.EventID); id != "" {
		return "event:" + row.Provider + "/" + id
	}
	return "market:" + row.Provider + "/" + row.MarketID
}

// group is one event: its eligible markets in publication order, and the buckets it covers.
type group struct {
	key     string
	markets []candidate
	covers  map[bucket]bool
}

// representative is the market an event is published as when it holds no proposal yet: the soonest
// to close, then the lowest identifier — the same every cycle, whatever order the listing was in.
func (g *group) representative() candidate { return g.markets[0] }

// closes is the event's soonest close, or zero when none of its markets has one.
func (g *group) closes() int64 { return g.markets[0].market.CloseTime }

// before is the stable order of events: the soonest to close first, as markets always were, and the
// identity as the tie-break.
func before(left, right *group) bool {
	if left.closes() != right.closes() {
		if left.closes() == 0 || right.closes() == 0 {
			return right.closes() == 0
		}
		return left.closes() < right.closes()
	}
	return left.key < right.key
}

// grouped is the walk's candidates as events, in stable order, and which event each market is in.
func grouped(found []candidate, configured []bucket) ([]*group, map[string]string) {
	byKey := map[string]*group{}
	byMarket := map[string]string{}
	events := []*group{}
	for _, one := range found {
		key := identity(one.event, one.market)
		byMarket[one.market.Provider+"/"+one.market.MarketID] = key
		held, ok := byKey[key]
		if !ok {
			held = &group{key: key, covers: map[bucket]bool{}}
			byKey[key] = held
			events = append(events, held)
		}
		held.markets = append(held.markets, one)
		for _, each := range configured {
			if each.covers(one.event, one.market) {
				held.covers[each] = true
			}
		}
	}
	for _, event := range events {
		ordered(event.markets)
	}
	sort.SliceStable(events, func(first, second int) bool {
		return before(events[first], events[second])
	})
	return events, byMarket
}

// pick is one event holding a slot: the proposal it already has, when it has one, and its group
// when the walk found it eligible.
type pick struct {
	key    string
	group  *group
	holder *markets.Tracked
}

func (p *pick) incumbent() bool { return p.holder != nil }
func (p *pick) pinned() bool    { return p.holder != nil && p.holder.Market.Pinned }

// retirement is a held proposal the selection withdraws, and why.
type retirement struct {
	row    markets.Tracked
	reason string
}

// plan is what a cycle does with what it found and what it holds.
type plan struct {
	// Held proposals whose market the walk found: brought up to date.
	refresh []refreshing
	// Events taking a slot with a market that holds no open proposal.
	publish []candidate
	// Held proposals the walk did not find, which keep their slot: asked about directly.
	unseen []markets.Tracked
	// Held proposals the selection withdraws.
	retire []retirement
	// Eligible events left without a slot.
	skipped   int
	selection markets.Selection
}

type refreshing struct {
	one     candidate
	tracked markets.Tracked
}

// choose decides which events hold the feed's slots and which market represents each.
//
// [complete] is whether the walk read every page it meant to. An incomplete walk is a provider that
// failed half way, and nothing is concluded from what it did not see: no held proposal is replaced,
// rebalanced or exchanged for a sibling, only a second proposal for one event — which the store
// alone proves — is withdrawn.
func (f Filters) choose(found []candidate, held []markets.Tracked, now time.Time, complete bool) plan {
	configured := f.buckets()
	events, byMarket := grouped(found, configured)
	eligible := map[string]*group{}
	for _, event := range events {
		eligible[event.key] = event
	}
	candidates := map[string]candidate{}
	for _, one := range found {
		candidates[one.market.Provider+"/"+one.market.MarketID] = one
	}
	result := plan{selection: markets.Selection{
		Candidates: len(found),
		Events:     len(events),
		Retired:    map[string]int{},
	}}
	retire := func(row markets.Tracked, reason string) {
		result.retire = append(result.retire, retirement{row: row, reason: reason})
		result.selection.Retired[reason]++
	}

	// The proposals that count against the ceiling: open and not yet expired. An expired one is
	// not actionable on any phone, so it is not holding anybody's slot.
	byEvent := map[string][]markets.Tracked{}
	keys := []string{}
	for _, row := range held {
		if row.Record.Signal.Status != signals.Open || !row.Record.Signal.ExpiresAt.After(now) {
			continue
		}
		key := heldIdentity(row.Market, byMarket)
		if _, seen := byEvent[key]; !seen {
			keys = append(keys, key)
		}
		byEvent[key] = append(byEvent[key], row)
	}
	sort.Strings(keys)

	// One proposal per event. The one kept is the operator's, then one the walk still finds
	// eligible, then the oldest — so the card a phone already shows is the card it keeps.
	var pinned, current, stale []*pick
	for _, key := range keys {
		rows := byEvent[key]
		sort.SliceStable(rows, func(first, second int) bool {
			left, right := rows[first], rows[second]
			_, leftFound := candidates[left.Market.Provider+"/"+left.Market.MarketID]
			_, rightFound := candidates[right.Market.Provider+"/"+right.Market.MarketID]
			switch {
			case left.Market.Pinned != right.Market.Pinned:
				return left.Market.Pinned
			case leftFound != rightFound:
				return leftFound
			case !left.Market.FirstSeenAt.Equal(right.Market.FirstSeenAt):
				return left.Market.FirstSeenAt.Before(right.Market.FirstSeenAt)
			default:
				return left.Market.MarketID < right.Market.MarketID
			}
		})
		for _, duplicate := range rows[1:] {
			retire(duplicate, DuplicateEvent)
		}
		holder := rows[0]
		one := &pick{key: key, group: eligible[key], holder: &holder}
		switch {
		case one.pinned():
			pinned = append(pinned, one)
		case one.group != nil:
			current = append(current, one)
		default:
			stale = append(stale, one)
		}
	}

	capacity := f.MostOpen
	chosen := []*pick{}
	// Events holding a slot, and events that gave one up this cycle and are not chosen again.
	taken, dropped := map[string]bool{}, map[string]bool{}
	counts := map[bucket]int{}
	add := func(one *pick) {
		chosen = append(chosen, one)
		taken[one.key] = true
		if one.group != nil {
			for each := range one.group.covers {
				counts[each]++
			}
		}
	}
	drop := func(one *pick) {
		for at, each := range chosen {
			if each == one {
				chosen = append(chosen[:at], chosen[at+1:]...)
				break
			}
		}
		taken[one.key], dropped[one.key] = false, true
		if one.group != nil {
			for each := range one.group.covers {
				counts[each]--
			}
		}
	}
	gain := func(event *group) int {
		missing := 0
		for each := range event.covers {
			if counts[each] == 0 {
				missing++
			}
		}
		return missing
	}

	// An operator's choice is held whatever the ceiling; a walk that failed half way concludes
	// nothing about what it did not read.
	for _, one := range pinned {
		add(one)
	}
	if !complete {
		for _, one := range stale {
			add(one)
		}
		stale = nil
	}

	// The events already held and still eligible keep their slots — all of them, unless the ceiling
	// was lowered, and then the ones covering the most come first.
	sort.SliceStable(current, func(first, second int) bool {
		return before(current[first].group, current[second].group)
	})
	for len(current) > 0 {
		at := 0
		for index, one := range current {
			if gain(one.group) > gain(current[at].group) {
				at = index
			}
		}
		one := current[at]
		current = append(current[:at], current[at+1:]...)
		if len(chosen) >= capacity && complete {
			retire(*one.holder, OverCeiling)
			continue
		}
		add(one)
	}

	// Coverage: the event covering the most buckets nothing chosen covers yet, until every bucket
	// any eligible event covers is covered or the slots run out. With no slot left, an event whose
	// every bucket another chosen event also covers gives its slot up — which strictly widens the
	// coverage, so this ends, and running it every cycle is what makes the next cycle's answer the
	// same.
	fresh := func() []*group {
		left := []*group{}
		for _, event := range events {
			if !taken[event.key] && !dropped[event.key] && byEvent[event.key] == nil {
				left = append(left, event)
			}
		}
		return left
	}
	for {
		var best *group
		for _, event := range fresh() {
			if gain(event) > 0 && (best == nil || gain(event) > gain(best)) {
				best = event
			}
		}
		if best == nil {
			break
		}
		if len(chosen) >= capacity {
			if !complete {
				break
			}
			victim := redundant(chosen, counts)
			if victim == nil {
				break
			}
			drop(victim)
			if victim.incumbent() {
				retire(*victim.holder, Rebalanced)
			}
		}
		add(&pick{key: best.key, group: best})
	}

	// The rest of the slots go to further distinct events, the ones whose buckets are least
	// represented first. Coverage is not a one-per-bucket cap.
	for len(chosen) < capacity {
		var best *group
		bestScore := 0
		for _, event := range fresh() {
			score := least(event, counts)
			if best == nil || score < bestScore {
				best, bestScore = event, score
			}
		}
		if best == nil {
			break
		}
		add(&pick{key: best.key, group: best})
	}

	// A held event the walk no longer finds eligible keeps its slot only if no eligible event
	// wanted it.
	sort.SliceStable(stale, func(first, second int) bool {
		left, right := stale[first].holder.Market, stale[second].holder.Market
		if !left.CloseAt.Equal(right.CloseAt) {
			return left.CloseAt.Before(right.CloseAt)
		}
		return stale[first].key < stale[second].key
	})
	for _, one := range stale {
		if len(chosen) >= capacity {
			retire(*one.holder, Replaced)
			continue
		}
		add(one)
	}

	// What each chosen event does this cycle.
	for _, one := range chosen {
		if one.holder == nil {
			result.publish = append(result.publish, one.group.representative())
			continue
		}
		if found, ok := candidates[one.holder.Market.Provider+"/"+one.holder.Market.MarketID]; ok {
			result.refresh = append(result.refresh, refreshing{one: found, tracked: *one.holder})
			continue
		}
		if one.group != nil && complete && !one.pinned() {
			// The event is still eligible but this market of it is not — it closes too soon, or
			// the provider stopped taking orders on it — so another of its markets takes the slot.
			retire(*one.holder, Replaced)
			result.publish = append(result.publish, one.group.representative())
			continue
		}
		result.unseen = append(result.unseen, *one.holder)
	}
	for _, event := range events {
		if !taken[event.key] {
			result.skipped++
		}
	}

	result.selection.Selected = len(chosen)
	for _, each := range configured {
		reported := markets.Bucket{Kind: each.kind, Name: each.name, Selected: counts[each]}
		for _, event := range events {
			if event.covers[each] {
				reported.Eligible++
			}
		}
		switch {
		case reported.Selected > 0:
		case reported.Eligible > 0:
			reported.Uncovered = markets.NoRoom
		case complete:
			reported.Uncovered = markets.NoCandidate
		default:
			reported.Uncovered = markets.NotReached
		}
		result.selection.Buckets = append(result.selection.Buckets, reported)
	}
	return result
}

// redundant is a chosen event that can give its slot up without uncovering anything: not an
// operator's, and every bucket it covers covered by another chosen event too. The one covering the
// fewest buckets goes first, then one chosen this cycle over one already held, then the latest to
// close.
func redundant(chosen []*pick, counts map[bucket]int) *pick {
	var victim *pick
	for _, one := range chosen {
		if one.pinned() || one.group == nil {
			continue
		}
		spare := true
		for each := range one.group.covers {
			if counts[each] < 2 {
				spare = false
				break
			}
		}
		if !spare {
			continue
		}
		if victim == nil || worse(one, victim) {
			victim = one
		}
	}
	return victim
}

func worse(left, right *pick) bool {
	switch {
	case len(left.group.covers) != len(right.group.covers):
		return len(left.group.covers) < len(right.group.covers)
	case left.incumbent() != right.incumbent():
		return !left.incumbent()
	default:
		return before(right.group, left.group)
	}
}

// least is how represented an event's least represented bucket already is: the lower, the more the
// event adds to the feed's variety. An event in no bucket at all — a deployment that configured
// none — scores zero, and the stable order decides.
func least(event *group, counts map[bucket]int) int {
	score := -1
	for each := range event.covers {
		if score < 0 || counts[each] < score {
			score = counts[each]
		}
	}
	if score < 0 {
		return 0
	}
	return score
}
