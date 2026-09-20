/**
 * The Stage 7.1 integration acceptance run (SEE-98): the direct and the gateway flow against each
 * other, the privacy claim against what the run actually wrote, and the existing private agent
 * workflow proved unchanged while two public feeds are live.
 *
 * Every component here is the shipped one. The broadcast gateway, both publisher templates and
 * their two operator CLIs are the binaries `pnpm test:integration` builds out of the two Go
 * modules; the sidecar is its own process with its own database; the agent is the real MCP client.
 * What is stood in for is only the world outside — the prediction provider
 * (`integration/provider.ts`, serving this repository's committed captures) and the wallet, which
 * is a throwaway key pair producing the same Ed25519 signature a wallet app would.
 *
 * **Nothing here spends anything, reaches a network, or needs a credential.** Both deployments run
 * as sandbox, every address in the run is loopback, there is no wallet app for anything to reach,
 * and the provider stand-in serves no order, fill or settlement route at all — a template that
 * asked for one would be recorded asking for a path that does not exist, and would fail the run.
 *
 * What it cannot do is the phone: the Android client is another runtime, and its half of every
 * scenario below is under `android/app/src/test` (`pnpm check:android`, and the cross-component
 * subset that `pnpm test:integration` runs). The device half is the owner's, and the checklist is
 * in `docs/testing/see-98.md`.
 *
 * Run it with `pnpm test:integration`. It is not the owner's check on a physical Seeker.
 */
import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { mkdirSync, mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, before, describe, it } from "node:test";
import { setTimeout as delay } from "node:timers/promises";

import { Network, RequestState } from "@seeker-vault/server-sdk/protocol";
import {
  callTool,
  connectAgent,
  pairPhone,
  pairingClient,
  requestClient,
  viewOf,
  type TestPhone,
} from "../../sidecar/src/testing/clients.ts";
import {
  freePort,
  startSidecarProcess,
  type SidecarProcess,
} from "../../sidecar/src/testing/process.ts";
import {
  testWallet,
  type TestWallet,
} from "../../server-sdk/src/testing/wallet.ts";
import {
  BROKER_API_KEY,
  BROKER_TOKEN_KEY,
  startBroker,
  type Broker,
} from "./integration/broker.ts";
import {
  channelFor,
  device,
  refusal,
  type Device,
} from "./integration/feed.ts";
import {
  broadcastctl,
  publish,
  publishctl,
  register,
  run,
  startGateway,
  startTemplate,
  type Gateway,
  type Template,
} from "./integration/processes.ts";
import {
  TRACKED_EVENT,
  TRACKED_MARKET,
  TRACKED_MARKETS,
  startFakeProvider,
  type FakeProvider,
} from "./integration/provider.ts";
import {
  describe as describeFindings,
  filesUnder,
  mustFind,
  sweep,
} from "./integration/sweep.ts";

/** The binaries `pnpm test:integration` builds. The suite refuses to guess at them. */
const BINARIES = {
  gateway: named("SEEKERVAULT_BROADCAST"),
  control: named("SEEKERVAULT_BROADCASTCTL"),
  copytrading: named("SEEKERVAULT_COPYTRADING"),
  prediction: named("SEEKERVAULT_PREDICTION"),
  publishctl: named("SEEKERVAULT_PUBLISHCTL"),
};

/** The pinned broker, when the machine running this has it. Absent is a skip, never a pass. */
const CENTRIFUGO = process.env.SEEKERVAULT_CENTRIFUGO ?? "";

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);
const API_TOKEN = "t".repeat(48);
/** The message the agent asks the owner's wallet to sign; one of the privacy sweep's needles. */
const MESSAGE = "SEE-98: one request, answered to the agent that asked for it";
/** The two mints a published swap signal names. Both are public facts and nobody's business. */
const SOL = "So11111111111111111111111111111111111111112";
const USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";

let root: string;
let gatewayDatabase: string;
let gateway: Gateway;
let broker: Broker | undefined;
let signals: Template;
let markets: Template;
let provider: FakeProvider;
let sidecar: SidecarProcess;
let sidecarDatabase: string;
let phone: TestPhone;
let owner: TestWallet;
/** What the agent read back, which is where the signature legitimately is. */
let agentRead = "";
/** Every log line every process in the run printed, including the ones that have since stopped. */
const logs: { what: string; text: string }[] = [];
/** Every directory a publisher or the gateway wrote to, for the privacy sweep. */
const directories: string[] = [];

