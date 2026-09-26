// Package manifest is what a publisher template says about itself, and the reference a phone adds
// it from (SEE-88, SEE-95).
//
// A manifest is bounded declarative data: the server's lasting ID, the phone–server contract it
// speaks, a settings revision, the mode, the bundled plugins its operations need, the environments
// it serves, and a name that is never believed. There is no field in it that installs code, asks
// for a permission, carries a policy or names a wallet endpoint — what a phone will do with this
// server is decided by the build it is running and by its owner, never by this document.
//
// A template publishes a **gateway feed** manifest and nothing else. A direct manifest carries a
// URL, and the gateway refuses to relay one: relaying it would let a publisher point a phone at an
// address of its choosing (docs/wiki/server-manifests.md).
package manifest

import (
	"crypto/sha256"
	"encoding/hex"
	"net/url"

	"google.golang.org/protobuf/proto"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/environment"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// Protocol is the phone–server contract this template speaks: version 1 is Stage 7.1's feed
// contract. It is the gateway's `rules.Protocol` and the phone's `SERVER_PROTOCOL`, and a manifest
// naming another version is refused by the gateway rather than relayed.
const Protocol uint32 = 1

// Settings are the parts of a manifest that come from the deployment rather than from a document:
// who this publisher is, which gateway serves it, what it proposes and what it promises.
type Settings struct {
	ServerID string
	// The gateway's own canonical origin. It must be the origin that gateway publishes as
	// (BROADCAST_PUBLIC_URL), because the phone compares the two character for character.
	GatewayURL string
	// Exactly one promise, and a phone refuses to treat a server as supported in an environment
	// the server does not name: the two are different promises about what happens when the owner
	// approves (SEE-97).
	Environment environment.Environment
	// The bundled plugin the template's operation needs, from the kind it registered.
	Requirement signals.Requirement
	// The name the server calls itself, for the connection's default label. The owner can rename
	// any connection, and their name is the one the app shows.
	DisplayName string
	// AuthOrigin, when set, makes this a restricted feed (SEE-156): only devices this publisher
	// approved may read it, and they prove who they are at this origin. It must be the origin the
	// gateway's operator registered, and the gateway refuses the manifest otherwise. Empty is a
	// public feed, whose manifest is byte for byte what it always was.
	AuthOrigin string
}

// Restricted says whether these settings describe a restricted feed.
func (s Settings) Restricted() bool { return s.AuthOrigin != "" }

// Document is the manifest as it will be published, at the revision the store holds for it.
func Document(settings Settings, revision uint64) *serverv1.ServerManifest {
	var access *serverv1.FeedAccess
	if settings.Restricted() {
		access = &serverv1.FeedAccess{
			Policy:     serverv1.FeedAccessPolicy_FEED_ACCESS_POLICY_RESTRICTED,
			AuthOrigin: settings.AuthOrigin,
		}
	}
	return &serverv1.ServerManifest{
		ServerId:         settings.ServerID,
		ProtocolVersion:  Protocol,
		SettingsRevision: revision,
		Mode:             serverv1.ConnectionMode_CONNECTION_MODE_GATEWAY_FEED,
		RequiredPlugins: []*serverv1.PluginRequirement{{
			PluginId:    settings.Requirement.PluginID,
			MinContract: settings.Requirement.MinContract,
			MaxContract: settings.Requirement.MostContract,
		}},
		// Exactly one, always. A deployment serves one environment, and a second one is a second
		// deployment with its own server ID and its own database (publisher-support/store). An environment
		// that came from nowhere is published as unspecified rather than as production, and the
		// gateway refuses it (publisher-support/environment).
		Environments: []serverv1.ServerEnvironment{settings.Environment.Wire()},
		DisplayName:  settings.DisplayName,
		Reference: &serverv1.ServerManifest_Feed{Feed: &serverv1.GatewayFeed{
			GatewayUrl: settings.GatewayURL,
			Channel:    signals.ChannelFor(settings.ServerID),
			Access:     access,
		}},
	}
}

// Fingerprint is the content of a manifest with its revision left out, so that the revision can be
// what it is for: a number that moves when the settings move.
//
// A template that bumped it on every start would make every phone re-read a manifest that had not
// changed, and a template that never bumped it would leave them reading a stale one. Publishing
// the same revision again is answered `UNCHANGED` by the gateway, which is why a restart is not an
// event (docs/protocol.md#publisherservice).
func Fingerprint(settings Settings) string {
	document := Document(settings, 0)
	bytes, err := proto.MarshalOptions{Deterministic: true}.Marshal(document)
	if err != nil {
		panic("manifest: a manifest built here did not marshal: " + err.Error())
	}
	sum := sha256.Sum256(bytes)
	return hex.EncodeToString(sum[:])
}

// Reference is how a phone adds this feed: `seekervault://feed?v=1&gateway=…&server=…`
// (servers/FeedReference.kt).
//
// It carries no secret, because there is nothing to authenticate. A feed is a broadcast: the phone
// subscribes through the gateway, this server is never contacted and learns nothing about the
// phone — so a reference can be printed in a README, put in a QR code, or posted publicly, and
// holding one grants nothing.
func Reference(gatewayURL, serverID string) string {
	return "seekervault://feed?v=1&gateway=" + url.QueryEscape(gatewayURL) +
		"&server=" + url.QueryEscape(serverID)
}

// ReferenceOf is the feed reference for these settings. A restricted feed's reference says so
// (`&access=restricted`), which is a hint and never an authority: the phone reads the policy and
// the authentication origin from the manifest the gateway serves, and refuses a feed whose manifest
// is less restricted than its reference claims — so a link cannot downgrade a feed, and it carries
// no origin a phone would send anything to (SEE-156).
func ReferenceOf(settings Settings) string {
	reference := Reference(settings.GatewayURL, settings.ServerID)
	if settings.Restricted() {
		reference += "&access=restricted"
	}
	return reference
}
