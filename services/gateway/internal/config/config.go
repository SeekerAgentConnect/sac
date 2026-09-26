// Package config reads the feed gateway's settings from the environment (SEE-90).
//
// It follows the sidecar's rules, because an operator running both should not have to learn two
// habits: every setting is one environment variable, a problem names the variable rather than
// guessing a value, and every problem is reported at once so a first start fixes all of them in one
// pass. Nothing here has a default that opens something: the listeners bind loopback unless told
// otherwise, and the two settings a deployment cannot get wrong — the origin it publishes as and
// the file it keeps documents in — have no default at all.
//
// No credential is configured here. A publisher's credential is created by feed-gatewayctl and stored
// as a hash (internal/storage), so there is no token in the environment to leak into a process list,
// a log line, or a compose file. SEE-92's push credential is the one thing that has to be usable
// rather than compared, and it is still not configured here: what is configured is the path of the
// file it is in, and the file is read by the package that sends hints (internal/relay).
package config

import (
	"fmt"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/credential"
)

// Config is the validated deployment.
type Config struct {
	// Where the public read API listens: FeedService and /healthz, and nothing that writes.
	ReadAddress string
	// Where the publisher API listens. A separate socket rather than a path on the first one, so a
	// deployment can keep it off the internet entirely and no routing mistake can expose a write
	// through a read endpoint.
	PublisherAddress string
	// This gateway's canonical origin, as a phone's feed reference spells it. A published manifest
	// must name exactly this, because the phone compares the two character for character (SEE-88).
	PublicURL string
	// The SQLite file, when the gateway keeps its own. It is the authority for everything the
	// gateway serves.
	DatabasePath string
	// The Postgres URL, when the gateway keeps its state in a database somebody else runs
	// (SEE-145). Exactly one of this and DatabasePath is set, and which one is set is the whole of
	// how a deployment chooses its storage implementation.
	//
	// It carries a password, so it is configured as a secret and never logged: what a startup line
	// says is the host and the database, which the store builds from the parsed parts.
	DatabaseURL string
	// How long past its expiry a proposal is still served before it is swept.
	Retention time.Duration
	// The most proposals one channel may hold at once.
	MaxProposals int
	// How often, at most, a publisher is expected to say that its own server is running (SEE-150).
	// It is what PublisherService.Heartbeat answers with, and the gateway shows a feed offline once
	// three of these have passed with no authenticated call from its publisher.
	//
	// Three rather than one because a check-in is one HTTP request over somebody else's network: a
	// single lost one must not flip a running feed to offline on every phone reading it. The window
	// is derived rather than configured, so no operator can set one shorter than the interval and
	// leave every feed permanently offline.
	Heartbeat time.Duration
	// The longest a restricted feed's grant may run without its publisher renewing it (SEE-156).
	// It is the bound on how long a device keeps access when its publisher cannot reach this
	// gateway to revoke it, and the gateway shortens any longer grant to it.
	MostGrant time.Duration
	// Reads per second and the burst above it, per remote address.
	ReadRate  float64
	ReadBurst int
	// Publications per second and the burst above it, per publisher.
	PublishRate  float64
	PublishBurst int
	// Proxies whose X-Forwarded-For value may identify the caller for read-rate limiting. Loopback
	// is always trusted; ordinary container networks must opt in to their exact CIDR.
	TrustedProxies []netip.Prefix
	// The broker that fans publications out, when one is configured (SEE-91).
	Stream Stream
	// The push relay that hints to phones nobody is looking at, when one is configured (SEE-92).
	Relay Relay
	// The operator's browser administration, when one is configured (SEE-141).
	Admin Admin
}

// Stream is the fan-out, and an empty URL is a complete answer: the gateway holds the documents,
// answers reads, keeps its outbox draining to a log line, and tells anyone who asks to listen that
// there is no stream here. Running one without a broker is a smaller deployment rather than a
// broken one.
type Stream struct {
	// The broker's API origin, reachable from the gateway and from nowhere else — a compose
	// service name or a loopback port, never the public URL. It is the gateway that talks to the
	// broker's API; a phone never does, and the broker's API key would let it publish anything to
	// any channel if it could.
	URL string
	// The broker's API key, and the HMAC key it verifies listener tickets with. Both are secrets of
	// the deployment, not of a publisher: they say nothing about who may publish (that is a
	// credential in the store) and everything about who may fan out and who may listen.
	APIKey   string
	TokenKey string
	// How long a listener's ticket is good for, and how many channels one may grant.
	TicketLifetime time.Duration
	MostChannels   int
}

