/**
 * Stage 5.3's joined sidecar acceptance path. Real Connect and MCP clients drive one configured
 * sidecar while an injected Firebase boundary records the exact messages that would be sent. No
 * Firebase credential or network is used here; physical delivery stays in the Seeker runbook.
 */
import assert from "node:assert/strict";
import { setTimeout as delay } from "node:timers/promises";
import { afterEach, describe, it } from "node:test";

import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import type { Message } from "firebase-admin/messaging";

import {
  CANCEL_REQUEST_TOOL,
  REQUEST_ACK_TOOL,
} from "../requests/mcp-tools.ts";
import { startSidecar, type Sidecar } from "../server.ts";
import { openDatabase } from "../../../server-sdk/src/storage/database.ts";
import { PairingStore } from "../../../server-sdk/src/storage/pairing-store.ts";
import {
  Code,
  ConnectError,
  callTool,
  connectAgent,
  pairPhone,
  pairingClient,
  viewOf,
  waitFor,
  type TestPhone,
} from "../testing/clients.ts";
import { temporaryDatabasePath } from "../testing/process.ts";
import { FcmSender } from "./fcm.ts";
import {
  FCM_INVALIDATION_COLLAPSE_KEY,
  FCM_INVALIDATION_DATA,
  FCM_INVALIDATION_TTL_MS,
} from "../../../server-sdk/src/push/invalidation.ts";

describe("Stage 5.3 sidecar acceptance", () => {
  const started: Sidecar[] = [];
  const agents: Client[] = [];

  afterEach(async () => {
    await Promise.all(agents.splice(0).map((agent) => agent.close()));
    await Promise.all(started.splice(0).map((sidecar) => sidecar.close()));
  });

  it("binds rotation and invalidation to the owning connection and clears revocation", async () => {
    const a = await configuredSidecar();
    const b = await configuredSidecar();
    const phoneA = await pairPhone(a.sidecar.url, a.databasePath, "Seeker A");
    const phoneB = await pairPhone(b.sidecar.url, b.databasePath, "Seeker B");
    const agentA = await agent(a.sidecar);
    const agentB = await agent(b.sidecar);

    await register(a.sidecar, phoneA, INITIAL_TARGET);
    await register(b.sidecar, phoneB, INITIAL_TARGET);

    // A valid credential still cannot name another sidecar's connection, and another sidecar's
    // credential cannot authenticate here. Neither failed call changes a target.
    await assert.rejects(
      pairingClient(a.sidecar.url, phoneA.phoneToken).setFcmToken({
        connectionId: phoneB.connectionId,
        update: { case: "token", value: FOREIGN_TARGET },
      }),
      isCode(Code.NotFound),
    );
    await assert.rejects(
      pairingClient(a.sidecar.url, phoneB.phoneToken).setFcmToken({
        connectionId: phoneA.connectionId,
        update: { case: "token", value: FOREIGN_TARGET },
      }),
      isCode(Code.Unauthenticated),
    );

    const firstA = await createAck(
      agentA,
      "private A request",
      "stage53-a-first",
    );
    const firstB = await createAck(
      agentB,
      "private B request",
      "stage53-b-first",
    );
    await waitFor(() => a.messages.length === 1, "sidecar A invalidation");
    await waitFor(() => b.messages.length === 1, "sidecar B invalidation");
    audit(a.messages[0], INITIAL_TARGET, "high");
    audit(b.messages[0], INITIAL_TARGET, "high");

    await register(a.sidecar, phoneA, ROTATED_TARGET);
    await pairingClient(a.sidecar.url, phoneA.phoneToken).setFcmToken({
      connectionId: phoneA.connectionId,
      update: { case: "clearIfToken", value: INITIAL_TARGET },
    });
    const secondA = await createAck(
      agentA,
      "private rotated request",
      "stage53-a-second",
    );
    await waitFor(
      () => a.messages.length === 2,
      "rotated sidecar A invalidation",
    );
    audit(a.messages[1], ROTATED_TARGET, "high");

    // An idempotent agent retry creates no durable mutation and therefore no extra ping.
    assert.equal(
      await createAck(agentA, "private rotated request", "stage53-a-second"),
      secondA,
    );
    await settle();
    assert.equal(a.messages.length, 2);

    // Firebase's permanent rejection compare-clears only the value it rejected.
    await register(a.sidecar, phoneA, INVALID_TARGET);
    await cancel(agentA, firstA);
    await waitFor(() => a.messages.length === 3, "invalid-target send");
    await waitFor(
      () => fcmTarget(a.databasePath, phoneA.connectionId) === undefined,
      "invalid target cleanup",
    );

    await register(a.sidecar, phoneA, ROTATED_TARGET);
    await pairingClient(a.sidecar.url, phoneA.phoneToken).revokeConnection({
      connectionId: phoneA.connectionId,
    });
    assert.equal(fcmTarget(a.databasePath, phoneA.connectionId), undefined);
    await assert.rejects(
      register(a.sidecar, phoneA, FOREIGN_TARGET),
      isCode(Code.Unauthenticated),
    );

    // Revoking A does not affect B's registration or its normal-priority state invalidation.
    await cancel(agentB, firstB);
    await waitFor(
      () => b.messages.length === 2,
      "sidecar B cancellation invalidation",
    );
    audit(b.messages[1], INITIAL_TARGET, "normal");
    assert.equal(
      fcmTarget(b.databasePath, phoneB.connectionId),
      INITIAL_TARGET,
    );

    const privateValues = [
      INITIAL_TARGET,
      ROTATED_TARGET,
      INVALID_TARGET,
      FOREIGN_TARGET,
      phoneA.phoneToken,
      phoneB.phoneToken,
      "private A request",
      "private B request",
      "private rotated request",
    ];
    for (const value of privateValues) {
      assert.doesNotMatch(a.logs.join("\n"), new RegExp(escapeRegExp(value)));
      assert.doesNotMatch(b.logs.join("\n"), new RegExp(escapeRegExp(value)));
      assert.doesNotMatch(
        JSON.stringify(a.messages.map((message) => message.data)),
        new RegExp(escapeRegExp(value)),
      );
      assert.doesNotMatch(
        JSON.stringify(b.messages.map((message) => message.data)),
        new RegExp(escapeRegExp(value)),
      );
    }
  });

  async function configuredSidecar(): Promise<ConfiguredSidecar> {
    const databasePath = temporaryDatabasePath();
    const messages: Message[] = [];
    const logs: string[] = [];
    const sidecar = await startSidecar(
      {
        host: "127.0.0.1",
        port: 0,
        mcpToken: MCP_TOKEN,
        phoneToken: PHONE_TOKEN,
        liveCommandTimeoutSeconds: 1,
        databasePath,
        requestTtlSeconds: 86_400,
        pendingLimit: 100,
        demoTools: true,
        fcmProjectId: "stage53-acceptance-project",
      },
      {
        log: (line) => logs.push(line),
        fcmSenderFactory: () =>
          new FcmSender(
            {
              send: (message) => {
                messages.push(message);
                return "fid" in message && message.fid === INVALID_TARGET
                  ? Promise.reject(
                      Object.assign(new Error("private Firebase response"), {
                        code: "messaging/installation-id-not-registered",
                      }),
                    )
                  : Promise.resolve("opaque-message-id");
              },
            },
            () => Promise.resolve(),
          ),
      },
    );
    started.push(sidecar);
    return { sidecar, databasePath, messages, logs };
  }

  async function agent(sidecar: Sidecar): Promise<Client> {
    const client = await connectAgent(sidecar.url, MCP_TOKEN);
    agents.push(client);
    return client;
  }
});

