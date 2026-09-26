package rules

import (
	"slices"

	"google.golang.org/protobuf/proto"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
)

// Manifest checks what a publisher says about itself and returns the document the gateway will
// store, or the one rule it broke.
//
// The order is the phone's: what the server claims to be, then where it claims to be, then what it
// asks of a build. The two rules that are the gateway's own come first among the "where" checks and
// are the reason this method is authenticated at all:
//
//   - **A feed, never a direct server.** A direct manifest carries a URL. Relaying one would let a
//     publisher hand every subscribed phone an address of its choosing, and the phone's own check
//     (the origin must equal the one it paired with) is the only thing that would stop it. The
//     gateway does not rely on that: it refuses to hold one.
//   - **This gateway's own origin, and the publisher's own channel.** A phone compares both with
//     the feed reference it was added from, character for character, so a document that names
//     anything else is one no phone could use — and one that names another publisher's channel is
//     a claim on their audience.
//
// What comes back is rebuilt field by field from what was validated, not the message that arrived:
// an unknown field cannot be relayed to a subscriber by a gateway that never stores one
// (docs/wiki/feed-gateway.md#what-cannot-pass-through).
func Manifest(message *serverv1.ServerManifest, expect Expectation) (*serverv1.ServerManifest, *Fault) {
	if message == nil {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NOT_A_FEED, "manifest")
	}
	if message.GetProtocolVersion() != Protocol {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_PROTOCOL, "protocol_version")
	}
	if !IsID(message.GetServerId()) {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ID, "server_id")
	}
	if message.GetServerId() != expect.ServerID {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_SERVER, "server_id")
	}
	var feedReference *serverv1.GatewayFeed
	switch message.GetMode() {
	case serverv1.ConnectionMode_CONNECTION_MODE_GATEWAY_FEED:
		feed := message.GetFeed()
		if feed == nil || message.GetDirect() != nil {
			return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NOT_A_FEED, "reference")
		}
		if feed.GetGatewayUrl() != expect.GatewayURL {
			return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_GATEWAY, "feed.gateway_url")
		}
		if feed.GetChannel() != ChannelFor(message.GetServerId()) {
			return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_FOREIGN_CHANNEL, "feed.channel")
		}
		feedReference = &serverv1.GatewayFeed{
			GatewayUrl: expect.GatewayURL, Channel: ChannelFor(message.GetServerId())}
	default:
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NOT_A_FEED, "mode")
	}
	revision := message.GetSettingsRevision()
	if revision == 0 || revision > MaxRevision {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_REVISION, "settings_revision")
	}
	if len(message.GetRequiredPlugins()) > MaxRequiredPlugins {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_PLUGINS, "required_plugins")
	}
	required := make([]*serverv1.PluginRequirement, 0, len(message.GetRequiredPlugins()))
	seen := make(map[string]bool, len(message.GetRequiredPlugins()))
	for _, requirement := range message.GetRequiredPlugins() {
		id := requirement.GetPluginId()
		least, most := requirement.GetMinContract(), requirement.GetMaxContract()
		if !IsPluginID(id) || least < 1 || most < least {
			return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_PLUGIN, "required_plugins")
		}
		if seen[id] {
			return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_DUPLICATE_PLUGIN, "required_plugins")
		}
		seen[id] = true
		required = append(required, &serverv1.PluginRequirement{
			PluginId:    id,
			MinContract: least,
			MaxContract: most,
		})
	}
	environments := make([]serverv1.ServerEnvironment, 0, len(message.GetEnvironments()))
	named := make(map[serverv1.ServerEnvironment]bool, len(message.GetEnvironments()))
	for _, environment := range message.GetEnvironments() {
		switch environment {
		case serverv1.ServerEnvironment_SERVER_ENVIRONMENT_PRODUCTION,
			serverv1.ServerEnvironment_SERVER_ENVIRONMENT_SANDBOX:
		default:
			// Unspecified, or an environment from a later version of the format. Either way the
			// gateway does not know what promise it names, and it does not pick one.
			return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ENVIRONMENT, "environments")
		}
		if named[environment] {
			return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ENVIRONMENT, "environments")
		}
		named[environment] = true
		environments = append(environments, environment)
	}
	if len(environments) == 0 {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_ENVIRONMENT, "environments")
	}
	// A name is a label rather than prose, so a line break is a control character here.
	if !printable(message.GetDisplayName(), MaxNameBytes, false) {
		return nil, fault(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_NAME, "display_name")
	}
	rebuilt := &serverv1.ServerManifest{
		ServerId:         message.GetServerId(),
		ProtocolVersion:  Protocol,
		SettingsRevision: revision,
		Mode:             message.GetMode(),
		RequiredPlugins:  required,
		Environments:     environments,
		DisplayName:      message.GetDisplayName(),
	}
	rebuilt.Reference = &serverv1.ServerManifest_Feed{Feed: feedReference}
	return rebuilt, nil
}

