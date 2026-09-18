// Command gateway-website is the developer-owned webpage form of SEE-109 onboarding.
//
// The browser receives only a temporary invitation. The gateway publisher credential stays in
// this backend process, and the gateway-hosted QR is the only image the page loads.
package main

import (
	"context"
	"html/template"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/gateway/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher/sdk"
)

func main() {
	gatewayURL := os.Getenv("GATEWAY_URL")
	serverID := os.Getenv("SERVER_ID")
	client, err := sdk.NewGateway(sdk.GatewayOptions{
		URL: gatewayURL, Token: os.Getenv("GATEWAY_TOKEN"), ServerID: serverID,
	})
	if err != nil {
		log.Fatal(err)
	}
	kind, err := websiteKind(os.Getenv("SERVER_KIND"))
	if err != nil {
		log.Fatal(err)
	}
	environment, err := websiteEnvironment(os.Getenv("SERVER_ENVIRONMENT"))
	if err != nil {
		log.Fatal(err)
	}
	_, err = client.PublishServerManifest(context.Background(), &serverv1.ServerManifest{
		ServerId: serverID, ProtocolVersion: 1, SettingsRevision: 1,
		Mode: serverv1.ConnectionMode_CONNECTION_MODE_GATEWAY_PRIVATE,
		Reference: &serverv1.ServerManifest_GatewayPrivate{GatewayPrivate: &serverv1.GatewayPrivate{
			GatewayUrl: gatewayURL,
		}},
		RequiredPlugins: []*serverv1.PluginRequirement{{
			PluginId: kind.plugin, MinContract: 1, MaxContract: 1,
		}},
		Environments: []serverv1.ServerEnvironment{environment},
		DisplayName:  kind.name,
	})
	if err != nil {
		log.Fatal(err)
	}

	handler := &website{gateway: client, kind: kind}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /", handler.form)
	mux.HandleFunc("POST /invitations", handler.create)
	mux.HandleFunc("GET /status", handler.status)
	server := &http.Server{Addr: address(), Handler: securityHeaders(mux), ReadHeaderTimeout: 5 * time.Second}
	log.Printf("developer invitation page listening on %s", server.Addr)
	log.Fatal(server.ListenAndServe())
}

func websiteEnvironment(given string) (serverv1.ServerEnvironment, error) {
	switch strings.ToLower(strings.TrimSpace(given)) {
	case "", "sandbox":
		return serverv1.ServerEnvironment_SERVER_ENVIRONMENT_SANDBOX, nil
	case "production":
		return serverv1.ServerEnvironment_SERVER_ENVIRONMENT_PRODUCTION, nil
	default:
		return serverv1.ServerEnvironment_SERVER_ENVIRONMENT_UNSPECIFIED,
			&configurationError{"SERVER_ENVIRONMENT must be sandbox or production"}
	}
}

type kind struct{ name, plugin string }

func websiteKind(given string) (kind, error) {
	switch strings.ToLower(strings.TrimSpace(given)) {
	case "", "trading":
		return kind{"Trading bot", "jupiter.swap"}, nil
	case "prediction":
		return kind{"Prediction bot", "jupiter.prediction"}, nil
	case "mcp":
		// MCP is how the backend receives work, not a phone-side approval capability. This example
		// uses the bundled swap plugin; an MCP tool handler calls the same sdk.SendRequest as a CLI.
		return kind{"MCP-backed trading agent", "jupiter.swap"}, nil
	default:
		return kind{}, &configurationError{"SERVER_KIND must be trading, prediction, or mcp"}
	}
}

type configurationError struct{ message string }

func (e *configurationError) Error() string { return e.message }

type website struct {
	gateway *sdk.Gateway
	kind    kind
}

func (w *website) form(writer http.ResponseWriter, _ *http.Request) {
	w.render(writer, page{Name: w.kind.name})
}

func (w *website) create(writer http.ResponseWriter, request *http.Request) {
	if err := request.ParseForm(); err != nil {
		http.Error(writer, "invalid form", http.StatusBadRequest)
		return
	}
	userRef := strings.TrimSpace(request.FormValue("user_ref"))
	if userRef == "" {
		http.Error(writer, "user_ref is required", http.StatusBadRequest)
		return
	}
	invitation, err := w.gateway.CreateInvitation(request.Context(), userRef, 15*time.Minute)
	if err != nil {
		http.Error(writer, "could not create invitation", http.StatusBadGateway)
		return
	}
	// html/template rejects non-HTTP schemes unless the backend marks its validated SDK value as a
	// URL. Both values came from the authenticated gateway response, not from the submitted form.
	w.render(writer, page{
		Name: w.kind.name, InvitationID: invitation.GetInvitationId(),
		HostedURL: template.URL(invitation.GetInvitationUrl()),             // #nosec G203 -- authenticated SDK value
		AppURI:    template.URL(invitation.GetAppUri()),                    // #nosec G203 -- authenticated SDK value
		QRURL:     template.URL(invitation.GetInvitationUrl() + "/qr.png"), // #nosec G203
		Expires:   invitation.GetExpiresAt().AsTime().Format(time.RFC3339),
	})
}

func (w *website) status(writer http.ResponseWriter, request *http.Request) {
	id := request.URL.Query().Get("id")
	invitation, err := w.gateway.Invitation(request.Context(), id)
	if err != nil {
		http.Error(writer, "could not read invitation", http.StatusBadGateway)
		return
	}
	state := "Pending"
	if invitation.GetStatus() == gatewayv1.InvitationStatus_INVITATION_STATUS_CONNECTED {
		state = "Connected — device " + invitation.GetConnectionId()
	} else if invitation.GetStatus() == gatewayv1.InvitationStatus_INVITATION_STATUS_EXPIRED {
		state = "Expired — create a fresh invitation"
	}
	w.render(writer, page{Name: w.kind.name, State: state})
}

type page struct {
	Name, InvitationID, Expires, State string
	HostedURL, AppURI, QRURL           template.URL
}

func (w *website) render(writer http.ResponseWriter, value page) {
	writer.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := webpage.Execute(writer, value); err != nil {
		log.Printf("render invitation page: %v", err)
	}
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Cache-Control", "no-store")
		writer.Header().Set("Referrer-Policy", "no-referrer")
		writer.Header().Set("X-Content-Type-Options", "nosniff")
		writer.Header().Set("Content-Security-Policy", "default-src 'none'; img-src https:; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
		next.ServeHTTP(writer, request)
	})
}

func address() string {
	if value := strings.TrimSpace(os.Getenv("LISTEN_ADDR")); value != "" {
		return value
	}
	return "127.0.0.1:8080"
}

var webpage = template.Must(template.New("page").Parse(`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Connect SAC</title><style>body{font:16px system-ui;max-width:36rem;margin:3rem auto;padding:1rem}img{width:240px}label,input,button,a{display:block;margin:.75rem 0;padding:.5rem}</style></head><body>
<h1>Connect SAC to {{.Name}}</h1>
{{if .InvitationID}}<p>This invitation expires at {{.Expires}}. Viewing this page does not connect a device.</p>
<img src="{{.QRURL}}" alt="QR code for this SAC invitation">
<a href="{{.AppURI}}">Open in SAC</a><a href="{{.HostedURL}}">Use the gateway-hosted page</a>
<a href="/status?id={{.InvitationID}}">Check connection status</a>
{{else if .State}}<p>{{.State}}</p><a href="/">Create another invitation</a>
{{else}}<form method="post" action="/invitations"><label>Opaque server user/session reference
<input name="user_ref" required maxlength="128"></label><button type="submit">Create SAC invitation</button></form>{{end}}
</body></html>`))
