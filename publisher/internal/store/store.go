// Package store is a publisher template's durable state, and the only place in it that speaks SQL
// (SEE-95, docs/wiki/copytrading-template.md#where-it-is-kept).
//
// # What is here, and what is deliberately not
//
// The template's own signals, and what the gateway has confirmed about each of them. A signal's
// terms, its revision, its expiry, the prose its publisher wrote, and the idempotency keys callers
// created them with.
//
// **Nothing about a subscriber.** There is no table and no column for a wallet address, an amount
// somebody chose, a decision they made, or anything they signed — and `store_test.go` reads the
// live schema, not this string, and fails if one appears. A publisher cannot lose a subscriber's
// financial history because it never has one: it does not learn who reads its channel, and what
// each owner picks stays on the device that picked it (SEE-89, docs/security.md).
//
// There are no FCM tokens either, and no per-phone rows of any kind. Delivery is the gateway's
// (SEE-91, SEE-92): the template submits one document and is done.
//
// # The document is its own outbox
//
// Every row carries two revisions: the one the signal is at, and the one the gateway has confirmed
// it holds. Anything where the first is above the second is work to do, which the drainer finds by
// asking for exactly that (internal/publish). There is no second table to keep in step with the
// first, so there is no state in which a signal exists and its publication does not — and a
// restart resumes from the same two numbers, which is why a template that was killed mid-publish
// republishes the identical document rather than inventing a new one.
//
// # Why SQLite
//
// One file, transactional, no service to operate, and a pure-Go driver so the image has no libc
// and the tests need nothing running. A template's load is a handful of documents at human rates.
// It is the same choice the gateway made, for the same reasons (broadcast/internal/store).
package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"path/filepath"
	"sort"
	"time"

	_ "modernc.org/sqlite" // the pure-Go SQLite driver, registered as "sqlite"

	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/signals"
)

// Version is the schema this build writes and reads. A file from a later version is refused rather
// than guessed at.
const Version = 1

var (
	// ErrNewerSchema is returned by Open when the file was written by a later version.
	ErrNewerSchema = errors.New("the database was written by a newer publisher")
	// ErrOtherServer is returned by Open when the file belongs to another publisher. Two
	// publishers sharing one database would publish each other's signals under their own
	// credential, so the file remembers whose it is.
	ErrOtherServer = errors.New("the database belongs to another publisher")
	// ErrOtherEnvironment is returned by Open when the file was created for the other environment.
	// Sandbox and production are separate promises about what happens when an owner approves, and
	// the way they get mixed up is a copied compose file pointed at an existing volume — so the
	// isolation is stamped into the file rather than left to the configuration that opened it.
	ErrOtherEnvironment = errors.New("the database was created for the other environment")
	// ErrNoSignal is returned when nothing is held under that proposal ID.
	ErrNoSignal = errors.New("no signal of that ID")
	// ErrKeyReused is returned when an idempotency key was already used for a different request.
	// It is not a duplicate — it is two different signals asking to be the same one.
	ErrKeyReused = errors.New("that idempotency key was used for a different signal")
	// ErrCancelled is returned when a withdrawn signal is asked to change. A withdrawal is final:
	// a phone that acted on it keeps its record for ever, and must never see the same identity
	// open again.
	ErrCancelled = errors.New("that signal was withdrawn")
)

// Stamp is who the file belongs to. It is checked on every open, and the check is the isolation.
type Stamp struct {
	ServerID    string
	Environment string
	// The gateway this template publishes through. Unlike the two above it may change — a
	// deployment moves domain — so it is recorded and updated rather than enforced. The manifest's
	// fingerprint notices, which is what moves its revision.
	GatewayURL string
}

// Store is the open database. Its zero value is not usable; call Open.
type Store struct {
	writer *sql.DB
	reader *sql.DB
	path   string
	// The publisher this file belongs to. It is here because a signal's fingerprint is a
	// fingerprint of the document, and a document names its server: computing it here rather than
	// taking it from a caller means no caller can store a fingerprint that does not describe what
	// would be published.
	serverID string
}

