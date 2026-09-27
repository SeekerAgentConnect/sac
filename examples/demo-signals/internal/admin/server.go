// Package admin is the password-gated CopyTrading trader UI (SEE-126).
//
// It is a client of the template's existing /v1 API and nothing else: the publisher token stays in
// this process, judges log in with named bcrypt passwords, and the public origin serves HTML on
// /trader rather than /v1. Forms carry every action; the one script, served from this origin, only
// copies a reference and asks before a cancel or a revocation, so the page works without it.
package admin

import (
	"html/template"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/limit"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

const (
	MostBodyBytes     = 64 << 10
	defaultLoginLimit = 8
	defaultCreateCap  = 20
	defaultMutateCap  = 60
	loginWindow       = 15 * time.Minute
	actionWindow      = time.Hour
)

// Server is the HTML UI.
type Server struct {
	path    string
	secret  string
	pass    *File
	api     *API
	log     *slog.Logger
	now     func() time.Time
	loginIP *limit.Limiter
	loginN  *limit.Limiter
	creates *limit.Limiter
	mutates *limit.Limiter
}

// Plan is what a [Server] needs.
type Plan struct {
	Config        *Config
	Passwords     *File
	Log           *slog.Logger
	Now           func() time.Time
	HTTP          *http.Client
	LoginPerIP    int
	LoginPerName  int
	CreatePerUser int
	MutatePerUser int
}

// New builds the UI.
func New(plan Plan) *Server {
	now := plan.Now
	if now == nil {
		now = time.Now
	}
	loginIP := plan.LoginPerIP
	if loginIP == 0 {
		loginIP = defaultLoginLimit
	}
	loginN := plan.LoginPerName
	if loginN == 0 {
		loginN = defaultLoginLimit
	}
	creates := plan.CreatePerUser
	if creates == 0 {
		creates = defaultCreateCap
	}
	mutates := plan.MutatePerUser
	if mutates == 0 {
		mutates = defaultMutateCap
	}
	return &Server{
		path:    plan.Config.PublicPath,
		secret:  plan.Config.SessionSecret,
		pass:    plan.Passwords,
		api:     newAPI(plan.Config.APIURL, plan.Config.APIToken, plan.HTTP),
		log:     plan.Log,
		now:     now,
		loginIP: limit.New(loginIP, loginWindow, now),
		loginN:  limit.New(loginN, loginWindow, now),
		creates: limit.New(creates, actionWindow, now),
		mutates: limit.New(mutates, actionWindow, now),
	}
}

// Handler is the routed UI. Login is public; everything else needs a live named session.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.health)
	mux.HandleFunc("GET "+s.path, s.home)
	mux.HandleFunc("GET "+s.path+"/", s.home)
	mux.HandleFunc("GET "+s.path+"/login", s.showLogin)
	mux.HandleFunc("POST "+s.path+"/login", s.login)
	mux.HandleFunc("POST "+s.path+"/logout", s.logout)
	mux.HandleFunc("POST "+s.path+"/create", s.create)
	mux.HandleFunc("POST "+s.path+"/signals/{id}/cancel", s.cancel)
	mux.HandleFunc("POST "+s.path+"/signals/{id}/retry", s.retry)
	// Devices / feed access (SEE-156).
	mux.HandleFunc("GET "+s.path+"/devices", s.showDevices)
	mux.HandleFunc("POST "+s.path+"/devices/{id}/{action}", s.actOnDevice)
	mux.HandleFunc("POST "+s.path+"/wallets/{wallet}/revoke", s.revokeWallet)
	mux.HandleFunc("GET "+s.path+"/assets/{file}", s.asset)
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		request.Body = http.MaxBytesReader(writer, request.Body, MostBodyBytes)
		s.headers(writer)
		mux.ServeHTTP(writer, request)
	})
}

func (s *Server) health(writer http.ResponseWriter, _ *http.Request) {
	writer.Header().Set("Content-Type", "text/plain; charset=utf-8")
	_, _ = writer.Write([]byte("ok\n"))
}

// asset serves the embedded stylesheet, script and fonts. They are public, like the login page
// that needs them, and carry nothing of any trader's.
func (s *Server) asset(writer http.ResponseWriter, request *http.Request) {
	body, kind, ok := assetFor(request.PathValue("file"))
	if !ok {
		http.NotFound(writer, request)
		return
	}
	writer.Header().Set("Content-Type", kind)
	// They ship inside the image and change only when it does.
	writer.Header().Set("Cache-Control", "private, max-age=600")
	_, _ = writer.Write(body)
}

