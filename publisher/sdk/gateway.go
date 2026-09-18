package sdk

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"

	"connectrpc.com/connect"
	"google.golang.org/protobuf/types/known/timestamppb"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/gateway/v1/gatewayv1connect"
	requestv2 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/request/v2"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/server/v1"
)

// GatewayOptions configures the independent-server side of the existing SDK. Token is the
// publisher credential and must stay in backend configuration; the SDK sends it only in the
// Authorization header and never puts it in an invitation URL, QR payload or response value.
type GatewayOptions struct {
	URL      string
	Token    string
	ServerID string
	HTTP     *http.Client
}

// Gateway is the Server SDK surface for gateway-private onboarding and request delivery.
type Gateway struct {
	serverID string
	client   gatewayv1connect.PublisherServiceClient
}

func NewGateway(options GatewayOptions) (*Gateway, error) {
	if strings.TrimSpace(options.URL) == "" || strings.TrimSpace(options.Token) == "" ||
		strings.TrimSpace(options.ServerID) == "" {
		return nil, errors.New("publisher sdk: gateway URL, backend token and server ID are required")
	}
	httpClient := options.HTTP
	if httpClient == nil {
		httpClient = &http.Client{Timeout: 10 * time.Second}
	}
	auth := func(next connect.UnaryFunc) connect.UnaryFunc {
		return func(ctx context.Context, request connect.AnyRequest) (connect.AnyResponse, error) {
			request.Header().Set("Authorization", "Bearer "+options.Token)
			return next(ctx, request)
		}
	}
	return &Gateway{serverID: options.ServerID,
		client: gatewayv1connect.NewPublisherServiceClient(httpClient,
			strings.TrimRight(options.URL, "/"), connect.WithInterceptors(connect.UnaryInterceptorFunc(auth)))}, nil
}

// ServerManifest is the independent server's durable statement. Publish it before creating an
// invitation; the gateway validates that the ID and origin are its own and that mode is private.
func (g *Gateway) PublishServerManifest(
	ctx context.Context,
	manifest *serverv1.ServerManifest,
) (*gatewayv1.PublishManifestResponse, error) {
	response, err := g.client.PublishManifest(ctx,
		connect.NewRequest(&gatewayv1.PublishManifestRequest{Manifest: manifest}))
	if err != nil {
		return nil, err
	}
	return response.Msg, nil
}

// CreateInvitation creates a temporary single-use onboarding capability for one opaque,
// server-scoped user or onboarding-session reference. Zero lifetime selects the gateway default.
func (g *Gateway) CreateInvitation(
	ctx context.Context,
	userRef string,
	lifetime time.Duration,
) (*gatewayv1.Invitation, error) {
	if strings.TrimSpace(userRef) == "" || lifetime < 0 || (lifetime > 0 && lifetime < time.Minute) ||
		lifetime > 24*time.Hour {
		return nil, errors.New("publisher sdk: user reference is required and lifetime must be zero or 1m..24h")
	}
	response, err := g.client.CreateInvitation(ctx, connect.NewRequest(&gatewayv1.CreateInvitationRequest{
		UserRef: userRef, LifetimeSeconds: uint32(lifetime / time.Second),
	}))
	if err != nil {
		return nil, err
	}
	return response.Msg.GetInvitation(), nil
}

// Invitation reports pending, connected or expired. Completion is polled over the authenticated
// backend API; no browser callback carries a publisher credential.
func (g *Gateway) Invitation(ctx context.Context, invitationID string) (*gatewayv1.Invitation, error) {
	response, err := g.client.GetInvitation(ctx,
		connect.NewRequest(&gatewayv1.GetInvitationRequest{InvitationId: invitationID}))
	if err != nil {
		return nil, err
	}
	return response.Msg.GetInvitation(), nil
}

// RevokeInvitation permanently invalidates one still-pending invitation. Retrying is safe. A
// completed invitation is a connection and must be handled with RevokeConnection instead.
func (g *Gateway) RevokeInvitation(ctx context.Context, invitationID string) error {
	_, err := g.client.RevokeInvitation(ctx,
		connect.NewRequest(&gatewayv1.RevokeInvitationRequest{InvitationId: invitationID}))
	return err
}

// WaitForConnection polls the completion transport until the invitation connects, expires, or the
// context ends. interval defaults to one second.
func (g *Gateway) WaitForConnection(
	ctx context.Context,
	invitationID string,
	interval time.Duration,
) (*gatewayv1.Invitation, error) {
	if interval <= 0 {
		interval = time.Second
	}
	timer := time.NewTimer(0)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-timer.C:
			invitation, err := g.Invitation(ctx, invitationID)
			if err != nil {
				code := connect.CodeOf(err)
				if code != connect.CodeUnavailable && code != connect.CodeDeadlineExceeded {
					return nil, err
				}
				timer.Reset(interval)
				continue
			}
			if invitation.GetStatus() != gatewayv1.InvitationStatus_INVITATION_STATUS_PENDING {
				return invitation, nil
			}
			timer.Reset(interval)
		}
	}
}

