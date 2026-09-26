# Working on the SKR staking server (SEE-146)

`servers/mcp-skr-staking/` is the repository's second direct server. It exists to show that Seeker Agent
Connect supports independent servers with different capabilities through one approval path, and it is
built to be run beside `servers/mcp-server/` rather than instead of it.

- What it offers an agent: [integrations/skr-staking.md](../integrations/skr-staking.md)
- What the phone does with a request: [wiki/skr-staking.md](../wiki/skr-staking.md)
- What was verified on chain, and the owner-run test: [testing/see-146.md](../testing/see-146.md)

## The layout

| Path | What lives there |
| --- | --- |
| `src/cli.ts` | The executable: `start` and `pair`, with signal handling. |
| `src/config.ts` | Every `SKR_STAKING_*` variable, validated all at once. |
| `src/server.ts` | One listener carrying `/healthz`, `/mcp`, `/pair` and the SDK's phone API. |
| `src/mcp-endpoint.ts` | The agent's transport: bearer token, allowed hosts, session handling. |
| `src/requests/tools.ts` | The five staking tools. |
| `src/pairing/mcp-tool.ts` | `skr_create_pairing_link` (SEE-149): the MCP registration only. |
| `src/pairing/landing-page.ts` | What the shared pairing page says it is, and where its QR library is. |
| `src/pairing/cli.ts` | `pair`, `pair status`, `pair revoke`, against this server's own database. |
| `src/skr/program.ts` | The program's identity, its discriminators and every PDA derivation. |
| `src/skr/accounts.ts` | The account decoders. |
| `src/skr/shares.ts` | Exact share arithmetic, including the full-position case. |
| `src/skr/instructions.ts` | The four instruction builders, accounts in IDL order. |
| `src/skr/chain.ts` | Solana RPC, and the genesis check that refuses the wrong cluster. |
| `src/skr/provider.ts` | Chain reads, each operation's preconditions, and preparation. |

Everything specific to SKR is under `src/skr/`. Everything else is the Direct Server SDK
(`packages/server-sdk/`), which owns pairing, the pairing page, the request lifecycle, preparation, results
and confirmation — the same package `servers/mcp-server/` uses, unchanged by this server's existence.

The pairing page is the clearest case of that rule. SEE-149 gave this server the HTTPS pairing link
`mcp-server` has, and it did it by moving the page, the fragment codec and the tool's wording into
`packages/server-sdk/src/pairing/` rather than copying them here: the token-in-the-fragment rule, the CSP and
the refusal of a shortened or foreign code are security-carrying code, and two copies of those would
drift. This package supplies the two things that are genuinely its own — the sentence saying which
server the owner is pairing, and the path to its own `uqr` build — and registers its own MCP tool,
because MCP is a host concern the SDK does not import. Nothing here imports from `mcp-server`, and
nothing should.

## Two servers, one host

Every configuration name this server reads is prefixed `SKR_STAKING_`, and that is a rule rather than
a style: the two servers are meant to run side by side, often out of one `.env`, and two servers that
both read `SIDECAR_PORT` cannot both be configured. `scripts/check-deployments.mjs` fails if this
server's `.env.example` starts reusing one of the general server's names, and if its default port
stops being a different one (8090 against 8080).

## Running it

```bash
pnpm install
cp servers/mcp-skr-staking/.env.example .env
pnpm --filter @seeker-vault/skr-staking-server run dev
```

Two values have no default: `SKR_STAKING_MCP_TOKEN` (`openssl rand -hex 32`) and
`SKR_STAKING_RPC_URL` (a mainnet-beta endpoint). A bad configuration is reported in full on the first
run — every problem at once, rather than one per attempt — and no value that could be a secret is ever
echoed, because an endpoint URL can carry an API key.

## Checks

```bash
pnpm --filter @seeker-vault/skr-staking-server run typecheck
pnpm --filter @seeker-vault/skr-staking-server run test
```

Both run in `pnpm check` with the rest of the workspace. The tests are offline: the account decoders
and the instruction builders run against bytes recorded from mainnet, and the share arithmetic against
the cases that matter — a whole-position unstake, a flooring conversion, the cooldown boundary.

The phone's half is Kotlin, under `apps/android/app/src/test/java/io/github/brrenat/seekervault/skr/`:

```bash
apps/android/gradlew -p apps/android :app:testDebugUnitTest --tests 'io.github.brrenat.seekervault.skr.*' \
  --tests 'io.github.brrenat.seekervault.inbox.StakingReviewScreenTest'
```

## Deploying it

```bash
cp deploy/skr-staking/.env.example deploy/skr-staking/.env
docker compose --env-file deploy/skr-staking/.env -f deploy/skr-staking/compose.yaml up --build
```

The image is built from the repository root, because the checkout supplies the unpublished SDK. It
runs as an unprivileged user, drops every capability, keeps its database on a named volume, and its
health check is a Node script rather than a shell fetch. `scripts/check-deployments.mjs` resolves the
compose project and asserts both.

The compose project joins the same `direct-ingress` network the general server uses, so one ingress
can serve both; the two are separate services with separate volumes and separate ports.

## Live updates and waking the phone (SEE-150)

The phone opens its update stream only on an origin the server advertised at pairing, and this server
advertised none. It *served* the update routes; it never said where they were. So `Pair` and
`GetConnectionCapabilities` came back with no update capability, the phone never subscribed, and a
staking request the agent had just created sat unseen until the owner pulled to refresh. Nothing
failed — which is the whole reason it was only noticed by somebody looking at a phone.

