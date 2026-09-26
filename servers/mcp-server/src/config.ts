/**
 * Sidecar configuration.
 *
 * `pnpm dev:mcp-server` loads the ignored root `.env` file with Node's
 * `--env-file-if-exists`; variables already set in the environment take
 * precedence over the file. Problems name the variable but never echo a
 * token value.
 */
import { homedir } from "node:os";
import { isAbsolute, join, resolve } from "node:path";

import {
  MAX_EXPIRES_IN_SECONDS,
  MIN_EXPIRES_IN_SECONDS,
  invalidRelayReason,
  invalidServerUrlReason,
  normalizeServerUrl,
  type RelayConfiguration,
} from "@seeker_agent_connect/server-sdk";

import { isSecureEndpoint, type OAuthConfig } from "./oauth.ts";
import { CHAIN_BUDGET_MS } from "./solana/rpc.ts";

export interface SidecarConfig {
  readonly host: string;
  readonly port: number;
  /**
   * The agent's bearer token for /mcp, and the switch for the whole MCP adapter (SEE-87,
   * docs/wiki/mcp-adapter.md): absent means MCP_ENABLED=false, and then the endpoint is never
   * constructed, /mcp is not served, and no MCP-only setting is required. It is the same shape
   * every other optional subsystem here uses — [solanaRpcUrl], [fcmProjectId], [oauth] — so there
   * is one place that says whether the adapter exists rather than two that could disagree.
   */
  readonly mcpToken?: string;
  /** The Stage 1 live diagnostic's development token; the durable phone API needs pairing. */
  readonly phoneToken: string;
  readonly liveCommandTimeoutSeconds: number;
  /**
   * Host names besides loopback that /mcp accepts in Host and Origin (MCP_ALLOWED_HOSTS). Ignored,
   * with a log line, when the adapter is off.
   */
  readonly mcpAllowedHosts?: readonly string[];
  /**
   * The authorization server /mcp accepts access tokens from, for a hosted MCP client (SAW-036).
   * Absent unless MCP_OAUTH_ISSUER is set, and then the endpoint takes an access token issued for
   * this deployment; the phone's API is never part of it (docs/integrations/claude.md).
   */
  readonly oauth?: OAuthConfig;
  /**
   * Serves vault_request_ack, which queues a wallet-free acknowledgement, for development and demos
   * (MCP_DEMO_TOOLS). Off unless it's set, and ignored, with a log line, when the adapter is off.
   */
  readonly demoTools?: boolean;
  /**
   * The SQLite file for durable requests (DATABASE_PATH). Relative values are resolved under
   * MCP_SERVER_DATA_DIR, never the launcher's current directory. ":memory:" is for tests.
   */
  readonly databasePath: string;
  /** The lifetime of a request whose agent doesn't choose one (REQUEST_TTL_SECONDS). */
  readonly requestTtlSeconds: number;
  /** The most PENDING requests the phone may have at once (REQUEST_PENDING_LIMIT). */
  readonly pendingLimit: number;
  /**
   * The URL the phone pairs with and calls (SIDECAR_PUBLIC_URL): HTTPS, or by default the loopback
   * URL, for development over adb reverse. Only the `pair` command reads it.
   */
  readonly publicUrl?: string;
  /** How long a pairing code works (PAIRING_TOKEN_TTL_SECONDS). Only `pair` reads it. */
  readonly pairingTokenTtlSeconds?: number;
  /**
   * The Solana JSON-RPC endpoint transfers are prepared against (SOLANA_RPC_URL). Without it the
   * sidecar serves no transfer tool at all, rather than accepting requests it couldn't prepare.
   * The URL may carry an API key, so it is never logged or put in an error message.
   */
  readonly solanaRpcUrl?: string;
  /** How long one chain call may take (SOLANA_RPC_TIMEOUT_MS); the default applies when unset. */
  readonly solanaRpcTimeoutMs?: number;
  /** Cleartext HTTP/2 update listener for loopback development (SIDECAR_UPDATE_PORT). */
  readonly updatePort?: number;
  /**
   * Main listener is HTTP/2 cleartext (h2c) and advertises SIDECAR_PUBLIC_URL as the update
   * origin. For a TLS-terminating HTTP/2 reverse proxy (App Platform `protocol: HTTP2`).
   */
  readonly h2c?: boolean;
  /** PEM identity for the production HTTP/2 + HTTP/1.1 TLS listener. */
  readonly tlsCertificatePath?: string;
  readonly tlsPrivateKeyPath?: string;
  /**
   * Firebase project used by the optional FCM sender (FCM_PROJECT_ID). Credentials are resolved
   * separately through Application Default Credentials and never enter this configuration.
   */
  readonly fcmProjectId?: string;
  /**
   * The gateway push relay (SEE-144), for a server this operator hosts themselves and does not
   * want to give a Firebase project. All three of RELAY_URL, RELAY_SERVER_ID and RELAY_CREDENTIAL
   * are set together or none is, and configuring it beside FCM_PROJECT_ID is refused: both would
   * fire on the same committed update, so a phone would be woken twice for one change.
   *
   * The credential is a secret and behaves like one here: it is read, never logged, and never
   * printed back by any startup line.
   */
  readonly relay?: RelayConfiguration;
  /**
   * Settings this configuration read and will not act on, named so startup can say so (SEE-87).
   *
   * Turning the MCP adapter off must not force an operator to delete a token they may want back,
   * so a leftover MCP-only setting is dropped rather than refused — but nothing here is allowed to
   * do nothing *quietly*. A setting that would contradict the deployment instead of merely
   * outliving it, such as the OAuth profile, is a configuration error rather than an entry here.
   */
  readonly ignoredSettings?: readonly string[];
}

