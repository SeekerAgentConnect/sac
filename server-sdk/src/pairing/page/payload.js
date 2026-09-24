/**
 * Bounded pairing-page fragment codec. This file is served to the browser and imported by
 * whichever server hosts the page; it has no Node, SDK, fetch, or storage APIs.
 *
 * Encoding: UTF-8 JSON object → base64url without padding. The HTTP request never carries
 * this payload. A fragment is not encryption.
 */

export const PAIRING_FRAGMENT_VERSION = 1;
export const MAX_FRAGMENT_BYTES = 4096;
export const MAX_DISPLAY_CHARS = 500;

export const REPLACEMENT_WARNING =
  "Connecting a phone with this link will disconnect the previously paired phone and cancel its pending requests. Creating or opening this link does not disconnect it.";

export const CONDITIONAL_REPLACEMENT_WARNING =
  "If a phone is already paired with this server, connecting with this link will disconnect it and cancel its pending requests. Creating or opening this link does not disconnect it.";

const SECRET = /^[A-Za-z0-9_-]{43}$/;
const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const EXPIRES_AT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/;
const LOOPBACK = new Set(["127.0.0.1", "localhost", "[::1]"]);

export function encodePairingFragment(payload) {
  const body = JSON.stringify(canonicalPayload(payload));
  const bytes = new TextEncoder().encode(body);
  if (bytes.byteLength > MAX_FRAGMENT_BYTES) {
    throw new Error("pairing fragment exceeds the size bound");
  }
  return toBase64Url(bytes);
}

export function decodePairingFragment(fragment) {
  if (typeof fragment !== "string" || fragment.length === 0) {
    return { ok: false, reason: "empty" };
  }
  if (fragment.length > MAX_FRAGMENT_BYTES * 2) {
    return { ok: false, reason: "too_large" };
  }
  const bytes = fromBase64Url(fragment);
  if (bytes === undefined) return { ok: false, reason: "encoding" };
  if (bytes.byteLength > MAX_FRAGMENT_BYTES) {
    return { ok: false, reason: "too_large" };
  }
  let parsed;
  try {
    parsed = JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    return { ok: false, reason: "json" };
  }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    return { ok: false, reason: "json" };
  }
  const keys = Object.keys(parsed);
  for (const key of keys) {
    if (
      key !== "v" &&
      key !== "pairing_uri" &&
      key !== "expires_at" &&
      key !== "warning" &&
      key !== "replaces"
    ) {
      return { ok: false, reason: "fields" };
    }
  }
  if (parsed.v !== PAIRING_FRAGMENT_VERSION) {
    return { ok: false, reason: "version" };
  }
  if (typeof parsed.pairing_uri !== "string") {
    return { ok: false, reason: "pairing_uri" };
  }
  const uri = parseCustomPairingUri(parsed.pairing_uri);
  if (!uri.ok) return { ok: false, reason: "pairing_uri" };
  if (
    typeof parsed.expires_at !== "string" ||
    !EXPIRES_AT.test(parsed.expires_at)
  ) {
    return { ok: false, reason: "expires_at" };
  }
  if (Number.isNaN(Date.parse(parsed.expires_at))) {
    return { ok: false, reason: "expires_at" };
  }
  const warning = optionalDisplay(parsed.warning);
  if (warning === false) return { ok: false, reason: "warning" };
  const replaces = optionalDisplay(parsed.replaces);
  if (replaces === false) return { ok: false, reason: "replaces" };
  return {
    ok: true,
    payload: {
      v: PAIRING_FRAGMENT_VERSION,
      pairing_uri: parsed.pairing_uri,
      expires_at: parsed.expires_at,
      ...(warning === undefined ? {} : { warning }),
      ...(replaces === undefined ? {} : { replaces }),
    },
    code: uri.code,
  };
}