// Open opens or creates the database at path, applies the schema, and checks the stamp. The caller
// closes it.
func Open(path string, stamp Stamp) (*Store, error) {
	absolute, err := filepath.Abs(path)
	if err != nil {
		return nil, fmt.Errorf("resolve %s: %w", path, err)
	}
	writer, err := open(absolute, true)
	if err != nil {
		return nil, err
	}
	reader, err := open(absolute, false)
	if err != nil {
		_ = writer.Close()
		return nil, err
	}
	store := &Store{writer: writer, reader: reader, path: absolute, serverID: stamp.ServerID}
	if err := store.migrate(context.Background(), stamp); err != nil {
		_ = store.Close()
		return nil, err
	}
	return store, nil
}

func open(path string, writing bool) (*sql.DB, error) {
	// Durability over speed. A caller is told its signal is held before it is published, so that
	// answer has to survive the machine losing power as much as the process losing its footing:
	// the whole point of storing first is that the publication can be retried from the file.
	dsn := "file:" + url.PathEscape(path) +
		"?_pragma=busy_timeout(5000)" +
		"&_pragma=journal_mode(WAL)" +
		"&_pragma=synchronous(FULL)" +
		"&_pragma=foreign_keys(1)"
	if writing {
		dsn += "&_txlock=immediate"
	}
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open %s: %w", path, err)
	}
	if writing {
		// One writer, in this process and by SQLite's own rule.
		db.SetMaxOpenConns(1)
	} else {
		db.SetMaxOpenConns(4)
	}
	db.SetMaxIdleConns(2)
	db.SetConnMaxIdleTime(5 * time.Minute)
	if err := db.PingContext(context.Background()); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("open %s: %w", path, err)
	}
	return db, nil
}

// Path is the file the store was opened on, for a startup log line.
func (s *Store) Path() string { return s.path }

// Close closes both pools.
func (s *Store) Close() error {
	return errors.Join(s.reader.Close(), s.writer.Close())
}

const schema = `
-- Whose file this is. One row, and it is checked on every open: a database is a publisher's
-- identity as much as its credential is, and two publishers sharing one would publish each
-- other's signals.
CREATE TABLE deployment (
  id             INTEGER PRIMARY KEY CHECK (id = 1),
  schema_version INTEGER NOT NULL,
  server_id      TEXT NOT NULL,
  environment    TEXT NOT NULL,
  gateway_url    TEXT NOT NULL,
  created_at_ms  INTEGER NOT NULL
);

-- What this template says about itself, as a revision and a fingerprint of the settings it was
-- built from. The document itself is not stored: it is rebuilt from the settings and this
-- revision, which is deterministic, and storing bytes as well would be a second copy of one fact.
CREATE TABLE manifest (
  id                 INTEGER PRIMARY KEY CHECK (id = 1),
  revision           INTEGER NOT NULL,
  fingerprint        TEXT NOT NULL,
  confirmed_revision INTEGER NOT NULL,
  attempts           INTEGER NOT NULL,
  due_at_ms          INTEGER NOT NULL,
  problem            TEXT NOT NULL,
  detail             TEXT NOT NULL
);

-- The signals this template holds. Every column is part of the document every subscriber reads,
-- or part of what the gateway has confirmed about it. There is deliberately no column for an
-- address, an amount, a decision or a result: a proposal is common, and a decision about it is not
-- (docs/security.md).
CREATE TABLE signal (
  proposal_id        TEXT PRIMARY KEY,
  revision           INTEGER NOT NULL,
  status             TEXT NOT NULL,
  operation          TEXT NOT NULL,
  plugin_id          TEXT NOT NULL,
  created_at_ms      INTEGER NOT NULL,
  updated_at_ms      INTEGER NOT NULL,
  expires_at_ms      INTEGER NOT NULL,
  note               TEXT NOT NULL,
  terms              TEXT NOT NULL,
  fingerprint        TEXT NOT NULL,
  confirmed_revision INTEGER NOT NULL,
  attempts           INTEGER NOT NULL,
  due_at_ms          INTEGER NOT NULL,
  problem            TEXT NOT NULL,
  detail             TEXT NOT NULL
);
-- The drainer asks for what is not published yet, oldest first.
CREATE INDEX signal_pending ON signal(due_at_ms) WHERE confirmed_revision < revision;

-- The idempotency keys callers created signals with, and a fingerprint of the request each was
-- used for. A repeat of the same key with the same request is the same signal; the same key with
-- a different request is two signals asking to be one, which is a conflict rather than a
-- duplicate (docs/integrations/signal-api.md).
CREATE TABLE idempotency (
  key           TEXT PRIMARY KEY,
  proposal_id   TEXT NOT NULL REFERENCES signal(proposal_id) ON DELETE CASCADE,
  request       TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL
);
`

