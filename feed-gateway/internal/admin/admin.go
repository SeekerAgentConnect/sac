// Package admin is the gateway operator's password-protected browser administration (SEE-141).
//
// It is the same publisher administration cmd/feed-gatewayctl performs, through the same
// storage.PublisherAdminStore and the same SQLite writer, reached over an authenticated route
// instead of a shell on the host that holds the database. The CLI is not replaced: it is the
// recovery path for when a browser cannot reach the deployment, and the two see each other's work
// because there is one authority and one set of semantics behind both.
//
// # What this deliberately is not
//
// Not an account system, a signup flow, a role model or an identity provider. There is one
// operator, configured by a password hash in the deployment's own secrets, and when that hash is
// absent nothing here is built — no listener, no route, and no unconfigured setup page waiting for
// whoever finds it first.
//
// Not a publisher's surface either. A publishing credential is never an administrator credential
// and an administrator session is never a publishing credential: separate secrets, separate code
// paths, separate listeners, and a boundary test that holds them apart.
//
// # Two capabilities, held apart the same way
//
// Since SEE-144 a registered server may publish a public feed, relay private push wake-ups, both,
// or neither while the operator sets it up. They are enabled independently and issued as separate
// credentials, and the separation is in the store's queries rather than in a check here: a
// publishing credential resolves to nothing in the relay and a relay credential resolves to
// nothing in the publisher API, whatever this page does. What this page adds is the operator's
// decision and a page that never makes the two look like one thing.
//
// # What it reaches
//
// The store, and nothing else. This package opens no connection, fetches no publisher's host and
// asks no other service anything — the gateway is called, and calls only its broker and its push
// relay (internal/stream, internal/relay). Everything an operator sees here came out of the same
// database the feed is served from.
package admin

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"slices"
	"strings"
	"time"
	"unicode"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/credential"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// MaxLabelBytes is how long an operator's note about a publisher may be. It is the same bound the
// protocol puts on a display name, because it is the same kind of thing: a short human label.
const MaxLabelBytes = 64

// MostFormBytes is the largest form this surface reads. Every one of them is a handful of short
// fields, so anything approaching this is not one.
const MostFormBytes = 16 * 1024

// SessionCookie is the name of the cookie a login sets. It is scoped to the admin route's own path,
// so it is never sent to a feed read or a publication.
const SessionCookie = "gateway_admin_session"

// Store is what administration needs of storage: the publisher administration boundary, plus the
// two authoritative reads that say whether a publisher has actually published anything.
//
// It is the contract rather than the SQLite implementation, like every other consumer in this
// service. Nothing here writes a document, reads a subscriber — there are none stored — or touches
// the outbox.
type Store interface {
	storage.PublisherAdminStore
	Manifest(context.Context, string) (*storage.StoredManifest, error)
	Sequence(context.Context, string) (uint64, error)
	// RelayStatus is the aggregate a relay server's page shows. It is counts and instants by
	// construction: there is no query behind it that could return a device target, a push handle
	// or an installation identity, so this surface cannot show one by accident (SEE-144).
	RelayStatus(context.Context, string) (storage.RelayStatus, error)
}

// Limiter is the token bucket a login is counted against. It is an interface so this package takes
// the gateway's own limiter without importing it, which would be a cycle: the gateway builds this
// surface, not the other way round.
type Limiter interface{ Allow(key string) bool }

// Options is everything the admin surface is built from.
type Options struct {
	// Path is the canonical route prefix, with no trailing slash (config.AdminPath).
	Path string
	// Password is the one operator's parsed password hash. Without it there is nothing to build.
	Password *credential.Password
	// SessionLifetime is how long a login lasts, absolutely.
	SessionLifetime time.Duration
	// Secure says whether the session cookie is marked Secure. It follows the public origin's
	// scheme: a deployment that is served over HTTPS must never hand this cookie to plain HTTP,
	// and a loopback development gateway over HTTP could not keep a cookie that demanded it.
	Secure bool
	// PublicURL is the gateway's canonical origin, which is what a manifest names and what a
	// developer configures. PublisherURL is where that developer's backend actually sends a
	// publication.
	PublicURL    string
	PublisherURL string
	Store        Store
	// Logins counts login attempts per caller, and Caller says who a caller is under the
	// deployment's trusted-proxy policy.
	Logins Limiter
	Caller func(*http.Request) string
	Log    *slog.Logger
	Now    func() time.Time
}

