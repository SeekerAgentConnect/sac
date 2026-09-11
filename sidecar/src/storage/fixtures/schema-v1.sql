-- A sidecar database at schema version 1, frozen when SAW-010 shipped. database.test.ts checks
-- that its schema is exactly migration 1's, and that the current sidecar opens it and keeps its
-- data. Never edit this file: when the schema changes, add a fixture for the new version.
BEGIN;

CREATE TABLE connections (
        connection_id TEXT PRIMARY KEY NOT NULL,
        created_at_ms INTEGER NOT NULL,
        revoked_at_ms INTEGER
      ) STRICT;

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

CREATE TABLE idempotency_keys (
        idempotency_key TEXT PRIMARY KEY NOT NULL,
        fingerprint TEXT NOT NULL,
        request_id TEXT NOT NULL UNIQUE REFERENCES requests (request_id),
        created_at_ms INTEGER NOT NULL
      ) STRICT;

CREATE TABLE prepared_transactions (
        request_id TEXT NOT NULL REFERENCES requests (request_id),
        version INTEGER NOT NULL,
        prepared BLOB NOT NULL,
        created_at_ms INTEGER NOT NULL,
        PRIMARY KEY (request_id, version)
      ) STRICT;

CREATE TABLE results (
        request_id TEXT NOT NULL REFERENCES requests (request_id),
        seq INTEGER NOT NULL,
        result BLOB NOT NULL,
        from_state INTEGER NOT NULL,
        to_state INTEGER NOT NULL,
        created_at_ms INTEGER NOT NULL,
        PRIMARY KEY (request_id, seq)
      ) STRICT;

-- The connection made with the database, at 2026-09-11T12:00:00Z.
INSERT INTO connections (connection_id, created_at_ms, revoked_at_ms)
VALUES ('5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f', 1789128000000, NULL);

-- A PENDING ack ("Deploy finished") due a day later, and a COMPLETED one ("Hello from schema
-- version 1") that the phone acknowledged a minute in. action is the Action's Protobuf binary.
INSERT INTO requests (request_id, connection_id, kind, action, agent_note, state,
  created_at_ms, expires_at_ms, updated_at_ms, outcome)
VALUES
  ('3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c', '5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f', 'ack',
    X'0A110A0F4465706C6F792066696E6973686564', 'Written by schema version 1', 1,
    1789128000000, 1789214400000, 1789128000000, NULL),
  ('8e1d2c3b-4a5f-4e6d-9c8b-7a6f5e4d3c2b', '5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f', 'ack',
    X'0A1D0A1B48656C6C6F2066726F6D20736368656D612076657273696F6E2031', '', 5,
    1789128001000, 1789214401000, 1789128060000, NULL);

-- Fingerprints are the SHA-256 of each action's binary, in hex.
INSERT INTO idempotency_keys (idempotency_key, fingerprint, request_id, created_at_ms)
VALUES
  ('fixture-pending', '4e30dec0b4dbc2568ad8388b04cffc440c0bf1e789c12a7a22923341a01ed058',
    '3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c', 1789128000000),
  ('fixture-completed', 'e64fa5e547c47e96b0b5abcc0575749f4acf52200c4c19a3ea8174d4593a8bf7',
    '8e1d2c3b-4a5f-4e6d-9c8b-7a6f5e4d3c2b', 1789128001000);

-- The phone's accepted acknowledgement: SubmitResultRequest {acknowledgement {}}, PENDING to COMPLETED.
INSERT INTO results (request_id, seq, result, from_state, to_state, created_at_ms)
VALUES ('8e1d2c3b-4a5f-4e6d-9c8b-7a6f5e4d3c2b', 1, X'1200', 1, 5, 1789128060000);

PRAGMA user_version = 1;
COMMIT;
