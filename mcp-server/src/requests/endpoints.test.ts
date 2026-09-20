import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { request as httpRequest } from "node:http";
import { after, before, describe, it } from "node:test";

import type { Client } from "@modelcontextprotocol/sdk/client/index.js";

import { timestampDate } from "@bufbuild/protobuf/wkt";

import {
  Network,
  RequestError,
  RequestErrorDetailSchema,
  RequestState,
} from "@seeker-vault/server-sdk/protocol";
import { PairingStore } from "../../../server-sdk/src/storage/pairing-store.ts";
import { startSidecar, type Sidecar } from "../server.ts";
import { openDatabase } from "../../../server-sdk/src/storage/database.ts";
import {
  Code,
  ConnectError,
  callTool,
  connectAgent,
  errorCode,
  pairPhone,
  pairingClient,
  requestClient,
  viewOf,
  type TestPhone,
} from "../testing/clients.ts";
import { temporaryDatabasePath } from "../testing/process.ts";
import { testWallet } from "../../../server-sdk/src/testing/wallet.ts";
import { decodeBase58 } from "../../../server-sdk/src/requests/action.ts";
import { verifySignature } from "../../../server-sdk/src/requests/signature.ts";
import { CREATE_PAIRING_LINK_TOOL } from "../pairing/mcp-tool.ts";
import {
  CANCEL_REQUEST_TOOL,
  GET_ADDRESS_TOOL,
  GET_CAPABILITIES_TOOL,
  GET_REQUEST_TOOL,
  REQUEST_ACK_TOOL,
  SIGN_MESSAGE_TOOL,
  type RequestView,
} from "./mcp-tools.ts";

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);
const OTHER_CONNECTION = "a7e9c1b3-4d5f-4a6b-8c7d-9e0f1a2b3c4d";
const WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const logs: string[] = [];
let sidecar: Sidecar;
let agent: Client;
let paired: TestPhone;
let keys = 0;

function config(databasePath: string, pendingLimit = 100, demoTools = true) {
  return {
    host: "127.0.0.1",
    port: 0,
    mcpToken: MCP_TOKEN,
    phoneToken: PHONE_TOKEN,
    liveCommandTimeoutSeconds: 1,
    databasePath,
    requestTtlSeconds: 86_400,
    pendingLimit,
    demoTools,
  };
}

