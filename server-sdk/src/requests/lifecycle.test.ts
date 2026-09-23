import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { describe, it } from "node:test";

import { create, type MessageInitShape } from "@bufbuild/protobuf";
import { timestampFromMs } from "@bufbuild/protobuf/wkt";

import {
  ActionRequestSchema,
  Network,
  StakingOperation,
  PreparedTransactionSchema,
  RequestError,
  RequestState,
  RequestStateSchema,
  type ActionRequest,
  type ActionSchema,
} from "../gen/seekervault/request/v1/request_pb.js";
import {
  SubmitResultRequestSchema,
  type SubmitResultRequest,
} from "../gen/seekervault/request/v1/service_pb.js";
import {
  APPROVAL_MARGIN_MS,
  TRANSITIONS,
  canTransition,
  decideResult,
  isOverdue,
  isTerminal,
  resultTarget,
  successState,
  unpreparableReason,
  type ActionKind,
  type Actor,
  type ResultCase,
} from "./lifecycle.ts";
import { testWallet } from "../testing/wallet.ts";

const NOON = Date.UTC(2026, 8, 11, 12); // 2026-09-11T12:00:00Z
const DEADLINE = NOON + 60_000;
const REF = {
  connectionId: "5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f",
  requestId: "3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c",
};
// A throwaway wallet, so a message_signature can carry a signature the sidecar really verifies.
const SIGNER = testWallet();
const WALLET = SIGNER.address;
const RECIPIENT = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh";
const USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
const MESSAGE = "Sign in to Example\r\ne\u{301}"; // decomposed e-acute, which NFC would change

const KINDS: readonly ActionKind[] = [
  "ack",
  "signMessage",
  "transfer",
  "swap",
  "staking",
];
const RESULTS: readonly ResultCase[] = [
  "acknowledgement",
  "rejection",
  "approval",
  "messageSignature",
  "transactionSubmission",
  "executionFailure",
  "unknownOutcome",
];
const STATES: readonly RequestState[] = RequestStateSchema.values
  .map((value): RequestState => value.number)
  .filter((state) => state !== RequestState.UNSPECIFIED);

const ACTIONS: Readonly<
  Record<ActionKind, MessageInitShape<typeof ActionSchema>>
> = {
  ack: { kind: { case: "ack", value: { text: "Deploy finished" } } },
  signMessage: {
    kind: {
      case: "signMessage",
      value: { wallet: WALLET, content: { case: "text", value: MESSAGE } },
    },
  },
  transfer: {
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
  },
  swap: {
    kind: {
      case: "swap",
      value: {
        wallet: WALLET,
        network: Network.MAINNET,
        inputAsset: { kind: { case: "tokenMint", value: USDC } },
        outputAsset: { kind: { case: "nativeSol", value: {} } },
        inputAmount: "1000000",
        slippageBps: 50,
      },
    },
  },
  staking: {
    kind: {
      case: "staking",
      value: {
        wallet: WALLET,
        network: Network.MAINNET,
        operation: StakingOperation.UNSTAKE,
        amount: "1000000",
      },
    },
  },
};

const HASH_V2 = sha256(Uint8Array.of(1, 2, 3));
const PREPARED_V2 = create(PreparedTransactionSchema, {
  ref: REF,
  version: 2,
  transaction: Uint8Array.of(1, 2, 3),
  contentHash: HASH_V2,
});

function sha256(bytes: Uint8Array): Uint8Array {
  return new Uint8Array(createHash("sha256").update(bytes).digest());
}

function name(state: RequestState): string {
  return RequestState[state];
}

function request(
  kind: ActionKind,
  state: RequestState = RequestState.PENDING,
): ActionRequest {
  return create(ActionRequestSchema, {
    ref: REF,
    action: ACTIONS[kind],
    state,
    createdAt: timestampFromMs(NOON),
    expiresAt: timestampFromMs(DEADLINE),
    updatedAt: timestampFromMs(NOON),
  });
}

function submission(
  result: MessageInitShape<typeof SubmitResultRequestSchema>["result"],
): SubmitResultRequest {
  return create(SubmitResultRequestSchema, { ref: REF, result });
}

/** The transitions for one kind, as "FROM -> TO by actor" lines. */
function edges(kind: ActionKind): string[] {
  return TRANSITIONS.filter((transition) => transition.kinds.includes(kind))
    .map(({ from, to, by }) => `${name(from)} -> ${name(to)} by ${by}`)
    .sort();
}

