# The SKR staking server, for an agent (SEE-146)

A standalone MCP server that turns an agent's request to stake, unstake, cancel an unstake or
withdraw SKR into an approval the owner answers on their phone. This page is its wire surface: the
five tools, what they take, what they return, and what goes wrong. What the phone does with a request
is [wiki/skr-staking.md](../wiki/skr-staking.md); how to run and deploy it is
[development/skr-staking-server.md](../development/skr-staking-server.md).

It is a second, independent server. It connects to the phone as its **own** connection, shares no
database, token or listener with the general MCP server, and neither one can see the other's requests.

## Connecting

The server serves `/mcp` over streamable HTTP on `SKR_STAKING_PORT` (8090 by default), authenticated
with the bearer token in `SKR_STAKING_MCP_TOKEN`.

```json
{
  "mcpServers": {
    "skr-staking": {
      "type": "http",
      "url": "http://127.0.0.1:8090/mcp",
      "headers": { "Authorization": "Bearer ${SKR_STAKING_MCP_TOKEN}" }
    }
  }
}
```

The phone is connected separately, once, by pairing. Either you ask for the link:

```jsonc
// skr_create_pairing_link, no arguments
{
  "pairing_uri": "seekervault://pair?v=1&url=https%3A%2F%2Fstaking.example.com&server=…&token=…",
  "https_url": "https://staking.example.com/pair#eyJ2IjoxLCJwYWlyaW5nX3VyaSI6…",
  "server_url": "https://staking.example.com",
  "expires_at": "2026-09-24T16:10:00.000Z"
}
```

or whoever has a shell on the host runs:

```bash
node --env-file-if-exists=.env servers/mcp-skr-staking/src/cli.ts pair
```

Show the owner the **whole** `https_url`. A label such as "Connect your phone" is fine when that
entire string is the link target, but an abbreviated, wrapped or escaped URL is refused by the page
as damaged — it does not pair something else by accident. `pairing_uri` is the copy/paste fallback,
and any `warning` field belongs next to the link: a newer code voids an unused one, and whoever
pairs first replaces the phone paired now. The tool issues a code and nothing more; the owner still
confirms on their phone, and nothing is disconnected by creating or opening the link.

Until a phone is paired and has published a wallet, every tool answers that there is no connected
wallet. That is the same shape as the general server's pairing ([wiki/mcp-adapter.md](../wiki/mcp-adapter.md)),
and the pairing code names this server's own origin — the one in `SKR_STAKING_PUBLIC_URL`.

## The tools

### `get_staking_status`

Takes nothing. Reads the connected wallet's position from the chain and answers with it. It creates
no request, asks the owner nothing, and builds no transaction.

It answers only for a connection bound to **mainnet**, and only when the configured endpoint is
serving mainnet-beta. A devnet binding is refused with `INVALID_PARAMETERS` before anything is read,
because the alternative is this mainnet-only server reading that address on mainnet and labelling
the answer `devnet` — a position off one cluster presented as another's, which looks like an
ordinary answer. An endpoint that cannot be reached is `CHAIN_UNAVAILABLE`: try again.

| Field | Meaning |
| --- | --- |
| `wallet`, `network` | The connection's own binding. Never a parameter. |
| `available_skr` | SKR in the wallet, in base units: what a stake can use. |
| `staked_skr`, `shares` | What the active shares are worth now, and the shares themselves. |
| `unstaking_skr` | SKR waiting out the cooldown, fixed when the unstake was made. |
| `withdrawable`, `withdrawable_at` | Whether the cooldown has finished, and when it does. |
| `cooldown_seconds` | Read from the deployment's configuration, not assumed. |
| `minimum_stake_skr`, `share_price` | The program's floor, and the scaled price the amounts were computed with. |
| `sol_lamports` | The wallet's SOL, which pays the network fee for any of this. |
| `program`, `mint`, `stake_account`, `token_account` | The deployment, and this owner's derived accounts. |
| `display` | The same position as a sentence, for showing a person. |

### `request_stake` and `request_unstake`

| Parameter | |
| --- | --- |
| `amount` (required) | SKR base units, as a decimal integer string. SKR has 6 decimals, so 1 SKR is `"1000000"`. No sign, decimal point, exponent or leading zeros. |
| `idempotency_key` (required) | 1–128 characters from `A–Z a–z 0–9 . _ : -`. Reuse it to retry or to read the request you already created; use a new one for a new request. |
| `note` (optional) | Why you are asking. The owner sees it apart from the verified parameters, and it is never treated as a fact about the transaction. |
| `expires_in_seconds` (optional) | How long the owner has to decide. |