export class ConfigError extends Error {
  readonly problems: readonly string[];

  constructor(problems: readonly string[]) {
    super(
      [
        "Invalid MCP server configuration:",
        ...problems.map((problem) => `  - ${problem}`),
      ].join("\n"),
    );
    this.name = "ConfigError";
    this.problems = problems;
  }
}

type Env = Readonly<Record<string, string | undefined>>;

/** A stable user-owned directory, outside npm installs and transient npx caches. */
export const DEFAULT_DATA_DIRECTORY = join(
  homedir(),
  ".seeker-agent-connect",
  "mcp-server",
);

/** Where durable requests live unless DATABASE_PATH says otherwise. */
export const DEFAULT_DATABASE_PATH = join(
  DEFAULT_DATA_DIRECTORY,
  "direct-server.db",
);

// Source/npm default to loopback. A container may bind a wildcard only when the separately
// advertised phone origin is explicit; publishing that listener is the operator's decision.
const LOOPBACK_HOSTS = new Set(["127.0.0.1", "::1", "localhost"]);
const WILDCARD_HOSTS = new Set(["0.0.0.0", "::"]);
const PLACEHOLDER_PREFIX = "REPLACE_WITH_";
const MIN_TOKEN_LENGTH = 32;
// RFC 6750 b64token: the characters a bearer token can carry in an Authorization header.
const BEARER_TOKEN = /^[A-Za-z0-9\-._~+/]+=*$/;
// A DNS name or an IPv4 address, or an IPv6 address in brackets; no scheme, port, or wildcard.
const HOSTNAME =
  /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*$|^\[[0-9a-f:.]+\]$/;
// RFC 6749's scope-token: printable ASCII without spaces, quotes, or backslashes.
const SCOPE_TOKEN = /^[\x21\x23-\x5B\x5D-\x7E]+$/;
const MAX_LIVE_COMMAND_TIMEOUT_SECONDS = 3600;
const DEFAULT_REQUEST_TTL_SECONDS = 24 * 60 * 60;
const DEFAULT_PENDING_LIMIT = 100;
const MAX_PENDING_LIMIT = 10_000;
const DEFAULT_PAIRING_TOKEN_TTL_SECONDS = 600;
const MIN_PAIRING_TOKEN_TTL_SECONDS = 60;
const MAX_PAIRING_TOKEN_TTL_SECONDS = 3600;
/** How long one Solana RPC call may take unless SOLANA_RPC_TIMEOUT_MS says otherwise. */
export const DEFAULT_SOLANA_RPC_TIMEOUT_MS = 10_000;
const MIN_SOLANA_RPC_TIMEOUT_MS = 1000;
/**
 * No single call may be given longer than a whole chain-backed operation gets, because the phone
 * waits on one unary RPC for the operation and gives up at its own deadline (solana/rpc.ts).
 */
