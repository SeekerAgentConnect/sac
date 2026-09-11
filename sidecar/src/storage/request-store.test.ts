import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { create, equals, type MessageInitShape } from "@bufbuild/protobuf";
import { timestampMs } from "@bufbuild/protobuf/wkt";

import {
  ActionRequestSchema,
  ActionSchema,
  Network,
  RequestError,
  RequestState,
  type ActionRequest,
} from "../gen/seekervault/request/v1/request_pb.js";
import { SubmitResultRequestSchema } from "../gen/seekervault/request/v1/service_pb.js";
import { IN_MEMORY, openDatabase, type DatabaseSync } from "./database.ts";
import { RequestFailure } from "../requests/failure.ts";
import { temporaryDatabasePath } from "../testing/process.ts";
import { PairingStore } from "./pairing-store.ts";
import { RequestStore, type NewRequest } from "./request-store.ts";

const NOON = Date.UTC(2026, 8, 11, 12); // 2026-09-11T12:00:00Z
const DAY_SECONDS = 86_400;
const OTHER_CONNECTION = "a7e9c1b3-4d5f-4a6b-8c7d-9e0f1a2b3c4d";
const SERVER_URL = "http://127.0.0.1:8080";
const { PENDING, COMPLETED, REJECTED, CANCELLED, EXPIRED } = RequestState;

type Result = MessageInitShape<typeof SubmitResultRequestSchema>["result"];
const ACKNOWLEDGEMENT: Result = { case: "acknowledgement", value: {} };
const REJECTION: Result = { case: "rejection", value: {} };

interface Setup {
  readonly clock: { now: number };
  readonly db: DatabaseSync;
  readonly store: RequestStore;
  readonly connectionId: string;
}

/** A store on a new database, with a clock the test moves and IDs that sort in creation order. */
function setup(
  options: { readonly path?: string; readonly pendingLimit?: number } = {},
): Setup {
  const clock = { now: NOON };
  let issued = 0;
  const db = openDatabase(options.path ?? IN_MEMORY);
  const store = new RequestStore(db, {
    defaultTtlSeconds: DAY_SECONDS,
    pendingLimit: options.pendingLimit ?? 100,
    now: () => clock.now,
    newId: () =>
      `00000000-0000-4000-8000-${String(++issued).padStart(12, "0")}`,
  });
  return { clock, db, store, connectionId: pairTestPhone(db, clock) };
}

/** Pairs a phone the way PairingService does, so that new requests have a connection. */
function pairTestPhone(db: DatabaseSync, clock: { now: number }): string {
  const pairing = new PairingStore(db, { now: () => clock.now });
  const { token } = pairing.issue(SERVER_URL, 600);
  return pairing.pair(token, SERVER_URL, "Test phone").connectionId;
}

function ack(
  text: string,
  idempotencyKey: string,
  fields: Partial<NewRequest> = {},
): NewRequest {
  return {
    action: create(ActionSchema, { kind: { case: "ack", value: { text } } }),
    agentNote: "",
    idempotencyKey,
    ...fields,
  };
}

/** Submits a result for `request` as the phone of `connectionId`. */
function answer(
  s: Setup,
  request: ActionRequest,
  result: Result,
  connectionId = s.connectionId,
): { readonly request: ActionRequest; readonly duplicate: boolean } {
  return s.store.submit(
    connectionId,
    create(SubmitResultRequestSchema, { ref: request.ref, result }),
  );
}

function refused(
  error: RequestError,
  state?: RequestState,
): (thrown: unknown) => boolean {
  return (thrown) =>
    thrown instanceof RequestFailure &&
    thrown.error === error &&
    (state === undefined || thrown.request?.state === state);
}

function idOf(request: ActionRequest): string {
  return request.ref?.requestId ?? "";
}

function rows(db: DatabaseSync, table: string): number {
  return Number(db.prepare(`SELECT count(*) AS n FROM ${table}`).get()?.n);
}

function pending(s: Setup, pageToken = "", pageSize = 0) {
  return s.store.listPending(s.connectionId, {
    connectionId: s.connectionId,
    pageSize,
    pageToken,
  });
}

