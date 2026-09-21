// Package postgres is the feed gateway's durable storage implementation for deployments whose
// filesystem does not survive the process (SEE-145).
//
// # Why there are two
//
// storage/sqlite remains the right answer for a self-hosted gateway: one file, no service to
// operate, and a transaction that commits a publication and its fan-out notice together. What it
// cannot do is outlive a container that is replaced rather than restarted. On a platform with no
// attached disk — the hosted demo runs on DigitalOcean App Platform — the file went with the
// container, and with it every registration, every credential hash and every relay binding. That
// was documented as an accepted limitation and recovered from by re-registering publishers; this
// package is that limitation removed rather than described.
//
// The choice is the deployment's and is made by configuration alone: BROADCAST_DATABASE_URL
// selects this implementation, BROADCAST_DATABASE_PATH the file. Both satisfy the same
// storage.GatewayStore contract, so nothing above the store knows which one it has — which is the
// property the storage package was written to have, and this is the first time anything has
// depended on it.
//
// # One writer, still
//
// The SQLite store takes that database's write lock at the start of every write transaction
// (_txlock=immediate) and holds exactly one writing connection. That is not a performance choice:
// a publication reads the revision it holds and writes its accepted successor in the same
// transaction, and two of them deciding against the same stale revision would both accept.
// Postgres's default isolation would allow exactly that, so every write here takes one
// transaction-scoped advisory lock first. It is the same rule, and it now holds across processes
// rather than only within one — two gateway instances, or a gateway and feed-gatewayctl, serialize
// against each other where two processes on one SQLite file could only fail fast.
//
// Writes are rare and bounded — a publisher publishing a document, an operator registering a
// server, a phone authorizing a binding — so one lock costs nothing that matters. Reads take no
// lock at all and run on their own pool, exactly as they do on the file.
//
// # Time, and why it is still an integer
//
// Every instant is the same millisecond integer the SQLite schema uses rather than a timestamptz.
// The rules that read them — a proposal's expiry, a notice's backoff, a binding's window, the
// three relay cutoffs — are comparisons the two stores must answer identically, and a port that
// changed the type would have changed what those comparisons mean on the way past. The column
// type is not where this schema's meaning lives.
//
// # Where the tables are
//
// In a schema of this service's own, never public. Supabase serves the public schema over PostgREST
// to anyone holding the project's publishable key, and this database holds credential hashes and
// the one value in the service that cannot be a hash — an installation's current push target. A
// schema PostgREST is not configured to expose is not reachable by that key at all; row-level
// security is enabled underneath it as well, so a later change that exposed the schema would still
// find no policy that lets anyone read a row.
package postgres

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/stdlib"
)

// Version is the schema this build writes and reads. A database written by a later version is
// refused rather than guessed at, for the reason the SQLite store refuses one: an old binary
// reading a new schema could silently ignore a column that a rule depends on.
//
// The lineage is this implementation's own and starts at 1. It is not SQLite's version 5 renamed:
// nothing has ever migrated between the two, and a Postgres database is created at the shape the
// current release needs rather than by replaying five years of somebody else's history.
const Version = 1

// Schema is the namespace every statement in this package qualifies. It is deliberately not
// public: see the package comment.
const Schema = "gateway"

// writeLock is the advisory lock every write transaction takes, so that one gateway's publication
// cannot decide against a revision another is in the middle of replacing. The number is arbitrary
// and fixed; what matters is that every writer uses the same one.
const writeLock = 0x53414347 // "SACG"

// ErrNewerSchema is returned by Open when the database was written by a later version of the
// gateway.
var ErrNewerSchema = errors.New("the database was written by a newer gateway")

// Store is the open database. Its zero value is not usable; call Open.
type Store struct {
	writer *sql.DB
	reader *sql.DB
	where  string
}

var (
	_ storage.GatewayStore        = (*Store)(nil)
	_ storage.PublisherAdminStore = (*Store)(nil)
	_ storage.PublicationTx       = (*Tx)(nil)
)