// AdvanceManifest says what a manifest publication is, given what the gateway already holds:
// something new, the same thing again, or a contradiction.
//
// A revision is the publisher's promise about its content, and all three answers come from taking
// that literally. The same revision with the same content is a retry — the ordinary case for a
// template that was restarted or lost its answer — and writes nothing. The same revision with
// different content is refused rather than resolved, because the gateway has no way to know which
// of the two the publisher meant, and every phone caching by revision would believe whichever it
// happened to read. A lower revision is refused because a late retry must not restore settings the
// publisher has moved past.
//
// One field cannot move at all (SEE-97). A higher revision may change anything a publisher may
// change — the plugins it needs, the name it calls itself — but not the environments it serves: an
// environment is what a server promises when the owner approves, and a promise that a higher
// revision can raise is not one. A phone that added a demonstration would be moved to real money
// by a document nobody looked at. A second environment is a second deployment, with its own server
// ID, credential and database, which is the same rule the publisher's own database stamp keeps at
// its end (docs/wiki/environments.md).
func AdvanceManifest(held, next *serverv1.ServerManifest) (Decision, *Fault) {
	if held == nil {
		return Stored, nil
	}
	if !sameEnvironments(held.GetEnvironments(), next.GetEnvironments()) {
		return Stored, &Fault{
			Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_ENVIRONMENT,
			Field:   "environments",
			Held:    held.GetSettingsRevision(),
		}
	}
	// The mode is the transport relationship a phone confirmed, not mutable presentation.
	if held.GetMode() != next.GetMode() {
		return Stored, &Fault{
			Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_REVISION_CONFLICT,
			Field:   "mode",
			Held:    held.GetSettingsRevision(),
		}
	}
	switch {
	case next.GetSettingsRevision() < held.GetSettingsRevision():
		return Stored, &Fault{
			Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_STALE_REVISION,
			Field:   "settings_revision",
			Held:    held.GetSettingsRevision(),
		}
	case next.GetSettingsRevision() == held.GetSettingsRevision():
		if proto.Equal(held, next) {
			return Unchanged, nil
		}
		return Stored, &Fault{
			Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_REVISION_CONFLICT,
			Field:   "settings_revision",
			Held:    held.GetSettingsRevision(),
		}
	default:
		return Stored, nil
	}
}

// sameEnvironments is whether two manifests promise the same thing, compared as sets: the order a
// publisher wrote them in is not part of the promise, and a document that reorders them is a retry
// rather than a change of mind. Both have been through [Manifest], which refuses an empty set, an
// unknown value and a repeated one, so length and membership are the whole comparison.
func sameEnvironments(held, next []serverv1.ServerEnvironment) bool {
	if len(held) != len(next) {
		return false
	}
	for _, environment := range held {
		if !slices.Contains(next, environment) {
			return false
		}
	}
	return true
}
