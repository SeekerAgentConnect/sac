/**
 * The gateway relay (SEE-144). What a server may say to the gateway, what it does with each
 * answer, and the one rule that keeps a handle and an FCM target from being confused for each
 * other.
 *
 * A real loopback HTTP server rather than a stubbed fetch, because the thing worth pinning is the
 * request on the wire: the path, the bearer, and — the part that matters most — that there is
 * nothing in the body but a handle and one of two words.
 */
import assert from "node:assert/strict";
import { createServer, type IncomingMessage, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { after, describe, it } from "node:test";

import {
  GatewayRelaySender,
  RELAY_PATH,
  RelayInvalidationDispatcher,
  invalidRelayReason,
  type RelayOutcome,
  type RelaySender,
} from "./relay.ts";
import { openDatabase } from "../storage/database.ts";
import { PairingStore } from "../storage/pairing-store.ts";

const SERVER_ID = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
const CREDENTIAL = "a-scoped-relay-credential";

interface Received {
  readonly path: string;
  readonly authorization: string | undefined;
  readonly body: unknown;
}

/** A gateway that records what it was asked and answers what it is told to. */
async function relayEndpoint(
  status: () => number,
): Promise<{ url: string; received: Received[]; server: Server }> {
  const received: Received[] = [];
  const server = createServer((request: IncomingMessage, response) => {
    const chunks: Buffer[] = [];
    request.on("data", (chunk: Buffer) => chunks.push(chunk));
    request.on("end", () => {
      received.push({
        path: request.url ?? "",
        authorization: request.headers.authorization,
        body: JSON.parse(Buffer.concat(chunks).toString("utf8")) as unknown,
      });
      response.writeHead(status(), { "content-type": "application/json" });
      response.end(JSON.stringify({ version: "1", status: "accepted" }));
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  return { url: `http://127.0.0.1:${port}`, received, server };
}

function store(): PairingStore {
  return new PairingStore(openDatabase(":memory:"));
}

/** One paired connection with a relay handle on it, the way the phone's RPC would leave it. */
function paired(pairing: PairingStore, handle?: string): string {
  const issued = pairing.issue("http://127.0.0.1:7070", 600);
  const connection = pairing.pair(
    issued.token,
    "http://127.0.0.1:7070",
    "a phone",
  );
  if (handle !== undefined)
    pairing.setRelayHandle(connection.connectionId, handle);
  return connection.connectionId;
}

describe("the gateway relay sender", () => {
  it("sends a handle and one of two words, and nothing else", async () => {
    let status = 202;
    const gateway = await relayEndpoint(() => status);
    after(() => gateway.server.close());
    const sender = new GatewayRelaySender({
      relayUrl: gateway.url,
      serverId: SERVER_ID,
      credential: CREDENTIAL,
    });

    assert.equal(
      await sender.send({ handle: "h", hint: "created" }),
      "accepted",
    );
    const [one] = gateway.received;
    assert.equal(one?.path, `${RELAY_PATH}/notify`);
    assert.equal(one?.authorization, `Bearer ${CREDENTIAL}`);
    // The whole body. A field added here is a field an external server could put something in, so
    // the shape is asserted as a whole rather than key by key.
    assert.deepEqual(one?.body, { version: "1", handle: "h", hint: "created" });

    // There is no way to reach a notification, a target, a topic, a priority or a TTL: the type
    // has no member for any of them, and the gateway builds the message itself.
    status = 200;
    assert.equal(
      await sender.send({ handle: "h", hint: "updated" }),
      "coalesced",
    );
    assert.deepEqual(gateway.received[1]?.body, {
      version: "1",
      handle: "h",
      hint: "updated",
    });
  });

  it("classifies every answer, and never throws at the caller", async () => {
    for (const [status, outcome] of [
      [202, "accepted"],
      [200, "coalesced"],
      [401, "unauthorized"],
      [403, "unauthorized"],
      [429, "unavailable"],
      [503, "unavailable"],
      [500, "unavailable"],
    ] as [number, RelayOutcome][]) {
      const gateway = await relayEndpoint(() => status);
      const sender = new GatewayRelaySender({
        relayUrl: gateway.url,
        serverId: SERVER_ID,
        credential: CREDENTIAL,
      });
      assert.equal(
        await sender.send({ handle: "h", hint: "created" }),
        outcome,
        `status ${status}`,
      );
      gateway.server.close();
    }
  });

  it("treats an unreachable gateway as nobody knows, not as a refusal", async () => {
    const gateway = await relayEndpoint(() => 202);
    const url = gateway.url;
    await new Promise<void>((resolve) => gateway.server.close(() => resolve()));
    const sender = new GatewayRelaySender({
      relayUrl: url,
      serverId: SERVER_ID,
      credential: CREDENTIAL,
    });
    // "unavailable", never "unauthorized": the call may never have left here, and concluding that
    // a handle is finished from a transport failure would discard an authorization that works.
    assert.equal(
      await sender.send({ handle: "h", hint: "created" }),
      "unavailable",
    );
  });

  it("refuses a configuration that would send nowhere, by name and without the credential", () => {
    for (const [configuration, expected] of [
      [
        { relayUrl: "not a url", serverId: SERVER_ID, credential: CREDENTIAL },
        "absolute URL",
      ],
      [
        {
          relayUrl: "http://gateway.example.com",
          serverId: SERVER_ID,
          credential: CREDENTIAL,
        },
        "HTTPS",
      ],
      [
        {
          relayUrl: "https://gateway.example.com/relay",
          serverId: SERVER_ID,
          credential: CREDENTIAL,
        },
        "no path",
      ],
      [
        {
          relayUrl: "https://gateway.example.com",
          serverId: "not-a-uuid",
          credential: CREDENTIAL,
        },
        "lowercase UUID",
      ],
      [
        {
          relayUrl: "https://gateway.example.com",
          serverId: SERVER_ID,
          credential: "",
        },
        "empty",
      ],
    ] as [Parameters<typeof invalidRelayReason>[0], string][]) {
      const problem = invalidRelayReason(configuration);
      assert.ok(problem !== undefined, JSON.stringify(configuration));
      assert.ok(problem.includes(expected), problem);
      assert.equal(problem.includes(CREDENTIAL), false);
    }
    // A loopback gateway over plain HTTP is development, and is allowed for the same reason the
    // gateway's own origin rule allows it.
    assert.equal(
      invalidRelayReason({
        relayUrl: "http://127.0.0.1:8090",
        serverId: SERVER_ID,
        credential: CREDENTIAL,
      }),
      undefined,
    );
  });
});

describe("the relay dispatcher", () => {
  it("coalesces a burst about one connection into one wake-up", async () => {
    const pairing = store();
    const connection = paired(pairing, "a-handle");
    const sent: string[] = [];
    const sender: RelaySender = {
      send(invalidation) {
        sent.push(invalidation.hint);
        return Promise.resolve("accepted");
      },
    };
    const dispatcher = new RelayInvalidationDispatcher(
      pairing,
      sender,
      () => {},
    );

    for (let index = 0; index < 5; index += 1) {
      dispatcher.invalidate({ connectionId: connection, timeSensitive: false });
    }
    // A creation in the same batch upgrades the whole thing: the phone is being woken anyway, and
    // waking it at normal priority for a request that is waiting on its owner would be worse.
    dispatcher.invalidate({ connectionId: connection, timeSensitive: true });
    await dispatcher.close();
    assert.deepEqual(sent, ["created"]);
  });

  it("clears a handle the gateway no longer honours, and only that handle", async () => {
    const pairing = store();
    const connection = paired(pairing, "the-old-handle");
    const logged: string[] = [];
    let answer: RelayOutcome = "unauthorized";
    const sender: RelaySender = {
      send() {
        // The phone re-authorizes while this send is in flight, which is the race the compare
        // exists for: clearing unconditionally would throw away a handle that works.
        pairing.setRelayHandle(connection, "the-new-handle");
        return Promise.resolve(answer);
      },
    };
    const dispatcher = new RelayInvalidationDispatcher(
      pairing,
      sender,
      (line) => logged.push(line),
    );
    dispatcher.invalidate({ connectionId: connection, timeSensitive: false });
    await dispatcher.close();
    assert.equal(pairing.relayHandle(connection), "the-new-handle");

    // And a refusal about the current handle does clear it.
    answer = "unauthorized";
    const settled: RelaySender = {
      send() {
        return Promise.resolve<RelayOutcome>("unauthorized");
      },
    };
    const second = new RelayInvalidationDispatcher(pairing, settled, () => {});
    second.invalidate({ connectionId: connection, timeSensitive: false });
    await second.close();
    assert.equal(pairing.relayHandle(connection), undefined);

    // Nothing logged repeats a handle: a log line is the one place an authorization would end up
    // somewhere somebody reads.
    for (const line of logged) {
      assert.equal(line.includes("the-old-handle"), false, line);
      assert.equal(line.includes("the-new-handle"), false, line);
    }
  });

  it("does nothing for a connection that has authorized no relay", async () => {
    const pairing = store();
    const connection = paired(pairing);
    let calls = 0;
    const sender: RelaySender = {
      send() {
        calls += 1;
        return Promise.resolve("accepted");
      },
    };
    const dispatcher = new RelayInvalidationDispatcher(
      pairing,
      sender,
      () => {},
    );
    dispatcher.invalidate({ connectionId: connection, timeSensitive: true });
    await dispatcher.close();
    assert.equal(calls, 0);
  });

  it("keeps a sender that throws from reaching anything else", async () => {
    const pairing = store();
    const connection = paired(pairing, "a-handle");
    const logged: string[] = [];
    const sender: RelaySender = {
      send() {
        throw new Error("the host's own transport, naming a deployment");
      },
    };
    const dispatcher = new RelayInvalidationDispatcher(
      pairing,
      sender,
      (line) => logged.push(line),
    );
    dispatcher.invalidate({ connectionId: connection, timeSensitive: true });
    await dispatcher.close();
    // The authorization survives a transport failure — "nobody knows" is not "no longer
    // authorized" — and the thrown message is classified rather than quoted.
    assert.equal(pairing.relayHandle(connection), "a-handle");
    assert.equal(logged.length, 1);
    assert.equal(logged[0]?.includes("naming a deployment"), false);
  });
});

describe("a handle and an FCM target", () => {
  it("are separate values that never stand in for each other", () => {
    const pairing = store();
    const connection = paired(pairing);
    pairing.setFcmToken(connection, "an-fcm-target");
    pairing.setRelayHandle(connection, "a-relay-handle");

    assert.equal(pairing.fcmToken(connection), "an-fcm-target");
    assert.equal(pairing.relayHandle(connection), "a-relay-handle");
    // Clearing one leaves the other, so a server that was moved from direct Firebase to the relay
    // cannot have its remaining registration silently reinterpreted as the other kind of value.
    pairing.clearRelayHandle(connection, "a-relay-handle");
    assert.equal(pairing.relayHandle(connection), undefined);
    assert.equal(pairing.fcmToken(connection), "an-fcm-target");

    // Revocation ends both in the statement that ends the connection.
    pairing.setRelayHandle(connection, "a-relay-handle");
    pairing.revoke(connection);
    assert.equal(pairing.relayHandle(connection), undefined);
    assert.equal(pairing.fcmToken(connection), undefined);
  });
});
