# Releasing

How a component becomes something somebody can install without cloning this repository (SEE-168,
SEE-182).

Every component is released **independently**, under its own version and its own tag. One tag
publishes that component's artifacts and nothing else: its npm package, its GHCR image, or its
signed APK, built from the one commit the tag points at, plus a GitHub Release that records them.
[`release/components.json`](../../release/components.json) is where each version is decided;
everything else follows it.

> **Not part of this process:** the separate
> [`SeekerAgentConnect/do-deploy`](https://github.com/SeekerAgentConnect/do-deploy) repository and
> its deployments. Publishing a release here deploys nothing, and do-deploy keeps pulling the
> Docker Hub images it already references (`docker.io/brenat/seeker-agent-connect:<prefix>-<version>`),
> which stay where they are. Pointing a deployment at a GHCR image is a do-deploy change, made
> there, on its own schedule.

## The components

| Component | npm | Image | APK |
| -- | -- | -- | -- |
| `server-sdk` | `@seekeragentconnect/server-sdk` | — | — |
| `mcp-server` | `@seekeragentconnect/mcp-server` | `ghcr.io/seekeragentconnect/mcp-server` | — |
| `mcp-skr-staking` | `@seekeragentconnect/mcp-skr-staking` | `ghcr.io/seekeragentconnect/mcp-skr-staking` | — |
| `gateway` | — | `ghcr.io/seekeragentconnect/gateway` (Dockerfile target `console`) | — |
| `gateway-centrifugo` | — | `ghcr.io/seekeragentconnect/gateway-centrifugo` (context `services/gateway`) | — |
| `demo-signals` | — | `ghcr.io/seekeragentconnect/demo-signals` | — |
| `demo-prediction` | — | `ghcr.io/seekeragentconnect/demo-prediction` | — |
| `android` | — | — | `sac-<version>.apk` on the `android-v<version>` GitHub Release |

**Every component's `0.0.1` was published on 2026-10-07** from commit
[`f3521ff7`](https://github.com/SeekerAgentConnect/sac/commit/f3521ff7f3f0a05523453105ad5d09bcafa53cb5),
one tag each. The npm integrities, image digests, APK SHA-256 and signing certificate are recorded
in [docs/guides/installation.md § Published versions](../guides/installation.md#published-versions)
and on each [GitHub Release](https://github.com/SeekerAgentConnect/sac/releases), whose
`<!-- sac-release … -->` comment is the machine-readable record `scripts/release-state.mjs` reads
on a retry.

`packages/protocol` is deliberately **not** published on its own. Its generated types are the SDK's
public `@seekeragentconnect/server-sdk/protocol` entry. The root workspace, `packages/publisher-support`,
`tools/test-agent` and `tools/loadtest` are never published.

Images are built for `linux/amd64` and `linux/arm64`, with SBOM and provenance attestations, and
carry the OCI `source`, `revision` and `version` labels (and index annotations). The `source` label
is what links each GHCR package to this repository.

## Versions and release identity

**Every component's public baseline is `0.0.1`.** The earlier `0.2.0-rc.*` candidates belong to
the old `@seeker_agent_connect` scope (SEE-168, [docs/testing/see-168.md](../testing/see-168.md);
they no longer resolve on the registry), and the earlier images live in the Docker Hub repository
above. Both are kept as history in each component's `previousVersions` and `previousArtifacts` and
are never rewritten; nothing is republished under the old names, and the new scope has no version
below `0.0.1` to collide with. From `0.0.1` on, versions move independently: bumping
the SDK does not bump either MCP server, and a server's version is never required to match or
exceed the SDK's.

| Channel | Version | npm dist-tag | Image tags |
| -- | -- | -- | -- |
| Stable | `X.Y.Z` | `latest` | `X.Y.Z`, `sha-<commit>`, then `latest` |
| Prerelease | `X.Y.Z-rc.N` (`alpha`, `beta` too) | `next` | `X.Y.Z-rc.N`, `sha-<commit>` |
| Development (manual only) | — | never published | `dev-sha-<commit>`, `develop` |

- **A tag is the release.** `<component>-v<version>` — `server-sdk-v0.0.1`, `mcp-server-v0.0.1`,
  `android-v0.0.1`. There is no shared or global `v0.0.1`. The release workflow reads
  `release/components.json` **at the tagged commit**, and refuses a tag whose version disagrees
  with it.
- **One commit, one version, every artifact.** A component's npm tarball, image and GitHub Release
  share the tag's version and commit. The tarball's `package.json` carries the commit as `gitHead`;
  the image carries it as `org.opencontainers.image.revision`; the APK's release notes and record
  carry it too.
- **Aliases only move forwards.** A stable release moves npm `latest` and image `latest` only if no
  newer stable version holds them — retrying an old version, or two releases finishing out of
  order, leaves them on the newest. (npm needs some dist-tag for an older version published later,
  so it gets `latest-backport`.) A prerelease never moves `latest`.
- **First publication.** The npm registry points `latest` at whatever version of a package is
  published first. The first releases are stable `0.0.1`, so this changes nothing; if a package's
  first version were ever a prerelease, the workflow says so and the first stable release repoints
  it.
- **Development images** are isolated: `dev-sha-<commit>` and `develop` never collide with a
  release's `sha-<commit>`, and the development channel never publishes npm or an APK.

### What an MCP server bundles

Both MCP servers vendor the SDK's built output into their tarball and image
([`scripts/package-mcp-artifact.mjs`](../../scripts/package-mcp-artifact.mjs)), so they need no SDK
release to land first. Each artifact records exactly which SDK it carries, twice:

- `dist/vendor/server-sdk/package.json` — the SDK's name and version and a SHA-256 over the vendored
  files;
- the published manifest's `seekerAgentConnect.serverSdk`, readable without installing:

  ```bash
  npm view @seekeragentconnect/mcp-server@0.0.1 seekerAgentConnect
  ```

The package tests recompute the digest from the installed artifact and require it to be this
workspace's SDK, and the release's GitHub Release names the bundled SDK version. When an MCP
release should carry changed SDK bytes, bump **that server's** version and release it; nothing
forces it.

## Bumping a version

```bash
pnpm release:bump <component> <version>        # e.g. pnpm release:bump gateway 0.0.2
pnpm release:bump android 0.0.2                # versionCode advances by one
pnpm release:bump android 0.1.0 --version-code 10
pnpm release:bump server-sdk 0.1.0-rc.1        # a prerelease
pnpm check:release
```

[`scripts/release-bump.mjs`](../../scripts/release-bump.mjs) writes the new version into
`release/components.json`, the component's `package.json` (npm components), its runtime version
constant (`servers/mcp-server/src/version.ts`, `servers/mcp-skr-staking/src/server.ts`), and for
`android` advances `versionCode` and records the code it replaced in `previousVersionCode`. It edits
in place, so the diff is exactly the version lines. It refuses a version lower than the current one
unless `--allow-lower` is given — which is only for a deliberate new baseline like `0.0.1`.

`pnpm check:release` ([`scripts/check-release.mjs`](../../scripts/check-release.mjs)) then proves
the manifest, the package manifests, the version constants, the Android build, the Dockerfiles and
both workflows agree. It also runs the planner, retry-state, bump, Android-configuration and
release-workflow tests. None of them assume the versions the manifest currently pins: scenarios that
name a version use a pinned copy of the manifest, the live one is held only to what is true at any
version, and the bump tests bump each kind of component in a scratch repository and run
`check-release.mjs` on it — so a legitimate bump never fails the checks.

The Android build has no version of its own: `apps/android/app/build.gradle.kts` reads `versionName`
and `versionCode` from the manifest's `android` component, so rebuilding a commit always produces
the same code. `versionCode` is an explicit integer that only goes up — the published `0.0.1` is
`2`, above the `1` every earlier build carried — and the release workflow additionally refuses to
create or resume a release whose code is not above every other published `android-v*` release. A
release the tag already published is verified against its own record instead, so retrying an older
release after a newer one shipped keeps its original APK and does not move `latest`.

## Releasing one component

1. Bump (above), open a pull request, let CI pass, merge.
2. Tag the merge commit and push the tag:

   ```bash
   git switch master && git pull
   git tag server-sdk-v0.0.1
   git push origin server-sdk-v0.0.1
   ```

   The first releases were made exactly this way on 2026-10-07, one tag each, in any order; the
   same loop releases any set of components bumped together:

   ```bash
   for component in server-sdk mcp-server mcp-skr-staking gateway gateway-centrifugo \
                    demo-signals demo-prediction android; do
     git tag "$component-v0.0.1"
     git push origin "$component-v0.0.1"
   done
   ```

3. The push starts [`.github/workflows/release.yml`](../../.github/workflows/release.yml):

   | Job | What it does | Credentials |
   | -- | -- | -- |
   | `resolve` | Reads the manifest at the tagged commit; refuses a mismatched tag. | none |
   | `checks` | Runs the component's `checks` from the manifest (`pnpm check`, the package test, `pnpm check:gateway`, `pnpm check:android`, …). | none |
   | `npm-pack` | Builds, writes `gitHead`, packs **once**, audits the packed `package.json` (name, version, commit, public access, no `workspace:`/`catalog:`/`file:`), keeps the tarball for 7 days. | none |
   | `image-build` | Builds both platforms, pushes nothing. | none |
   | `android-build` | Checks the build configuration, builds the unsigned release APK, checks its id/versionName/versionCode, keeps it for 3 days. | none (variables only) |
   | `gate` | Passes only when `checks` and every build the component selects succeeded and this is a real run. | none |
   | `npm-publish` | Publishes **the audited tarball** with trusted publishing and provenance, then confirms the registry serves that integrity. | OIDC (`id-token: write`) |
   | `image-publish` | Pushes `X.Y.Z` and `sha-<commit>`, moves `latest` if allowed, then proves every tag pulls **anonymously** to the recorded digest. | `GITHUB_TOKEN` (`packages: write`) |
   | `android-publish` | Signs, verifies the certificate against the pin, creates a **draft** release, uploads APK + `SHA256SUMS`, publishes it, re-downloads the public asset and checks it. | signing secrets, `contents: write` |
   | `github-release` | Records an npm/image release as a GitHub Release (never marked latest), only once every publisher the component selects succeeded. | `contents: write` |

   Every component skips at least one build job, and GitHub's implicit `success()` treats a
   skipped ancestor as not successful, transitively
   ([actions/runner#2205](https://github.com/actions/runner/issues/2205)). So `gate`, each
   publisher and `github-release` state their status condition explicitly (`!cancelled() &&
   needs.gate.result == 'success' && …`), and
   [`scripts/release-workflow.test.mjs`](../../scripts/release-workflow.test.mjs) evaluates the
   workflow's job graph for every component to prove its publishers run.

   Each job's summary reports what it did: source commit, npm integrity and dist-tag, image digest
   and tags, APK SHA-256 and certificate, release URLs.

### Dry runs

A dry run builds, checks and audits everything for one component on any commit, and **logs in
nowhere, reads no secret, signs nothing and publishes nothing** — it never reaches `gate`. From the
Actions tab (**Release → Run workflow**, `dry_run` is on by default) or:

```bash
gh workflow run release.yml --ref master -f component=gateway -f dry_run=true
gh workflow run release.yml --ref my-branch -f component=android -f dry_run=true -f version=0.0.1
```

`version` is optional; when given, the run stops unless the manifest pins exactly that. An Android
dry run builds the unsigned candidate with whatever configuration is set, reports any feature the
official build would require but lacks, and says plainly that **signing was not verified**.

### Prereleases

```bash
pnpm release:bump mcp-server 0.1.0-rc.1
# merge, then
git tag mcp-server-v0.1.0-rc.1 && git push origin mcp-server-v0.1.0-rc.1
```

npm gets it under `next`; the image gets `0.1.0-rc.1` and `sha-<commit>`; neither `latest` moves.
An Android prerelease is published as a GitHub *prerelease* and never becomes the repository's
latest release.

### Development images

`gh workflow run release.yml --ref <branch> -f component=gateway -f channel=development -f dry_run=false`
pushes `ghcr.io/seekeragentconnect/gateway:dev-sha-<commit>` and `:develop`. It requires an image
component and never touches npm, an APK, a release tag or `latest`.

## Retrying a partial release

Registries are not transactional, so a release can stop halfway — npm published and the image push
failed, the image pushed but `latest` did not move, the APK uploaded to a draft that was never
published. A retry **finishes** a release; it never redoes or replaces a published part. Before
each publication step, [`scripts/release-state.mjs`](../../scripts/release-state.mjs) reads what is
already there:

| Already published | A retry |
| -- | -- |
| npm version, same `gitHead` (or identical integrity) | skips the publish as verified and reports the published integrity |
| npm version from another commit | stops: the version is immutable — bump instead |
| image `X.Y.Z` labelled with this version and commit | keeps it, re-points `sha-<commit>` at it, decides `latest`, re-checks anonymous pulls |
| image `X.Y.Z` from another commit, or unlabelled | stops |
| Android draft release for the tag | resumes it: replaces the draft's assets (nothing public yet), then publishes |
| Android published release from this commit | verifies the public APK against the recorded SHA-256 and certificate, changes nothing — also when a newer app release has shipped since: the older release keeps its APK and `latest` stays on the newer one |
| Android published release from another commit, or without its record | stops |

Absence is concluded only from an explicit not-found answer. A timeout, `5xx`, authentication
failure or unreadable reply fails the job; it is never taken to mean "not published".

To retry:

- **Re-run failed jobs** on the failed run (Actions UI, or `gh run rerun <run id> --failed`). The
  build artifacts of that run are reused, so the npm job publishes exactly the tarball that was
  audited.
- Or start a fresh run **from the tag**, which rebuilds and re-checks before finishing the missing
  steps:

  ```bash
  gh workflow run release.yml --ref gateway-v0.0.1 -f component=gateway -f dry_run=false
  ```

  A real manual run must be on the tag's own commit; from anywhere else it is refused. If the tag
  does not exist, a manual run cannot create a release — push the tag.

**Never** delete and re-push a tag whose artifacts were published, force a version over a published
one, or move `latest` by hand. Ship the next patch or `-rc.N` instead.

### Concurrency

Publication is serialised **per component and registry**: `release-npm-<component>`,
`release-image-<component>`, `release-android-android`, `release-github-<component>`. Two releases
of one component — different tags, or a tag and a manual retry — never publish to the same registry
at once, while different components release in parallel. A running publication is never cancelled.
GitHub keeps one pending run per group; if a third run of the same component queues behind a
pending one, the older pending run is cancelled before it starts — re-run it afterwards.

## One-time owner setup

These need the owner's accounts. Until they are done, the corresponding first publication fails
with an error naming the missing piece; nothing is published halfway and no version is consumed.

The `0.0.1` releases went through all of it: the three npm packages exist (with provenance), the
six GHCR packages are public, and the signed APK was published against the pinned certificate. What
follows is kept for a **new** package, image or signing setup — and for the two npm steps that only
the owner's account can confirm, trusted publishing and the bootstrap token's revocation (steps 3
and 4 below). The next npm release's publish job warns if it still had to use the bootstrap token.

### The `release` environment

**Settings → Environments → `release`** (created on first use if absent). The npm, GHCR and Android
publishing jobs run in it. Under **Deployment branches and tags**, allow tags matching `*-v*`, plus
any branch you dispatch development images from, if you restrict it. Required reviewers are optional; with them,
every publication waits for approval after all checks have passed.

### npm: organisation, first publication, trusted publishing

1. The npm organisation **`seekeragentconnect`** must exist and the publishing account must be an
   owner or a member with publish rights.
2. **First publication (bootstrap).** npm only lets a trusted publisher be configured on a package
   that exists, so each package's very first version — the real `0.0.1`, never a placeholder — is
   published with a temporary token:
   - create a **granular access token** on npmjs.com: *Read and write*, scoped to the
     `seekeragentconnect` organisation's packages, shortest expiry that covers the first releases;
   - store it as the secret **`NPM_BOOTSTRAP_TOKEN`** in the `release` environment;
   - push `server-sdk-v0.0.1`, `mcp-server-v0.0.1` and `mcp-skr-staking-v0.0.1`. The publish job
     warns that it used the bootstrap token, and still publishes with provenance.

   If a bootstrap publish fails (missing organisation, wrong token), nothing is published and
   `0.0.1` is not consumed: fix the account and re-run the failed job. Never publish under the old
   `@seeker_agent_connect` scope instead.
3. **Trusted publishing.** For each of the three packages, on npmjs.com → package → **Settings →
   Trusted publishing → GitHub Actions**: organisation `SeekerAgentConnect`, repository `sac`,
   workflow `release.yml`, environment `release`. Then set **Publishing access** to *Require
   two-factor authentication and disallow tokens*.
4. **Delete** the `NPM_BOOTSTRAP_TOKEN` secret and **revoke** the token on npmjs.com. From then on
   every publication uses OIDC only; the job holds no npm credential at all.

The publish job installs the npm CLI pinned in `release/components.json` (`toolchain.npm`,
currently `11.19.0`; trusted publishing needs 11.5.1 or later) and checks the version before use.

### GHCR: package visibility

Images are pushed with the workflow's own `GITHUB_TOKEN` (`packages: write`); no registry secret
exists. A package is created by its first push, linked to this repository through its `source`
label — and **created private**, whatever the repository's visibility. All six packages were
created and made public by the `0.0.1` releases; these steps are for a new image.

1. **Organisation settings → Packages → Package creation**: allow *Public* packages.
2. Push the first image tag (say `gateway-v0.0.1`). The image is pushed, then the **anonymous pull
   check fails** with a link to the package — expected, once per package.
3. Open the package → **Package settings → Change visibility → Public**. While there, confirm
   **Manage Actions access** lists `SeekerAgentConnect/sac` with the *Write* role (it does when the
   package was created by this workflow).
4. **Re-run the failed job.** It finds its own image, keeps its digest and passes the anonymous
   check.

Repeat per image: `mcp-server`, `mcp-skr-staking`, `gateway`, `gateway-centrifugo`,
`demo-signals`, `demo-prediction`. No Docker Hub credential is used by this repository any more;
the `DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN` secrets can be removed from it (do-deploy's own
credentials are untouched).

### Android signing

Updates install only over an app signed with the same key, so the release key is permanent.

- **The release key exists.** It signed `sac-0.0.1.apk`, and its certificate's SHA-256 is
  `3b29a71fb3c5ff63c9b693f1e9e1fdc945bfd5231a2c1ec1537f4f7ad2164f28` — the value of
  `SAC_ANDROID_SIGNING_CERT_SHA256`, and what every published APK is verified against. Use that key
  for every release (the `sac-release.jks` procedure in
  [swap-fee-config.md](swap-fee-config.md#sign-verify-and-install-the-release-apk)). Do not create
  another: an APK signed with a different key cannot update an installed app.
- **Only a fresh fork of the project creates one:**

  ```bash
  keytool -genkeypair -v -keystore sac-release.jks -alias sac-release \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=Seeker Agent Connect, O=SeekerAgentConnect"
  ```

  Back it up **before** first use: the `.jks` and both passwords in at least two separate offline
  or password-manager locations. A lost key means no installed copy can ever be updated; a leaked
  key means anyone can ship an update. Never commit it.

Then, in the `release` environment:

| Name | Kind | Value |
| -- | -- | -- |
| `SAC_ANDROID_KEYSTORE_BASE64` | secret | `base64 -w0 sac-release.jks` |
| `SAC_ANDROID_KEYSTORE_PASSWORD` | secret | keystore password |
| `SAC_ANDROID_KEY_ALIAS` | secret | `sac-release` |
| `SAC_ANDROID_KEY_PASSWORD` | secret | key password |
| `SAC_ANDROID_SIGNING_CERT_SHA256` | variable | the certificate's SHA-256, from `keytool -list -v -keystore sac-release.jks -alias sac-release` (colons optional) |

The keystore is restored only inside the signing step, with `umask 077`, and deleted as soon as
`apksigner` is done. The signed APK must verify with APK Signature Scheme v2 or v3 and its certificate must
equal the pinned SHA-256, or nothing is published. A real Android release with any of these missing
fails immediately and clearly; it never falls back to a debug key or an unsigned APK.

### Android build configuration

The official app's configuration is **client configuration**: every value is compiled into the APK
and readable by anybody who has it. It therefore lives in **repository variables** (Settings →
Secrets and variables → Actions → Variables), never secrets, and
[`scripts/release-android.mjs`](../../scripts/release-android.mjs) refuses what would make it a
leak.

| Variable | Feature | Notes |
| -- | -- | -- |
| `SEEKERVAULT_GOOGLE_SERVICES_JSON` | `firebase` — push | The **client** `google-services.json` from the Firebase console's Android app settings, for `io.github.brrenat.seekervault`. A service-account JSON is refused: it is the relay's/MCP server's server credential and never goes in an app. |
| `SEEKERVAULT_SOLANA_RPC_MAINNET`, `_DEVNET`, `_TESTNET` | each network's initial endpoint — account reads and transaction confirmation; `_MAINNET` satisfies `solanaRpc` | https, no username/password. A query-string key is warned about: it ships in the APK. The owner can replace each on the phone ([solana-rpc.md](../wiki/solana-rpc.md)). |
| `SEEKERVAULT_SOLANA_RPC` | legacy general endpoint; satisfies `solanaRpc` on its own | Same rules. Used for a network only after its genesis hash proves it; not needed beside `_MAINNET` (SEE-184). |
| `SEEKERVAULT_RELAY_URL` | `relayUrl` — push relayed for direct servers | An https origin. |
| `SEEKERVAULT_DISCOVERY_URL` | `discoveryUrl` — the Discover catalog | An https origin; unset means the relay's. |
| `SEEKERVAULT_SWAP_FEE_BPS`, `SEEKERVAULT_SWAP_FEE_OWNER`, `SEEKERVAULT_SWAP_FEE_ACCOUNTS` | SAC swap fee | Unset means no fee; validated by Gradle ([swap-fee-config.md](swap-fee-config.md)). A fee needs `SEEKERVAULT_SOLANA_RPC_MAINNET` (or the legacy `SEEKERVAULT_SOLANA_RPC`). |

`android.releaseRequires` in the manifest lists the features a **real** release must have —
currently `firebase`, `solanaRpc`, `relayUrl`. `solanaRpc` means a mainnet endpoint: the official
build's chain reads (prediction orders, the swap fee) are mainnet's, so `SEEKERVAULT_SOLANA_RPC_MAINNET`
alone satisfies it, and a general-only configuration still does. A real release missing one stops before building; a
dry run builds anyway and warns. The APK's release notes record the configuration it was built with.

## Installing and verifying a release

Installation, Compose and upgrade instructions are in
[docs/guides/installation.md](../guides/installation.md). The checks that matter:

```bash
# npm: the version, its commit, and its provenance
npm view @seekeragentconnect/server-sdk@0.0.1 version gitHead dist.integrity
npm view @seekeragentconnect/mcp-server@0.0.1 seekerAgentConnect   # the SDK it bundles
mkdir /tmp/sac && cd /tmp/sac && npm init -y >/dev/null && \
  npm install @seekeragentconnect/server-sdk@0.0.1 && npm audit signatures
npx --yes @seekeragentconnect/mcp-server@0.0.1 --version

# GHCR: anonymous pulls, pinned by digest
docker logout ghcr.io
docker pull ghcr.io/seekeragentconnect/gateway:0.0.1
docker buildx imagetools inspect ghcr.io/seekeragentconnect/gateway:0.0.1   # both platforms, digest
docker pull ghcr.io/seekeragentconnect/gateway@sha256:<digest>
docker inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' \
  ghcr.io/seekeragentconnect/gateway:0.0.1

# Android: checksum, certificate, install/update
gh release download android-v0.0.1 -R SeekerAgentConnect/sac
sha256sum -c SHA256SUMS
apksigner verify --print-certs sac-0.0.1.apk   # SHA-256 must match the release notes
adb install --replace sac-0.0.1.apk            # updates in place; same key, higher versionCode
```

The newest stable app is always at
<https://github.com/SeekerAgentConnect/sac/releases/latest>: only stable Android releases are
marked latest, so an npm or image release never displaces the APK link.

## What a pull request proves, and what only a live release can

**Pull requests** run [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) — on every pull
request and push to `master`/`develop`, with a read-only token and no secrets. It runs
`pnpm check:release`, every package test (the exact tarballs, installed outside the workspace,
exercised through their CLIs), the Go checks, both platforms of all six images (never pushed), the
Android checks, and the unsigned release APK's id/version check. `scripts/check-release.mjs` fails
if `ci.yml` ever contains a publish, login or write permission.

**Only the first real release** can prove what depends on the owner's accounts: that the npm
organisation and trusted publishers accept the publish, that each GHCR package is public, that the
signing secrets produce the pinned certificate, and that the official build configuration is
complete. Each of those is checked by the release itself — the npm integrity re-read, the anonymous
pull, the certificate pin, the public APK download — and reported in the run summary. The `0.0.1`
releases of all eight components passed them on 2026-10-07. The rule still holds for anything new —
a new package, image or component: until its first release has passed them, treat its artifacts as
unpublished.

## Compatibility

`mcp-server` and `mcp-skr-staking` each contain the SDK they were released with, so an operator
never matches an SDK version to a server version — the server's own version, and the SDK it records,
are the whole answer. A host application embedding `@seekeragentconnect/server-sdk` picks its own
version; the SDK's public entry points follow semantic versioning, so a breaking change to
`@seekeragentconnect/server-sdk` or `@seekeragentconnect/server-sdk/protocol` is a major bump (a
minor one while it is `0.x`).

Both MCP servers and the phone speak the wire protocol in `packages/protocol`, whose compatibility
rules are the protobuf ones and are independent of these versions. The images' runtime contract —
environment variables, ports, `/data`, uid/gid `10001`, health checks — is the one do-deploy relies
on and is unchanged by the move to GHCR.