describe("RequestStore: creation", () => {
  it("stores an ack as PENDING, bound to the connection, with its deadline", () => {
    const s = setup();
    const { request, created } = s.store.create(
      ack("Deploy finished", "create-1", { agentNote: "Checking the queue" }),
    );
    assert.equal(created, true);
    assert.equal(request.state, PENDING);
    assert.equal(request.ref?.connectionId, s.connectionId);
    assert.equal(request.agentNote, "Checking the queue");
    assert.equal(request.createdAt && timestampMs(request.createdAt), NOON);
    assert.equal(
      request.expiresAt && timestampMs(request.expiresAt),
      NOON + DAY_SECONDS * 1000,
    );
    assert.ok(equals(ActionRequestSchema, s.store.get(idOf(request)), request));
  });

  it("uses the agent's lifetime when it gives one, within 60 seconds to 7 days", () => {
    const s = setup();
    const { request } = s.store.create(
      ack("Soon", "lifetime", { expiresInSeconds: 60 }),
    );
    assert.equal(
      request.expiresAt && timestampMs(request.expiresAt),
      NOON + 60_000,
    );
    for (const expiresInSeconds of [59, 604_801, 1.5]) {
      assert.throws(
        () =>
          s.store.create(
            ack("Soon", `lifetime-${expiresInSeconds}`, { expiresInSeconds }),
          ),
        refused(RequestError.INVALID_PARAMETERS),
      );
    }
  });

  it("validates every field before storing anything", () => {
    const s = setup();
    const invalid: ReadonlyArray<readonly [NewRequest, string]> = [
      [ack("Hello", ""), "idempotency_key is missing"],
      [ack("", "empty-text"), "text is empty"],
      [
        ack("x".repeat(4097), "long-text"),
        "text is 4097 UTF-8 bytes; the limit is 4096",
      ],
      [
        ack("Hello", "long-note", { agentNote: "n".repeat(1025) }),
        "note is 1025 UTF-8 bytes; the limit is 1024",
      ],
    ];
    for (const [request, message] of invalid) {
      assert.throws(
        () => s.store.create(request),
        (thrown) =>
          refused(RequestError.INVALID_PARAMETERS)(thrown) &&
          (thrown as RequestFailure).message === message,
      );
    }
    assert.equal(rows(s.db, "requests"), 0);
    assert.equal(rows(s.db, "idempotency_keys"), 0);
  });

  it("refuses wallet actions while no wallet is connected", () => {
    const s = setup();
    const transfer = create(ActionSchema, {
      kind: {
        case: "transfer",
        value: {
          wallet: "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW",
          network: Network.DEVNET,
          recipient: "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh",
          asset: { kind: { case: "nativeSol", value: {} } },
          amount: "1",
        },
      },
    });
    assert.throws(
      () =>
        s.store.create({
          action: transfer,
          agentNote: "",
          idempotencyKey: "transfer",
        }),
      refused(RequestError.WALLET_MISMATCH),
    );
    assert.equal(rows(s.db, "requests"), 0);
  });
});

describe("RequestStore: idempotency", () => {
  it("returns the original request for a retried key, whatever its state now", () => {
    const s = setup();
    const first = s.store.create(ack("Deploy finished", "retry")).request;
    const retry = s.store.create(
      ack("Deploy finished", "retry", {
        agentNote: "Reworded",
        expiresInSeconds: 600,
      }),
    );
    assert.equal(retry.created, false);
    assert.ok(equals(ActionRequestSchema, retry.request, first));
    answer(s, first, ACKNOWLEDGEMENT);
    const late = s.store.create(ack("Deploy finished", "retry"));
    assert.equal(late.created, false);
    assert.equal(idOf(late.request), idOf(first));
    assert.equal(late.request.state, COMPLETED);
    assert.equal(rows(s.db, "requests"), 1);
  });

  it("refuses the same key with changed parameters instead of reusing it", () => {
    const s = setup();
    const first = s.store.create(ack("Pay invoice 42", "conflict")).request;
    for (const text of [
      "Pay invoice 43",
      "Pay invoice 42 ",
      "pay invoice 42",
    ]) {
      assert.throws(
        () => s.store.create(ack(text, "conflict")),
        (thrown) =>
          refused(RequestError.IDEMPOTENCY_CONFLICT)(thrown) &&
          (thrown as RequestFailure).message.includes(idOf(first)),
      );
    }
    assert.equal(rows(s.db, "requests"), 1);
  });
});

