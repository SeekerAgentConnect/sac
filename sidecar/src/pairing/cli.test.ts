import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

import { openDatabase } from "../storage/database.ts";
import { temporaryDatabasePath } from "../testing/process.ts";
import { PairingStore } from "../storage/pairing-store.ts";
import { parsePairingUri } from "./uri.ts";

const CLI = fileURLToPath(new URL("./cli.ts", import.meta.url));
const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);

function run(args: readonly string[], env: Record<string, string>) {
  const result = spawnSync(process.execPath, [CLI, ...args], {
    encoding: "utf8",
    env: {
      PATH: process.env.PATH,
      SIDECAR_HOST: "127.0.0.1",
      SIDECAR_PORT: "8080",
      MCP_TOKEN,
      PHONE_TOKEN,
      LIVE_COMMAND_TIMEOUT_SECONDS: "60",
      ...env,
    },
  });
  return { code: result.status, stdout: result.stdout, stderr: result.stderr };
}

describe("pnpm pair", () => {
  it("shows a one-use code, as a QR code and as a URI, for the public URL", () => {
    const databasePath = temporaryDatabasePath();
    const { code, stdout } = run([], {
      DATABASE_PATH: databasePath,
      SIDECAR_PUBLIC_URL: "https://vault.example.ts.net",
    });
    assert.equal(code, 0);
    assert.match(stdout, /pair it with https:\/\/vault\.example\.ts\.net\./);
    assert.match(stdout, /█/, "a QR code in block characters");
    const uri = stdout
      .split("\n")
      .find((line) => line.startsWith("seekervault://pair?"));
    const parsed = parsePairingUri(uri ?? "");
    assert.ok(parsed.ok, "the printed URI is a valid pairing code");
    assert.equal(parsed.code.serverUrl, "https://vault.example.ts.net");
    // The printed token is the one the sidecar will accept.
    const db = openDatabase(databasePath);
    try {
      const paired = new PairingStore(db).pair(
        parsed.code.token,
        parsed.code.serverUrl,
        "Seeker",
      );
      assert.equal(paired.serverId, parsed.code.serverId);
    } finally {
      db.close();
    }
  });

  it("notes the loopback development URL, and the phone a new code would replace", () => {
    const databasePath = temporaryDatabasePath();
    const db = openDatabase(databasePath);
    let connectionId: string;
    try {
      const pairing = new PairingStore(db);
      connectionId = pairing.pair(
        pairing.issue("http://127.0.0.1:8080", 600).token,
        "http://127.0.0.1:8080",
        "Seeker",
      ).connectionId;
    } finally {
      db.close();
    }
    const { code, stdout } = run([], { DATABASE_PATH: databasePath });
    assert.equal(code, 0);
    assert.match(stdout, /This is the loopback development URL/);
    assert.match(
      stdout,
      new RegExp(
        `Pairing revokes the phone paired now: connection ${connectionId} \\("Seeker"\\)`,
      ),
    );
  });

  it("shows and revokes the paired phone without printing its credential", () => {
    const databasePath = temporaryDatabasePath();
    const env = { DATABASE_PATH: databasePath };
    assert.match(run(["status"], env).stdout, /No phone is paired/);
    const db = openDatabase(databasePath);
    let credential: string;
    try {
      const pairing = new PairingStore(db);
      credential = pairing.pair(
        pairing.issue("http://127.0.0.1:8080", 600).token,
        "http://127.0.0.1:8080",
        "Seeker",
      ).phoneToken;
    } finally {
      db.close();
    }
    const status = run(["status"], env);
    assert.equal(status.code, 0);
    assert.match(
      status.stdout,
      /^Paired phone: connection [0-9a-f-]{36} \("Seeker"\), paired /,
    );
    const revoke = run(["revoke"], env);
    assert.equal(revoke.code, 0);
    assert.match(
      revoke.stdout,
      /Its credential no longer works, and 0 pending requests were cancelled\./,
    );
    assert.match(run(["status"], env).stdout, /No phone is paired/);
    for (const output of [status, revoke]) {
      assert.ok(!(output.stdout + output.stderr).includes(credential));
    }
    const check = openDatabase(databasePath);
    try {
      assert.equal(new PairingStore(check).authenticate(credential), undefined);
    } finally {
      check.close();
    }
  });

  it("prints the phone's own name with its control characters escaped", () => {
    const databasePath = temporaryDatabasePath();
    const env = { DATABASE_PATH: databasePath };
    // A name that tries to set the terminal's title and to forge a second status line.
    const name =
      "Seeker\u{1b}]0;owned\u{7}\nPaired phone: someone else\u{202e} C:\\";
    const db = openDatabase(databasePath);
    try {
      const pairing = new PairingStore(db);
      pairing.pair(
        pairing.issue("http://127.0.0.1:8080", 600).token,
        "http://127.0.0.1:8080",
        name,
      );
    } finally {
      db.close();
    }
    const escaped =
      "Seeker\\u{1b}]0;owned\\u{7}\\u{a}Paired phone: someone else\\u{202e} C:\\\\";
    const status = run(["status"], env);
    const issue = run([], env); // names the phone that the new code would replace
    const revoke = run(["revoke"], env);
    for (const output of [status, issue, revoke]) {
      assert.equal(output.code, 0);
      for (const raw of ["\u{1b}", "\u{7}", "\u{202e}"]) {
        assert.ok(!output.stdout.includes(raw));
      }
      assert.ok(output.stdout.includes(`("${escaped}")`), output.stdout);
    }
    assert.equal(status.stdout.trimEnd().split("\n").length, 1);
  });

  it("exits 2 for unknown arguments and bad configuration, without echoing tokens", () => {
    const usage = run(["everything"], {
      DATABASE_PATH: temporaryDatabasePath(),
    });
    assert.equal(usage.code, 2);
    assert.match(usage.stderr, /^Usage: pnpm pair \[status \| revoke\]/);
    const insecure = run([], {
      DATABASE_PATH: temporaryDatabasePath(),
      SIDECAR_PUBLIC_URL: "http://192.168.1.20:8080",
    });
    assert.equal(insecure.code, 2);
    assert.match(
      insecure.stderr,
      /SIDECAR_PUBLIC_URL: the server URL must use HTTPS/,
    );
    assert.ok(!(insecure.stdout + insecure.stderr).includes(MCP_TOKEN));
    assert.ok(
      !(insecure.stdout + insecure.stderr).includes("seekervault://"),
      "no code without a valid URL",
    );
  });
});
