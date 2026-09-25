package gateway

import (
	"context"
	"crypto/sha256"
	"errors"
	"time"

	"connectrpc.com/connect"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/credential"
	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/pushrelay"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// Restricted feeds (SEE-156, docs/wiki/restricted-feeds.md).
//
// The gateway's part is enforcement and nothing else. A publisher decides which devices may read
// its feed — it saw the wallet proof, the device and whatever eligibility rule it applies — and
// tells this gateway the outcome over its authenticated API: a grant, with opaque references, the
// digest of a session and a finite lifetime. From then on every read of the channel is checked
// against the grants, here, whatever the phone does or fails to do:
//
//   - every snapshot page, point read and legacy proposal view needs a live grant's session;
//   - a stream ticket names a restricted channel only for a live grant, lasts no longer than the
//     grant, and names the channel's current access epoch;
//   - a revocation moves the epoch, so a listener still attached under the old stream name — or
//     reconnecting with an old ticket — hears nothing more, without the broker having to close
//     anybody's connection;
//   - a restricted channel has no public topic and no anonymous presence answer, and its hints go
//     only to the push targets of live grants.
//
// What the gateway never learns is who: no wallet, no signature, no device name. A grant is the
// publisher's statement that some device may read, and a revocation is the publisher taking it
// back.

// MostGrantIDs is how many grants one revocation may name. A wallet with more devices than this is
// revoked in more than one call.
const MostGrantIDs = 64

// MostRefBytes bounds the publisher's opaque references.
const MostRefBytes = 64

// DefaultMostGrant is how long a grant may run without renewal unless the operator configures
// otherwise: the bound on how long a device keeps access when its publisher cannot reach the gateway
// to revoke it.
const DefaultMostGrant = 24 * time.Hour

// sessionDigest is what the gateway stores and compares for a session: never the session itself,
// so a copy of the database is not a copy of anybody's access.
func sessionDigest(session string) []byte {
	sum := sha256.Sum256([]byte(session))
	return sum[:]
}

// accessOf reads a hosted channel's policy. A publisher that disappeared between the existence
// check and this read is not hosted any more.
func (f *Feed) accessOf(ctx context.Context, serverID string) (storage.Access, bool, error) {
	access, err := f.storage.Access(ctx, serverID)
	if errors.Is(err, storage.ErrNoPublisher) {
		return access, false, nil
	}
	if err != nil {
		return access, false, err
	}
	return access, true, nil
}

// admit is the check every restricted read makes. It answers the grant when the session opens a
// live one on this server's channel, and the refusal otherwise; a public channel is admitted with
// no grant at all.
//
// The order of refusals is the order a phone acts on: no session it holds (ask the publisher for
// access), a revoked one (access ended for good) and an expired one (the publisher may renew it).
func (f *Feed) admit(ctx context.Context, serverID string, access storage.Access, session string) (*storage.Grant, *connect.Error) {
	if !access.Restricted() {
		return nil, nil
	}
	if !credential.Valid(session) {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_ACCESS_REQUIRED, "session")
	}
	grant, err := f.storage.GrantFor(ctx, serverID, sessionDigest(session))
	if err != nil {
		return nil, internal(err)
	}
	switch {
	case grant == nil:
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_ACCESS_REQUIRED, "session")
	case grant.RevokedAt != nil:
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_ACCESS_REVOKED, "session")
	case !grant.Live(f.now()):
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_ACCESS_EXPIRED, "session")
	}
	return grant, nil
}

// authorize is admit for a read about one channel: it reads the policy and checks the session in
// one step, after the caller has established the channel is hosted.
func (f *Feed) authorize(ctx context.Context, channel, session string) *connect.Error {
	serverID := rules.ServerOf(channel)
	access, hosted, err := f.accessOf(ctx, serverID)
	if err != nil {
		return internal(err)
	}
	if !hosted {
		return problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_SERVER, "channel")
	}
	_, refused := f.admit(ctx, serverID, access, session)
	return refused
}

