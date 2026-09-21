// Package pushrelay is the gateway's private push routing for independently hosted direct servers
// (SEE-144, docs/guides/server-development.md#the-gateway-push-relay).
//
// # What it is for
//
// A developer runs their own MCP server. A phone pairs with it directly, streams from it, syncs
// with it and submits decisions to it — none of that goes through this gateway and none of it
// changes. The one thing the developer cannot do alone is wake a phone whose app is in the
// background, because that needs a Firebase project and a service-account credential. This routes
// exactly that, and nothing else.
//
// # What it deliberately is not
//
// It is not the gateway-private request routing that SEE-130 removed, and it must not become it.
// Nothing here holds a request, an approval, a signature, a result, a wallet or anything an owner
// decided. The message it sends carries two constant fields; the phone that receives one goes and
// reads its own server, authenticated, and renders what that server says. A push never executes,
// approves or reveals anything — it says "there is something to read", and the reading is the part
// that has authority.
//
// # The three authorities, and why they are three
//
//   - The operator registers a server and enables relay for it (internal/admin). Without that, a
//     server can hold any credential it likes and reach nothing.
//   - The phone's installation proves ownership of itself with a secret this gateway minted once,
//     and authorizes one server per direct connection. Without that, knowing a device's FCM target
//     is worth nothing: there is no call that changes where a target points without the secret.
//   - The server proves it is the server the binding names, with its own scoped relay credential.
//     Without that, a handle is a string.
//
// A send happens where all three meet, and each of the three is a separate secret held by a
// separate party. That is what makes "a server cannot wake a phone that did not agree", "a phone
// cannot be redirected by someone who saw its target" and "a server cannot use another server's
// handle" three properties of the data rather than three checks somebody has to remember.
//
// # Best effort, always
//
// Every failure here is the caller's to shrug at. The request that prompted a wake-up was created,
// committed and answered on the server that asked; the phone finds out on its next stream or its
// next foreground read either way. So this surface answers "retry" or "do not" and never becomes a
// reason a request, a synchronization or an approval did not happen.
package pushrelay

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/credential"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/relay"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// Sender is the push itself, as the one thing this package cannot test without: internal/relay's
// device dispatch, or a fake endpoint standing in for it.
type Sender interface {
	Send(ctx context.Context, target string, timeSensitive bool) (relay.Outcome, error)
}

// Limiter is a token bucket, taken as an interface so this package uses the gateway's own limiter
// without importing it — the gateway builds this surface, not the other way round.
type Limiter interface{ Allow(key string) bool }

// Options is everything the relay is built from. Nothing here has a default that opens something.
type Options struct {
	Store storage.RelayStore
	// Send is nil on a deployment with no Firebase credential. The phone-facing calls still work —
	// a phone may enroll and bind against a gateway whose operator has not finished configuring
	// push — and a server that asks for a send is told the relay cannot send right now, which is
	// true and is retryable.
	Send Sender

	// BindingLifetime is how long one authorization lasts before the phone renews it. It is the
	// bound on an abandoned grant: a phone that is wiped, reinstalled or simply stops caring
	// cannot leave a server able to wake it for ever, and nothing has to happen for that to end.
	BindingLifetime time.Duration

	// Enrollments counts the phone-facing calls against the caller's address, and Servers and
	// Bindings bound what an authorized server may cost — per server, and per device it may wake.
	// Global is the whole deployment's ceiling.
	Enrollments Limiter
	Servers     Limiter
	Bindings    Limiter
	Global      Limiter
	// Caller says who a caller is under the deployment's trusted-proxy policy, for Enrollments.
	Caller func(*http.Request) string

	Log *slog.Logger
	Now func() time.Time
}

// Server is the relay, as two handlers on two listeners.
//
// They are separate because their callers are: Installations is reached by phones, over the public
// read listener they already read feeds from; Servers is reached by developers' backends, over the
// publisher listener they already publish to and which a deployment may keep off the internet
// entirely. Neither route exists on the other's listener, so a routing mistake cannot let a phone
// send an invalidation or a server enroll an installation.
type Server struct {
	// Installations is the phone-facing half: enroll, replace a target, authorize and revoke
	// bindings, and read back what this gateway still holds.
	Installations http.Handler
	// Servers is the server-facing half: one call, which wakes one device.
	Servers http.Handler

	options Options
}