// Relay is the push hint, and an empty credential path is a complete answer in the same way an
// empty broker URL is: the gateway holds the documents, answers reads, streams to whoever is
// looking, and tells a phone that asks where hints arrive that none are sent here.
//
// The credential itself is not configured, only where to read it. It is the one credential in this
// service that cannot be a hash — a push has to be sent — so it stays a file the deployment mounts
// into this container and nothing else, and no part of it is ever in the environment, a log line or
// an answer (internal/relay).
type Relay struct {
	// The service account file. Setting it is what turns the relay on.
	CredentialsPath string
	// Where the push API is. It has no default on purpose: the one package in this service that
	// opens a connection takes its address from its operator, which is what keeps a hostname out of
	// the source and lets a deployment point this at something of its own.
	Endpoint string
	// Which topics this deployment publishes hints on: "production" or "sandbox". A Firebase
	// project can serve both kinds of deployment, and a sandbox publication must not wake a
	// production subscriber, so the scope is part of every topic name.
	Environment string
	// Hints per topic per second, and the burst above it. This is a bound on how often a feed's
	// subscribers are woken, which is not the same thing as the publish limit above: that one
	// bounds what a publisher can cost this gateway.
	Rate  float64
	Burst int
	// Direct is the private push routing for independently hosted direct servers (SEE-144). It is
	// configured separately from the hint above because it is a different thing sent to a
	// different kind of address on behalf of a different caller, and an operator raising one of
	// these numbers is almost never asking to raise the other.
	Direct Direct
}

// Direct is the gateway's relay for independently hosted direct MCP servers (SEE-144): the bounds
// on who may ask for a wake-up, how often, and how long an authorization outlives being used.
//
// Unlike the two blocks above, this has no on/off switch of its own. The phone-facing half is
// always served, because a phone may enroll and authorize a binding against a gateway whose
// operator has not finished setting up Firebase, and neither of those actually sends anything. The
// sending half follows BROADCAST_PUSH_CREDENTIALS: with no credential a server that asks for a
// wake-up is told the relay cannot send right now, which is both true and worth retrying.
//
// Nothing here is reachable without an operator's decision either way. A server can present any
// credential it likes and reach nothing until the operator registers it and enables relay for it
// in the admin surface, and a phone can enroll all it likes without any server being able to wake
// it until it authorizes one.
type Direct struct {
	// Sends per second per registered server, and the burst above it. It bounds what one
	// developer's backend can cost the deployment.
	ServerRate  float64
	ServerBurst int
	// Sends per second per binding, and the burst above it. This one is not an abuse bound: it is
	// the coalescing. A wake-up says "read your own server" and the next one says the same, so
	// above this rate the answer is that the device is already being woken.
	DeviceRate  float64
	DeviceBurst int
	// Sends per second across the whole deployment, and the burst above it. The ceiling that holds
	// whatever the per-server limits add up to.
	GlobalRate  float64
	GlobalBurst int
	// Phone-facing calls per second per caller address, and the burst above it. Enrolling needs no
	// credential — the caller has none yet — so this is what bounds it.
	EnrollRate  float64
	EnrollBurst int
	// How long one binding lasts before the phone renews it, and how long an installation may go
	// unauthenticated before it is forgotten. Both are what stops an abandoned grant from being
	// permanent, and neither needs anything to happen in order to take effect.
	BindingLifetime  time.Duration
	InstallationIdle time.Duration
	// How long an installation that has authorized nothing is kept. A real app binds within
	// seconds of enrolling; one that never does has granted nobody anything.
	UnboundGrace time.Duration
}

