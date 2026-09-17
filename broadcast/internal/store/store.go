// Package store is the broadcast gateway's durable state, and the only place in it that speaks SQL
// (SEE-90, docs/wiki/broadcast-gateway.md#where-it-is-kept).
//
// # What is here, and what is deliberately not
//
// Shared publications and publisher configuration. A manifest, the proposals a publisher currently
// holds open, the per-channel sequence a reader uses as a snapshot boundary, the hashes of the
// credentials a publisher authenticates with, and one pending notice per document for fan-out.
//
// **Nothing about a subscriber.** There is no table, and no column, for an address, a quantity
// someone chose, a decision they made, or anything they signed — and a Go boundary test reads this
// schema and fails if one appears. The gateway cannot lose a user's financial history because it
// never has one: what each owner picks and what came of it stays on the device that decided it
// (SEE-89, docs/security.md).
//
// # Why SQLite
//
// The store has one hard requirement: a publication and the notice that fans it out must commit
// together, so that a crash between them leaves work to redo rather than a document nobody hears
// about. A single-file transactional database does that with no service to operate, and
// modernc.org/sqlite is pure Go, so the image has no libc in it and the tests need nothing running.
// The load is bounded documents at human rates, not a stream of events. Postgres would be a second
// thing to run, back up and reason about for a workload that fits in a file — and if a deployment
// ever outgrows one process, the documents here are the authority either way: Centrifugo's and
// Redis's history (SEE-91) is a recovery cache, never a source of truth.
//
// # Two pools
//
// One writer connection and several readers, both on the same file in WAL mode. Writes are rare,
// serialized and transactional; reads are the public API and must not queue behind them. The writer
// takes SQLite's write lock at the start of a transaction (_txlock=immediate) so a second process —
// broadcastctl, registering a publisher while the server runs — fails fast instead of deadlocking
// half way through one.
package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/url"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite" // the pure-Go SQLite driver, registered as "sqlite"
)

// Version is the schema this build writes and reads. There is one, and a file from a later version
// is refused rather than guessed at: an old binary reading a new file could silently ignore a
// column that a rule depends on.
const Version = 1

// ErrNewerSchema is returned by Open when the file was written by a later version of the gateway.
var ErrNewerSchema = errors.New("the database was written by a newer gateway")

// Store is the open database. Its zero value is not usable; call Open.
type Store struct {
	writer *sql.DB
	reader *sql.DB
	path   string
}

// Open opens or creates the database at path and applies the schema. The caller closes it.
func Open(path string) (*Store, error) {
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
	store := &Store{writer: writer, reader: reader, path: absolute}
	if err := store.migrate(context.Background()); err != nil {
		_ = store.Close()
		return nil, err
	}
	return store, nil
}

func open(path string, writing bool) (*sql.DB, error) {
	// Durability over speed: a publication is answered as accepted, and the answer has to survive
	// the machine losing power as much as the process losing its footing, because the publisher is
	// entitled to stop retrying once it has been told the gateway holds the document.
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
		// One writer, in this process and by SQLite's own rule. Holding exactly one connection
		// makes that explicit rather than leaving it to lock contention to discover.
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

func (s *Store) Close() error {
	return errors.Join(s.reader.Close(), s.writer.Close())
}

// Write runs fn inside one transaction, which commits when fn returns nil and rolls back
// otherwise. Everything that changes durable state goes through here, so "persist first" is one
// mechanism rather than a habit: the document and the notice that fans it out are written by the
// same fn and commit or vanish together.
func (s *Store) Write(ctx context.Context, fn func(*Tx) error) error {
	transaction, err := s.writer.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin: %w", err)
	}
	if err := fn(&Tx{tx: transaction}); err != nil {
		_ = transaction.Rollback()
		return err
	}
	if err := transaction.Commit(); err != nil {
		return fmt.Errorf("commit: %w", err)
	}
	return nil
}

// Tx is the transaction Write hands to its caller. Its methods are the writes, plus the reads a
// write has to make first — what the gateway holds, which is what the rules in internal/rules
// decide against.
type Tx struct{ tx *sql.Tx }

