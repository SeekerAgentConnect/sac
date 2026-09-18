#!/bin/sh
set -eu
if [ "$(id -u)" = "0" ]; then
    mkdir -p /data
    chown 10001:10001 /data
    exec gosu sidecar:sidecar "$@"
fi
exec "$@"