// Admin is the operator's password-protected administration, and an empty password hash is a
// complete answer in the same way an empty broker URL is: the listener is not opened, no route
// exists, and the gateway says at startup that it has no administrative surface. There is
// deliberately no unconfigured setup page — a first-run endpoint that grants administration to
// whoever finds it is the worst failure mode this surface could have, and not building it is the
// only way to be sure it is not reachable.
//
// The password hash is configuration rather than database state on purpose. It is what makes the
// documented recovery work: a deployment that lost its SQLite file still has an operator who can
// log in and register its publishers again, without a database console (SEE-141).
type Admin struct {
	// Where the admin UI listens. A third socket, for the same reason the publisher API is a
	// second one: a deployment can keep it off the internet entirely, and no routing mistake can
	// expose an administrative mutation through a feed read.
	Address string
	// The route prefix it is served under, so an ingress can forward one prefix and the pages can
	// link to themselves correctly behind it. It has no trailing slash.
	Path string
	// The operator's password, as internal/admin encodes one. Setting it is what turns the admin
	// surface on; nothing else here does anything without it.
	PasswordHash string
	// How long a login lasts. It is absolute rather than sliding.
	SessionLifetime time.Duration
	// Login attempts per second per caller, and the burst above it. Deliberately slow: there is one
	// password and one operator, and a person logging in takes seconds rather than milliseconds.
	LoginRate  float64
	LoginBurst int
	// The publisher API origin an external publisher can actually reach, which is what the admin
	// hands a developer. It defaults to PublicURL, because that is where both packaged ingresses
	// and the App Platform edge route PublisherService; an operator who keeps the publisher
	// listener on a private network sets it to the address they will give out. It is never a
	// container-local hostname unless the operator says so.
	PublisherURL string
}

// Defaults every setting that has one. They are deliberately modest: a gateway is a shared service,
// and a publisher that needs a higher rate is a conversation with its operator rather than a
// number that was set high enough to never come up.
const (
	DefaultReadAddress      = "127.0.0.1:8090"
	DefaultPublisherAddress = "127.0.0.1:8091"
	DefaultRetention        = 7 * 24 * time.Hour
	DefaultMaxProposals     = 200
	// Half a minute between check-ins, so a feed whose publisher stopped reads as offline within
	// about ninety seconds. It is a bounded cost the gateway can state: one tiny write per
	// publisher per interval, and only from a publisher that had nothing else to say.
	DefaultHeartbeat = 30 * time.Second
	// A day. A publisher renews well inside it (publisher-support/access renews at a third of what
	// it asked for), so a gateway that loses its publisher for an afternoon does not cut approved
	// devices off, and one that never hears again stops admitting them within a day.
	DefaultMostGrant    = 24 * time.Hour
	DefaultReadRate     = 20
	DefaultReadBurst    = 60
	DefaultPublishRate  = 2
	DefaultPublishBurst = 20
	// An hour is long enough that a phone in the foreground rarely renews, and short enough that a
	// grant which escaped stops mattering on its own. The broker ends the connection when it
	// expires and the listener asks for another, which is a path the client has to have working
	// anyway — every reconnect takes it.
	DefaultTicketLifetime = time.Hour
	// Enough for every feed a phone is plausibly subscribed to, on one connection. It is also the
	// bound on how much one ticket can cost the broker to honour, which is why it is a number and
	// not a hope.
	DefaultMostChannels = 32
	// One hint per topic per ten seconds, with five in hand. A publisher that republishes three
	// proposals at once wakes its subscribers once each, which is ordinary; one that publishes every
	// second wakes them six times a minute instead of sixty, and the documents it published are all
	// still there to be read. It is deliberately the kind of number an operator raises after a
	// conversation rather than one set high enough never to come up.
	DefaultPushRate  = 0.1
	DefaultPushBurst = 5
	// The direct relay (SEE-144). Two sends a second per server with twenty in hand matches the
	// publisher limit's shape, because it bounds the same kind of caller: a backend that answers a
	// burst of agent activity and then goes quiet. One wake-up every two seconds per device, with
	// five in hand, is the coalescing — a phone woken more often than that is being told the same
	// thing again. The global ceiling is what the deployment as a whole will spend on waking
	// phones, and the enrollment limit is per address because an enrolling caller has no identity
	// yet.
	DefaultRelayServerRate  = 2
	DefaultRelayServerBurst = 20
	DefaultRelayDeviceRate  = 0.5
	DefaultRelayDeviceBurst = 5
	DefaultRelayGlobalRate  = 50
	DefaultRelayGlobalBurst = 200
	DefaultRelayEnrollRate  = 1
	DefaultRelayEnrollBurst = 10
	// A month is long enough that an ordinary phone renews on a reconciliation it was going to run
	// anyway, and short enough that a device nobody has seen since stops being able to be woken
	// without anyone deciding anything. Two months of silence forgets the installation itself, and
	// a day is all an enrollment that authorized nothing is kept for.
	DefaultRelayBindingLifetime  = 30 * 24 * time.Hour
	DefaultRelayInstallationIdle = 60 * 24 * time.Hour
	DefaultRelayUnboundGrace     = 24 * time.Hour
	// The admin surface. The address is loopback like the other two, the route is the one the
	// ticket names, and an hour is the same bound a listener's ticket gets: long enough that an
	// operator finishes a registration without logging in twice, short enough that a session left
	// open stops mattering on its own.
	DefaultAdminAddress  = "127.0.0.1:8092"
	DefaultAdminPath     = "/admin"
	DefaultAdminLifetime = time.Hour
	// One attempt every ten seconds with five in hand. A person who mistypes a password twice
	// notices nothing; something working through a list gets 300 tries a day.
	DefaultAdminLoginRate  = 0.1
	DefaultAdminLoginBurst = 5
)