// authorizePoint is authorize for a point read. A channel this gateway does not host is left to the
// read itself, which answers that the document is not here — the same answer a point read gave
// before SEE-156.
func (f *Feed) authorizePoint(ctx context.Context, channel, session string) *connect.Error {
	serverID := rules.ServerOf(channel)
	access, hosted, err := f.accessOf(ctx, serverID)
	if err != nil {
		return internal(err)
	}
	if !hosted {
		return nil
	}
	_, refused := f.admit(ctx, serverID, access, session)
	return refused
}

// sessionsOf indexes the sessions a multi-channel call carries. A repeated channel keeps the first
// session named for it, the same rule a repeated channel in the list follows.
func sessionsOf(sessions []*gatewayv1.ChannelSession) map[string]string {
	held := make(map[string]string, len(sessions))
	for _, one := range sessions {
		if _, seen := held[one.GetChannel()]; !seen {
			held[one.GetChannel()] = one.GetSession()
		}
	}
	return held
}

// stamped is a manifest as a phone must see it: with the access policy the operator registered,
// whatever the stored document says. A feed switched to restricted after its manifest was published
// is never served as public.
func stamped(manifest *serverv1.ServerManifest, access storage.Access) *serverv1.ServerManifest {
	feed := manifest.GetFeed()
	if feed == nil {
		return manifest
	}
	want := feedAccess(access)
	if (want == nil) == (feed.GetAccess() == nil) &&
		(want == nil || (want.GetPolicy() == feed.GetAccess().GetPolicy() &&
			want.GetAuthOrigin() == feed.GetAccess().GetAuthOrigin())) {
		return manifest
	}
	copied := &serverv1.ServerManifest{
		ServerId:         manifest.GetServerId(),
		ProtocolVersion:  manifest.GetProtocolVersion(),
		SettingsRevision: manifest.GetSettingsRevision(),
		Mode:             manifest.GetMode(),
		RequiredPlugins:  manifest.GetRequiredPlugins(),
		Environments:     manifest.GetEnvironments(),
		DisplayName:      manifest.GetDisplayName(),
		Reference: &serverv1.ServerManifest_Feed{Feed: &serverv1.GatewayFeed{
			GatewayUrl: feed.GetGatewayUrl(),
			Channel:    feed.GetChannel(),
			Access:     want,
		}},
	}
	return copied
}

// feedAccess is the wire form of a registration's policy. A public feed carries none, so its
// manifest is byte for byte what every manifest was before SEE-156.
func feedAccess(access storage.Access) *serverv1.FeedAccess {
	if !access.Restricted() {
		return nil
	}
	return &serverv1.FeedAccess{
		Policy:     serverv1.FeedAccessPolicy_FEED_ACCESS_POLICY_RESTRICTED,
		AuthOrigin: access.AuthOrigin,
	}
}

// described is the policy as a publisher is told it: explicit either way, so a restricted publisher
// can tell "public" from "a gateway that has never heard of restricted feeds", which answers
// nothing.
func described(access storage.Access) *serverv1.FeedAccess {
	if access.Restricted() {
		return feedAccess(access)
	}
	return &serverv1.FeedAccess{Policy: serverv1.FeedAccessPolicy_FEED_ACCESS_POLICY_PUBLIC}
}

// declaredAccessFits says whether what a publisher's manifest claims about its access agrees with
// the operator's registration. A restricted feed must say so and name the registered origin, so a
// publisher that forgot cannot be served as public by omission; a public feed may say nothing or
// say public, and must not name an origin nobody registered.
func declaredAccessFits(declared *serverv1.FeedAccess, registered storage.Access) bool {
	if registered.Restricted() {
		return declared.GetPolicy() == serverv1.FeedAccessPolicy_FEED_ACCESS_POLICY_RESTRICTED &&
			declared.GetAuthOrigin() == registered.AuthOrigin
	}
	if declared == nil {
		return true
	}
	return declared.GetPolicy() == serverv1.FeedAccessPolicy_FEED_ACCESS_POLICY_PUBLIC &&
		declared.GetAuthOrigin() == ""
}

