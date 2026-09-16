/**
 * The OAuth profile for a hosted MCP client (SAW-036): what the sidecar publishes, which access
 * tokens open /mcp, and — as much as it matters — which ones do not.
 *
 * Every token here is minted by a fake authorization server (testing/authorization-server.ts), so
 * these are the real checks a resource server performs, against real signatures, without an
 * account at any provider. What they cannot stand in for is a hosted Claude client's own round
 * trip: that needs a public domain and a real authorization server, and docs/testing/stage-7.md
 * records it as NOT RUN.
 */
import assert from "node:assert/strict";
import { request } from "node:http";
import { after, before, describe, it } from "node:test";

import { Code, ConnectError } from "@connectrpc/connect";
import { LATEST_PROTOCOL_VERSION } from "@modelcontextprotocol/sdk/types.js";

import { startSidecar, type Sidecar } from "./server.ts";
import {
  startAuthorizationServer,
  type FakeAuthorizationServer,
  type TokenOptions,
} from "./testing/authorization-server.ts";
import {
  connectAgent,
  pairingClient,
  requestClient,
} from "./testing/clients.ts";

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);
/** The domain this deployment answers for, as the public gateway would (SAW-035). */
const PUBLIC_HOST = "vault.example.com";
const RESOURCE = `https://${PUBLIC_HOST}/mcp`;
const SCOPE = "seeker-vault:agent";
/** The first message of a real session, so an accepted token is answered with a real result. */
const INITIALIZE = JSON.stringify({
  jsonrpc: "2.0",
  id: 1,
  method: "initialize",
  params: {
    protocolVersion: LATEST_PROTOCOL_VERSION,
    capabilities: {},
    clientInfo: { name: "seeker-vault-tests", version: "0.0.0" },
  },
});

const logs: string[] = [];
let authorizationServer: FakeAuthorizationServer;
let sidecar: Sidecar;

interface Response {
  readonly status: number;
  readonly headers: Record<string, string | string[] | undefined>;
  readonly body: string;
}

/** A raw request, so every header — Host and Authorization included — is the test's own. */
function send(
  path: string,
  options: {
    readonly method?: string;
    readonly headers?: Record<string, string>;
    readonly body?: string;
    readonly target?: Sidecar;
  } = {},
): Promise<Response> {
  const { hostname, port } = new URL((options.target ?? sidecar).url);
  return new Promise((resolve, reject) => {
    const req = request(
      {
        hostname,
        port,
        path,
        method: options.method ?? "GET",
        headers: options.headers ?? {},
      },
      (res) => {
        let body = "";
        res.setEncoding("utf8");
        res.on("data", (chunk: string) => (body += chunk));
        res.on("end", () =>
          resolve({ status: res.statusCode ?? 0, headers: res.headers, body }),
        );
      },
    );
    req.on("error", reject);
    req.end(options.body);
  });
}

/** A POST to /mcp carrying a bearer token, if any. */
function postMcp(
  token: string | undefined,
  headers: Record<string, string> = {},
): Promise<Response> {
  return send("/mcp", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json, text/event-stream",
      ...(token === undefined ? {} : { Authorization: `Bearer ${token}` }),
      ...headers,
    },
    body: INITIALIZE,
  });
}

function accessToken(options: TokenOptions = {}): Promise<string> {
  return authorizationServer.issue({
    audience: RESOURCE,
    scope: SCOPE,
    ...options,
  });
}

/** The parsed WWW-Authenticate parameters of a refusal. */
function challengeOf(response: Response): Record<string, string> {
  const header = String(response.headers["www-authenticate"] ?? "");
  assert.match(header, /^Bearer /, "the refusal carries a Bearer challenge");
  return Object.fromEntries(
    [...header.matchAll(/(\w+)="([^"]*)"/g)].map((match) => [
      match[1] ?? "",
      match[2] ?? "",
    ]),
  );
}

before(async () => {
  authorizationServer = await startAuthorizationServer();
  sidecar = await startSidecar(
    {
      host: "127.0.0.1",
      port: 0,
      mcpToken: MCP_TOKEN,
      phoneToken: PHONE_TOKEN,
      liveCommandTimeoutSeconds: 1,
      databasePath: ":memory:",
      requestTtlSeconds: 86_400,
      pendingLimit: 100,
      mcpAllowedHosts: [PUBLIC_HOST],
      oauth: {
        issuer: authorizationServer.issuer,
        resource: RESOURCE,
        scopes: [SCOPE],
      },
    },
    { log: (line) => logs.push(line) },
  );
});

