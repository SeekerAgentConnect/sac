package gateway

import (
	"context"
	"crypto/sha256"
	"errors"
	"net/url"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/protobuf/types/known/timestamppb"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1"
	requestv2 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/request/v2"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/store"
)

const (
	defaultInvitationLifetime = 15 * time.Minute
	minimumInvitationLifetime = time.Minute
	maximumInvitationLifetime = 24 * time.Hour
)

func (p *Publisher) CreateInvitation(
	ctx context.Context,
	request *connect.Request[gatewayv1.CreateInvitationRequest],
) (*connect.Response[gatewayv1.CreateInvitationResponse], error) {
	userRef := request.Msg.GetUserRef()
	if !rules.IsUserRef(userRef) {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_USER_REF, "user_ref")
	}
	lifetime := time.Duration(request.Msg.GetLifetimeSeconds()) * time.Second
	if lifetime == 0 {
		lifetime = defaultInvitationLifetime
	}
	if lifetime < minimumInvitationLifetime || lifetime > maximumInvitationLifetime {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_LIFETIME, "lifetime_seconds")
	}
	serverID := publisherOf(ctx)
	manifest, err := p.store.Manifest(ctx, serverID)
	if err != nil {
		return nil, internal(err)
	}
	if manifest == nil || manifest.Document.GetMode() != serverv1.ConnectionMode_CONNECTION_MODE_GATEWAY_PRIVATE {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NOT_PRIVATE, "manifest.mode")
	}
	id, err := randomID()
	if err != nil {
		return nil, internal(err)
	}
	token, err := randomToken()
	if err != nil {
		return nil, internal(err)
	}
	now := p.now().UTC()
	one := store.Invitation{ID: id, ServerID: serverID, UserRef: userRef,
		CreatedAt: now, ExpiresAt: now.Add(lifetime)}
	hash := sha256.Sum256([]byte(token))
	if err := p.store.CreateInvitation(ctx, one, hash[:]); err != nil {
		return nil, internal(err)
	}
	return connect.NewResponse(&gatewayv1.CreateInvitationResponse{
		Invitation: p.invitation(one, token),
	}), nil
}

func (p *Publisher) GetInvitation(
	ctx context.Context,
	request *connect.Request[gatewayv1.GetInvitationRequest],
) (*connect.Response[gatewayv1.GetInvitationResponse], error) {
	if !rules.IsID(request.Msg.GetInvitationId()) {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_INVALID_INVITATION, "invitation_id")
	}
	one, err := p.store.Invitation(ctx, publisherOf(ctx), request.Msg.GetInvitationId())
	if err != nil {
		return nil, internal(err)
	}
	if one == nil {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_INVALID_INVITATION, "invitation_id")
	}
	if one.RevokedAt != nil {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_INVALID_INVITATION, "invitation_id")
	}
	return connect.NewResponse(&gatewayv1.GetInvitationResponse{Invitation: p.invitation(*one, "")}), nil
}

func (p *Publisher) RevokeInvitation(
	ctx context.Context,
	request *connect.Request[gatewayv1.RevokeInvitationRequest],
) (*connect.Response[gatewayv1.RevokeInvitationResponse], error) {
	if !rules.IsID(request.Msg.GetInvitationId()) {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_INVALID_INVITATION, "invitation_id")
	}
	err := p.store.RevokeInvitation(ctx, publisherOf(ctx), request.Msg.GetInvitationId(), p.now())
	switch {
	case errors.Is(err, store.ErrNoInvitation):
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_INVALID_INVITATION, "invitation_id")
	case errors.Is(err, store.ErrInvitationUsed):
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_INVITATION_USED, "invitation_id")
	case err != nil:
		return nil, internal(err)
	}
	return connect.NewResponse(&gatewayv1.RevokeInvitationResponse{}), nil
}

