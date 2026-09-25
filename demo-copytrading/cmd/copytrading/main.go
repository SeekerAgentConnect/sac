// Command copytrading is the CopyTrading publisher template (SEE-95,
// docs/wiki/copytrading-template.md).
//
// A trader — or a strategy system on their behalf — posts one signal here. The template publishes
// it once to the shared feed gateway, and every phone subscribed to this publisher's channel
// reads the same document. Each owner then chooses their own amount on their own device, approves
// it there, and executes it through the bundled `jupiter.swap` plugin. **None of that comes back
// here**: this process never learns what anyone chose, whether they went ahead, or what came of it.
//
// The feed is **restricted** (SEE-156, docs/wiki/restricted-feeds.md). A phone proves it controls a
// wallet by signing a challenge — not a transaction — at PUBLISHER_AUTH_ORIGIN, the operator
// approves or rejects that device on the trader page, and an approved device redeems a one-use
// invitation for a session the gateway enforces. So this process does know which wallets and
// devices it admitted, because admitting them is its decision; it still never learns what any of
// them did with a signal.
//
// "CopyTrading" means user-approved trader signals. There is no wallet monitoring in it, no copy
// detection, no unattended execution and no exchange account: a signal is a statement, and every
// owner decides for themselves.
//
//	cp .env.example .env    # PUBLISHER_*, and the credential the gateway's operator issued
//	go run ./cmd/copytrading
//
// It is not `mcp-server/`, which is one owner's private server for their own phone,
// and it is not the gateway in `feed-gateway/`, which is the shared service this publishes to. Three
// different servers, three different operators (docs/wiki/mcp-adapter.md).
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/access"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/api"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/config"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/gateway"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/manifest"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/publish"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/store"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))
	if err := run(log); err != nil {
		log.Error("the publisher stopped", "error", err)
		os.Exit(1)
	}
	log.Info("the publisher stopped")
}

