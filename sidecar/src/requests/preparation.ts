/**
 * Preparing a stored transfer for review (docs/protocol.md#preparation). The phone asks when the
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
} from "../gen/seekervault/request/v1/request_pb.js";
import { ChainUnavailable, type ChainReader } from "../solana/rpc.ts";
import {
  UnsupportedTransfer,
  assertSupportedAsset,
  buildTransfer,
} from "../solana/transfer.ts";
import type { RequestStore } from "../storage/request-store.ts";
import { RequestFailure } from "./failure.ts";
import { unpreparableReason } from "./lifecycle.ts";

export class TransactionPreparer {
  readonly #store: RequestStore;
  readonly #rpc: ChainReader;
  readonly #now: () => number;

  constructor(store: RequestStore, rpc: ChainReader, now?: () => number) {
    this.#store = store;
    this.#rpc = rpc;
    this.#now = now ?? (() => Date.now());
  }

  /**
   * Checks that an agent's transfer names an asset this sidecar can send, before the request is
   * stored. Native SOL reads nothing; a token mint is read once.
   */
  async checkAsset(asset: Asset | undefined): Promise<void> {
    try {
      await assertSupportedAsset(this.#rpc, asset);
    } catch (error) {
      throw chainFailure(error);
    }
  }

  /**
   * Builds and stores the request's next prepared transaction. Nothing is signed and nothing is
   * sent: the bytes carry empty signature slots, and only the owner's wallet can fill them.
   */
  async prepareTransfer(
    connectionId: string,
    ref: RequestRef | undefined,
  ): Promise<PreparedTransaction> {
    const request = this.#store.getForConnection(connectionId, ref);
    const reason = unpreparableReason(request);
    if (reason !== undefined) {
      throw new RequestFailure(reason.error, reason.message, request);
    }
    const kind = request.action?.kind;
    if (kind?.case !== "transfer") {
      throw new RequestFailure(
        RequestError.INVALID_PARAMETERS,
        "only transfers are prepared in Stage 4",
        request,
      );
    }
    try {
      const built = await buildTransfer(this.#rpc, kind.value, this.#now());
      return this.#store.storePrepared(connectionId, ref, built);
    } catch (error) {
      throw chainFailure(error, request);
    }
  }
}

/**
 * A chain problem as a RequestFailure. A refusal is the request's own (INVALID_PARAMETERS), and an
 * endpoint that didn't answer is not (CHAIN_UNAVAILABLE), so an agent can tell what to retry.
 * Anything else is a bug here and is rethrown as it is.
 */
export function chainFailure(error: unknown, request?: ActionRequest): unknown {
  if (error instanceof UnsupportedTransfer) {
    return new RequestFailure(
      RequestError.INVALID_PARAMETERS,
      error.message,
      request,
    );
  }
  if (error instanceof ChainUnavailable) {
    return new RequestFailure(
      RequestError.CHAIN_UNAVAILABLE,
      error.message,
      request,
    );
  }
  return error;
}
