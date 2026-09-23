import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { create } from "@bufbuild/protobuf";

import {
  ActionSchema,
  Network,
  RequestError,
  RequestState,
  StakingOperation,
  WalletBindingSchema,
  type Action,
  type RequestRef,
  type StakingAction,
} from "../gen/seekervault/request/v1/request_pb.js";
import {
  ProviderUnavailable,
  UnsupportedPreparation,
  type BuiltPreparation,
  type StakingProvider,
  type TransferProvider,
} from "../providers.ts";
import {
  IN_MEMORY,
  openDatabase,
  type DatabaseSync,
} from "../storage/database.ts";
import { PairingStore } from "../storage/pairing-store.ts";
import { RequestStore } from "../storage/request-store.ts";
import { RequestFailure } from "./failure.ts";
import { TransactionPreparer } from "./preparation.ts";

const NOON = Date.UTC(2026, 8, 11, 12); // 2026-09-11T12:00:00Z
const DAY_SECONDS = 86_400;
const SERVER_URL = "http://127.0.0.1:8080";
const WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW";
const OTHER_WALLET = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh";

/** One build the fake provider was asked for. */
interface Build {
  readonly action: StakingAction;
  readonly nowMs: number;
}

interface Setup {
  readonly clock: { now: number };
  readonly db: DatabaseSync;
  readonly store: RequestStore;
  readonly connectionId: string;
}

/** A store on a new database, with a phone paired and the owner's mainnet wallet published. */
function setup(): Setup {
  const clock = { now: NOON };
  let issued = 0;
  const db = openDatabase(IN_MEMORY);
  const store = new RequestStore(db, {
    defaultTtlSeconds: DAY_SECONDS,
    pendingLimit: 100,
    now: () => clock.now,
    newId: () =>
      `00000000-0000-4000-8000-${String(++issued).padStart(12, "0")}`,
  });
  const connectionId = pairTestPhone(db, clock);
  store.publishWallet(
    connectionId,
    create(WalletBindingSchema, { wallet: WALLET, network: Network.MAINNET }),
  );
  return { clock, db, store, connectionId };
}

/** Pairs a phone the way PairingService does, so that new requests have a connection. */
function pairTestPhone(db: DatabaseSync, clock: { now: number }): string {
  const pairing = new PairingStore(db, { now: () => clock.now });
  const { token } = pairing.issue(SERVER_URL, 600);
  return pairing.pair(token, SERVER_URL, "Test phone").connectionId;
}

function stakingAction(
  operation: StakingOperation = StakingOperation.STAKE,
  amount = "1000000",
): Action {
  return create(ActionSchema, {
    kind: {
      case: "staking",
      value: { wallet: WALLET, network: Network.MAINNET, operation, amount },
    },
  });
}

function transferAction(): Action {
  return create(ActionSchema, {
    kind: {
      case: "transfer",
      value: {
        wallet: WALLET,
        network: Network.MAINNET,
        recipient: OTHER_WALLET,
        asset: { kind: { case: "nativeSol", value: {} } },
        amount: "1",
      },
    },
  });
}

/** Stores `action` as a PENDING request and returns the reference the phone prepares by. */
function stored(s: Setup, action: Action, idempotencyKey: string): RequestRef {
  const { request } = s.store.create({ action, agentNote: "", idempotencyKey });
  return request.ref!;
}

/**
 * Unsigned bytes as a provider returns them, told apart by `marker`. Nothing here is signed: a
 * provider never holds a key, and the signature slots stay empty until the owner's wallet fills
 * them.
 */
function built(marker: number): BuiltPreparation {
  return {
    transaction: Uint8Array.of(marker),
    contentHash: new Uint8Array(32).fill(marker),
    lastValidBlockHeight: 250n,
    estimatedExpiryMs: NOON + 90_000,
    feeLamports: 5_000n,
    rentLamports: 0n,
  };
}

/** A transfer provider that builds fixed bytes, for the preparers that are given both. */
const TRANSFERS: TransferProvider = {
  checkAsset: () => Promise.resolve(),
  buildTransfer: () => Promise.resolve(built(9)),
};

/** A staking provider that records nothing, for the getters. */
const STAKING: StakingProvider = {
  checkStaking: () => Promise.resolve(),
  buildStaking: () => Promise.resolve(built(9)),
};

function refused(
  error: RequestError,
  message?: string,
): (thrown: unknown) => boolean {
  return (thrown) =>
    thrown instanceof RequestFailure &&
    thrown.error === error &&
    (message === undefined || thrown.message === message);
}

