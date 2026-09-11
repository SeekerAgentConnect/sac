import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { ConfigError, loadSidecarConfig } from "./config.ts";

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
  it("parses a valid Stage 1 configuration", () => {
    assert.deepEqual(loadSidecarConfig(validEnv), {
      host: "127.0.0.1",
      port: 8080,
      mcpToken: MCP_TOKEN,
      phoneToken: PHONE_TOKEN,
      liveCommandTimeoutSeconds: 60,
      mcpAllowedHosts: [],
    });
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
