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
# it alone; compose.direct.yaml mounts that directory read-only at /run/tls. When
# .env's BROADCAST_DOMAIN is the same name, compose.tailscale.yaml's Caddy serves
# the gateway with these files too, and the key is also readable by group root.
set -eu

cd "$(dirname "$0")"

# The only value read from .env.direct; an exported SERVER_DOMAIN wins over it.
if [ -z "${SERVER_DOMAIN:-}" ] && [ -f .env.direct ]; then
    SERVER_DOMAIN=$(sed -n 's/^SERVER_DOMAIN=//p' .env.direct | tail -n 1)
fi
# The gateway behind Funnel (compose.tailscale.yaml) serves the same name with the
# same files. Without a sidecar its BROADCAST_DOMAIN is the name to ask for.
GATEWAY_DOMAIN=
if [ -f .env ]; then
    GATEWAY_DOMAIN=$(sed -n 's/^BROADCAST_DOMAIN=//p' .env | tail -n 1)
fi
case "${SERVER_DOMAIN:-}:$GATEWAY_DOMAIN" in
    :*.ts.net) SERVER_DOMAIN=$GATEWAY_DOMAIN ;;
esac
: "${SERVER_DOMAIN:?SERVER_DOMAIN is not set: fill in .env.direct, or export it}"
# The account the sidecar image runs as (sidecar/Dockerfile).
SIDECAR_UID=10001

mkdir -p tls
before=$(sha256sum tls/fullchain.pem 2>/dev/null | cut -d' ' -f1 || true)

tailscale cert --cert-file tls/fullchain.pem --key-file tls/privkey.pem "$SERVER_DOMAIN"

chown "$SIDECAR_UID:$SIDECAR_UID" tls/fullchain.pem tls/privkey.pem
chmod 0644 tls/fullchain.pem
chmod 0600 tls/privkey.pem
if [ "$GATEWAY_DOMAIN" = "$SERVER_DOMAIN" ]; then
    # The gateway's Caddy is root without capabilities: it reads the key as group root.
    chown "$SIDECAR_UID:0" tls/privkey.pem
    chmod 0640 tls/privkey.pem
fi

after=$(sha256sum tls/fullchain.pem | cut -d' ' -f1)
if [ "$before" != "$after" ]; then
    echo "certificate for $SERVER_DOMAIN written to tls/; restarting what serves it, if it runs"
    if docker compose -f compose.direct.yaml ps --status running --services 2>/dev/null | grep -qx sidecar; then
        docker compose -f compose.direct.yaml restart sidecar
    fi
    if [ "$GATEWAY_DOMAIN" = "$SERVER_DOMAIN" ] \
        && docker compose -f compose.yaml -f compose.tailscale.yaml ps --status running --services 2>/dev/null | grep -qx gateway-proxy; then
        # Caddy's administration is off, so a new certificate is a restart; streams reconnect.
        docker compose -f compose.yaml -f compose.tailscale.yaml restart gateway-proxy
    fi
else
    echo "certificate for $SERVER_DOMAIN unchanged"
fi
openssl x509 -in tls/fullchain.pem -noout -subject -enddate 2>/dev/null || true
