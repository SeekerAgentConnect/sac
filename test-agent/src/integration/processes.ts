/**
 * The processes the Stage 7.1 integration run is made of (SEE-98): the real feed gateway, the
 * real publisher template binaries, and their two operator CLIs — each as its own process, on
 * loopback, with its own throwaway database.
 *
 * Nothing here is a stand-in for one of them. The point of this harness is that the components are
 * the shipped ones: `pnpm test:integration` builds the five binaries out of the two Go modules and
 * hands their paths in, so what the suite exercises is the code a deployment runs
 * (`docs/development/integration.md`). What *is* stood in for is the world outside — the provider
 * (`provider.ts`) and the wallet (`server-sdk/src/testing/wallet.ts`) — because a run that reached a
 * real provider would not be repeatable, and one that reached a real wallet would not be free.
 *
 * Every process keeps its output, and a failure prints it. A gateway that refused a publication for
 * a reason the assertion cannot see is otherwise a mystery, and the log line naming the problem is
 * always there.
 */
import {
  spawn,
  spawnSync,
  type ChildProcessWithoutNullStreams,
} from "node:child_process";
import { once } from "node:events";
import { setTimeout as delay } from "node:timers/promises";

/**
 * Every child still running, and one handler that stops them.
 *
 * `after` does not run when the runner is killed, and these are servers: a Ctrl+C or a CI timeout
 * would otherwise leave a gateway, two templates and their databases behind, holding ports on
 * somebody's machine. A signal that cannot be caught at all (`SIGKILL`, or the `SIGALRM` of a
 * `perl -e alarm` wrapper) still leaks them, so a killed run is worth a look at `lsof`.
 */
const running = new Set<ChildProcessWithoutNullStreams>();
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, () => {
    for (const child of running) child.kill("SIGKILL");
    process.exit(1);
  });
}

/** A process this harness started, and the two things a test needs from it. */
export interface Running {
  /** Everything it has printed so far, stdout and stderr in the order they arrived. */
  output(): string;
  /** Sends `signal` (SIGTERM by default) and waits for the exit. Safe to call twice. */
  stop(signal?: NodeJS.Signals): Promise<void>;
}

interface Process extends Running {
  /** Starts it again, on the same settings and the same database. */
  restart(): Promise<void>;
}

export interface GatewayOptions {
  /** The built `feed-gateway` binary. */
  readonly binary: string;
  /** The built `feed-gatewayctl` binary, which is the only way to register a publisher. */
  readonly control: string;
  /** Its SQLite file. Keeping it across a restart is how the suite proves documents survive one. */
  readonly databasePath: string;
  readonly readPort: number;
  readonly publisherPort: number;
  /** The broker's API origin, for the opt-in leg that runs a real Centrifugo. */
  readonly streamUrl?: string;
  readonly streamApiKey?: string;
  readonly streamTokenKey?: string;
}

export interface Gateway extends Process {
  /** What a phone reads, and the origin every manifest must name. */
  readonly origin: string;
  /** The publisher listener, which is a different port and serves nothing a subscriber may call. */
  readonly publishTo: string;
}

/** Starts the gateway and resolves once it answers `/healthz`. */
export async function startGateway(options: GatewayOptions): Promise<Gateway> {
  const origin = `http://127.0.0.1:${options.readPort}`;
  const environment: Record<string, string> = {
    BROADCAST_PUBLIC_URL: origin,
    BROADCAST_DATABASE_PATH: options.databasePath,
    BROADCAST_READ_ADDRESS: `127.0.0.1:${options.readPort}`,
    BROADCAST_PUBLISHER_ADDRESS: `127.0.0.1:${options.publisherPort}`,
    // Empty rather than absent: the gateway treats a partial stream configuration as an error, and
    // a run with no broker must say "publications are not streamed" rather than fail to start.
    BROADCAST_STREAM_URL: options.streamUrl ?? "",
    ...(options.streamUrl === undefined
      ? {}
      : {
          BROADCAST_STREAM_API_KEY: options.streamApiKey ?? "",
          BROADCAST_STREAM_TOKEN_KEY: options.streamTokenKey ?? "",
        }),
  };
  const process = await started({
    what: "the feed gateway",
    command: options.binary,
    environment,
    healthUrl: `${origin}/healthz`,
  });
  return {
    origin,
    publishTo: `http://127.0.0.1:${options.publisherPort}`,
    ...process,
  };
}