type PrivateRequest struct {
	RequestID string
	Revision  uint64
	UserRef   string
	// ConnectionID is the device binding reported by Invitation or WaitForConnection. Keeping it
	// explicit makes several devices for one user independently addressable and revocable.
	ConnectionID      string
	CreatedAt         time.Time
	UpdatedAt         time.Time
	ExpiresAt         time.Time
	Title             string
	Description       string
	CapabilityID      string
	CapabilityVersion uint32
	PluginID          string
	Parameters        []*requestv2.Value
	OwnerInputs       []*requestv2.OwnerInput
}

// SendRequest routes the common SEE-108 request contract to the confirmed ConnectionID for
// UserRef. The gateway verifies both belong together under this server. The owner still reviews it
// and the client still evaluates policy; connecting grants neither.
func (g *Gateway) SendRequest(ctx context.Context, asked PrivateRequest) (*gatewayv1.PrivateRequestRecord, error) {
	if strings.TrimSpace(asked.UserRef) == "" || strings.TrimSpace(asked.ConnectionID) == "" {
		return nil, errors.New("publisher sdk: user reference and connection ID are required")
	}
	created := asked.CreatedAt
	if created.IsZero() {
		created = time.Now().UTC()
	}
	updated := asked.UpdatedAt
	if updated.IsZero() {
		updated = created
	}
	document := &requestv2.Request{ContractVersion: 1,
		Identity: &requestv2.RequestIdentity{SourceId: g.serverID,
			Scope: "private/" + g.serverID, RequestId: asked.RequestID},
		Lifecycle: &requestv2.RequestLifecycle{Revision: asked.Revision,
			Status:    requestv2.RequestStatus_REQUEST_STATUS_OPEN,
			CreatedAt: timestamppb.New(created), UpdatedAt: timestamppb.New(updated),
			ExpiresAt: timestamppb.New(asked.ExpiresAt)},
		Presentation: &requestv2.Presentation{Title: asked.Title, Description: asked.Description,
			Category: requestv2.PresentationCategory_PRESENTATION_CATEGORY_REQUEST},
		Action: &requestv2.ActionCapability{CapabilityId: asked.CapabilityID,
			CapabilityVersion: asked.CapabilityVersion, PluginId: asked.PluginID,
			Parameters: asked.Parameters},
		OwnerInputs: asked.OwnerInputs,
		Audience: &requestv2.Audience{Audience: &requestv2.Audience_Private{
			Private: &requestv2.PrivateAudience{RecipientId: asked.UserRef}}},
		ResultHandling: &requestv2.ResultHandling{Mode: requestv2.ResultMode_RESULT_MODE_RETURN_TO_ORIGIN}}
	response, err := g.client.CreatePrivateRequest(ctx,
		connect.NewRequest(&gatewayv1.CreatePrivateRequestRequest{
			Request: document, ConnectionId: asked.ConnectionID,
		}))
	if err != nil {
		return nil, err
	}
	return response.Msg.GetRecord(), nil
}

// Request returns the source document and, once SAC has an outcome to return, its result.
func (g *Gateway) Request(ctx context.Context, requestID string) (*gatewayv1.PrivateRequestRecord, error) {
	response, err := g.client.GetPrivateRequest(ctx,
		connect.NewRequest(&gatewayv1.GetPrivateRequestRequest{RequestId: requestID}))
	if err != nil {
		return nil, err
	}
	return response.Msg.GetRecord(), nil
}

func (g *Gateway) CancelRequest(ctx context.Context, requestID string, revision uint64) (*gatewayv1.PrivateRequestRecord, error) {
	response, err := g.client.CancelPrivateRequest(ctx,
		connect.NewRequest(&gatewayv1.CancelPrivateRequestRequest{RequestId: requestID, Revision: revision}))
	if err != nil {
		return nil, err
	}
	return response.Msg.GetRecord(), nil
}

// RevokeConnection revokes one device binding owned by this server. It is independent of backend
// credential rotation and any other user/device binding. Reconnecting always starts with a fresh
// invitation.
func (g *Gateway) RevokeConnection(ctx context.Context, connectionID string) error {
	_, err := g.client.RevokePrivateConnection(ctx,
		connect.NewRequest(&gatewayv1.RevokePrivateConnectionRequest{ConnectionId: connectionID}))
	return err
}
