package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/markets"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// The discovery half of the store: the markets the Prediction template is tracking, and what its
// last cycle did (SEE-96, internal/discovery).
//
// Every write here puts the market row and the signal in one transaction, because they are one
// fact. A market this template tracks *is* a proposal it publishes: a state in which the row
// existed without the signal would be a market nobody hears about, and one in which the signal
// existed without the row would be a proposal nothing maintains — and the second is the dangerous
// one, because nothing would ever withdraw it.

// ErrNoMarket is returned when nothing is tracked under that venue and identifier.
var ErrNoMarket = errors.New("no market of that identifier")

// Markets is every market this template is tracking, with the signal published for each, soonest
// to close first.
//
// It is a list rather than a page for the same reason the signals are: what this holds is bounded
// by the template's own [markets.Filters.MostOpen], plus however many have closed since.
func (s *Store) Markets(ctx context.Context) ([]markets.Tracked, error) {
	rows, err := s.reader.QueryContext(ctx, selectTracked+
		` ORDER BY market.close_at_ms, market.market_id`)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	tracked := []markets.Tracked{}
	for rows.Next() {
		one, err := trackedFrom(rows)
		if err != nil {
			return nil, err
		}
		tracked = append(tracked, one)
	}
	return tracked, rows.Err()
}

// Live is how many tracked markets still have a proposal this publisher stands behind. It is what
// the status answer reports and what the reconciler's own ceiling is counted against.
func (s *Store) Live(ctx context.Context) (int, error) {
	var live int
	err := s.reader.QueryRowContext(ctx,
		`SELECT count(*) FROM market
		 JOIN signal ON signal.proposal_id = market.proposal_id
		 WHERE signal.status = ?`, string(signals.Open)).Scan(&live)
	return live, err
}

// Discover stores a market's first signal — or its first since the last one was withdrawn — and the
// row that tracks it.
//
// The key is derived from the market and its generation, so this is safe to call again with the
// same market: the idempotency table answers with the signal the earlier call created, and no
// second proposal exists (see [createIn]).
func (s *Store) Discover(
	ctx context.Context,
	market markets.Market,
	key, request string,
	signal signals.Signal,
) (signals.Record, error) {
	transaction, err := s.writer.BeginTx(ctx, nil)
	if err != nil {
		return signals.Record{}, err
	}
	defer func() { _ = transaction.Rollback() }()

	record, held, err := createIn(ctx, transaction, s.serverID, key, request, signal)
	if err != nil {
		return signals.Record{}, err
	}
	if held {
		// An earlier call with this key already created the signal, so the row is about that one:
		// taking the identity from the record rather than from the caller is what keeps the two
		// from disagreeing.
		market.ProposalID = record.Signal.ProposalID
	}
	if err := trackIn(ctx, transaction, market); err != nil {
		return signals.Record{}, err
	}
	return record, transaction.Commit()
}

// Refresh brings a tracked market's row up to date and applies the statement to its signal.
//
// The row is written either way; the signal moves only if the document changed, which is the
// ordinary case for a cycle that found the same markets again — and the boolean says which
// happened, so a cycle can report what it actually did.
func (s *Store) Refresh(
	ctx context.Context,
	market markets.Market,
	next signals.Signal,
	now time.Time,
) (signals.Record, bool, error) {
	transaction, err := s.writer.BeginTx(ctx, nil)
	if err != nil {
		return signals.Record{}, false, err
	}
	defer func() { _ = transaction.Rollback() }()

	if _, err := transaction.ExecContext(ctx,
		`UPDATE market SET event_id = ?, title = ?, state = ?, close_at_ms = ?, source_url = ?,
		                   last_seen_at_ms = ?
		 WHERE provider = ? AND market_id = ?`,
		market.EventID, market.Title, market.State, optional(market.CloseAt), market.SourceURL,
		milliseconds(now), market.Provider, market.MarketID,
	); err != nil {
		return signals.Record{}, false, err
	}
	record, changed, err := updateIn(ctx, transaction, s.serverID, market.ProposalID, next, now)
	if err != nil {
		return signals.Record{}, false, err
	}
	return record, changed, transaction.Commit()
}

// Checked records that the provider was asked about a market directly and still has it. Nothing
// about the signal changes: the market is there, so the proposal stands.
//
// The instant it writes is what makes the direct checks a round robin — [Markets] reads it back,
// and the reconciler asks about the ones it has left longest (internal/discovery).
func (s *Store) Checked(
	ctx context.Context,
	provider, marketID, state string,
	at time.Time,
) error {
	answer, err := s.writer.ExecContext(ctx,
		`UPDATE market SET state = ?, last_checked_at_ms = ? WHERE provider = ? AND market_id = ?`,
		state, milliseconds(at), provider, marketID)
	if err != nil {
		return err
	}
	return one(answer, provider, marketID)
}

