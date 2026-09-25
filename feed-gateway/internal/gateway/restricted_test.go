// Restricted feeds (SEE-156): the gateway's half, driven over the real handlers.
//
// The publisher decides which devices may read; these tests stand in for it with its real API —
// GrantAccess and RevokeAccess under its own credential — and then read as a phone does, with and
// without the session a grant was made for. What they pin is that every path a phone could read a
// restricted feed by is checked, that a revocation ends all of them including a stream already
// open, and that nothing about public feeds moved.
package gateway_test

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"testing"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/protobuf/proto"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/config"
	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1/gatewayv1connect"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/relay"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/stream"
)

const (
	authOrigin = "https://auth.copytrading.example.com"
	grantOne   = "0b8f6f7e-1c2d-4e3f-8a9b-0c1d2e3f4a5b"
	grantTwo   = "1c9a7a8f-2d3e-4f4a-9b0c-1d2e3f4a5b6c"
	grantThree = "2dab8b90-3e4f-4a5b-8c1d-2e3f4a5b6c7d"
)

// newSession is a session as a publisher mints one: 32 random bytes, written the way a credential
// is. The gateway never sees it except in a reader's request.
func newSession(t *testing.T) string {
	t.Helper()
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		t.Fatal(err)
	}
	return base64.RawURLEncoding.EncodeToString(raw)
}

func digestOf(session string) []byte {
	sum := sha256.Sum256([]byte(session))
	return sum[:]
}

// restricted registers a publisher whose feed is restricted, publishes its manifest and one
// proposal, and answers its publisher client.
func (h *harness) restricted(serverID string) gatewayv1connect.PublisherServiceClient {
	h.t.Helper()
	publisher := h.publisher(h.register(serverID))
	if err := h.documents.SetAccess(context.Background(), serverID, storage.Access{
		Policy: storage.RestrictedAccess, AuthOrigin: authOrigin,
	}); err != nil {
		h.t.Fatal(err)
	}
	answer := h.publishManifest(publisher, manifestOf(serverID, 1, restrictedManifest))
	if answer.GetAccess().GetPolicy() != serverv1.FeedAccessPolicy_FEED_ACCESS_POLICY_RESTRICTED {
		h.t.Fatalf("the gateway did not confirm the restricted policy: %v", answer.GetAccess())
	}
	h.publishProposal(publisher, proposalOf(serverID, proposalA, 1))
	return publisher
}

func restrictedManifest(manifest *serverv1.ServerManifest) {
	manifest.GetFeed().Access = &serverv1.FeedAccess{
		Policy:     serverv1.FeedAccessPolicy_FEED_ACCESS_POLICY_RESTRICTED,
		AuthOrigin: authOrigin,
	}
}

func (h *harness) grant(as gatewayv1connect.PublisherServiceClient, grantID, session string, lifetime time.Duration) *gatewayv1.GrantAccessResponse {
	h.t.Helper()
	response, err := as.GrantAccess(context.Background(), connect.NewRequest(&gatewayv1.GrantAccessRequest{
		GrantId:         grantID,
		SubscriberRef:   "subscriber-1",
		DeviceRef:       "device-" + grantID[:8],
		SessionDigest:   digestOf(session),
		LifetimeSeconds: uint32(lifetime.Seconds()),
	}))
	if err != nil {
		h.t.Fatalf("granting failed: %v", err)
	}
	return response.Msg
}

func (h *harness) revoke(as gatewayv1connect.PublisherServiceClient, grantIDs ...string) uint32 {
	h.t.Helper()
	response, err := as.RevokeAccess(context.Background(),
		connect.NewRequest(&gatewayv1.RevokeAccessRequest{GrantIds: grantIDs}))
	if err != nil {
		h.t.Fatalf("revoking failed: %v", err)
	}
	return response.Msg.GetRevoked()
}

