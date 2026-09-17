package store

import (
	"context"
	"database/sql"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/discovery"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/signals"
)

// The store is what a reconciliation needs, said at compile time rather than discovered at run
// time: the reconciler is written against an interface, and this is the thing that has to satisfy
// it (internal/discovery).
var _ discovery.Documents = (*Store)(nil)

func market() discovery.Market {
	return discovery.Market{
		Provider:    "polymarket",
		MarketID:    "POLY-2589813",
		EventID:     "POLY-606422",
		Title:       "Fed Decision in October? — 25 bps increase",
		State:       "open",
		CloseAt:     now.Add(72 * time.Hour),
		SourceURL:   "https://jup.ag/prediction/fed-decision-in-october",
		ProposalID:  "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
		Generation:  1,
		FirstSeenAt: now,
		LastSeenAt:  now,
	}
}

func prediction(id string, expires time.Time) signals.Signal {
	return signals.Signal{
		ProposalID: id,
		CreatedAt:  now,
		UpdatedAt:  now,
		ExpiresAt:  expires,
		Note:       "Listed on Jupiter Prediction as \"Fed Decision in October? — 25 bps increase\".",
		Terms: map[string]string{
			signals.MarketID:        "POLY-2589813",
			signals.EventID:         "POLY-606422",
			signals.SourceProvider:  "polymarket",
			signals.DepositMint:     signals.USDCMint,
			signals.DepositDecimals: "6",
			signals.LeastDeposit:    "5000000",
		},
		Operation: "prediction",
		PluginID:  "jupiter.prediction",
	}
}

func discovered(t *testing.T, documents *Store) (discovery.Market, signals.Record) {
	t.Helper()
	row := market()
	signal := prediction(row.ProposalID, now.Add(72*time.Hour))
	record, err := documents.Discover(context.Background(), row, row.Key(),
		signals.Statement(signal), signal)
	if err != nil {
		t.Fatal(err)
	}
	return row, record
}

