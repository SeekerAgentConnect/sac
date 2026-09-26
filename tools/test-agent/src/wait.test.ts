/**
 * The bound `wait` and `--wait` advertise (SAW-037).
 *
 * A deadline is only a deadline if it is enforced while a read is in flight. Reading a SUBMITTED
 * transfer makes the sidecar check the chain, so a poll can take seconds; the wait must still come
 * back when it said it would, report the request as it was last seen, and call that a timeout
 * rather than a failure.
 *
 * The client here is a stub that behaves as the MCP SDK does — it honours the timeout it is given
 * and rejects with `RequestTimeout` — so what is under test is this package's own budgeting.
 */
import assert from "node:assert/strict";
import { describe, it } from "node:test";

import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import {
  ErrorCode,
  McpError,
  type CallToolResult,
} from "@modelcontextprotocol/sdk/types.js";

import { waitForRequest } from "./agent.ts";

const REQUEST_ID = "7c1f0a9e-2b3c-4d5e-8f60-112233445566";

function pending(): CallToolResult {
  return {
    content: [],
    structuredContent: {
      request_id: REQUEST_ID,
      action: "transfer",
      status: "SUBMITTED",
      terminal: false,
      created_at: "2026-09-17T00:00:00.000Z",
      expires_at: "2026-09-18T00:00:00.000Z",
      updated_at: "2026-09-17T00:00:00.000Z",
    },
  };
}

/**
 * A client whose first `answers` calls return at once and whose later ones never answer, failing
 * at whatever timeout they were given — exactly what a slow confirmation check looks like.
 */
function slowClient(answers: number): { client: Client; timeouts: number[] } {
  const timeouts: number[] = [];
  let calls = 0;
  const client = {
    callTool: (
      _request: unknown,
      _schema: unknown,
      options?: { readonly timeout?: number },
    ): Promise<CallToolResult> => {
      calls += 1;
      timeouts.push(options?.timeout ?? 0);
      if (calls <= answers) return Promise.resolve(pending());
      return new Promise((_resolve, reject) => {
        setTimeout(
          () =>
            reject(
              new McpError(
                Number(ErrorCode.RequestTimeout),
                "Request timed out",
              ),
            ),
          options?.timeout ?? 30_000,
        );
      });
    },
  } as unknown as Client;
  return { client, timeouts };
}

describe("waiting for a request", () => {
  it("gives each read only what is left of the deadline", async () => {
    const { client, timeouts } = slowClient(1);
    const started = Date.now();
    const result = await waitForRequest(client, REQUEST_ID, {
      timeoutMs: 400,
      intervalMs: 50,
    });
    const elapsed = Date.now() - started;

    // It came back on its own deadline, not on the call's 30-second one.
    assert.ok(elapsed < 3000, `waited ${String(elapsed)} ms`);
    assert.equal(result.timedOut, true);
    // And it reports the request as it was last seen, rather than an error.
    assert.equal(result.view.request_id, REQUEST_ID);
    assert.equal(result.view.status, "SUBMITTED");
    assert.equal(result.polls, 1);
    // No read was ever given more time than the wait had left.
    assert.ok(timeouts.length >= 2, "it read more than once");
    for (const timeout of timeouts) {
      assert.ok(timeout <= 400, `a read was given ${String(timeout)} ms`);
    }
  });

  it("reports the read itself when nothing has been seen yet", async () => {
    // With no answer at all, there is no request to report, and inventing one would be worse than
    // saying the sidecar did not answer.
    const { client } = slowClient(0);
    await assert.rejects(
      waitForRequest(client, REQUEST_ID, { timeoutMs: 200, intervalMs: 50 }),
      (error: unknown) => error instanceof McpError,
    );
  });

  it("stops as soon as the request is terminal", async () => {
    let calls = 0;
    const client = {
      callTool: (): Promise<CallToolResult> => {
        calls += 1;
        return Promise.resolve({
          content: [],
          structuredContent: {
            ...pending().structuredContent,
            status: "CONFIRMED",
            terminal: true,
          },
        });
      },
    } as unknown as Client;

    const result = await waitForRequest(client, REQUEST_ID, {
      timeoutMs: 600_000,
      intervalMs: 60_000,
    });
    assert.equal(result.timedOut, false);
    assert.equal(result.view.status, "CONFIRMED");
    assert.equal(result.polls, 1);
    assert.equal(calls, 1, "it read once and did not wait for the interval");
  });
});
