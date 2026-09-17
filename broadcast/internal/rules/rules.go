// Package rules holds what the broadcast gateway will accept, as pure functions over documents
// (SEE-90, docs/wiki/broadcast-gateway.md).
//
// Every rule here has a twin on the phone. `proposals/ProposalValidation.kt` and
// `servers/ManifestValidation.kt` apply the same ones to the same documents, because neither side
// trusts the other: the gateway refuses a document no phone could use, and the phone refuses one
// the gateway should not have relayed. Where the two could drift — an identity's shape, a text
// bound, whether a revision can be ordered — this file says so and keeps the phone's answer, since
// the phone is the side that has to show the owner something.
//
// Nothing in this package reads a database, a clock, a request or a credential. It takes a document
// and what the gateway already holds, and answers with the one rule that stopped it.
package rules

import (
	"regexp"
	"strings"
	"unicode"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1"
)

// The contract this gateway speaks with a phone, which is ServerManifest.protocol_version. It is
// the same number the phone's SERVER_PROTOCOL is, and a manifest that names another version is
// refused rather than relayed: a publisher cannot claim a contract that the thing serving its
// documents does not implement.
const Protocol uint32 = 1

// A document is bounded data: this much of it, and no more. Each of these is the phone's own
// constant (MAX_REQUIRED_PLUGINS, MAX_SERVER_NAME_BYTES, MAX_PROPOSAL_VALUES,
// MAX_PROPOSAL_TEXT_BYTES, MAX_PROPOSAL_NOTE_BYTES), repeated here because the gateway has to be
// able to refuse before the phone ever sees it.
const (
	MaxRequiredPlugins = 16
	MaxNameBytes       = 64
	MaxValues          = 32
	MaxValueTextBytes  = 512
	MaxNoteBytes       = 1024
)

// MaxRevision is the highest revision the gateway will accept. A revision is a uint64 on the wire,
// and the phone reads one into a signed 64-bit Long: above this it arrives there as a negative
// number and is refused as unorderable. The store keeps revisions as SQLite integers, which are
// signed too, so both sides of this service have the same limit — and a publisher learns about it
// here rather than by watching every phone ignore its proposal.
const MaxRevision uint64 = 1<<63 - 1

// Fault is the one rule a document broke: a problem code, the field it was about when it was about
// one, and what the gateway holds when that is the point. A caller gets it as a Connect error
// detail (internal/gateway/errors.go).
type Fault struct {
	Problem gatewayv1.GatewayProblem
	Field   string
	Held    uint64
}

func fault(problem gatewayv1.GatewayProblem, field string) *Fault {
	return &Fault{Problem: problem, Field: field}
}

// Expectation is what the gateway knows before it reads a document: which server the caller
// publishes as, and this gateway's own origin. Both come from the deployment and the credential,
// never from the document — that is what makes "a publisher may name only its own channel" a rule
// rather than a hope.
type Expectation struct {
	// The server ID the presented credential was issued for.
	ServerID string
	// This gateway's canonical public origin (config.PublicURL), which a published manifest must
	// name character for character, because the phone compares it with the feed reference it was
	// added from the same way.
	GatewayURL string
}

// Decision is what a publication turned out to be.
type Decision int

const (
	// The gateway holds something new: a first publication, or a higher revision with different
	// content. Subscribers are notified.
	Stored Decision = iota
	// The gateway already held exactly this. Nothing is written and nothing is notified, so a
	// retry — which is the ordinary case for a publisher that lost its answer — is not an event.
	Unchanged
)

// ChannelFor is the one channel a server owns. It is the phone's `channelFor` (servers/
// FeedReference.kt) and Centrifugo's channel name (SEE-91), and there is exactly one per server so
// that a grant needs no separate record: a publisher's credential names its server, and its
// server names its channel.
func ChannelFor(serverID string) string { return channelPrefix + serverID }

// ServerOf is the server a channel belongs to, or "" when the name is not a channel of this
// gateway's. It is ChannelFor read backwards, and it exists because a notice and a page request
// both carry the channel while a manifest is keyed by the server.
func ServerOf(channel string) string {
	id, found := strings.CutPrefix(channel, channelPrefix)
	if !found || !IsID(id) {
		return ""
	}
	return id
}

const channelPrefix = "server/"

var (
	uuidPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
	idPattern   = regexp.MustCompile(`^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$`)
)

// IsID is the shape of every server and proposal identity: a lowercase UUID, which is the phone's
// isConnectionId.
func IsID(value string) bool { return uuidPattern.MatchString(value) }

// IsPluginID is a plugin's stable name — lowercase dot-separated segments, such as "jupiter.swap".
// It is a name and nothing loadable, here as much as on the phone.
func IsPluginID(value string) bool {
	return len(value) >= 1 && len(value) <= 128 && idPattern.MatchString(value) &&
		strings.Contains(value, ".")
}

// IsOperation is an operation name at the protocol's own level ("swap"), and the shape of a term's
// key as well: the phone's isOperationId, used for both for the same reason it is there.
func IsOperation(value string) bool {
	return len(value) >= 1 && len(value) <= 64 && idPattern.MatchString(value)
}

// printable is the phone's own rule for text a person will read: short enough in UTF-8 bytes, no
// control characters, and nothing hiding in leading or trailing whitespace. A line break counts as
// text in a note and as a control character in a name, which is the difference between prose and a
// label.
func printable(text string, mostBytes int, allowNewline bool) bool {
	if len(text) > mostBytes {
		return false
	}
	if strings.TrimSpace(text) != text {
		return false
	}
	for _, r := range text {
		if r == '\n' && allowNewline {
			continue
		}
		// Java's Character.isISOControl, which is what the phone applies.
		if r <= 0x1f || (r >= 0x7f && r <= 0x9f) {
			return false
		}
		// Go and Java disagree about a few exotic separators; refusing every one either of them
		// would call whitespace keeps the gateway from accepting a name the phone then refuses.
		if unicode.IsSpace(r) && r != ' ' && r != '\n' {
			return false
		}
	}
	return true
}