after(async () => {
  await sidecar.close();
  await authorizationServer.close();
});

describe("protected resource metadata", () => {
  it("names the authorization server, this resource, and the scope to ask for", async () => {
    const response = await send("/.well-known/oauth-protected-resource");
    assert.equal(response.status, 200);
    assert.equal(response.headers["content-type"], "application/json");
    assert.equal(response.headers["access-control-allow-origin"], "*");
    assert.deepEqual(JSON.parse(response.body), {
      resource: RESOURCE,
      authorization_servers: [authorizationServer.issuer],
      bearer_methods_supported: ["header"],
      scopes_supported: [SCOPE],
      resource_name: "Seeker Agent Connect",
    });
  });

  it("answers at the path-inserted well-known URI a client tries first", async () => {
    const inserted = await send("/.well-known/oauth-protected-resource/mcp");
    const root = await send("/.well-known/oauth-protected-resource");
    assert.equal(inserted.status, 200);
    assert.deepEqual(JSON.parse(inserted.body), JSON.parse(root.body));
  });

  it("is read-only", async () => {
    const response = await send("/.well-known/oauth-protected-resource", {
      method: "POST",
    });
    assert.equal(response.status, 405);
  });

  it("does not exist when no authorization server is configured", async () => {
    const plain = await startSidecar({
      host: "127.0.0.1",
      port: 0,
      mcpToken: MCP_TOKEN,
      phoneToken: PHONE_TOKEN,
      liveCommandTimeoutSeconds: 1,
      databasePath: ":memory:",
      requestTtlSeconds: 86_400,
      pendingLimit: 100,
    });
    try {
      for (const path of [
        "/.well-known/oauth-protected-resource",
        "/.well-known/oauth-protected-resource/mcp",
      ]) {
        const response = await send(path, { target: plain });
        assert.equal(response.status, 404);
      }
      // And the refusal still points at MCP_TOKEN rather than at an authorization server.
      const refused = await postMcp(undefined, { Host: "127.0.0.1" });
      assert.equal(refused.status, 401);
    } finally {
      await plain.close();
    }
  });
});

describe("an access token", () => {
  it("opens a real MCP session, and the tools are the sidecar's own", async () => {
    const agent = await connectAgent(sidecar.url, await accessToken());
    try {
      const tools = (await agent.listTools()).tools.map((tool) => tool.name);
      assert.ok(
        tools.includes("vault_get_capabilities"),
        `the session lists the sidecar's tools: ${tools.join(", ")}`,
      );
    } finally {
      await agent.close();
    }
  });

  it("works under the deployment's own public host name", async () => {
    const response = await postMcp(await accessToken(), { Host: PUBLIC_HOST });
    assert.equal(response.status, 200);
  });

  it("opens nothing on the phone's API", async () => {
    const token = await accessToken();
    // The agent's credential and the phone's are different roles (docs/protocol.md#roles), and an
    // access token is an agent's. A hosted client never gains the phone's side of the protocol.
    for (const call of [
      () => requestClient(sidecar.url, token).listPending({}),
      () => pairingClient(sidecar.url, token).getConnectionCapabilities({}),
    ]) {
      await assert.rejects(
        call,
        (error: unknown) =>
          error instanceof ConnectError &&
          (error.code === Code.Unauthenticated ||
            error.code === Code.PermissionDenied),
      );
    }
  });
});