describe("RequestStore: the pending limit", () => {
  it("refuses a new request once the connection has the most PENDING ones allowed", () => {
    const s = setup({ pendingLimit: 2 });
    const first = s.store.create(ack("One", "limit-1")).request;
    s.store.create(ack("Two", "limit-2"));
    assert.throws(
      () => s.store.create(ack("Three", "limit-3")),
      refused(RequestError.PENDING_LIMIT),
    );
    // A retry of a stored request isn't a new one, so the limit doesn't apply to it.
    assert.equal(s.store.create(ack("One", "limit-1")).created, false);
    // Once the owner answers one, there's room again.
    answer(s, first, REJECTION);
    assert.equal(s.store.create(ack("Three", "limit-3")).created, true);
  });

  it("stops counting a request once it expires", () => {
    const s = setup({ pendingLimit: 1 });
    s.store.create(ack("Soon", "limit-expiry", { expiresInSeconds: 60 }));
    s.clock.now = NOON + 60_000;
    assert.equal(s.store.create(ack("Next", "limit-next")).created, true);
  });
});

describe("RequestStore: expiry", () => {
  it("expires a PENDING request at expires_at, and not a millisecond before", () => {
    const s = setup();
    const { request } = s.store.create(
      ack("Soon", "expiry", { expiresInSeconds: 60 }),
    );
    s.clock.now = NOON + 59_999;
    assert.equal(s.store.get(idOf(request)).state, PENDING);
    assert.equal(pending(s).requests.length, 1);
    s.clock.now = NOON + 60_000;
    const expired = s.store.get(idOf(request));
    assert.equal(expired.state, EXPIRED);
    assert.equal(
      expired.outcome?.detail,
      "The request expired before the owner decided.",
    );
    assert.equal(
      expired.updatedAt && timestampMs(expired.updatedAt),
      NOON + 60_000,
    );
    assert.deepEqual(pending(s).requests, []);
  });

  it("refuses the owner's late answer and the agent's late cancellation", () => {
    const s = setup();
    const { request } = s.store.create(
      ack("Soon", "late", { expiresInSeconds: 60 }),
    );
    s.clock.now = NOON + 120_000;
    assert.throws(
      () => answer(s, request, ACKNOWLEDGEMENT),
      refused(RequestError.INVALID_STATE, EXPIRED),
    );
    assert.throws(
      () => s.store.cancel(idOf(request)),
      refused(RequestError.INVALID_STATE, EXPIRED),
    );
    assert.equal(rows(s.db, "results"), 0);
  });
});

