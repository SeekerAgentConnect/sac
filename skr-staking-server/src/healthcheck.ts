/**
 * The container health probe: one request to this server's own `/healthz`, over loopback.
 *
 * It exists as a compiled file rather than an inline `CMD` so the probe and the server agree about
 * the port without the Dockerfile repeating it, and so a probe that starts failing is a thing with
 * a name that can be read.
 */
import { request as httpRequest } from "node:http";
import { connect } from "node:http2";

const PORT = Number(process.env.SKR_STAKING_PORT ?? "8080");
const H2C = (process.env.SKR_STAKING_H2C ?? "").trim().toLowerCase() === "true";
const TIMEOUT_MS = 4_000;

async function probeHttp1(): Promise<boolean> {
  return new Promise<boolean>((resolve) => {
    const request = httpRequest(
      { host: "127.0.0.1", port: PORT, path: "/healthz", timeout: TIMEOUT_MS },
      (response) => {
        response.resume();
        resolve(response.statusCode === 200);
      },
    );
    request.on("timeout", () => request.destroy());
    request.on("error", () => resolve(false));
    request.end();
  });
}

async function probeH2c(): Promise<boolean> {
  return new Promise<boolean>((resolve) => {
    const session = connect(`http://127.0.0.1:${PORT}`);
    const done = (ok: boolean): void => {
      // A session left open would keep the probe process alive past its answer.
      session.destroy();
      resolve(ok);
    };
    session.setTimeout(TIMEOUT_MS, () => done(false));
    session.on("error", () => done(false));
    const stream = session.request({ ":path": "/healthz" });
    stream.on("response", (headers) => done(headers[":status"] === 200));
    stream.on("error", () => done(false));
    stream.end();
  });
}

process.exitCode = (await (H2C ? probeH2c() : probeHttp1())) ? 0 : 1;
