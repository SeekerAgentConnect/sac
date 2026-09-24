## Codebase Navigation

- Before exploring the filesystem, read `CODEBASE.md` first.
- Follow `AGENTS.md` for stage boundaries, stock UI, testing, and documentation rules.
- Before any Android UI task, read `design/README.md` and then the target component's `spec.md`.
- Before changing the design guide or generated design references, read `design/UPDATING.md` and
  follow its refresh procedure as a separate design-only change.
- To run, debug or test the Android app on an emulator against the DigitalOcean deployment, follow
  `docs/development/emulator-e2e.md` (scripts: `scripts/emulator.sh`, `scripts/mcp-call.mjs`,
  `scripts/demo-signal.sh`).
- Only explore files directly if `CODEBASE.md` doesn't cover what you need.
- If you explored files not covered by `CODEBASE.md` during a task, append them to the relevant section.
- After completing any task that adds, removes, or moves files or changes architecture, update `CODEBASE.md` to reflect the changes.

## Project Commands

### Package Manager Detection (ALWAYS do this first)

Detect the package manager by lock file before running ANY command:

| Lock file           | Package manager | Run scripts with    |
| ------------------- | --------------- | ------------------- |
| `pnpm-lock.yaml`    | pnpm            | `pnpm run <script>` |
| `yarn.lock`         | yarn            | `yarn <script>`     |
| `package-lock.json` | npm             | `npm run <script>`  |

**Never default to npm** — always check the lock file first.

- **Build**: `<pm> run build` (or relevant build script from package.json)
- **Lint**: `<pm> run lint` — use `<pm> run lint -- --fix` for autofixing
- **Test**: `<pm> run test` — **only when user explicitly asks**
- **Always check package.json** for available scripts before guessing commands

## Documentation

Every completed task **except pure bug fixes** must generate or update relevant docs:

| Change type                        | Action                                                  |
| ---------------------------------- | ------------------------------------------------------- |
| New feature / major change         | Create or update `docs/wiki/<feature>.md`              |
| Integration (APIs, SDKs, webhooks) | Create or update `docs/integrations/<integration>.md`  |
| Dev process (CI, tooling, conventions) | Update `docs/development/<topic>.md`               |
| Any shipped change                 | Append entry to `docs/changelog/<version-or-date>.md`  |

Bug fixes only need a changelog entry when significant enough for release notes.

## Workflow Orchestration

### 0. Read the Ticket First

- **Never do a task you cannot read the ticket for.** Read the Linear ticket (`SEE-…`) through the project's configured MCP server (the `linear` plugin in `.claude/settings.json`); if it is unavailable or unauthorized, fall back to the superset runner's ticket access.
- If neither works, STOP: report which ticket, which tools were tried and what each returned, POST `blocked` to the superset hook when `$SEE_SUPERSET_TOKEN` is set, and change nothing.
- **NEVER guess the task** — a branch name, a prompt summary, an old plan file or a neighbouring ticket is not the ticket. Do exactly what the Linear ticket describes.
- Full rules: `AGENTS.md` ("The ticket") and `docs/development/tickets.md`.

### 1. Plan First

- Enter plan mode for ANY non-trivial task (3+ steps or architectural decisions)
- Write plan to `.claude/plans/<task-name>.md` with checkable items
- If something goes sideways, STOP and re-plan — don't keep pushing
- Check in with user before starting implementation on complex tasks

### 2. Subagent Strategy

- Use subagents liberally to keep main context window clean
- Offload research, exploration, and parallel analysis to subagents
- One task per subagent for focused execution
- For complex problems, throw more compute at it via parallel subagents

### 3. Verification Before Done

- Run build to verify nothing is broken
- Ask yourself: "Would a staff engineer approve this?"
- Diff behavior between main and your changes when relevant

### 4. Self-Improvement Loop

- After ANY correction from the user: update `.claude/tasks/lessons.md` with the pattern
- Write rules that prevent the same mistake class
- Review lessons at session start for the relevant project

## Code Standards

### Simplicity & Minimal Impact

