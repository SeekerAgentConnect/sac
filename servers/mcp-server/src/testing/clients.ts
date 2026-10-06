/**
 * Real clients for the sidecar's two endpoints, used by the integration tests: Connect clients
 * acting as the phone, and an MCP SDK client acting as the agent.
 */
// Re-exported so tests in other packages check errors against this module's Connect instance.
export { Code, ConnectError } from "@connectrpc/connect";
import assert from "node:assert/strict";
import { setTimeout as delay } from "node:timers/promises";

import {
  createClient,
  type Client as ConnectClient,
  type Interceptor,
} from "@connectrpc/connect";
import { createConnectTransport } from "@connectrpc/connect-node";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

import {
  AcknowledgementResult,
  LiveCommandService,
  type AcknowledgeCommandResponse,
  type LiveCommand,
  type WatchCommandsResponse,
} from "@seekeragentconnect/server-sdk/protocol";
import {
  PairingService,
  RequestService,
} from "@seekeragentconnect/server-sdk/protocol";
import { DISPLAY_COMMAND_TOOL } from "../mcp-endpoint.ts";
import { PairingStore } from "../../../../packages/server-sdk/src/storage/pairing-store.ts";
import type { RequestView } from "../requests/mcp-tools.ts";
import { openDatabase } from "../../../../packages/server-sdk/src/storage/database.ts";

export function phoneClient(
  baseUrl: string,
  token?: string,
): ConnectClient<typeof LiveCommandService> {
  return createClient(LiveCommandService, transport(baseUrl, token));
}

/** A Connect client for the durable RequestService, acting as the phone. */
export function requestClient(
  baseUrl: string,
  token?: string,
): ConnectClient<typeof RequestService> {
  return createClient(RequestService, transport(baseUrl, token));
}

/** A Connect client for PairingService, acting as the phone. */
export function pairingClient(
  baseUrl: string,
  token?: string,
): ConnectClient<typeof PairingService> {
  return createClient(PairingService, transport(baseUrl, token));
}

/** A phone paired by pairPhone. */
export interface TestPhone {
  readonly connectionId: string;
  readonly phoneToken: string;
  readonly serverId: string;
}

/**
 * Pairs a test phone the way the owner does. It issues a pairing code in the sidecar's database, as
 * `pnpm pair` does, then calls Pair with its token and URL.
 */
export async function pairPhone(
  baseUrl: string,
  databasePath: string,
  deviceName = "Test phone",
): Promise<TestPhone> {
  const db = openDatabase(databasePath);
  let token: string;
  try {
    token = new PairingStore(db).issue(baseUrl, 600).token;
  } finally {
    db.close();
  }
  const { connectionId, phoneToken, serverId } = await pairingClient(
    baseUrl,
    token,
  ).pair({ serverUrl: baseUrl, deviceName });
  return { connectionId, phoneToken, serverId };
}

export interface Phone {
  /** The next event must be a command; returns it. */
  nextCommand(): Promise<LiveCommand>;
  /** The next stream event; rejects with the error that ended the stream. */
  next(): Promise<IteratorResult<WatchCommandsResponse>>;
  acknowledge(id: string): Promise<AcknowledgeCommandResponse>;
  disconnect(): void;
}

/** Opens WatchCommands like the live-test screen and waits for `ready`. */
export async function connectPhone(
  baseUrl: string,
  token: string,
): Promise<Phone> {
  const client = phoneClient(baseUrl, token);
  const abort = new AbortController();
  const stream = client.watchCommands({}, { signal: abort.signal });
  const events = stream[Symbol.asyncIterator]();
  const first = await events.next();
  assert.equal(first.done ? undefined : first.value.event.case, "ready");
  return {
    async nextCommand() {
      const next = await events.next();
      assert.ok(
        !next.done && next.value.event.case === "command",
        "expected a command event",
      );
      return next.value.event.value;
    },
    next: () => events.next(),
    acknowledge: (id) =>
      client.acknowledgeCommand({
        acknowledgement: { id, result: AcknowledgementResult.OK },
      }),
    disconnect: () => {
      abort.abort();
    },
  };
}

export async function connectAgent(
  baseUrl: string,
  token: string,
): Promise<Client> {
  const client = new Client({ name: "seeker-vault-tests", version: "0.0.0" });
  await client.connect(
    new StreamableHTTPClientTransport(new URL("/mcp", baseUrl), {
      requestInit: { headers: { Authorization: `Bearer ${token}` } },
    }),
  );
  return client;
}

/** Calls vault_display_command with a client timeout longer than the sidecar's deadline. */
export async function display(
  agent: Client,
  text: string,
  options: { readonly signal?: AbortSignal; readonly timeoutMs?: number } = {},
): Promise<CallToolResult> {
  const result = await agent.callTool(
    { name: DISPLAY_COMMAND_TOOL, arguments: { text } },
    undefined,
    {
      signal: options.signal,
      timeout: options.timeoutMs ?? 10_000,
    },
  );
  return result as CallToolResult;
}

/** Calls any tool; the durable ones answer at once, so the client timeout is short. */
export async function callTool(
  agent: Client,
  name: string,
  args: Record<string, unknown>,
): Promise<CallToolResult> {
  const result = await agent.callTool({ name, arguments: args }, undefined, {
    timeout: 10_000,
  });
  return result as CallToolResult;
}

/** The request a durable tool returned. Fails the test if the tool failed. */
export function viewOf(result: CallToolResult): RequestView {
  assert.notEqual(result.isError, true, JSON.stringify(result.content));
  return result.structuredContent as unknown as RequestView;
}

/** The code of a failed tool result, such as `BUSY`, or undefined for a success. */
export function errorCode(result: CallToolResult): string | undefined {
  if (result.isError !== true) return undefined;
  const first = result.content[0];
  return first?.type === "text"
    ? /^([A-Z_]+): /.exec(first.text)?.[1]
    : undefined;
}

/** Polls `condition` every 10 ms until it holds, failing after `timeoutMs`. */
export async function waitFor(
  condition: () => boolean,
  description: string,
  timeoutMs = 3000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!condition()) {
    if (Date.now() > deadline)
      assert.fail(`timed out waiting for ${description}`);
    await delay(10);
  }
}

function transport(baseUrl: string, token: string | undefined) {
  return createConnectTransport({
    baseUrl,
    httpVersion: "1.1",
    interceptors: [authorization(token)],
  });
}

function authorization(token: string | undefined): Interceptor {
  return (next) => (request) => {
    if (token !== undefined)
      request.header.set("Authorization", `Bearer ${token}`);
    return next(request);
  };
}