// Lookup is os.LookupEnv, injected so the tests configure a gateway without touching the process.
type Lookup func(name string) (string, bool)

// Load reads the environment and returns the configuration, or every problem it found. A caller
// that gets problems prints all of them and stops: a gateway that started with half a
// configuration would serve something nobody asked for.
func Load(lookup Lookup) (*Config, []string) {
	var problems []string
	note := func(format string, argument ...any) {
		problems = append(problems, fmt.Sprintf(format, argument...))
	}
	text := func(name, fallback string) string {
		value, set := lookup(name)
		if !set || strings.TrimSpace(value) == "" {
			return fallback
		}
		return strings.TrimSpace(value)
	}
	number := func(name string, fallback, least, most float64) float64 {
		raw := text(name, "")
		if raw == "" {
			return fallback
		}
		value, err := strconv.ParseFloat(raw, 64)
		if err != nil || value < least || value > most {
			note("%s must be a number between %g and %g", name, least, most)
			return fallback
		}
		return value
	}

	config := &Config{
		ReadAddress:      text("BROADCAST_READ_ADDRESS", DefaultReadAddress),
		PublisherAddress: text("BROADCAST_PUBLISHER_ADDRESS", DefaultPublisherAddress),
		DatabasePath:     text("BROADCAST_DATABASE_PATH", ""),
		DatabaseURL:      text("BROADCAST_DATABASE_URL", ""),
		Retention:        DefaultRetention,
		MaxProposals:     DefaultMaxProposals,
		Heartbeat:        DefaultHeartbeat,
		MostGrant:        DefaultMostGrant,
		ReadRate:         DefaultReadRate,
		ReadBurst:        DefaultReadBurst,
		PublishRate:      DefaultPublishRate,
		PublishBurst:     DefaultPublishBurst,
	}

	public := text("BROADCAST_PUBLIC_URL", "")
	switch {
	case public == "":
		note("BROADCAST_PUBLIC_URL must be set to this gateway's own origin, " +
			"for example https://feeds.example.com - a published manifest has to name it, " +
			"and the phone compares it with the feed reference it was added from")
	default:
		canonical, err := Origin(public)
		if err != nil {
			note("BROADCAST_PUBLIC_URL %v", err)
		} else {
			config.PublicURL = canonical
		}
	}
	// One store, named one way. Both set is refused rather than resolved by precedence: they are
	// two different authorities for the same state, and a deployment that set both has already
	// lost track of which one its publications are in. The image ships a BROADCAST_DATABASE_PATH
	// default, so a platform deployment that adds a URL clears the path in the same spec.
	switch {
	case config.DatabasePath == "" && config.DatabaseURL == "":
		note("BROADCAST_DATABASE_PATH must be set to the file the gateway keeps publications in, " +
			"for example /data/broadcast.db - or BROADCAST_DATABASE_URL to a Postgres database, " +
			"for a deployment whose filesystem does not survive the container")
	case config.DatabasePath != "" && config.DatabaseURL != "":
		note("BROADCAST_DATABASE_PATH and BROADCAST_DATABASE_URL must not both be set: " +
			"they are two authorities for the same state, and only one of them would hold it")
	}
	if config.ReadAddress == config.PublisherAddress {
		note("BROADCAST_READ_ADDRESS and BROADCAST_PUBLISHER_ADDRESS " +
			"must be different: feed reads and backend writes are separate listeners on purpose")
	}
	config.Retention = time.Duration(number("BROADCAST_RETENTION_HOURS",
		DefaultRetention.Hours(), 1, 24*365)) * time.Hour
	config.MaxProposals = int(number("BROADCAST_MAX_PROPOSALS", DefaultMaxProposals, 1, 10000))
	// Seconds rather than the hours and minutes the other durations take: a presence window is the
	// one setting here an operator tunes against how quickly a phone should notice, and both ends of
	// the range are shorter than a minute's granularity could say.
	config.Heartbeat = time.Duration(number("BROADCAST_HEARTBEAT_SECONDS",
		DefaultHeartbeat.Seconds(), 5, 3600)) * time.Second
	config.MostGrant = time.Duration(number("BROADCAST_MAX_GRANT_HOURS",
		DefaultMostGrant.Hours(), 1, 24*30)) * time.Hour
	config.ReadRate = number("BROADCAST_READ_RATE", DefaultReadRate, 0.1, 10000)
	config.ReadBurst = int(number("BROADCAST_READ_BURST", DefaultReadBurst, 1, 100000))
	config.PublishRate = number("BROADCAST_PUBLISH_RATE", DefaultPublishRate, 0.1, 10000)
	config.PublishBurst = int(number("BROADCAST_PUBLISH_BURST", DefaultPublishBurst, 1, 100000))
	for _, raw := range strings.Split(text("BROADCAST_TRUSTED_PROXIES", ""), ",") {
		value := strings.TrimSpace(raw)
		if value == "" {
			continue
		}
		prefix, err := netip.ParsePrefix(value)
		if err != nil {
			address, addressErr := netip.ParseAddr(value)
			if addressErr != nil {
				note("BROADCAST_TRUSTED_PROXIES must contain only IP addresses or CIDR prefixes")
				continue
			}
			prefix = netip.PrefixFrom(address, address.BitLen())
		}
		config.TrustedProxies = append(config.TrustedProxies, prefix.Masked())
	}

	// The stream, all of it or none of it. A URL with no keys would start a gateway that cannot
	// publish to its broker and cannot grant a listener, and the first sign of it would be an
	// outbox that never drains — so the three are one setting in three variables, and a partial
	// one is a problem at startup instead.
	stream := Stream{
		URL:            text("BROADCAST_STREAM_URL", ""),
		APIKey:         text("BROADCAST_STREAM_API_KEY", ""),
		TokenKey:       text("BROADCAST_STREAM_TOKEN_KEY", ""),
		TicketLifetime: DefaultTicketLifetime,
		MostChannels:   DefaultMostChannels,
	}
	switch {
	case stream.URL != "":
		canonical, err := reachable(stream.URL)
		if err != nil {
			note("BROADCAST_STREAM_URL %v", err)
		} else {
			stream.URL = canonical
		}
		if stream.APIKey == "" {
			note("BROADCAST_STREAM_API_KEY must be set when BROADCAST_STREAM_URL is: " +
				"the gateway publishes to the broker's API with it")
		}
		if stream.TokenKey == "" {
			note("BROADCAST_STREAM_TOKEN_KEY must be set when BROADCAST_STREAM_URL is, " +
				"to the same key the broker verifies connection tokens with: " +
				"the gateway signs a listener's ticket with it")
		}
	case stream.APIKey != "" || stream.TokenKey != "":
		note("BROADCAST_STREAM_URL must be set when BROADCAST_STREAM_API_KEY or " +
			"BROADCAST_STREAM_TOKEN_KEY is: without it nothing is fanned out")
	}
	stream.TicketLifetime = time.Duration(number("BROADCAST_TICKET_MINUTES",
		DefaultTicketLifetime.Minutes(), 1, 24*60)) * time.Minute
	stream.MostChannels = int(number("BROADCAST_MAX_CHANNELS", DefaultMostChannels, 1, 128))
	config.Stream = stream

	// The relay, on the same all-or-nothing terms and for the same reason (SEE-92). A credential
	// with nowhere to send to, or an endpoint with no credential, is a deployment that would look
	// like it hints and never does — and the only sign of it would be phones that never wake.
	relay := Relay{
		CredentialsPath: text("BROADCAST_PUSH_CREDENTIALS", ""),
		Endpoint:        text("BROADCAST_PUSH_ENDPOINT", ""),
		Environment:     strings.ToLower(text("BROADCAST_PUSH_ENVIRONMENT", "")),
		Rate:            DefaultPushRate,
		Burst:           DefaultPushBurst,
	}
	switch {
	case relay.CredentialsPath != "":
		if relay.Endpoint == "" {
			note("BROADCAST_PUSH_ENDPOINT must be set when BROADCAST_PUSH_CREDENTIALS is, " +
				"to the push API this deployment sends through, " +
				"for example https://fcm.googleapis.com")
		} else if canonical, err := reachable(relay.Endpoint); err != nil {
			note("BROADCAST_PUSH_ENDPOINT %v", err)
		} else {
			relay.Endpoint = canonical
		}
		if relay.Environment != "production" && relay.Environment != "sandbox" {
			note("BROADCAST_PUSH_ENVIRONMENT must be set when BROADCAST_PUSH_CREDENTIALS is, " +
				"to production or sandbox: it scopes every topic this deployment sends on, " +
				"so one Firebase project can serve both kinds of deployment")
		}
	case relay.Endpoint != "" || relay.Environment != "":
		note("BROADCAST_PUSH_CREDENTIALS must be set when BROADCAST_PUSH_ENDPOINT or " +
			"BROADCAST_PUSH_ENVIRONMENT is: without it no hint can be sent")
	}
	relay.Rate = number("BROADCAST_PUSH_RATE", DefaultPushRate, 0.001, 100)
	relay.Burst = int(number("BROADCAST_PUSH_BURST", DefaultPushBurst, 1, 1000))
	relay.Direct = Direct{
		ServerRate:  number("BROADCAST_RELAY_SERVER_RATE", DefaultRelayServerRate, 0.001, 10000),
		ServerBurst: int(number("BROADCAST_RELAY_SERVER_BURST", DefaultRelayServerBurst, 1, 100000)),
		DeviceRate:  number("BROADCAST_RELAY_DEVICE_RATE", DefaultRelayDeviceRate, 0.001, 10000),
		DeviceBurst: int(number("BROADCAST_RELAY_DEVICE_BURST", DefaultRelayDeviceBurst, 1, 100000)),
		GlobalRate:  number("BROADCAST_RELAY_GLOBAL_RATE", DefaultRelayGlobalRate, 0.001, 100000),
		GlobalBurst: int(number("BROADCAST_RELAY_GLOBAL_BURST", DefaultRelayGlobalBurst, 1, 1000000)),
		EnrollRate:  number("BROADCAST_RELAY_ENROLL_RATE", DefaultRelayEnrollRate, 0.001, 10000),
		EnrollBurst: int(number("BROADCAST_RELAY_ENROLL_BURST", DefaultRelayEnrollBurst, 1, 100000)),
		BindingLifetime: time.Duration(number("BROADCAST_RELAY_BINDING_HOURS",
			DefaultRelayBindingLifetime.Hours(), 1, 24*365)) * time.Hour,
		InstallationIdle: time.Duration(number("BROADCAST_RELAY_IDLE_HOURS",
			DefaultRelayInstallationIdle.Hours(), 1, 24*365)) * time.Hour,
		UnboundGrace: time.Duration(number("BROADCAST_RELAY_UNBOUND_HOURS",
			DefaultRelayUnboundGrace.Hours(), 1, 24*365)) * time.Hour,
	}
	// An installation has to outlive the authorizations it holds, or a phone would be forgotten
	// while its bindings were still current and would find itself unable to renew them. Saying so
	// here is better than discovering it as devices that quietly stop being woken.
	if relay.Direct.InstallationIdle < relay.Direct.BindingLifetime {
		note("BROADCAST_RELAY_IDLE_HOURS must be at least BROADCAST_RELAY_BINDING_HOURS: " +
			"an installation forgotten while its bindings are still valid cannot renew them")
	}
	if relay.Direct.UnboundGrace > relay.Direct.InstallationIdle {
		note("BROADCAST_RELAY_UNBOUND_HOURS must not exceed BROADCAST_RELAY_IDLE_HOURS: " +
			"an installation that authorized nothing is kept for less time, not more")
	}
	config.Relay = relay

	// The admin surface, on the same all-or-nothing terms as the two above (SEE-141). The password
	// hash is the switch: without it nothing is built, and with it everything else is checked here
	// rather than discovered by an operator who cannot log in.
	admin := Admin{
		Address:         text("BROADCAST_ADMIN_ADDRESS", DefaultAdminAddress),
		Path:            text("BROADCAST_ADMIN_PATH", DefaultAdminPath),
		PasswordHash:    text("BROADCAST_ADMIN_PASSWORD_HASH", ""),
		SessionLifetime: DefaultAdminLifetime,
		LoginRate:       DefaultAdminLoginRate,
		LoginBurst:      DefaultAdminLoginBurst,
		PublisherURL:    text("BROADCAST_ADMIN_PUBLISHER_URL", ""),
	}
	if admin.PasswordHash != "" {
		if _, err := credential.ParsePassword(admin.PasswordHash); err != nil {
			note("BROADCAST_ADMIN_PASSWORD_HASH %v", err)
		}
		path, err := AdminPath(admin.Path)
		if err != nil {
			note("BROADCAST_ADMIN_PATH %v", err)
		} else {
			admin.Path = path
		}
		for _, other := range []struct{ name, address string }{
			{"BROADCAST_READ_ADDRESS", config.ReadAddress},
			{"BROADCAST_PUBLISHER_ADDRESS", config.PublisherAddress},
		} {
			if admin.Address == other.address {
				note("BROADCAST_ADMIN_ADDRESS and %s must be different: "+
					"administration is a third listener, so a deployment can keep it off the "+
					"internet and no routing mistake can reach it through a feed read", other.name)
			}
		}
		switch {
		case admin.PublisherURL != "":
			canonical, err := reachable(admin.PublisherURL)
			if err != nil {
				note("BROADCAST_ADMIN_PUBLISHER_URL %v", err)
			} else {
				admin.PublisherURL = canonical
			}
		default:
			// Where both packaged ingresses and the App Platform edge route PublisherService.
			admin.PublisherURL = config.PublicURL
		}
		admin.SessionLifetime = time.Duration(number("BROADCAST_ADMIN_SESSION_MINUTES",
			DefaultAdminLifetime.Minutes(), 5, 24*60)) * time.Minute
		admin.LoginRate = number("BROADCAST_ADMIN_LOGIN_RATE", DefaultAdminLoginRate, 0.001, 100)
		admin.LoginBurst = int(number("BROADCAST_ADMIN_LOGIN_BURST", DefaultAdminLoginBurst, 1, 1000))
	} else if _, declared := lookup("BROADCAST_ADMIN_PASSWORD_HASH"); !declared {
		// A setting with no password behind it does nothing, and silently doing nothing is how an
		// operator comes to believe a surface is protected differently than it is.
		//
		// Declaring the hash and leaving it empty is the deliberate exception, and it is what a
		// deployment template does: the whole group is written down where an operator can see it,
		// the surface stays off, and turning it on is one secret rather than a second edit
		// somewhere else (deploy/feed/compose.yaml, deploy/seeker-gateway.yaml).
		for _, name := range []string{
			"BROADCAST_ADMIN_ADDRESS", "BROADCAST_ADMIN_PATH", "BROADCAST_ADMIN_SESSION_MINUTES",
			"BROADCAST_ADMIN_LOGIN_RATE", "BROADCAST_ADMIN_LOGIN_BURST",
			"BROADCAST_ADMIN_PUBLISHER_URL",
		} {
			if value, set := lookup(name); set && strings.TrimSpace(value) != "" {
				note("%s is set but BROADCAST_ADMIN_PASSWORD_HASH is not: "+
					"without it there is no administrative surface at all, and this setting "+
					"would do nothing", name)
			}
		}
	}
	config.Admin = admin

	if len(problems) > 0 {
		return nil, problems
	}
	return config, nil
}