/**
 * Runs `feed-gatewayctl` against the gateway's database, and returns what it printed. The database
 * goes after the subcommand, which is where the CLI's own examples put it.
 */
export function feedGatewayctl(
  control: string,
  databasePath: string,
  args: readonly string[],
): string {
  const [subcommand, ...rest] = args;
  const answered = spawnSync(
    control,
    [subcommand ?? "", "--database", databasePath, ...rest],
    { encoding: "utf8" },
  );
  if (answered.status !== 0) {
    throw new Error(
      `feed-gatewayctl ${args.join(" ")} exited with ${String(answered.status)}: ${answered.stderr}${answered.stdout}`,
    );
  }
  return answered.stdout;
}

/**
 * Registers a publisher and returns the credential, which the gateway prints once and stores only
 * as a hash. There is no RPC that could do this, which is the point: a publisher exists because an
 * operator said so.
 */
export function register(
  control: string,
  databasePath: string,
  serverId: string,
  label: string,
): string {
  const printed = feedGatewayctl(control, databasePath, [
    "register",
    "--server",
    serverId,
    "--label",
    label,
  ]);
  const credential = /^[A-Za-z0-9_-]{43}$/m.exec(printed)?.[0];
  if (credential === undefined) {
    throw new Error(
      `no credential in what feed-gatewayctl printed:\n${printed}`,
    );
  }
  return credential;
}

export interface TemplateOptions {
  /** The built `copytrading` or `prediction` binary. */
  readonly binary: string;
  /** For the failure message, and for the log a privacy sweep reads. */
  readonly what: string;
  /** Its own settings, exactly: a template never sees this machine's environment. */
  readonly environment: Record<string, string>;
  /** Where its API listens, which is also where `publishctl` is pointed. */
  readonly apiPort: number;
}

export interface Template extends Process {
  readonly apiUrl: string;
}

/** Starts a publisher template and resolves once its own API answers `/healthz`. */
export async function startTemplate(
  options: TemplateOptions,
): Promise<Template> {
  const apiUrl = `http://127.0.0.1:${options.apiPort}`;
  const process = await started({
    what: options.what,
    command: options.binary,
    environment: {
      ...options.environment,
      PUBLISHER_API_ADDRESS: `127.0.0.1:${options.apiPort}`,
    },
    healthUrl: `${apiUrl}/healthz`,
  });
  return { apiUrl, ...process };
}

export interface Answer {
  readonly code: number | null;
  readonly stdout: string;
  readonly stderr: string;
  /** True when it was still running at `timeoutMs` and was killed. Always false without one. */
  readonly timedOut: boolean;
}

/**
 * Runs `publishctl` as the operator does, against a template's own API.
 *
 * It is asynchronous, and that is not a style choice: a cycle the CLI asks for makes the template
 * call the provider, and the provider in this run is served by *this* process. `spawnSync` here
 * would block the event loop that has to answer it, and the template would time out waiting for a
 * server it could see listening.
 */
export async function publishctl(
  binary: string,
  template: { readonly apiUrl: string; readonly token: string },
  args: readonly string[],
): Promise<Answer> {
  return run(binary, args, {
    PUBLISHER_API_URL: template.apiUrl,
    PUBLISHER_API_TOKEN: template.token,
  });
}

/** `publishctl`, and a failure that says what the CLI said rather than only that it failed. */
export async function publish(
  binary: string,
  template: { readonly apiUrl: string; readonly token: string },
  args: readonly string[],
): Promise<string> {
  const answer = await publishctl(binary, template, args);
  if (answer.code !== 0) {
    throw new Error(
      `publishctl ${args.join(" ")} exited with ${String(answer.code)}: ${answer.stderr}${answer.stdout}`,
    );
  }
  return answer.stdout;
}

