/**
 * The durable request lifecycle (docs/protocol.md): the states a request moves between, who moves
 * it, and which result from the phone moves it where. Pure rules; SAW-010 stores requests and
 * applies these rules in transactions.
 */
import { createHash } from "node:crypto";

import { timestampMs } from "@bufbuild/protobuf/wkt";

import {
  RequestError,
  RequestState,
  type Action,
  type ActionRequest,
  type Approval,
  type PreparedTransaction,
} from "../gen/seekervault/request/v1/request_pb.js";
import type { SubmitResultRequest } from "../gen/seekervault/request/v1/service_pb.js";
import { isExpired } from "../live/command.ts";
import { invalidNoteReason, messageBytes } from "./action.ts";
import { invalidRefReason } from "./identity.ts";
import { verifySignature } from "./signature.ts";

/** An action kind, as the Action.kind oneof names it. */
export type ActionKind = NonNullable<Action["kind"]["case"]>;

/** A result from the phone, as the SubmitResultRequest.result oneof names it. */
export type ResultCase = NonNullable<SubmitResultRequest["result"]["case"]>;

/**
 * Who moves a request. The phone reports the user's decision and the wallet's result, the agent
 * cancels, and the sidecar applies expiry, revocation, and what it learns from the chain.
 */
export type Actor = "phone" | "agent" | "sidecar";

/** One allowed move. */
export interface Transition {
  readonly from: RequestState;
  readonly to: RequestState;
  readonly by: Actor;
  /** The action kinds it applies to. */
  readonly kinds: readonly ActionKind[];
  /** What causes it. */
  readonly when: string;
}

const {
  PENDING,
  PROCESSING,
  SUBMITTED,
  CONFIRMED,
  COMPLETED,
  REJECTED,
  CANCELLED,
  EXPIRED,
  FAILED,
  UNKNOWN,
} = RequestState;

/** An action kind as the protocol document writes it, for a message an agent reads. */
export function kindName(kind: ActionKind | undefined): string {
  if (kind === undefined) return "malformed";
  return kind === "signMessage" ? "sign_message" : kind;
}

/**
 * How much of a prepared transaction's blockhash window must be left when an approval arrives.
 * The phone invokes the wallet as soon as the sidecar accepts the approval, so anything less
 * would have the owner sign a transaction that can no longer land (docs/protocol.md#preparation).
 */
export const APPROVAL_MARGIN_MS = 15_000;

const EVERY_KIND: readonly ActionKind[] = [
  "ack",
  "signMessage",
  "transfer",
  "swap",
  "staking",
];
const WALLET_KINDS: readonly ActionKind[] = [
  "signMessage",
  "transfer",
  "swap",
  "staking",
];
const TRANSACTION_KINDS: readonly ActionKind[] = [
  "transfer",
  "swap",
  "staking",
];
const MESSAGE_KINDS: readonly ActionKind[] = ["signMessage"];

/**
 * Every allowed transition; anything else is refused. No state moves backward, and nothing leaves
 * a terminal state. ACK and message requests succeed as COMPLETED, transfers and swaps as
 * CONFIRMED.
 */
