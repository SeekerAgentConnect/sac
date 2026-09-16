import assert from "node:assert/strict";
import { setTimeout as delay } from "node:timers/promises";
import { afterEach, describe, it } from "node:test";

import { create } from "@bufbuild/protobuf";
import type { Message } from "firebase-admin/messaging";

import { ActionSchema } from "../gen/seekervault/request/v1/request_pb.js";
import {
  IN_MEMORY,
  openDatabase,
  transaction,
  type DatabaseSync,
} from "../storage/database.ts";
import { PairingStore } from "../storage/pairing-store.ts";
import { RequestStore } from "../storage/request-store.ts";
import {
  observeCommittedRequestUpdates,
  recordRequestUpdate,
} from "../storage/update-store.ts";
import {
  FCM_INVALIDATION_COLLAPSE_KEY,
  FCM_INVALIDATION_DATA,
  FCM_INVALIDATION_TTL_MS,
  FcmInvalidationDispatcher,
} from "./invalidation.ts";

describe("FCM request invalidations", () => {
  let db: DatabaseSync | undefined;
  let stop: (() => void) | undefined;
  let dispatcher: FcmInvalidationDispatcher | undefined;

  afterEach(async () => {
    stop?.();
    await dispatcher?.close();
    db?.close();
    db = undefined;
    stop = undefined;
    dispatcher = undefined;
  });

  it("coalesces durable changes into fixed high and normal data-only hints", async () => {
    const setup = createSetup();
    const messages: Message[] = [];
    const logs: string[] = [];
    dispatcher = new FcmInvalidationDispatcher(
      setup.pairing,
      {
        send: (message) => (messages.push(message), Promise.resolve("opaque")),
      },
      (line) => logs.push(line),
    );
    stop = observeCommittedRequestUpdates(setup.db, (update) =>
      dispatcher?.invalidate(update),
    );

    const first = setup.requests.create(
      request("first", SECRET_MESSAGE, SECRET_NOTE),
    );
    setup.requests.create(request("second", "another request", ""));
    await settle();

    assert.equal(messages.length, 1, "same-turn creations are coalesced");
    audit(messages[0], "high");
    const appVisible = JSON.stringify({
      data: messages[0]?.data,
      notification: messages[0]?.notification,
    });
    for (const forbidden of [
      SECRET_MESSAGE,
      SECRET_NOTE,
      SECRET_CREDENTIAL,
      SECRET_POLICY,
      SECRET_AUTHORIZATION,
      first.request.ref?.requestId ?? "missing-request-id",
      setup.connectionId,
    ]) {
      assert.equal(appVisible.includes(forbidden), false, forbidden);
    }
    assert.equal(logs.length, 0);

    assert.throws(() =>
      transaction(setup.db, () => {
        recordRequestUpdate(
          setup.db,
          checkNotEmpty(first.request.ref?.requestId),
          NOW,
        );
        throw new Error("roll back the update event");
      }),
    );
    await settle();
    assert.equal(messages.length, 1, "a rolled-back event sends nothing");

    setup.requests.cancel(checkNotEmpty(first.request.ref?.requestId));
    await settle();
    assert.equal(messages.length, 2);
    audit(messages[1], "normal");
  });

  it("compare-clears a rejected target without erasing a concurrent rotation", async () => {
    const setup = createSetup();
    let rejectSend: ((error: unknown) => void) | undefined;
    const messages: Message[] = [];
    const logs: string[] = [];
    dispatcher = new FcmInvalidationDispatcher(
      setup.pairing,
      {
        send: (message) => {
          messages.push(message);
          return new Promise((_resolve, reject) => {
            rejectSend = reject;
          });
        },
      },
      (line) => logs.push(line),
    );
    stop = observeCommittedRequestUpdates(setup.db, (update) =>
      dispatcher?.invalidate(update),
    );

    setup.requests.create(request("rotation", "review", ""));
    await waitFor(() => messages.length === 1);
    setup.pairing.setFcmToken(setup.connectionId, ROTATED_TARGET);
    rejectSend?.(
      Object.assign(new Error(`do not log ${SECRET_CREDENTIAL} or ${TARGET}`), {
        code: "messaging/installation-id-not-registered",
      }),
    );
    await settle();

    assert.equal(setup.pairing.fcmToken(setup.connectionId), ROTATED_TARGET);
    assert.equal(logs.length, 1);
    assert.match(logs[0] ?? "", /target is no longer valid/);
    assert.doesNotMatch(
      logs.join("\n"),
      new RegExp(`${TARGET}|${SECRET_CREDENTIAL}`),
    );
  });

  it("keeps a target after a transient failure and never logs Firebase error text", async () => {
    const setup = createSetup();
    const logs: string[] = [];
    dispatcher = new FcmInvalidationDispatcher(
      setup.pairing,
      {
        send: () =>
          Promise.reject(
            new Error(`quota response contained ${SECRET_CREDENTIAL}`),
          ),
      },
      (line) => logs.push(line),
    );
    stop = observeCommittedRequestUpdates(setup.db, (update) =>
      dispatcher?.invalidate(update),
    );

    setup.requests.create(request("transient", "review", ""));
    await settle();

    assert.equal(setup.pairing.fcmToken(setup.connectionId), TARGET);
    assert.deepEqual(logs, [
      `FCM invalidation failed for connection ${setup.connectionId}: delivery unavailable`,
    ]);
    assert.doesNotMatch(
      logs.join("\n"),
      new RegExp(`${TARGET}|${SECRET_CREDENTIAL}`),
    );
  });

  function createSetup() {
    db = openDatabase(IN_MEMORY);
    const pairing = new PairingStore(db, { now: () => NOW });
    const issued = pairing.issue(SERVER_URL, 600);
    const paired = pairing.pair(issued.token, SERVER_URL, "Seeker");
    pairing.setFcmToken(paired.connectionId, TARGET);
    const requests = new RequestStore(db, {
      defaultTtlSeconds: 86_400,
      pendingLimit: 100,
      now: () => NOW,
    });
    return { db, pairing, requests, connectionId: paired.connectionId };
  }
});