describe("RequestStore: results", () => {
  it("completes an ack when the owner acknowledges it", () => {
    const s = setup();
    const { request } = s.store.create(ack("Deploy finished", "answer"));
    s.clock.now = NOON + 5000;
    const answered = answer(s, request, ACKNOWLEDGEMENT);
    assert.equal(answered.duplicate, false);
    assert.equal(answered.request.state, COMPLETED);
    assert.equal(
      answered.request.updatedAt && timestampMs(answered.request.updatedAt),
      NOON + 5000,
    );
    assert.ok(
      equals(ActionRequestSchema, s.store.get(idOf(request)), answered.request),
    );
  });

  it("applies a repeated result once, and refuses a different one after the end", () => {
    const s = setup();
    const { request } = s.store.create(ack("Deploy finished", "repeat"));
    const first = answer(s, request, ACKNOWLEDGEMENT);
    s.clock.now = NOON + 10_000;
    const repeat = answer(s, request, ACKNOWLEDGEMENT);
    assert.equal(repeat.duplicate, true);
    assert.ok(equals(ActionRequestSchema, repeat.request, first.request));
    assert.equal(rows(s.db, "results"), 1);
    assert.throws(
      () => answer(s, request, REJECTION),
      refused(RequestError.INVALID_STATE, COMPLETED),
    );
  });

  it("records the owner's rejection, and repeats it without a second effect", () => {
    const s = setup();
    const { request } = s.store.create(ack("Deploy finished", "reject"));
    const rejected = answer(s, request, REJECTION).request;
    assert.equal(rejected.state, REJECTED);
    assert.equal(rejected.outcome?.detail, "The owner rejected the request.");
    assert.equal(answer(s, request, REJECTION).duplicate, true);
    assert.throws(
      () => answer(s, request, ACKNOWLEDGEMENT),
      refused(RequestError.INVALID_STATE, REJECTED),
    );
  });

  it("refuses results that don't apply to an ack", () => {
    const s = setup();
    const { request } = s.store.create(ack("Deploy finished", "wrong-result"));
    assert.throws(
      () =>
        answer(s, request, {
          case: "approval",
          value: { contentHash: new Uint8Array(32) },
        }),
      refused(RequestError.INVALID_STATE, PENDING),
    );
    assert.throws(
      () => answer(s, request, { case: "approval", value: {} }),
      refused(RequestError.INVALID_PARAMETERS),
    );
    assert.equal(s.store.get(idOf(request)).state, PENDING);
  });
});

describe("RequestStore: cancellation", () => {
  it("cancels a PENDING request for the agent, and a repeat returns it unchanged", () => {
    const s = setup();
    const { request } = s.store.create(ack("Deploy finished", "cancel"));
    const cancelled = s.store.cancel(idOf(request));
    assert.equal(cancelled.state, CANCELLED);
    assert.equal(cancelled.outcome?.detail, "The agent cancelled the request.");
    s.clock.now = NOON + 1000;
    assert.ok(
      equals(ActionRequestSchema, s.store.cancel(idOf(request)), cancelled),
    );
    assert.throws(
      () => answer(s, request, ACKNOWLEDGEMENT),
      refused(RequestError.INVALID_STATE, CANCELLED),
    );
  });

  it("settles a race between cancellation and the owner's answer: whichever comes first wins", () => {
    const s = setup();
    const cancelledFirst = s.store.create(ack("Race 1", "race-1")).request;
    s.store.cancel(idOf(cancelledFirst));
    assert.throws(
      () => answer(s, cancelledFirst, ACKNOWLEDGEMENT),
      refused(RequestError.INVALID_STATE, CANCELLED),
    );

    const answeredFirst = s.store.create(ack("Race 2", "race-2")).request;
    answer(s, answeredFirst, ACKNOWLEDGEMENT);
    assert.throws(
      () => s.store.cancel(idOf(answeredFirst)),
      refused(RequestError.INVALID_STATE, COMPLETED),
    );
    assert.equal(s.store.get(idOf(answeredFirst)).state, COMPLETED);
  });

  it("reports unknown and malformed request IDs", () => {
    const s = setup();
    assert.throws(
      () => s.store.get(OTHER_CONNECTION),
      refused(RequestError.NOT_FOUND),
    );
    assert.throws(
      () => s.store.cancel("abc"),
      refused(RequestError.INVALID_PARAMETERS),
    );
  });
});

describe("RequestStore: connection scope", () => {
  it("keeps the phone to its own connection's requests", () => {
    const s = setup();
    const { request } = s.store.create(ack("Deploy finished", "scope"));
    const theirs = { connectionId: OTHER_CONNECTION, requestId: idOf(request) };
    assert.throws(
      () =>
        s.store.getForConnection(
          s.connectionId,
          create(ActionRequestSchema, { ref: theirs }).ref,
        ),
      refused(RequestError.NOT_FOUND),
    );
    assert.throws(
      () => s.store.getForConnection(OTHER_CONNECTION, request.ref),
      refused(RequestError.NOT_FOUND),
    );
    assert.throws(
      () => answer(s, request, ACKNOWLEDGEMENT, OTHER_CONNECTION),
      refused(RequestError.NOT_FOUND),
    );
    assert.throws(
      () =>
        s.store.listPending(s.connectionId, {
          connectionId: OTHER_CONNECTION,
          pageSize: 0,
          pageToken: "",
        }),
      refused(RequestError.NOT_FOUND),
    );
    assert.throws(
      () =>
        s.store.getForConnection(
          s.connectionId,
          create(ActionRequestSchema, {
            ref: { connectionId: s.connectionId, requestId: "abc" },
          }).ref,
        ),
      refused(RequestError.INVALID_PARAMETERS),
    );
    assert.equal(s.store.get(idOf(request)).state, PENDING);
  });
});