before(async () => {
  const databasePath = temporaryDatabasePath();
  sidecar = await startSidecar(config(databasePath), {
    log: (line) => logs.push(line),
  });
  agent = await connectAgent(sidecar.url, MCP_TOKEN);
  paired = await pairPhone(sidecar.url, databasePath);
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

function refOf(view: RequestView, connectionId = paired.connectionId) {
  return { connectionId, requestId: view.request_id };
}

function phone(token: string = paired.phoneToken, target = sidecar) {
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
    assert.match(agent.getInstructions() ?? "", /^Seeker Agent Connect puts /);
    const { tools } = await agent.listTools();
    assert.deepEqual(
      tools.map((tool) => tool.name).sort(),
      [
        CANCEL_REQUEST_TOOL,
        CREATE_PAIRING_LINK_TOOL,
        "vault_display_command",
        GET_ADDRESS_TOOL,
        GET_CAPABILITIES_TOOL,
        GET_REQUEST_TOOL,
        REQUEST_ACK_TOOL,
        SIGN_MESSAGE_TOOL,
      ].sort(),
    );
    assert.match(
      tools.find((tool) => tool.name === REQUEST_ACK_TOOL)?.description ?? "",
      /not the owner's approval/,
    );
  });

  it("serves vault_request_ack only when the demo tools are on", async () => {
    const plain = await startSidecar(config(":memory:", 100, false), {
      log: () => undefined,
    });
    const plainAgent = await connectAgent(plain.url, MCP_TOKEN);
    try {
      const { tools } = await plainAgent.listTools();
      assert.deepEqual(
        tools.map((tool) => tool.name).sort(),
        [
          CANCEL_REQUEST_TOOL,
          CREATE_PAIRING_LINK_TOOL,
          "vault_display_command",
          GET_ADDRESS_TOOL,
          GET_CAPABILITIES_TOOL,
          GET_REQUEST_TOOL,
          SIGN_MESSAGE_TOOL,
        ].sort(),
      );
      assert.doesNotMatch(
        plainAgent.getInstructions() ?? "",
        /vault_request_ack/,
      );
      assert.match(
        agent.getInstructions() ?? "",
        /vault_request_ack, a development and demo tool/,
      );
      const refused = await callTool(plainAgent, REQUEST_ACK_TOOL, {
        text: "Not a demo sidecar",
        idempotency_key: "no-demo-1",
      });
      assert.equal(refused.isError, true);
      const [content] = refused.content;
      assert.match(
        content?.type === "text" ? content.text : "",
        /Tool vault_request_ack not found/,
      );
    } finally {
      await plainAgent.close();
      await plain.close();
    }
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
      connectionId: paired.connectionId,
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
    const limitedPath = temporaryDatabasePath();
    const limited = await startSidecar(config(limitedPath, 2), {
      log: () => undefined,
    });
    const limitedAgent = await connectAgent(limited.url, MCP_TOKEN);
    const limitedPhone = await pairPhone(limited.url, limitedPath);
    try {
      const first = await queue("One", {}, limitedAgent);
      await queue("Two", {}, limitedAgent);
      const third = await callTool(limitedAgent, REQUEST_ACK_TOOL, {
        text: "Three",
        idempotency_key: "limit-three",
      });
      assert.equal(errorCode(third), "PENDING_LIMIT");
      await phone(limitedPhone.phoneToken, limited).submitResult({
        ref: refOf(first, limitedPhone.connectionId),
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
          connectionId: paired.connectionId,
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
    // The Stage 1 development token belongs to the live diagnostic, not to a paired phone.
    for (const token of [undefined, MCP_TOKEN, PHONE_TOKEN]) {
      await assert.rejects(
        requestClient(sidecar.url, token).listPending({
          connectionId: paired.connectionId,
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
        ref: { connectionId: paired.connectionId, requestId: "abc" },
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

  it("publishes the owner's wallet, and gives the agent the address but never a key", async () => {
    const before = await callTool(agent, GET_ADDRESS_TOOL, {});
    assert.equal(errorCode(before), "WALLET_NOT_CONNECTED");
    const published = await phone().publishWallet({
      connectionId: paired.connectionId,
      binding: { wallet: WALLET, network: Network.DEVNET },
    });
    assert.equal(published.binding?.wallet, WALLET);
    assert.deepEqual(published.cancelled, []);
    const view = viewOf(await callTool(agent, GET_ADDRESS_TOOL, {})) as unknown;
    assert.deepEqual(view, {
      wallet: WALLET,
      network: "devnet",
      bound_at: timestampDate(published.binding.boundAt!).toISOString(),
    });
    // Disconnecting the wallet leaves the agent with no address at all.
    await phone().publishWallet({ connectionId: paired.connectionId });
    assert.equal(
      errorCode(await callTool(agent, GET_ADDRESS_TOOL, {})),
      "WALLET_NOT_CONNECTED",
    );
  });

  it("keeps the phone to its own connection's wallet, and checks what it publishes", async () => {
    await assert.rejects(
      phone().publishWallet({ connectionId: OTHER_CONNECTION }),
      connectFailure(Code.NotFound, RequestError.NOT_FOUND),
    );
    await assert.rejects(
      phone().publishWallet({
        connectionId: paired.connectionId,
        binding: { wallet: "not-an-address", network: Network.DEVNET },
      }),
      connectFailure(Code.InvalidArgument, RequestError.INVALID_PARAMETERS),
    );
  });

  it("says what it can do, and never promises an operation it doesn't serve", async () => {
    const view = viewOf(
      await callTool(agent, GET_CAPABILITIES_TOOL, {}),
    ) as unknown as Record<string, unknown>;
    assert.equal(view.approval, "manual");
    assert.equal(view.signing, "wallet");
    // The demo tool is on for this sidecar, so ack is served here too; transfer and swap are
    // not implemented, and must not be advertised.
    assert.deepEqual(view.operations, ["ack", "sign_message"]);
    assert.equal(view.wallet_connected, false);
    assert.equal(view.max_message_bytes, 4096);
    assert.equal(view.min_expires_in_seconds, 60);
    await phone().publishWallet({
      connectionId: paired.connectionId,
      binding: { wallet: WALLET, network: Network.DEVNET },
    });
    try {
      const connected = viewOf(
        await callTool(agent, GET_CAPABILITIES_TOOL, {}),
      ) as unknown as Record<string, unknown>;
      assert.equal(connected.wallet_connected, true);
    } finally {
      await phone().publishWallet({ connectionId: paired.connectionId });
    }
  });

  it("signs a message only after the owner approves, and returns the signed bytes", async () => {
    const signer = testWallet();
    // Decomposed e-acute and a CRLF: bytes that any normalization would change.
    const message = "Sign in to Example\r\ne\u{301}";
    const bytes = new TextEncoder().encode(message);
    await phone().publishWallet({
      connectionId: paired.connectionId,
      binding: { wallet: signer.address, network: Network.DEVNET },
    });
    try {
      // Another wallet than the owner's is refused before anything is stored.
      const mismatch = await callTool(agent, SIGN_MESSAGE_TOOL, {
        wallet: WALLET,
        message,
        idempotency_key: `sign-${++keys}`,
      });
      assert.equal(errorCode(mismatch), "WALLET_MISMATCH");

      const created = viewOf(
        await callTool(agent, SIGN_MESSAGE_TOOL, {
          wallet: signer.address,
          message,
          idempotency_key: `sign-${++keys}`,
          note: "Logging in to Example",
        }),
      );
      assert.equal(created.action, "sign_message");
      assert.equal(created.status, "PENDING");
      assert.equal(created.wallet, signer.address);
      assert.equal(created.signature, undefined);
      assert.equal(created.signed_message_base64, undefined);

      // The phone approves exactly what the owner reviewed, then reports the wallet's signature.
      const ref = refOf(created);
      const approved = await phone().submitResult({
        ref,
        result: {
          case: "approval",
          value: {
            preparedVersion: 0,
            contentHash: createHash("sha256").update(bytes).digest(),
          },
        },
      });
      assert.equal(approved.request?.state, RequestState.PROCESSING);
      // A signature over anything but the request's own bytes is refused, and the request stays
      // where it was: the sidecar checks it rather than taking the phone's word.
      await assert.rejects(
        phone().submitResult({
          ref,
          result: {
            case: "messageSignature",
            value: { signature: signer.sign(`${message} `) },
          },
        }),
        connectFailure(Code.InvalidArgument, RequestError.INVALID_PARAMETERS),
      );
      assert.equal((await read(created.request_id)).status, "PROCESSING");

      const completed = await phone().submitResult({
        ref,
        result: {
          case: "messageSignature",
          value: { signature: signer.sign(bytes) },
        },
      });
      assert.equal(completed.request?.state, RequestState.COMPLETED);

      const view = await read(created.request_id);
      assert.equal(view.status, "COMPLETED");
      assert.equal(view.terminal, true);
      assert.equal(view.wallet, signer.address);
      assert.equal(
        view.signed_message_base64,
        Buffer.from(bytes).toString("base64"),
      );
      // What the agent gets back is enough to check the signature on its own: the address, the
      // exact bytes, and the signature, with no re-encoding of the message anywhere.
      const signature = decodeBase58(view.signature ?? "");
      assert.ok(signature !== undefined);
      assert.equal(
        verifySignature(
          view.wallet ?? "",
          Uint8Array.from(
            Buffer.from(view.signed_message_base64 ?? "", "base64"),
          ),
          signature,
        ),
        true,
      );
    } finally {
      await phone().publishWallet({ connectionId: paired.connectionId });
    }
  });

  it("signs the owner's text and offers no way to queue raw bytes", async () => {
    const signer = testWallet();
    await phone().publishWallet({
      connectionId: paired.connectionId,
      binding: { wallet: signer.address, network: Network.DEVNET },
    });
    try {
      // The tool takes the message as text and nothing else (SEE-24): the owner reviews exactly
      // what their wallet signs, so there is no public way to ask for bytes that aren't text.
      const { tools } = await agent.listTools();
      const schema = tools.find((tool) => tool.name === SIGN_MESSAGE_TOOL)
        ?.inputSchema as {
        properties?: Record<string, unknown>;
        required?: string[];
      };
      assert.deepEqual(Object.keys(schema.properties ?? {}).sort(), [
        "expires_in_seconds",
        "idempotency_key",
        "message",
        "note",
        "wallet",
      ]);
      assert.ok(schema.required?.includes("message"));

      // An agent that sends bytes instead of text is refused before anything is stored: the
      // field doesn't exist, and message is required.
      const bytes = await callTool(agent, SIGN_MESSAGE_TOOL, {
        wallet: signer.address,
        message_base64: Buffer.from([0, 1, 2, 250, 255]).toString("base64"),
        idempotency_key: `sign-${++keys}`,
      });
      assert.equal(bytes.isError, true);
      const [refusal] = bytes.content;
      assert.match(
        refusal?.type === "text" ? refusal.text : "",
        /expected string, received undefined at message/,
      );

      for (const [fields, reason] of [
        [{ message: "" }, /message is empty/],
        [{ message: "x".repeat(4097) }, /the limit is 4096/],
      ] as const) {
        const refused = await callTool(agent, SIGN_MESSAGE_TOOL, {
          wallet: signer.address,
          idempotency_key: `sign-${++keys}`,
          ...fields,
        });
        assert.equal(errorCode(refused), "INVALID_PARAMETERS");
        const [content] = refused.content;
        assert.match(content?.type === "text" ? content.text : "", reason);
      }
    } finally {
      await phone().publishWallet({ connectionId: paired.connectionId });
    }
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

// A fake clock that moves only when the test moves it, and a throwaway database for each sidecar.
describe("durable requests and pairing codes on the sidecar's clock", () => {
  async function clockedSidecar(): Promise<{
    readonly sidecar: Sidecar;
    readonly databasePath: string;
    readonly clock: { now: number };
  }> {
    const clock = { now: Date.now() };
    const databasePath = temporaryDatabasePath();
    const started = await startSidecar(config(databasePath), {
      log: () => undefined,
      now: () => clock.now,
    });
    return { sidecar: started, databasePath, clock };
  }

  it("expires a request on both endpoints at its deadline, and not a millisecond before", async () => {
    const { sidecar: clocked, databasePath, clock } = await clockedSidecar();
    const clockedAgent = await connectAgent(clocked.url, MCP_TOKEN);
    try {
      const owner = await pairPhone(clocked.url, databasePath);
      const created = viewOf(
        await callTool(clockedAgent, REQUEST_ACK_TOOL, {
          text: "Expires on the sidecar's clock",
          idempotency_key: "clock-1",
          expires_in_seconds: 60,
        }),
      );
      assert.equal(created.created_at, new Date(clock.now).toISOString());
      assert.equal(
        created.expires_at,
        new Date(clock.now + 60_000).toISOString(),
      );
      const read = async (): Promise<RequestView> =>
        viewOf(
          await callTool(clockedAgent, GET_REQUEST_TOOL, {
            request_id: created.request_id,
          }),
        );
      const pending = async (): Promise<(string | undefined)[]> =>
        (
          await phone(owner.phoneToken, clocked).listPending({
            connectionId: owner.connectionId,
          })
        ).requests.map((request) => request.ref?.requestId);

      clock.now += 59_999;
      assert.equal((await read()).status, "PENDING");
      assert.deepEqual(await pending(), [created.request_id]);

      clock.now += 1;
      const expired = await read();
      assert.equal(expired.status, "EXPIRED");
      assert.equal(expired.terminal, true);
      assert.equal(expired.updated_at, new Date(clock.now).toISOString());
      assert.equal(
        expired.detail,
        "The request expired before the owner decided.",
      );
      assert.deepEqual(await pending(), []);
      await assert.rejects(
        phone(owner.phoneToken, clocked).submitResult({
          ref: refOf(created, owner.connectionId),
          result: { case: "acknowledgement", value: {} },
        }),
        connectFailure(
          Code.FailedPrecondition,
          RequestError.INVALID_STATE,
          RequestState.EXPIRED,
        ),
      );
      assert.equal(
        errorCode(
          await callTool(clockedAgent, CANCEL_REQUEST_TOOL, {
            request_id: created.request_id,
          }),
        ),
        "INVALID_STATE",
      );
    } finally {
      await clockedAgent.close();
      await clocked.close();
    }
  });

  it("pairs with a code until its expiry, refuses it from then on, and keeps the paired phone", async () => {
    const { sidecar: clocked, databasePath, clock } = await clockedSidecar();
    const issue = (): string => {
      const db = openDatabase(databasePath);
      try {
        return new PairingStore(db, { now: () => clock.now }).issue(
          clocked.url,
          60,
        ).token;
      } finally {
        db.close();
      }
    };
    try {
      const first = issue();
      clock.now += 59_999;
      const paired = await pairingClient(clocked.url, first).pair({
        serverUrl: clocked.url,
        deviceName: "In time",
      });
      const late = issue();
      clock.now += 60_000;
      await assert.rejects(
        pairingClient(clocked.url, late).pair({
          serverUrl: clocked.url,
          deviceName: "Too late",
        }),
        connectFailure(Code.Unauthenticated, RequestError.UNAUTHENTICATED),
      );
      // The refused code revoked nothing: the phone paired in time still works.
      const { requests } = await phone(paired.phoneToken, clocked).listPending({
        connectionId: paired.connectionId,
      });
      assert.deepEqual(requests, []);
    } finally {
      await clocked.close();
    }
  });
});
