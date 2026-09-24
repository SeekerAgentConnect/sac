/**
 * What `seeker-skr-staking-mcp pair` prints.
 *
 * The operator command is the fallback for whoever does have a shell on the host, and since
 * SEE-149 it shows the same three things an agent can hand the owner: the QR code, the pairing URI
 * and the HTTPS landing link. All three have to carry the same code, and the printed link must not
 * put the token anywhere but the fragment.
 */
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, describe, it } from "node:test";
import { fileURLToPath } from "node:url";

import {
  decodeLandingFragment,
  parsePairingUri,
} from "@seeker-vault/server-sdk";

const CLI = fileURLToPath(new URL("../cli.ts", import.meta.url));
const MCP_TOKEN = "s".repeat(64);
const directories: string[] = [];

after(() => {
  for (const directory of directories) {
    rmSync(directory, { recursive: true, force: true });
  }
});

function run(
  args: readonly string[],
  env: Record<string, string> = {},
): { code: number | null; stdout: string; stderr: string } {
  const directory = mkdtempSync(join(tmpdir(), "skr-staking-cli-test-"));
  directories.push(directory);
  const result = spawnSync(process.execPath, [CLI, "pair", ...args], {
    encoding: "utf8",
    env: {
      PATH: process.env.PATH,
      SKR_STAKING_HOST: "127.0.0.1",
      SKR_STAKING_PORT: "8090",
      SKR_STAKING_MCP_TOKEN: MCP_TOKEN,
      SKR_STAKING_RPC_URL: "https://api.mainnet-beta.solana.com",
      SKR_STAKING_DATA_DIR: directory,
      ...env,
    },
  });
  return { code: result.status, stdout: result.stdout, stderr: result.stderr };
}

function lineStartingWith(output: string, prefix: string): string {
  const line = output.split("\n").find((text) => text.startsWith(prefix));
  assert.ok(line, `no line starting with ${prefix} in:\n${output}`);
  return line;
}

describe("seeker-skr-staking-mcp pair", () => {
  it("prints the QR code, the pairing URI and the HTTPS landing link for the public URL", () => {
    const origin = "https://staking.example.com";
    const { code, stdout } = run([], { SKR_STAKING_PUBLIC_URL: origin });
    assert.equal(code, 0);
    assert.match(
      stdout,
      /pair it with the SKR staking server at https:\/\/staking\.example\.com\./,
    );
    assert.match(stdout, /█/, "a QR code in block characters");

    const uri = lineStartingWith(stdout, "seekervault://pair?");
    const parsed = parsePairingUri(uri);
    assert.ok(parsed.ok, "the printed URI is a valid pairing code");
    assert.equal(parsed.code.serverUrl, origin);

    assert.match(
      stdout,
      /HTTPS landing page \(opens a pairing page on this server/,
    );
    const landing = lineStartingWith(stdout, `${origin}/pair#`);
    const url = new URL(landing);
    assert.equal(url.pathname, "/pair");
    assert.equal(url.search, "");
    assert.doesNotMatch(url.pathname, /token/);
    // The link and the URI are the same code: the page reads the one from the fragment.
    const decoded = decodeLandingFragment(url.hash.slice(1), origin);
    assert.ok(decoded.ok);
    assert.equal(decoded.payload.pairing_uri, uri);
    assert.doesNotMatch(stdout, /\.\.\.|…/);
  });

  it("names the loopback origin, and its landing link, when no public URL is set", () => {
    const { code, stdout } = run([]);
    assert.equal(code, 0);
    const uri = lineStartingWith(stdout, "seekervault://pair?");
    const parsed = parsePairingUri(uri);
    assert.ok(parsed.ok);
    assert.equal(parsed.code.serverUrl, "http://127.0.0.1:8090");
    const landing = lineStartingWith(stdout, "http://127.0.0.1:8090/pair#");
    assert.equal(new URL(landing).search, "");
    assert.match(stdout, /This is a cleartext URL/);
  });

  it("exits 2 for unknown arguments without printing a code", () => {
    const { code, stdout, stderr } = run(["everything"]);
    assert.equal(code, 2);
    assert.match(
      stderr,
      /^Usage: seeker-skr-staking-mcp pair \[status \| revoke\]/,
    );
    assert.doesNotMatch(stdout, /seekervault:\/\//);
    assert.ok(!(stdout + stderr).includes(MCP_TOKEN));
  });

  it("shows and revokes the paired phone without printing a credential", () => {
    const directory = mkdtempSync(join(tmpdir(), "skr-staking-cli-test-"));
    directories.push(directory);
    const env = { SKR_STAKING_DATA_DIR: directory };
    assert.match(
      run(["status"], env).stdout,
      /No phone is paired with the staking server/,
    );
    const issued = run([], env);
    assert.equal(issued.code, 0);
    const token = parsePairingUri(
      lineStartingWith(issued.stdout, "seekervault://pair?"),
    );
    assert.ok(token.ok);
    // Nothing paired: issuing a code is not pairing, here or over MCP.
    assert.match(
      run(["status"], env).stdout,
      /No phone is paired with the staking server/,
    );
    const revoke = run(["revoke"], env);
    assert.equal(revoke.code, 0);
    assert.match(revoke.stdout, /No phone is paired with the staking server/);
  });
});
