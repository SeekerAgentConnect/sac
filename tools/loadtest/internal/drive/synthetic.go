package drive

import (
	"fmt"
	"hash/fnv"
	"math/rand/v2"
	"strings"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"

	proposalv1 "github.com/BrRenat/SeekerAgentWallet/loadtest/internal/gen/seekervault/proposal/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/loadtest/internal/gen/seekervault/server/v1"
)

// The documents a run publishes (SEE-99).
//
// Synthetic, and only synthetic: SEE-99 says so, and the reason is worth stating rather than
// citing. A load run's documents have to be many, identical in shape, and free — a real copy-trading
// or prediction document would mean a provider, a market that is open, and a rate limit that is
// somebody else's. So these are proposals the gateway's own rules accept (internal/rules) and the
// phone's own validators would accept, carrying nothing but their own identity and enough padding
// to be the size the profile asked for.
//
// They are **deterministic**. Every identifier and every byte of padding comes from a counter and
// the run's seed, so two runs of the same profile publish the same documents in the same order —
// which is what makes a report reproducible rather than merely repeatable, and what lets a run that
// found a bottleneck be run again with one thing changed.

// The operation and plugin a synthetic proposal names. They are spelled the way the rules require
// (lowercase, dotted) and they are deliberately not one of the real operations: nothing in this
// run is a swap, an order, or anything a wallet would be asked to sign.
const (
	Operation = "loadtest.publish"
	Plugin    = "loadtest.synthetic"
)

// Synthetic makes one publisher's documents.
type Synthetic struct {
	serverID string
	gateway  string
	payload  int
	seed     uint64
}

// NewSynthetic builds a document maker for one publisher. `payload` is the document size the
// profile asked for, in bytes; the padding is trimmed to what the rules allow and the report states
// the size actually produced rather than the one requested.
func NewSynthetic(serverID, gatewayURL string, payload int, seed uint64) *Synthetic {
	return &Synthetic{serverID: serverID, gateway: gatewayURL, payload: payload, seed: seed}
}

// Channel is the one channel this publisher owns.
func (s *Synthetic) Channel() string { return "server/" + s.serverID }

// Manifest is the settings document, which has to be published before anybody can subscribe.
//
// Sandbox, always. A load run must never look like a production publisher to a phone that somehow
// read it, and SEE-97's environment model is what says so out loud in a document.
func (s *Synthetic) Manifest(revision uint64, name string) *serverv1.ServerManifest {
	return &serverv1.ServerManifest{
		ServerId:         s.serverID,
		ProtocolVersion:  1,
		SettingsRevision: revision,
		Mode:             serverv1.ConnectionMode_CONNECTION_MODE_GATEWAY_FEED,
		Environments: []serverv1.ServerEnvironment{
			serverv1.ServerEnvironment_SERVER_ENVIRONMENT_SANDBOX,
		},
		DisplayName: name,
		RequiredPlugins: []*serverv1.PluginRequirement{
			{PluginId: Plugin, MinContract: 1, MaxContract: 1},
		},
		Reference: &serverv1.ServerManifest_Feed{
			Feed: &serverv1.GatewayFeed{
				GatewayUrl: s.gateway,
				Channel:    s.Channel(),
			},
		},
	}
}

// Proposal is one document, at one revision.
//
// `created` is fixed for a given proposal identity because the rules say it cannot move: a
// publication that changes it is describing a different proposal under the same ID
// (rules.AdvanceProposal). So a republication passes the same instant back in.
func (s *Synthetic) Proposal(
	id string, revision uint64, created time.Time, life time.Duration,
) *proposalv1.Proposal {
	created = created.UTC().Truncate(time.Second)
	proposal := &proposalv1.Proposal{
		ServerId:   s.serverID,
		Channel:    s.Channel(),
		ProposalId: id,
		Revision:   revision,
		Operation:  Operation,
		PluginId:   Plugin,
		Status:     proposalv1.ProposalStatus_PROPOSAL_STATUS_OPEN,
		CreatedAt:  timestamppb.New(created),
		// The revision is in the update time as seconds, so two revisions of one proposal are
		// different documents by content as well as by number — which is what stops the gateway
		// answering `unchanged` to a republication the run is timing.
		UpdatedAt: timestamppb.New(created.Add(time.Duration(revision) * time.Second)),
		ExpiresAt: timestamppb.New(created.Add(life)),
	}
	pad(proposal, s.payload, s.seed)
	return proposal
}

