# Tickets

Every task in this repository comes from a Linear ticket (`SEE-…`, and `SAW-…` historically). The
ticket is the specification: it is the only statement of what a task is. This page is the rule for
coding agents, and [`AGENTS.md`](../../AGENTS.md) states it in short.

## The rule

- **No ticket text, no work.** If you cannot read the ticket itself, stop. Don't plan, don't edit,
  don't commit, don't push, don't open a PR.
- **Do exactly what the ticket describes.** Never guess the task, never infer it, never expand it,
  never narrow it. If the ticket doesn't ask for it, it belongs to another ticket.

Both halves matter. A task reconstructed from a branch name and a plausible reading of the codebase
looks like work and is not the work.

## Reading the ticket

**1. The project's configured MCP server first.** `.claude/settings.json` enables
`linear@claude-plugins-official`, so the Linear tools (`get_issue`, `list_comments`,
`list_issues`, `get_document`, `get_project`, `get_milestone`) are the first and normal path. Read
the whole issue, not the title:

- the description and its acceptance criteria, in full
- every comment — a comment can amend, narrow or cancel the description, and the last word wins
- the parent issue and sub-issues, for what this ticket owns and what it doesn't
- linked documents and attachments named by the description

**2. Superset tools if that MCP server is unavailable.** If the Linear server isn't connected,
isn't authorized, or its tools return an authentication error — a non-interactive session cannot
run an OAuth flow — fall back to the superset runner's own ticket access (`superset.sh`, the
`$SEE_SUPERSET_*` environment described in [`CLAUDE.md`](../../CLAUDE.md)). It is a fallback for
*fetching the same ticket*, not a second source of truth.

**3. Nothing else counts as reading the ticket.** None of these is the ticket:

| Not the ticket | Why |
| --- | --- |
| A ticket ID in a branch name, a prompt, or a commit message | An identifier is not a description |
| A one-line summary in the session prompt, or a paraphrase from another agent | It is somebody's reading of the ticket, and the omissions are invisible |
| An existing `.claude/plans/see-NN-*.md` | A plan records what a past run decided to do, not what this ticket asks for |
| The parent epic, a sibling ticket, or a similar earlier ticket | Adjacent scope is the most convincing way to build the wrong thing |
| `RFC.md`, `README.md` or this repository's docs | They say what the product is, not what this task is |

The ticket body quoted in full by the owner *is* the ticket; say in the plan and the hand-off where
that text came from, and use the tools instead whenever they work.

## When you cannot read it

Stop before the first edit, and report it:

- name the ticket you were asked to do, each tool you tried, and what each one returned
- name the fix: authorize the Linear connector in claude.ai connector settings, or run `claude mcp`
  / `/mcp` in an interactive session; or have the owner paste the ticket body
- if `$SEE_SUPERSET_TOKEN` and `$SEE_SUPERSET_HOOK` are set, POST `"status": "blocked"` with that
  reason, per the Superset Hook section of [`CLAUDE.md`](../../CLAUDE.md)

Then do nothing else on that ticket. Never substitute work for it: no adjacent cleanup, no
scaffolding "while we wait", no partial implementation of the half you think you understood, and no
branch or PR that implies the ticket was started. Reporting blocked with an untouched tree is the
correct outcome, and it is not a failure.

## When the ticket is readable but unclear

Ambiguity is not permission to choose:

- **Implement what is stated.** Leave what isn't stated undone and say so, item by item.
- **Ask where the answer lives** — a comment on the Linear issue, or the owner — rather than
  picking the reading that is easiest to build.
- **A conflict with the repository's own rules is a finding, not a choice.** When a ticket
  contradicts a stage boundary, the approved design, or an invariant in `AGENTS.md`, record the
  conflict and raise it; don't silently satisfy one side.

## While the work runs

- The plan file is named after the ticket (`.claude/plans/see-NN-<slug>.md`), carries the Linear
  link at the top, and turns the ticket's acceptance criteria into checkable items — copied, not
  paraphrased. The ticket's wording is what the hand-off is checked against.
- Re-read the issue and its comments before handing off; they may have moved while you worked.
- Report each ticket item honestly as **PASS**, **FAIL** or **NOT RUN**, on the ticket and in the
  plan's review section. An item the ticket asks for and the run couldn't do is NOT RUN with its
  blocker named — never quietly dropped, and never ticked because the code for it exists.

## Checklist

| Before you start | |
| --- | --- |
| Ticket read in full through Linear MCP, or through the superset fallback | required |
| Comments, parent and sub-issues read | required |
| Acceptance criteria copied into `.claude/plans/see-NN-<slug>.md` | required |
| Anything not in the ticket | out of scope |
| Ticket unreadable | stop, report blocked, change nothing |