/** Two publishers, each its own deployment: its own server ID, credential and database. */
const copytrading = { id: randomUUID(), credential: "" };
const prediction = { id: randomUUID(), credential: "" };
/** A third, whose manifest names a gateway this one does not serve. */
const redirecting = { id: randomUUID(), credential: "" };
/** A fourth, registered so that it can be forgotten while somebody is reading it. */
const forgotten = { id: randomUUID(), credential: "" };

let alice: Device;
let bob: Device;
let swapProposal = "";
let marketProposal = "";

before(async () => {
  root = mkdtempSync(join(tmpdir(), "seeker-vault-stage71-"));
  const gatewayDirectory = join(root, "gateway");
  mkdirSync(gatewayDirectory);
  directories.push(gatewayDirectory);
  gatewayDatabase = join(gatewayDirectory, "broadcast.db");
  for (const [server, label] of [
    [copytrading, "copy trading"],
    [prediction, "prediction markets"],
    [redirecting, "somebody else's gateway"],
    [forgotten, "a publisher that goes away"],
  ] as const) {
    server.credential = register(
      BINARIES.control,
      gatewayDatabase,
      server.id,
      label,
    );
  }

  if (CENTRIFUGO !== "") {
    broker = await startBroker(CENTRIFUGO, {
      api: await freePort(),
      stream: await freePort(),
    });
  }
  gateway = await startGateway({
    binary: BINARIES.gateway,
    control: BINARIES.control,
    databasePath: gatewayDatabase,
    readPort: await freePort(),
    publisherPort: await freePort(),
    ...(broker === undefined
      ? {}
      : {
          streamUrl: broker.url,
          streamApiKey: BROKER_API_KEY,
          streamTokenKey: BROKER_TOKEN_KEY,
        }),
  });

  provider = await startFakeProvider();
  signals = await startTemplate({
    binary: BINARIES.copytrading,
    what: "the CopyTrading template",
    apiPort: await freePort(),
    environment: deployment(copytrading, "copytrading", "copy trading demo"),
  });
  markets = await startTemplate({
    binary: BINARIES.prediction,
    what: "the Prediction template",
    apiPort: await freePort(),
    environment: {
      ...deployment(prediction, "prediction", "prediction demo"),
      PREDICTION_PROVIDER_URL: provider.url,
      // No pacing and no cycle timer: every pass in this run is one the suite asked for.
      PREDICTION_CALL_GAP_MS: "0",
      PREDICTION_POLL_SECONDS: "3600",
      PREDICTION_SOURCE: "polymarket",
      PREDICTION_CATEGORIES: "economics",
      PREDICTION_PAGE_SIZE: "1",
      PREDICTION_MOST_PAGES: "2",
      PREDICTION_MOST_OPEN: "5",
      PREDICTION_MOST_CHECKS: "10",
      // The captured listing's market closes about six weeks out; the window has to hold it.
      PREDICTION_MOST_CLOSE_IN_MINUTES: "129600",
    },
  });

  // One swap signal, with the terms an operator would publish: two real mints, a slippage cap, and
  // no amount — because the amount is each owner's own and never the publisher's.
  swapProposal = created(
    await publish(BINARIES.publishctl, template(signals), [
      "create",
      "--in",
      "2h",
      "--note",
      "trimming SOL into USDC",
      "--key",
      randomUUID(),
      ...terms(SOL, "9", USDC, "6", "50"),
    ]),
  );
  // The Prediction template runs its first cycle as it starts, so there is nothing to ask for
  // here: what the suite waits for is the document that cycle publishes.
  alice = device(gateway.origin, "alice");
  bob = device(gateway.origin, "bob");
  await until(
    async () => {
      const answered = await alice.proposals(channelFor(prediction.id));
      const held = answered.value.proposals ?? [];
      // The captured event lists five markets — the five outcomes of one question — and a signal
      // is per market, so the template publishes five. The suite follows one of them.
      if (held.length < TRACKED_MARKETS.length) return false;
      marketProposal =
        held.find((one) =>
          (one.values ?? []).some(
            (value) =>
              value.key === "market_id" && value.text === TRACKED_MARKET,
          ),
        )?.proposalId ?? "";
      return marketProposal !== "";
    },
    "the Prediction template to publish the markets it discovered",
    60_000,
    () => markets.output(),
  );

  // The owner's own side: a paired sidecar, the wallet the phone published, and a real agent.
  const sidecarDirectory = join(root, "sidecar");
  mkdirSync(sidecarDirectory);
  sidecarDatabase = join(sidecarDirectory, "sidecar.db");
  sidecar = await startSidecarProcess({
    port: await freePort(),
    mcpToken: MCP_TOKEN,
    phoneToken: PHONE_TOKEN,
    liveCommandTimeoutSeconds: 30,
    databasePath: sidecarDatabase,
  });
  phone = await pairPhone(sidecar.url, sidecarDatabase, "the owner's Seeker");
  owner = testWallet();
  await requestClient(sidecar.url, phone.phoneToken).publishWallet({
    connectionId: phone.connectionId,
    binding: { wallet: owner.address, network: Network.MAINNET },
  });
});

