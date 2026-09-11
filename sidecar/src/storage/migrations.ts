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
];
