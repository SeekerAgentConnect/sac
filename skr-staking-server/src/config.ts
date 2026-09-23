/**
 * This server's configuration, read from the environment.
 *
 * Every name is prefixed `SKR_STAKING_`, and that is the point rather than a style choice: this
 * server is meant to run beside the general MCP server, often on one host and from one `.env`, and
 * two servers that read `SIDECAR_PORT` cannot both be configured. A shared name would make "deploy
 * them side by side" mean "edit one of them first".
 *
 * Validation collects every problem before reporting any, so a first run says everything that is
 * wrong rather than one thing at a time. No value that could be a secret is ever echoed: an
 * endpoint URL can carry an API key, so a problem with it names the variable and stops there.
 */
import { homedir } from "node:os";
import { isAbsolute, join, resolve } from "node:path";
import { PublicKey } from "@solana/web3.js";

/** The live diagnostic this server does not serve. The SDK wants a timeout; nothing uses it. */
export const UNUSED_LIVE_COMMAND_TIMEOUT_SECONDS = 60;

/** Where durable state goes when nothing says otherwise. */
export const DEFAULT_DATA_DIRECTORY = join(
  homedir(),
  ".seeker-agent-connect",
  "skr-staking-server",
);

const DEFAULT_DATABASE_FILE = "skr-staking-server.db";
const DEFAULT_REQUEST_TTL_SECONDS = 86_400;
const DEFAULT_PENDING_LIMIT = 100;
const DEFAULT_PAIRING_TOKEN_TTL_SECONDS = 600;
const DEFAULT_RPC_TIMEOUT_MS = 10_000;
const LEAST_TOKEN_LENGTH = 32;
const MOST_PENDING_LIMIT = 10_000;

const LOOPBACK = new Set(["127.0.0.1", "::1", "localhost"]);
const WILDCARD = new Set(["0.0.0.0", "::"]);

export type Env = Readonly<Record<string, string | undefined>>;

export class ConfigError extends Error {
  readonly problems: readonly string[];

  constructor(problems: readonly string[]) {
    super(
      [
        "Invalid SKR staking server configuration:",
        ...problems.map((problem) => `  - ${problem}`),
      ].join("\n"),
    );
    this.name = "ConfigError";
    this.problems = problems;
  }
}

export interface Config {
  readonly host: string;
  readonly port: number;
  readonly mcpToken: string;
  readonly publicUrl: string | undefined;
  readonly dataDirectory: string;
  readonly databasePath: string;
  readonly rpcUrl: string;
  readonly rpcTimeoutMs: number;
  readonly requestTtlSeconds: number;
  readonly pendingLimit: number;
  readonly pairingTokenTtlSeconds: number;
  readonly allowedHosts: readonly string[];
  /** Which guardian's pool to stake into; the official one unless told otherwise. */
  readonly guardian: PublicKey | undefined;
  readonly h2c: boolean;
}

