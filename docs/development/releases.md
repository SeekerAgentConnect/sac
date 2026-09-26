# Releasing

How a component becomes something somebody can install without cloning this repository (SEE-168).

Components are released **independently**. Each one has a version, and that one version names both
its npm package and its container image, built from the one commit the release tag points at.
`release/components.json` is where that version is decided; everything else follows it.

## The components

| Component | npm | Image | Replaces |
| -- | -- | -- | -- |
| `server-sdk` | `@seeker_agent_connect/server-sdk` | — | — |
| `mcp-server` | `@seeker_agent_connect/mcp-server` | `ghcr.io/seekeragentconnect/mcp-server` | `docker.io/brenat/seeker-agent-connect:mcp-*` |
| `mcp-skr-staking` | `@seeker_agent_connect/mcp-skr-staking` | `ghcr.io/seekeragentconnect/mcp-skr-staking` | `docker.io/brenat/seeker-agent-connect:skr-staking-mcp-*` |
| `gateway` | — | `ghcr.io/seekeragentconnect/gateway` | `docker.io/brenat/seeker-agent-connect:gateway-*` |
| `gateway-centrifugo` | — | `ghcr.io/seekeragentconnect/gateway-centrifugo` | `docker.io/brenat/seeker-agent-connect:centrifugo-*` |
| `demo-signals` | — | `ghcr.io/seekeragentconnect/demo-signals` | `docker.io/brenat/seeker-agent-connect:copytrading-*` |
| `demo-prediction` | — | `ghcr.io/seekeragentconnect/demo-prediction` | `docker.io/brenat/seeker-agent-connect:prediction-*` |

`packages/protocol` is deliberately **not** published on its own. Its generated types are the SDK's
public `@seeker_agent_connect/server-sdk/protocol` entry, which is the supported way to consume
them; a second package would be a second thing to keep in step for no consumer that exists yet.

`tools/test-agent` and `tools/loadtest` are development tools and stay unpublished.

### Why the scope is `@seeker_agent_connect`

SEE-168 specifies `@seekeragentconnect`. That npm organisation does not exist. The one this project
owns — and the one the release token can write — is **`@seeker_agent_connect`**, so that is the
published scope. Moving to `@seekeragentconnect` later means creating that organisation, publishing
the same versions under it, and pointing the old names at the new ones with a deprecation notice;
nothing in this repository assumes the underscore beyond `release/components.json`, which is the
one file that would change.

## Versioning

Semantic versioning, per component.

| Channel | npm | dist-tag | Image tags |
| -- | -- | -- | -- |
| Stable | `X.Y.Z` | `latest` | `X.Y.Z`, `latest`, `sha-<commit>` |
| Release candidate | `X.Y.Z-rc.N` | `next` | `X.Y.Z-rc.N`, `sha-<commit>` |
| Development | not published | — | `sha-<commit>`, `develop` |

A release candidate **never** moves `latest` on either registry. `npm install <name>` and
`docker pull <image>` keep resolving to the last stable release until a stable one replaces it.
`scripts/release-plan.test.mjs` is the test that says so, and the release workflow re-checks it
against the live registry after every prerelease publish.

There is one exception, and it is npm's, not ours: **a package's first published version always
becomes `latest`**, whatever `--tag` asks for, and npm refuses to delete a `latest` tag afterwards
(`npm dist-tag rm … latest` answers `403`). So a package whose first release is a candidate has
`latest` pointing at that candidate until the first stable version repoints it. The workflow logs
a notice when it sees this; there is nothing to fix at the registry, and the fix is to ship the
stable version. Every package released after its first one obeys the rule without qualification.

Every image carries `sha-<commit>`, so an image in a deployment can always be traced back to a
revision without reading its labels.

## Releasing

1. **Bump the version** in `release/components.json`, and in the component's `package.json` if it
   has one. Both, to the same value — `pnpm check:release` fails otherwise.
2. If the change is in `packages/server-sdk`, bump **`mcp-server` and `mcp-skr-staking` to the same
   version too.** They vendor the SDK's built output into their own tarballs and images, so an SDK
   release that leaves them behind ships consumers the old copy with no way to tell. The check
   enforces this.
3. Land the bump on the base branch and let CI pass.
4. Tag the merge commit and push the tag:

   ```bash
   git tag server-sdk-v0.2.0
   git push origin server-sdk-v0.2.0
   ```

   The tag format is `<component>-v<version>` using the identifiers in `release/components.json`.
   Pushing it starts `.github/workflows/release.yml`, which re-reads the manifest, refuses the
   release if the tag and the manifest disagree, re-runs the component's package audit against the
   tagged commit, and then publishes.

A dry run first, without a tag, is **Actions → Release → Run workflow** with the component's
identifier, `channel: release` and `dry_run: true`. It builds and audits everything and publishes
nothing. `channel: development` pushes only `sha-<commit>` and `develop` images and never touches
npm.

