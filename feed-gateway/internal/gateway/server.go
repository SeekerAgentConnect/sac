package gateway

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"time"

	"connectrpc.com/connect"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/admin"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/config"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/credential"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/dispatch"
	gatewayv1connect "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1/gatewayv1connect"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/pushrelay"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// MostBytes is the largest request the gateway reads, matching what the sidecar and the Caddy in
// front of it already enforce (64 KiB). A proposal is bounded data an order of magnitude smaller
// than this; the limit is here so that a body which is not one costs nothing to refuse.
const MostBytes = 64 * 1024

// Timeouts, chosen for what this service does: unary calls over a few kilobytes. Nothing here
// streams, so there is no long-lived request a generous write timeout would be protecting.
const (
	headerTimeout = 10 * time.Second
	readTimeout   = 30 * time.Second
	writeTimeout  = 30 * time.Second
	idleTimeout   = 2 * time.Minute
	drainTimeout  = 15 * time.Second
)

// Gateway is the whole service: its isolated feed and publisher handlers, the operator's
// administration when one is configured, the fan-out drainer, and the retention sweep.
type Gateway struct {
	Read    http.Handler
	Publish http.Handler
	// Admin is nil unless the deployment configured an operator password. A nil handler means no
	// listener is opened and no administrative route exists anywhere in this process (SEE-141).
	Admin http.Handler
	// Relay is the private push routing for independently hosted direct servers (SEE-144). It is
	// two handlers rather than one because its two callers are different parties on different
	// listeners: phones reach Installations over the public read listener, and developers' own
	// backends reach Servers over the publisher listener. Neither route exists on the other's
	// listener, so a routing mistake cannot let a phone send an invalidation or a server enroll an
	// installation.
	Relay   *pushrelay.Server
	Drainer *dispatch.Drainer

	config  *config.Config
	storage storage.MaintenanceStore
	log     *slog.Logger
	now     func() time.Time
}

