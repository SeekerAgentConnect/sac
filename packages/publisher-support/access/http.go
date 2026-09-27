package access

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/limit"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/store"
)

// MostBodyBytes bounds every request to the authentication endpoint. The largest legitimate body is
// a device key and a label.
const MostBodyBytes = 16 << 10

// Handler is the public authentication endpoint phones call (`/access/v1/...`), served on its own
// listener at the origin the gateway's operator registered (PUBLISHER_AUTH_ADDRESS). It holds no
// credential for anything and answers nothing about anybody but the caller: a challenge is issued to
// whoever asks, and every step after it has to be signed by the key the wallet bound.
//
// The operator's actions are not here. They are [Service.AdminRoutes], mounted behind the
// publisher's own API token, which is the path the trader page already uses.
func (s *Service) Handler(limits Limits) http.Handler {
	if limits.PerHour <= 0 {
		limits.PerHour = 60
	}
	challenges := limit.New(limits.PerHour, time.Hour, s.now)
	questions := limit.New(limits.PerHour*20, time.Hour, s.now)
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(writer http.ResponseWriter, _ *http.Request) {
		send(writer, http.StatusOK, map[string]string{"status": "ok"})
	})
	mux.HandleFunc("POST /access/v1/challenges", s.limited(challenges, s.challenge))
	mux.HandleFunc("POST /access/v1/requests", s.limited(challenges, s.request))
	mux.HandleFunc("POST /access/v1/requests/{id}/status", s.limited(questions, s.status))
	mux.HandleFunc("POST /access/v1/redeem", s.limited(questions, s.redeem))
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("X-Content-Type-Options", "nosniff")
		writer.Header().Set("Referrer-Policy", "no-referrer")
		writer.Header().Set("Cache-Control", "no-store")
		mux.ServeHTTP(writer, request)
	})
}

// Limits bounds how often one address may ask.
type Limits struct {
	// PerHour is how many challenges (and answers) one address may ask for in an hour; status
	// questions and redemptions get twenty times that. Zero is 60.
	PerHour int
}

func (s *Service) limited(limiter *limit.Limiter, next http.HandlerFunc) http.HandlerFunc {
	return func(writer http.ResponseWriter, request *http.Request) {
		if limiter != nil && !limiter.Allow(clientOf(request)) {
			fail(writer, refusal(http.StatusTooManyRequests, "too_many_requests",
				"too many requests from this address; try again later"))
			return
		}
		next(writer, request)
	}
}

// clientOf is who a limit counts: the first forwarded address when a proxy in front says who it
// forwarded for, and the connection's otherwise.
func clientOf(request *http.Request) string {
	if forwarded := request.Header.Get("X-Forwarded-For"); forwarded != "" {
		first, _, _ := strings.Cut(forwarded, ",")
		return strings.TrimSpace(first)
	}
	host, _, err := net.SplitHostPort(request.RemoteAddr)
	if err != nil {
		return request.RemoteAddr
	}
	return host
}

type challengeBody struct {
	Feed      string `json:"feed"`
	Wallet    string `json:"wallet"`
	DeviceKey string `json:"device_key"`
	Label     string `json:"label"`
}

type challengeAnswer struct {
	Attempt      string `json:"attempt"`
	Nonce        string `json:"nonce"`
	IssuedAt     string `json:"issued_at"`
	ExpiresAt    string `json:"expires_at"`
	AuthOrigin   string `json:"auth_origin"`
	Feed         string `json:"feed"`
	Installation string `json:"installation"`
	Message      string `json:"message"`
}

func (s *Service) challenge(writer http.ResponseWriter, request *http.Request) {
	var body challengeBody
	if !read(writer, request, &body) {
		return
	}
	key, ok := bytesOf(body.DeviceKey)
	if !ok {
		fail(writer, refusal(400, "bad_device_key", "device_key must be base64"))
		return
	}
	answer, err := s.Challenge(request.Context(), ChallengeRequest{
		Channel: body.Feed, Wallet: body.Wallet, DeviceKey: key, Label: body.Label,
	})
	if s.failed(writer, err) {
		return
	}
	challenge := answer.Challenge
	send(writer, http.StatusCreated, challengeAnswer{
		Attempt: challenge.Attempt, Nonce: challenge.Nonce,
		IssuedAt:   challenge.IssuedAt.Format(time.RFC3339),
		ExpiresAt:  challenge.ExpiresAt.Format(time.RFC3339),
		AuthOrigin: challenge.AuthOrigin, Feed: challenge.Channel,
		Installation: challenge.Installation, Message: answer.Message,
	})
}

