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
# The compiler runs on the building machine's own platform and cross-compiles
# for the target: CGO is off, so GOOS/GOARCH is all it takes, and `docker buildx
# build --platform linux/amd64` from an Apple silicon Mac needs no emulation (as
# in mcp-server/Dockerfile).
FROM --platform=$BUILDPLATFORM golang:${GO_VERSION}-bookworm AS build
ARG TARGETOS
ARG TARGETARCH
WORKDIR /src
COPY broadcast/go.mod broadcast/go.sum ./
RUN go mod download
COPY broadcast/ ./
# CGO off, so the SQLite driver's pure-Go implementation is what gets compiled and
# the binary needs no libc at run time. -trimpath keeps the build machine's paths
# out of it.
ENV CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH
RUN go build -trimpath -ldflags="-s -w" -o /out/broadcast ./cmd/broadcast \
 && go build -trimpath -ldflags="-s -w" -o /out/broadcastctl ./cmd/broadcastctl \
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
# image shipped none until now, deliberately, which is why this is a visible line
# with a reason beside it rather than something that was always there.
#
# It is copied from the build image's own Debian bundle, so it is the same set of
# roots the toolchain was built against and it is pinned by GO_VERSION with
# everything else. A deployment that relays nothing still carries it: an image is
# not conditional, and one bundle is a smaller thing to explain than two images.
FROM scratch AS runtime
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
# The publications and the publisher configuration. It is the only writable path.
ENV BROADCAST_DATABASE_PATH=/data/broadcast.db
# Both listeners bind every interface *inside this container*, which is the only
# way the Caddy in front of it — sharing this network namespace — can reach them.
# Neither is published to the host by the compose file; that is the proxy's job.
ENV BROADCAST_READ_ADDRESS=127.0.0.1:8090
ENV BROADCAST_PUBLISHER_ADDRESS=127.0.0.1:8091

COPY --from=build /out/broadcast /out/broadcastctl /
# /data has to exist in the image, owned by the account below: Docker gives a
# new named volume the ownership of the directory it is mounted over, and a path
# the image lacks becomes root's, where uid 10001 cannot create the database.
COPY --from=build --chown=10001:10001 /out/data /data
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
