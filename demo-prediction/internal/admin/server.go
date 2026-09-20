// Package admin is the password-gated Prediction operator UI (SEE-138).
//
// It is a client of the template's existing /v1 API and nothing else: the publisher token stays in
// this process, operators log in with named bcrypt passwords, and the public origin serves HTML on
// /trader rather than /v1. Forms carry the actions; a few lines of script only mark the page busy
// while a search or publish is in flight.
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
	defaultSelectCap  = 20
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
	selects *limit.Limiter
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
	SelectPerUser int
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
	selects := plan.SelectPerUser
	if selects == 0 {
		selects = defaultSelectCap
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
		selects: limit.New(selects, actionWindow, now),
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
	mux.HandleFunc("POST "+s.path+"/select", s.selectMarket)
	mux.HandleFunc("POST "+s.path+"/signals/{id}/retry", s.retry)
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

func (s *Server) selectMarket(writer http.ResponseWriter, request *http.Request) {
	name, ok := s.guard(writer, request)
	if !ok {
		return
	}
	if !s.selects.Allow("select:" + name) {
		s.redirectErr(writer, request, "too many publishes; try later")
		return
	}
	if err := request.ParseForm(); err != nil {
		s.redirectErr(writer, request, "the form could not be read")
		return
	}
	id := strings.TrimSpace(request.FormValue("market_id"))
	if _, err := s.api.selectMarket(id); err != nil {
		s.selects.Undo("select:" + name)
		s.redirectErr(writer, request, err.Error())
		return
	}
	s.redirectOK(writer, request, "published")
}

func (s *Server) retry(writer http.ResponseWriter, request *http.Request) {
	name, authed := s.guard(writer, request)
	if !authed {
		return
	}
	if !s.mutates.Allow("mutate:" + name) {
		s.redirectErr(writer, request, "too many retry attempts; try later")
		return
	}
	id := request.PathValue("id")
	if !signals.IsID(id) {
		s.redirectErr(writer, request, "that is not a signal ID")
		return
	}
	if _, err := s.api.retry(id); err != nil {
		s.redirectErr(writer, request, err.Error())
		return
	}
	s.redirectOK(writer, request, "retrying publication")
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
	query := queryFrom(request)
	if query.Source == "" && query.Category == "" && query.Keywords == "" {
		query = s.api.defaults()
		query = mergeQuery(query, queryFrom(request))
	}
	searched := request.URL.Query().Get("search") != ""
	var markets []Market
	if searched {
		found, searchErr := s.api.search(query)
		if searchErr != nil && fail == "" {
			fail = searchErr.Error()
		}
		markets = found
	}
	s.page(writer, http.StatusOK, homePage, homeView{
		Path:        s.path,
		Name:        name,
		Message:     ok,
		Error:       fail,
		Reference:   feed.Reference,
		Environment: feed.Environment,
		Query:       query,
		Signals:     items,
		Markets:     markets,
		Searched:    searched,
	})
}

func queryFrom(request *http.Request) Query {
	values := request.URL.Query()
	return Query{
		Source:            values.Get("source"),
		Category:          values.Get("category"),
		Filter:            values.Get("filter"),
		Keywords:          values.Get("keywords"),
		Tags:              values.Get("tags"),
		LeastCloseMinutes: values.Get("least_close_minutes"),
		MostCloseMinutes:  values.Get("most_close_minutes"),
		State:             values.Get("state"),
	}
}

func mergeQuery(base, over Query) Query {
	if over.Source != "" {
		base.Source = over.Source
	}
	if over.Category != "" {
		base.Category = over.Category
	}
	if over.Filter != "" {
		base.Filter = over.Filter
	}
	if over.Keywords != "" {
		base.Keywords = over.Keywords
	}
	if over.Tags != "" {
		base.Tags = over.Tags
	}
	if over.LeastCloseMinutes != "" {
		base.LeastCloseMinutes = over.LeastCloseMinutes
	}
	if over.MostCloseMinutes != "" {
		base.MostCloseMinutes = over.MostCloseMinutes
	}
	if over.State != "" {
		base.State = over.State
	}
	return base
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
	writer.Header().Set("Referrer-Policy", "same-origin")
	writer.Header().Set("Content-Security-Policy",
		"default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
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
	if origin != "" && origin != "null" {
		return origin == expected
	}
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
