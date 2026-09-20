/**
 * The phone-facing Connect API: LiveCommandService, authenticated with PHONE_TOKEN.
 */
import {
  Code,
  ConnectError,
  type ConnectRouter,
  type HandlerContext,
} from "@connectrpc/connect";

import { bearerTokenMatches } from "./phone-auth.ts";
import {
  AcknowledgementResult,
  LiveCommandError,
  LiveCommandService,
} from "./gen/seekervault/live/v1/live_pb.js";
import type { LiveCommandBridge } from "./live/bridge.ts";

const ACKNOWLEDGE_ERRORS: ReadonlyMap<
  LiveCommandError,
  readonly [Code, string]
> = new Map([
  [
    LiveCommandError.TIMEOUT,
    [Code.DeadlineExceeded, "the command timed out before it was acknowledged"],
  ],
  [
    LiveCommandError.CANCELLED,
    [Code.Canceled, "the command was cancelled before it was acknowledged"],
  ],
  [
    LiveCommandError.UNKNOWN_COMMAND,
    [Code.NotFound, "the sidecar is not tracking this command"],
  ],
]);

export function phoneRoutes(
  bridge: LiveCommandBridge,
  phoneToken: string,
  log: (message: string) => void,
): (router: ConnectRouter) => void {
  function authenticate(context: HandlerContext): void {
    if (
      !bearerTokenMatches(
        context.requestHeader.get("authorization"),
        phoneToken,
      )
    ) {
      log(`rejected ${context.method.name}: missing or wrong phone token`);
      throw new ConnectError(
        "a valid phone token is required",
        Code.Unauthenticated,
      );
    }
  }

  return (router) =>
    router.service(LiveCommandService, {
      async *watchCommands(_request, context) {
        authenticate(context);
        const watch = bridge.watch();
        const onAbort = (): void => {
          bridge.unwatch(watch);
        };
        context.signal.addEventListener("abort", onAbort, { once: true });
        try {
          yield { event: { case: "ready", value: {} } };
          const commands = watch.commands();
          for (;;) {
            const next = await commands.next();
            if (!next.done) {
              yield { event: { case: "command", value: next.value } };
            } else if (next.value === "replaced") {
              throw new ConnectError(
                "replaced by a newer WatchCommands stream",
                Code.Canceled,
              );
            } else if (next.value === "shutdown") {
              throw new ConnectError(
                "the sidecar is shutting down",
                Code.Unavailable,
              );
            } else {
              return;
            }
          }
        } finally {
          context.signal.removeEventListener("abort", onAbort);
          bridge.unwatch(watch);
        }
      },

      acknowledgeCommand(request, context) {
        authenticate(context);
        const acknowledgement = request.acknowledgement;
        if (acknowledgement === undefined || acknowledgement.id === "") {
          throw new ConnectError(
            "acknowledgement.id is required",
            Code.InvalidArgument,
          );
        }
        if (acknowledgement.result !== AcknowledgementResult.OK) {
          throw new ConnectError(
            "acknowledgement.result must be ACKNOWLEDGEMENT_RESULT_OK",
            Code.InvalidArgument,
          );
        }
        const result = bridge.acknowledge(acknowledgement.id);
        if (!result.ok) {
          const [code, message] = ACKNOWLEDGE_ERRORS.get(result.error) ?? [
            Code.Internal,
            "unexpected outcome",
          ];
          throw new ConnectError(message, code);
        }
        return {};
      },
    });
}
