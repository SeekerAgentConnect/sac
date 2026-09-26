# The feed gateway's image. Build from the repository root:
#   docker build -f services/gateway/Dockerfile .
# The context is the root because the service compiles the generated protocol code
# in services/gateway/internal/gen, which is generated from packages/protocol/proto/ and committed.
#
# This is the shared service a developer's publisher publishes to and every
# subscribed phone reads from (SEE-90). Portable orchestration and optional
# ingress are separate under deploy/ (SEE-135).

# Pinned to the version services/gateway/go.mod requires, so a build from a clean
# checkout uses the toolchain the tests were run with
# (docs/development/toolchain.md).
ARG GO_VERSION=1.27.1

# --- build ------------------------------------------------------------------
# Dependencies first, from go.mod and go.sum alone, so editing a source file does
# not invalidate the module download.
# The compiler runs on the building machine's own platform and cross-compiles
# for the target: CGO is off, so GOOS/GOARCH is all it takes, and `docker buildx
# build --platform linux/amd64` from an Apple silicon Mac needs no emulation (as
# in servers/mcp-server/Dockerfile).
# Release identity (SEE-168). The defaults make a local `docker build` produce an image that is
# honestly labelled as one, and .github/workflows/release.yml passes the released version and the
# exact commit it was built from, so an image digest can always be traced back to a revision.
ARG VERSION=0.0.0-dev
ARG REVISION=unknown

FROM --platform=$BUILDPLATFORM golang:${GO_VERSION}-bookworm AS build
ARG TARGETOS
ARG TARGETARCH
WORKDIR /src
COPY services/gateway/go.mod services/gateway/go.sum ./
RUN go mod download
COPY services/gateway/ ./
# CGO off, so the SQLite driver's pure-Go implementation is what gets compiled and
# the binary needs no libc at run time. The Postgres driver (pgx) is pure Go as
# well, so the second store costs the image nothing: same scratch base, same
# absence of libc. -trimpath keeps the build machine's paths out of it.
ENV CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH
RUN go build -trimpath -ldflags="-s -w" -o /out/feed-gateway ./cmd/feed-gateway \
 && go build -trimpath -ldflags="-s -w" -o /out/feed-gatewayctl ./cmd/feed-gatewayctl \
 && mkdir /out/data

# --- runtime ----------------------------------------------------------------
# The two binaries, a CA bundle, and nothing else. No shell and no package
# manager.
#
# The bundle is here because SEE-92 gave the gateway its second outbound
# connection, and this one is over TLS to somebody else's service: the push
# endpoint a hint is sent through, and the endpoint the grant for it is exchanged
# at (internal/relay). SEE-91's first one needs no trust store — the broker is
# plain HTTP on an internal network with no route out of the deployment — and the
# image shipped none until then, deliberately, which is why this is a visible line
# with a reason beside it rather than something that was always there.
#
# SEE-145 gave it a third reason, and the first one the service cannot start
# without: a Postgres deployment reaches a managed database over TLS, so an image
# that could not verify one would fail to open its own store.
#
# It is copied from the build image's own Debian bundle, so it is the same set of
# roots the toolchain was built against and it is pinned by GO_VERSION with
# everything else. A deployment that relays nothing still carries it: an image is
# not conditional, and one bundle is a smaller thing to explain than two images.
FROM scratch AS runtime
ARG VERSION
ARG REVISION
LABEL org.opencontainers.image.title="Seeker Agent Connect feed gateway" \
      org.opencontainers.image.description="The shared public feed gateway: publishers submit documents, subscribed phones read them." \
      org.opencontainers.image.source="https://github.com/BrRenat/SeekerAgentConnect" \
      org.opencontainers.image.url="https://github.com/BrRenat/SeekerAgentConnect" \
      org.opencontainers.image.documentation="https://github.com/BrRenat/SeekerAgentConnect/blob/master/services/gateway/README.md" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.vendor="Seeker Agent Connect" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
# Where the state goes is the deployment's to say, and the image deliberately does not guess
# (SEE-145). There are two answers now — BROADCAST_DATABASE_PATH for a file under /data, and
# BROADCAST_DATABASE_URL for Postgres, which is what a deployment whose filesystem does not
# survive the container uses — and the gateway refuses to start with both, because they are two
# authorities for the same state.
#
# A default here would make "both" the resting state of every Postgres deployment, which would
# then be working only for as long as somebody remembered to blank a variable they never set. The
# image was never startable with no configuration anyway: BROADCAST_PUBLIC_URL has no default
# either, for the same reason. The four Compose presets name the file; deploy/seeker-gateway.yaml
# names the URL.
# The image's safe standalone default is loopback. The portable Compose preset
# overrides these with explicit container-network binds and publishes only its
# chosen host-loopback ports; optional Caddy ingress has a separate lifecycle.
ENV BROADCAST_READ_ADDRESS=127.0.0.1:8090
ENV BROADCAST_PUBLISHER_ADDRESS=127.0.0.1:8091

COPY --from=build /out/feed-gateway /out/feed-gatewayctl /
# /data has to exist in the image, owned by the account below: Docker gives a
# new named volume the ownership of the directory it is mounted over, and a path
# the image lacks becomes root's, where uid 10001 cannot create the database.
COPY --from=build --chown=10001:10001 /out/data /data
# A fixed, unprivileged uid/gid, the same pair the sidecar image uses, so a
# volume's ownership is the same on every host. There is no /etc/passwd in a
# scratch image and none is needed: the number is what the kernel checks.
USER 10001:10001
VOLUME ["/data"]

# 8092 is the operator's admin page, which exists only when BROADCAST_ADMIN_PASSWORD_HASH is
# configured (SEE-141). It is deliberately not given a default address here: an address with no
# password behind it is a configuration error the gateway refuses at startup, and the image must
# stay startable with no administrative surface at all.
EXPOSE 8090 8091 8092

# Exec form: the binary is PID 1 and receives SIGTERM itself, which is what its
# shutdown needs — stop accepting, finish what is in flight, make one last
# fan-out pass, close the database.
ENTRYPOINT ["/feed-gateway"]

# App Platform Console execs /bin/sh. Scratch has none, so that tab cannot
# attach and feed-gatewayctl cannot run against the live SQLite file. The
# default image is this stage: the same binaries plus static busybox as
# /bin/sh. Compose can still build scratch with --target runtime.
#
#   docker buildx build --platform linux/amd64 --push \
#     -f services/gateway/Dockerfile -t brenat/seeker-agent-connect:gateway-0.1.7 .
FROM runtime AS console
USER 0
COPY --from=busybox:1.37.0-uclibc /bin/busybox /bin/sh
USER 10001:10001
