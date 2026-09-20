import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { request } from "node:http";
import { after, before, describe, it } from "node:test";
import { setTimeout as delay } from "node:timers/promises";

import { Code, ConnectError } from "@connectrpc/connect";
import type { Message } from "firebase-admin/messaging";

import { AcknowledgementResult } from "@seeker-vault/server-sdk/protocol";
import { Network } from "@seeker-vault/server-sdk/protocol";
import { DISPLAY_COMMAND_TOOL } from "./mcp-endpoint.ts";
import { REQUEST_ACK_TOOL } from "./requests/mcp-tools.ts";
import { FcmSender } from "./push/fcm.ts";
import { FCM_INVALIDATION_DATA } from "../../server-sdk/src/push/invalidation.ts";
import { isLoopbackAddress, startSidecar, type Sidecar } from "./server.ts";
import {
  callTool,
  connectAgent,
  connectPhone,
  display,
  errorCode,
  pairPhone,
  pairingClient,
  phoneClient,
  requestClient,
  viewOf,
  waitFor,
} from "./testing/clients.ts";
import { temporaryDatabasePath } from "./testing/process.ts";

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);
const WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW";
const logs: string[] = [];
let sidecar: Sidecar;

function isConnectError(code: Code): (error: unknown) => boolean {
  return (error) => error instanceof ConnectError && error.code === code;
}

/** A raw POST to /mcp, so tests control every header, including Host. */
function postMcp(
  headers: Record<string, string>,
  target: Sidecar = sidecar,
): Promise<number> {
  const { hostname, port } = new URL(target.url);
  return new Promise((resolve, reject) => {
    const req = request(
      { hostname, port, path: "/mcp", method: "POST", headers },
      (res) => {
        res.resume();
        resolve(res.statusCode ?? 0);
      },
    );
    req.on("error", reject);
    req.end(JSON.stringify({ jsonrpc: "2.0", id: 1, method: "ping" }));
  });
}

/** A raw GET, so a test can read a path the Connect router never sees. */
function getPath(
  path: string,
  target: Sidecar = sidecar,
): Promise<{ status: number; body: string }> {
  const { hostname, port } = new URL(target.url);
  return new Promise((resolve, reject) => {
    const req = request({ hostname, port, path, method: "GET" }, (res) => {
      let body = "";
      res.setEncoding("utf8");
      res.on("data", (chunk: string) => {
        body += chunk;
      });
      res.on("end", () => resolve({ status: res.statusCode ?? 0, body }));
    });
    req.on("error", reject);
    req.end();
  });
}

before(async () => {
  sidecar = await startSidecar(
    {
      host: "127.0.0.1",
      port: 0,
      mcpToken: MCP_TOKEN,
      phoneToken: PHONE_TOKEN,
      liveCommandTimeoutSeconds: 1,
      databasePath: ":memory:",
      requestTtlSeconds: 86_400,
      pendingLimit: 100,
    },
    { log: (line) => logs.push(line) },
  );
});

after(async () => {
  await sidecar.close();
});

