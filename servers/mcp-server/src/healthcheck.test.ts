import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { createServer as createHttpServer } from "node:http";
import {
  createServer as createHttp2Server,
  type Http2Session,
} from "node:http2";
import { createServer as createHttpsServer } from "node:https";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

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

type TestServer = {
  listen(port: number, host: string, listening: () => void): unknown;
  close(callback: (error?: Error) => void): unknown;
  address(): AddressInfo | string | null;
  once(event: "error", listener: (error: Error) => void): unknown;
};

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
  it("accepts the exact HTTP/2 cleartext readiness response", async () => {
    const server = createHttp2Server((_request, response) => {
      response
        .writeHead(200, { "Content-Type": "application/json" })
        .end('{"status":"ok"}');
    });
    const port = await listen(server);
    try {
      await checkHealth(healthEnvironment(port, { SIDECAR_H2C: "true" }));
    } finally {
      await close(server);
    }
  });

  it("closes the stalled h2c session after the probe deadline", async () => {
    const server = createHttp2Server();
    const sessions = trackHttp2Sessions(server);
    const streamsClosed: Promise<void>[] = [];
    server.on("stream", (stream) => {
      streamsClosed.push(closed(stream));
    });
    const port = await listen(server);
    try {
      await assert.rejects(
        checkHealth(healthEnvironment(port, { SIDECAR_H2C: "true" }), {
          timeoutMs: 50,
        }),
        /timed out after 50 ms/,
      );
      assert.ok(
        streamsClosed.length >= 1,
        "server never accepted the h2c request",
      );
      await within(
        500,
        Promise.all([
          ...streamsClosed,
          ...[...sessions].map((session) => closed(session)),
        ]),
        "h2c peer did not observe stream/session closure after probe timeout",
      );
      assert.equal(sessions.size, 0);
    } finally {
      await stopHttp2(server, sessions);
    }
  });

  it("closes h2c when response headers never finish the body", async () => {
    const server = createHttp2Server();
    const sessions = trackHttp2Sessions(server);
    const streamsClosed: Promise<void>[] = [];
    server.on("stream", (stream) => {
      streamsClosed.push(closed(stream));
      stream.respond({
        ":status": 200,
        "content-type": "application/json",
      });
    });
    const port = await listen(server);
    try {
      await assert.rejects(
        checkHealth(healthEnvironment(port, { SIDECAR_H2C: "true" }), {
          timeoutMs: 50,
        }),
        /timed out after 50 ms|closed before a complete response/,
      );
      assert.ok(
        streamsClosed.length >= 1,
        "server never accepted the h2c request",
      );
      await within(
        500,
        Promise.all([
          ...streamsClosed,
          ...[...sessions].map((session) => closed(session)),
        ]),
        "h2c peer did not observe closure after incomplete body",
      );
      assert.equal(sessions.size, 0);
    } finally {
      await stopHttp2(server, sessions);
    }
  });

  it("closes h2c when the peer ends the stream before a response", async () => {
    const server = createHttp2Server();
    const sessions = trackHttp2Sessions(server);
    const streamsClosed: Promise<void>[] = [];
    server.on("stream", (stream) => {
      streamsClosed.push(closed(stream));
      stream.close();
    });
    const port = await listen(server);
    try {
      await assert.rejects(
        checkHealth(healthEnvironment(port, { SIDECAR_H2C: "true" }), {
          timeoutMs: 200,
        }),
      );
      assert.ok(
        streamsClosed.length >= 1,
        "server never accepted the h2c request",
      );
      await within(
        500,
        Promise.all([
          ...streamsClosed,
          ...[...sessions].map((session) => closed(session)),
        ]),
        "h2c peer did not observe closure after premature stream close",
      );
      assert.equal(sessions.size, 0);
    } finally {
      await stopHttp2(server, sessions);
    }
  });

  it("does not accumulate h2c sessions across timed-out probes", async () => {
    const server = createHttp2Server();
    const sessions = trackHttp2Sessions(server);
    server.on("stream", () => undefined);
    const port = await listen(server);
    try {
      for (let i = 0; i < 5; i += 1) {
        await assert.rejects(
          checkHealth(healthEnvironment(port, { SIDECAR_H2C: "true" }), {
            timeoutMs: 50,
          }),
          /timed out after 50 ms/,
        );
        await within(
          500,
          Promise.all([...sessions].map((session) => closed(session))),
          `h2c session remained after timed-out probe ${String(i + 1)}`,
        );
        assert.equal(sessions.size, 0);
      }
    } finally {
      await stopHttp2(server, sessions);
    }
  });

  it(
    "exits the standalone h2c probe without an external kill when the peer never answers",
    { timeout: 15_000 },
    async () => {
      const server = createHttp2Server();
      const sessions = trackHttp2Sessions(server);
      server.on("stream", () => undefined);
      const port = await listen(server);
      const child = spawn(
        process.execPath,
        [fileURLToPath(new URL("./healthcheck.ts", import.meta.url))],
        {
          env: {
            PATH: process.env.PATH,
            SIDECAR_PORT: String(port),
            SIDECAR_H2C: "true",
          },
          stdio: ["ignore", "ignore", "pipe"],
        },
      );
      let stderr = "";
      child.stderr?.setEncoding("utf8");
      child.stderr?.on("data", (chunk: string) => {
        stderr += chunk;
      });
      let killedByWatchdog = false;
      const watchdog = setTimeout(() => {
        killedByWatchdog = true;
        child.kill("SIGKILL");
      }, 8_000);
      try {
        const [code, signal] = await new Promise<
          [number | null, NodeJS.Signals | null]
        >((resolve, reject) => {
          child.once("error", reject);
          child.once("exit", (exitCode, exitSignal) => {
            resolve([exitCode, exitSignal]);
          });
        });
        assert.equal(killedByWatchdog, false);
        assert.equal(signal, null);
        assert.equal(code, 1);
        assert.match(stderr, /timed out after 3000 ms/);
      } finally {
        clearTimeout(watchdog);
        if (child.exitCode === null && child.signalCode === null) {
          child.kill("SIGKILL");
        }
        await stopHttp2(server, sessions);
      }
    },
  );

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

async function stopHttp2(
  server: ReturnType<typeof createHttp2Server>,
  sessions: Set<Http2Session>,
): Promise<void> {
  for (const session of sessions) {
    session.destroy();
  }
  await close(server);
}

function trackHttp2Sessions(
  server: ReturnType<typeof createHttp2Server>,
): Set<Http2Session> {
  const sessions = new Set<Http2Session>();
  server.on("session", (session) => {
    sessions.add(session);
    session.on("close", () => {
      sessions.delete(session);
    });
  });
  return sessions;
}

function closed(emitter: {
  readonly closed: boolean;
  readonly destroyed?: boolean;
  once(event: "close", listener: () => void): unknown;
}): Promise<void> {
  if (emitter.closed || emitter.destroyed === true) {
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    emitter.once("close", resolve);
  });
}

async function within(
  timeoutMs: number,
  promise: Promise<unknown>,
  message: string,
): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    await Promise.race([
      promise,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => {
          reject(new Error(message));
        }, timeoutMs);
      }),
    ]);
  } finally {
    if (timer !== undefined) {
      clearTimeout(timer);
    }
  }
}
