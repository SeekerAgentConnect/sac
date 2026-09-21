// Command feed-gateway is the shared feed gateway (SEE-90, docs/wiki/feed-gateway.md).
//
// A developer's publisher publishes a manifest and its proposals here once; every phone subscribed
// to that publisher's channel reads them from here. The publisher keeps no connection to any phone,
// and no phone ever contacts the publisher.
//
// It is not the `gateway/` directory's Caddy. That one is the reverse proxy in front of one
// owner's private sidecar (SAW-035); this is a service of its own, run by whoever hosts the
// public feed, with its own compose stack in feed-gateway/.
//
// Since SEE-144 it also relays private push for independently hosted direct MCP servers. That is
// a wake-up and nothing else: a phone pairs with, streams from, synchronizes with and submits its
// owner's decisions to that server directly, and the gateway relays one content-free "there is
// something to read" message to a device that has explicitly authorized that server. No request,
// approval, signature or result passes through here, and the developer never receives this
// deployment's Firebase credential.
//
// Configuration is the environment (internal/config). There is no publishing credential in it: a
// publisher's credential is created by feed-gatewayctl or by the operator's admin surface and kept
// as a hash, so nothing that could publish appears in a process list or a compose file. The one
// secret the environment does carry is the operator's own password, and it carries it as a hash
// too (SEE-141).
package main

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/config"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/dispatch"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gateway"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/pushrelay"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/relay"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage/sqlite"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/stream"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))

	settings, problems := config.Load(os.LookupEnv)
	if len(problems) > 0 {
		// Every problem at once, so a first start is fixed in one pass rather than one variable at
		// a time. The sidecar's configuration says the same thing the same way.
		fmt.Fprintln(os.Stderr, "The feed gateway cannot start:")
		for _, problem := range problems {
			fmt.Fprintf(os.Stderr, "  - %s\n", problem)
		}
		os.Exit(1)
	}

	documents, err := sqlite.Open(settings.DatabasePath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "The feed gateway cannot open its database: %v\n", err)
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
		fan     dispatch.Fan
		grants  gateway.Grants
		topics  gateway.Topics
		devices pushrelay.Sender
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
			fmt.Fprintf(os.Stderr, "The feed gateway cannot use its broker: %v\n", err)
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
		credentials, err := relay.LoadCredentials(settings.Relay.CredentialsPath)
		if err != nil {
			fmt.Fprintf(os.Stderr, "The feed gateway cannot use its push relay: %v\n", err)
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
			fmt.Fprintf(os.Stderr, "The feed gateway cannot use its push relay: %v\n", err)
			os.Exit(1)
		}
		fan, topics = append(fan, hints), hints
		// The same credential and the same access token, addressing a device instead of a topic
		// (SEE-144). It is a second sender rather than a second method on the first, because what
		// may be addressed and who may ask are different for each — and the one thing worth
		// sharing between them is the grant, which is one Firebase project either way.
		devices = hints.Device()
		// The project is the credential's, and it is the one part of it that is not a secret: it is
		// in every topic name a phone is told. Nothing else about the credential is logged, ever.
		log.Info("relaying hints",
			"endpoint", settings.Relay.Endpoint,
			"project", credentials.ProjectID,
			"environment", settings.Relay.Environment,
			"rate", settings.Relay.Rate,
			"burst", settings.Relay.Burst)
		log.Info("relaying direct push for registered servers",
			"binding_hours", int(settings.Relay.Direct.BindingLifetime.Hours()),
			"idle_hours", int(settings.Relay.Direct.InstallationIdle.Hours()),
			"server_rate", settings.Relay.Direct.ServerRate,
			"device_rate", settings.Relay.Direct.DeviceRate,
			"global_rate", settings.Relay.Direct.GlobalRate)
	} else {
		log.Info("no push relay is configured: nothing is hinted to a phone that is not " +
			"listening, phones that ask where hints arrive are told none are sent here, and a " +
			"registered server that asks for a direct wake-up is told the relay cannot send")
	}
	var dispatcher dispatch.Dispatcher = fan
	if len(fan) == 0 {
		dispatcher = dispatch.Logger{Log: log}
	}

	service := gateway.Build(
		settings, documents, documents, dispatcher, grants, topics, devices, log, time.Now)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := service.Run(ctx); err != nil {
		log.Error("the feed gateway stopped", "error", err)
		os.Exit(1)
	}
	log.Info("the feed gateway stopped")
}
