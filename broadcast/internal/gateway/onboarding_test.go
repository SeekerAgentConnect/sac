package gateway_test

import (
	"context"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/protobuf/types/known/timestamppb"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1/gatewayv1connect"
	requestv2 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/request/v2"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/rules"
)

const privateUser = "customer:opaque-7qvN9"

func TestInvitationPreviewConfirmationAndPrivateRequestRoundTrip(t *testing.T) {
	g := newGateway(t)
	publisher := g.publisher(g.register(publisherA))
	g.publishManifest(publisher, privateManifestOf(publisherA, 1))

	created, err := publisher.CreateInvitation(context.Background(),
		connect.NewRequest(&gatewayv1.CreateInvitationRequest{UserRef: privateUser, LifetimeSeconds: 600}))
	if err != nil {
		t.Fatal(err)
	}
	invite := created.Msg.GetInvitation()
	if invite.GetStatus() != gatewayv1.InvitationStatus_INVITATION_STATUS_PENDING ||
		invite.GetInvitationUrl() == "" || invite.GetAppUri() == "" || invite.GetConnectionId() != "" {
		t.Fatalf("unexpected invitation: %+v", invite)
	}
	token := strings.TrimPrefix(invite.GetInvitationUrl(), gatewayURL+"/invite/")

	// Resolving, opening, and rendering the QR are all previews. None consumes the capability.
	for _, target := range []string{"/invite/" + token, "/invite/" + token + "/qr.png"} {
		response, err := g.client.Client().Get(g.client.URL + target)
		if err != nil {
			t.Fatal(err)
		}
		_ = response.Body.Close()
		if response.StatusCode != http.StatusOK {
			t.Fatalf("preview %s answered %s", target, response.Status)
		}
	}
	for range 2 {
		resolved, err := g.invitation.ResolveInvitation(context.Background(),
			connect.NewRequest(&gatewayv1.ResolveInvitationRequest{Token: token}))
		if err != nil || resolved.Msg.GetStatus() != gatewayv1.InvitationStatus_INVITATION_STATUS_PENDING {
			t.Fatalf("preview consumed the invitation: %v %+v", err, resolved)
		}
		if strings.Contains(resolved.Msg.String(), privateUser) {
			t.Fatal("the server's opaque user reference was disclosed to the device")
		}
	}

	redeemed, err := g.invitation.RedeemInvitation(context.Background(),
		connect.NewRequest(&gatewayv1.RedeemInvitationRequest{Token: token, DeviceName: "Owner's Seeker"}))
	if err != nil {
		t.Fatal(err)
	}
	connectionID, deviceToken := redeemed.Msg.GetConnectionId(), redeemed.Msg.GetDeviceToken()
	if connectionID == "" || deviceToken == "" || strings.Contains(g.logs.text(), token) ||
		strings.Contains(g.logs.text(), deviceToken) || strings.Contains(g.logs.text(), privateUser) {
		t.Fatalf("redemption leaked or omitted a capability: %s", g.logs.text())
	}
	status, err := publisher.GetInvitation(context.Background(),
		connect.NewRequest(&gatewayv1.GetInvitationRequest{InvitationId: invite.GetInvitationId()}))
	if err != nil || status.Msg.GetInvitation().GetStatus() != gatewayv1.InvitationStatus_INVITATION_STATUS_CONNECTED ||
		status.Msg.GetInvitation().GetConnectionId() != connectionID || status.Msg.GetInvitation().GetAppUri() != "" {
		t.Fatalf("completion was not reported safely: %v %+v", err, status)
	}
	if _, err := g.invitation.RedeemInvitation(context.Background(),
		connect.NewRequest(&gatewayv1.RedeemInvitationRequest{Token: token})); connect.CodeOf(err) != connect.CodeFailedPrecondition {
		t.Fatalf("a used invitation was redeemable: %v", err)
	}

	document := privateRequestOf(publisherA, proposalA, privateUser, 1)
	accepted, err := publisher.CreatePrivateRequest(context.Background(),
		connect.NewRequest(&gatewayv1.CreatePrivateRequestRequest{
			Request: document, ConnectionId: connectionID,
		}))
	if err != nil || accepted.Msg.GetRecord().GetConnectionId() != connectionID {
		t.Fatalf("private request was not routed to the binding: %v %+v", err, accepted)
	}
	device := gatewayv1connect.NewDeviceServiceClient(g.client.Client(), g.client.URL,
		connect.WithInterceptors(presenting(deviceToken)))
	page, err := device.ListRequests(context.Background(),
		connect.NewRequest(&gatewayv1.DeviceServiceListRequestsRequest{ConnectionId: connectionID}))
	if err != nil || len(page.Msg.GetRequests()) != 1 ||
		page.Msg.GetRequests()[0].GetIdentity().GetRequestId() != proposalA {
		t.Fatalf("paired device did not receive its request: %v %+v", err, page)
	}
	result := &gatewayv1.DeviceResult{RequestId: proposalA, RequestRevision: 1,
		Status:      gatewayv1.DeviceResultStatus_DEVICE_RESULT_STATUS_REJECTED,
		CompletedAt: timestamppb.New(g.now())}
	if _, err := device.SubmitResult(context.Background(),
		connect.NewRequest(&gatewayv1.DeviceServiceSubmitResultRequest{ConnectionId: connectionID, Result: result})); err != nil {
		t.Fatal(err)
	}
	reported, err := publisher.GetPrivateRequest(context.Background(),
		connect.NewRequest(&gatewayv1.GetPrivateRequestRequest{RequestId: proposalA}))
	if err != nil || reported.Msg.GetRecord().GetResult().GetStatus() != result.GetStatus() {
		t.Fatalf("origin did not receive its result: %v %+v", err, reported)
	}

	if _, err := device.RevokeConnection(context.Background(),
		connect.NewRequest(&gatewayv1.DeviceServiceRevokeConnectionRequest{ConnectionId: connectionID})); err != nil {
		t.Fatal(err)
	}
	if _, err := device.ListRequests(context.Background(),
		connect.NewRequest(&gatewayv1.DeviceServiceListRequestsRequest{ConnectionId: connectionID})); connect.CodeOf(err) != connect.CodeUnauthenticated {
		t.Fatalf("a revoked device kept reading: %v", err)
	}
}

