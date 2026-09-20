/**
 * The manifest this sidecar publishes about itself (SEE-88, docs/wiki/server-manifests.md).
 *
 * Stage 7.1 has two kinds of server, and a phone has to be able to tell them apart from the
 * server's own statement rather than from how it was added. This one is always the first kind: the
 * owner's private server, which one paired phone calls directly with the credential it holds. It
 * requires no client plugin — an acknowledgement, a message signature and a transfer are actions
 * the app carries out itself — and it names its own URL and nothing else.
 *
 * What is not here is the point. There is no permission to grant, no policy to override, no wallet
 * endpoint to choose, and nothing loadable: the manifest is a description, and every decision it
 * could be imagined to make is still the build's or the owner's (docs/security.md).
 */
import { createHash } from "node:crypto";

import { create, toJsonString } from "@bufbuild/protobuf";

import {
  ConnectionMode,
  ServerEnvironment,
  ServerManifestSchema,
  type ServerManifest,
} from "./gen/seekervault/server/v1/manifest_pb.js";
import type { PairingStore } from "./storage/pairing-store.ts";

/**
 * The phone–server contract this sidecar speaks. It goes up when a change would make an older
 * phone wrong to talk to this server at all; a phone that doesn't speak the version it reads
 * reports the server as unsupported rather than guessing at the nearest one it knows.
 */
export const SERVER_PROTOCOL_VERSION = 1;

export interface ManifestSettings {
  /** The sidecar's lasting ID, the one its pairing code shows. */
  readonly serverId: string;
  /** The URL the phone paired with and calls, in its canonical form. */
  readonly url: string;
}

/**
 * The manifest for [settings], with [revision] as its settings revision.
 *
 * The sidecar publishes no `display_name`: a direct connection is labelled by the host the owner
 * paired with, and a name the server picked for itself would be read as this app's word for it.
 */
export function serverManifest(
  settings: ManifestSettings,
  revision: number,
): ServerManifest {
  return create(ServerManifestSchema, {
    serverId: settings.serverId,
    protocolVersion: SERVER_PROTOCOL_VERSION,
    settingsRevision: BigInt(revision),
    mode: ConnectionMode.DIRECT,
    reference: { case: "direct", value: { url: settings.url } },
    environments: [ServerEnvironment.PRODUCTION],
  });
}

/**
 * A fingerprint of everything a manifest for [settings] says except its revision.
 *
 * The revision has to change when the content does and stay put when it doesn't, and the sidecar
 * has no memory of its own settings between restarts, so it compares this instead. The canonical
 * JSON of the message is the whole content by construction: a field added to the manifest later is
 * part of the fingerprint without anything here being updated to remember it.
 */
export function manifestFingerprint(settings: ManifestSettings): string {
  const content = serverManifest(settings, 0);
  return createHash("sha256")
    .update(toJsonString(ServerManifestSchema, content))
    .digest("hex");
}

/**
 * The manifest to publish for [settings], with the revision the store keeps for that content.
 *
 * Called once at startup, because the settings a manifest describes are fixed for the life of the
 * process: an operator changes them by restarting, which is exactly when the revision moves.
 */
export function publishManifest(
  pairing: PairingStore,
  settings: ManifestSettings,
): ServerManifest {
  return serverManifest(
    settings,
    pairing.settingsRevision(manifestFingerprint(settings)),
  );
}
