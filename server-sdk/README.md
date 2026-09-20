# `@seeker-vault/server-sdk`

An embeddable TypeScript engine for a Seeker Agent Connect direct server. Your backend calls the
SDK in-process to create, read, cancel and observe requests. The owner's phone pairs with and calls
the mounted Connect API. The SDK never holds a wallet key, approves, signs or broadcasts.

This repository prepares `0.1.0` as a private npm artifact for local-tarball verification. It is not
published. The supported runtime is Node.js `>=24.21.0 <25`, and the package is ESM-only.

## Install a reviewed local tarball

From this repository:

```sh
pnpm --filter @seeker-vault/server-sdk run build
npm pack --dry-run --json ./server-sdk
npm pack --json ./server-sdk --pack-destination /absolute/reviewed/directory
```

Then, from a separate project outside this repository:

```sh
npm install --ignore-scripts /absolute/reviewed/directory/seeker-vault-server-sdk-0.1.0.tgz
```

The SDK has no registry release yet. A future registry install must be version-pinned after a
release owner deliberately publishes it; do not use an unversioned `latest` instruction before
that release exists.

## Public API

The package exports only `@seeker-vault/server-sdk` and
`@seeker-vault/server-sdk/protocol`. There are no supported source or private deep imports.

`openDirectServer` explicitly opens and migrates the configured SQLite file. It requires a durable
path, public origin, logger and the current request/pairing limits. Importing the package alone does
not open a file or port, inspect `.env`, initialize an integration, register a signal handler or
start a loop.

```ts
import {
  openDirectServer,
  privateRequest,
  startPhoneApi,
} from "@seeker-vault/server-sdk";
import {
  AckActionSchema,
  ActionSchema,
} from "@seeker-vault/server-sdk/protocol";
import { create } from "@bufbuild/protobuf";

const server = openDirectServer({
  databasePath: "/var/lib/my-server/direct.db",
  publicOrigin: "https://direct.example.com",
  requestTtlSeconds: 86_400,
  pendingLimit: 100,
  pairingTokenTtlSeconds: 600,
  liveCommandTimeoutSeconds: 30,
  log: (message) => console.info(`[direct] ${message}`),
});
const phoneApi = await startPhoneApi(server, {
  host: "127.0.0.1",
  port: 8080,
});

const pairing = server.pairing.issue();
console.info(`Show this once to the owner: ${pairing.uri}`);

const action = create(ActionSchema, {
  kind: {
    case: "ack",
    value: create(AckActionSchema, { text: "Review this request" }),
  },
});
const created = server.requests.createRequest(
  privateRequest(action, "Review this request", "example-1", 300),
);
const stopObserving = server.requests.observe(
  created.request.ref?.requestId ?? "",
  (request) => console.info(`Request is now ${request.state}`),
);

// On process shutdown: stop accepting traffic before closing the durable engine.
server.beginShutdown();
await phoneApi.close();
stopObserving();
await server.close();
```

The complete runnable source is available in the repository's
[`examples/minimal.ts`](https://github.com/BrRenat/SeekerAgentWallet/blob/superset/feat/see-128/server-sdk/examples/minimal.ts).
The example is intentionally excluded from the runtime tarball. A production host may
mount `server.phoneHandler()` in its existing listener instead; that is how the repository's MCP
host shares one port without reaching SDK internals.

## Optional integrations

Concrete preparation/confirmation and invalidation implementations are constructor options. If
they are absent, the SDK imports no Solana or Firebase package, reads no provider credential and
starts no integration. The provider interfaces have no signing or submission method.

MCP tokens and OAuth configuration belong to the MCP host. Paired-phone credentials stay in the
SDK database and are accepted only by the phone API.

## Persistence and shutdown

The database uses the existing direct-server schema and identity. Reopening the same file preserves
the server ID, phone credential, wallet binding, idempotency records, prepared versions, results,
revision events and snapshots. `beginShutdown()` cancels in-memory live/update streams.
`close()` drains queued best-effort invalidations and closes SQLite; it is safe to call twice.

Back up the SQLite database with the direct-server procedure before an update. Do not run an older
binary against a schema it reports as newer; rollback restores the matching pre-update backup.

## Release policy

The package is `private: true`, and this repository contains no npm token or publish workflow. A
future release is manual: choose and review a semantic version, run all package/consumer checks,
inspect the exact tarball, remove the private guard in a dedicated reviewed release change,
authenticate to the intended registry and publish that reviewed artifact deliberately.