type requestBody struct {
	Attempt         string `json:"attempt"`
	WalletSignature string `json:"wallet_signature"`
	DeviceSignature string `json:"device_signature"`
}

func (s *Service) request(writer http.ResponseWriter, request *http.Request) {
	var body requestBody
	if !read(writer, request, &body) {
		return
	}
	wallet, okWallet := bytesOf(body.WalletSignature)
	device, okDevice := bytesOf(body.DeviceSignature)
	if !okWallet || !okDevice {
		fail(writer, refusal(400, "bad_signature", "signatures must be base64"))
		return
	}
	recorded, err := s.Request(request.Context(), Answer{
		Attempt: body.Attempt, WalletSignature: wallet, DeviceSignature: device,
	})
	if s.failed(writer, err) {
		return
	}
	send(writer, http.StatusCreated, map[string]string{
		"request_id": recorded.ID, "state": recorded.State,
	})
}

type signedBody struct {
	At              int64  `json:"at"`
	DeviceSignature string `json:"device_signature"`
}

type invitationAnswer struct {
	Token     string `json:"token"`
	ExpiresAt string `json:"expires_at"`
	Link      string `json:"link"`
}

type statusAnswer struct {
	RequestID  string            `json:"request_id"`
	State      string            `json:"state"`
	Connected  bool              `json:"connected"`
	Invitation *invitationAnswer `json:"invitation,omitempty"`
}

func (s *Service) status(writer http.ResponseWriter, request *http.Request) {
	var body signedBody
	if !read(writer, request, &body) {
		return
	}
	signature, ok := bytesOf(body.DeviceSignature)
	if !ok {
		fail(writer, refusal(400, "bad_signature", "device_signature must be base64"))
		return
	}
	status, err := s.Status(request.Context(), request.PathValue("id"),
		Signed{AtMillis: body.At, DeviceSignature: signature})
	if s.failed(writer, err) {
		return
	}
	answer := statusAnswer{RequestID: status.RequestID, State: status.State, Connected: status.Connected}
	if status.Invitation != nil {
		answer.Invitation = &invitationAnswer{
			Token: status.Invitation.Token, Link: status.Link,
			ExpiresAt: status.Invitation.ExpiresAt.UTC().Format(time.RFC3339),
		}
	}
	send(writer, http.StatusOK, answer)
}

type redeemBody struct {
	Feed            string `json:"feed"`
	Invitation      string `json:"invitation"`
	At              int64  `json:"at"`
	DeviceSignature string `json:"device_signature"`
}

type redeemAnswer struct {
	RequestID string `json:"request_id"`
	Session   string `json:"session"`
	GrantID   string `json:"grant_id"`
	Until     string `json:"until"`
	// "synced" when the gateway already holds the grant, "pending" when this publisher is still
	// telling it — reads are refused until it lands.
	Gateway string `json:"gateway"`
}

func (s *Service) redeem(writer http.ResponseWriter, request *http.Request) {
	var body redeemBody
	if !read(writer, request, &body) {
		return
	}
	signature, ok := bytesOf(body.DeviceSignature)
	if !ok {
		fail(writer, refusal(400, "bad_signature", "device_signature must be base64"))
		return
	}
	session, err := s.Redeem(request.Context(), Redemption{
		Channel: body.Feed, Invitation: body.Invitation,
		Signed: Signed{AtMillis: body.At, DeviceSignature: signature},
	})
	if s.failed(writer, err) {
		return
	}
	gatewayState := "pending"
	if session.Synced {
		gatewayState = "synced"
	}
	send(writer, http.StatusOK, redeemAnswer{
		RequestID: session.RequestID, Session: session.Session, GrantID: session.GrantID,
		Until: session.Until.UTC().Format(time.RFC3339), Gateway: gatewayState,
	})
}

// --- the operator's routes ----------------------------------------------------