// readAll makes every read a phone can make of one channel, under one session, and answers the
// error of each — nil for the ones that were served.
func (h *harness) readAll(channel, session string) map[string]error {
	ctx := context.Background()
	errs := map[string]error{}
	_, errs["ListRequests"] = h.feed.ListRequests(ctx, connect.NewRequest(
		&gatewayv1.ListRequestsRequest{Channel: channel, Session: session}))
	_, errs["ListRequests(page)"] = h.feed.ListRequests(ctx, connect.NewRequest(
		&gatewayv1.ListRequestsRequest{Channel: channel, Session: session, PageSize: 1}))
	_, errs["GetRequest"] = h.feed.GetRequest(ctx, connect.NewRequest(
		&gatewayv1.GetRequestRequest{Channel: channel, RequestId: proposalA, Session: session}))
	_, errs["ListProposals"] = h.feed.ListProposals(ctx, connect.NewRequest(
		&gatewayv1.ListProposalsRequest{Channel: channel, Session: session}))
	_, errs["GetProposal"] = h.feed.GetProposal(ctx, connect.NewRequest(
		&gatewayv1.GetProposalRequest{Channel: channel, ProposalId: proposalA, Session: session}))
	return errs
}

func (h *harness) ticketWith(channel, session string, others ...string) (*gatewayv1.GetStreamTicketResponse, error) {
	request := &gatewayv1.GetStreamTicketRequest{Channels: append([]string{channel}, others...)}
	if session != "" {
		request.Sessions = []*gatewayv1.ChannelSession{{Channel: channel, Session: session}}
	}
	response, err := h.feed.GetStreamTicket(context.Background(), connect.NewRequest(request))
	if err != nil {
		return nil, err
	}
	return response.Msg, nil
}

func streamNameOf(answer *gatewayv1.GetStreamTicketResponse, channel string) string {
	for _, one := range answer.GetChannels() {
		if one.GetChannel() == channel {
			return one.GetStreamChannel()
		}
	}
	return ""
}

// Anyone may learn that a restricted feed exists and where to prove themselves; nobody without a
// grant may read anything else — not the current documents, not a later page, not the legacy
// proposal view an older client uses, not a stream ticket, not a topic and not whether its
// publisher is running.
func TestARestrictedFeedServesOnlyItsOnboardingMetadataWithoutAGrant(t *testing.T) {
	gateway := newGateway(t)
	gateway.restricted(publisherA)
	channel := rules.ChannelFor(publisherA)
	ctx := context.Background()

	manifest, err := gateway.feed.GetServerManifest(ctx, connect.NewRequest(
		&gatewayv1.GetServerManifestRequest{ServerId: publisherA}))
	if err != nil {
		t.Fatal(err)
	}
	access := manifest.Msg.GetManifest().GetFeed().GetAccess()
	if access.GetPolicy() != serverv1.FeedAccessPolicy_FEED_ACCESS_POLICY_RESTRICTED ||
		access.GetAuthOrigin() != authOrigin {
		t.Fatalf("the manifest does not say how to onboard: %v", access)
	}

	for _, session := range []string{"", newSession(t), "not-a-session"} {
		for read, err := range gateway.readAll(channel, session) {
			if err == nil {
				t.Fatalf("%s was served without a grant (session %q)", read, session)
			}
			refused(t, err, connect.CodePermissionDenied,
				gatewayv1.GatewayProblem_GATEWAY_PROBLEM_ACCESS_REQUIRED)
		}
		_, err := gateway.ticketWith(channel, session)
		refused(t, err, connect.CodePermissionDenied, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_ACCESS_REQUIRED)
	}
	// Asked beside a public feed, the restricted channel is left out and the public one kept.
	public := gateway.publisher(gateway.register(publisherB))
	gateway.publishManifest(public, manifestOf(publisherB, 1))
	answer, err := gateway.ticketWith(channel, "", rules.ChannelFor(publisherB))
	if err != nil {
		t.Fatal(err)
	}
	if streamNameOf(answer, channel) != "" || streamNameOf(answer, rules.ChannelFor(publisherB)) == "" {
		t.Fatalf("the ticket granted %v", answer.GetChannels())
	}
	if topics := gateway.namedTopics(channel, rules.ChannelFor(publisherB)).GetTopics(); len(topics) != 1 ||
		topics[0].GetChannel() != rules.ChannelFor(publisherB) {
		t.Fatalf("a restricted channel was given a public topic: %v", topics)
	}
	if got := availabilityOf(gateway.status(channel), channel); got != gatewayv1.FeedAvailability_FEED_AVAILABILITY_UNSPECIFIED {
		t.Fatalf("a restricted feed's presence was answered without a grant: %v", got)
	}
}

