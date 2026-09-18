package sdk

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"google.golang.org/protobuf/proto"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/gateway/v1"
)

func TestGatewayInvitationKeepsTheBackendCredentialOutOfURLsAndBodies(t *testing.T) {
	const credential = "publisher-backend-secret"
	var path, authorization, body string
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		path, authorization = request.URL.Path, request.Header.Get("Authorization")
		read, _ := io.ReadAll(request.Body)
		body = string(read)
		answer, _ := proto.Marshal(&gatewayv1.CreateInvitationResponse{Invitation: &gatewayv1.Invitation{
			InvitationId:  "7c9e6679-7425-40de-944b-e07fc1f90ae7",
			InvitationUrl: "https://gateway.example/invite/temporary",
			AppUri:        "seekervault://invite?token=temporary",
			Status:        gatewayv1.InvitationStatus_INVITATION_STATUS_PENDING,
		}})
		writer.Header().Set("Content-Type", "application/proto")
		_, _ = writer.Write(answer)
	}))
	defer server.Close()
	client, err := NewGateway(GatewayOptions{URL: server.URL, Token: credential,
		ServerID: "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"})
	if err != nil {
		t.Fatal(err)
	}
	invitation, err := client.CreateInvitation(context.Background(), "opaque-session-42", 15*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if path != "/seekervault.gateway.v1.PublisherService/CreateInvitation" ||
		authorization != "Bearer "+credential {
		t.Fatalf("path=%q authorization=%q", path, authorization)
	}
	if strings.Contains(path, credential) || strings.Contains(body, credential) ||
		strings.Contains(invitation.GetInvitationUrl(), credential) || strings.Contains(invitation.GetAppUri(), credential) {
		t.Fatal("the backend credential escaped its Authorization header")
	}
	if !strings.Contains(body, "opaque-session-42") || invitation.GetInvitationUrl() == "" || invitation.GetAppUri() == "" {
		t.Fatalf("the invitation was not created: body=%s invitation=%+v", body, invitation)
	}
}

func TestGatewayInvitationRejectsAMisleadingSubMinuteLifetimeLocally(t *testing.T) {
	client, err := NewGateway(GatewayOptions{URL: "https://gateway.example", Token: "secret",
		ServerID: "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = client.CreateInvitation(context.Background(), "opaque-session-42", 30*time.Second); err == nil ||
		!strings.Contains(err.Error(), "1m..24h") {
		t.Fatalf("sub-minute lifetime should be refused locally: %v", err)
	}
}

func TestGatewayRevokesAPendingInvitationThroughTheAuthenticatedBackendAPI(t *testing.T) {
	const credential = "publisher-backend-secret"
	var path, authorization string
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		path, authorization = request.URL.Path, request.Header.Get("Authorization")
		answer, _ := proto.Marshal(&gatewayv1.RevokeInvitationResponse{})
		writer.Header().Set("Content-Type", "application/proto")
		_, _ = writer.Write(answer)
	}))
	defer server.Close()
	client, err := NewGateway(GatewayOptions{URL: server.URL, Token: credential,
		ServerID: "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"})
	if err != nil {
		t.Fatal(err)
	}
	if err := client.RevokeInvitation(context.Background(),
		"7c9e6679-7425-40de-944b-e07fc1f90ae7"); err != nil {
		t.Fatal(err)
	}
	if path != "/seekervault.gateway.v1.PublisherService/RevokeInvitation" ||
		authorization != "Bearer "+credential {
		t.Fatalf("path=%q authorization=%q", path, authorization)
	}
}

func TestWaitForConnectionRetriesAnOfflinePoll(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if calls.Add(1) == 1 {
			http.Error(writer, "temporarily offline", http.StatusServiceUnavailable)
			return
		}
		answer, _ := proto.Marshal(&gatewayv1.GetInvitationResponse{Invitation: &gatewayv1.Invitation{
			InvitationId: "7c9e6679-7425-40de-944b-e07fc1f90ae7",
			ConnectionId: "8d0f778a-8536-41ef-a46d-f18f84ffbff8",
			Status:       gatewayv1.InvitationStatus_INVITATION_STATUS_CONNECTED,
		}})
		writer.Header().Set("Content-Type", "application/proto")
		_, _ = writer.Write(answer)
	}))
	defer server.Close()
	client, err := NewGateway(GatewayOptions{URL: server.URL, Token: "secret",
		ServerID: "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	connected, err := client.WaitForConnection(ctx,
		"7c9e6679-7425-40de-944b-e07fc1f90ae7", time.Millisecond)
	if err != nil || connected.GetConnectionId() == "" || calls.Load() != 2 {
		t.Fatalf("offline poll did not recover: calls=%d invitation=%+v err=%v",
			calls.Load(), connected, err)
	}
}