func (p *Publisher) invitation(one store.Invitation, token string) *gatewayv1.Invitation {
	status := gatewayv1.InvitationStatus_INVITATION_STATUS_PENDING
	if one.RedeemedAt != nil {
		status = gatewayv1.InvitationStatus_INVITATION_STATUS_CONNECTED
	} else if !p.now().Before(one.ExpiresAt) {
		status = gatewayv1.InvitationStatus_INVITATION_STATUS_EXPIRED
	}
	answer := &gatewayv1.Invitation{InvitationId: one.ID, ExpiresAt: timestamppb.New(one.ExpiresAt),
		Status: status, ConnectionId: one.ConnectionID}
	if one.RedeemedAt != nil {
		answer.ConnectedAt = timestamppb.New(*one.RedeemedAt)
	}
	// The raw token is returned once, on creation. Polling status cannot recover it.
	if token != "" {
		answer.InvitationUrl = p.gateway + "/invite/" + token
		answer.AppUri = "seekervault://invite?v=1&gateway=" + url.QueryEscape(p.gateway) + "&token=" + token
	}
	return answer
}

func (p *Publisher) CreatePrivateRequest(
	ctx context.Context,
	request *connect.Request[gatewayv1.CreatePrivateRequestRequest],
) (*connect.Response[gatewayv1.CreatePrivateRequestResponse], error) {
	document, fault := rules.PrivateRequest(request.Msg.GetRequest(), p.expectation(ctx))
	if fault != nil {
		return nil, refuse(fault)
	}
	serverID := publisherOf(ctx)
	recipient := document.GetAudience().GetPrivate().GetRecipientId()
	connectionID := request.Msg.GetConnectionId()
	if !rules.IsID(connectionID) {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_BINDING, "connection_id")
	}
	manifest, err := p.store.Manifest(ctx, serverID)
	if err != nil {
		return nil, internal(err)
	}
	if manifest == nil || manifest.Document.GetMode() != serverv1.ConnectionMode_CONNECTION_MODE_GATEWAY_PRIVATE {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NOT_PRIVATE, "manifest.mode")
	}
	declared := false
	for _, required := range manifest.Document.GetRequiredPlugins() {
		if required.GetPluginId() == document.GetAction().GetPluginId() {
			declared = true
			break
		}
	}
	if !declared {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_PLUGIN, "action.plugin_id")
	}
	binding, err := p.store.ActiveBinding(ctx, serverID, recipient, connectionID)
	if err != nil {
		return nil, internal(err)
	}
	if binding == nil {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_BINDING, "connection_id")
	}
	answer := &gatewayv1.CreatePrivateRequestResponse{}
	var refused *rules.Fault
	err = p.store.Write(ctx, func(tx *store.Tx) error {
		held, err := tx.PrivateRequest(ctx, serverID, document.GetIdentity().GetRequestId())
		if err != nil {
			return err
		}
		var current *requestv2.Request
		if held != nil {
			current = held.Document
			if held.ConnectionID != connectionID {
				refused = &rules.Fault{
					Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_REVISION_CONFLICT,
					Field:   "connection_id",
					Held:    current.GetLifecycle().GetRevision(),
				}
				return nil
			}
			if current.GetAudience().GetPrivate().GetRecipientId() != recipient {
				refused = &rules.Fault{Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_WRONG_RECIPIENT,
					Field: "audience.private.recipient_id", Held: current.GetLifecycle().GetRevision()}
				return nil
			}
		}
		decision, fault := rules.AdvanceRequest(current, document)
		if fault != nil {
			refused = fault
			return nil
		}
		if decision == rules.Unchanged {
			answer.Unchanged = true
			answer.Record = privateRecord(held)
			return nil
		}
		sequence, err := tx.PutPrivateRequest(ctx, document, recipient, binding.ConnectionID, p.now())
		if err != nil {
			return err
		}
		answer.Record = &gatewayv1.PrivateRequestRecord{Request: document, ConnectionId: binding.ConnectionID}
		_ = sequence
		return nil
	})
	if err != nil {
		if err == store.ErrNoBinding {
			return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_BINDING, "connection_id")
		}
		return nil, internal(err)
	}
	if refused != nil {
		return nil, refuse(refused)
	}
	return connect.NewResponse(answer), nil
}