describe("sidecar", () => {
  it("does not construct Firebase while FCM is unconfigured", async () => {
    let constructions = 0;
    const unconfigured = await startSidecar(
      {
        host: "127.0.0.1",
        port: 0,
        mcpToken: MCP_TOKEN,
        phoneToken: PHONE_TOKEN,
        liveCommandTimeoutSeconds: 1,
        databasePath: ":memory:",
        requestTtlSeconds: 86_400,
        pendingLimit: 100,
      },
      {
        log: (line) => logs.push(line),
        fcmSenderFactory: () => {
          constructions += 1;
          return new FcmSender({ send: () => Promise.resolve("unused") }, () =>
            Promise.resolve(),
          );
        },
      },
    );
    await unconfigured.close();

    assert.equal(constructions, 0);
    assert.ok(
      logs.includes("FCM sender is off; FCM_PROJECT_ID is not configured"),
    );
  });

  it("owns one optional Firebase sender without exposing its configuration in logs", async () => {
    const configuredLogs: string[] = [];
    const projects: string[] = [];
    let closes = 0;
    const configured = await startSidecar(
      {
        host: "127.0.0.1",
        port: 0,
        mcpToken: MCP_TOKEN,
        phoneToken: PHONE_TOKEN,
        liveCommandTimeoutSeconds: 1,
        databasePath: ":memory:",
        requestTtlSeconds: 86_400,
        pendingLimit: 100,
        fcmProjectId: "seeker-vault-prod-123",
      },
      {
        log: (line) => configuredLogs.push(line),
        fcmSenderFactory: (projectId) => {
          projects.push(projectId);
          return new FcmSender(
            { send: () => Promise.resolve("unused") },
            () => {
              closes += 1;
              return Promise.resolve();
            },
          );
        },
      },
    );
    await Promise.all([configured.close(), configured.close()]);

    assert.deepEqual(projects, ["seeker-vault-prod-123"]);
    assert.equal(closes, 1);
    assert.ok(
      configuredLogs.includes(
        "FCM sender is configured through Application Default Credentials",
      ),
    );
    assert.doesNotMatch(configuredLogs.join("\n"), /seeker-vault-prod-123/);
  });

  it("sends a fixed invalidation only after a durable request is created", async () => {
    const databasePath = temporaryDatabasePath();
    const configuredLogs: string[] = [];
    const messages: Message[] = [];
    const target = "private-routing-target-that-must-not-be-logged";
    const configured = await startSidecar(
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
        fcmProjectId: "seeker-vault-prod-123",
      },
      {
        log: (line) => configuredLogs.push(line),
        fcmSenderFactory: () =>
          new FcmSender(
            {
              send: (message) => {
                messages.push(message);
                return Promise.resolve("opaque-message-id");
              },
            },
            () => Promise.resolve(),
          ),
      },
    );
    const agent = await connectAgent(configured.url, MCP_TOKEN);
    try {
      const paired = await pairPhone(configured.url, databasePath);
      await pairingClient(configured.url, paired.phoneToken).setFcmToken({
        connectionId: paired.connectionId,
        update: { case: "token", value: target },
      });
      const created = viewOf(
        await callTool(agent, REQUEST_ACK_TOOL, {
          text: "private request contents",
          note: "private agent note",
          idempotency_key: "push-after-commit",
        }),
      );
      await waitFor(() => messages.length === 1, "FCM invalidation");

      assert.equal(created.status, "PENDING");
      assert.deepEqual(messages[0]?.data, FCM_INVALIDATION_DATA);
      assert.equal(messages[0]?.android?.priority, "high");
      assert.equal(messages[0]?.notification, undefined);
      assert.doesNotMatch(
        JSON.stringify(messages[0]?.data),
        /private request contents|private agent note/,
      );
      assert.doesNotMatch(configuredLogs.join("\n"), new RegExp(target));
    } finally {
      await agent.close();
      await configured.close();
    }
  });

  it("fails a tool call at once with OFFLINE when no phone is watching", async () => {
    const agent = await connectAgent(sidecar.url, MCP_TOKEN);
    try {
      const started = Date.now();
      const result = await display(agent, "Hello Seeker");
      assert.equal(errorCode(result), "OFFLINE");
      assert.ok(Date.now() - started < 500, "OFFLINE should not wait");
    } finally {
      await agent.close();
    }
  });

  it("completes a tool call only after the phone acknowledges the same command", async () => {
    const phone = await connectPhone(sidecar.url, PHONE_TOKEN);
    const agent = await connectAgent(sidecar.url, MCP_TOKEN);
    try {
      const { tools } = await agent.listTools(); // the client then validates the output schema
      // The durable request tools (SAW-010, SAW-016) follow the live one. The demo tool
      // vault_request_ack is off here (MCP_DEMO_TOOLS); requests/endpoints.test.ts turns it on.
      assert.deepEqual(
        tools.map((tool) => tool.name),
        [
          DISPLAY_COMMAND_TOOL,
          "vault_sign_message",
          "vault_get_capabilities",
          "vault_get_address",
          "vault_get_request",
          "vault_cancel_request",
        ],
      );
      let settled = false;
      const call = display(agent, "Hello Seeker 👋").finally(() => {
        settled = true;
      });
      const command = await phone.nextCommand();
      assert.equal(command.text, "Hello Seeker 👋");
      await delay(100);
      assert.equal(
        settled,
        false,
        "the call must wait for the acknowledgement",
      );
      await phone.acknowledge(command.id);
      const result = await call;
      assert.equal(result.isError, undefined);
      assert.deepEqual(result.structuredContent, {
        id: command.id,
        result: "OK",
      });
      await phone.acknowledge(command.id); // a repeated OK is accepted and changes nothing
    } finally {
      phone.disconnect();
      await agent.close();
    }
  });

  it("answers BUSY while another command waits", async () => {
    const phone = await connectPhone(sidecar.url, PHONE_TOKEN);
    const agent = await connectAgent(sidecar.url, MCP_TOKEN);
    try {
      const first = display(agent, "first");
      const command = await phone.nextCommand();
      assert.equal(errorCode(await display(agent, "second")), "BUSY");
      await phone.acknowledge(command.id);
      assert.equal(errorCode(await first), undefined);
    } finally {
      phone.disconnect();
      await agent.close();
    }
  });

  it("rejects invalid text", async () => {
    const phone = await connectPhone(sidecar.url, PHONE_TOKEN);
    const agent = await connectAgent(sidecar.url, MCP_TOKEN);
    try {
      assert.equal(errorCode(await display(agent, "   ")), "INVALID_TEXT");
      assert.equal(
        errorCode(await display(agent, "a".repeat(4097))),
        "INVALID_TEXT",
      );
    } finally {
      phone.disconnect();
      await agent.close();
    }
  });

  it("times out without an acknowledgement and refuses the late one", async () => {
    const phone = await connectPhone(sidecar.url, PHONE_TOKEN);
    const agent = await connectAgent(sidecar.url, MCP_TOKEN);
    try {
      const call = display(agent, "Nobody will tap OK");
      const command = await phone.nextCommand();
      assert.equal(errorCode(await call), "TIMEOUT");
      await assert.rejects(
        phone.acknowledge(command.id),
        isConnectError(Code.DeadlineExceeded),
      );
    } finally {
      phone.disconnect();
      await agent.close();
    }
  });

  it("cancels the command when the agent cancels the call", async () => {
    const phone = await connectPhone(sidecar.url, PHONE_TOKEN);
    const agent = await connectAgent(sidecar.url, MCP_TOKEN);
    try {
      const abort = new AbortController();
      const call = display(agent, "Cancel me", { signal: abort.signal });
      const command = await phone.nextCommand();
      abort.abort();
      await assert.rejects(call);
      await waitFor(
        () =>
          logs.includes(
            `command ${command.id} cancelled: the agent cancelled the call`,
          ),
        "cancel",
      );
      await assert.rejects(
        phone.acknowledge(command.id),
        isConnectError(Code.Canceled),
      );
      const next = display(agent, "The slot is free again");
      await phone.acknowledge((await phone.nextCommand()).id);
      assert.equal(errorCode(await next), undefined);
    } finally {
      phone.disconnect();
      await agent.close();
    }
  });

  it("cancels the command when the agent's connection drops", async () => {
    const phone = await connectPhone(sidecar.url, PHONE_TOKEN);
    const agent = await connectAgent(sidecar.url, MCP_TOKEN);
    try {
      const call = display(agent, "Agent goes away");
      const command = await phone.nextCommand();
      await agent.close();
      await assert.rejects(call);
      await waitFor(
        () =>
          logs.some((line) =>
            line.startsWith(`command ${command.id} cancelled`),
          ),
        "cancel",
      );
      await assert.rejects(
        phone.acknowledge(command.id),
        isConnectError(Code.Canceled),
      );
    } finally {
      phone.disconnect();
    }
  });

  it("cancels the command when the phone disconnects", async () => {
    const phone = await connectPhone(sidecar.url, PHONE_TOKEN);
    const agent = await connectAgent(sidecar.url, MCP_TOKEN);
    try {
      const call = display(agent, "Phone goes away");
      await phone.nextCommand();
      phone.disconnect();
      assert.equal(errorCode(await call), "CANCELLED");
    } finally {
      await agent.close();
    }
  });

  it("replaces an older phone stream and cancels its command", async () => {
    const older = await connectPhone(sidecar.url, PHONE_TOKEN);
    const agent = await connectAgent(sidecar.url, MCP_TOKEN);
    const newer = await connectPhone(sidecar.url, PHONE_TOKEN);
    try {
      await assert.rejects(older.next(), isConnectError(Code.Canceled));
      const call = display(agent, "Only the newer stream gets this");
      const command = await newer.nextCommand();
      await newer.acknowledge(command.id);
      assert.deepEqual((await call).structuredContent, {
        id: command.id,
        result: "OK",
      });
    } finally {
      older.disconnect();
      newer.disconnect();
      await agent.close();
    }
  });

  it("rejects missing and swapped tokens on both endpoints", async () => {
    await assert.rejects(connectAgent(sidecar.url, "wrong-token"));
    await assert.rejects(connectAgent(sidecar.url, PHONE_TOKEN));
    const mcpHeaders = {
      "Content-Type": "application/json",
      Accept: "application/json, text/event-stream",
    };
    assert.equal(await postMcp(mcpHeaders), 401);
    assert.equal(
      await postMcp({ ...mcpHeaders, Authorization: `Bearer ${PHONE_TOKEN}` }),
      401,
    );

    const acknowledgement = { id: "any", result: AcknowledgementResult.OK };
    for (const token of [undefined, "wrong-token", MCP_TOKEN]) {
      const client = phoneClient(sidecar.url, token);
      await assert.rejects(
        client.acknowledgeCommand({ acknowledgement }),
        isConnectError(Code.Unauthenticated),
      );
      await assert.rejects(
        client.watchCommands({})[Symbol.asyncIterator]().next(),
        isConnectError(Code.Unauthenticated),
      );
    }
  });

  it("rejects /mcp requests with a non-loopback Host or Origin", async () => {
    const headers = {
      "Content-Type": "application/json",
      Accept: "application/json, text/event-stream",
      Authorization: `Bearer ${MCP_TOKEN}`,
    };
    assert.equal(await postMcp({ ...headers, Host: "evil.example" }), 403);
    assert.equal(
      await postMcp({ ...headers, Origin: "http://evil.example" }),
      403,
    );
    assert.equal(await postMcp({ ...headers, Origin: "null" }), 403);
    assert.notEqual(
      await postMcp({ ...headers, Origin: "http://localhost:5173" }),
      403,
    );
  });

  it("accepts an MCP_ALLOWED_HOSTS name in the Host and Origin headers", async () => {
    const vpn = await startSidecar(
      {
        host: "127.0.0.1",
        port: 0,
        mcpToken: MCP_TOKEN,
        phoneToken: PHONE_TOKEN,
        liveCommandTimeoutSeconds: 1,
        databasePath: ":memory:",
        requestTtlSeconds: 86_400,
        pendingLimit: 100,
        mcpAllowedHosts: ["100.64.0.1"],
      },
      { log: () => undefined },
    );
    try {
      const headers = {
        "Content-Type": "application/json",
        Accept: "application/json, text/event-stream",
        Authorization: `Bearer ${MCP_TOKEN}`,
        Host: "100.64.0.1:8081",
      };
      assert.notEqual(await postMcp(headers, vpn), 403);
      assert.notEqual(
        await postMcp({ ...headers, Origin: "http://100.64.0.1:8081" }, vpn),
        403,
      );
      assert.equal(
        await postMcp({ ...headers, Host: "evil.example" }, vpn),
        403,
      );
      assert.equal(await postMcp(headers), 403); // not allowed without the setting
    } finally {
      await vpn.close();
    }
  });

  // Without the fix the sidecar never answers, so fail fast instead of hanging.
  it(
    "answers 400 to a request target that isn't a path, and keeps serving",
    {
      timeout: 10_000,
    },
    async () => {
      const { hostname, port } = new URL(sidecar.url);
      const status = await new Promise<number>((resolve, reject) => {
        const req = request(
          { hostname, port, path: "//[", method: "GET" },
          (res) => {
            res.resume();
            resolve(res.statusCode ?? 0);
          },
        );
        req.on("error", reject);
        req.end();
      });
      assert.equal(status, 400);
      assert.equal((await fetch(`${sidecar.url}/healthz`)).status, 200);
    },
  );

  it("serves /healthz", async () => {
    const response = await fetch(`${sidecar.url}/healthz`);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { status: "ok" });
    assert.equal(
      (await fetch(`${sidecar.url}/healthz`, { method: "POST" })).status,
      405,
    );
  });

  it("recognizes only loopback peers for native-TLS readiness", () => {
    for (const address of [
      "127.0.0.1",
      "127.12.0.9",
      "::1",
      "::ffff:127.0.0.1",
    ]) {
      assert.equal(isLoopbackAddress(address), true, address);
    }
    for (const address of [
      undefined,
      "0.0.0.0",
      "10.0.0.2",
      "::ffff:10.0.0.2",
      "2001:db8::1",
    ]) {
      assert.equal(isLoopbackAddress(address), false, String(address));
    }
  });

  it("never logs a token or command text", () => {
    assert.ok(logs.length > 0);
    for (const line of logs) {
      assert.ok(!line.includes(MCP_TOKEN) && !line.includes(PHONE_TOKEN), line);
      assert.ok(!line.includes("Hello Seeker"), line);
    }
  });
});

