package discovery

import (
	"context"
	"errors"
	"log/slog"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/demo-prediction/internal/jupiter"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/ids"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/markets"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// Source is the part of the provider a reconciliation uses: the listing, and one market by its own
// identifier. It is an interface so that this package is written against two questions rather than
// against one provider's client — and so that the tests can serve a provider that fails.
type Source interface {
	Events(ctx context.Context, query jupiter.Query) (jupiter.Page, error)
	Market(ctx context.Context, id string) (jupiter.Market, error)
}

// Documents is the part of the store a reconciliation uses.
//
// Each of these writes the market row and the signal in one transaction, because the two are one
// fact: a market this template is tracking *is* a proposal it is publishing, and a state in which
// one exists without the other would be either a market nobody hears about or a proposal nothing
// maintains (publisher-support/store).
type Documents interface {
	// Every market this template is tracking, with the signal published for each.
	Markets(ctx context.Context) ([]markets.Tracked, error)
	// A market seen for the first time — or for the first time since its last proposal was
	// withdrawn: the signal is created and the row is written or replaced.
	Discover(ctx context.Context, market markets.Market, key, request string, signal signals.Signal) (
		signals.Record, error)
	// A market seen again: the row is brought up to date and the statement applied to the signal,
	// which moves the revision only if the document changed.
	Refresh(ctx context.Context, market markets.Market, next signals.Signal, now time.Time) (
		signals.Record, bool, error)
	// A market asked about directly and still there: nothing about the signal changes.
	Checked(ctx context.Context, provider, marketID, state string, at time.Time) error
	// A market the source has ended: the signal is withdrawn at the next revision.
	Withdraw(ctx context.Context, provider, marketID, state string, now time.Time) (
		signals.Record, bool, error)
	// What the cycle did, which replaces the last one and comes back with its number.
	Recorded(ctx context.Context, cycle markets.Cycle) (markets.Cycle, error)
}

// Deposit is the half of every signal that comes from the deployment rather than from the source:
// which token a stake is deposited in, and what this publisher will have its signals acted on with.
//
// It is the same for every market, because it is not a judgement about any of them. The amount
// inside these bounds is each owner's own and is chosen on their phone (docs/security.md).
type Deposit struct {
	Mint   string
	Symbol string
	// In the mint's base units. Zero for the floor means the provider's own minimum, and zero for
	// the ceiling means none at all — bounded in the end by the owner's own balance.
	Least uint64
	Most  uint64
}

// Reconciler keeps this template's proposals in step with the provider's markets.
type Reconciler struct {
	documents Documents
	source    Source
	kind      signals.Kind
	filters   Filters
	deposit   Deposit
	// The operator's own line, published above the generated one in every note. Optional.
	note  string
	log   *slog.Logger
	now   func() time.Time
	newID func() string
	// Asks the drainer for a pass, when a cycle changed anything. Publication is the drainer's
	// (publisher-support/publish): one path to the gateway, one place that decides what a failure was.
	wake func()
	// One cycle at a time.
	running sync.Mutex
}

// Plan is what a [Reconciler] needs.
type Plan struct {
	Documents Documents
	Source    Source
	Kind      signals.Kind
	Filters   Filters
	Deposit   Deposit
	Note      string
	Log       *slog.Logger
	Now       func() time.Time
	NewID     func() string
	Wake      func()
}

// New builds one.
func New(plan Plan) *Reconciler {
	now := plan.Now
	if now == nil {
		now = time.Now
	}
	newID := plan.NewID
	if newID == nil {
		newID = ids.New
	}
	wake := plan.Wake
	if wake == nil {
		wake = func() {}
	}
	return &Reconciler{
		documents: plan.Documents,
		source:    plan.Source,
		kind:      plan.Kind,
		filters:   plan.Filters,
		deposit:   plan.Deposit,
		note:      plan.Note,
		log:       plan.Log,
		now:       now,
		newID:     newID,
		wake:      wake,
	}
}

// Filters are the filters in force, for the startup line and the API's own answer.
func (r *Reconciler) Filters() Filters { return r.filters }

// Run reconciles every [Filters.Every] until ctx is done, starting at once.
//
// The first cycle is deliberately at startup rather than after the first interval: an operator who
// has just changed a filter wants to see what it matches now, and a template that published nothing
// for five minutes after a restart looks broken.
func (r *Reconciler) Run(ctx context.Context) {
	ticker := time.NewTicker(r.filters.Every)
	defer ticker.Stop()
	for {
		cycle, err := r.Pass(ctx)
		switch {
		case errors.Is(err, context.Canceled):
		case errors.Is(err, markets.ErrBusy):
			r.log.Warn("a discovery cycle was still running when the next one was due",
				"every", r.filters.Every.String())
		case err != nil:
			r.log.Error("a discovery cycle stopped early", "error", err)
		default:
			r.log.Info("a discovery cycle finished", "cycle", cycle.Number,
				"outcome", cycle.Outcome, "considered", cycle.Considered,
				"matched", cycle.Matched, "created", cycle.Created, "updated", cycle.Updated,
				"cancelled", cycle.Cancelled, "checked", cycle.Checked, "skipped", cycle.Skipped)
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

// Pass is one cycle: read the listing, publish what matches, and ask about what has stopped
// appearing.
//
// The order is what makes it safe to interrupt. Everything is derived from the source and the store
// — no cursor is kept in memory between cycles — so a cycle that is killed half way leaves the
// store consistent and the next one reaches the same conclusions.
func (r *Reconciler) Pass(ctx context.Context) (markets.Cycle, error) {
	if !r.running.TryLock() {
		return markets.Cycle{}, markets.ErrBusy
	}
	defer r.running.Unlock()

	now := r.now().UTC().Truncate(time.Second)
	cycle := markets.Cycle{StartedAt: now, Outcome: markets.OK, Reasons: map[string]int{}}

	tracked, err := r.documents.Markets(ctx)
	if err != nil {
		return cycle, err
	}
	held := make(map[string]markets.Tracked, len(tracked))
	open := 0
	for _, one := range tracked {
		held[one.Market.Provider+"/"+one.Market.MarketID] = one
		if one.Record.Signal.Status == signals.Open {
			open++
		}
	}

	found, fault, err := r.collect(ctx, r.filters, &cycle, now, 0)
	if err != nil {
		return cycle, err
	}
	if fault != nil {
		r.noted(&cycle, fault)
	}
	cycle.Matched = len(found)
	ordered(found)

	seen := make(map[string]bool, len(found))
	changed := false
	for _, one := range found {
		name := one.market.Provider + "/" + one.market.MarketID
		seen[name] = true
		existing, tracking := held[name]
		// A withdrawal is final, so a market whose last proposal was withdrawn is published again
		// as a new one rather than revived (markets.Market.Generation).
		if tracking && existing.Record.Signal.Status == signals.Open {
			moved, err := r.refresh(ctx, &cycle, one, existing, now)
			if err != nil {
				return cycle, err
			}
			changed = changed || moved
			continue
		}
		if open >= r.filters.MostOpen {
			cycle.Skipped++
			continue
		}
		published, err := r.discover(ctx, &cycle, one, existing.Market, tracking, now)
		if err != nil {
			return cycle, err
		}
		if published {
			changed = true
			open++
		}
	}

	ended, err := r.absent(ctx, &cycle, held, seen, now)
	if err != nil {
		return cycle, err
	}
	changed = changed || ended

	cycle.FinishedAt = r.now().UTC().Truncate(time.Second)
	recorded, err := r.documents.Recorded(ctx, cycle)
	if err != nil {
		return cycle, err
	}
	if changed {
		// Publication is the drainer's, and it happens whether or not this cycle is the thing that
		// asked for it: what is unpublished is in the file (publisher-support/store).
		r.wake()
	}
	return recorded, nil
}

// collect reads the listing, bucket by bucket and page by page, and returns the markets that
// matched [filters]. A cycle uses the deployment's filters; an operator search uses the query's.
//
// It stops at the first failure rather than trying the next bucket. A provider that rate-limited
// one call will rate-limit the next, and a cycle that kept going would turn one refusal into a
// dozen: what was read is used, the cycle is recorded as partial, and the next one starts again.
func (r *Reconciler) collect(ctx context.Context, filters Filters, cycle *markets.Cycle, now time.Time, limit int) (
	[]candidate, *jupiter.Fault, error,
) {
	buckets := filters.Categories
	if len(buckets) == 0 {
		// One walk, with no bucket at all: whatever the provider's default listing is.
		buckets = []string{""}
	}
	found := []candidate{}
	// The same event can arrive twice — two buckets cannot overlap, but a listing that shifts
	// between two calls can put one event on both pages — and a market seen twice must not become
	// two proposals.
	already := map[string]bool{}
	for _, bucket := range buckets {
		for page := 0; page < filters.MostPages; page++ {
			start := page * filters.PageSize
			answer, err := r.source.Events(ctx, jupiter.Query{
				Source:   filters.Source,
				Category: bucket,
				Filter:   filters.Filter,
				Start:    start,
				End:      start + filters.PageSize,
			})
			if err != nil {
				var fault *jupiter.Fault
				if errors.As(err, &fault) {
					return found, fault, nil
				}
				return found, nil, err
			}
			cycle.Pages++
			cycle.Events += len(answer.Events)
			for _, event := range answer.Events {
				for _, market := range event.Markets {
					market = named(market, event, filters.Source)
					name := market.Provider + "/" + market.MarketID
					if already[name] {
						continue
					}
					cycle.Considered++
					if reason := filters.Match(event, market, now); reason != "" {
						cycle.Reasons[reason]++
						continue
					}
					already[name] = true
					found = append(found, candidate{event: event, market: market})
					if limit > 0 && len(found) >= limit {
						return found, nil, nil
					}
				}
			}
			if !answer.HasNext || len(answer.Events) == 0 {
				break
			}
		}
	}
	return found, nil, nil
}

// candidate is a market that matched, with the event it came from: both are needed, because the
// note says where a market was listed and the event is where that is written down.
type candidate struct {
	event  jupiter.Event
	market jupiter.Market
}

// named fills in what a market inside an event leaves implicit. A provider that stops repeating
// itself in nested records must not turn one market into two rows.
func named(market jupiter.Market, event jupiter.Event, source string) jupiter.Market {
	if market.EventID == "" {
		market.EventID = event.EventID
	}
	if market.Provider == "" {
		market.Provider = source
	}
	if market.Provider == "" {
		market.Provider = "unknown"
	}
	return market
}

// discover publishes a market for the first time, or for the first time since its last proposal was
// withdrawn.
func (r *Reconciler) discover(
	ctx context.Context,
	cycle *markets.Cycle,
	one candidate,
	previous markets.Market,
	tracking bool,
	now time.Time,
) (bool, error) {
	// A new proposal's clock starts now, including the one an expiry is counted from when the
	// provider gives the market no close time.
	row := r.row(one, markets.Market{
		FirstSeenAt: now,
		Generation:  1,
		ProposalID:  r.newID(),
	}, now)
	if tracking {
		row.Generation = previous.Generation + 1
	}
	signal, fault := r.statement(one, row, now)
	if fault != nil {
		r.unpublishable(cycle, one, fault)
		return false, nil
	}
	if _, err := r.documents.Discover(ctx, row, row.Key(), signals.Statement(signal),
		signal); err != nil {
		return false, err
	}
	cycle.Created++
	r.log.Info("a market is published", "market", row.MarketID, "event", row.EventID,
		"signal", row.ProposalID, "generation", row.Generation,
		"expires_at", signal.ExpiresAt.Format(time.RFC3339), "source", row.SourceURL)
	return true, nil
}

// refresh brings a tracked market's row and statement up to date. The store publishes nothing when
// the statement has not changed, which is the ordinary case: a market that has not moved is not an
// event, and no phone is woken by a cycle that found the same markets again.
func (r *Reconciler) refresh(
	ctx context.Context,
	cycle *markets.Cycle,
	one candidate,
	existing markets.Tracked,
	now time.Time,
) (bool, error) {
	row := r.row(one, existing.Market, now)
	signal, fault := r.statement(one, row, now)
	if fault != nil {
		r.unpublishable(cycle, one, fault)
		return false, nil
	}
	_, moved, err := r.documents.Refresh(ctx, row, signal, now)
	if err != nil {
		return false, err
	}
	if moved {
		cycle.Updated++
		r.log.Info("a published market has changed", "market", row.MarketID,
			"signal", row.ProposalID, "state", row.State,
			"expires_at", signal.ExpiresAt.Format(time.RFC3339))
	}
	return moved, nil
}

// absent is the other half of a cycle: the tracked markets this walk did not see.
//
// **Absence is not closure.** A market missing from a filtered listing may have closed, or may
// simply have left the filter — stopped trending, moved out of the close-time window, or been
// pushed off the pages this cycle read. So each one is asked about directly, and only the
// provider's own answer ends a proposal: gone, closed, cancelled or settled.
//
// A provider that cannot be reached ends nothing at all. That is the rule this function exists for:
// an outage must never withdraw everybody's proposals, because the markets have not changed — this
// template merely cannot see them.
func (r *Reconciler) absent(
	ctx context.Context,
	cycle *markets.Cycle,
	held map[string]markets.Tracked,
	seen map[string]bool,
	now time.Time,
) (bool, error) {
	missing := []markets.Tracked{}
	for name, one := range held {
		switch {
		case seen[name]:
		case one.Record.Signal.Status != signals.Open:
			// Already withdrawn: there is nothing left to end.
		case !one.Record.Signal.ExpiresAt.After(now):
			// Already expired, so nothing new is executed from it whatever the provider says. Not
			// asking is the difference between a template that keeps calling about last month's
			// markets and one that does not.
		default:
			missing = append(missing, one)
		}
	}
	// Oldest asked-about first, so the checks are a round robin rather than the same few markets
	// every cycle.
	sort.SliceStable(missing, func(first, second int) bool {
		left, right := missing[first].Market, missing[second].Market
		if !left.LastCheckedAt.Equal(right.LastCheckedAt) {
			return left.LastCheckedAt.Before(right.LastCheckedAt)
		}
		return left.MarketID < right.MarketID
	})

	changed := false
	for _, one := range missing {
		if cycle.Checked >= r.filters.MostChecks {
			break
		}
		market, err := r.source.Market(ctx, one.Market.MarketID)
		cycle.Checked++
		if err != nil {
			var fault *jupiter.Fault
			if !errors.As(err, &fault) {
				return changed, err
			}
			if fault.Problem != jupiter.NoSuchMarket {
				// Unreachable, rate limited, or refused: nothing is concluded about this market or
				// any other, and the rest of the checks wait for the next cycle.
				r.noted(cycle, fault)
				r.log.Warn("the provider could not be asked about a tracked market",
					"market", one.Market.MarketID, "problem", string(fault.Problem),
					"temporary", fault.Temporary, "detail", fault.Detail)
				break
			}
			// The provider has no such market any more. That is a closure by any other name.
			ended, err := r.withdraw(ctx, cycle, one.Market, "gone", now)
			if err != nil {
				return changed, err
			}
			changed = changed || ended
			continue
		}
		state := market.State()
		if market.Tradeable() || r.filters.Closed {
			// Still a market, and still one this deployment would publish. What it says is
			// recorded; the proposal is left exactly as it is.
			if err := r.documents.Checked(ctx, one.Market.Provider, one.Market.MarketID, state,
				now); err != nil {
				return changed, err
			}
			continue
		}
		ended, err := r.withdraw(ctx, cycle, one.Market, state, now)
		if err != nil {
			return changed, err
		}
		changed = changed || ended
	}
	return changed, nil
}

func (r *Reconciler) withdraw(
	ctx context.Context,
	cycle *markets.Cycle,
	market markets.Market,
	state string,
	now time.Time,
) (bool, error) {
	_, withdrawn, err := r.documents.Withdraw(ctx, market.Provider, market.MarketID, state, now)
	if err != nil {
		return false, err
	}
	if withdrawn {
		cycle.Cancelled++
		r.log.Info("a market's proposal is withdrawn because the source ended it",
			"market", market.MarketID, "signal", market.ProposalID, "state", state)
	}
	return withdrawn, nil
}

// row is the market as this template will record it: what the provider currently says, with the
// identity and the two stored instants this template keeps.
func (r *Reconciler) row(one candidate, previous markets.Market, now time.Time) markets.Market {
	row := markets.Market{
		Provider:      one.market.Provider,
		MarketID:      one.market.MarketID,
		EventID:       one.market.EventID,
		Title:         fold(one.market.Title, 200),
		State:         one.market.State(),
		SourceURL:     one.event.SourceURL,
		ProposalID:    previous.ProposalID,
		Generation:    previous.Generation,
		FirstSeenAt:   previous.FirstSeenAt,
		LastSeenAt:    now,
		LastCheckedAt: previous.LastCheckedAt,
	}
	if one.market.CloseTime > 0 {
		row.CloseAt = time.Unix(one.market.CloseTime, 0).UTC()
	}
	return row
}

// statement is the signal a market becomes: the terms, the note, and the expiry.
//
// It goes through [signals.Check] like every other statement this module publishes, which is what
// keeps one validator: a market the kind refuses is a market this template does not publish, rather
// than a document the gateway or the phone throws away later.
func (r *Reconciler) statement(one candidate, row markets.Market, now time.Time) (
	signals.Signal, *signals.Fault,
) {
	expires := r.filters.Expiry(one.market, row.FirstSeenAt)
	terms := map[string]string{
		signals.MarketID:        one.market.MarketID,
		signals.DepositMint:     r.deposit.Mint,
		signals.DepositDecimals: strconv.FormatUint(signals.DepositMints[r.deposit.Mint], 10),
	}
	if signals.IsMarketID(one.market.EventID) {
		terms[signals.EventID] = one.market.EventID
	}
	// The venue, when it is short enough to be a label. It is the provider's own word, and the
	// plugin cross-checks it against what the provider says for the market.
	if signals.IsLabel(one.market.Provider) {
		terms[signals.SourceProvider] = one.market.Provider
	}
	if r.deposit.Symbol != "" {
		terms[signals.DepositSymbol] = r.deposit.Symbol
	}
	if r.deposit.Least > 0 {
		terms[signals.LeastDeposit] = strconv.FormatUint(r.deposit.Least, 10)
	}
	if r.deposit.Most > 0 {
		terms[signals.MostDeposit] = strconv.FormatUint(r.deposit.Most, 10)
	}

	written, expiry, checked, fault := signals.Check(r.kind,
		note(r.note, one.event, one.market, expires), expires, now, terms)
	if fault != nil {
		return signals.Signal{}, fault
	}
	return signals.Signal{
		ProposalID: row.ProposalID,
		Status:     signals.Open,
		Operation:  r.kind.Operation(),
		PluginID:   r.kind.Requirement().PluginID,
		CreatedAt:  now,
		UpdatedAt:  now,
		ExpiresAt:  expiry,
		Title:      predictionTitle(one.event, one.market),
		Note:       written,
		Terms:      checked,
	}, nil
}

// predictionTitle is the provider's own question, not an app-added category or venue prefix.
//
// A multi-market event shares one event title ("Fed Decision in October?") and distinguishes
// each binary market with its own title ("25 bps increase"). Both are kept, joined with a
// middle dot, so carousel cards of the same event are not identical. A market that already
// carries the event's question is left as the provider wrote it. An empty side falls back to
// the other. Long event text is shortened first so the distinguishing market is not clipped
// off the 64-byte bound.
func predictionTitle(event jupiter.Event, market jupiter.Market) string {
	eventTitle := fold(event.Title, signals.MaxNameBytes)
	marketTitle := fold(market.Title, signals.MaxNameBytes)
	switch {
	case eventTitle == "":
		return marketTitle
	case marketTitle == "" || marketTitle == eventTitle:
		return eventTitle
	case strings.Contains(eventTitle, marketTitle):
		return eventTitle
	case strings.Contains(marketTitle, eventTitle):
		return marketTitle
	}
	const separator = " · "
	overhead := len(separator) + len(marketTitle)
	if overhead >= signals.MaxNameBytes {
		return marketTitle
	}
	head := fold(event.Title, signals.MaxNameBytes-overhead)
	if head == "" {
		return marketTitle
	}
	return head + separator + marketTitle
}

// Unpublishable is the reason a market that matched every filter still could not be published: the
// kind refused its terms. It is counted separately from the filters' own reasons because it is not
// a filter — it is this template and the phone disagreeing with the provider about something, and
// an operator seeing it should read the log line that names the term.
const Unpublishable = "unpublishable"

func (r *Reconciler) unpublishable(cycle *markets.Cycle, one candidate, fault *signals.Fault) {
	cycle.Reasons[Unpublishable]++
	r.log.Warn("a market matched but cannot be published as a signal",
		"market", one.market.MarketID, "event", one.market.EventID,
		"problem", fault.Code, "term", fault.Term)
}

// noted records a provider failure on the cycle. The first one is kept: it is the one that stopped
// the walk, and the ones after it are usually the same thing said again.
func (r *Reconciler) noted(cycle *markets.Cycle, fault *jupiter.Fault) {
	if cycle.Pages == 0 && cycle.Checked == 0 {
		cycle.Outcome = markets.Failed
	} else if cycle.Outcome == markets.OK {
		cycle.Outcome = markets.Partial
	}
	if cycle.Problem == "" {
		cycle.Problem, cycle.Detail = string(fault.Problem), fault.Detail
	}
}