// Open connects to dsn, applies the schema, and returns the store. The caller closes it.
//
// dsn is a libpq URL. On Supabase it must be the session-mode pooler rather than the direct host:
// a direct connection resolves to IPv6 only, and the platforms this implementation exists for do
// not route it.
func Open(ctx context.Context, dsn string) (*Store, error) {
	config, err := pgx.ParseConfig(dsn)
	if err != nil {
		return nil, fmt.Errorf("read the database URL: %w", err)
	}
	// The application name is what an operator sees in pg_stat_activity, which is the difference
	// between "some client" and "the gateway" when two things share a project.
	if config.RuntimeParams == nil {
		config.RuntimeParams = map[string]string{}
	}
	config.RuntimeParams["application_name"] = "seeker-feed-gateway"

	writer := pool(config, 1)
	reader := pool(config, 4)
	opened := &Store{
		writer: writer,
		reader: reader,
		where:  fmt.Sprintf("%s:%d/%s", config.Host, config.Port, config.Database),
	}
	if err := writer.PingContext(ctx); err != nil {
		_ = opened.Close()
		return nil, fmt.Errorf("connect to %s: %w", opened.where, err)
	}
	if err := reader.PingContext(ctx); err != nil {
		_ = opened.Close()
		return nil, fmt.Errorf("connect to %s: %w", opened.where, err)
	}
	if err := opened.migrate(ctx); err != nil {
		_ = opened.Close()
		return nil, err
	}
	return opened, nil
}

// pool opens one connection pool from an already-parsed configuration, so the credentials are
// parsed once and the two pools cannot drift apart.
func pool(config *pgx.ConnConfig, most int) *sql.DB {
	db := stdlib.OpenDB(*config)
	db.SetMaxOpenConns(most)
	db.SetMaxIdleConns(1)
	// Supavisor closes idle connections on its own schedule, and a pooled connection that the
	// other end has already dropped fails the statement that finds out. Retiring them here first
	// keeps that discovery out of a publication's path.
	db.SetConnMaxIdleTime(2 * time.Minute)
	db.SetConnMaxLifetime(30 * time.Minute)
	return db
}

// Describe is the host and database the store opened, for a startup log line. It never contains
// the user or the password: pgx parsed those, and this is built from the parts that are not them.
func (s *Store) Describe() string { return s.where }

func (s *Store) Close() error {
	return errors.Join(s.reader.Close(), s.writer.Close())
}

// Write runs fn inside one transaction, which commits when fn returns nil and rolls back
// otherwise. The advisory lock is taken before fn runs and released by the commit or the rollback,
// so "the document and the notice that fans it out commit together" and "two publications cannot
// both decide against the same revision" are one mechanism rather than two habits.
func (s *Store) Write(ctx context.Context, fn func(storage.PublicationTx) error) error {
	return s.write(ctx, func(tx *Tx) error { return fn(tx) })
}

// write is the internal form used for operator transactions and the schema, which are not public
// gateway publications. It deliberately does not escape this implementation package.
func (s *Store) write(ctx context.Context, fn func(*Tx) error) error {
	transaction, err := s.writer.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin: %w", err)
	}
	if _, err := transaction.ExecContext(ctx, `SELECT pg_advisory_xact_lock($1)`, writeLock); err != nil {
		_ = transaction.Rollback()
		return fmt.Errorf("take the write lock: %w", err)
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

// Tx is the transaction Write hands to its caller: the writes, plus the reads a write has to make
// first — what the gateway holds, which is what the rules in internal/rules decide against.
type Tx struct{ tx *sql.Tx }

// migrate brings the database to Version, under the same advisory lock every write takes, so two
// gateways starting at once cannot both create the schema. The lock is what makes this safe to run
// on every start, which is what makes a deployment need no migration step of its own.
func (s *Store) migrate(ctx context.Context) error {
	return s.write(ctx, func(tx *Tx) error {
		if _, err := tx.tx.ExecContext(ctx,
			`CREATE SCHEMA IF NOT EXISTS `+Schema); err != nil {
			return fmt.Errorf("create the schema: %w", err)
		}
		if _, err := tx.tx.ExecContext(ctx,
			`CREATE TABLE IF NOT EXISTS `+Schema+`.schema_version (
			   singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
			   version   INTEGER NOT NULL
			 )`); err != nil {
			return fmt.Errorf("create the version record: %w", err)
		}
		// Sealed like every other table, and for the same reason rather than a different one. It
		// holds no state about anyone, so nothing is protected by this — but "which tables have
		// RLS" should not be a question with an interesting answer, and a table that is the
		// exception is a table somebody has to remember is the exception. Re-running it is a
		// no-op, which is why it sits here rather than in a migration.
		if _, err := tx.tx.ExecContext(ctx,
			`ALTER TABLE `+Schema+`.schema_version ENABLE ROW LEVEL SECURITY`); err != nil {
			return fmt.Errorf("seal the version record: %w", err)
		}
		var version int
		err := tx.tx.QueryRowContext(ctx,
			`SELECT version FROM `+Schema+`.schema_version`).Scan(&version)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return fmt.Errorf("read schema version: %w", err)
		}
		if version > Version {
			return fmt.Errorf("%w: found version %d, this build writes %d",
				ErrNewerSchema, version, Version)
		}
		for version < Version {
			migration := ""
			switch version + 1 {
			case 1:
				migration = schemaV1
			}
			if _, err := tx.tx.ExecContext(ctx, migration); err != nil {
				return fmt.Errorf("apply schema version %d: %w", version+1, err)
			}
			version++
		}
		if _, err := tx.tx.ExecContext(ctx,
			`INSERT INTO `+Schema+`.schema_version (singleton, version) VALUES (TRUE, $1)
			 ON CONFLICT (singleton) DO UPDATE SET version = EXCLUDED.version`,
			Version); err != nil {
			return fmt.Errorf("set schema version: %w", err)
		}
		return nil
	})
}