const MAX_SOLANA_RPC_TIMEOUT_MS = CHAIN_BUDGET_MS;
// Google Cloud project IDs are 6-30 lowercase letters, digits, and hyphens; they start with a
// letter and end with a letter or digit. Validate before handing the value to the Admin SDK.
const FIREBASE_PROJECT_ID = /^[a-z][a-z0-9-]{4,28}[a-z0-9]$/;

export function loadSidecarConfig(env: Env): SidecarConfig & {
  readonly demoTools: boolean;
  readonly publicUrl: string;
  readonly pairingTokenTtlSeconds: number;
} {
  const problems: string[] = [];

  const host = required(env, "SIDECAR_HOST", problems);
  if (
    host !== undefined &&
    !LOOPBACK_HOSTS.has(host) &&
    !WILDCARD_HOSTS.has(host)
  ) {
    problems.push(
      "SIDECAR_HOST must be loopback (127.0.0.1, ::1, localhost) or a container wildcard (0.0.0.0, ::).",
    );
  }
  const port = wholeNumber(env, "SIDECAR_PORT", 1, 65_535, problems);
  // MCP is one adapter over the request core, and this is its switch (SEE-87,
  // docs/wiki/mcp-adapter.md). It defaults to on, so an existing deployment keeps every setting
  // and every behaviour it has; off, the endpoint is never built and nothing MCP-only is required.
  const mcpEnabled = enabled(env, "MCP_ENABLED", problems);
  const mcpToken = mcpEnabled
    ? token(
        env,
        "MCP_TOKEN",
        problems,
        "Set it, or set MCP_ENABLED=false to run without the MCP adapter.",
      )
    : undefined;
  const phoneToken = token(env, "PHONE_TOKEN", problems);
  if (mcpToken !== undefined && mcpToken === phoneToken) {
    problems.push("MCP_TOKEN and PHONE_TOKEN must be different values.");
  }
  const liveCommandTimeoutSeconds = wholeNumber(
    env,
    "LIVE_COMMAND_TIMEOUT_SECONDS",
    1,
    MAX_LIVE_COMMAND_TIMEOUT_SECONDS,
    problems,
  );
  const mcpAllowedHosts = mcpEnabled ? allowedHosts(env, problems) : [];
  const demoTools = mcpEnabled ? flag(env, "MCP_DEMO_TOOLS", problems) : false;
  // Named at startup rather than dropped in silence. A token kept in .env while the adapter is off
  // is an operator's own business; a setting nobody is told about is not. A setting left at its
  // own default is not something anybody is waiting on, so only a demo-tools value that would
  // have done something counts.
  const ignoredSettings = mcpEnabled
    ? []
    : [
        ...(env.MCP_TOKEN?.trim() ? ["MCP_TOKEN"] : []),
        ...(env.MCP_ALLOWED_HOSTS?.trim() ? ["MCP_ALLOWED_HOSTS"] : []),
        ...(env.MCP_DEMO_TOOLS?.trim().toLowerCase() === "true"
          ? ["MCP_DEMO_TOOLS"]
          : []),
      ];
  const dataDirectory = dataDirectoryOf(env, problems);
  const databasePath = databasePathOf(env, dataDirectory);
  const requestTtlSeconds = optionalWholeNumber(
    env,
    "REQUEST_TTL_SECONDS",
    MIN_EXPIRES_IN_SECONDS,
    MAX_EXPIRES_IN_SECONDS,
    DEFAULT_REQUEST_TTL_SECONDS,
    problems,
  );
  const pendingLimit = optionalWholeNumber(
    env,
    "REQUEST_PENDING_LIMIT",
    1,
    MAX_PENDING_LIMIT,
    DEFAULT_PENDING_LIMIT,
    problems,
  );
  const publicUrl = publicUrlOf(env, host, port, problems);
  const pairingTokenTtlSeconds = optionalWholeNumber(
    env,
    "PAIRING_TOKEN_TTL_SECONDS",
    MIN_PAIRING_TOKEN_TTL_SECONDS,
    MAX_PAIRING_TOKEN_TTL_SECONDS,
    DEFAULT_PAIRING_TOKEN_TTL_SECONDS,
    problems,
  );
  const oauth = oauthConfig(env, publicUrl, mcpEnabled, problems);
  const solanaRpcUrl = endpointUrl(env, problems);
  const solanaRpcTimeoutMs = optionalWholeNumber(
    env,
    "SOLANA_RPC_TIMEOUT_MS",
    MIN_SOLANA_RPC_TIMEOUT_MS,
    MAX_SOLANA_RPC_TIMEOUT_MS,
    DEFAULT_SOLANA_RPC_TIMEOUT_MS,
    problems,
  );
  const updatePort = optionalNumber(
    env,
    "SIDECAR_UPDATE_PORT",
    1,
    65_535,
    problems,
  );
  const h2c = flag(env, "SIDECAR_H2C", problems);
  const tlsCertificatePath = env.SIDECAR_TLS_CERT_PATH?.trim() || undefined;
  const tlsPrivateKeyPath = env.SIDECAR_TLS_KEY_PATH?.trim() || undefined;
  const fcmProjectId = firebaseProjectId(env, problems);
  const relay = relayConfiguration(env, problems);
  if (fcmProjectId !== undefined && relay !== undefined) {
    problems.push(
      "FCM_PROJECT_ID and RELAY_URL configure two ways of sending the same wake-up. " +
        "Set one: FCM_PROJECT_ID sends through this server's own Firebase project, " +
        "RELAY_URL asks a gateway operator to send on its behalf.",
    );
  }
  if (
    (tlsCertificatePath === undefined) !==
    (tlsPrivateKeyPath === undefined)
  ) {
    problems.push(
      "SIDECAR_TLS_CERT_PATH and SIDECAR_TLS_KEY_PATH must both be set or both be unset.",
    );
  }
  if (updatePort !== undefined && tlsCertificatePath !== undefined) {
    problems.push(
      "SIDECAR_UPDATE_PORT is the loopback development listener and cannot be combined with the production TLS listener.",
    );
  }
  if (h2c && tlsCertificatePath !== undefined) {
    problems.push(
      "SIDECAR_H2C is the reverse-proxy HTTP/2 listener and cannot be combined with the production TLS listener.",
    );
  }
  if (h2c && updatePort !== undefined) {
    problems.push("SIDECAR_H2C cannot be combined with SIDECAR_UPDATE_PORT.");
  }
  if (
    tlsCertificatePath !== undefined &&
    tlsPrivateKeyPath !== undefined &&
    publicUrl !== undefined
  ) {
    const url = new URL(publicUrl);
    if (url.protocol !== "https:") {
      problems.push(
        "SIDECAR_PUBLIC_URL must use https:// when the production TLS listener is configured.",
      );
    }
  }
  if (h2c && publicUrl !== undefined) {
    const url = new URL(publicUrl);
    if (url.protocol !== "https:") {
      problems.push(
        "SIDECAR_PUBLIC_URL must use https:// when SIDECAR_H2C is true.",
      );
    }
  }

  if (
    problems.length > 0 ||
    host === undefined ||
    port === undefined ||
    phoneToken === undefined ||
    liveCommandTimeoutSeconds === undefined ||
    requestTtlSeconds === undefined ||
    pendingLimit === undefined ||
    publicUrl === undefined ||
    pairingTokenTtlSeconds === undefined ||
    solanaRpcTimeoutMs === undefined
  ) {
    throw new ConfigError(problems);
  }
  return {
    host,
    port,
    ...(mcpToken === undefined ? {} : { mcpToken }),
    phoneToken,
    liveCommandTimeoutSeconds,
    mcpAllowedHosts,
    demoTools,
    databasePath,
    requestTtlSeconds,
    pendingLimit,
    publicUrl,
    pairingTokenTtlSeconds,
    ...(oauth === undefined ? {} : { oauth }),
    solanaRpcUrl,
    solanaRpcTimeoutMs,
    ...(updatePort === undefined ? {} : { updatePort }),
    ...(h2c ? { h2c: true } : {}),
    ...(tlsCertificatePath === undefined ? {} : { tlsCertificatePath }),
    ...(tlsPrivateKeyPath === undefined ? {} : { tlsPrivateKeyPath }),
    ...(fcmProjectId === undefined ? {} : { fcmProjectId }),
    ...(relay === undefined ? {} : { relay }),
    ...(ignoredSettings.length === 0 ? {} : { ignoredSettings }),
  };
}

