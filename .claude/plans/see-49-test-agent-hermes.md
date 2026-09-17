# SEE-49 — SAW-037: finish the test-agent CLI and the real Hermes integration

**Ticket:** SEE-49 (SAW-037), child of SEE-45 (Stage 7). Branch `superset/feat/see-45`.

## Where this starts

The CLI already covers `hello`, `address`, `capabilities`, `sign`, `transfer`, `ack`, `get`,
`status`, `cancel`, and `tools`, with JSON on stdout and a documented exit code per outcome. What
the ticket asks for that is not there yet: a swap command, bounded waiting, an explicit
development mode for the two diagnostics, and Hermes examples for a hosted endpoint.

## Implementation

- [x] `wait <id>`: bounded polling of `vault_get_request` until the request settles, with
      `--for` (deadline) and `--every` (interval), and the same exit codes `status` uses.
- [x] `--wait` on `sign`, `transfer`, and `ack`: the `request_id` goes to stderr the moment the
      sidecar accepts it, then the CLI polls, and stdout carries one JSON document — the outcome.
- [x] An `outcome` field on what `status` and `wait` print, so a script branches on
      `succeeded` / `failed` / `unsettled` instead of knowing the state table. `UNKNOWN` stays
      unsettled: a script that reads it as a failure is a script that pays twice.
- [x] `swap`: the command exists and fails clearly, because this sidecar serves no swap tool.
      Stage 6 owns swaps, and nothing here implements one early.
- [x] Development mode: `hello` and `ack` are diagnostics, and they run only with
      `MCP_DEMO_TOOLS=true` — the sidecar's own switch — or an explicit `--demo`. Their output
      says plainly that an acknowledgement is neither a signature nor a payment.

## Tests and checks

- [x] `cli.test.ts`: waiting to a terminal state, a polling timeout, waiting on a request that is
      already terminal, `--wait` on a durable command, the swap refusal, the demo gate on both
      diagnostics, one JSON document on stdout, and missing credentials.
- [x] `pnpm check`, `pnpm test:hello`, `pnpm test:queue`, `pnpm test:transfer`.
- [x] The CLI from the checkout, through the packaged gateway. From its container: needs a Docker daemon, NOT RUN.

## Documentation

- [x] `test-agent/README.md`, `docs/integrations/hermes.md`, `examples/hermes.config.yaml` and a
      hosted example, `docs/testing/stage-7.md`, the changelog, `CODEBASE.md`.

## Honest limits

A durable request driven by real Hermes through the packaged stack, answered on the Seeker, is the
acceptance. It needs Hermes, the phone, and the stack in containers, and is NOT RUN here.

## Review

**What shipped.** `wait <id>` and `--wait` with `--for` and `--every`; an `outcome` word on what
`status` and `wait` print; `swap`, which reports that no sidecar serves `vault_swap` and queues
nothing; and development mode for `hello` and `ack`. On the Hermes side,
`examples/hermes.config.hosted.yaml` and a new section 9 covering the packaged stack locally and
over its own domain.

**Three decisions worth recording.**

1. *Waiting never changes a request.* A deadline that passes is exit 10 with `timed_out: true` and
   the request exactly as it was — not a failure, and not a reason to retry. That is the same
   reasoning `UNKNOWN` already had: a script that reads "no answer yet" as failure is a script that
   asks for the same payment twice. The tests assert the request is untouched afterwards.

2. *Development mode is the sidecar's own switch.* `MCP_DEMO_TOOLS` already existed and already
   meant "this is a development deployment", so the CLI reads the same variable rather than
   inventing a second one, with `--demo` for a single run. It cost three test-helper lines and the
   two runbooks that name the variable, and it means a deployment's container cannot reach for an
   acknowledgement by accident.

3. *`swap` exists and refuses.* Implementing a swap would be Stage 6 work landing early. Leaving
   the command out would make "the CLI supports swap" a usage error with no explanation. It is held
   to `transfer`'s rules so that the day a sidecar serves `vault_swap`, the command that reports it
   is not the loose one.

**What is outstanding.** The acceptance — a durable request created by real Hermes through the
packaged stack and answered on the Seeker — is NOT RUN, and so is the CLI in its container.
`docs/testing/stage-7.md#saw-037` lists both with what they need.
