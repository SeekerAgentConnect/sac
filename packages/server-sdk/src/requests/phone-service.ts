/**
 * The phone's durable API: RequestService over Connect. Every call needs the credential the phone
 * got from pairing (PairingService), and acts for that credential's connection. Every RPC is
 * unary, and every error carries a RequestErrorDetail (docs/protocol.md).
 */
import {
  Code,
  ConnectError,
  type ConnectRouter,
  type HandlerContext,
} from "@connectrpc/connect";

import { bearerToken } from "../phone-auth.ts";
import {
  RequestError,
  RequestErrorDetailSchema,
  RequestState,
} from "../gen/seekervault/request/v1/request_pb.js";
import { RequestService } from "../gen/seekervault/request/v1/service_pb.js";
import type { SolanaNetwork } from "../gen/seekervault/server/v1/manifest_pb.js";
import { solanaNetworkName } from "../manifest.ts";
import type { PairingStore } from "../storage/pairing-store.ts";
import { networkName, type RequestStore } from "../storage/request-store.ts";
import { checkable, type ConfirmationTracker } from "./confirmation.ts";
import { RequestFailure } from "./failure.ts";
import { kindName } from "./lifecycle.ts";
import type { TransactionPreparer } from "./preparation.ts";

const CODES: ReadonlyMap<RequestError, Code> = new Map([
  [RequestError.INVALID_PARAMETERS, Code.InvalidArgument],
  [RequestError.NOT_FOUND, Code.NotFound],
  [RequestError.INVALID_STATE, Code.FailedPrecondition],
  [RequestError.STALE_PREPARATION, Code.FailedPrecondition],
  [RequestError.UNAUTHENTICATED, Code.Unauthenticated],
  [RequestError.CHAIN_UNAVAILABLE, Code.Unavailable],
]);