export const TRANSITIONS: readonly Transition[] = [
  {
    from: PENDING,
    to: COMPLETED,
    by: "phone",
    kinds: ["ack"],
    when: "the user acknowledged",
  },
  {
    from: PENDING,
    to: PROCESSING,
    by: "phone",
    kinds: WALLET_KINDS,
    when: "the user approved, and the phone is about to invoke the wallet",
  },
  {
    from: PENDING,
    to: REJECTED,
    by: "phone",
    kinds: EVERY_KIND,
    when: "the user rejected it in the app",
  },
  {
    from: PENDING,
    to: CANCELLED,
    by: "agent",
    kinds: EVERY_KIND,
    when: "the agent cancelled it",
  },
  {
    from: PENDING,
    to: CANCELLED,
    by: "sidecar",
    kinds: EVERY_KIND,
    when: "its connection was revoked",
  },
  {
    from: PENDING,
    to: EXPIRED,
    by: "sidecar",
    kinds: EVERY_KIND,
    when: "expires_at passed",
  },
  {
    from: PROCESSING,
    to: COMPLETED,
    by: "phone",
    kinds: MESSAGE_KINDS,
    when: "the wallet signed the message",
  },
  {
    from: PROCESSING,
    to: SUBMITTED,
    by: "phone",
    kinds: TRANSACTION_KINDS,
    when: "the wallet sent the transaction",
  },
  {
    from: PROCESSING,
    to: SUBMITTED,
    by: "sidecar",
    kinds: TRANSACTION_KINDS,
    when: "the sidecar found the approved transaction on chain",
  },
  {
    from: PROCESSING,
    to: REJECTED,
    by: "phone",
    kinds: WALLET_KINDS,
    when: "the user declined in the wallet",
  },
  {
    from: PROCESSING,
    to: FAILED,
    by: "phone",
    kinds: WALLET_KINDS,
    when: "the wallet failed before signing or sending",
  },
  {
    from: PROCESSING,
    to: FAILED,
    by: "sidecar",
    kinds: TRANSACTION_KINDS,
    when: "the approved transaction's blockhash expired, and it never landed",
  },
  {
    from: PROCESSING,
    to: UNKNOWN,
    by: "phone",
    kinds: WALLET_KINDS,
    when: "the phone lost track of the wallet",
  },
  {
    from: PROCESSING,
    to: UNKNOWN,
    by: "sidecar",
    kinds: WALLET_KINDS,
    when: "no report arrived in time, and the chain doesn't settle it",
  },
  {
    from: SUBMITTED,
    to: CONFIRMED,
    by: "sidecar",
    kinds: TRANSACTION_KINDS,
    when: "the transaction succeeded on chain",
  },
  {
    from: SUBMITTED,
    to: FAILED,
    by: "sidecar",
    kinds: TRANSACTION_KINDS,
    when: "the transaction failed on chain, or its blockhash expired before it landed",
  },
  {
    from: UNKNOWN,
    to: COMPLETED,
    by: "phone",
    kinds: MESSAGE_KINDS,
    when: "a late report delivered the signature",
  },
  {
    from: UNKNOWN,
    to: SUBMITTED,
    by: "phone",
    kinds: TRANSACTION_KINDS,
    when: "a late report delivered the transaction's signature",
  },
  {
    from: UNKNOWN,
    to: SUBMITTED,
    by: "sidecar",
    kinds: TRANSACTION_KINDS,
    when: "the sidecar found the approved transaction on chain",
  },
  {
    from: UNKNOWN,
    to: REJECTED,
    by: "phone",
    kinds: WALLET_KINDS,
    when: "a late report says the user declined in the wallet",
  },
  {
    from: UNKNOWN,
    to: FAILED,
    by: "phone",
    kinds: WALLET_KINDS,
    when: "a late report says the wallet failed before signing or sending",
  },
  {
    from: UNKNOWN,
    to: FAILED,
    by: "sidecar",
    kinds: TRANSACTION_KINDS,
    when: "the approved transaction's blockhash expired, and it never landed",
  },
];

const TERMINAL: ReadonlySet<RequestState> = new Set([
  CONFIRMED,
  COMPLETED,
  REJECTED,
  CANCELLED,
  EXPIRED,
  FAILED,
]);

/**
 * The results the phone can submit: the states each one applies in, and the state it moves the
 * request to. TRANSITIONS decides which action kinds accept it.
 */
const RESULTS: Readonly<
  Record<
    ResultCase,
    { readonly from: readonly RequestState[]; readonly to: RequestState }
  >
