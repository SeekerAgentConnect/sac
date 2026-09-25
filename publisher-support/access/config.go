package access

import (
	"net"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/config"
)

// Config is a restricted feed's deployment settings, read beside publisher-support/config's.
type Config struct {
	// AuthOrigin is PUBLISHER_AUTH_ORIGIN: the public origin phones reach this publisher's
	// authentication endpoint at, exactly as the gateway's operator registered it.
	AuthOrigin string
	// AuthAddress is PUBLISHER_AUTH_ADDRESS: a listener of its own for the endpoint, so the
	// publisher's token-protected API can stay on loopback while this one is published. Empty — the
	// default — serves /access/v1 on the API's own listener beside the token-protected /v1, which is
	// what a platform that gives a service one public port needs (deploy/signals-demo.yaml).
	AuthAddress string
	// GrantLifetime is PUBLISHER_ACCESS_GRANT_HOURS: how long a grant runs before this publisher
	// renews it, which is also the bound on how long access outlives this publisher's reach.
	GrantLifetime time.Duration
	// InvitationLifetime is PUBLISHER_ACCESS_INVITATION_MINUTES.
	InvitationLifetime time.Duration
	// ChallengesPerHour is PUBLISHER_ACCESS_CHALLENGES_PER_HOUR, per address.
	ChallengesPerHour int
}

// Load reads a restricted feed's settings. Every problem is noted on the reader, so a first start
// is fixed in one pass.
func Load(reader *config.Reader) Config {
	settings := Config{
		AuthAddress: reader.Text("PUBLISHER_AUTH_ADDRESS", ""),
		GrantLifetime: time.Duration(reader.Whole("PUBLISHER_ACCESS_GRANT_HOURS", 6, 1, 24*30,
			"how many hours a device's gateway grant runs before this publisher renews it")) * time.Hour,
		InvitationLifetime: time.Duration(reader.Whole("PUBLISHER_ACCESS_INVITATION_MINUTES", 5, 1, 60,
			"how many minutes an approved device has to redeem its invitation")) * time.Minute,
		ChallengesPerHour: reader.Whole("PUBLISHER_ACCESS_CHALLENGES_PER_HOUR", 60, 1, 100000,
			"how many wallet challenges one address may ask for in an hour"),
	}
	raw := reader.Text("PUBLISHER_AUTH_ORIGIN", "")
	if raw == "" {
		reader.Note("PUBLISHER_AUTH_ORIGIN is required for a restricted feed: the HTTPS origin " +
			"phones reach its /access/v1 endpoint at, as the gateway's operator registered it " +
			"(feed-gatewayctl access --access restricted --auth-origin ...)")
	} else if origin, err := config.Origin(raw); err != nil {
		reader.Note("PUBLISHER_AUTH_ORIGIN %v", err)
	} else {
		settings.AuthOrigin = origin
	}
	if settings.AuthAddress == "" {
		return settings
	}
	if _, _, err := net.SplitHostPort(settings.AuthAddress); err != nil {
		reader.Note("PUBLISHER_AUTH_ADDRESS must be host:port: %v", err)
	}
	return settings
}