/**
 * MCP_SERVER_DATA_DIR is the one writable application home shared by source, npm and Docker.
 * Requiring an absolute configured value keeps a service restart independent of its working
 * directory; the default is already absolute and belongs to the current user.
 */
function dataDirectoryOf(env: Env, problems: string[]): string | undefined {
  const raw = env.MCP_SERVER_DATA_DIR?.trim();
  if (!raw) return DEFAULT_DATA_DIRECTORY;
  if (!isAbsolute(raw)) {
    problems.push("MCP_SERVER_DATA_DIR must be an absolute path.");
    return undefined;
  }
  return resolve(raw);
}

function databasePathOf(env: Env, dataDirectory: string | undefined): string {
  const raw = env.DATABASE_PATH?.trim();
  if (!raw)
    return join(dataDirectory ?? DEFAULT_DATA_DIRECTORY, "direct-server.db");
  if (raw === ":memory:" || isAbsolute(raw)) return raw;
  return resolve(dataDirectory ?? DEFAULT_DATA_DIRECTORY, raw);
}

/**
 * MCP_OAUTH_*: the optional OAuth profile for a hosted MCP client (SAW-036). MCP_OAUTH_ISSUER is
 * the switch; without it there is no OAuth at all, and the other three are a configuration error
 * rather than settings that quietly do nothing. None of them is a secret: this sidecar is a
 * resource server, so it reads the authorization server's public keys and holds no client
 * credential of its own.
 */