// The bounds the rules put on the text a document may carry (internal/rules/rules.go). They are
// duplicated here rather than imported, for the reason every other number in this harness is: the
// gateway's package is the gateway's, and a harness that read its constants could not notice the
// two disagreeing. `TestSyntheticFitsTheRules` is what notices.
const (
	mostNoteBytes      = 1024
	mostValues         = 32
	mostValueTextBytes = 512
)

// MostPayload is the largest document this maker can produce: every value at its limit, plus a full
// note. A profile asking for more gets this, and the report says what it got.
const MostPayload = mostValues*mostValueTextBytes + mostNoteBytes

// A value's key is a term key, and a term key is an operation name: dot-separated segments that
// each begin with a lowercase letter (rules.IsOperation). `loadtest.publish.0` is **not** one —
// a segment cannot start with a digit — which is the kind of thing a harness discovers by having
// every document refused, so the padded keys are `…pad0` and a test checks them against the rule
// rather than against a guess.
//
// pad fills a proposal out to roughly `size` bytes with text that reads as what it is.
//
// The padding is not random bytes. It is printable ASCII, because the rules refuse control
// characters and anything either Go or Java would call whitespace — and because a person reading a
// failure in a broker's log should be able to see at a glance that the document was a test's.
func pad(proposal *proposalv1.Proposal, size int, seed uint64) {
	if size <= 0 {
		return
	}
	// What the document already costs: the identifiers, the times and the field tags. It does not
	// have to be exact — the report measures the serialized size of what was actually sent — but
	// starting from roughly the right place keeps a 1 KiB profile from producing 1.2 KiB.
	const overhead = 180
	remaining := min(size, MostPayload) - overhead
	if remaining <= 0 {
		return
	}
	source := rand.New(rand.NewPCG(seed, uint64(len(proposal.GetProposalId()))))
	note := min(remaining, mostNoteBytes)
	proposal.PublisherNote = text(source, note)
	remaining -= note
	for index := 0; remaining > 0 && index < mostValues; index++ {
		// Each value costs its key and two field tags as well as its text.
		this := min(remaining, mostValueTextBytes)
		key := fmt.Sprintf("%s.pad%d", Operation, index)
		proposal.Values = append(proposal.Values, &proposalv1.ProposalValue{
			Key:  key,
			Text: text(source, this),
		})
		remaining -= this + len(key) + 4
	}
}

// text is `n` bytes of printable, trimmed, deterministic filler.
func text(source *rand.Rand, n int) string {
	if n <= 2 {
		return strings.Repeat("x", max(n, 1))
	}
	const alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
	built := strings.Builder{}
	built.Grow(n)
	for range n {
		built.WriteByte(alphabet[source.IntN(len(alphabet))])
	}
	return built.String()
}

// ID is a deterministic identifier in the shape the rules require: lowercase hex in a UUID's
// grouping (internal/rules.IsID).
//
// It is derived from a name rather than drawn at random, so the same run publishes the same
// identities twice — and so a report can name one. Two different names never collide in practice
// and would be a refusal rather than a silent overwrite if they did: the gateway would see a
// republication with a different creation time and refuse it.
func ID(name string) string {
	digest := fnv.New128a()
	_, _ = digest.Write([]byte(name))
	sum := digest.Sum(nil)
	hex := fmt.Sprintf("%x", sum)
	return strings.Join([]string{
		hex[0:8], hex[8:12], hex[12:16], hex[16:20], hex[20:32],
	}, "-")
}
