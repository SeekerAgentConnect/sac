package gateway

import (
	"context"
	"crypto/sha256"
	"errors"
	"html/template"
	"net/http"
	"net/url"
	"strings"
	"time"

	"connectrpc.com/connect"
	"github.com/skip2/go-qrcode"
	"google.golang.org/protobuf/types/known/timestamppb"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/store"
)

type Invitation struct {
	store   *store.Store
	gateway string
	now     func() time.Time
}

func NewInvitation(from *store.Store, gateway string, now func() time.Time) *Invitation {
	return &Invitation{store: from, gateway: gateway, now: now}
}

func (i *Invitation) ResolveInvitation(
	ctx context.Context,
	request *connect.Request[gatewayv1.ResolveInvitationRequest],
) (*connect.Response[gatewayv1.ResolveInvitationResponse], error) {
	token := request.Msg.GetToken()
	if !validToken(token) {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_INVALID_INVITATION, "token")
	}
	one, manifest, err := i.resolve(ctx, token)
	if err != nil {
		return nil, err
	}
	status := gatewayv1.InvitationStatus_INVITATION_STATUS_PENDING
	if one.RedeemedAt != nil {
		status = gatewayv1.InvitationStatus_INVITATION_STATUS_CONNECTED
	} else if !i.now().Before(one.ExpiresAt) {
		status = gatewayv1.InvitationStatus_INVITATION_STATUS_EXPIRED
	}
	return connect.NewResponse(&gatewayv1.ResolveInvitationResponse{
		InvitationId: one.ID, ServerId: one.ServerID, DisplayName: manifest.GetDisplayName(),
		ExpiresAt: timestamppb.New(one.ExpiresAt), Status: status, Manifest: manifest,
	}), nil
}

func (i *Invitation) RedeemInvitation(
	ctx context.Context,
	request *connect.Request[gatewayv1.RedeemInvitationRequest],
) (*connect.Response[gatewayv1.RedeemInvitationResponse], error) {
	token := request.Msg.GetToken()
	if !validToken(token) {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_INVALID_INVITATION, "token")
	}
	if !rules.IsDeviceName(request.Msg.GetDeviceName()) {
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_NAME, "device_name")
	}
	// Resolve the manifest before the write. Its mode and origin cannot change at a later revision,
	// and deleting the publisher cascades the invitation, so Redeem below either commits against
	// this same private identity or finds no invitation. A successful commit can always return a
	// manifest the phone can validate; there is no committed binding followed by a second read.
	_, manifest, resolveErr := i.resolve(ctx, token)
	if resolveErr != nil {
		return nil, resolveErr
	}
	connectionID, err := randomID()
	if err != nil {
		return nil, internal(err)
	}
	credential, err := randomToken()
	if err != nil {
		return nil, internal(err)
	}
	tokenHash := sha256.Sum256([]byte(token))
	credentialHash := sha256.Sum256([]byte(credential))
	one, err := i.store.Redeem(ctx, tokenHash[:], connectionID, credentialHash[:],
		request.Msg.GetDeviceName(), i.now())
	switch {
	case errors.Is(err, store.ErrNoInvitation):
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_INVALID_INVITATION, "token")
	case errors.Is(err, store.ErrInvitationExpired):
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_INVITATION_EXPIRED, "token")
	case errors.Is(err, store.ErrInvitationRevoked):
		// A revoked capability looks exactly like a token that was never valid to the unauthenticated
		// side of the boundary. Only the authenticated origin knows it revoked the invitation.
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_INVALID_INVITATION, "token")
	case errors.Is(err, store.ErrInvitationUsed):
		return nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_INVITATION_USED, "token")
	case err != nil:
		return nil, internal(err)
	}
	return connect.NewResponse(&gatewayv1.RedeemInvitationResponse{ConnectionId: connectionID,
		DeviceToken: credential, ServerId: one.ServerID, Manifest: manifest}), nil
}

