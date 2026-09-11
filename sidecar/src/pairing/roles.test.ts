/**
 * Every protected operation against every credential (docs/protocol.md#roles): the agent's MCP
 * token, a pairing code, a paired phone's credential, a revoked credential, the Stage 1 development
 * token, and none. Each credential opens exactly the operations of its role.
 */
import assert from "node:assert/strict";
import { request as httpRequest } from "node:http";
import { after, before, describe, it } from "node:test";

import { AcknowledgementResult } from "../gen/seekervault/live/v1/live_pb.js";
import {
  RequestError,
  RequestErrorDetailSchema,
} from "../gen/seekervault/request/v1/request_pb.js";
import { startSidecar, type Sidecar } from "../server.ts";
import { openDatabase } from "../storage/database.ts";
import {
  Code,
  ConnectError,
  connectAgent,
  pairPhone,
  pairingClient,
  phoneClient,
  requestClient,
  type TestPhone,
} from "../testing/clients.ts";
import { temporaryDatabasePath } from "../testing/process.ts";
import { PairingStore } from "../storage/pairing-store.ts";

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);
const UNKNOWN = "a7e9c1b3-4d5f-4a6b-8c7d-9e0f1a2b3c4d";
const logs: string[] = [];
let sidecar: Sidecar;
let databasePath: string;
let revoked: TestPhone;
let phone: TestPhone;
let pairingToken: string;

type Role = "agent" | "pairing" | "phone" | "live";
type Credential = {
  readonly name: string;
  readonly token: () => string | undefined;
  readonly role?: Role;
};

const CREDENTIALS: readonly Credential[] = [
  { name: "no credential", token: () => undefined },
  { name: "the MCP token", token: () => MCP_TOKEN, role: "agent" },
  { name: "a pairing code", token: () => pairingToken, role: "pairing" },
  {
    name: "the paired phone's credential",
    token: () => phone.phoneToken,
    role: "phone",
  },
  { name: "a revoked phone credential", token: () => revoked.phoneToken },
  {
    name: "the Stage 1 development token",
    token: () => PHONE_TOKEN,
    role: "live",
  },
];

/** Each phone RPC, called so that it changes nothing when it gets past authentication. */
const RPCS: ReadonlyArray<{
  readonly name: string;
  readonly role: Role;
  readonly call: (token: string | undefined) => Promise<unknown>;
}> = [
  {
    name: "RequestService.ListPending",
    role: "phone",
    call: (token) =>
      requestClient(sidecar.url, token).listPending({
        connectionId: phone.connectionId,
      }),
  },
  {
    name: "RequestService.GetRequest",
    role: "phone",
    call: (token) =>
      requestClient(sidecar.url, token).getRequest({
        ref: { connectionId: phone.connectionId, requestId: UNKNOWN },
      }),
  },
  {
    name: "RequestService.PrepareRequest",
    role: "phone",
    call: (token) =>
      requestClient(sidecar.url, token).prepareRequest({
        ref: { connectionId: phone.connectionId, requestId: UNKNOWN },
      }),
  },
  {
    name: "RequestService.SubmitResult",
    role: "phone",
    call: (token) =>
      requestClient(sidecar.url, token).submitResult({
        ref: { connectionId: phone.connectionId, requestId: UNKNOWN },
        result: { case: "acknowledgement", value: {} },
      }),
  },
  {
    name: "RequestService.PublishWallet",
    role: "phone",
    // No binding: past authentication, this clears a wallet that was never connected.
    call: (token) =>
      requestClient(sidecar.url, token).publishWallet({
        connectionId: phone.connectionId,
      }),
  },
  {
    name: "PairingService.RevokeConnection",
    role: "phone",
    // Another connection's ID: past authentication, this gets NOT_FOUND and revokes nothing.
    call: (token) =>
      pairingClient(sidecar.url, token).revokeConnection({
        connectionId: UNKNOWN,
      }),
  },
  {
    name: "PairingService.Pair",
    role: "pairing",
    // Another URL: past authentication, this gets INVALID_PARAMETERS and leaves the code unused.
    call: (token) =>
      pairingClient(sidecar.url, token).pair({
        serverUrl: "https://elsewhere.example.com",
        deviceName: "",
      }),
  },
  {
    name: "LiveCommandService.AcknowledgeCommand",
    role: "live",
    call: (token) =>
      phoneClient(sidecar.url, token).acknowledgeCommand({
        acknowledgement: { id: UNKNOWN, result: AcknowledgementResult.OK },
      }),
  },
  {
    name: "LiveCommandService.WatchCommands",
    role: "live",
    call: async (token) => {
      const abort = new AbortController();
      try {
        const stream = phoneClient(sidecar.url, token).watchCommands(
          {},
          { signal: abort.signal },
        );
        await stream[Symbol.asyncIterator]().next();
      } finally {
        abort.abort();
      }
    },
  },
];

/** The MCP methods, as raw JSON-RPC POSTs to /mcp. */
const MCP_METHODS: ReadonlyArray<{
  readonly name: string;
  readonly body: object;
}> = [
  {
    name: "initialize",
    body: {
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
      params: {
        protocolVersion: "2025-06-18",
        capabilities: {},
        clientInfo: { name: "roles", version: "0" },
      },
    },
  },
  { name: "tools/list", body: { jsonrpc: "2.0", id: 2, method: "tools/list" } },
  ...[
    "vault_display_command",
    "vault_get_address",
    "vault_request_ack",
    "vault_get_request",
    "vault_cancel_request",
  ].map((tool, index) => ({
    name: `tools/call ${tool}`,
    body: {
      jsonrpc: "2.0",
      id: 3 + index,
      method: "tools/call",
      params: { name: tool, arguments: {} },
    },
  })),
];