export function parseCustomPairingUri(text) {
  let uri;
  try {
    uri = new URL(String(text).trim());
  } catch {
    return { ok: false, reason: "this isn't a pairing code" };
  }
  if (uri.protocol !== "seekervault:" || uri.hostname !== "pair") {
    return {
      ok: false,
      reason: "this isn't a Seeker Agent Connect pairing code",
    };
  }
  if (uri.searchParams.get("v") !== "1") {
    return {
      ok: false,
      reason:
        "this pairing code is for another version of Seeker Agent Connect",
    };
  }
  const serverUrl = uri.searchParams.get("url") ?? "";
  const serverId = uri.searchParams.get("server") ?? "";
  const token = uri.searchParams.get("token") ?? "";
  const serverReason = invalidServerUrl(serverUrl);
  if (serverReason !== undefined) return { ok: false, reason: serverReason };
  if (!UUID.test(serverId))
    return { ok: false, reason: "the server id is malformed" };
  if (!SECRET.test(token))
    return { ok: false, reason: "the pairing token is malformed" };
  return {
    ok: true,
    code: {
      serverUrl: normalizeServerUrl(serverUrl),
      serverId,
      token,
    },
  };
}

export function parseLegacyPairingQuery(search) {
  const query = search.startsWith("?") ? search : `?${search}`;
  return parseCustomPairingUri(`seekervault://pair${query}`);
}

export function pairingUriFromCode(code) {
  const query = new URLSearchParams({
    v: "1",
    url: code.serverUrl,
    server: code.serverId,
    token: code.token,
  }).toString();
  return `seekervault://pair?${query}`;
}

export function originOf(url) {
  return new URL(url).origin;
}

export function sameOrigin(serverUrl, trustedOrigin) {
  try {
    return originOf(serverUrl) === originOf(trustedOrigin);
  } catch {
    return false;
  }
}

function canonicalPayload(payload) {
  if (payload.v !== PAIRING_FRAGMENT_VERSION) {
    throw new Error("unsupported pairing fragment version");
  }
  const uri = parseCustomPairingUri(payload.pairing_uri);
  if (!uri.ok) throw new Error("pairing_uri is not a seekervault://pair code");
  if (
    typeof payload.expires_at !== "string" ||
    !EXPIRES_AT.test(payload.expires_at)
  ) {
    throw new Error("expires_at must be RFC 3339 UTC");
  }
  const out = {
    v: PAIRING_FRAGMENT_VERSION,
    pairing_uri: payload.pairing_uri,
    expires_at: payload.expires_at,
  };
  if (payload.warning !== undefined) {
    if (optionalDisplay(payload.warning) === false) {
      throw new Error("warning is not displayable text");
    }
    out.warning = payload.warning;
  }
  if (payload.replaces !== undefined) {
    if (optionalDisplay(payload.replaces) === false) {
      throw new Error("replaces is not displayable text");
    }
    out.replaces = payload.replaces;
  }
  return out;
}

function optionalDisplay(value) {
  if (value === undefined) return undefined;
  if (typeof value !== "string") return false;
  if (value.length === 0 || value.length > MAX_DISPLAY_CHARS) return false;
  if (value.includes("\0")) return false;
  return value;
}

function invalidServerUrl(url) {
  let parsed;
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
  if (parsed.port === "0") return "the server URL's port must be 1 to 65535";
  if (parsed.protocol === "https:") return undefined;
  if (parsed.protocol === "http:" && LOOPBACK.has(parsed.hostname))
    return undefined;
  return "the server URL must use HTTPS; plain HTTP is allowed only on loopback, for development";
}

function normalizeServerUrl(url) {
  const parsed = new URL(url);
  return `${parsed.origin}${parsed.pathname.replace(/\/+$/, "")}`;
}

function toBase64Url(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary)
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "");
}

function fromBase64Url(text) {
  if (!/^[A-Za-z0-9_-]*$/.test(text)) return undefined;
  const padded = text.replaceAll("-", "+").replaceAll("_", "/");
  const pad = (4 - (padded.length % 4)) % 4;
  try {
    const binary = atob(padded + "=".repeat(pad));
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
    return bytes;
  } catch {
    return undefined;
  }
}
