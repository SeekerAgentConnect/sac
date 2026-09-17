package gateway

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"time"

	"connectrpc.com/connect"

	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/config"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/dispatch"
	gatewayv1connect "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1/gatewayv1connect"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/store"
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

// Gateway is the whole service: two handlers, the fan-out drainer, and the retention sweep.
type Gateway struct {
	Read    http.Handler
	Publish http.Handler
	Drainer *dispatch.Drainer

	config *config.Config
	store  *store.Store
	log    *slog.Logger
	now    func() time.Time
}

// Build assembles the service. It opens no listener — [Gateway.Run] does that, and the tests serve
// the same two handlers over loopback instead, so what they exercise is the service rather than a
// rehearsal of it.
func Build(
	settings *config.Config,
	from *store.Store,
	to dispatch.Dispatcher,
	log *slog.Logger,
	now func() time.Time,
) *Gateway {
	drainer := dispatch.New(from, to, log, now)

	// The read side. No authentication — a feed is a broadcast — and a limit per caller so that
	// one of them cannot spend the store's time.
	reads := NewLimiter(settings.ReadRate, settings.ReadBurst, now)
	readMux := http.NewServeMux()
	readMux.Handle(gatewayv1connect.NewFeedServiceHandler(
		NewFeed(from, now),
		connect.WithReadMaxBytes(MostBytes),
		connect.WithCodec(strictJSON{}),
		connect.WithInterceptors(
			reporting(log),
			Limiting(reads, func(_ context.Context, request connect.AnyRequest) string {
				return caller(request.Peer().Addr, request.Header().Get("X-Forwarded-For"))
			}),
		),
	))
	readMux.HandleFunc("/healthz", healthz)

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

	return &Gateway{
		Read:    readMux,
		Publish: publishMux,
		Drainer: drainer,
		config:  settings,
		store:   from,
		log:     log,
		now:     now,
	}
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
	g.log.Info("broadcast gateway listening",
		"read", reading.Addr().String(),
		"publish", publishing.Addr().String(),
		"origin", g.config.PublicURL,
		"database", g.store.Path())

	failed := make(chan error, 2)
	go func() { failed <- serve(read, reading) }()
	go func() { failed <- serve(publish, publishing) }()

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
	shutdown := errors.Join(read.Shutdown(closing), publish.Shutdown(closing))
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
		removed, err := g.store.Sweep(ctx, g.now().Add(-g.config.Retention))
		switch {
		case err != nil && ctx.Err() == nil:
			g.log.Warn("retention sweep failed", "error", err)
		case removed > 0:
			g.log.Info("retention swept expired proposals", "removed", removed)
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
