import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createServer, type AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, before, describe, it } from "node:test";
import { fileURLToPath } from "node:url";

// The real sidecar (in process) and its Connect test client acting as the phone.
import { startSidecar, type Sidecar } from "../../sidecar/src/server.ts";
import {
  Code,
  ConnectError,
  connectPhone,
} from "../../sidecar/src/testing/clients.ts";

const MAIN = fileURLToPath(new URL("./main.ts", import.meta.url));
const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);
const WRONG_TOKEN = "w".repeat(64);

interface Run {
  readonly code: number | null;
  readonly stdout: string;
  readonly stderr: string;
}

const outputs: string[] = [];
let sidecar: Sidecar; // 30 s deadline
let quickSidecar: Sidecar; // 1 s deadline

/** Runs the CLI as a real process, with only the given environment. */
function agent(
  args: readonly string[],
  env: Record<string, string>,
  nodeArgs: readonly string[] = [],
): Promise<Run> {
  const child = spawn(process.execPath, [...nodeArgs, MAIN, ...args], {
    env: { PATH: process.env.PATH ?? "", ...env },
  });
  let stdout = "";
  let stderr = "";
  child.stdout
    .setEncoding("utf8")
    .on("data", (chunk: string) => (stdout += chunk));
  child.stderr
    .setEncoding("utf8")
    .on("data", (chunk: string) => (stderr += chunk));
  return new Promise((resolve) => {
    child.on("close", (code) => {
      outputs.push(stdout, stderr);
      resolve({ code, stdout, stderr });
    });
  });
}

function envFor(
  target: Sidecar,
  overrides: Record<string, string> = {},
): Record<string, string> {
  return {
    MCP_URL: `${target.url}/mcp`,
    MCP_TOKEN,
    PHONE_TOKEN, // present in a developer's .env; the CLI must not print it either
    LIVE_COMMAND_TIMEOUT_SECONDS: target === quickSidecar ? "1" : "30",
    ...overrides,
  };
}

async function closedPort(): Promise<number> {
  const server = createServer().listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  const { port } = server.address() as AddressInfo;
  await new Promise((resolve) => server.close(resolve));
  return port;
}

function isConnectError(code: Code): (error: unknown) => boolean {
  return (error) => error instanceof ConnectError && error.code === code;
}

before(async () => {
  const base = {
    host: "127.0.0.1",
    port: 0,
    mcpToken: MCP_TOKEN,
    phoneToken: PHONE_TOKEN,
  };
  const log = (): void => undefined;
  sidecar = await startSidecar(
    { ...base, liveCommandTimeoutSeconds: 30 },
    { log },
  );
  quickSidecar = await startSidecar(
    { ...base, liveCommandTimeoutSeconds: 1 },
    { log },
  );
});

after(async () => {
  await Promise.all([sidecar.close(), quickSidecar.close()]);
});