func (s *Store) migrate(ctx context.Context, stamp Stamp) error {
	var present int
	if err := s.writer.QueryRowContext(ctx,
		`SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'deployment'`,
	).Scan(&present); err != nil {
		return fmt.Errorf("read the schema: %w", err)
	}
	if present == 0 {
		transaction, err := s.writer.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		defer func() { _ = transaction.Rollback() }()
		if _, err := transaction.ExecContext(ctx, schema); err != nil {
			return fmt.Errorf("create the schema: %w", err)
		}
		if _, err := transaction.ExecContext(ctx,
			`INSERT INTO deployment (id, schema_version, server_id, environment, gateway_url,
			                         created_at_ms)
			 VALUES (1, ?, ?, ?, ?, ?)`,
			Version, stamp.ServerID, stamp.Environment, stamp.GatewayURL,
			milliseconds(time.Now()),
		); err != nil {
			return fmt.Errorf("stamp the database: %w", err)
		}
		return transaction.Commit()
	}

	var (
		version     int
		serverID    string
		environment string
	)
	if err := s.writer.QueryRowContext(ctx,
		`SELECT schema_version, server_id, environment FROM deployment WHERE id = 1`,
	).Scan(&version, &serverID, &environment); err != nil {
		return fmt.Errorf("read the deployment: %w", err)
	}
	switch {
	case version > Version:
		return fmt.Errorf("%w: the file is version %d and this build reads %d",
			ErrNewerSchema, version, Version)
	case serverID != stamp.ServerID:
		return fmt.Errorf("%w: it is %s's, and PUBLISHER_SERVER_ID is %s",
			ErrOtherServer, serverID, stamp.ServerID)
	case environment != stamp.Environment:
		return fmt.Errorf("%w: it is a %s database, and PUBLISHER_ENVIRONMENT is %s",
			ErrOtherEnvironment, environment, stamp.Environment)
	}
	// The gateway may move; the publisher and the environment may not.
	_, err := s.writer.ExecContext(ctx,
		`UPDATE deployment SET gateway_url = ? WHERE id = 1`, stamp.GatewayURL)
	return err
}

// ManifestRevision settles what revision the manifest is at, given the fingerprint of the settings
// this process was started with, and returns it.
//
// It moves only when the fingerprint moves. That is what makes a restart a non-event: the same
// settings produce the same fingerprint, the same revision is republished, and the gateway answers
// `UNCHANGED` — so no phone re-reads a manifest that has not changed, and nobody is notified.
//
// The publication state is reset either way, because this is called once per start and a start is
// a deliberate act: whatever a previous process was refused, the operator who restarted this one is
// entitled to have it tried again. There is no such reset for a signal — a refused signal is
// retried when somebody asks (`POST /v1/signals/<id>/retry`), so that a restart loop cannot
// become a publication loop.
func (s *Store) ManifestRevision(ctx context.Context, fingerprint string, now time.Time) (uint64, error) {
	transaction, err := s.writer.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer func() { _ = transaction.Rollback() }()

	var (
		revision uint64
		held     string
	)
	err = transaction.QueryRowContext(ctx,
		`SELECT revision, fingerprint FROM manifest WHERE id = 1`).Scan(&revision, &held)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		if _, err := transaction.ExecContext(ctx,
			`INSERT INTO manifest (id, revision, fingerprint, confirmed_revision, attempts,
			                       due_at_ms, problem, detail)
			 VALUES (1, 1, ?, 0, 0, ?, '', '')`, fingerprint, milliseconds(now)); err != nil {
			return 0, err
		}
		return 1, transaction.Commit()
	case err != nil:
		return 0, err
	case held != fingerprint:
		revision++
	}
	if _, err := transaction.ExecContext(ctx,
		`UPDATE manifest SET revision = ?, fingerprint = ?, attempts = 0, due_at_ms = ?,
		                     problem = '', detail = ''
		 WHERE id = 1`, revision, fingerprint, milliseconds(now)); err != nil {
		return 0, err
	}
	return revision, transaction.Commit()
}