func (s *Server) showLogin(writer http.ResponseWriter, request *http.Request) {
	if _, ok := s.identity(request); ok {
		http.Redirect(writer, request, s.path, http.StatusSeeOther)
		return
	}
	s.page(writer, http.StatusOK, loginPage, loginView{Path: s.path, Message: request.URL.Query().Get("error")})
}

func (s *Server) home(writer http.ResponseWriter, request *http.Request) {
	name, ok := s.identity(request)
	if !ok {
		http.Redirect(writer, request, s.path+"/login", http.StatusSeeOther)
		return
	}
	s.renderHome(writer, request, name, request.URL.Query().Get("ok"), request.URL.Query().Get("error"))
}

func (s *Server) login(writer http.ResponseWriter, request *http.Request) {
	if !s.sameOrigin(request) {
		s.refuse(writer, http.StatusForbidden, "that request did not come from this page")
		return
	}
	if err := request.ParseForm(); err != nil {
		s.page(writer, http.StatusBadRequest, loginPage, loginView{Path: s.path, Message: "the form could not be read"})
		return
	}
	name := strings.TrimSpace(request.FormValue("name"))
	password := request.FormValue("password")
	ip := clientIP(request)
	ipKey := "ip:" + ip
	nameKey := "name:" + strings.ToLower(name)
	if !s.loginIP.Allow(ipKey) {
		s.log.Warn("login rate limited", "from", ip)
		s.page(writer, http.StatusTooManyRequests, loginPage, loginView{
			Path: s.path, Message: "too many attempts; try later",
		})
		return
	}
	if !s.loginN.Allow(nameKey) {
		s.loginIP.Undo(ipKey)
		s.log.Warn("login rate limited", "from", ip)
		s.page(writer, http.StatusTooManyRequests, loginPage, loginView{
			Path: s.path, Message: "too many attempts; try later",
		})
		return
	}
	if !ValidName(name) || !s.pass.Check(name, password) {
		s.log.Warn("login failed", "from", ip)
		s.page(writer, http.StatusUnauthorized, loginPage, loginView{
			Path: s.path, Message: "name or password is wrong",
		})
		return
	}
	s.loginIP.Undo(ipKey)
	s.loginN.Undo(nameKey)
	cookie, err := signSession(s.secret, name, s.now())
	if err != nil {
		s.page(writer, http.StatusInternalServerError, loginPage, loginView{
			Path: s.path, Message: "could not start a session",
		})
		return
	}
	s.setCookie(writer, cookie, int(sessionTTL.Seconds()))
	http.Redirect(writer, request, s.path, http.StatusSeeOther)
}

func (s *Server) logout(writer http.ResponseWriter, request *http.Request) {
	if !s.sameOrigin(request) {
		s.refuse(writer, http.StatusForbidden, "that request did not come from this page")
		return
	}
	s.setCookie(writer, "", -1)
	http.Redirect(writer, request, s.path+"/login", http.StatusSeeOther)
}

func (s *Server) create(writer http.ResponseWriter, request *http.Request) {
	name, ok := s.guard(writer, request)
	if !ok {
		return
	}
	if !s.creates.Allow("create:" + name) {
		s.redirectErr(writer, request, "too many new signals; try later")
		return
	}
	if err := request.ParseForm(); err != nil {
		s.redirectErr(writer, request, "the form could not be read")
		return
	}
	_, err := s.api.create(createInput{
		Pair:      request.FormValue("pair"),
		Slippage:  request.FormValue("slippage"),
		ExpiresIn: request.FormValue("expires"),
		Note:      request.FormValue("note"),
	})
	if err != nil {
		s.creates.Undo("create:" + name)
		s.redirectErr(writer, request, err.Error())
		return
	}
	s.redirectOK(writer, request, "published")
}

func (s *Server) cancel(writer http.ResponseWriter, request *http.Request) {
	s.change(writer, request, s.api.cancel, "cancelled")
}

func (s *Server) retry(writer http.ResponseWriter, request *http.Request) {
	s.change(writer, request, s.api.retry, "retrying publication")
}

func (s *Server) change(
	writer http.ResponseWriter,
	request *http.Request,
	call func(string) (Item, error),
	ok string,
) {
	name, authed := s.guard(writer, request)
	if !authed {
		return
	}
	if !s.mutates.Allow("mutate:" + name) {
		s.redirectErr(writer, request, "too many cancel or retry attempts; try later")
		return
	}
	id := request.PathValue("id")
	if !signals.IsID(id) {
		s.redirectErr(writer, request, "that is not a signal ID")
		return
	}
	if _, err := call(id); err != nil {
		s.redirectErr(writer, request, err.Error())
		return
	}
	s.redirectOK(writer, request, ok)
}