func TestInvitationIsAtomicExpiresAndAddsIndependentlyRevocableDevices(t *testing.T) {
	g := newGateway(t)
	publisher := g.publisher(g.register(publisherA))
	g.publishManifest(publisher, privateManifestOf(publisherA, 1))
	create := func(user string) *gatewayv1.Invitation {
		response, err := publisher.CreateInvitation(context.Background(),
			connect.NewRequest(&gatewayv1.CreateInvitationRequest{UserRef: user, LifetimeSeconds: 60}))
		if err != nil {
			t.Fatal(err)
		}
		return response.Msg.GetInvitation()
	}
	first := create(privateUser)
	token := strings.TrimPrefix(first.GetInvitationUrl(), gatewayURL+"/invite/")

	var wait sync.WaitGroup
	wait.Add(2)
	codes := make(chan connect.Code, 2)
	for range 2 {
		go func() {
			defer wait.Done()
			_, err := g.invitation.RedeemInvitation(context.Background(),
				connect.NewRequest(&gatewayv1.RedeemInvitationRequest{Token: token}))
			codes <- connect.CodeOf(err)
		}()
	}
	wait.Wait()
	close(codes)
	seen := map[connect.Code]int{}
	for code := range codes {
		seen[code]++
	}
	if seen[connect.CodeUnknown] != 1 || seen[connect.CodeFailedPrecondition] != 1 {
		t.Fatalf("atomic redemption codes were %v", seen)
	}

	firstStatus, err := publisher.GetInvitation(context.Background(),
		connect.NewRequest(&gatewayv1.GetInvitationRequest{InvitationId: first.GetInvitationId()}))
	if err != nil {
		t.Fatal(err)
	}
	firstConnection := firstStatus.Msg.GetInvitation().GetConnectionId()
	if firstConnection == "" {
		t.Fatal("the winning redemption did not create a binding")
	}

	// Another device for the same server-scoped user needs another invitation. It creates a second
	// binding instead of replacing the first, and either binding can be revoked on its own.
	second := create(privateUser)
	secondToken := strings.TrimPrefix(second.GetInvitationUrl(), gatewayURL+"/invite/")
	secondRedeemed, err := g.invitation.RedeemInvitation(context.Background(),
		connect.NewRequest(&gatewayv1.RedeemInvitationRequest{Token: secondToken}))
	if err != nil || secondRedeemed.Msg.GetConnectionId() == firstConnection {
		t.Fatalf("a fresh invitation did not add another device: %v %+v", err, secondRedeemed)
	}
	if _, err := publisher.RevokePrivateConnection(context.Background(),
		connect.NewRequest(&gatewayv1.RevokePrivateConnectionRequest{ConnectionId: firstConnection})); err != nil {
		t.Fatal(err)
	}
	secondDevice := gatewayv1connect.NewDeviceServiceClient(g.client.Client(), g.client.URL,
		connect.WithInterceptors(presenting(secondRedeemed.Msg.GetDeviceToken())))
	if _, err := secondDevice.GetServerManifest(context.Background(),
		connect.NewRequest(&gatewayv1.DeviceServiceGetServerManifestRequest{
			ConnectionId: secondRedeemed.Msg.GetConnectionId(),
		})); err != nil {
		t.Fatalf("revoking the first device revoked the second: %v", err)
	}

	revoked := create("customer:cancelled-onboarding")
	revokedToken := strings.TrimPrefix(revoked.GetInvitationUrl(), gatewayURL+"/invite/")
	for range 2 {
		if _, err := publisher.RevokeInvitation(context.Background(),
			connect.NewRequest(&gatewayv1.RevokeInvitationRequest{
				InvitationId: revoked.GetInvitationId(),
			})); err != nil {
			t.Fatalf("pending invitation revocation was not idempotent: %v", err)
		}
	}
	if _, err := g.invitation.ResolveInvitation(context.Background(),
		connect.NewRequest(&gatewayv1.ResolveInvitationRequest{Token: revokedToken})); connect.CodeOf(err) != connect.CodeNotFound {
		t.Fatalf("a revoked invitation still resolved: %v", err)
	}
	if _, err := g.invitation.RedeemInvitation(context.Background(),
		connect.NewRequest(&gatewayv1.RedeemInvitationRequest{Token: revokedToken})); connect.CodeOf(err) != connect.CodeNotFound {
		t.Fatalf("a revoked invitation was redeemed: %v", err)
	}
	if _, err := publisher.GetInvitation(context.Background(),
		connect.NewRequest(&gatewayv1.GetInvitationRequest{
			InvitationId: revoked.GetInvitationId(),
		})); connect.CodeOf(err) != connect.CodeNotFound {
		t.Fatalf("a revoked invitation remained pollable as pending: %v", err)
	}

	expired := create("customer:expired")
	g.at(g.now().Add(61 * time.Second))
	expiredToken := strings.TrimPrefix(expired.GetInvitationUrl(), gatewayURL+"/invite/")
	resolved, err := g.invitation.ResolveInvitation(context.Background(),
		connect.NewRequest(&gatewayv1.ResolveInvitationRequest{Token: expiredToken}))
	if err != nil || resolved.Msg.GetStatus() != gatewayv1.InvitationStatus_INVITATION_STATUS_EXPIRED {
		t.Fatalf("expiry was not visible before confirmation: %v %+v", err, resolved)
	}
	if _, err := g.invitation.RedeemInvitation(context.Background(),
		connect.NewRequest(&gatewayv1.RedeemInvitationRequest{Token: expiredToken})); connect.CodeOf(err) != connect.CodeFailedPrecondition {
		t.Fatalf("an expired invitation was redeemed: %v", err)
	}
}

