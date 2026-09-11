/**
 * The phone's durable API: RequestService over Connect. Until pairing arrives (SAW-011),
 * PHONE_TOKEN authenticates the caller as the sidecar's one connection. Every RPC is unary, and
 * every error carries a RequestErrorDetail (docs/protocol.md).
 */
import {
  Code,
  ConnectError,
  type ConnectRouter,
  type HandlerContext,
} from "@connectrpc/connect";

import { bearerTokenMatches } from "../auth.ts";
import {
  RequestError,
  RequestErrorDetailSchema,
  RequestState,
} from "../gen/seekervault/request/v1/request_pb.js";
import { RequestService } from "../gen/seekervault/request/v1/service_pb.js";
import { RequestFailure, type RequestStore } from "./store.ts";

const CODES: ReadonlyMap<RequestError, Code> = new Map([
  [RequestError.INVALID_PARAMETERS, Code.InvalidArgument],
  [RequestError.NOT_FOUND, Code.NotFound],
  [RequestError.INVALID_STATE, Code.FailedPrecondition],
  [RequestError.STALE_PREPARATION, Code.FailedPrecondition],
  [RequestError.UNAUTHENTICATED, Code.Unauthenticated],
]);

export function requestRoutes(
  store: RequestStore,
  phoneToken: string,
  connectionId: string,
  log: (message: string) => void,
): (router: ConnectRouter) => void {
  /** Authenticates the phone, then runs `work`, turning a RequestFailure into a Connect error. */
  function handle<T>(context: HandlerContext, work: () => T): T {
    if (
      !bearerTokenMatches(
        context.requestHeader.get("authorization"),
        phoneToken,
      )
    ) {
      log(`rejected ${context.method.name}: missing or wrong phone token`);
      throw connectError(
        new RequestFailure(
          RequestError.UNAUTHENTICATED,
          "a valid phone token is required",
        ),
      );
    }
    try {
      return work();
    } catch (error) {
      throw error instanceof RequestFailure ? connectError(error) : error;
    }
  }

  return (router) =>
    router.service(RequestService, {
      listPending: (request, context) =>
        handle(context, () => store.listPending(connectionId, request)),

      getRequest: (request, context) =>
        handle(context, () => ({
          request: store.getForConnection(connectionId, request.ref),
        })),

      prepareRequest: (request, context) =>
        handle(context, () => {
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

      submitResult: (request, context) =>
        handle(context, () => {
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

function connectError(failure: RequestFailure): ConnectError {
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
