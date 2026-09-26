/**
 * The two shapes a Node response can have, written to as one.
 *
 * `ServerResponse` and `Http2ServerResponse` both have `writeHead` and `end`, but their overloads
 * do not unify, so a handler that must serve either needs one place where that is resolved. This is
 * that place, and it is the only place that casts.
 */
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Http2ServerRequest, Http2ServerResponse } from "node:http2";

export type AnyRequest = IncomingMessage | Http2ServerRequest;
export type AnyResponse = ServerResponse | Http2ServerResponse;

/** Sends a JSON body with a status and optional headers. */
export function writeJson(
  response: AnyResponse,
  status: number,
  body: unknown,
  headers: Readonly<Record<string, string>> = {},
): void {
  const target = response as ServerResponse;
  target.writeHead(status, { "Content-Type": "application/json", ...headers });
  target.end(JSON.stringify(body));
}

/** Whether anything has been written yet, for deciding if an error can still be reported. */
export function alreadyAnswered(response: AnyResponse): boolean {
  return (response as ServerResponse).headersSent;
}