// SetFeedPushTarget registers where one grant's hints go.
func (f *Feed) SetFeedPushTarget(
	ctx context.Context,
	request *connect.Request[gatewayv1.SetFeedPushTargetRequest],
) (*connect.Response[gatewayv1.SetFeedPushTargetResponse], error) {
	if f.targets == nil {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_PUSH, "")
	}
	channel := request.Msg.GetChannel()
	serverID := rules.ServerOf(channel)
	if serverID == "" {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID, "channel")
	}
	target := request.Msg.GetPushTarget()
	if target != "" && !pushrelay.ValidTarget(target) {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_VALUE, "push_target")
	}
	access, hosted, err := f.accessOf(ctx, serverID)
	if err != nil {
		return nil, internal(err)
	}
	if !hosted {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_SERVER, "channel")
	}
	if !access.Restricted() {
		// A public channel's hints go to its topic, which nobody registers for here.
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NOT_RESTRICTED, "channel")
	}
	if _, refused := f.admit(ctx, serverID, access, request.Msg.GetSession()); refused != nil {
		return nil, refused
	}
	err = f.targets.SetPushTarget(ctx, serverID, sessionDigest(request.Msg.GetSession()), target, f.now())
	if errors.Is(err, storage.ErrNoGrant) {
		// Revoked or expired between the check and the write.
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_ACCESS_REQUIRED, "session")
	}
	if err != nil {
		return nil, internal(err)
	}
	return uncached(connect.NewResponse(&gatewayv1.SetFeedPushTargetResponse{})), nil
}

// WithPushTargets lets the read API record where a restricted grant's hints go. A deployment that
// relays nothing leaves it unset, and a device asking is told this gateway has no push.
func (f *Feed) WithPushTargets(targets storage.AccessStore) *Feed {
	f.targets = targets
	return f
}

// --- the publisher's half -----------------------------------------------------

// Access is what the publisher API needs to enforce a restricted feed.
type Access struct {
	Store storage.AccessStore
	// Read is the policy as it stands, outside a publication.
	Read func(context.Context, string) (storage.Access, error)
	// MostGrant is the longest a grant may run without renewal.
	MostGrant time.Duration
	// Revoked is told, after a revocation commits, which push targets the revoked grants held, so
	// a best-effort hint can make those devices look sooner. It must not block.
	Revoked func([]storage.PushTarget)
	// Wake asks the fan-out to send the notice a revocation wrote.
	Wake func()
}

// WithAccess enables the restricted-feed methods of the publisher API.
func (p *Publisher) WithAccess(access Access) *Publisher {
	if access.MostGrant <= 0 {
		access.MostGrant = DefaultMostGrant
	}
	if access.Revoked == nil {
		access.Revoked = func([]storage.PushTarget) {}
	}
	if access.Wake == nil {
		access.Wake = func() {}
	}
	p.access = &access
	return p
}

// DescribeAccess answers the policy the gateway enforces for the caller's feed.
func (p *Publisher) DescribeAccess(
	ctx context.Context,
	_ *connect.Request[gatewayv1.DescribeAccessRequest],
) (*connect.Response[gatewayv1.DescribeAccessResponse], error) {
	if p.access == nil {
		return nil, connect.NewError(connect.CodeUnimplemented, errors.New("restricted feeds are not served here"))
	}
	access, err := p.access.Read(ctx, publisherOf(ctx))
	if err != nil {
		return nil, internal(err)
	}
	return connect.NewResponse(&gatewayv1.DescribeAccessResponse{
		Access:           described(access),
		MostGrantSeconds: uint32(p.access.MostGrant.Seconds()),
	}), nil
}

