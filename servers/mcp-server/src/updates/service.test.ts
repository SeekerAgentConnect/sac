/**
 * SAW-049 production transport: the real sidecar listener, durable event replay, frozen Sync,
 * role/connection isolation, replacement, revocation, restart recovery, and bounded history.
 */
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { get as httpsGet, request as httpsRequest } from "node:https";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";
import { setTimeout as delay } from "node:timers/promises";

import { create, toBinary } from "@bufbuild/protobuf";
import {
  Code,
  ConnectError,
  createClient,
  type Interceptor,
} from "@connectrpc/connect";
import {
  createConnectTransport,
  createGrpcTransport,
} from "@connectrpc/connect-node";

import {
  ActionSchema,
  Network,
  RequestState,
} from "@seekeragentconnect/server-sdk/protocol";
import {
  ListPendingResponseSchema,
  LiveCommandService,
  PairingService,
  RequestService,
} from "@seekeragentconnect/server-sdk/protocol";
import {
  ClientHeartbeatSchema,
  ResumeDisposition,
  SubscribeRequestSchema,
  SubscribeSchema,
  SyncRequiredReason,
  UpdateError,
  UpdateErrorDetailSchema,
  UpdateService,
  type SubscribeRequest,
  type SubscribeResponse,
} from "@seekeragentconnect/server-sdk/protocol";
import { startSidecar, type Sidecar } from "../server.ts";
import { openDatabase } from "../../../../packages/server-sdk/src/storage/database.ts";
import { PairingStore } from "../../../../packages/server-sdk/src/storage/pairing-store.ts";
import { RequestStore } from "../../../../packages/server-sdk/src/storage/request-store.ts";
import { SNAPSHOT_TTL_MS } from "../../../../packages/server-sdk/src/storage/update-store.ts";
import { temporaryDatabasePath } from "../testing/process.ts";
import { UPDATE_MAX_MESSAGE_BYTES } from "../../../../packages/server-sdk/src/updates/service.ts";

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);
const tlsDir = mkdtempSync(join(tmpdir(), "seeker-vault-updates-tls-"));
const certificatePath = join(tlsDir, "cert.pem");
const privateKeyPath = join(tlsDir, "key.pem");
execFileSync(
  "openssl",
  [
    "req",
    "-x509",
    "-newkey",
    "rsa:2048",
    "-nodes",
    "-days",
    "1",
    "-keyout",
    privateKeyPath,
    "-out",
    certificatePath,
    "-subj",
    "/CN=127.0.0.1",
    "-addext",
    "subjectAltName=IP:127.0.0.1",
  ],
  { stdio: "ignore" },
);
const certificate = readFileSync(certificatePath);

interface Fixture {
  readonly databasePath: string;
  readonly sidecar: Sidecar;
  readonly connectionId: string;
  readonly phoneToken: string;
  readonly pairedUpdateUrl?: string;
  readonly store: RequestStore;
  readonly close: () => Promise<void>;
}

async function fixture(
  options: {
    readonly cleartext?: boolean;
    readonly now?: () => number;
    readonly updatePollMs?: number;
  } = {},
): Promise<Fixture> {
  const databasePath = temporaryDatabasePath();
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
      ...(options.cleartext
        ? { updatePort: 0 }
        : {
            tlsCertificatePath: certificatePath,
            tlsPrivateKeyPath: privateKeyPath,
          }),
    },
    {
      log: () => undefined,
      now: options.now,
      updatePollMs: options.updatePollMs ?? 10,
    },
  );
  const pairingDb = openDatabase(databasePath);
  const issued = new PairingStore(pairingDb, { now: options.now }).issue(
    sidecar.url,
    600,
  );
  pairingDb.close();
  const paired = await pairingClient(sidecar.url, issued.token).pair({
    serverUrl: sidecar.url,
    deviceName: "Seeker",
  });
  const db = openDatabase(databasePath);
  const store = new RequestStore(db, {
    defaultTtlSeconds: 86_400,
    pendingLimit: 100,
    now: options.now,
  });
  return {
    databasePath,
    sidecar,
    connectionId: paired.connectionId,
    phoneToken: paired.phoneToken,
    pairedUpdateUrl: paired.updates?.grpcUrl,
    store,
    close: async () => {
      db.close();
      await sidecar.close();
    },
  };
}

