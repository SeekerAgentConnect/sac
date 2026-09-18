# Seeker Agent Connect on Render

One Docker image runs the sidecar and its gateway as a single [Render](https://render.com) web
service. Render terminates HTTPS on the service's `onrender.com` name (or a custom domain) and
forwards plain HTTP to the container; nothing here obtains a certificate, and nothing here changes
the sidecar, the Compose stack in `gateway/`, or any stage boundary.

There are two ways to get the image onto Render. Both are described below; the Blueprint is the
simpler one and needs no registry account.

| | Blueprint (build from this repository) | Prebuilt image |
| --- | --- | --- |
| How | `render.yaml` at the repository root; Render builds `deploy/render/Dockerfile` on every push | You build `linux/amd64` on your machine and push to Docker Hub, or start `.github/workflows/build-render.yml` by hand to push to GitHub Container Registry |
| Needs | A Render account connected to the GitHub repository | A registry Render can pull from |
| Updates | Push to the branch, or turn `autoDeployTrigger` off and deploy by hand | Push a new tag, then deploy it |

## What runs

- **Render** terminates TLS and forwards HTTP to the container on `$PORT` (`10000` by default).
- **Caddy** (`Caddyfile`) listens on `0.0.0.0:$PORT`. It forwards `/healthz` for Render's probes,
  and, under the deployment's own hostname only, `/mcp`, the OAuth discovery document, and the
  phone's `PairingService`, `RequestService`, and `UpdateService`, each with a 64 KiB body cap, to
  the sidecar at `127.0.0.1:8080`. Every other path, the Stage 1 `LiveCommandService` diagnostic
  among them, and every other `Host` get `404`. There is no admin API, no certificate issuance, no
  access log, and `Host` and `Authorization` pass through unchanged, so the sidecar's DNS-rebinding
  check and its own token checks are the ones that count.
- **The sidecar** binds loopback only, as everywhere else, with `DATABASE_PATH=/data/sidecar.db`
  on Render's persistent disk.
- **The supervisor** (`supervisor.mjs`) checks the configuration, derives `MCP_ALLOWED_HOSTS`
  from the public URL, starts both, stops the service if either exits, and passes `SIGTERM` on with
  a 25-second bound. `tini` reaps orphans. `entrypoint.sh` chowns the mounted disk to uid/gid
  `10001` as root and drops to that account before anything else starts; Caddy carries no file
  capability because it needs no privileged port.
- **`render-pair`** (`pair.sh`) is the pairing CLI for Render's Shell tab: the same derivation as
  the supervisor, run as uid `10001`, so a shell that opens as root leaves no root-owned SQLite
  files behind.

The live update stream is not carried: it is gRPC over HTTP/2 to the sidecar's own TLS listener,
which this deployment does not have, so pairing advertises no update endpoint and the phone keeps
its manual refresh, exactly as behind the Compose gateway. FCM is optional and not set up here.
OAuth (`MCP_OAUTH_*`) works if configured; no authorization server is included.

## Environment

| Variable | Value |
| --- | --- |
| `MCP_TOKEN` | A random secret, at least 32 characters. The Blueprint generates it. |
| `PHONE_TOKEN` | A different random secret, at least 32 characters. The Blueprint generates it. |
| `SIDECAR_PUBLIC_URL` | Optional. The URL the pairing code carries and the only `Host` `/mcp` accepts. Unset, the supervisor uses Render's `RENDER_EXTERNAL_URL` (`https://<service>.onrender.com`). Set it to `https://your.domain` when a custom domain is attached. HTTPS, no path, no port. |
| `SOLANA_RPC_URL` | `https://api.devnet.solana.com` for devnet; unset serves no transfer tool at all. |
| `PORT` | Render sets `10000`. Anything but `8080` in 1024–65535 works. |
| `DATABASE_PATH` | `/data/sidecar.db`, the image default and the only value accepted. |
| `MCP_DEMO_TOOLS` | `false`, the image default. |

Generate a token by hand with `openssl rand -hex 32`. `SIDECAR_UPDATE_PORT`,
`SIDECAR_TLS_CERT_PATH`, and `SIDECAR_TLS_KEY_PATH` are refused: this deployment has no TLS
listener of its own. `GATEWAY_DOMAIN` and `ACME_EMAIL` belong to the Compose stack and are unused
here. A refused configuration names the variable in the log, never its value, and neither child
starts.

## A. Deploy with the Blueprint

1. In Render: **New > Blueprint**, pick this repository and branch. Render reads `render.yaml`:
   a Docker web service built from `deploy/render/Dockerfile` with the repository root as
   context, a 1 GB disk at `/data`, `/healthz` as the health check, generated `MCP_TOKEN` and
   `PHONE_TOKEN`, and devnet as the RPC endpoint. It asks for `SIDECAR_PUBLIC_URL`; leave it
   empty unless you have a custom domain.
2. Let the first deploy finish. A disk needs a paid instance, and a service with a disk runs one
   instance; deploys stop the old instance before starting the new one, so each deploy is a few
   seconds of downtime, not zero.
3. Check it from outside:

   ```bash
   curl -fsS https://YOUR-SERVICE.onrender.com/healthz
   curl -i -X POST https://YOUR-SERVICE.onrender.com/mcp
   ```

   Expect `{"status":"ok"}` and `401`.

To change the region or plan, edit `render.yaml` before the first sync; Render syncs later edits
too. `autoDeployTrigger: commit` redeploys on every push to the branch.

## B. Deploy a prebuilt image

**From Docker Hub, built on your machine.** Render runs `linux/amd64`, so build for it
explicitly. The Dockerfile installs and compiles on your machine's own platform and assembles only
the runtime stage for the target, so this works on an Apple silicon Mac too (pnpm itself cannot
run under Docker Desktop's QEMU emulation, and it does not have to):

```bash
docker login -u YOUR-USER
docker buildx build --platform linux/amd64 -f deploy/render/Dockerfile \
  -t YOUR-USER/seeker-agent-connect:render-v1 --load .
docker push YOUR-USER/seeker-agent-connect:render-v1
```

Use a fresh tag for each release, and do not overwrite a tag Render is running. Docker Hub creates
the repository public on the first push; make it private in the repository's settings if you
prefer, and then give Render a read-only access token. The image carries no secret either way.

Then in Render: **New > Web Service > Existing Image**, image
`docker.io/YOUR-USER/seeker-agent-connect:render-v1` (with a registry credential for a private
repository). Pick a paid instance, attach a disk at `/data` before the first successful start, set
the health check path to `/healthz`, leave the Docker command unset, keep one instance, and set the
environment above: `MCP_TOKEN`, `PHONE_TOKEN`, and `SOLANA_RPC_URL` if transfers are wanted;
`SIDECAR_PUBLIC_URL` only for a custom domain.

**From GitHub Container Registry.** The **Render image** workflow runs only when started by hand
from the Actions tab, so it spends no Actions minutes on its own. It builds `linux/amd64` on a
native runner and pushes `ghcr.io/brrenat/seeker-agent-connect:<branch or tag>`. Render's setup is
the same as above, with a GitHub token that has `read:packages` as the registry credential for a
private repository.

## Smoke test locally

Any machine with Docker can run the image natively for a check before deploying (the port and the
`onrender.com` name are pretend; TLS is Render's job):

```bash
docker build -f deploy/render/Dockerfile -t seeker-agent-connect:render .
docker run -d --name seeker-render-test \
  -e MCP_TOKEN="$(openssl rand -hex 32)" -e PHONE_TOKEN="$(openssl rand -hex 32)" \
  -e RENDER_EXTERNAL_URL=https://seeker-render-test.onrender.com \
  -p 127.0.0.1:10000:10000 -v seeker-render-test-data:/data \
  seeker-agent-connect:render

curl -fsS http://127.0.0.1:10000/healthz
curl -i -X POST http://127.0.0.1:10000/mcp -H 'Host: seeker-render-test.onrender.com'
curl -i -X POST http://127.0.0.1:10000/mcp -H 'Host: wrong.example.com'
curl -i http://127.0.0.1:10000/seekervault.live.v1.LiveCommandService/Test \
  -H 'Host: seeker-render-test.onrender.com'
docker exec seeker-render-test render-pair status
docker exec seeker-render-test getcap /usr/bin/caddy
docker top seeker-render-test
docker stop --time 30 seeker-render-test && docker inspect seeker-render-test --format '{{.State.ExitCode}}'
docker rm seeker-render-test && docker volume rm seeker-render-test-data
```

Expected: health `200`; MCP `401` under the right host and `404` under a wrong one; the diagnostic
`404`; "No phone is paired"; `getcap` prints nothing; every process runs as uid `10001`; exit `0`.

## Pair the phone, and a first request

Open the service's **Shell** tab in Render, on the running service itself (pairing writes to this
service's disk), and run:

```bash
render-pair
render-pair status
```

Scan the QR code on the Seeker. `render-pair revoke` revokes the phone and cancels its pending
requests. If the hostname changes (a custom domain, a renamed service), set `SIDECAR_PUBLIC_URL`
and pair again: the phone holds the old URL.

Point an agent at `https://YOUR-SERVICE.onrender.com/mcp` with the Render `MCP_TOKEN` (Render
shows a generated value in the Environment tab). The test agent is not in this image; run it from
a checkout, or use Hermes with `examples/hermes.config.hosted.yaml`. Then, on mobile data rather
than the same Wi-Fi: ask for a message signature, refresh the app, approve by hand, and read the
result from the agent. Leave one request unanswered, restart the service from the dashboard, and
confirm the pairing and the request survive. Only then try a small devnet transfer.

## Operating it

- **Logs.** The Render log stream carries the sidecar's own lines and Caddy's runtime messages;
  there is no access log, so no URL or token is written.
- **Rotating a token.** Change it in the Environment tab; Render redeploys. `MCP_TOKEN` cuts every
  agent over at once. `PHONE_TOKEN` is the Stage 1 diagnostic's token and is not what the paired
  phone uses; to cut the phone off, `render-pair revoke`.
- **Backups.** Render snapshots the disk daily and keeps snapshots for at least seven days.
  Restoring one moves the sidecar's record backwards and does nothing else: no request is
  re-executed and nothing is resent (`docs/guides/self-hosting.md`, "What recovery can and cannot
  do"). A database file you restore by hand must be writable by uid/gid `10001`: startup fixes the
  mount directory's ownership, not every file inside it.
- **Updating.** Blueprint: push, or deploy by hand. Prebuilt image: deploy the new tag. The
  sidecar migrates its schema forward on start.

## Verification record

Run on 2026-09-17 on an Apple silicon Mac (Docker Desktop, engine 20.10, `linux/arm64`):

- PASS: `docker build -f deploy/render/Dockerfile .` (648 MB).
- PASS: the smoke test above, including `caddy validate` of the shipped Caddyfile, MCP `200` with
  a real `initialize` under the right host and a bearer token, `PairingService` reachable under the
  right host (`401` without a credential), and a clean `SIGTERM` with exit `0`.
- PASS: with only `RENDER_EXTERNAL_URL` set, `/mcp` accepts that hostname and `render-pair` prints
  a pairing URL with it; an explicit `SIDECAR_PUBLIC_URL` wins; a missing or `http://` URL is
  refused before either child starts, with the reason in the log.
- PASS: `render-pair` from a root shell and from uid `10001` both work, and `/data` stays owned by
  `10001`.
- PASS: `docker buildx build --platform linux/amd64` on the same Mac (634 MB), after the
  dependency and build stages were pinned to `$BUILDPLATFORM` — before that, pnpm aborted under
  QEMU (`unexpected error when polling the I/O driver`). The amd64 image ran under emulation
  through the same smoke test: health, `401`/`200`/`404`s, `render-pair` with the derived URL,
  and exit `0` on `SIGTERM`. Pushed as `docker.io/brenat/seeker-agent-connect:render-v1`.
- NOT RUN: anything on Render itself — the disk's ownership as Render mounts it, its health
  probes, the Blueprint sync, and pairing a Seeker over an `onrender.com` name. Those need an
  account and a phone.

## Sources

- Blueprint specification: https://render.com/docs/blueprint-spec
- Deploying an image, and the amd64 requirement: https://render.com/docs/deploying-an-image
- Default environment variables (`PORT`, `RENDER_EXTERNAL_URL`): https://render.com/docs/environment-variables
- Persistent disks, one instance, no zero-downtime deploys: https://render.com/docs/disks
- Health checks: https://render.com/docs/health-checks
