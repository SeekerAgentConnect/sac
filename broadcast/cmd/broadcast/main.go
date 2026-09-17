// Command broadcast is the shared broadcast gateway (SEE-90, docs/wiki/broadcast-gateway.md).
//
// A developer's publisher publishes a manifest and its proposals here once; every phone subscribed
// to that publisher's channel reads them from here. The publisher keeps no connection to any phone,
// and no phone ever contacts the publisher.
//
// It is not the `gateway/` directory's Caddy. That one is the reverse proxy in front of one
// owner's private sidecar (SAW-035); this is a service of its own, run by whoever hosts the
// broadcast, with its own compose stack in broadcast/.
//
// Configuration is the environment (internal/config). There is no credential in it: a publisher's
// credential is created by broadcastctl and kept as a hash, so nothing that could publish appears
// in a process list or a compose file.
package main

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/config"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/dispatch"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gateway"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/relay"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/store"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/stream"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))

	settings, problems := config.Load(os.LookupEnv)
	if len(problems) > 0 {
		// Every problem at once, so a first start is fixed in one pass rather than one variable at
		// a time. The sidecar's configuration says the same thing the same way.
		fmt.Fprintln(os.Stderr, "The broadcast gateway cannot start:")
		for _, problem := range problems {
			fmt.Fprintf(os.Stderr, "  - %s\n", problem)
		}
		os.Exit(1)
	}

	documents, err := store.Open(settings.DatabasePath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "The broadcast gateway cannot open its database: %v\n", err)
		os.Exit(1)
	}
	defer func() { _ = documents.Close() }()

	// Anything left in the outbox is work from before this process existed: a publication that
	// committed while the last one was stopping. Saying so at startup is how an operator sees that
	// nothing was lost (internal/dispatch).
	if pending, err := documents.Pending(context.Background()); err == nil && pending > 0 {
		log.Info("fan-out has work left over from a previous run", "pending", pending)
	}

	// The fan-out, and the only things in this process that reach out of it. A deployment with
	// neither a broker nor a relay keeps the log dispatcher: the outbox still commits with the
	// document and still drains, so the machinery that makes a crash between committing and fanning
	// out harmless is the same machinery either way — there is just nobody listening at the end of
	// it. The two are independent, and a deployment may configure either, both or neither.
	var (
		fan    dispatch.Fan
		grants gateway.Grants
		topics gateway.Topics
	)
	if settings.Stream.URL != "" {
		broker, err := stream.New(stream.Options{
			URL:          settings.Stream.URL,
			APIKey:       settings.Stream.APIKey,
			TokenKey:     settings.Stream.TokenKey,
			Lifetime:     settings.Stream.TicketLifetime,
			MostChannels: settings.Stream.MostChannels,
		})
		if err != nil {
			fmt.Fprintf(os.Stderr, "The broadcast gateway cannot use its broker: %v\n", err)
			os.Exit(1)
		}
		fan, grants = append(fan, broker), broker
		log.Info("fanning out through the broker",
			"url", settings.Stream.URL,
			"namespace", stream.Namespace,
			"ticket_minutes", int(settings.Stream.TicketLifetime.Minutes()),
			"most_channels", settings.Stream.MostChannels)
	} else {
		log.Info("no broker is configured: publications are not streamed, " +
			"and listeners are told there is no stream here")
	}
	if settings.Relay.CredentialsPath != "" {
		// Read here rather than in the relay's constructor so that a missing or malformed
		// credential stops the process at startup, where an operator is looking, instead of
		// becoming a warning per publication.
		credentials, err := relay.ReadCredentials(settings.Relay.CredentialsPath)
		if err != nil {
			fmt.Fprintf(os.Stderr, "The broadcast gateway cannot use its push relay: %v\n", err)
			os.Exit(1)
		}
		hints, err := relay.New(relay.Options{
			Endpoint:    settings.Relay.Endpoint,
			Credentials: credentials,
			Environment: settings.Relay.Environment,
			Rate:        settings.Relay.Rate,
			Burst:       settings.Relay.Burst,
			Now:         time.Now,
			Log:         log,
		})
		if err != nil {
			fmt.Fprintf(os.Stderr, "The broadcast gateway cannot use its push relay: %v\n", err)
			os.Exit(1)
		}
		fan, topics = append(fan, hints), hints
		// The project is the credential's, and it is the one part of it that is not a secret: it is
		// in every topic name a phone is told. Nothing else about the credential is logged, ever.
		log.Info("relaying hints",
			"endpoint", settings.Relay.Endpoint,
			"project", credentials.ProjectID,
			"environment", settings.Relay.Environment,
			"rate", settings.Relay.Rate,
			"burst", settings.Relay.Burst)
	} else {
		log.Info("no push relay is configured: nothing is hinted to a phone that is not " +
			"listening, and phones that ask where hints arrive are told none are sent here")
	}
	var dispatcher dispatch.Dispatcher = fan
	if len(fan) == 0 {
		dispatcher = dispatch.Logger{Log: log}
	}

	service := gateway.Build(settings, documents, dispatcher, grants, topics, log, time.Now)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := service.Run(ctx); err != nil {
		log.Error("the broadcast gateway stopped", "error", err)
		os.Exit(1)
	}
	log.Info("the broadcast gateway stopped")
}
