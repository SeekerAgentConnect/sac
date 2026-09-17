# SEE-50 — SAW-038: the complete self-hosting and operations guide

**Ticket:** SEE-50 (SAW-038), child of SEE-45 (Stage 7), and the last of them. Branch
`superset/feat/see-45`.

## Where this starts

`docs/guides/self-hosting.md` already covers what the stack is, what is reachable, going public,
the OAuth profile, the test agent, persistence, the images, and troubleshooting. What it does not
have is the part that makes it a deployment guide rather than a description: a numbered path from
a clean checkout to a first request on two kinds of machine, and everything after the first start —
logs, rotation, re-pairing, updates, migrations, backup, restore, and what recovery cannot do.

The guide also opens with an absolute claim ("no account to make") that the ticket is explicitly
sceptical of. This stack needs a domain, a certificate authority, and — for transfers — somebody's
RPC endpoint. That belongs in writing.

## Implementation

- [x] Numbered deployments, separately: a Mac on its own machine, and a Linux VPS on the internet.
      Clone, fill `.env`, build and start, check health, print the pairing code, pair the phone,
      point an agent at it, and make one first request that moves no money.
- [x] What this needs from outside, named: the base images and the npm registry at build time, DNS
      and a certificate authority for a public deployment, a Solana RPC endpoint for transfers, an
      authorization server for a hosted client, and Firebase if push is wanted. Each with whether
      it is optional.
- [x] The two agent profiles kept apart: Hermes with a token (the default) and Claude through
      OAuth (optional), with what each needs.
- [x] Operating it: logs and what never reaches them, rotating each credential, revoking and
      re-pairing the phone, updating the images, what migrations do, and a backup and restore
      procedure that is the same every time. Warnings before `down -v` and before uninstalling the
      app.
- [x] What recovery can and cannot do: no wallet key is in this stack, and no backup here brings a
      wallet back. What the file holds, what only the phone holds, and — precisely — what happens
      when an older backup is restored: nothing is re-executed, nothing is re-signed, and no
      transaction is rebuilt or resent.

## Tests and checks

- [x] `sidecar/src/backup.test.ts`: the guide's own backup and restore procedure, run for real.
      A hot backup while the sidecar runs, an answer recorded after it, a restore, and then the
      assertions that matter — the request is PENDING again, the server ID and the pairing survive,
      nothing was submitted or executed, and the phone's stored answer settles it without a wallet.
      Plus the refusal of a database from a newer sidecar.
- [x] `sidecar/src/self-hosting-guide.test.ts`: every variable, service, profile, port, file path,
      and `docker compose` command the guide names exists in the shipped files.
- [x] `pnpm check`.
- [x] Following the guide end to end needs a Docker daemon: NOT RUN, recorded as such.

## Documentation

- [x] `docs/guides/self-hosting.md` (the ticket's main target), `docs/guides/troubleshooting.md`,
      `README.md`, `docs/testing/stage-7.md`, the changelog, `CODEBASE.md`.

## Review

**What shipped.** The guide gained the parts that make it a deployment guide: what the stack needs
from other people, two numbered walkthroughs (your own machine, and a VPS on the internet), how to
connect either kind of agent, an operations section — logs, rotation, revocation and re-pairing,
updates, migrations, backup and restore, and what deleting each thing costs — and a section on what
recovery can and cannot do. `README.md` and `troubleshooting.md` point at it from where an operator
actually starts.

**Three decisions worth recording.**

1. *The absolute claim came out.* "No account to make" was true about us and false about the
   deployment. It now reads "no account to make **with us**", and a table names the registrar, the
   certificate authority, the RPC provider, the authorization server, and Firebase, each marked
   with when it is needed. A guide that overstates its independence is a guide that surprises
   somebody at the worst moment.

2. *The recovery promise is tested, not asserted.* `backup.test.ts` runs the guide's own procedure
   — hot backup, an answer after it, restore — and checks what the page claims: the record moves
   back, the server ID survives, nothing is submitted or executed, and the phone's stored answer
   settles the request without a wallet. The paragraph and the test were written together, and the
   test would fail if either drifted.

3. *The guide is checked against the files, mechanically.* `self-hosting-guide.test.ts` holds every
   setting, Compose file, service, profile, port, image path, link, and heading to what is shipped.
   It is the ticket's third check, it runs in CI, and it was confirmed to bite by breaking the
   guide on purpose. Documentation drift is the failure mode this ticket exists to prevent.

**What is outstanding.** Following the guide end to end needs a Docker daemon, a VPS, a domain, and
the phone: NOT RUN, and recorded that way in `docs/testing/stage-7.md#saw-038`. Every command's
spelling is checked; none has been executed here.
