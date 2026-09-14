import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { create } from "@bufbuild/protobuf";

import {
  ActionSchema,
  RequestError,
  RequestState,
} from "../gen/seekervault/request/v1/request_pb.js";
import { SubmitResultRequestSchema } from "../gen/seekervault/request/v1/service_pb.js";
import { canTransition, type ActionKind } from "../requests/lifecycle.ts";
import { RequestFailure } from "../requests/failure.ts";
import { IN_MEMORY, openDatabase, type DatabaseSync } from "./database.ts";
import { temporaryDatabasePath } from "../testing/process.ts";
import {
  invalidFcmTokenReason,
  MAX_FCM_TOKEN_BYTES,
  PairingStore,
  REVOKED_DETAIL,
} from "./pairing-store.ts";
import { RequestStore, type NewRequest } from "./request-store.ts";
import { isSecret } from "../pairing/uri.ts";

const NOON = Date.UTC(2026, 8, 11, 12); // 2026-09-11T12:00:00Z
const URL_A = "https://vault.example.com";
const URL_B = "https://other.example.com";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function setup(path = IN_MEMORY) {
  const clock = { now: NOON };
  const db = openDatabase(path);
  const pairing = new PairingStore(db, { now: () => clock.now });
  const requests = new RequestStore(db, {
    defaultTtlSeconds: 86_400,
    pendingLimit: 100,
    now: () => clock.now,
  });
  return { clock, db, pairing, requests };
}

function refused(error: RequestError): (thrown: unknown) => boolean {
  return (thrown) => thrown instanceof RequestFailure && thrown.error === error;
}

function ack(
  text: string,
  key: string,
  fields: Partial<NewRequest> = {},
): NewRequest {
  return {
    action: create(ActionSchema, { kind: { case: "ack", value: { text } } }),
    agentNote: "",
    idempotencyKey: key,
    ...fields,
  };
}

/** Issues a code for `url` and pairs with it. */
function pairWith(pairing: PairingStore, url = URL_A, name = "Seeker") {
  const { token } = pairing.issue(url, 600);
  return pairing.pair(token, url, name);
}

/** Every text and blob in a table, to look for a secret in it. */
function contents(db: DatabaseSync, table: string): string {
  return db
    .prepare(`SELECT * FROM ${table}`)
    .all()
    .flatMap((row) => Object.values(row))
    .map((value) =>
      value instanceof Uint8Array
        ? Buffer.from(value).toString("latin1")
        : String(value),
    )
    .join("\n");
}

describe("PairingStore: pairing tokens", () => {
  it("exchanges a code once for a new connection and its credential", () => {
    const { pairing } = setup();
    const issued = pairing.issue(URL_A, 600);
    assert.ok(isSecret(issued.token));
    assert.equal(issued.serverUrl, URL_A);
    assert.equal(issued.expiresAtMs, NOON + 600_000);
    assert.equal(issued.replaces, undefined);
    const paired = pairing.pair(issued.token, URL_A, "Seeker");
    assert.match(paired.connectionId, UUID);
    assert.ok(isSecret(paired.phoneToken));
    assert.equal(paired.serverId, issued.serverId);
    assert.deepEqual(paired.revoked, []);
    assert.equal(pairing.authenticate(paired.phoneToken), paired.connectionId);
    assert.deepEqual(pairing.activeConnection(), {
      connectionId: paired.connectionId,
      deviceName: "Seeker",
      pairedAtMs: NOON,
    });
    // A code works once.
    assert.throws(
      () => pairing.pair(issued.token, URL_A, "Seeker"),
      refused(RequestError.UNAUTHENTICATED),
    );
  });

  it("refuses unknown, missing, and expired codes with the same answer", () => {
    const { clock, pairing } = setup();
    const messages = new Set<string>();
    const refusal = (token: string | undefined): void => {
      assert.throws(
        () => pairing.pair(token, URL_A, ""),
        (thrown) => {
          if (!refused(RequestError.UNAUTHENTICATED)(thrown)) return false;
          messages.add((thrown as RequestFailure).message);
          return true;
        },
      );
    };
    refusal(undefined);
    refusal("Lq3v7Yk2Qm9XwTzR4bN8cJ1dH6fG0sA5eP-uV_iKoLw");
    const { token } = pairing.issue(URL_A, 60);
    clock.now = NOON + 60_000; // the code works strictly before its expiry
    refusal(token);
    assert.equal(messages.size, 1, "the answer doesn't say which");

    clock.now = NOON;
    const fresh = pairing.issue(URL_A, 60);
    clock.now = NOON + 59_999;
    assert.equal(
      typeof pairing.pair(fresh.token, URL_A, "").connectionId,
      "string",
    );
  });

  it("binds a code to its URL, and leaves it usable after a mismatch", () => {
    const { pairing } = setup();
    const { token } = pairing.issue(`${URL_A}/`, 600);
    for (const url of [URL_B, "http://192.168.1.20:8080", ""]) {
      assert.throws(
        () => pairing.pair(token, url, ""),
        refused(RequestError.INVALID_PARAMETERS),
        url,
      );
    }
    assert.equal(pairing.activeConnection(), undefined);
    assert.ok(pairing.pair(token, `${URL_A}/`, "").connectionId);
  });

  it("voids an unused code when a newer one is issued", () => {
    const { pairing } = setup();
    const older = pairing.issue(URL_A, 600);
    const newer = pairing.issue(URL_A, 600);
    assert.throws(
      () => pairing.pair(older.token, URL_A, ""),
      refused(RequestError.UNAUTHENTICATED),
    );
    assert.ok(pairing.pair(newer.token, URL_A, "").connectionId);
  });

  it("refuses a device name over 128 UTF-8 bytes, and keeps the code usable", () => {
    const { pairing } = setup();
    const { token } = pairing.issue(URL_A, 600);
    for (const name of ["€".repeat(43), "broken \ud83d name"]) {
      assert.throws(
        () => pairing.pair(token, URL_A, name),
        refused(RequestError.INVALID_PARAMETERS),
      );
    }
    assert.ok(pairing.pair(token, URL_A, "€".repeat(42)).connectionId); // 126 bytes
  });

  it("stores only hashes of codes and credentials", () => {
    const { db, pairing } = setup();
    const { token } = pairing.issue(URL_A, 600);
    const paired = pairing.pair(token, URL_A, "Seeker");
    for (const table of ["pairing_tokens", "connections"]) {
      const text = contents(db, table);
      assert.ok(!text.includes(token), `${table} holds the pairing token`);
      assert.ok(
        !text.includes(paired.phoneToken),
        `${table} holds the credential`,
      );
    }
  });
});

