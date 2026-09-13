/**
 * Test-only HTTP/2 server for GrpcBidiInteropTest. It deliberately serves no sidecar state: its
 * one job is to prove that the pinned Connect Kotlin/OkHttp client and Connect Node adapter can
 * exchange client and server messages in both directions before either side closes the stream.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { createSecureServer } from "node:http2";
import type { ServerHttp2Session } from "node:http2";
import type { AddressInfo } from "node:net";

import { create } from "@bufbuild/protobuf";
import { Code, ConnectError, type HandlerContext } from "@connectrpc/connect";
import { connectNodeAdapter } from "@connectrpc/connect-node";

import {
  ResumeDisposition,
  SubscribeResponseSchema,
  ServerHeartbeatSchema,
  ServerReadySchema,
  UpdateService,
  type SubscribeRequest,
  type SubscribeResponse,
} from "../gen/seekervault/update/v1/update_pb.js";

const token = required("PROOF_PHONE_TOKEN");
const connectionId = required("PROOF_CONNECTION_ID");
const markerPath = required("PROOF_MARKER_PATH");
const certificatePath = required("PROOF_CERTIFICATE_PATH");
const privateKeyPath = required("PROOF_PRIVATE_KEY_PATH");
let streamsStarted = 0;
const sessions = new Set<ServerHttp2Session>();

const rpc = connectNodeAdapter({
  routes: (router) =>
    router.service(UpdateService, {
      subscribe: (requests, context) => replies(requests, context),
    }),
  readMaxBytes: 65_536,
  writeMaxBytes: 65_536,
});

const server = createSecureServer(
  {
    allowHTTP1: true,
    cert: readFileSync(certificatePath),
    key: readFileSync(privateKeyPath),
  },
  (request, response) => {
    if (request.httpVersion !== "2.0") {
      response.writeHead(505).end();
      return;
    }
    rpc(request, response);
  },
);
server.on("session", (session) => {
  sessions.add(session);
  session.once("close", () => sessions.delete(session));
});

server.listen(0, "127.0.0.1", () => {
  const { port } = server.address() as AddressInfo;
  process.stdout.write(`https://localhost:${port}\n`);
});

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, () => {
    server.close();
    for (const session of sessions) session.destroy();
  });
}

async function* replies(
  requests: AsyncIterable<SubscribeRequest>,
  context: HandlerContext,
): AsyncGenerator<SubscribeResponse> {
  authenticate(context);
  streamsStarted += 1;
  let graceful = false;
  let messages = 0;
  let heartbeats = 0;
  context.signal.addEventListener(
    "abort",
    () => {
      if (!graceful) {
        record({
          outcome: "cancelled",
          httpVersion: "2.0",
          protocol: context.protocolName,
          streamsStarted,
          messages,
          heartbeats,
        });
      }
    },
    { once: true },
  );

  for await (const request of requests) {
    messages += 1;
    if (request.connectionId !== connectionId) {
      throw new ConnectError("no such connection", Code.NotFound);
    }
    if (messages === 1) {
      if (
        request.message.case !== "subscribe" ||
        request.message.value.protocolVersion !== 1
      ) {
        throw new ConnectError(
          "the first message must subscribe with protocol version 1",
          Code.FailedPrecondition,
        );
      }
      yield create(SubscribeResponseSchema, {
        connectionId,
        serverInstanceId: "bidi-proof",
        cursor: "0",
        event: {
          case: "ready",
          value: create(ServerReadySchema, {
            protocolVersion: 1,
            resume: ResumeDisposition.FULL_SYNC_REQUIRED,
            heartbeatIntervalSeconds: 30,
            maxMessageBytes: 65_536,
            maxPageSize: 50,
          }),
        },
      });
      continue;
    }
    if (request.message.case !== "heartbeat") {
      throw new ConnectError(
        "only heartbeats follow subscribe",
        Code.InvalidArgument,
      );
    }
    heartbeats += 1;
    yield create(SubscribeResponseSchema, {
      connectionId,
      serverInstanceId: "bidi-proof",
      cursor: `${heartbeats}`,
      event: {
        case: "heartbeat",
        value: create(ServerHeartbeatSchema, {
          acknowledgedSequence: request.message.value.sequence,
        }),
      },
    });
  }

  graceful = true;
  if (heartbeats < 2) {
    throw new ConnectError(
      "the client closed before two responses interleaved",
      Code.FailedPrecondition,
    );
  }
  record({
    outcome: "complete",
    httpVersion: "2.0",
    protocol: context.protocolName,
    streamsStarted,
    messages,
    heartbeats,
  });
}

function authenticate(context: HandlerContext): void {
  if (context.requestHeader.get("authorization") !== `Bearer ${token}`) {
    throw new ConnectError(
      "a valid phone credential is required",
      Code.Unauthenticated,
    );
  }
}

function record(value: object): void {
  writeFileSync(markerPath, JSON.stringify(value), { encoding: "utf8" });
}

function required(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === "")
    throw new Error(`${name} is required`);
  return value;
}
