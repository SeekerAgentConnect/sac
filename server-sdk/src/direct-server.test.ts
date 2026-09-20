import assert from "node:assert/strict";
import { setTimeout as delay } from "node:timers/promises";
import { describe, it } from "node:test";

import { create } from "@bufbuild/protobuf";
import { createClient, type Interceptor } from "@connectrpc/connect";
import { createConnectTransport } from "@connectrpc/connect-node";

import {
  AckActionSchema,
  ActionSchema,
  PairingService,
  RequestService,
  RequestState,
} from "./protocol.ts";
import { openDirectServer, privateRequest, startPhoneApi } from "./index.ts";
import { temporaryDatabasePath } from "./testing/process.ts";

describe("public direct-server API", () => {
  it("pairs, creates, observes and preserves one result without MCP or feed infrastructure", async () => {
    const databasePath = temporaryDatabasePath();
    let publicOrigin = "http://127.0.0.1:1";
    const open = () =>
      openDirectServer({
        databasePath,
        publicOrigin: () => publicOrigin,
        requestTtlSeconds: 86_400,
        pendingLimit: 100,
        pairingTokenTtlSeconds: 600,
        liveCommandTimeoutSeconds: 30,
        log: () => undefined,
      });

    const direct = open();
    const serverId = direct.serverId;
    const phoneApi = await startPhoneApi(direct, {
      host: "127.0.0.1",
      port: 0,
    });
    publicOrigin = phoneApi.url;
    const issued = direct.pairing.issue();
    assert.equal(issued.serverId, serverId);
    assert.match(issued.uri, /^seekervault:\/\/pair\?/);

    const paired = await client(
      PairingService,
      phoneApi.url,
      issued.token,
    ).pair({ serverUrl: phoneApi.url, deviceName: "SDK test phone" });
    const phone = client(RequestService, phoneApi.url, paired.phoneToken);
    const action = create(ActionSchema, {
      kind: {
        case: "ack",
        value: create(AckActionSchema, { text: "Review once" }),
      },
    });
    const created = direct.requests.createRequest(
      privateRequest(action, "SDK lifecycle", "sdk-public-api-1", 300),
    );
    const requestId = created.request.ref?.requestId ?? "";
    const seen: RequestState[] = [];
    const stop = direct.requests.observe(requestId, (request) => {
      seen.push(request.state);
    });
    await settle();

    const pending = await phone.listPending({
      connectionId: paired.connectionId,
    });
    assert.deepEqual(
      pending.requests.map((request) => request.ref?.requestId),
      [requestId],
    );
    const result = {
      ref: { connectionId: paired.connectionId, requestId },
      result: { case: "acknowledgement" as const, value: {} },
    };
    const first = await phone.submitResult(result);
    const duplicate = await phone.submitResult(result);
    assert.equal(first.request?.state, RequestState.COMPLETED);
    assert.deepEqual(duplicate.request, first.request);
    await settle();
    assert.deepEqual(seen, [RequestState.PENDING, RequestState.COMPLETED]);

    direct.beginShutdown();
    await phoneApi.close();
    stop();
    await direct.close();
    await direct.close();

    const restarted = open();
    assert.equal(restarted.serverId, serverId);
    assert.equal(restarted.pairing.active()?.connectionId, paired.connectionId);
    assert.equal(
      restarted.requests.get(requestId).state,
      RequestState.COMPLETED,
    );
    const retried = restarted.requests.createRequest(
      privateRequest(action, "SDK lifecycle", "sdk-public-api-1", 300),
    );
    assert.equal(retried.request.ref?.requestId, requestId);
    await restarted.close();
  });
});

function client<T extends typeof PairingService | typeof RequestService>(
  service: T,
  baseUrl: string,
  token: string,
) {
  const authorization: Interceptor = (next) => (request) => {
    request.header.set("Authorization", `Bearer ${token}`);
    return next(request);
  };
  return createClient(
    service,
    createConnectTransport({
      baseUrl,
      httpVersion: "1.1",
      interceptors: [authorization],
    }),
  );
}

async function settle(): Promise<void> {
  await delay(0);
  await delay(0);
}
