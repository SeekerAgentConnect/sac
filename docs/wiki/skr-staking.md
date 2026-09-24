# SKR staking, as this app reviews it (SEE-146)

An agent can ask its owner to stake SKR, to start unstaking it, to cancel a pending unstake, or to
withdraw what a finished cooldown released. The asking is done by a **separate MCP server**
([`skr-staking-server/`](../../skr-staking-server)), which connects to the phone as its own
connection; the deciding is done on the phone, in the review the owner already knows, and the signing
is done by their wallet.

Nothing about that is special to staking, and that is the point of this ticket: Seeker Agent Connect
supports independent servers with different capabilities through one approval path. What is special
is what the phone has to check, because a staking transaction is not a transfer and three of the four
actions move no tokens at all.

- The server side, its tools and its configuration: [integrations/skr-staking.md](../integrations/skr-staking.md)
- Running and deploying it: [development/skr-staking-server.md](../development/skr-staking-server.md)
- What was verified against mainnet, and the owner-run lifecycle test: [testing/see-146.md](../testing/see-146.md)

## The four actions, and why they are one message

| Action | What the program does | Does anything move? |
| --- | --- | --- |
| Stake | Takes SKR out of the owner's token account into the vault and mints shares | Yes, out of the wallet |
| Unstake | Burns shares, records their value as pending, and starts a cooldown | No |
| Cancel unstake | Puts the whole pending amount back to work and clears the cooldown | No |
| Withdraw | Pays the recorded amount out of the vault into the owner's token account | Yes, into the wallet |

On the wire they are one message — `StakingAction { wallet, network, operation, amount }`, field 5
of the `Action` oneof — because the four are four states of one position rather than four unrelated
things, and they share a wallet, a network and at most an amount. Cancelling and withdrawing carry no
amount at all, and an amount on either is **refused rather than ignored**: the program acts on the
whole pending unstake for both, so a number there would describe a choice nobody is being offered,
and an ignored field is one an agent could believe in.

What the message deliberately does *not* carry is a program, a mint, a pool, a vault or a stake
account. The phone verifies prepared bytes against the staking deployment it was compiled with, so a
field here that could name another one would be a way for a server to have an owner review one
program and sign for a different one.

## What happens, end to end

```
agent ──MCP──▶ skr-staking-server ──server-sdk──▶ phone ──MWA──▶ wallet
                    │                                 │
                    │ builds unsigned bytes           │ decodes those bytes itself and
                    │ from fresh chain state          │ checks them against the request
                    └── never signs, never sends ─────┘
```

1. The agent calls one of the four `request_*` tools. The server creates an approval request and
   stops there: it builds no transaction at that moment, signs nothing and sends nothing.
2. The phone shows the request in the Inbox, with the same source label and connection rules as any
   other direct request.
3. When the owner opens it, the phone asks the server to prepare — and, **before** asking, reads the
   owner's position from its own Solana endpoint. That order matters: a share price fetched after the
   server built the transaction could make an honest unstake look wrong.
4. The phone decodes the prepared bytes with its own parser and checks them (below). The verdict is
   the gate: a transaction it could not account for has no Approve button to overrule.
5. The owner approves. The approval names the exact prepared version and its content hash, the server
   accepts it, and only then is the wallet opened — with the bytes from the stored approval, never
   with bytes fetched again.
6. The wallet signs and sends. The result goes back as a submission, and the confirmation is checked
   on chain the same way a transfer's is.

## What the phone checks

Everything below is read out of the transaction's own bytes and out of accounts the phone fetched
itself. The server's account of what it built is shown nowhere.

**Whose transaction it is.** The fee payer is the request's wallet, the only signer is that wallet,
the wallet is the one connected on the phone, its network is the request's network, and the bytes are
still unsigned.

**Which deployment it is.** The program ID and the SKR mint are compiled into the app. The stake
configuration, the vault, the guardian's pool and the owner's stake account are all **program-derived
and recomputed here**, then compared against the accounts the instruction names. The configuration
read from chain must itself name the mint and vault the app expects, and the pool must name the
configuration and the guardian it derives from. The instruction's fixed positions — the program's own
ID, its event authority, and the token and system programs it calls into — are compared too: nobody
has a reason to change one, which is why one that changed is a reason to stop.

**Which action it is.** The instruction's discriminator must be the operation the request named. An
instruction of the staking program that is none of the four is refused by name rather than treated as
unknown: that program can move the whole position.

**What it is worth.** A stake's amount must equal the amount the request names, be at least the
program's minimum, and be no more than the SKR the wallet holds. An unstake is asked for in SKR and
carried out in **shares**, so the phone converts the shares in the bytes at the price it read and
refuses anything worth more than was approved — and a request covering the whole position must burn
exactly the shares the account holds, because anything recomputed from its token value floors a
second time and leaves dust the owner could not close.

**What else is in there.** Compute-budget instructions are fine. A withdrawal may carry the
associated-token program's *idempotent* create, because the SKR has to land somewhere, and its owner,
payer, mint and address are all checked. Any transfer, any account creation carried by the other three
actions, and anything else the phone cannot read is refused.

**When it can happen.** The cooldown is read from `StakeConfig.cooldown_seconds` — 48 hours today, but
read every time rather than assumed. A withdrawal before it finishes is refused; the boundary second
is accepted, because the program's own check is `now >= timestamp + cooldown` and anything stricter
would refuse the second the chain would take. A cancellation with nothing pending is refused.

**The one documented claim that did not survive the program.** "Unstaking again combines the amounts
and resets the cooldown" is only true while the cooldown is still running. Once it has finished the
program answers `WithdrawRequired`, so the phone refuses first, by name, rather than letting a
transaction fail after the owner approved it. While the cooldown *is* running, the review warns that
approving will restart it.

## What the owner sees

The review names the action in four different ways rather than one, because the pair most worth
telling apart is unstake and withdraw: one starts a wait and moves nothing, the other is the step that
finally moves the SKR. Each gets its own title, its own sentence about what it does, its own label for
the amount, and its own Approve button.

Beside that: the wallet, the network, the amount as SKR rather than base units, what is left staked
after a partial unstake, when the pending amount may be withdrawn, whether a token account is being
created, the owner's derived stake account, and the programs the transaction touches. A pending
cooldown that this action would restart is called out in the error colour.

## How the rules treat it

Staking is one `PolicyAction` — `staking` — rather than four, because a rule an owner writes is about
whether this app may touch their staking position at all; which of the four it is, is a fact of the
review rather than of the rule.

What the rules are told about a request is **not** the same for all four. A stake commits tokens the
owner holds, so it moves value and is outgoing. An unstake and a cancellation move nothing at all, so
they are neither. A withdrawal brings SKR back, so it moves value and is incoming. Counting all four
as outgoing transfers would charge a spending limit for something that did not happen; counting none
of them would let a stake past one. The recipient, where there is one, is the owner's own wallet, and
that is established rather than assumed: the stake account derives from their address, the vault
belongs to the program, and the only instruction that pays out of the vault pays the account derived
for that same owner.

## What is not here

No staking dashboard, no background monitoring of positions, no withdrawal reminders, no shared
staking feed, no automatic approval, and no staking contract of this project's own. `get_staking_status`
exists for the agent, for preparation, and for the review — not to put a balance screen on the phone.

Sandbox is unchanged by this ticket. Nothing in the staking path signs or broadcasts: that line is
still the wallet hand-off's alone, and `StageBoundaryTest` and `SkrChainTest` both hold the staking
package to it.