// AdminRoutes are the operator's actions, for the publisher's token-protected API to mount
// (api.Plan.Access). Every mutation goes through this process's one store, so there is one writer.
func (s *Service) AdminRoutes() map[string]http.HandlerFunc {
	return map[string]http.HandlerFunc{
		"GET /v1/access/devices":                  s.listDevices,
		"POST /v1/access/devices/{id}/approve":    s.act("approve"),
		"POST /v1/access/devices/{id}/reject":     s.act("reject"),
		"POST /v1/access/devices/{id}/revoke":     s.act("revoke"),
		"POST /v1/access/devices/{id}/reissue":    s.act("reissue"),
		"POST /v1/access/wallets/{wallet}/revoke": s.revokeWallet,
	}
}

// DeviceView is one device as the operator sees it.
type DeviceView struct {
	RequestID    string `json:"request_id"`
	Wallet       string `json:"wallet"`
	Installation string `json:"installation"`
	// Label is the phone's own name for itself: a claim, shown as one.
	Label       string          `json:"label"`
	State       string          `json:"state"`
	Access      string          `json:"access"`
	RequestedAt string          `json:"requested_at"`
	DecidedAt   string          `json:"decided_at,omitempty"`
	DecidedBy   string          `json:"decided_by,omitempty"`
	RevokedAt   string          `json:"revoked_at,omitempty"`
	Invitation  *InvitationView `json:"invitation,omitempty"`
	Grant       *GrantView      `json:"grant,omitempty"`
}

// InvitationView is a device's newest invitation.
type InvitationView struct {
	State     string `json:"state"`
	IssuedAt  string `json:"issued_at"`
	ExpiresAt string `json:"expires_at"`
	// Link is present only while the invitation can still be redeemed.
	Link string `json:"link,omitempty"`
}

// GrantView is a device's newest grant and whether the gateway has confirmed it.
type GrantView struct {
	ID        string `json:"id"`
	State     string `json:"state"`
	ExpiresAt string `json:"expires_at"`
	// Gateway is "confirmed" when the gateway holds this state, and "pending" until it does.
	Gateway  string `json:"gateway"`
	Attempts int    `json:"attempts,omitempty"`
	Error    string `json:"error,omitempty"`
	SyncedAt string `json:"synced_at,omitempty"`
}

// View is a device as the operator's page shows it, including the one word that says where its
// access stands — and a revocation the gateway has not confirmed says exactly that.
func (s *Service) View(device store.AccessDevice) DeviceView {
	now := s.now()
	view := DeviceView{
		RequestID: device.ID, Wallet: device.Wallet, Installation: device.Installation,
		Label: device.Label, State: device.State, RequestedAt: stamp(&device.RequestedAt),
		DecidedAt: stamp(device.DecidedAt), DecidedBy: device.DecidedBy,
		RevokedAt: stamp(device.RevokedAt),
	}
	if invitation := device.Invitation; invitation != nil {
		state := "live"
		switch {
		case invitation.UsedAt != nil:
			state = "used"
		case invitation.SupersededAt != nil:
			state = "superseded"
		case !now.Before(invitation.ExpiresAt):
			state = "expired"
		}
		view.Invitation = &InvitationView{
			State: state, IssuedAt: stamp(&invitation.IssuedAt), ExpiresAt: stamp(&invitation.ExpiresAt),
		}
		if state == "live" && device.State == store.DeviceApproved {
			view.Invitation.Link = s.Link(invitation.Token)
		}
	}
	if grant := device.Grant; grant != nil {
		gatewayState := "confirmed"
		if !grant.Synced() {
			gatewayState = "pending"
		}
		view.Grant = &GrantView{
			ID: grant.ID, State: grant.State, ExpiresAt: stamp(&grant.ExpiresAt),
			Gateway: gatewayState, Attempts: grant.Attempts, Error: grant.SyncError,
			SyncedAt: stamp(grant.SyncedAt),
		}
	}
	view.Access = accessOf(device, view, now)
	return view
}

// accessOf is the one word for where a device's access stands.
func accessOf(device store.AccessDevice, view DeviceView, now time.Time) string {
	grant := device.Grant
	switch device.State {
	case store.DevicePending:
		return "pending_approval"
	case store.DeviceRejected:
		return "rejected"
	case store.DeviceRevoked:
		if grant != nil && !grant.Synced() {
			return "revocation_pending"
		}
		return "revoked"
	}
	if grant != nil && grant.State == store.GrantActive {
		switch {
		case !grant.Synced():
			return "grant_pending"
		case !now.Before(grant.ExpiresAt):
			return "expired"
		}
		return "connected"
	}
	if view.Invitation != nil && view.Invitation.State == "live" {
		return "invited"
	}
	return "invitation_expired"
}