after(async () => {
  await Promise.all([
    gateway?.stop(),
    signals?.stop(),
    markets?.stop(),
    sidecar?.stop(),
    provider?.stop(),
    broker?.stop(),
  ]);
});

describe("what a phone reads from a feed", () => {
  it("is the same document on two devices, and a revision it holds is not sent again", async () => {
    const first = await alice.manifest(copytrading.id);
    const second = await bob.manifest(copytrading.id);
    assert.equal(first.status, 200, first.text);
    assert.equal(
      first.text,
      second.text,
      "two devices read different manifests",
    );

    const manifest = first.value.manifest;
    assert.equal(manifest?.serverId, copytrading.id);
    assert.equal(manifest?.mode, "CONNECTION_MODE_GATEWAY_FEED");
    assert.equal(manifest?.protocolVersion, 1);
    assert.deepEqual(manifest?.environments, ["SERVER_ENVIRONMENT_SANDBOX"]);
    assert.deepEqual(manifest?.feed, {
      gatewayUrl: gateway.origin,
      channel: channelFor(copytrading.id),
    });
    assert.deepEqual(manifest?.requiredPlugins, [
      { pluginId: "jupiter.swap", minContract: 1, maxContract: 1 },
    ]);

    const again = await alice.manifest(
      copytrading.id,
      Number(first.value.settingsRevision ?? "0"),
    );
    assert.equal(again.value.unchanged, true);
    assert.equal(
      again.value.manifest,
      undefined,
      "a revision it already holds was sent again",
    );
  });

  it("carries the publisher's terms, and nothing about any subscriber", async () => {
    const answered = await alice.proposals(channelFor(copytrading.id));
    const proposals = answered.value.proposals ?? [];
    assert.equal(proposals.length, 1, answered.text);
    const [proposal] = proposals;
    assert.equal(proposal?.proposalId, swapProposal);
    assert.equal(proposal?.operation, "swap");
    assert.equal(proposal?.pluginId, "jupiter.swap");
    assert.equal(proposal?.status, "PROPOSAL_STATUS_OPEN");
    assert.equal(proposal?.publisherNote, "trimming SOL into USDC");
    assert.deepEqual(
      (proposal?.values ?? []).map((value) => value.key).sort(),
      [
        "input_decimals",
        "input_mint",
        "max_slippage_bps",
        "output_decimals",
        "output_mint",
      ],
    );
    // The amount is not in the document, because it is not the publisher's to state.
    for (const word of [
      "amount",
      "wallet",
      "payer",
      "decision",
      "signature",
      "result",
    ]) {
      assert.ok(
        !answered.text.includes(word),
        `the feed's answer carries "${word}"`,
      );
    }
  });

  it("is a stable walk, and one proposal can be asked for on its own", async () => {
    const page = await alice.proposals(channelFor(copytrading.id), {
      pageSize: 1,
    });
    assert.equal((page.value.proposals ?? []).length, 1);
    assert.equal(
      page.value.nextPageToken,
      undefined,
      "a one-proposal channel offered a second page",
    );

    const one = await bob.proposal(channelFor(copytrading.id), swapProposal);
    assert.equal(one.value.proposal?.proposalId, swapProposal);
    const missing = await bob.proposal(
      channelFor(copytrading.id),
      randomUUID(),
    );
    assert.equal(refusal(missing).message, "no_such_proposal (proposal_id)");
  });

  it("tells a device that already holds the snapshot that nothing changed", async () => {
    const first = await alice.proposals(channelFor(copytrading.id));
    const sequence = first.value.snapshotSequence ?? "0";
    assert.notEqual(sequence, "0");
    const again = await alice.proposals(channelFor(copytrading.id), {
      knownSnapshotSequence: sequence,
    });
    assert.equal(again.value.unchanged, true);
    assert.deepEqual(again.value.proposals ?? [], []);
  });
});