// Enabled says whether this deployment has an administrative surface at all.
func (a Admin) Enabled() bool { return a.PasswordHash != "" }

// AdminPath is the canonical form of the route prefix the admin UI is served under: one absolute
// path, no trailing slash, no query and no fragment. It is a prefix rather than a host so that an
// existing ingress can forward it without a second certificate or a second name.
func AdminPath(raw string) (string, error) {
	value := strings.TrimSpace(raw)
	switch {
	case !strings.HasPrefix(value, "/"):
		return "", fmt.Errorf("must be an absolute path, for example /admin")
	case strings.ContainsAny(value, "?#"):
		return "", fmt.Errorf("must be a path with no query or fragment")
	case strings.Contains(value, "//"):
		return "", fmt.Errorf("must have no empty segment")
	}
	value = strings.TrimRight(value, "/")
	if value == "" {
		return "", fmt.Errorf("must not be the whole site: " +
			"a route boundary is what keeps administration apart from everything else")
	}
	for _, segment := range strings.Split(strings.TrimPrefix(value, "/"), "/") {
		if segment == "." || segment == ".." {
			return "", fmt.Errorf("must have no relative segment")
		}
		if escaped := url.PathEscape(segment); escaped != segment {
			return "", fmt.Errorf("must be made of ordinary path characters")
		}
	}
	return value, nil
}