func stamp(at *time.Time) string {
	if at == nil {
		return ""
	}
	return at.UTC().Format(time.RFC3339)
}

func (s *Service) listDevices(writer http.ResponseWriter, request *http.Request) {
	devices, err := s.Devices(request.Context())
	if s.failed(writer, err) {
		return
	}
	views := make([]DeviceView, 0, len(devices))
	for _, device := range devices {
		views = append(views, s.View(device))
	}
	send(writer, http.StatusOK, map[string]any{"feed": s.Channel(), "devices": views})
}

type actionBody struct {
	By string `json:"by"`
}

func (s *Service) act(action string) http.HandlerFunc {
	return func(writer http.ResponseWriter, request *http.Request) {
		var body actionBody
		if request.ContentLength != 0 && !read(writer, request, &body) {
			return
		}
		by := strings.TrimSpace(body.By)
		if by == "" {
			by = "operator"
		}
		id := request.PathValue("id")
		ctx := request.Context()
		var (
			device *store.AccessDevice
			err    error
		)
		switch action {
		case "approve":
			device, err = s.Approve(ctx, id, by)
		case "reject":
			device, err = s.Reject(ctx, id, by)
		case "reissue":
			device, err = s.Reissue(ctx, id)
		case "revoke":
			if err = s.Revoke(ctx, id); err == nil {
				device, err = s.store.Device(ctx, id)
			}
		}
		if s.failed(writer, err) {
			return
		}
		s.log.Info("the operator changed a device's access", "request", id, "action", action, "by", by)
		send(writer, http.StatusOK, s.View(*device))
	}
}

func (s *Service) revokeWallet(writer http.ResponseWriter, request *http.Request) {
	revoked, err := s.RevokeWallet(request.Context(), request.PathValue("wallet"))
	if s.failed(writer, err) {
		return
	}
	send(writer, http.StatusOK, map[string]int{"revoked": revoked})
}

// --- plumbing -------------------------------------------------------------------

type problemBody struct {
	Error  string `json:"error"`
	Detail string `json:"detail"`
}

func (s *Service) failed(writer http.ResponseWriter, err error) bool {
	if err == nil {
		return false
	}
	var problem *Problem
	if errors.As(err, &problem) {
		fail(writer, problem)
		return true
	}
	s.log.Error("an access call could not be served", "error", err)
	fail(writer, refusal(http.StatusInternalServerError, "internal",
		"this publisher could not serve that; its log says why"))
	return true
}

func fail(writer http.ResponseWriter, problem *Problem) {
	send(writer, problem.Status, problemBody{Error: problem.Code, Detail: problem.Detail})
}

func send(writer http.ResponseWriter, status int, body any) {
	writer.Header().Set("Content-Type", "application/json")
	writer.Header().Set("Cache-Control", "no-store")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(body)
}

// read decodes one strict JSON object: no unknown field, nothing after it, and a bounded size.
func read(writer http.ResponseWriter, request *http.Request, into any) bool {
	if kind := request.Header.Get("Content-Type"); kind != "" && !strings.HasPrefix(kind, "application/json") {
		fail(writer, refusal(http.StatusUnsupportedMediaType, "not_json", "send `Content-Type: application/json`"))
		return false
	}
	decoder := json.NewDecoder(http.MaxBytesReader(writer, request.Body, MostBodyBytes))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(into); err != nil || decoder.More() {
		fail(writer, refusal(http.StatusBadRequest, "bad_request", "send one JSON object with the documented fields"))
		return false
	}
	return true
}

// bytesOf decodes base64 in either alphabet, padded or not: Android's and Go's defaults differ, and
// neither is worth a refusal.
func bytesOf(text string) ([]byte, bool) {
	text = strings.TrimRight(text, "=")
	if text == "" {
		return nil, false
	}
	if raw, err := base64.RawURLEncoding.DecodeString(text); err == nil {
		return raw, true
	}
	raw, err := base64.RawStdEncoding.DecodeString(text)
	return raw, err == nil
}
