/**
 * Real clients for this server's two faces, used by the integration tests: Connect clients acting
 * as the phone — including the genuine gRPC one the phone streams updates over — and an MCP SDK
 * client acting as the agent.
 *
 * They are the general server's test clients (servers/mcp-server/src/testing/clients.ts) cut down to what
 * this server serves. Nothing here imports from `mcp-server`, and nothing should: the two packages
 * share the SDK and nothing else.
 */
import assert from "node:assert/strict";
import { setTimeout as delay } from "node:timers/promises";

import { create } from "@bufbuild/protobuf";
import {
  createClient,
  type Client as ConnectClient,
  type Interceptor,
} from "@connectrpc/connect";
import {
  createConnectTransport,
  createGrpcTransport,
} from "@connectrpc/connect-node";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

import {
  PairingService,
  RequestService,
  SubscribeRequestSchema,
  SubscribeSchema,
  UpdateService,
  type SubscribeRequest,
  type SubscribeResponse,
} from "@seeker-vault/server-sdk/protocol";

/**
 * Which HTTP the phone's unary calls go over. "1.1" is what the phone uses against an `http://`
 * origin; "2" is what reaches an h2c listener, which refuses HTTP/1.1 outright.
 */
export type UnaryHttp = "1.1" | "2";

/** A Connect client for PairingService, acting as the phone. */
export function pairingClient(
  baseUrl: string,
  token: string,
  http: UnaryHttp = "1.1",
): ConnectClient<typeof PairingService> {
  return createClient(PairingService, unary(baseUrl, token, http));
}

/** A Connect client for the durable RequestService, acting as the phone. */
export function requestClient(
  baseUrl: string,
  token: string,
  http: UnaryHttp = "1.1",
): ConnectClient<typeof RequestService> {
  return createClient(RequestService, unary(baseUrl, token, http));
}

/**
 * The update stream's client: genuine gRPC over cleartext HTTP/2, which is what the phone opens
 * against a loopback or proxied origin and the only transport UpdateService.Subscribe is served on.
 */
export function updateClient(
  baseUrl: string,
  token: string,
): ConnectClient<typeof UpdateService> {
  return createClient(
    UpdateService,
    createGrpcTransport({
      baseUrl,
      interceptors: [authorization(token)],
    }),
  );
}

/** A phone paired by `pairPhone`. */
export interface TestPhone {
  readonly connectionId: string;
  readonly phoneToken: string;
  /** The update origin Pair advertised, or undefined when it advertised none. */
  readonly updateUrl: string | undefined;
}

/**
 * Pairs a test phone the way the owner does: a code issued by the server itself, then Pair with
 * that code's token and the origin it names.
 */
export async function pairPhone(
  baseUrl: string,
  issued: { readonly token: string; readonly serverUrl: string },
  http: UnaryHttp = "1.1",
): Promise<TestPhone> {
  const paired = await pairingClient(baseUrl, issued.token, http).pair({
    serverUrl: issued.serverUrl,
    deviceName: "Test phone",
  });
  return {
    connectionId: paired.connectionId,
    phoneToken: paired.phoneToken,
    updateUrl: paired.updates?.grpcUrl,
  };
}

/** The first message on a Subscribe stream: protocol 1, no cursor, so a fresh start. */
export function subscribeMessage(connectionId: string): SubscribeRequest {
  return create(SubscribeRequestSchema, {
    connectionId,
    message: {
      case: "subscribe",
      value: create(SubscribeSchema, {
        protocolVersion: 1,
        resumeCursor: "",
        serverInstanceId: "",
      }),
    },
  });
}

/** An open update stream, read one event at a time. */
export interface UpdateStream {
  next(timeoutMs?: number): Promise<SubscribeResponse>;
  close(): void;
}

/** Opens Subscribe the way the phone's foreground loop does and sends its opening message. */
export function subscribe(
  baseUrl: string,
  token: string,
  connectionId: string,
): UpdateStream {
  const input = new StreamInput<SubscribeRequest>();
  const abort = new AbortController();
  const stream = updateClient(baseUrl, token).subscribe(input, {
    signal: abort.signal,
  });
  const replies = stream[Symbol.asyncIterator]();
  input.push(subscribeMessage(connectionId));
  return {
    next: async (timeoutMs = 2_000) => {
      const result = await Promise.race([
        replies.next(),
        new Promise<never>((_, reject) =>
          setTimeout(
            () => reject(new Error("timed out waiting for an update event")),
            timeoutMs,
          ).unref(),
        ),
      ]);
      assert.equal(result.done, false, "the update stream ended");
      return result.value;
    },
    close: () => {
      input.close();
      abort.abort();
    },
  };
}

export async function connectAgent(
  baseUrl: string,
  token: string,
): Promise<Client> {
  const client = new Client({ name: "skr-staking-tests", version: "0.0.0" });
  await client.connect(
    new StreamableHTTPClientTransport(new URL("/mcp", baseUrl), {
      requestInit: { headers: { Authorization: `Bearer ${token}` } },
    }),
  );
  return client;
}

/** Calls any tool; the staking tools answer at once, so the client timeout is short. */
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

/** Polls `condition` every 10 ms until it holds, failing after `timeoutMs`. */
export async function waitFor(
  condition: () => boolean,
  description: string,
  timeoutMs = 3_000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!condition()) {
    if (Date.now() > deadline)
      assert.fail(`timed out waiting for ${description}`);
    await delay(10);
  }
}

function unary(baseUrl: string, token: string, http: UnaryHttp) {
  return createConnectTransport({
    baseUrl,
    httpVersion: http,
    interceptors: [authorization(token)],
  });
}

function authorization(token: string): Interceptor {
  return (next) => (request) => {
    request.header.set("Authorization", `Bearer ${token}`);
    return next(request);
  };
}

/** The client half of a bidirectional stream, fed one message at a time. */
class StreamInput<T> implements AsyncIterable<T> {
  readonly #queued: T[] = [];
  readonly #waiting: ((value: IteratorResult<T>) => void)[] = [];
  #closed = false;

  push(value: T): void {
    const waiter = this.#waiting.shift();
    if (waiter === undefined) this.#queued.push(value);
    else waiter({ done: false, value });
  }

  close(): void {
    this.#closed = true;
    for (const waiter of this.#waiting.splice(0)) {
      waiter({ done: true, value: undefined });
    }
  }

  [Symbol.asyncIterator](): AsyncIterator<T> {
    return {
      next: () => {
        const value = this.#queued.shift();
        if (value !== undefined) {
          return Promise.resolve({ done: false, value });
        }
        if (this.#closed) {
          return Promise.resolve({ done: true, value: undefined });
        }
        return new Promise((resolve) => this.#waiting.push(resolve));
      },
      // Connect ends the input when the call ends, however it ends; a waiter left behind would
      // hold the test process open. Both halves are needed: Connect makes the input abortable and
      // refuses outright ("AsyncIterable does not implement throw") an iterator that has only
      // `return`, so a stream aborted mid-flight would fail the test rather than close it.
      return: () => {
        this.close();
        return Promise.resolve({ done: true, value: undefined });
      },
      throw: (error?: unknown) => {
        this.close();
        return Promise.reject(
          error instanceof Error ? error : new Error("stream input failed"),
        );
      },
    };
  }
}