> = {
  acknowledgement: { from: [PENDING], to: COMPLETED },
  rejection: { from: [PENDING, PROCESSING, UNKNOWN], to: REJECTED },
  approval: { from: [PENDING], to: PROCESSING },
  messageSignature: { from: [PROCESSING, UNKNOWN], to: COMPLETED },
  transactionSubmission: { from: [PROCESSING, UNKNOWN], to: SUBMITTED },
  executionFailure: { from: [PROCESSING, UNKNOWN], to: FAILED },
  unknownOutcome: { from: [PROCESSING], to: UNKNOWN },
};

/** Whether a request in `state` is finished for good. UNKNOWN isn't: it can still be settled. */
export function isTerminal(state: RequestState): boolean {
  return TERMINAL.has(state);
}

/** The state a request of this kind ends in when it succeeds. */
export function successState(kind: ActionKind): RequestState {
  return TRANSACTION_KINDS.includes(kind) ? CONFIRMED : COMPLETED;
}

/** Whether `by` may move a request of this kind from `from` to `to`. */
export function canTransition(
  kind: ActionKind,
  from: RequestState,
  to: RequestState,
  by: Actor,
): boolean {
  return TRANSITIONS.some(
    (transition) =>
      transition.from === from &&
      transition.to === to &&
      transition.by === by &&
      transition.kinds.includes(kind),
  );
}

/** Where a result moves a request of this kind from `from`, or undefined if it doesn't apply. */
export function resultTarget(
  kind: ActionKind,
  from: RequestState,
  result: ResultCase,
): RequestState | undefined {
  const rule = RESULTS[result];
  return rule.from.includes(from) && canTransition(kind, from, rule.to, "phone")
    ? rule.to
    : undefined;
}

/**
 * Whether a PENDING request has reached its deadline at `nowMs`. The sidecar expires such a
 * request before it applies any other operation to it. The deadline doesn't apply once the user
 * approved.
 */
export function isOverdue(request: ActionRequest, nowMs: number): boolean {
  return request.state === PENDING && isExpired(request.expiresAt, nowMs);
}

/**
 * Says why a submission is malformed in any state: a missing ref or result, or a hash,
 * signature, or detail of the wrong size.
 */
export function invalidSubmissionReason(
  submission: SubmitResultRequest,
): string | undefined {
  const refReason = invalidRefReason(submission.ref);
  if (refReason !== undefined) return refReason;
  const { result } = submission;
  switch (result.case) {
    case undefined:
      return "result is missing";
    case "approval":
      return result.value.contentHash.length === 32
        ? undefined
        : "approval.content_hash must be 32 bytes (SHA-256)";
    case "messageSignature":
    case "transactionSubmission":
      return result.value.signature.length === 64
        ? undefined
        : `${snakeCase(result.case)}.signature must be 64 bytes (Ed25519)`;
    case "executionFailure":
    case "unknownOutcome":
      return invalidNoteReason(
        `${snakeCase(result.case)}.detail`,
        result.value.detail,
      );
    default:
      return undefined;
  }
}

export type ResultDecision =
  | { readonly ok: true; readonly to: RequestState }
  | {
      readonly ok: false;
      readonly error:
        | RequestError.INVALID_PARAMETERS
        | RequestError.INVALID_STATE
        | RequestError.STALE_PREPARATION;
      readonly message: string;
    };

/**
 * Checks a result from the phone against the stored request it names, and says which state it
 * moves the request to. `latest` is the request's newest PreparedTransaction, for transfers and
 * swaps.
 *
 * Callers do what this doesn't:
 * - expire an overdue request first (`isOverdue`)
 * - answer a repeat of a result they already accepted with the request as it is (SAW-010 keeps the
 *   accepted results)
 */
