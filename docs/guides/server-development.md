# Building a server that publishes to Seeker Agent Connect

This guide takes you from a fresh checkout of this repository to a live feed, using one of the two Go templates it ships. At the end you will have published a manifest, published a signal, revised it, withdrawn it, seen two subscribers receive the same document, and sent the content-free hint that wakes a phone which is not looking — and you will have done it without a credential of ours for Firebase, without a line of Kotlin, and without holding anybody's wallet keys.

One thing to know before you start: a feed is added from the app's existing **Add connection** screen.
The owner scans or pastes the public reference and confirms the gateway and server identity before
anything is stored. There is no `seekervault://feed` intent filter yet, so a link does not open the
app directly. [Step 5](#5-connect-the-app) describes the flow.

It is written for the developer who wants their own server in this system: a trader publishing their own swaps, or somebody republishing a market listing. You do not need to know how the app works inside, and you never see the private MCP implementation, which is a different kind of server entirely ([`docs/wiki/mcp-adapter.md`](../wiki/mcp-adapter.md)).

**What this is not.** It is not an SDK guide, because there is no SDK. Nothing here embeds this app's screens in another app, and the boundary that a later extraction would cut along is documented rather than built ([`docs/wiki/client-plugins.md`](../wiki/client-plugins.md#how-a-host-app-would-get-an-entry-point--documented-not-built) is the honest account; the packaging is SEE-102 and the host-app entry point is SEE-104). What you build by following this page is a **publisher**: a server that says what is proposed, to everybody subscribed, and stops there.

## How the pieces fit

Three parties, and each one does exactly one thing:

1. **Your server publishes proposals.** One document per thing it has to say, submitted once, to its own channel. It never learns who reads it, holds no subscriber list, and has no endpoint anybody's phone could reach.
2. **The shared gateway distributes them.** It holds the authoritative copy, answers reads from any phone, fans each publication out to the phones currently listening, and sends a content-free hint to the ones that are not ([`docs/wiki/broadcast-gateway.md`](../wiki/broadcast-gateway.md)).
3. **The app executes what its owner chose.** A bundled client plugin reads the proposal, asks the owner for the parts that are theirs — the amount, the side — fetches fresh execution data from the provider itself, shows the owner exactly what will be signed, and opens the wallet once. Then it stops.

The consequence worth internalising before you write anything: **a proposal is common and a decision about it is not.** Every subscriber receives the same bytes. What each owner then does with it — the amount, the wallet, the approval, the result — happens on their phone and is not sent to you, to the gateway, or to each other ([`docs/wiki/shared-proposals.md`](../wiki/shared-proposals.md#nothing-goes-the-other-way)).

### The two connection modes

A phone's connection to a server is one of two kinds, and the kind comes from the server's own validated statement about itself rather than from how it was added:

|                              | `direct`                                            | `gateway_feed`                                              |
| ---------------------------- | --------------------------------------------------- | ----------------------------------------------------------- |
| Whose server                 | One owner's own private sidecar                     | **Yours**, the publisher you are about to build             |
| How the phone adds it        | A pairing code (`pnpm pair`)                        | A feed reference, through the gateway                        |
| Credential on the phone      | It holds one, from pairing                          | **None.** There is nothing to authenticate to                |
| Who sees a request           | Only the owner who paired                           | Every subscriber of the channel                              |
| Who the phone calls          | The server itself                                   | The gateway. **Never your server**                           |
| What the server learns       | That one phone is paired                            | **Nothing about any phone**                                  |
| Where a result goes          | Back to the server that asked                       | Nowhere. It stays on the phone                               |

A phone holds any mixture of the two at once and neither affects the other; a private request is never converted into a broadcast one. The full account is [`docs/wiki/server-manifests.md`](../wiki/server-manifests.md#the-two-kinds-of-server).

### What never leaves the phone

In `gateway_feed` mode the phone calls nothing of yours, so there is no path for any of this to reach you even by accident: the amount an owner chose, which side they took, which wallet is selected, whether they approved or dismissed, the signature, and the outcome. The app has no outbox for a feed — no `SubmitResult`, no upload, no per-subscriber state anywhere ([`docs/security.md`](../security.md#a-proposal-is-common-and-a-decision-about-it-is-not-see-89)). The measured version of that claim, with the search that was run over every file both sides wrote, is [`docs/testing/see-98.md`](../testing/see-98.md#observed-data-flows).

## Before you start

**On your machine:**

- **Go 1.27.1 or newer.** Both Go modules pin it (`publisher/go.mod`, `broadcast/go.mod`). It is the only thing you strictly need to build and run a template.
- **Node 24.21.0 and pnpm**, if you want to run this repository's checks (`pnpm check:publisher`, `pnpm test:integration`). Versions and setup are in [`docs/development/toolchain.md`](../development/toolchain.md).
- **Docker Engine 24+ with Compose v2**, if you want the packaged deployment rather than a process you started yourself. Optional for everything in this guide, required for nothing.
- `curl` and `openssl`, which every example below uses.

**From other people:**

- **A broadcast gateway to publish to**, and a credential it issued you. Either somebody runs one and gives you both, or you run your own ([step 1](#1-get-a-gateway-to-publish-to)).
- **A domain and a certificate authority**, only if something off your own machine has to reach either service. Neither is needed for a local run.
- **Nothing from us for push.** You are never given a Firebase project, service account, API key or device token, and there is no configuration on your side for any of it ([step 9](#9-topic-push)).
- **Nothing from a provider for a first deployment.** Jupiter's keyless tier serves what the Prediction template reads; a key buys a higher rate limit and is your own business ([`docs/integrations/jupiter.md`](../integrations/jupiter.md#authentication-none-deliberately)).

**Three roles, and they are usually three people.** The **gateway operator** runs the shared service and decides who may publish. The **publisher developer** — you — runs a template and says what is proposed. The **phone owner** subscribes, decides, and approves. This guide is written for the second role and tells you exactly what to ask the first for.

**One naming trap, once.** This repository has two directories with "gateway" in their description and they are different services with different operators. `broadcast/` is the **shared broadcast gateway**: what you publish to, what phones read from. `gateway/` is **one owner's reverse proxy in front of their own private sidecar** and has nothing to do with publishing. In the docs, "gateway" means the first when it is next to *broadcast*, *shared* or *feed*, and the second next to *reverse proxy* or *deployment*.

## 1. Get a gateway to publish to

If somebody already runs one, skip to [step 2](#2-be-registered-as-a-publisher); what you need from them is its **origin** (`https://feeds.example.com`, character for character) and a **credential**.

To run your own, the deployment is already packaged — extend it rather than inventing another. From `broadcast/`:

```sh
cd broadcast
cp .env.example .env          # then generate the two broker secrets it asks for
docker compose up -d --build
```

That is the local-development configuration: plain HTTP, published on your loopback address only. The internet-facing one is the same file plus an overlay that terminates TLS for a domain you own, and the push relay is a third:

```sh
docker compose -f compose.yaml -f compose.public.yaml up -d --build                      # HTTPS
docker compose -f compose.yaml -f compose.public.yaml -f compose.push.yaml up -d --build  # and hints
```

Four services start: the gateway, the broker that fans publications out, the Redis the broker keeps its recovery cache in, and the proxy in front of all of it. Only the proxy's port is ever published. **Starting the stack creates no publisher and accepts no publication** — that takes step 2, which is a deliberate local act with no network surface at all.

What to read rather than have repeated here: [`broadcast/README.md`](../../broadcast/README.md) for the stack itself, [`docs/development/broadcast.md#configuration`](../development/broadcast.md#configuration) for every variable with its default and range, and [`docs/development/broadcast.md#deployment`](../development/broadcast.md#deployment) for the three ways this stack differs from the sidecar's. The TLS, DNS and port mechanics are the same ones the sidecar's deployment uses, and they are written out once in [`docs/guides/self-hosting.md#going-public-tls-dns-and-ports`](self-hosting.md#going-public-tls-dns-and-ports) — the domain must already resolve to the host and ports 80 and 443 must be reachable before the first start, because that is how the proxy obtains a certificate.

Without Docker, the same thing as two processes and a broker:

```sh
cd broadcast
BROADCAST_PUBLIC_URL=http://127.0.0.1:8090 BROADCAST_DATABASE_PATH=./broadcast.db go run ./cmd/broadcast
```

The four settings that decide whether phones can read you at all: `BROADCAST_PUBLIC_URL` is the origin every published manifest has to name — the phone compares it with the reference the feed was added from, so a deployment that gets it wrong has a feed nobody can read. `BROADCAST_READ_ADDRESS` (default `127.0.0.1:8090`) serves phones, `BROADCAST_PUBLISHER_ADDRESS` (default `127.0.0.1:8091`) accepts publications, and they are separate listeners on purpose: an operator who wants publishing kept off the internet deletes one route from the proxy's configuration. `BROADCAST_STREAM_URL` with its two keys turns the live stream on; without them the gateway still holds every document and answers every read, and tells a phone that asks to listen that there is no stream here.

## 2. Be registered as a publisher

A publisher exists only because an operator made one. There is no signup endpoint, no self-registration, and no way to do this over a network — the tool writes to the gateway's database directly:

```sh
# in broadcast/, or `docker compose run --rm ctl …` against the packaged stack
go run ./cmd/broadcastctl register --database ./broadcast.db \
  --server 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d --label "copy trading"
```

The server ID is a lowercase UUID you choose (`uuidgen | tr 'A-Z' 'a-z'`); it becomes your lasting identity. What comes back is shown once:

```
publisher   3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d
channel     server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d
credential  d7baec00

zv5d4ETcax_r3dXHtA_mAcmh1J33zqFCf0XFz4pfXP0

That credential is shown once and is not stored. Give it to the publisher as BROADCAST_CREDENTIAL,
and keep it out of version control. Rotate with `rotate`, then `revoke --credential <id>`.
```

Four things, and it is worth being clear about which is which:

- **`publisher`** — your server ID. It goes in your manifest and in every proposal you publish.
- **`channel`** — `server/<your server ID>`, derived, never chosen. It is the only channel you can write to, and the gateway checks every document against the channel your credential resolves to rather than against what the document claims.
- **`credential`** — an 8-character handle for the secret, so an operator can revoke this one later without ever seeing it again. The gateway stores only a hash.
- **the 43-character line** — the secret itself, sent as `Authorization: Bearer <credential>`. It is not recoverable: if you lose it, the operator runs `rotate` and gives you a new one.

The other commands an operator has are `rotate` (add a second credential so the first can be retired), `revoke --credential <id>`, `revoke --server <uuid> --all`, `list`, and `forget --server <uuid> --yes`, which removes a publisher and everything it published. Revoking stops future publications; it does not unpublish what is already there, and phones that already read your proposals keep their own copies until their owners remove the feed. The full table is [`docs/wiki/broadcast-gateway.md#registering-a-publisher`](../wiki/broadcast-gateway.md#registering-a-publisher).

## 3. Copy a template out

`publisher/` is a **Go module of its own**, not a command inside the gateway, precisely so that it can be copied out and still build:

```sh
cp -R publisher ~/my-signals
cd ~/my-signals
go build ./... && go test ./...
```

It shares the protocol with the gateway and nothing else. Only the generated code in `internal/gen/` and the document rules in `internal/signals/` come from this repository's contract, and one test reads the gateway's own source to catch a drift in those bounds — that test skips when the file is not there, "which is what a copied-out template looks like". There is deliberately **no feed client compiled into it**: a template publishes, and a boundary test fails if anything in it could read a feed, reach a subscriber, or acquire an address other than its own provider's.

Three commands are built from it — the two templates, and one CLI for either:

| Binary            | What it is                                                                                  |
| ----------------- | ------------------------------------------------------------------------------------------- |
| `cmd/copytrading` | Publishes what a person or a program tells it to, through its own authenticated API (SEE-95) |
| `cmd/prediction`  | Discovers Jupiter Prediction markets through its operator's filters and publishes those (SEE-96) |
| `cmd/publishctl`  | The operator's CLI for either one. Every command is one HTTP call to the template's API       |

Then configure it. `cp .env.example .env` and fill it in; the file documents every setting where it sits, including why each one exists, so read it rather than this table. **Six settings have no default and nothing starts without them:**

| Variable                | What it is                                                                                          |
| ----------------------- | --------------------------------------------------------------------------------------------------- |
| `PUBLISHER_SERVER_ID`   | The lowercase UUID the operator registered you with                                                  |
| `PUBLISHER_GATEWAY_URL` | The gateway's own origin, character for character — its `BROADCAST_PUBLIC_URL`                        |
| `PUBLISHER_ENVIRONMENT` | `production` or `sandbox`. **No default at all**: a deployment that says nothing does not start       |
| `PUBLISHER_DATABASE_PATH` | The SQLite file this template keeps its own signals in (compose and the image set it for you)       |
| `BROADCAST_CREDENTIAL`  | The 43-character secret from step 2 — the grant the *gateway* issued you                              |
| `PUBLISHER_API_TOKEN`   | Who may call *this template's* API. `openssl rand -base64 32`. At least 32 characters                 |

Those last two are different things and confusing them is the first mistake to avoid. `BROADCAST_CREDENTIAL` is how the gateway knows you; `PUBLISHER_API_TOKEN` is how your own strategy process, or your own hand at a terminal, is allowed to tell your template what to say. Either may be a file instead of a value (`BROADCAST_CREDENTIAL_FILE`, `PUBLISHER_API_TOKEN_FILE`) for a deployment that mounts secrets; setting both a value and a file is a configuration error, because then there would be two answers and no way to tell which was used.

One more is worth setting deliberately: `PUBLISHER_PUBLISH_URL`. Empty means `PUBLISHER_GATEWAY_URL`, which is right when the gateway's proxy serves both of its APIs on one origin — the packaged deployment does. Set it when the gateway runs with two loopback listeners (reads on 8090, publications on 8091), or when its operator keeps publishing off the internet and you reach it over a tunnel. Getting it wrong is the one mistake the gateway cannot report: a read origin has no handler that could write anything, so a publication gets a 404. The template says so at startup and names the variable.

**Both `.env` examples ship as sandbox deployments**, so copying one and running it demonstrates the whole path without anybody's money. Promoting to production is a deliberate edit of one line ([step 11](#11-sandbox-and-production)).

The complete settings reference, with every default and range, is [`docs/development/publisher.md#configuration`](../development/publisher.md#configuration). The reasoning behind the template's shape is [`docs/wiki/copytrading-template.md`](../wiki/copytrading-template.md).

## 4. Start it, and read the two things it prints

```sh
cd ~/my-signals
PUBLISHER_SERVER_ID=3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d \
PUBLISHER_GATEWAY_URL=http://127.0.0.1:8090 \
PUBLISHER_PUBLISH_URL=http://127.0.0.1:8091 \
PUBLISHER_ENVIRONMENT=sandbox \
PUBLISHER_DISPLAY_NAME="Copy trading demo" \
PUBLISHER_DATABASE_PATH=./copytrading.db \
BROADCAST_CREDENTIAL=<the 43 characters from step 2> \
PUBLISHER_API_TOKEN=$(openssl rand -base64 32) \
go run ./cmd/copytrading
```

Or, packaged: `docker compose up -d --build` in the copied directory, which publishes the API on `127.0.0.1:8092` and puts a proxy in front of it.

Three log lines and one bare line of text:

```
{"level":"INFO","msg":"publishing as this server","server_id":"3f1b2c4d-…","channel":"server/3f1b2c4d-…","gateway":"http://127.0.0.1:8090","publishing_to":"http://127.0.0.1:8091","environment":"sandbox","operation":"swap","plugin":"jupiter.swap","settings_revision":1,"pending":0}
seekervault://feed?v=1&gateway=http%3A%2F%2F127.0.0.1%3A8090&server=3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d
{"level":"INFO","msg":"the manifest is published","revision":1,"status":"stored"}
{"level":"INFO","msg":"the API is listening","address":"127.0.0.1:8092"}
```

**The manifest** is published for you, before the API opens, and kept in step afterwards. You never write one: it is built from your configuration and from the kind of signal this template publishes. It carries your server ID, the phone–server contract version it speaks (`1`), a settings revision, the mode (always `gateway_feed`), the plugin your proposals need with the contract range it works with, the environments you serve, your display name, and one reference — the gateway's origin and your channel. There is no field in it that installs code, asks for a permission, carries a policy, or names an endpoint of yours, and there is no field that could grow into one: a test reads the contract and fails if the field set changes ([`docs/wiki/server-manifests.md#the-document`](../wiki/server-manifests.md#the-document)).

If it was refused, nobody can subscribe to you at all, and the template says so with the advice in its hand — the usual cause is `PUBLISHER_PUBLISH_URL` pointing at the read origin, or `PUBLISHER_GATEWAY_URL` not being the gateway's own `BROADCAST_PUBLIC_URL` character for character. [When something is refused](#15-when-something-is-refused) has the codes.

**The bare line is the feed reference**, and it is what you give people:

```
seekervault://feed?v=1&gateway=<the gateway's origin, percent-encoded>&server=<your server ID>
```

Ask for it again at any time with `publishctl reference`, or read it out of `GET /v1/manifest`. **It carries no secret**, because there is nothing to authenticate to: holding one grants the ability to read a public broadcast, which is what a broadcast is. You can print it in a README, put it on a web page, or turn it into a QR code. The app accepts the QR or pasted text in [step 5](#5-connect-the-app).

## 5. Connect the app

On the phone, open **Add connection** and scan the reference's QR code or paste its text. A pairing
code and a feed reference are routed separately: neither can fall into the other's network path. The
confirmation shows the gateway origin, server ID and the fact that this is a public broadcast with
no credential. **Add feed** then resolves and validates the manifest; **Cancel** stores nothing.
There is no deep link or Android intent filter in this release, so tapping the URI outside the app is
not an onboarding path.

An added feed opens with the publisher's manifest name, Sandbox or Production, and its required
client plugins visible. A reference that is already present writes nothing. A refused manifest, a
gateway check failure and a build with no gateway adapter are distinct results, and only a transient
gateway failure offers Retry. The exact owner flow is in
[`docs/wiki/feed-onboarding.md`](../wiki/feed-onboarding.md).

Nothing else about the app changes to accommodate you: nothing is installed, no code of yours runs on the phone, and the phone holds no credential for you.

What happens after the owner confirms, in this order:

1. The phone reads your **manifest** from the gateway and validates it — that it is a feed, that the server ID matches the one it is adding, that the gateway origin is the one it is adding it from, that the channel is `server/<that ID>`, and that everything inside is within bounds. A manifest that fails is `Refused` and nothing from you is executable; the connection is not revoked by it.
2. It resolves **support**: whether this build carries the plugin your manifest requires, at a contract inside the range you published, for the environment the connection keeps. This is never cached — it is derived from the compiled plugin registry on every read.
3. It reads a **snapshot** of your proposals over the gateway's unary API, in pages.
4. While the app is being looked at, it opens a **stream** for the feeds it holds, using a ticket the gateway mints. Everything that arrives on it goes through the same validators a snapshot's documents go through: a document is not trusted more for having arrived quickly.
5. When the app is closed, a [topic hint](#9-topic-push) tells it that the feed moved, and it reads the feed authoritatively rather than believing the hint.

Two phones with the same reference hold the same documents at the same revisions. That is the whole of what "two subscribers" means here: there is no per-device copy of anything, and the gateway keeps no record that either of them read it. The verified version of that is step 19 of [`docs/testing/see-100.md`](../testing/see-100.md) — two listeners on the shipped transport, one publication, the same document at the same offset on both.

The owner-facing half of this — what the screens say, and what "not checked yet" means — is [`docs/wiki/server-manifests.md#what-the-owner-is-told`](../wiki/server-manifests.md#what-the-owner-is-told).

## 6. Publish a signal

Two ways in, and they are the same way: the CLI is an HTTP client for the template's own API, which is the one path in. There is no side door — no file to drop a signal in, no queue to write to, nothing that publishes without the API token.

### From a terminal

```sh
export PUBLISHER_API_TOKEN=…
publishctl create \
  --term input_mint=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v \
  --term input_decimals=6 --term input_symbol=USDC \
  --term output_mint=So11111111111111111111111111111111111111112 \
  --term output_decimals=9 --term output_symbol=SOL \
  --term max_slippage_bps=50 \
  --in 2h --note "Rotating a third of the stable leg into SOL."
```

Flags come after the command. `--in 2h` is a convenience: the API takes nothing but an absolute instant, so the CLI works the instant out and sends that. The answer, in full:

```json
{
  "idempotent": false,
  "publication": { "state": "published", "confirmed_revision": "1", "attempts": 0 },
  "signal": {
    "server_id": "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
    "channel": "server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
    "proposal_id": "dcf362cb-647b-4e8f-9506-fb3dc72a3fba",
    "revision": "1",
    "status": "open",
    "operation": "swap",
    "plugin_id": "jupiter.swap",
    "environment": "sandbox",
    "created_at": "2026-09-18T04:00:36Z",
    "updated_at": "2026-09-18T04:00:36Z",
    "expires_at": "2026-09-18T06:00:36Z",
    "note": "Rotating a third of the stable leg into SOL.",
    "terms": {
      "input_mint": "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
      "input_decimals": "6", "input_symbol": "USDC",
      "output_mint": "So11111111111111111111111111111111111111112",
      "output_decimals": "9", "output_symbol": "SOL",
      "max_slippage_bps": "50"
    }
  }
}
```

It also prints, on stderr, the idempotency key it minted for you and the one-line summary `signal dcf362cb-… revision 1 open, publication published`.

### From a program

```sh
curl -sS http://127.0.0.1:8092/v1/signals \
  -H "Authorization: Bearer $PUBLISHER_API_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: strategy-2026-09-18-0001' \
  -d '{"expires_at":"2026-09-18T08:00:00Z",
       "note":"Fresh JUP leg from the strategy process.",
       "terms":{"input_mint":"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                "input_decimals":"6","input_symbol":"USDC",
                "output_mint":"JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN",
                "output_decimals":"6","output_symbol":"JUP",
                "max_slippage_bps":"75","least_input":"5000000"}}'
```

The same answer shape. The status code carries the publication's fate: **201** created and published, **202** created and not yet published (stored here, and it will go when the gateway answers), **502** created and refused by the gateway, **200** nothing changed. The complete request, answer and status-code contract, with a working Python strategy loop, is [`docs/integrations/signal-api.md`](../integrations/signal-api.md#publishing-a-signal).

### What a signal may say, and what it may not

A swap signal names the pair and caps the risk: `input_mint`, `output_mint`, `input_decimals`, `output_decimals` and `max_slippage_bps` are required; `least_input`, `most_input`, `input_symbol` and `output_symbol` are optional. **There is no amount**, and there is no side — a publisher may bound the amount and must cap the slippage it will have its signal acted on with, and within that the amount is chosen on each owner's own phone and stays there. That is the difference between a signal and an order.

Nor can you name the operation, the plugin, the proposal ID, the channel, the revision, the status or the environment: every one of those in a request body is a 400, because they are the template's to say. An unknown field is refused by name, with the reason:

```
there is no field "amount" in a signal. A signal says what is proposed and until when; the
amount, the wallet and the decision are each subscriber's own and are never sent here
```

Bounds: at most 32 terms, each key a short lowercase name, each value at most 512 bytes; a note at most 1024 bytes; the whole request at most 64 KiB. A list that breaks any of them is refused whole rather than trimmed to fit.

## 7. The other template: markets you did not write

`cmd/prediction` is the same core with one thing changed: who writes its signals. It reads Jupiter's prediction listing through its operator's filters and publishes one proposal per market that matches. Its API is read-only about signals — `create`, `update` and `cancel` answer **403 `written_by_discovery`** rather than "no such route", so a caller is told why.

```sh
PUBLISHER_SERVER_ID=<a second server ID, registered separately> \
PUBLISHER_GATEWAY_URL=http://127.0.0.1:8090 PUBLISHER_PUBLISH_URL=http://127.0.0.1:8091 \
PUBLISHER_ENVIRONMENT=sandbox PUBLISHER_DATABASE_PATH=./prediction.db \
PUBLISHER_API_ADDRESS=127.0.0.1:8094 \
BROADCAST_CREDENTIAL=<the credential for *this* server> PUBLISHER_API_TOKEN=$(openssl rand -base64 32) \
PREDICTION_CATEGORIES=crypto PREDICTION_MOST_OPEN=3 \
go run ./cmd/prediction
```

It needs its **own** server ID and its own credential: two templates sharing one identity would be two servers claiming one channel, which the gateway refuses. Note also that the database is stamped with the server ID and the environment word and refuses to open for another — pointing a sandbox deployment at a production volume is a startup error rather than a silent mixture.

The filters are all operator settings and they divide in two. `PREDICTION_SOURCE` (`polymarket`, `kalshi`, `bisonfi`), `PREDICTION_CATEGORIES` and `PREDICTION_FILTER` (`new`, `live`, `trending`, `upcoming`) are the **provider's own query parameters** and decide which events the listing returns at all. Everything else is applied here, to the records that came back: `PREDICTION_TAGS`, `PREDICTION_KEYWORDS`, `PREDICTION_STATE`, the two close-time edges, `PREDICTION_LIFETIME_HOURS`, and the ceilings `PREDICTION_MOST_OPEN` and `PREDICTION_MOST_CHECKS`. Each one's range and default is in [`docs/development/publisher.md#the-prediction-templates-own-settings`](../development/publisher.md#the-prediction-templates-own-settings); what each one *means* is [`docs/wiki/prediction-template.md#the-filters-and-what-they-mean`](../wiki/prediction-template.md#the-filters-and-what-they-mean).

Run a cycle now rather than at the next interval, and read back exactly what it decided:

```sh
export PUBLISHER_API_URL=http://127.0.0.1:8094 PUBLISHER_API_TOKEN=…
publishctl poll
publishctl discovery
```

```json
{
  "filters": { "source": "polymarket", "categories": ["crypto"], "tags": [], "keywords": [],
               "closed_markets": false, "closes_between": "1h0m0s and 720h0m0s",
               "page_size": 25, "most_pages": 4, "most_open": 3, "most_checks": 20,
               "every": "5m0s", "lifetime": "168h0m0s" },
  "last_cycle": { "number": 1, "outcome": "ok", "pages": 4, "events": 100,
                  "considered": 1135, "matched": 483, "created": 3, "updated": 0,
                  "cancelled": 0, "checked": 0, "skipped": 480,
                  "skipped_because": { "closes_too_late": 414, "not_tradeable": 202,
                                       "closes_too_soon": 36 } },
  "working": true,
  "markets": [ { "provider": "polymarket", "market_id": "POLY-4470841",
                 "event_id": "POLY-1005106", "title": "2,100", "state": "open",
                 "generation": 1, "close_at": "2026-09-18T16:00:00Z",
                 "source_url": "https://jup.ag/prediction/ethereum-above-on-september-18-2026" } ]
}
```

`skipped_because` is the useful one when a filter is not doing what you expected: it counts every reason a market was passed over, by name.

What it publishes for each market is the market's identity and the deposit terms — `market_id`, `event_id`, `provider`, `deposit_mint`, `deposit_decimals`, `deposit_symbol`, `least_deposit`, `most_deposit` — and a note it generates, which says where the market is listed and that the state, prices and rules are read on the owner's own phone. **It publishes no side.** `side`, `is_yes`, `outcome`, `direction`, `recommendation` and `confidence` are all refused as terms this kind does not know. A publisher names a market and is believed about nothing else: whether it is open, what the two prices are, and what the rules say are read from the provider by the phone, at the moment the owner looks ([`docs/wiki/jupiter-prediction.md`](../wiki/jupiter-prediction.md)).

The `source_url` it tracks per market is deliberately **not** published — it is for your own logs.

## 8. The lifecycle: revisions, expiry, withdrawal, retries and outages

### Identity and revision

A signal's identity is a proposal ID the template mints; a caller cannot name one. Everything after the first publication is a **revision** of that identity, and the revision is the whole ordering rule at both ends: the gateway refuses a revision below the one it holds, and the phone applies a document only over an older revision of itself. Revision 1 is the first publication, and the store only bumps it **if the statement actually moved** — publishing an identical statement returns `"changed": false` and sends nothing, which is what makes a strategy loop that republishes its whole book every minute harmless.

### Expiry

`expires_at` is required, absolute, and must be in the future. It is a fact inside the document rather than a reason to hide it: the gateway keeps serving an expired proposal until retention sweeps it (`BROADCAST_RETENTION_HOURS`, a week by default, counted past the proposal's own expiry), and a phone derives "expired" from the clock at the moment it looks rather than storing it. So an owner who opens the app an hour late sees the proposal, sees that it has expired, and is offered nothing to approve.

For the Prediction template, expiry is the market's own close time, or first-seen plus `PREDICTION_LIFETIME_HOURS` when the provider gives none — never "now plus something", because an expiry that moved with the clock would move the document and wake every subscribed phone on every cycle.

### Updating

```sh
publishctl update dcf362cb-647b-4e8f-9506-fb3dc72a3fba \
  --term input_mint=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v --term input_decimals=6 \
  --term output_mint=So11111111111111111111111111111111111111112 --term output_decimals=9 \
  --term max_slippage_bps=30 --in 90m --note "Tightening the slippage cap to 0.3%."
# signal dcf362cb-647b-4e8f-9506-fb3dc72a3fba revision 2 open, publication published
```

An update replaces the **whole** statement; it is never a merge, so a term you leave out is a term you removed. On the phone, a higher revision replaces the publisher's half and leaves that device's half exactly where it was — which is what makes a review of the older terms detectably stale rather than silently applied to the new ones.

### Withdrawal

```sh
publishctl cancel 277e94f7-c510-488f-97e8-29e4b721b66f
# signal 277e94f7-c510-488f-97e8-29e4b721b66f revision 2 cancelled, publication published
```

A withdrawal is a new revision with the status changed, not a deletion. A phone that reads it afterwards gets the document, its terms intact, with `"status": "PROPOSAL_STATUS_CANCELLED"` — still readable, and nothing new executed from it. **A record of an execution that already happened stays exactly as it is:** what a phone did happened, and a publisher cannot unsay it. Withdrawal is final for the identity; there is no reopening, and a Prediction market that closes and comes back gets a new proposal rather than a resurrection.

### Idempotent retry

Every create takes an `Idempotency-Key`. The key is compared against the statement it was first used with:

- the same key and the same statement returns the original signal with `"idempotent": true`, and **200** rather than 201;
- the same key with a *different* statement is **409 `key_reused`**, and nothing is stored.

Update, cancel and retry need no key — they are idempotent by what they are. So the correct behaviour for a program that lost its answer is to send the identical request again with the identical key, and that is safe whether the first one arrived or not.

### When the gateway is not there

The document is its own outbox. A publication that cannot be delivered is stored anyway and retried:

```
signal c8f01feb-7c64-494e-a03f-bcc17932eb40 revision 1 open, publication pending
  dial tcp 127.0.0.1:8091: connect: connection refused
  it is stored here and will be published when the gateway answers
```

The template's own drainer retries with a doubling backoff from one second to a minute (the log says `"this signal will be published again","in":"1s"`, then `2s`, and so on), and `publishctl status` counts what is outstanding as `pending`. `publishctl retry <id>` nudges one by hand, which is also the only way out of a *permanent* refusal — a restart does not clear one, on purpose. What is retried is the **identical document**, which the gateway answers `unchanged` if it did arrive, so a slow gateway costs a delay and never a duplicate signal.

### When the source is not there

This applies to the Prediction template, and it is the rule worth understanding before you trust it: **absence is not closure.** A market missing from a filtered listing may have closed, or may simply have left the filter, so each one is asked about directly, and only the provider's own answer ends a proposal — gone, closed, cancelled or settled. A provider that cannot be reached ends **nothing at all**: the cycle is recorded as `partial` or `failed` and every proposal stays exactly as it was, because the markets have not changed and the template merely cannot see them. A filter change never withdraws anything either.

### The authoritative snapshot

The gateway's database is the authority and the broker's history is a recovery cache. A phone whose stream dropped asks the broker to catch it up; when continuity cannot be *proven* — no subscription, nothing held, not recoverable, a changed epoch, or too far behind — it reads the snapshot over the unary API instead. You do not have to do anything about this, and there is nothing you can do to make it wrong. The measured version: with Redis stopped mid-run, a third of a window's publications reached their listeners as an authoritative read rather than as a publication, every listener came back to a replaced history, and every one held everything at the end ([`docs/testing/see-99.md`](../testing/see-99.md)).

What a phone reads, as it reads it — no credential, and the same answer for everybody:

```sh
curl -sS http://127.0.0.1:8090/seekervault.gateway.v1.FeedService/ListProposals \
  -H 'Content-Type: application/json' \
  -d '{"channel":"server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d","pageSize":10}'
```

```json
{
  "proposals": [ { "serverId": "3f1b2c4d-…", "channel": "server/3f1b2c4d-…",
                   "proposalId": "dcf362cb-647b-4e8f-9506-fb3dc72a3fba", "revision": "1",
                   "operation": "swap", "pluginId": "jupiter.swap",
                   "status": "PROPOSAL_STATUS_OPEN",
                   "createdAt": "2026-09-18T04:00:36Z", "updatedAt": "2026-09-18T04:00:36Z",
                   "expiresAt": "2026-09-18T06:00:36Z",
                   "publisherNote": "Rotating a third of the stable leg into SOL.",
                   "values": [ { "key": "input_mint", "text": "EPjFWdd5…" }, … ] } ],
  "snapshotSequence": "2"
}
```

That is the same document you published, echoed back through the contract phones read. It is a useful thing to curl while developing: if your terms look wrong here, they will look wrong on the phone.

## 9. Topic push

A phone that is not looking at the app still needs to learn that your feed moved. That is what the push relay is for, and the whole of your part in it is **publishing** — there is no configuration, no credential and no API call on your side.

**How it works.** After a publication commits, the gateway sends one content-free message to that feed's public Firebase topic. Devices subscribe to topics through Firebase themselves, so nothing keeps a per-device token list for these feeds, and the gateway is never told whether any phone joined. The topic name is `feed.<environment>.<server_id>`:

```sh
curl -sS http://127.0.0.1:8090/seekervault.gateway.v1.FeedService/GetFeedTopics \
  -H 'Content-Type: application/json' \
  -d '{"channels":["server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"]}'
```

```json
{ "topics": [ { "channel": "server/3f1b2c4d-…",
                "topic": "feed.sandbox.3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d" } ] }
```

The phone asks for that name rather than deriving it, because a name both sides worked out for themselves would be a mismatch that shows up as silence rather than as an error. A gateway with no relay configured answers `no_push` and phones simply rely on the stream and the periodic read.

**What the relay sends** is exactly this, to that topic:

```json
{ "message": { "topic": "feed.sandbox.3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
               "data": { "kind": "feed_invalidation", "version": "1" },
               "android": { "collapse_key": "seeker-vault-feed-invalidation-v1",
                            "priority": "HIGH", "ttl": "300s" } } }
```

That is the whole payload. No proposal, no revision, no sequence, no publisher, and nothing that identifies a subscriber — a topic message is identical for everyone who receives it. **Which feed changed is the topic it arrived on**, which is routing rather than content. The phone matches that map whole, ignores anything with a field it does not know, and then acts on none of it directly: a hint schedules one bounded authoritative read through the same validators a snapshot goes through. A forged, replayed or delayed hint can therefore cause a read and nothing else.

**What you are never given, and why that is the point:**

- **No Firebase project, service account, API key or device token.** The push credential is the *deployment's*, mounted read-only into the gateway alone and read once at startup. No part of it reaches an answer, an error or a log line.
- **No way to name, choose or address a topic.** There is no field anywhere a publisher could put one in: the gateway derives the topic from the channel it derived from your credential.
- **No per-user token registry to keep**, and nothing to keep one about. You are not told that a phone received anything, or who subscribed; Firebase owns topic membership.
- **No way to wake somebody without publishing.** Revoking your credential stops your hints because it stops your publications.

**Bounds.** One hint per topic per ten seconds with five in hand (`BROADCAST_PUSH_RATE`, `BROADCAST_PUSH_BURST`); over the quota a hint is dropped rather than queued, because the next one wakes a phone that reads everything anyway. One collapse key for every feed, so a phone that was off for an hour is woken once. Five-minute expiry. Nothing about a hint is retried, and a hint that fails never fails the publication — a hint is a hint, and the stream and the periodic read are what the app actually relies on.

For a deployment that wants the relay on, the operator's side is `compose.push.yaml` and the walkthrough in [`docs/guides/firebase.md#the-broadcast-relay-and-feed-topics-see-92`](firebase.md#the-broadcast-relay-and-feed-topics-see-92). Note that `BROADCAST_PUSH_ENVIRONMENT` is a label the operator chooses for their topic names and decides nothing — it is not the same thing as [step 11](#11-sandbox-and-production)'s environment, and sandbox and production publishers must not share a topic.

## 10. A build that does not have your plugin

Your manifest names the client plugin your proposals are written for and the contract range it works with. The app carries a fixed list of plugins, compiled in — two of them today, `jupiter.swap` and `jupiter.prediction`, both at contract 1. **Nothing is downloaded, nothing is discovered at runtime, and there is no dynamic load path to secure because there is no dynamic load path.** So a manifest is matched against what the build already has, and the phone says which way it went:

| What the phone resolved                                           | Is anything from you executable? | What the owner is told                                                                                            |
| ----------------------------------------------------------------- | -------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `Supported` — the plugin is there, at a contract in your range    | **Yes**                          | The feed's own line: a shared feed this phone reads through the gateway, holding no credential for it               |
| `PluginMissing` — this build has no such plugin                   | No                               | "This app doesn't have a client plugin this server needs, so nothing from it can be approved."                      |
| `PluginIncompatible` — it has the plugin, at a contract outside your range | No                      | "This app has the client plugin this server needs, but not at a version it works with."                             |
| `ProtocolUnsupported` — you speak a newer phone–server contract   | No                               | "This server speaks a newer version of the protocol than this app. Update the app to use it."                       |
| `EnvironmentUnsupported` — you do not serve the environment this connection keeps | No               | "This server doesn't serve the environment this app is running in."                                                 |
| `ManifestRefused` — your manifest broke one of the phone's rules  | No                               | "This app refused this server's own description of itself, so nothing from it can be approved."                     |
| `Unknown` — not asked yet, or the answer has not arrived          | Yes, provisionally               | "Not checked yet." Not hearing an answer is not an answer, so nothing is assumed from it                            |

**An unsupported feed is still readable**, which is the state you would call view-only. The proposal is shown in full — the note, the terms, the times — and says why it cannot be approved: *"This build has no plugin for what this server proposes, so it is shown in full and nothing here can carry it out."* The affirmative answer is simply not offered: nothing is prepared, no wallet is opened, and there is no warning for the owner to overrule, because there is nothing on that phone that would carry the operation out. There is also **no silent downgrade** — an unsupported operation never falls back to signing a raw message or a transaction the app could not account for.

The practical consequences for you:

- **A new server that uses the plugins that already ship needs no mobile change at all.** A second copy-trading publisher, a differently-filtered prediction publisher, a hundred of them: they are manifests and proposals, and the app carries the plugin already. This is the case the whole design is for.
- **A new execution platform needs a new bundled client plugin**, and that means a release of the app rather than anything you can deploy. A plugin is a compiled boundary: it declares a stable ID, the contract it was written against, the operations it serves and the environments it serves, and it reaches its own provider and takes everything else from what it is handed — no credential, no wallet token, no transport, no store, no approval ([`docs/wiki/client-plugins.md`](../wiki/client-plugins.md)).
- **Publish an honest contract range.** `jupiter.swap` and `jupiter.prediction` are contract 1, and both templates publish `1..1` for exactly that reason. A wider range would hand a document to a phone written against a contract neither side agreed on; a phone outside your range reports you as incompatible, which is the correct outcome and a legible one.

## 11. Sandbox and production

One setting decides which promise every subscribed phone is told to expect: `PUBLISHER_ENVIRONMENT`, `production` or `sandbox`, with no default at all.

|                    | Production                                                        | Sandbox                                             |
| ------------------ | ----------------------------------------------------------------- | --------------------------------------------------- |
| The market data    | Real, live, from the provider                                     | **The same.** Real, live, from the provider          |
| The transaction    | Built by the plugin, on the phone                                 | **The same bytes**, built the same way               |
| The review         | Every fact, read out of the bytes                                 | **The same review**                                  |
| The owner's rules  | Applied                                                           | **Applied**                                          |
| The wallet         | Opened once, with exactly those bytes                             | **Never opened**                                     |
| What is sent       | The signed transaction, to the network                            | **Nothing**                                          |
| Afterwards         | A signature, and an explorer link                                 | **No signature, and so no link**                     |
| The record says    | `Sent`, with the signature                                        | `Simulated`, with no signature                       |

The last four rows are the whole difference. Sandbox is not a smaller version of the operation and not a mock of it: it is the operation, carried out as far as an environment that performs nothing can carry it, and then stopped and labelled. On the phone that stop is structural — the sandbox branch of an approval is lexically outside the block that holds a wallet session, so there is no wallet in scope to ask, rather than a flag beside one.

Three things to be clear about:

- **An environment is not a Solana cluster.** The cluster is whatever network the owner's wallet is selected for; it is checked separately and always, and no environment changes it.
- **There is no Jupiter devnet or testnet to point sandbox at.** Neither the swap aggregator nor the prediction markets exist on another cluster, and this repository does not pretend otherwise. The app's own devnet checks, for transfers and message signing, are about something else entirely.
- **Nothing about a simulation is fabricated.** No signature, no explorer confirmation, no fill, no profit, no position. A rehearsal has nothing to look up afterwards and the app offers nothing.

**Validating production is opt-in, deliberate, and small.** Nothing in this repository's default checks, CI or load runs spends anything: the templates ship as sandbox, the load harness publishes synthetic proposals, and no automated test opens a wallet. When you do want to prove a production feed end to end, the way it is done here is one real operation with a deliberately small amount, recorded with the date, the build, the publisher's server ID and environment, the pair or market, and the signature — steps 6, 7 and 10 of [`docs/testing/stage-7-1.md`](../testing/stage-7-1.md) and step 10 of [`docs/testing/see-98.md`](../testing/see-98.md#the-device-checklist-for-the-owner). That is a device run by somebody who owns the wallet, and no laptop result substitutes for it.

**One deployment serves one environment.** Run two if you want both: separate server IDs, separate credentials, separate databases (the file is stamped and will refuse the wrong one), and separate push topics. Promotion is a deliberate edit of one line and a restart, and the gateway refuses a manifest that changes the environments a server ID already published — `other_environment` — so a feed cannot quietly become real money under its subscribers. On the phone, a manifest says which environments a server *serves* while a connection records which one it *keeps*, which is what makes a promotion something a person did. The full account is [`docs/wiki/environments.md`](../wiki/environments.md).

## 12. Where a result lives, and where the owner continues

Nothing comes back to you. That is not a limitation to work around, it is the design: there is no endpoint on your template that could accept a result, no field in any contract that carries one, and no call the phone makes to you.

What happens instead, on the phone: the owner's own **Activity** record gets one entry per operation, with the operation and plugin, the revision reviewed, the wallet's public address, the network, the environment, the public terms, and the outcome — `Sent` with the signature for a real operation, `Simulated` with none for a rehearsal, plus `DeclinedInWallet`, `NotSigned` or `Unknown` for the ways it can fail. No URL is ever stored in it; every link the app hands to a browser is built at the moment it is shown, from code that is compiled in.

And the app stops at honest submission. Under the links it says so:

> This app submitted the transaction and stops there. It does not follow whether an order filled, what a position is worth, how a market settles, or whether anything is paid out — open the links above to see any of that.

For a prediction, the handoff is to the market's page on Jupiter, and there is deliberately **no position link** — the platform has no per-position address, and inventing one would be the single dishonest thing on offer. "Order submitted" means the wallet reported that it signed and sent a transaction. It does not mean the order filled, that the prediction is right, or that anything will pay out, and nothing in this system will ever tell you otherwise. Do not build a product story on a fill you are not told about.

**What a private MCP request still does** is worth stating beside this, because the two kinds of server are easy to conflate. A paired sidecar asked for a signature *is* owed an answer and gets one: the phone submits the result back to the server that asked, which is the existing private workflow and is unchanged. A public feed is not owed one and does not get one. Both can run on the same phone at the same time, and a private request is never converted into a broadcast one ([`docs/wiki/mcp-adapter.md`](../wiki/mcp-adapter.md#two-different-kinds-of-server-and-why-the-distinction-matters)).

What a **provider** learns is separate from both and is not private: a quote carries two mints and an amount, a market read carries a market identifier, and building either carries the owner's public address, because a transaction has to be built for the account that will sign it. On-chain activity is public by construction. [`docs/integrations/jupiter.md#what-goes-to-jupiter-and-what-does-not`](../integrations/jupiter.md#what-goes-to-jupiter-and-what-does-not) is the itemised list.

## 13. Privacy, storage and retention — what is yours to answer for

**What your template keeps** is its own signals, in one SQLite file: the statement, the revision, the publication state and the idempotency keys it has seen. Plus, for the Prediction template, the markets it tracks and their source links. **Nothing about a subscriber**, because there is nothing to keep: no wallet, no amount, no decision, no outcome, and no endpoint that would accept one. A boundary test fails if anything in the module could send something about a subscriber, and another fails if a credential reaches a log line.

**What the gateway keeps** is the publications, the manifests and the publisher permissions, plus the transient connection state a broker needs. It has no column for anything about a subscriber, and a test reads the database after several reads and requires every row count to be unchanged. What it does necessarily observe is that somebody read a channel, with the caller's address, for rate limiting — and it writes none of it down. This is not a claim that network infrastructure observes no metadata; Firebase learns that a topic was subscribed to, a provider learns what it was asked, and a proxy sees connections.

**What is yours to answer for**, in practice:

- **The two secrets.** `BROADCAST_CREDENTIAL` is the ability to say anything your server can say to everybody subscribed; `PUBLISHER_API_TOKEN` is the same ability one step earlier. Keep both out of version control, prefer the `_FILE` forms in a deployment that mounts secrets, and rotate through the operator's `rotate` and then `revoke` rather than by editing one in place. Neither reaches a log from this code — do not put one in yours.
- **Your own logs.** The template logs what it published and why something was refused. If you add logging, keep it about documents rather than readers: your API's callers are yours to account for, and the `X-Forwarded-For` of somebody reading your feed is not something you ever see in the first place.
- **Retention you choose.** Your database grows with the signals you publish; nothing prunes it for you. The gateway's own retention is its operator's (`BROADCAST_RETENTION_HOURS`, a week past a proposal's expiry by default), and the broker's recovery cache is 256 publications or an hour per channel.
- **What you must not collect.** There is no supported way to learn who your subscribers are, what they chose, or what they did, and adding one would be a different product. If you want per-user state, you want a `direct` connection and a paired server, which is the other half of this repository.
- **What you say about it.** Do not describe a published feed as private, do not describe on-chain activity as private, and do not describe an environment as a test network. The wording that is accurate is in the docs linked from this page, and it is accurate because somebody checked.

## 14. Rate limits and bounds

Against the gateway, per publisher — keyed on the server ID your credential resolves to, so a publisher behind a changing address is still one publisher:

| Bound                     | Default                                    | What happens past it                                     |
| ------------------------- | ------------------------------------------ | -------------------------------------------------------- |
| Publications per second   | 2, burst 20 (`BROADCAST_PUBLISH_RATE/_BURST`) | `too_many_requests` — `resource_exhausted`             |
| Open proposals per channel | 200 (`BROADCAST_MAX_PROPOSALS`)           | `too_many_proposals` for a **new** identity; you can still update and withdraw |
| Request body              | 64 KiB                                     | Refused, at the proxy and again at the gateway            |
| Terms per proposal        | 32, each value ≤ 512 bytes                 | `too_many_values` / `bad_value`                           |
| Note                      | 1024 bytes                                 | `bad_note`                                                |
| Plugin requirements       | 16                                         | `too_many_plugins`                                        |

Reads are limited too, and this is the one a *deployment* should know about: 20 a second with a burst of 60, **keyed on the caller's address**, honouring `X-Forwarded-For` from a loopback proxy. That is per address rather than per phone, so a few hundred subscribers behind one NAT address share one bucket — measured, 400 listeners behind a single address produced 1,260 refused ticket requests where the same gateway served 5,000 on distinct ones. An office, a campus or a carrier-NAT deployment needs those numbers raised ([`docs/testing/see-99.md`](../testing/see-99.md#the-limits-a-deployment-should-know-about)).

Your template's own limits: a 64 KiB body, an idempotency key of 1–200 printable characters, a 10-second publication timeout by default (`PUBLISHER_PUBLISH_TIMEOUT_SECONDS`, 1–120), and a doubling retry from 1 second to 1 minute. The Prediction template's provider budget is deliberately small: a default cycle costs at most 24 calls every 300 seconds, spaced 2.1 seconds apart, which sits inside Jupiter's keyless allowance ([`docs/integrations/jupiter.md#rate-limits`](../integrations/jupiter.md#rate-limits)).

## 15. When something is refused

The gateway answers with a Connect code and a lowercase problem word, and the word is the useful part. The ones you will actually meet:

| Problem                 | What it means                                                                                  |
| ----------------------- | ---------------------------------------------------------------------------------------------- |
| `unauthenticated`       | No credential, a malformed header, an unknown one, or a revoked one. One code for all four      |
| `other_server`          | The document names a server ID that is not the one your credential resolves to                  |
| `foreign_channel`       | The document's channel is not `server/<your server ID>`                                          |
| `other_gateway`         | Your manifest's `gateway_url` is not this gateway's `BROADCAST_PUBLIC_URL`, character for character |
| `other_environment`     | You are publishing a manifest that changes the environments this server ID already published     |
| `stale_revision`        | A revision at or below the one held. What is held is in the error's detail                       |
| `revision_conflict`     | The same revision with different content — the revision is your promise about the content        |
| `too_many_proposals`    | The channel is at its bound. Withdraw or let some expire                                         |
| `too_many_requests`     | Over the publish rate. Back off; the template already does                                       |
| `no_stream` / `no_push` | This gateway has no broker, or no relay, configured. Not an error in your deployment             |

Two worked examples, because these are the two that look like something else:

```sh
# publishing into another publisher's channel, with a valid credential of your own
{"code":"permission_denied","message":"other_server (server_id)",
 "details":[{"type":"seekervault.gateway.v1.GatewayErrorDetail",
             "debug":{"problem":"GATEWAY_PROBLEM_OTHER_SERVER","field":"server_id"}}]}

# no credential at all
{"code":"unauthenticated","message":"unauthenticated","details":[…]}
```

Your template's own refusals are JSON with an `error`, an optional `term`, and a `detail` that says what to do — `bad_expiry`, `missing`, `unknown_term`, `not_a_mint`, `bad_number`, `impossible_amounts`, `bad_symbol`, `key_reused`, `cancelled`, `written_by_discovery`, `no_such_signal`, `not_json`. The full table with status codes is [`docs/integrations/signal-api.md#when-a-publication-is-refused`](../integrations/signal-api.md#when-a-publication-is-refused).

Three failures that are not refusals and are worth recognising:

- **`publication pending`, with a dial error.** The gateway is not answering. Your signal is stored and will go when it does; nothing is lost.
- **Nobody can subscribe, and the manifest log says it was refused.** Almost always `PUBLISHER_PUBLISH_URL` or `PUBLISHER_GATEWAY_URL`. A publication sent to a read origin gets a 404, which is the one mistake the gateway cannot report.
- **A phone shows your feed but offers nothing to approve.** Read [step 10](#10-a-build-that-does-not-have-your-plugin) — it is a support state, and the screen says which.

## 16. Checking your work

```sh
pnpm check:publisher     # the templates' own formatting, vet and tests. Needs Go
pnpm test:integration    # the gateway, both templates, two subscribers, the sidecar and an agent
```

`pnpm test:integration` is the one to run before you believe anything: it builds the real gateway, both real templates and both CLIs, runs them against each other with two subscribers, and reports every leg as PASS, FAIL or **NOT RUN** — a leg that could not run on the machine is never quietly a pass. It needs Go and nothing else; naming a Centrifugo and a Redis binary in `SEEKERVAULT_CENTRIFUGO` and `SEEKERVAULT_REDIS` adds the real-broker leg ([`docs/development/integration.md`](../development/integration.md)).

To run one of the two opt-in tests against real things:

```sh
cd broadcast && go build -o /tmp/broadcast ./cmd/broadcast && go build -o /tmp/broadcastctl ./cmd/broadcastctl
cd ../publisher && SEEKERVAULT_BROADCAST=/tmp/broadcast go test ./internal/publish/ -run Gateway -v
SEEKERVAULT_JUPITER=1 go test ./internal/jupiter/ -run Live -v
```

The evidence behind the claims on this page, rather than the claims: [`docs/testing/see-100.md`](../testing/see-100.md) is this guide walked end to end, command by command, with what each one answered. [`docs/testing/see-98.md`](../testing/see-98.md) is the cross-component and privacy acceptance, including the search that was run over every file both sides wrote. [`docs/testing/see-99.md`](../testing/see-99.md) is the load, isolation and failover measurement — 5,000 simultaneous listeners on one broker node, and where it stopped.

## What is not here

- **A feed deep link.** The owner can scan or paste a `seekervault://feed` reference in **Add connection**, but Android does not yet route a URI tapped outside the app into that screen. [Step 5](#5-connect-the-app) shows the supported path.
- **An SDK, or any way to embed these screens in another app.** The boundary a later extraction would cut along is documented and unbuilt; the packaging is SEE-102 and a host-app entry point is SEE-104. Nothing on this page ships either.
- **A third template.** Two exist, on purpose.
- **A plugin you can deploy.** A new execution platform is a new bundled client plugin in a release of the app.
- **Anything that follows an order.** No fill, position, settlement, payout or profit-and-loss, on the phone or anywhere else.
- **Anything about your subscribers.** There is no supported way to learn who they are or what they did, and there is not meant to be.
