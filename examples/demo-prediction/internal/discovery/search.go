package discovery

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/demo-prediction/internal/jupiter"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/markets"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// The operator search is bounded so a typed query cannot walk the whole listing and exhaust the
// provider allowance. A cycle still uses the deployment's own page size and page cap.
const (
	searchMostPages   = 4
	searchPageSize    = 25
	searchMostResults = 10
)

// ErrNotTradeable is a selected market the provider would not take an order for. Publishing it
// would be a proposal that fails on every phone.
var ErrNotTradeable = errors.New("that market is not open for orders")

// Preview is one market as an operator search shows it. It is not persisted: searching the listing
// does not create a proposal.
type Preview struct {
	MarketID   string
	EventID    string
	Title      string
	EventTitle string
	Category   string
	Tags       []string
	State      string
	CloseAt    time.Time
	SourceURL  string
	Published  bool
}

// Search walks the provider listing with [query] and returns at most [searchMostResults] markets
// that match. It does not write. Empty page bounds default to one page of twenty-five and are
// capped so a form cannot ask the provider for more than a cycle would.
func (r *Reconciler) Search(ctx context.Context, query Filters) ([]Preview, error) {
	query = boundSearch(query)
	now := r.now().UTC().Truncate(time.Second)
	cycle := markets.Cycle{StartedAt: now, Outcome: markets.OK, Reasons: map[string]int{}}
	found, fault, err := r.collect(ctx, query, &cycle, now, searchMostResults)
	if err != nil {
		return nil, err
	}
	if fault != nil && len(found) == 0 {
		return nil, fault
	}
	held, err := r.published(ctx)
	if err != nil {
		return nil, err
	}
	previews := make([]Preview, 0, len(found))
	for _, one := range found {
		previews = append(previews, previewOf(one, held[one.market.Provider+"/"+one.market.MarketID]))
	}
	return previews, nil
}

// Select publishes one market by the provider's own identifier, through the same store path a
// cycle uses. It does not change the deployment's filters. A later cycle does not withdraw it
// unless the source itself ends the market — absence from the filtered listing is not closure.
//
// It bypasses MostOpen: an operator who named a market asked for that one, not for the next
// soonest-to-close among the current filter. The market is pinned, so no cycle's selection retires
// it, and it becomes its event's one proposal: any other open proposal for the same event is
// withdrawn, because an event holds one slot (SEE-177).
func (r *Reconciler) Select(ctx context.Context, marketID string) (markets.Tracked, error) {
	if !signals.IsMarketID(marketID) {
		return markets.Tracked{}, fmt.Errorf("that is not a market ID")
	}
	if !r.running.TryLock() {
		return markets.Tracked{}, markets.ErrBusy
	}
	defer r.running.Unlock()

	now := r.now().UTC().Truncate(time.Second)
	market, err := r.source.Market(ctx, marketID)
	if err != nil {
		return markets.Tracked{}, err
	}
	market = named(market, jupiter.Event{EventID: market.EventID}, r.filters.Source)
	if !market.Tradeable() && !r.filters.Closed {
		return markets.Tracked{}, ErrNotTradeable
	}
	event := jupiter.Event{
		EventID:   market.EventID,
		Title:     market.Title,
		Active:    true,
		SourceURL: "",
		Markets:   []jupiter.Market{market},
	}
	one := candidate{event: event, market: market, pinned: true}

	tracked, err := r.documents.Markets(ctx)
	if err != nil {
		return markets.Tracked{}, err
	}
	held := make(map[string]markets.Tracked, len(tracked))
	for _, row := range tracked {
		held[row.Market.Provider+"/"+row.Market.MarketID] = row
	}
	name := market.Provider + "/" + market.MarketID
	existing, tracking := held[name]
	cycle := markets.Cycle{StartedAt: now, Outcome: markets.OK, Reasons: map[string]int{}}
	changed := false
	if tracking && existing.Record.Signal.Status == signals.Open {
		moved, err := r.refresh(ctx, &cycle, one, existing, now)
		if err != nil {
			return markets.Tracked{}, err
		}
		changed = moved || !existing.Market.Pinned
	} else {
		published, err := r.discover(ctx, &cycle, one, existing.Market, tracking, now)
		if err != nil {
			return markets.Tracked{}, err
		}
		if !published {
			if cycle.Reasons[Unpublishable] > 0 {
				return markets.Tracked{}, fmt.Errorf("that market cannot be published as a signal")
			}
			return markets.Tracked{}, fmt.Errorf("that market was not published")
		}
		changed = true
	}
	for _, other := range tracked {
		if other.Market.Provider != market.Provider || other.Market.MarketID == market.MarketID ||
			other.Market.EventID == "" || other.Market.EventID != market.EventID ||
			other.Record.Signal.Status != signals.Open {
			continue
		}
		ended, err := r.retire(ctx, &cycle, retirement{row: other, reason: Replaced}, now)
		if err != nil {
			return markets.Tracked{}, err
		}
		changed = changed || ended
	}
	if changed {
		r.wake()
	}
	return r.lookup(ctx, market.Provider, market.MarketID)
}

func (r *Reconciler) lookup(ctx context.Context, provider, marketID string) (markets.Tracked, error) {
	tracked, err := r.documents.Markets(ctx)
	if err != nil {
		return markets.Tracked{}, err
	}
	for _, one := range tracked {
		if one.Market.Provider == provider && one.Market.MarketID == marketID {
			return one, nil
		}
	}
	return markets.Tracked{}, fmt.Errorf("the selected market was not stored")
}

func (r *Reconciler) published(ctx context.Context) (map[string]bool, error) {
	tracked, err := r.documents.Markets(ctx)
	if err != nil {
		return nil, err
	}
	held := make(map[string]bool, len(tracked))
	for _, one := range tracked {
		if one.Record.Signal.Status == signals.Open {
			held[one.Market.Provider+"/"+one.Market.MarketID] = true
		}
	}
	return held, nil
}

func boundSearch(query Filters) Filters {
	if query.PageSize <= 0 || query.PageSize > searchPageSize {
		query.PageSize = searchPageSize
	}
	switch {
	case query.MostPages <= 0:
		query.MostPages = 1
	case query.MostPages > searchMostPages:
		query.MostPages = searchMostPages
	}
	return query
}

func previewOf(one candidate, published bool) Preview {
	shown := Preview{
		MarketID:   one.market.MarketID,
		EventID:    one.market.EventID,
		Title:      one.market.Title,
		EventTitle: one.event.Title,
		Category:   one.event.Category,
		Tags:       one.event.Tags,
		State:      one.market.State(),
		SourceURL:  one.event.SourceURL,
		Published:  published,
	}
	if one.market.CloseTime > 0 {
		shown.CloseAt = time.Unix(one.market.CloseTime, 0).UTC()
	}
	return shown
}