// With a live grant's session, every read works — each page, each point read, the legacy views —
// and the ticket names the channel's restricted stream name for no longer than the grant runs.
func TestAnApprovedDeviceReadsEveryPathAndListens(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.restricted(publisherA)
	channel := rules.ChannelFor(publisherA)
	session := newSession(t)
	granted := gateway.grant(publisher, grantOne, session, 20*time.Minute)
	if granted.GetLifetimeSeconds() != 1200 {
		t.Fatalf("granted %d seconds", granted.GetLifetimeSeconds())
	}

	for read, err := range gateway.readAll(channel, session) {
		if err != nil {
			t.Fatalf("%s was refused to an approved device: %v", read, err)
		}
	}
	answer, err := gateway.ticketWith(channel, session)
	if err != nil {
		t.Fatal(err)
	}
	if name := streamNameOf(answer, channel); name != stream.RestrictedStreamChannel(channel, 1) {
		t.Fatalf("the ticket named %q", name)
	}
	if answer.GetLifetimeSeconds() > 1200 {
		t.Fatalf("a ticket outlives its grant: %d seconds", answer.GetLifetimeSeconds())
	}
	// Presence is answered to a device that may read the feed.
	status, err := gateway.feed.GetFeedStatus(context.Background(), connect.NewRequest(
		&gatewayv1.GetFeedStatusRequest{
			Channels: []string{channel},
			Sessions: []*gatewayv1.ChannelSession{{Channel: channel, Session: session}},
		}))
	if err != nil {
		t.Fatal(err)
	}
	if availabilityOf(status.Msg, channel) == gatewayv1.FeedAvailability_FEED_AVAILABILITY_UNSPECIFIED {
		t.Fatal("an approved device was not told whether the publisher is running")
	}
	// A publication fans out to the restricted stream name, never the public one.
	gateway.drain()
	for _, delivery := range gateway.dispatcher.all() {
		if !delivery.Restricted || delivery.Epoch != 1 {
			t.Fatalf("a restricted feed's delivery went out as %+v", delivery)
		}
	}
}