// Manifest is the revision the manifest is at and what the gateway has confirmed about it.
func (s *Store) Manifest(ctx context.Context) (uint64, signals.Publication, error) {
	var (
		revision uint64
		state    signals.Publication
		due      int64
	)
	err := s.reader.QueryRowContext(ctx,
		`SELECT revision, confirmed_revision, attempts, due_at_ms, problem, detail
		 FROM manifest WHERE id = 1`,
	).Scan(&revision, &state.ConfirmedRevision, &state.Attempts, &due, &state.Problem,
		&state.Detail)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, signals.Publication{}, nil
	}
	state.DueAt = instant(due)
	return revision, state, err
}

// ManifestPublished records that the gateway holds this revision of the manifest.
func (s *Store) ManifestPublished(ctx context.Context, revision uint64) error {
	_, err := s.writer.ExecContext(ctx,
		`UPDATE manifest SET confirmed_revision = ?, attempts = 0, problem = '', detail = ''
		 WHERE id = 1 AND revision = ?`, revision, revision)
	return err
}

// ManifestDeferred records a publication that failed for a reason that may not fail next time.
func (s *Store) ManifestDeferred(ctx context.Context, due time.Time, detail string) error {
	_, err := s.writer.ExecContext(ctx,
		`UPDATE manifest SET attempts = attempts + 1, due_at_ms = ?, detail = ? WHERE id = 1`,
		milliseconds(due), detail)
	return err
}

// ManifestRefused records a refusal that retrying cannot fix. Nothing is retried after one: the
// problem is the gateway's own code, and an operator has to change something.
func (s *Store) ManifestRefused(ctx context.Context, problem, detail string) error {
	_, err := s.writer.ExecContext(ctx,
		`UPDATE manifest SET attempts = attempts + 1, problem = ?, detail = ? WHERE id = 1`,
		problem, detail)
	return err
}

// Create stores a new signal, or returns the one an earlier call with the same idempotency key
// created.
//
// The three answers are the three things that can be true, and telling them apart is the whole
// value of the key: this is new, this is the same request again (so here is the same signal, and
// nothing was published twice), or this key was used for a different request (so nothing is
// stored, because two different signals cannot be one).
func (s *Store) Create(ctx context.Context, key, request string, signal signals.Signal) (
	signals.Record, bool, error,
) {
	transaction, err := s.writer.BeginTx(ctx, nil)
	if err != nil {
		return signals.Record{}, false, err
	}
	defer func() { _ = transaction.Rollback() }()

	var (
		held    string
		earlier string
	)
	err = transaction.QueryRowContext(ctx,
		`SELECT proposal_id, request FROM idempotency WHERE key = ?`, key).Scan(&held, &earlier)
	switch {
	case err == nil && earlier == request:
		record, err := read(ctx, transaction, held)
		if err != nil {
			return signals.Record{}, false, err
		}
		if err := transaction.Commit(); err != nil {
			return signals.Record{}, false, err
		}
		return record, true, nil
	case err == nil:
		return signals.Record{}, false, ErrKeyReused
	case !errors.Is(err, sql.ErrNoRows):
		return signals.Record{}, false, err
	}

	// A first publication is revision 1. Zero is never published, and the number is the store's
	// to mint for the same reason the next one is: a caller that chose its own could publish a
	// revision below what the gateway already holds and be refused as stale for ever.
	signal.Revision = 1
	signal.Status = signals.Open
	signal.Fingerprint = signals.Fingerprint(s.serverID, signal)
	terms, err := json.Marshal(signal.Terms)
	if err != nil {
		return signals.Record{}, false, err
	}
	if _, err := transaction.ExecContext(ctx,
		`INSERT INTO signal (proposal_id, revision, status, operation, plugin_id, created_at_ms,
		                     updated_at_ms, expires_at_ms, note, terms, fingerprint,
		                     confirmed_revision, attempts, due_at_ms, problem, detail)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, '', '')`,
		signal.ProposalID, signal.Revision, string(signal.Status), signal.Operation,
		signal.PluginID, milliseconds(signal.CreatedAt), milliseconds(signal.UpdatedAt),
		milliseconds(signal.ExpiresAt), signal.Note, string(terms), signal.Fingerprint,
		milliseconds(signal.CreatedAt),
	); err != nil {
		return signals.Record{}, false, err
	}
	if _, err := transaction.ExecContext(ctx,
		`INSERT INTO idempotency (key, proposal_id, request, created_at_ms) VALUES (?, ?, ?, ?)`,
		key, signal.ProposalID, request, milliseconds(signal.CreatedAt),
	); err != nil {
		return signals.Record{}, false, err
	}
	record, err := read(ctx, transaction, signal.ProposalID)
	if err != nil {
		return signals.Record{}, false, err
	}
	return record, false, transaction.Commit()
}