// Withdraw withdraws the proposal for a market the source has ended, and records what the provider
// said about it.
//
// Withdrawing one that is already withdrawn changes nothing and publishes nothing, which is what
// makes this safe to reach twice — a market that closed while a cycle was interrupted is withdrawn
// by the next cycle, and a third cycle does nothing at all.
func (s *Store) Withdraw(
	ctx context.Context,
	provider, marketID, state string,
	now time.Time,
) (signals.Record, bool, error) {
	transaction, err := s.writer.BeginTx(ctx, nil)
	if err != nil {
		return signals.Record{}, false, err
	}
	defer func() { _ = transaction.Rollback() }()

	var proposal string
	err = transaction.QueryRowContext(ctx,
		`SELECT proposal_id FROM market WHERE provider = ? AND market_id = ?`,
		provider, marketID).Scan(&proposal)
	if errors.Is(err, sql.ErrNoRows) {
		return signals.Record{}, false, fmt.Errorf("%w: %s/%s", ErrNoMarket, provider, marketID)
	}
	if err != nil {
		return signals.Record{}, false, err
	}
	record, withdrawn, err := cancelIn(ctx, transaction, s.serverID, proposal, now)
	if err != nil {
		return signals.Record{}, false, err
	}
	if _, err := transaction.ExecContext(ctx,
		`UPDATE market SET state = ?, last_checked_at_ms = ? WHERE provider = ? AND market_id = ?`,
		state, milliseconds(now), provider, marketID,
	); err != nil {
		return signals.Record{}, false, err
	}
	return record, withdrawn, transaction.Commit()
}

// Recorded replaces the record of the last cycle and answers with the cycle's own number.
//
// The number is the store's to mint, like a revision: a reconciler that counted its own cycles
// would start again from one at every restart, and "cycle 1" would stop meaning anything.
func (s *Store) Recorded(ctx context.Context, cycle markets.Cycle) (markets.Cycle, error) {
	transaction, err := s.writer.BeginTx(ctx, nil)
	if err != nil {
		return cycle, err
	}
	defer func() { _ = transaction.Rollback() }()

	var previous int
	err = transaction.QueryRowContext(ctx, `SELECT number FROM discovery WHERE id = 1`).
		Scan(&previous)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return cycle, err
	}
	cycle.Number = previous + 1
	reasons, err := json.Marshal(cycle.Reasons)
	if err != nil {
		return cycle, err
	}
	if _, err := transaction.ExecContext(ctx,
		`INSERT INTO discovery (id, number, started_at_ms, finished_at_ms, outcome, problem,
		                        detail, pages, events, considered, matched, created, updated,
		                        cancelled, checked, skipped, reasons)
		 VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT(id) DO UPDATE SET
		   number = excluded.number, started_at_ms = excluded.started_at_ms,
		   finished_at_ms = excluded.finished_at_ms, outcome = excluded.outcome,
		   problem = excluded.problem, detail = excluded.detail, pages = excluded.pages,
		   events = excluded.events, considered = excluded.considered,
		   matched = excluded.matched, created = excluded.created, updated = excluded.updated,
		   cancelled = excluded.cancelled, checked = excluded.checked,
		   skipped = excluded.skipped, reasons = excluded.reasons`,
		cycle.Number, optional(cycle.StartedAt), optional(cycle.FinishedAt), cycle.Outcome,
		cycle.Problem, cycle.Detail, cycle.Pages, cycle.Events, cycle.Considered, cycle.Matched,
		cycle.Created, cycle.Updated, cycle.Cancelled, cycle.Checked, cycle.Skipped,
		string(reasons),
	); err != nil {
		return cycle, err
	}
	return cycle, transaction.Commit()
}

// Cycle is what the last discovery cycle did, or a zero cycle when none has run yet — which is what
// a template that has just started looks like, and is not an error.
func (s *Store) Cycle(ctx context.Context) (markets.Cycle, error) {
	var (
		cycle             markets.Cycle
		started, finished int64
		reasons           string
	)
	err := s.reader.QueryRowContext(ctx,
		`SELECT number, started_at_ms, finished_at_ms, outcome, problem, detail, pages, events,
		        considered, matched, created, updated, cancelled, checked, skipped, reasons
		 FROM discovery WHERE id = 1`,
	).Scan(&cycle.Number, &started, &finished, &cycle.Outcome, &cycle.Problem, &cycle.Detail,
		&cycle.Pages, &cycle.Events, &cycle.Considered, &cycle.Matched, &cycle.Created,
		&cycle.Updated, &cycle.Cancelled, &cycle.Checked, &cycle.Skipped, &reasons)
	if errors.Is(err, sql.ErrNoRows) {
		return markets.Cycle{Reasons: map[string]int{}}, nil
	}
	if err != nil {
		return markets.Cycle{}, err
	}
	cycle.StartedAt, cycle.FinishedAt = maybe(started), maybe(finished)
	cycle.Reasons = map[string]int{}
	if reasons != "" {
		if err := json.Unmarshal([]byte(reasons), &cycle.Reasons); err != nil {
			return markets.Cycle{}, fmt.Errorf("read the last cycle's reasons: %w", err)
		}
	}
	return cycle, nil
}