function oauthConfig(
  env: Env,
  publicUrl: string | undefined,
  mcpEnabled: boolean,
  problems: string[],
): OAuthConfig | undefined {
  const issuer = env.MCP_OAUTH_ISSUER?.trim();
  const resource = env.MCP_OAUTH_RESOURCE?.trim();
  const jwksUrl = env.MCP_OAUTH_JWKS_URL?.trim();
  const scope = env.MCP_OAUTH_SCOPE?.trim();
  // With the adapter off this is a contradiction rather than a leftover, so it is refused where
  // the other MCP-only settings are ignored (SEE-87). The profile is a public promise: the sidecar
  // publishes protected-resource metadata telling a client where to authorize, and the endpoint it
  // would authorize for does not exist. Silently dropping it would leave an operator believing a
  // hosted client could connect.
  if (!mcpEnabled) {
    const configured = [
      ...(issuer ? ["MCP_OAUTH_ISSUER"] : []),
      ...(resource ? ["MCP_OAUTH_RESOURCE"] : []),
      ...(jwksUrl ? ["MCP_OAUTH_JWKS_URL"] : []),
      ...(scope ? ["MCP_OAUTH_SCOPE"] : []),
    ];
    if (configured.length > 0) {
      problems.push(
        `${configured.join(", ")} configures authorization for /mcp, which MCP_ENABLED=false does not serve: either enable MCP or remove the OAuth profile.`,
      );
    }
    return undefined;
  }
  if (!issuer) {
    const orphans = [
      ...(resource ? ["MCP_OAUTH_RESOURCE"] : []),
      ...(jwksUrl ? ["MCP_OAUTH_JWKS_URL"] : []),
      ...(scope ? ["MCP_OAUTH_SCOPE"] : []),
    ];
    if (orphans.length > 0) {
      problems.push(
        `${orphans.join(", ")} needs MCP_OAUTH_ISSUER: without an authorization server /mcp takes MCP_TOKEN and nothing else.`,
      );
    }
    return undefined;
  }

  // The issuer is compared with a token's `iss` claim exactly as written, trailing slash and all,
  // because that is how the comparison is defined; some authorization servers publish one.
  const issuerUrl = publicEndpoint(issuer, "MCP_OAUTH_ISSUER", problems);
  const canonical = resource
    ? publicEndpoint(resource, "MCP_OAUTH_RESOURCE", problems)
    : publicUrl === undefined
      ? undefined
      : publicEndpoint(
          `${publicUrl.replace(/\/$/, "")}/mcp`,
          "SIDECAR_PUBLIC_URL",
          problems,
        );
  const keys = jwksUrl
    ? publicEndpoint(jwksUrl, "MCP_OAUTH_JWKS_URL", problems)
    : undefined;
  const scopes = (scope ?? "").split(/\s+/).filter((entry) => entry !== "");
  const invalidScopes = scopes.filter((entry) => !SCOPE_TOKEN.test(entry));
  if (invalidScopes.length > 0) {
    problems.push(
      `MCP_OAUTH_SCOPE must be scopes separated by spaces (invalid: ${invalidScopes.join(", ")}).`,
    );
  }
  if (issuerUrl === undefined || canonical === undefined) return undefined;
  return {
    issuer: issuerUrl,
    resource: canonical,
    ...(keys === undefined ? {} : { jwksUrl: keys }),
    scopes,
  };
}

