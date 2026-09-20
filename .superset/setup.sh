#!/usr/bin/env bash
# Workspace boot only. Does not post status or merge PRs.
set -euo pipefail

if [[ -n "${SUPERSET_ROOT_PATH:-}" && -f "${SUPERSET_ROOT_PATH}/.env" ]]; then
  cp "${SUPERSET_ROOT_PATH}/.env" .env
fi

chmod +x scripts/superset-hook.sh 2>/dev/null || true
echo "Superset workspace ready: ${SUPERSET_WORKSPACE_NAME:-unknown}"