describe("TransactionPreparer: staking", () => {
  it("serves staking exactly when the host supplied a staking provider", () => {
    const s = setup();
    const both = new TransactionPreparer(s.store, {
      transfers: TRANSFERS,
      staking: STAKING,
    });
    assert.equal(both.servesStaking, true);
    assert.equal(both.servesTransfers, true);
    const onlyTransfers = new TransactionPreparer(s.store, {
      transfers: TRANSFERS,
    });
    assert.equal(onlyTransfers.servesStaking, false);
    assert.equal(onlyTransfers.servesTransfers, true);
    const onlyStaking = new TransactionPreparer(s.store, { staking: STAKING });
    assert.equal(onlyStaking.servesStaking, true);
    assert.equal(onlyStaking.servesTransfers, false);
  });

  it("asks the staking provider what the owner asked for, and stores it as the next version", async () => {
    const s = setup();
    const ref = stored(
      s,
      stakingAction(StakingOperation.UNSTAKE, "2500000"),
      "unstake",
    );
    const builds: Build[] = [];
    const preparer = new TransactionPreparer(
      s.store,
      {
        staking: {
          checkStaking: () => Promise.resolve(),
          buildStaking: (action, nowMs) => {
            builds.push({ action, nowMs });
            return Promise.resolve(built(builds.length));
          },
        },
      },
      () => s.clock.now,
    );

    const first = await preparer.prepareTransaction(s.connectionId, ref);
    assert.equal(first.version, 1);
    assert.deepEqual(first.transaction, Uint8Array.of(1));
    assert.equal(first.feeLamports, 5_000n);
    assert.equal(builds.length, 1);
    assert.equal(builds[0]?.action.operation, StakingOperation.UNSTAKE);
    assert.equal(builds[0]?.action.amount, "2500000");
    assert.equal(builds[0]?.nowMs, NOON);

    // The owner opened the review again later: a second build is version 2, and the first stays.
    s.clock.now = NOON + 60_000;
    const second = await preparer.prepareTransaction(s.connectionId, ref);
    assert.equal(second.version, 2);
    assert.equal(builds.length, 2);
    assert.equal(builds[1]?.nowMs, NOON + 60_000);
    assert.deepEqual(
      s.store.latestPrepared(s.connectionId, ref)?.transaction,
      Uint8Array.of(2),
    );
  });

  it("passes an agent's staking action to the provider before the request is stored", async () => {
    const s = setup();
    const checked: StakingAction[] = [];
    const preparer = new TransactionPreparer(s.store, {
      staking: {
        checkStaking: (action) => {
          checked.push(action);
          return Promise.resolve();
        },
        buildStaking: () => Promise.resolve(built(1)),
      },
    });
    const { kind } = stakingAction(StakingOperation.WITHDRAW, "");
    assert.equal(kind.case, "staking");
    if (kind.case === "staking") await preparer.checkStaking(kind.value);
    assert.equal(checked.length, 1);
    assert.equal(checked[0]?.operation, StakingOperation.WITHDRAW);
    // A host that serves no staking says so with the same error a preparation would give.
    await assert.rejects(
      new TransactionPreparer(s.store, { transfers: TRANSFERS }).checkStaking(
        checked[0],
      ),
      refused(
        RequestError.CHAIN_UNAVAILABLE,
        "this sidecar has no staking provider configured, so it can prepare no staking action",
      ),
    );
  });

  it("leaves a staking request PENDING and still preparable when the chain can't be read", async () => {
    const s = setup();
    const ref = stored(s, stakingAction(), "unavailable");
    let outcome: Error | undefined = new ProviderUnavailable(
      "the staking endpoint didn't answer",
    );
    const preparer = new TransactionPreparer(
      s.store,
      {
        staking: {
          checkStaking: () => Promise.resolve(),
          buildStaking: () =>
            outcome === undefined
              ? Promise.resolve(built(1))
              : Promise.reject(outcome),
        },
      },
      () => s.clock.now,
    );

    await assert.rejects(
      preparer.prepareTransaction(s.connectionId, ref),
      refused(
        RequestError.CHAIN_UNAVAILABLE,
        "the staking endpoint didn't answer",
      ),
    );
    assert.equal(s.store.get(ref.requestId).state, RequestState.PENDING);
    assert.equal(s.store.latestPrepared(s.connectionId, ref), undefined);
    // Nothing was stored, so the next attempt is still the first version the owner sees.
    outcome = undefined;
    const prepared = await preparer.prepareTransaction(s.connectionId, ref);
    assert.equal(prepared.version, 1);
  });

  it("reports a refusal the provider is certain about as the request's own", async () => {
    const s = setup();
    const ref = stored(
      s,
      stakingAction(StakingOperation.WITHDRAW, ""),
      "withdraw",
    );
    const preparer = new TransactionPreparer(s.store, {
      staking: {
        checkStaking: () => Promise.resolve(),
        buildStaking: () =>
          Promise.reject(
            new UnsupportedPreparation(
              "no cooldown has completed, so there is nothing to withdraw",
            ),
          ),
      },
    });
    await assert.rejects(
      preparer.prepareTransaction(s.connectionId, ref),
      refused(
        RequestError.INVALID_PARAMETERS,
        "no cooldown has completed, so there is nothing to withdraw",
      ),
    );
    assert.equal(s.store.get(ref.requestId).state, RequestState.PENDING);
  });

  it("prepares no staking action for a host that only sends transfers", async () => {
    const s = setup();
    const ref = stored(s, stakingAction(), "staking-unserved");
    const preparer = new TransactionPreparer(s.store, {
      transfers: TRANSFERS,
    });
    await assert.rejects(
      preparer.prepareTransaction(s.connectionId, ref),
      refused(
        RequestError.CHAIN_UNAVAILABLE,
        "this sidecar has no staking provider configured, so it can prepare no staking action",
      ),
    );
    assert.equal(s.store.latestPrepared(s.connectionId, ref), undefined);
  });

  it("prepares no transfer for a host that only serves staking", async () => {
    const s = setup();
    const ref = stored(s, transferAction(), "transfer-unserved");
    const preparer = new TransactionPreparer(s.store, { staking: STAKING });
    await assert.rejects(
      preparer.prepareTransaction(s.connectionId, ref),
      refused(
        RequestError.CHAIN_UNAVAILABLE,
        "this sidecar has no transfer provider configured, so it can prepare no transfer",
      ),
    );
    assert.equal(s.store.latestPrepared(s.connectionId, ref), undefined);
  });
});