// New builds the relay, or says what is missing. A half-built one is not served.
func New(options Options) (*Server, error) {
	switch {
	case options.Store == nil:
		return nil, errors.New("pushrelay: no store")
	case options.BindingLifetime <= 0:
		return nil, errors.New("pushrelay: no binding lifetime")
	case options.Enrollments == nil || options.Servers == nil || options.Bindings == nil ||
		options.Global == nil:
		return nil, errors.New("pushrelay: incomplete limits")
	case options.Caller == nil || options.Log == nil || options.Now == nil:
		return nil, errors.New("pushrelay: incomplete options")
	}
	server := &Server{options: options}

	phones := http.NewServeMux()
	phones.HandleFunc("POST "+Prefix+"/installations", server.metered(server.enroll))
	phones.HandleFunc("GET "+Prefix+"/installations/{installation}", server.metered(server.read))
	phones.HandleFunc("DELETE "+Prefix+"/installations/{installation}", server.metered(server.forget))
	phones.HandleFunc("POST "+Prefix+"/installations/{installation}/target", server.metered(server.retarget))
	phones.HandleFunc("POST "+Prefix+"/installations/{installation}/bindings", server.metered(server.bind))
	phones.HandleFunc("DELETE "+Prefix+"/installations/{installation}/bindings/{binding}",
		server.metered(server.unbind))

	backends := http.NewServeMux()
	backends.HandleFunc("POST "+Prefix+"/notify", server.notify)

	server.Installations = headers(phones)
	server.Servers = headers(backends)
	return server, nil
}

// headers are on every answer, including the refusals. This surface serves JSON to programs; there
// is no page here, nothing to frame and nothing a browser should keep.
func headers(next http.Handler) http.Handler {
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		head := writer.Header()
		head.Set("Cache-Control", "no-store, max-age=0")
		head.Set("X-Content-Type-Options", "nosniff")
		head.Set("Referrer-Policy", "no-referrer")
		next.ServeHTTP(writer, request)
	})
}

// metered counts a phone-facing call against the address it came from.
//
// It is per caller rather than per installation on purpose: an installation identity is minted by
// the call this protects, so counting against one would let anyone mint as many buckets as they
// liked. It bounds what an address can cost the store, which is the same job the read listener's
// limiter does for feed reads, and it is deliberately not the bound that matters for sending —
// that one is per server and per binding, on the other listener.
func (s *Server) metered(next http.HandlerFunc) http.HandlerFunc {
	return func(writer http.ResponseWriter, request *http.Request) {
		if !s.options.Enrollments.Allow(s.options.Caller(request)) {
			refuse(writer, http.StatusTooManyRequests, tooMany)
			return
		}
		next(writer, request)
	}
}

// --- the phone's half ---------------------------------------------------------

// enroll mints an installation identity and the secret that proves ownership of it.
//
// There is no authentication on this call and there cannot be: the caller has nothing yet. What
// bounds it is the rate limit above and the fact that an enrollment grants nothing — an
// installation with no bindings is a row that can be woken by nobody, and every binding it later
// gets is authorized individually and refused unless the operator enabled that server.
func (s *Server) enroll(writer http.ResponseWriter, request *http.Request) {
	var asked enrollRequest
	if !read(writer, request, &asked) {
		return
	}
	if asked.Version != Version {
		refuse(writer, http.StatusBadRequest, wrongVersion)
		return
	}
	if !validTarget(asked.Target) {
		// Never repeating the value: a refusal that quoted a registration would be the one place
		// this service writes one down where somebody reads it.
		refuse(writer, http.StatusBadRequest, malformed)
		return
	}
	installation := newID()
	secret, hash := credential.New()
	if err := s.options.Store.Enroll(
		request.Context(), installation, hash, asked.Target, s.options.Now()); err != nil {
		s.failed(writer, "enroll installation", err)
		return
	}
	s.options.Log.Info("relay installation enrolled")
	answer(writer, http.StatusCreated, enrollResponse{
		Version:        Version,
		Installation:   installation,
		Secret:         secret,
		BindingSeconds: int(s.options.BindingLifetime.Seconds()),
	})
}