/** Reads and checks the configuration, or throws a `ConfigError` naming every problem. */
export function loadConfig(env: Env = process.env): Config {
  const problems: string[] = [];

  const host = required(env, "SKR_STAKING_HOST", problems);
  if (host !== undefined && !LOOPBACK.has(host) && !WILDCARD.has(host)) {
    problems.push(
      "SKR_STAKING_HOST must be a loopback address (127.0.0.1, ::1, localhost) or a wildcard (0.0.0.0, ::).",
    );
  }
  const port = wholeNumber(env, "SKR_STAKING_PORT", problems, 1, 65_535);

  // One credential only, and it is the agent's. The phone authenticates with what pairing issued
  // it, so there is no second shared secret to configure, to rotate, or to accidentally give an
  // agent — which is the one credential that would let it answer its own requests.
  const mcpToken = token(env, "SKR_STAKING_MCP_TOKEN", problems);

  const publicUrl = publicOrigin(env, host, problems);
  const dataDirectory = directory(env, problems);
  const databasePath = database(env, dataDirectory, problems);

  const rpcUrl = endpoint(env, problems);
  const rpcTimeoutMs =
    optionalWholeNumber(
      env,
      "SKR_STAKING_RPC_TIMEOUT_MS",
      problems,
      1_000,
      60_000,
    ) ?? DEFAULT_RPC_TIMEOUT_MS;

  const requestTtlSeconds =
    optionalWholeNumber(
      env,
      "SKR_STAKING_REQUEST_TTL_SECONDS",
      problems,
      60,
      604_800,
    ) ?? DEFAULT_REQUEST_TTL_SECONDS;
  const pendingLimit =
    optionalWholeNumber(
      env,
      "SKR_STAKING_PENDING_LIMIT",
      problems,
      1,
      MOST_PENDING_LIMIT,
    ) ?? DEFAULT_PENDING_LIMIT;
  const pairingTokenTtlSeconds =
    optionalWholeNumber(
      env,
      "SKR_STAKING_PAIRING_TOKEN_TTL_SECONDS",
      problems,
      60,
      3_600,
    ) ?? DEFAULT_PAIRING_TOKEN_TTL_SECONDS;

  const guardian = optionalAddress(env, "SKR_STAKING_GUARDIAN", problems);
  const h2c = flag(env, "SKR_STAKING_H2C", problems);

  if (problems.length > 0) throw new ConfigError(problems);

  return {
    host: host as string,
    port: port as number,
    mcpToken: mcpToken as string,
    publicUrl,
    dataDirectory,
    databasePath,
    rpcUrl: rpcUrl as string,
    rpcTimeoutMs,
    requestTtlSeconds,
    pendingLimit,
    pairingTokenTtlSeconds,
    allowedHosts: hosts(env, problems),
    guardian,
    h2c,
  };
}

function required(
  env: Env,
  name: string,
  problems: string[],
): string | undefined {
  const value = env[name]?.trim() ?? "";
  if (value === "") {
    problems.push(`${name} is required.`);
    return undefined;
  }
  return value;
}

/**
 * A credential this server accepts. It has to be long enough to be worth having and must not still
 * be the placeholder from the example file — a deployment that kept it has no credential at all.
 */
function token(env: Env, name: string, problems: string[]): string | undefined {
  const value = required(env, name, problems);
  if (value === undefined) return undefined;
  if (value.includes("REPLACE_WITH_")) {
    problems.push(`${name} is still the placeholder from .env.example.`);
    return undefined;
  }
  if (value.length < LEAST_TOKEN_LENGTH) {
    problems.push(
      `${name} must be at least ${LEAST_TOKEN_LENGTH} characters; generate one with "openssl rand -hex 32".`,
    );
    return undefined;
  }
  if (/\s/.test(value)) {
    problems.push(
      `${name} must contain no whitespace: it is sent as a bearer token.`,
    );
    return undefined;
  }
  return value;
}

/**
 * The Solana endpoint. Required, unlike the general server's: a staking server with no chain to
 * read cannot answer a status query, cannot check whether an action is possible, and cannot prepare
 * anything, so starting one would only produce a connection that refuses every call.
 */
function endpoint(env: Env, problems: string[]): string | undefined {
  const value = required(env, "SKR_STAKING_RPC_URL", problems);
  if (value === undefined) return undefined;
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    // Never the value: it can carry an API key.
    problems.push("SKR_STAKING_RPC_URL must be an absolute http or https URL.");
    return undefined;
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    problems.push("SKR_STAKING_RPC_URL must be an http or https URL.");
    return undefined;
  }
  return value;
}

