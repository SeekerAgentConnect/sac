/**
 * Asking the chain what became of a submitted transaction (docs/protocol.md#confirmation).
 * SAW-021 ends at SUBMITTED: the wallet said it sent something and named its signature. This
 * finds out whether that signature succeeded, failed, or never ran — and says so only when the
 * transaction under it is the one the owner approved.
 *
 * The sidecar has no background worker, so nothing here runs on its own. A check happens when the
 * agent reads the request (`vault_get_request`) or the owner asks the phone to check
 * (`RequestService.CheckStatus`). Nothing here signs, sends, or resubmits: the only thing it can
 * do to a request is record what one endpoint said.
 */
import { create } from "@bufbuild/protobuf";
import { timestampFromMs, timestampMs } from "@bufbuild/protobuf/wkt";

import {
  ConfirmationLevel,
  ConfirmationSchema,
  Network,
  RequestState,
  type ActionRequest,
  type Confirmation,
  type PreparedTransaction,
} from "../gen/seekervault/request/v1/request_pb.js";
import { isApprovedTransaction } from "../solana/confirmation.ts";
import {
  ChainUnavailable,
  withChainBudget,
  type ChainReader,
  type SignatureStatus,
} from "../solana/rpc.ts";
import { UnsupportedTransfer, assertNetwork } from "../solana/transfer.ts";
import type { RequestStore } from "../storage/request-store.ts";
import { encodeBase58 } from "./action.ts";

/**
 * The shortest gap between two chain checks of the same request. An agent polling
 * `vault_get_request` gets the stored answer in between, rather than an endpoint call per poll.
 * The owner's own check ignores it: they asked.
 */
export const CONFIRMATION_CHECK_INTERVAL_MS = 2000;

const { SUBMITTED, CONFIRMED, FAILED, UNKNOWN } = RequestState;

/**
 * What an UNKNOWN transfer is told. The phone lost the wallet before it reported anything, so
 * there is no signature to look up and no amount of checking will produce one. It stays UNKNOWN,
 * which is what it is, and nothing here builds a replacement.
 */
const NOTHING_TO_LOOK_UP: Finding = {
  level: ConfirmationLevel.UNSPECIFIED,
  slot: 0n,
  chainError: "",
  matchesApproval: false,
  detail:
    "The wallet never reported a signature, so there is nothing to look up on chain. Whether it sent the transaction can only be seen in the wallet's own history, and nothing here will send it again.",
};

/** What one look at the chain found. `to` is where it leaves the request, if anywhere. */
interface Finding {
  readonly level: ConfirmationLevel;
  readonly slot: bigint;
  readonly chainError: string;
  readonly matchesApproval: boolean;
  /** Confirmation.detail: what was found, including why it settled nothing. */
  readonly detail: string;
  readonly to?: RequestState;
  /** Outcome.detail, when it settles the request. */
  readonly outcomeDetail?: string;
}

export interface ConfirmationTrackerOptions {
  /** The clock, in epoch milliseconds. Tests replace it. */
  readonly now?: () => number;
  /** The shortest gap between two checks of the same request. */
  readonly intervalMs?: number;
}

export class ConfirmationTracker {
  readonly #store: RequestStore;
  readonly #rpc: ChainReader;
  readonly #endpoint: string;
  readonly #now: () => number;
  readonly #intervalMs: number;

  /**
   * `endpointUrl` is only ever reduced to its host: the configured URL can carry an API key, and
   * what the agent and the owner are told is which host's word a result rests on.
   */
  constructor(
    store: RequestStore,
    rpc: ChainReader,
    endpointUrl: string,
    options: ConfirmationTrackerOptions = {},
  ) {
    this.#store = store;
    this.#rpc = rpc;
    this.#endpoint = hostOf(endpointUrl);
    this.#now = options.now ?? (() => Date.now());
    this.#intervalMs = options.intervalMs ?? CONFIRMATION_CHECK_INTERVAL_MS;
  }

  /** The host whose word a confirmed or failed transfer rests on. */
  get endpoint(): string {
    return this.#endpoint;
  }

  /**
   * Brings `request` up to date from the chain, and returns it as it is afterwards. A request
   * with nothing on chain to look up, or one that has finished, comes back untouched.
   *
   * `force` is the owner asking in person: it checks however recently the last one ran.
   */
  async settle(request: ActionRequest, force = false): Promise<ActionRequest> {
    if (!checkable(request)) return request;
    if (!force && this.#checkedRecently(request)) return request;
    const requestId = request.ref?.requestId ?? "";
    const checks = (request.outcome?.confirmation?.checks ?? 0) + 1;
    const signature = request.outcome?.signature ?? new Uint8Array();
    // An UNKNOWN transfer is one the phone lost track of before the wallet answered, so no
    // signature was ever reported and there is nothing to look up. Saying that is the answer.
    if (request.state !== SUBMITTED || signature.length !== 64) {
      return this.#store.recordConfirmation(requestId, {
        from: request.state,
        confirmation: this.#confirmation(NOTHING_TO_LOOK_UP, checks),
      });
    }