describe("a manifest cannot move a subscriber anywhere", () => {
  it("is refused when it names another gateway, and that publisher stays unreadable", async () => {
    const elsewhere = await startTemplate({
      binary: BINARIES.copytrading,
      what: "a template published for another gateway",
      apiPort: await freePort(),
      environment: {
        ...deployment(redirecting, "redirecting", "a feed pointing elsewhere"),
        // The document names a gateway this one does not serve; the publication still comes here.
        PUBLISHER_GATEWAY_URL: "https://feeds.example.com",
        PUBLISHER_PUBLISH_URL: gateway.publishTo,
      },
    });
    try {
      await until(
        () => Promise.resolve(elsewhere.output().includes("other_gateway")),
        "the gateway to refuse a redirected manifest",
        20_000,
        () => elsewhere.output(),
      );
      assert.match(
        elsewhere.output(),
        /nobody can subscribe to this publisher until its manifest is published/,
      );
      const answered = await alice.manifest(redirecting.id);
      assert.equal(refusal(answered).message, "no_such_server (server_id)");
    } finally {
      logs.push({
        what: "a template published for another gateway",
        text: elsewhere.output(),
      });
      await elsewhere.stop();
    }
  });

  it("is refused with no credential, with an unknown one, and with a revoked one alike", async () => {
    broadcastctl(BINARIES.control, gatewayDatabase, [
      "revoke",
      "--server",
      redirecting.id,
      "--all",
    ]);
    const said: string[] = [];
    for (const credential of [
      undefined,
      "n".repeat(43),
      redirecting.credential,
      copytrading.credential,
    ]) {
      const answered = await alice.call(
        "PublishManifest",
        {},
        {
          service: "seekervault.gateway.v1.PublisherService",
          origin: gateway.publishTo,
          ...(credential === undefined ? {} : { credential }),
        },
      );
      said.push(refusal(answered).message);
    }
    assert.deepEqual(said.slice(0, 3), [
      "unauthenticated",
      "unauthenticated",
      "unauthenticated",
    ]);
    // A credential that is still live gets past authentication, so what it is told about is the
    // document. That is what makes the three refusals above about the credential and not the body.
    assert.notEqual(said[3], "unauthenticated");
  });

  it("is refused when one publisher publishes on another's channel", async () => {
    const impersonating = await startTemplate({
      binary: BINARIES.copytrading,
      what: "a template holding another publisher's credential",
      apiPort: await freePort(),
      environment: deployment(
        { id: forgotten.id, credential: copytrading.credential },
        "impersonating",
        "not mine to publish",
      ),
    });
    try {
      await until(
        () =>
          Promise.resolve(
            /other_server|foreign_channel/.test(impersonating.output()),
          ),
        "the gateway to refuse a publication for another server",
        20_000,
        () => impersonating.output(),
      );
    } finally {
      logs.push({
        what: "a template holding another publisher's credential",
        text: impersonating.output(),
      });
      await impersonating.stop();
    }
  });

  it("takes the feed with it when the operator forgets the publisher", async () => {
    const going = await startTemplate({
      binary: BINARIES.copytrading,
      what: "a publisher that is about to be forgotten",
      apiPort: await freePort(),
      environment: deployment(forgotten, "forgotten", "here for a moment"),
    });
    try {
      await until(
        async () => (await alice.manifest(forgotten.id)).ok,
        "the fourth publisher's manifest to be readable",
        20_000,
        () => going.output(),
      );
    } finally {
      logs.push({
        what: "a publisher that is about to be forgotten",
        text: going.output(),
      });
      await going.stop();
    }
    broadcastctl(BINARIES.control, gatewayDatabase, [
      "forget",
      "--server",
      forgotten.id,
      "--yes",
    ]);
    const answered = await alice.manifest(forgotten.id);
    assert.equal(refusal(answered).message, "no_such_server (server_id)");
    const proposals = await alice.proposals(channelFor(forgotten.id));
    assert.deepEqual(proposals.value.proposals ?? [], []);
  });

  it("moves the revision when the deployment's own configuration changes", async () => {
    const before = await alice.manifest(copytrading.id);
    const port = Number(new URL(signals.apiUrl).port);
    logs.push({
      what: "the CopyTrading template, before it was renamed",
      text: signals.output(),
    });
    await signals.stop();
    signals = await startTemplate({
      binary: BINARIES.copytrading,
      what: "the CopyTrading template, renamed",
      apiPort: port,
      environment: deployment(
        copytrading,
        "copytrading",
        "copy trading, renamed",
      ),
    });
    await until(
      async () =>
        (await alice.manifest(copytrading.id)).value.manifest?.displayName ===
        "copy trading, renamed",
      "the renamed manifest to be published",
      20_000,
      () => signals.output(),
    );
    const after = await alice.manifest(copytrading.id);
    assert.ok(
      Number(after.value.settingsRevision) >
        Number(before.value.settingsRevision),
      "a changed configuration kept the old revision",
    );
    // What it published stands: a settings change is not a republication of every document.
    const proposals = await alice.proposals(channelFor(copytrading.id));
    assert.equal(
      (proposals.value.proposals ?? [])[0]?.proposalId,
      swapProposal,
    );
  });
});

