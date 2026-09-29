// Package sqlite is the feed gateway's sole storage implementation and the only place in it that
// speaks SQL (SEE-133, docs/wiki/feed-gateway.md#where-it-is-kept).
//
// # What is here, and what is deliberately not
//
// The public half is shared publications and publisher configuration: a manifest, proposals, the
// per-channel sequence, publisher credential hashes, and pending fan-out notices. Those tables
// still have no subscriber column and a public read still writes nothing.
//
// Schema version 3 removes the retired gateway-private routing tables; version 4 adds a
// publisher's developer-supplied host as a column on the registration it belongs to (SEE-141);
// version 5 adds capabilities to a registration and its credentials, and the two records the push
// relay needs (SEE-144); version 8 adds whether the operator lists a feed in the app's Discover
// catalog, and its public description (SEE-176).
//
// The relay's two tables are the first private state this store has held since version 3 retired
// the old routing, and they are deliberately not a return of it. What was removed routed requests
// and approvals: a server's document, an owner's decision, a result. What is added routes a
// wake-up — an installation identity, where to send to, and which server a device agreed may send.
// No request, no approval, no signature and nothing an owner decided is stored here, and a
// boundary test pins the whole schema so a column that could carry one would have to be argued for
// by name.
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
// feed-gatewayctl, registering a publisher while the server runs — fails fast instead of deadlocking
// half way through one.
package sqlite

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/url"
	"path/filepath"
	"time"

	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
	"google.golang.org/protobuf/proto"

	_ "modernc.org/sqlite" // the pure-Go SQLite driver, registered as "sqlite"
)

// Version is the schema this build writes and reads. There is one, and a file from a later version
// is refused rather than guessed at: an old binary reading a new file could silently ignore a
// column that a rule depends on.
const Version = 8

// ErrNewerSchema is returned by Open when the file was written by a later version of the gateway.
var ErrNewerSchema = errors.New("the database was written by a newer gateway")

// Store is the open database. Its zero value is not usable; call Open.
type Store struct {
	writer *sql.DB
	reader *sql.DB
	path   string
}

var (
	_ storage.GatewayStore        = (*Store)(nil)
	_ storage.PublisherAdminStore = (*Store)(nil)
	_ storage.PublicationTx       = (*Tx)(nil)
)

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
	opened := &Store{writer: writer, reader: reader, path: absolute}
	if err := opened.migrate(context.Background()); err != nil {
		_ = opened.Close()
		return nil, err
	}
	return opened, nil
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
func (s *Store) Write(ctx context.Context, fn func(storage.PublicationTx) error) error {
	return s.write(ctx, func(tx *Tx) error { return fn(tx) })
}

// write is the SQLite-internal form used for schema and operator transactions that are not public
// gateway publications. It deliberately does not escape this implementation package.
func (s *Store) write(ctx context.Context, fn func(*Tx) error) error {
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
	return s.write(ctx, func(tx *Tx) error {
		var version int
		err := tx.tx.QueryRowContext(ctx, `PRAGMA user_version`).Scan(&version)
		if err != nil {
			return fmt.Errorf("read schema version: %w", err)
		}
		if version > Version {
			return fmt.Errorf("%w: found version %d, this build writes %d",
				ErrNewerSchema, version, Version)
		}
		for version < Version {
			var migration string
			switch version + 1 {
			case 1:
				migration = schemaV1
			case 2:
				migration = schemaV2
			case 3:
				if err := retirePrivateManifests(ctx, tx.tx); err != nil {
					return fmt.Errorf("retire gateway-private manifests: %w", err)
				}
				migration = schemaV3
			case 4:
				migration = schemaV4
			case 5:
				migration = schemaV5
			case 6:
				migration = schemaV6
			case 7:
				migration = schemaV7
			case 8:
				migration = schemaV8
			}
			if _, err := tx.tx.ExecContext(ctx, migration); err != nil {
				return fmt.Errorf("apply schema version %d: %w", version+1, err)
			}
			version++
		}
		// PRAGMA user_version takes no parameter, and Version is a constant in this file.
		if _, err := tx.tx.ExecContext(ctx,
			fmt.Sprintf(`PRAGMA user_version = %d`, Version)); err != nil {
			return fmt.Errorf("set schema version: %w", err)
		}
		return nil
	})
}