// Build assembles the service. It opens no listener — [Gateway.Run] does that, and the tests serve
// the same two handlers over loopback instead, so what they exercise is the service rather than
// a rehearsal of it.
func Build(
	settings *config.Config,
	from storage.GatewayStore,
	administers storage.PublisherAdminStore,
	to dispatch.Dispatcher,
	grants Grants,
	topics Topics,
	devices pushrelay.Sender,
	log *slog.Logger,
	now func() time.Time,
) *Gateway {
	drainer := dispatch.New(from, to, log, now)

	// The read side. No authentication — a feed is a broadcast — and a limit per caller so that
	// one of them cannot spend the store's time.
	reads := NewLimiter(settings.ReadRate, settings.ReadBurst, now)
	readMux := http.NewServeMux()
	readMux.Handle(gatewayv1connect.NewFeedServiceHandler(
		NewFeed(from, now, grants, topics),
		connect.WithReadMaxBytes(MostBytes),
		connect.WithCodec(strictJSON{}),
		connect.WithInterceptors(
			reporting(log),
			Limiting(reads, func(_ context.Context, request connect.AnyRequest) string {
				return caller(request.Peer().Addr, request.Header().Get("X-Forwarded-For"), settings.TrustedProxies)
			}),
		),
	))
	readMux.HandleFunc("/healthz", healthz)

	// The relay (SEE-144). It is built whether or not this deployment can actually send: a phone
	// may enroll and authorize a binding against a gateway whose operator has not finished setting
	// up Firebase, and neither of those sends anything. What follows the credential is the send
	// itself, and with no sender a server that asks for one is told the relay cannot send right
	// now — which is true, and is worth retrying.
	//
	// Nothing here is reachable without a decision somebody already made. A server reaches nothing
	// until the operator registers it and enables relay for it; a phone's enrollment wakes nobody
	// until the phone itself authorizes a server.
	relaying, err := pushrelay.New(pushrelay.Options{
		Store:           from,
		Send:            devices,
		BindingLifetime: settings.Relay.Direct.BindingLifetime,
		Enrollments: NewLimiter(settings.Relay.Direct.EnrollRate,
			settings.Relay.Direct.EnrollBurst, now),
		Servers: NewLimiter(settings.Relay.Direct.ServerRate,
			settings.Relay.Direct.ServerBurst, now),
		Bindings: NewLimiter(settings.Relay.Direct.DeviceRate,
			settings.Relay.Direct.DeviceBurst, now),
		Global: NewLimiter(settings.Relay.Direct.GlobalRate,
			settings.Relay.Direct.GlobalBurst, now),
		Caller: func(request *http.Request) string {
			return caller(request.RemoteAddr, request.Header.Get("X-Forwarded-For"),
				settings.TrustedProxies)
		},
		Log: log,
		Now: now,
	})
	if err != nil {
		// The configuration was validated already, so this is a programming error rather than an
		// operator's mistake. Not serving the routes is the only safe answer: a half-built relay
		// that enrolled installations it could not authorize would be worse than none.
		log.Error("the push relay could not be built", "error", err)
	} else {
		readMux.Handle(pushrelay.Prefix+"/", relaying.Installations)
	}

	// The publisher side, on its own handler and its own listener. The credential is resolved
	// once, before any method runs, and the limit counts against the publisher it resolved to
	// rather than against an address: a publisher behind a changing address is still one publisher.
	publishes := NewLimiter(settings.PublishRate, settings.PublishBurst, now)
	publishMux := http.NewServeMux()
	publishMux.Handle(gatewayv1connect.NewPublisherServiceHandler(
		NewPublisher(from, settings.PublicURL, settings.MaxProposals, now, drainer.Wake),
		connect.WithReadMaxBytes(MostBytes),
		connect.WithCodec(strictJSON{}),
		connect.WithInterceptors(
			reporting(log),
			Authenticating(from),
			Limiting(publishes, func(ctx context.Context, _ connect.AnyRequest) string {
				return publisherOf(ctx)
			}),
		),
	))
	publishMux.HandleFunc("/healthz", healthz)
	if relaying != nil {
		// The send is on the publisher listener because its caller is the same party as a
		// publication's: a developer's own backend, outbound only, with a credential this operator
		// issued. A deployment that keeps this listener on a private network keeps both.
		publishMux.Handle(pushrelay.Prefix+"/", relaying.Servers)
	}

	// The operator's administration, on a third listener of its own and only when a password hash
	// is configured. A deployment that configured none has no administrative surface at all: not a
	// disabled route, not a setup page — nothing is built, so there is nothing to reach.
	var administration http.Handler
	if settings.Admin.Enabled() {
		built, err := buildAdmin(settings, administers, from, log, now)
		if err != nil {
			// Refusing to serve is the only safe answer: an administrative surface that half
			// exists is worse than one that does not, and the configuration was already validated,
			// so this is a programming error rather than an operator's mistake.
			log.Error("the operator's administration could not be built", "error", err)
		} else {
			administration = built
			log.Info("serving operator administration",
				"address", settings.Admin.Address,
				"path", settings.Admin.Path,
				"session_minutes", int(settings.Admin.SessionLifetime.Minutes()))
		}
	} else {
		log.Info("no operator password is configured: there is no administrative surface, " +
			"and publishers are registered with feed-gatewayctl")
	}

	return &Gateway{
		Read:    readMux,
		Publish: publishMux,
		Admin:   administration,
		Relay:   relaying,
		Drainer: drainer,
		config:  settings,
		storage: from,
		log:     log,
		now:     now,
	}
}