describe("the transport", () => {
  it("publishes one document for one signal, however many times it is asked for", async () => {
    const key = randomUUID();
    const first = created(
      await publish(BINARIES.publishctl, template(signals), [
        "create",
        "--in",
        "2h",
        "--key",
        key,
        "--note",
        "the same signal, twice",
        ...terms(USDC, "6", SOL, "9", "30"),
      ]),
    );
    const sequence = (await alice.proposals(channelFor(copytrading.id))).value
      .snapshotSequence;
    const again = created(
      await publish(BINARIES.publishctl, template(signals), [
        "create",
        "--in",
        "2h",
        "--key",
        key,
        "--note",
        "the same signal, twice",
        ...terms(USDC, "6", SOL, "9", "30"),
      ]),
    );
    assert.equal(again, first, "the same key minted a second proposal");
    const after = await alice.proposals(channelFor(copytrading.id));
    assert.equal(
      after.value.snapshotSequence,
      sequence,
      "the same document published twice moved the channel",
    );

    await publish(BINARIES.publishctl, template(signals), ["cancel", first]);
    await until(
      async () =>
        (await alice.proposal(channelFor(copytrading.id), first)).value.proposal
          ?.status === "PROPOSAL_STATUS_CANCELLED",
      "the withdrawal to reach the feed",
    );
  });

  it("keeps an expired proposal readable, because expiry is a fact in the document", async () => {
    const answer = JSON.parse(
      await publish(BINARIES.publishctl, template(signals), [
        "create",
        "--in",
        "3s",
        "--key",
        randomUUID(),
        "--note",
        "a signal with seconds to live",
        ...terms(SOL, "9", USDC, "6", "50"),
      ]),
    ) as {
      request: {
        identity: { request_id: string };
        lifecycle: { expires_at: string };
      };
    };
    await until(
      () =>
        Promise.resolve(
          Date.parse(answer.request.lifecycle.expires_at) < Date.now(),
        ),
      "the signal to expire",
      15_000,
    );
    const read = await alice.proposal(
      channelFor(copytrading.id),
      answer.request.identity.request_id,
    );
    assert.equal(read.value.proposal?.status, "PROPOSAL_STATUS_OPEN");
    assert.ok(
      Date.parse(read.value.proposal?.expiresAt ?? "") < Date.now(),
      "an expired proposal was served with a future expiry",
    );
  });

  it("serves the same documents after the gateway restarts on its own database", async () => {
    const first = await alice.proposals(channelFor(prediction.id));
    await gateway.restart();
    const again = await bob.proposals(channelFor(prediction.id));
    assert.equal(
      again.text,
      first.text,
      "a restart changed what a phone reads",
    );
  });

  it("republishes nothing when a publisher restarts and its settings stand", async () => {
    const manifest = await alice.manifest(prediction.id);
    const proposals = await alice.proposals(channelFor(prediction.id));
    await markets.restart();
    await until(
      () =>
        Promise.resolve(markets.output().includes("publishing as this server")),
      "the Prediction template to come back",
      20_000,
      () => markets.output(),
    );
    assert.equal(
      (await alice.manifest(prediction.id)).value.settingsRevision,
      manifest.value.settingsRevision,
    );
    assert.equal(
      (await bob.proposals(channelFor(prediction.id))).text,
      proposals.text,
      "a restart republished what it had already published",
    );
  });

  it("serves no publisher procedure on the read port, and no feed procedure on the other", async () => {
    // Not one of the two devices: what a subscriber sends is asserted elsewhere, and this is a
    // client deliberately knocking on the wrong door.
    const stray = device(gateway.origin, "a stray client");
    const write = await stray.call(
      "PublishProposal",
      {},
      {
        service: "seekervault.gateway.v1.PublisherService",
        credential: copytrading.credential,
      },
    );
    assert.equal(write.status, 404, `the read listener answered ${write.text}`);
    const read = await stray.call(
      "ListProposals",
      { channel: channelFor(copytrading.id) },
      { origin: gateway.publishTo },
    );
    assert.equal(
      read.status,
      404,
      `the publisher listener answered ${read.text}`,
    );
    // Nor does either listener carry the sidecar's own API, which is the other transport entirely.
    const direct = await stray.call(
      "ListPending",
      {},
      { service: "seekervault.request.v1.RequestService" },
    );
    assert.equal(direct.status, 404, direct.text);
  });
});

