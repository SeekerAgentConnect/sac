/**
 * The sidecar database's schema, as numbered migrations. `openDatabase` applies the ones a database
 * doesn't have yet, in order. Append a migration for every schema change, and never edit one that
 * has shipped: existing databases already carry it, and the fixture in `fixtures/` freezes v1.
 */

export interface Migration {
  /** 1 for the first migration, then one more for each. */
  readonly version: number;
  readonly description: string;
  readonly sql: string;
}

export const MIGRATIONS: readonly Migration[] = [
  {
    version: 1,
    description:
      "connections, requests, results, prepared transactions, and idempotency keys",
    sql: `
      CREATE TABLE connections (
        connection_id TEXT PRIMARY KEY NOT NULL,
        created_at_ms INTEGER NOT NULL,
        revoked_at_ms INTEGER
      ) STRICT;

      -- action and outcome hold the Protobuf binary of Action and Outcome; state is RequestState.
      CREATE TABLE requests (
        request_id TEXT PRIMARY KEY NOT NULL,
        connection_id TEXT NOT NULL REFERENCES connections (connection_id),
        kind TEXT NOT NULL,
        action BLOB NOT NULL,
        agent_note TEXT NOT NULL,
        state INTEGER NOT NULL,
        created_at_ms INTEGER NOT NULL,
        expires_at_ms INTEGER NOT NULL,
        updated_at_ms INTEGER NOT NULL,
        outcome BLOB
      ) STRICT;
      CREATE INDEX requests_by_connection_and_state
        ON requests (connection_id, state, created_at_ms, request_id);
      CREATE INDEX requests_by_state_and_expiry ON requests (state, expires_at_ms);

      -- One row per idempotency key, for the whole sidecar: the agent's scope.
      CREATE TABLE idempotency_keys (
        idempotency_key TEXT PRIMARY KEY NOT NULL,
        fingerprint TEXT NOT NULL,
        request_id TEXT NOT NULL UNIQUE REFERENCES requests (request_id),
        created_at_ms INTEGER NOT NULL
      ) STRICT;

      -- Each preparation of a transfer or swap (Stage 4), as PreparedTransaction binary.
      CREATE TABLE prepared_transactions (
        request_id TEXT NOT NULL REFERENCES requests (request_id),
        version INTEGER NOT NULL,
        prepared BLOB NOT NULL,
        created_at_ms INTEGER NOT NULL,
        PRIMARY KEY (request_id, version)
      ) STRICT;

      -- Every result the phone submitted and the sidecar accepted, as SubmitResultRequest binary
      -- with only the result set. A repeat of one of them changes nothing.
      CREATE TABLE results (
        request_id TEXT NOT NULL REFERENCES requests (request_id),
        seq INTEGER NOT NULL,
        result BLOB NOT NULL,
        from_state INTEGER NOT NULL,
        to_state INTEGER NOT NULL,
        created_at_ms INTEGER NOT NULL,
        PRIMARY KEY (request_id, seq)
      ) STRICT;
    `,
  },
  {
    version: 2,
    description:
      "pairing: the sidecar's ID, phone credentials, and pairing tokens",
    sql: `
      -- The sidecar's lasting ID, created on first use and shown in every pairing code.
      CREATE TABLE server (
        singleton INTEGER PRIMARY KEY NOT NULL CHECK (singleton = 1),
        server_id TEXT NOT NULL,
        created_at_ms INTEGER NOT NULL
      ) STRICT;

      -- A paired phone's credential, as SHA-256, and the name the phone gave. SAW-010's stand-in
      -- connection has neither.
      ALTER TABLE connections ADD COLUMN credential_hash BLOB;
      ALTER TABLE connections ADD COLUMN device_name TEXT NOT NULL DEFAULT '';
      CREATE UNIQUE INDEX connections_by_credential ON connections (credential_hash)
        WHERE credential_hash IS NOT NULL;

      -- One-use pairing tokens, as SHA-256, each bound to the URL its pairing code shows.
      CREATE TABLE pairing_tokens (
        token_hash BLOB PRIMARY KEY NOT NULL,
        server_url TEXT NOT NULL,
        created_at_ms INTEGER NOT NULL,
        expires_at_ms INTEGER NOT NULL,
        used_at_ms INTEGER,
        connection_id TEXT REFERENCES connections (connection_id)
      ) STRICT;

      -- Only a paired phone may act for a connection now. SAW-010's stand-in connection is revoked,
      -- and its PENDING (1) requests become CANCELLED (7), as a revocation cancels them. The
      -- outcome is the Outcome binary with detail "The phone's connection was revoked."
      UPDATE connections SET revoked_at_ms = CAST(unixepoch('subsec') * 1000 AS INTEGER)
        WHERE credential_hash IS NULL AND revoked_at_ms IS NULL;
      UPDATE requests
        SET state = 7,
          outcome = X'1A235468652070686F6E65277320636F6E6E656374696F6E20776173207265766F6B65642E',
          updated_at_ms = CAST(unixepoch('subsec') * 1000 AS INTEGER)
        WHERE state = 1
          AND connection_id IN (SELECT connection_id FROM connections WHERE revoked_at_ms IS NOT NULL);
    `,
  },
  {
    version: 3,
    description: "the wallet and network the phone's owner selected (SAW-015)",
    sql: `
      -- The connection's wallet binding, as the phone published it (RequestService.PublishWallet).
      -- All three are set together, or all three are NULL: no wallet is connected. wallet_address
      -- is a base58 public key, and wallet_network is a Network enum value. No key material and no
      -- wallet authorization token is ever stored here.
      ALTER TABLE connections ADD COLUMN wallet_address TEXT;
      ALTER TABLE connections ADD COLUMN wallet_network INTEGER;
      ALTER TABLE connections ADD COLUMN wallet_bound_at_ms INTEGER;
    `,
  },
  {
    version: 4,
    description:
      "durable request revisions, update replay, and frozen sync snapshots (SAW-049)",
    sql: `
      -- Every current request has a monotonic revision. Existing requests begin at one and enter
      -- a fresh phone through Sync; later mutations increment the revision and append an event in
      -- the same transaction as the request row.
      ALTER TABLE requests ADD COLUMN update_revision INTEGER NOT NULL DEFAULT 1;

      -- Cursors are per connection. pruned_through makes a retained-history gap distinguishable
      -- from an empty replay after old events have been bounded away.
      CREATE TABLE update_state (
        connection_id TEXT PRIMARY KEY NOT NULL REFERENCES connections (connection_id),
        next_sequence INTEGER NOT NULL DEFAULT 0,
        pruned_through INTEGER NOT NULL DEFAULT 0,
        confirmation_offset INTEGER NOT NULL DEFAULT 0
      ) STRICT;

      -- payload is an ActionRequest for kind=request; revocation has no payload. The event row is
      -- committed atomically with the request/connection mutation it reports.
      CREATE TABLE update_events (
        connection_id TEXT NOT NULL REFERENCES connections (connection_id),
        sequence INTEGER NOT NULL,
        kind TEXT NOT NULL CHECK (kind IN ('request', 'revoked')),
        request_id TEXT,
        revision INTEGER,
        payload BLOB,
        created_at_ms INTEGER NOT NULL,
        PRIMARY KEY (connection_id, sequence),
        CHECK (
          (kind = 'request' AND request_id IS NOT NULL AND revision IS NOT NULL AND payload IS NOT NULL)
          OR (kind = 'revoked' AND request_id IS NULL AND revision IS NULL AND payload IS NULL)
        )
      ) STRICT;

      -- A Sync snapshot is frozen on disk so a large pending queue does not become a large heap
      -- allocation and mutations between pages cannot change what later pages mean. The process
      -- instance binds every token, so rows left by a crash are invalid and cleaned on next use.
      CREATE TABLE update_snapshots (
        snapshot_id TEXT PRIMARY KEY NOT NULL,
        connection_id TEXT NOT NULL REFERENCES connections (connection_id),
        server_instance_id TEXT NOT NULL,
        snapshot_sequence INTEGER NOT NULL,
        expires_at_ms INTEGER NOT NULL,
        created_at_ms INTEGER NOT NULL
      ) STRICT;
      CREATE INDEX update_snapshots_by_connection
        ON update_snapshots (connection_id, created_at_ms, snapshot_id);

      -- kind 1 is SyncedRequest and kind 2 is RequestRemoved, each as protobuf binary.
      CREATE TABLE update_snapshot_items (
        snapshot_id TEXT NOT NULL REFERENCES update_snapshots (snapshot_id) ON DELETE CASCADE,
        ordinal INTEGER NOT NULL,
        kind INTEGER NOT NULL CHECK (kind IN (1, 2)),
        revision INTEGER NOT NULL,
        payload BLOB NOT NULL,
        PRIMARY KEY (snapshot_id, ordinal)
      ) STRICT;

      CREATE TABLE update_snapshot_deferred (
        snapshot_id TEXT NOT NULL REFERENCES update_snapshots (snapshot_id) ON DELETE CASCADE,
        ordinal INTEGER NOT NULL,
        connection_id TEXT NOT NULL,
        request_id TEXT NOT NULL,
        PRIMARY KEY (snapshot_id, ordinal)
      ) STRICT;
    `,
  },
  {
    version: 5,
    description:
      "one current FCM direct-send target per paired connection (SAW-055)",
    sql: `
      -- The current opaque FCM target must be available to the later sender, so unlike a bearer
      -- credential it cannot be hashed. It is private deployment data: no RPC returns it and no log
      -- names it. Revocation clears it in the same transaction that ends the connection.
      ALTER TABLE connections ADD COLUMN fcm_token TEXT;
    `,
  },
  {
    version: 6,
    description: "the settings revision the server manifest publishes (SEE-88)",
    sql: `
      -- The revision in this server's manifest, and a fingerprint of the content it was computed
      -- for. A phone caches a manifest by identity and revision, so the revision has to change
      -- exactly when the manifest's content does and never go backwards: startup compares the
      -- fingerprint and bumps the revision when it differs. It counts settings changes, not time,
      -- and it is not a version of the sidecar's software.
      ALTER TABLE server ADD COLUMN manifest_revision INTEGER NOT NULL DEFAULT 0;
      ALTER TABLE server ADD COLUMN manifest_fingerprint TEXT;
    `,
  },
  {
    version: 7,
    description:
      "the gateway push handle a phone authorized for one connection (SEE-144)",
    sql: `
      -- A column of its own rather than a second meaning for fcm_token, because the two are not
      -- the same kind of value. An FCM target addresses a device and works for whoever holds it; a
      -- relay handle addresses one authorization at one gateway and is worth nothing without that
      -- gateway's relay credential. A server in relay mode has a handle and no target, one in
      -- direct mode has a target and no handle, and neither can be mistaken for the other by a
      -- query. Like the target it cannot be hashed — the send needs it — it is never returned by
      -- an RPC or named in a log, and revocation clears it in the same statement that ends the
      -- connection.
      ALTER TABLE connections ADD COLUMN relay_handle TEXT;
    `,
  },
];
