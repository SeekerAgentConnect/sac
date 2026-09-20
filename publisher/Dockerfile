# A publisher template's image. Build from the repository root:
#   docker build -f publisher/Dockerfile .
# The context is the root because the template compiles the generated protocol
# code in publisher/internal/gen, which is generated from proto/ and committed.
#
# This is neither of the other two images. `gateway/` is Caddy in front of one
# owner's private sidecar (SAW-035); `feed-gateway/` is the shared service this
# publishes to (SEE-90). This is a developer's own publisher: it submits
# documents to that gateway and serves its own small API to whoever writes the
# signals (docs/wiki/copytrading-template.md).
#
# One image, two templates and the CopyTrading trader UI. `/copytrading` is the
# entrypoint and publishes a trader's own signals (SEE-95); `/prediction`
# discovers Jupiter Prediction markets and publishes those (SEE-96,
# docs/wiki/prediction-template.md). `/copytrading-admin` is the password-gated
# HTML UI that calls the CopyTrading API (SEE-126). They share an image —
# a deployment names the binary it runs (compose.prediction.yaml and
# compose.demos.yaml override the entrypoint).

# Pinned to the version publisher/go.mod requires, so a build from a clean
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
COPY publisher/go.mod publisher/go.sum ./
RUN go mod download
COPY publisher/ ./
# CGO off, so the SQLite driver's pure-Go implementation is what gets compiled and
# the binary needs no libc at run time. -trimpath keeps the build machine's paths
# out of it.
ENV CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH
RUN go build -trimpath -ldflags="-s -w" -o /out/copytrading ./cmd/copytrading \
 && go build -trimpath -ldflags="-s -w" -o /out/prediction ./cmd/prediction \
 && go build -trimpath -ldflags="-s -w" -o /out/publishctl ./cmd/publishctl \
 && go build -trimpath -ldflags="-s -w" -o /out/copytrading-admin ./cmd/copytrading-admin \
 && mkdir /out/data

# --- runtime ----------------------------------------------------------------
# The four binaries, a CA bundle, and nothing else. No shell and no package
# manager.
#
# The bundle is here because this service calls out over TLS in the ordinary
# case: the shared gateway is somebody else's HTTPS endpoint, and so is the
# prediction provider — a publisher that could not verify either would be
# publishing to, and believing, whatever answered. That is the difference from
# the gateway's own image, which shipped none until it gained an outbound call of
# its own (feed-gateway/Dockerfile).
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

COPY --from=build /out/copytrading /out/prediction /out/publishctl /out/copytrading-admin /
# /data has to exist in the image, owned by the account below: Docker gives a
# new named volume the ownership of the directory it is mounted over, and a path
# the image lacks becomes root's, where uid 10001 cannot create the database.
COPY --from=build --chown=10001:10001 /out/data /data
# A fixed, unprivileged uid/gid, the same pair the other images use, so a volume's
# ownership is the same on every host. There is no /etc/passwd in a scratch image
# and none is needed: the number is what the kernel checks.
USER 10001:10001
VOLUME ["/data"]

EXPOSE 8092

# Exec form: the binary is PID 1 and receives SIGTERM itself, which is what its
# shutdown needs — stop accepting, finish what is in flight, and leave anything
# unpublished in the file for the next start.
#
# The CopyTrading template is the default because it is the one with no provider
# to configure. A Prediction deployment overrides this with ["/prediction"].
ENTRYPOINT ["/copytrading"]
