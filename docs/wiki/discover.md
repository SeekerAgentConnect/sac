# Discover: gateway-recommended feeds (SEE-176)

The app's **Discover** tab is a catalog of the feeds a SAC gateway's operator chose to recommend.
An owner browses cards — name, description, Public or Restricted, Solana networks — and connects
without finding or pasting a feed link. A public feed offers **Connect**; a restricted one offers
**Request access** and goes through the existing wallet-proof and publisher-approval flow
([restricted feeds](restricted-feeds.md)).

Two settings, kept apart on purpose:

| Setting | Who sets it | What it decides |
| --- | --- | --- |
| **Show in app recommendations** + public description | The gateway operator (admin page or `feed-gatewayctl listing`) | Whether the feed appears in the Discover catalog |
| **Public / Restricted** access | The gateway operator (admin page or `feed-gatewayctl access`) | Who may read the feed |

**Visibility is independent of access.** Both public and restricted feeds may be listed or
unlisted. Listing a restricted feed opens nothing: the catalog shows only its onboarding metadata,
and every content, stream, push and presence read still needs a live grant. Unlisting a feed
removes it from the catalog and nothing else — existing subscriptions keep working and feed links
already shared still add it.

## The operator's side

A registration carries `show_in_recommendations` (default **off**) and a bounded plain-text public
description (at most 500 characters, line breaks allowed, no other control characters). Both are
stored on the registration, in SQLite (schema v8) and Postgres (schema v4); every registration that
existed before migrates to unlisted with no description.

- **Admin page.** *Add server* has a **Show in app recommendations** switch and a description field;
  each server's page has an **App recommendations** form to change either at any time. The page says
  whether the feed is actually shown and, if not, the first thing missing: publishing disabled, no
  description, no manifest yet, or a manifest without a display name. Changing a listing needs no
  confirmation, because it moves no stream name, grant or credential.
- **CLI.** `feed-gatewayctl register … [--recommend yes|no] [--description <text>]` and
  `feed-gatewayctl listing --server <uuid> [--recommend yes|no] [--description <text>]`. `listing`
  changes only what is given; `list` shows listed feeds.

The public name is the manifest's display name; the description is separate from the operator's
label and host, which are never served. A publisher republishing its manifest writes the manifest
table only and cannot reset the listing or the description.

## The catalog API

`FeedService.ListRecommendedFeeds` on the gateway's public read listener
(`packages/protocol/proto/seekervault/gateway/v1/feed.proto`):

- **Selection.** A registration is in the catalog when it is listed, enabled for publishing, has a
  public description, and its publisher has published a usable manifest (a gateway feed naming this
  server and its own channel, with a display name). Unlisted, relay-only, publishing-disabled,
  manifest-less and incomplete listings are left out. Presence is not consulted: an offline
  publisher is still listed.
- **Order and pages.** Display name without case, then server ID. Page size 1–100 (default 20); the
  page token is the last key of the previous page, so a feed listed or unlisted between pages moves
  nothing across the boundary. A bad size or token is `bad_page_size` / `bad_cursor`.
- **Each item** is an explicit public DTO built field by field: server ID, gateway origin, channel,
  display name, description, the **registered** access policy (with its authentication origin),
  supported networks, required plugins, environments, protocol version and settings revision. No
  operator label or host, credential, check-in, grant, session, subscriber or device data, and no
  feed content. `TestTheContractIsBoundedAndSaysNothingAboutAnyone` pins the field list.
- **Freshness.** Nothing is cached — every call reads the store — and answers carry
  `Cache-Control: no-store`, so a listing change is on the next request without a restart.

## The app's side

- **Configuration.** The catalog gateway is `BuildConfig.DISCOVERY_URL`, from
  `-Pseekervault.discoveryUrl=https://…` and otherwise the build's `seekervault.relayUrl` (the
  gateway the build already trusts). Nothing about the catalog is hardcoded in the APK. A build with
  neither shows "No catalog configured". The tab works on a fresh install with no connection and no
  wallet.
- **States.** Loading, empty, failure with **Retry**, **Refresh** (a failed refresh keeps what was
  shown), and pagination (the next page loads as the end comes into view, or with **Load more**; a
  failed page offers **Retry**). The list position survives a card's detail sheet and a trip through
  Add connection.
- **Reading only.** Browsing, paging and opening a card's details add no connection, open no
  wallet, send no request and subscribe to nothing. The catalog call is unauthenticated and carries
  nothing about the phone.
- **Card state comes from the phone**, never from the catalog (`discover/CatalogStanding.kt`). A held
  connection is found by the repository's own duplicate rule (one gateway feed per server ID), and a
  held feed's restriction is read from the manifest it was added with. Public held feeds show
  **Connected · Open**. A restricted held feed shows its persisted access state — access not
  requested yet (**Continue**), sending the request, waiting for approval, approved, connected,
  rejected, revoked or expired — and is never shown as connected before the gateway admits the
  device. **Open**, **View** and **Continue** open the existing connection sheet over Discover.
- **Connect / Request access** opens the ordinary Add connection screen with the feed's reference
  prefilled (`FeedReferences.format`), from Discover and returning to it. Everything after is the
  existing flow: the confirmation, `ConnectionRepository.addFeed` (which reads and validates the
  current manifest, so a stale card is revalidated and a restricted listing keeps its
  restricted-access floor), duplicate detection, then the wallet picker on the new connection's
  sheet with SEE-174's network filtering. For a restricted feed, binding a wallet sends the access
  request through `FeedAccessManager` (the signed challenge is feed-access authentication, not a
  transaction); cancelling the signature, a rejection or a publisher failure leaves the card on its
  real state. Pending state is the persisted access record, so it survives restart; the existing
  20-second check and invitation redemption move it on.
- **No banner burst.** A feed added from Discover is a new connection like any other: its first
  snapshot is its backlog and raises no per-item banner ([SEE-175](in-app-notifications.md)).

## Design

The tab, the five-item navigation bar, `CatalogCard` and `CatalogDetailSheet` are specified by hand
in `design/navigation.md` and `design/components/catalog-card/spec.md`: the Stage 7.2 Claude Design
export has no Discover screen. The approved renderings are the Roborazzi goldens
`screens/discover.png`, `screens/discover-pending.png` and `catalog-card/*.png` until the design is
re-exported.

## Where the code is

- Gateway: `services/gateway/internal/gateway/catalog.go`, `internal/storage/{storage.go,sqlite,postgres}`
  (`SetListing`, `Listed`), `internal/admin/listing.go`, `cmd/feed-gatewayctl`.
- App: `apps/android/app/src/main/java/io/github/brrenat/seekervault/discover/`,
  `AppNavigation.kt` (`AppScreen.Discover`, `AppSheet.CatalogDetail`,
  `AppScreen.AddConnection(from)`), `SeekerVaultApp.kt`, `feeds/ConnectFeedGateway.kt`
  (`FeedCatalog`).
- Tests: gateway `catalog_test.go`, `storage/*/listing_test.go`, `admin/listing_test.go`,
  `feed-gatewayctl/listing_test.go`; app `discover/*Test.kt`, `DiscoverActivityTest`,
  `feeds/ConnectFeedCatalogTest`, `AppNavigationTest`. The run is recorded in
  [`docs/testing/see-176.md`](../testing/see-176.md).