// A market and the signal published for it are written together, and read back together. The row
// is what makes a market one proposal: it is where the identity, the generation and the instant it
// was first seen live.
func TestAMarketAndItsSignalAreOneWrite(t *testing.T) {
	documents := opened(t)
	ctx := context.Background()
	row, record := discovered(t, documents)

	if record.Signal.Revision != 1 || record.Signal.Status != signals.Open {
		t.Fatalf("the signal is at revision %d, %s", record.Signal.Revision,
			record.Signal.Status)
	}
	tracked, err := documents.Markets(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(tracked) != 1 {
		t.Fatalf("%d markets", len(tracked))
	}
	held := tracked[0]
	switch {
	case held.Market.MarketID != row.MarketID || held.Market.Provider != row.Provider:
		t.Fatalf("the row is %+v", held.Market)
	case held.Market.ProposalID != record.Signal.ProposalID:
		t.Fatalf("the row names %s and the signal is %s", held.Market.ProposalID,
			record.Signal.ProposalID)
	case held.Market.Generation != 1:
		t.Fatalf("generation %d", held.Market.Generation)
	case !held.Market.CloseAt.Equal(row.CloseAt):
		t.Fatalf("close time %s", held.Market.CloseAt)
	case !held.Market.FirstSeenAt.Equal(now):
		t.Fatalf("first seen %s", held.Market.FirstSeenAt)
	case !held.Market.LastCheckedAt.IsZero():
		t.Fatalf("it has never been asked about directly, and reads as %s",
			held.Market.LastCheckedAt)
	case held.Market.SourceURL != row.SourceURL:
		t.Fatalf("source %q", held.Market.SourceURL)
	case held.Record.Signal.Terms[signals.MarketID] != row.MarketID:
		t.Fatalf("the signal's terms are %v", held.Record.Signal.Terms)
	case held.Record.Publication.ConfirmedRevision != 0:
		t.Fatal("a market's first signal is not published yet")
	}
	live, err := documents.Live(ctx)
	if err != nil || live != 1 {
		t.Fatalf("%d live markets (%v)", live, err)
	}
}

// The same market discovered twice is one proposal. The key is derived from the market rather than
// minted, so a cycle that is interrupted after reading the listing and run again cannot publish the
// same market twice.
func TestTheSameMarketDiscoveredTwiceIsOneProposal(t *testing.T) {
	documents := opened(t)
	ctx := context.Background()
	row, first := discovered(t, documents)

	// A second cycle, with its own fresh identity for the signal — which is what a reconciler
	// would mint — and the same derived key.
	again := row
	again.ProposalID = "9f8e7d6c-5b4a-4321-8f0e-1d2c3b4a5968"
	signal := prediction(again.ProposalID, now.Add(72*time.Hour))
	second, err := documents.Discover(ctx, again, again.Key(), signals.Statement(signal), signal)
	if err != nil {
		t.Fatal(err)
	}
	if second.Signal.ProposalID != first.Signal.ProposalID {
		t.Fatalf("two proposals for one market: %s and %s", first.Signal.ProposalID,
			second.Signal.ProposalID)
	}
	if second.Signal.Revision != 1 {
		t.Fatalf("the signal moved to revision %d without its content changing",
			second.Signal.Revision)
	}
	tracked, err := documents.Markets(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(tracked) != 1 || tracked[0].Market.ProposalID != first.Signal.ProposalID {
		t.Fatalf("the rows are %+v", tracked)
	}
	signalled, err := documents.Signals(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(signalled) != 1 {
		t.Fatalf("%d signals for one market", len(signalled))
	}
}

// One market is one live proposal, and the schema says so: the row's proposal is unique, so a bug
// that tried to publish two signals for one market fails a write rather than waking every
// subscriber twice.
func TestOneMarketCannotBeTwoProposals(t *testing.T) {
	documents := opened(t)
	ctx := context.Background()
	row, _ := discovered(t, documents)

	// A second market row pointing at the same signal: a different market, the same proposal.
	second := row
	second.MarketID = "POLY-2589812"
	transaction, err := documents.writer.BeginTx(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = transaction.Rollback() }()
	if err := trackIn(ctx, transaction, second); err == nil {
		t.Fatal("two markets were written against one proposal; the UNIQUE index is gone")
	}
}

// A market seen again brings the row up to date and publishes nothing, because the document has not
// changed. That is the ordinary case — a cycle every five minutes finding the same markets — and it
// is why no phone is woken by one.
func TestAMarketSeenAgainPublishesNothing(t *testing.T) {
	documents := opened(t)
	ctx := context.Background()
	row, first := discovered(t, documents)

	later := now.Add(5 * time.Minute)
	seen := row
	seen.State = "open"
	seen.Title = row.Title + " (as the provider now words it)"
	signal := prediction(row.ProposalID, now.Add(72*time.Hour))
	record, changed, err := documents.Refresh(ctx, seen, signal, later)
	if err != nil {
		t.Fatal(err)
	}
	if changed {
		t.Fatal("a market whose statement has not changed moved the revision")
	}
	if record.Signal.Revision != first.Signal.Revision {
		t.Fatalf("revision %d", record.Signal.Revision)
	}
	tracked, err := documents.Markets(ctx)
	if err != nil {
		t.Fatal(err)
	}
	// The row moved even though the document did not: what the provider says is recorded either
	// way, and the operator can see when a market was last seen.
	switch {
	case tracked[0].Market.Title != seen.Title:
		t.Fatalf("the title is %q", tracked[0].Market.Title)
	case !tracked[0].Market.LastSeenAt.Equal(later):
		t.Fatalf("last seen %s", tracked[0].Market.LastSeenAt)
	}

	// And a market whose close time moved is a document that moved: the expiry is part of it.
	moved := prediction(row.ProposalID, now.Add(96*time.Hour))
	record, changed, err = documents.Refresh(ctx, seen, moved, later)
	if err != nil {
		t.Fatal(err)
	}
	if !changed || record.Signal.Revision != 2 {
		t.Fatalf("a postponed market did not move the revision: %v, %d", changed,
			record.Signal.Revision)
	}
}

// A market the source has ended is withdrawn, and a market that closes and re-opens gets a new
// proposal rather than reviving a withdrawn one — because a withdrawal is final for every phone
// that read it.
func TestAWithdrawnMarketComesBackAsANewProposal(t *testing.T) {
	documents := opened(t)
	ctx := context.Background()
	row, first := discovered(t, documents)

	closed := now.Add(time.Hour)
	record, withdrawn, err := documents.Withdraw(ctx, row.Provider, row.MarketID, "closed", closed)
	if err != nil {
		t.Fatal(err)
	}
	switch {
	case !withdrawn:
		t.Fatal("nothing was withdrawn")
	case record.Signal.Status != signals.Cancelled:
		t.Fatalf("status %s", record.Signal.Status)
	case record.Signal.Revision != first.Signal.Revision+1:
		t.Fatalf("a withdrawal is a revision: %d", record.Signal.Revision)
	}
	// Twice is not an event.
	_, again, err := documents.Withdraw(ctx, row.Provider, row.MarketID, "closed", closed)
	if err != nil || again {
		t.Fatalf("withdrawing twice: %v, %v", again, err)
	}
	live, err := documents.Live(ctx)
	if err != nil || live != 0 {
		t.Fatalf("%d live markets (%v)", live, err)
	}
	// The row is still there, with what the provider said.
	tracked, err := documents.Markets(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(tracked) != 1 || tracked[0].Market.State != "closed" {
		t.Fatalf("the row is %+v", tracked[0].Market)
	}
	if !tracked[0].Market.LastCheckedAt.Equal(closed) {
		t.Fatalf("a withdrawal is also a direct check: %s", tracked[0].Market.LastCheckedAt)
	}

	// Re-opened: the next generation, a new key, and a new proposal.
	reopened := row
	reopened.Generation = 2
	reopened.ProposalID = "5b4a3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d"
	reopened.FirstSeenAt = closed
	signal := prediction(reopened.ProposalID, now.Add(120*time.Hour))
	second, err := documents.Discover(ctx, reopened, reopened.Key(), signals.Statement(signal),
		signal)
	if err != nil {
		t.Fatal(err)
	}
	switch {
	case second.Signal.ProposalID == first.Signal.ProposalID:
		t.Fatal("the withdrawn proposal was revived")
	case second.Signal.Status != signals.Open:
		t.Fatalf("status %s", second.Signal.Status)
	case second.Signal.Revision != 1:
		t.Fatalf("a new proposal starts at revision 1, not %d", second.Signal.Revision)
	case reopened.Key() == row.Key():
		t.Fatalf("both generations would use the key %s", row.Key())
	}
	held, err := documents.Markets(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(held) != 1 || held[0].Market.Generation != 2 ||
		held[0].Market.ProposalID != second.Signal.ProposalID {
		t.Fatalf("the row is %+v", held[0].Market)
	}
	// Both signals are still there: the withdrawn one is still readable, because a phone that acted
	// on it has its own record either way.
	all, err := documents.Signals(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(all) != 2 {
		t.Fatalf("%d signals", len(all))
	}
}

// A direct check records what the provider said and touches nothing else. It is the round robin's
// own clock: the reconciler asks about the markets it has left longest.
func TestADirectCheckRecordsWhenItHappened(t *testing.T) {
	documents := opened(t)
	ctx := context.Background()
	row, first := discovered(t, documents)

	at := now.Add(30 * time.Minute)
	if err := documents.Checked(ctx, row.Provider, row.MarketID, "open", at); err != nil {
		t.Fatal(err)
	}
	tracked, err := documents.Markets(ctx)
	if err != nil {
		t.Fatal(err)
	}
	switch {
	case !tracked[0].Market.LastCheckedAt.Equal(at):
		t.Fatalf("last checked %s", tracked[0].Market.LastCheckedAt)
	case tracked[0].Record.Signal.Revision != first.Signal.Revision:
		t.Fatal("a direct check moved the signal")
	case tracked[0].Record.Signal.Status != signals.Open:
		t.Fatal("a direct check withdrew the signal")
	}
	// And a market nothing is tracking is an error rather than a silent success: it would mean the
	// reconciler and the file disagree about what exists.
	if err := documents.Checked(ctx, row.Provider, "POLY-NOTHING", "open", at); !errors.Is(err,
		ErrNoMarket) {
		t.Fatalf("%v", err)
	}
	if _, _, err := documents.Withdraw(ctx, row.Provider, "POLY-NOTHING", "closed",
		at); !errors.Is(err, ErrNoMarket) {
		t.Fatalf("%v", err)
	}
}

// The last cycle, and only the last one. Its number is the store's to mint, so it keeps counting
// across a restart.
func TestACyclesNumberKeepsCountingAcrossARestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "publisher.db")
	documents, err := Open(path, stamp())
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()

	empty, err := documents.Cycle(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if empty.Number != 0 || empty.Outcome != "" {
		t.Fatalf("a template that has just started has run %d cycles: %+v", empty.Number, empty)
	}

	first, err := documents.Recorded(ctx, discovery.Cycle{
		StartedAt: now, FinishedAt: now.Add(time.Second), Outcome: discovery.OK,
		Pages: 2, Events: 4, Considered: 20, Matched: 3, Created: 3,
		Reasons: map[string]int{discovery.NoKeyword: 17},
	})
	if err != nil {
		t.Fatal(err)
	}
	if first.Number != 1 {
		t.Fatalf("the first cycle is number %d", first.Number)
	}
	if _, err := documents.Recorded(ctx, discovery.Cycle{
		StartedAt: now.Add(time.Minute), Outcome: discovery.Partial,
		Problem: "provider_rate_limited", Detail: "Too many requests", Pages: 1,
		Reasons: map[string]int{},
	}); err != nil {
		t.Fatal(err)
	}
	if err := documents.Close(); err != nil {
		t.Fatal(err)
	}

	again, err := Open(path, stamp())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = again.Close() })
	held, err := again.Cycle(ctx)
	if err != nil {
		t.Fatal(err)
	}
	switch {
	case held.Number != 2:
		t.Fatalf("the cycle after the second is number %d", held.Number)
	case held.Outcome != discovery.Partial:
		t.Fatalf("outcome %q", held.Outcome)
	case held.Problem != "provider_rate_limited":
		t.Fatalf("problem %q", held.Problem)
	case !held.StartedAt.Equal(now.Add(time.Minute)):
		t.Fatalf("started %s", held.StartedAt)
	case !held.FinishedAt.IsZero():
		t.Fatalf("a cycle that did not finish reads as finishing at %s", held.FinishedAt)
	}
	third, err := again.Recorded(ctx, discovery.Cycle{Outcome: discovery.OK})
	if err != nil {
		t.Fatal(err)
	}
	if third.Number != 3 {
		t.Fatalf("after a restart the cycle number went to %d", third.Number)
	}
}

// A database written by the CopyTrading template — schema version 1, with signals in it — opens
// with this build and keeps everything. Refusing it, or creating a second file beside it, would
// both throw away the outbox that makes a restart safe.
func TestAVersionOneDatabaseIsBroughtForward(t *testing.T) {
	path := filepath.Join(t.TempDir(), "publisher.db")
	ctx := context.Background()

	// A version-1 file, built exactly as the earlier build built one: the first schema step, and a
	// deployment row saying version 1.
	earlier, err := open(path, true)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := earlier.ExecContext(ctx, schemaV1); err != nil {
		t.Fatal(err)
	}
	if _, err := earlier.ExecContext(ctx,
		`INSERT INTO deployment (id, schema_version, server_id, environment, gateway_url,
		                         created_at_ms)
		 VALUES (1, 1, ?, 'production', 'https://feeds.example.com', ?)`,
		server, now.UnixMilli()); err != nil {
		t.Fatal(err)
	}
	if err := earlier.Close(); err != nil {
		t.Fatal(err)
	}

	// The earlier build's own signal, stored through the code that is still the code.
	first, err := Open(path, stamp())
	if err != nil {
		t.Fatal(err)
	}
	held, _, err := first.Create(ctx, "key-1", "request-1", swap())
	if err != nil {
		t.Fatal(err)
	}
	if err := first.Close(); err != nil {
		t.Fatal(err)
	}

	// And now this build, which needs two tables that file does not have.
	documents, err := Open(path, stamp())
	if err != nil {
		t.Fatalf("a version-1 database could not be opened: %v", err)
	}
	t.Cleanup(func() { _ = documents.Close() })

	var version int
	if err := documents.reader.QueryRowContext(ctx,
		`SELECT schema_version FROM deployment WHERE id = 1`).Scan(&version); err != nil {
		t.Fatal(err)
	}
	if version != Version {
		t.Fatalf("the file is still at version %d", version)
	}
	// The signal came through untouched, including what the gateway has confirmed about it.
	signal, err := documents.Signal(ctx, held.Signal.ProposalID)
	if err != nil {
		t.Fatal(err)
	}
	if signal.Signal.Fingerprint != held.Signal.Fingerprint {
		t.Fatal("the signal did not survive the migration unchanged")
	}
	// And the new tables work.
	discovered(t, documents)
	tracked, err := documents.Markets(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(tracked) != 1 {
		t.Fatalf("%d markets after the migration", len(tracked))
	}
	// Opening it again is not another migration.
	if err := documents.Close(); err != nil {
		t.Fatal(err)
	}
	third, err := Open(path, stamp())
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = third.Close() }()
	if _, err := third.Markets(ctx); err != nil {
		t.Fatal(err)
	}
}

// A file from a later version is still refused, which is the other half of the same rule: this
// build can bring a file forward and must never guess at one from the future.
func TestAFileFromALaterVersionIsStillRefused(t *testing.T) {
	path := filepath.Join(t.TempDir(), "publisher.db")
	documents, err := Open(path, stamp())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := documents.writer.ExecContext(context.Background(),
		`UPDATE deployment SET schema_version = ? WHERE id = 1`, Version+1); err != nil {
		t.Fatal(err)
	}
	if err := documents.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(path, stamp()); !errors.Is(err, ErrNewerSchema) {
		t.Fatalf("%v", err)
	}
}

// A withdrawn market's row still points at its signal, and deleting a signal would take the row
// with it. Nothing deletes signals — this is about the schema saying which way the dependency
// goes.
func TestAMarketRowCannotOutliveItsSignal(t *testing.T) {
	documents := opened(t)
	ctx := context.Background()
	row, _ := discovered(t, documents)

	if _, err := documents.writer.ExecContext(ctx, `DELETE FROM signal WHERE proposal_id = ?`,
		row.ProposalID); err != nil {
		t.Fatal(err)
	}
	tracked, err := documents.Markets(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(tracked) != 0 {
		t.Fatalf("%d market rows survived their signal", len(tracked))
	}
	var rows int
	if err := documents.reader.QueryRowContext(ctx, `SELECT count(*) FROM market`).
		Scan(&rows); err != nil && !errors.Is(err, sql.ErrNoRows) {
		t.Fatal(err)
	}
	if rows != 0 {
		t.Fatalf("%d rows in market", rows)
	}
}