func (s *Store) migrate(ctx context.Context) error {
	return s.Write(ctx, func(tx *Tx) error {
		var version int
		err := tx.tx.QueryRowContext(ctx, `PRAGMA user_version`).Scan(&version)
		if err != nil {
			return fmt.Errorf("read schema version: %w", err)
		}
		if version > Version {
			return fmt.Errorf("%w: found version %d, this build writes %d",
				ErrNewerSchema, version, Version)
		}
		if version == Version {
			return nil
		}
		if _, err := tx.tx.ExecContext(ctx, schema); err != nil {
			return fmt.Errorf("apply schema: %w", err)
		}
		// PRAGMA user_version takes no parameter, and Version is a constant in this file.
		if _, err := tx.tx.ExecContext(ctx,
			fmt.Sprintf(`PRAGMA user_version = %d`, Version)); err != nil {
			return fmt.Errorf("set schema version: %w", err)
		}
		return nil
	})
}

// The schema, in one statement per table and with the reasoning where a column carries a rule.
// There is no migration path yet because there is no earlier version; when there is, this constant
// gets a sibling and migrate applies them in order, the way the sidecar's migrations do.
const schema = `
-- A registered publisher: the developer's server, by the lasting ID its manifest and every
-- proposal of its own must name. The label is the operator's note to themselves and is never
-- served to anyone.
CREATE TABLE publisher (
  server_id     TEXT PRIMARY KEY,
  label         TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL
);

-- What a publisher authenticates with, kept the way the sidecar keeps a phone's credential: only
-- the SHA-256 of it, so the file cannot hand anyone the ability to publish. A publisher may hold
-- several at once, which is what makes rotation a thing that can be done without an outage: add
-- the new one, deploy it, revoke the old one.
CREATE TABLE publisher_credential (
  credential_hash BLOB PRIMARY KEY,
  server_id       TEXT NOT NULL REFERENCES publisher(server_id) ON DELETE CASCADE,
  label           TEXT NOT NULL,
  created_at_ms   INTEGER NOT NULL,
  revoked_at_ms   INTEGER
);
CREATE INDEX publisher_credential_by_server ON publisher_credential(server_id);

-- One manifest per publisher: what it says about itself, as the gateway validated and rebuilt it.
CREATE TABLE manifest (
  server_id         TEXT PRIMARY KEY REFERENCES publisher(server_id) ON DELETE CASCADE,
  settings_revision INTEGER NOT NULL,
  document          BLOB NOT NULL,
  updated_at_ms     INTEGER NOT NULL
);

-- The proposals a channel currently holds. The identity is (channel, proposal_id), which is what
-- makes a republication an update rather than a second proposal, and cancelled is kept as its own
-- column so a withdrawal can be refused a further publication without parsing the document.
CREATE TABLE proposal (
  channel       TEXT NOT NULL,
  proposal_id   TEXT NOT NULL,
  server_id     TEXT NOT NULL REFERENCES publisher(server_id) ON DELETE CASCADE,
  revision      INTEGER NOT NULL,
  cancelled     INTEGER NOT NULL,
  expires_at_ms INTEGER NOT NULL,
  sequence      INTEGER NOT NULL,
  document      BLOB NOT NULL,
  updated_at_ms INTEGER NOT NULL,
  PRIMARY KEY (channel, proposal_id)
);
-- Retention sweeps by expiry, and nothing else reads this index.
CREATE INDEX proposal_by_expiry ON proposal(expires_at_ms);

-- The channel's count of accepted publications, which never goes backwards. A reader gets it as
-- the snapshot boundary of a walk (ListProposalsResponse.snapshot_sequence) and as the answer to
-- "has anything changed since?", so it is a version for the whole channel rather than for one
-- document.
CREATE TABLE channel_sequence (
  channel  TEXT PRIMARY KEY,
  sequence INTEGER NOT NULL
);

-- The outbox: one pending notice per document, written in the same transaction as the document
-- itself. A crash between the two is impossible, and a crash after them leaves a row that the
-- drainer picks up on the next start (internal/dispatch). It is deliberately one row per document
-- and not one per revision: what a subscriber wants is the document as it stands, so two
-- publications that have not been fanned out yet collapse into the later one, exactly as the push
-- invalidations for a private request already collapse under one key (SAW-056).
CREATE TABLE notice (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  channel       TEXT NOT NULL,
  kind          TEXT NOT NULL,
  proposal_id   TEXT NOT NULL,
  revision      INTEGER NOT NULL,
  sequence      INTEGER NOT NULL,
  created_at_ms INTEGER NOT NULL,
  attempts      INTEGER NOT NULL,
  ready_at_ms   INTEGER NOT NULL
);
CREATE UNIQUE INDEX notice_identity ON notice(channel, kind, proposal_id);
CREATE INDEX notice_ready ON notice(ready_at_ms);
`

func milliseconds(at time.Time) int64 { return at.UTC().UnixMilli() }

func instant(milliseconds int64) time.Time {
	return time.UnixMilli(milliseconds).UTC()
}