// retarget replaces the registration after Firebase rotates it.
//
// This is the call the whole ownership model exists for. Somebody who has seen a device's FCM
// target — a server it paired with, anyone who ever held one — must not be able to point that
// device's wake-ups somewhere else, or to point somebody else's at themselves. The secret is what
// is checked, it is checked inside the transaction that writes, and there is no other way in.
func (s *Server) retarget(writer http.ResponseWriter, request *http.Request) {
	installation, secret, ok := s.identified(writer, request)
	if !ok {
		return
	}
	var asked targetRequest
	if !read(writer, request, &asked) {
		return
	}
	if asked.Version != Version {
		refuse(writer, http.StatusBadRequest, wrongVersion)
		return
	}
	if !validTarget(asked.Target) {
		refuse(writer, http.StatusBadRequest, malformed)
		return
	}
	err := s.options.Store.SetTarget(
		request.Context(), installation, credential.Hash(secret), asked.Target, s.options.Now())
	switch {
	case errors.Is(err, storage.ErrNoInstallation):
		s.absent(writer)
	case err != nil:
		s.failed(writer, "replace target", err)
	default:
		writer.WriteHeader(http.StatusNoContent)
	}
}

// read is reconciliation in one call: does this gateway still hold my enrollment, and which of my
// authorizations does it still have?
//
// It is what makes a gateway that lost its database recoverable without re-pairing anything. The
// app gets a 404, enrolls again, and re-authorizes the connections it holds — the direct
// connections themselves were never the gateway's and are untouched.
func (s *Server) read(writer http.ResponseWriter, request *http.Request) {
	installation, secret, ok := s.identified(writer, request)
	if !ok {
		return
	}
	hash := credential.Hash(secret)
	held, err := s.options.Store.Installation(request.Context(), installation, hash)
	switch {
	case errors.Is(err, storage.ErrNoInstallation):
		s.absent(writer)
		return
	case err != nil:
		s.failed(writer, "read installation", err)
		return
	}
	bindings, err := s.options.Store.Bindings(request.Context(), installation, hash)
	if err != nil {
		s.failed(writer, "list bindings", err)
		return
	}
	view := installationResponse{
		Version: Version, Installation: held.ID, HasTarget: held.HasTarget,
		Bindings: []bindingView{},
	}
	for _, binding := range bindings {
		view.Bindings = append(view.Bindings, bindingView{
			Binding:    binding.ID,
			Server:     binding.ServerID,
			Connection: binding.Connection,
			Expires:    instant(binding.ExpiresAt),
			Revoked:    binding.RevokedAt != nil,
		})
	}
	answer(writer, http.StatusOK, view)
}

// forget removes the installation and every binding it authorized. It is the owner's own
// "disconnect everything", and it is proved the same way as everything else.
func (s *Server) forget(writer http.ResponseWriter, request *http.Request) {
	installation, secret, ok := s.identified(writer, request)
	if !ok {
		return
	}
	err := s.options.Store.ForgetInstallation(
		request.Context(), installation, credential.Hash(secret))
	switch {
	case errors.Is(err, storage.ErrNoInstallation):
		// Already gone is the outcome that was asked for. A phone retries this after being
		// offline, and a retry must not be a failure it keeps retrying.
		writer.WriteHeader(http.StatusNoContent)
	case err != nil:
		s.failed(writer, "forget installation", err)
	default:
		s.options.Log.Info("relay installation forgotten")
		writer.WriteHeader(http.StatusNoContent)
	}
}

