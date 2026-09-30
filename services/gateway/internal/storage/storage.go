// Package storage is the durable boundary used by the feed gateway's business and delivery code.
//
// It describes the operations the gateway needs without describing SQLite, SQL transactions, a
// schema, or a database connection. The sole implementation in this release is storage/sqlite.
// Adding a different database later would require another implementation and an explicit data
// migration; this contract does not make the current local SQLite file remotely deployable.
package storage

import (
	"context"
	"errors"
	"time"
	"unicode"
	"unicode/utf8"

	proposalv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/proposal/v1"
	requestv2 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/request/v2"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
)

// StoredManifest is the validated manifest the gateway currently holds.
type StoredManifest struct {
	Document  *serverv1.ServerManifest
	UpdatedAt time.Time
}

// StoredProposal is the protocol-1 view of a publication and the sequence at which it was stored.
type StoredProposal struct {
	Document  *proposalv1.Proposal
	Sequence  uint64
	UpdatedAt time.Time
	Legacy    bool
}

// StoredRequest is the common-contract view of a publication. Legacy identifies a row originally
// written with the protocol-1 proposal encoding.
type StoredRequest struct {
	Document  *requestv2.Request
	Sequence  uint64
	UpdatedAt time.Time
	Legacy    bool
}

// NoticeKind identifies which authoritative document an outbox row invalidates.
type NoticeKind string

const (
	ManifestNotice NoticeKind = "manifest"
	ProposalNotice NoticeKind = "proposal"
	// AccessNotice retires a restricted channel's stream name after a revocation (SEE-156). Its
	// ProposalID and Revision both carry the access epoch being retired, so every retired name gets
	// its own notice and two revocations in a row cannot collapse into one that skips a name.
	AccessNotice NoticeKind = "access"
)

// Notice is one durable, pending fan-out. It carries an identity rather than document bytes so a
// delayed or replayed delivery always reads the current authoritative document.
type Notice struct {
	ID         int64
	Channel    string
	Kind       NoticeKind
	ProposalID string
	Revision   uint64
	Sequence   uint64
	Attempts   int
}

// PublicationTx is the atomic decision boundary for one publication. The handler reads the held
// revision and writes its accepted successor through the same transaction, so concurrent writes
// cannot decide against stale state.
//
// Each Put method atomically advances the channel sequence, stores the document, and upserts its
// durable outbox notice. Implementations must never make only a subset of those effects visible.
type PublicationTx interface {
	Manifest(context.Context, string) (*StoredManifest, error)
	Request(context.Context, string, string) (*StoredRequest, error)
	Proposal(context.Context, string, string) (*StoredProposal, error)
	Count(context.Context, string) (int, error)
	// Access is the publisher's access policy as this transaction sees it, so a manifest is stamped
	// with the policy that is in force when it is stored (SEE-156).
	Access(context.Context, string) (Access, error)
	PutManifest(context.Context, *serverv1.ServerManifest, time.Time) (uint64, error)
	PutRequest(context.Context, *requestv2.Request, time.Time) (uint64, error)
	PutProposal(context.Context, *proposalv1.Proposal, time.Time) (uint64, error)
}

// PublicationStore commits fn's complete publication on nil and makes none of it visible on an
// error. A nil return means the durable commit has completed; only then may an API answer success
// or wake fan-out.
type PublicationStore interface {
	Write(context.Context, func(PublicationTx) error) error
}

