# Superset status hooks

One script posts session status to the Grok bot webhook. Claude, Codex, Grok, and GLM-compatible CLIs all call it.

## Contract

Set both env vars on the machine / agent session:

- `SEE_SUPERSET_HOOK` — POST URL
- `SEE_SUPERSET_TOKEN` — bearer token

If either is missing the script exits 0 and does nothing.

Body (same as `CLAUDE.md` § Superset Hook):

```json
{
  "ticket": "SEE-100",
  "repo": "SeekerAgentConnect",
  "branch": "feat/see-100",
  "status": "finished",
  "message": "short human summary",
  "pr": "https://github.com/BrRenat/SeekerAgentConnect/pull/1"
}
```

`status` is one of: `working` | `stuck` | `blocked` | `finished` | `failed`.

## Event map

| Agent event | Posted status |
| ----------- | ------------- |
| `SessionStart` | `working` |
| `Notification`, `PermissionRequest` | `blocked` |
| `Stop`, `SessionEnd` | `finished` |

Manual posts from an agent prompt (blocked on a missing ticket, failed validate, etc.):

```bash
bash scripts/superset-hook.sh blocked "Linear ticket unreadable"
bash scripts/superset-hook.sh failed "tests red"
bash scripts/superset-hook.sh stuck "waiting on wallet device"
```

## Files

| Path | Agent |
| ---- | ----- |
| `scripts/superset-hook.sh` | shared poster |
| `.claude/settings.json` `hooks` | Claude Code and GLM CLIs that read Claude settings |
| `.codex/hooks.json` | Codex |
| `.grok/hooks/superset.json` | Grok |

Do not put the token or hook URL in git.
