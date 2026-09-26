import { create } from "@bufbuild/protobuf";
import {
  openDirectServer,
  privateRequest,
  startPhoneApi,
} from "@seeker-vault/server-sdk";
import {
  AckActionSchema,
  ActionSchema,
} from "@seeker-vault/server-sdk/protocol";

const direct = openDirectServer({
  databasePath: process.env.DIRECT_DATABASE_PATH ?? "/var/lib/direct/server.db",
  publicOrigin: process.env.DIRECT_PUBLIC_ORIGIN ?? "http://127.0.0.1:8080",
  requestTtlSeconds: 86_400,
  pendingLimit: 100,
  pairingTokenTtlSeconds: 600,
  liveCommandTimeoutSeconds: 30,
  log: (message) => console.info(`[direct] ${message}`),
});
const phoneApi = await startPhoneApi(direct, {
  host: "127.0.0.1",
  port: 8080,
});

console.info(`Pair once with ${direct.pairing.issue().uri}`);
const action = create(ActionSchema, {
  kind: {
    case: "ack",
    value: create(AckActionSchema, { text: "Review this request" }),
  },
});
const created = direct.requests.createRequest(
  privateRequest(action, "Review this request", "minimal-example-1", 300),
);
const requestId = created.request.ref?.requestId ?? "";
const stopObserving = direct.requests.observe(requestId, (request) => {
  console.info(`Request ${requestId} is now ${request.state}`);
});

async function shutdown(): Promise<void> {
  direct.beginShutdown();
  await phoneApi.close();
  stopObserving();
  await direct.close();
}

process.once("SIGINT", () => void shutdown());
process.once("SIGTERM", () => void shutdown());