// FeedStore is the authoritative, read-only feed view. Reads must not create subscriber or cursor
// state: a restricted read looks a grant up and writes nothing down (SEE-156).
type FeedStore interface {
	PublisherExists(context.Context, string) (bool, error)
	// Access is a registered publisher's access policy, or ErrNoPublisher.
	Access(context.Context, string) (Access, error)
	// GrantFor is the grant on this server's channel whose session hashes to the digest, or nil. It
	// is scoped to the server in the same statement, so a session issued for one channel can never
	// read another.
	GrantFor(context.Context, string, []byte) (*Grant, error)
	Manifest(context.Context, string) (*StoredManifest, error)
	Request(context.Context, string, string) (*StoredRequest, error)
	RequestPage(context.Context, string, string, int) ([]*StoredRequest, error)
	Proposal(context.Context, string, string) (*StoredProposal, error)
	Page(context.Context, string, string, int) ([]*StoredProposal, error)
	Sequence(context.Context, string) (uint64, error)
	// PublisherLastSeen is when this publisher last made an authenticated call, or the zero time
	// when it never has (SEE-150). It is the only fact the gateway has about whether a publisher's
	// own server is running, and it is one the publisher pushed: nothing here ever contacts a
	// publisher's host, so a registration that has never checked in and one whose process stopped
	// are the same answer.
	PublisherLastSeen(context.Context, string) (time.Time, error)
	// Listed is every registration the operator put in the Discover catalog that could be shown
	// there (SEE-176): listed, enabled for publishing, with a public description and a manifest.
	// It is bounded by MostListed, read fresh on every call, and ordered by server ID; the caller
	// decides what a usable manifest is and orders the catalog itself.
	Listed(context.Context) ([]ListedFeed, error)
}

// --- the Discover catalog (SEE-176) --------------------------------------------

// MostListed is the most registrations one catalog read considers. A gateway's catalog is what its
// operator chose to list by hand, so this is a bound on one read's work rather than a size anyone
// is expected to reach.
const MostListed = 1000

// MaxDescriptionRunes is how long a public description may be. It is a paragraph for a card and a
// detail sheet, not a document.
const MaxDescriptionRunes = 500

// Listing is how a registered feed appears in the app's Discover catalog.
//
// Recommended is the operator's opt-in, and the zero value is unlisted. Description is the public,
// plain-text paragraph the catalog shows under the feed's name; it is separate from the operator's
// label and host, which are never served to anyone. Neither moves who may read the feed: a listed
// restricted feed is still restricted, and unlisting a feed revokes nothing and invalidates no
// reference anyone already holds.
type Listing struct {
	Recommended bool
	Description string
}

// Valid says whether a listing can be stored: a description of at most MaxDescriptionRunes of
// valid UTF-8, with no control character but a line break. An empty description is valid — it is
// a listing that is not yet complete, and the catalog leaves it out until it is.
func (l Listing) Valid() bool {
	if !utf8.ValidString(l.Description) || utf8.RuneCountInString(l.Description) > MaxDescriptionRunes {
		return false
	}
	for _, r := range l.Description {
		if r != '\n' && (unicode.IsControl(r) || r == ' ' || r == ' ') {
			return false
		}
	}
	return true
}

// ListedFeed is one catalog candidate as the store holds it: the registration's own listing and
// access, and the manifest its publisher last published. It carries nothing else about the
// registration — no label, no host, no credential and no grant.
type ListedFeed struct {
	ServerID    string
	Description string
	Access      Access
	Manifest    *serverv1.ServerManifest
}

// PresenceStore records that a publisher's server is running (SEE-150).
//
// It is not a publication and deliberately not on PublicationTx: a check-in is not part of any
// publication's atomic decision, it carries no document, it advances no sequence and it wakes
// nobody. A check-in for a server the gateway does not host is not an error — it is a registration
// that was forgotten between the credential resolving and the write, and there is nothing to
// record.
type PresenceStore interface {
	PublisherSeen(context.Context, string, time.Time) error
}

// CredentialResolver is the complete authority of a publisher credential: resolve its hash to the
// one publisher identity it may act as, or return an empty identity.
//
// It answers for publishing only. A relay credential resolves through RelayStore.RelayServerFor
// and is a different method on purpose: two grants that resolved through one function would be one
// mistake away from being one grant.
type CredentialResolver interface {
	PublisherFor(context.Context, []byte) (string, error)
}

