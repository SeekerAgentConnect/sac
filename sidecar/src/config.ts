/**
 * Stage 1 sidecar configuration.
 *
 * `pnpm dev:sidecar` loads the ignored root `.env` file with Node's
 * `--env-file-if-exists`; variables already set in the environment take
 * precedence over the file. Problems name the variable but never echo a
 * token value.
 */

export interface SidecarConfig {
  readonly host: string;
  readonly port: number;
  readonly mcpToken: string;
  readonly phoneToken: string;
  readonly liveCommandTimeoutSeconds: number;
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

// Stage 1 only listens on the local machine; remote agents reach it through a tunnel.
const LOOPBACK_HOSTS = new Set(["127.0.0.1", "::1", "localhost"]);
const PLACEHOLDER_PREFIX = "REPLACE_WITH_";
const MIN_TOKEN_LENGTH = 32;
const MAX_LIVE_COMMAND_TIMEOUT_SECONDS = 3600;

export function loadSidecarConfig(env: Env): SidecarConfig {
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

  if (
    problems.length > 0 ||
    host === undefined ||
    port === undefined ||
    mcpToken === undefined ||
    phoneToken === undefined ||
    liveCommandTimeoutSeconds === undefined
  ) {
    throw new ConfigError(problems);
  }
  return { host, port, mcpToken, phoneToken, liveCommandTimeoutSeconds };
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

function token(env: Env, name: string, problems: string[]): string | undefined {
  const value = required(env, name, problems);
  if (value === undefined) return undefined;
  if (value.startsWith(PLACEHOLDER_PREFIX)) {
    problems.push(
      `${name} still has the .env.example placeholder; set a random value (for example \`openssl rand -hex 32\`).`,
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
