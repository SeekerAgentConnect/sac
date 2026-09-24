#!/usr/bin/env bash
# Publish (or cancel) a CopyTrading test signal through the deployed demo's /trader admin page.
# Runbook: docs/development/emulator-e2e.md
#
#   scripts/demo-signal.sh create [note] [pair=usdc-sol|sol-usdc] [expires=15m|1h|2h|4h]
#   scripts/demo-signal.sh cancel <signal-id>
#   scripts/demo-signal.sh list
#
# Needs DEMO_ADMIN_USER and DEMO_ADMIN_PWD (source ~/.env). SEEKER_SIGNALS_DEMO_URL overrides the origin.
set -euo pipefail

BASE="${SEEKER_SIGNALS_DEMO_URL:-https://signals-demo-fzs2q.ondigitalocean.app}"
: "${DEMO_ADMIN_USER:?DEMO_ADMIN_USER is not set (source ~/.env)}"
: "${DEMO_ADMIN_PWD:?DEMO_ADMIN_PWD is not set (source ~/.env)}"
JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

# The admin page checks Origin/Referer on every POST.
post() {
  curl -fsS -c "$JAR" -b "$JAR" -o /dev/null -w '%{redirect_url}\n' \
    -H "Origin: $BASE" -H "Referer: $BASE/trader" "$@"
}

login() {
  local to
  to="$(post --data-urlencode "name=$DEMO_ADMIN_USER" --data-urlencode "password=$DEMO_ADMIN_PWD" \
    "$BASE/trader/login")"
  [[ "$to" != *login* ]] || { echo "login refused: $to" >&2; exit 1; }
}

listing() {
  # One line per signal: pair, note, id, state, publication, expiry.
  curl -fsS -b "$JAR" "$BASE/trader" | sed -e 's/<[^>]*>/ /g; s/^ *//; s/ *$//' | grep -v '^$' \
    | awk '/[0-9a-f]{8}-[0-9a-f]{4}-/ { if (row) print row; row = $0; n = 3; next }
           n > 0 { row = row " | " $0; n-- }
           END { if (row) print row; else print "(no signals)" }'
}

login
case "${1:-}" in
  create)
    post --data-urlencode "pair=${3:-usdc-sol}" --data "slippage=50" \
      --data-urlencode "expires=${4:-1h}" --data-urlencode "note=${2:-agent test signal}" \
      "$BASE/trader/create"
    listing | head -3
    ;;
  cancel) post --data "" "$BASE/trader/signals/${2:?signal id}/cancel" ;;
  list) listing ;;
  *) sed -n '2,10p' "$0"; exit 2 ;;
esac
