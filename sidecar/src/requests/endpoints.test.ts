import assert from "node:assert/strict";
import { request as httpRequest } from "node:http";
import { after, before, describe, it } from "node:test";

import type { Client } from "@modelcontextprotocol/sdk/client/index.js";

import {
  RequestError,
  RequestErrorDetailSchema,
  RequestState,
} from "../gen/seekervault/request/v1/request_pb.js";
import { startSidecar, type Sidecar } from "../server.ts";
import {
  Code,
  ConnectError,
  callTool,
  connectAgent,
  errorCode,
  requestClient,
  viewOf,
} from "../testing/clients.ts";
import {
  CANCEL_REQUEST_TOOL,
  GET_REQUEST_TOOL,
  REQUEST_ACK_TOOL,
  type RequestView,
} from "./mcp-tools.ts";

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);
const OTHER_CONNECTION = "a7e9c1b3-4d5f-4a6b-8c7d-9e0f1a2b3c4d";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const logs: string[] = [];
let sidecar: Sidecar;
let agent: Client;
let keys = 0;

function config(pendingLimit = 100) {
  return {
    host: "127.0.0.1",
    port: 0,
    mcpToken: MCP_TOKEN,
    phoneToken: PHONE_TOKEN,
    liveCommandTimeoutSeconds: 1,
    databasePath: ":memory:",
    requestTtlSeconds: 86_400,
    pendingLimit,
  };
}

before(async () => {
  sidecar = await startSidecar(config(), { log: (line) => logs.push(line) });
  agent = await connectAgent(sidecar.url, MCP_TOKEN);
});

after(async () => {
  await agent.close();
  await sidecar.close();
});

async function queue(
  text: string,
  fields: Record<string, unknown> = {},
  target: Client = agent,
): Promise<RequestView> {
  return viewOf(
    await callTool(target, REQUEST_ACK_TOOL, {
      text,
      idempotency_key: `test-${++keys}`,
      ...fields,
    }),
  );
}

async function read(requestId: string): Promise<RequestView> {
  return viewOf(
    await callTool(agent, GET_REQUEST_TOOL, { request_id: requestId }),
  );
}

function refOf(view: RequestView, connectionId = sidecar.connectionId) {
  return { connectionId, requestId: view.request_id };
}

function phone(token: string | undefined = PHONE_TOKEN, target = sidecar) {
  return requestClient(target.url, token);
}

/** A ConnectError with this code, whose RequestErrorDetail names `error` (and `state`). */
function connectFailure(
  code: Code,
  error: RequestError,
  state?: RequestState,
): (thrown: unknown) => boolean {
  return (thrown) => {
    if (!(thrown instanceof ConnectError) || thrown.code !== code) return false;
    const [detail] = thrown.findDetails(RequestErrorDetailSchema);
    return (
      detail?.error === error &&
      (state === undefined || detail.request?.state === state)
    );
  };
}

/** A raw POST to /mcp: chunked with `body`, or only headers declaring `declaredLength` bytes. */
function postMcp(options: {
  readonly body?: string;
  readonly declaredLength?: number;
}): Promise<number> {
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
          Authorization: `Bearer ${MCP_TOKEN}`,
          ...(options.declaredLength === undefined
            ? {}
            : { "Content-Length": String(options.declaredLength) }),
        },
      },
      (res) => {
        res.resume();
        resolve(res.statusCode ?? 0);
        if (options.declaredLength !== undefined) req.destroy();
      },
    );
    req.on("error", reject);
    if (options.body !== undefined) req.end(options.body);
    else req.flushHeaders();
  });
}