func run(log *slog.Logger) error {
	settings, problems := config.Load(os.LookupEnv)
	// This demo's feed is restricted (SEE-156): only devices the operator approved may read it. That
	// is compiled in, like the kind below, rather than a setting — the Prediction demo is the public
	// one — so the settings it needs are required rather than optional.
	reader := config.NewReader(os.LookupEnv)
	restricted := access.Load(reader)
	problems = append(problems, reader.Problems()...)
	if len(problems) > 0 {
		// Every problem at once, so a first start is fixed in one pass rather than one variable at
		// a time. The gateway and the sidecar say the same thing the same way.
		fmt.Fprintln(os.Stderr, "The publisher cannot start:")
		for _, problem := range problems {
			fmt.Fprintf(os.Stderr, "  - %s\n", problem)
		}
		os.Exit(1)
	}

	// The one kind this template publishes. Registering it here — rather than choosing one from a
	// setting — is what makes this a template: the prediction template (SEE-96) is the same core
	// with a different kind, and neither can be turned into the other by configuration.
	kind := signals.Swap{}
	description := manifest.Settings{
		ServerID:    settings.ServerID,
		GatewayURL:  settings.GatewayURL,
		Environment: settings.Environment,
		Requirement: kind.Requirement(),
		DisplayName: settings.DisplayName,
		AuthOrigin:  restricted.AuthOrigin,
	}

	documents, err := store.Open(settings.DatabasePath, store.Stamp{
		ServerID: settings.ServerID,
		// The word itself: what the file is stamped with is text, and the store compares it
		// without knowing the vocabulary, so a file that holds anything else is refused and named
		// rather than interpreted.
		Environment: settings.Environment.String(),
		GatewayURL:  settings.GatewayURL,
	})
	if err != nil {
		return fmt.Errorf("open %s: %w", settings.DatabasePath, err)
	}
	defer func() { _ = documents.Close() }()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// What revision the manifest is at, given the settings this process was started with. It moves
	// only when they do, so a restart republishes the same revision and the gateway answers
	// "unchanged": nobody's phone re-reads a manifest that has not changed.
	revision, err := documents.ManifestRevision(ctx, manifest.Fingerprint(description), time.Now())
	if err != nil {
		return fmt.Errorf("settle the manifest revision: %w", err)
	}

	gateway, err := gateway.New(gateway.Options{
		URL:        settings.PublishURL,
		Credential: settings.Credential,
		Timeout:    settings.PublishTimeout,
	})
	if err != nil {
		return err
	}
	// Nothing is published until the gateway confirms it enforces this feed as restricted: a gateway
	// too old to know, or one whose operator registered the feed as public, would serve every signal
	// to anybody (SEE-156).
	guard := access.NewGuard(gateway, restricted.AuthOrigin, time.Now)
	drainer := publish.NewDrainer(publish.Plan{
		Documents: documents,
		Gateway:   gateway,
		ServerID:  settings.ServerID,
		Manifest: func(at uint64) *serverv1.ServerManifest {
			return manifest.Document(description, at)
		},
		Log:   log,
		Now:   time.Now,
		Guard: guard.Check,
	})

	// Who may read: the operator decides (access.ManualApproval) on the trader page's Devices view,
	// and the syncer tells the gateway, retrying until it confirms.
	syncer := access.NewSyncer(access.SyncPlan{
		Store:    documents,
		Grants:   gateway,
		Lifetime: restricted.GrantLifetime,
		Channel:  signals.ChannelFor(settings.ServerID),
		Log:      log,
		Now:      time.Now,
	})
	devices := access.New(access.Plan{
		Store:       documents,
		Eligibility: access.ManualApproval{},
		Syncer:      syncer,
		Log:         log,
		Now:         time.Now,
		Settings: access.Settings{
			ServerID:           settings.ServerID,
			GatewayURL:         settings.GatewayURL,
			AuthOrigin:         restricted.AuthOrigin,
			GrantLifetime:      restricted.GrantLifetime,
			InvitationLifetime: restricted.InvitationLifetime,
		},
	})

	pending, err := documents.Pending(ctx)
	if err != nil {
		return err
	}
	log.Info("publishing as this server",
		"server_id", settings.ServerID,
		"channel", signals.ChannelFor(settings.ServerID),
		"gateway", settings.GatewayURL,
		"publishing_to", settings.PublishURL,
		"environment", settings.Environment,
		"operation", kind.Operation(),
		"plugin", kind.Requirement().PluginID,
		"settings_revision", revision,
		"database", documents.Path(),
		"pending", pending,
		"access", "restricted",
		"auth_origin", restricted.AuthOrigin,
		"grant_hours", int(restricted.GrantLifetime.Hours()))

	// The reference a phone adds this feed from, on stdout rather than in the log: it is the one
	// line an operator has to copy somewhere, it carries no secret, and it is the same string
	// every start.
	fmt.Println(manifest.ReferenceOf(description))

	// One pass before the API opens, so that a template whose manifest cannot be published says so
	// at startup — where an operator is looking — rather than at the first signal.
	if _, err := drainer.Pass(ctx); err != nil && !errors.Is(err, context.Canceled) {
		log.Error("the first publication pass did not finish", "error", err)
	}
	// And if the manifest was refused, say what it usually means. Every phone reads a feed's
	// manifest before it will hold the feed at all, so a template whose manifest is not there is a
	// template nobody can subscribe to — and the commonest cause is this pair of settings, which
	// the gateway cannot tell us about because the address that answered was not its publisher
	// API.
	if _, state, err := documents.Manifest(ctx); err == nil && state.Problem != "" {
		log.Error("nobody can subscribe to this publisher until its manifest is published",
			"problem", state.Problem,
			"publishing_to", settings.PublishURL,
			"advice", "PUBLISHER_PUBLISH_URL must reach the gateway's publisher API, and "+
				"PUBLISHER_GATEWAY_URL must be the origin phones read from (its own "+
				"BROADCAST_PUBLIC_URL). They are the same address when the gateway's proxy "+
				"serves both, and different when it uses separate read and publisher listeners")
	}

	service := &http.Server{
		Addr: settings.APIAddress,
		Handler: api.New(api.Plan{
			Documents:   documents,
			Drainer:     drainer,
			Kind:        kind,
			Settings:    description,
			Token:       settings.APIToken,
			Log:         log,
			Now:         time.Now,
			CreateLimit: settings.CreateLimit,
			Access:      devices,
		}).Handler(),
		// A slow-header client should not be able to hold a connection open indefinitely, and
		// nothing here streams: a request is a bounded JSON document and an answer is another.
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       2 * time.Minute,
		ErrorLog:          slog.NewLogLogger(log.Handler(), slog.LevelWarn),
	}

	drained := make(chan struct{})
	go func() {
		defer close(drained)
		drainer.Run(ctx)
	}()

	// Telling the gateway this process is running, so a phone can be shown that this feed is
	// online and not merely that the gateway is (SEE-150). It publishes nothing; a publisher with
	// signals to send has already checked in by sending them.
	checked := make(chan struct{})
	go func() {
		defer close(checked)
		publish.NewPresence(publish.PresencePlan{Gateway: gateway, Log: log}).Run(ctx)
	}()

	synced := make(chan struct{})
	go func() {
		defer close(synced)
		syncer.Run(ctx)
	}()

	// The authentication endpoint phones call, on its own listener: it is published at
	// PUBLISHER_AUTH_ORIGIN, while the token-protected API above stays where it was.
	authentication := &http.Server{
		Addr:              restricted.AuthAddress,
		Handler:           devices.Handler(access.Limits{PerHour: restricted.ChallengesPerHour}),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       2 * time.Minute,
		ErrorLog:          slog.NewLogLogger(log.Handler(), slog.LevelWarn),
	}

	failed := make(chan error, 2)
	go func() {
		log.Info("the API is listening", "address", settings.APIAddress)
		if err := service.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			failed <- err
		}
	}()
	go func() {
		log.Info("the authentication endpoint is listening", "address", restricted.AuthAddress,
			"origin", restricted.AuthOrigin)
		if err := authentication.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			failed <- err
		}
	}()

	select {
	case err := <-failed:
		return err
	case <-ctx.Done():
	}

	// Stop accepting, let what is in flight finish, and leave what is unpublished in the file: it
	// is durable, and the next start finds the same two revisions (publisher-support/store).
	log.Info("stopping")
	shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	err = errors.Join(service.Shutdown(shutdown), authentication.Shutdown(shutdown))
	<-drained
	<-checked
	<-synced
	return err
}