func (s *Server) guard(writer http.ResponseWriter, request *http.Request) (string, bool) {
	if !s.sameOrigin(request) {
		s.refuse(writer, http.StatusForbidden, "that request did not come from this page")
		return "", false
	}
	name, ok := s.identity(request)
	if !ok {
		http.Redirect(writer, request, s.path+"/login", http.StatusSeeOther)
		return "", false
	}
	return name, true
}

func (s *Server) identity(request *http.Request) (string, bool) {
	cookie, err := request.Cookie(cookieName)
	if err != nil {
		return "", false
	}
	name, ok := verifySession(s.secret, cookie.Value, s.now())
	if !ok {
		return "", false
	}
	if !s.pass.Known(name) {
		return "", false
	}
	return name, true
}

func (s *Server) renderHome(writer http.ResponseWriter, request *http.Request, name, ok, fail string) {
	feed, err := s.api.feed()
	if err != nil && fail == "" {
		fail = err.Error()
	}
	items, listErr := s.api.list()
	if listErr != nil && fail == "" {
		fail = listErr.Error()
	}
	s.page(writer, http.StatusOK, homePage, homeView{
		Path:        s.path,
		Name:        name,
		Message:     ok,
		Error:       fail,
		Reference:   feed.Reference,
		Environment: feed.Environment,
		Signals:     items,
	})
}

func (s *Server) page(writer http.ResponseWriter, status int, page *template.Template, data any) {
	body, err := render(page, data)
	if err != nil {
		http.Error(writer, "could not render", http.StatusInternalServerError)
		return
	}
	writer.Header().Set("Content-Type", "text/html; charset=utf-8")
	writer.WriteHeader(status)
	_, _ = writer.Write([]byte(body))
}

func (s *Server) headers(writer http.ResponseWriter) {
	writer.Header().Set("Cache-Control", "no-store")
	writer.Header().Set("X-Content-Type-Options", "nosniff")
	writer.Header().Set("X-Frame-Options", "DENY")
	// same-origin, not no-referrer: Chrome sends Origin: null on a same-origin form POST when the
	// document used no-referrer, and that fails the check below.
	writer.Header().Set("Referrer-Policy", "same-origin")
	writer.Header().Set("Content-Security-Policy",
		"default-src 'none'; style-src 'self'; script-src 'self'; font-src 'self'; "+
			"form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
}

func (s *Server) setCookie(writer http.ResponseWriter, value string, maxAge int) {
	http.SetCookie(writer, &http.Cookie{
		Name:     cookieName,
		Value:    value,
		Path:     s.path,
		MaxAge:   maxAge,
		HttpOnly: true,
		Secure:   true,
		SameSite: http.SameSiteStrictMode,
	})
}

func (s *Server) sameOrigin(request *http.Request) bool {
	expected := publicOrigin(request)
	origin := request.Header.Get("Origin")
	// Chrome sends the literal "null" on a same-origin form POST under no-referrer. That is not a
	// missing header, and it is not our origin.
	if origin != "" && origin != "null" {
		return origin == expected
	}
	// Forbidden request header: browsers set this. A cross-site form CSRF is "cross-site".
	if request.Header.Get("Sec-Fetch-Site") == "same-origin" {
		return true
	}
	referer := request.Header.Get("Referer")
	if referer == "" {
		return false
	}
	return strings.HasPrefix(referer, expected+s.path)
}

func (s *Server) refuse(writer http.ResponseWriter, status int, message string) {
	s.page(writer, status, loginPage, loginView{Path: s.path, Message: message})
}

func (s *Server) redirectOK(writer http.ResponseWriter, request *http.Request, message string) {
	http.Redirect(writer, request, s.path+"?ok="+queryToken(message), http.StatusSeeOther)
}

func (s *Server) redirectErr(writer http.ResponseWriter, request *http.Request, message string) {
	http.Redirect(writer, request, s.path+"?error="+queryToken(message), http.StatusSeeOther)
}

func queryToken(message string) string {
	message = strings.TrimSpace(message)
	if len(message) > 180 {
		message = message[:180]
	}
	return url.QueryEscape(message)
}

func publicOrigin(request *http.Request) string {
	scheme := request.Header.Get("X-Forwarded-Proto")
	if scheme == "" {
		if request.TLS != nil {
			scheme = "https"
		} else {
			scheme = "http"
		}
	}
	host := request.Header.Get("X-Forwarded-Host")
	if host == "" {
		host = request.Host
	}
	return scheme + "://" + host
}

func clientIP(request *http.Request) string {
	if forwarded := request.Header.Get("X-Forwarded-For"); forwarded != "" {
		ip, _, _ := strings.Cut(forwarded, ",")
		return strings.TrimSpace(ip)
	}
	host, _, err := net.SplitHostPort(request.RemoteAddr)
	if err != nil {
		return request.RemoteAddr
	}
	return host
}