// Update replaces a signal's terms, expiry and note with the whole statement given, and moves the
// revision if — and only if — that changed the document.
//
// An update that changes nothing is not an event: it answers with the signal as it stands and
// publishes nothing, so a strategy engine may re-post its current view as often as it likes
// without a single phone being woken. The boolean says which of the two happened.
func (s *Store) Update(ctx context.Context, id string, next signals.Signal, now time.Time) (
	signals.Record, bool, error,
) {
	transaction, err := s.writer.BeginTx(ctx, nil)
	if err != nil {
		return signals.Record{}, false, err
	}
	defer func() { _ = transaction.Rollback() }()

	held, err := read(ctx, transaction, id)
	if err != nil {
		return signals.Record{}, false, err
	}
	if held.Signal.Status == signals.Cancelled {
		return signals.Record{}, false, ErrCancelled
	}
	// The candidate is what is held with the caller's whole statement applied: an update replaces
	// the terms, the expiry and the note, and changes nothing else. The identity, the creation
	// time and the operation are not a caller's to move.
	candidate := held.Signal
	candidate.ExpiresAt = next.ExpiresAt
	candidate.Note = next.Note
	candidate.Terms = next.Terms
	candidate.Fingerprint = signals.Fingerprint(s.serverID, candidate)
	if held.Signal.Fingerprint == candidate.Fingerprint {
		return held, false, transaction.Commit()
	}
	revision := held.Signal.Revision + 1
	if revision > signals.MaxRevision {
		return signals.Record{}, false, fmt.Errorf("that signal is at the highest revision " +
			"a phone can order; publish a new signal instead")
	}
	terms, err := json.Marshal(candidate.Terms)
	if err != nil {
		return signals.Record{}, false, err
	}
	if _, err := transaction.ExecContext(ctx,
		`UPDATE signal SET revision = ?, updated_at_ms = ?, expires_at_ms = ?, note = ?,
		                   terms = ?, fingerprint = ?, attempts = 0, due_at_ms = ?,
		                   problem = '', detail = ''
		 WHERE proposal_id = ?`,
		revision, milliseconds(now), milliseconds(candidate.ExpiresAt), candidate.Note,
		string(terms), candidate.Fingerprint, milliseconds(now), id,
	); err != nil {
		return signals.Record{}, false, err
	}
	record, err := read(ctx, transaction, id)
	if err != nil {
		return signals.Record{}, false, err
	}
	return record, true, transaction.Commit()
}