/** The states a request of this kind can reach from `start`, including `start`. */
function reachable(
  kind: ActionKind,
  start: RequestState = RequestState.PENDING,
): Set<RequestState> {
  const seen = new Set([start]);
  const queue = [start];
  for (const state of queue) {
    for (const { from, to, kinds } of TRANSITIONS) {
      if (from === state && kinds.includes(kind) && !seen.has(to)) {
        seen.add(to);
        queue.push(to);
      }
    }
  }
  return seen;
}

describe("the transition table", () => {
  it("ack: the user acknowledges or rejects it, or it's cancelled or expires", () => {
    assert.deepEqual(
      edges("ack"),
      [
        "PENDING -> COMPLETED by phone",
        "PENDING -> REJECTED by phone",
        "PENDING -> CANCELLED by agent",
        "PENDING -> CANCELLED by sidecar",
        "PENDING -> EXPIRED by sidecar",
      ].sort(),
    );
  });

  it("sign_message: approval, then the wallet's signature or an uncertain outcome", () => {
    assert.deepEqual(
      edges("signMessage"),
      [
        "PENDING -> PROCESSING by phone",
        "PENDING -> REJECTED by phone",
        "PENDING -> CANCELLED by agent",
        "PENDING -> CANCELLED by sidecar",
        "PENDING -> EXPIRED by sidecar",
        "PROCESSING -> COMPLETED by phone",
        "PROCESSING -> REJECTED by phone",
        "PROCESSING -> FAILED by phone",
        "PROCESSING -> UNKNOWN by phone",
        "PROCESSING -> UNKNOWN by sidecar",
        "UNKNOWN -> COMPLETED by phone",
        "UNKNOWN -> REJECTED by phone",
        "UNKNOWN -> FAILED by phone",
      ].sort(),
    );
  });

  for (const kind of ["transfer", "swap", "staking"] as const) {
    it(`${kind}: approval, submission, then on-chain confirmation`, () => {
      assert.deepEqual(
        edges(kind),
        [
          "PENDING -> PROCESSING by phone",
          "PENDING -> REJECTED by phone",
          "PENDING -> CANCELLED by agent",
          "PENDING -> CANCELLED by sidecar",
          "PENDING -> EXPIRED by sidecar",
          "PROCESSING -> SUBMITTED by phone",
          "PROCESSING -> SUBMITTED by sidecar",
          "PROCESSING -> REJECTED by phone",
          "PROCESSING -> FAILED by phone",
          "PROCESSING -> FAILED by sidecar",
          "PROCESSING -> UNKNOWN by phone",
          "PROCESSING -> UNKNOWN by sidecar",
          "SUBMITTED -> CONFIRMED by sidecar",
          "SUBMITTED -> FAILED by sidecar",
          "UNKNOWN -> SUBMITTED by phone",
          "UNKNOWN -> SUBMITTED by sidecar",
          "UNKNOWN -> REJECTED by phone",
          "UNKNOWN -> FAILED by phone",
          "UNKNOWN -> FAILED by sidecar",
        ].sort(),
      );
    });
  }

  it("staking: confirms on chain, and never ends as a signed message", () => {
    assert.equal(successState("staking"), RequestState.CONFIRMED);
    assert.notEqual(successState("staking"), RequestState.COMPLETED);
    const path: ReadonlyArray<readonly [RequestState, RequestState, Actor]> = [
      [RequestState.PENDING, RequestState.PROCESSING, "phone"],
      [RequestState.PROCESSING, RequestState.SUBMITTED, "phone"],
      [RequestState.SUBMITTED, RequestState.CONFIRMED, "sidecar"],
    ];
    for (const [from, to, by] of path) {
      assert.ok(
        canTransition("staking", from, to, by),
        `${name(from)} -> ${name(to)} by ${by}`,
      );
    }
    // A staking action is a transaction: the wallet sends one, and signs nothing to hand back.
    assert.equal(
      canTransition(
        "staking",
        RequestState.PROCESSING,
        RequestState.COMPLETED,
        "phone",
      ),
      false,
    );
    assert.equal(
      resultTarget("staking", RequestState.PROCESSING, "messageSignature"),
      undefined,
    );
    assert.ok(!reachable("staking").has(RequestState.COMPLETED));
  });

  it("has six terminal states that nothing leaves, and UNKNOWN isn't one", () => {
    assert.deepEqual(STATES.filter(isTerminal).map(name), [
      "CONFIRMED",
      "COMPLETED",
      "REJECTED",
      "CANCELLED",
      "EXPIRED",
      "FAILED",
    ]);
    for (const { from, to } of TRANSITIONS) {
      assert.ok(!isTerminal(from), `${name(from)} -> ${name(to)}`);
    }
    for (const state of STATES.filter((state) => !isTerminal(state))) {
      assert.ok(
        TRANSITIONS.some(({ from }) => from === state),
        `${name(state)} has a way forward`,
      );
    }
  });

  it("never moves a request backward: the transitions form no cycle", () => {
    const visiting = new Set<RequestState>();
    const done = new Set<RequestState>();
    const cyclic = (state: RequestState): boolean => {
      if (done.has(state)) return false;
      if (visiting.has(state)) return true;
      visiting.add(state);
      const found = TRANSITIONS.some(
        ({ from, to }) => from === state && cyclic(to),
      );
      visiting.delete(state);
      done.add(state);
      return found;
    };
    assert.equal(STATES.some(cyclic), false);
  });

  it("lets every request reach an end, and succeed in its own success state", () => {
    for (const kind of KINDS) {
      assert.ok(reachable(kind).has(successState(kind)), kind);
      for (const state of reachable(kind)) {
        assert.ok(
          [...reachable(kind, state)].some(isTerminal),
          `${kind} in ${name(state)}`,
        );
      }
    }
  });

  it("uses COMPLETED for ack and messages, and CONFIRMED only for transactions", () => {
    assert.equal(successState("ack"), RequestState.COMPLETED);
    assert.equal(successState("signMessage"), RequestState.COMPLETED);
    assert.equal(successState("transfer"), RequestState.CONFIRMED);
    assert.equal(successState("swap"), RequestState.CONFIRMED);
    assert.equal(successState("staking"), RequestState.CONFIRMED);
    for (const kind of ["ack", "signMessage"] as const) {
      assert.ok(!reachable(kind).has(RequestState.CONFIRMED), kind);
      assert.ok(!reachable(kind).has(RequestState.SUBMITTED), kind);
    }
    for (const kind of ["transfer", "swap", "staking"] as const) {
      assert.ok(!reachable(kind).has(RequestState.COMPLETED), kind);
    }
    assert.ok(!reachable("ack").has(RequestState.PROCESSING));
  });

  it("lets only the sidecar expire, confirm, or find a transaction, and only before approval for expiry and cancellation", () => {
    for (const { from, to, by } of TRANSITIONS) {
      const line = `${name(from)} -> ${name(to)} by ${by}`;
      if (to === RequestState.EXPIRED || to === RequestState.CANCELLED) {
        assert.equal(from, RequestState.PENDING, line);
      }
      if (to === RequestState.EXPIRED || to === RequestState.CONFIRMED) {
        assert.equal(by, "sidecar", line);
      }
      if (by === "agent") assert.equal(to, RequestState.CANCELLED, line);
    }
  });

  it("keeps the policy assessment out of the execution states", () => {
    assert.deepEqual(
      STATES.map(name).filter((state) => /ALLOWED|RESTRICT|POLICY/.test(state)),
      [],
    );
  });
});