// Server is the admin surface as one http.Handler.
type Server struct {
	http.Handler

	options  Options
	sessions *sessions
	pages    *pages
}

// New builds the surface, or says why it cannot be built. A caller that gets an error must not
// serve anything: a half-built administrative surface is worse than none.
func New(options Options) (*Server, error) {
	switch {
	case options.Password == nil:
		return nil, errors.New("admin: no operator password is configured")
	case options.Store == nil:
		return nil, errors.New("admin: no store")
	case options.Log == nil || options.Now == nil || options.Caller == nil || options.Logins == nil:
		return nil, errors.New("admin: incomplete options")
	}
	path, err := canonicalPath(options.Path)
	if err != nil {
		return nil, err
	}
	options.Path = path
	rendered, err := newPages(path)
	if err != nil {
		return nil, err
	}
	server := &Server{
		options:  options,
		sessions: newSessions(options.SessionLifetime, options.Now),
		pages:    rendered,
	}
	server.Handler = headers(server.routes())
	return server, nil
}

// Sessions is how many logins are live, for the tests that prove logout and expiry remove them.
func (s *Server) Sessions() int { return s.sessions.live() }

func (s *Server) routes() http.Handler {
	at := s.options.Path
	mux := http.NewServeMux()
	// The prefix itself, without its slash, so a typed address reaches the page rather than a 404.
	mux.HandleFunc("GET "+at, func(writer http.ResponseWriter, request *http.Request) {
		http.Redirect(writer, request, at+"/", http.StatusSeeOther)
	})
	mux.HandleFunc("GET "+at+"/{$}", s.guarded(s.showServers))
	mux.HandleFunc("GET "+at+"/login", s.showLogin)
	mux.HandleFunc("POST "+at+"/login", s.logIn)
	mux.HandleFunc("POST "+at+"/logout", s.logOut)
	mux.HandleFunc("GET "+at+"/reveal", s.guarded(s.showReveal))
	mux.HandleFunc("POST "+at+"/servers", s.guarded(s.registerServer))
	mux.HandleFunc("GET "+at+"/servers/{server}", s.guarded(s.showServer))
	mux.HandleFunc("POST "+at+"/servers/{server}/capabilities", s.guarded(s.setCapabilities))
	mux.HandleFunc("POST "+at+"/servers/{server}/access", s.guarded(s.setAccess))
	mux.HandleFunc("POST "+at+"/servers/{server}/rotate", s.guarded(s.rotateCredential))
	mux.HandleFunc("POST "+at+"/servers/{server}/revoke", s.guarded(s.revokeCredential))
	mux.HandleFunc("POST "+at+"/servers/{server}/forget", s.guarded(s.forgetServer))
	mux.HandleFunc("GET "+at+"/assets/{file}", s.serveAsset)
	return mux
}

// headers are on every answer this surface gives, including its refusals.
//
// The content policy is the strict one: this page loads its own stylesheet and its own script and
// nothing else — no CDN, no font service, no analytics, nothing inline — so a content injection has
// nowhere to send anything and nothing to load. It may not be framed, it sends no referrer
// anywhere, and nothing it answers is stored by a cache or a browser: an administrative page holds
// a list of publishers and, once, a credential.
func headers(next http.Handler) http.Handler {
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		head := writer.Header()
		head.Set("Content-Security-Policy",
			"default-src 'none'; style-src 'self'; script-src 'self'; font-src 'self'; "+
				"img-src 'self' data:; "+
				"form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
		head.Set("Referrer-Policy", "no-referrer")
		head.Set("X-Content-Type-Options", "nosniff")
		head.Set("X-Frame-Options", "DENY")
		head.Set("Cache-Control", "no-store, max-age=0")
		head.Set("Cross-Origin-Opener-Policy", "same-origin")
		head.Set("Cross-Origin-Resource-Policy", "same-origin")
		next.ServeHTTP(writer, request)
	})
}