// trackIn writes a market row, replacing whatever was there for that venue and identifier. It is
// used by [Store.Discover] alone: a market's identity, its generation and the instant it was first
// seen change together, and only when a new proposal is minted for it.
func trackIn(ctx context.Context, transaction *sql.Tx, market markets.Market) error {
	_, err := transaction.ExecContext(ctx,
		`INSERT INTO market (provider, market_id, event_id, title, state, close_at_ms, source_url,
		                     proposal_id, generation, first_seen_at_ms, last_seen_at_ms,
		                     last_checked_at_ms)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT(provider, market_id) DO UPDATE SET
		   event_id = excluded.event_id, title = excluded.title, state = excluded.state,
		   close_at_ms = excluded.close_at_ms, source_url = excluded.source_url,
		   proposal_id = excluded.proposal_id, generation = excluded.generation,
		   first_seen_at_ms = excluded.first_seen_at_ms,
		   last_seen_at_ms = excluded.last_seen_at_ms,
		   last_checked_at_ms = excluded.last_checked_at_ms`,
		market.Provider, market.MarketID, market.EventID, market.Title, market.State,
		optional(market.CloseAt), market.SourceURL, market.ProposalID, market.Generation,
		optional(market.FirstSeenAt), optional(market.LastSeenAt),
		optional(market.LastCheckedAt))
	return err
}

const selectTracked = `
SELECT market.provider, market.market_id, market.event_id, market.title, market.state,
       market.close_at_ms, market.source_url, market.proposal_id, market.generation,
       market.first_seen_at_ms, market.last_seen_at_ms, market.last_checked_at_ms,
       signal.revision, signal.status, signal.operation, signal.plugin_id, signal.created_at_ms,
       signal.updated_at_ms, signal.expires_at_ms, signal.note, signal.terms, signal.fingerprint,
       signal.confirmed_revision, signal.attempts, signal.due_at_ms, signal.problem, signal.detail
FROM market
JOIN signal ON signal.proposal_id = market.proposal_id`

func trackedFrom(row scanner) (markets.Tracked, error) {
	var (
		tracked                               markets.Tracked
		closeAt, firstSeen, lastSeen, checked int64
		status, terms                         string
		created, updated, expires, due        int64
	)
	if err := row.Scan(
		&tracked.Market.Provider, &tracked.Market.MarketID, &tracked.Market.EventID,
		&tracked.Market.Title, &tracked.Market.State, &closeAt, &tracked.Market.SourceURL,
		&tracked.Market.ProposalID, &tracked.Market.Generation, &firstSeen, &lastSeen, &checked,
		&tracked.Record.Signal.Revision, &status, &tracked.Record.Signal.Operation,
		&tracked.Record.Signal.PluginID, &created, &updated, &expires,
		&tracked.Record.Signal.Note, &terms, &tracked.Record.Signal.Fingerprint,
		&tracked.Record.Publication.ConfirmedRevision, &tracked.Record.Publication.Attempts, &due,
		&tracked.Record.Publication.Problem, &tracked.Record.Publication.Detail,
	); err != nil {
		return markets.Tracked{}, err
	}
	tracked.Market.CloseAt = maybe(closeAt)
	tracked.Market.FirstSeenAt = maybe(firstSeen)
	tracked.Market.LastSeenAt = maybe(lastSeen)
	tracked.Market.LastCheckedAt = maybe(checked)
	// The signal is the market's, so it is read under the row's own proposal ID: the join is on it.
	tracked.Record.Signal.ProposalID = tracked.Market.ProposalID
	tracked.Record.Signal.Status = signals.Status(status)
	tracked.Record.Signal.CreatedAt = instant(created)
	tracked.Record.Signal.UpdatedAt = instant(updated)
	tracked.Record.Signal.ExpiresAt = instant(expires)
	tracked.Record.Publication.DueAt = instant(due)
	if err := json.Unmarshal([]byte(terms), &tracked.Record.Signal.Terms); err != nil {
		return markets.Tracked{}, fmt.Errorf("read the terms of %s: %w",
			tracked.Market.ProposalID, err)
	}
	return tracked, nil
}

// one turns "that market is not tracked" into an error rather than a silent success, because a
// write that changed nothing means the reconciler and the file disagree about what exists.
func one(answer sql.Result, provider, marketID string) error {
	changed, err := answer.RowsAffected()
	if err != nil {
		return err
	}
	if changed == 0 {
		return fmt.Errorf("%w: %s/%s", ErrNoMarket, provider, marketID)
	}
	return nil
}

// optional is [milliseconds] for an instant that may be absent: a market with no close time, or
// one that has never been asked about directly. The zero instant is written as zero rather than as
// its distance from 1970, so a "no close time" row reads back as no close time.
func optional(at time.Time) int64 {
	if at.IsZero() {
		return 0
	}
	return milliseconds(at)
}

func maybe(milliseconds int64) time.Time {
	if milliseconds == 0 {
		return time.Time{}
	}
	return instant(milliseconds)
}