function publicOrigin(
  env: Env,
  host: string | undefined,
  problems: string[],
): string | undefined {
  const value = env.SKR_STAKING_PUBLIC_URL?.trim() ?? "";
  if (value === "") {
    if (host !== undefined && WILDCARD.has(host)) {
      // A wildcard bind has no address a phone could be told to pair with.
      problems.push(
        "SKR_STAKING_PUBLIC_URL is required when SKR_STAKING_HOST is a wildcard: a pairing code has to name a reachable origin.",
      );
    }
    return undefined;
  }
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    problems.push("SKR_STAKING_PUBLIC_URL must be an absolute URL.");
    return undefined;
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    problems.push("SKR_STAKING_PUBLIC_URL must be an http or https URL.");
    return undefined;
  }
  if (url.pathname !== "/" || url.search !== "" || url.hash !== "") {
    problems.push(
      "SKR_STAKING_PUBLIC_URL must be an origin, with no path, query, or fragment.",
    );
    return undefined;
  }
  return url.origin;
}

function directory(env: Env, problems: string[]): string {
  const value = env.SKR_STAKING_DATA_DIR?.trim() ?? "";
  if (value === "") return DEFAULT_DATA_DIRECTORY;
  if (!isAbsolute(value)) {
    problems.push("SKR_STAKING_DATA_DIR must be an absolute path.");
    return DEFAULT_DATA_DIRECTORY;
  }
  return value;
}

/**
 * The database file. A relative path resolves under the data directory rather than the working
 * directory, so the same configuration finds the same database whichever directory it is started
 * from — a server that silently paired again because it was started from elsewhere would look like
 * a phone that had forgotten it.
 */
function database(env: Env, dataDirectory: string, problems: string[]): string {
  const value = env.SKR_STAKING_DATABASE_PATH?.trim() ?? "";
  if (value === "") return join(dataDirectory, DEFAULT_DATABASE_FILE);
  if (value === ":memory:") {
    problems.push(
      "SKR_STAKING_DATABASE_PATH must be a file: an in-memory database would forget every pairing on restart.",
    );
    return join(dataDirectory, DEFAULT_DATABASE_FILE);
  }
  return isAbsolute(value) ? value : resolve(dataDirectory, value);
}

function hosts(env: Env, problems: string[]): readonly string[] {
  const value = env.SKR_STAKING_ALLOWED_HOSTS?.trim() ?? "";
  if (value === "") return [];
  const names = value
    .split(",")
    .map((name) => name.trim().toLowerCase())
    .filter((name) => name !== "");
  for (const name of names) {
    if (/[^a-z0-9.:\-[\]]/.test(name)) {
      problems.push(
        `SKR_STAKING_ALLOWED_HOSTS contains "${name}", which is not a host name or address.`,
      );
    }
  }
  return names;
}

function optionalAddress(
  env: Env,
  name: string,
  problems: string[],
): PublicKey | undefined {
  const value = env[name]?.trim() ?? "";
  if (value === "") return undefined;
  try {
    return new PublicKey(value);
  } catch {
    problems.push(`${name} must be a base58 address of exactly 32 bytes.`);
    return undefined;
  }
}

function wholeNumber(
  env: Env,
  name: string,
  problems: string[],
  least: number,
  most: number,
): number | undefined {
  const value = required(env, name, problems);
  if (value === undefined) return undefined;
  return bounded(name, value, problems, least, most);
}

function optionalWholeNumber(
  env: Env,
  name: string,
  problems: string[],
  least: number,
  most: number,
): number | undefined {
  const value = env[name]?.trim() ?? "";
  if (value === "") return undefined;
  return bounded(name, value, problems, least, most);
}

function bounded(
  name: string,
  value: string,
  problems: string[],
  least: number,
  most: number,
): number | undefined {
  if (!/^(?:0|[1-9][0-9]*)$/.test(value)) {
    problems.push(`${name} must be a whole number from ${least} to ${most}.`);
    return undefined;
  }
  const parsed = Number(value);
  if (parsed < least || parsed > most) {
    problems.push(`${name} must be a whole number from ${least} to ${most}.`);
    return undefined;
  }
  return parsed;
}

/** A setting that is true or false; unset or empty means false. */
function flag(env: Env, name: string, problems: string[]): boolean {
  const value = env[name]?.trim().toLowerCase() ?? "";
  if (value === "") return false;
  if (value === "true") return true;
  if (value === "false") return false;
  problems.push(`${name} must be true or false.`);
  return false;
}