// guarded is authentication, CSRF and the browser's own cross-site signal, in one place, for every
// route that is not the login form or the stylesheet.
//
// Doing it as a wrapper rather than a check inside each handler is what makes a handler added later
// protected by existing rather than by remembering — the same reason the publisher API resolves its
// credential in an interceptor.
func (s *Server) guarded(next func(http.ResponseWriter, *http.Request, *visit)) http.HandlerFunc {
	return func(writer http.ResponseWriter, request *http.Request) {
		token := cookieValue(request)
		held := s.sessions.lookup(token)
		if held == nil {
			s.clearCookie(writer)
			if request.Method == http.MethodGet {
				http.Redirect(writer, request, s.options.Path+"/login", http.StatusSeeOther)
				return
			}
			s.pages.render(writer, http.StatusUnauthorized, "message", s.message(
				"Your session has ended", "Log in again and repeat the action.", true, ""))
			return
		}
		if request.Method == http.MethodPost {
			if !sameSite(request) {
				s.refuse(writer, request, "that request did not come from this page")
				return
			}
			request.Body = http.MaxBytesReader(writer, request.Body, MostFormBytes)
			if err := request.ParseForm(); err != nil {
				s.refuse(writer, request, "that form could not be read")
				return
			}
			if !sameToken(request.PostFormValue("csrf"), held.csrf) {
				s.refuse(writer, request, "that form was out of date")
				return
			}
		}
		next(writer, request, &visit{token: token, session: held})
	}
}

// visit is one authenticated request's session.
type visit struct {
	token   string
	session *session
}

func (s *Server) refuse(writer http.ResponseWriter, request *http.Request, why string) {
	s.options.Log.Warn("admin request refused", "path", request.URL.Path, "reason", why)
	s.pages.render(writer, http.StatusForbidden, "message", s.message(
		"That action was not accepted", "The gateway refused it because "+why+
			". Go back, reload the page and try again.", true, ""))
}

// sameSite is the browser's own statement about where a request came from. Chrome, Firefox and
// Safari all send Sec-Fetch-Site; a cross-site form post says so, and this is what stops one before
// the session token is even considered.
//
// An absent header is allowed, because the thing that sends none is not a browser — curl, a test,
// an operator's script — and cross-site request forgery is an attack that needs a browser with a
// session in it. The session's own CSRF token is checked either way.
func sameSite(request *http.Request) bool {
	switch request.Header.Get("Sec-Fetch-Site") {
	case "", "same-origin", "none":
		return true
	default:
		return false
	}
}

func cookieValue(request *http.Request) string {
	cookie, err := request.Cookie(SessionCookie)
	if err != nil {
		return ""
	}
	return cookie.Value
}

func (s *Server) setCookie(writer http.ResponseWriter, token string) {
	http.SetCookie(writer, &http.Cookie{
		Name:  SessionCookie,
		Value: token,
		// Scoped to the administrative route, so the feed API never sees it.
		Path:     s.options.Path,
		HttpOnly: true,
		Secure:   s.options.Secure,
		SameSite: http.SameSiteStrictMode,
		// A session cookie with no Max-Age: the browser forgets it when it closes, and the process
		// forgets it when it expires. Both have to agree for a session to still exist.
	})
}

func (s *Server) clearCookie(writer http.ResponseWriter) {
	http.SetCookie(writer, &http.Cookie{
		Name:     SessionCookie,
		Value:    "",
		Path:     s.options.Path,
		HttpOnly: true,
		Secure:   s.options.Secure,
		SameSite: http.SameSiteStrictMode,
		MaxAge:   -1,
	})
}

// --- login ------------------------------------------------------------------

func (s *Server) showLogin(writer http.ResponseWriter, request *http.Request) {
	if s.sessions.lookup(cookieValue(request)) != nil {
		http.Redirect(writer, request, s.options.Path+"/", http.StatusSeeOther)
		return
	}
	s.pages.render(writer, http.StatusOK, "login", s.loginView(""))
}