Two things had to be configured, and they are separate: **the stream**, which reaches a phone whose
app is open, and **the relay**, which wakes a phone whose app is not.

### The stream

`UpdateService.Subscribe` is gRPC over HTTP/2, and that is the constraint everything else follows
from.

| Deployment | Setting | What the phone is told |
| --- | --- | --- |
| Behind a TLS-terminating HTTP/2 proxy (App Platform) | `SKR_STAKING_H2C=true` | The public origin it paired with — the only one the proxy answers on |
| Loopback, phone on `adb reverse` | `SKR_STAKING_UPDATE_PORT=8091` | `http://127.0.0.1:<that port>` |
| Plain HTTP/1.1, neither set | — | Nothing. The phone refreshes by hand, which still works |

The second exists because of what h2c cannot do. Against an `http://` origin the phone makes its unary
calls over HTTP/1.1 — cleartext has no ALPN to negotiate anything better — and an h2c listener refuses
HTTP/1.1 outright. So a loopback server keeps HTTP/1.1 on its main port and carries the stream on a
second one, exactly as the general server does with `SIDECAR_UPDATE_PORT`. Setting both is refused at
startup: they answer the same question differently, and choosing one quietly would advertise an origin
the operator did not mean. `SKR_STAKING_UPDATE_PORT` also requires a loopback `SKR_STAKING_HOST` — a
cleartext listener has no business on a wildcard bind, and `0.0.0.0` is not an origin a phone could
reach anyway.

Startup says which it is, in one line, so an operator never has to infer it:

```
live updates are served as gRPC over HTTP/2 at https://staking.example.com
live updates are not served: set SKR_STAKING_H2C behind an HTTP/2 proxy, or
  SKR_STAKING_UPDATE_PORT on loopback; the phone refreshes only by hand
```

Recovery after a disconnect is the phone's, and it already existed: `ForegroundUpdateManager`
reconnects with backoff and resumes from its cursor. Nothing in this server had to change for that —
what changed is that there is now a stream for it to reconnect to.

### The relay

This server holds no Firebase credential and is not going to: the gateway's operator holds one and
wakes this server's paired phone on its behalf (SEE-144). Three variables, all or none:

```bash
SKR_STAKING_RELAY_URL=https://feeds.example.com
SKR_STAKING_RELAY_SERVER_ID=<this server's uuid>
SKR_STAKING_RELAY_CREDENTIAL=<shown once at registration>
```

Registered on the gateway host, or from its admin page with the relay box ticked:

```sh
feed-gatewayctl register --server <uuid> --label "skr staking" --for relay
```

The names are this server's own rather than the general server's `RELAY_*` because the two are
registered at the gateway as **two servers**, each with its own identity and credential, and one
`.env` has to be able to hold both. Two of the three is refused at startup: a server that looks like
it wakes phones and never does would show up only as an owner whose staking requests stop arriving
until they happen to open the app. The credential appears in no startup line and no configuration
problem — the check-in tests assert that it does not.

Startup says this too:

```
gateway push relay is configured: https://feeds.example.com as server <uuid>
gateway push relay is off; SKR_STAKING_RELAY_URL is not configured
```

The App Platform spec (`deploy/seeker-skr-staking-mcp.yaml`) already sets `SKR_STAKING_H2C=true`, so
the stream needs nothing there. The relay needs the three keys added as encrypted secrets by whoever
holds the gateway registration; they are deliberately **not** in the committed spec, because two of
three would stop the deployment from starting.

### Tests

`src/updates.test.ts` acts as the phone does, with its real transports — Connect for the unary calls,
genuine gRPC over cleartext HTTP/2 for `Subscribe`, the MCP SDK for the agent:

- the public HTTPS origin advertised on an h2c listener, and that `Pair` carries it
- nothing advertised on a plain HTTP/1.1 listener, and manual refresh still working
- an agent's staking request, and a later status change, arriving on an open `Subscribe`
- the phone woken through the gateway exactly once for a new request
- startup saying the relay is off when it is not configured

`src/config.test.ts` covers the refusals: `H2C` with `UPDATE_PORT`, a wildcard bind with
`UPDATE_PORT`, two of the three relay variables, a relay URL that is not an origin, and that no
problem ever repeats the credential.

## Things worth knowing before changing it

**The program's interface is derived, not copied.** `solana-mobile/react-native-samples` carries no
licence file, so nothing of it is vendored: instruction and account discriminators are computed as
Anchor computes them, the Borsh layouts are written out field by field, and every PDA is derived from
its seeds. Each derivation is asserted against the on-chain IDL and against real accounts.

**Only the program ID and the SKR mint are roots of trust.** The configuration, the vault and a stake
account are all program-derived, so they are computed rather than believed — on this side *and* on
the phone, independently.

**`unstake` takes shares, not SKR.** An owner's amount is converted against a fresh price, and a
request that covers the whole position passes `UserStake.shares` exactly: reconverting would floor a
second time and leave dust the owner could not close.

**Nothing here decides anything the chain decides.** The cooldown is `StakeConfig.cooldown_seconds`,
the minimum is `min_stake_amount`, and a withdrawal's amount is whatever the program recorded. All
three are read every time.

**It will not start against the wrong cluster.** Genesis hashes are compared, because a devnet
endpoint reads every account as absent — which looks exactly like an owner with nothing staked.
