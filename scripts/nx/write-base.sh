#!/usr/bin/env bash
# Print NX_BASE for `nx affected` from the GitHub event. PR_BASE and BEFORE are the
# pull_request base sha and push's github.event.before (SEE-187).
set -euo pipefail
zero="0000000000000000000000000000000000000000"
if [[ "${GITHUB_EVENT_NAME:-}" == "pull_request" && -n "${PR_BASE:-}" ]]; then
  base="$PR_BASE"
elif [[ -n "${BEFORE:-}" && "$BEFORE" != "$zero" ]]; then
  base="$BEFORE"
else
  base="HEAD~1"
fi
echo "NX_BASE=$base" >> "${GITHUB_ENV:-/dev/stdout}"
echo "affected base $base"
