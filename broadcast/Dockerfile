# The broadcast gateway's image. Build from the repository root:
#   docker build -f broadcast/Dockerfile .
# The context is the root because the service compiles the generated protocol code
# in broadcast/internal/gen, which is generated from proto/ and committed.
#
# This is not the `gateway/` directory's image. That one is Caddy in front of one
# owner's private sidecar (SAW-035). This is the shared service a developer's
# publisher publishes to and every subscribed phone reads from (SEE-90).

# Pinned to the version broadcast/go.mod requires, so a build from a clean
# checkout uses the toolchain the tests were run with
# (docs/development/toolchain.md).
ARG GO_VERSION=1.27.1

# --- build ------------------------------------------------------------------
# Dependencies first, from go.mod and go.sum alone, so editing a source file does
# not invalidate the module download.
FROM golang:${GO_VERSION}-bookworm AS build
WORKDIR /src
COPY broadcast/go.mod broadcast/go.sum ./
RUN go mod download
COPY broadcast/ ./
# CGO off, so the SQLite driver's pure-Go implementation is what gets compiled and
# the binary needs no libc at run time. -trimpath keeps the build machine's paths
# out of it.
ENV CGO_ENABLED=0
RUN go build -trimpath -ldflags="-s -w" -o /out/broadcast ./cmd/broadcast \
 && go build -trimpath -ldflags="-s -w" -o /out/broadcastctl ./cmd/broadcastctl

# --- runtime ----------------------------------------------------------------
# Nothing but the two binaries. No shell, no package manager, and — deliberately —
# still no CA certificates.
#
# SEE-91 gave the gateway one outbound connection: the broker it publishes events
# to. That link is plain HTTP on an internal compose network with no route out of
# the deployment, so there is nothing for a trust store to verify. A deployment
# that puts its broker behind TLS instead needs a bundle here, and adding one is
# a visible line in this file rather than something that was always there.
FROM scratch AS runtime
# The publications and the publisher configuration. It is the only writable path.
ENV BROADCAST_DATABASE_PATH=/data/broadcast.db
# Both listeners bind every interface *inside this container*, which is the only
# way the Caddy in front of it — sharing this network namespace — can reach them.
# Neither is published to the host by the compose file; that is the proxy's job.
ENV BROADCAST_READ_ADDRESS=127.0.0.1:8090
ENV BROADCAST_PUBLISHER_ADDRESS=127.0.0.1:8091

COPY --from=build /out/broadcast /out/broadcastctl /
# A fixed, unprivileged uid/gid, the same pair the sidecar image uses, so a
# volume's ownership is the same on every host. There is no /etc/passwd in a
# scratch image and none is needed: the number is what the kernel checks.
USER 10001:10001
VOLUME ["/data"]

EXPOSE 8090 8091

# Exec form: the binary is PID 1 and receives SIGTERM itself, which is what its
# shutdown needs — stop accepting, finish what is in flight, make one last
# fan-out pass, close the database.
ENTRYPOINT ["/broadcast"]
