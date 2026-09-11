import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { AgentConfigError, loadAgentConfig } from "./config.ts";

const TOKEN = "m".repeat(64);

function problemsFor(
  env: Record<string, string | undefined>,
): readonly string[] {
  try {
    loadAgentConfig(env);
  } catch (error) {
    assert.ok(error instanceof AgentConfigError);
    return error.problems;
  }
  assert.fail("expected an AgentConfigError");
}

describe("loadAgentConfig", () => {
  it("reads MCP_URL and MCP_TOKEN and waits 15 s longer than the sidecar", () => {
    const config = loadAgentConfig({
      MCP_URL: "http://127.0.0.1:8080/mcp",
      MCP_TOKEN: TOKEN,
      LIVE_COMMAND_TIMEOUT_SECONDS: "30",
    });
    assert.equal(config.mcpUrl.href, "http://127.0.0.1:8080/mcp");
    assert.equal(config.mcpToken, TOKEN);
    assert.equal(config.timeoutSeconds, 45);
  });

  it("assumes the sidecar's 60 s default deadline when it is not set", () => {
    assert.equal(
      loadAgentConfig({
        MCP_URL: "http://127.0.0.1:8080/mcp",
        MCP_TOKEN: TOKEN,
      }).timeoutSeconds,
      75,
    );
  });

  it("reports every problem without echoing the token", () => {
    assert.deepEqual(problemsFor({}), [
      "MCP_URL is not set.",
      "MCP_TOKEN is not set.",
    ]);
    const problems = problemsFor({
      MCP_URL: "127.0.0.1:8080/mcp",
      MCP_TOKEN: "REPLACE_WITH_A_RANDOM_DEVELOPMENT_TOKEN",
      LIVE_COMMAND_TIMEOUT_SECONDS: "0",
    });
    assert.equal(problems.length, 3);
    assert.match(problems[0] ?? "", /^MCP_URL must be an http/);
    assert.match(
      problems[1] ?? "",
      /^MCP_TOKEN still has the \.env\.example placeholder/,
    );
    assert.match(problems[2] ?? "", /^LIVE_COMMAND_TIMEOUT_SECONDS must be/);
    assert.ok(!problems.join("\n").includes("REPLACE_WITH"));
  });
});
