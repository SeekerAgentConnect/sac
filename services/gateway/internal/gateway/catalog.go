package gateway

import (
	"cmp"
	"context"
	"encoding/base64"
	"slices"
	"strings"

	"connectrpc.com/connect"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// How many catalog entries a page holds when the caller does not say, and the most it may ask for
// (SEE-176). A catalog page is a screenful of cards, not a sync.
const (
	DefaultCatalogPage = 20
	MostCatalogPage    = 100
)

// ListRecommendedFeeds is the app's Discover catalog (SEE-176): the feeds this gateway's operator
// listed, as public onboarding metadata, one page at a time.
//
// A feed is in it when the operator listed it with a public description, it may publish, and its
// publisher has published a usable manifest: a gateway feed that names this server and its own
// channel and has a display name. A relay-only registration has no feed and is never in it. The
// check-in is not consulted — a publisher that is offline right now is still a feed its operator
// chose to list, and what it last published is still served.
//
// Every item is built field by field from the manifest and the registration's listing and access,
// never by copying a stored record, so nothing the operator keeps for themselves — a label, a
// host, a credential, a grant — can reach it by a later change to either. The access policy is the
// registration's, as a manifest read's is: no card describes a feed as more open than it is.
//
// The catalog is read from the store on every call and nothing is cached, so listing, unlisting or
// editing a description shows on the next request without a restart.
func (f *Feed) ListRecommendedFeeds(
	ctx context.Context,
	request *connect.Request[gatewayv1.ListRecommendedFeedsRequest],
) (*connect.Response[gatewayv1.ListRecommendedFeedsResponse], error) {
	size := int(request.Msg.GetPageSize())
	switch {
	case size == 0:
		size = DefaultCatalogPage
	case size < 0 || size > MostCatalogPage:
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_PAGE_SIZE, "page_size")
	}
	var after *catalogKey
	if token := request.Msg.GetPageToken(); token != "" {
		key, ok := decodeCatalogCursor(token)
		if !ok {
			return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_CURSOR, "page_token")
		}
		after = &key
	}

	listed, err := f.storage.Listed(ctx)
	if err != nil {
		return nil, internal(err)
	}
	catalog := make([]*gatewayv1.RecommendedFeed, 0, len(listed))
	for _, candidate := range listed {
		if item := recommended(candidate); item != nil {
			catalog = append(catalog, item)
		}
	}
	slices.SortFunc(catalog, func(a, b *gatewayv1.RecommendedFeed) int {
		return keyOf(a).compare(keyOf(b))
	})

	start := 0
	if after != nil {
		start, _ = slices.BinarySearchFunc(catalog, *after,
			func(item *gatewayv1.RecommendedFeed, key catalogKey) int {
				return keyOf(item).compare(key)
			})
		// A key that is still in the catalog is where the previous page ended, so this page
		// starts after it; a key that was unlisted meanwhile is simply a position.
		if start < len(catalog) && keyOf(catalog[start]) == *after {
			start++
		}
	}
	end := min(start+size, len(catalog))
	answer := &gatewayv1.ListRecommendedFeedsResponse{Feeds: catalog[start:end]}
	if end < len(catalog) && end > start {
		answer.NextPageToken = encodeCatalogCursor(keyOf(catalog[end-1]))
	}
	return uncached(connect.NewResponse(answer)), nil
}

// recommended is one catalog item, or nil when the candidate's manifest is not one a phone could
// onboard from.
func recommended(candidate storage.ListedFeed) *gatewayv1.RecommendedFeed {
	manifest := candidate.Manifest
	feed := manifest.GetFeed()
	name := strings.TrimSpace(manifest.GetDisplayName())
	description := strings.TrimSpace(candidate.Description)
	channel := rules.ChannelFor(candidate.ServerID)
	switch {
	case !rules.IsID(candidate.ServerID),
		manifest.GetServerId() != candidate.ServerID,
		manifest.GetMode() != serverv1.ConnectionMode_CONNECTION_MODE_GATEWAY_FEED,
		feed == nil,
		feed.GetGatewayUrl() == "",
		feed.GetChannel() != channel,
		name == "",
		description == "",
		!candidate.Access.Valid():
		return nil
	}
	return &gatewayv1.RecommendedFeed{
		ServerId:          candidate.ServerID,
		GatewayUrl:        feed.GetGatewayUrl(),
		Channel:           channel,
		DisplayName:       name,
		Description:       description,
		Access:            described(candidate.Access),
		SupportedNetworks: slices.Clone(feed.GetSupportedNetworks()),
		RequiredPlugins:   clonePlugins(manifest.GetRequiredPlugins()),
		Environments:      slices.Clone(manifest.GetEnvironments()),
		ProtocolVersion:   manifest.GetProtocolVersion(),
		SettingsRevision:  manifest.GetSettingsRevision(),
	}
}

func clonePlugins(plugins []*serverv1.PluginRequirement) []*serverv1.PluginRequirement {
	cloned := make([]*serverv1.PluginRequirement, 0, len(plugins))
	for _, plugin := range plugins {
		cloned = append(cloned, &serverv1.PluginRequirement{
			PluginId:    plugin.GetPluginId(),
			MinContract: plugin.GetMinContract(),
			MaxContract: plugin.GetMaxContract(),
		})
	}
	return cloned
}

// catalogKey is a feed's place in the catalog: its display name without case, then its server ID,
// which is unique and so breaks every tie.
type catalogKey struct {
	name     string
	serverID string
}

func keyOf(item *gatewayv1.RecommendedFeed) catalogKey {
	return catalogKey{name: strings.ToLower(item.GetDisplayName()), serverID: item.GetServerId()}
}

func (k catalogKey) compare(other catalogKey) int {
	if order := cmp.Compare(k.name, other.name); order != 0 {
		return order
	}
	return cmp.Compare(k.serverID, other.serverID)
}

// A catalog page token is the key of the last feed the previous page returned. Like a snapshot
// cursor it is opaque and unsigned, and there is nothing to gain by writing one: every position in
// the catalog is one a caller could reach by asking for pages in turn.
const catalogCursorVersion = "c1"

func encodeCatalogCursor(key catalogKey) string {
	return base64.RawURLEncoding.EncodeToString([]byte(
		strings.Join([]string{catalogCursorVersion, key.serverID, key.name}, cursorSeparator)))
}

func decodeCatalogCursor(token string) (catalogKey, bool) {
	raw, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil {
		return catalogKey{}, false
	}
	parts := strings.SplitN(string(raw), cursorSeparator, 3)
	if len(parts) != 3 || parts[0] != catalogCursorVersion || !rules.IsID(parts[1]) {
		return catalogKey{}, false
	}
	return catalogKey{name: parts[2], serverID: parts[1]}, true
}
