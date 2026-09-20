/**
 * The phone's PairingService over Connect (docs/protocol.md#pairing). Pair takes the one-use
 * pairing token as its bearer credential. GetConnectionCapabilities, GetServerManifest,
 * SetFcmToken, and RevokeConnection take the phone's own credential. No credential or FCM target
 * reaches the log.
 */
import type { ConnectRouter } from "@connectrpc/connect";

import { bearerToken } from "../phone-auth.ts";
import { RequestError } from "../gen/seekervault/request/v1/request_pb.js";
import {
  PairingService,
  type UpdateCapability,
} from "../gen/seekervault/request/v1/service_pb.js";
import type { ServerManifest } from "../gen/seekervault/server/v1/manifest_pb.js";
import { connectError } from "../requests/phone-service.ts";
import { RequestFailure } from "../requests/failure.ts";
import type { PairingStore } from "../storage/pairing-store.ts";

export function pairingRoutes(
  pairing: PairingStore,
  log: (message: string) => void,
  updateCapability: () => UpdateCapability | undefined = () => undefined,
  // What this server says about itself (SEE-88). It is published once the listener is up, because
  // the manifest names the URL the phone reaches — so like updateCapability it is read per call
  // rather than captured, and the only way it is absent is before anything can ask for it.
  serverManifest: () => ServerManifest | undefined = () => undefined,
): (router: ConnectRouter) => void {
  return (router) =>
    router.service(PairingService, {
      pair(request, context) {
        try {
          const updates = updateCapability();
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
            updates,
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
        return { updates: updateCapability() };
      },

      getServerManifest(request, context) {
        const connectionId = pairing.authenticate(
          bearerToken(context.requestHeader.get("authorization")),
        );
        if (connectionId === undefined) {
          log(
            "rejected GetServerManifest: missing, wrong, or revoked phone credential",
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
        const manifest = serverManifest();
        if (manifest === undefined) {
          // Not reachable while the sidecar is listening, and deliberately not answered with
          // UNIMPLEMENTED: that is what an older sidecar says, and it would tell the phone this
          // server has no manifest when what happened is that this one failed to publish its own.
          throw connectError(
            new RequestFailure(
              RequestError.UNSPECIFIED,
              "the server manifest is not published",
            ),
          );
        }
        return { manifest };
      },

      setFcmToken(request, context) {
        const connectionId = pairing.authenticate(
          bearerToken(context.requestHeader.get("authorization")),
        );
        if (connectionId === undefined) {
          log(
            "rejected SetFcmToken: missing, wrong, or revoked phone credential",
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
        try {
          let changed: boolean;
          if (request.update.case === "token") {
            changed = pairing.setFcmToken(connectionId, request.update.value);
          } else if (request.update.case === "clearIfToken") {
            changed = pairing.clearFcmToken(connectionId, request.update.value);
          } else {
            throw new RequestFailure(
              RequestError.INVALID_PARAMETERS,
              "one FCM token update is required",
            );
          }
          log(
            `connection ${connectionId} FCM registration ` +
              (changed ? "updated" : "unchanged"),
          );
          return {};
        } catch (error) {
          if (!(error instanceof RequestFailure)) throw error;
          log(`rejected SetFcmToken: ${error.code}`);
          throw connectError(error);
        }
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
