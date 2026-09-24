# Working on the SKR staking server (SEE-146)

`skr-staking-server/` is the repository's second direct server. It exists to show that Seeker Agent
Connect supports independent servers with different capabilities through one approval path, and it is
built to be run beside `mcp-server/` rather than instead of it.

- What it offers an agent: [integrations/skr-staking.md](../integrations/skr-staking.md)
- What the phone does with a request: [wiki/skr-staking.md](../wiki/skr-staking.md)
- What was verified on chain, and the owner-run test: [testing/see-146.md](../testing/see-146.md)

## The layout

| Path | What lives there |
| --- | --- |
| `src/cli.ts` | The executable: `start` and `pair`, with signal handling. |
| `src/config.ts` | Every `SKR_STAKING_*` variable, validated all at once. |
| `src/server.ts` | One listener carrying `/healthz`, `/mcp` and the SDK's phone API. |
| `src/mcp-endpoint.ts` | The agent's transport: bearer token, allowed hosts, session handling. |
| `src/requests/tools.ts` | The five tools. |
| `src/skr/program.ts` | The program's identity, its discriminators and every PDA derivation. |
| `src/skr/accounts.ts` | The account decoders. |
| `src/skr/shares.ts` | Exact share arithmetic, including the full-position case. |
| `src/skr/instructions.ts` | The four instruction builders, accounts in IDL order. |
| `src/skr/chain.ts` | Solana RPC, and the genesis check that refuses the wrong cluster. |
| `src/skr/provider.ts` | Chain reads, each operation's preconditions, and preparation. |

Everything specific to SKR is under `src/skr/`. Everything else is the Direct Server SDK
(`server-sdk/`), which owns pairing, the request lifecycle, preparation, results and confirmation —
the same package `mcp-server/` uses, unchanged by this server's existence.

## Two servers, one host

Every configuration name this server reads is prefixed `SKR_STAKING_`, and that is a rule rather than
a style: the two servers are meant to run side by side, often out of one `.env`, and two servers that
both read `SIDECAR_PORT` cannot both be configured. `scripts/check-deployments.mjs` fails if this
server's `.env.example` starts reusing one of the general server's names, and if its default port
stops being a different one (8090 against 8080).

## Running it

```bash
pnpm install
cp skr-staking-server/.env.example .env
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

The phone's half is Kotlin, under `android/app/src/test/java/io/github/brrenat/seekervault/skr/`:

```bash
android/gradlew -p android :app:testDebugUnitTest --tests 'io.github.brrenat.seekervault.skr.*' \
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
