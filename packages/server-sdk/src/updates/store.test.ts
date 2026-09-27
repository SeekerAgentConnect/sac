/** Every published update is the serialized result of the same transaction as its mutation. */
import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { create } from "@bufbuild/protobuf";

import {
  ActionSchema,
  Network,
  RequestState,
  WalletBindingSchema,
} from "../gen/seekervault/request/v1/request_pb.js";
import { SubmitResultRequestSchema } from "../gen/seekervault/request/v1/service_pb.js";
import { openDatabase } from "../storage/database.ts";
import { PairingStore } from "../storage/pairing-store.ts";
import { RequestStore } from "../storage/request-store.ts";
import { UpdateStore } from "../storage/update-store.ts";

const URL = "http://127.0.0.1:8080";
const WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW";
const OTHER_WALLET = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh";

describe("durable update publication", () => {
  it("revisions every lifecycle mutation exactly once and puts revocation last", () => {
    const db = openDatabase(":memory:");
    const clock = { now: Date.UTC(2026, 8, 13, 12) };
    let id = 0;
    const pairing = new PairingStore(db, {
      now: () => clock.now,
      newId: () => `00000000-0000-4000-8000-${String(++id).padStart(12, "0")}`,
    });
    const token = pairing.issue(URL, 600).token;
    const connectionId = pairing.pair(token, URL, "Seeker").connectionId;
    const requests = new RequestStore(db, {
      defaultTtlSeconds: 86_400,
      pendingLimit: 100,
      now: () => clock.now,
      newId: () => `10000000-0000-4000-8000-${String(++id).padStart(12, "0")}`,
    });
    const updates = new UpdateStore(db, {
      serverInstanceId: "20000000-0000-4000-8000-000000000001",
      now: () => clock.now,
    });
    try {
      const completed = requests.create(ack("Complete", "complete")).request;
      requests.submit(
        connectionId,
        create(SubmitResultRequestSchema, {
          ref: completed.ref,
          result: { case: "acknowledgement", value: {} },
        }),
      );
      // An exact duplicate is storage-idempotent and publishes no revision.
      requests.submit(
        connectionId,
        create(SubmitResultRequestSchema, {
          ref: completed.ref,
          result: { case: "acknowledgement", value: {} },
        }),
      );

      const cancelled = requests.create(ack("Cancel", "cancel")).request;
      requests.cancel(cancelled.ref?.requestId ?? "");
      requests.cancel(cancelled.ref?.requestId ?? "");

      const expired = requests.create({
        ...ack("Expire", "expire"),
        expiresInSeconds: 60,
      }).request;
      clock.now += 60_000;
      requests.expireOverdue();

      requests.publishWallet(
        connectionId,
        create(WalletBindingSchema, {
          wallet: WALLET,
          network: Network.DEVNET,
        }),
      );
      const walletCancelled = requests.create(message("wallet-change")).request;
      requests.publishWallet(
        connectionId,
        create(WalletBindingSchema, {
          wallet: OTHER_WALLET,
          network: Network.DEVNET,
        }),
      );
      pairing.revoke(connectionId);

      const replay = updates.replay(connectionId, 0);
      assert.equal(replay.kind, "events");
      if (replay.kind !== "events") return;
      assert.deepEqual(
        replay.events.map((event) => event.sequence),
        replay.events.map((_, index) => index + 1),
      );
      assert.deepEqual(
        replay.events.map((event) =>
          event.kind === "revoked"
            ? "REVOKED"
            : `${event.request?.ref?.requestId}:${RequestState[event.request?.state ?? 0]}:${event.revision}`,
        ),
        [
          `${completed.ref?.requestId}:PENDING:1`,
          `${completed.ref?.requestId}:COMPLETED:2`,
          `${cancelled.ref?.requestId}:PENDING:1`,
          `${cancelled.ref?.requestId}:CANCELLED:2`,
          `${expired.ref?.requestId}:PENDING:1`,
          `${expired.ref?.requestId}:EXPIRED:2`,
          `${walletCancelled.ref?.requestId}:PENDING:1`,
          `${walletCancelled.ref?.requestId}:CANCELLED:2`,
          "REVOKED",
        ],
      );
    } finally {
      db.close();
    }
  });
});

function ack(text: string, idempotencyKey: string) {
  return {
    action: create(ActionSchema, {
      kind: { case: "ack" as const, value: { text } },
    }),
    agentNote: "",
    idempotencyKey,
  };
}

function message(idempotencyKey: string) {
  return {
    action: create(ActionSchema, {
      kind: {
        case: "signMessage" as const,
        value: {
          wallet: WALLET,
          content: { case: "text" as const, value: "Sign me" },
        },
      },
    }),
    agentNote: "",
    idempotencyKey,
  };
}