interface ConfiguredSidecar {
  readonly sidecar: Sidecar;
  readonly databasePath: string;
  readonly messages: Message[];
  readonly logs: string[];
}

function register(
  sidecar: Sidecar,
  phone: TestPhone,
  target: string,
): Promise<unknown> {
  return pairingClient(sidecar.url, phone.phoneToken).setFcmToken({
    connectionId: phone.connectionId,
    update: { case: "token", value: target },
  });
}

async function createAck(
  agent: Client,
  text: string,
  idempotencyKey: string,
): Promise<string> {
  const view = viewOf(
    await callTool(agent, REQUEST_ACK_TOOL, {
      text,
      idempotency_key: idempotencyKey,
    }),
  );
  assert.equal(view.status, "PENDING");
  return view.request_id;
}

async function cancel(agent: Client, requestId: string): Promise<void> {
  const view = viewOf(
    await callTool(agent, CANCEL_REQUEST_TOOL, { request_id: requestId }),
  );
  assert.equal(view.status, "CANCELLED");
}

function fcmTarget(
  databasePath: string,
  connectionId: string,
): string | undefined {
  const db = openDatabase(databasePath);
  try {
    return new PairingStore(db).fcmToken(connectionId);
  } finally {
    db.close();
  }
}

function audit(
  message: Message | undefined,
  target: string,
  priority: "high" | "normal",
): void {
  assert.ok(message !== undefined);
  assert.deepEqual(Object.keys(message).sort(), ["android", "data", "fid"]);
  assert.ok("fid" in message);
  assert.equal(message.fid, target);
  assert.deepEqual(message.data, FCM_INVALIDATION_DATA);
  assert.deepEqual(message.android, {
    collapseKey: FCM_INVALIDATION_COLLAPSE_KEY,
    priority,
    ttl: FCM_INVALIDATION_TTL_MS,
  });
  assert.equal(message.notification, undefined);
}

function isCode(code: Code): (error: unknown) => boolean {
  return (error) => error instanceof ConnectError && error.code === code;
}

async function settle(): Promise<void> {
  await delay(0);
  await delay(0);
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);
const INITIAL_TARGET = "private-stage53-target-before-rotation";
const ROTATED_TARGET = "private-stage53-target-after-rotation";
const INVALID_TARGET = "private-stage53-target-rejected-by-firebase";
const FOREIGN_TARGET = "private-stage53-target-without-ownership";
