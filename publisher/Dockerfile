# A publisher template's image. Build from the repository root:
#   docker build -f publisher/Dockerfile .
# The context is the root because the template compiles the generated protocol
# code in publisher/internal/gen, which is generated from proto/ and committed.
#
# This is neither of the other two images. `gateway/` is Caddy in front of one
# owner's private sidecar (SAW-035); `broadcast/` is the shared service this
# publishes to (SEE-90). This is a developer's own publisher: it submits
# documents to that gateway and serves its own small API to whoever writes the
# signals (docs/wiki/copytrading-template.md).

# Pinned to the version publisher/go.mod requires, so a build from a clean
# checkout uses the toolchain the tests were run with
# (docs/development/toolchain.md).
ARG GO_VERSION=1.27.1

# --- build ------------------------------------------------------------------
# Dependencies first, from go.mod and go.sum alone, so editing a source file does
# not invalidate the module download.
FROM golang:${GO_VERSION}-bookworm AS build
WORKDIR /src
COPY publisher/go.mod publisher/go.sum ./
RUN go mod download
COPY publisher/ ./
# CGO off, so the SQLite driver's pure-Go implementation is what gets compiled and
# the binary needs no libc at run time. -trimpath keeps the build machine's paths
# out of it.
ENV CGO_ENABLED=0
RUN go build -trimpath -ldflags="-s -w" -o /out/copytrading ./cmd/copytrading \
 && go build -trimpath -ldflags="-s -w" -o /out/publishctl ./cmd/publishctl

# --- runtime ----------------------------------------------------------------
# The two binaries, a CA bundle, and nothing else. No shell and no package
# manager.
#
# The bundle is here because this service calls out over TLS in the ordinary
# case: the shared gateway is somebody else's HTTPS endpoint, and a publisher
# that could not verify it would be publishing to whatever answered. That is the
# difference from the gateway's own image, which shipped none until it gained an
# outbound call of its own (broadcast/Dockerfile).
#
# It is copied from the build image's Debian bundle, so it is the same set of
# roots the toolchain was built against and it is pinned by GO_VERSION with
# everything else.
FROM scratch AS runtime
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
# The signals this template holds and what the gateway has confirmed about each.
# It is the only writable path.
ENV PUBLISHER_DATABASE_PATH=/data/publisher.db
# The API binds every interface *inside this container*, which is the only way
# the Caddy in front of it — sharing this network namespace — can reach it. It is
# not published to the host by the compose file; that is the proxy's job.
ENV PUBLISHER_API_ADDRESS=127.0.0.1:8092

COPY --from=build /out/copytrading /out/publishctl /
# A fixed, unprivileged uid/gid, the same pair the other images use, so a volume's
# ownership is the same on every host. There is no /etc/passwd in a scratch image
# and none is needed: the number is what the kernel checks.
USER 10001:10001
VOLUME ["/data"]

EXPOSE 8092

# Exec form: the binary is PID 1 and receives SIGTERM itself, which is what its
# shutdown needs — stop accepting, finish what is in flight, and leave anything
# unpublished in the file for the next start.
ENTRYPOINT ["/copytrading"]
