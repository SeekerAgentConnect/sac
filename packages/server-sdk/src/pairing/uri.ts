/**
 * The pairing code the operator shows the phone (docs/protocol.md#pairing): a URI that carries the
 * sidecar's URL, its lasting ID, and a one-use pairing token. `pnpm pair` prints it as a QR code
 * and as text.
 */
import { invalidUuidReason } from "../requests/identity.ts";

export const PAIRING_URI_VERSION = "1";

// 32 random bytes in base64url, the form of every pairing token and phone credential.
const SECRET = /^[A-Za-z0-9_-]{43}$/;
const LOOPBACK_HOSTS: ReadonlySet<string> = new Set([
  "127.0.0.1",
  "localhost",
  "[::1]",
]);

export interface PairingCode {
  /** The URL the phone pairs with and then calls. */
  readonly serverUrl: string;
  /** The sidecar's lasting ID (a UUID). */
  readonly serverId: string;
  /** The one-use pairing token. */
  readonly token: string;
}

export type ParsedPairingUri =
  | { readonly ok: true; readonly code: PairingCode }
  | { readonly ok: false; readonly reason: string };

/**
 * Says why `url` can't be the URL a phone pairs with. It must be HTTPS, which the phone checks with
 * the normal certificate and hostname rules. The one exception is plain HTTP on a loopback host,
 * the development path over `adb reverse` that debug builds allow. The URL has no user name,
 * password, query, or fragment.
 */
export function invalidServerUrlReason(url: string): string | undefined {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return "the server URL isn't a URL";
  }
  if (parsed.username !== "" || parsed.password !== "") {
    return "the server URL must not contain a user name or password";
  }
  if (parsed.search !== "" || parsed.hash !== "") {
    return "the server URL must not have a query or a fragment";
  }
  // The URL parser takes port 0, but nothing listens there, and the phone refuses it.
  if (parsed.port === "0") return "the server URL's port must be 1 to 65535";
  if (parsed.protocol === "https:") return undefined;
  if (parsed.protocol === "http:" && LOOPBACK_HOSTS.has(parsed.hostname)) {
    return undefined;
  }
  return "the server URL must use HTTPS; plain HTTP is allowed only on loopback, for development";
}

/** The canonical form of a valid server URL, without a trailing slash, so that comparisons are exact. */
export function normalizeServerUrl(url: string): string {
  const parsed = new URL(url);
  return `${parsed.origin}${parsed.pathname.replace(/\/+$/, "")}`;
}

/** Whether `token` has the form of a pairing token or a phone credential. */
export function isSecret(token: string): boolean {
  return SECRET.test(token);
}

/** Query shared by the app deep link and the HTTPS landing page. */
function pairingQuery(code: PairingCode): string {
  return new URLSearchParams({
    v: PAIRING_URI_VERSION,
    url: code.serverUrl,
    server: code.serverId,
    token: code.token,
  }).toString();
}

/** The pairing URI: `seekervault://pair?v=1&url=<server URL>&server=<server ID>&token=<token>`. */
export function pairingUri(code: PairingCode): string {
  return `seekervault://pair?${pairingQuery(code)}`;
}

/**
 * HTTPS (or loopback HTTP) landing page for the same code: `<origin>/pair?<query>`.
 * Opening it in a browser redirects to {@link pairingUri}.
 */
export function pairingHttpsUrl(code: PairingCode): string {
  return `${new URL(normalizeServerUrl(code.serverUrl)).origin}/pair?${pairingQuery(code)}`;
}

/** The custom-scheme deep link, or this server's `/pair` landing page with the same query. */
function isPairingLink(uri: URL): boolean {
  if (uri.protocol === "seekervault:" && uri.hostname === "pair") return true;
  const path = uri.pathname.replace(/\/+$/, "") || "/";
  if (path !== "/pair") return false;
  if (uri.protocol === "https:") return true;
  return uri.protocol === "http:" && LOOPBACK_HOSTS.has(uri.hostname);
}

/** Reads a pairing URI the way the phone does, and says why it can't be used when it can't. */
export function parsePairingUri(text: string): ParsedPairingUri {
  let uri: URL;
  try {
    uri = new URL(text.trim());
  } catch {
    return failure("this isn't a pairing code");
  }
  if (!isPairingLink(uri)) {
    return failure("this isn't a Seeker Agent Connect pairing code");
  }
  const query = uri.searchParams;
  if (query.get("v") !== PAIRING_URI_VERSION) {
    return failure(
      "this pairing code is for another version of Seeker Agent Connect",
    );
  }
  const serverUrl = query.get("url") ?? "";
  const serverId = query.get("server") ?? "";
  const token = query.get("token") ?? "";
  const reason =
    invalidServerUrlReason(serverUrl) ??
    invalidUuidReason("server", serverId) ??
    (isSecret(token) ? undefined : "the pairing token is malformed");
  if (reason !== undefined) return failure(reason);
  return {
    ok: true,
    code: { serverUrl: normalizeServerUrl(serverUrl), serverId, token },
  };
}

function failure(reason: string): ParsedPairingUri {
  return { ok: false, reason };
}
