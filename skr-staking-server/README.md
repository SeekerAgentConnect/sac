# SKR staking server

A standalone MCP server that lets an agent ask its owner to stake, unstake, cancel an unstake or
withdraw SKR, and reaches them through the review and wallet path Seeker Agent Connect already has.

It is a **second, independent direct server**. It shares no database, no token and no listener with
the general MCP server in `mcp-server/`, it connects to the phone as its own connection, and the two
can run side by side on one host because every configuration name it reads is its own.

It never signs and never sends. It builds unsigned transactions from fresh chain state; the phone
decodes those bytes itself, checks them against the request the owner saw and against the position
it reads for itself, and the owner's wallet is the only thing that signs.

## What it serves

| Tool | What it does |
| --- | --- |
| `get_staking_status` | Reads the connected wallet's SKR, active stake, pending unstake and withdrawal readiness. A read: it creates no request and builds no transaction. |
| `request_stake` | Creates an approval request to stake an amount of SKR. |
| `request_unstake` | Creates an approval request to start unstaking an amount. Starts a cooldown; moves nothing. |
| `request_cancel_unstake` | Creates an approval request to cancel the whole pending unstake. |
| `request_withdraw` | Creates an approval request to withdraw what a finished cooldown released. |

The wallet is never a parameter: it comes from the connection's own binding, so an agent cannot name
somebody else's. The four mutating tools create a request and stop there — they do not sign, execute
or approve anything. Calling one again with the same `idempotency_key` reads how the first one ended,
which is also what makes a retry after a lost response safe.

`request_cancel_unstake` and `request_withdraw` take no amount. The program acts on the whole pending
unstake for both, so a number there would describe a choice nobody is being offered.

See `docs/integrations/skr-staking.md` for the tool schemas and worked examples, and
`docs/wiki/skr-staking.md` for how a request reaches the owner and what the phone checks.

## Networks

Mainnet-beta, and nothing else. The staking program is deployed there and nowhere else — checked
rather than assumed — and a devnet endpoint reads every account as absent, which is indistinguishable
from an owner who has nothing staked. The server compares genesis hashes at startup and refuses to
come up against any other cluster.

## Running it

From source, in a checkout:

```bash
pnpm install
cp skr-staking-server/.env.example .env      # then fill in the two required values
pnpm --filter @seeker-vault/skr-staking-server run dev
```

The two values with no default are `SKR_STAKING_MCP_TOKEN` (generate one with `openssl rand -hex 32`)
and `SKR_STAKING_RPC_URL` (a mainnet-beta endpoint). Everything else has a working default;
`.env.example` documents each one.

With Docker, from the repository root:

```bash
cp deploy/skr-staking/.env.example deploy/skr-staking/.env    # then fill it in
docker compose --env-file deploy/skr-staking/.env -f deploy/skr-staking/compose.yaml up --build
```

## Connecting a phone

```bash
node --env-file-if-exists=.env skr-staking-server/src/cli.ts pair
```

That prints a pairing code and a QR image; the phone adds it as a direct connection like any other.
The connection is this server's own — pairing the general MCP server does not pair this one, and
revoking either leaves the other alone.

## What it is not

It holds no key, keeps no staking database of its own, and implements no staking contract: the
program on chain is the only authority on shares, prices and cooldowns, and everything it reports is
read from there. There is no dashboard, no background monitoring and no automatic approval.