describe("pnpm agent", () => {
  it("prints the acknowledgement of the same command once the phone taps OK", async () => {
    const phone = await connectPhone(sidecar.url, PHONE_TOKEN);
    try {
      const run = agent(["hello", "Hello Seeker 👋"], envFor(sidecar));
      const command = await phone.nextCommand();
      assert.equal(command.text, "Hello Seeker 👋");
      await phone.acknowledge(command.id);
      const { code, stdout, stderr } = await run;
      assert.equal(code, 0, stderr);
      assert.deepEqual(JSON.parse(stdout), { id: command.id, result: "OK" });
    } finally {
      phone.disconnect();
    }
  });

  it("sends Hello Seeker when no text is given", async () => {
    const phone = await connectPhone(sidecar.url, PHONE_TOKEN);
    try {
      const run = agent(["hello"], envFor(sidecar));
      const command = await phone.nextCommand();
      assert.equal(command.text, "Hello Seeker");
      await phone.acknowledge(command.id);
      assert.equal((await run).code, 0);
    } finally {
      phone.disconnect();
    }
  });

  it("exits 4 with OFFLINE when no phone is watching", async () => {
    const { code, stdout, stderr } = await agent(
      ["hello", "Anyone there?"],
      envFor(sidecar),
    );
    assert.equal(code, 4);
    assert.equal(stdout, "");
    assert.match(stderr, /^OFFLINE: /m);
  });

  it("exits 5 with BUSY while another command waits", async () => {
    const phone = await connectPhone(sidecar.url, PHONE_TOKEN);
    try {
      const first = agent(["hello", "first"], envFor(sidecar));
      const command = await phone.nextCommand();
      const second = await agent(["hello", "second"], envFor(sidecar));
      assert.equal(second.code, 5);
      assert.match(second.stderr, /^BUSY: /m);
      await phone.acknowledge(command.id);
      assert.equal((await first).code, 0);
    } finally {
      phone.disconnect();
    }
  });

  it("exits 8 with INVALID_TEXT for blank text", async () => {
    const phone = await connectPhone(sidecar.url, PHONE_TOKEN);
    try {
      const { code, stderr } = await agent(["hello", "   "], envFor(sidecar));
      assert.equal(code, 8);
      assert.match(stderr, /^INVALID_TEXT: /m);
    } finally {
      phone.disconnect();
    }
  });

  it("exits 6 when the sidecar's deadline passes without OK", async () => {
    const phone = await connectPhone(quickSidecar.url, PHONE_TOKEN);
    try {
      const run = agent(["hello", "Nobody taps OK"], envFor(quickSidecar));
      await phone.nextCommand();
      const { code, stderr } = await run;
      assert.equal(code, 6);
      assert.match(stderr, /^TIMEOUT: no acknowledgement within 1 seconds/m);
    } finally {
      phone.disconnect();
    }
  });

  it("exits 6 on its own client timeout and cancels the waiting command", async () => {
    const phone = await connectPhone(sidecar.url, PHONE_TOKEN);
    try {
      const run = agent(
        ["hello", "Too slow", "--timeout", "1"],
        envFor(sidecar),
      );
      const command = await phone.nextCommand();
      const { code, stderr } = await run;
      assert.equal(code, 6);
      assert.match(stderr, /client timeout/);
      await assert.rejects(
        phone.acknowledge(command.id),
        isConnectError(Code.Canceled),
      );
    } finally {
      phone.disconnect();
    }
  });

  it("exits 3 when the sidecar is unreachable or rejects the token", async () => {
    const unreachable = await agent(
      ["hello"],
      envFor(sidecar, {
        MCP_URL: `http://127.0.0.1:${await closedPort()}/mcp`,
      }),
    );
    assert.equal(unreachable.code, 3);
    assert.match(
      unreachable.stderr,
      /could not reach http:\/\/127\.0\.0\.1:\d+\/mcp/,
    );
    const rejected = await agent(
      ["hello"],
      envFor(sidecar, { MCP_TOKEN: WRONG_TOKEN }),
    );
    assert.equal(rejected.code, 3);
    assert.match(
      rejected.stderr,
      /the sidecar rejected MCP_TOKEN \(HTTP 401\)/,
    );
    const phoneToken = await agent(
      ["hello"],
      envFor(sidecar, { MCP_TOKEN: PHONE_TOKEN }),
    );
    assert.equal(phoneToken.code, 3);
  });

  it("exits 2 for missing configuration and bad arguments", async () => {
    const missing = await agent(["hello"], { MCP_URL: `${sidecar.url}/mcp` });
    assert.equal(missing.code, 2);
    assert.match(missing.stderr, /MCP_TOKEN is not set/);
    assert.equal((await agent(["launch"], envFor(sidecar))).code, 2);
    assert.equal((await agent(["hello", "a", "b"], envFor(sidecar))).code, 2);
    assert.equal(
      (await agent(["hello", "--timeout", "soon"], envFor(sidecar))).code,
      2,
    );
    const help = await agent(["--help"], {});
    assert.equal(help.code, 0);
    assert.match(help.stdout, /^Usage: pnpm agent/);
  });

  it("lists the sidecar's tools", async () => {
    const { code, stdout } = await agent(["tools"], envFor(sidecar));
    assert.equal(code, 0);
    const tools = JSON.parse(stdout) as { name: string }[];
    assert.deepEqual(
      tools.map((tool) => tool.name),
      ["vault_display_command"],
    );
  });

  it("reads .env while variables already in the environment win", async () => {
    const dir = mkdtempSync(join(tmpdir(), "test-agent-env-"));
    try {
      // .env points at a dead port but holds the token; the environment's MCP_URL wins.
      writeFileSync(
        join(dir, ".env"),
        `MCP_URL=http://127.0.0.1:${await closedPort()}/mcp\nMCP_TOKEN=${MCP_TOKEN}\nLIVE_COMMAND_TIMEOUT_SECONDS=30\n`,
      );
      const { code, stdout } = await agent(
        ["tools"],
        { MCP_URL: `${sidecar.url}/mcp` },
        [`--env-file-if-exists=${join(dir, ".env")}`],
      );
      assert.equal(code, 0);
      assert.match(stdout, /vault_display_command/);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("never prints a token", () => {
    assert.ok(outputs.length > 0);
    for (const output of outputs) {
      for (const secret of [MCP_TOKEN, PHONE_TOKEN, WRONG_TOKEN]) {
        assert.ok(!output.includes(secret), output);
      }
    }
  });
});