/**
 * One command, to completion, with only the settings given, and never blocking the loop.
 *
 * `timeoutMs` is for a command that is expected to *stop*: a binary that should have refused its
 * configuration and exited, say. Without a bound, a binary that wrongly kept running would hang
 * the suite instead of failing the check that says it must not.
 */
export function run(
  command: string,
  args: readonly string[],
  environment: Record<string, string> = {},
  timeoutMs?: number,
): Promise<Answer> {
  const child = spawn(command, [...args], {
    env: { PATH: process.env.PATH ?? "", ...environment },
  });
  let stdout = "";
  let stderr = "";
  child.stdout.setEncoding("utf8").on("data", (chunk: string) => {
    stdout += chunk;
  });
  child.stderr.setEncoding("utf8").on("data", (chunk: string) => {
    stderr += chunk;
  });
  return new Promise((resolve) => {
    const bound =
      timeoutMs === undefined
        ? undefined
        : setTimeout(() => {
            child.kill("SIGKILL");
            resolve({ code: null, stdout, stderr, timedOut: true });
          }, timeoutMs);
    child.on("close", (code) => {
      if (bound !== undefined) clearTimeout(bound);
      resolve({ code, stdout, stderr, timedOut: false });
    });
  });
}

interface Started {
  readonly what: string;
  readonly command: string;
  readonly environment: Record<string, string>;
  readonly healthUrl: string;
}

/**
 * Spawns one of the Go binaries with only the settings given, waits until it is listening, and
 * keeps its output. A binary that exits while being waited for fails the test with its own log,
 * because a configuration problem is printed by the process and nowhere else.
 */
async function started(options: Started): Promise<Process> {
  let output = "";
  let child = started_(options);
  const collect = (): void => {
    child.stdout.setEncoding("utf8").on("data", (chunk: string) => {
      output += chunk;
    });
    child.stderr.setEncoding("utf8").on("data", (chunk: string) => {
      output += chunk;
    });
  };
  collect();
  const said = (): string => output;
  await listening(options, child, said);
  return {
    output: () => output,
    async stop(signal: NodeJS.Signals = "SIGTERM") {
      if (child.exitCode !== null || child.signalCode !== null) return;
      const exited = once(child, "exit");
      child.kill(signal);
      await exited;
    },
    async restart() {
      if (child.exitCode === null && child.signalCode === null) {
        const exited = once(child, "exit");
        child.kill("SIGTERM");
        await exited;
      }
      output += `\n--- ${options.what} restarted ---\n`;
      child = started_(options);
      collect();
      await listening(options, child, said);
    },
  };
}

/** Spawns one child and remembers it until it exits, so a killed run can still stop it. */
function started_(options: Started): ChildProcessWithoutNullStreams {
  const child = spawn(options.command, [], {
    env: { PATH: process.env.PATH ?? "", ...options.environment },
  });
  running.add(child);
  child.once("exit", () => running.delete(child));
  return child;
}

/** Polls the health endpoint until it answers, or the process dies, or twenty seconds pass. */
async function listening(
  options: Started,
  child: ChildProcessWithoutNullStreams,
  said: () => string,
): Promise<void> {
  const deadline = Date.now() + 20_000;
  for (;;) {
    if (child.exitCode !== null || child.signalCode !== null) {
      throw new Error(
        `${options.what} exited with ${String(child.exitCode)}:\n${said()}`,
      );
    }
    try {
      const answer = await fetch(options.healthUrl);
      if (answer.ok) return;
    } catch {
      // Not listening yet, which is the ordinary case for the first few tries.
    }
    if (Date.now() > deadline) {
      throw new Error(
        `${options.what} never answered ${options.healthUrl}:\n${said()}`,
      );
    }
    await delay(50);
  }
}
