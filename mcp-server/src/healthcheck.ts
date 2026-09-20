/** The container readiness probe for HTTP/1, h2c, and native-TLS listeners. */
import { request as httpRequest } from "node:http";
import { connect as http2Connect } from "node:http2";
import { request as httpsRequest } from "node:https";
import { resolve } from "node:path";
import { rootCertificates } from "node:tls";
import { pathToFileURL } from "node:url";

import { readTlsTrustAnchor } from "./storage/tls.ts";

type Env = Readonly<Record<string, string | undefined>>;

export interface HealthcheckOptions {
  /** The image gives the whole process five seconds; the request must finish before that. */
  readonly timeoutMs?: number;
}

const DEFAULT_PORT = 8080;
const DEFAULT_TIMEOUT_MS = 3000;
const MAX_BODY_BYTES = 1024;

/**
 * Checks the actual application listener on container loopback.
 *
 * TLS mode still connects to 127.0.0.1, but validates the certificate against the public URL's
 * hostname. A private test/operator CA extends Node's normal roots; verification is never disabled.
 */
export async function checkHealth(
  env: Env,
  options: HealthcheckOptions = {},
): Promise<void> {
  const port = portOf(env.SIDECAR_PORT);
  const certificate = env.SIDECAR_TLS_CERT_PATH?.trim() || undefined;
  const tlsKey = env.SIDECAR_TLS_KEY_PATH?.trim() || undefined;
  if ((certificate === undefined) !== (tlsKey === undefined)) {
    throw new Error(
      "SIDECAR_TLS_CERT_PATH and SIDECAR_TLS_KEY_PATH must both be set or both be unset",
    );
  }
  const secure = certificate !== undefined;
  const h2c = env.SIDECAR_H2C?.trim().toLowerCase() === "true";
  if (h2c && secure) {
    throw new Error(
      "SIDECAR_H2C cannot be combined with SIDECAR_TLS_CERT_PATH",
    );
  }
  const publicUrl = secure ? tlsPublicUrl(env.SIDECAR_PUBLIC_URL) : undefined;
  const caPath = env.SIDECAR_HEALTH_CA_CERT_PATH?.trim() || undefined;
  if (!secure && caPath !== undefined) {
    throw new Error(
      "SIDECAR_HEALTH_CA_CERT_PATH applies only to the native TLS listener",
    );
  }
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0) {
    throw new Error("the health-check timeout must be a positive whole number");
  }

  const body = h2c
    ? await checkH2cHealth(port, timeoutMs)
    : await new Promise<string>((resolveBody, reject) => {
        const abort = new AbortController();
        const timer = setTimeout(() => {
          abort.abort(
            new Error(`health check timed out after ${timeoutMs} ms`),
          );
        }, timeoutMs);
        timer.unref();
        const request = (secure ? httpsRequest : httpRequest)(
          {
            hostname: "127.0.0.1",
            port,
            path: "/healthz",
            method: "GET",
            headers: {
              Host: secure
                ? (publicUrl?.host ?? "")
                : `127.0.0.1:${String(port)}`,
            },
            signal: abort.signal,
            ...(secure
              ? {
                  servername: publicUrl?.hostname,
                  rejectUnauthorized: true,
                  ...(caPath === undefined
                    ? {}
                    : {
                        ca: [...rootCertificates, readTlsTrustAnchor(caPath)],
                      }),
                }
              : {}),
          },
          (response) => {
            const chunks: Buffer[] = [];
            let bytes = 0;
            response.on("data", (chunk: Buffer) => {
              bytes += chunk.byteLength;
              if (bytes > MAX_BODY_BYTES) {
                response.destroy(
                  new Error("health response exceeded 1024 bytes"),
                );
                return;
              }
              chunks.push(chunk);
            });
            response.once("error", reject);
            response.once("end", () => {
              clearTimeout(timer);
              if (response.statusCode !== 200) {
                reject(
                  new Error(
                    `health endpoint returned HTTP ${String(response.statusCode ?? 0)}`,
                  ),
                );
                return;
              }
              resolveBody(Buffer.concat(chunks).toString("utf8"));
            });
          },
        );
        request.once("error", (error) => {
          clearTimeout(timer);
          reject(error);
        });
        request.end();
      });

  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch (error) {
    throw new Error("health endpoint did not return JSON", { cause: error });
  }
  if (
    typeof parsed !== "object" ||
    parsed === null ||
    Object.keys(parsed).length !== 1 ||
    (parsed as { status?: unknown }).status !== "ok"
  ) {
    throw new Error('health endpoint did not return {"status":"ok"}');
  }
}

function checkH2cHealth(port: number, timeoutMs: number): Promise<string> {
  return new Promise((resolveBody, reject) => {
    const timer = setTimeout(() => {
      reject(new Error(`health check timed out after ${timeoutMs} ms`));
    }, timeoutMs);
    timer.unref();
    const client = http2Connect(`http://127.0.0.1:${String(port)}`);
    const fail = (error: Error) => {
      clearTimeout(timer);
      client.close();
      reject(error);
    };
    client.once("error", fail);
    const request = client.request({
      ":method": "GET",
      ":path": "/healthz",
      ":authority": `127.0.0.1:${String(port)}`,
    });
    const chunks: Buffer[] = [];
    let bytes = 0;
    request.on("data", (chunk: Buffer) => {
      bytes += chunk.byteLength;
      if (bytes > MAX_BODY_BYTES) {
        request.close();
        fail(new Error("health response exceeded 1024 bytes"));
      } else {
        chunks.push(chunk);
      }
    });
    request.once("error", fail);
    request.once("response", (headers) => {
      const status = Number(headers[":status"] ?? 0);
      request.once("end", () => {
        clearTimeout(timer);
        client.close();
        if (status !== 200) {
          reject(new Error(`health endpoint returned HTTP ${String(status)}`));
          return;
        }
        resolveBody(Buffer.concat(chunks).toString("utf8"));
      });
    });
    request.end();
  });
}

function portOf(raw: string | undefined): number {
  const value = raw?.trim() || String(DEFAULT_PORT);
  const port = Number(value);
  if (!Number.isSafeInteger(port) || port < 1 || port > 65_535) {
    throw new Error("SIDECAR_PORT must be a whole number from 1 to 65535");
  }
  return port;
}

function tlsPublicUrl(raw: string | undefined): URL {
  let url: URL;
  try {
    url = new URL(raw?.trim() ?? "");
  } catch (error) {
    throw new Error(
      "SIDECAR_PUBLIC_URL must be a valid HTTPS URL for the native TLS health check",
      { cause: error },
    );
  }
  if (url.protocol !== "https:" || url.hostname === "") {
    throw new Error(
      "SIDECAR_PUBLIC_URL must be a valid HTTPS URL for the native TLS health check",
    );
  }
  return url;
}

function isMainModule(): boolean {
  const entry = process.argv[1];
  return (
    entry !== undefined &&
    pathToFileURL(resolve(entry)).href === import.meta.url
  );
}

if (isMainModule()) {
  try {
    await checkHealth(process.env);
  } catch (error) {
    console.error(
      `[mcp-healthcheck] ${error instanceof Error ? error.message : String(error)}`,
    );
    process.exitCode = 1;
  }
}
