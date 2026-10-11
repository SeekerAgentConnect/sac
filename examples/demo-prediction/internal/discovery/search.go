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

// ErrIlliquid is a selected market one side of which nobody quotes both ways, or quotes too far
// apart: an owner who entered it might not be able to leave it (see [Liquid]).
var ErrIlliquid = errors.New("that market has no liquidity to get out at")

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
	// An operator unpublished it, so no cycle publishes it again on its own.
	Dismissed bool
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
		state := held[one.market.Provider+"/"+one.market.MarketID]
		previews = append(previews, previewOf(one, state.Status == signals.Open, state.Dismissed))
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
	if err := r.wait(ctx); err != nil {
		return markets.Tracked{}, err
	}
	defer r.unlock()

	now := r.now().UTC().Truncate(time.Second)
	market, err := r.source.Market(ctx, marketID)
	if err != nil {
		return markets.Tracked{}, err
	}
	market = named(market, jupiter.Event{EventID: market.EventID}, r.filters.Source)
	if !market.Tradeable() && !r.filters.Closed {
		return markets.Tracked{}, ErrNotTradeable
	}
	// The operator's name overrides what the filters look for, never whether an owner can leave.
	if r.filters.MostSpread > 0 && !r.filters.Closed && !Liquid(market, r.filters.MostSpread) {
		return markets.Tracked{}, ErrIlliquid
	}
	event, err := r.selectedEvent(ctx, market)
	if err != nil {
		return markets.Tracked{}, err
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

// selectedEvent is the event a selected market belongs to, read from the provider so the proposal
// carries the event's own title, bucket and tags. A market answer names its event and nothing else
// about it: titled by the market alone, a yes-or-no question would be published as "Yes — Yes".
// When the event cannot be read the market stands in for it, as it always used to.
func (r *Reconciler) selectedEvent(ctx context.Context, market jupiter.Market) (jupiter.Event, error) {
	fallback := jupiter.Event{
		EventID: market.EventID,
		Title:   market.Title,
		Active:  true,
		Markets: []jupiter.Market{market},
	}
	if market.EventID == "" {
		return fallback, nil
	}
	event, err := r.source.Event(ctx, market.EventID)
	if err != nil {
		if ctx.Err() != nil {
			return jupiter.Event{}, ctx.Err()
		}
		r.log.Warn("a selected market's event could not be read; the market names the proposal",
			"market", market.MarketID, "event", market.EventID, "error", err)
		return fallback, nil
	}
	event.Active = true
	if len(event.Markets) == 0 {
		event.Markets = []jupiter.Market{market}
	}
	return event, nil
}

// ErrNotPublished is an unpublish asked for a proposal this template is not publishing: one it
// never tracked, or one already withdrawn, expired or replaced by a newer proposal.
var ErrNotPublished = errors.New("that proposal is not on the feed")

// Unpublish withdraws one proposal by its identifier because an operator asked for it, and marks
// its market dismissed so a later cycle does not publish it again on its own. Selecting the market
// by name publishes it again as a new proposal.
//
// It is the operator's counterpart of [Reconciler.Select], and like it waits for a running cycle:
// a withdrawal decided in the middle of a cycle could be undone by that cycle's own conclusions.
func (r *Reconciler) Unpublish(ctx context.Context, proposalID string) (markets.Tracked, error) {
	if err := r.wait(ctx); err != nil {
		return markets.Tracked{}, err
	}
	defer r.unlock()

	tracked, err := r.documents.Markets(ctx)
	if err != nil {
		return markets.Tracked{}, err
	}
	for _, one := range tracked {
		if one.Market.ProposalID != proposalID {
			continue
		}
		if one.Record.Signal.Status != signals.Open {
			break
		}
		now := r.now().UTC().Truncate(time.Second)
		_, withdrawn, err := r.documents.Dismiss(ctx, one.Market.Provider, one.Market.MarketID,
			one.Market.State, now)
		if err != nil {
			return markets.Tracked{}, err
		}
		if withdrawn {
			r.log.Info("a market's proposal is withdrawn by the operator",
				"market", one.Market.MarketID, "event", one.Market.EventID, "signal", proposalID)
			r.wake()
		}
		return r.lookup(ctx, one.Market.Provider, one.Market.MarketID)
	}
	return markets.Tracked{}, ErrNotPublished
}

// undismissed drops the candidates an operator unpublished, counting each under [Dismissed]. A
// dismissed market still has its withdrawn proposal, so nothing else of the cycle asks about it.
func (r *Reconciler) undismissed(
	cycle *markets.Cycle,
	found []candidate,
	held map[string]markets.Tracked,
) []candidate {
	kept := found[:0]
	for _, one := range found {
		row, tracking := held[one.market.Provider+"/"+one.market.MarketID]
		if tracking && row.Market.Dismissed && row.Record.Signal.Status != signals.Open {
			cycle.Reasons[Dismissed]++
			continue
		}
		kept = append(kept, one)
	}
	return kept
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

// standing is what an operator search says about a market this template has tracked.
type standing struct {
	Status    signals.Status
	Dismissed bool
}

func (r *Reconciler) published(ctx context.Context) (map[string]standing, error) {
	tracked, err := r.documents.Markets(ctx)
	if err != nil {
		return nil, err
	}
	held := make(map[string]standing, len(tracked))
	for _, one := range tracked {
		held[one.Market.Provider+"/"+one.Market.MarketID] = standing{
			Status:    one.Record.Signal.Status,
			Dismissed: one.Market.Dismissed,
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

func previewOf(one candidate, published, dismissed bool) Preview {
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
		Dismissed:  dismissed,
	}
	if one.market.CloseTime > 0 {
		shown.CloseAt = time.Unix(one.market.CloseTime, 0).UTC()
	}
	return shown
}
