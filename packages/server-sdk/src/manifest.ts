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
 *
 * Since SEE-174 it also names the Solana networks the server's wallet operations are configured
 * for. That is the host's statement, never the SDK's guess: a host that declares nothing publishes
 * an empty list, which the phone reads as "no networks declared" and signs nothing for, rather than
 * as Mainnet or as every network.
 */
import { createHash } from "node:crypto";

import { create, toJsonString } from "@bufbuild/protobuf";

import {
  ConnectionMode,
  ServerEnvironment,
  ServerManifestSchema,
  SolanaNetwork,
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
  /**
   * The Solana networks this server's wallet operations are configured for (SEE-174), published as
   * `direct.supported_networks`. Absent is the same as empty: the server declares no network, and
   * the phone signs nothing for it until it does. Validated and put in canonical order by
   * [canonicalSupportedNetworks], so two hosts that list the same networks in a different order
   * publish the same manifest and the same revision.
   */
  readonly supportedNetworks?: readonly SolanaNetwork[];
}

/**
 * The name a network is configured by: the same lowercase word agents and error messages already
 * use for a wallet binding's network (`networkName`). Case matters, and there are no aliases —
 * "mainnet-beta" is the cluster's RPC name, not one of these — because a configuration value that
 * means something only after it has been guessed at is one an operator cannot read back.
 */
export type SolanaNetworkName = "mainnet" | "devnet" | "testnet";

const NETWORKS_BY_NAME: ReadonlyMap<string, SolanaNetwork> = new Map([
  ["mainnet", SolanaNetwork.MAINNET],
  ["devnet", SolanaNetwork.DEVNET],
  ["testnet", SolanaNetwork.TESTNET],
]);

/** Every network a manifest can name, by its configuration name, in canonical order. */
export const SOLANA_NETWORK_NAMES: readonly SolanaNetworkName[] = [
  "mainnet",
  "devnet",
  "testnet",
];

/** The configuration name of [network], or undefined for UNSPECIFIED and any unknown value. */
export function solanaNetworkName(
  network: SolanaNetwork,
): SolanaNetworkName | undefined {
  for (const [name, value] of NETWORKS_BY_NAME) {
    if (value === network) return name as SolanaNetworkName;
  }
  return undefined;
}

/**
 * [networks] in canonical order — ascending by value, which is Mainnet, Devnet, Testnet — or an
 * error naming what is wrong with them.
 *
 * Refused rather than repaired: UNSPECIFIED, a number the contract doesn't name, and the same
 * network twice. Each of those is a configuration that says something other than what its author
 * meant, and a manifest that quietly dropped the part it didn't understand would advertise a
 * server the operator never configured. Order is the one thing corrected, because it carries no
 * meaning and the revision must not move when only the order of a list does.
 */
export function canonicalSupportedNetworks(
  networks: readonly SolanaNetwork[],
): SolanaNetwork[] {
  const seen = new Set<SolanaNetwork>();
  for (const network of networks) {
    const name = solanaNetworkName(network);
    if (name === undefined) {
      throw new Error(
        `supported networks may name only ${SOLANA_NETWORK_NAMES.join(", ")}; ` +
          `${network === SolanaNetwork.UNSPECIFIED ? "SOLANA_NETWORK_UNSPECIFIED" : `value ${String(network)}`} is not one`,
      );
    }
    if (seen.has(network)) {
      throw new Error(`supported networks name ${name} more than once`);
    }
    seen.add(network);
  }
  return [...seen].sort((a, b) => a - b);
}

/**
 * The networks a comma-separated configuration value names, such as `"mainnet,devnet"`, in
 * canonical order. It is how a host reads an environment variable, so that every server built on
 * this SDK accepts the same spelling and refuses the same mistakes.
 *
 * A blank value, or exactly `none`, is an empty list: the host declares no network. `none` is the
 * spelling the Go publisher templates use for the same thing, so it means the same here, and it
 * cannot be combined with a network. Each name is trimmed, and must be exactly one of
 * [SOLANA_NETWORK_NAMES]; an unknown name, a name in another case, an empty entry
 * ("mainnet,,devnet") and a repeated name are each an error whose message names [source], so a
 * startup failure says which variable to fix. Nothing is defaulted: a server that should sign on
 * Mainnet says so.
 */
export function parseSupportedNetworks(
  value: string,
  source = "supported networks",
): SolanaNetwork[] {
  const trimmed = value.trim();
  if (trimmed === "" || trimmed === "none") return [];
  const networks: SolanaNetwork[] = [];
  const seen = new Set<string>();
  for (const raw of value.split(",")) {
    const name = raw.trim();
    if (name === "") {
      throw new Error(
        `${source} has an empty entry; list networks separated by single commas, such as "mainnet,devnet"`,
      );
    }
    if (name === "none") {
      throw new Error(
        `${source} lists none together with a network; use none alone to declare no network`,
      );
    }
    const network = NETWORKS_BY_NAME.get(name);
    if (network === undefined) {
      throw new Error(
        `${source} names "${name}", which is not a network; use ${SOLANA_NETWORK_NAMES.join(", ")} (lowercase)`,
      );
    }
    if (seen.has(name)) {
      throw new Error(`${source} names ${name} more than once`);
    }
    seen.add(name);
    networks.push(network);
  }
  return canonicalSupportedNetworks(networks);
}

/**
 * The manifest for [settings], with [revision] as its settings revision.
 *
 * The sidecar publishes no `display_name`: a direct connection is labelled by the host the owner
 * paired with, and a name the server picked for itself would be read as this app's word for it.
 *
 * The networks go in the reference, as `direct.supported_networks`, so the reference stays the last
 * thing serialized. They are written in canonical order, and a list the contract would refuse
 * throws here rather than being published for the phone to refuse instead.
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
    reference: {
      case: "direct",
      value: {
        url: settings.url,
        supportedNetworks: canonicalSupportedNetworks(
          settings.supportedNetworks ?? [],
        ),
      },
    },
    environments: [ServerEnvironment.PRODUCTION],
  });
}

/**
 * A fingerprint of everything a manifest for [settings] says except its revision.
 *
 * The revision has to change when the content does and stay put when it doesn't, and the sidecar
 * has no memory of its own settings between restarts, so it compares this instead. The canonical
 * JSON of the message is the whole content by construction: a field added to the manifest later is
 * part of the fingerprint without anything here being updated to remember it. That includes the
 * supported networks (SEE-174): declaring, dropping or adding one moves the revision, and an empty
 * list is absent from the JSON, so a server that still declares nothing keeps the fingerprint — and
 * the revision — it had before the field existed.
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