function postMcp(token: string | undefined, body: object): Promise<number> {
  const { hostname, port } = new URL(sidecar.url);
  return new Promise((resolve, reject) => {
    const req = httpRequest(
      {
        hostname,
        port,
        path: "/mcp",
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json, text/event-stream",
          ...(token === undefined ? {} : { Authorization: `Bearer ${token}` }),
        },
      },
      (res) => {
        res.resume();
        resolve(res.statusCode ?? 0);
      },
    );
    req.on("error", reject);
    req.end(JSON.stringify(body));
  });
}

/** The Connect code a call ended with, or "ok". */
async function outcome(call: Promise<unknown>): Promise<Code | "ok"> {
  try {
    await call;
    return "ok";
  } catch (error) {
    if (error instanceof ConnectError) return error.code;
    throw error;
  }
}

before(async () => {
  databasePath = temporaryDatabasePath();
  sidecar = await startSidecar(
    {
      host: "127.0.0.1",
      port: 0,
      mcpToken: MCP_TOKEN,
      phoneToken: PHONE_TOKEN,
      liveCommandTimeoutSeconds: 1,
      databasePath,
      requestTtlSeconds: 86_400,
      pendingLimit: 100,
      // Every tool the sidecar can serve is in the matrix, the demo tool too.
      demoTools: true,
    },
    { log: (line) => logs.push(line) },
  );
  // A phone that pairs and is then replaced, the paired phone, and an unused code.
  revoked = await pairPhone(sidecar.url, databasePath, "Replaced phone");
  phone = await pairPhone(sidecar.url, databasePath, "Seeker");
  const db = openDatabase(databasePath);
  try {
    pairingToken = new PairingStore(db).issue(sidecar.url, 600).token;
  } finally {
    db.close();
  }
});

after(async () => {
  await sidecar.close();
});

describe("roles", () => {
  for (const rpc of RPCS) {
    it(`${rpc.name} accepts only the ${rpc.role} credential`, async () => {
      for (const credential of CREDENTIALS) {
        const code = await outcome(rpc.call(credential.token()));
        if (credential.role === rpc.role) {
          assert.notEqual(
            code,
            Code.Unauthenticated,
            `${credential.name} is refused`,
          );
        } else {
          assert.equal(
            code,
            Code.Unauthenticated,
            `${credential.name} gets ${String(code)}`,
          );
        }
      }
    });
  }

  for (const method of MCP_METHODS) {
    it(`MCP ${method.name} accepts only the MCP token`, async () => {
      for (const credential of CREDENTIALS) {
        const status = await postMcp(credential.token(), method.body);
        if (credential.role === "agent") {
          assert.notEqual(status, 401, `${credential.name} is refused`);
        } else {
          assert.equal(status, 401, `${credential.name} gets ${status}`);
        }
      }
    });
  }

  it("gives the agent no tool that pairs, prepares, answers, or revokes", async () => {
    const agent = await connectAgent(sidecar.url, MCP_TOKEN);
    try {
      const { tools } = await agent.listTools();
      assert.deepEqual(
        tools.map((tool) => tool.name).sort(),
        MCP_METHODS.flatMap(
          (method) => /^tools\/call (\S+)$/.exec(method.name)?.[1] ?? [],
        ).sort(),
        "the matrix above covers every tool",
      );
      assert.ok(
        tools.every(
          (tool) =>
            !/approve|prepare|submit|result|pair|revoke/.test(tool.name),
        ),
      );
    } finally {
      await agent.close();
    }
  });

  it("tells the phone its credential is revoked, and keeps the new phone off the old connection", async () => {
    await assert.rejects(
      requestClient(sidecar.url, revoked.phoneToken).submitResult({
        ref: { connectionId: revoked.connectionId, requestId: UNKNOWN },
        result: { case: "rejection", value: {} },
      }),
      (error) =>
        error instanceof ConnectError &&
        error.code === Code.Unauthenticated &&
        error.findDetails(RequestErrorDetailSchema)[0]?.error ===
          RequestError.UNAUTHENTICATED,
    );
    await assert.rejects(
      requestClient(sidecar.url, phone.phoneToken).listPending({
        connectionId: revoked.connectionId,
      }),
      (error) => error instanceof ConnectError && error.code === Code.NotFound,
    );
  });

  it("pairs with the code, replaces the phone, and then revokes itself", async () => {
    const newest = await pairingClient(sidecar.url, pairingToken).pair({
      serverUrl: sidecar.url,
      deviceName: "Newest phone",
    });
    assert.equal(newest.serverId, sidecar.serverId);
    assert.equal(
      await outcome(
        requestClient(sidecar.url, phone.phoneToken).listPending({
          connectionId: phone.connectionId,
        }),
      ),
      Code.Unauthenticated,
    );
    await pairingClient(sidecar.url, newest.phoneToken).revokeConnection({
      connectionId: newest.connectionId,
    });
    assert.equal(
      await outcome(
        requestClient(sidecar.url, newest.phoneToken).listPending({
          connectionId: newest.connectionId,
        }),
      ),
      Code.Unauthenticated,
    );
    assert.ok(
      logs.some(
        (line) =>
          line ===
          `phone paired: connection ${newest.connectionId}; revoked connection ${phone.connectionId}`,
      ),
    );
    assert.ok(
      logs.some((line) =>
        line.startsWith(
          `connection ${newest.connectionId} revoked by the phone`,
        ),
      ),
    );
  });

  it("keeps every token and credential out of the log", () => {
    const secrets = [
      MCP_TOKEN,
      PHONE_TOKEN,
      pairingToken,
      phone.phoneToken,
      revoked.phoneToken,
    ];
    for (const line of logs) {
      for (const secret of secrets) assert.ok(!line.includes(secret), line);
    }
  });
});
