import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  ConfigError,
  DEFAULT_DATABASE_PATH,
  loadSidecarConfig,
} from "./config.ts";

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);

const validEnv = {
  SIDECAR_HOST: "127.0.0.1",
  SIDECAR_PORT: "8080",
  MCP_TOKEN,
  PHONE_TOKEN,
  LIVE_COMMAND_TIMEOUT_SECONDS: "60",
};

function problemsFor(
  env: Record<string, string | undefined>,
): readonly string[] {
  try {
    loadSidecarConfig(env);
  } catch (error) {
    assert.ok(error instanceof ConfigError);
    return error.problems;
  }
  assert.fail("expected a ConfigError");
}

describe("loadSidecarConfig", () => {
  it("parses a valid configuration, with the storage defaults", () => {
    assert.deepEqual(loadSidecarConfig(validEnv), {
      host: "127.0.0.1",
      port: 8080,
      mcpToken: MCP_TOKEN,
      phoneToken: PHONE_TOKEN,
      liveCommandTimeoutSeconds: 60,
      mcpAllowedHosts: [],
      databasePath: DEFAULT_DATABASE_PATH,
      requestTtlSeconds: 86_400,
      pendingLimit: 100,
      publicUrl: "http://127.0.0.1:8080",
      pairingTokenTtlSeconds: 600,
    });
    assert.match(
      DEFAULT_DATABASE_PATH,
      /[/\\]sidecar[/\\]data[/\\]sidecar\.db$/,
    );
  });

  it("accepts the other loopback hosts", () => {
    for (const host of ["::1", "localhost"]) {
      assert.equal(
        loadSidecarConfig({ ...validEnv, SIDECAR_HOST: host }).host,
        host,
      );
    }
  });

  it("reports every missing variable at once", () => {
    assert.deepEqual(problemsFor({ SIDECAR_HOST: " " }), [
      "SIDECAR_HOST is not set.",
      "SIDECAR_PORT is not set.",
      "MCP_TOKEN is not set.",
      "PHONE_TOKEN is not set.",
      "LIVE_COMMAND_TIMEOUT_SECONDS is not set.",
    ]);
  });

  it("rejects hosts that are not loopback addresses", () => {
    for (const host of ["0.0.0.0", "192.168.1.20", "example.com"]) {
      assert.deepEqual(problemsFor({ ...validEnv, SIDECAR_HOST: host }), [
        "SIDECAR_HOST must be a loopback address (127.0.0.1, ::1, or localhost) in Stage 1.",
      ]);
    }
  });

  it("rejects ports and timeouts outside their ranges", () => {
    for (const port of ["0", "65536", "80a", "-1", "8080.5"]) {
      assert.deepEqual(problemsFor({ ...validEnv, SIDECAR_PORT: port }), [
        "SIDECAR_PORT must be a whole number from 1 to 65535.",
      ]);
    }
    for (const timeout of ["0", "3601", "1.5"]) {
      assert.deepEqual(
        problemsFor({ ...validEnv, LIVE_COMMAND_TIMEOUT_SECONDS: timeout }),
        ["LIVE_COMMAND_TIMEOUT_SECONDS must be a whole number from 1 to 3600."],
      );
    }
  });

  it("rejects the .env.example token placeholders", () => {
    const problems = problemsFor({
      ...validEnv,
      MCP_TOKEN: "REPLACE_WITH_A_RANDOM_DEVELOPMENT_TOKEN",
      PHONE_TOKEN: "REPLACE_WITH_A_DIFFERENT_RANDOM_DEVELOPMENT_TOKEN",
    });
    assert.equal(problems.length, 2);
    assert.match(
      problems[0] ?? "",
      /^MCP_TOKEN still has the \.env\.example placeholder/,
    );
    assert.match(
      problems[1] ?? "",
      /^PHONE_TOKEN still has the \.env\.example placeholder/,
    );
  });

  it("requires tokens that are long enough and different from each other", () => {
    assert.deepEqual(problemsFor({ ...validEnv, PHONE_TOKEN: "short" }), [
      "PHONE_TOKEN must be at least 32 characters long.",
    ]);
    assert.deepEqual(problemsFor({ ...validEnv, PHONE_TOKEN: MCP_TOKEN }), [
      "MCP_TOKEN and PHONE_TOKEN must be different values.",
    ]);
  });

  it("rejects tokens that can't travel as bearer credentials", () => {
    const spaced = `${"m".repeat(20)} ${"m".repeat(20)}`;
    assert.match(
      problemsFor({ ...validEnv, MCP_TOKEN: spaced }).join("\n"),
      /MCP_TOKEN may contain only/,
    );
    assert.match(
      problemsFor({ ...validEnv, PHONE_TOKEN: `${"p".repeat(40)}"` }).join(
        "\n",
      ),
      /PHONE_TOKEN may contain only/,
    );
  });

  it("reads the extra /mcp host names in MCP_ALLOWED_HOSTS", () => {
    assert.deepEqual(
      loadSidecarConfig({
        ...validEnv,
        MCP_ALLOWED_HOSTS: " 100.64.0.1, Mac.tailnet.ts.net ,[fd7a:115c::1],",
      }).mcpAllowedHosts,
      ["100.64.0.1", "mac.tailnet.ts.net", "[fd7a:115c::1]"],
    );
    assert.deepEqual(
      loadSidecarConfig({ ...validEnv, MCP_ALLOWED_HOSTS: "" }).mcpAllowedHosts,
      [],
    );
  });

  it("rejects MCP_ALLOWED_HOSTS entries with a scheme, port, or wildcard", () => {
    for (const value of ["http://100.64.0.1", "100.64.0.1:8081", "*.ts.net"]) {
      assert.match(
        problemsFor({ ...validEnv, MCP_ALLOWED_HOSTS: value }).join("\n"),
        /MCP_ALLOWED_HOSTS must list host names/,
        value,
      );
    }
  });

  it("reads the optional storage settings, and treats empty ones as unset", () => {
    const config = loadSidecarConfig({
      ...validEnv,
      DATABASE_PATH: " /var/lib/seeker-vault/sidecar.db ",
      REQUEST_TTL_SECONDS: "3600",
      REQUEST_PENDING_LIMIT: "5",
    });
    assert.equal(config.databasePath, "/var/lib/seeker-vault/sidecar.db");
    assert.equal(config.requestTtlSeconds, 3600);
    assert.equal(config.pendingLimit, 5);
    const empty = loadSidecarConfig({
      ...validEnv,
      DATABASE_PATH: "",
      REQUEST_TTL_SECONDS: " ",
      REQUEST_PENDING_LIMIT: "",
    });
    assert.equal(empty.databasePath, DEFAULT_DATABASE_PATH);
    assert.equal(empty.requestTtlSeconds, 86_400);
    assert.equal(empty.pendingLimit, 100);
  });

  it("rejects request lifetimes and pending limits outside their ranges", () => {
    for (const ttl of ["59", "604801", "1h"]) {
      assert.deepEqual(problemsFor({ ...validEnv, REQUEST_TTL_SECONDS: ttl }), [
        "REQUEST_TTL_SECONDS must be a whole number from 60 to 604800.",
      ]);
    }
    for (const limit of ["0", "10001", "-5"]) {
      assert.deepEqual(
        problemsFor({ ...validEnv, REQUEST_PENDING_LIMIT: limit }),
        ["REQUEST_PENDING_LIMIT must be a whole number from 1 to 10000."],
      );
    }
  });

  it("reads SIDECAR_PUBLIC_URL and PAIRING_TOKEN_TTL_SECONDS, with loopback and 10-minute defaults", () => {
    assert.equal(
      loadSidecarConfig({ ...validEnv, SIDECAR_HOST: "::1" }).publicUrl,
      "http://[::1]:8080",
    );
    const config = loadSidecarConfig({
      ...validEnv,
      SIDECAR_PUBLIC_URL: " https://Vault.example.ts.net/seeker/ ",
      PAIRING_TOKEN_TTL_SECONDS: "120",
    });
    assert.equal(config.publicUrl, "https://vault.example.ts.net/seeker");
    assert.equal(config.pairingTokenTtlSeconds, 120);
    assert.equal(
      loadSidecarConfig({
        ...validEnv,
        SIDECAR_PUBLIC_URL: "http://localhost:8080",
      }).publicUrl,
      "http://localhost:8080",
    );
  });

  it("refuses a public URL without HTTPS off loopback, and pairing codes outside 1 to 60 minutes", () => {
    for (const url of [
      "http://192.168.1.20:8080",
      "ftp://vault.example.com",
      "https://owner:secret@vault.example.com",
      "https://vault.example.com/?code=1",
      "not a URL",
    ]) {
      assert.match(
        problemsFor({ ...validEnv, SIDECAR_PUBLIC_URL: url }).join("\n"),
        /^SIDECAR_PUBLIC_URL: /,
        url,
      );
    }
    for (const ttl of ["59", "3601"]) {
      assert.deepEqual(
        problemsFor({ ...validEnv, PAIRING_TOKEN_TTL_SECONDS: ttl }),
        ["PAIRING_TOKEN_TTL_SECONDS must be a whole number from 60 to 3600."],
      );
    }
  });

  it("never includes token values in the error message", () => {
    const secret = "do-not-print-this-token-value";
    try {
      loadSidecarConfig({
        ...validEnv,
        MCP_TOKEN: secret,
        PHONE_TOKEN: secret,
      });
      assert.fail("expected a ConfigError");
    } catch (error) {
      assert.ok(error instanceof ConfigError);
      assert.ok(!error.message.includes(secret));
    }
  });
});