// Cancel withdraws a signal at the next revision. Withdrawing one that is already withdrawn
// changes nothing and publishes nothing, which is what makes a retried cancellation safe.
func (s *Store) Cancel(ctx context.Context, id string, now time.Time) (
	signals.Record, bool, error,
) {
	transaction, err := s.writer.BeginTx(ctx, nil)
	if err != nil {
		return signals.Record{}, false, err
	}
	defer func() { _ = transaction.Rollback() }()

	held, err := read(ctx, transaction, id)
	if err != nil {
		return signals.Record{}, false, err
	}
	if held.Signal.Status == signals.Cancelled {
		return held, false, transaction.Commit()
	}
	revision := held.Signal.Revision + 1
	if revision > signals.MaxRevision {
		return signals.Record{}, false, fmt.Errorf("that signal is at the highest revision " +
			"a phone can order, so it cannot be withdrawn through a revision")
	}
	withdrawn := held.Signal
	withdrawn.Status = signals.Cancelled
	withdrawn.Revision = revision
	withdrawn.UpdatedAt = now
	if _, err := transaction.ExecContext(ctx,
		`UPDATE signal SET revision = ?, status = ?, updated_at_ms = ?, fingerprint = ?,
		                   attempts = 0, due_at_ms = ?, problem = '', detail = ''
		 WHERE proposal_id = ?`,
		revision, string(signals.Cancelled), milliseconds(now),
		signals.Fingerprint(s.serverID, withdrawn), milliseconds(now), id,
	); err != nil {
		return signals.Record{}, false, err
	}
	record, err := read(ctx, transaction, id)
	if err != nil {
		return signals.Record{}, false, err
	}
	return record, true, transaction.Commit()
}

// Signal is one record, or [ErrNoSignal].
func (s *Store) Signal(ctx context.Context, id string) (signals.Record, error) {
	return read(ctx, s.reader, id)
}

// Signals is every record this template holds, newest first. A channel holds at most a couple of
// hundred proposals — the gateway's own bound — so this is a list rather than a page.
func (s *Store) Signals(ctx context.Context) ([]signals.Record, error) {
	rows, err := s.reader.QueryContext(ctx, selectSignal+` ORDER BY created_at_ms DESC, proposal_id`)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	return scan(rows)
}

// Due is the signals whose confirmed revision is behind their own and whose next attempt is due:
// the outbox, which is the same rows read by a different question.
func (s *Store) Due(ctx context.Context, now time.Time, limit int) ([]signals.Record, error) {
	rows, err := s.reader.QueryContext(ctx,
		selectSignal+` WHERE confirmed_revision < revision AND problem = '' AND due_at_ms <= ?
		 ORDER BY due_at_ms, proposal_id LIMIT ?`, milliseconds(now), limit)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	return scan(rows)
}

// Pending is how many signals are waiting to be published, for a startup line and the status
// answer. Anything above zero after a quiet minute is something an operator wants to know.
func (s *Store) Pending(ctx context.Context) (int, error) {
	var pending int
	err := s.reader.QueryRowContext(ctx,
		`SELECT count(*) FROM signal WHERE confirmed_revision < revision AND problem = ''`,
	).Scan(&pending)
	return pending, err
}

// Published records that the gateway holds this revision of this signal. The revision is part of
// the condition: an answer that arrived after the signal moved on confirms nothing about the
// document it is now.
//
// The detail is usually empty. It carries one line in one case — a withdrawal the gateway had
// nothing to apply, because the publication it would have withdrawn never arrived — because
// "published" and "there was never anything to publish" are both settled and are not the same
// thing to read in an answer (internal/publish).
func (s *Store) Published(ctx context.Context, id string, revision uint64, detail string) error {
	_, err := s.writer.ExecContext(ctx,
		`UPDATE signal SET confirmed_revision = ?, attempts = 0, problem = '', detail = ?
		 WHERE proposal_id = ? AND revision = ?`, revision, detail, id, revision)
	return err
}

// Retry clears a refusal so the drainer picks the signal up again, and answers whether there was
// one to clear.
//
// It is the one way out of a permanent refusal, and it is deliberately a request rather than a
// timer: the gateway refused this document for a reason, and something — a credential, a channel
// that was full, a deployment pointed at the wrong gateway — has to have changed before asking
// again means anything (docs/integrations/signal-api.md).
func (s *Store) Retry(ctx context.Context, id string, now time.Time) (signals.Record, bool, error) {
	transaction, err := s.writer.BeginTx(ctx, nil)
	if err != nil {
		return signals.Record{}, false, err
	}
	defer func() { _ = transaction.Rollback() }()

	held, err := read(ctx, transaction, id)
	if err != nil {
		return signals.Record{}, false, err
	}
	if held.Publication.Problem == "" {
		return held, false, transaction.Commit()
	}
	if _, err := transaction.ExecContext(ctx,
		`UPDATE signal SET attempts = 0, due_at_ms = ?, problem = '', detail = ''
		 WHERE proposal_id = ?`, milliseconds(now), id); err != nil {
		return signals.Record{}, false, err
	}
	record, err := read(ctx, transaction, id)
	if err != nil {
		return signals.Record{}, false, err
	}
	return record, true, transaction.Commit()
}