## What cannot publish

Pull requests cannot. `.github/workflows/ci.yml` builds the same artifacts and runs the same audits
on every run, but it contacts no registry and holds no credential — `scripts/check-release.mjs`
asserts the CI workflow contains no publish step, and that the release workflow is reachable only
from a tag or a dispatch.

A version that is already published cannot be republished. Both jobs check first — `npm view` for
the package, `docker buildx imagetools inspect` for the image — and stop before doing anything if
the version exists. Re-running a finished release is therefore a no-op that fails loudly rather
than a silent overwrite. The moving development tags (`sha-<commit>`, `develop`) are exempt, which
is what makes them moving tags.

## Access requirements

### npm

The `NPM_TOKEN` repository secret must be a granular token with **package: write** on the
`@seeker_agent_connect` scope. The publish job runs in the `release` GitHub environment, so the
secret can be scoped to it and given required reviewers.

npm **trusted publishing** (OIDC, no long-lived token) and build **provenance** are the better
mechanism and are not used yet for one concrete reason: provenance requires a public source
repository, and `BrRenat/SeekerAgentConnect` is private. When the repository becomes public,
configure each package's trusted publisher on npmjs.com, add `id-token: write` to the `npm` job,
add `--provenance` to the publish command, and drop `NPM_TOKEN`.

### GHCR

Images publish to `ghcr.io/seekeragentconnect`, the namespace of the **SeekerAgentConnect** GitHub
organisation.

This repository lives under the personal account `BrRenat`, not under that organisation. A
workflow's built-in `GITHUB_TOKEN` can only write packages in its own repository owner's namespace,
so it **cannot** push to the organisation. The release workflow therefore logs in with a
`GHCR_TOKEN` repository secret, which must be:

- a **classic** personal access token (fine-grained tokens cannot write GHCR),
- with the `write:packages` scope,
- held by an account with package-creation rights in the SeekerAgentConnect organisation.

The job stops with an explicit error if the secret is absent, rather than failing deep inside a
build.

Two things have to be done **once, by hand, in the GitHub UI**, because no API call in a workflow
can do them:

1. **Make each package public** after its first push. A package created from a private repository
   is private, and a private package is not installable by the external consumers this work exists
   for: *Organisation → Packages → the package → Package settings → Change visibility → Public.*
2. **Link each package to this repository**, so the GHCR page shows the source and the README:
   *Package settings → Manage Actions access / Connect repository.* The
   `org.opencontainers.image.source` label is already set on every image and does the same job for
   tools that read labels.

If this repository ever moves under the SeekerAgentConnect organisation, `GHCR_TOKEN` can be
deleted and the login switched to `GITHUB_TOKEN`; the `packages: write` permission the job already
declares is what it would use.

## Retrying a partial release

A release publishes to two registries. They can disagree — npm succeeds and the image push fails,
or the reverse. Nothing here is transactional, so the procedure is to finish the half that did not
happen, never to redo the half that did.

1. Read the failed run's job summary. Each job writes the artifact it published, its dist-tag or
   image tags, and the image digest.
2. **If npm succeeded and GHCR failed:** fix the cause, then **Actions → Release → Run workflow**
   with the component, `channel: release`, `dry_run: false`. The npm job stops on its own
   ("already on the registry") and the image job proceeds. That refusal is the design, not a
   problem to work around.
3. **If GHCR succeeded and npm failed:** the same dispatch. The image job refuses the existing tag
   and the npm job proceeds.
4. **If both failed before publishing anything:** delete and re-push the tag, or dispatch it.
5. **Never** force a version over one that is already published. A published npm version is
   immutable, and an overwritten image tag makes every recorded digest a lie. Bump to the next
   patch or the next `-rc.N` instead.

## Installing, upgrading, rolling back

Installation and Compose examples, including migration from the old Docker Hub names, are in
[docs/guides/installation.md](../guides/installation.md).

Roll back by pinning an exact version, never by moving a tag:

```bash
npm install @seeker_agent_connect/mcp-server@0.1.9
docker pull ghcr.io/seekeragentconnect/gateway@sha256:<digest>
```

Pin images **by digest** in anything that matters. A digest cannot be moved; `:0.2.0` can be, by
mistake, and the guard against that is a check in one workflow rather than a property of the
registry.

## Compatibility

`mcp-server` and `mcp-skr-staking` each contain the SDK version they were released with, so an
operator never has to match an SDK version to a server version — the server's own version is the
whole answer. A host application that embeds `@seeker_agent_connect/server-sdk` directly picks its
own version, and the SDK's public entry points follow semantic versioning: a breaking change to
`@seeker_agent_connect/server-sdk` or `@seeker_agent_connect/server-sdk/protocol` is a major bump.

Both MCP servers and the phone speak the wire protocol in `packages/protocol`, whose compatibility
rules are the protobuf ones and are independent of these versions.