describe("the prediction template, against the provider's own answers", () => {
  it("publishes the market it discovered, with the provider's facts and no order of its own", async () => {
    const answered = await alice.proposals(channelFor(prediction.id));
    const proposals = answered.value.proposals ?? [];
    assert.equal(proposals.length, TRACKED_MARKETS.length, answered.text);
    const proposal = proposals.find((one) => one.proposalId === marketProposal);
    assert.ok(
      proposal !== undefined,
      "the market the suite follows is not on the channel",
    );
    assert.equal(proposal?.operation, "prediction");
    assert.equal(proposal?.pluginId, "jupiter.prediction");
    const values = new Map(
      (proposal?.values ?? []).map((value) => [value.key, value.text]),
    );
    assert.equal(values.get("market_id"), TRACKED_MARKET);
    assert.equal(values.get("event_id"), TRACKED_EVENT);
    // The side and the stake are the owner's, so the document states neither.
    for (const key of ["is_yes", "side", "stake", "amount", "wallet"]) {
      assert.equal(values.get(key), undefined, `the document carries ${key}`);
    }
    // And the provider is not named to the phone: the plugin knows where to look.
    assert.ok(!answered.text.includes("jup.ag"), answered.text);
  });

  it("asked the provider for a listing and for one market, and for nothing that could fill", () => {
    const paths = provider.requests.map(
      (request) => request.target.split("?")[0] ?? "",
    );
    assert.ok(paths.length > 0, "the template asked the provider nothing");
    for (const path of paths) {
      assert.ok(
        path === "/prediction/v1/events" ||
          path.startsWith("/prediction/v1/markets/"),
        `the template asked the provider for ${path}`,
      );
    }
    for (const request of provider.requests) {
      assert.equal(request.headers.authorization, undefined);
      assert.equal(request.headers["x-api-key"], undefined);
    }
  });

  it("withdraws the market when the provider says it closed, and offers nothing in its place", async () => {
    provider.close();
    await poll();
    await until(
      async () =>
        (await alice.proposal(channelFor(prediction.id), marketProposal)).value
          .proposal?.status === "PROPOSAL_STATUS_CANCELLED",
      "the withdrawal to reach the feed",
      20_000,
      () => markets.output(),
    );
    const answered = await alice.proposals(channelFor(prediction.id));
    const proposals = answered.value.proposals ?? [];
    assert.equal(
      proposals.length,
      TRACKED_MARKETS.length,
      "a closure published something new",
    );
    for (const one of proposals) {
      assert.equal(
        one.status,
        "PROPOSAL_STATUS_CANCELLED",
        "a market whose event closed is still open",
      );
    }
  });
});

describe("mixed mode: the owner's own sidecar, while two feeds are live", () => {
  it("still returns a private request's result to the agent that asked for it", async () => {
    const agent = await connectAgent(sidecar.url, MCP_TOKEN);
    try {
      const asked = viewOf(
        await callTool(agent, "vault_sign_message", {
          wallet: owner.address,
          message: MESSAGE,
          note: "SEE-98",
          idempotency_key: randomUUID(),
        }),
      );
      assert.equal(asked.status, "PENDING");
      const client = requestClient(sidecar.url, phone.phoneToken);
      const { requests } = await client.listPending({
        connectionId: phone.connectionId,
      });
      assert.deepEqual(
        requests.map((request) => request.ref?.requestId ?? ""),
        [asked.request_id],
      );

      const ref = {
        connectionId: phone.connectionId,
        requestId: asked.request_id,
      };
      // The approval names the exact bytes. Anything else is refused before a wallet is opened.
      await assert.rejects(
        client.submitResult({
          ref,
          result: {
            case: "approval",
            value: {
              preparedVersion: 0,
              contentHash: sha256("something else entirely"),
            },
          },
        }),
        "an approval over other bytes was accepted",
      );
      await client.submitResult({
        ref,
        result: {
          case: "approval",
          value: { preparedVersion: 0, contentHash: sha256(MESSAGE) },
        },
      });
      await client.submitResult({
        ref,
        result: {
          case: "messageSignature",
          value: { signature: owner.sign(MESSAGE) },
        },
      });

      const read = viewOf(
        await callTool(agent, "vault_get_request", {
          request_id: asked.request_id,
        }),
      );
      assert.equal(read.status, "COMPLETED");
      assert.ok(
        read.signature !== undefined,
        "the agent got no signature back",
      );
      agentRead = JSON.stringify(read);
      const stored = await client.getRequest({ ref });
      assert.equal(stored.request?.state, RequestState.COMPLETED);
    } finally {
      await agent.close();
    }
  });

  it("read both feeds while that happened, and told neither publisher anything", () => {
    for (const device of [alice, bob]) {
      for (const call of device.calls) {
        assert.match(
          call.procedure,
          /^\/seekervault\.gateway\.v1\.(FeedService|PublisherService)\//,
          `${device.name} called ${call.procedure}`,
        );
      }
    }
    const readable = [alice, bob].map((device) => device.sent()).join("\n");
    for (const needle of [
      owner.address,
      phone.phoneToken,
      MCP_TOKEN,
      MESSAGE,
    ]) {
      assert.ok(
        !readable.includes(needle),
        "a device sent something about its owner",
      );
    }
    for (const which of [signals, markets]) {
      assert.ok(
        !which.output().includes(owner.address),
        "a publisher's log names the owner's wallet",
      );
    }
  });

  it("keeps the sidecar's own manifest a direct one, whatever a feed says", async () => {
    const answered = await pairingClient(
      sidecar.url,
      phone.phoneToken,
    ).getServerManifest({ connectionId: phone.connectionId });
    // CONNECTION_MODE_DIRECT. A paired sidecar is the private workflow, and no feed reaches it.
    assert.equal(answered.manifest?.mode, 1);
    assert.deepEqual(answered.manifest?.requiredPlugins ?? [], []);
  });
});