    let finding: Finding;
    try {
      finding = await withChainBudget(() =>
        this.#lookOnTheRequestsChain(request, signature),
      );
    } catch (error) {
      if (error instanceof UnsupportedTransfer) {
        // The endpoint serves another cluster, so nothing it says is about this transaction at
        // all: not its status, not the transaction under it, and above all not the block height,
        // which belongs to a chain this transfer was never sent to. Nothing moves.
        finding = {
          ...unchanged(request),
          detail: `The status couldn't be checked: ${error.message}. Nothing on that cluster is evidence about this transfer, so nothing here has been settled.`,
        };
      } else if (error instanceof ChainUnavailable) {
        // An endpoint that didn't answer is not evidence about the transaction. Nothing moves, and
        // the attempt is kept so the owner can see that it was tried and what stopped it.
        finding = {
          ...unchanged(request),
          detail: `The status couldn't be checked: ${error.message}. This says nothing about the transaction itself.`,
        };
      } else {
        throw error;
      }
    }

    return this.#store.recordConfirmation(requestId, {
      from: SUBMITTED,
      to: finding.to,
      confirmation: this.#confirmation(finding, checks),
      detail: finding.outcomeDetail,
    });
  }

  /**
   * Checks that the configured endpoint serves the cluster this request is bound to, and only
   * then reads anything from it.
   *
   * The database outlives the process, and `SOLANA_RPC_URL` does not: a restart can point the
   * same stored requests at another cluster. On that cluster the signature is absent and the
   * block height is somebody else's, which together read exactly like "the transaction expired
   * and nothing was spent" — a settled, terminal answer, and a false one. So the cluster is
   * established first, from the genesis hash, the same way a preparation establishes it
   * (solana/transfer.ts). A mismatch throws, and the caller settles nothing.
   */
  async #lookOnTheRequestsChain(
    request: ActionRequest,
    signature: Uint8Array,
  ): Promise<Finding> {
    await assertNetwork(this.#rpc, boundNetwork(request));
    return this.#look(
      encodeBase58(signature),
      this.#approvedTransaction(request),
    );
  }

  /** What the endpoint says about `signature`, read as a move for a SUBMITTED request. */
  async #look(
    signature: string,
    approved: PreparedTransaction | undefined,
  ): Promise<Finding> {
    const status = await this.#rpc.signatureStatus(signature, false);
    if (status === undefined) return this.#absent(signature, approved);
    if (status.commitment === "processed") {
      return {
        level: ConfirmationLevel.PROCESSED,
        slot: status.slot,
        chainError: status.chainError ?? "",
        matchesApproval: false,
        detail:
          "A node has processed the transaction but no supermajority has voted on its block yet, so this isn't a result.",
      };
    }
    return this.#seen(signature, status, approved);
  }

  /** The endpoint has a confirmed or finalized status: check that the transaction is ours. */
  async #seen(
    signature: string,
    status: SignatureStatus,
    approved: PreparedTransaction | undefined,
  ): Promise<Finding> {
    const level =
      status.commitment === "finalized"
        ? ConfirmationLevel.FINALIZED
        : ConfirmationLevel.CONFIRMED;
    const chainError = status.chainError ?? "";
    if (approved === undefined) {
      return {
        level,
        slot: status.slot,
        chainError,
        matchesApproval: false,
        detail:
          "This sidecar no longer holds the transaction the owner approved, so it can't tell whether what is on chain under this signature is it.",
      };
    }
    const onChain = await this.#rpc.confirmedTransaction(signature);
    if (onChain === undefined) {
      return {
        level,
        slot: status.slot,
        chainError,
        matchesApproval: false,
        detail:
          "The endpoint has a status for this signature but hasn't served the transaction itself yet, so it isn't checked against the approved one.",
      };
    }
    if (!isApprovedTransaction(approved.transaction, onChain.transaction)) {
      // The signature names something else, so this settles nothing: it is not evidence that the
      // approved transaction ran, and it is not evidence that it didn't. The request stays where
      // it is — SUBMITTED already means "sent, not settled" — and says why, rather than being
      // presented as a success or a failure. A state never moves backward here, so there is no
      // move to make; what there is to say goes in the confirmation.
      return {
        level,
        slot: onChain.slot,
        chainError: onChain.chainError ?? chainError,
        matchesApproval: false,
        detail:
          "The transaction on chain under this signature isn't the one the owner approved, so this says nothing about the approved transaction. Look the signature up on an explorer before assuming anything, and don't send a replacement.",
      };
    }
    const failure = onChain.chainError ?? chainError;
    if (failure !== "") {
      return {
        level,
        slot: onChain.slot,
        chainError: failure,
        matchesApproval: true,
        detail: "The transaction ran on chain and failed.",
        to: FAILED,
        outcomeDetail: `The transaction ran on chain and failed: ${failure}`,
      };
    }
    return {
      level,
      slot: onChain.slot,
      chainError: "",
      matchesApproval: true,
      detail: `The approved transaction succeeded on chain in slot ${onChain.slot}, as ${this.#endpoint} reports it.`,
      to: CONFIRMED,
      outcomeDetail: `The transfer succeeded on chain in slot ${onChain.slot}.`,
    };
  }

  /**
   * The endpoint has no status. That alone is never proof: a signature drops out of the status
   * cache after a while. Only once the approved transaction's blockhash window has passed, and a
   * search of the ledger itself still finds nothing, can it no longer land.
   */
  async #absent(
    signature: string,
    approved: PreparedTransaction | undefined,
  ): Promise<Finding> {
    const absent = {
      level: ConfirmationLevel.NOT_FOUND,
      slot: 0n,
      chainError: "",
      matchesApproval: false,
    } as const;
    if (approved === undefined) {
      return {
        ...absent,
        detail:
          "The endpoint has no status for this signature, and this sidecar no longer holds the approved transaction to say whether it could still land.",
      };
    }
    const height = await this.#rpc.blockHeight();
    if (height <= approved.lastValidBlockHeight) {
      return {
        ...absent,
        detail: `The endpoint has no status for this signature yet. The transaction can still land: the chain is at block ${height}, and it is valid through ${approved.lastValidBlockHeight}.`,
      };
    }
    const searched = await this.#rpc.signatureStatus(signature, true);
    if (searched !== undefined) {
      return this.#seen(signature, searched, approved);
    }
    return {
      ...absent,
      detail: `The endpoint has no record of this signature, in its cache or its ledger, and the approved transaction could only land through block ${approved.lastValidBlockHeight}.`,
      to: FAILED,
      outcomeDetail:
        "The transaction never landed, and its blockhash has expired, so it never can. Nothing was spent.",
    };
  }

  #confirmation(finding: Finding, checks: number): Confirmation {
    return create(ConfirmationSchema, {
      level: finding.level,
      slot: finding.slot,
      chainError: finding.chainError,
      checkedAt: timestampFromMs(this.#now()),
      checks,
      endpoint: this.#endpoint,
      matchesApproval: finding.matchesApproval,
      detail: finding.detail,
    });
  }

  #checkedRecently(request: ActionRequest): boolean {
    const checkedAt = request.outcome?.confirmation?.checkedAt;
    if (checkedAt === undefined) return false;
    return this.#now() - timestampMs(checkedAt) < this.#intervalMs;
  }

  /** The exact version the owner approved, which is what the chain's copy is compared with. */
  #approvedTransaction(
    request: ActionRequest,
  ): PreparedTransaction | undefined {
    const version = request.outcome?.approval?.preparedVersion;
    if (version === undefined || version === 0) return undefined;
    return this.#store.preparedVersion(request.ref?.requestId ?? "", version);
  }
}

