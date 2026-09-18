// Package signals is what a publisher template says, as documents rather than as requests
// (SEE-95, docs/wiki/copytrading-template.md).
//
// A signal is one statement: this is what I propose, on these terms, until this instant. It names
// no subscriber, because a publisher has none to name — it never learns which phones read its
// channel — and it carries no amount, no wallet and nothing prepared to sign, because those are
// each owner's and are chosen on their own device (SEE-89, docs/security.md).
//
// # The seam
//
// [Kind] is the one thing a template has to supply: which operation it publishes, which bundled
// plugin serves it, and what its terms have to say. `swap.go` is the kind for `jupiter.swap`
// (SEE-93); the prediction template (SEE-96) is a second one, and everything else in this module —
// the configuration, the store, the outbox, the API and the CLI — is written against this
// interface and not against either.
//
// # Nothing here reaches anything
//
// No clock, no database, no network and no credential: a signal is checked as pure data, and the
// rules are the phone's own so that a template cannot broadcast a document the phone would then
// refuse. Where a bound could drift between the two, `contract_test.go` reads the gateway's own
// source and fails.
package signals

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"regexp"
	"sort"
	"strings"
	"time"
	"unicode"

	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"

	proposalv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/proposal/v1"
	requestv2 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/request/v2"
)

// Status is whether the publisher still stands behind a signal. It is the document's own
// ProposalStatus, spelled as the two states a template can be in: there is no third, and a missing
// one is never read as open.
type Status string

const (
	// Open: the publisher stands behind it, and it may be acted on until it expires.
	Open Status = "open"
	// Cancelled: withdrawn. It is still readable — a phone that acted on it has its own record
	// either way — and nothing new is executed from one.
	Cancelled Status = "cancelled"
)

// Signal is one statement of one thing a template proposes. Everything in it is the publisher's
// own, and every subscriber receives it identically.
type Signal struct {
	// The template's own ID for it, a lowercase UUID, stable for as long as the signal exists.
	ProposalID string
	// The revision of everything else here: it moves when the content moves, never backwards, and
	// it is the idempotency key the gateway orders publications by (docs/protocol.md).
	Revision uint64
	Status   Status
	// The operation at the protocol's own level ("swap"), and the bundled plugin written for it.
	// Both come from the [Kind] the template registered, never from a caller.
	Operation string
	PluginID  string
	CreatedAt time.Time
	UpdatedAt time.Time
	// The instant after which nothing is executed from it: absolute, required, and in the future
	// when it is published, because a signal that has already expired proposes nothing.
	ExpiresAt time.Time
	// The publisher's own prose, for a person to read. Unverified, shown apart from anything the
	// phone established for itself, and optional.
	Note string
	// The operation's common terms, validated by the kind. The document lists them in key order,
	// always, so that publishing the same signal twice is the same bytes twice.
	Terms map[string]string
	// [Fingerprint] of everything above except the revision and the update time. The store
	// computes it and reads it back; nothing else sets it, because a fingerprint a caller supplied
	// would be a claim about a document rather than a reading of one.
	Fingerprint string
}

// Publication is what the gateway has confirmed about a signal, which is the only thing that makes
// a signal public. It is kept beside the statement rather than inside it because it is not part of
// the document: no subscriber ever sees any of this.
type Publication struct {
	// The revision the gateway has confirmed it holds. Below the signal's own revision means there
	// is something to publish, which is the whole of the outbox (internal/store).
	ConfirmedRevision uint64
	// How many times publishing it has failed so far, and when the next attempt is due.
	Attempts int
	DueAt    time.Time
	// The gateway's own problem code for a refusal that retrying cannot fix, and the message that
	// came with it. Empty while nothing has been refused.
	Problem string
	Detail  string
}

// State is what an operator or a strategy engine is told about a publication: it is pending until
// the gateway confirms the revision, published once it has, and refused when the gateway said no
// in a way that retrying cannot change.
func (p Publication) State(signal Signal) string {
	switch {
	case p.Problem != "":
		return "refused"
	case p.ConfirmedRevision >= signal.Revision:
		return "published"
	default:
		return "pending"
	}
}

// Record is a signal and what became of publishing it: one row of the store, and one object of the
// API.
type Record struct {
	Signal      Signal
	Publication Publication
}