// logIn is the one place a password is compared.
//
// Every way it can fail answers the same thing: the wrong password, an empty one, and a rate limit
// that has run out all say "that was not the right password" with no detail. A login that told the
// difference would be telling whoever is trying which half they got right.
func (s *Server) logIn(writer http.ResponseWriter, request *http.Request) {
	if !sameSite(request) {
		s.pages.render(writer, http.StatusForbidden, "login",
			s.loginView("That request did not come from this page."))
		return
	}
	if err := request.ParseForm(); err != nil {
		s.pages.render(writer, http.StatusBadRequest, "login",
			s.loginView("That form could not be read."))
		return
	}
	who := s.options.Caller(request)
	if !s.options.Logins.Allow(who) {
		s.options.Log.Warn("admin login rate limited", "caller", who)
		s.pages.render(writer, http.StatusTooManyRequests, "login",
			s.loginView("Too many attempts. Wait a little and try again."))
		return
	}
	if !s.options.Password.Verify(request.PostFormValue("password")) {
		s.options.Log.Warn("admin login refused", "caller", who)
		s.pages.render(writer, http.StatusUnauthorized, "login",
			s.loginView("That was not the right password."))
		return
	}
	token := s.sessions.begin()
	if token == "" {
		s.options.Log.Warn("admin login refused: too many live sessions", "caller", who)
		s.pages.render(writer, http.StatusServiceUnavailable, "login",
			s.loginView("This gateway is holding as many sessions as it will. "+
				"Wait for one to expire, or restart the gateway."))
		return
	}
	s.options.Log.Info("admin logged in", "caller", who)
	s.setCookie(writer, token)
	http.Redirect(writer, request, s.options.Path+"/", http.StatusSeeOther)
}

func (s *Server) logOut(writer http.ResponseWriter, request *http.Request) {
	// Logout is a real revocation rather than a request that the browser forget something: the
	// session is removed here, so a token that was copied elsewhere stops working too.
	if !sameSite(request) {
		s.refuse(writer, request, "that request did not come from this page")
		return
	}
	s.sessions.end(cookieValue(request))
	s.clearCookie(writer)
	s.options.Log.Info("admin logged out")
	http.Redirect(writer, request, s.options.Path+"/login", http.StatusSeeOther)
}

// --- publishers -------------------------------------------------------------

func (s *Server) showServers(writer http.ResponseWriter, request *http.Request, at *visit) {
	view, err := s.serversView(request.Context(), at)
	if err != nil {
		s.failed(writer, "list publishers", err)
		return
	}
	s.pages.render(writer, http.StatusOK, "servers", view)
}

func (s *Server) showServer(writer http.ResponseWriter, request *http.Request, at *visit) {
	view, err := s.serverView(request.Context(), at, request.PathValue("server"))
	switch {
	case errors.Is(err, storage.ErrNoPublisher):
		s.pages.render(writer, http.StatusNotFound, "message", s.message(
			"No such publisher", "This gateway holds no registration with that server ID.",
			true, ""))
	case err != nil:
		s.failed(writer, "read publisher", err)
	default:
		s.pages.render(writer, http.StatusOK, "server", view)
	}
}

// registerServer is "Add server": one transaction that either creates the publisher with its first
// credential or creates nothing at all.
//
// A server ID that is already registered is refused here rather than merged into, so a second
// registration cannot quietly hand out the ability to publish as an existing publisher. Rotation is
// a different button on a different page and says what it does.
func (s *Server) registerServer(writer http.ResponseWriter, request *http.Request, at *visit) {
	form := registrationForm(request)
	registration, generated, problem := form.parse()
	if problem != "" {
		view, err := s.serversView(request.Context(), at)
		if err != nil {
			s.failed(writer, "list publishers", err)
			return
		}
		view.Form, view.Problem = form, problem
		s.pages.render(writer, http.StatusBadRequest, "servers", view)
		return
	}

	secret, hash := credential.New()
	now := s.options.Now()
	// The first credential is issued for whichever capability the registration has. A server that
	// is enabled for both gets a publishing credential here and a relay credential from its own
	// page, because one credential that did both would be the thing SEE-144 exists to prevent.
	first := storage.Publishing
	if !registration.Publishing {
		first = storage.Relaying
	}
	credentialID, err := s.options.Store.Register(
		request.Context(), registration, first, hash, now)
	switch {
	case errors.Is(err, storage.ErrPublisherExists):
		s.record("register", registration.ServerID, "refused: already registered")
		view, listErr := s.serversView(request.Context(), at)
		if listErr != nil {
			s.failed(writer, "list publishers", listErr)
			return
		}
		view.Form = form
		view.Problem = "That server ID is already registered. Open it below to rotate its " +
			"credential; registering it again would not be the same publisher."
		s.pages.render(writer, http.StatusConflict, "servers", view)
		return
	case err != nil:
		s.record("register", registration.ServerID, "failed")
		s.failed(writer, "register publisher", err)
		return
	}
	s.record("register", registration.ServerID, "registered")
	s.reveal(writer, request, at, &Reveal{
		Action:       "registered",
		Registration: registration,
		Generated:    generated,
		Capability:   first,
		CredentialID: credentialID,
		Secret:       secret,
		Connection:   s.connection(registration),
	})
}

