/**
 * Live updates and the gateway relay on the staking server (SEE-150).
 *
 * The regression these pin is one of omission. The phone opens its update stream only on an origin
 * the server advertised at pairing, and this server advertised none: it served the update routes
 * but never told the SDK where they were, so Pair and GetConnectionCapabilities came back with no
 * update capability, the phone never subscribed, and a staking request the agent had just asked
 * for sat unseen until the owner pulled to refresh. Nothing failed — which is why it took a person
 * looking at a phone to notice.
 *
 * So these tests act as the phone does, with its real transports: Connect for the unary calls,
 * genuine gRPC over cleartext HTTP/2 for Subscribe, and the MCP SDK for the agent. The chain is a
 * stub that serves the real accounts recorded in `testing/accounts.ts`; no test reaches a network,
 * and the gateway is a loopback listener that records what it was asked.
 */
import assert from "node:assert/strict";
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { after, describe, it } from "node:test";
import { setTimeout as delay } from "node:timers/promises";

import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { Network, RequestState } from "@seekeragentconnect/server-sdk/protocol";

import { CANCEL_UNSTAKE_TOOL } from "./requests/tools.ts";
import { startStakingServer } from "./server.ts";
import { MAINNET_GENESIS_HASH } from "./skr/chain.ts";
import { STAKING_PROGRAM_ID, stakingAddresses } from "./skr/program.ts";
import {
  GUARDIAN_POOL_ACCOUNT,
  STAKE_CONFIG_ACCOUNT,
  USER_STAKE_ACCOUNT,
  USER_STAKE_ADDRESS,
  USER_STAKE_OWNER,
} from "./testing/accounts.ts";
import {
  callTool,
  connectAgent,
  pairPhone,
  pairingClient,
  requestClient,
  subscribe,
  updateClient,
  waitFor,
} from "./testing/clients.ts";
import {
  configFor,
  removeTemporaryDirectories,
  stubCluster,
  type StubAccount,
  type StubCluster,
} from "./testing/cluster.ts";

after(removeTemporaryDirectories);

/** What `configFor` configures the agent's token as. */
const MCP_TOKEN = "t".repeat(64);

/**
 * A mainnet cluster holding one real staker's position: the configuration, the official guardian's
 * pool, and a stake account with shares *and* an unstake in progress. That staker is the owner in
 * these tests because a pending unstake is what `request_cancel_unstake` needs to be possible, so
 * the agent's request passes the same chain check it would in production.
 */
function stakingCluster(): Promise<StubCluster> {
  const addresses = stakingAddresses();
  const program = STAKING_PROGRAM_ID.toBase58();
  const accounts = new Map<string, StubAccount>([
    [
      addresses.stakeConfig.toBase58(),
      { owner: program, data: STAKE_CONFIG_ACCOUNT },
    ],
    [
      addresses.guardianPool.toBase58(),
      { owner: program, data: GUARDIAN_POOL_ACCOUNT },
    ],
    [USER_STAKE_ADDRESS, { owner: program, data: USER_STAKE_ACCOUNT }],
  ]);
  return stubCluster(MAINNET_GENESIS_HASH, accounts);
}

/** A port nothing is listening on, so a public origin can name it before the server runs. */
async function freePort(): Promise<number> {
  const probe = createServer();
  await new Promise<void>((resolve) => probe.listen(0, "127.0.0.1", resolve));
  const { port } = probe.address() as AddressInfo;
  await new Promise<void>((resolve) => probe.close(() => resolve()));
  return port;
}

/** The request id a staking tool answered with. Fails the test if the tool failed. */
function requestIdOf(result: CallToolResult): string {
  assert.notEqual(result.isError, true, JSON.stringify(result.content));
  const view = result.structuredContent as { request_id?: unknown };
  assert.equal(typeof view.request_id, "string");
  return view.request_id as string;
}