// Revoking one device ends every path it had — reads, pages, tickets, push — and moves the stream
// name so the listener it already has open hears nothing more. The other device on the same feed is
// untouched, and so is every other feed.
func TestRevokingOneDeviceEndsItsAccessAndLeavesTheOthers(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.restricted(publisherA)
	channel := rules.ChannelFor(publisherA)
	public := gateway.publisher(gateway.register(publisherB))
	gateway.publishManifest(public, manifestOf(publisherB, 1))
	gateway.drain()

	revokedSession, keptSession := newSession(t), newSession(t)
	gateway.grant(publisher, grantOne, revokedSession, time.Hour)
	gateway.grant(publisher, grantTwo, keptSession, time.Hour)
	ctx := context.Background()
	for session, target := range map[string]string{revokedSession: "target-revoked", keptSession: "target-kept"} {
		if _, err := gateway.feed.SetFeedPushTarget(ctx, connect.NewRequest(&gatewayv1.SetFeedPushTargetRequest{
			Channel: channel, Session: session, PushTarget: target,
		})); err != nil {
			t.Fatal(err)
		}
	}
	before, err := gateway.ticketWith(channel, revokedSession)
	if err != nil {
		t.Fatal(err)
	}
	oldName := streamNameOf(before, channel)

	if revoked := gateway.revoke(publisher, grantOne); revoked != 1 {
		t.Fatalf("revoked %d", revoked)
	}
	// Revoking it again is answered as done, and changes nothing.
	if revoked := gateway.revoke(publisher, grantOne); revoked != 0 {
		t.Fatalf("revoked %d the second time", revoked)
	}

	for read, err := range gateway.readAll(channel, revokedSession) {
		if err == nil {
			t.Fatalf("%s was served after revocation", read)
		}
		refused(t, err, connect.CodePermissionDenied, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_ACCESS_REVOKED)
	}
	_, err = gateway.ticketWith(channel, revokedSession)
	refused(t, err, connect.CodePermissionDenied, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_ACCESS_REVOKED)
	_, err = gateway.feed.SetFeedPushTarget(ctx, connect.NewRequest(&gatewayv1.SetFeedPushTargetRequest{
		Channel: channel, Session: revokedSession, PushTarget: "target-again",
	}))
	refused(t, err, connect.CodePermissionDenied, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_ACCESS_REVOKED)

	// The kept device reads, and its new ticket names the new stream name.
	for read, err := range gateway.readAll(channel, keptSession) {
		if err != nil {
			t.Fatalf("%s was refused to the device that was not revoked: %v", read, err)
		}
	}
	after, err := gateway.ticketWith(channel, keptSession, rules.ChannelFor(publisherB))
	if err != nil {
		t.Fatal(err)
	}
	newName := streamNameOf(after, channel)
	if newName == oldName || newName != stream.RestrictedStreamChannel(channel, 2) {
		t.Fatalf("the stream name did not move: %q then %q", oldName, newName)
	}
	if streamNameOf(after, rules.ChannelFor(publisherB)) != "feed:"+rules.ChannelFor(publisherB) {
		t.Fatal("the public feed on the same ticket was affected")
	}

	// The old name's last word is an access change, and the next publication goes only to the
	// new name.
	gateway.drain()
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalA, 2))
	gateway.drain()
	var retired, current bool
	for _, delivery := range gateway.dispatcher.all() {
		if delivery.Channel != channel {
			continue
		}
		if delivery.Kind == storage.AccessNotice {
			var event gatewayv1.FeedEvent
			if err := proto.Unmarshal(delivery.Event, &event); err != nil {
				t.Fatal(err)
			}
			if event.GetAccessChanged() == nil || delivery.Epoch != 1 {
				t.Fatalf("the retired name was told %v at epoch %d", &event, delivery.Epoch)
			}
			retired = true
		}
		if delivery.Kind == storage.ProposalNotice && delivery.Revision == 2 {
			if delivery.Epoch != 2 {
				t.Fatalf("a publication after revocation went to epoch %d", delivery.Epoch)
			}
			current = true
		}
	}
	if !retired || !current {
		t.Fatalf("retired %v, published on the new name %v", retired, current)
	}

	// Hints: the revoked device was told once to look (and will be refused when it does); the next
	// publication is hinted only to the device that still has access.
	waitFor(t, func() bool {
		for _, sent := range gateway.devices.all() {
			if sent.target == "target-revoked" && sent.feed {
				return true
			}
		}
		return false
	})
	hinted := false
	for _, sent := range gateway.devices.all() {
		if sent.target == "target-kept" && sent.feed {
			hinted = true
		}
	}
	if !hinted {
		t.Fatal("the approved device was not hinted")
	}
	count := 0
	for _, sent := range gateway.devices.all() {
		if sent.target == "target-revoked" {
			count++
		}
	}
	if count != 1 {
		t.Fatalf("the revoked device was sent %d hints", count)
	}

	// A renewal of the revoked grant never brings it back.
	_, err = publisher.GrantAccess(ctx, connect.NewRequest(&gatewayv1.GrantAccessRequest{
		GrantId: grantOne, SubscriberRef: "subscriber-1", DeviceRef: "device-" + grantOne[:8],
		SessionDigest: digestOf(revokedSession), LifetimeSeconds: 3600,
	}))
	refused(t, err, connect.CodeFailedPrecondition, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_GRANT_REVOKED)
	for read, err := range gateway.readAll(channel, revokedSession) {
		if err == nil {
			t.Fatalf("%s was served after a refused renewal", read)
		}
	}
}

// Revoking every device of one subscriber is one call naming all its grants, and a batch that
// names a grant the caller does not hold revokes nothing at all.
func TestWalletWideRevocationEndsEveryDeviceAndABadBatchEndsNone(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.restricted(publisherA)
	other := gateway.restricted(publisherB)
	channel := rules.ChannelFor(publisherA)
	one, two, foreign := newSession(t), newSession(t), newSession(t)
	gateway.grant(publisher, grantOne, one, time.Hour)
	gateway.grant(publisher, grantTwo, two, time.Hour)
	gateway.grant(other, grantThree, foreign, time.Hour)

	_, err := publisher.RevokeAccess(context.Background(), connect.NewRequest(
		&gatewayv1.RevokeAccessRequest{GrantIds: []string{grantOne, grantThree}}))
	refused(t, err, connect.CodeNotFound, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_GRANT)
	for _, session := range []string{one, two} {
		for read, err := range gateway.readAll(channel, session) {
			if err != nil {
				t.Fatalf("%s: a refused batch revoked something: %v", read, err)
			}
		}
	}

	if revoked := gateway.revoke(publisher, grantOne, grantTwo); revoked != 2 {
		t.Fatalf("revoked %d", revoked)
	}
	for _, session := range []string{one, two} {
		for read, err := range gateway.readAll(channel, session) {
			if err == nil {
				t.Fatalf("%s was served after the wallet was revoked", read)
			}
		}
	}
	// The other publisher's grant is untouched.
	for read, err := range gateway.readAll(rules.ChannelFor(publisherB), foreign) {
		if err != nil {
			t.Fatalf("%s: another publisher's device lost access: %v", read, err)
		}
	}
}