func TestPrivateRoutingValidatesManifestCapabilityDeviceAndServerIsolation(t *testing.T) {
	g := newGateway(t)
	publisherAClient := g.publisher(g.register(publisherA))
	publisherBClient := g.publisher(g.register(publisherB))
	g.publishManifest(publisherAClient, privateManifestOf(publisherA, 1))
	g.publishManifest(publisherBClient, privateManifestOf(publisherB, 1))

	created, err := publisherAClient.CreateInvitation(context.Background(),
		connect.NewRequest(&gatewayv1.CreateInvitationRequest{UserRef: privateUser}))
	if err != nil {
		t.Fatal(err)
	}
	token := strings.TrimPrefix(created.Msg.GetInvitation().GetInvitationUrl(), gatewayURL+"/invite/")
	redeemed, err := g.invitation.RedeemInvitation(context.Background(),
		connect.NewRequest(&gatewayv1.RedeemInvitationRequest{Token: token}))
	if err != nil {
		t.Fatal(err)
	}
	connectionID := redeemed.Msg.GetConnectionId()

	// A request identity is pinned to the device chosen on its first accepted revision. Even a
	// second valid binding for the same user cannot silently retarget an idempotent retry.
	second, err := publisherAClient.CreateInvitation(context.Background(),
		connect.NewRequest(&gatewayv1.CreateInvitationRequest{UserRef: privateUser}))
	if err != nil {
		t.Fatal(err)
	}
	secondToken := strings.TrimPrefix(second.Msg.GetInvitation().GetInvitationUrl(), gatewayURL+"/invite/")
	secondDevice, err := g.invitation.RedeemInvitation(context.Background(),
		connect.NewRequest(&gatewayv1.RedeemInvitationRequest{Token: secondToken}))
	if err != nil {
		t.Fatal(err)
	}
	pinned := privateRequestOf(publisherA, proposalA, privateUser, 1)
	if _, err := publisherAClient.CreatePrivateRequest(context.Background(),
		connect.NewRequest(&gatewayv1.CreatePrivateRequestRequest{
			Request: pinned, ConnectionId: connectionID,
		})); err != nil {
		t.Fatal(err)
	}
	_, err = publisherAClient.CreatePrivateRequest(context.Background(),
		connect.NewRequest(&gatewayv1.CreatePrivateRequestRequest{
			Request: pinned, ConnectionId: secondDevice.Msg.GetConnectionId(),
		}))
	detail := refused(t, err, connect.CodeFailedPrecondition,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_REVISION_CONFLICT)
	if detail.GetField() != "connection_id" || detail.GetHeldRevision() != 1 {
		t.Fatalf("retarget refusal was %+v", detail)
	}

	// The completion ID is not a bearer capability: another authenticated server and another user
	// under the right server are both refused by the binding lookup.
	for name, one := range map[string]struct {
		publisher gatewayv1connect.PublisherServiceClient
		document  *requestv2.Request
	}{
		"other server": {publisherBClient, privateRequestOf(publisherB, proposalA, privateUser, 1)},
		"other user":   {publisherAClient, privateRequestOf(publisherA, proposalB, "customer:other", 1)},
	} {
		t.Run(name, func(t *testing.T) {
			_, err := one.publisher.CreatePrivateRequest(context.Background(),
				connect.NewRequest(&gatewayv1.CreatePrivateRequestRequest{
					Request: one.document, ConnectionId: connectionID,
				}))
			if connect.CodeOf(err) != connect.CodeNotFound {
				t.Fatalf("isolated binding was addressable: %v", err)
			}
		})
	}

	// A syntactically valid plugin is still not a capability this server declared in its manifest.
	undeclared := privateRequestOf(publisherA, proposalB, privateUser, 1)
	undeclared.Action.PluginId = "jupiter.prediction"
	if _, err := publisherAClient.CreatePrivateRequest(context.Background(),
		connect.NewRequest(&gatewayv1.CreatePrivateRequestRequest{
			Request: undeclared, ConnectionId: connectionID,
		})); connect.CodeOf(err) != connect.CodeInvalidArgument {
		t.Fatalf("an undeclared plugin reached the device: %v", err)
	}
}