var loopback = map[string]bool{"127.0.0.1": true, "localhost": true, "::1": true}

// Database is what the gateway holds its state in, as a line a log may carry. It is the file when
// there is one, and otherwise the host and database name of the URL — never its user and never its
// password, which is why this exists rather than a caller reading DatabaseURL and hoping.
//
// A URL that will not parse is reported as the fact that it will not parse. This is a log field,
// and the connection attempt is what refuses a bad URL properly.
func (c *Config) Database() string {
	if c.DatabasePath != "" {
		return c.DatabasePath
	}
	if c.DatabaseURL == "" {
		return ""
	}
	parsed, err := url.Parse(c.DatabaseURL)
	if err != nil {
		return "an unreadable database URL"
	}
	return "postgres " + parsed.Host + strings.TrimSuffix(parsed.Path, "/")
}

// Origin is the canonical form of a gateway origin, by the same rules the phone reads one by
// (servers/FeedReferences.gatewayUrlProblem and PairingCodes.normalizeServerUrl): HTTPS, or plain
// HTTP on a loopback host for development; a lowercase scheme and host; no default port; and no
// path, query, fragment or user information at all.
//
// A path is refused rather than kept because a gateway is addressed by origin: two paths on one
// host would be two gateways to a phone that compares the string, and one gateway to the operator
// who deployed it.
func Origin(raw string) (string, error) {
	parsed, err := url.Parse(raw)
	if err != nil {
		return "", fmt.Errorf("is not a URL: %w", err)
	}
	scheme := strings.ToLower(parsed.Scheme)
	host := strings.ToLower(parsed.Hostname())
	switch {
	case host == "":
		return "", fmt.Errorf("must name a host")
	case parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "":
		return "", fmt.Errorf("must have no user information, query or fragment")
	case strings.Trim(parsed.Path, "/") != "":
		return "", fmt.Errorf("must have no path: a gateway is addressed by origin")
	case scheme == "https":
	case scheme == "http" && loopback[host]:
	default:
		return "", fmt.Errorf("must use HTTPS; plain HTTP is allowed only on loopback, " +
			"for development")
	}
	port := parsed.Port()
	if (scheme == "https" && port == "443") || (scheme == "http" && port == "80") {
		port = ""
	}
	if port != "" {
		if number, err := strconv.Atoi(port); err != nil || number < 1 || number > 65535 {
			return "", fmt.Errorf("has a port that is not a port")
		}
	}
	// An IPv6 host is written in brackets in a URL, and the phone compares the written form.
	written := host
	if strings.Contains(host, ":") {
		written = "[" + host + "]"
	}
	if port != "" {
		written += ":" + port
	}
	return scheme + "://" + written, nil
}

