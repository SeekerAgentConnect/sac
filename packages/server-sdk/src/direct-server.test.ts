import assert from "node:assert/strict";
import { setTimeout as delay } from "node:timers/promises";
import { describe, it } from "node:test";

import { create } from "@bufbuild/protobuf";
import { createClient, type Interceptor } from "@connectrpc/connect";
import { createConnectTransport } from "@connectrpc/connect-node";

import {
  AckActionSchema,
  ActionSchema,
  Network,
  PairingService,
  RequestService,
  RequestState,
  SolanaNetwork,
  type ServerManifest,
} from "./protocol.ts";
import {
  openDirectServer,
  privateRequest,
  startPhoneApi,
  type RelayOutcome,
} from "./index.ts";
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

/**
 * The networks a direct server declares (SEE-174), through openDirectServer rather than the
 * manifest module: the option is checked before anything is opened, the manifest carries it in
 * canonical order, and a wallet the phone publishes on another network is still stored — an older
 * phone keeps working — while the operator is told why an updated one will not sign for it.
 */
describe("supported networks", () => {
  const declared = (manifest: ServerManifest) =>
    manifest.reference.case === "direct"
      ? manifest.reference.value.supportedNetworks
      : undefined;
  const WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW";
  const options = (
    databasePath: string,
    supportedNetworks?: readonly SolanaNetwork[],
    log: (message: string) => void = () => undefined,
  ) => ({
    databasePath,
    publicOrigin: "http://127.0.0.1:1",
    requestTtlSeconds: 86_400,
    pendingLimit: 100,
    pairingTokenTtlSeconds: 600,
    liveCommandTimeoutSeconds: 30,
    log,
    ...(supportedNetworks === undefined ? {} : { supportedNetworks }),
  });

  it("declares nothing unless the host does, and never defaults to Mainnet", async () => {
    const direct = openDirectServer(options(temporaryDatabasePath()));
    assert.deepEqual(declared(direct.manifest), []);
    await direct.close();
  });

  it("refuses a list the contract refuses before opening anything", () => {
    for (const supportedNetworks of [
      [SolanaNetwork.MAINNET, SolanaNetwork.MAINNET],
      [SolanaNetwork.UNSPECIFIED],
      [7 as SolanaNetwork],
    ]) {
      assert.throws(() =>
        openDirectServer(options(temporaryDatabasePath(), supportedNetworks)),
      );
    }
  });

  it("publishes them in canonical order and moves the revision when they change", async () => {
    const databasePath = temporaryDatabasePath();
    const revision = async (supportedNetworks: readonly SolanaNetwork[]) => {
      const direct = openDirectServer(options(databasePath, supportedNetworks));
      const manifest = direct.manifest;
      await direct.close();
      return [manifest.settingsRevision, declared(manifest)] as const;
    };

    assert.deepEqual(await revision([SolanaNetwork.MAINNET]), [
      1n,
      [SolanaNetwork.MAINNET],
    ]);
    assert.deepEqual(await revision([SolanaNetwork.MAINNET]), [
      1n,
      [SolanaNetwork.MAINNET],
    ]);
    assert.deepEqual(
      await revision([SolanaNetwork.DEVNET, SolanaNetwork.MAINNET]),
      [2n, [SolanaNetwork.MAINNET, SolanaNetwork.DEVNET]],
    );
  });

  it("stores a binding on an undeclared network and says so", async () => {
    const lines: string[] = [];
    const direct = openDirectServer(
      options(temporaryDatabasePath(), [SolanaNetwork.MAINNET], (line) =>
        lines.push(line),
      ),
    );
    const phoneApi = await startPhoneApi(direct, {
      host: "127.0.0.1",
      port: 0,
    });
    const issued = direct.pairing.issue();
    const paired = await client(
      PairingService,
      phoneApi.url,
      issued.token,
    ).pair({ serverUrl: issued.serverUrl, deviceName: "SDK test phone" });
    const phone = client(RequestService, phoneApi.url, paired.phoneToken);

    const devnet = await phone.publishWallet({
      connectionId: paired.connectionId,
      binding: { wallet: WALLET, network: Network.DEVNET },
    });
    assert.equal(devnet.binding?.network, Network.DEVNET);
    assert.ok(
      lines.some((line) =>
        line.includes("devnet is not a network this server declares (mainnet)"),
      ),
    );

    lines.length = 0;
    await phone.publishWallet({
      connectionId: paired.connectionId,
      binding: { wallet: WALLET, network: Network.MAINNET },
    });
    assert.ok(!lines.some((line) => line.includes("not a network")));

    await phoneApi.close();
    await direct.close();
  });
});