/**
 * An OAuth endpoint or resource identifier: absolute, https, and without a query or a fragment.
 * http is allowed on a loopback host, which is how the tests and a developer's own authorization
 * server run; a token that travelled over plain HTTP anywhere else is a token somebody else has.
 */
function publicEndpoint(
  raw: string,
  name: string,
  problems: string[],
): string | undefined {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    problems.push(`${name} must be an absolute https:// URL.`);
    return undefined;
  }
  // The same rule the sidecar applies to a key URL an authorization server advertises, so there is
  // one definition of what an OAuth endpoint may look like (oauth.ts).
  if (!isSecureEndpoint(url)) {
    problems.push(
      `${name} must be an https:// URL, or http:// on a loopback host for development.`,
    );
    return undefined;
  }
  if (url.search !== "" || url.hash !== "") {
    problems.push(`${name} must carry no query string and no fragment.`);
    return undefined;
  }
  return raw;
}

/** FCM_PROJECT_ID: optional and non-secret; unset means no Firebase Admin app or sender exists. */
function firebaseProjectId(env: Env, problems: string[]): string | undefined {
  const value = env.FCM_PROJECT_ID?.trim();
  if (!value) return undefined;
  if (!FIREBASE_PROJECT_ID.test(value)) {
    problems.push(
      "FCM_PROJECT_ID must be a 6-30 character lowercase Google Cloud project ID.",
    );
    return undefined;
  }
  return value;
}

/**
 * RELAY_URL, RELAY_SERVER_ID and RELAY_CREDENTIAL: the gateway that wakes this server's paired
 * phones on its behalf (SEE-144).
 *
 * All three or none, on the same all-or-nothing terms the gateway applies to its own push
 * settings and for the same reason: two of the three is a server that looks like it wakes phones
 * and never does, and the only sign of it would be an owner who stops getting notifications.
 *
 * No problem here ever repeats the credential. A configuration error is printed at startup, and a
 * startup line is pasted into places an operator does not control.
 */
function relayConfiguration(
  env: Env,
  problems: string[],
): RelayConfiguration | undefined {
  const relayUrl = env.RELAY_URL?.trim();
  const serverId = env.RELAY_SERVER_ID?.trim();
  const credential = env.RELAY_CREDENTIAL?.trim();
  const given = [relayUrl, serverId, credential].filter(
    (value) => value !== undefined && value !== "",
  );
  if (given.length === 0) return undefined;
  if (given.length < 3) {
    problems.push(
      "RELAY_URL, RELAY_SERVER_ID and RELAY_CREDENTIAL must all be set together: " +
        "they are one setting in three variables, and a partial one sends nothing.",
    );
    return undefined;
  }
  const configuration: RelayConfiguration = {
    relayUrl: relayUrl as string,
    serverId: serverId as string,
    credential: credential as string,
  };
  const problem = invalidRelayReason(configuration);
  if (problem !== undefined) {
    problems.push(`The gateway relay is not configured correctly: ${problem}.`);
    return undefined;
  }
  return configuration;
}

/**
 * SOLANA_RPC_URL: the JSON-RPC endpoint transfers are prepared against. It's optional, and
 * without it the sidecar simply serves no transfer tool. A problem names the variable and never
 * the value, which may hold an API key.
 */
function endpointUrl(env: Env, problems: string[]): string | undefined {
  const raw = env.SOLANA_RPC_URL?.trim();
  if (!raw) return undefined;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    problems.push("SOLANA_RPC_URL is not a URL.");
    return undefined;
  }
  if (url.protocol !== "https:" && url.protocol !== "http:") {
    problems.push("SOLANA_RPC_URL must be an http:// or https:// endpoint.");
    return undefined;
  }
  return url.toString();
}

/**
 * SIDECAR_PUBLIC_URL: the URL a pairing code gives the phone. Without it, the loopback URL the
 * sidecar listens on, which reaches a phone only over adb reverse.
 */