// reachable is the canonical form of a URL the gateway calls, rather than one anybody calls it by
// (the broker's API, SEE-91). It is a different check from [Origin] on purpose, and the difference
// is who the address is for.
//
// A gateway origin is a public promise: a phone compares it with the feed reference it was added
// from, so plain HTTP is allowed only on loopback and there is no room for a path. A broker API URL
// is a link inside one deployment — a compose service name, a private host, a loopback port — that
// no phone ever sees and nothing compares. So any host may be named over plain HTTP here, and the
// reason to say so out loud is that it is a weaker rule: what keeps the broker's API key off the
// network is the network it is on, which is the deployment's job and is documented as such
// (feed-gateway/README.md).
//
// Still no path, query, fragment or user information: this is an origin the gateway appends its own
// paths to, and a base URL carrying half a request would produce requests nobody meant.
func reachable(raw string) (string, error) {
	parsed, err := url.Parse(raw)
	if err != nil {
		return "", fmt.Errorf("is not a URL: %w", err)
	}
	scheme := strings.ToLower(parsed.Scheme)
	host := strings.ToLower(parsed.Hostname())
	switch {
	case host == "":
		return "", fmt.Errorf("must name a host")
	case scheme != "http" && scheme != "https":
		return "", fmt.Errorf("must use HTTP or HTTPS")
	case parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "":
		return "", fmt.Errorf("must have no user information, query or fragment")
	case strings.Trim(parsed.Path, "/") != "":
		return "", fmt.Errorf("must have no path: the gateway appends its own")
	}
	port := parsed.Port()
	if port != "" {
		if number, err := strconv.Atoi(port); err != nil || number < 1 || number > 65535 {
			return "", fmt.Errorf("has a port that is not a port")
		}
	}
	written := host
	if strings.Contains(host, ":") {
		written = "[" + host + "]"
	}
	if port != "" {
		written += ":" + port
	}
	return scheme + "://" + written, nil
}