describe("a token that is not this deployment's", () => {
  it("is refused when it carries no audience for this server", async () => {
    const response = await postMcp(
      await accessToken({ audience: "https://someone-else.example.com/mcp" }),
    );
    assert.equal(response.status, 401);
    const challenge = challengeOf(response);
    assert.equal(challenge.error, "invalid_token");
    assert.match(
      challenge.error_description ?? "",
      /not issued for this MCP server/,
    );
  });

  it("is refused when another authorization server issued it", async () => {
    const response = await postMcp(
      await accessToken({ issuer: "https://another.example.com" }),
    );
    assert.equal(response.status, 401);
    assert.match(
      challengeOf(response).error_description ?? "",
      /not issued by the configured authorization server/,
    );
  });

  it("is refused when it has expired", async () => {
    const response = await postMcp(
      await accessToken({ expiresInSeconds: -3600 }),
    );
    assert.equal(response.status, 401);
    assert.match(challengeOf(response).error_description ?? "", /expired/);
  });

  it("is refused when it was not made with the published keys", async () => {
    const response = await postMcp(await accessToken({ unknownKey: true }));
    assert.equal(response.status, 401);
    assert.match(
      challengeOf(response).error_description ?? "",
      /signature is not from the authorization server's published keys/,
    );
  });

  it("is refused when it uses a shared secret instead of a published key", async () => {
    const response = await postMcp(await accessToken({ sharedSecret: true }));
    assert.equal(response.status, 401);
    assert.match(
      challengeOf(response).error_description ?? "",
      /algorithm this server does not accept/,
    );
  });

  it("is refused when it is not a JSON Web Token at all", async () => {
    const response = await postMcp("an-opaque-token-from-somewhere-else");
    assert.equal(response.status, 401);
    assert.match(
      challengeOf(response).error_description ?? "",
      /not a JSON Web Token/,
    );
  });

  it("is refused, with the way in, when there is no token", async () => {
    const response = await postMcp(undefined);
    assert.equal(response.status, 401);
    const challenge = challengeOf(response);
    assert.equal(
      challenge.resource_metadata,
      `https://${PUBLIC_HOST}/.well-known/oauth-protected-resource/mcp`,
    );
    assert.equal(challenge.scope, SCOPE);
  });

  it("is refused with 403 when it lacks the scope this endpoint needs", async () => {
    const response = await postMcp(await accessToken({ scope: "profile" }));
    assert.equal(response.status, 403);
    const challenge = challengeOf(response);
    assert.equal(challenge.error, "insufficient_scope");
    assert.equal(challenge.scope, SCOPE);
  });
});

describe("the operator's own token, while OAuth is on", () => {
  it("still opens the stack's private endpoint on loopback", async () => {
    const response = await postMcp(MCP_TOKEN, { Host: "127.0.0.1" });
    assert.equal(response.status, 200);
  });

  it("does not open the endpoint the internet reaches", async () => {
    const response = await postMcp(MCP_TOKEN, { Host: PUBLIC_HOST });
    assert.equal(response.status, 401);
    assert.equal(challengeOf(response).resource_metadata !== undefined, true);
  });
});

describe("the authorization server's keys", () => {
  it("are found from the issuer alone, and read once", async () => {
    const before = { ...authorizationServer.reads };
    assert.ok(before.metadata >= 1, "discovery read the metadata document");
    assert.ok(before.jwks >= 1, "and the keys behind it");
    const response = await postMcp(await accessToken());
    assert.equal(response.status, 200);
    assert.equal(
      authorizationServer.reads.metadata,
      before.metadata,
      "a later request re-reads neither document",
    );
    assert.equal(authorizationServer.reads.jwks, before.jwks);
  });

  it("are found through OpenID Connect discovery, and under an issuer with a path", async () => {
    const openid = await startAuthorizationServer({
      discovery: "openid",
      path: "/realms/seeker",
    });
    const deployment = await startSidecar({
      host: "127.0.0.1",
      port: 0,
      mcpToken: MCP_TOKEN,
      phoneToken: PHONE_TOKEN,
      liveCommandTimeoutSeconds: 1,
      databasePath: ":memory:",
      requestTtlSeconds: 86_400,
      pendingLimit: 100,
      oauth: { issuer: openid.issuer, resource: RESOURCE, scopes: [] },
    });
    try {
      const token = await openid.issue({ audience: RESOURCE });
      const direct = await send("/mcp", {
        method: "POST",
        target: deployment,
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json, text/event-stream",
          Authorization: `Bearer ${token}`,
        },
        body: INITIALIZE,
      });
      assert.equal(direct.status, 200);
      assert.ok(openid.reads.metadata >= 1);
    } finally {
      await deployment.close();
      await openid.close();
    }
  });
});

describe("the log", () => {
  it("says why a token was refused and never what it was", async () => {
    const token = await accessToken({ expiresInSeconds: -3600 });
    await postMcp(token);
    const refusals = logs.filter((line) =>
      line.startsWith("rejected POST /mcp"),
    );
    assert.ok(refusals.some((line) => line.includes("expired")));
    assert.equal(
      logs.filter((line) => line.includes(token)).length,
      0,
      "no log line carries the token",
    );
    assert.equal(
      logs.filter((line) => line.includes(MCP_TOKEN)).length,
      0,
      "and none carries MCP_TOKEN either",
    );
  });
});