describe("resultTarget", () => {
  it("moves each phone transition with exactly one result", () => {
    for (const { from, to, by, kinds } of TRANSITIONS) {
      if (by !== "phone") continue;
      for (const kind of kinds) {
        const results = RESULTS.filter(
          (result) => resultTarget(kind, from, result) === to,
        );
        assert.equal(
          results.length,
          1,
          `${kind}: ${name(from)} -> ${name(to)}`,
        );
      }
    }
  });

  it("allows only phone transitions from the table", () => {
    for (const kind of KINDS) {
      for (const from of STATES) {
        for (const result of RESULTS) {
          const to = resultTarget(kind, from, result);
          if (to !== undefined) {
            assert.ok(canTransition(kind, from, to, "phone"));
          }
        }
      }
    }
  });

  it("refuses results that don't fit the kind or the state", () => {
    const refused: ReadonlyArray<
      readonly [ActionKind, RequestState, ResultCase]
    > = [
      ["transfer", RequestState.PENDING, "acknowledgement"],
      ["ack", RequestState.PENDING, "approval"],
      ["ack", RequestState.PENDING, "messageSignature"],
      ["transfer", RequestState.PROCESSING, "messageSignature"],
      ["signMessage", RequestState.PROCESSING, "transactionSubmission"],
      ["transfer", RequestState.UNKNOWN, "unknownOutcome"],
      ["transfer", RequestState.SUBMITTED, "rejection"],
      ["transfer", RequestState.PROCESSING, "approval"],
    ];
    for (const [kind, state, result] of refused) {
      assert.equal(
        resultTarget(kind, state, result),
        undefined,
        `${kind} ${name(state)} ${result}`,
      );
    }
    for (const kind of KINDS) {
      for (const state of STATES.filter(isTerminal)) {
        for (const result of RESULTS) {
          assert.equal(resultTarget(kind, state, result), undefined);
        }
      }
    }
  });
});

