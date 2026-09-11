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

import { bearerToken } from "../auth.ts";
import {
  RequestError,
  RequestErrorDetailSchema,
  RequestState,
} from "../gen/seekervault/request/v1/request_pb.js";
import { RequestService } from "../gen/seekervault/request/v1/service_pb.js";
import type { PairingStore } from "../storage/pairing-store.ts";
import { networkName, type RequestStore } from "../storage/request-store.ts";
import { RequestFailure } from "./failure.ts";

const CODES: ReadonlyMap<RequestError, Code> = new Map([
  [RequestError.INVALID_PARAMETERS, Code.InvalidArgument],
  [RequestError.NOT_FOUND, Code.NotFound],
  [RequestError.INVALID_STATE, Code.FailedPrecondition],
  [RequestError.STALE_PREPARATION, Code.FailedPrecondition],
  [RequestError.UNAUTHENTICATED, Code.Unauthenticated],
]);

export function requestRoutes(
  store: RequestStore,
  pairing: PairingStore,
  log: (message: string) => void,
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
        handle(context, (connectionId) => {
          const current = store.getForConnection(connectionId, request.ref);
          const kind = current.action?.kind.case;
          if (kind === "transfer" || kind === "swap") {
            throw new ConnectError(
              "transactions are prepared from Stage 4",
              Code.Unimplemented,
            );
          }
          throw new RequestFailure(
            RequestError.INVALID_PARAMETERS,
            `${kind === "signMessage" ? "sign_message" : "ack"} requests have nothing to prepare`,
            current,
          );
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