// rotateCredential is the existing additive rotation: the publisher keeps publishing with what it
// has while the new credential is deployed, and the old one is revoked afterwards. Both work in
// between, which is the whole point of rotation being two steps.
func (s *Server) rotateCredential(writer http.ResponseWriter, request *http.Request, at *visit) {
	serverID := request.PathValue("server")
	if !rules.IsID(serverID) {
		s.notFound(writer)
		return
	}
	// Which capability this credential is for is a field on the form rather than a guess from the
	// server's own state: a server enabled for both would otherwise get whichever the code
	// happened to prefer, and an operator would hand a developer a credential that silently does
	// the wrong one of two jobs.
	capability := storage.Capability(strings.TrimSpace(request.PostFormValue("capability")))
	if !capability.Valid() {
		s.back(writer, request, at, serverID,
			"Say what this credential is for: publishing to the feed, or relaying push.", true)
		return
	}
	held, err := s.options.Store.Publisher(request.Context(), serverID)
	if err != nil {
		s.failed(writer, "read publisher", err)
		return
	}
	if held == nil {
		s.notFound(writer)
		return
	}
	if (capability == storage.Publishing && !held.Publishing) ||
		(capability == storage.Relaying && !held.Relaying) {
		// A credential for a capability this server does not have would not work, and issuing one
		// anyway is how an operator comes to believe they have set something up that they have
		// not. Enabling the capability is one checkbox above this form.
		s.record("rotate", serverID, "refused: capability not enabled")
		s.back(writer, request, at, serverID, "Enable that capability for this server first — "+
			"a credential it does not have would be refused on every call.", true)
		return
	}
	label, problem := label(request.PostFormValue("label"), string(capability)+" credential, "+
		s.options.Now().UTC().Format(time.RFC3339))
	if problem != "" {
		s.back(writer, request, at, serverID, problem, true)
		return
	}
	secret, hash := credential.New()
	credentialID, err := s.options.Store.AddCredential(
		request.Context(), serverID, label, capability, hash, s.options.Now())
	switch {
	case errors.Is(err, storage.ErrNoPublisher):
		s.record("rotate", serverID, "refused: no such publisher")
		s.notFound(writer)
		return
	case err != nil:
		s.record("rotate", serverID, "failed")
		s.failed(writer, "add credential", err)
		return
	}
	s.record("rotate", serverID, "added "+string(capability)+" credential "+credentialID)

	registration := registrationOf(*held)
	s.reveal(writer, request, at, &Reveal{
		Action:       "rotated",
		Registration: registration,
		Capability:   capability,
		CredentialID: credentialID,
		Secret:       secret,
		Connection:   s.connection(registration),
	})
}

// setCapabilities is the operator's switch: what this server is allowed to do, effective on the
// next call and without a restart.
//
// It is not revocation and says so on the page. Disabling a capability stops every credential of
// that kind being accepted while it is off, and enabling it again finds the same credentials
// working; revoking one ends it for good. Two operations, because an operator turning something
// off for an afternoon and an operator ending a credential that leaked are doing different things.
//
// Turning everything off is allowed. It is the state a registration is in while an operator is
// still deciding, and it is a server that can do nothing rather than a server that is gone.
func (s *Server) setCapabilities(writer http.ResponseWriter, request *http.Request, at *visit) {
	serverID := request.PathValue("server")
	if !rules.IsID(serverID) {
		s.notFound(writer)
		return
	}
	publishing := request.PostFormValue("publishing") != ""
	relaying := request.PostFormValue("relaying") != ""
	err := s.options.Store.SetCapabilities(request.Context(), serverID, publishing, relaying)
	switch {
	case errors.Is(err, storage.ErrNoPublisher):
		s.record("capabilities", serverID, "refused: no such publisher")
		s.notFound(writer)
	case err != nil:
		s.record("capabilities", serverID, "failed")
		s.failed(writer, "set capabilities", err)
	default:
		s.record("capabilities", serverID,
			fmt.Sprintf("publishing=%t relay=%t", publishing, relaying))
		s.back(writer, request, at, serverID, capabilityNotice(publishing, relaying), false)
	}
}