// schemaV1 is the shape the SQLite store reaches at its version 5, in this database's own types.
// The reasoning behind each table is in storage/sqlite, which is where the schema was argued out;
// what is written here is what is different and why.
//
// Row-level security is enabled on every table and no policy is created. Nothing the gateway does
// is affected — it connects as the owner, and an owner is not subject to its own policies — but a
// role that is, which is what a Supabase publishable key reaches the database as, finds a table it
// can read no row of. It is the second of the two defences described in the package comment, and
// it is the one that still holds if somebody exposes the schema later.
const schemaV1 = `
CREATE TABLE ` + Schema + `.publisher (
  server_id     TEXT PRIMARY KEY,
  label         TEXT NOT NULL,
  host          TEXT NOT NULL DEFAULT '',
  created_at_ms BIGINT NOT NULL,
  publishing    BOOLEAN NOT NULL DEFAULT TRUE,
  relaying      BOOLEAN NOT NULL DEFAULT FALSE
);

-- The SHA-256 of what a publisher authenticates with, never the credential. A publisher may hold
-- several at once, which is what makes rotation possible without an outage.
CREATE TABLE ` + Schema + `.publisher_credential (
  credential_hash BYTEA PRIMARY KEY,
  server_id       TEXT NOT NULL REFERENCES ` + Schema + `.publisher(server_id) ON DELETE CASCADE,
  label           TEXT NOT NULL,
  capability      TEXT NOT NULL DEFAULT 'publish',
  created_at_ms   BIGINT NOT NULL,
  revoked_at_ms   BIGINT
);
CREATE INDEX publisher_credential_by_server ON ` + Schema + `.publisher_credential(server_id);

CREATE TABLE ` + Schema + `.manifest (
  server_id         TEXT PRIMARY KEY REFERENCES ` + Schema + `.publisher(server_id) ON DELETE CASCADE,
  settings_revision BIGINT NOT NULL,
  document          BYTEA NOT NULL,
  updated_at_ms     BIGINT NOT NULL
);

-- The identity is (channel, proposal_id), which is what makes a republication an update rather
-- than a second proposal. cancelled is a boolean here rather than SQLite's 0/1 integer: the column
-- carries a fact, and this database has a type for it.
CREATE TABLE ` + Schema + `.proposal (
  channel       TEXT NOT NULL,
  proposal_id   TEXT NOT NULL,
  server_id     TEXT NOT NULL REFERENCES ` + Schema + `.publisher(server_id) ON DELETE CASCADE,
  revision      BIGINT NOT NULL,
  cancelled     BOOLEAN NOT NULL,
  expires_at_ms BIGINT NOT NULL,
  sequence      BIGINT NOT NULL,
  document      BYTEA NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  PRIMARY KEY (channel, proposal_id)
);
CREATE INDEX proposal_by_expiry ON ` + Schema + `.proposal(expires_at_ms);

CREATE TABLE ` + Schema + `.channel_sequence (
  channel  TEXT PRIMARY KEY,
  sequence BIGINT NOT NULL
);

-- The outbox, written in the same transaction as the document itself. One row per document and
-- not one per revision: two publications that have not been fanned out yet collapse into the
-- later one. The identity column is Postgres's own rather than SQLite's AUTOINCREMENT, and the
-- id is handed out by the database either way.
CREATE TABLE ` + Schema + `.notice (
  id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  channel       TEXT NOT NULL,
  kind          TEXT NOT NULL,
  proposal_id   TEXT NOT NULL,
  revision      BIGINT NOT NULL,
  sequence      BIGINT NOT NULL,
  created_at_ms BIGINT NOT NULL,
  attempts      INTEGER NOT NULL,
  ready_at_ms   BIGINT NOT NULL
);
CREATE UNIQUE INDEX notice_identity ON ` + Schema + `.notice(channel, kind, proposal_id);
CREATE INDEX notice_ready ON ` + Schema + `.notice(ready_at_ms);

-- The push relay (SEE-144). target is the one value in this database that cannot be a hash,
-- because delivery needs it; ownership is therefore the secret, which is stored as one.
CREATE TABLE ` + Schema + `.relay_installation (
  installation_id TEXT PRIMARY KEY,
  secret_hash     BYTEA NOT NULL UNIQUE,
  target          TEXT NOT NULL,
  created_at_ms   BIGINT NOT NULL,
  seen_at_ms      BIGINT NOT NULL
);
CREATE INDEX relay_installation_by_seen ON ` + Schema + `.relay_installation(seen_at_ms);

CREATE TABLE ` + Schema + `.relay_binding (
  handle_hash     BYTEA PRIMARY KEY,
  binding_id      TEXT NOT NULL UNIQUE,
  installation_id TEXT NOT NULL REFERENCES ` + Schema + `.relay_installation(installation_id) ON DELETE CASCADE,
  server_id       TEXT NOT NULL REFERENCES ` + Schema + `.publisher(server_id) ON DELETE CASCADE,
  connection_ref  TEXT NOT NULL,
  created_at_ms   BIGINT NOT NULL,
  expires_at_ms   BIGINT NOT NULL,
  revoked_at_ms   BIGINT,
  sends           BIGINT NOT NULL DEFAULT 0,
  last_sent_at_ms BIGINT
);
-- Partial on purpose: a phone that rebinds the same connection replaces its authorization instead
-- of adding a second one, so the set of live handles for one connection cannot grow without bound.
CREATE UNIQUE INDEX relay_binding_active
  ON ` + Schema + `.relay_binding(installation_id, server_id, connection_ref) WHERE revoked_at_ms IS NULL;
CREATE INDEX relay_binding_by_server ON ` + Schema + `.relay_binding(server_id);
CREATE INDEX relay_binding_by_expiry ON ` + Schema + `.relay_binding(expires_at_ms);

ALTER TABLE ` + Schema + `.publisher            ENABLE ROW LEVEL SECURITY;
ALTER TABLE ` + Schema + `.publisher_credential ENABLE ROW LEVEL SECURITY;
ALTER TABLE ` + Schema + `.manifest             ENABLE ROW LEVEL SECURITY;
ALTER TABLE ` + Schema + `.proposal             ENABLE ROW LEVEL SECURITY;
ALTER TABLE ` + Schema + `.channel_sequence     ENABLE ROW LEVEL SECURITY;
ALTER TABLE ` + Schema + `.notice               ENABLE ROW LEVEL SECURITY;
ALTER TABLE ` + Schema + `.relay_installation   ENABLE ROW LEVEL SECURITY;
ALTER TABLE ` + Schema + `.relay_binding        ENABLE ROW LEVEL SECURITY;
`

func milliseconds(at time.Time) int64 { return at.UTC().UnixMilli() }

func instant(milliseconds int64) time.Time {
	return time.UnixMilli(milliseconds).UTC()
}

// querier is the half of database/sql that a read needs, so a read can be written once and run
// either on a pool or inside a write transaction.
type querier interface {
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

type scanner interface{ Scan(into ...any) error }

// one turns "the update matched nothing" into the caller's own error, the way the SQLite store's
// does: the row is found and changed in one statement, or it was not this caller's row.
func one(outcome sql.Result, absent error) error {
	changed, err := outcome.RowsAffected()
	if err != nil {
		return err
	}
	if changed == 0 {
		return absent
	}
	return nil
}