- Make every change as simple as possible — touch only what's necessary
- Find root causes. No temporary fixes. Senior developer standards
- For non-trivial changes: pause and ask "is there a more elegant way?"
- If a fix feels hacky, step back and implement the clean solution
- Skip elegance overthinking for simple, obvious fixes

### Respect User's Code

- **Do NOT remove console.logs** from uncommitted changes unless user explicitly asks
- **Do NOT remove comments**, TODO markers, or debugging helpers unless asked
- **Do NOT refactor** adjacent code that isn't part of the current task
- **Do NOT change formatting/style** of untouched code (respect existing patterns)
- Preserve import order conventions, naming patterns, and file structure

### Dependencies & Libraries

- Always check `package.json` if `node` or `frontend` is in the selected languages for current dependency versions before using any API
- Use Context7 MCP to look up documentation for the **exact version** in use
- Never assume API signatures — verify against the installed version
- When adding dependencies, prefer what's already in the project ecosystem

### Autonomous Bug Fixing

- When given a bug report: just fix it — don't ask for hand-holding
- Point at logs, errors, failing tests — then resolve them
- Go fix failing CI tests without being told how
- Zero context switching required from the user

## Task Management

Session metadata lives in the `.claude/` folder. What is shared and what is local differs,
and the difference is whether it outlives the task.

| File / Folder                  | Purpose                                            | In Git |
| ------------------------------ | -------------------------------------------------- | ------ |
| `.claude/plans/*.md`           | Plans with checkable items + review section        | No — local working notes |
| `.claude/tasks/lessons.md`     | Patterns learned from corrections                  | Yes    |
| `.claude/tasks/decisions.md`   | Architectural decisions and reasoning (optional)   | Yes    |
| `.claude/settings.json`        | Hooks, permissions, enabled plugins                | Yes    |

Plans are still written, still checked off, and still get their review section — they are just
not committed. By the time the work lands, what the plan concluded belongs in the commit message,
the changelog and `docs/`, which is where somebody reading the repository will look for it.

### Workflow

1. **Plan** -> Write to `.claude/plans/<task-name>.md`
2. **Confirm** -> Check in before starting (for complex tasks)
3. **Execute** -> Mark items complete as you go, explain changes at each step
4. **Verify** -> Lint, build, test
5. **Document** -> Add review section to the plan file, update `.claude/tasks/lessons.md` if needed.

## Communication

- Give high-level summaries, not line-by-line narration
- When presenting options, give a clear recommendation with reasoning
- If blocked or unsure, say so immediately — don't guess and break things
- When a task is done, summarize: what changed, what was tested, any caveats

## Superset Hook

When a session runs in Superset (Claude, Codex, Grok, or GLM) and status changes (`working`, `stuck`, `blocked`, `finished`, `failed`), and `$SEE_SUPERSET_TOKEN` is set: POST JSON to `$SEE_SUPERSET_HOOK` with header `Authorization: Bearer $SEE_SUPERSET_TOKEN`.

Use the shared script — do not invent a second payload:

```bash
bash scripts/superset-hook.sh                 # maps agent hook events automatically
bash scripts/superset-hook.sh blocked "why"   # explicit status from the agent prompt
```

Body:

```json
{
  "ticket": "SEE-100",
  "repo": "SeekerAgentConnect",
  "branch": "superset/feat/see-100",
  "status": "finished",
  "message": "short human summary",
  "pr": "https://github.com/..."
}
```

`status` is one of: `finished` | `stuck` | `blocked` | `failed` | `working`.

Wiring: `.claude/settings.json` (Claude + GLM), `.codex/hooks.json`, `.grok/hooks/superset.json`. Details: `docs/development/superset-hooks.md`.

If Linear is unreadable, STOP, run `bash scripts/superset-hook.sh blocked "ticket unreadable"`, and change nothing.

## PR Code Review

Use `/review <PR#>` to run a self-review on any pull request. It auto-detects languages and applies the right rulesets (Node.js, Frontend, iOS).

Use `/commit-push` to lint, build, commit with conventional format, and push.