// capabilityNotice says what just changed in the terms the operator will care about next: which
// credentials are now honoured, and what it did not do.
func capabilityNotice(publishing, relaying bool) string {
	switch {
	case publishing && relaying:
		return "This server may publish to its feed and relay push. Its credentials of each " +
			"kind are honoured from the next call."
	case publishing:
		return "This server may publish to its feed. Its relay credentials are refused from " +
			"the next call; they are not revoked, so enabling relay again makes them work."
	case relaying:
		return "This server may relay push. Its publishing credentials are refused from the " +
			"next call; they are not revoked, so enabling publishing again makes them work."
	default:
		return "This server may do nothing. Every credential it holds is refused from the next " +
			"call; none is revoked, so enabling a capability again makes them work. Its feed is " +
			"still served and its bindings still exist."
	}
}

// setAccess is feed-gatewayctl access on this page (SEE-162): the operator choosing who may read a
// feed, and where its subscribers prove who they are.
//
// It is not a switch like the capabilities above. Changing the policy or the origin moves the
// channel's access epoch, so every stream name issued under the old policy is retired and every
// listener attached under it goes silent — which is why a change needs the server ID typed back,
// as forgetting does. Asking for what is already held changes nothing and needs no confirmation.
func (s *Server) setAccess(writer http.ResponseWriter, request *http.Request, at *visit) {
	serverID := request.PathValue("server")
	if !rules.IsID(serverID) {
		s.notFound(writer)
		return
	}
	access, problem := accessOf(strings.TrimSpace(request.PostFormValue("access")),
		strings.TrimSpace(request.PostFormValue("auth_origin")))
	if problem != "" {
		s.back(writer, request, at, serverID, problem, true)
		return
	}
	held, err := s.options.Store.Publisher(request.Context(), serverID)
	if err != nil {
		s.failed(writer, "read publisher", err)
		return
	}
	if held == nil {
		s.notFound(writer)
		return
	}
	if held.Access.Restricted() == access.Restricted() && held.Access.AuthOrigin == access.AuthOrigin {
		s.back(writer, request, at, serverID, "Nothing changed: this feed already is "+
			describeAccess(access)+".", false)
		return
	}
	if access.Restricted() && !held.Publishing {
		s.back(writer, request, at, serverID, "Only a feed can be restricted. Enable publishing "+
			"for this server first.", true)
		return
	}
	if strings.TrimSpace(request.PostFormValue("confirm")) != serverID {
		s.back(writer, request, at, serverID, "Type this publisher's server ID exactly to confirm "+
			"the change: it retires every stream name issued under the current policy.", true)
		return
	}
	err = s.options.Store.SetAccess(request.Context(), serverID, access)
	switch {
	case errors.Is(err, storage.ErrNoPublisher):
		s.record("access", serverID, "refused: no such publisher")
		s.notFound(writer)
	case err != nil:
		s.record("access", serverID, "failed")
		s.failed(writer, "set access", err)
	default:
		s.record("access", serverID, describeAccess(access))
		s.back(writer, request, at, serverID, accessNotice(access), false)
	}
}

// accessNotice says what just changed and what the publisher has to do about it, in the words
// feed-gatewayctl access prints.
func accessNotice(access storage.Access) string {
	said := "This feed is now " + describeAccess(access) + ". Every stream name issued under the " +
		"old policy is retired. The publisher should publish its manifest again so the policy it " +
		"states matches this one."
	if access.Restricted() {
		said += " Its PUBLISHER_AUTH_ORIGIN must be " + access.AuthOrigin + " character for " +
			"character, or it publishes nothing."
	}
	return said
}

func describeAccess(access storage.Access) string {
	if access.Restricted() {
		return "restricted, authenticated at " + access.AuthOrigin
	}
	return "public"
}

func registrationOf(publisher storage.Publisher) storage.Registration {
	return storage.Registration{
		ServerID:   publisher.ServerID,
		Label:      publisher.Label,
		Host:       publisher.Host,
		Publishing: publisher.Publishing,
		Relaying:   publisher.Relaying,
		Access:     publisher.Access,
	}
}

