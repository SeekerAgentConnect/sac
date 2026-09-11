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
];