describe("the two environments", () => {
  it("published both deployments as sandbox, and say so in their own answers", async () => {
    for (const server of [copytrading, prediction]) {
      const answered = await alice.manifest(server.id);
      assert.deepEqual(answered.value.manifest?.environments, [
        "SERVER_ENVIRONMENT_SANDBOX",
      ]);
    }
    for (const which of [signals, markets]) {
      const status = JSON.parse(
        await publish(BINARIES.publishctl, template(which), ["status"]),
      ) as { environment?: string };
      assert.equal(status.environment, "sandbox");
    }
  });

  it("refuses to start a production deployment on a sandbox database", async () => {
    const tried = await run(
      BINARIES.copytrading,
      [],
      {
        ...deployment(copytrading, "copytrading", "copy trading, renamed"),
        PUBLISHER_ENVIRONMENT: "production",
        PUBLISHER_API_ADDRESS: "127.0.0.1:0",
      },
      // It must stop, and quickly: the check is the first thing `store.Open` does. Bounding the
      // wait is what makes a deployment that wrongly kept running fail this rather than hang it.
      15_000,
    );
    assert.equal(
      tried.timedOut,
      false,
      "a production process kept running on a sandbox database",
    );
    assert.notEqual(
      tried.code,
      0,
      "a production process opened a sandbox database",
    );
    assert.match(
      `${tried.stdout}${tried.stderr}`,
      /it is a sandbox database, and PUBLISHER_ENVIRONMENT is production/,
    );
  });
});

describe("the stream", () => {
  it("is granted where a broker is configured, and reported absent where none is", async () => {
    const answered = await alice.ticket([channelFor(copytrading.id)]);
    if (broker === undefined) {
      assert.equal(refusal(answered).message, "no_stream");
      assert.match(
        gateway.output(),
        /no broker is configured/,
        "a gateway with no broker did not say so",
      );
      return;
    }
    assert.ok(answered.ok, answered.text);
    const channels = answered.value.channels ?? [];
    assert.equal(channels.length, 1);
    assert.equal(channels[0]?.channel, channelFor(copytrading.id));
    assert.match(
      channels[0]?.streamChannel ?? "",
      /^feed:/,
      "the stream channel is outside the broker's namespace",
    );
  });

  it("carried this run's publications to the broker, when there was one", async () => {
    if (broker === undefined) {
      assert.match(gateway.output(), /publications are not streamed/);
      return;
    }
    const position = await broker.position(
      `feed:${channelFor(copytrading.id)}`,
    );
    assert.ok(
      position.offset > 0,
      `nothing reached the broker's channel: ${JSON.stringify(position)}`,
    );
  });
});

describe("privacy, over what this run actually wrote", () => {
  it("finds nothing about a subscriber in either publisher's files, or the gateway's", () => {
    const found = sweep(
      needles(),
      [
        { what: "the gateway's log", text: gateway.output() },
        { what: "the CopyTrading template's log", text: signals.output() },
        { what: "the Prediction template's log", text: markets.output() },
        ...logs,
        { what: "what alice sent", text: alice.sent() },
        { what: "what bob sent", text: bob.sent() },
      ],
      directories.flatMap((directory) => filesUnder(directory)),
    );
    assert.deepEqual(found, [], describeFindings(found));
  });

  it("finds all of it on the owner's own side, which is what proves the search works", () => {
    // Its write-ahead log counts as much as the database: what a sweep reads is files, not tables.
    const own = filesUnder(join(root, "sidecar"))
      .map((path) => readFileSync(path).toString("latin1"))
      .join("");
    const missing = mustFind(needles(), {
      what: "the owner's own sidecar, and the answer the agent read",
      text: `${own}${agentRead}`,
    });
    assert.deepEqual(
      missing,
      [],
      `the sweep cannot find ${missing.join(", ")} where it is`,
    );
  });

  it("records what the provider unavoidably learned, and it is nothing about anybody", () => {
    const targets = provider.requests.map((request) => request.target);
    assert.ok(targets.length > 0);
    // A provider read tells it which markets a publisher is interested in. That is the publisher's
    // own interest, on a server no subscriber ever touched, and it is the whole of what leaves.
    for (const target of targets) {
      assert.ok(!target.includes(owner.address), target);
      assert.ok(!target.includes(copytrading.id), target);
      assert.ok(!target.includes(prediction.id), target);
    }
  });
});