// buildAdmin assembles the operator's surface from the same store the feed is served from, the
// gateway's own rate limiter, and the deployment's own trusted-proxy policy — so a login is
// counted against the same caller identity a read is.
func buildAdmin(
	settings *config.Config,
	administers storage.PublisherAdminStore,
	from storage.GatewayStore,
	log *slog.Logger,
	now func() time.Time,
) (http.Handler, error) {
	if administers == nil {
		return nil, errors.New("no publisher administration store")
	}
	password, err := credential.ParsePassword(settings.Admin.PasswordHash)
	if err != nil {
		return nil, err
	}
	return admin.New(admin.Options{
		Path:            settings.Admin.Path,
		Password:        password,
		SessionLifetime: settings.Admin.SessionLifetime,
		// The cookie is marked Secure exactly when this deployment is served over HTTPS. A
		// loopback development gateway over plain HTTP could not keep a cookie that demanded it.
		Secure:       strings.HasPrefix(settings.PublicURL, "https://"),
		PublicURL:    settings.PublicURL,
		PublisherURL: settings.Admin.PublisherURL,
		Store: adminStore{
			PublisherAdminStore: administers, FeedStore: from, RelayStore: from,
		},
		Logins: NewLimiter(settings.Admin.LoginRate, settings.Admin.LoginBurst, now),
		Caller: func(request *http.Request) string {
			return caller(request.RemoteAddr, request.Header.Get("X-Forwarded-For"),
				settings.TrustedProxies)
		},
		Log: log,
		Now: now,
	})
}

// adminStore joins the publisher administration boundary with the two authoritative reads the
// operator's pages make — what a publisher's manifest says and how much its channel holds. Both
// halves are the storage contract rather than the SQLite implementation.
type adminStore struct {
	storage.PublisherAdminStore
	storage.FeedStore
	// The relay's aggregate for a server's page. It is the contract's own read, so the admin
	// surface gets counts and instants and has no query available to it that could return a device
	// target, a push handle or an installation identity (SEE-144).
	storage.RelayStore
}

// Run serves both APIs, drains the outbox and sweeps retention until ctx is done, and then shuts
// down in order: stop accepting, let what is in flight finish, make one last fan-out pass.
func (g *Gateway) Run(ctx context.Context) error {
	read := &http.Server{
		Addr:              g.config.ReadAddress,
		Handler:           g.Read,
		ReadHeaderTimeout: headerTimeout,
		ReadTimeout:       readTimeout,
		WriteTimeout:      writeTimeout,
		IdleTimeout:       idleTimeout,
		MaxHeaderBytes:    16 * 1024,
	}
	publish := &http.Server{
		Addr:              g.config.PublisherAddress,
		Handler:           g.Publish,
		ReadHeaderTimeout: headerTimeout,
		ReadTimeout:       readTimeout,
		WriteTimeout:      writeTimeout,
		IdleTimeout:       idleTimeout,
		MaxHeaderBytes:    16 * 1024,
	}
	reading, err := net.Listen("tcp", read.Addr)
	if err != nil {
		return err
	}
	publishing, err := net.Listen("tcp", publish.Addr)
	if err != nil {
		_ = reading.Close()
		return err
	}
	servers := []*http.Server{read, publish}
	listeners := []net.Listener{reading, publishing}
	fields := []any{
		"read", reading.Addr().String(),
		"publish", publishing.Addr().String(),
		"origin", g.config.PublicURL,
		"database", g.config.Database(),
	}
	if g.Admin != nil {
		administration := &http.Server{
			Addr:              g.config.Admin.Address,
			Handler:           g.Admin,
			ReadHeaderTimeout: headerTimeout,
			ReadTimeout:       readTimeout,
			WriteTimeout:      writeTimeout,
			IdleTimeout:       idleTimeout,
			MaxHeaderBytes:    16 * 1024,
		}
		administering, err := net.Listen("tcp", administration.Addr)
		if err != nil {
			_ = reading.Close()
			_ = publishing.Close()
			return err
		}
		servers = append(servers, administration)
		listeners = append(listeners, administering)
		fields = append(fields, "admin", administering.Addr().String()+g.config.Admin.Path)
	}
	g.log.Info("feed gateway listening", fields...)

	failed := make(chan error, len(servers))
	for at, server := range servers {
		go func() { failed <- serve(server, listeners[at]) }()
	}

	background, stop := context.WithCancel(ctx)
	defer stop()
	go g.Drainer.Run(background)
	go g.sweep(background)

	var reason error
	select {
	case <-ctx.Done():
	case reason = <-failed:
	}
	closing, cancel := context.WithTimeout(context.WithoutCancel(ctx), drainTimeout)
	defer cancel()
	// The listeners first, so nothing new arrives while the drainer makes its last pass.
	var shutdown error
	for _, server := range servers {
		shutdown = errors.Join(shutdown, server.Shutdown(closing))
	}
	stop()
	return errors.Join(reason, shutdown)
}