describe("decideResult", () => {
  it("completes a pending ack when the user acknowledges it", () => {
    assert.deepEqual(
      decideResult(
        request("ack"),
        submission({ case: "acknowledgement", value: {} }),
      ),
      { ok: true, to: RequestState.COMPLETED },
    );
  });

  it("binds a transaction approval to the latest prepared version and its hash", () => {
    const approve = (preparedVersion: number, contentHash: Uint8Array) =>
      decideResult(
        request("transfer"),
        submission({
          case: "approval",
          value: { preparedVersion, contentHash },
        }),
        PREPARED_V2,
      );
    assert.deepEqual(approve(2, HASH_V2), {
      ok: true,
      to: RequestState.PROCESSING,
    });
    assert.deepEqual(approve(1, HASH_V2), {
      ok: false,
      error: RequestError.STALE_PREPARATION,
      message:
        "the approval is for version 1, but the latest prepared version is 2",
    });
    assert.deepEqual(approve(2, sha256(Uint8Array.of(9))), {
      ok: false,
      error: RequestError.STALE_PREPARATION,
      message:
        "the approval's content_hash isn't the latest prepared transaction's",
    });
    assert.deepEqual(
      decideResult(
        request("swap"),
        submission({
          case: "approval",
          value: { preparedVersion: 1, contentHash: HASH_V2 },
        }),
      ),
      {
        ok: false,
        error: RequestError.STALE_PREPARATION,
        message: "nothing has been prepared for this request yet",
      },
    );
  });

  it("binds a message approval to the hash of the message's exact bytes", () => {
    const exact = sha256(new TextEncoder().encode(MESSAGE));
    const normalized = sha256(
      new TextEncoder().encode(MESSAGE.normalize("NFC")),
    );
    const approve = (preparedVersion: number, contentHash: Uint8Array) =>
      decideResult(
        request("signMessage"),
        submission({
          case: "approval",
          value: { preparedVersion, contentHash },
        }),
      );
    assert.deepEqual(approve(0, exact), {
      ok: true,
      to: RequestState.PROCESSING,
    });
    const mismatch = {
      ok: false,
      error: RequestError.INVALID_PARAMETERS,
      message:
        "a message approval needs prepared_version 0 and the SHA-256 of the message as content_hash",
    };
    assert.deepEqual(approve(0, normalized), mismatch);
    assert.deepEqual(approve(1, exact), mismatch);
  });

  it("moves wallet results on from PROCESSING and from UNKNOWN", () => {
    const signature = new Uint8Array(64).fill(7);
    for (const state of [RequestState.PROCESSING, RequestState.UNKNOWN]) {
      assert.deepEqual(
        decideResult(
          request("signMessage", state),
          submission({
            case: "messageSignature",
            value: { signature: SIGNER.sign(MESSAGE) },
          }),
        ),
        { ok: true, to: RequestState.COMPLETED },
      );
      assert.deepEqual(
        decideResult(
          request("transfer", state),
          submission({ case: "transactionSubmission", value: { signature } }),
        ),
        { ok: true, to: RequestState.SUBMITTED },
      );
      assert.deepEqual(
        decideResult(
          request("swap", state),
          submission({ case: "rejection", value: {} }),
        ),
        { ok: true, to: RequestState.REJECTED },
      );
      assert.deepEqual(
        decideResult(
          request("swap", state),
          submission({
            case: "executionFailure",
            value: { detail: "wallet error" },
          }),
        ),
        { ok: true, to: RequestState.FAILED },
      );
    }
    assert.deepEqual(
      decideResult(
        request("transfer", RequestState.PROCESSING),
        submission({
          case: "unknownOutcome",
          value: { detail: "wallet session dropped" },
        }),
      ),
      { ok: true, to: RequestState.UNKNOWN },
    );
  });

  it("refuses a message signature that isn't this wallet's, over these bytes", () => {
    const other = testWallet();
    const refusal = {
      ok: false,
      error: RequestError.INVALID_PARAMETERS,
      message: `message_signature.signature isn't ${WALLET}'s signature of this request's message`,
    };
    // Another wallet's signature, however valid it is in itself.
    assert.deepEqual(
      decideResult(
        request("signMessage", RequestState.PROCESSING),
        submission({
          case: "messageSignature",
          value: { signature: other.sign(MESSAGE) },
        }),
      ),
      refusal,
    );
    // The right wallet, over bytes that aren't the request's: a trailing newline, and the NFC
    // form of the same text, are both different messages.
    for (const message of [`${MESSAGE}\n`, MESSAGE.normalize("NFC")]) {
      assert.deepEqual(
        decideResult(
          request("signMessage", RequestState.PROCESSING),
          submission({
            case: "messageSignature",
            value: { signature: SIGNER.sign(message) },
          }),
        ),
        refusal,
      );
    }
    // 64 bytes that are no signature at all.
    assert.deepEqual(
      decideResult(
        request("signMessage", RequestState.PROCESSING),
        submission({
          case: "messageSignature",
          value: { signature: new Uint8Array(64).fill(7) },
        }),
      ),
      refusal,
    );
  });

  it("refuses a result that conflicts with how the request already ended", () => {
    assert.deepEqual(
      decideResult(
        request("ack", RequestState.COMPLETED),
        submission({ case: "rejection", value: {} }),
      ),
      {
        ok: false,
        error: RequestError.INVALID_STATE,
        message: "rejection doesn't apply to ack requests in state COMPLETED",
      },
    );
    assert.deepEqual(
      decideResult(
        request("transfer", RequestState.EXPIRED),
        submission({
          case: "approval",
          value: { preparedVersion: 2, contentHash: HASH_V2 },
        }),
        PREPARED_V2,
      ),
      {
        ok: false,
        error: RequestError.INVALID_STATE,
        message: "approval doesn't apply to transfer requests in state EXPIRED",
      },
    );
  });

  it("settles a race between the agent's cancellation and the user's approval by order", () => {
    const approval = submission({
      case: "approval",
      value: { preparedVersion: 2, contentHash: HASH_V2 },
    });
    // Cancellation first: the approval is refused, so the phone never invokes the wallet.
    assert.ok(
      canTransition(
        "transfer",
        RequestState.PENDING,
        RequestState.CANCELLED,
        "agent",
      ),
    );
    assert.equal(
      decideResult(
        request("transfer", RequestState.CANCELLED),
        approval,
        PREPARED_V2,
      ).ok,
      false,
    );
    // Approval first: the request is PROCESSING, and the agent can no longer cancel it.
    assert.deepEqual(decideResult(request("transfer"), approval, PREPARED_V2), {
      ok: true,
      to: RequestState.PROCESSING,
    });
    assert.ok(
      !canTransition(
        "transfer",
        RequestState.PROCESSING,
        RequestState.CANCELLED,
        "agent",
      ),
    );
  });

  it("reports malformed submissions before looking at the state", () => {
    const cases: ReadonlyArray<readonly [SubmitResultRequest, string]> = [
      [
        create(SubmitResultRequestSchema, {
          result: { case: "rejection", value: {} },
        }),
        "ref is missing",
      ],
      [create(SubmitResultRequestSchema, { ref: REF }), "result is missing"],
      [
        submission({
          case: "approval",
          value: { preparedVersion: 2, contentHash: HASH_V2.slice(0, 31) },
        }),
        "approval.content_hash must be 32 bytes (SHA-256)",
      ],
      [
        submission({
          case: "transactionSubmission",
          value: { signature: new Uint8Array(63) },
        }),
        "transaction_submission.signature must be 64 bytes (Ed25519)",
      ],
      [
        submission({ case: "messageSignature", value: {} }),
        "message_signature.signature must be 64 bytes (Ed25519)",
      ],
      [
        submission({
          case: "executionFailure",
          value: { detail: "x".repeat(1025) },
        }),
        "execution_failure.detail is 1025 UTF-8 bytes; the limit is 1024",
      ],
    ];
    for (const [malformed, message] of cases) {
      assert.deepEqual(
        decideResult(
          request("transfer", RequestState.PROCESSING),
          malformed,
          PREPARED_V2,
        ),
        {
          ok: false,
          error: RequestError.INVALID_PARAMETERS,
          message,
        },
      );
    }
  });
});