// OutboxStore is the durable at-least-once delivery boundary. NoticeSent must remove only the
// revision that was actually delivered, leaving a newer publication pending.
type OutboxStore interface {
	Notices(context.Context, time.Time, int) ([]Notice, error)
	NoticeSent(context.Context, int64, uint64) (bool, error)
	NoticeDeferred(context.Context, int64, time.Time) error
	NoticeDropped(context.Context, int64) error
	Pending(context.Context) (int, error)
	Manifest(context.Context, string) (*StoredManifest, error)
	Request(context.Context, string, string) (*StoredRequest, error)
	// Access is read when a notice is sent rather than when it was written, so a delivery goes to
	// the channel's stream name as it stands now (SEE-156).
	Access(context.Context, string) (Access, error)
}

// MaintenanceStore owns local retention only; it does not change the public channel sequence.
type MaintenanceStore interface {
	Sweep(context.Context, time.Time) (int64, error)
	// SweepRelay bounds abandoned relay grants. A phone that was reinstalled, wiped or simply
	// unpaired while offline cannot leave a grant behind indefinitely, because nothing has to
	// happen for one to end (SEE-144).
	SweepRelay(context.Context, RelayRetention) (int64, error)
	// SweepGrants forgets grants that ended — revoked or expired — before the instant given. A
	// grant is kept that long after it ends so a late renewal of a revoked grant is still refused
	// by name rather than by absence (SEE-156).
	SweepGrants(context.Context, time.Time) (int64, error)
}

// GatewayStore is the focused set the running gateway composes. Close and filesystem ownership
// remain responsibilities of the concrete SQLite implementation at the process boundary.
type GatewayStore interface {
	PublicationStore
	FeedStore
	PresenceStore
	CredentialResolver
	OutboxStore
	MaintenanceStore
	RelayStore
	AccessStore
}

// Publisher and Credential are the non-secret records the operator's surfaces show. Neither
// carries a credential: the raw secret exists only in the answer that created it.
type Publisher struct {
	ServerID string
	Label    string
	// Host is the developer-supplied base URL of the publisher's own backend, when they gave one.
	// It is administrative metadata and nothing else: it is not the authentication identity, the
	// gateway never fetches it, and a registration made before SEE-141 has none.
	Host      string
	CreatedAt time.Time
	// Active is how many publishing credentials would be accepted right now, and ActiveRelay how
	// many relay credentials would. They are counted separately because they are separate grants:
	// a publishing credential never gains relay permission and a relay credential never gains
	// publishing (SEE-144).
	Active      int
	ActiveRelay int
	// Publishing and Relaying are what the operator has enabled for this server. A credential is
	// only honoured while its capability is enabled here, so disabling one takes effect on the
	// next call without revoking anything or restarting the gateway.
	Publishing bool
	Relaying   bool
	// Access is who may read this publisher's feed, as the operator registered it (SEE-156), and
	// Grants how many of its grants are live right now. A count and never a list of anyone.
	Access Access
	Grants int
	// Listing is whether the app's Discover catalog shows this feed, and what it says about it
	// there (SEE-176). It is the operator's, like Access, and nothing a publisher publishes moves it.
	Listing Listing
}

type Credential struct {
	ID       string
	ServerID string
	Label    string
	// Capability is the one thing this credential may be used for. It is stored with the
	// credential rather than derived from the server, because a server may hold both kinds at
	// once and presenting one must never do the other's work.
	Capability Capability
	CreatedAt  time.Time
	RevokedAt  *time.Time
}

// Capability is what a registered server may do through this gateway. There are two, they are
// independent, and a server may have either, both or neither.
//
// Publishing is SEE-141's public feed: a manifest and proposals anyone may read. Relaying is
// SEE-144's private push routing: a fixed, content-free invalidation sent to one device that
// already authorized this server. Neither implies the other, and neither is administration.
type Capability string

const (
	Publishing Capability = "publish"
	Relaying   Capability = "relay"
)