// A publisher reaches only its own grants: it cannot revoke, renew or take over another's, and a
// session granted on one channel reads no other.
func TestCrossPublisherGrantsAndRevocationsAreRefused(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.restricted(publisherA)
	other := gateway.restricted(publisherB)
	session := newSession(t)
	gateway.grant(publisher, grantOne, session, time.Hour)
	ctx := context.Background()

	_, err := other.RevokeAccess(ctx, connect.NewRequest(&gatewayv1.RevokeAccessRequest{GrantIds: []string{grantOne}}))
	refused(t, err, connect.CodeNotFound, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_GRANT)
	_, err = other.GrantAccess(ctx, connect.NewRequest(&gatewayv1.GrantAccessRequest{
		GrantId: grantOne, SubscriberRef: "theirs", DeviceRef: "theirs",
		SessionDigest: digestOf(newSession(t)), LifetimeSeconds: 3600,
	}))
	refused(t, err, connect.CodeNotFound, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_GRANT)
	// Nor can it register the same session under a grant of its own.
	_, err = other.GrantAccess(ctx, connect.NewRequest(&gatewayv1.GrantAccessRequest{
		GrantId: grantTwo, SubscriberRef: "theirs", DeviceRef: "theirs",
		SessionDigest: digestOf(session), LifetimeSeconds: 3600,
	}))
	refused(t, err, connect.CodeInvalidArgument, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_GRANT)

	for read, err := range gateway.readAll(rules.ChannelFor(publisherA), session) {
		if err != nil {
			t.Fatalf("%s: another publisher's attempt changed this grant: %v", read, err)
		}
	}
	for read, err := range gateway.readAll(rules.ChannelFor(publisherB), session) {
		if err == nil {
			t.Fatalf("%s: a session for one channel read another", read)
		}
	}
}

// A grant that is not renewed runs out — the bound on how long access outlives a publisher the
// gateway cannot hear from — and the publisher's renewal brings the same session back. No grant
// runs longer than the operator's bound, however long a publisher asks for.
func TestAGrantExpiresUnlessRenewedWithinTheBound(t *testing.T) {
	gateway := newGateway(t, func(settings *config.Config) { settings.MostGrant = 6 * time.Hour })
	publisher := gateway.restricted(publisherA)
	channel := rules.ChannelFor(publisherA)
	session := newSession(t)
	if granted := gateway.grant(publisher, grantOne, session, 72*time.Hour); granted.GetLifetimeSeconds() != 6*3600 {
		t.Fatalf("a grant ran %d seconds past a six-hour bound", granted.GetLifetimeSeconds())
	}
	described, err := publisher.DescribeAccess(context.Background(), connect.NewRequest(&gatewayv1.DescribeAccessRequest{}))
	if err != nil {
		t.Fatal(err)
	}
	if described.Msg.GetMostGrantSeconds() != 6*3600 ||
		described.Msg.GetAccess().GetPolicy() != serverv1.FeedAccessPolicy_FEED_ACCESS_POLICY_RESTRICTED {
		t.Fatalf("described %v", described.Msg)
	}

	gateway.at(published.Add(6*time.Hour + time.Second))
	for read, err := range gateway.readAll(channel, session) {
		if err == nil {
			t.Fatalf("%s was served past the grant's expiry", read)
		}
		refused(t, err, connect.CodePermissionDenied, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_ACCESS_EXPIRED)
	}
	gateway.grant(publisher, grantOne, session, time.Hour)
	for read, err := range gateway.readAll(channel, session) {
		if err != nil {
			t.Fatalf("%s was refused after renewal: %v", read, err)
		}
	}
}

