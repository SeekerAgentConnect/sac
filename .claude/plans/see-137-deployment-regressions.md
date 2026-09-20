# SEE-137 — deployment regressions and end-to-end setup

Linear: https://linear.app/seekeragentwallet/issue/SEE-137/fix-pr-38-deployment-regressions-and-complete-the-end-to-end-setup

Ticket source: the configured Linear plugin returned `401 invalid_token`, so the repository-required
Superset runner fallback (`superset tasks get SEE-137 --json`) supplied the full issue body. The same
fallback supplied parent SEE-128. PR #38 and `origin/superset/feat/see-128` were re-read before work;
both point at the ticket's review baseline `89373064c94f808aee8f703903069120494a0e98`, so there are no
later branch fixes to reconcile at the start of this run.

## Ordered plan

- [x] 1. Replace PID-only MCP ownership with crash-safe, cross-container ownership that preserves
      exclusive access, pairing-command coexistence, stable state, and deterministic concurrent recovery.
- [x] 2. Make the MCP container health check follow HTTP versus native TLS configuration while retaining
      bounded internal probing and certificate/hostname verification.
- [x] 3. Add and exercise a generic non-Tailscale native-TLS direct deployment for authenticated MCP,
      phone APIs, and bidirectional HTTP/2 UpdateService streaming; reduce Tailscale to an optional example.
- [x] 4. Turn `deploy/README.md` into the canonical numbered MCP-only, feeds-only, and combined-host guide;
      assign conflict-free combined ports and ingress ownership and keep all standalone guides linked.
- [x] 5. Run focused/runtime/package/build checks; update SEE-135/SEE-136 evidence and all touched docs,
      CODEBASE/changelog, plan review, and PR #38 metadata without weakening CI or publishing artifacts.
- [ ] Re-read SEE-137/SEE-128 before handoff, commit and push the existing branch, move SEE-137 to In Review,
      comment with evidence if Linear access works, post the required finished webhook, and stop without merge.

## Acceptance criteria (Linear, verbatim)

- [x] MCP restarts after an unclean container exit on the same volume without manual cleanup; a live competing owner is still rejected.
- [x] HTTP and native-TLS container health checks reflect actual readiness without bypassing certificate checks.
- [x] A generic deployment guide and configuration deliver authenticated direct streaming over HTTP/2 without requiring Tailscale.
- [x] One clearly discoverable, numbered guide covers MCP-only, feeds-only and the combined setup, with working commands, expected results and no port/address ambiguity.
- [x] Each service remains independently deployable and documented; existing identities/data and the two-mode privacy/approval boundaries are preserved.
- [x] Focused fixes and available runtime/package checks have reproducible evidence; all remaining unavailable live checks have concrete follow-up and correct status.
- [ ] PR metadata is accurate, and exhausted CI limits are recorded as infrastructure context rather than a product defect.

## Verification ledger

Record every ticket-required item as **PASS**, **FAIL**, or **NOT RUN**, including the exact environment,
commands, commit, and blocker/follow-up for unavailable live checks. GitHub Actions account usage exhaustion is
infrastructure context, never a product failure and never grounds for weakening a workflow.

## Review

Implementation follows the ticket's required order. MCP ownership now uses a persistent SQLite guard
and an OS-released exclusive transaction rather than trusting a PID. The executable container health
probe derives HTTP versus HTTPS from server configuration and retains CA and hostname verification.
The generic direct-TLS overlay is independent of Tailscale, combined publishers use an internal feed
network, and the feed/MCP defaults no longer both claim port 8080. `deploy/README.md` is the canonical
numbered clean-host guide; component guides link to it instead of duplicating the walkthrough.

Focused tests, full workspace checks, package gates, Go checks, native integration, deployment
resolution and the final build pass. `docs/testing/see-137.md` records exact results. Docker/live
public deployment, broker/client/device checks and Android interoperability remain **NOT RUN** with
concrete environment blockers. GitHub Actions' exhausted account usage is recorded only as an
infrastructure limitation. No workflow was weakened and no package or image was published.
