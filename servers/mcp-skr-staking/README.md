# SKR staking server

A standalone MCP server that lets an agent ask its owner to stake, unstake, cancel an unstake or
withdraw SKR, and reaches them through the review and wallet path Seeker Agent Connect already has.

It is a **second, independent direct server**. It shares no database, no token and no listener with
the general MCP server in `servers/mcp-server/`, it connects to the phone as its own connection, and the two
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
| `skr_create_pairing_link` | Issues a one-use pairing code for this server and returns the complete HTTPS link and `seekervault://pair` deep link. It pairs nothing: the owner still confirms on the phone. |

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
cp servers/mcp-skr-staking/.env.example .env      # then fill in the two required values
pnpm --filter @seeker_agent_connect/mcp-skr-staking run dev
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

There are two ways, and they issue the same kind of one-use code.

**From an agent.** Ask it to call `skr_create_pairing_link`. It answers with an `https_url` —
`https://<SKR_STAKING_PUBLIC_URL origin>/pair#<fragment>` — which the owner opens on the phone. The
page it lands on is served by this server at `/pair`, says that it is the staking server, and has an
**Open Seeker Agent Connect** button, a QR code and a copy fallback. This is the way to pair a hosted
deployment: it needs no shell on the host.

The token travels in the URL fragment, so it is not in the request this server receives, in its logs
or in an access log in front of it. The page does not pair, revoke or issue anything: opening it,
copying the code or reading the QR changes nothing on the server, and the app still asks the owner
to confirm. Show the whole `https_url` — a shortened or wrapped one is refused by the page as
damaged rather than silently pairing something else.

**From a shell on the host.**

```bash
node --env-file-if-exists=.env servers/mcp-skr-staking/src/cli.ts pair
```

That prints the same code three ways: a QR image, the `seekervault://pair` URI, and the HTTPS
landing link.

Either way the phone adds it as a direct connection like any other. The connection is this server's
own — pairing the general MCP server does not pair this one, and revoking either leaves the other
alone. A newer code voids an unused one, and whoever pairs first replaces the phone paired now;
creating or opening a link never disconnects it by itself.

The page and its four assets are served at `/pair`, `/pair/page.js`, `/pair/page.css`,
`/pair/payload.js` and `/pair/uqr.js`, under a strict Content-Security-Policy, with no cache, no
analytics and no pairing-status API. A reverse proxy in front of this server has to pass those paths
through for the page to work.

## What it is not

It holds no key, keeps no staking database of its own, and implements no staking contract: the
program on chain is the only authority on shares, prices and cooldowns, and everything it reports is
read from there. There is no dashboard, no background monitoring and no automatic approval.