// bind authorizes one server to wake this installation, for one direct connection.
//
// The server named here is the one the phone authenticated directly, and the gateway cannot verify
// that pairing — it is not in it. What it can guarantee is that the authorization is worth nothing
// to anybody else: the handle it returns works only for a caller who also holds that server's
// scoped relay credential, which the operator issued and only that developer has. A phone that
// named the wrong server has given that server nothing and cost itself a wake-up.
func (s *Server) bind(writer http.ResponseWriter, request *http.Request) {
	installation, secret, ok := s.identified(writer, request)
	if !ok {
		return
	}
	var asked bindRequest
	if !read(writer, request, &asked) {
		return
	}
	switch {
	case asked.Version != Version:
		refuse(writer, http.StatusBadRequest, wrongVersion)
		return
	case !rules.IsID(asked.Server), !validConnection(asked.Connection):
		refuse(writer, http.StatusBadRequest, malformed)
		return
	}
	handle, handleHash := credential.New()
	now := s.options.Now()
	expires := now.Add(s.options.BindingLifetime)
	binding := newID()
	err := s.options.Store.Bind(request.Context(), storage.RelayBindingRequest{
		InstallationID: installation,
		SecretHash:     credential.Hash(secret),
		ServerID:       asked.Server,
		Connection:     asked.Connection,
		HandleID:       binding,
		HandleHash:     handleHash,
		CreatedAt:      now,
		ExpiresAt:      expires,
	})
	switch {
	case errors.Is(err, storage.ErrNoInstallation):
		s.absent(writer)
	case errors.Is(err, storage.ErrNotPermitted):
		// One answer for "no such server" and "relay is off for that server". A phone may learn
		// that this binding was refused; which of the two it was is the operator's business.
		refuse(writer, http.StatusForbidden, notPermitted)
	case err != nil:
		s.failed(writer, "authorize binding", err)
	default:
		s.options.Log.Info("relay binding authorized", "server", asked.Server, "binding", binding)
		answer(writer, http.StatusCreated, bindResponse{
			Version: Version, Binding: binding, Handle: handle, Expires: instant(expires),
		})
	}
}

// unbind revokes one authorization. It is what removing a connection does on the phone, and it is
// idempotent because the phone retries it: a device that was offline when its owner disconnected
// keeps the revocation as work to do and repeats it until it succeeds.
func (s *Server) unbind(writer http.ResponseWriter, request *http.Request) {
	installation, secret, ok := s.identified(writer, request)
	if !ok {
		return
	}
	binding := request.PathValue("binding")
	revoked, err := s.options.Store.Unbind(
		request.Context(), installation, credential.Hash(secret), binding, s.options.Now())
	switch {
	case errors.Is(err, storage.ErrNoInstallation):
		s.absent(writer)
	case err != nil:
		s.failed(writer, "revoke binding", err)
	default:
		if revoked {
			s.options.Log.Info("relay binding revoked", "binding", binding)
		}
		writer.WriteHeader(http.StatusNoContent)
	}
}

// --- the server's half --------------------------------------------------------

