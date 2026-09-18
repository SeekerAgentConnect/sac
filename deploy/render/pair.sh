#!/bin/sh
# The operator's pairing commands on Render, from the service's Shell tab:
#
#   render-pair           shows a one-use pairing code, as a QR code and as text
#   render-pair status    shows the paired phone
#   render-pair revoke    revokes the paired phone's connection
#
# A shell opened into the container does not pass through the supervisor, so this repeats the two
# things it settles: the public URL (SIDECAR_PUBLIC_URL, or Render's own RENDER_EXTERNAL_URL) and
# the database on the disk. It also runs the command as the service's own account, uid 10001, so
# the SQLite files it touches stay readable by the running sidecar. The sidecar's own configuration
# check refuses a public URL that is not HTTPS.
set -eu
: "${SIDECAR_PUBLIC_URL:=${RENDER_EXTERNAL_URL:-}}"
export SIDECAR_PUBLIC_URL
export SIDECAR_HOST=127.0.0.1 SIDECAR_PORT=8080 DATABASE_PATH=/data/sidecar.db
cd /app
if [ "$(id -u)" = "0" ]; then
    exec gosu sidecar:sidecar node sidecar/dist/pairing/cli.js "$@"
fi
exec node sidecar/dist/pairing/cli.js "$@"