// GrantAccess records or renews one grant on the caller's own channel.
func (p *Publisher) GrantAccess(
	ctx context.Context,
	request *connect.Request[gatewayv1.GrantAccessRequest],
) (*connect.Response[gatewayv1.GrantAccessResponse], error) {
	if p.access == nil {
		return nil, connect.NewError(connect.CodeUnimplemented, errors.New("restricted feeds are not served here"))
	}
	asked := request.Msg
	switch {
	case !rules.IsID(asked.GetGrantId()):
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_GRANT, "grant_id")
	case !reference(asked.GetSubscriberRef()):
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_GRANT, "subscriber_ref")
	case !reference(asked.GetDeviceRef()):
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_GRANT, "device_ref")
	case len(asked.GetSessionDigest()) != sha256.Size:
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_GRANT, "session_digest")
	case asked.GetLifetimeSeconds() == 0:
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_GRANT, "lifetime_seconds")
	}
	lifetime := min(time.Duration(asked.GetLifetimeSeconds())*time.Second, p.access.MostGrant)
	now := p.now()
	grant, err := p.access.Store.PutGrant(ctx, storage.GrantRequest{
		GrantID:       asked.GetGrantId(),
		ServerID:      publisherOf(ctx),
		SubscriberRef: asked.GetSubscriberRef(),
		DeviceRef:     asked.GetDeviceRef(),
		SessionDigest: asked.GetSessionDigest(),
		At:            now,
		ExpiresAt:     now.Add(lifetime),
	})
	switch {
	case errors.Is(err, storage.ErrNotRestricted):
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NOT_RESTRICTED, "")
	case errors.Is(err, storage.ErrNoGrant):
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_GRANT, "grant_id")
	case errors.Is(err, storage.ErrGrantRevoked):
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_GRANT_REVOKED, "grant_id")
	case errors.Is(err, storage.ErrGrantMismatch):
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_GRANT, "session_digest")
	case err != nil:
		return nil, internal(err)
	}
	return connect.NewResponse(&gatewayv1.GrantAccessResponse{
		LifetimeSeconds: uint32(grant.ExpiresAt.Sub(now).Seconds()),
	}), nil
}

// RevokeAccess ends grants on the caller's own channel.
func (p *Publisher) RevokeAccess(
	ctx context.Context,
	request *connect.Request[gatewayv1.RevokeAccessRequest],
) (*connect.Response[gatewayv1.RevokeAccessResponse], error) {
	if p.access == nil {
		return nil, connect.NewError(connect.CodeUnimplemented, errors.New("restricted feeds are not served here"))
	}
	ids := request.Msg.GetGrantIds()
	if len(ids) == 0 || len(ids) > MostGrantIDs {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_GRANT, "grant_ids")
	}
	seen := make(map[string]bool, len(ids))
	unique := make([]string, 0, len(ids))
	for _, id := range ids {
		if !rules.IsID(id) {
			return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_GRANT, "grant_ids")
		}
		if !seen[id] {
			seen[id] = true
			unique = append(unique, id)
		}
	}
	revoked, targets, err := p.access.Store.RevokeGrants(ctx, publisherOf(ctx), unique, p.now())
	switch {
	case errors.Is(err, storage.ErrNoGrant):
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_GRANT, "grant_ids")
	case err != nil:
		return nil, internal(err)
	}
	if revoked > 0 {
		p.access.Wake()
		if len(targets) > 0 {
			p.access.Revoked(targets)
		}
	}
	return connect.NewResponse(&gatewayv1.RevokeAccessResponse{Revoked: uint32(revoked)}), nil
}

// reference is the shape of a publisher's opaque reference: short printable ASCII, which is all a
// label for somebody else's record needs to be and nothing that could smuggle a document.
func reference(value string) bool {
	if value == "" || len(value) > MostRefBytes {
		return false
	}
	for index := range len(value) {
		if value[index] < 0x21 || value[index] > 0x7e {
			return false
		}
	}
	return true
}
