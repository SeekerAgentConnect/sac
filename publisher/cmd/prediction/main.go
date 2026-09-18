// Command prediction is the Prediction publisher template (SEE-96,
// docs/wiki/prediction-template.md).
//
// It reads Jupiter's prediction listing, applies the filters its operator configured, and publishes
// one proposal per market that matches — once, to the shared broadcast gateway. Every phone
// subscribed to this publisher's channel reads the same document, and each owner then chooses a
// side and a stake on their own device, approves it there, and places the order through the bundled
// `jupiter.prediction` plugin. **None of that comes back here**: this process never learns who is
// subscribed, which side anyone took, how much they staked, or what became of it.
//
// There is no model in it and no recommendation. A signal says "this market exists and I am
// following it"; whether YES is worth taking is the owner's judgement, made against the market's own
// state as their phone reads it at that moment.
//
//	cp .env.prediction.example .env    # PUBLISHER_*, PREDICTION_*, and the gateway's credential
//	go run ./cmd/prediction
//
// It is the same module as `cmd/copytrading` and a different template: the store, the outbox, the
// drainer, the manifest and the API are shared, and what differs is the kind it registers and that
// its signals are its own rather than its callers'. Neither can be turned into the other by
// configuration.
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

	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/api"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/config"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/discovery"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/jupiter"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/manifest"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/publish"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/signals"
	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/store"
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
	settings, predicting, problems := config.LoadPrediction(os.LookupEnv)
	if len(problems) > 0 {
		// Every problem at once — both halves of the deployment — so a first start is fixed in one
		// pass rather than one variable at a time.
		fmt.Fprintln(os.Stderr, "The publisher cannot start:")
		for _, problem := range problems {
			fmt.Fprintf(os.Stderr, "  - %s\n", problem)
		}
		os.Exit(1)
	}

	// The one kind this template publishes. Registering it here is what makes this a template: the
	// CopyTrading template (SEE-95) is the same core with the other kind, and no setting turns
	// either into the other.
	kind := signals.Prediction{}
	description := manifest.Settings{
		ServerID:    settings.ServerID,
		GatewayURL:  settings.GatewayURL,
		Environment: settings.Environment,
		Requirement: kind.Requirement(),
		DisplayName: settings.DisplayName,
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

	revision, err := documents.ManifestRevision(ctx, manifest.Fingerprint(description), time.Now())
	if err != nil {
		return fmt.Errorf("settle the manifest revision: %w", err)
	}

	gateway, err := publish.New(publish.Options{
		URL:        settings.PublishURL,
		Credential: settings.Credential,
		Timeout:    settings.PublishTimeout,
	})
	if err != nil {
		return err
	}
	drainer := publish.NewDrainer(publish.Plan{
		Documents: documents,
		Gateway:   gateway,
		ServerID:  settings.ServerID,
		Manifest: func(at uint64) *serverv1.ServerManifest {
			return manifest.Document(description, at)
		},
		Log: log,
		Now: time.Now,
	})

	provider, err := jupiter.New(jupiter.Options{
		URL:     predicting.Provider,
		Key:     predicting.Key,
		Timeout: predicting.Timeout,
		Gap:     predicting.Gap,
	})
	if err != nil {
		return err
	}
	reconciler := discovery.New(discovery.Plan{
		Documents: documents,
		Source:    provider,
		Kind:      kind,
		Filters:   predicting.Filters,
		Deposit:   predicting.Deposit,
		Note:      predicting.Note,
		Log:       log,
		Now:       time.Now,
		// A cycle that changed something asks the drainer for a pass. Publication stays one path,
		// in one package, whoever asked for it (internal/publish).
		Wake: drainer.Wake,
	})

	pending, err := documents.Pending(ctx)
	if err != nil {
		return err
	}
	live, err := documents.Live(ctx)
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
		"markets", live,
		"pending", pending)
	// What this deployment is looking for, in the log, because "why is my feed empty" is the
	// question an operator asks first and the filters are the answer to most of it.
	log.Info("looking for markets", "provider", predicting.Provider,
		"filters", predicting.Filters.Describe(),
		"deposit_mint", predicting.Deposit.Mint)

	// The reference a phone adds this feed from, on stdout rather than in the log: it is the one
	// line an operator has to copy somewhere, it carries no secret, and it is the same string every
	// start.
	fmt.Println(manifest.Reference(settings.GatewayURL, settings.ServerID))

	// One publication pass before anything else, so that a template whose manifest cannot be
	// published says so at startup — where an operator is looking — rather than at the first market
	// it finds.
	if _, err := drainer.Pass(ctx); err != nil && !errors.Is(err, context.Canceled) {
		log.Error("the first publication pass did not finish", "error", err)
	}
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
			Documents: documents,
			Drainer:   drainer,
			Kind:      kind,
			Settings:  description,
			Token:     settings.APIToken,
			Log:       log,
			Now:       time.Now,
			// Nobody may write a signal here: they are this template's own, and a caller's would
			// be undone by the next cycle (internal/api).
			Authorship: api.ByDiscovery,
			Markets:    documents,
			Cycles:     reconciler,
		}).Handler(),
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
	// The reconciler's first cycle is at startup rather than after the first interval: an operator
	// who has just changed a filter wants to see what it matches now.
	reconciled := make(chan struct{})
	go func() {
		defer close(reconciled)
		reconciler.Run(ctx)
	}()

	failed := make(chan error, 1)
	go func() {
		log.Info("the API is listening", "address", settings.APIAddress)
		if err := service.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			failed <- err
		}
	}()

	select {
	case err := <-failed:
		return err
	case <-ctx.Done():
	}

	// Stop accepting, let what is in flight finish, and leave what is unpublished in the file: it
	// is durable, and the next start finds the same markets and the same two revisions.
	log.Info("stopping")
	shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	err = service.Shutdown(shutdown)
	<-drained
	<-reconciled
	return err
}