export function requestRoutes(
  store: RequestStore,
  pairing: PairingStore,
  log: (message: string) => void,
  /** Absent when no Solana RPC endpoint is configured, which is when transfers aren't served. */
  preparer?: TransactionPreparer,
  /** Absent for the same reason: with no endpoint there is no chain to check a signature on. */
  tracker?: ConfirmationTracker,
  /**
   * The networks the manifest declares (SEE-174), in canonical order, and only for the log: see
   * publishWallet below.
   */
  supportedNetworks: readonly SolanaNetwork[] = [],
): (router: ConnectRouter) => void {
  /**
   * Authenticates the paired phone, then runs `work` for its connection, turning a RequestFailure
   * into a Connect error.
   */
  function handle<T>(
    context: HandlerContext,
    work: (connectionId: string) => T,
  ): T {
    const connectionId = pairing.authenticate(
      bearerToken(context.requestHeader.get("authorization")),
    );
    if (connectionId === undefined) {
      log(
        `rejected ${context.method.name}: missing, wrong, or revoked phone credential`,
      );
      throw connectError(
        new RequestFailure(
          RequestError.UNAUTHENTICATED,
          "a valid phone credential is required; pair the phone first",
        ),
      );
    }
    try {
      return work(connectionId);
    } catch (error) {
      throw error instanceof RequestFailure ? connectError(error) : error;
    }
  }

  /** `handle` for an RPC that awaits: a RequestFailure it rejects with becomes a Connect error. */
  async function handleAsync<T>(
    context: HandlerContext,
    work: (connectionId: string) => Promise<T>,
  ): Promise<T> {
    try {
      return await handle(context, work);
    } catch (error) {
      throw error instanceof RequestFailure ? connectError(error) : error;
    }
  }

  return (router) =>
    router.service(RequestService, {
      listPending: (request, context) =>
        handle(context, (connectionId) =>
          store.listPending(connectionId, request),
        ),

      getRequest: (request, context) =>
        handle(context, (connectionId) => ({
          request: store.getForConnection(connectionId, request.ref),
        })),

      prepareRequest: (request, context) =>
        handleAsync(context, async (connectionId) => {
          const current = store.getForConnection(connectionId, request.ref);
          const kind = current.action?.kind.case;
          if (kind === "swap") {
            throw new ConnectError(
              "swaps are prepared from Stage 6",
              Code.Unimplemented,
            );
          }
          if (kind === "transfer" && preparer?.servesTransfers !== true) {
            throw new RequestFailure(
              RequestError.CHAIN_UNAVAILABLE,
              "this sidecar has no Solana RPC endpoint configured, so it can prepare no transfer; set SOLANA_RPC_URL",
              current,
            );
          }
          if (kind === "staking" && preparer?.servesStaking !== true) {
            throw new RequestFailure(
              RequestError.CHAIN_UNAVAILABLE,
              "this sidecar has no staking provider configured, so it can prepare no staking action",
              current,
            );
          }
          if (
            preparer === undefined ||
            (kind !== "transfer" && kind !== "staking")
          ) {
            throw new RequestFailure(
              RequestError.INVALID_PARAMETERS,
              `${kindName(kind)} requests have nothing to prepare`,
              current,
            );
          }
          const prepared = await preparer.prepareTransaction(
            connectionId,
            request.ref,
          );
          // Addresses and amounts belong to the request, not to the log; only what happened does.
          log(
            `request ${current.ref?.requestId ?? ""}: prepared ${kind} version ${prepared.version}`,
          );
          return { prepared };
        }),

      checkStatus: (request, context) =>
        handleAsync(context, async (connectionId) => {
          const current = store.getForConnection(connectionId, request.ref);
          if (tracker === undefined) {
            throw new RequestFailure(
              RequestError.CHAIN_UNAVAILABLE,
              "this sidecar has no Solana RPC endpoint configured, so it can check nothing on chain; set SOLANA_RPC_URL",
              current,
            );
          }
          if (!checkable(current)) {
            throw new RequestFailure(
              RequestError.INVALID_STATE,
              `there is nothing left to check on chain: the request is ${RequestState[current.state]}`,
              current,
            );
          }
          // The owner asked in person, so this checks however recently the last one ran.
          const checked = await tracker.settle(current, true);
          log(
            `request ${checked.ref?.requestId ?? ""}: checked on chain, now ${RequestState[checked.state]}`,
          );
          return { request: checked };
        }),

      publishWallet: (request, context) =>
        handle(context, (connectionId) => {
          if (request.connectionId !== connectionId) {
            throw new RequestFailure(
              RequestError.NOT_FOUND,
              "no such connection",
            );
          }
          const { binding, cancelled } = store.publishWallet(
            connectionId,
            request.binding,
          );
          log(
            binding === undefined
              ? `connection ${connectionId}: the phone has no wallet connected`
              : // The address is a public key, so it belongs in the log; nothing secret does.
                `connection ${connectionId}: wallet ${binding.wallet} on ${networkName(binding.network)}`,
          );
          // A binding on a network the manifest doesn't declare is stored all the same (SEE-174).
          // A phone that reads supported_networks never offers one, so this is an older phone, and
          // refusing it would take away the wallet it has always published without making anything
          // safer: the network check that matters is the phone's, which signs nothing for a network
          // the server didn't declare. The operator is told, because it is the reason signing on
          // that connection stops once the phone is updated. SolanaNetwork and Network share their
          // numbers by contract, so the two are compared by number.
          if (
            binding !== undefined &&
            !supportedNetworks.some(
              (network) => Number(network) === Number(binding.network),
            )
          ) {
            log(
              `connection ${connectionId}: ${networkName(binding.network)} is not a network this server declares ` +
                `(${supportedNetworks.length === 0 ? "it declares none" : supportedNetworks.map(solanaNetworkName).join(", ")}); ` +
                "an up-to-date phone signs nothing for this connection until it is",
            );
          }
          if (cancelled.length > 0) {
            log(
              `${cancelled.length} pending request(s) cancelled: they no longer fit the owner's wallet`,
            );
          }
          return { binding, cancelled };
        }),

      submitResult: (request, context) =>
        handle(context, (connectionId) => {
          const { request: updated, duplicate } = store.submit(
            connectionId,
            request,
          );
          const id = updated.ref?.requestId ?? "";
          const result = request.result.case ?? "result";
          log(
            duplicate
              ? `request ${id}: a repeated ${result} changed nothing`
              : `request ${id}: ${result}, now ${RequestState[updated.state]}`,
          );
          return { request: updated };
        }),
    });
}

/** A RequestFailure as a Connect error, with its RequestErrorDetail. */
export function connectError(failure: RequestFailure): ConnectError {
  return new ConnectError(
    failure.message,
    CODES.get(failure.error) ?? Code.Internal,
    undefined,
    [
      {
        desc: RequestErrorDetailSchema,
        value: { error: failure.error, request: failure.request },
      },
    ],
  );
}
