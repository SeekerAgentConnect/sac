import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { createServer as createHttpServer } from "node:http";
import { createServer as createHttpsServer } from "node:https";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";

import { checkHealth } from "./healthcheck.ts";

const tlsDirectory = mkdtempSync(join(tmpdir(), "mcp-health-tls-"));
const certificatePath = join(tlsDirectory, "cert.pem");
const privateKeyPath = join(tlsDirectory, "key.pem");
execFileSync(
  "openssl",
  [
    "req",
    "-x509",
    "-newkey",
    "rsa:2048",
    "-nodes",
    "-days",
    "1",
    "-keyout",
    privateKeyPath,
    "-out",
    certificatePath,
    "-subj",
    "/CN=localhost",
    "-addext",
    "subjectAltName=DNS:localhost",
  ],
  { stdio: "ignore" },
);

type TestServer = ReturnType<typeof createHttpServer>;

async function listen(server: TestServer): Promise<number> {
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  return (server.address() as AddressInfo).port;
}

async function close(server: TestServer): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    server.close((error) => (error === undefined ? resolve() : reject(error)));
  });
}

function healthEnvironment(
  port: number,
  overrides: Record<string, string> = {},
): Record<string, string> {
  return { SIDECAR_PORT: String(port), ...overrides };
}

describe("MCP container health check", () => {
  it("accepts the exact HTTP readiness response", async () => {
    const server = createHttpServer((_request, response) => {
      response
        .writeHead(200, { "Content-Type": "application/json" })
        .end('{"status":"ok"}');
    });
    const port = await listen(server);
    try {
      await checkHealth(healthEnvironment(port));
    } finally {
      await close(server);
    }
  });

  it("detects a refused listener, timeout, non-200 response, and wrong body", async () => {
    const closed = createHttpServer();
    const closedPort = await listen(closed);
    await close(closed);
    await assert.rejects(
      checkHealth(healthEnvironment(closedPort), { timeoutMs: 100 }),
      /ECONNREFUSED|fetch failed|socket hang up/,
    );

    for (const [status, body, expected] of [
      [503, '{"status":"ok"}', /HTTP 503/],
      [200, '{"status":"starting"}', /did not return \{"status":"ok"\}/],
    ] as const) {
      const server = createHttpServer((_request, response) => {
        response.writeHead(status).end(body);
      });
      const port = await listen(server);
      try {
        await assert.rejects(checkHealth(healthEnvironment(port)), expected);
      } finally {
        await close(server);
      }
    }

    const stalled = createHttpServer(() => undefined);
    const stalledPort = await listen(stalled);
    try {
      await assert.rejects(
        checkHealth(healthEnvironment(stalledPort), { timeoutMs: 50 }),
        /timed out|aborted/i,
      );
    } finally {
      await close(stalled);
    }
  });

  it("validates the configured TLS hostname and explicit trust without disabling verification", async () => {
    const server = createHttpsServer(
      {
        cert: readFileSync(certificatePath),
        key: readFileSync(privateKeyPath),
      },
      (_request, response) => {
        response.writeHead(200).end('{"status":"ok"}');
      },
    );
    const port = await listen(server);
    const base = {
      SIDECAR_TLS_CERT_PATH: certificatePath,
      SIDECAR_TLS_KEY_PATH: privateKeyPath,
    };
    try {
      await checkHealth(
        healthEnvironment(port, {
          ...base,
          SIDECAR_PUBLIC_URL: `https://localhost:${String(port)}`,
          SIDECAR_HEALTH_CA_CERT_PATH: certificatePath,
        }),
      );
      await assert.rejects(
        checkHealth(
          healthEnvironment(port, {
            ...base,
            SIDECAR_PUBLIC_URL: `https://wrong.example:${String(port)}`,
            SIDECAR_HEALTH_CA_CERT_PATH: certificatePath,
          }),
        ),
        /hostname|not in the cert|does not match/i,
      );
      await assert.rejects(
        checkHealth(
          healthEnvironment(port, {
            ...base,
            SIDECAR_PUBLIC_URL: `https://localhost:${String(port)}`,
          }),
        ),
        /self-signed|unable to verify|certificate/i,
      );
    } finally {
      await close(server);
    }
  });
});