// Valid says whether a capability is one of the two. Anything else is refused at the boundary
// rather than stored and discovered later.
func (c Capability) Valid() bool { return c == Publishing || c == Relaying }

// Registration is what registering a publisher says. It is a record rather than three strings in a
// row because two of them are free text an operator types and the third is an identity, and a
// caller that swapped a pair would be registering something nobody meant.
type Registration struct {
	ServerID string
	Label    string
	Host     string
	// Publishing and Relaying are what this registration is allowed to do. A relay-only
	// registration is a complete registration: it needs no manifest, publishes no feed and has no
	// channel anyone reads (SEE-144).
	Publishing bool
	Relaying   bool
	// Access is the feed's access policy. The zero value is public, which is what every
	// registration made before SEE-156 is.
	Access Access
	// Listing is the feed's place in the app's Discover catalog. The zero value is unlisted, which
	// is what every registration made before SEE-176 is.
	Listing Listing
}

var (
	ErrNoPublisher = errors.New("no such publisher")
	// ErrPublisherExists is a registration that names an identity the gateway already holds. It is
	// refused rather than merged: a second registration of the same server is either a mistake or
	// somebody claiming an identity that is in use, and adding a credential to it silently would
	// grant the ability to publish as an existing publisher (SEE-141).
	ErrPublisherExists = errors.New("that publisher is already registered")
)

// PublisherAdminStore is the publisher administration boundary. It is reached by the operator's
// CLI and, since SEE-141, by the operator's authenticated admin surface — both in the same process
// space as the database, and neither through the publisher or feed APIs, which have no method that
// could register anything however a request were authenticated.
//
// Register and AddCredential return the new credential's ID so the caller can name it back to the
// operator without deriving it a second way.
type PublisherAdminStore interface {
	Register(context.Context, Registration, Capability, []byte, time.Time) (string, error)
	AddCredential(context.Context, string, string, Capability, []byte, time.Time) (string, error)
	Revoke(context.Context, string, time.Time) (int64, error)
	RevokeAll(context.Context, string, time.Time) (int64, error)
	// SetCapabilities is the operator enabling or disabling what a server may do. It changes no
	// credential: a disabled capability refuses the credentials that exist, and enabling it again
	// makes the same ones work. That is what makes "disable relay" a switch an operator can flip
	// back, and revocation the thing that cannot be undone.
	SetCapabilities(context.Context, string, bool, bool) error
	// SetAccess is the operator choosing who may read a feed, and where its subscribers prove who
	// they are (SEE-156). Moving a feed between policies moves its stream name too, so a listener
	// admitted under the old policy stops receiving anything on the name it holds.
	SetAccess(context.Context, string, Access) error
	// SetListing is the operator choosing whether the app's Discover catalog shows a feed, and
	// what its public description says (SEE-176). It changes nothing about who may read the feed:
	// no policy, no epoch, no grant and no credential moves.
	SetListing(context.Context, string, Listing) error
	// Grants lists one publisher's grants, newest first, for the operator's view: opaque
	// references and instants, never a session and never a push target.
	Grants(context.Context, string) ([]Grant, error)
	Forget(context.Context, string, string) error
	Publishers(context.Context) ([]Publisher, error)
	Publisher(context.Context, string) (*Publisher, error)
	Credentials(context.Context, string) ([]Credential, error)
	Publications(context.Context, string) (int, error)
}

// --- the push relay (SEE-144) -------------------------------------------------

// RelayInstallation is one app installation as the relay knows it, which is deliberately almost
// nothing: an opaque identity this gateway minted, and when it was last seen. There is no owner,
// no account, no device name and no wallet — the relay routes a wake-up, and routing is all it
// learns.
//
// The FCM target is not here on purpose. It is the one value in this service that has to be
// recoverable rather than hashed, because delivery needs it, so it never leaves the store except
// into the send itself ([RelayTarget]) and never appears in a listing, an admin page or a log.
type RelayInstallation struct {
	ID        string
	CreatedAt time.Time
	SeenAt    time.Time
	// HasTarget says whether a target is currently held. The target itself is never returned here.
	HasTarget bool
}