// Fault is the one rule a caller's signal broke: a stable code, and the term it was about when it
// was about one. It is the shape the gateway's own refusals have (rules.Fault), for the same
// reason — a caller retrying needs to know which field to fix, and nothing about anyone else's.
type Fault struct {
	Code string
	Term string
}

func (f *Fault) Error() string {
	if f.Term == "" {
		return f.Code
	}
	return f.Code + " (" + f.Term + ")"
}

// Requirement is the bundled client plugin a kind's operations need, by the stable ID the plugin
// declares, with the inclusive range of plugin-boundary contract versions it was written against
// (PluginRequirement, SEE-86). It is a name and nothing loadable.
type Requirement struct {
	PluginID     string
	MinContract  uint32
	MostContract uint32
}

// Kind is one thing a template publishes. A template registers exactly one: `cmd/copytrading`
// registers [Swap], and the API, the store and the CLI know nothing else about what is being
// proposed.
type Kind interface {
	// The operation name at the protocol's own level, which a phone matches against the plugins
	// compiled into the build it is running.
	Operation() string
	// The plugin the proposal is written for, and the contract range it works with.
	Requirement() Requirement
	// Terms checks what a caller asked to publish and returns the terms as they will be
	// published, or the one rule that stopped them. A kind refuses a term it does not know: the
	// phone ignores an extra term, because a publisher may say more than a plugin reads, but a
	// template that minted one would be broadcasting a word nothing will ever read
	// (docs/integrations/signal-api.md).
	Terms(raw map[string]string) (map[string]string, *Fault)
}

// A document is bounded data. Each of these is the phone's own constant and the gateway's, and
// `contract_test.go` reads the gateway's source to keep the three from drifting.
const (
	MaxValues         = 32
	MaxValueTextBytes = 512
	MaxNoteBytes      = 1024
	MaxNameBytes      = 64
)

// MaxRevision is the highest revision that can be published: a uint64 on the wire, read into a
// signed 64-bit Long on the phone, so anything above this arrives there as a negative number and
// is refused as unorderable.
const MaxRevision uint64 = 1<<63 - 1

// ChannelFor is the one channel a server owns, which is the only channel it may publish on. It is
// the phone's `channelFor` and the gateway's `ChannelFor`.
func ChannelFor(serverID string) string { return "server/" + serverID }

var (
	uuidPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
	idPattern   = regexp.MustCompile(`^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$`)
)

// IsID is the shape of every server and proposal identity: a lowercase UUID.
func IsID(value string) bool { return uuidPattern.MatchString(value) }

// IsPluginID is a plugin's stable name — lowercase dot-separated segments, such as "jupiter.swap".
func IsPluginID(value string) bool {
	return len(value) >= 1 && len(value) <= 128 && idPattern.MatchString(value) &&
		strings.Contains(value, ".")
}

// IsOperation is an operation name at the protocol's own level ("swap"), and the shape of a term's
// key as well.
func IsOperation(value string) bool {
	return len(value) >= 1 && len(value) <= 64 && idPattern.MatchString(value)
}

// Printable is the phone's rule for text a person will read: short enough in UTF-8 bytes, no
// control characters, and nothing hiding in leading or trailing whitespace. A line break is text
// in prose and a control character in a label, which is the difference the flag carries.
//
// It is the gateway's `printable`, character for character, because a note this template accepts
// and the gateway refuses is a signal that fails to publish for a reason nobody can see in it.
func Printable(text string, mostBytes int, allowNewline bool) bool {
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
		if unicode.IsSpace(r) && r != ' ' && r != '\n' {
			return false
		}
	}
	return true
}