func (p *Publisher) GetPrivateRequest(
	ctx context.Context,
	request *connect.Request[gatewayv1.GetPrivateRequestRequest],
) (*connect.Response[gatewayv1.GetPrivateRequestResponse], error) {
	if !rules.IsID(request.Msg.GetRequestId()) {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID, "request_id")
	}
	held, err := p.store.PrivateRequest(ctx, publisherOf(ctx), request.Msg.GetRequestId())
	if err != nil {
		return nil, internal(err)
	}
	if held == nil {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_REQUEST, "request_id")
	}
	return connect.NewResponse(&gatewayv1.GetPrivateRequestResponse{Record: privateRecord(held)}), nil
}

func (p *Publisher) CancelPrivateRequest(
	ctx context.Context,
	request *connect.Request[gatewayv1.CancelPrivateRequestRequest],
) (*connect.Response[gatewayv1.CancelPrivateRequestResponse], error) {
	if !rules.IsID(request.Msg.GetRequestId()) {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID, "request_id")
	}
	serverID := publisherOf(ctx)
	answer := &gatewayv1.CancelPrivateRequestResponse{}
	var refused *rules.Fault
	err := p.store.Write(ctx, func(tx *store.Tx) error {
		held, err := tx.PrivateRequest(ctx, serverID, request.Msg.GetRequestId())
		if err != nil {
			return err
		}
		if held == nil {
			refused = &rules.Fault{Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_REQUEST, Field: "request_id"}
			return nil
		}
		if held.Result != nil {
			refused = &rules.Fault{Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_REQUEST_SETTLED,
				Field: "request_id", Held: held.Document.GetLifecycle().GetRevision()}
			return nil
		}
		if held.Document.GetLifecycle().GetStatus() == requestv2.RequestStatus_REQUEST_STATUS_CANCELLED {
			if request.Msg.GetRevision() == held.Document.GetLifecycle().GetRevision() {
				answer.Unchanged, answer.Record = true, privateRecord(held)
				return nil
			}
			refused = &rules.Fault{Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_CANCELLED,
				Field: "request_id", Held: held.Document.GetLifecycle().GetRevision()}
			return nil
		}
		cancelled, fault := rules.CancelledRequest(held.Document, request.Msg.GetRevision(), p.now())
		if fault != nil {
			refused = fault
			return nil
		}
		if _, err := tx.PutPrivateRequest(ctx, cancelled,
			cancelled.GetAudience().GetPrivate().GetRecipientId(), held.ConnectionID, p.now()); err != nil {
			return err
		}
		answer.Record = &gatewayv1.PrivateRequestRecord{Request: cancelled, ConnectionId: held.ConnectionID}
		return nil
	})
	if err != nil {
		return nil, internal(err)
	}
	if refused != nil {
		return nil, refuse(refused)
	}
	return connect.NewResponse(answer), nil
}

func (p *Publisher) RevokePrivateConnection(
	ctx context.Context,
	request *connect.Request[gatewayv1.RevokePrivateConnectionRequest],
) (*connect.Response[gatewayv1.RevokePrivateConnectionResponse], error) {
	if !rules.IsID(request.Msg.GetConnectionId()) {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_BINDING, "connection_id")
	}
	if err := p.store.RevokeBindingForServer(ctx, publisherOf(ctx), request.Msg.GetConnectionId(), p.now()); err != nil {
		if err == store.ErrNoBinding {
			return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_BINDING, "connection_id")
		}
		return nil, internal(err)
	}
	return connect.NewResponse(&gatewayv1.RevokePrivateConnectionResponse{}), nil
}

func privateRecord(held *store.StoredPrivateRequest) *gatewayv1.PrivateRequestRecord {
	if held == nil {
		return nil
	}
	return &gatewayv1.PrivateRequestRecord{Request: held.Document,
		ConnectionId: held.ConnectionID, Result: held.Result}
}