func (i *Invitation) resolve(ctx context.Context, token string) (*store.Invitation, *serverv1.ServerManifest, error) {
	hash := sha256.Sum256([]byte(token))
	one, err := i.store.InvitationForToken(ctx, hash[:])
	if err != nil {
		return nil, nil, internal(err)
	}
	if one == nil {
		return nil, nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_INVALID_INVITATION, "token")
	}
	if one.RevokedAt != nil {
		return nil, nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_INVALID_INVITATION, "token")
	}
	held, err := i.store.Manifest(ctx, one.ServerID)
	if err != nil {
		return nil, nil, internal(err)
	}
	if held == nil || held.Document.GetMode() != serverv1.ConnectionMode_CONNECTION_MODE_GATEWAY_PRIVATE ||
		held.Document.GetGatewayPrivate().GetGatewayUrl() != i.gateway {
		return nil, nil, problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NOT_PRIVATE, "manifest.mode")
	}
	return one, held.Document, nil
}

// Page is the shared gateway-hosted invitation page. Every route here is a GET and calls only the
// read half of the invitation store; neither viewing the page nor rendering/scanning its QR can
// consume an invitation or create a binding.
func (i *Invitation) Page(writer http.ResponseWriter, request *http.Request) {
	writer.Header().Set("Cache-Control", "no-store")
	writer.Header().Set("Referrer-Policy", "no-referrer")
	writer.Header().Set("X-Content-Type-Options", "nosniff")
	writer.Header().Set("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
	if request.Method != http.MethodGet && request.Method != http.MethodHead {
		writer.Header().Set("Allow", "GET, HEAD")
		http.Error(writer, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	remainder := strings.TrimPrefix(request.URL.Path, "/invite/")
	qr := strings.HasSuffix(remainder, "/qr.png")
	token := strings.TrimSuffix(remainder, "/qr.png")
	if strings.Contains(token, "/") || !validToken(token) {
		http.NotFound(writer, request)
		return
	}
	one, manifest, err := i.resolve(request.Context(), token)
	if err != nil {
		http.NotFound(writer, request)
		return
	}
	appURI := "seekervault://invite?v=1&gateway=" + url.QueryEscape(i.gateway) + "&token=" + token
	if qr {
		image, err := qrcode.Encode(appURI, qrcode.Medium, 320)
		if err != nil {
			http.Error(writer, "could not render QR", http.StatusInternalServerError)
			return
		}
		writer.Header().Set("Content-Type", "image/png")
		if request.Method == http.MethodGet {
			_, _ = writer.Write(image)
		}
		return
	}
	writer.Header().Set("Content-Type", "text/html; charset=utf-8")
	state := "Ready to connect"
	pending := true
	if one.RedeemedAt != nil {
		state, pending = "Already connected", false
	} else if !i.now().Before(one.ExpiresAt) {
		state, pending = "Invitation expired", false
		writer.WriteHeader(http.StatusGone)
	}
	if request.Method == http.MethodHead {
		return
	}
	_ = invitationPage.Execute(writer, struct {
		Name, State, AppURI string
		Pending             bool
	}{manifest.GetDisplayName(), state, appURI, pending})
}

var invitationPage = template.Must(template.New("invitation").Parse(`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Connect to {{.Name}} · Seeker Agent Wallet</title><style>
body{font:16px system-ui,sans-serif;background:#0d1117;color:#f0f3f6;margin:0;display:grid;min-height:100vh;place-items:center}
main{max-width:32rem;padding:2rem;text-align:center}img{background:white;border-radius:18px;padding:12px;width:260px;height:260px}
a{display:inline-block;margin:1rem;padding:.8rem 1.2rem;border-radius:999px;background:#7c5cff;color:white;text-decoration:none}
.state{color:#aab2c0}small{color:#8b949e}</style></head><body><main>
<h1>{{.Name}}</h1><p class="state">{{.State}}</p>{{if .Pending}}
<img src="qr.png" alt="QR code to open this invitation in Seeker Agent Wallet">
<p><a href="{{.AppURI}}">Open in SAC</a></p>
<p>On another device, scan the QR from SAC’s Add connection screen.</p>{{end}}
<small>Opening or previewing this page does not connect a device. SAC shows the server details and asks for confirmation first. If SAC is not installed, install it from the project’s verified release source, then return to this page.</small>
</main></body></html>`))
