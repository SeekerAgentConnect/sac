/**
 * The prediction provider, as this integration run serves it (SEE-98): the real captured answers in
 * `publisher/internal/jupiter/testdata/`, served back over loopback to the template's own HTTP
 * client.
 *
 * Those seven answers are the ones `publisher/internal/jupiter`'s tests already hold the client to,
 * captured from the live keyless API by `scripts/capture-jupiter.mjs`. Serving them to the template
 * **binary** is the one step those tests do not take: `PREDICTION_PROVIDER_URL` accepts any
 * loopback origin, so the shipped `prediction` binary — its real client, its real pacing, its real
 * fault classification — reads real provider answers without a network.
 *
 * Two deliberate departures from the bytes on disk, both about time rather than shape:
 *
 *   - **Every timestamp is moved forward by the age of the capture.** A market's close time decides
 *     whether it is published at all, and a binary reads the real clock. Pinning a clock is what
 *     the Go tests do; a process cannot be handed one, so the answers arrive as they would have on
 *     the day they were captured. The spacing between them, which is what the filters actually
 *     judge, is untouched.
 *   - **A market answers under the identity it was asked about.** The captured listing names five
 *     markets and the captured single-market answers are two particular ones, so a direct read
 *     serves the open answer's shape — and, after `close()`, the closed answer's shape — with the
 *     provider, market ID and event of the market in the request. A withdrawal is only meaningful
 *     for a market somebody is following. That is the stand-in `docs/testing/stage-7-1.md` records
 *     for SEE-96, for the same reason: a market closes when it closes.
 *
 * It records every request, which is what lets the suite assert what the template asked for — and,
 * more to the point, what it never asked for. There is no order, no fill, no settlement and no
 * position route here, and a template that looked for one would be recorded asking for a path that
 * does not exist.
 */
import { readFileSync } from "node:fs";
import { once } from "node:events";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const TESTDATA = fileURLToPath(
  new URL("../../../publisher/internal/jupiter/testdata/", import.meta.url),
);

/** The market the captured listing's first event opens with, and the one the suite follows. */
export const TRACKED_MARKET = "POLY-2589813";
/** Every market that event lists; the template publishes one signal for each of them. */
export const TRACKED_MARKETS = [
  "POLY-2589810",
  "POLY-2589811",
  "POLY-2589812",
  "POLY-2589813",
  "POLY-2589814",
];
/** Its event, which a published signal names as well. */
export const TRACKED_EVENT = "POLY-606422";

/** One request the template made, as the provider saw it. */
export interface ProviderRequest {
  readonly method: string;
  /** Path and query, exactly as sent: the query is where the filters show up. */
  readonly target: string;
  /** Every header, so a test can assert no credential of the deployment's reached a provider. */
  readonly headers: Record<string, string>;
}

export interface FakeProvider {
  readonly url: string;
  /** Every request so far, in order. */
  readonly requests: readonly ProviderRequest[];
  /** The market leaves the listing and answers as closed, which is a withdrawal on the next pass. */
  close(): void;
  stop(): Promise<void>;
}

interface Captured {
  readonly capturedAt: string;
  readonly cases: readonly {
    readonly name: string;
    readonly status: number;
    readonly body: string;
  }[];
}

/** Serves the captured answers on loopback. Nothing here reaches a provider. */
export async function startFakeProvider(): Promise<FakeProvider> {
  const captured = JSON.parse(
    readFileSync(join(TESTDATA, "captured.json"), "utf8"),
  ) as Captured;
  const shift = Math.floor(
    (Date.now() - Date.parse(captured.capturedAt)) / 1000,
  );
  const answer = (name: string): { status: number; body: unknown } => {
    const held = captured.cases.find((one) => one.name === name);
    if (held === undefined) throw new Error(`no captured answer ${name}`);
    return {
      status: held.status,
      body: moved(
        JSON.parse(readFileSync(join(TESTDATA, held.body), "utf8")),
        shift,
      ),
    };
  };

  const requests: ProviderRequest[] = [];
  let closed = false;
  const server: Server = createServer((request, response) => {
    const target = request.url ?? "";
    requests.push({
      method: request.method ?? "",
      target,
      headers: Object.fromEntries(
        Object.entries(request.headers).map(([name, value]) => [
          name,
          Array.isArray(value) ? value.join(",") : (value ?? ""),
        ]),
      ),
    });
    const [path = "", query = ""] = target.split("?");
    const sent = (status: number, body: unknown): void => {
      // One request per connection. Node closes an idle keep-alive socket after five seconds, and
      // a client that writes its next request into that exact moment waits for headers that never
      // come — which showed up here as a provider timeout minutes after the last call.
      response.writeHead(status, {
        "content-type": "application/json",
        connection: "close",
      });
      response.end(JSON.stringify(body));
    };
    if (path === "/prediction/v1/events") {
      const start = Number(new URLSearchParams(query).get("start") ?? "0");
      // The listing is one page long. A walk that asked for a second page is told there is
      // nothing there, which is what stops it, and a closed market has left the listing.
      if (closed || start > 0) {
        const empty = answer("events-empty");
        sent(empty.status, empty.body);
        return;
      }
      const first = answer("events-first");
      sent(first.status, first.body);
      return;
    }
    const market = /^\/prediction\/v1\/markets\/([^/]+)$/.exec(path)?.[1];
    if (market !== undefined) {
      if (!TRACKED_MARKETS.includes(market)) {
        const missing = answer("market-missing");
        sent(missing.status, missing.body);
        return;
      }
      if (closed) {
        // The event is over, so every market on it is: the captured closed answer's own shape,
        // under the identity of the market that was asked about.
        const shut = answer("market-closed").body as Record<string, unknown>;
        sent(200, {
          ...shut,
          provider: "polymarket",
          marketId: market,
          eventId: TRACKED_EVENT,
        });
        return;
      }
      const open = answer("market-open").body as Record<string, unknown>;
      sent(200, {
        ...open,
        provider: "polymarket",
        marketId: market,
        eventId: TRACKED_EVENT,
      });
      return;
    }
    sent(404, { code: "no_such_route", message: `nothing serves ${path}` });
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const { port } = server.address() as AddressInfo;
  return {
    url: `http://127.0.0.1:${port}`,
    requests,
    close() {
      closed = true;
    },
    async stop() {
      server.closeAllConnections();
      server.close();
      await once(server, "close");
    },
  };
}

/**
 * Moves every epoch-seconds field forward by `shift`. The provider's timestamps are named
 * `openTime`, `closeTime` and `resolveAt`; anything else that looks like a time is left alone,
 * because a fixture rewritten more broadly than it has to be stops being the captured answer.
 */
function moved(value: unknown, shift: number): unknown {
  if (Array.isArray(value)) return value.map((one) => moved(one, shift));
  if (value === null || typeof value !== "object") return value;
  return Object.fromEntries(
    Object.entries(value as Record<string, unknown>).map(([name, held]) => {
      if (
        (name === "openTime" || name === "closeTime" || name === "resolveAt") &&
        typeof held === "number"
      ) {
        return [name, held + shift];
      }
      return [name, moved(held, shift)];
    }),
  );
}
