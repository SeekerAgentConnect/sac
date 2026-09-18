#!/bin/sh
# Obtain, or renew, the sidecar's certificate from Tailscale, and restart the sidecar
# when it changed (deploy/server/README.md).
#
#   sudo ./tls-from-tailscale.sh
#
# `tailscale cert` asks Let's Encrypt for a publicly trusted certificate for this
# node's MagicDNS name, over a DNS challenge Tailscale completes itself; it needs
# "HTTPS Certificates" enabled for the tailnet, and root or the Tailscale operator
# account. It renews only when the certificate is close to expiry, so this script
# is safe to run often — a weekly cron entry is the intended use — and it stays
# well inside Let's Encrypt's rate limits. The sidecar reads the PEM files once, at
# start, which is why a changed certificate ends in a restart.
#
# The files land in ./tls, owned by the image's account (uid 10001) and readable by
# it alone; compose.yaml mounts that directory read-only at /run/tls.
set -eu

cd "$(dirname "$0")"

# The only value read from .env; an exported SERVER_DOMAIN wins over it.
if [ -z "${SERVER_DOMAIN:-}" ] && [ -f .env ]; then
    SERVER_DOMAIN=$(sed -n 's/^SERVER_DOMAIN=//p' .env | tail -n 1)
fi
: "${SERVER_DOMAIN:?SERVER_DOMAIN is not set: fill in .env, or export it}"
# The account the sidecar image runs as (sidecar/Dockerfile).
SIDECAR_UID=10001

mkdir -p tls
before=$(sha256sum tls/fullchain.pem 2>/dev/null | cut -d' ' -f1 || true)

tailscale cert --cert-file tls/fullchain.pem --key-file tls/privkey.pem "$SERVER_DOMAIN"

chown "$SIDECAR_UID:$SIDECAR_UID" tls/fullchain.pem tls/privkey.pem
chmod 0644 tls/fullchain.pem
chmod 0600 tls/privkey.pem

after=$(sha256sum tls/fullchain.pem | cut -d' ' -f1)
if [ "$before" != "$after" ]; then
    echo "certificate for $SERVER_DOMAIN written to tls/; restarting the sidecar if it runs"
    if docker compose ps --status running --services 2>/dev/null | grep -qx sidecar; then
        docker compose restart sidecar
    fi
else
    echo "certificate for $SERVER_DOMAIN unchanged"
fi
openssl x509 -in tls/fullchain.pem -noout -subject -enddate 2>/dev/null || true