describe("live updates on the staking server", () => {
  // The deployed shape: App Platform terminates TLS and speaks HTTP/2 to an h2c listener, so the
  // phone must be told to stream from the public HTTPS origin — the only one the proxy answers on —
  // and never from the address the process happens to be bound to.
  it("advertises the public HTTPS origin on an h2c main listener", async () => {
    const cluster = await stubCluster(MAINNET_GENESIS_HASH);
    const port = await freePort();
    const publicUrl = "https://staking.example.com";
    const server = await startStakingServer(
      configFor(cluster.url, { port, publicUrl, h2c: true }),
      { log: () => undefined },
    );
    // Where the proxy would deliver to: the listener itself, over HTTP/2 because h2c takes nothing
    // else.
    const listener = `http://127.0.0.1:${port}`;
    try {
      assert.equal(server.updateUrl, publicUrl);
      const phone = await pairPhone(
        listener,
        server.direct.pairing.issue(),
        "2",
      );
      assert.equal(phone.updateUrl, publicUrl);

      const capabilities = await pairingClient(
        listener,
        phone.phoneToken,
        "2",
      ).getConnectionCapabilities({ connectionId: phone.connectionId });
      assert.equal(capabilities.updates?.protocolVersion, 1);
      assert.equal(capabilities.updates?.grpcUrl, publicUrl);

      // And the stream it names really opens on this listener.
      const stream = subscribe(listener, phone.phoneToken, phone.connectionId);
      try {
        assert.equal((await stream.next()).event.case, "ready");
      } finally {
        stream.close();
      }
    } finally {
      await server.close();
      await cluster.close();
    }
  });

  // The honest answer for a listener that cannot carry gRPC: no capability, rather than one naming
  // a stream that would never open. The phone then has its manual refresh, and that still works.
  it("advertises nothing on a plain HTTP/1.1 listener, and manual refresh still works", async () => {
    const cluster = await stubCluster(MAINNET_GENESIS_HASH);
    const server = await startStakingServer(configFor(cluster.url), {
      log: () => undefined,
    });
    try {
      assert.equal(server.updateUrl, undefined);
      const phone = await pairPhone(server.url, server.direct.pairing.issue());
      assert.equal(phone.updateUrl, undefined);
      const pending = await requestClient(
        server.url,
        phone.phoneToken,
      ).listPending({ connectionId: phone.connectionId });
      assert.deepEqual(pending.requests, []);
    } finally {
      await server.close();
      await cluster.close();
    }
  });

  // The loopback shape a developer pairs an emulator with over `adb reverse`: HTTP/1.1 on the main
  // port for everything unary, and the stream on a port of its own. It is also the whole of what
  // the ticket asks for — the agent asks, and the open app hears about it without a refresh.
  it("streams an agent's staking request, and a later status change, to an open Subscribe", async () => {
    const cluster = await stakingCluster();
    const server = await startStakingServer(
      // Port 0 for the update listener too; the advertised origin carries the one it was given.
      configFor(cluster.url, { updatePort: 0 }),
      { log: () => undefined },
    );
    const agent = await connectAgent(server.url, MCP_TOKEN);
    try {
      const updateUrl = server.updateUrl ?? "";
      assert.match(updateUrl, /^http:\/\/127\.0\.0\.1:\d+$/);
      assert.notEqual(updateUrl, server.url);

      const phone = await pairPhone(server.url, server.direct.pairing.issue());
      assert.equal(phone.updateUrl, updateUrl);
      const requests = requestClient(server.url, phone.phoneToken);
      await requests.publishWallet({
        connectionId: phone.connectionId,
        binding: { wallet: USER_STAKE_OWNER, network: Network.MAINNET },
      });

      // The foreground loop: subscribe, be told to sync because there is no cursor yet, sync, and
      // then sit on the stream.
      const stream = subscribe(updateUrl, phone.phoneToken, phone.connectionId);
      try {
        const ready = await stream.next();
        assert.equal(ready.event.case, "ready");
        assert.equal((await stream.next()).event.case, "syncRequired");
        const snapshot = await updateClient(updateUrl, phone.phoneToken).sync({
          connectionId: phone.connectionId,
          protocolVersion: 1,
          subscriptionCursor: ready.cursor,
        });
        assert.deepEqual(snapshot.requests, []);

        // Through the real tool, so the request passes the same wallet, network and chain checks
        // it would in production before it is stored.
        const requestId = requestIdOf(
          await callTool(agent, CANCEL_UNSTAKE_TOOL, {
            idempotency_key: "see-150-live-1",
          }),
        );

        const created = await stream.next();
        assert.equal(created.event.case, "requestChanged");
        assert.equal(created.event.value.request?.ref?.requestId, requestId);
        assert.equal(created.event.value.request?.state, RequestState.PENDING);
        assert.equal(created.event.value.revision, 1n);

        // Manual refresh is the same data by another road, and it still agrees.
        const pending = await requests.listPending({
          connectionId: phone.connectionId,
        });
        assert.deepEqual(
          pending.requests.map((request) => request.ref?.requestId),
          [requestId],
        );

        // A change the phone did not make — the path an expiry or a server-side cancellation
        // takes — reaches it on the same stream, one revision later.
        server.direct.requests.cancel(requestId);
        const cancelled = await stream.next();
        assert.equal(cancelled.event.case, "requestChanged");
        assert.equal(cancelled.event.value.request?.ref?.requestId, requestId);
        assert.equal(
          cancelled.event.value.request?.state,
          RequestState.CANCELLED,
        );
        assert.equal(cancelled.event.value.revision, 2n);
      } finally {
        stream.close();
      }
    } finally {
      await agent.close();
      await server.close();
      await cluster.close();
    }
  });
});