/**
 * The network the request is bound to. Its action names one, and the action never changes, so
 * this is what the endpoint has to be serving for anything it says to be about this request.
 */
function boundNetwork(request: ActionRequest): Network {
  const kind = request.action?.kind;
  if (kind?.case === "transfer" || kind?.case === "swap")
    return kind.value.network;
  return Network.UNSPECIFIED;
}

/** The confirmation a request already has, for a check that established nothing new. */
function unchanged(request: ActionRequest): Omit<Finding, "detail"> {
  const known = request.outcome?.confirmation;
  return {
    level: known?.level ?? ConfirmationLevel.UNSPECIFIED,
    slot: known?.slot ?? 0n,
    chainError: known?.chainError ?? "",
    matchesApproval: known?.matchesApproval ?? false,
  };
}

/**
 * Whether a check has anything to say about this request: an unfinished transfer or swap. A
 * message has nothing on chain, and a finished request is finished. A SUBMITTED one has a
 * signature to look up; an UNKNOWN one hasn't, and is told so.
 */
export function checkable(request: ActionRequest): boolean {
  const kind = request.action?.kind.case;
  if (kind !== "transfer" && kind !== "swap") return false;
  return request.state === SUBMITTED || request.state === UNKNOWN;
}

/** The endpoint's host, and only its host: the configured URL can carry an API key. */
function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return "";
  }
}