// Check reads what a caller asked to publish — a note, an expiry and a kind's terms — and answers
// with the parts of a signal that are theirs to choose, or the one rule that stopped it.
//
// The identity, the revision, the times a template keeps and the operation are not a caller's to
// give: they are minted here and in the store, so an API caller cannot publish under somebody
// else's proposal ID, move a creation time, or claim an operation the template does not serve.
func Check(kind Kind, note string, expires time.Time, now time.Time, raw map[string]string) (
	string, time.Time, map[string]string, *Fault,
) {
	if !Printable(note, MaxNoteBytes, true) {
		return "", time.Time{}, nil, &Fault{Code: "bad_note"}
	}
	if expires.IsZero() {
		return "", time.Time{}, nil, &Fault{Code: "no_expiry"}
	}
	// Truncated to the second, like every other time in a published document: it is the publisher's
	// own clock, nothing orders by it, and a whole second is the coarsest thing both runtimes
	// certainly agree on.
	expires = expires.UTC().Truncate(time.Second)
	if !expires.After(now.UTC().Truncate(time.Second)) {
		return "", time.Time{}, nil, &Fault{Code: "past_expiry"}
	}
	terms, fault := kind.Terms(raw)
	if fault != nil {
		return "", time.Time{}, nil, fault
	}
	if len(terms) > MaxValues {
		return "", time.Time{}, nil, &Fault{Code: "too_many_terms"}
	}
	for key, text := range terms {
		if !IsOperation(key) {
			return "", time.Time{}, nil, &Fault{Code: "bad_term_name", Term: key}
		}
		if !Printable(text, MaxValueTextBytes, false) {
			return "", time.Time{}, nil, &Fault{Code: "bad_term", Term: key}
		}
	}
	return note, expires, terms, nil
}