// retirePrivateManifests recognizes the one legacy numeric mode needed for migration. It neither
// recreates that mode in the active protocol nor maps the manifest to a feed: the old private
// document is removed, while every public feed manifest remains byte-for-byte unchanged.
func retirePrivateManifests(ctx context.Context, tx *sql.Tx) error {
	rows, err := tx.QueryContext(ctx, `SELECT server_id, document FROM manifest`)
	if err != nil {
		return err
	}
	var retired []string
	for rows.Next() {
		var serverID string
		var document []byte
		if err := rows.Scan(&serverID, &document); err != nil {
			_ = rows.Close()
			return err
		}
		var manifest serverv1.ServerManifest
		if err := proto.Unmarshal(document, &manifest); err != nil {
			_ = rows.Close()
			return fmt.Errorf("decode manifest for %s: %w", serverID, err)
		}
		if int32(manifest.GetMode()) == 3 {
			retired = append(retired, serverID)
		}
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return err
	}
	if err := rows.Close(); err != nil {
		return err
	}
	for _, serverID := range retired {
		if _, err := tx.ExecContext(ctx, `DELETE FROM manifest WHERE server_id = ?`, serverID); err != nil {
			return err
		}
	}
	return nil
}

// The schema, in one statement per table and with the reasoning where a column carries a rule.
// Migrations are append-only and applied in order, the way the sidecar's migrations are.
const schemaV1 = `
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
-- invalidations for the same document already collapse under one key (SAW-056).
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

// Version 2 adds gateway-private onboarding. Unlike a public feed, this is intentionally the
// minimum association needed to route one server's requests to one device. user_ref is scoped by
// server_id; no wallet, account, amount, decision, history, or SAC-wide identity is stored here.
const schemaV2 = `
CREATE TABLE invitation (
  invitation_id  TEXT PRIMARY KEY,
  token_hash     BLOB NOT NULL UNIQUE,
  server_id      TEXT NOT NULL REFERENCES publisher(server_id) ON DELETE CASCADE,
  user_ref       TEXT NOT NULL,
  created_at_ms  INTEGER NOT NULL,
  expires_at_ms  INTEGER NOT NULL,
  revoked_at_ms  INTEGER,
  redeemed_at_ms INTEGER,
  connection_id  TEXT
);
CREATE INDEX invitation_by_server ON invitation(server_id, invitation_id);
CREATE INDEX invitation_by_expiry ON invitation(expires_at_ms);

CREATE TABLE device_binding (
  connection_id  TEXT PRIMARY KEY,
  server_id      TEXT NOT NULL REFERENCES publisher(server_id) ON DELETE CASCADE,
  user_ref       TEXT NOT NULL,
  credential_hash BLOB NOT NULL UNIQUE,
  device_name    TEXT NOT NULL,
  created_at_ms  INTEGER NOT NULL,
  revoked_at_ms  INTEGER,
  sequence       INTEGER NOT NULL DEFAULT 0
);
-- A server-scoped user may have several devices. Each is a separate binding created by a separate
-- single-use invitation, addressed by its own connection ID, and revoked independently.
CREATE INDEX active_binding_by_recipient
  ON device_binding(server_id, user_ref) WHERE revoked_at_ms IS NULL;
CREATE INDEX binding_by_server ON device_binding(server_id, connection_id);

CREATE TABLE private_request (
  server_id       TEXT NOT NULL REFERENCES publisher(server_id) ON DELETE CASCADE,
  request_id      TEXT NOT NULL,
  user_ref        TEXT NOT NULL,
  connection_id   TEXT NOT NULL REFERENCES device_binding(connection_id),
  revision        INTEGER NOT NULL,
  cancelled       INTEGER NOT NULL,
  expires_at_ms   INTEGER NOT NULL,
  sequence        INTEGER NOT NULL,
  document        BLOB NOT NULL,
  result           BLOB,
  updated_at_ms   INTEGER NOT NULL,
  PRIMARY KEY (server_id, request_id)
);
CREATE INDEX private_request_by_device
  ON private_request(connection_id, request_id);