describe("PairingStore: one FCM target per connection", () => {
  it("registers idempotently, rotates atomically, and survives a restart", () => {
    const path = temporaryDatabasePath();
    const before = setup(path);
    const paired = pairWith(before.pairing);
    const first = "first-fcm-target:APA91_example";
    const second = "second-fcm-target:APA91_example";
    assert.equal(before.pairing.fcmToken(paired.connectionId), undefined);
    assert.equal(before.pairing.setFcmToken(paired.connectionId, first), true);
    assert.equal(before.pairing.setFcmToken(paired.connectionId, first), false);
    assert.equal(before.pairing.setFcmToken(paired.connectionId, second), true);
    before.db.close();

    const after = setup(path);
    try {
      assert.equal(after.pairing.fcmToken(paired.connectionId), second);
    } finally {
      after.db.close();
    }
  });

  it("rejects malformed targets without echoing them", () => {
    const { pairing } = setup();
    const paired = pairWith(pairing);
    for (const token of [
      "",
      "contains whitespace",
      "unicode-🙂",
      "x".repeat(MAX_FCM_TOKEN_BYTES + 1),
    ]) {
      assert.equal(invalidFcmTokenReason(token), "invalid FCM target");
      assert.throws(
        () => pairing.setFcmToken(paired.connectionId, token),
        (thrown) => {
          if (!refused(RequestError.INVALID_PARAMETERS)(thrown)) return false;
          if (token !== "")
            assert.ok(!(thrown as Error).message.includes(token));
          return true;
        },
      );
      assert.throws(
        () => pairing.clearFcmToken(paired.connectionId, token),
        (thrown) => {
          if (!refused(RequestError.INVALID_PARAMETERS)(thrown)) return false;
          if (token !== "")
            assert.ok(!(thrown as Error).message.includes(token));
          return true;
        },
      );
    }
  });

  it("compare-and-deletes only the target that became invalid", () => {
    const { pairing } = setup();
    const paired = pairWith(pairing);
    const old = "old-fcm-target";
    const current = "current-fcm-target";
    pairing.setFcmToken(paired.connectionId, old);
    pairing.setFcmToken(paired.connectionId, current);

    assert.equal(pairing.clearFcmToken(paired.connectionId, old), false);
    assert.equal(pairing.fcmToken(paired.connectionId), current);
    assert.equal(pairing.clearFcmToken(paired.connectionId, current), true);
    assert.equal(pairing.clearFcmToken(paired.connectionId, current), false);
    assert.equal(pairing.fcmToken(paired.connectionId), undefined);
  });

  it("deletes a target when its connection is revoked or replaced", () => {
    const { db, pairing } = setup();
    const first = pairWith(pairing);
    pairing.setFcmToken(first.connectionId, "target-before-revocation");
    pairing.revoke(first.connectionId);
    assert.equal(pairing.fcmToken(first.connectionId), undefined);
    assert.equal(
      db
        .prepare("SELECT fcm_token FROM connections WHERE connection_id = ?")
        .get(first.connectionId)?.fcm_token,
      null,
    );

    const second = pairWith(pairing);
    pairing.setFcmToken(second.connectionId, "target-before-replacement");
    pairWith(pairing);
    assert.equal(pairing.fcmToken(second.connectionId), undefined);
    assert.equal(
      db
        .prepare("SELECT fcm_token FROM connections WHERE connection_id = ?")
        .get(second.connectionId)?.fcm_token,
      null,
    );
  });
});