/**
 * The sidecar without its MCP adapter (SEE-87, docs/wiki/mcp-adapter.md).
 *
 * MCP is one way an agent reaches this sidecar, not what the sidecar is. With the adapter off the
 * generic core has to start, serve the phone, pair, and stop exactly as it does with the adapter
 * on — and /mcp has to be absent rather than merely locked.
 */
describe("the sidecar without the MCP adapter", () => {
  const databasePath = temporaryDatabasePath();
  const lines: string[] = [];
  let core: Sidecar;

  before(async () => {
    core = await startSidecar(
      {
        host: "127.0.0.1",
        port: 0,
        // No mcpToken: the adapter is off. Nothing else about this configuration differs.
        phoneToken: PHONE_TOKEN,
        liveCommandTimeoutSeconds: 1,
        databasePath,
        requestTtlSeconds: 86_400,
        pendingLimit: 100,
      },
      { log: (line) => lines.push(line) },
    );
  });

  after(async () => {
    await core.close();
  });

  it("starts without an MCP token and says the adapter is off", () => {
    assert.ok(core.url.startsWith("http://127.0.0.1:"));
    assert.ok(
      lines.some((line) =>
        line.startsWith(
          "the MCP adapter is off (MCP_ENABLED=false): /mcp is not served",
        ),
      ),
      lines.join("\n"),
    );
    // Nothing claims an endpoint that isn't there, and nothing mentions a demo tool that
    // only /mcp could have served.
    assert.ok(lines.some((line) => line.includes("no MCP endpoint")));
    assert.ok(!lines.some((line) => line.includes("vault_request_ack")));
    assert.ok(!lines.some((line) => line.includes("MCP OAuth")));
  });

  it("does not serve /mcp, and no credential opens it", async () => {
    // 404 rather than 401: there is no endpoint here, and an authentication challenge would
    // suggest that some token would open one.
    assert.equal(await postMcp({}, core), 404);
    assert.equal(
      await postMcp(
        {
          Authorization: `Bearer ${MCP_TOKEN}`,
          "Content-Type": "application/json",
          Accept: "application/json, text/event-stream",
        },
        core,
      ),
      404,
    );
    // And no authorization is advertised for it either.
    for (const path of [
      "/.well-known/oauth-protected-resource",
      "/.well-known/oauth-protected-resource/mcp",
    ]) {
      assert.equal((await getPath(path, core)).status, 404);
    }
  });

  it("serves the generic APIs: health, pairing, the phone API, and the diagnostic", async () => {
    const health = await getPath("/healthz", core);
    assert.equal(health.status, 200);
    assert.deepEqual(JSON.parse(health.body), { status: "ok" });

    // Direct pairing is untouched: the phone gets its own credential the way it always has.
    const paired = await pairPhone(core.url, databasePath);
    assert.equal(paired.serverId, core.serverId);
    const requests = requestClient(core.url, paired.phoneToken);
    const pending = await requests.listPending({
      connectionId: paired.connectionId,
    });
    assert.deepEqual(pending.requests, []);

    // Phone permissions are unchanged: a connection this credential doesn't name is still
    // refused, and the agent's token still opens nothing of the phone's.
    await assert.rejects(
      requests.listPending({ connectionId: randomUUID() }),
      isConnectError(Code.NotFound),
    );
    await assert.rejects(
      requestClient(core.url, MCP_TOKEN).listPending({
        connectionId: paired.connectionId,
      }),
      isConnectError(Code.Unauthenticated),
    );

    // The wallet binding still publishes, so the wallet-signing rules are the same rules.
    const published = await requests.publishWallet({
      connectionId: paired.connectionId,
      binding: { wallet: WALLET, network: Network.DEVNET },
    });
    assert.equal(published.binding?.wallet, WALLET);

    // And the Stage 1 diagnostic keeps its own development token.
    const phone = await connectPhone(core.url, PHONE_TOKEN);
    phone.disconnect();
  });

  it("stops cleanly, and stopping twice is the same as stopping once", async () => {
    const second = await startSidecar(
      {
        host: "127.0.0.1",
        port: 0,
        phoneToken: PHONE_TOKEN,
        liveCommandTimeoutSeconds: 1,
        databasePath: ":memory:",
        requestTtlSeconds: 86_400,
        pendingLimit: 100,
      },
      { log: () => undefined },
    );
    await second.close();
    await second.close();
  });
});