func serve(server *http.Server, on net.Listener) error {
	if err := server.Serve(on); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

// sweep is retention: every hour, proposals whose expiry passed longer ago than the retention
// window stop being served. It is deliberately a long, dull loop rather than a scheduler — there is
// one job, it is idempotent, and missing a pass costs nothing but a few more rows.
func (g *Gateway) sweep(ctx context.Context) {
	const interval = time.Hour
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		// A pass on start as well, so a gateway that was down for a week does not serve a week of
		// expired proposals until the first tick.
		at := g.now()
		removed, err := g.storage.Sweep(ctx, at.Add(-g.config.Retention))
		switch {
		case err != nil && ctx.Err() == nil:
			g.log.Warn("retention sweep failed", "error", err)
		case removed > 0:
			g.log.Info("retention swept expired proposals", "removed", removed)
		}
		// The relay's own retention, on the same loop and for the same reason: it is one
		// idempotent job, and what it bounds is grants that outlived being used rather than
		// documents that outlived being interesting (SEE-144).
		direct := g.config.Relay.Direct
		ended, err := g.storage.SweepRelay(ctx, storage.RelayRetention{
			Bindings: at,
			Idle:     at.Add(-direct.InstallationIdle),
			Unbound:  at.Add(-direct.UnboundGrace),
		})
		switch {
		case err != nil && ctx.Err() == nil:
			g.log.Warn("relay sweep failed", "error", err)
		case ended > 0:
			g.log.Info("relay swept abandoned grants", "removed", ended)
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

// healthz is unauthenticated and says one thing. It names no publisher, counts nothing and reveals
// no configuration: it is for the process manager in front, which only needs to know that this one
// is answering.
func healthz(writer http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodGet && request.Method != http.MethodHead {
		writer.Header().Set("Allow", "GET, HEAD")
		http.Error(writer, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	writer.Header().Set("Content-Type", "application/json")
	writer.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(writer).Encode(map[string]string{"status": "ok"})
}

// reporting logs what a call was and how it ended, and is the one place an internal failure is
// written down. A caller gets "internal" and nothing else: the message from the store could name a
// file, a column or a publisher, and an error is not the place to publish any of those.
func reporting(log *slog.Logger) connect.UnaryInterceptorFunc {
	return func(next connect.UnaryFunc) connect.UnaryFunc {
		return func(ctx context.Context, request connect.AnyRequest) (connect.AnyResponse, error) {
			response, err := next(ctx, request)
			if err == nil {
				return response, nil
			}
			var failure *connect.Error
			// A typed nil in an error interface is not nil, and a handler that returned one would
			// otherwise panic here rather than answer. It is a mistake to make once.
			if !errors.As(err, &failure) || failure == nil {
				failure = connect.NewError(connect.CodeInternal, err)
			}
			if failure.Code() == connect.CodeInternal {
				log.Error("call failed",
					"procedure", request.Spec().Procedure,
					"error", failure.Message())
				// Rebuilt without the message, and without the details, which are the store's.
				return nil, connect.NewError(connect.CodeInternal, errors.New("internal"))
			}
			log.Info("call refused",
				"procedure", request.Spec().Procedure,
				"code", failure.Code().String(),
				"reason", failure.Message())
			return nil, err
		}
	}
}