CREATE INDEX private_request_by_expiry ON private_request(expires_at_ms);
`

// Version 3 retires gateway-private routing. The order satisfies the private request's foreign key
// to its device binding, and SQLite drops each table's indexes with it. The migration runs in the
// same transaction as the version stamp, preserving every public row, sequence and pending notice.
const schemaV3 = `
DROP TABLE private_request;
DROP TABLE invitation;
DROP TABLE device_binding;
`

// Version 4 adds the developer-supplied host of a publisher's own backend (SEE-141). It is a
// column rather than a table on purpose: the live schema stays the six public-feed tables a
// boundary test pins, and a host is one more fact about a registration rather than a new kind of
// state.
//
// It is administrative metadata and nothing more. The gateway never fetches it, no phone is ever
// told to contact it, and it is not what authenticates a publication — the credential is. Every
// registration made before this version keeps the empty default, which is why the column is NOT
// NULL with one rather than nullable: "no host was given" and "the host is nothing" are the same
// fact, and two spellings of it would be two branches in every reader.
const schemaV4 = `
ALTER TABLE publisher ADD COLUMN host TEXT NOT NULL DEFAULT '';
`

// Version 5 is the push relay (SEE-144): what a registered server is allowed to do, and the two
// records that let one wake a phone it has never been given a target for.
//
// # Capabilities
//
// A registration gains two switches and a credential gains the one thing it may be used for.
// Every row that already exists becomes exactly what it already was — publishing enabled, relay
// not, every credential a publishing credential — so SEE-141's behaviour is unchanged by the
// migration rather than restored by a special case. The two are separate columns rather than one
// mode because a server may do both, and separate from the credential's capability because
// enabling is the operator's reversible switch while revoking is the credential's end.
//
// # Installations
//
// One row per app installation that asked this gateway to route for it. It holds an opaque
// identity the gateway minted, the SHA-256 of the secret that proves ownership of it, and the
// current FCM target.
//
// The target is the one value in this database that cannot be a hash, because delivery needs it.
// That is why ownership is a secret rather than the target itself: if knowing a target were enough
// to change where it points, anyone who saw one could redirect a phone's wake-ups. It is also why
// no read outside internal/pushrelay returns it.
//
// There is no owner, no account, no device name and no wallet here. The gateway learns that an
// installation exists and where to wake it; that is the whole of what routing needs.
//
// # Bindings
//
// One row per authorization: this installation agreed that this registered server may wake it, for
// one of the connections the phone holds directly. The push handle the server presents is stored
// only as its SHA-256, beside an opaque binding ID that is safe to name in a log or a listing.
//
// The active index is partial on purpose. A phone that rebinds the same connection — after a
// reconnection, a reinstall, or a gateway that lost its file — replaces its authorization instead
// of adding a second one, so the set of live handles for one connection cannot grow without bound.
// Revoked rows stay until the sweep so a revocation is visible while it matters.
//
// connection_ref is the phone's own identifier for the direct connection. It is never parsed,
// never resolved and never shown to a server: it exists so a phone can reconcile its own bindings
// without the gateway keeping a second index of anything.
const schemaV5 = `
ALTER TABLE publisher ADD COLUMN publishing INTEGER NOT NULL DEFAULT 1;
ALTER TABLE publisher ADD COLUMN relaying INTEGER NOT NULL DEFAULT 0;
ALTER TABLE publisher_credential ADD COLUMN capability TEXT NOT NULL DEFAULT 'publish';

CREATE TABLE relay_installation (
  installation_id TEXT PRIMARY KEY,
  secret_hash     BLOB NOT NULL UNIQUE,
  -- The current FCM target, or '' when none is held: "no target" and "the target is nothing" are
  -- the same fact, and two spellings of it would be two branches in every reader.
  target          TEXT NOT NULL,
  created_at_ms   INTEGER NOT NULL,
  -- The last time the installation authenticated. It is what the idle sweep reads, so a device
  -- that was wiped or reinstalled stops holding a grant without anyone having to notice.
  seen_at_ms      INTEGER NOT NULL
);
CREATE INDEX relay_installation_by_seen ON relay_installation(seen_at_ms);

CREATE TABLE relay_binding (
  handle_hash     BLOB PRIMARY KEY,
  binding_id      TEXT NOT NULL UNIQUE,
  installation_id TEXT NOT NULL REFERENCES relay_installation(installation_id) ON DELETE CASCADE,
  server_id       TEXT NOT NULL REFERENCES publisher(server_id) ON DELETE CASCADE,
  connection_ref  TEXT NOT NULL,
  created_at_ms   INTEGER NOT NULL,
  expires_at_ms   INTEGER NOT NULL,
  revoked_at_ms   INTEGER,
  sends           INTEGER NOT NULL DEFAULT 0,
  last_sent_at_ms INTEGER
);
CREATE UNIQUE INDEX relay_binding_active
  ON relay_binding(installation_id, server_id, connection_ref) WHERE revoked_at_ms IS NULL;