describe("production updates over gRPC HTTP/2", () => {
  it("advertises the public HTTPS origin on an h2c main listener", async () => {
    const publicUrl = "https://vault.example.com";
    const databasePath = temporaryDatabasePath();
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
        h2c: true,
        publicUrl,
      },
      { log: () => undefined, updatePollMs: 10 },
    );
    try {
      assert.equal(sidecar.updateUrl, publicUrl);
      const pairingDb = openDatabase(databasePath);
      const issued = new PairingStore(pairingDb).issue(sidecar.url, 600);
      pairingDb.close();
      const pairing = createClient(
        PairingService,
        createConnectTransport({
          baseUrl: sidecar.url,
          httpVersion: "2",
          interceptors: [authorization(issued.token)],
        }),
      );
      const paired = await pairing.pair({
        serverUrl: sidecar.url,
        deviceName: "Seeker",
      });
      assert.equal(paired.updates?.grpcUrl, publicUrl);
      const input = new StreamInput<SubscribeRequest>();
      const abort = new AbortController();
      const replies = iteratorOf(
        updateClient(sidecar.url, paired.phoneToken, false).subscribe(input, {
          signal: abort.signal,
        }),
      );
      input.push(subscribeMessage(paired.connectionId));
      assert.equal((await next(replies)).event.case, "ready");
      abort.abort();
    } finally {
      await sidecar.close();
    }
  });

  it("keeps the update message cap off existing RequestService responses", async () => {
    const f = await fixture({ cleartext: true });
    try {
      await requestClient(f.sidecar.url, f.phoneToken).publishWallet({
        connectionId: f.connectionId,
        binding: {
          wallet: "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW",
          network: Network.DEVNET,
        },
      });
      for (let index = 0; index < 14; index += 1) {
        f.store.create({
          action: create(ActionSchema, {
            kind: {
              case: "signMessage",
              value: {
                wallet: "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW",
                content: { case: "data", value: new Uint8Array(4096) },
              },
            },
          }),
          agentNote: "n".repeat(1024),
          idempotencyKey: `large-list-${index}`,
        });
      }

      const response = await requestClient(
        f.sidecar.url,
        f.phoneToken,
      ).listPending({
        connectionId: f.connectionId,
        pageSize: 100,
      });

      assert.equal(response.requests.length, 14);
      assert.ok(
        toBinary(ListPendingResponseSchema, response).byteLength >
          UPDATE_MAX_MESSAGE_BYTES,
      );
    } finally {
      await f.close();
    }
  });

  it("serves the deployed TLS route while preserving health, pairing, and unary RequestService", async () => {
    const f = await fixture({ updatePollMs: 60_000 });
    try {
      assert.equal(f.sidecar.updateUrl, f.sidecar.url);
      assert.equal(f.pairedUpdateUrl, f.sidecar.url);
      const capability = await pairingClient(
        f.sidecar.url,
        f.phoneToken,
      ).getConnectionCapabilities({
        connectionId: f.connectionId,
      });
      assert.equal(capability.updates?.protocolVersion, 1);
      assert.equal(capability.updates?.grpcUrl, f.sidecar.url);
      assert.equal(await health(f.sidecar.url), 200);
      await assert.rejects(
        liveClient(f.sidecar.url, PHONE_TOKEN).acknowledgeCommand({}),
        (error) =>
          error instanceof ConnectError && error.code === Code.Unimplemented,
      );
      const initialized = await mcpRequest(f.sidecar.url, {
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: {
          protocolVersion: "2025-06-18",
          capabilities: {},
          clientInfo: { name: "tls-route-proof", version: "0.0.0" },
        },
      });
      assert.equal(initialized.status, 200);
      assert.ok(initialized.sessionId);
      assert.equal(
        (
          await mcpRequest(
            f.sidecar.url,
            { jsonrpc: "2.0", method: "notifications/initialized" },
            initialized.sessionId,
          )
        ).status,
        202,
      );
      assert.deepEqual(
        (
          await requestClient(f.sidecar.url, f.phoneToken).listPending({
            connectionId: f.connectionId,
          })
        ).requests,
        [],
      );

      const input = new StreamInput<SubscribeRequest>();
      const abort = new AbortController();
      const replies = iteratorOf(
        updateClient(f.sidecar.updateUrl ?? "", f.phoneToken).subscribe(input, {
          signal: abort.signal,
        }),
      );
      input.push(subscribeMessage(f.connectionId));
      const ready = await next(replies);
      assert.equal(ready.event.case, "ready");
      assert.equal(
        ready.event.value.resume,
        ResumeDisposition.FULL_SYNC_REQUIRED,
      );
      assert.equal((await next(replies)).event.case, "syncRequired");

      const snapshot = await updateClient(
        f.sidecar.updateUrl ?? "",
        f.phoneToken,
      ).sync({
        connectionId: f.connectionId,
        protocolVersion: 1,
        subscriptionCursor: ready.cursor,
      });
      assert.deepEqual(snapshot.requests, []);
      await delay(25);
      assert.equal(
        (
          await mcpRequest(
            f.sidecar.url,
            {
              jsonrpc: "2.0",
              id: 2,
              method: "tools/call",
              params: {
                name: "vault_request_ack",
                arguments: {
                  text: "New request",
                  idempotency_key: "tls-new",
                },
              },
            },
            initialized.sessionId,
          )
        ).status,
        200,
      );
      const changed = await next(replies, 500);
      assert.equal(changed.event.case, "requestChanged");
      const requestId = changed.event.value.request?.ref?.requestId ?? "";
      assert.notEqual(requestId, "");
      assert.equal(changed.event.value.revision, 1n);

      input.push(
        create(SubscribeRequestSchema, {
          connectionId: f.connectionId,
          message: {
            case: "heartbeat",
            value: create(ClientHeartbeatSchema, {
              sequence: 1n,
              appliedCursor: changed.cursor,
            }),
          },
        }),
      );
      assert.equal(
        (
          await mcpRequest(
            f.sidecar.url,
            {
              jsonrpc: "2.0",
              id: 3,
              method: "tools/call",
              params: {
                name: "vault_cancel_request",
                arguments: { request_id: requestId },
              },
            },
            initialized.sessionId,
          )
        ).status,
        200,
      );
      const cancelled = await next(replies, 500);
      assert.equal(cancelled.event.case, "requestChanged");
      assert.equal(
        cancelled.event.value.request?.state,
        RequestState.CANCELLED,
      );
      assert.equal(cancelled.event.value.revision, 2n);
      abort.abort();
    } finally {
      await f.close();
    }
  });

  it("freezes pages, catches up from a retained cursor, and rejects another role or connection", async () => {
    const f = await fixture({ cleartext: true });
    try {
      const first = f.store.create(ack("One", "page-one")).request;
      const second = f.store.create(ack("Two", "page-two")).request;
      const third = f.store.create(ack("Three", "page-three")).request;
      const client = updateClient(
        f.sidecar.updateUrl ?? "",
        f.phoneToken,
        false,
      );
      await assert.rejects(
        client.sync({
          connectionId: f.connectionId,
          protocolVersion: 2,
        }),
        updateFailure(UpdateError.PROTOCOL_UNSUPPORTED, 1),
      );
      await assert.rejects(
        client.sync({
          connectionId: f.connectionId,
          protocolVersion: 1,
          pageToken: "not-a-page-token",
        }),
        updateFailure(UpdateError.SNAPSHOT_INVALID),
      );
      const page1 = await client.sync({
        connectionId: f.connectionId,
        protocolVersion: 1,
        pageSize: 2,
      });
      const firstPageIds = page1.requests.map(
        (item) => item.request?.ref?.requestId,
      );
      assert.equal(firstPageIds.length, 2);
      const remaining = [first, second, third].find(
        (request) => !firstPageIds.includes(request.ref?.requestId),
      );
      assert.ok(remaining !== undefined);
      assert.ok(page1.nextPageToken);
      f.store.cancel(remaining.ref?.requestId ?? "");
      const page2 = await client.sync({
        connectionId: f.connectionId,
        protocolVersion: 1,
        pageSize: 2,
        pageToken: page1.nextPageToken,
      });
      assert.equal(page2.snapshotCursor, page1.snapshotCursor);
      assert.equal(
        page2.requests[0]?.request?.ref?.requestId,
        remaining.ref?.requestId,
      );
      assert.equal(page2.requests[0]?.request?.state, RequestState.PENDING);
      assert.equal(page2.nextPageToken, "");

      await assert.rejects(
        updateClient(f.sidecar.updateUrl ?? "", MCP_TOKEN, false).sync({
          connectionId: f.connectionId,
          protocolVersion: 1,
        }),
        code(Code.Unauthenticated),
      );
      const badInput = new StreamInput<SubscribeRequest>();
      const badStream = iteratorOf(
        updateClient(f.sidecar.updateUrl ?? "", MCP_TOKEN, false).subscribe(
          badInput,
        ),
      );
      badInput.push(subscribeMessage(f.connectionId));
      await assert.rejects(badStream.next(), code(Code.Unauthenticated));
      await assert.rejects(
        client.sync({
          connectionId: "a7e9c1b3-4d5f-4a6b-8c7d-9e0f1a2b3c4d",
          protocolVersion: 1,
        }),
        code(Code.NotFound),
      );
      const missing = await client.sync({
        connectionId: f.connectionId,
        protocolVersion: 1,
        knownNonterminal: [
          {
            ref: {
              connectionId: f.connectionId,
              requestId: "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
            },
            revision: 3n,
            state: RequestState.SUBMITTED,
          },
        ],
      });
      assert.equal(
        missing.removed[0]?.ref?.requestId,
        "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
      );
      assert.equal(missing.removed[0]?.revision, 4n);

      const input = new StreamInput<SubscribeRequest>();
      const abort = new AbortController();
      const stream = iteratorOf(
        client.subscribe(input, { signal: abort.signal }),
      );
      input.push(
        resumeMessage(
          f.connectionId,
          page1.snapshotCursor,
          page1.serverInstanceId,
        ),
      );
      const ready = await next(stream);
      assert.equal(ready.event.case, "ready");
      assert.equal(ready.event.value.resume, ResumeDisposition.REPLAYING);
      const replayed = await next(stream);
      assert.equal(replayed.event.case, "requestChanged");
      assert.equal(
        replayed.event.value.request?.ref?.requestId,
        remaining.ref?.requestId,
      );
      assert.equal(replayed.event.value.request?.state, RequestState.CANCELLED);
      assert.equal((await next(stream)).event.case, "replayComplete");
      abort.abort();
    } finally {
      await f.close();
    }
  });

  it("renews an active snapshot lease but expires an idle page token", async () => {
    const clock = { now: Date.UTC(2026, 8, 13, 12) };
    const f = await fixture({ cleartext: true, now: () => clock.now });
    try {
      for (let index = 0; index < 4; index += 1) {
        f.store.create(ack(`Page ${index}`, `lease-${index}`));
      }
      const client = updateClient(
        f.sidecar.updateUrl ?? "",
        f.phoneToken,
        false,
      );
      const first = await client.sync({
        connectionId: f.connectionId,
        protocolVersion: 1,
        pageSize: 1,
      });
      assert.ok(first.nextPageToken);

      clock.now += SNAPSHOT_TTL_MS - 1;
      const second = await client.sync({
        connectionId: f.connectionId,
        protocolVersion: 1,
        pageSize: 1,
        pageToken: first.nextPageToken,
      });
      clock.now += SNAPSHOT_TTL_MS - 1;
      const third = await client.sync({
        connectionId: f.connectionId,
        protocolVersion: 1,
        pageSize: 1,
        pageToken: second.nextPageToken,
      });
      assert.ok(
        third.nextPageToken,
        "active paging outlives the original lease",
      );

      clock.now += SNAPSHOT_TTL_MS;
      await assert.rejects(
        client.sync({
          connectionId: f.connectionId,
          protocolVersion: 1,
          pageSize: 1,
          pageToken: third.nextPageToken,
        }),
        updateFailure(UpdateError.SNAPSHOT_INVALID),
      );
    } finally {
      await f.close();
    }
  });

  it("publishes expiry and revocation, cancels a replaced stream, and isolates the replacement phone", async () => {
    const clock = { now: Date.UTC(2026, 8, 13, 12) };
    const f = await fixture({ cleartext: true, now: () => clock.now });
    try {
      const expiring = f.store.create({
        ...ack("Soon", "expires"),
        expiresInSeconds: 60,
      }).request;
      const firstInput = new StreamInput<SubscribeRequest>();
      const first = iteratorOf(
        updateClient(f.sidecar.updateUrl ?? "", f.phoneToken, false).subscribe(
          firstInput,
        ),
      );
      firstInput.push(subscribeMessage(f.connectionId));
      await next(first);
      await next(first);

      const replacementInput = new StreamInput<SubscribeRequest>();
      const replacement = iteratorOf(
        updateClient(f.sidecar.updateUrl ?? "", f.phoneToken, false).subscribe(
          replacementInput,
        ),
      );
      replacementInput.push(subscribeMessage(f.connectionId));
      await next(replacement);
      await assert.rejects(first.next(), code(Code.Canceled));
      await next(replacement);

      clock.now += 60_000;
      const expired = await next(replacement, 3000);
      assert.equal(expired.event.case, "requestChanged");
      assert.equal(
        expired.event.value.request?.ref?.requestId,
        expiring.ref?.requestId,
      );
      assert.equal(expired.event.value.request?.state, RequestState.EXPIRED);

      const pairingDb = openDatabase(f.databasePath);
      const issued = new PairingStore(pairingDb, {
        now: () => clock.now,
      }).issue(f.sidecar.url, 600);
      pairingDb.close();
      const newer = await pairingClient(f.sidecar.url, issued.token).pair({
        serverUrl: f.sidecar.url,
        deviceName: "Replacement",
      });
      const revoked = await next(replacement);
      assert.equal(revoked.event.case, "revoked");
      assert.equal((await replacement.next()).done, true);
      await assert.rejects(
        updateClient(f.sidecar.updateUrl ?? "", f.phoneToken, false).sync({
          connectionId: f.connectionId,
          protocolVersion: 1,
        }),
        code(Code.Unauthenticated),
      );
      const newerSnapshot = await updateClient(
        f.sidecar.updateUrl ?? "",
        newer.phoneToken,
        false,
      ).sync({ connectionId: newer.connectionId, protocolVersion: 1 });
      assert.deepEqual(newerSnapshot.requests, []);
    } finally {
      await f.close();
    }
  });

  it("requires a snapshot when bounded history cannot replay an old slow-consumer cursor", async () => {
    const f = await fixture({ cleartext: true });
    try {
      const client = updateClient(
        f.sidecar.updateUrl ?? "",
        f.phoneToken,
        false,
      );
      const initial = await client.sync({
        connectionId: f.connectionId,
        protocolVersion: 1,
      });
      for (let index = 0; index < 257; index += 1) {
        const request = f.store.create(
          ack(`Request ${index}`, `overflow-${index}`),
        ).request;
        f.store.cancel(request.ref?.requestId ?? "");
      }
      const input = new StreamInput<SubscribeRequest>();
      const abort = new AbortController();
      const stream = iteratorOf(
        client.subscribe(input, { signal: abort.signal }),
      );
      input.push(
        resumeMessage(
          f.connectionId,
          initial.snapshotCursor,
          initial.serverInstanceId,
        ),
      );
      const ready = await next(stream);
      assert.equal(ready.event.case, "ready");
      assert.equal(
        ready.event.value.resume,
        ResumeDisposition.FULL_SYNC_REQUIRED,
      );
      const required = await next(stream);
      assert.equal(required.event.case, "syncRequired");
      assert.equal(required.event.value.reason, SyncRequiredReason.HISTORY_GAP);
      abort.abort();
    } finally {
      await f.close();
    }
  });

  it("invalidates process-bound cursors after restart and recovers durable state through Sync", async () => {
    const first = await fixture({ cleartext: true });
    const initial = await updateClient(
      first.sidecar.updateUrl ?? "",
      first.phoneToken,
      false,
    ).sync({ connectionId: first.connectionId, protocolVersion: 1 });
    const pending = first.store.create(
      ack("Across restart", "restart"),
    ).request;
    await first.close();

    const restarted = await startSidecar(
      {
        host: "127.0.0.1",
        port: 0,
        updatePort: 0,
        mcpToken: MCP_TOKEN,
        phoneToken: PHONE_TOKEN,
        liveCommandTimeoutSeconds: 1,
        databasePath: first.databasePath,
        requestTtlSeconds: 86_400,
        pendingLimit: 100,
      },
      { log: () => undefined, updatePollMs: 10 },
    );
    try {
      const client = updateClient(
        restarted.updateUrl ?? "",
        first.phoneToken,
        false,
      );
      const input = new StreamInput<SubscribeRequest>();
      const abort = new AbortController();
      const stream = iteratorOf(
        client.subscribe(input, { signal: abort.signal }),
      );
      input.push(
        resumeMessage(
          first.connectionId,
          initial.snapshotCursor,
          initial.serverInstanceId,
        ),
      );
      const ready = await next(stream);
      assert.equal(ready.event.case, "ready");
      assert.equal(
        ready.event.value.resume,
        ResumeDisposition.FULL_SYNC_REQUIRED,
      );
      const required = await next(stream);
      assert.equal(required.event.case, "syncRequired");
      assert.equal(
        required.event.value.reason,
        SyncRequiredReason.CURSOR_INVALID,
      );
      const recovered = await client.sync({
        connectionId: first.connectionId,
        protocolVersion: 1,
        subscriptionCursor: ready.cursor,
      });
      assert.equal(
        recovered.requests[0]?.request?.ref?.requestId,
        pending.ref?.requestId,
      );
      abort.abort();
    } finally {
      await restarted.close();
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

function subscribeMessage(connectionId: string): SubscribeRequest {
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

function resumeMessage(
  connectionId: string,
  resumeCursor: string,
  serverInstanceId: string,
): SubscribeRequest {
  return create(SubscribeRequestSchema, {
    connectionId,
    message: {
      case: "subscribe",
      value: create(SubscribeSchema, {
        protocolVersion: 1,
        resumeCursor,
        serverInstanceId,
      }),
    },
  });
}

function authorization(token: string): Interceptor {
  return (nextHandler) => (request) => {
    request.header.set("Authorization", `Bearer ${token}`);
    return nextHandler(request);
  };
}

function pairingClient(baseUrl: string, token: string) {
  return createClient(
    PairingService,
    createConnectTransport({
      baseUrl,
      httpVersion: "1.1",
      nodeOptions: baseUrl.startsWith("https:") ? { ca: certificate } : {},
      interceptors: [authorization(token)],
    }),
  );
}

function requestClient(baseUrl: string, token: string) {
  return createClient(
    RequestService,
    createConnectTransport({
      baseUrl,
      httpVersion: "1.1",
      nodeOptions: baseUrl.startsWith("https:") ? { ca: certificate } : {},
      interceptors: [authorization(token)],
    }),
  );
}

function liveClient(baseUrl: string, token: string) {
  return createClient(
    LiveCommandService,
    createConnectTransport({
      baseUrl,
      httpVersion: "1.1",
      nodeOptions: baseUrl.startsWith("https:") ? { ca: certificate } : {},
      interceptors: [authorization(token)],
    }),
  );
}

function updateClient(baseUrl: string, token: string, tls = true) {
  return createClient(
    UpdateService,
    createGrpcTransport({
      baseUrl,
      nodeOptions: tls ? { ca: certificate } : {},
      readMaxBytes: 65_536,
      writeMaxBytes: 65_536,
      interceptors: [authorization(token)],
    }),
  );
}

async function health(baseUrl: string): Promise<number> {
  return new Promise((resolve, reject) => {
    const request = httpsGet(
      new URL("/healthz", baseUrl),
      { ca: certificate },
      (response) => {
        response.resume();
        resolve(response.statusCode ?? 0);
      },
    );
    request.on("error", reject);
  });
}

function mcpRequest(
  baseUrl: string,
  message: object,
  sessionId?: string,
): Promise<{ readonly status: number; readonly sessionId?: string }> {
  const body = JSON.stringify(message);
  return new Promise((resolve, reject) => {
    const request = httpsRequest(
      new URL("/mcp", baseUrl),
      {
        method: "POST",
        ca: certificate,
        headers: {
          Accept: "application/json, text/event-stream",
          Authorization: `Bearer ${MCP_TOKEN}`,
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(body),
          ...(sessionId === undefined ? {} : { "Mcp-Session-Id": sessionId }),
        },
      },
      (response) => {
        response.resume();
        response.on("end", () => {
          const returnedSession = response.headers["mcp-session-id"];
          resolve({
            status: response.statusCode ?? 0,
            ...(typeof returnedSession === "string"
              ? { sessionId: returnedSession }
              : {}),
          });
        });
      },
    );
    request.on("error", reject);
    request.end(body);
  });
}

function code(expected: Code): (error: unknown) => boolean {
  return (error) => error instanceof ConnectError && error.code === expected;
}

function updateFailure(
  expected: UpdateError,
  supportedProtocolVersion = 0,
): (error: unknown) => boolean {
  return (error) => {
    if (
      !(error instanceof ConnectError) ||
      error.code !== Code.FailedPrecondition
    )
      return false;
    const [detail] = error.findDetails(UpdateErrorDetailSchema);
    return (
      detail?.error === expected &&
      detail.supportedProtocolVersion === supportedProtocolVersion
    );
  };
}

async function next(
  iterator: AsyncIterator<SubscribeResponse>,
  timeoutMs = 2000,
): Promise<SubscribeResponse> {
  const result = await Promise.race([
    iterator.next(),
    new Promise<never>((_, reject) =>
      setTimeout(
        () => reject(new Error("timed out waiting for update event")),
        timeoutMs,
      ).unref(),
    ),
  ]);
  assert.equal(result.done, false);
  return result.value;
}

class StreamInput<T> implements AsyncIterable<T> {
  readonly #queued: T[] = [];
  readonly #waiting: ((value: IteratorResult<T>) => void)[] = [];
  #closed = false;

  push(value: T): void {
    const waiter = this.#waiting.shift();
    if (waiter === undefined) this.#queued.push(value);
    else waiter({ done: false, value });
  }

  [Symbol.asyncIterator](): AsyncIterator<T> {
    return {
      next: () => {
        const value = this.#queued.shift();
        if (value !== undefined) return Promise.resolve({ done: false, value });
        if (this.#closed)
          return Promise.resolve({ done: true, value: undefined });
        return new Promise((resolve) => this.#waiting.push(resolve));
      },
      return: () => {
        this.#closed = true;
        for (const waiter of this.#waiting.splice(0))
          waiter({ done: true, value: undefined });
        return Promise.resolve({ done: true, value: undefined });
      },
      throw: (error?: unknown) => {
        this.#closed = true;
        for (const waiter of this.#waiting.splice(0))
          waiter({ done: true, value: undefined });
        return Promise.reject(
          error instanceof Error ? error : new Error("stream input failed"),
        );
      },
    };
  }
}

function iteratorOf<T>(stream: AsyncIterable<T>): AsyncIterator<T> {
  return stream[Symbol.asyncIterator]();
}