func privateManifestOf(serverID string, revision uint64) *serverv1.ServerManifest {
	return &serverv1.ServerManifest{ServerId: serverID, ProtocolVersion: rules.Protocol,
		SettingsRevision: revision, Mode: serverv1.ConnectionMode_CONNECTION_MODE_GATEWAY_PRIVATE,
		RequiredPlugins: []*serverv1.PluginRequirement{{PluginId: "jupiter.swap", MinContract: 1, MaxContract: 1}},
		Environments:    []serverv1.ServerEnvironment{serverv1.ServerEnvironment_SERVER_ENVIRONMENT_PRODUCTION},
		DisplayName:     "Independent trader", Reference: &serverv1.ServerManifest_GatewayPrivate{
			GatewayPrivate: &serverv1.GatewayPrivate{GatewayUrl: gatewayURL}}}
}

func privateRequestOf(serverID, requestID, userRef string, revision uint64) *requestv2.Request {
	return &requestv2.Request{ContractVersion: rules.RequestContract,
		Identity: &requestv2.RequestIdentity{SourceId: serverID, Scope: rules.PrivateScope(serverID), RequestId: requestID},
		Lifecycle: &requestv2.RequestLifecycle{Revision: revision,
			Status:    requestv2.RequestStatus_REQUEST_STATUS_OPEN,
			CreatedAt: timestamppb.New(published), UpdatedAt: timestamppb.New(published),
			ExpiresAt: timestamppb.New(published.Add(time.Hour))},
		Presentation: &requestv2.Presentation{Title: "Review swap", Description: "Server request",
			Category: requestv2.PresentationCategory_PRESENTATION_CATEGORY_REQUEST},
		Action: &requestv2.ActionCapability{CapabilityId: "swap", CapabilityVersion: 1, PluginId: "jupiter.swap",
			Parameters: []*requestv2.Value{{Key: "input_mint", Value: &requestv2.Value_Text{Text: "mint"}}}},
		OwnerInputs:    []*requestv2.OwnerInput{{Key: "amount", Label: "Amount", Kind: requestv2.OwnerInputKind_OWNER_INPUT_KIND_AMOUNT, Required: true}},
		Audience:       &requestv2.Audience{Audience: &requestv2.Audience_Private{Private: &requestv2.PrivateAudience{RecipientId: userRef}}},
		ResultHandling: &requestv2.ResultHandling{Mode: requestv2.ResultMode_RESULT_MODE_RETURN_TO_ORIGIN}}
}