// What a restricted feed's publisher says about its access has to be what the operator registered,
// and a public feed cannot claim to be restricted, or send phones to an origin nobody registered.
func TestTheManifestsAccessIsTheRegistrationsNeverThePublishers(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.publisher(gateway.register(publisherA))
	if err := gateway.documents.SetAccess(context.Background(), publisherA, storage.Access{
		Policy: storage.RestrictedAccess, AuthOrigin: authOrigin,
	}); err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	for name, change := range map[string]func(*serverv1.ServerManifest){
		"silent": func(*serverv1.ServerManifest) {},
		"public": func(manifest *serverv1.ServerManifest) {
			manifest.GetFeed().Access = &serverv1.FeedAccess{Policy: serverv1.FeedAccessPolicy_FEED_ACCESS_POLICY_PUBLIC}
		},
		"another origin": func(manifest *serverv1.ServerManifest) {
			manifest.GetFeed().Access = &serverv1.FeedAccess{
				Policy:     serverv1.FeedAccessPolicy_FEED_ACCESS_POLICY_RESTRICTED,
				AuthOrigin: "https://attacker.example.com",
			}
		},
	} {
		_, err := publisher.PublishManifest(ctx, connect.NewRequest(
			&gatewayv1.PublishManifestRequest{Manifest: manifestOf(publisherA, 1, change)}))
		if err == nil {
			t.Fatalf("a %s manifest was accepted for a restricted feed", name)
		}
		refused(t, err, connect.CodeFailedPrecondition, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_ACCESS_MISMATCH)
	}

	public := gateway.publisher(gateway.register(publisherB))
	_, err := public.PublishManifest(ctx, connect.NewRequest(
		&gatewayv1.PublishManifestRequest{Manifest: manifestOf(publisherB, 1, restrictedManifest)}))
	refused(t, err, connect.CodeFailedPrecondition, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_ACCESS_MISMATCH)
	// A public feed's manifest is byte for byte what it was before SEE-156, and it is told so.
	answer := gateway.publishManifest(public, manifestOf(publisherB, 1))
	if answer.GetAccess().GetPolicy() != serverv1.FeedAccessPolicy_FEED_ACCESS_POLICY_PUBLIC {
		t.Fatalf("a public publisher was told %v", answer.GetAccess())
	}
	served, err := gateway.feed.GetServerManifest(ctx, connect.NewRequest(
		&gatewayv1.GetServerManifestRequest{ServerId: publisherB}))
	if err != nil {
		t.Fatal(err)
	}
	if served.Msg.GetManifest().GetFeed().GetAccess() != nil {
		t.Fatal("a public feed's manifest grew an access field")
	}
	_, err = public.GrantAccess(ctx, connect.NewRequest(&gatewayv1.GrantAccessRequest{
		GrantId: grantOne, SubscriberRef: "s", DeviceRef: "d",
		SessionDigest: digestOf(newSession(t)), LifetimeSeconds: 60,
	}))
	refused(t, err, connect.CodeFailedPrecondition, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NOT_RESTRICTED)

	// A feed switched to restricted after its manifest was stored is served as restricted at once,
	// before its publisher publishes again.
	if err := gateway.documents.SetAccess(ctx, publisherB, storage.Access{
		Policy: storage.RestrictedAccess, AuthOrigin: authOrigin,
	}); err != nil {
		t.Fatal(err)
	}
	served, err = gateway.feed.GetServerManifest(ctx, connect.NewRequest(
		&gatewayv1.GetServerManifestRequest{ServerId: publisherB}))
	if err != nil {
		t.Fatal(err)
	}
	if served.Msg.GetManifest().GetFeed().GetAccess().GetPolicy() != serverv1.FeedAccessPolicy_FEED_ACCESS_POLICY_RESTRICTED {
		t.Fatal("a feed switched to restricted was still described as public")
	}
	for read, err := range gateway.readAll(rules.ChannelFor(publisherB), "") {
		if err == nil {
			t.Fatalf("%s: a feed switched to restricted still read anonymously", read)
		}
	}
}

// A decision survives a restart: the grants, revocations and the stream epoch are in the database,
// not in the process.
func TestAccessDecisionsSurviveARestart(t *testing.T) {
	path := t.TempDir() + "/broadcast.db"
	first := gatewayOn(t, path)
	publisher := first.restricted(publisherA)
	kept, revoked := newSession(t), newSession(t)
	first.grant(publisher, grantOne, kept, time.Hour)
	first.grant(publisher, grantTwo, revoked, time.Hour)
	first.revoke(publisher, grantTwo)
	first.read.Close()
	first.publish.Close()
	if err := first.documents.Close(); err != nil {
		t.Fatal(err)
	}

	second := gatewayOn(t, path)
	channel := rules.ChannelFor(publisherA)
	for read, err := range second.readAll(channel, kept) {
		if err != nil {
			t.Fatalf("%s: an approved device lost access across a restart: %v", read, err)
		}
	}
	for read, err := range second.readAll(channel, revoked) {
		if err == nil {
			t.Fatalf("%s: a revoked device regained access across a restart", read)
		}
	}
	answer, err := second.ticketWith(channel, kept)
	if err != nil {
		t.Fatal(err)
	}
	if streamNameOf(answer, channel) != stream.RestrictedStreamChannel(channel, 2) {
		t.Fatalf("the stream epoch did not survive: %q", streamNameOf(answer, channel))
	}
}