function publicUrlOf(
  env: Env,
  host: string | undefined,
  port: number | undefined,
  problems: string[],
): string | undefined {
  const raw = env.SIDECAR_PUBLIC_URL?.trim();
  if (!raw) {
    if (host === undefined || port === undefined) return undefined;
    if (WILDCARD_HOSTS.has(host)) {
      problems.push(
        "SIDECAR_PUBLIC_URL must be set when SIDECAR_HOST is a container wildcard.",
      );
      return undefined;
    }
    return `http://${host.includes(":") ? `[${host}]` : host}:${port}`;
  }
  const reason = invalidServerUrlReason(raw);
  if (reason !== undefined) {
    problems.push(`SIDECAR_PUBLIC_URL: ${reason}.`);
    return undefined;
  }
  return normalizeServerUrl(raw);
}

/**
 * MCP_ALLOWED_HOSTS: optional, comma-separated host names that /mcp accepts besides loopback,
 * for an agent that reaches the sidecar through a VPN address (docs/integrations/hermes.md).
 */
function allowedHosts(env: Env, problems: string[]): readonly string[] {
  const entries = (env.MCP_ALLOWED_HOSTS ?? "")
    .split(",")
    .map((entry) => entry.trim().toLowerCase())
    .filter((entry) => entry !== "");
  const invalid = entries.filter((entry) => !HOSTNAME.test(entry));
  if (invalid.length > 0) {
    problems.push(
      `MCP_ALLOWED_HOSTS must list host names or IP addresses without a scheme, port, or wildcard, separated by commas (invalid: ${invalid.join(", ")}).`,
    );
  }
  return entries;
}

/**
 * A setting that turns something off; unset or empty means on. It is spelled this way round so an
 * existing deployment that has never heard of it keeps the behaviour it has.
 */
function enabled(env: Env, name: string, problems: string[]): boolean {
  const value = env[name]?.trim().toLowerCase() ?? "";
  if (value === "" || value === "true") return true;
  if (value === "false") return false;
  problems.push(`${name} must be true or false.`);
  return true;
}

/** A setting that is true or false; unset or empty means false. */
function flag(env: Env, name: string, problems: string[]): boolean {
  const value = env[name]?.trim().toLowerCase() ?? "";
  if (value === "true") return true;
  if (value !== "" && value !== "false") {
    problems.push(`${name} must be true or false.`);
  }
  return false;
}

function required(
  env: Env,
  name: string,
  problems: string[],
): string | undefined {
  const value = env[name]?.trim();
  if (!value) {
    problems.push(`${name} is not set.`);
    return undefined;
  }
  return value;
}

function wholeNumber(
  env: Env,
  name: string,
  min: number,
  max: number,
  problems: string[],
): number | undefined {
  const raw = required(env, name, problems);
  if (raw === undefined) return undefined;
  const value = /^\d+$/.test(raw) ? Number(raw) : Number.NaN;
  if (!Number.isInteger(value) || value < min || value > max) {
    problems.push(`${name} must be a whole number from ${min} to ${max}.`);
    return undefined;
  }
  return value;
}

/** Like wholeNumber, but an unset or empty variable takes `fallback`. */
function optionalWholeNumber(
  env: Env,
  name: string,
  min: number,
  max: number,
  fallback: number,
  problems: string[],
): number | undefined {
  if (!env[name]?.trim()) return fallback;
  return wholeNumber(env, name, min, max, problems);
}

/** Like wholeNumber, but an unset or empty variable is absent. */
function optionalNumber(
  env: Env,
  name: string,
  min: number,
  max: number,
  problems: string[],
): number | undefined {
  if (!env[name]?.trim()) return undefined;
  return wholeNumber(env, name, min, max, problems);
}

/** [hint] is appended to the "not set" problem, for a token whose requirement has a way out. */
function token(
  env: Env,
  name: string,
  problems: string[],
  hint?: string,
): string | undefined {
  const value = env[name]?.trim();
  if (!value) {
    problems.push([`${name} is not set.`, hint].filter(Boolean).join(" "));
    return undefined;
  }
  if (value.startsWith(PLACEHOLDER_PREFIX)) {
    problems.push(
      `${name} still has the .env.example placeholder; set a random value (for example \`openssl rand -hex 32\`).`,
    );
    return undefined;
  }
  if (!BEARER_TOKEN.test(value)) {
    problems.push(
      `${name} may contain only letters, digits, and - . _ ~ + /, like any bearer token; \`openssl rand -hex 32\` makes a valid one.`,
    );
    return undefined;
  }
  if (value.length < MIN_TOKEN_LENGTH) {
    problems.push(
      `${name} must be at least ${MIN_TOKEN_LENGTH} characters long.`,
    );
    return undefined;
  }
  return value;
}