// revokeCredential ends one credential, or every credential a publisher holds. Enforcement is
// immediate: the publisher API resolves a credential per call, so the next publication with a
// revoked one is refused.
//
// Revocation is not removal. The manifest and the publications this publisher already made stay on
// the feed, because a publisher that can no longer publish is not a reason for what phones already
// read to disappear.
func (s *Server) revokeCredential(writer http.ResponseWriter, request *http.Request, at *visit) {
	serverID := request.PathValue("server")
	if !rules.IsID(serverID) {
		s.notFound(writer)
		return
	}
	now := s.options.Now()
	if request.PostFormValue("all") != "" {
		revoked, err := s.options.Store.RevokeAll(request.Context(), serverID, now)
		if err != nil {
			s.record("revoke-all", serverID, "failed")
			s.failed(writer, "revoke credentials", err)
			return
		}
		s.record("revoke-all", serverID, fmt.Sprintf("revoked %d", revoked))
		s.back(writer, request, at, serverID, fmt.Sprintf(
			"Revoked %d credential(s). This publisher can no longer publish. "+
				"Its manifest and publications are still served; use “Forget publisher” to "+
				"remove those.", revoked), false)
		return
	}
	id := strings.TrimSpace(request.PostFormValue("credential"))
	if id == "" {
		s.back(writer, request, at, serverID, "Name the credential to revoke.", true)
		return
	}
	// The credential has to be one of this publisher's. A credential ID is a prefix of a hash and
	// the store revokes whatever it uniquely names, so without this check a request assembled by
	// hand could end one publisher's credential from another's page — the operator may do that
	// anyway, from the right page, but the record of it would then name the wrong publisher.
	held, err := s.options.Store.Credentials(request.Context(), serverID)
	if err != nil {
		s.failed(writer, "read credentials", err)
		return
	}
	if !slices.ContainsFunc(held, func(one storage.Credential) bool { return one.ID == id }) {
		s.record("revoke", serverID, "refused: not this publisher's credential")
		s.back(writer, request, at, serverID,
			"That credential does not belong to this publisher.", true)
		return
	}
	revoked, err := s.options.Store.Revoke(request.Context(), id, now)
	if err != nil {
		s.record("revoke", serverID, "failed")
		s.back(writer, request, at, serverID, "That is not a credential ID.", true)
		return
	}
	if revoked == 0 {
		s.record("revoke", serverID, "no credential of that ID is in use")
		s.back(writer, request, at, serverID,
			"No credential of that ID is in use. It may already have been revoked.", true)
		return
	}
	s.record("revoke", serverID, "revoked credential "+id)
	s.back(writer, request, at, serverID, fmt.Sprintf(
		"Revoked credential %s. Publishing with it is refused from the next request.", id), false)
}

// forgetServer is the destructive one, and it is deliberately not a variant of revoke. It removes
// this publisher's registration, its credentials, its manifest and every publication the gateway
// holds for it. The confirmation is the server's own ID typed back, so it cannot be the button
// somebody meant to press next to it.
func (s *Server) forgetServer(writer http.ResponseWriter, request *http.Request, at *visit) {
	serverID := request.PathValue("server")
	if !rules.IsID(serverID) {
		s.notFound(writer)
		return
	}
	if strings.TrimSpace(request.PostFormValue("confirm")) != serverID {
		s.back(writer, request, at, serverID,
			"Type this publisher's server ID exactly to confirm removal.", true)
		return
	}
	if err := s.options.Store.Forget(request.Context(), serverID, rules.ChannelFor(serverID)); err != nil {
		if errors.Is(err, storage.ErrNoPublisher) {
			s.record("forget", serverID, "refused: no such publisher")
			s.notFound(writer)
			return
		}
		s.record("forget", serverID, "failed")
		s.failed(writer, "forget publisher", err)
		return
	}
	s.record("forget", serverID, "forgotten")
	s.flash(at, "Forgot "+serverID+" and everything it published. Phones that already read "+
		"its publications keep their own copies until their owners remove the feed — this "+
		"removed what the gateway held, not what a device decided.")
	http.Redirect(writer, request, s.options.Path+"/", http.StatusSeeOther)
}

// --- the one-time credential -------------------------------------------------

// reveal hands the raw credential to the session that created it and redirects. The secret never
// travels in a URL, a redirect target or a referrer, and it is held for exactly one GET — so a
// reload of the page it was shown on cannot mint a second credential, and a back button shows the
// ordinary page instead of the secret again.
func (s *Server) reveal(writer http.ResponseWriter, request *http.Request, at *visit, shown *Reveal) {
	s.sessions.hold(at.token, shown)
	http.Redirect(writer, request, s.options.Path+"/reveal", http.StatusSeeOther)
}