/** What must never appear off the owner's own device. */
function needles(): { what: string; value: string }[] {
  const signature = (
    JSON.parse(agentRead === "" ? "{}" : agentRead) as {
      signature?: string;
    }
  ).signature;
  return [
    { what: "the owner's wallet address", value: owner.address },
    { what: "the message the agent asked to have signed", value: MESSAGE },
    ...(signature === undefined
      ? []
      : [{ what: "the signature the wallet produced", value: signature }]),
  ];
}

/** The settings one deployment runs with: its own server, credential, database and name. */
function deployment(
  server: { readonly id: string; readonly credential: string },
  directory: string,
  name: string,
): Record<string, string> {
  const at = join(root, directory);
  mkdirSync(at, { recursive: true });
  if (!directories.includes(at)) directories.push(at);
  return {
    PUBLISHER_SERVER_ID: server.id,
    PUBLISHER_GATEWAY_URL: gateway.origin,
    PUBLISHER_PUBLISH_URL: gateway.publishTo,
    PUBLISHER_ENVIRONMENT: "sandbox",
    PUBLISHER_DATABASE_PATH: join(at, "publisher.db"),
    PUBLISHER_API_TOKEN: API_TOKEN,
    PUBLISHER_DISPLAY_NAME: name,
    BROADCAST_CREDENTIAL: server.credential,
  };
}

/** A swap signal's terms: the pair, its decimals, and the slippage the publisher will allow. */
function terms(
  input: string,
  inputDecimals: string,
  output: string,
  outputDecimals: string,
  slippage: string,
): string[] {
  return [
    "--term",
    `input_mint=${input}`,
    "--term",
    `input_decimals=${inputDecimals}`,
    "--term",
    `output_mint=${output}`,
    "--term",
    `output_decimals=${outputDecimals}`,
    "--term",
    `max_slippage_bps=${slippage}`,
  ];
}

function template(which: Template): { apiUrl: string; token: string } {
  return { apiUrl: which.apiUrl, token: API_TOKEN };
}

/**
 * One reconciliation cycle, waited for. The template refuses a second cycle while one is running
 * (`busy`), which is the guard doing its job rather than a failure, so this asks again.
 */
async function poll(): Promise<void> {
  await until(async () => {
    const answered = await publishctl(BINARIES.publishctl, template(markets), [
      "poll",
    ]);
    if (answered.code === 0) return true;
    if (!`${answered.stdout}${answered.stderr}`.includes("busy")) {
      // A moment for the template's own log line to arrive: it is written as the answer is sent.
      await delay(300);
      assert.fail(
        `publishctl poll said ${answered.stderr}${answered.stdout}\nthe template said:\n${markets.output()}`,
      );
    }
    return false;
  }, "a reconciliation cycle this suite asked for");
}

/** The durable identity in the common request that `publishctl create` printed. */
function created(printed: string): string {
  return (
    JSON.parse(printed) as { request: { identity: { request_id: string } } }
  ).request.identity.request_id;
}

function sha256(text: string): Uint8Array {
  return Uint8Array.from(createHash("sha256").update(text, "utf8").digest());
}

/** Polls until `condition` holds, and fails with what was happening if it never does. */
async function until(
  condition: () => Promise<boolean>,
  description: string,
  timeoutMs = 30_000,
  said?: () => string,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    if (await condition()) return;
    if (Date.now() > deadline) {
      assert.fail(
        `timed out waiting for ${description}${said === undefined ? "" : `:\n${said()}`}`,
      );
    }
    await delay(100);
  }
}

/** A required binary, with the message for somebody who ran this file directly. */
function named(variable: string): string {
  const path = process.env[variable];
  if (path === undefined || path === "") {
    throw new Error(
      `${variable} is not set: run this suite with \`pnpm test:integration\`, which builds the binaries it needs.`,
    );
  }
  return path;
}
