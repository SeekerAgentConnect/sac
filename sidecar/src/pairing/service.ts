/**
 * The phone's PairingService over Connect (docs/protocol.md#pairing). Pair takes the one-use
 * pairing token as its bearer credential. GetConnectionCapabilities and RevokeConnection take the
 * phone's own credential. No token reaches the log.
 */
import type { ConnectRouter } from "@connectrpc/connect";

import { bearerToken } from "../auth.ts";
import { RequestError } from "../gen/seekervault/request/v1/request_pb.js";
import { PairingService } from "../gen/seekervault/request/v1/service_pb.js";
import { connectError } from "../requests/phone-service.ts";
import { RequestFailure } from "../requests/failure.ts";
import type { PairingStore } from "../storage/pairing-store.ts";

export function pairingRoutes(
  pairing: PairingStore,
  log: (message: string) => void,
): (router: ConnectRouter) => void {
  return (router) =>
    router.service(PairingService, {
      pair(request, context) {
        try {
          const paired = pairing.pair(
            bearerToken(context.requestHeader.get("authorization")),
            request.serverUrl,
            request.deviceName,
          );
          log(
            `phone paired: connection ${paired.connectionId}` +
              (paired.revoked.length > 0
                ? `; revoked connection ${paired.revoked.join(", ")}`
                : ""),
          );
          return {
            connectionId: paired.connectionId,
            phoneToken: paired.phoneToken,
            serverId: paired.serverId,
          };
        } catch (error) {
          if (!(error instanceof RequestFailure)) throw error;
          log(`rejected Pair: ${error.code}`);
          throw connectError(error);
        }
      },

      getConnectionCapabilities(request, context) {
        const connectionId = pairing.authenticate(
          bearerToken(context.requestHeader.get("authorization")),
        );
        if (connectionId === undefined) {
          log(
            "rejected GetConnectionCapabilities: missing, wrong, or revoked phone credential",
          );
          throw connectError(
            new RequestFailure(
              RequestError.UNAUTHENTICATED,
              "a valid phone credential is required",
            ),
          );
        }
        if (request.connectionId !== connectionId) {
          throw connectError(
            new RequestFailure(RequestError.NOT_FOUND, "no such connection"),
          );
        }
        // SAW-048 defines discovery. SAW-049 supplies the capability once the production HTTP/2
        // endpoint exists; until then this new sidecar explicitly reports no configured endpoint.
        return {};
      },

      revokeConnection(request, context) {
        const connectionId = pairing.authenticate(
          bearerToken(context.requestHeader.get("authorization")),
        );
        if (connectionId === undefined) {
          log(
            "rejected RevokeConnection: missing, wrong, or revoked phone credential",
          );
          throw connectError(
            new RequestFailure(
              RequestError.UNAUTHENTICATED,
              "a valid phone credential is required",
            ),
          );
        }
        if (request.connectionId !== connectionId) {
          throw connectError(
            new RequestFailure(RequestError.NOT_FOUND, "no such connection"),
          );
        }
        const { cancelled } = pairing.revoke(connectionId);
        log(
          `connection ${connectionId} revoked by the phone; ${cancelled} pending requests cancelled`,
        );
        return {};
      },
    });
}