// notify is the one call an external server makes, and the one place all three authorities meet.
//
// In order: the credential says which server this is; the handle says which binding it may use,
// and the store resolves it *for that server* so a handle from somebody else's binding simply does
// not match; the quotas say whether this one goes now; and the invalidation the gateway builds
// itself goes to the target the binding resolved to.
//
// The caller is never told which of those refused it and never learns anything about the device.
func (s *Server) notify(writer http.ResponseWriter, request *http.Request) {
	presented := bearer(request)
	if !credential.Valid(presented) {
		refuse(writer, http.StatusUnauthorized, unauthenticated)
		return
	}
	serverID, err := s.options.Store.RelayServerFor(request.Context(), credential.Hash(presented))
	if err != nil {
		s.failed(writer, "resolve relay credential", err)
		return
	}
	if serverID == "" {
		// An unknown credential, a revoked one, a publishing credential presented here, and a
		// server whose relay the operator switched off are one answer. A caller learns that it may
		// not send, never whether the thing it presented used to work.
		refuse(writer, http.StatusUnauthorized, unauthenticated)
		return
	}
	var asked notifyRequest
	if !read(writer, request, &asked) {
		return
	}
	switch {
	case asked.Version != Version:
		refuse(writer, http.StatusBadRequest, wrongVersion)
		return
	case asked.Hint != Created && asked.Hint != Updated:
		refuse(writer, http.StatusBadRequest, malformed)
		return
	case !credential.Valid(asked.Handle):
		// The shape is refused before the lookup, which costs a caller nothing it did not already
		// know: a string that is not a handle's shape was never one.
		refuse(writer, http.StatusForbidden, unauthorized)
		return
	}
	if !s.options.Servers.Allow(serverID) || !s.options.Global.Allow("") {
		refuse(writer, http.StatusTooManyRequests, tooMany)
		return
	}
	now := s.options.Now()
	target, err := s.options.Store.TargetFor(
		request.Context(), serverID, credential.Hash(asked.Handle), now)
	switch {
	case errors.Is(err, storage.ErrNoBinding):
		s.options.Log.Info("relay send refused", "server", serverID, "reason", "unauthorized")
		refuse(writer, http.StatusForbidden, unauthorized)
		return
	case err != nil:
		s.failed(writer, "resolve handle", err)
		return
	}
	// The per-binding bucket is the coalescing rather than a second refusal. A burst of updates
	// about the same connection is one thing for the phone to do — read that server — so the
	// wake-up already in flight carries the same news as this one, and saying so is an answer
	// rather than an error the caller has to handle.
	if !s.options.Bindings.Allow(target.BindingID) {
		answer(writer, http.StatusOK, notifyResponse{Version: Version, Status: coalesced})
		return
	}
	if s.options.Send == nil {
		refuse(writer, http.StatusServiceUnavailable, unavailable)
		return
	}
	outcome, err := s.options.Send.Send(request.Context(), target.Target, asked.Hint == Created)
	if err != nil {
		// Classified by the sender, never quoted: a message from Google can name a project.
		s.options.Log.Warn("relay send failed", "server", serverID, "binding", target.BindingID,
			"reason", err)
	}
	switch outcome {
	case relay.Delivered:
		if err := s.options.Store.Sent(request.Context(), target.BindingID, now); err != nil {
			// The counter is for the operator's page. Failing the caller because a statistic could
			// not be written would make a best-effort hint less reliable than the thing it hints
			// about.
			s.options.Log.Warn("relay send counter not recorded", "binding", target.BindingID)
		}
		answer(writer, http.StatusAccepted, notifyResponse{Version: Version, Status: accepted})
	case relay.TargetGone:
		// Compare-and-clear: only if it is still the target that failed. A phone that rotated its
		// registration while this send was in flight has already registered the new one, and
		// clearing unconditionally would unregister a device that had just registered.
		cleared, clearErr := s.options.Store.TargetRejected(
			request.Context(), target.InstallationID, target.Target)
		if clearErr != nil {
			s.failed(writer, "clear rejected target", clearErr)
			return
		}
		s.options.Log.Info("relay target rejected", "server", serverID,
			"binding", target.BindingID, "cleared", cleared)
		// The binding is still an authorization; what is gone is somewhere to send. The phone
		// re-registers on its own, so this is retryable rather than final.
		refuse(writer, http.StatusServiceUnavailable, unavailable)
	case relay.Refused:
		refuse(writer, http.StatusServiceUnavailable, unavailable)
	default:
		refuse(writer, http.StatusServiceUnavailable, unavailable)
	}
}

// --- plumbing -----------------------------------------------------------------

// identified is the installation a phone-facing call is about, and the secret it presented.
//
// It checks the shape and nothing else: whether the secret is the right one is decided by the
// store, inside the transaction that acts on it, because a check and the act it guards on opposite
// sides of a wait are not one check.
func (s *Server) identified(writer http.ResponseWriter, request *http.Request) (string, string, bool) {
	installation := request.PathValue("installation")
	secret := bearer(request)
	if installation == "" || !credential.Valid(secret) {
		refuse(writer, http.StatusUnauthorized, unauthenticated)
		return "", "", false
	}
	return installation, secret, true
}

// absent is the one answer for an installation that does not exist and one whose secret did not
// match. A caller may learn that it is not the owner of this installation, never whether the
// installation is there.
func (s *Server) absent(writer http.ResponseWriter) {
	refuse(writer, http.StatusNotFound, noInstallation)
}

// failed is a store that could not answer. The reason goes to the log, where an operator reads it,
// and never into the answer: a storage error can name a file, a column or an identity.
func (s *Server) failed(writer http.ResponseWriter, doing string, err error) {
	s.options.Log.Error("relay action failed", "doing", doing, "error", err)
	refuse(writer, http.StatusInternalServerError, failed)
}

// newID mints an opaque identity: sixteen bytes of randomness, written as hex.
//
// It is not derived from anything — not from a hash, not from a target, not from a time — so it
// says nothing about what it names, and two of them cannot be compared for anything but equality.
// An installation ID and a binding ID are both this, because both are handles an owner or an
// operator may see and neither is a secret.
func newID() string {
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		panic("pushrelay: no randomness available: " + err.Error())
	}
	return hex.EncodeToString(raw)
}