function request(idempotencyKey: string, text: string, agentNote: string) {
  return {
    action: create(ActionSchema, {
      kind: { case: "ack" as const, value: { text } },
    }),
    agentNote,
    idempotencyKey,
  };
}

function audit(
  message: Message | undefined,
  priority: "high" | "normal",
): void {
  assert.ok(message !== undefined);
  assert.deepEqual(Object.keys(message).sort(), ["android", "data", "fid"]);
  assert.ok("fid" in message);
  assert.equal(message.fid, TARGET);
  assert.deepEqual(message.data, FCM_INVALIDATION_DATA);
  assert.deepEqual(message.android, {
    collapseKey: FCM_INVALIDATION_COLLAPSE_KEY,
    priority,
    ttl: FCM_INVALIDATION_TTL_MS,
  });
  assert.equal(message.notification, undefined);
  assert.equal("notification" in (message.android ?? {}), false);
}

async function settle(): Promise<void> {
  await delay(0);
  await delay(0);
}

async function waitFor(condition: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 100 && !condition(); attempt += 1) {
    await delay(0);
  }
  assert.ok(condition(), "timed out waiting for invalidation send");
}

function checkNotEmpty(value: string | undefined): string {
  assert.ok(value !== undefined && value !== "");
  return value;
}

const NOW = Date.parse("2026-09-14T12:00:00Z");
const SERVER_URL = "https://sidecar.example.com";
const TARGET = "private-fcm-routing-target";
const ROTATED_TARGET = "rotated-private-fcm-routing-target";
const SECRET_MESSAGE = "message body the owner may authorize";
const SECRET_NOTE = "agent prose must stay out of push";
const SECRET_CREDENTIAL = "phone-or-server-secret";
const SECRET_POLICY = "owner-only-policy-document";
const SECRET_AUTHORIZATION = "serialized-transaction-approval";