describe("PairingStore: one active phone", () => {
  it("revokes the previous phone when a new one pairs, and cancels its pending requests", () => {
    const { pairing, requests } = setup();
    const first = pairWith(pairing, URL_A, "Old phone");
    const waiting = requests.create(ack("Waiting", "one-phone-1")).request;
    const answered = requests.create(ack("Answered", "one-phone-2")).request;
    requests.submit(
      first.connectionId,
      create(SubmitResultRequestSchema, {
        ref: answered.ref,
        result: { case: "acknowledgement", value: {} },
      }),
    );
    const issued = pairing.issue(URL_A, 600);
    assert.equal(issued.replaces?.connectionId, first.connectionId);
    const second = pairing.pair(issued.token, URL_A, "New phone");
    assert.deepEqual(second.revoked, [first.connectionId]);
    assert.equal(pairing.authenticate(first.phoneToken), undefined);
    assert.equal(pairing.authenticate(second.phoneToken), second.connectionId);
    const cancelled = requests.get(waiting.ref?.requestId ?? "");
    assert.equal(cancelled.state, RequestState.CANCELLED);
    assert.equal(cancelled.outcome?.detail, REVOKED_DETAIL);
    assert.equal(
      requests.get(answered.ref?.requestId ?? "").state,
      RequestState.COMPLETED,
    );
    // New requests go to the new phone.
    const next = requests.create(ack("Next", "one-phone-3")).request;
    assert.equal(next.ref?.connectionId, second.connectionId);
  });

  it("never changes an existing connection: a code for another URL makes a new one", () => {
    const { pairing, requests } = setup();
    const first = pairWith(pairing, URL_A);
    const kept = requests.create(
      ack("Stays with the first connection", "redirect-1"),
    ).request;
    const second = pairWith(pairing, URL_B);
    assert.notEqual(second.connectionId, first.connectionId);
    assert.notEqual(second.phoneToken, first.phoneToken);
    // The server ID is lasting, so the phone can tell it's the same server at another URL.
    assert.equal(second.serverId, first.serverId);
    // The old credential doesn't follow the new URL, and the new phone doesn't inherit the old
    // connection's requests.
    assert.equal(pairing.authenticate(first.phoneToken), undefined);
    assert.throws(
      () => requests.getForConnection(second.connectionId, kept.ref),
      refused(RequestError.NOT_FOUND),
    );
  });

  it("revokes a connection: its credential stops, its PENDING requests are cancelled, and agents still read them", () => {
    const { clock, pairing, requests } = setup();
    const paired = pairWith(pairing);
    const live = requests.create(ack("Still live", "revoke-1")).request;
    const overdue = requests.create(
      ack("Past its deadline", "revoke-2", { expiresInSeconds: 60 }),
    ).request;
    clock.now = NOON + 120_000;
    assert.deepEqual(pairing.revoke(paired.connectionId), {
      revoked: true,
      cancelled: 1,
    });
    assert.equal(pairing.authenticate(paired.phoneToken), undefined);
    assert.equal(pairing.activeConnection(), undefined);
    assert.equal(
      requests.get(live.ref?.requestId ?? "").state,
      RequestState.CANCELLED,
    );
    // Expiry comes first: the overdue request expires rather than being cancelled.
    assert.equal(
      requests.get(overdue.ref?.requestId ?? "").state,
      RequestState.EXPIRED,
    );
    assert.deepEqual(pairing.revoke(paired.connectionId), {
      revoked: false,
      cancelled: 0,
    });
    assert.throws(
      () => requests.create(ack("No phone", "revoke-3")),
      refused(RequestError.NOT_PAIRED),
    );
  });

  it("cancels by revocation only where the lifecycle lets the sidecar cancel", () => {
    // Revocation writes CANCELLED directly in SQL, so the lifecycle must allow it for every kind.
    const kinds: readonly ActionKind[] = [
      "ack",
      "signMessage",
      "transfer",
      "swap",
    ];
    for (const kind of kinds) {
      assert.ok(
        canTransition(
          kind,
          RequestState.PENDING,
          RequestState.CANCELLED,
          "sidecar",
        ),
        kind,
      );
    }
  });

  it("refuses new requests until a phone pairs", () => {
    const { pairing, requests } = setup();
    assert.throws(
      () => requests.create(ack("Too early", "not-paired")),
      refused(RequestError.NOT_PAIRED),
    );
    pairWith(pairing);
    assert.equal(requests.create(ack("Now", "not-paired")).created, true);
  });

  it("authenticates no empty or unknown credential", () => {
    const { pairing } = setup();
    pairWith(pairing);
    for (const token of [
      undefined,
      "",
      "Lq3v7Yk2Qm9XwTzR4bN8cJ1dH6fG0sA5eP-uV_iKoLw",
    ]) {
      assert.equal(pairing.authenticate(token), undefined);
    }
  });

  it("keeps the server ID and the paired phone across a restart", () => {
    const path = temporaryDatabasePath();
    const before = setup(path);
    const paired = pairWith(before.pairing);
    before.db.close();
    const after = setup(path);
    try {
      assert.equal(after.pairing.serverId(), paired.serverId);
      assert.equal(
        after.pairing.authenticate(paired.phoneToken),
        paired.connectionId,
      );
    } finally {
      after.db.close();
    }
  });
});