// Reading a restricted feed records nothing about the reader either: the grant is looked up, and
// nothing is written.
func TestReadingARestrictedFeedWritesNothingDown(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.restricted(publisherA)
	session := newSession(t)
	gateway.grant(publisher, grantOne, session, time.Hour)
	gateway.drain()
	before := rowCounts(t, gateway.path)
	for range 3 {
		gateway.readAll(rules.ChannelFor(publisherA), session)
		gateway.readAll(rules.ChannelFor(publisherA), "")
		_, _ = gateway.ticketWith(rules.ChannelFor(publisherA), session)
	}
	if after := rowCounts(t, gateway.path); fmt.Sprint(before) != fmt.Sprint(after) {
		t.Fatalf("reading changed the store:\n%v\n%v", before, after)
	}
}

// A restricted feed's hints go to the devices that registered a target under a live grant, and a
// target cannot be registered without one — or for a public feed, which has its topic.
func TestARestrictedHintReachesOnlyLiveGrantsTargets(t *testing.T) {
	gateway := newGateway(t)
	publisher := gateway.restricted(publisherA)
	channel := rules.ChannelFor(publisherA)
	ctx := context.Background()

	_, err := gateway.feed.SetFeedPushTarget(ctx, connect.NewRequest(&gatewayv1.SetFeedPushTargetRequest{
		Channel: channel, Session: newSession(t), PushTarget: "target-stranger",
	}))
	refused(t, err, connect.CodePermissionDenied, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_ACCESS_REQUIRED)

	session := newSession(t)
	gateway.grant(publisher, grantOne, session, time.Hour)
	if _, err := gateway.feed.SetFeedPushTarget(ctx, connect.NewRequest(&gatewayv1.SetFeedPushTargetRequest{
		Channel: channel, Session: session, PushTarget: "target-approved",
	})); err != nil {
		t.Fatal(err)
	}
	gateway.drain()
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalB, 1))
	gateway.drain()
	sent := gateway.devices.all()
	if len(sent) == 0 {
		t.Fatal("the approved device was not hinted")
	}
	for _, one := range sent {
		if one.target != "target-approved" || !one.feed {
			t.Fatalf("a hint went to %+v", one)
		}
	}

	// A target the push endpoint says is finished is cleared, and hinted no more.
	gateway.devices.answer(relay.TargetGone)
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalB, 2))
	gateway.drain()
	count := len(gateway.devices.all())
	gateway.devices.answer(relay.Delivered)
	gateway.publishProposal(publisher, proposalOf(publisherA, proposalB, 3))
	gateway.drain()
	if len(gateway.devices.all()) != count {
		t.Fatal("a finished push target was hinted again")
	}

	public := gateway.publisher(gateway.register(publisherB))
	gateway.publishManifest(public, manifestOf(publisherB, 1))
	_, err = gateway.feed.SetFeedPushTarget(ctx, connect.NewRequest(&gatewayv1.SetFeedPushTargetRequest{
		Channel: rules.ChannelFor(publisherB), Session: session, PushTarget: "target-public",
	}))
	refused(t, err, connect.CodeFailedPrecondition, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NOT_RESTRICTED)
}

// A gateway that relays nothing says so to a device registering a restricted target, exactly as it
// does to one asking for a public topic.
func TestARestrictedTargetOnAGatewayWithoutPushIsUnimplemented(t *testing.T) {
	gateway := newGatewayWithoutPush(t)
	publisher := gateway.restricted(publisherA)
	session := newSession(t)
	gateway.grant(publisher, grantOne, session, time.Hour)
	_, err := gateway.feed.SetFeedPushTarget(context.Background(), connect.NewRequest(
		&gatewayv1.SetFeedPushTargetRequest{Channel: rules.ChannelFor(publisherA), Session: session, PushTarget: "t"}))
	refused(t, err, connect.CodeUnimplemented, gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_PUSH)
}

// waitFor polls a condition a background send makes true.
func waitFor(t *testing.T, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("the condition never became true")
}