// RelayBinding is one authorization: this installation has agreed that this registered server may
// wake it, for one direct connection the phone holds.
//
// The push handle is not here either. What is here is its ID — a prefix of its hash — which is
// what an operator or a log line names a binding by and which cannot be turned back into a handle.
type RelayBinding struct {
	ID             string
	InstallationID string
	ServerID       string
	// Connection is the phone's own identifier for the direct connection this binding is for. It
	// is opaque to the gateway: it is never parsed, never resolved and never shown to a server,
	// and it exists so a phone can reconcile its own bindings without keeping a second index.
	Connection string
	CreatedAt  time.Time
	ExpiresAt  time.Time
	RevokedAt  *time.Time
	// Sends is how many invalidations this binding has accepted, and LastSentAt when the last one
	// was. Accepting a send is not delivering it: FCM is asked, and a phone may be off.
	Sends      int64
	LastSentAt *time.Time
}

// Active says whether this binding would authorize a send at the given instant.
func (b RelayBinding) Active(at time.Time) bool {
	return b.RevokedAt == nil && at.Before(b.ExpiresAt)
}

// RelayTarget is everything one send needs and nothing else: which binding authorized it, which
// installation it wakes, and the device target to wake. It is the only shape the FCM target
// leaves the store in.
type RelayTarget struct {
	BindingID      string
	InstallationID string
	Target         string
}

// RelayStatus is the aggregate an operator sees on a server's page. It is counts and instants: no
// target, no handle, no installation identity, and nothing that says a device is online — a
// binding is a standing authorization, not a connection.
type RelayStatus struct {
	Bindings   int
	Revoked    int
	Sends      int64
	LastSentAt *time.Time
	LastBindAt *time.Time
}

var (
	// ErrNoInstallation is an installation identity that does not exist, or one whose secret did
	// not match. It is one error for both because a caller may learn only that it is not the owner
	// of this installation, never whether the installation exists.
	ErrNoInstallation = errors.New("no such relay installation")
	// ErrNoBinding is every way a handle fails to authorize a send: fabricated, revoked, expired,
	// belonging to another server, or naming an installation that has since been forgotten. One
	// error, because a server may learn only that this handle does not authorize it.
	ErrNoBinding = errors.New("no such relay binding")
	// ErrNotPermitted is a registered server that has not been granted the capability it is using.
	ErrNotPermitted = errors.New("that server may not do that")
)

