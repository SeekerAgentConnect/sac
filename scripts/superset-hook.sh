#!/usr/bin/env bash
# Shared status poster for Claude, Codex, Grok, and GLM sessions in Superset.
# Contract: CLAUDE.md § Superset Hook
# No-ops unless both SEE_SUPERSET_HOOK and SEE_SUPERSET_TOKEN are set.
set -euo pipefail

if [[ -z "${SEE_SUPERSET_HOOK:-}" || -z "${SEE_SUPERSET_TOKEN:-}" ]]; then
  exit 0
fi

ROOT="${CLAUDE_PROJECT_DIR:-${GROK_WORKSPACE_ROOT:-${SUPERSET_WORKSPACE_PATH:-}}}"
if [[ -z "$ROOT" ]]; then
  ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fi
cd "$ROOT" || exit 0

PAYLOAD="$(cat || true)"
STATUS_OVERRIDE="${1:-}"
MESSAGE_OVERRIDE="${2:-}"

export SEE_HOOK_PAYLOAD="$PAYLOAD"
export SEE_HOOK_STATUS_OVERRIDE="$STATUS_OVERRIDE"
export SEE_HOOK_MESSAGE_OVERRIDE="$MESSAGE_OVERRIDE"
export SEE_HOOK_AGENT="${SUPERSET_AGENT_ID:-${GROK_HOOK_EVENT:-unknown}}"
export SEE_HOOK_EVENT_ENV="${GROK_HOOK_EVENT:-}"

BODY="$(node <<'NODE'
const fs = require('fs');
const { execSync } = require('child_process');

function sh(cmd) {
  try { return execSync(cmd, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim(); }
  catch { return ''; }
}

let raw = process.env.SEE_HOOK_PAYLOAD || '';
let event = {};
try { if (raw.trim()) event = JSON.parse(raw); } catch { event = { raw }; }

const hookEvent = String(
  event.hook_event_name || event.hookEventName || event.event || process.env.SEE_HOOK_EVENT_ENV || ''
);

const map = {
  SessionStart: 'working',
  session_start: 'working',
  Notification: 'blocked',
  notification: 'blocked',
  PermissionRequest: 'blocked',
  Stop: 'finished',
  stop: 'finished',
  SessionEnd: 'finished',
  session_end: 'finished',
  SubagentStop: 'finished',
};

const override = process.env.SEE_HOOK_STATUS_OVERRIDE || '';
const status = override || map[hookEvent] || '';
if (!status) {
  process.stdout.write('');
  process.exit(0);
}

const branch = sh('git rev-parse --abbrev-ref HEAD');
const ticketMatch = branch.match(/SEE[-_]?(\d+)/i) || sh('git log -1 --pretty=%s').match(/SEE[-_]?(\d+)/i);
const ticket = ticketMatch ? `SEE-${ticketMatch[1]}` : '';

const remote = sh('git remote get-url origin');
const repoMatch = remote.match(/github\.com[:/]([^/]+)\/([^/.]+)/);
const repo = repoMatch ? repoMatch[2] : 'SeekerAgentConnect';

let pr = '';
try {
  pr = sh('gh pr view --json url -q .url');
} catch { pr = ''; }

const message =
  process.env.SEE_HOOK_MESSAGE_OVERRIDE ||
  event.message ||
  event.title ||
  event.last_assistant_message ||
  hookEvent ||
  status;

const body = {
  ticket,
  repo,
  branch,
  status,
  message: String(message).slice(0, 500),
};
if (pr) body.pr = pr;

process.stdout.write(JSON.stringify(body));
NODE
)"

if [[ -z "$BODY" ]]; then
  exit 0
fi

curl -sS -X POST "$SEE_SUPERSET_HOOK" \
  -H "Authorization: Bearer $SEE_SUPERSET_TOKEN" \
  -H "Content-Type: application/json" \
  --data "$BODY" \
  >/dev/null || true