export function decideResult(
  request: ActionRequest,
  submission: SubmitResultRequest,
  latest?: PreparedTransaction,
  nowMs: number = Date.now(),
): ResultDecision {
  const action = request.action;
  const kind = action?.kind.case;
  if (action === undefined || kind === undefined) {
    throw new Error("a stored request always has an action");
  }
  const { result } = submission;
  const reason = invalidSubmissionReason(submission);
  if (reason !== undefined || result.case === undefined) {
    return {
      ok: false,
      error: RequestError.INVALID_PARAMETERS,
      message: reason ?? "result is missing",
    };
  }
  const to = resultTarget(kind, request.state, result.case);
  if (to === undefined) {
    return {
      ok: false,
      error: RequestError.INVALID_STATE,
      message: `${snakeCase(result.case)} doesn't apply to ${snakeCase(kind)} requests in state ${RequestState[request.state]}`,
    };
  }
  if (result.case === "approval") {
    const mismatch = approvalMismatch(action, result.value, latest, nowMs);
    if (mismatch !== undefined) return mismatch;
  }
  if (
    result.case === "messageSignature" &&
    action.kind.case === "signMessage"
  ) {
    // The sidecar holds no key and signs nothing; it only checks that the wallet the request
    // names signed exactly the bytes the request stores (docs/protocol.md#message-results).
    const signed = action.kind.value;
    if (
      !verifySignature(
        signed.wallet,
        messageBytes(signed),
        result.value.signature,
      )
    ) {
      return {
        ok: false,
        error: RequestError.INVALID_PARAMETERS,
        message: `message_signature.signature isn't ${signed.wallet}'s signature of this request's message`,
      };
    }
  }
  return { ok: true, to };
}

/** Whether the approval names exactly what the wallet will sign, and still can. */
function approvalMismatch(
  action: Action,
  approval: Approval,
  latest: PreparedTransaction | undefined,
  nowMs: number,
): ResultDecision | undefined {
  if (action.kind.case === "signMessage") {
    const hash = createHash("sha256")
      .update(messageBytes(action.kind.value))
      .digest();
    return approval.preparedVersion === 0 &&
      Buffer.compare(approval.contentHash, hash) === 0
      ? undefined
      : {
          ok: false,
          error: RequestError.INVALID_PARAMETERS,
          message:
            "a message approval needs prepared_version 0 and the SHA-256 of the message as content_hash",
        };
  }
  if (latest === undefined) {
    return stale("nothing has been prepared for this request yet");
  }
  if (approval.preparedVersion !== latest.version) {
    return stale(
      `the approval is for version ${approval.preparedVersion}, but the latest prepared version is ${latest.version}`,
    );
  }
  if (Buffer.compare(approval.contentHash, latest.contentHash) !== 0) {
    return stale(
      "the approval's content_hash isn't the latest prepared transaction's",
    );
  }
  // The wallet is invoked right after the sidecar accepts the approval, so an approval that
  // arrives with almost no window left would have it sign something that can no longer land.
  const expiry = latest.estimatedExpiry;
  if (
    expiry !== undefined &&
    timestampMs(expiry) - nowMs < APPROVAL_MARGIN_MS
  ) {
    return stale(
      "the prepared transaction's blockhash has expired or is about to; prepare the request again and have the owner review the new version",
    );
  }
  return undefined;
}

/**
 * Why a request can't be prepared now, or undefined when it can. Only a PENDING transfer or swap
 * has a transaction to build, and only while the owner can still decide: a request they have
 * already answered must never get a new version to approve.
 */
export function unpreparableReason(
  request: ActionRequest,
): { readonly error: RequestError; readonly message: string } | undefined {
  const kind = request.action?.kind.case;
  if (!TRANSACTION_KINDS.includes(kind as ActionKind)) {
    return {
      error: RequestError.INVALID_PARAMETERS,
      message: `${kindName(kind)} requests have nothing to prepare`,
    };
  }
  if (request.state !== PENDING) {
    return {
      error: RequestError.INVALID_STATE,
      message: `only a PENDING request can be prepared; this one is ${RequestState[request.state]}`,
    };
  }
  return undefined;
}

function stale(message: string): ResultDecision {
  return { ok: false, error: RequestError.STALE_PREPARATION, message };
}

function snakeCase(name: string): string {
  return name.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
}
