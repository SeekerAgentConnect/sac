// The Discover catalog (SEE-176): which registrations the gateway recommends to a phone, what it
// says about each, and how the list pages — driven over the real read handler and the real store.
package gateway_test

import (
	"context"
	"crypto/sha256"
	"strings"
	"testing"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/protobuf/encoding/protojson"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

const (
	catalogC = "c0000000-0000-4000-8000-00000000000c"
	catalogD = "d0000000-0000-4000-8000-00000000000d"
	catalogE = "e0000000-0000-4000-8000-00000000000e"
	catalogF = "f0000000-0000-4000-8000-00000000000f"
	catalogG = "a0000000-0000-4000-8000-0000000000a1"
)

// listed registers a feed publisher, publishes its manifest under the given name, and lists it with
// a description.
func (h *harness) listed(serverID, name, description string) {
	h.t.Helper()
	publisher := h.publisher(h.register(serverID))
	h.publishManifest(publisher, manifestOf(serverID, 1, func(manifest *serverv1.ServerManifest) {
		manifest.DisplayName = name
	}))
	h.setListing(serverID, storage.Listing{Recommended: true, Description: description})
}

func (h *harness) setListing(serverID string, listing storage.Listing) {
	h.t.Helper()
	if err := h.documents.SetListing(context.Background(), serverID, listing); err != nil {
		h.t.Fatal(err)
	}
}

func (h *harness) catalog(size uint32, token string) (*gatewayv1.ListRecommendedFeedsResponse, error) {
	answer, err := h.feed.ListRecommendedFeeds(context.Background(),
		connect.NewRequest(&gatewayv1.ListRecommendedFeedsRequest{PageSize: size, PageToken: token}))
	if err != nil {
		return nil, err
	}
	if cache := answer.Header().Get("Cache-Control"); cache != "no-store" {
		h.t.Fatalf("the catalog may be cached in between: %q", cache)
	}
	return answer.Msg, nil
}

func (h *harness) wholeCatalog() []*gatewayv1.RecommendedFeed {
	h.t.Helper()
	answer, err := h.catalog(0, "")
	if err != nil {
		h.t.Fatal(err)
	}
	if answer.GetNextPageToken() != "" {
		h.t.Fatalf("a small catalog came in more than one page")
	}
	return answer.GetFeeds()
}

func serverIDs(feeds []*gatewayv1.RecommendedFeed) []string {
	var ids []string
	for _, feed := range feeds {
		ids = append(ids, feed.GetServerId())
	}
	return ids
}

func TestTheCatalogIsEveryCompleteListingAndNothingElse(t *testing.T) {
	gateway := newGateway(t)
	ctx := context.Background()

	// Listed, public, complete.
	gateway.listed(publisherA, "Alpha signals", "Daily swap ideas.")
	// Listed and restricted: it is in the catalog, and it is still restricted.
	gateway.restricted(publisherB)
	gateway.setListing(publisherB, storage.Listing{Recommended: true, Description: "Members only."})
	// Published, described, but never listed: the default.
	publisherC := gateway.publisher(gateway.register(catalogC))
	gateway.publishManifest(publisherC, manifestOf(catalogC, 1))
	gateway.setListing(catalogC, storage.Listing{Description: "Not opted in."})
	// Listed, but with no public description yet.
	publisherD := gateway.publisher(gateway.register(catalogD))
	gateway.publishManifest(publisherD, manifestOf(catalogD, 1))
	gateway.setListing(catalogD, storage.Listing{Recommended: true})
	// Listed and described, but its publisher has published no manifest.
	gateway.register(catalogE)
	gateway.setListing(catalogE, storage.Listing{Recommended: true, Description: "Nothing yet."})
	// Listed and complete, then its publishing was switched off.
	gateway.listed(catalogF, "Paused", "Switched off.")
	if err := gateway.documents.SetCapabilities(ctx, catalogF, false, false); err != nil {
		t.Fatal(err)
	}
	// A relay-only registration that somehow carries the flag has no feed to recommend.
	sum := sha256.Sum256([]byte("relay only"))
	if _, err := gateway.documents.Register(ctx, storage.Registration{
		ServerID: catalogG, Label: "relay", Relaying: true,
		Listing: storage.Listing{Recommended: true, Description: "Push only."},
	}, storage.Relaying, sum[:], gateway.now()); err != nil {
		t.Fatal(err)
	}

	feeds := gateway.wholeCatalog()
	if got := serverIDs(feeds); strings.Join(got, ",") != publisherA+","+publisherB {
		t.Fatalf("the catalog holds %v, expected only the two complete listings", got)
	}

	public, restricted := feeds[0], feeds[1]
	if public.GetDisplayName() != "Alpha signals" || public.GetDescription() != "Daily swap ideas." ||
		public.GetGatewayUrl() != gatewayURL || public.GetChannel() != rules.ChannelFor(publisherA) ||
		public.GetAccess().GetPolicy() != serverv1.FeedAccessPolicy_FEED_ACCESS_POLICY_PUBLIC ||
		public.GetAccess().GetAuthOrigin() != "" {
		t.Fatalf("the public listing says %v", public)
	}
	if len(public.GetSupportedNetworks()) != 1 ||
		public.GetSupportedNetworks()[0] != serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET ||
		len(public.GetRequiredPlugins()) != 1 ||
		public.GetRequiredPlugins()[0].GetPluginId() != "jupiter.swap" ||
		len(public.GetEnvironments()) != 1 || public.GetProtocolVersion() != rules.Protocol ||
		public.GetSettingsRevision() != 1 {
		t.Fatalf("the public listing's compatibility metadata is %v", public)
	}
	if restricted.GetAccess().GetPolicy() != serverv1.FeedAccessPolicy_FEED_ACCESS_POLICY_RESTRICTED ||
		restricted.GetAccess().GetAuthOrigin() != authOrigin ||
		restricted.GetDescription() != "Members only." {
		t.Fatalf("the restricted listing says %v", restricted)
	}

	// Listing a restricted feed opened nothing: its snapshot still needs a grant.
	refusals := gateway.readAll(rules.ChannelFor(publisherB), "")
	for path, err := range refusals {
		if err == nil {
			t.Fatalf("a listed restricted feed answered %s without a grant", path)
		}
	}
}

// What a catalog item is built from is the manifest and the listing, field by field. Nothing the
// operator keeps for themselves and nothing about a grant or a credential can reach it.
func TestTheCatalogCarriesNoOperatorOrReaderData(t *testing.T) {
	gateway := newGateway(t)
	ctx := context.Background()
	sum := sha256.Sum256([]byte("a publishing credential"))
	if _, err := gateway.documents.Register(ctx, storage.Registration{
		ServerID: publisherA, Label: "operator-only label", Host: "https://backend.internal.example",
		Publishing: true,
	}, storage.Publishing, sum[:], gateway.now()); err != nil {
		t.Fatal(err)
	}
	publisher := gateway.publisher(gateway.register(publisherA))
	if err := gateway.documents.SetAccess(ctx, publisherA, storage.Access{
		Policy: storage.RestrictedAccess, AuthOrigin: authOrigin,
	}); err != nil {
		t.Fatal(err)
	}
	gateway.publishManifest(publisher, manifestOf(publisherA, 1, restrictedManifest))
	session := newSession(t)
	gateway.grant(publisher, grantOne, session, time.Hour)
	gateway.setListing(publisherA, storage.Listing{Recommended: true, Description: "Public words."})

	answer, err := gateway.catalog(0, "")
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := protojson.Marshal(answer)
	if err != nil {
		t.Fatal(err)
	}
	for _, private := range []string{
		"operator-only label", "backend.internal.example", session, grantOne,
		"subscriber", "device", "grant", "label", "host",
	} {
		if strings.Contains(string(encoded), private) {
			t.Fatalf("the catalog carries %q: %s", private, encoded)
		}
	}
}

func TestTheCatalogPagesInNameOrderAndSurvivesChangesBetweenPages(t *testing.T) {
	gateway := newGateway(t)
	// Out of order, with two names that differ only in case, so the server ID has to break the tie.
	gateway.listed(catalogE, "delta", "d")
	gateway.listed(catalogC, "Alpha", "a")
	gateway.listed(publisherB, "beta", "b")
	gateway.listed(catalogD, "alpha", "a2")
	gateway.listed(publisherA, "Gamma", "g")
	expected := []string{catalogC, catalogD, publisherB, catalogE, publisherA}
	if got := serverIDs(gateway.wholeCatalog()); strings.Join(got, ",") != strings.Join(expected, ",") {
		t.Fatalf("the catalog's order is %v, expected %v", got, expected)
	}

	first, err := gateway.catalog(2, "")
	if err != nil {
		t.Fatal(err)
	}
	if got := serverIDs(first.GetFeeds()); strings.Join(got, ",") != catalogC+","+catalogD ||
		first.GetNextPageToken() == "" {
		t.Fatalf("the first page is %v with token %q", got, first.GetNextPageToken())
	}
	// The last feed of the first page is unlisted between pages, and one that sorts before the
	// boundary is listed: neither moves anything after the boundary.
	gateway.setListing(catalogD, storage.Listing{Description: "a2"})
	gateway.listed(catalogF, "Aardvark", "early")
	second, err := gateway.catalog(2, first.GetNextPageToken())
	if err != nil {
		t.Fatal(err)
	}
	if got := serverIDs(second.GetFeeds()); strings.Join(got, ",") != publisherB+","+catalogE {
		t.Fatalf("the second page is %v", got)
	}
	third, err := gateway.catalog(2, second.GetNextPageToken())
	if err != nil {
		t.Fatal(err)
	}
	if got := serverIDs(third.GetFeeds()); strings.Join(got, ",") != publisherA ||
		third.GetNextPageToken() != "" {
		t.Fatalf("the last page is %v with token %q", got, third.GetNextPageToken())
	}
}

func TestTheCatalogRefusesABadPage(t *testing.T) {
	gateway := newGateway(t)
	_, err := gateway.catalog(101, "")
	refused(t, err, connect.CodeInvalidArgument, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_PAGE_SIZE)
	for _, token := range []string{"not base64!", "c3RyYW5nZQ", "YzEfbm90LWEtdXVpZB9uYW1l"} {
		_, err = gateway.catalog(0, token)
		refused(t, err, connect.CodeInvalidArgument, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_CURSOR)
	}
	// A snapshot cursor is not a catalog cursor.
	walk := gateway.catalogWithSnapshotCursor()
	_, err = gateway.catalog(0, walk)
	refused(t, err, connect.CodeInvalidArgument, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_CURSOR)
}

func (h *harness) catalogWithSnapshotCursor() string {
	h.t.Helper()
	publisher := h.publisher(h.register(catalogG))
	h.publishManifest(publisher, manifestOf(catalogG, 1))
	h.publishProposal(publisher, proposalOf(catalogG, proposalA, 1))
	h.publishProposal(publisher, proposalOf(catalogG, proposalB, 1))
	page := h.list(rules.ChannelFor(catalogG), func(request *gatewayv1.ListProposalsRequest) {
		request.PageSize = 1
	})
	if page.GetNextPageToken() == "" {
		h.t.Fatal("the feed read had no second page")
	}
	return page.GetNextPageToken()
}

// The listing is the operator's and the access policy is the operator's, and each moves without
// the other. Neither is reset by what a publisher publishes, and every change is on the next read.
func TestListingAndAccessAreSeparateAndNeitherIsResetByARepublication(t *testing.T) {
	gateway := newGateway(t)
	ctx := context.Background()
	publisher := gateway.publisher(gateway.register(publisherA))
	gateway.publishManifest(publisher, manifestOf(publisherA, 1))
	gateway.setListing(publisherA, storage.Listing{Recommended: true, Description: "First words."})
	before, err := gateway.documents.Access(ctx, publisherA)
	if err != nil {
		t.Fatal(err)
	}

	// Editing the description is on the next read, and moves no access epoch.
	gateway.setListing(publisherA, storage.Listing{Recommended: true, Description: "Second words."})
	if feeds := gateway.wholeCatalog(); len(feeds) != 1 || feeds[0].GetDescription() != "Second words." {
		t.Fatalf("the edited description is not served: %v", feeds)
	}
	after, err := gateway.documents.Access(ctx, publisherA)
	if err != nil || after != before {
		t.Fatalf("a listing change moved the access policy from %+v to %+v (%v)", before, after, err)
	}

	// Restricting the feed changes its card's policy and not its listing.
	if err := gateway.documents.SetAccess(ctx, publisherA, storage.Access{
		Policy: storage.RestrictedAccess, AuthOrigin: authOrigin,
	}); err != nil {
		t.Fatal(err)
	}
	feeds := gateway.wholeCatalog()
	if len(feeds) != 1 ||
		feeds[0].GetAccess().GetPolicy() != serverv1.FeedAccessPolicy_FEED_ACCESS_POLICY_RESTRICTED {
		t.Fatalf("a restricted feed is described as %v", feeds)
	}

	// The publisher republishes under a new name: the listing and description stay.
	gateway.publishManifest(publisher, manifestOf(publisherA, 2, restrictedManifest,
		func(manifest *serverv1.ServerManifest) { manifest.DisplayName = "Renamed" }))
	held, err := gateway.documents.Publisher(ctx, publisherA)
	if err != nil || !held.Listing.Recommended || held.Listing.Description != "Second words." {
		t.Fatalf("a republication reset the listing: %+v, %v", held, err)
	}
	if feeds := gateway.wholeCatalog(); len(feeds) != 1 || feeds[0].GetDisplayName() != "Renamed" ||
		feeds[0].GetSettingsRevision() != 2 {
		t.Fatalf("the catalog does not follow the current manifest: %v", feeds)
	}

	// Unlisting takes it out of the next read, and the feed is still served to whoever holds it.
	gateway.setListing(publisherA, storage.Listing{Description: "Second words."})
	if feeds := gateway.wholeCatalog(); len(feeds) != 0 {
		t.Fatalf("an unlisted feed is still recommended: %v", feeds)
	}
	manifest, err := gateway.feed.GetServerManifest(ctx,
		connect.NewRequest(&gatewayv1.GetServerManifestRequest{ServerId: publisherA}))
	if err != nil || manifest.Msg.GetManifest().GetDisplayName() != "Renamed" {
		t.Fatalf("an unlisted feed is no longer served: %v", err)
	}
}

// A publisher that is not running is still a feed its operator listed.
func TestAnOfflinePublisherStaysInTheCatalog(t *testing.T) {
	gateway := newGateway(t)
	gateway.listed(publisherA, "Quiet", "Not checked in for a while.")
	gateway.at(published.Add(365 * 24 * time.Hour))
	if feeds := gateway.wholeCatalog(); len(feeds) != 1 {
		t.Fatalf("an offline publisher left the catalog: %v", feeds)
	}
}