// RelayStore is the private push-routing boundary. It holds routing and authorization metadata
// only: no request, no approval, no signature, no result, and nothing an owner decided (SEE-144).
//
// Every method that changes an installation's state takes the installation's secret hash and
// checks it in the same transaction as the change. Ownership is therefore structural rather than
// remembered: there is no way to replace a target or authorize a binding without the secret the
// enrolling device was given once, so knowing another device's FCM target grants nothing.
type RelayStore interface {
	// RelayServerFor resolves a relay credential's hash to the server it may relay for, or "" when
	// the hash is unknown, revoked, or belongs to a server whose relay capability is disabled. It
	// is the relay's whole grant, and it is separate from PublisherFor on purpose.
	RelayServerFor(context.Context, []byte) (string, error)

	// Enroll records a new installation with its first target. The identity and the secret are
	// minted by the caller; this stores the hash.
	Enroll(context.Context, string, []byte, string, time.Time) error
	// SetTarget replaces an installation's FCM target, proving ownership first. It is also the
	// renewal: an installation that keeps its target current keeps being seen.
	SetTarget(context.Context, string, []byte, string, time.Time) error
	// Installation is the authenticated read an app makes to find out whether the gateway still
	// holds its enrollment — the answer to "was this gateway's database lost?".
	Installation(context.Context, string, []byte) (*RelayInstallation, error)
	// ForgetInstallation removes an installation and every binding it authorized. It is what an
	// owner's "disconnect everything" does, and it is proved the same way.
	ForgetInstallation(context.Context, string, []byte) error

	// Bind authorizes one server to wake one installation, for one of the phone's connections, and
	// stores the handle's hash. A binding that already exists for the same connection and server
	// is revoked in the same transaction, so a rebinding replaces rather than accumulates.
	Bind(context.Context, RelayBindingRequest) error
	// Unbind revokes one of an installation's bindings by its ID. It says whether it revoked one.
	Unbind(context.Context, string, []byte, string, time.Time) (bool, error)
	// Bindings lists one installation's bindings, for the reconciliation an app runs at startup.
	Bindings(context.Context, string, []byte) ([]RelayBinding, error)

	// TargetFor resolves a presented handle to the device it may wake, for one authenticated
	// server, at one instant. Every failure is ErrNoBinding.
	TargetFor(context.Context, string, []byte, time.Time) (*RelayTarget, error)
	// Sent records that an invalidation was accepted for a binding. It is a counter for the
	// operator's page and nothing a rule depends on.
	Sent(context.Context, string, time.Time) error
	// TargetRejected clears a target the push endpoint refused, and only when it is still the one
	// that failed: a rotation can race a rejection, and clearing the newer target would unregister
	// a device that had just registered. It says whether it cleared one.
	TargetRejected(context.Context, string, string) (bool, error)

	// RelayStatus is the aggregate for the operator's page.
	RelayStatus(context.Context, string) (RelayStatus, error)
}

// RelayRetention is the three cutoffs a relay sweep applies. They are three rather than one
// because they bound three different kinds of abandonment.
//
// Bindings is straightforward: an authorization past its expiry is one nobody renewed, and a phone
// that is still paired re-authorizes on its next reconciliation.
//
// Idle is the device that stopped existing. An installation nothing has authenticated as since
// this instant is removed with its bindings, because a wiped or reinstalled phone cannot come back
// and tidy up after itself.
//
// Unbound is the cheap defence against enrollment noise. Enrolling needs no credential — the
// caller has none yet — so the surface is rate limited per address and the rows it creates are
// given a short grace rather than the full idle window: a real app binds within seconds of
// enrolling, and one that never binds has granted nobody anything and is not worth keeping for
// weeks.
type RelayRetention struct {
	Bindings time.Time
	Idle     time.Time
	Unbound  time.Time
}

// RelayBindingRequest is what authorizing a binding says. It is a record rather than seven
// arguments because four of them are opaque strings and a caller that swapped a pair would
// authorize something nobody meant.
type RelayBindingRequest struct {
	InstallationID string
	// SecretHash proves the caller is the installation. Checked in the same transaction.
	SecretHash []byte
	// ServerID is the registered relay server this binding is for. It must hold the relay
	// capability, or the binding is refused with ErrNotPermitted — a phone cannot authorize a
	// server the operator never enabled.
	ServerID   string
	Connection string
	// HandleID is the handle's public name and HandleHash is what is stored. The handle itself is
	// returned to the phone by the caller and never written down here.
	HandleID   string
	HandleHash []byte
	CreatedAt  time.Time
	ExpiresAt  time.Time
}

// --- restricted feeds (SEE-156) -----------------------------------------------

// AccessPolicy is who may read a feed.
type AccessPolicy string

const (
	// PublicAccess is the broadcast every feed was before SEE-156, and the zero value's meaning.
	PublicAccess AccessPolicy = "public"
	// RestrictedAccess is a feed only devices its publisher approved may read.
	RestrictedAccess AccessPolicy = "restricted"
)

// Access is a registered feed's access policy.
type Access struct {
	Policy AccessPolicy
	// AuthOrigin is where a restricted feed's subscribers prove who they are, as the operator
	// registered it. Empty for a public feed.
	AuthOrigin string
	// Epoch counts the restricted channel's stream names. It moves on every revocation and on every
	// change of policy, so a listener attached under an earlier one hears nothing more.
	Epoch uint64
}