describe("RequestStore: pending pages", () => {
  it("pages oldest first, never repeating or skipping a request, while states change", () => {
    const s = setup();
    const at = [0, 1000, 2000, 3000, 4000, 4000]; // the last two share a timestamp
    const requests = at.map((offset, index) => {
      s.clock.now = NOON + offset;
      return s.store.create(ack(`Request ${index}`, `page-${index}`)).request;
    });
    const [r0, r1, r2, r3, r4, r5] = requests.map(idOf);

    const first = pending(s, "", 2);
    assert.deepEqual(first.requests.map(idOf), [r0, r1]);
    // Between pages: one listed request is answered, one not yet listed is rejected, and a new
    // request arrives.
    const [, listed, unlisted] = requests;
    assert.ok(listed && unlisted);
    answer(s, listed, ACKNOWLEDGEMENT);
    answer(s, unlisted, REJECTION);
    s.clock.now = NOON + 5000;
    const r6 = idOf(s.store.create(ack("Request 6", "page-6")).request);

    const second = pending(s, first.nextPageToken, 2);
    const third = pending(s, second.nextPageToken, 2);
    assert.equal(third.nextPageToken, "");
    const seen = [first, second, third].flatMap((page) =>
      page.requests.map(idOf),
    );
    assert.deepEqual(seen, [r0, r1, r3, r4, r5, r6]);
    assert.equal(new Set(seen).size, seen.length);
    assert.ok(!seen.includes(r2 ?? ""), "the rejected request isn't listed");
  });

  it("defaults the page size to 50, allows up to 100, and refuses a forged token", () => {
    const s = setup();
    for (let index = 0; index < 51; index++) {
      s.store.create(ack(`Request ${index}`, `size-${index}`));
    }
    const page = pending(s);
    assert.equal(page.requests.length, 50);
    assert.notEqual(page.nextPageToken, "");
    assert.equal(pending(s, "", 100).requests.length, 51);
    assert.throws(
      () => pending(s, "", 101),
      refused(RequestError.INVALID_PARAMETERS),
    );
    for (const token of [
      "not-a-token",
      Buffer.from("1:abc").toString("base64url"),
    ]) {
      assert.throws(
        () => pending(s, token),
        refused(RequestError.INVALID_PARAMETERS),
      );
    }
  });
});

describe("RequestStore: restarts", () => {
  it("keeps requests, results, and idempotency keys across a restart, and runs nothing on its own", () => {
    const path = temporaryDatabasePath();
    const before = setup({ path });
    const waiting = before.store.create(
      ack("Waits across the restart", "restart-waiting"),
    ).request;
    const done = before.store.create(
      ack("Answered before the restart", "restart-done"),
    ).request;
    const answered = answer(before, done, ACKNOWLEDGEMENT).request;
    before.db.close();

    const db = openDatabase(path);
    try {
      const store = new RequestStore(db, {
        defaultTtlSeconds: DAY_SECONDS,
        pendingLimit: 100,
        now: () => NOON + 1000,
      });
      assert.equal(store.activeConnection(), before.connectionId);
      assert.ok(equals(ActionRequestSchema, store.get(idOf(waiting)), waiting));
      assert.ok(equals(ActionRequestSchema, store.get(idOf(done)), answered));
      assert.equal(
        store.create(ack("Waits across the restart", "restart-waiting"))
          .created,
        false,
      );
      assert.equal(
        store.submit(
          before.connectionId,
          create(SubmitResultRequestSchema, {
            ref: done.ref,
            result: ACKNOWLEDGEMENT,
          }),
        ).duplicate,
        true,
      );
    } finally {
      db.close();
    }
  });
});