`request_unstake` is not `request_withdraw`: it burns shares, stops them earning and starts a
cooldown, and nothing moves until a withdrawal afterwards. An `amount` at or above the position's
current value unstakes all of it. Unstaking again while a cooldown is still running adds to the
pending amount and **restarts** the cooldown; once the cooldown has finished the program refuses
instead, and so does this server — withdraw first.

### `request_cancel_unstake` and `request_withdraw`

The same parameters minus `amount`, which neither takes: the program acts on the whole pending
unstake for both. Cancelling puts all of it back to work and clears the cooldown; withdrawing pays
out exactly what the program recorded, and fails while the cooldown is still running.

### What a creating tool answers

The request as it stands, never an execution:

```json
{
  "request_id": "b3a29180-d5c4-4b3a-9180-e6d5c4b3a291",
  "operation": "unstake",
  "status": "PENDING",
  "terminal": false,
  "wallet": "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW",
  "network": "mainnet",
  "amount": "40000000",
  "created_at": "2026-09-24T09:00:00.002Z",
  "expires_at": "2026-09-24T09:30:00.002Z",
  "updated_at": "2026-09-24T09:00:00.002Z"
}
```

`status` is one of `PENDING`, `PROCESSING`, `SUBMITTED`, `CONFIRMED`, `REJECTED`, `CANCELLED`,
`EXPIRED`, `FAILED`, `UNKNOWN`, and `terminal` says whether it can still change. A settled request
also carries `signature` (the transaction's ID on chain), `detail`, and — once the chain has been
read — `confirmation`, `slot`, `chain_error`, `checked_at` and `checked_with`, the host of the one
endpoint that answer rests on.

`UNKNOWN` is not a failure. It is what this server says when nobody knows whether a transaction was
sent, and it is never retried automatically: a staking action that may have happened must not be
made to happen twice.

## Following a request

Call the same tool again, with the same `idempotency_key` and the same parameters. You get the
original request as it stands now, whatever state it has reached — which is how an agent follows a
request to its end without a sixth tool, and what makes a retry after a lost response safe: the
second call finds the first request instead of creating a second one.

A different `idempotency_key` with otherwise identical parameters creates a **second** request. The
key is what says "the same ask", not the amount.

**The owner sees it without being asked to look (SEE-150).** A deployment that serves live updates
pushes a new request, and each supported status change, to the phone while the app is open, and wakes
it through the gateway relay while it is not. Neither is anything an agent configures or can address:
there is no field for a device, a topic or a message anywhere in these tools, the wake-up carries no
content at all, and the phone reads this server for itself afterwards. What an agent notices is only
that the owner tends to answer sooner. Polling the same tool with the same `idempotency_key` remains
the whole of an agent's side, and works identically on a deployment that serves no updates.

## Worked example

```
> get_staking_status
{ "available_skr": "500000000", "staked_skr": "0", "unstaking_skr": "0",
  "minimum_stake_skr": "1000000", "cooldown_seconds": 172800, ... }

> request_stake { "amount": "25000000", "idempotency_key": "treasury-2026-09-24",
                  "note": "Idle SKR; the vesting review is next month" }
{ "request_id": "...", "status": "PENDING", "operation": "stake", "amount": "25000000" }

  ... the owner reviews it on their phone and approves ...

> request_stake { "amount": "25000000", "idempotency_key": "treasury-2026-09-24" }
{ "request_id": "...", "status": "CONFIRMED", "signature": "5Qf...", "slot": 318000001,
  "confirmation": "finalized", "checked_with": "api.mainnet-beta.solana.com" }
```

## When it says no

| What you get | What it means |
| --- | --- |
| No connected wallet | Nothing is paired yet, or the owner has disconnected their wallet. Pair the phone, or ask them to reconnect. |
| Invalid amount | Not a decimal integer string of base units, or zero. A stake also has to clear the program's minimum. |
| Below the minimum stake / insufficient balance | Checked against the chain before the owner is asked, because a request nobody could approve is not worth asking. |
| Nothing staked / nothing pending | An unstake with no position, or a cancellation or withdrawal with no pending unstake. |
| Withdraw first | A further unstake after the cooldown already finished. The program answers `WithdrawRequired`; call `request_withdraw`. |
| The cooldown has not finished | A withdrawal too early. `get_staking_status` says when `withdrawable_at` is. |
| The chain could not be read | The configured RPC endpoint did not answer. Nothing is created: a request prepared against state nobody read is worse than no request. |
| The server will not start | It compares genesis hashes at startup. The staking program is on mainnet-beta and nowhere else, so a devnet endpoint is refused rather than run against — every account reads as absent there, which is indistinguishable from an owner who has nothing staked. |

Rejection by the owner is an answer, not an error: the request settles as `REJECTED` and stays that
way. An expired one settles as `EXPIRED`. Neither is retried by asking again with the same key —
that returns the settled request. A new ask needs a new key.