CREATE INDEX relay_binding_by_server ON relay_binding(server_id);
CREATE INDEX relay_binding_by_expiry ON relay_binding(expires_at_ms);
`

// Version 6 is publisher presence (SEE-150): when a publisher last told this gateway that its own
// server is running.
//
// It is a column on the registration for the same reason the host is: the live schema stays the
// tables a boundary test pins, and this is one more fact about a registration rather than a new
// kind of state. The gateway still never contacts a publisher's host — presence is pushed, by every
// authenticated call the publisher makes, and by PublisherService.Heartbeat when it has nothing to
// publish.
//
// It is nullable, and that is the opposite of the choice schemaV4 made for the host. There, "no
// host was given" and "the host is nothing" were the same fact. Here they are not: a registration
// that has never checked in is not one that checked in at the epoch, and a NOT NULL DEFAULT 0 would
// make every publisher registered before this version look like one that was running in 1970 and
// stopped. Both read as offline today, but only one of them would still be wrong if the window ever
// grew.
//
// Nothing about a *reader* is recorded here, and nothing can be: the column is on the publisher,
// written on the publisher listener, where every caller has already been authenticated as exactly
// one registered server.
const schemaV6 = `
ALTER TABLE publisher ADD COLUMN last_seen_at_ms INTEGER;
`

// Version 7 is restricted feeds (SEE-156): who may read a feed, and which approved devices may.
//
// # The policy
//
// Three columns on the registration, because a policy is one more fact the operator records about
// a publisher. Every registration that already exists becomes exactly what it was — public, with no
// authentication origin — so migrating changes no feed's behaviour. The epoch counts a restricted
// channel's stream names and moves on every revocation (internal/gateway/access.go).
//
// # Grants
//
// One row per approved device, written by the publisher over its authenticated API and scoped to
// its own server. It is the least a gateway needs to enforce a publisher's decision: the grant's
// identity, two references the publisher chose and the gateway never interprets, the SHA-256 of the
// session the device presents — never the session itself — and when the grant runs until. There is
// no wallet, no address, no signature, no device name and no decision here: the publisher saw all
// of that and kept it; the gateway enforces the outcome.
//
// push_target is the one value that cannot be a hash, for the reason relay_installation.target
// cannot: delivery needs it. It is routing data for this grant only, set under the grant's own
// session, never returned by a read outside the relay, and gone when the grant is swept.
//
// A revoked grant is kept, not deleted, until the sweep: a renewal that arrives after a revocation
// is refused by name, which is what makes revocation final rather than a race.
const schemaV7 = `
ALTER TABLE publisher ADD COLUMN access_policy TEXT NOT NULL DEFAULT 'public';
ALTER TABLE publisher ADD COLUMN auth_origin TEXT NOT NULL DEFAULT '';
ALTER TABLE publisher ADD COLUMN access_epoch INTEGER NOT NULL DEFAULT 0;

CREATE TABLE access_grant (
  grant_id       TEXT PRIMARY KEY,
  server_id      TEXT NOT NULL REFERENCES publisher(server_id) ON DELETE CASCADE,
  subscriber_ref TEXT NOT NULL,
  device_ref     TEXT NOT NULL,
  session_digest BLOB NOT NULL UNIQUE,
  created_at_ms  INTEGER NOT NULL,
  renewed_at_ms  INTEGER NOT NULL,
  expires_at_ms  INTEGER NOT NULL,
  revoked_at_ms  INTEGER,
  push_target    TEXT NOT NULL DEFAULT ''
);
CREATE INDEX access_grant_by_server ON access_grant(server_id);
CREATE INDEX access_grant_by_expiry ON access_grant(expires_at_ms);
`

// Version 8 is the app's Discover catalog (SEE-176): whether the operator lists a feed there, and
// the public description it is listed with.
//
// Two columns on the registration, because a listing is one more fact the operator records about a
// publisher, beside its access policy and apart from it. Every registration that already exists
// becomes unlisted with no description, so migrating shows nothing in anyone's catalog until an
// operator opts a feed in. The description is not the label or the host: those are the operator's
// notes and are never served, while this is written to be read by anyone browsing the catalog.
//
// Nothing a publisher publishes writes either column. A manifest lives in its own table, so a
// republication cannot reset what the operator chose here.
const schemaV8 = `
ALTER TABLE publisher ADD COLUMN show_in_recommendations INTEGER NOT NULL DEFAULT 0;
ALTER TABLE publisher ADD COLUMN public_description TEXT NOT NULL DEFAULT '';
`

func milliseconds(at time.Time) int64 { return at.UTC().UnixMilli() }

func instant(milliseconds int64) time.Time {
	return time.UnixMilli(milliseconds).UTC()
}