// Proposal is the document itself: the signal as every subscriber will read it.
//
// The terms are listed in key order, always. Ordering them is not cosmetic — a repeated field's
// order is part of the bytes, and the gateway compares a republication with what it holds field by
// field, so a template that ordered its terms by chance would turn its own retry into a revision
// conflict (docs/protocol.md#publisherservice).
func Proposal(serverID string, signal Signal) *proposalv1.Proposal {
	keys := make([]string, 0, len(signal.Terms))
	for key := range signal.Terms {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	values := make([]*proposalv1.ProposalValue, 0, len(keys))
	for _, key := range keys {
		values = append(values, &proposalv1.ProposalValue{Key: key, Text: signal.Terms[key]})
	}
	status := proposalv1.ProposalStatus_PROPOSAL_STATUS_OPEN
	if signal.Status == Cancelled {
		status = proposalv1.ProposalStatus_PROPOSAL_STATUS_CANCELLED
	}
	return &proposalv1.Proposal{
		ServerId:      serverID,
		Channel:       ChannelFor(serverID),
		ProposalId:    signal.ProposalID,
		Revision:      signal.Revision,
		Operation:     signal.Operation,
		PluginId:      signal.PluginID,
		Status:        status,
		CreatedAt:     timestamppb.New(signal.CreatedAt.UTC().Truncate(time.Second)),
		UpdatedAt:     timestamppb.New(signal.UpdatedAt.UTC().Truncate(time.Second)),
		ExpiresAt:     timestamppb.New(signal.ExpiresAt.UTC().Truncate(time.Second)),
		PublisherNote: signal.Note,
		Values:        values,
	}
}

// Request is the common developer document a signal becomes. Signal is presentation — the small
// label in Presentation.category — while the action, inputs, audience and result policy are the
// same extensible contract a private adapter consumes. No owner's answer is represented here.
func Request(serverID string, signal Signal) *requestv2.Request {
	keys := make([]string, 0, len(signal.Terms))
	for key := range signal.Terms {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	parameters := make([]*requestv2.Value, 0, len(keys))
	for _, key := range keys {
		parameters = append(parameters, &requestv2.Value{
			Key: key, Value: &requestv2.Value_Text{Text: signal.Terms[key]},
		})
	}
	status := requestv2.RequestStatus_REQUEST_STATUS_OPEN
	if signal.Status == Cancelled {
		status = requestv2.RequestStatus_REQUEST_STATUS_CANCELLED
	}
	return &requestv2.Request{
		ContractVersion: 1,
		Identity: &requestv2.RequestIdentity{
			SourceId: serverID, Scope: ChannelFor(serverID), RequestId: signal.ProposalID,
		},
		Lifecycle: &requestv2.RequestLifecycle{
			Revision: signal.Revision, Status: status,
			CreatedAt: timestamppb.New(signal.CreatedAt.UTC().Truncate(time.Second)),
			UpdatedAt: timestamppb.New(signal.UpdatedAt.UTC().Truncate(time.Second)),
			ExpiresAt: timestamppb.New(signal.ExpiresAt.UTC().Truncate(time.Second)),
		},
		Presentation: &requestv2.Presentation{
			Title: title(signal.Operation), Description: signal.Note,
			Category: requestv2.PresentationCategory_PRESENTATION_CATEGORY_SIGNAL,
		},
		Action: &requestv2.ActionCapability{
			CapabilityId: signal.Operation, CapabilityVersion: 1,
			PluginId: signal.PluginID, Parameters: parameters,
		},
		OwnerInputs: ownerInputs(signal),
		Audience: &requestv2.Audience{Audience: &requestv2.Audience_Feed{
			Feed: &requestv2.FeedAudience{Channel: ChannelFor(serverID)},
		}},
		ResultHandling: &requestv2.ResultHandling{Mode: requestv2.ResultMode_RESULT_MODE_DEVICE_LOCAL},
	}
}

func title(operation string) string {
	switch operation {
	case "swap":
		return "Swap"
	case "prediction":
		return "Prediction market"
	default:
		return operation
	}
}

func ownerInputs(signal Signal) []*requestv2.OwnerInput {
	switch signal.Operation {
	case "swap":
		return []*requestv2.OwnerInput{
			{Key: "input_amount", Label: "Amount", Kind: requestv2.OwnerInputKind_OWNER_INPUT_KIND_AMOUNT,
				Required: true, Minimum: lower(signal.Terms[LeastInput], "1"), Maximum: signal.Terms[MostInput],
				Help: "Base units of the input mint. Kept on this device."},
			{Key: "slippage_bps", Label: "Slippage", Kind: requestv2.OwnerInputKind_OWNER_INPUT_KIND_COUNT,
				Required: true, Minimum: "1", Maximum: signal.Terms[MaxSlippageBps],
				Help: "Whole basis points. Kept on this device."},
		}
	case "prediction":
		return []*requestv2.OwnerInput{
			{Key: "side", Label: "Side", Kind: requestv2.OwnerInputKind_OWNER_INPUT_KIND_CHOICE,
				Required: true, Options: []*requestv2.InputOption{{Value: "yes", Label: "Yes"}, {Value: "no", Label: "No"}},
				Help: "Your choice is never published."},
			{Key: "deposit_amount", Label: "Stake", Kind: requestv2.OwnerInputKind_OWNER_INPUT_KIND_AMOUNT,
				Required: true, Minimum: lower(signal.Terms[LeastDeposit], "1"), Maximum: signal.Terms[MostDeposit],
				Help: "Base units of the deposit mint. Kept on this device."},
		}
	default:
		return nil
	}
}

func lower(value, fallback string) string {
	if value == "" || value == "0" {
		return fallback
	}
	return value
}

// Statement is what an idempotency key is checked against: the signal a caller asked for, with the
// parts a template mints left out.
//
// It is the validated statement rather than the bytes that arrived, so two calls that differ only
// in whitespace, key order or how a number was spelled are the same request — which is what a
// retrying client actually sends — while a call that asks for different terms under the same key is
// a conflict. The reconciler uses it too, for the same reason: it re-derives a market's statement
// every cycle, and the key it would use is the same key (internal/discovery).
func Statement(signal Signal) string {
	keys := make([]string, 0, len(signal.Terms))
	for key := range signal.Terms {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	digest := sha256.New()
	fmt.Fprintf(digest, "%s\n%s\n", signal.ExpiresAt.UTC().Format(time.RFC3339), signal.Note)
	for _, key := range keys {
		fmt.Fprintf(digest, "%s=%s\n", key, signal.Terms[key])
	}
	return hex.EncodeToString(digest.Sum(nil))
}

// Fingerprint is the content of a signal, with the two fields that are not content left out: the
// revision, which says that the content changed, and the update time, which says when it did.
//
// It is what makes "a revision moves only when the content does" a decision and not a habit. An
// update that produces this same fingerprint publishes nothing at all, so a strategy engine can
// re-post its whole state every minute without waking a single phone (SEE-92's hints go out on a
// stored publication, and there is no stored publication).
func Fingerprint(serverID string, signal Signal) string {
	document := Request(serverID, signal)
	document.Lifecycle.Revision = 0
	document.Lifecycle.UpdatedAt = nil
	// Deterministic marshalling, because this is compared with itself across restarts and Go's
	// default field ordering is documented as unstable.
	bytes, err := proto.MarshalOptions{Deterministic: true}.Marshal(document)
	if err != nil {
		// A document built here cannot fail to marshal: every field is a scalar this package set.
		panic("signals: a proposal built here did not marshal: " + err.Error())
	}
	sum := sha256.Sum256(bytes)
	return hex.EncodeToString(sum[:])
}
