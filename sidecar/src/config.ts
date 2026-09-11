/**
 * Sidecar configuration.
 *
 * `pnpm dev:sidecar` loads the ignored root `.env` file with Node's
 * `--env-file-if-exists`; variables already set in the environment take
 * precedence over the file. Problems name the variable but never echo a
 * token value.
 */
import { fileURLToPath } from "node:url";

import { invalidServerUrlReason, normalizeServerUrl } from "./pairing/uri.ts";
import {
  MAX_EXPIRES_IN_SECONDS,
  MIN_EXPIRES_IN_SECONDS,
} from "./requests/store.ts";

export interface SidecarConfig {
  readonly host: string;
  readonly port: number;
  readonly mcpToken: string;
  /** The Stage 1 live diagnostic's development token; the durable phone API needs pairing. */
  readonly phoneToken: string;
  readonly liveCommandTimeoutSeconds: number;
  /** Host names besides loopback that /mcp accepts in Host and Origin (MCP_ALLOWED_HOSTS). */
  readonly mcpAllowedHosts?: readonly string[];
  /**
   * Serves vault_request_ack, which queues a wallet-free acknowledgement, for development and demos
   * (MCP_DEMO_TOOLS). Off unless it's set.
   */
  readonly demoTools?: boolean;
  /** The SQLite file for durable requests (DATABASE_PATH). ":memory:" keeps them in memory, for tests. */
  readonly databasePath: string;
  /** The lifetime of a request whose agent doesn't choose one (REQUEST_TTL_SECONDS). */
  readonly requestTtlSeconds: number;
  /** The most PENDING requests the phone may have at once (REQUEST_PENDING_LIMIT). */
  readonly pendingLimit: number;
  /**
   * The URL the phone pairs with and calls (SIDECAR_PUBLIC_URL): HTTPS, or by default the loopback
   * URL, for development over adb reverse. Only `pnpm pair` reads it.
   */
  readonly publicUrl?: string;
  /** How long a pairing code works (PAIRING_TOKEN_TTL_SECONDS). Only `pnpm pair` reads it. */
  readonly pairingTokenTtlSeconds?: number;
}

export class ConfigError extends Error {
  readonly problems: readonly string[];

  constructor(problems: readonly string[]) {
    super(
      [
        "Invalid sidecar configuration:",
        ...problems.map((problem) => `  - ${problem}`),
      ].join("\n"),
    );
    this.name = "ConfigError";
    this.problems = problems;
  }
}

type Env = Readonly<Record<string, string | undefined>>;

/** Where durable requests live unless DATABASE_PATH says otherwise: `sidecar/data/sidecar.db`. */
export const DEFAULT_DATABASE_PATH = fileURLToPath(
  new URL("../data/sidecar.db", import.meta.url),
);

// Stage 1 only listens on the local machine; remote agents reach it through a tunnel.
const LOOPBACK_HOSTS = new Set(["127.0.0.1", "::1", "localhost"]);
const PLACEHOLDER_PREFIX = "REPLACE_WITH_";
const MIN_TOKEN_LENGTH = 32;
// RFC 6750 b64token: the characters a bearer token can carry in an Authorization header.
const BEARER_TOKEN = /^[A-Za-z0-9\-._~+/]+=*$/;
// A DNS name or an IPv4 address, or an IPv6 address in brackets; no scheme, port, or wildcard.
const HOSTNAME =
  /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*$|^\[[0-9a-f:.]+\]$/;
const MAX_LIVE_COMMAND_TIMEOUT_SECONDS = 3600;
const DEFAULT_REQUEST_TTL_SECONDS = 24 * 60 * 60;
const DEFAULT_PENDING_LIMIT = 100;
const MAX_PENDING_LIMIT = 10_000;
const DEFAULT_PAIRING_TOKEN_TTL_SECONDS = 600;
const MIN_PAIRING_TOKEN_TTL_SECONDS = 60;
const MAX_PAIRING_TOKEN_TTL_SECONDS = 3600;

export function loadSidecarConfig(env: Env): SidecarConfig & {
  readonly demoTools: boolean;
  readonly publicUrl: string;
  readonly pairingTokenTtlSeconds: number;
} {
  const problems: string[] = [];

  const host = required(env, "SIDECAR_HOST", problems);
  if (host !== undefined && !LOOPBACK_HOSTS.has(host)) {
    problems.push(
      "SIDECAR_HOST must be a loopback address (127.0.0.1, ::1, or localhost) in Stage 1.",
    );
  }
  const port = wholeNumber(env, "SIDECAR_PORT", 1, 65_535, problems);
  const mcpToken = token(env, "MCP_TOKEN", problems);
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
  const mcpAllowedHosts = allowedHosts(env, problems);
  const demoTools = flag(env, "MCP_DEMO_TOOLS", problems);
  const databasePath = env.DATABASE_PATH?.trim() || DEFAULT_DATABASE_PATH;
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

  if (
    problems.length > 0 ||
    host === undefined ||
    port === undefined ||
    mcpToken === undefined ||
    phoneToken === undefined ||
    liveCommandTimeoutSeconds === undefined ||
    requestTtlSeconds === undefined ||
    pendingLimit === undefined ||
    publicUrl === undefined ||
    pairingTokenTtlSeconds === undefined
  ) {
    throw new ConfigError(problems);
  }
  return {
    host,
    port,
    mcpToken,
    phoneToken,
    liveCommandTimeoutSeconds,
    mcpAllowedHosts,
    demoTools,
    databasePath,
    requestTtlSeconds,
    pendingLimit,
    publicUrl,
    pairingTokenTtlSeconds,
  };
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

function token(env: Env, name: string, problems: string[]): string | undefined {
  const value = required(env, name, problems);
  if (value === undefined) return undefined;
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