describe("durable requests over MCP and Connect", () => {
  it("lists the durable tools beside the live one, and says that storing isn't approval", async () => {
    const { tools } = await agent.listTools();
    assert.deepEqual(
      tools.map((tool) => tool.name).sort(),
      [
        CANCEL_REQUEST_TOOL,
        "vault_display_command",
        GET_REQUEST_TOOL,
        REQUEST_ACK_TOOL,
      ].sort(),
    );
    assert.match(
      tools.find((tool) => tool.name === REQUEST_ACK_TOOL)?.description ?? "",
      /not the owner's approval/,
    );
  });

  it("answers vault_request_ack at once with a PENDING request, with no phone connected", async () => {
    const started = Date.now();
    const view = await queue("Deploy finished", { note: "Checking the queue" });
    assert.ok(
      Date.now() - started < 1000,
      "creation doesn't wait for the owner",
    );
    assert.match(view.request_id, UUID);
    assert.equal(view.action, "ack");
    assert.equal(view.status, "PENDING");
    assert.equal(view.terminal, false);
    assert.equal(
      Date.parse(view.expires_at) - Date.parse(view.created_at),
      86_400_000,
    );
    assert.equal(view.signature, undefined);
    assert.equal(view.detail, undefined);
  });

  it("carries a request to the phone and the owner's answer back, and keeps that answer", async () => {
    const text = "Round trip text that must stay out of the logs";
    const note = "A note that must stay out of the logs too";
    const created = await queue(text, { note, expires_in_seconds: 600 });
    const listed = await phone().listPending({
      connectionId: sidecar.connectionId,
    });
    const stored = listed.requests.find(
      (request) => request.ref?.requestId === created.request_id,
    );
    assert.equal(stored?.action?.kind.case, "ack");
    assert.equal(stored.action.kind.value.text, text);
    assert.equal(stored.agentNote, note);

    const { request } = await phone().submitResult({
      ref: refOf(created),
      result: { case: "acknowledgement", value: {} },
    });
    assert.equal(request?.state, RequestState.COMPLETED);
    const first = await read(created.request_id);
    assert.equal(first.status, "COMPLETED");
    assert.equal(first.terminal, true);
    assert.deepEqual(await read(created.request_id), first);
    assert.ok(!logs.some((line) => line.includes(text) || line.includes(note)));
  });

  it("returns the same request for a retried key, and refuses a changed retry", async () => {
    const args = { text: "Pay invoice 42", idempotency_key: "endpoint-retry" };
    const first = viewOf(await callTool(agent, REQUEST_ACK_TOOL, args));
    const retry = viewOf(await callTool(agent, REQUEST_ACK_TOOL, args));
    assert.deepEqual(retry, first);
    const changed = await callTool(agent, REQUEST_ACK_TOOL, {
      ...args,
      text: "Pay invoice 43",
    });
    assert.equal(errorCode(changed), "IDEMPOTENCY_CONFLICT");
    assert.equal(changed.structuredContent, undefined);
  });

  it("lets the agent cancel a PENDING request, and tells the phone why its late answer fails", async () => {
    const created = await queue("Withdrawn before the owner decides");
    const cancelled = viewOf(
      await callTool(agent, CANCEL_REQUEST_TOOL, {
        request_id: created.request_id,
      }),
    );
    assert.equal(cancelled.status, "CANCELLED");
    assert.equal(cancelled.detail, "The agent cancelled the request.");
    await assert.rejects(
      phone().submitResult({
        ref: refOf(created),
        result: { case: "acknowledgement", value: {} },
      }),
      connectFailure(
        Code.FailedPrecondition,
        RequestError.INVALID_STATE,
        RequestState.CANCELLED,
      ),
    );
  });

  it("settles simultaneous cancellations and acknowledgements with exactly one winner", async () => {
    const created = await Promise.all(
      Array.from({ length: 8 }, (_, index) => queue(`Race ${index}`)),
    );
    await Promise.all(
      created.map(async (view) => {
        const [cancel, acknowledge] = await Promise.allSettled([
          callTool(agent, CANCEL_REQUEST_TOOL, { request_id: view.request_id }),
          phone().submitResult({
            ref: refOf(view),
            result: { case: "acknowledgement", value: {} },
          }),
        ]);
        assert.equal(cancel.status, "fulfilled");
        const cancelled = errorCode(cancel.value) === undefined;
        assert.equal(
          acknowledge.status === "fulfilled",
          !cancelled,
          "exactly one wins",
        );
        if (cancelled) assert.equal(errorCode(cancel.value), undefined);
        else assert.equal(errorCode(cancel.value), "INVALID_STATE");
        assert.equal(
          (await read(view.request_id)).status,
          cancelled ? "CANCELLED" : "COMPLETED",
        );
      }),
    );
  });

  it("accepts a repeated result once, and refuses a different one after the end", async () => {
    const created = await queue("Acknowledged twice");
    const submit = {
      ref: refOf(created),
      result: { case: "acknowledgement" as const, value: {} },
    };
    const first = await phone().submitResult(submit);
    const again = await phone().submitResult(submit);
    assert.deepEqual(again.request, first.request);
    assert.ok(
      logs.some((line) =>
        line.includes(
          `request ${created.request_id}: a repeated acknowledgement changed nothing`,
        ),
      ),
    );
    await assert.rejects(
      phone().submitResult({
        ref: refOf(created),
        result: { case: "rejection", value: {} },
      }),
      connectFailure(
        Code.FailedPrecondition,
        RequestError.INVALID_STATE,
        RequestState.COMPLETED,
      ),
    );
  });

  it("refuses a new request beyond the pending limit, until the owner answers one", async () => {
    const limited = await startSidecar(config(2), { log: () => undefined });
    const limitedAgent = await connectAgent(limited.url, MCP_TOKEN);
    try {
      const first = await queue("One", {}, limitedAgent);
      await queue("Two", {}, limitedAgent);
      const third = await callTool(limitedAgent, REQUEST_ACK_TOOL, {
        text: "Three",
        idempotency_key: "limit-three",
      });
      assert.equal(errorCode(third), "PENDING_LIMIT");
      await phone(PHONE_TOKEN, limited).submitResult({
        ref: refOf(first, limited.connectionId),
        result: { case: "rejection", value: {} },
      });
      const retried = await callTool(limitedAgent, REQUEST_ACK_TOOL, {
        text: "Three",
        idempotency_key: "limit-three",
      });
      assert.equal(viewOf(retried).status, "PENDING");
    } finally {
      await limitedAgent.close();
      await limited.close();
    }
  });

  it("bounds the size of every request", async () => {
    const failures = [
      [
        { text: "x".repeat(4097) },
        "INVALID_PARAMETERS: text is 4097 UTF-8 bytes; the limit is 4096",
      ],
      [
        { text: "Hi", note: "n".repeat(1025) },
        "INVALID_PARAMETERS: note is 1025 UTF-8 bytes; the limit is 1024",
      ],
      [
        { text: "Hi", expires_in_seconds: 30 },
        "INVALID_PARAMETERS: expires_in_seconds must be a whole number from 60 to 604800",
      ],
    ] as const;
    for (const [fields, message] of failures) {
      const result = await callTool(agent, REQUEST_ACK_TOOL, {
        idempotency_key: `bounds-${++keys}`,
        ...fields,
      });
      const [content] = result.content;
      assert.equal(
        content?.type === "text" ? content.text : undefined,
        message,
      );
    }
    assert.equal(await postMcp({ body: "x".repeat(70 * 1024) }), 413);
    assert.equal(await postMcp({ declaredLength: 1024 * 1024 }), 413);
    await assert.rejects(
      phone().submitResult({
        ref: {
          connectionId: sidecar.connectionId,
          requestId: OTHER_CONNECTION,
        },
        result: {
          case: "executionFailure",
          value: { detail: "x".repeat(70 * 1024) },
        },
      }),
      (thrown) =>
        thrown instanceof ConnectError &&
        thrown.code === Code.ResourceExhausted,
    );
  });

  it("authenticates the phone, keeps it to its own connection, and prepares nothing for an ack", async () => {
    const created = await queue("Scoped to the connection");
    for (const token of [undefined, MCP_TOKEN]) {
      await assert.rejects(
        requestClient(sidecar.url, token).listPending({
          connectionId: sidecar.connectionId,
        }),
        connectFailure(Code.Unauthenticated, RequestError.UNAUTHENTICATED),
      );
    }
    await assert.rejects(
      phone().listPending({ connectionId: OTHER_CONNECTION }),
      connectFailure(Code.NotFound, RequestError.NOT_FOUND),
    );
    await assert.rejects(
      phone().getRequest({ ref: refOf(created, OTHER_CONNECTION) }),
      connectFailure(Code.NotFound, RequestError.NOT_FOUND),
    );
    await assert.rejects(
      phone().getRequest({
        ref: { connectionId: sidecar.connectionId, requestId: "abc" },
      }),
      connectFailure(Code.InvalidArgument, RequestError.INVALID_PARAMETERS),
    );
    await assert.rejects(
      phone().prepareRequest({ ref: refOf(created) }),
      connectFailure(
        Code.InvalidArgument,
        RequestError.INVALID_PARAMETERS,
        RequestState.PENDING,
      ),
    );
    const { request } = await phone().getRequest({ ref: refOf(created) });
    assert.equal(request?.state, RequestState.PENDING);
  });

  it("reports unknown and malformed request IDs to the agent", async () => {
    const unknown = await callTool(agent, GET_REQUEST_TOOL, {
      request_id: OTHER_CONNECTION,
    });
    assert.equal(errorCode(unknown), "NOT_FOUND");
    const malformed = await callTool(agent, CANCEL_REQUEST_TOOL, {
      request_id: "abc",
    });
    assert.equal(errorCode(malformed), "INVALID_PARAMETERS");
  });
});
