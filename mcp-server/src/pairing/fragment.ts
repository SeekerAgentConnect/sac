/**
 * MCP-host HTTPS landing URLs for pairing codes. The custom-scheme URI stays the SDK's
 * `seekervault://pair?...`. Credentials live in the URL fragment so a new landing request
 * has no token in its path or query. The fragment is not encryption.
 */
import { parsePairingUri, type IssuedPairing } from "@seeker-vault/server-sdk";

import {
  CONDITIONAL_REPLACEMENT_WARNING,
  REPLACEMENT_WARNING,
  decodePairingFragment,
  encodePairingFragment,
  originOf,
  sameOrigin,
  type FragmentDecode,
  type PairingFragmentPayload,
} from "./page/payload.js";

export {
  CONDITIONAL_REPLACEMENT_WARNING,
  REPLACEMENT_WARNING,
  decodePairingFragment,
  encodePairingFragment,
};

export function pairingLandingUrl(issued: IssuedPairing): string {
  const expiresAt = new Date(issued.expiresAtMs).toISOString();
  const payload: PairingFragmentPayload = {
    v: 1,
    pairing_uri: issued.uri,
    expires_at: expiresAt,
    ...(issued.replaces === undefined
      ? {}
      : {
          warning: REPLACEMENT_WARNING,
          replaces: issued.replaces.connectionId,
        }),
  };
  const parsed = parsePairingUri(issued.uri);
  if (!parsed.ok) {
    throw new Error("issued pairing URI is not a seekervault://pair code");
  }
  return `${originOf(issued.serverUrl)}/pair#${encodePairingFragment(payload)}`;
}

export function landingUrlHasCredential(url: string): boolean {
  const parsed = new URL(url);
  const haystack = `${parsed.pathname}${parsed.search}`;
  return /(?:^|[?&])token=/.test(haystack) || parsed.pathname.includes("token");
}

/**
 * Host-side check used by tests and documentation: decode the fragment, require the SDK's
 * custom-scheme parser to accept the URI, and require the pairing server origin to match
 * this process's configured origin — not a hostname from the fragment alone.
 */
export function decodeLandingFragment(
  fragment: string,
  trustedOrigin: string,
): FragmentDecode | { readonly ok: false; readonly reason: "foreign_origin" } {
  const decoded = decodePairingFragment(fragment);
  if (!decoded.ok) return decoded;
  const parsed = parsePairingUri(decoded.payload.pairing_uri);
  if (!parsed.ok) return { ok: false, reason: "pairing_uri" };
  if (!sameOrigin(parsed.code.serverUrl, trustedOrigin)) {
    return { ok: false, reason: "foreign_origin" };
  }
  return decoded;
}
