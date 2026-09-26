/**
 * Preparing a stored request for review (docs/protocol.md#preparation). The phone asks when the
 * owner opens a request; the sidecar reads the chain, builds a fresh unsigned transaction, and
 * records it as the request's next version.
 *
 * Building and storing are deliberately separate: reading the chain takes time and can fail, and
 * the database transaction that numbers the version must not wait on it. A preparation that fails
 * leaves the request exactly as it was, still PENDING and still preparable.
 */
import {
  RequestError,
  type ActionRequest,
  type Asset,
  type PreparedTransaction,
  type RequestRef,
  type StakingAction,
} from "../gen/seekervault/request/v1/request_pb.js";
import {
  ProviderUnavailable,
  UnsupportedPreparation,
  withProviderBudget,
  type BuiltPreparation,
  type StakingProvider,
  type TransferProvider,
} from "../providers.ts";
import type { RequestStore } from "../storage/request-store.ts";
import { RequestFailure } from "./failure.ts";
import { unpreparableReason } from "./lifecycle.ts";

export class TransactionPreparer {
  readonly #store: RequestStore;
  readonly #provider: TransferProvider | undefined;
  readonly #staking: StakingProvider | undefined;
  readonly #now: () => number;

  constructor(
    store: RequestStore,
    providers: {
      readonly transfers?: TransferProvider;
      readonly staking?: StakingProvider;
    },
    now?: () => number,
  ) {
    this.#store = store;
    this.#provider = providers.transfers;
    this.#staking = providers.staking;
    this.#now = now ?? (() => Date.now());
  }

  /** Whether this sidecar was given a way to build a transfer. */
  get servesTransfers(): boolean {
    return this.#provider !== undefined;
  }

  /** Whether this sidecar was given a way to build a staking action. */
  get servesStaking(): boolean {
    return this.#staking !== undefined;
  }

  /**
   * Checks that an agent's transfer names an asset this sidecar can send, before the request is
   * stored. Native SOL reads nothing; a token mint is read once.
   */
  async checkAsset(asset: Asset | undefined): Promise<void> {
    const provider = this.#requireTransfers();
    try {
      await withProviderBudget(() => provider.checkAsset(asset));
    } catch (error) {
      throw chainFailure(error);
    }
  }

  /**
   * Checks that an agent's staking action is one this sidecar can act on, before the request is
   * stored: the right network, a position that allows it, an amount the program would take.
   */
  async checkStaking(action: StakingAction): Promise<void> {
    const provider = this.#requireStaking(
      undefined,
      "act on no staking position",
    );
    try {
      await withProviderBudget(() => provider.checkStaking(action));
    } catch (error) {
      throw chainFailure(error);
    }
  }

  /**
   * Builds and stores the request's next prepared transaction. Nothing is signed and nothing is
   * sent: the bytes carry empty signature slots, and only the owner's wallet can fill them.
   *
   * Which provider builds it follows from the action, so a host that supplied one of them is never
   * asked to do the other one's work.
   */
  async prepareTransaction(
    connectionId: string,
    ref: RequestRef | undefined,
  ): Promise<PreparedTransaction> {
    const request = this.#store.getForConnection(connectionId, ref);
    const reason = unpreparableReason(request);
    if (reason !== undefined) {
      throw new RequestFailure(reason.error, reason.message, request);
    }
    const kind = request.action?.kind;
    const build = this.#builderFor(kind, request);
    try {
      // The whole build runs under one budget: the phone waits on a single unary RPC for it, and
      // a preparation it never sees must not be built or stored (solana/rpc.ts).
      const built = await withProviderBudget(build);
      return this.#store.storePrepared(connectionId, ref, built);
    } catch (error) {
      throw chainFailure(error, request);
    }
  }

  #builderFor(
    kind: NonNullable<ActionRequest["action"]>["kind"] | undefined,
    request: ActionRequest,
  ): () => Promise<BuiltPreparation> {
    if (kind?.case === "transfer") {
      const provider = this.#requireTransfers(request);
      return () => provider.buildTransfer(kind.value, this.#now());
    }
    if (kind?.case === "staking") {
      const provider = this.#requireStaking(request);
      return () => provider.buildStaking(kind.value, this.#now());
    }
    // `unpreparableReason` has already refused every kind with nothing to prepare, so this is a
    // preparable kind no provider was supplied for — a swap, which Stage 6 prepares elsewhere.
    throw new RequestFailure(
      RequestError.INVALID_PARAMETERS,
      `this sidecar prepares no ${kind?.case === "swap" ? "swap" : "such"} request`,
      request,
    );
  }

  #requireTransfers(request?: ActionRequest): TransferProvider {
    if (this.#provider === undefined) {
      throw new RequestFailure(
        RequestError.CHAIN_UNAVAILABLE,
        "this sidecar has no transfer provider configured, so it can prepare no transfer",
        request,
      );
    }
    return this.#provider;
  }

  #requireStaking(
    request?: ActionRequest,
    cannot = "prepare no staking action",
  ): StakingProvider {
    if (this.#staking === undefined) {
      throw new RequestFailure(
        RequestError.CHAIN_UNAVAILABLE,
        `this sidecar has no staking provider configured, so it can ${cannot}`,
        request,
      );
    }
    return this.#staking;
  }
}

/**
 * A chain problem as a RequestFailure. A refusal is the request's own (INVALID_PARAMETERS), and an
 * endpoint that didn't answer is not (CHAIN_UNAVAILABLE), so an agent can tell what to retry.
 * Anything else is a bug here and is rethrown as it is.
 */
export function chainFailure(error: unknown, request?: ActionRequest): unknown {
  if (error instanceof UnsupportedPreparation) {
    return new RequestFailure(
      RequestError.INVALID_PARAMETERS,
      error.message,
      request,
    );
  }
  if (error instanceof ProviderUnavailable) {
    return new RequestFailure(
      RequestError.CHAIN_UNAVAILABLE,
      error.message,
      request,
    );
  }
  return error;
}
