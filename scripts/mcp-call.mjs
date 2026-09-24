#!/usr/bin/env node
// Minimal Streamable HTTP MCP client for agent sessions: initialize, then list or call one tool.
// Runbook: docs/development/emulator-e2e.md
//
//   node scripts/mcp-call.mjs <origin> <TOKEN_ENV_VAR> list
//   node scripts/mcp-call.mjs <origin> <TOKEN_ENV_VAR> call <tool> ['{"json":"args"}']
//
// The token is read from the named environment variable so it never appears in a command line.
const [origin, tokenVar, op, tool, args] = process.argv.slice(2);
const token = tokenVar ? process.env[tokenVar] : undefined;
if (
  !origin ||
  !token ||
  !["list", "call"].includes(op) ||
  (op === "call" && !tool)
) {
  console.error(
    "usage: mcp-call.mjs <origin> <TOKEN_ENV_VAR> list | call <tool> [json]",
  );
  if (tokenVar && !token) console.error(`${tokenVar} is not set`);
  process.exit(2);
}

let sessionId;
let nextId = 0;

async function rpc(method, params, notification = false) {
  const headers = {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json",
    Accept: "application/json, text/event-stream",
  };
  if (sessionId) headers["Mcp-Session-Id"] = sessionId;
  const body = { jsonrpc: "2.0", method, params };
  if (!notification) body.id = ++nextId;
  const response = await fetch(`${origin.replace(/\/$/, "")}/mcp`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });
  sessionId ??= response.headers.get("mcp-session-id") ?? undefined;
  const text = await response.text();
  if (notification) return undefined;
  const data = text.split("\n").find((line) => line.startsWith("data: "));
  const message = JSON.parse(data ? data.slice(6) : text);
  if (message.error)
    throw new Error(`${response.status} ${JSON.stringify(message.error)}`);
  return message.result;
}

await rpc("initialize", {
  protocolVersion: "2025-06-18",
  capabilities: {},
  clientInfo: { name: "seeker-agent-session", version: "0" },
});
await rpc("notifications/initialized", {}, true);
const result =
  op === "list"
    ? (await rpc("tools/list", {})).tools.map((t) => t.name)
    : await rpc("tools/call", {
        name: tool,
        arguments: JSON.parse(args ?? "{}"),
      });
console.log(
  JSON.stringify(
    op === "call" ? (result.structuredContent ?? result) : result,
    null,
    2,
  ),
);
