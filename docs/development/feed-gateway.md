# The feed gateway

The Go service in [`feed-gateway/`](../../feed-gateway): an authenticated publisher API and a feed
API that is anonymous for a public feed and, since SEE-156, needs an approved device's session for a
restricted one. SEE-130 removed the former invitation/device API for private server connections.
[`docs/wiki/feed-gateway.md`](../wiki/feed-gateway.md) is why it is shaped the way it is;
this page is how to run it, what its settings do, and where its code and tests are.

It is not the optional direct ingress in [`deploy/ingress/direct/`](../../deploy/ingress/direct),
which is one owner's independently managed edge in front of their MCP server. Different service,
different operator, different deployment.

## Running it

The toolchain is Go alone — the version in [`feed-gateway/go.mod`](../../feed-gateway/go.mod), which is
also what CI reads ([`docs/development/toolchain.md`](toolchain.md)).

```sh
cd feed-gateway
go build ./...
go test ./...
```

From the repository root, `pnpm check:feed-gateway` runs the formatting check, `go vet` and the tests —
the same command CI runs. It is separate from `pnpm check` because that one must not need Go.

Natively, with a database in the current directory:

```sh
go run ./cmd/feed-gatewayctl register --database ./broadcast.db \
  --server 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d --label "local"

BROADCAST_PUBLIC_URL=http://127.0.0.1:8090 \
BROADCAST_DATABASE_PATH=./broadcast.db \
go run ./cmd/feed-gateway
```

The credential is printed once. A publication then looks like this — the JSON codec is strict, so a
misspelled field is an error rather than something quietly dropped:

```sh
curl -sS http://127.0.0.1:8091/seekervault.gateway.v1.PublisherService/PublishManifest \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $CREDENTIAL" \
  -d '{"manifest":{"serverId":"3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d","protocolVersion":1,
       "settingsRevision":"1","mode":"CONNECTION_MODE_GATEWAY_FEED",
       "environments":["SERVER_ENVIRONMENT_PRODUCTION"],
       "feed":{"gatewayUrl":"http://127.0.0.1:8090",
               "channel":"server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"}}}'
```

and a read of a public feed needs no credential at all:

```sh
curl -sS http://127.0.0.1:8090/seekervault.gateway.v1.FeedService/ListRequests \
  -H 'Content-Type: application/json' \
  -d '{"channel":"server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"}'
```

`PublishRequest`/`CancelRequest` and `ListRequests`/`GetRequest` are the primary SEE-108
operations. The proposal operations remain compatibility adapters over the same rows and sequence.

In Docker, copy `deploy/feed/.env.example` to `deploy/feed/.env`, start
`deploy/feed/compose.yaml`, then register through the `gateway-ctl` operator profile. Public HTTPS
is the separate `deploy/ingress/feed` project, so plain HTTP cannot become the public default by
omission. The exact commands are in [`feed-gateway/README.md`](../../feed-gateway/README.md).

## Configuration

Every setting is one environment variable, a problem names the variable rather than guessing a
value, and every problem is reported at once — the sidecar's own rules
([`internal/config`](../../feed-gateway/internal/config)). Nothing here has a default that opens
something.