// Deferred records a publication that failed for a reason that may not fail next time, and when to
// try again.
func (s *Store) Deferred(ctx context.Context, id string, due time.Time, detail string) error {
	_, err := s.writer.ExecContext(ctx,
		`UPDATE signal SET attempts = attempts + 1, due_at_ms = ?, detail = ?
		 WHERE proposal_id = ?`, milliseconds(due), detail, id)
	return err
}

// Refused records a refusal retrying cannot fix. The signal stays exactly as it is — it is still
// the publisher's statement, and an operator may fix the deployment and publish it — but nothing
// retries it on its own, because a refused document refused for a reason.
func (s *Store) Refused(ctx context.Context, id string, problem, detail string) error {
	_, err := s.writer.ExecContext(ctx,
		`UPDATE signal SET attempts = attempts + 1, problem = ?, detail = ? WHERE proposal_id = ?`,
		problem, detail, id)
	return err
}

const selectSignal = `
SELECT proposal_id, revision, status, operation, plugin_id, created_at_ms, updated_at_ms,
       expires_at_ms, note, terms, fingerprint, confirmed_revision, attempts, due_at_ms,
       problem, detail
FROM signal`

// rower is the part of *sql.DB and *sql.Tx a read needs, so one function serves both a plain read
// and a read inside the transaction that just wrote.
type rower interface {
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

func read(ctx context.Context, from rower, id string) (signals.Record, error) {
	row := from.QueryRowContext(ctx, selectSignal+` WHERE proposal_id = ?`, id)
	record, err := scanOne(row)
	if errors.Is(err, sql.ErrNoRows) {
		return signals.Record{}, ErrNoSignal
	}
	return record, err
}

type scanner interface {
	Scan(destination ...any) error
}

func scanOne(row scanner) (signals.Record, error) {
	var (
		record                         signals.Record
		status, terms                  string
		created, updated, expires, due int64
	)
	if err := row.Scan(&record.Signal.ProposalID, &record.Signal.Revision, &status,
		&record.Signal.Operation, &record.Signal.PluginID, &created, &updated, &expires,
		&record.Signal.Note, &terms, &record.Signal.Fingerprint,
		&record.Publication.ConfirmedRevision, &record.Publication.Attempts, &due,
		&record.Publication.Problem, &record.Publication.Detail); err != nil {
		return signals.Record{}, err
	}
	record.Signal.Status = signals.Status(status)
	record.Signal.CreatedAt = instant(created)
	record.Signal.UpdatedAt = instant(updated)
	record.Signal.ExpiresAt = instant(expires)
	record.Publication.DueAt = instant(due)
	if err := json.Unmarshal([]byte(terms), &record.Signal.Terms); err != nil {
		return signals.Record{}, fmt.Errorf("read the terms of %s: %w",
			record.Signal.ProposalID, err)
	}
	return record, nil
}

func scan(rows *sql.Rows) ([]signals.Record, error) {
	records := []signals.Record{}
	for rows.Next() {
		record, err := scanOne(rows)
		if err != nil {
			return nil, err
		}
		records = append(records, record)
	}
	return records, rows.Err()
}

// Keys is every idempotency key held for a signal, in order. It exists for the tests and for
// `publishctl show`: a caller that lost its answer wants to know which key it used.
func (s *Store) Keys(ctx context.Context, id string) ([]string, error) {
	rows, err := s.reader.QueryContext(ctx,
		`SELECT key FROM idempotency WHERE proposal_id = ?`, id)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	keys := []string{}
	for rows.Next() {
		var key string
		if err := rows.Scan(&key); err != nil {
			return nil, err
		}
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys, rows.Err()
}

func milliseconds(at time.Time) int64 { return at.UTC().UnixMilli() }

func instant(milliseconds int64) time.Time {
	return time.UnixMilli(milliseconds).UTC()
}
