# Embedding the Direct Server SDK

`@seeker-vault/server-sdk` lets a TypeScript backend host a private direct connection to Seeker
Agent Connect without running MCP, a feed gateway, Redis or a deployment proxy. The package is
prepared and tested as a local npm tarball in SEE-131; it has not been published to a registry.

The supported runtime is Node.js `>=24.21.0 <25`, using ESM. Install a reviewed local tarball as
described in [`packages/server-sdk/README.md`](../../packages/server-sdk/README.md). Import only:

- `@seeker-vault/server-sdk` for initialization, request/pairing APIs and optional provider types;
- `@seeker-vault/server-sdk/protocol` for the direct protobuf messages and service descriptors.

Source-relative imports and any other package subpath are private and unsupported.

## Host lifecycle

1. Call `openDirectServer` with an explicit SQLite path, public origin, request/pairing limits and
   logger. Optional preparation, confirmation and invalidation adapters are host supplied, as is
   the gateway relay configuration described below.
2. Mount `direct.phoneHandler()` in an existing Node listener, or call `startPhoneApi` for a small
   dedicated loopback HTTP listener.
3. Show the one-use URI returned by `direct.pairing.issue()` to the owner. Phone credentials are
   exchanged and checked only on the phone API; they are not MCP or backend credentials.
4. Create/read/cancel/observe private requests through `direct.requests`. A successful create means
   the request is durable, not approved. The SDK cannot approve, sign, open a wallet or broadcast.
5. During shutdown, stop accepting new host traffic, call `direct.beginShutdown()`, close the
   listener, unsubscribe observers, then await `direct.close()`. Closing twice is safe.

Importing either package entry point alone does not read `.env`, open a file/database or port,
register a process signal, initialize Firebase/Solana/MCP, or start a timer/background loop.

The runnable [`minimal.ts`](../../packages/server-sdk/examples/minimal.ts) example shows this sequence using
only public imports. Production ingress, TLS, authorization, health checks and process signals stay
in the embedding host.

## Waking a phone

A direct server that holds a Firebase service-account credential passes an `invalidationSender`;
one that does not passes `relay`, and the gateway's operator sends on its behalf (SEE-144):

```ts
openDirectServer({
  // …
  relay: {
    relayUrl: "https://feeds.example.com",       // the gateway's origin, from its operator
    serverId: "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
    credential: process.env.RELAY_CREDENTIAL!,   // scoped: it cannot publish and is not admin
  },
});
```

Configuring both is refused at startup: they fire on the same committed update, so a phone would be
woken twice for one change. The relay configuration is validated when the server opens, so a URL
that is not an origin or a server ID that is not a lowercase UUID is a named failure rather than
wake-ups that go nowhere.

The relay handle a phone hands over (`PairingService.SetRelayHandle`) is stored against that
connection, in its own column, and is never placed in the FCM-target field: one addresses a device
and works for whoever holds it, the other addresses one authorization at one gateway. Revoking a
connection clears both in the statement that ends it.

Every outcome is swallowed. A push is a hint, and a request that was created, committed and
answered is not undone by a phone hearing about it later — an unreachable relay never fails a
create, a synchronization or a decision. A handle the gateway no longer honours is compare-cleared,
so a re-authorization racing a refusal is not thrown away.

The walkthrough, including what the operator registers and what rotation and revocation each end,
is [`docs/guides/server-development.md#17-waking-a-phone-from-a-server-you-host-yourself`](../guides/server-development.md#17-waking-a-phone-from-a-server-you-host-yourself).

## Persistence and upgrades

The SDK uses the same direct SQLite schema as the pre-extraction sidecar. Pointing it at the existing
database preserves the server ID, pairings, hashed phone credential, wallet binding, requests,
prepared versions, results, idempotency and update history. Back up the database before an upgrade;
rollback means restoring the complete matching backup before starting the older build. Never ask an
older build to interpret a newer schema.

## Package and release status

Run `pnpm test:server-sdk-package` to build and inspect the exact tarball, install it into a temporary
project outside the workspace, type-check the public API and exercise a direct lifecycle. The
package remains `private: true`; there is no npm token, upload or automatic publication workflow.
Future publication is a separate, deliberate release-owner action after version, provenance and
the exact packed files have been reviewed.