| Variable | Default | What it is |
| --- | --- | --- |
| `BROADCAST_PUBLIC_URL` | none; required | This gateway's own origin, as a phone's feed reference spells it. Every published manifest must name exactly this |
| `BROADCAST_DATABASE_PATH` | one of the two is required | The SQLite file. It is the authority for everything served |
| `BROADCAST_DATABASE_URL` | one of the two is required | A Postgres URL instead of a file (SEE-145), for a deployment whose filesystem does not survive the container. Setting both is refused at startup |
| `BROADCAST_READ_ADDRESS` | `127.0.0.1:8090` | Where the feed API listens: anonymous for a public feed, and a session from a live grant for a restricted one (SEE-156) |
| `BROADCAST_PUBLISHER_ADDRESS` | `127.0.0.1:8091` | Where the publisher API listens. It must differ from the read address |
| `BROADCAST_RETENTION_HOURS` | 168 | How long past its own expiry a proposal is still served |
| `BROADCAST_MAX_PROPOSALS` | 200 | The most proposals one channel may hold at once |
| `BROADCAST_HEARTBEAT_SECONDS` | 30, range 5–3600 | How often a publisher is asked to say its own server is running (SEE-150). `PublisherService.Heartbeat` answers with it, and a feed is shown online until three of these have passed with no authenticated call from its publisher. The window is three intervals and is not configurable on its own: one shorter than the interval would show every feed offline for ever |
| `BROADCAST_MAX_GRANT_HOURS` | 24, range 1–720 | The longest a restricted feed's grant may run without its publisher renewing it (SEE-156). It is the bound on how long an approved device keeps access when its publisher cannot reach this gateway to revoke it, it is what `DescribeAccess` answers, and a longer grant is shortened to it rather than refused |
| `BROADCAST_READ_RATE`, `BROADCAST_READ_BURST` | 20, 60 | Reads per second per caller, and the burst |
| `BROADCAST_PUBLISH_RATE`, `BROADCAST_PUBLISH_BURST` | 2, 20 | Publications per second per publisher, and the burst |
| `BROADCAST_STREAM_URL`, `BROADCAST_STREAM_API_KEY`, `BROADCAST_STREAM_TOKEN_KEY` | unset | The broker and its two keys (SEE-91). All three or none: without them the gateway answers every read and says once that there is no stream |
| `BROADCAST_TICKET_MINUTES`, `BROADCAST_MAX_CHANNELS` | 60, 32 | How long a listener's ticket lasts, and how many channels one grants |
| `BROADCAST_PUSH_CREDENTIALS`, `BROADCAST_PUSH_ENDPOINT`, `BROADCAST_PUSH_ENVIRONMENT` | unset | The push relay (SEE-92): the service account file, the push API, and `production` or `sandbox`. All three or none |
| `BROADCAST_PUSH_RATE`, `BROADCAST_PUSH_BURST` | 0.1, 5 | Hints per topic per second, and the burst. Above it a hint is dropped rather than queued |
| `BROADCAST_ADMIN_PASSWORD_HASH` | unset | The operator's password, as `feed-gatewayctl password` prints it (SEE-141). Setting it is the only thing that builds the admin surface; without it there is no listener and no route |
| `BROADCAST_ADMIN_ADDRESS` | `127.0.0.1:8092` | Where the admin page listens. It must differ from the read and publisher addresses |
| `BROADCAST_ADMIN_PATH` | `/admin` | The route prefix it is served under. Canonical: absolute, no trailing slash, no query, no relative segment |
| `BROADCAST_ADMIN_SESSION_MINUTES` | 60, range 5–1440 | How long a login lasts, absolutely |
| `BROADCAST_ADMIN_LOGIN_RATE`, `BROADCAST_ADMIN_LOGIN_BURST` | 0.1, 5 | Login attempts per second per caller, and the burst |
| `BROADCAST_ADMIN_PUBLISHER_URL` | `BROADCAST_PUBLIC_URL` | The publisher API address the admin page hands a developer, when publications arrive somewhere other than the public origin |

The admin group is all-or-nothing like the stream and the relay: a setting with no password behind
it is a startup problem rather than a line that does nothing, with one exception — declaring
`BROADCAST_ADMIN_PASSWORD_HASH` and leaving it **empty** keeps the whole group inert, which is what
lets a deployment template write the group down where an operator can see it and turn it on with one
secret (`deploy/feed/compose.yaml`, `deploy/seeker-gateway.yaml`).

**There is no publishing credential in the configuration.** A publisher's credential is created by
`feed-gatewayctl` or by the admin page and kept as a SHA-256, so there is nothing in the environment,
a process list or a compose file for one to leak from. The operator's own password is the one secret
the environment carries, and it carries it as a PBKDF2 hash that cannot be turned back into it. SEE-92's push credential is the one thing that has to be usable
rather than compared, and it is still not in the environment: what is configured is a **path**, the
file is mounted read-only into the gateway alone (`deploy/feed/compose.push.yaml`), and it is read once at
startup — a missing or malformed one stops the process with a message that names the field and no
part of its contents.

