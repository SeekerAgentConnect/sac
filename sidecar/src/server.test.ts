import assert from "node:assert/strict";
import { request } from "node:http";
import { after, before, describe, it } from "node:test";
import { setTimeout as delay } from "node:timers/promises";

import { Code, ConnectError } from "@connectrpc/connect";

import { AcknowledgementResult } from "./gen/seekervault/live/v1/live_pb.js";
import { DISPLAY_COMMAND_TOOL } from "./mcp-endpoint.ts";
import { startSidecar, type Sidecar } from "./server.ts";
import {
  connectAgent,
  connectPhone,
  display,
  errorCode,
  phoneClient,
  waitFor,
} from "./testing/clients.ts";

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);
const logs: string[] = [];
let sidecar: Sidecar;

function isConnectError(code: Code): (error: unknown) => boolean {
  return (error) => error instanceof ConnectError && error.code === code;
}

/** A raw POST to /mcp, so tests control every header, including Host. */
function postMcp(headers: Record<string, string>): Promise<number> {
  const { hostname, port } = new URL(sidecar.url);
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

before(async () => {
  sidecar = await startSidecar(
    {
      host: "127.0.0.1",
      port: 0,
      mcpToken: MCP_TOKEN,
      phoneToken: PHONE_TOKEN,
      liveCommandTimeoutSeconds: 1,
    },
    { log: (line) => logs.push(line) },
  );
});

after(async () => {
  await sidecar.close();
});

describe("sidecar", () => {
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
      assert.deepEqual(
        tools.map((tool) => tool.name),
        [DISPLAY_COMMAND_TOOL],
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

  it("serves /healthz", async () => {
    const response = await fetch(`${sidecar.url}/healthz`);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { status: "ok" });
    assert.equal(
      (await fetch(`${sidecar.url}/healthz`, { method: "POST" })).status,
      405,
    );
  });

  it("never logs a token or command text", () => {
    assert.ok(logs.length > 0);
    for (const line of logs) {
      assert.ok(!line.includes(MCP_TOKEN) && !line.includes(PHONE_TOKEN), line);
      assert.ok(!line.includes("Hello Seeker"), line);
    }
  });
});
