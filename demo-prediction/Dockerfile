# The Prediction demo's image, and only this demo's. Build from the repository
# root:
#   docker build -f demo-prediction/Dockerfile -t demo-prediction:local .
#
# The context is the repository root for one documented reason: this module's
# go.mod replaces the shared source library with ../publisher-support, so the
# build needs that directory as well as this one. It needs nothing else — and
# demo-copytrading/ is never copied in, so no signal-writing API, no trader UI
# and no CopyTrading binary exists anywhere in this image (SEE-134).
#
# This is none of the other images. `feed-gateway/` is the shared service this
# publishes to (SEE-90); `mcp-server/` is one owner's own direct server
# (SEE-132). This one discovers Jupiter Prediction markets and publishes those
# (SEE-96, docs/wiki/prediction-template.md).
#
# Two binaries. `/prediction` is the entrypoint; `/publishctl` is the operator's
# client of its read-only API.

# Pinned to the version demo-prediction/go.mod requires, so a build from a clean
# checkout uses the toolchain the tests were run with
# (docs/development/toolchain.md).
ARG GO_VERSION=1.27.1

# --- build ------------------------------------------------------------------
# Dependencies first, from the two go.mod/go.sum pairs alone, so editing a source
# file does not invalidate the module download.
# The compiler runs on the building machine's own platform and cross-compiles
# for the target: CGO is off, so GOOS/GOARCH is all it takes, and `docker buildx
# build --platform linux/amd64` from an Apple silicon Mac needs no emulation (as
# in mcp-server/Dockerfile).
FROM --platform=$BUILDPLATFORM golang:${GO_VERSION}-bookworm AS build
ARG TARGETOS
ARG TARGETARCH
WORKDIR /src
COPY publisher-support/go.mod publisher-support/go.sum ./publisher-support/
COPY demo-prediction/go.mod demo-prediction/go.sum ./demo-prediction/
WORKDIR /src/demo-prediction
RUN go mod download
COPY publisher-support/ /src/publisher-support/
COPY demo-prediction/ /src/demo-prediction/
# CGO off, so the SQLite driver's pure-Go implementation is what gets compiled and
# the binary needs no libc at run time. -trimpath keeps the build machine's paths
# out of it.
ENV CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH
RUN go build -trimpath -ldflags="-s -w" -o /out/prediction ./cmd/prediction \
 && go build -trimpath -ldflags="-s -w" -o /out/publishctl ./cmd/publishctl \
 && mkdir /out/data

# --- runtime ----------------------------------------------------------------
# The two binaries, a CA bundle, and nothing else. No shell and no package
# manager.
#
# The bundle is here because this service calls out over TLS twice: the shared
# gateway is somebody else's HTTPS endpoint, and so is the prediction provider —
# a publisher that could not verify either would be publishing to, and
# believing, whatever answered.
#
# It is copied from the build image's Debian bundle, so it is the same set of
# roots the toolchain was built against and it is pinned by GO_VERSION with
# everything else.
FROM scratch AS runtime
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
# The markets this demo tracks, the signals it publishes for them, and what the
# gateway has confirmed about each. It is the only writable path, and it is this
# demo's alone: the CopyTrading demo has its own file, its own volume and its own
# publisher identity (SEE-134).
ENV PUBLISHER_DATABASE_PATH=/data/prediction.db
# The image's safe standalone default is loopback. The portable Compose preset
# overrides it with an explicit container-network bind and publishes the API
# directly on host loopback; no proxy is bundled with this demo.
ENV PUBLISHER_API_ADDRESS=127.0.0.1:8092

COPY --from=build /out/prediction /out/publishctl /
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
ENTRYPOINT ["/prediction"]