`BROADCAST_PUBLIC_URL` is the one setting with no error message on this side if it is wrong:
publications succeed, and every phone refuses the manifest because the origin is not the one it
added the feed from (SEE-88). It is canonicalized the way the phone canonicalizes one — HTTPS, or
plain HTTP on loopback for development; lowercase scheme and host; no default port; no path at all —
so `https://Feeds.Example.com:443/` and `https://feeds.example.com` are the same origin and a URL
with a path is refused at startup.

## The operator's tool

`feed-gatewayctl` is local, and registering a publisher is the one act that grants the ability to
publish. There is still no publisher- or phone-facing API to authenticate against; since SEE-141 the
operator has a **second** surface for the same operations, [the admin page](#the-operators-admin-page),
and the two share this store and these semantics rather than shelling out to each other.

| Command | What it does |
| --- | --- |
| `register --server <uuid> --label <note> [--host <url>] [--access public\|restricted --auth-origin <origin>]` | Registers a publisher and prints one credential, once. Refuses an identity that is already registered. Omitting `--access` registers a public feed, which is what every registration was before SEE-156 |
| `access --server <uuid> --access public\|restricted [--auth-origin <origin>]` | Changes who may read a feed, and where its subscribers prove who they are (SEE-156). The policy is required here |
| `rotate --server <uuid> [--label]` | Adds a second credential, so the first can be retired without an outage |
| `revoke --credential <id>` | Ends one credential, named by the handle `list` prints |
| `revoke --server <uuid> --all` | Ends every credential a publisher holds. Its documents stay |
| `list [--server <uuid>]` | What is registered, or one publisher's credentials |
| `forget --server <uuid> --yes` | Removes a publisher and everything it published |
| `password [--password <text>]` | Reads a password from standard input and prints the hash to configure as `BROADCAST_ADMIN_PASSWORD_HASH`. Touches no database, so it works before one exists |

`--database`, or `BROADCAST_DATABASE_PATH`, or `BROADCAST_DATABASE_URL`, must be the database the
gateway reads — a Postgres URL is recognized by its scheme and anything else is a file. Pointing the two at
different files is the one mistake that looks like a credential that does not work. The tool can run
while the gateway is up, and what it changes the admin page sees immediately, because there is one
file and one set of rules.

`--host` records the developer's own base URL beside a registration. It is administrative metadata:
the gateway never fetches it, no phone is told to contact it, and claiming a host grants nothing.

`--access` and `--auth-origin` are the registration of a restricted feed (SEE-156). A restricted one
must name the origin its subscribers authenticate at, validated exactly as `BROADCAST_PUBLIC_URL` is
— it is the one address a phone will send a wallet proof to, and the phone trusts it because this
registration vouches for it. A public feed must name none. `rotate` refuses both, because rotation
adds a credential and changes nothing else about a server, and `list` prints a restricted feed's
policy, its origin and how many grants are live — a count, never a list of anyone. The `access`
command prints that every stream name issued under the old policy is retired and that the publisher
should publish its manifest again, because the manifest must state the policy this registration
holds.

## The operator's admin page

[`internal/admin`](../../feed-gateway/internal/admin), on its own listener, off unless
`BROADCAST_ADMIN_PASSWORD_HASH` is set. [`docs/wiki/feed-gateway.md`](../wiki/feed-gateway.md#the-operators-admin-page)
is why; this is what it is made of.

| File | What is in it |
| --- | --- |
| `admin.go` | The routes, the guard that authenticates and checks CSRF before any handler runs, and the six actions — register (with its access policy), set access, rotate, revoke one, revoke all, forget. `setAccess` is `feed-gatewayctl access` on the page (SEE-162): same rules, the server ID typed back to confirm a change, and a request for the policy already held writes nothing |
| `session.go` | Server-side sessions: an opaque random token in the cookie, stored as its SHA-256, absolute expiry, a bounded count, real logout, and the one-shot hold a new credential is revealed from |
| `pages.go` | The view models and embedded templates and assets, including the authenticated shell’s server count and the MIME types of the bundled fonts. `accessOf` is the page's copy of the CLI's `--access` / `--auth-origin` rules, over the same `config.Origin`. `PublisherRow.Credentials()` and `.Published()` are the only place the operator-facing state words are decided |
| `host.go` | What may be recorded as a publisher's host, and what may be rendered as a link |
| `templates/` | The login, responsive authenticated shell, registered-server table, Add-server drawer, server detail and one-time credential page. Forms keep the server-rendered CSRF and validation boundary; the custom dialog changes how destructive confirmation is presented, not what the server requires |
| `assets/admin.css` | The dark/lime responsive presentation from SEE-164, including the desktop sidebar, narrow stacked shell, scrolling data tables, drawer and in-page confirmation dialog |
| `assets/admin.js` | Progressive browser interaction for copying values, opening and closing the Add-server drawer, enabling typed confirmation and presenting revoke/forget confirmations without `window.confirm`. Mutations remain ordinary guarded form posts |
| `assets/roboto-variable.ttf`, `assets/roboto-mono-variable.ttf` | Roboto and Roboto Mono copied from the checked-in Android design-system assets, served by the gateway itself. The admin CSP permits fonts only from `self`; there is still no CDN |

It takes `Limiter` and `Caller` as injected seams rather than importing `internal/gateway`, which
would be a cycle: the gateway builds this surface and supplies its own token bucket and its own
trusted-proxy policy, so a login is counted against the same caller identity a read is.

Two things live outside it because more than one surface needs them:
[`internal/credential`](../../feed-gateway/internal/credential) is the one place a publishing
credential is minted, hashed and named **and** the one place a password is stretched (PBKDF2-HMAC-SHA256,
`crypto/pbkdf2`, encoded dot-separated so a `$` is never eaten by Compose's interpolation); and
`storage.PublisherAdminStore` is the one administration boundary, which the CLI and the page both
call.

## Restricted feeds (SEE-156)

Who may read a feed is **this operator's registration** and nothing else: not the link the owner
scanned and not the publisher's manifest, which the gateway stamps from the registration and refuses
when it claims anything else. [`docs/wiki/restricted-feeds.md`](../wiki/restricted-feeds.md) is why
it is shaped this way, [`docs/protocol.md`](../protocol.md#restricted-feeds-see-156) is the wire
contract, and this is the operator's side of it.

```sh
go run ./cmd/feed-gatewayctl register --database ./broadcast.db \
  --server 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d --label "copy trading" \
  --access restricted --auth-origin https://auth.example.com

go run ./cmd/feed-gatewayctl access --database ./broadcast.db \
  --server 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d --access restricted \
  --auth-origin https://auth.example.com
```

**What the schema holds.** SQLite version 7 and Postgres version 3 add the same three columns and
the same table ([`internal/storage/sqlite/store.go`](../../feed-gateway/internal/storage/sqlite/store.go),
[`internal/storage/postgres/store.go`](../../feed-gateway/internal/storage/postgres/store.go), where
the reasoning is written out once):

| Where | What |
| --- | --- |
| `publisher.access_policy` | `public` or `restricted`, defaulting to `public` |
| `publisher.auth_origin` | Where a restricted feed's subscribers prove who they are; empty for a public one |
| `publisher.access_epoch` | Which of the channel's stream names is current. It moves on every revocation and on every change of policy |
| `access_grant` | One row per approved device: `grant_id`, `server_id`, `subscriber_ref`, `device_ref`, `session_digest`, `created_at_ms`, `renewed_at_ms`, `expires_at_ms`, `revoked_at_ms`, `push_target`, with `access_grant_by_server` and `access_grant_by_expiry` over it |

The two references are opaque labels the publisher chose and this gateway never interprets, the
digest is the SHA-256 of a session it never holds, and `push_target` is the one value that cannot be
a hash because delivery needs it — it is kept against that grant, used only for that grant's hints,
and dropped with it. There is no wallet, no address, no signature, no device name and no reason for
a decision in any of it. `boundary_test.go` lists the live tables and fails if anything else
appears.

**Migrating an existing deployment changes no feed's behaviour.** Every registration that existed
before v7 becomes exactly what it was — `public`, with no origin and epoch 0 — and a public feed's
manifest is byte for byte what it was, because a public feed carries no `FeedAccess` at all. Nothing
is written to `access_grant` for one. The migration runs on open like every other, and a file
written at v7 is refused by an older binary (`ErrNewerSchema`) rather than read with a column its
rules do not know about. The Postgres lineage is its own: version 3 there, with the new table sealed
under row-level security like the others.

**Where enforcement runs.** All of it is the gateway's, and none of it is optional:

| Code | What it does |
| --- | --- |
| [`internal/gateway/access.go`](../../feed-gateway/internal/gateway/access.go) | `admit` is the one check: a live grant for this server's channel, or `ACCESS_REQUIRED`, `ACCESS_REVOKED`, `ACCESS_EXPIRED` in the order a phone acts on them. `stamped` writes the registration's policy onto a served manifest, `declaredAccessFits` refuses a publisher that claims another, and `SetFeedPushTarget`, `DescribeAccess`, `GrantAccess` and `RevokeAccess` live here |
| `internal/gateway/feed.go` | Every snapshot page, point read and legacy proposal view authorizes before it reads anything about the channel, a sequence included |
| `internal/gateway/ticket.go` | A restricted channel is granted only for a live grant, is left out otherwise as an unknown channel is, and the ticket is cut to the shortest grant it carries (`GrantWithin`) |
| `internal/gateway/topics.go` | A restricted channel is always left out: it has no public topic |
| `internal/gateway/presence.go` | A restricted channel with no live session is absent from the status answer |
| `internal/gateway/publisher.go` | `PublishManifest` reads the policy inside the publication's own transaction, so a manifest is stamped with what is in force when it is stored |
| `internal/storage/sqlite/access.go`, `internal/storage/postgres/access.go` | Every statement is scoped to one server in the statement itself, so a session for one channel matching another, or a publisher touching another's grants, is impossible rather than remembered. `RevokeGrants` ends the grants, moves the epoch and writes the retiring notice in one transaction |
| [`internal/stream/stream.go`](../../feed-gateway/internal/stream/stream.go) | `RestrictedStreamChannel` is the channel's name at one epoch, so publications after a revocation go out under a name the old listener is not on |
| `internal/dispatch/dispatch.go` | Reads the policy when a notice is sent rather than when it was written, so a publication that waited through a revocation goes out under the new name; the `access` notice kind carries the retired epoch and becomes an empty `AccessChanged` |
| [`internal/relay/restricted.go`](../../feed-gateway/internal/relay/restricted.go) | A restricted channel's hints go to the live grants' own targets, read fresh at send time, and the devices whose grants just ended are told once, best effort |

Two operational notes. A deployment that relays nothing answers `SetFeedPushTarget` with
`GATEWAY_PROBLEM_NO_PUSH`, exactly as it answers `GetFeedTopics`; restricted feeds otherwise work
without the relay, because nothing about access depends on push. And grants that ended — revoked, or
expired without renewal — are kept for `BROADCAST_RETENTION_HOURS` past the end and then swept, so a
late renewal of a revoked grant is refused by name rather than by absence.

## Code

| Path | What is in it |
| --- | --- |
| `cmd/feed-gateway` | The server: configuration, the store, two isolated listeners, the drainer, the sweep, and an orderly shutdown |
| `cmd/feed-gatewayctl` | The operator's tool, and its tests — which also pin that the tool and the gateway agree about what a credential is |
| `internal/config` | The environment, validated, and the canonical form of a gateway origin |
| `internal/rules` | What the gateway accepts, as pure functions: the document rules, the ordering rules, and what a withdrawal leaves behind. The phone's own rules, on this side |
| `internal/storage` | The durable contracts used by publication, reads, delivery, maintenance, and the local operator tool. Business code depends on these interfaces and contains no SQL and no import of either implementation |
| `internal/storage/postgres` | The second implementation of those contracts (SEE-145), for a deployment with no durable filesystem. Its own schema lineage, its own tables in a non-public schema with row-level security on, and one transaction-scoped advisory lock per write, which is SQLite's single-writer rule made to hold across processes |
| `internal/storage/sqlite` | The only place that speaks SQL: the publication/configuration/outbox tables, transaction ownership, the one-way schema-v3 retirement migration, v4's added publisher host column, and v7's access policy and `access_grant` (SEE-156) |
| `internal/gateway` | Feed and authenticated publisher handlers; credential interceptors, limiters, cursors, the strict JSON codec and boundary tests; `access.go` holds every restricted-feed check and the publisher's grant methods (SEE-156). It also composes the admin surface and opens its listener when one is configured |
| `internal/admin` | The operator's browser administration (SEE-141): routes, sessions, CSRF, server-rendered pages, responsive CSS and browser interactions, plus the bundled Roboto and Roboto Mono assets added by SEE-164. Built only when a password is configured |
| `internal/credential` | Minting, hashing and naming a publishing credential, and hashing and verifying the operator's password. One algorithm, so the CLI and the page cannot drift |
| `internal/dispatch` | The outbox drainer, its backoff, `Dispatcher`, and the event envelope every subscriber receives |
| `internal/stream` | The broker (SEE-91): publishing an event over its server API, and minting the ticket a listener connects with — bounded by a grant, and named for a restricted channel's access epoch, since SEE-156. One of the two packages that open a connection, and it takes the address from the operator |
| `internal/relay` | The push relay (SEE-92): one content-free hint per changed feed, the topic it goes to, the quota that bounds how often a feed's subscribers are woken, and the service-account grant it is sent with; `restricted.go` sends a restricted channel's hints to its live grants' own targets instead of a topic (SEE-156). The other package that opens a connection, and it takes both addresses from its operator — one from the environment, one from the credential document |
| `internal/gen` | Generated from `proto/`, committed, and never edited by hand |

## Tests

`go test ./...` covers the service contract and its retirement boundary. Nothing in the gateway
tests is mocked that the binary does not also use.

The Postgres store's tests need a real Postgres and skip without one, because what they check is
that this dialect answers the same questions SQLite does and the only thing that knows is the
database:

```sh
docker run -d --name gateway-pg -e POSTGRES_PASSWORD=test -e POSTGRES_DB=gatewaytest \
  -p 55432:5432 postgres:17-alpine

BROADCAST_TEST_DATABASE_URL='postgres://postgres:test@127.0.0.1:55432/gatewaytest?sslmode=disable' \
  go test ./internal/storage/postgres/
```

**Point it at a throwaway database.** Each case truncates every table so it starts from nothing, so
a URL naming a deployment's own database would empty it.

Beside them, `pnpm test:integration` runs this **binary** against both publisher templates and two
subscribers, with a privacy sweep of everything the run wrote (SEE-98,
[`integration.md`](integration.md)). It is where the public and publisher listeners' separation, a
forgotten publisher, a revoked credential and a restart on the same database are checked as a
deployment rather than as a handler. `boundary_test.go` proves every removed private procedure and
hosted invitation route is absent from both surviving listeners.

| File | What it holds |
| --- | --- |
| `internal/rules/rules_test.go` | Every document rule with its own answer, what a revision means, that a document is rebuilt rather than relayed, and that the environments a server ID published cannot move while the order they were written in does not matter (SEE-97) |
| `internal/storage/sqlite/store_test.go` | The schema, credentials and rotation, a publication and its notice committing together, a notice surviving a stop, paging order, retention, forgetting a publisher, the restart-safe schema-v2-to-v3 retirement that preserves every public table while dropping private routing rows, and the v3-to-v4 host column added to a database written before it without losing a row |
| `internal/config/config_test.go` | The two settings with no default, the ranges, what cannot be an origin, and the admin group: off without a password, inert when the password is declared empty, and every way it can be misconfigured |
| `internal/admin/admin_test.go` | Who is refused — an anonymous visitor, a real publishing credential, a form with no token, a cross-site post, an expired session — and what an operator can do: register, the one-time reveal, a refused duplicate that writes nothing, additive rotation, revocation, destructive removal behind a typed confirmation, the security headers, the assets, and that nothing outside the prefix exists |
| `internal/gateway/admin_test.go` | The wiring, against the running service: a publisher registered in the browser publishes on its next request with nothing restarted, the CLI and the page see the same registrations, a revocation is enforced immediately while another publisher keeps publishing, the route is absent from both other listeners, and an unconfigured gateway has no surface at all |
| `internal/dispatch/dispatch_test.go` | Delivery, failure and retry, duplicate delivery after a sent-but-unacknowledged notice, a publication landing mid-flight, a document swept while its notice waited, and backoff |
| `internal/gateway/publish_test.go` | Two publishers that cannot reach each other, credentials and rotation, refusing a redirection, refusing a promotion to production (SEE-97) while what a subscriber reads stays as it was, retries and conflicts, withdrawal, the channel bound, rate limits, and a restart that still owes a fan-out |
| `internal/gateway/publisher_storage_test.go` | A fully evaluated publication whose storage commit fails answers failure, wakes no fan-out, and leaves neither document nor notice visible |
| `internal/gateway/http_publication_test.go` | A plain HTTP client, with no generated binding, publishes a manifest, creates and updates a feed item, withdraws it, and reads the authoritative result |
| `internal/gateway/read_test.go` | A phone reading a feed with no credential, a walk that stays stable while the feed moves, the caching answers, what a reader cannot ask for, and expiry and retention |
| `internal/gateway/privacy_test.go` | The three ways to try to submit something about a person, the read listener's lack of any write, that no credential reaches a log line, and that reading writes nothing down |
| `internal/gateway/boundary_test.go` | No HTTP client in shipped business code, no store implementation imported outside composition/storage tests, SQL only in `internal/storage/sqlite` and `internal/storage/postgres`, pure rules, no provider named, the live schema tables, the contract's fields and reservations, two listeners only, no listener serving another role's procedures, and 404s for every retired private RPC and invitation route |
| `internal/gateway/fixtures_test.go` | The committed cross-runtime fixtures are what the gateway actually answers |
| `internal/gateway/internal_test.go` | Page tokens, the limiter's arithmetic and bound, who a call is counted against, and that every problem has a code |
| `internal/gateway/restricted_test.go` | A restricted feed serving only its onboarding metadata without a grant, an approved device reading every path and listening, one device revoked while the others keep reading, a wallet-wide revocation and a bad batch that ends none, refused cross-publisher grants and revocations, a grant expiring unless it is renewed within the bound, a manifest stamped with the registration rather than the publisher's claim, decisions surviving a restart, that a restricted read writes nothing down, that a hint reaches only live grants' targets, and a push target on a gateway with no relay |
| `internal/gateway/ticket_test.go` | Which channels a listener is granted, which are left out, what is refused, and that asking to listen writes nothing down |
| `internal/gateway/topics_test.go` | Which channels are named a topic, which are left out, what is refused, that asking writes nothing down, and that a publisher cannot cause a hint about another feed |
| `internal/stream/stream_test.go` | What a publication carries, that a retry is one publication, and that every refusal is a failure to retry rather than a delivery |
| `internal/stream/ticket_test.go` | The claim set, exactly: an empty subject, an expiry, the channels — and a signature that verifies the way the broker verifies it. Also a restricted ticket that ends with its grant while staying anonymous, and a stream name that moves with its epoch (SEE-156) |
| `internal/stream/broker_test.go` | The same publication against a **real** Centrifugo with the shipped configuration. Opt-in: `SEEKERVAULT_CENTRIFUGO=/path/to/centrifugo go test ./internal/stream/ -run TestBroker` |
| `internal/relay/relay_test.go` | What a hint carries — and, in bytes, what it does not — the topic's derivation, the quota, the one retry after a refused token, and that no failure is ever reported to the drainer |
| `internal/relay/token_test.go` | The assertion's claim set exactly, its signature verified with the public half of the key, the grant flow, the caching and its margin, and that no error quotes a credential |
| `internal/relay/firebase_test.go` | One **real** hint to a real project, which is the only way to know Google accepts this message. Opt-in: `SEEKERVAULT_FCM_CREDENTIALS=... SEEKERVAULT_FCM_SERVER=... go test ./internal/relay/ -run Firebase` |

The phone's side of the same contract is `GatewayProtocolFixturesTest`, which reads the same fixture
files — including the three `FeedEvent` ones taken from the gateway's own outbox — and requires the
phone's validators to accept what is in them.

### The stream, end to end

Two tests on the phone's side finish the loop, and one of them needs services:

| Where | What it needs | What it proves |
| --- | --- | --- |
| `feeds/UniStreamInteropTest` | nothing (always runs) | the phone's generated client and adapter against real gRPC framing over TLS and HTTP/2: the request it sends, the pushes it decodes, the codes it classifies |
| `feeds/CentrifugoStreamIntegrationTest` | a Centrifugo and a Redis binary | two real nodes sharing Redis: cross-node delivery, recovery from a cursor, a history gap, an epoch change, a duplicate, a node shutting down, and a listener that cannot keep up |
| `feeds/ConnectFeedTopicsTest` | nothing (always runs) | the phone asking where hints arrive, over real Connect bodies: the channels it sends, and that a topic for a channel nobody asked about is refused |
| `push/FeedTopicManagerTest`, `push/SeekerVaultMessagingServiceTest`, `sync/FeedSynchronizationTest`, `notifications/FeedNotificationsTest` | nothing (always run) | the phone's whole hint path (SEE-92): subscribing and unsubscribing, the two kinds of invalidation, the bounded read and what it skips, and the alert with its read-only route |
| `push/FeedHintContractTest` | nothing (always runs) | that the phone and the relay still agree on what a hint is — it reads the relay's own Go source, because a drift here would be silence rather than an error |

```sh
android/gradlew -p android :app:testDebugUnitTest \
  --tests 'io.github.brrenat.seekervault.feeds.CentrifugoStreamIntegrationTest' \
  -Dseekervault.centrifugo=/path/to/centrifugo \
  -Dseekervault.redis=/path/to/redis-server
```

Both skip cleanly when the binaries are not named, which is why CI runs everything else. What is
left after them is the device run: a real phone, a real certificate, one origin
(`docs/testing/stage-7-1.md`).

## Deployment

The application image remains in this module. Canonical orchestration is in `deploy/feed`; optional
public HTTPS/HTTP2 is a separately operated `deploy/ingress/feed` project.

Three details matter:

- **The runtime image is minimal.** The binary is static; the CA bundle added for SEE-92's optional
  push connection is the only runtime support file.
- **There is no healthcheck in the gateway's container**, because a scratch image has no shell to
  probe itself with. The optional ingress checks the private read listener.
- **The optional ingress routes two upstreams, or three**: `FeedService` to the public read listener,
  `PublisherService` to the backend listener, and — when the operator keeps the block — `/admin*` to
  the admin listener. An operator may keep publisher RPCs private while exposing public feed reads,
  and may delete the admin block entirely and reach the page over a tunnel instead. Nothing answers
  on the admin upstream until a password is configured.

`pnpm check:deployments` resolves every canonical Compose preset without a Docker daemon. A Caddy
runtime can additionally validate the ingress configuration without starting applications.