describe("an approval near the blockhash's expiry", () => {
  const prepared = (estimatedExpiryMs: number) =>
    create(PreparedTransactionSchema, {
      ref: REF,
      version: 2,
      transaction: Uint8Array.of(1, 2, 3),
      contentHash: HASH_V2,
      estimatedExpiry: timestampFromMs(estimatedExpiryMs),
    });
  const approve = (estimatedExpiryMs: number, nowMs: number) =>
    decideResult(
      request("transfer"),
      submission({
        case: "approval",
        value: { preparedVersion: 2, contentHash: HASH_V2 },
      }),
      prepared(estimatedExpiryMs),
      nowMs,
    );

  it("is accepted while at least the margin is left", () => {
    assert.deepEqual(approve(NOON + APPROVAL_MARGIN_MS, NOON), {
      ok: true,
      to: RequestState.PROCESSING,
    });
  });

  it("is refused once less than the margin is left", () => {
    for (const left of [APPROVAL_MARGIN_MS - 1, 0, -60_000]) {
      const decision = approve(NOON + left, NOON);
      assert.equal(decision.ok, false);
      assert.equal(
        decision.ok ? undefined : decision.error,
        RequestError.STALE_PREPARATION,
        `${left} ms left`,
      );
    }
  });
});

describe("unpreparableReason", () => {
  it("lets a PENDING transfer or swap be prepared", () => {
    assert.equal(unpreparableReason(request("transfer")), undefined);
    assert.equal(unpreparableReason(request("swap")), undefined);
  });

  it("refuses a request the owner has already answered", () => {
    for (const state of [
      RequestState.PROCESSING,
      RequestState.SUBMITTED,
      RequestState.CONFIRMED,
      RequestState.REJECTED,
      RequestState.CANCELLED,
      RequestState.EXPIRED,
      RequestState.FAILED,
      RequestState.UNKNOWN,
    ]) {
      assert.deepEqual(unpreparableReason(request("transfer", state)), {
        error: RequestError.INVALID_STATE,
        message: `only a PENDING request can be prepared; this one is ${RequestState[state]}`,
      });
    }
  });

  it("lets a PENDING staking action be prepared, and no answered one", () => {
    assert.equal(unpreparableReason(request("staking")), undefined);
    for (const state of STATES.filter(
      (state) => state !== RequestState.PENDING,
    )) {
      assert.deepEqual(unpreparableReason(request("staking", state)), {
        error: RequestError.INVALID_STATE,
        message: `only a PENDING request can be prepared; this one is ${RequestState[state]}`,
      });
    }
  });

  it("refuses an action with no transaction to build", () => {
    assert.deepEqual(unpreparableReason(request("ack")), {
      error: RequestError.INVALID_PARAMETERS,
      message: "ack requests have nothing to prepare",
    });
    assert.deepEqual(unpreparableReason(request("signMessage")), {
      error: RequestError.INVALID_PARAMETERS,
      message: "sign_message requests have nothing to prepare",
    });
  });
});

describe("isOverdue", () => {
  it("expires a PENDING request at expires_at, and not a millisecond before", () => {
    assert.equal(isOverdue(request("ack"), DEADLINE - 1), false);
    assert.equal(isOverdue(request("ack"), DEADLINE), true);
    assert.equal(isOverdue(request("ack"), DEADLINE + 1), true);
  });

  it("stops applying the deadline once the user approved", () => {
    for (const state of STATES.filter(
      (state) => state !== RequestState.PENDING,
    )) {
      assert.equal(
        isOverdue(request("transfer", state), DEADLINE + 1),
        false,
        name(state),
      );
    }
  });
});