/**
 * The relay, end to end through the SDK's own surfaces (SEE-144): the phone authorizes it over the
 * authenticated direct connection, the server stores the handle against that connection, and a
 * committed update reaches the gateway naming it.
 *
 * The gateway itself is a fake sender rather than a real one — what is pinned here is the SDK's
 * half: that the handle travels over its own RPC, is stored where the dispatcher reads it, and is
 * gone when the connection ends.
 */
describe("the gateway push relay", () => {
  const RELAY = {
    relayUrl: "https://feeds.example.com",
    serverId: "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
    credential: "a-scoped-relay-credential",
  };

  it("refuses both ways of sending the same wake-up", () => {
    assert.throws(
      () =>
        openDirectServer({
          databasePath: temporaryDatabasePath(),
          publicOrigin: "http://127.0.0.1:1",
          requestTtlSeconds: 86_400,
          pendingLimit: 100,
          pairingTokenTtlSeconds: 600,
          liveCommandTimeoutSeconds: 30,
          log: () => undefined,
          invalidationSender: { send: () => Promise.resolve() },
          relay: {
            ...RELAY,
            sender: { send: () => Promise.resolve<RelayOutcome>("accepted") },
          },
        }),
      /woken twice/,
    );
  });

  it("carries a handle from the phone to the gateway, and stops at revocation", async () => {
    const sent: { handle: string; hint: string }[] = [];
    let publicOrigin = "http://127.0.0.1:1";
    const direct = openDirectServer({
      databasePath: temporaryDatabasePath(),
      publicOrigin: () => publicOrigin,
      requestTtlSeconds: 86_400,
      pendingLimit: 100,
      pairingTokenTtlSeconds: 600,
      liveCommandTimeoutSeconds: 30,
      log: () => undefined,
      // The transport is replaced; the configuration is not, because what the phone is told is
      // what an operator configured and that is half of what this test is about.
      relay: {
        ...RELAY,
        sender: {
          send: (invalidation) => {
            sent.push({ ...invalidation });
            return Promise.resolve<RelayOutcome>("accepted");
          },
        },
      },
    });
    const phoneApi = await startPhoneApi(direct, {
      host: "127.0.0.1",
      port: 0,
    });
    publicOrigin = phoneApi.url;
    const issued = direct.pairing.issue();
    const paired = await client(
      PairingService,
      phoneApi.url,
      issued.token,
    ).pair({
      serverUrl: phoneApi.url,
      deviceName: "SDK relay phone",
    });
    const pairing = client(PairingService, phoneApi.url, paired.phoneToken);

    // The phone asks where this server's wake-ups come from, and is told the gateway and the
    // identity to authorize. It registers only with the relay it is configured to trust, so this
    // is an advertisement rather than an instruction.
    const capabilities = await pairing.getConnectionCapabilities({
      connectionId: paired.connectionId,
    });
    assert.equal(capabilities.relay?.relayUrl, RELAY.relayUrl);
    assert.equal(capabilities.relay?.serverId, RELAY.serverId);
    assert.equal(capabilities.relay?.protocolVersion, 1);

    // It authorizes the binding at that gateway and hands the handle over here, through an RPC of
    // its own: a handle is not an FCM target and is never stored as one.
    await pairing.setRelayHandle({
      connectionId: paired.connectionId,
      update: { case: "handle", value: "a-handle-from-the-gateway" },
    });

    const action = create(ActionSchema, {
      kind: {
        case: "ack",
        value: create(AckActionSchema, { text: "Review once" }),
      },
    });
    direct.requests.createRequest(
      privateRequest(action, "SDK relay", "sdk-relay-1", 300),
    );
    await settle();
    await settle();
    assert.deepEqual(sent, [
      { handle: "a-handle-from-the-gateway", hint: "created" },
    ]);

    // Revoking the connection clears the handle in the statement that ends it, so a gateway cannot
    // keep being asked to wake a phone about a server it is no longer paired with. The credential
    // stops working in the same moment, which is why this is the last thing the phone can do.
    direct.pairing.revoke(paired.connectionId);
    await assert.rejects(
      pairing.setRelayHandle({
        connectionId: paired.connectionId,
        update: { case: "handle", value: "another-handle" },
      }),
    );
    assert.equal(sent.length, 1);

    await phoneApi.close();
    await direct.close();
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
