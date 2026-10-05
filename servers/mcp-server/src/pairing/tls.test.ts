/**
 * Pairing over HTTPS through a TLS endpoint in front of the loopback sidecar, the way the owner's
 * remote test runs (docs/security.md). The phone's side keeps normal certificate and hostname
 * checks: an untrusted certificate or another host's certificate fails before any token is sent.
 */
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { request as httpRequest } from "node:http";
import { createServer as createHttpsServer, type Server } from "node:https";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, before, describe, it } from "node:test";

import { createClient } from "@connectrpc/connect";
import { createConnectTransport } from "@connectrpc/connect-node";

import {
  PairingService,
  RequestService,
} from "@seekeragentconnect/server-sdk/protocol";
import { startSidecar, type Sidecar } from "../server.ts";
import { openDatabase } from "../../../../packages/server-sdk/src/storage/database.ts";
import { ConnectError } from "../testing/clients.ts";
import { temporaryDatabasePath } from "../testing/process.ts";
import { PairingStore } from "../../../../packages/server-sdk/src/storage/pairing-store.ts";

let sidecar: Sidecar;
let proxy: Server;
let databasePath: string;
let certificate: Buffer;
let httpsPort: number;

function issue(serverUrl: string): string {
  const db = openDatabase(databasePath);
  try {
    return new PairingStore(db).issue(serverUrl, 600).token;
  } finally {
    db.close();
  }
}

/** Connect clients over HTTPS, trusting `ca` in addition to the system's roots, if given. */
function clients(baseUrl: string, token: string, ca?: Buffer) {
  const transport = createConnectTransport({
    baseUrl,
    httpVersion: "1.1",
    nodeOptions: ca === undefined ? {} : { ca },
    interceptors: [
      (next) => (request) => {
        request.header.set("Authorization", `Bearer ${token}`);
        return next(request);
      },
    ],
  });
  return {
    pairing: createClient(PairingService, transport),
    requests: createClient(RequestService, transport),
  };
}

before(async () => {
  const dir = mkdtempSync(join(tmpdir(), "seeker-vault-tls-"));
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
      join(dir, "key.pem"),
      "-out",
      join(dir, "cert.pem"),
      "-subj",
      "/CN=localhost",
      "-addext",
      "subjectAltName=DNS:localhost",
    ],
    { stdio: "ignore" },
  );
  certificate = readFileSync(join(dir, "cert.pem"));
  databasePath = temporaryDatabasePath();
  sidecar = await startSidecar(
    {
      host: "127.0.0.1",
      port: 0,
      mcpToken: "m".repeat(64),
      phoneToken: "p".repeat(64),
      liveCommandTimeoutSeconds: 1,
      databasePath,
      requestTtlSeconds: 86_400,
      pendingLimit: 100,
    },
    { log: () => undefined },
  );
  const upstream = new URL(sidecar.url);
  // A minimal TLS endpoint, like Tailscale Serve or Caddy: it ends TLS and forwards to loopback.
  proxy = createHttpsServer(
    { key: readFileSync(join(dir, "key.pem")), cert: certificate },
    (req, res) => {
      const forward = httpRequest(
        {
          hostname: upstream.hostname,
          port: upstream.port,
          path: req.url,
          method: req.method,
          headers: req.headers,
        },
        (answer) => {
          res.writeHead(answer.statusCode ?? 502, answer.headers);
          answer.pipe(res);
        },
      );
      forward.on("error", () => res.destroy());
      req.pipe(forward);
    },
  );
  await new Promise<void>((resolve) => proxy.listen(0, "127.0.0.1", resolve));
  httpsPort = (proxy.address() as AddressInfo).port;
});

after(async () => {
  await new Promise((resolve) => proxy.close(resolve));
  await sidecar.close();
});

describe("pairing over HTTPS", () => {
  it("exchanges the code for a credential through the TLS endpoint, which then works there", async () => {
    const serverUrl = `https://localhost:${httpsPort}`;
    const token = issue(serverUrl);
    const paired = await clients(serverUrl, token, certificate).pairing.pair({
      serverUrl,
      deviceName: "Seeker",
    });
    assert.equal(paired.serverId, sidecar.serverId);
    const { requests } = await clients(
      serverUrl,
      paired.phoneToken,
      certificate,
    ).requests.listPending({
      connectionId: paired.connectionId,
    });
    assert.deepEqual(requests, []);
  });

  it("fails on a certificate the phone doesn't trust, and the code stays unused", async () => {
    const serverUrl = `https://localhost:${httpsPort}`;
    const token = issue(serverUrl);
    await assert.rejects(
      clients(serverUrl, token).pairing.pair({ serverUrl, deviceName: "" }),
      (error) =>
        error instanceof ConnectError &&
        /self[- ]signed certificate/i.test(error.message),
    );
    // Nothing reached the sidecar, so the same code still pairs over a trusted connection.
    const paired = await clients(serverUrl, token, certificate).pairing.pair({
      serverUrl,
      deviceName: "",
    });
    assert.ok(paired.connectionId);
  });

  it("fails on a certificate for another host name", async () => {
    const serverUrl = `https://127.0.0.1:${httpsPort}`;
    const token = issue(serverUrl);
    await assert.rejects(
      clients(serverUrl, token, certificate).pairing.pair({
        serverUrl,
        deviceName: "",
      }),
      (error) =>
        error instanceof ConnectError &&
        /IP: 127\.0\.0\.1 is not in the cert's list|does not match/i.test(
          error.message,
        ),
    );
  });
});
