import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { setTimeout as delay } from "node:timers/promises";
import { describe, it } from "node:test";

import { create } from "@bufbuild/protobuf";
import {
  Code,
  ConnectError,
  createClient,
  type Interceptor,
} from "@connectrpc/connect";
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
import {
  ProviderUnavailable,
  UnsupportedPreparation,
  type ConfirmationProvider,
  type TransferProvider,
} from "./providers.ts";
import { REOPENED_DETAIL } from "./storage/request-store.ts";
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

/**
 * not_sent (docs/protocol.md#reopening): the one result that moves a request backward, UNKNOWN to
 * PENDING. The phone's word isn't enough, so the sidecar reads the chain itself through its
 * ConfirmationProvider, and refuses — moving nothing — until the approved blockhash has expired.
 */
describe("reopening a transfer that was never sent", () => {
  const WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW";
  const RECIPIENT = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh";
  const VALID_THROUGH = 100n;

  /** A chain the test steers: its height, its network, and whether it answers. */
  const chain = {
    height: 50n,
    network: Network.DEVNET,
    unavailable: false,
    built: 0,
  };
  const transfers: TransferProvider = {
    checkAsset: () => Promise.resolve(),
    buildTransfer: () => {
      chain.built += 1;
      const transaction = Uint8Array.of(9, chain.built);
      return Promise.resolve({
        transaction,
        contentHash: new Uint8Array(
          createHash("sha256").update(transaction).digest(),
        ),
        lastValidBlockHeight: VALID_THROUGH + BigInt(chain.built),
        estimatedExpiryMs: Date.now() + 60_000,
        feeLamports: 5000n,
        rentLamports: 0n,
      });
    },
  };
  const confirmations: ConfirmationProvider = {
    endpointUrl: "http://rpc.test/?api-key=secret",
    assertNetwork: (network) => {
      if (chain.unavailable) {
        return Promise.reject(new ProviderUnavailable("rpc.test timed out"));
      }
      return network === chain.network
        ? Promise.resolve()
        : Promise.reject(
            new UnsupportedPreparation("the endpoint serves another cluster"),
          );
    },
    signatureStatus: () => Promise.resolve(undefined),
    confirmedTransaction: () => Promise.resolve(undefined),
    blockHeight: () => Promise.resolve(chain.height),
    matchesApprovedTransaction: () => false,
  };

  async function open(confirmationProvider?: ConfirmationProvider) {
    let publicOrigin = "http://127.0.0.1:1";
    const direct = openDirectServer({
      databasePath: temporaryDatabasePath(),
      publicOrigin: () => publicOrigin,
      supportedNetworks: [SolanaNetwork.DEVNET],
      requestTtlSeconds: 86_400,
      pendingLimit: 100,
      pairingTokenTtlSeconds: 600,
      liveCommandTimeoutSeconds: 30,
      log: () => undefined,
      transferProvider: transfers,
      ...(confirmationProvider === undefined ? {} : { confirmationProvider }),
    });
    const phoneApi = await startPhoneApi(direct, {
      host: "127.0.0.1",
      port: 0,
    });
    publicOrigin = phoneApi.url;
    const paired = await client(
      PairingService,
      phoneApi.url,
      direct.pairing.issue().token,
    ).pair({ serverUrl: phoneApi.url, deviceName: "Reopening test phone" });
    const phone = client(RequestService, phoneApi.url, paired.phoneToken);
    await phone.publishWallet({
      connectionId: paired.connectionId,
      binding: { wallet: WALLET, network: Network.DEVNET },
    });
    return {
      direct,
      phone,
      connectionId: paired.connectionId,
      close: async () => {
        direct.beginShutdown();
        await phoneApi.close();
        await direct.close();
      },
    };
  }

  type Opened = Awaited<ReturnType<typeof open>>;

  async function approve(
    s: Opened,
    ref: { connectionId: string; requestId: string },
  ) {
    const { prepared } = await s.phone.prepareRequest({ ref });
    assert.ok(prepared !== undefined);
    const { request } = await s.phone.submitResult({
      ref,
      result: {
        case: "approval",
        value: {
          preparedVersion: prepared.version,
          contentHash: prepared.contentHash,
        },
      },
    });
    assert.equal(request?.state, RequestState.PROCESSING);
    return prepared;
  }

  /** A transfer approved and lost: the wallet never answered, so it is UNKNOWN. */
  async function lost(s: Opened, key: string) {
    const action = create(ActionSchema, {
      kind: {
        case: "transfer",
        value: {
          wallet: WALLET,
          network: Network.DEVNET,
          recipient: RECIPIENT,
          asset: { kind: { case: "nativeSol", value: {} } },
          amount: "1",
        },
      },
    });
    const { request } = s.direct.requests.createRequest(
      privateRequest(action, "", key, 300),
    );
    const ref = {
      connectionId: s.connectionId,
      requestId: request.ref?.requestId ?? "",
    };
    await approve(s, ref);
    await s.phone.submitResult({
      ref,
      result: { case: "unknownOutcome", value: { detail: "No answer." } },
    });
    return ref;
  }

  function notSent(
    s: Opened,
    ref: { connectionId: string; requestId: string },
  ) {
    return s.phone.submitResult({
      ref,
      result: {
        case: "notSent",
        value: {
          detail: "Not in the wallet's history; the blockhash expired.",
        },
      },
    });
  }

  async function refusal(
    attempt: Promise<unknown>,
  ): Promise<ConnectError | undefined> {
    return attempt.then(
      () => undefined,
      (caught: unknown) => ConnectError.from(caught),
    );
  }

  it("reopens only once the chain is past the approved blockhash, and the owner approves a new version", async () => {
    chain.height = 50n;
    const s = await open(confirmations);
    try {
      const ref = await lost(s, "reopen-1");
      const seen: RequestState[] = [];
      const stop = s.direct.requests.observe(ref.requestId, (request) => {
        seen.push(request.state);
      });

      // The approved version can still land: retryable, and nothing moves.
      const early = await refusal(notSent(s, ref));
      assert.equal(early?.code, Code.Unavailable);
      assert.match(early?.message ?? "", /can still land/);
      assert.equal(
        s.direct.requests.get(ref.requestId).state,
        RequestState.UNKNOWN,
      );

      chain.height = 1000n;
      const { request } = await notSent(s, ref);
      assert.equal(request?.state, RequestState.PENDING);
      assert.equal(request?.outcome?.detail, REOPENED_DETAIL);
      assert.equal(request?.outcome?.approval, undefined);
      assert.equal(request?.outcome?.confirmation, undefined);
      // A retry is the same answer, not a second move.
      assert.deepEqual((await notSent(s, ref)).request, request);
      const listed = await s.phone.listPending({
        connectionId: s.connectionId,
      });
      assert.ok(
        listed.requests.some(
          (pending) => pending.ref?.requestId === ref.requestId,
        ),
      );
      await settle();
      assert.equal(seen.at(-1), RequestState.PENDING);
      stop();

      const again = await approve(s, ref);
      assert.ok(again.version > 1, "a fresh preparation, never the dead one");
    } finally {
      await s.close();
    }
  });

  it("refuses, retryably, while the endpoint is down or serves another network", async () => {
    chain.height = 1000n;
    const s = await open(confirmations);
    try {
      const ref = await lost(s, "reopen-2");
      chain.unavailable = true;
      const down = await refusal(notSent(s, ref));
      assert.equal(down?.code, Code.Unavailable);
      chain.unavailable = false;
      chain.network = Network.MAINNET;
      const elsewhere = await refusal(notSent(s, ref));
      assert.equal(elsewhere?.code, Code.Unavailable);
      assert.match(elsewhere?.message ?? "", /another cluster/);
      chain.network = Network.DEVNET;
      assert.equal(
        s.direct.requests.get(ref.requestId).state,
        RequestState.UNKNOWN,
      );
    } finally {
      chain.unavailable = false;
      chain.network = Network.DEVNET;
      await s.close();
    }
  });

  it("refuses a request that isn't UNKNOWN as the request having moved on", async () => {
    const s = await open(confirmations);
    try {
      const ref = await lost(s, "reopen-3");
      chain.height = 1000n;
      await notSent(s, ref);
      await approve(s, ref);
      const moved = await refusal(notSent(s, ref));
      assert.equal(moved?.code, Code.FailedPrecondition);
    } finally {
      await s.close();
    }
  });

  it("answers Unimplemented on a sidecar with no chain to check", async () => {
    const s = await open();
    try {
      // Without a ConfirmationProvider nothing reaches UNKNOWN through the chain, but the phone can
      // still lose the wallet after an approval.
      const ref = await lost(s, "reopen-4");
      const error = await refusal(notSent(s, ref));
      assert.equal(error?.code, Code.Unimplemented);
      assert.equal(
        s.direct.requests.get(ref.requestId).state,
        RequestState.UNKNOWN,
      );
    } finally {
      await s.close();
    }
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
