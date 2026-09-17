// Package config reads the broadcast gateway's settings from the environment (SEE-90).
//
// It follows the sidecar's rules, because an operator running both should not have to learn two
// habits: every setting is one environment variable, a problem names the variable rather than
// guessing a value, and every problem is reported at once so a first start fixes all of them in one
// pass. Nothing here has a default that opens something: the listeners bind loopback unless told
// otherwise, and the two settings a deployment cannot get wrong — the origin it publishes as and
// the file it keeps documents in — have no default at all.
//
// No credential is configured here. A publisher's credential is created by broadcastctl and stored
// as a hash (internal/store), so there is no token in the environment to leak into a process list,
// a log line, or a compose file.
package config

import (
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"
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
	// The SQLite file. It is the authority for everything the gateway serves.
	DatabasePath string
	// How long past its expiry a proposal is still served before it is swept.
	Retention time.Duration
	// The most proposals one channel may hold at once.
	MaxProposals int
	// Reads per second and the burst above it, per remote address.
	ReadRate  float64
	ReadBurst int
	// Publications per second and the burst above it, per publisher.
	PublishRate  float64
	PublishBurst int
}

// Defaults every setting that has one. They are deliberately modest: a gateway is a shared service,
// and a publisher that needs a higher rate is a conversation with its operator rather than a
// number that was set high enough to never come up.
const (
	DefaultReadAddress      = "127.0.0.1:8090"
	DefaultPublisherAddress = "127.0.0.1:8091"
	DefaultRetention        = 7 * 24 * time.Hour
	DefaultMaxProposals     = 200
	DefaultReadRate         = 20
	DefaultReadBurst        = 60
	DefaultPublishRate      = 2
	DefaultPublishBurst     = 20
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
		Retention:        DefaultRetention,
		MaxProposals:     DefaultMaxProposals,
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
	if config.DatabasePath == "" {
		note("BROADCAST_DATABASE_PATH must be set to the file the gateway keeps publications in, " +
			"for example /data/broadcast.db")
	}
	if config.ReadAddress == config.PublisherAddress {
		note("BROADCAST_READ_ADDRESS and BROADCAST_PUBLISHER_ADDRESS must be different: " +
			"the read API and the publisher API are separate listeners on purpose")
	}
	config.Retention = time.Duration(number("BROADCAST_RETENTION_HOURS",
		DefaultRetention.Hours(), 1, 24*365)) * time.Hour
	config.MaxProposals = int(number("BROADCAST_MAX_PROPOSALS", DefaultMaxProposals, 1, 10000))
	config.ReadRate = number("BROADCAST_READ_RATE", DefaultReadRate, 0.1, 10000)
	config.ReadBurst = int(number("BROADCAST_READ_BURST", DefaultReadBurst, 1, 100000))
	config.PublishRate = number("BROADCAST_PUBLISH_RATE", DefaultPublishRate, 0.1, 10000)
	config.PublishBurst = int(number("BROADCAST_PUBLISH_BURST", DefaultPublishBurst, 1, 100000))

	if len(problems) > 0 {
		return nil, problems
	}
	return config, nil
}

var loopback = map[string]bool{"127.0.0.1": true, "localhost": true, "::1": true}

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