// Restricted says whether the feed needs a grant to read. Anything but an explicit restricted
// policy is public, because an empty policy is what a registration made before SEE-156 holds.
func (a Access) Restricted() bool { return a.Policy == RestrictedAccess }

// Valid says whether an access policy is one the gateway knows. A restricted feed must name the
// origin its subscribers authenticate with, and a public one must not name any.
func (a Access) Valid() bool {
	switch a.Policy {
	case PublicAccess, "":
		return a.AuthOrigin == ""
	case RestrictedAccess:
		return a.AuthOrigin != ""
	}
	return false
}

// Grant is one approved device's access to one restricted channel, as the gateway holds it: the
// publisher's opaque references, when it runs until, and whether it was revoked. There is no
// wallet, no person and no session here — only the session's digest is stored, and it is not part
// of this record.
type Grant struct {
	GrantID       string
	ServerID      string
	SubscriberRef string
	DeviceRef     string
	CreatedAt     time.Time
	RenewedAt     time.Time
	ExpiresAt     time.Time
	RevokedAt     *time.Time
	// HasPushTarget says whether the device registered where its hints go; the target itself is
	// read only by the relay (AccessStore.PushTargets).
	HasPushTarget bool
}

// Live says whether the grant admits a reader at this instant.
func (g Grant) Live(at time.Time) bool { return g.RevokedAt == nil && at.Before(g.ExpiresAt) }

// GrantRequest is a publisher granting or renewing one device's access to its own channel.
type GrantRequest struct {
	GrantID       string
	ServerID      string
	SubscriberRef string
	DeviceRef     string
	SessionDigest []byte
	At            time.Time
	ExpiresAt     time.Time
}

// PushTarget is where one grant's hints go. Only the relay reads it.
type PushTarget struct {
	GrantID string
	Target  string
}

var (
	// ErrNotRestricted is a grant for a feed whose policy is not restricted.
	ErrNotRestricted = errors.New("the feed is not restricted")
	// ErrNoGrant is a grant the caller does not hold: never granted, or another publisher's.
	ErrNoGrant = errors.New("no such grant")
	// ErrGrantRevoked is a renewal of a grant that was revoked. Revocation is final.
	ErrGrantRevoked = errors.New("the grant was revoked")
	// ErrGrantMismatch is a renewal that names the same grant with a different session or device:
	// a grant's binding never moves.
	ErrGrantMismatch = errors.New("the grant is bound to another session")
)

// AccessStore is the restricted-feed boundary (SEE-156): the publisher's writes, and the relay's
// reads of where hints go.
type AccessStore interface {
	// PutGrant creates a grant, or renews one the same publisher holds with the same binding. It
	// refuses a feed that is not restricted, a grant another publisher holds, a revoked grant and a
	// binding that moved, and never shortens a grant.
	PutGrant(context.Context, GrantRequest) (Grant, error)
	// RevokeGrants ends the named grants on the server's own channel and answers how many it
	// revoked now, plus the push targets they held so a best-effort hint can tell those devices to
	// look. When it revokes any, it moves the channel's epoch and writes the AccessNotice that
	// retires the old stream name, in the same transaction. A name the server does not hold is
	// ErrNoGrant and nothing is revoked.
	RevokeGrants(context.Context, string, []string, time.Time) (int, []PushTarget, error)
	// SetPushTarget registers where a live grant's hints go, found by its session's digest on the
	// server's channel. An empty target clears it. ErrNoGrant when no live grant matches.
	SetPushTarget(context.Context, string, []byte, string, time.Time) error
	// PushTargets is every live grant's target on the server's channel.
	PushTargets(context.Context, string, time.Time) ([]PushTarget, error)
	// PushTargetRejected clears a target the push endpoint said is finished, if it is still the
	// one that failed.
	PushTargetRejected(context.Context, string, string) error
}
