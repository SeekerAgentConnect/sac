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

// FeedStore is the authoritative, read-only public-feed view. Reads must not create subscriber or
// cursor state.
type FeedStore interface {
	PublisherExists(context.Context, string) (bool, error)
	Manifest(context.Context, string) (*StoredManifest, error)
	Request(context.Context, string, string) (*StoredRequest, error)
	RequestPage(context.Context, string, string, int) ([]*StoredRequest, error)
	Proposal(context.Context, string, string) (*StoredProposal, error)
	Page(context.Context, string, string, int) ([]*StoredProposal, error)
	Sequence(context.Context, string) (uint64, error)
}

// CredentialResolver is the complete authority of a publisher credential: resolve its hash to the
// one publisher identity it may act as, or return an empty identity.
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
}

// MaintenanceStore owns local retention only; it does not change the public channel sequence.
type MaintenanceStore interface {
	Sweep(context.Context, time.Time) (int64, error)
}

// GatewayStore is the focused set the running gateway composes. Close and filesystem ownership
// remain responsibilities of the concrete SQLite implementation at the process boundary.
type GatewayStore interface {
	PublicationStore
	FeedStore
	CredentialResolver
	OutboxStore
	MaintenanceStore
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
	Active    int
}

type Credential struct {
	ID        string
	ServerID  string
	Label     string
	CreatedAt time.Time
	RevokedAt *time.Time
}

// Registration is what registering a publisher says. It is a record rather than three strings in a
// row because two of them are free text an operator types and the third is an identity, and a
// caller that swapped a pair would be registering something nobody meant.
type Registration struct {
	ServerID string
	Label    string
	Host     string
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
	Register(context.Context, Registration, []byte, time.Time) (string, error)
	AddCredential(context.Context, string, string, []byte, time.Time) (string, error)
	Revoke(context.Context, string, time.Time) (int64, error)
	RevokeAll(context.Context, string, time.Time) (int64, error)
	Forget(context.Context, string, string) error
	Publishers(context.Context) ([]Publisher, error)
	Publisher(context.Context, string) (*Publisher, error)
	Credentials(context.Context, string) ([]Credential, error)
	Publications(context.Context, string) (int, error)
}
