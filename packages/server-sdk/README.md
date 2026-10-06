# `@seekeragentconnect/server-sdk`

An embeddable TypeScript engine for a Seeker Agent Connect direct server. Your backend calls the
SDK in-process to create, read, cancel and observe requests. The owner's phone pairs with and calls
the mounted Connect API. The SDK never holds a wallet key, approves, signs or broadcasts.

Releases of this package are published to npm by the release workflow of
[SeekerAgentConnect/sac](https://github.com/SeekerAgentConnect/sac), with provenance. The supported
runtime is Node.js `>=24.21.0 <25`, and the package is ESM-only.

## Install

```sh
npm install @seekeragentconnect/server-sdk
```

Generated protocol types are the second entry point, `@seekeragentconnect/server-sdk/protocol`;
there is no separate protocol package.

### A reviewed local tarball

From a checkout of the repository:

```sh
pnpm --filter @seekeragentconnect/server-sdk run build
npm pack --dry-run --json ./packages/server-sdk
npm pack --json ./packages/server-sdk --pack-destination /absolute/reviewed/directory
```

Then, from a separate project outside this repository:

```sh
npm install --ignore-scripts /absolute/reviewed/directory/seekeragentconnect-server-sdk-0.0.1.tgz
```

## Public API

The package exports only `@seekeragentconnect/server-sdk` and
`@seekeragentconnect/server-sdk/protocol`. There are no supported source or private deep imports.

`openDirectServer` explicitly opens and migrates the configured SQLite file. It requires a durable
path, public origin, logger and the current request/pairing limits. Importing the package alone does
not open a file or port, inspect `.env`, initialize an integration, register a signal handler or
start a loop.

```ts
import {
  openDirectServer,
  privateRequest,
  startPhoneApi,
} from "@seekeragentconnect/server-sdk";
import {
  AckActionSchema,
  ActionSchema,
} from "@seekeragentconnect/server-sdk/protocol";
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
[`examples/minimal.ts`](https://github.com/SeekerAgentConnect/sac/blob/master/packages/server-sdk/examples/minimal.ts).
The example is intentionally excluded from the runtime tarball. A production host may
mount `server.phoneHandler()` in its existing listener instead; that is how the repository's MCP
host shares one port without reaching SDK internals.

## Optional integrations

Concrete preparation/confirmation and invalidation implementations are constructor options. If
they are absent, the SDK imports no Solana or Firebase package, reads no provider credential and
starts no integration. The provider interfaces have no signing or submission method.

MCP tokens and OAuth configuration belong to the MCP host. Paired-phone credentials stay in the
SDK database and are accepted only by the phone API.

## Declaring networks

`supportedNetworks` is the list of Solana networks the server's manifest declares (SEE-174). The
phone offers only wallets on those networks and signs only for them. It has **no default**: omitted
or empty declares no network, which the phone never reads as Mainnet or as every network, and an
up-to-date phone signs nothing for such a server. The example above queues only acknowledgements,
so it rightly declares none; a server that asks the owner to sign passes what it runs against:

```ts
import { parseSupportedNetworks } from "@seekeragentconnect/server-sdk";

openDirectServer({
  // …
  supportedNetworks: parseSupportedNetworks(
    process.env.SAC_SUPPORTED_NETWORKS ?? "", // e.g. "mainnet" or "mainnet,devnet"
    "SAC_SUPPORTED_NETWORKS",
  ),
});
```

`parseSupportedNetworks` accepts `mainnet`, `devnet` and `testnet` (lowercase), or `none` alone,
refuses unknown names, duplicates and empty entries, and returns canonical order. `openDirectServer` refuses an
invalid list before opening anything. Changing the list moves the manifest's settings revision; an
unchanged list, in any order, keeps it. Network is not environment: `production` is not Mainnet.

## Persistence and shutdown

The database uses the existing direct-server schema and identity. Reopening the same file preserves
the server ID, phone credential, wallet binding, idempotency records, prepared versions, results,
revision events and snapshots. `beginShutdown()` cancels in-memory live/update streams.
`close()` drains queued best-effort invalidations and closes SQLite; it is safe to call twice.

Back up the SQLite database with the direct-server procedure before an update. Do not run an older
binary against a schema it reports as newer; rollback restores the matching pre-update backup.

## Release policy

The SDK is versioned on its own (semantic versioning; tags `server-sdk-vX.Y.Z`). A release is
published only by the repository's release workflow from the tagged commit, through npm trusted
publishing with provenance; stable versions take the `latest` dist-tag and release candidates
(`X.Y.Z-rc.N`) take `next`. The MCP servers in the same repository carry their own copy of the SDK
and are released independently of it. The procedure is
[docs/development/releases.md](https://github.com/SeekerAgentConnect/sac/blob/master/docs/development/releases.md).