func (s *Server) showReveal(writer http.ResponseWriter, request *http.Request, at *visit) {
	common, err := s.commonWithServerCount(request.Context(), at)
	if err != nil {
		s.failed(writer, "list publishers", err)
		return
	}
	shown := s.sessions.take(at.token)
	if shown == nil {
		http.Redirect(writer, request, s.options.Path+"/", http.StatusSeeOther)
		return
	}
	shown.Common = common
	s.pages.render(writer, http.StatusOK, "reveal", shown)
}

// --- assets -------------------------------------------------------------------

func (s *Server) serveAsset(writer http.ResponseWriter, request *http.Request) {
	name := request.PathValue("file")
	asset, known := assetFor(name)
	if !known {
		s.notFound(writer)
		return
	}
	writer.Header().Set("Content-Type", asset.kind)
	// The stylesheet and the script ship inside the image and change only when it does, so they
	// are cacheable — but privately, because everything this surface answers belongs to one
	// operator.
	writer.Header().Set("Cache-Control", "private, max-age=600")
	_, _ = writer.Write(asset.body)
}

// --- plumbing -----------------------------------------------------------------

func (s *Server) notFound(writer http.ResponseWriter) {
	s.pages.render(writer, http.StatusNotFound, "message", s.message(
		"Not found", "There is nothing at that address.", true, ""))
}

// failed is what an operator sees when the store could not answer. The reason is written to the
// log, where an operator can read it, and never to the page: a storage error can name a file, a
// column or a publisher, and a rendered error is not the place to publish any of those.
func (s *Server) failed(writer http.ResponseWriter, doing string, err error) {
	s.options.Log.Error("admin action failed", "doing", doing, "error", err)
	s.pages.render(writer, http.StatusInternalServerError, "message", s.message(
		"The gateway could not complete that", "Nothing was changed. "+
			"The reason is in the gateway's log.", true, ""))
}

// back re-renders one publisher's page with something to say about what just happened.
func (s *Server) back(writer http.ResponseWriter, request *http.Request, at *visit,
	serverID, said string, wrong bool) {
	view, err := s.serverView(request.Context(), at, serverID)
	if err != nil {
		s.failed(writer, "read publisher", err)
		return
	}
	if wrong {
		view.Problem = said
		s.pages.render(writer, http.StatusBadRequest, "server", view)
		return
	}
	view.Notice = said
	s.pages.render(writer, http.StatusOK, "server", view)
}

func (s *Server) flash(at *visit, said string) { at.session.notice = said }

// record is the administrative event log: what was done, to which publisher, and how it ended.
// There is no password, no session, no credential and no form body in it, ever — an audit trail
// that carried a secret would be a second place the secret lives.
func (s *Server) record(action, target, outcome string) {
	s.options.Log.Info("admin action", "action", action, "target", target, "outcome", outcome)
}

// newServerID mints a lowercase v4 UUID, for the operator who is registering a publisher that does
// not have an ID yet. The publisher must then use exactly this one: it is the identity every
// manifest and publication of its own has to name, and the page says so where the ID is shown.
func newServerID() string {
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		panic("admin: no randomness available: " + err.Error())
	}
	raw[6] = (raw[6] & 0x0f) | 0x40
	raw[8] = (raw[8] & 0x3f) | 0x80
	written := hex.EncodeToString(raw)
	return fmt.Sprintf("%s-%s-%s-%s-%s", written[0:8], written[8:12], written[12:16],
		written[16:20], written[20:32])
}

// label is an operator's note, bounded and printable, or the fallback when none was given. It is
// never served to anyone: it is what the operator calls this publisher in their own list.
func label(raw, fallback string) (string, string) {
	value := strings.TrimSpace(raw)
	if value == "" {
		return fallback, ""
	}
	if len(value) > MaxLabelBytes {
		return "", fmt.Sprintf("A label may be at most %d characters.", MaxLabelBytes)
	}
	for _, letter := range value {
		if !unicode.IsPrint(letter) {
			return "", "A label may not contain control characters."
		}
	}
	return value, ""
}

func canonicalPath(raw string) (string, error) {
	value := strings.TrimRight(strings.TrimSpace(raw), "/")
	if !strings.HasPrefix(value, "/") || value == "" {
		return "", fmt.Errorf("admin: %q is not a route prefix", raw)
	}
	return value, nil
}
