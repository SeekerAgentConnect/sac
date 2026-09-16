# gateway

The self-hosting stack: Docker Compose, the gateway in front of the sidecar, and the deployment's configuration.

- [`compose.yaml`](compose.yaml) — the stack. The sidecar and the gateway start; the test agent sits behind the `agent` profile and does not.
- [`Caddyfile`](Caddyfile) — the gateway's configuration.
- [`.env.example`](.env.example) — the deployment's settings. Copy to `.env` here, which git ignores.

Start it with `cp .env.example .env`, fill in the two tokens, then `docker compose up -d --build`. [`docs/guides/self-hosting.md`](../docs/guides/self-hosting.md) is the full guide, and [`docs/testing/stage-7.md`](../docs/testing/stage-7.md) records which checks have actually been run.

The sidecar binds loopback inside its own network namespace, which the gateway joins, so the only reachable way in is the gateway's published port. SAW-034 serves plain HTTP there; TLS is SAW-035 and the optional OAuth gateway is SAW-036.