/**
 * The gateway relay, from configuration to the gateway's front door: the server advertises the
 * relay the operator configured, the phone hands over the handle it got from that gateway, and a
 * request the agent creates becomes one authenticated POST naming that handle.
 *
 * The gateway is a real HTTP listener on loopback — plain HTTP is allowed there and only there —
 * so what is exercised is the SDK's own sender, not a stand-in for it.
 */
describe("the gateway push relay on the staking server", () => {
  const SERVER_ID = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
  const CREDENTIAL = "a-scoped-relay-credential-for-staking";
  const HANDLE = "a-handle-from-the-gateway";

  interface Notified {
    readonly path: string;
    readonly authorization: string | undefined;
    readonly body: unknown;
  }

  async function fakeGateway(): Promise<{
    url: string;
    notified: Notified[];
    close(): Promise<void>;
  }> {
    const notified: Notified[] = [];
    const gateway = createServer((request, response) => {
      let body = "";
      request.on("data", (chunk: Buffer) => (body += chunk.toString()));
      request.on("end", () => {
        notified.push({
          path: request.url ?? "",
          authorization: request.headers.authorization,
          body: JSON.parse(body) as unknown,
        });
        response.writeHead(202).end();
      });
    });
    await new Promise<void>((resolve) =>
      gateway.listen(0, "127.0.0.1", resolve),
    );
    const { port } = gateway.address() as AddressInfo;
    return {
      url: `http://127.0.0.1:${port}`,
      notified,
      close: () =>
        new Promise<void>((resolve) => gateway.close(() => resolve())),
    };
  }

  it("wakes the phone through the gateway exactly once for a new request", async () => {
    const gateway = await fakeGateway();
    const cluster = await stakingCluster();
    const logs: string[] = [];
    const server = await startStakingServer(
      configFor(cluster.url, {
        relay: {
          relayUrl: gateway.url,
          serverId: SERVER_ID,
          credential: CREDENTIAL,
        },
      }),
      { log: (line) => logs.push(line) },
    );
    const agent = await connectAgent(server.url, MCP_TOKEN);
    try {
      const phone = await pairPhone(server.url, server.direct.pairing.issue());
      const pairing = pairingClient(server.url, phone.phoneToken);

      // What the phone is told to authorize: the gateway and this server's identity there. It
      // registers only with the relay it was built to trust, so this is an advertisement.
      const capabilities = await pairing.getConnectionCapabilities({
        connectionId: phone.connectionId,
      });
      assert.equal(capabilities.relay?.relayUrl, gateway.url);
      assert.equal(capabilities.relay?.serverId, SERVER_ID);
      assert.equal(capabilities.relay?.protocolVersion, 1);

      await requestClient(server.url, phone.phoneToken).publishWallet({
        connectionId: phone.connectionId,
        binding: { wallet: USER_STAKE_OWNER, network: Network.MAINNET },
      });
      await pairing.setRelayHandle({
        connectionId: phone.connectionId,
        update: { case: "handle", value: HANDLE },
      });
      assert.deepEqual(gateway.notified, []);

      requestIdOf(
        await callTool(agent, CANCEL_UNSTAKE_TOOL, {
          idempotency_key: "see-150-relay-1",
        }),
      );
      await waitFor(
        () => gateway.notified.length > 0,
        "the gateway to be asked to wake the phone",
      );
      // Long enough for a second send to have happened, if one were coming.
      await delay(100);
      assert.deepEqual(gateway.notified, [
        {
          path: "/relay/v1/notify",
          authorization: `Bearer ${CREDENTIAL}`,
          body: { version: "1", handle: HANDLE, hint: "created" },
        },
      ]);

      // Startup says the relay is on and where, and never what the credential is.
      assert.ok(
        logs.some(
          (line) =>
            line.includes("gateway push relay is configured") &&
            line.includes(gateway.url) &&
            line.includes(SERVER_ID),
        ),
      );
      assert.ok(logs.every((line) => !line.includes(CREDENTIAL)));
    } finally {
      await agent.close();
      await server.close();
      await cluster.close();
      await gateway.close();
    }
  });

  it("says at startup that the relay is off when it is not configured", async () => {
    const cluster = await stubCluster(MAINNET_GENESIS_HASH);
    const logs: string[] = [];
    const server = await startStakingServer(configFor(cluster.url), {
      log: (line) => logs.push(line),
    });
    try {
      assert.ok(
        logs.includes(
          "gateway push relay is off; SKR_STAKING_RELAY_URL is not configured",
        ),
      );
    } finally {
      await server.close();
      await cluster.close();
    }
  });
});
