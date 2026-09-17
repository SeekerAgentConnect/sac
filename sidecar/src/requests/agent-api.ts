/**
 * What an adapter may ask of the request core (SEE-87, docs/wiki/mcp-adapter.md).
 *
 * MCP is one way an agent reaches this sidecar, not what the sidecar is. Everything an adapter
 * needs is named here, and it is a short list on purpose: store a request, read one, withdraw one,
 * answer a retry, read the owner's wallet binding, and — only when a chain endpoint is configured —
 * check an asset before storing a transfer and read what became of a submitted transaction.
 *
 * The boundary forwards; it decides nothing. Idempotency, parameter validation, the lifecycle's
 * allowed transitions, the pending limit and result handling all stay in
 * {@link ../storage/request-store.ts | RequestStore}, {@link ./lifecycle.ts} and
 * {@link ./action.ts}, so an adapter cannot reach around them or relax them. Nothing here
 * authenticates anybody: a credential belongs to the adapter that accepts it, and `/mcp`'s token
 * and OAuth checks stay in `mcp-endpoint.ts`.
 *
 * It also holds no connection or credential of its own, opens no socket, and is not a second store:
 * one is constructed per process over the store the sidecar already opened.
 */
import type { Asset } from "../gen/seekervault/request/v1/request_pb.js";
import type {
  ActionRequest,
  Action,
  WalletBinding,
} from "../gen/seekervault/request/v1/request_pb.js";
import type { ConfirmationTracker } from "./confirmation.ts";
import type { TransactionPreparer } from "./preparation.ts";
import type {
  Created,
  NewRequest,
  RequestStore,
} from "../storage/request-store.ts";

/**
 * Preparing a transfer, when the sidecar has a chain endpoint to prepare one against. Absent
 * otherwise, which is how an adapter learns not to offer a transfer at all rather than accepting a
 * request it could never prepare (SAW-019).
 */
export interface AgentTransfers {
  /**
   * Whether the asset a transfer names is one this sidecar can build for. It reads the chain and
   * stores nothing, so an adapter asks it before it stores a request — never after.
   */
  checkAsset(asset: Asset | undefined): Promise<void>;
}

/**
 * Reading what became of a transaction the owner's wallet sent (SAW-022). Absent when no endpoint
 * is configured, and then nothing anywhere claims to know.
 */
export interface AgentConfirmations {
  /** The host the answer came from, which a capability report discloses. Never the full URL. */
  readonly endpoint: string;

  /**
   * Settles [request] from the chain, or returns it exactly as it was. It is a read: it never
   * builds, signs, sends, or replaces a transaction.
   */
  settle(request: ActionRequest): Promise<ActionRequest>;
}

/** The core operations an adapter may use, and the whole of them. */
export interface AgentRequests {
  /**
   * Stores a new request for the paired connection, or answers a retry with the original. Storing
   * is not approval: the owner decides later, on their phone.
   */
  create(request: NewRequest): Created;

  /** The request as it stands now. */
  get(requestId: string): ActionRequest;

  /** Withdraws a PENDING request. Any other state fails rather than being forced. */
  cancel(requestId: string): ActionRequest;

  /**
   * The request an idempotency key already stands for, or undefined when the key is free. A tool
   * that reads the chain before storing asks this first, so a retry is answered with the original
   * whether or not an endpoint is reachable.
   */
  replayOf(idempotencyKey: string, action: Action): ActionRequest | undefined;

  /** The paired phone's wallet binding, or undefined when there is no phone or no wallet. */
  connectedWallet(): WalletBinding | undefined;

  /** The paired phone's wallet binding, or a failure naming which of the two is missing. */
  activeWallet(): WalletBinding;

  /** The most PENDING requests one connection may have, for a capability report. */
  readonly pendingLimit: number;

  /** Absent when no chain endpoint is configured, and then no transfer is served. */
  readonly transfers?: AgentTransfers;

  /** Absent when no chain endpoint is configured, and then nothing is confirmed on chain. */
  readonly confirmations?: AgentConfirmations;
}

export interface AgentRequestsOptions {
  /** Present exactly when SOLANA_RPC_URL is configured. */
  readonly preparer?: TransactionPreparer;
  /** Present exactly when SOLANA_RPC_URL is configured. */
  readonly tracker?: ConfirmationTracker;
}

/**
 * One boundary over the core the sidecar already opened.
 *
 * The forwarding is deliberately literal — no caching, no retry, no rewriting of a failure. A
 * `RequestFailure` an adapter sees is the one the core raised, which is what keeps the agent-facing
 * error codes the same whichever adapter is asking (docs/protocol.md#errors).
 */
export function agentRequests(
  store: RequestStore,
  options: AgentRequestsOptions = {},
): AgentRequests {
  const { preparer, tracker } = options;
  return {
    create: (request) => store.create(request),
    get: (requestId) => store.get(requestId),
    cancel: (requestId) => store.cancel(requestId),
    replayOf: (idempotencyKey, action) =>
      store.replayOf(idempotencyKey, action),
    connectedWallet: () => store.connectedWallet(),
    activeWallet: () => store.activeWallet(),
    get pendingLimit() {
      return store.pendingLimit;
    },
    ...(preparer === undefined
      ? {}
      : {
          transfers: {
            checkAsset: (asset) => preparer.checkAsset(asset),
          },
        }),
    ...(tracker === undefined
      ? {}
      : {
          confirmations: {
            endpoint: tracker.endpoint,
            settle: (request) => tracker.settle(request),
          },
        }),
  };
}
