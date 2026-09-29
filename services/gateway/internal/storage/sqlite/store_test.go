package sqlite

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"fmt"
	"path/filepath"
	"testing"
	"time"

	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"

	proposalv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/proposal/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

const (
	publisher = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
	stranger  = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
	proposalA = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
	proposalB = "a1b2c3d4-e5f6-4789-abcd-0123456789ab"
	channelA  = "server/" + publisher
	channelB  = "server/" + stranger
)

var published = time.Date(2026, 9, 17, 9, 0, 0, 0, time.UTC)

func openStore(t *testing.T) *Store {
	t.Helper()
	documents, err := Open(filepath.Join(t.TempDir(), "broadcast.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = documents.Close() })
	return documents
}

func hashOf(of string) []byte {
	sum := sha256.Sum256([]byte("credential for " + of))
	return sum[:]
}

func register(t *testing.T, documents *Store, serverID string) {
	t.Helper()
	if _, err := documents.Register(context.Background(),
		Registration{ServerID: serverID, Label: "test", Publishing: true},
		storage.Publishing, hashOf(serverID), published); err != nil {
		t.Fatal(err)
	}
}

func manifest(serverID string, revision uint64) *serverv1.ServerManifest {
	return &serverv1.ServerManifest{
		ServerId:         serverID,
		ProtocolVersion:  1,
		SettingsRevision: revision,
		Mode:             serverv1.ConnectionMode_CONNECTION_MODE_GATEWAY_FEED,
		Environments: []serverv1.ServerEnvironment{
			serverv1.ServerEnvironment_SERVER_ENVIRONMENT_PRODUCTION,
		},
		Reference: &serverv1.ServerManifest_Feed{Feed: &serverv1.GatewayFeed{
			GatewayUrl: "https://feeds.example.com",
			Channel:    "server/" + serverID,
			SupportedNetworks: []serverv1.SolanaNetwork{
				serverv1.SolanaNetwork_SOLANA_NETWORK_MAINNET,
				serverv1.SolanaNetwork_SOLANA_NETWORK_DEVNET,
			},
		}},
	}
}

func proposal(serverID, proposalID string, revision uint64, expires time.Time) *proposalv1.Proposal {
	return &proposalv1.Proposal{
		ServerId:   serverID,
		Channel:    "server/" + serverID,
		ProposalId: proposalID,
		Revision:   revision,
		Operation:  "swap",
		PluginId:   "jupiter.swap",
		Status:     proposalv1.ProposalStatus_PROPOSAL_STATUS_OPEN,
		CreatedAt:  timestamppb.New(published),
		UpdatedAt:  timestamppb.New(published),
		ExpiresAt:  timestamppb.New(expires),
	}
}

func put(t *testing.T, documents *Store, message *proposalv1.Proposal) uint64 {
	t.Helper()
	var sequence uint64
	err := documents.Write(context.Background(), func(tx storage.PublicationTx) error {
		var err error
		sequence, err = tx.PutProposal(context.Background(), message, published)
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	return sequence
}

func TestASchemaIsCreatedOnceAndReadBack(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")
	documents, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	register(t, documents, publisher)
	if err := documents.Close(); err != nil {
		t.Fatal(err)
	}

	// Reopening applies nothing and finds everything, which is what makes a restart uneventful.
	again, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = again.Close() }()
	serverID, err := again.PublisherFor(context.Background(), hashOf(publisher))
	if err != nil || serverID != publisher {
		t.Fatalf("the publisher did not survive a restart: %q %v", serverID, err)
	}
}

func TestAFileFromALaterVersionIsRefused(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")
	documents, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	_ = documents.Close()

	// A file written by a later gateway: an old binary reading it could ignore a column a rule
	// depends on, so it refuses instead of guessing.
	raw, err := sql.Open("sqlite", "file:"+path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := raw.Exec(fmt.Sprintf(`PRAGMA user_version = %d`, Version+1)); err != nil {
		t.Fatal(err)
	}
	_ = raw.Close()

	if _, err := Open(path); !errors.Is(err, ErrNewerSchema) {
		t.Fatalf("a newer schema was opened anyway: %v", err)
	}
}

func TestVersionTwoMigrationRetiresOnlyPrivateStateAndIsIdempotent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")
	raw, err := sql.Open("sqlite", "file:"+path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := raw.Exec(schemaV1); err != nil {
		t.Fatal(err)
	}
	if _, err := raw.Exec(schemaV2); err != nil {
		t.Fatal(err)
	}

	feedDocument, err := proto.Marshal(manifest(publisher, 7))
	if err != nil {
		t.Fatal(err)
	}
	legacyPrivateDocument, err := proto.Marshal(&serverv1.ServerManifest{
		ServerId: stranger, ProtocolVersion: 1, SettingsRevision: 9,
		Mode: serverv1.ConnectionMode(3),
	})
	if err != nil {
		t.Fatal(err)
	}
	proposalDocument, err := proto.Marshal(proposal(
		publisher, proposalA, 5, published.Add(3*time.Hour)))
	if err != nil {
		t.Fatal(err)
	}

	statements := []struct {
		query string
		args  []any
	}{
		{`INSERT INTO publisher VALUES (?, ?, ?)`, []any{publisher, "feed", 1}},
		{`INSERT INTO publisher VALUES (?, ?, ?)`, []any{stranger, "former private", 2}},
		{`INSERT INTO publisher_credential VALUES (?, ?, ?, ?, NULL)`,
			[]any{hashOf(publisher), publisher, "feed", 3}},
		{`INSERT INTO publisher_credential VALUES (?, ?, ?, ?, NULL)`,
			[]any{hashOf(stranger), stranger, "former private", 4}},
		{`INSERT INTO manifest VALUES (?, ?, ?, ?)`,
			[]any{publisher, 7, feedDocument, 5}},
		{`INSERT INTO manifest VALUES (?, ?, ?, ?)`,
			[]any{stranger, 9, legacyPrivateDocument, 6}},
		{`INSERT INTO proposal VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?)`,
			[]any{channelA, proposalA, publisher, 5, published.Add(3 * time.Hour).UnixMilli(),
				17, proposalDocument, 7}},
		{`INSERT INTO channel_sequence VALUES (?, ?)`, []any{channelA, 17}},
		{`INSERT INTO notice
		  (channel, kind, proposal_id, revision, sequence, created_at_ms, attempts, ready_at_ms)
		  VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
			[]any{channelA, "proposal", proposalA, 5, 17, 8, 2, 9}},
		{`INSERT INTO invitation VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL)`,
			[]any{"11111111-2222-4333-8444-555555555555", []byte("token"), stranger,
				"former-user", 10, 20}},
		{`INSERT INTO device_binding VALUES (?, ?, ?, ?, ?, ?, NULL, ?)`,
			[]any{"22222222-3333-4444-8555-666666666666", stranger, "former-user",
				[]byte("binding"), "Seeker", 11, 4}},
		{`INSERT INTO private_request VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, NULL, ?)`,
			[]any{stranger, "33333333-4444-4555-8666-777777777777", "former-user",
				"22222222-3333-4444-8555-666666666666", 1, 30, 4, []byte("private"), 12}},
	}
	for _, statement := range statements {
		if _, err := raw.Exec(statement.query, statement.args...); err != nil {
			t.Fatalf("seed version two: %v", err)
		}
	}
	if _, err := raw.Exec(`PRAGMA user_version = 2`); err != nil {
		t.Fatal(err)
	}
	if err := raw.Close(); err != nil {
		t.Fatal(err)
	}

	documents, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	if held, err := documents.Manifest(ctx, publisher); err != nil ||
		held == nil || !bytes.Equal(feedDocument, mustMarshal(t, held.Document)) {
		t.Fatalf("feed manifest changed during migration: %v %v", held, err)
	}
	if held, err := documents.Manifest(ctx, stranger); err != nil || held != nil {
		t.Fatalf("legacy private manifest remained active: %v %v", held, err)
	}
	for _, serverID := range []string{publisher, stranger} {
		if found, err := documents.PublisherFor(ctx, hashOf(serverID)); err != nil || found != serverID {
			t.Fatalf("publisher credential did not survive for %s: %q %v", serverID, found, err)
		}
	}
	if sequence, err := documents.Sequence(ctx, channelA); err != nil || sequence != 17 {
		t.Fatalf("sequence changed during migration: %d %v", sequence, err)
	}
	if pending, err := documents.Pending(ctx); err != nil || pending != 1 {
		t.Fatalf("outbox changed during migration: %d %v", pending, err)
	}
	var storedProposal []byte
	if err := documents.reader.QueryRowContext(ctx,
		`SELECT document FROM proposal WHERE channel = ? AND proposal_id = ?`,
		channelA, proposalA).Scan(&storedProposal); err != nil ||
		!bytes.Equal(proposalDocument, storedProposal) {
		t.Fatalf("proposal bytes changed during migration: %v", err)
	}
	for _, table := range []string{"invitation", "device_binding", "private_request"} {
		var count int
		if err := documents.reader.QueryRowContext(ctx,
			`SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?`, table).
			Scan(&count); err != nil || count != 0 {
			t.Fatalf("retired table %s remains: count=%d err=%v", table, count, err)
		}
	}
	var version int
	if err := documents.reader.QueryRowContext(ctx, `PRAGMA user_version`).Scan(&version); err != nil ||
		version != Version {
		t.Fatalf("schema version is %d: %v", version, err)
	}
	if err := documents.Close(); err != nil {
		t.Fatal(err)
	}

	again, err := Open(path)
	if err != nil {
		t.Fatalf("version three migration was not idempotent: %v", err)
	}
	defer func() { _ = again.Close() }()
	if pending, err := again.Pending(ctx); err != nil || pending != 1 {
		t.Fatalf("idempotent reopen changed the outbox: %d %v", pending, err)
	}
}

func mustMarshal(t *testing.T, message proto.Message) []byte {
	t.Helper()
	document, err := proto.Marshal(message)
	if err != nil {
		t.Fatal(err)
	}
	return document
}

func TestACredentialIsRotatedWithoutAnOutageAndRevokedForGood(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)

	first := hashOf(publisher)
	second := hashOf(publisher + " rotated")
	if _, err := documents.AddCredential(ctx, publisher, "rotated", storage.Publishing, second,
		published); err != nil {
		t.Fatal(err)
	}
	// Both work while the new one is being deployed. That overlap is the whole reason rotation is
	// two steps rather than a replacement.
	for _, hash := range [][]byte{first, second} {
		if serverID, err := documents.PublisherFor(ctx, hash); err != nil || serverID != publisher {
			t.Fatalf("a credential did not resolve: %q %v", serverID, err)
		}
	}

	revoked, err := documents.Revoke(ctx, CredentialID(first), published)
	if err != nil || revoked != 1 {
		t.Fatalf("revoking the first credential revoked %d: %v", revoked, err)
	}
	if serverID, _ := documents.PublisherFor(ctx, first); serverID != "" {
		t.Fatal("a revoked credential still publishes")
	}
	if serverID, _ := documents.PublisherFor(ctx, second); serverID != publisher {
		t.Fatal("revoking one credential stopped the other")
	}

	// And revoking everything leaves the publisher registered but unable to publish: its
	// documents are not a reason to forget it, and forgetting it is a separate, louder act.
	if _, err := documents.RevokeAll(ctx, publisher, published); err != nil {
		t.Fatal(err)
	}
	if serverID, _ := documents.PublisherFor(ctx, second); serverID != "" {
		t.Fatal("a revoked credential still publishes")
	}
	publishers, err := documents.Publishers(ctx)
	if err != nil || len(publishers) != 1 || publishers[0].Active != 0 {
		t.Fatalf("the publisher is not listed as unable to publish: %v %v", publishers, err)
	}
	credentials, err := documents.Credentials(ctx, publisher)
	if err != nil || len(credentials) != 2 {
		t.Fatalf("a revoked credential should still be listed: %v %v", credentials, err)
	}
	for _, one := range credentials {
		if one.RevokedAt == nil {
			t.Fatalf("%s is not marked as revoked", one.ID)
		}
	}
}

func TestACredentialCannotBeAddedToAPublisherThatIsNotThere(t *testing.T) {
	documents := openStore(t)
	_, err := documents.AddCredential(context.Background(), stranger, "x", storage.Publishing,
		hashOf(stranger), published)
	if !errors.Is(err, ErrNoPublisher) {
		t.Fatalf("a credential was created for an unregistered publisher: %v", err)
	}
}

func TestADocumentAndItsNoticeCommitTogetherOrNotAtAll(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)

	// A transaction that fails after writing both leaves neither. That is the whole reason the
	// notice is written where it is: there is no window in which one exists without the other.
	stop := errors.New("something went wrong after the write")
	err := documents.Write(ctx, func(tx storage.PublicationTx) error {
		if _, err := tx.PutProposal(ctx, proposal(publisher, proposalA, 1,
			published.Add(time.Hour)), published); err != nil {
			return err
		}
		return stop
	})
	if !errors.Is(err, stop) {
		t.Fatalf("the transaction did not fail: %v", err)
	}
	if held, err := documents.Proposal(ctx, channelA, proposalA); err != nil || held != nil {
		t.Fatal("a rolled-back publication is still there")
	}
	if pending, err := documents.Pending(ctx); err != nil || pending != 0 {
		t.Fatalf("a rolled-back publication left %d notices", pending)
	}
	if sequence, err := documents.Sequence(ctx, channelA); err != nil || sequence != 0 {
		t.Fatalf("a rolled-back publication moved the sequence to %d", sequence)
	}
}

func TestANoticeSurvivesAProcessThatStopsBeforeSendingIt(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")
	documents, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	if _, err := documents.Register(ctx, Registration{
		ServerID: publisher, Label: "test", Publishing: true,
	}, storage.Publishing, hashOf(publisher), published); err != nil {
		t.Fatal(err)
	}
	put(t, documents, proposal(publisher, proposalA, 1, published.Add(time.Hour)))
	// Stopping here is the crash the design is about: the document is committed and nobody has
	// been told.
	if err := documents.Close(); err != nil {
		t.Fatal(err)
	}

	again, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = again.Close() }()
	pending, err := again.Pending(ctx)
	if err != nil || pending != 1 {
		t.Fatalf("the notice did not survive: %d %v", pending, err)
	}
	notices, err := again.Notices(ctx, published.Add(time.Minute), 10)
	if err != nil || len(notices) != 1 {
		t.Fatalf("the notice is not due: %v %v", notices, err)
	}
	if notices[0].ProposalID != proposalA || notices[0].Revision != 1 {
		t.Fatalf("the notice is about something else: %+v", notices[0])
	}
}

func TestTwoPublicationsWaitingToBeSentAreOneNotice(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)

	put(t, documents, proposal(publisher, proposalA, 1, published.Add(time.Hour)))
	put(t, documents, proposal(publisher, proposalA, 2, published.Add(time.Hour)))

	notices, err := documents.Notices(ctx, published.Add(time.Minute), 10)
	if err != nil {
		t.Fatal(err)
	}
	// One row, at the later revision: a subscriber wants the document as it stands, and the
	// sequence of how it got there is not a thing the fan-out has to replay.
	if len(notices) != 1 || notices[0].Revision != 2 {
		t.Fatalf("two publications left %d notices: %+v", len(notices), notices)
	}

	// Completing a notice is conditional on the revision, so a publication that lands while one is
	// in flight is not silently dropped.
	if removed, err := documents.NoticeSent(ctx, notices[0].ID, 1); err != nil || removed {
		t.Fatalf("a notice was removed for a revision it was not about: %v %v", removed, err)
	}
	if removed, err := documents.NoticeSent(ctx, notices[0].ID, 2); err != nil || !removed {
		t.Fatalf("the notice was not removed: %v %v", removed, err)
	}
	if pending, _ := documents.Pending(ctx); pending != 0 {
		t.Fatalf("%d notices are left", pending)
	}
}

func TestAFailedNoticeComesBackLater(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)
	put(t, documents, proposal(publisher, proposalA, 1, published.Add(time.Hour)))

	notices, _ := documents.Notices(ctx, published, 10)
	if err := documents.NoticeDeferred(ctx, notices[0].ID, published.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if due, _ := documents.Notices(ctx, published.Add(30*time.Second), 10); len(due) != 0 {
		t.Fatal("a deferred notice came back too early")
	}
	due, _ := documents.Notices(ctx, published.Add(2*time.Minute), 10)
	if len(due) != 1 || due[0].Attempts != 1 {
		t.Fatalf("a deferred notice came back as %+v", due)
	}
}

func TestEachChannelCountsItsOwnPublications(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)
	register(t, documents, stranger)

	if sequence := put(t, documents, proposal(publisher, proposalA, 1,
		published.Add(time.Hour))); sequence != 1 {
		t.Fatalf("the first publication is at sequence %d", sequence)
	}
	if sequence := put(t, documents, proposal(publisher, proposalB, 1,
		published.Add(time.Hour))); sequence != 2 {
		t.Fatalf("the second publication is at sequence %d", sequence)
	}
	// Another publisher's channel is counted separately: one publisher's activity is not a reason
	// for another's subscribers to read their feed again.
	if sequence := put(t, documents, proposal(stranger, proposalA, 1,
		published.Add(time.Hour))); sequence != 1 {
		t.Fatalf("another channel started at sequence %d", sequence)
	}
	first, _ := documents.Sequence(ctx, channelA)
	second, _ := documents.Sequence(ctx, channelB)
	if first != 2 || second != 1 {
		t.Fatalf("the sequences are %d and %d", first, second)
	}

	// A manifest moves the same counter, because a subscriber keeping up by sequence has to learn
	// about a settings change as well as a proposal.
	err := documents.Write(ctx, func(tx storage.PublicationTx) error {
		_, err := tx.PutManifest(ctx, manifest(publisher, 5), published)
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	if sequence, _ := documents.Sequence(ctx, channelA); sequence != 3 {
		t.Fatalf("a manifest left the sequence at %d", sequence)
	}
}

func TestAPageIsOrderedByIdentityAndStopsWhereItIsAsked(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)
	ids := []string{
		"11111111-1111-4111-8111-111111111111",
		"22222222-2222-4222-8222-222222222222",
		"33333333-3333-4333-8333-333333333333",
	}
	// Published in the opposite order, so ordering by identity is visibly not ordering by when.
	for i := len(ids) - 1; i >= 0; i-- {
		put(t, documents, proposal(publisher, ids[i], 1, published.Add(time.Hour)))
	}

	page, err := documents.Page(ctx, channelA, "", 2)
	if err != nil || len(page) != 2 {
		t.Fatalf("the first page is %v (%v)", page, err)
	}
	if page[0].Document.GetProposalId() != ids[0] || page[1].Document.GetProposalId() != ids[1] {
		t.Fatalf("the first page is out of order: %s %s",
			page[0].Document.GetProposalId(), page[1].Document.GetProposalId())
	}
	next, err := documents.Page(ctx, channelA, ids[1], 2)
	if err != nil || len(next) != 1 || next[0].Document.GetProposalId() != ids[2] {
		t.Fatalf("the second page is %v (%v)", next, err)
	}
	if last, _ := documents.Page(ctx, channelA, ids[2], 2); len(last) != 0 {
		t.Fatalf("there is a page after the last one: %v", last)
	}
	// Another publisher's channel is not in it, whatever is asked for.
	if other, _ := documents.Page(ctx, channelB, "", 10); len(other) != 0 {
		t.Fatalf("another channel's page has %d proposals in it", len(other))
	}
}

func TestRetentionRemovesWhatExpiredLongEnoughAgo(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)
	put(t, documents, proposal(publisher, proposalA, 1, published.Add(time.Hour)))
	put(t, documents, proposal(publisher, proposalB, 1, published.Add(48*time.Hour)))

	removed, err := documents.Sweep(ctx, published.Add(24*time.Hour))
	if err != nil || removed != 1 {
		t.Fatalf("the sweep removed %d (%v)", removed, err)
	}
	if held, _ := documents.Proposal(ctx, channelA, proposalA); held != nil {
		t.Fatal("an expired proposal is still served")
	}
	if held, _ := documents.Proposal(ctx, channelA, proposalB); held == nil {
		t.Fatal("a proposal that has not expired was swept")
	}
	// The sequence is untouched: retention is the gateway forgetting, not the publisher changing
	// anything, and a reader must not be told the feed moved because of it.
	if sequence, _ := documents.Sequence(ctx, channelA); sequence != 2 {
		t.Fatalf("the sweep moved the sequence to %d", sequence)
	}
}

// A manifest is stored as its bytes, so what is read back is the whole document the rules built —
// including the Solana networks it declares (SEE-174), which have no column of their own and need
// no migration for the same reason.
func TestAManifestIsReadBackWhole(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)
	err := documents.Write(ctx, func(tx storage.PublicationTx) error {
		_, err := tx.PutManifest(ctx, manifest(publisher, 1), published)
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	held, err := documents.Manifest(ctx, publisher)
	if err != nil || held == nil {
		t.Fatalf("the manifest was not read back (%v)", err)
	}
	if !proto.Equal(held.Document, manifest(publisher, 1)) {
		t.Fatalf("the manifest came back changed:\n%v", held.Document)
	}
}

func TestForgettingAPublisherTakesEverythingItPublished(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)
	register(t, documents, stranger)
	err := documents.Write(ctx, func(tx storage.PublicationTx) error {
		_, err := tx.PutManifest(ctx, manifest(publisher, 1), published)
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	put(t, documents, proposal(publisher, proposalA, 1, published.Add(time.Hour)))
	put(t, documents, proposal(stranger, proposalA, 1, published.Add(time.Hour)))

	if err := documents.Forget(ctx, publisher, channelA); err != nil {
		t.Fatal(err)
	}
	if held, _ := documents.Manifest(ctx, publisher); held != nil {
		t.Fatal("the manifest outlived the publisher")
	}
	if held, _ := documents.Proposal(ctx, channelA, proposalA); held != nil {
		t.Fatal("a proposal outlived the publisher")
	}
	if serverID, _ := documents.PublisherFor(ctx, hashOf(publisher)); serverID != "" {
		t.Fatal("the credential outlived the publisher")
	}
	if sequence, _ := documents.Sequence(ctx, channelA); sequence != 0 {
		t.Fatalf("the channel's sequence outlived the publisher: %d", sequence)
	}
	// And the other publisher is untouched, because the two share nothing but the file.
	if held, _ := documents.Proposal(ctx, channelB, proposalA); held == nil {
		t.Fatal("forgetting one publisher took another's proposal")
	}
	if err := documents.Forget(ctx, publisher, channelA); !errors.Is(err, ErrNoPublisher) {
		t.Fatalf("forgetting a publisher that is gone answered %v", err)
	}
}

func TestACredentialIdIsAHandleAndNotACredential(t *testing.T) {
	hash := hashOf(publisher)
	id := CredentialID(hash)
	if len(id) != 8 {
		t.Fatalf("a credential ID is %q", id)
	}
	// It is a prefix of the hash of the credential: it cannot be turned back into one, and it is
	// short enough to type into a revocation.
	if fmt.Sprintf("%x", hash[:4]) != id {
		t.Fatalf("a credential ID is not the hash's prefix: %q", id)
	}
}

// Version 4 adds a publisher's host to the registration it belongs to (SEE-141). A database
// written before it opens unchanged, keeps every row, and answers "no host was given" for the
// registrations that predate the column.
func TestTheHostColumnIsAddedWithoutLosingAnything(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")

	// A database at version 3: the schema as it was, with a publisher, a credential and a
	// publication in it.
	older, err := sql.Open("sqlite", "file:"+path+"?_pragma=foreign_keys(1)")
	if err != nil {
		t.Fatal(err)
	}
	for _, statement := range []string{schemaV1, schemaV2, schemaV3, `PRAGMA user_version = 3`} {
		if _, err := older.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := older.Exec(
		`INSERT INTO publisher (server_id, label, created_at_ms) VALUES (?, ?, ?)`,
		publisher, "from an older gateway", milliseconds(published)); err != nil {
		t.Fatal(err)
	}
	if _, err := older.Exec(
		`INSERT INTO publisher_credential
		   (credential_hash, server_id, label, created_at_ms, revoked_at_ms)
		 VALUES (?, ?, ?, ?, NULL)`,
		hashOf(publisher), publisher, "old", milliseconds(published)); err != nil {
		t.Fatal(err)
	}
	if err := older.Close(); err != nil {
		t.Fatal(err)
	}

	documents, err := Open(path)
	if err != nil {
		t.Fatalf("a version 3 database could not be opened: %v", err)
	}
	t.Cleanup(func() { _ = documents.Close() })
	ctx := context.Background()

	held, err := documents.Publisher(ctx, publisher)
	if err != nil || held == nil {
		t.Fatalf("the existing registration is gone: %v", err)
	}
	if held.Label != "from an older gateway" || held.Host != "" {
		t.Fatalf("the migrated registration is %+v", held)
	}
	if resolved, err := documents.PublisherFor(ctx, hashOf(publisher)); err != nil ||
		resolved != publisher {
		t.Fatalf("the existing credential stopped resolving: %q, %v", resolved, err)
	}

	// And a host given now is kept across a restart on the same file, which is the whole of what
	// durability means here: the records survive as long as the file does.
	if _, err := documents.Register(ctx, Registration{
		ServerID: stranger, Label: "new", Host: "https://example.com", Publishing: true,
	}, storage.Publishing, hashOf(stranger), published); err != nil {
		t.Fatal(err)
	}
	if err := documents.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = reopened.Close() })
	publishers, err := reopened.Publishers(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(publishers) != 2 {
		t.Fatalf("a restart left %d publisher(s)", len(publishers))
	}
	back, err := reopened.Publisher(ctx, stranger)
	if err != nil || back == nil || back.Host != "https://example.com" {
		t.Fatalf("the host did not survive a restart: %+v, %v", back, err)
	}
}

// Publications is the durable evidence the operator's view has that a publisher has said anything.
// It counts rows and names none of them.
func TestPublicationsCountsWhatAChannelHolds(t *testing.T) {
	documents := openStore(t)
	register(t, documents, publisher)
	ctx := context.Background()
	channel := channelA

	count, err := documents.Publications(ctx, channel)
	if err != nil || count != 0 {
		t.Fatalf("an empty channel holds %d: %v", count, err)
	}
	put(t, documents, proposal(publisher, proposalA, 1, published.Add(time.Hour)))
	put(t, documents, proposal(publisher, proposalB, 1, published.Add(time.Hour)))
	if count, err = documents.Publications(ctx, channel); err != nil || count != 2 {
		t.Fatalf("the channel holds %d: %v", count, err)
	}
	// A republication is an update rather than a second document.
	put(t, documents, proposal(publisher, proposalA, 2, published.Add(time.Hour)))
	if count, err = documents.Publications(ctx, channel); err != nil || count != 2 {
		t.Fatalf("after a republication the channel holds %d: %v", count, err)
	}
}

// Version 6 adds when a publisher last said its own server is running (SEE-150). A database written
// before it opens unchanged, keeps every row, and answers "never" — not "at the epoch" — for the
// registrations that predate the column.
//
// The distinction is why the column is nullable. Both read as offline today, but only one of them is
// true: a registration made before this version has not told this gateway anything, and encoding
// that as a check-in in 1970 would be a fact the store made up.
func TestTheLastSeenColumnIsAddedWithoutLosingAnything(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")

	// A database at version 5: the schema as it was, with a publisher and a credential in it.
	older, err := sql.Open("sqlite", "file:"+path+"?_pragma=foreign_keys(1)")
	if err != nil {
		t.Fatal(err)
	}
	for _, statement := range []string{
		schemaV1, schemaV2, schemaV3, schemaV4, schemaV5, `PRAGMA user_version = 5`,
	} {
		if _, err := older.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := older.Exec(
		`INSERT INTO publisher (server_id, label, created_at_ms, host, publishing, relaying)
		 VALUES (?, ?, ?, '', 1, 0)`,
		publisher, "from an older gateway", milliseconds(published)); err != nil {
		t.Fatal(err)
	}
	if _, err := older.Exec(
		`INSERT INTO publisher_credential
		   (credential_hash, server_id, label, created_at_ms, revoked_at_ms, capability)
		 VALUES (?, ?, ?, ?, NULL, 'publish')`,
		hashOf(publisher), publisher, "old", milliseconds(published)); err != nil {
		t.Fatal(err)
	}
	if err := older.Close(); err != nil {
		t.Fatal(err)
	}

	documents, err := Open(path)
	if err != nil {
		t.Fatalf("a version 5 database could not be opened: %v", err)
	}
	t.Cleanup(func() { _ = documents.Close() })
	ctx := context.Background()

	held, err := documents.Publisher(ctx, publisher)
	if err != nil || held == nil || held.Label != "from an older gateway" {
		t.Fatalf("the existing registration is gone: %+v, %v", held, err)
	}
	if resolved, err := documents.PublisherFor(ctx, hashOf(publisher)); err != nil ||
		resolved != publisher {
		t.Fatalf("the existing credential stopped resolving: %q, %v", resolved, err)
	}
	seen, err := documents.PublisherLastSeen(ctx, publisher)
	if err != nil {
		t.Fatal(err)
	}
	if !seen.IsZero() {
		t.Fatalf("a registration that never checked in reads as seen at %v", seen)
	}

	// And a check-in made now survives a restart on the same file.
	if err := documents.PublisherSeen(ctx, publisher, published); err != nil {
		t.Fatal(err)
	}
	if err := documents.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = reopened.Close() })
	back, err := reopened.PublisherLastSeen(ctx, publisher)
	if err != nil || !back.Equal(published) {
		t.Fatalf("the check-in did not survive a restart: %v, %v", back, err)
	}
}

// A check-in only ever moves forward, and one for a server nothing is registered under is not an
// error: the only way to reach it is with a credential that resolved a moment ago, so the case is a
// registration deleted in between, and there is nothing to record about one that is gone.
func TestACheckInOnlyMovesForwardAndToleratesAStranger(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	if _, err := documents.Register(ctx,
		Registration{ServerID: publisher, Label: "test", Publishing: true},
		storage.Publishing, hashOf(publisher), published); err != nil {
		t.Fatal(err)
	}

	later := published.Add(time.Hour)
	if err := documents.PublisherSeen(ctx, publisher, later); err != nil {
		t.Fatal(err)
	}
	if err := documents.PublisherSeen(ctx, publisher, published); err != nil {
		t.Fatal(err)
	}
	seen, err := documents.PublisherLastSeen(ctx, publisher)
	if err != nil {
		t.Fatal(err)
	}
	if !seen.Equal(later) {
		t.Fatalf("an earlier check-in moved the instant back to %v", seen)
	}

	if err := documents.PublisherSeen(ctx, stranger, later); err != nil {
		t.Fatalf("a check-in for an unregistered server failed: %v", err)
	}
	unknown, err := documents.PublisherLastSeen(ctx, stranger)
	if err != nil {
		t.Fatal(err)
	}
	if !unknown.IsZero() {
		t.Fatalf("an unregistered server reads as seen at %v", unknown)
	}
}

// Every registration that existed before restricted feeds did is public, with no authentication
// origin and no epoch (SEE-156). That is the migration's whole promise: a gateway that upgrades
// does not quietly restrict a feed, and does not quietly open one either.
func TestTheAccessColumnsAreAddedWithoutLosingAnythingOrChangingWhoMayRead(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")

	// A database at version 6: the schema as it was, with a publisher and a credential in it.
	older, err := sql.Open("sqlite", "file:"+path+"?_pragma=foreign_keys(1)")
	if err != nil {
		t.Fatal(err)
	}
	for _, statement := range []string{
		schemaV1, schemaV2, schemaV3, schemaV4, schemaV5, schemaV6, `PRAGMA user_version = 6`,
	} {
		if _, err := older.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := older.Exec(
		`INSERT INTO publisher (server_id, label, created_at_ms, host, publishing, relaying)
		 VALUES (?, ?, ?, '', 1, 0)`,
		publisher, "from an older gateway", milliseconds(published)); err != nil {
		t.Fatal(err)
	}
	if _, err := older.Exec(
		`INSERT INTO publisher_credential
		   (credential_hash, server_id, label, created_at_ms, revoked_at_ms, capability)
		 VALUES (?, ?, ?, ?, NULL, 'publish')`,
		hashOf(publisher), publisher, "old", milliseconds(published)); err != nil {
		t.Fatal(err)
	}
	if err := older.Close(); err != nil {
		t.Fatal(err)
	}

	documents, err := Open(path)
	if err != nil {
		t.Fatalf("a version 6 database could not be opened: %v", err)
	}
	t.Cleanup(func() { _ = documents.Close() })
	ctx := context.Background()

	held, err := documents.Publisher(ctx, publisher)
	if err != nil || held == nil || held.Label != "from an older gateway" {
		t.Fatalf("the existing registration is gone: %+v, %v", held, err)
	}
	if resolved, err := documents.PublisherFor(ctx, hashOf(publisher)); err != nil ||
		resolved != publisher {
		t.Fatalf("the existing credential stopped resolving: %q, %v", resolved, err)
	}
	access, err := documents.Access(ctx, publisher)
	if err != nil {
		t.Fatal(err)
	}
	if access.Restricted() || access.AuthOrigin != "" || access.Epoch != 0 {
		t.Fatalf("a registration made before SEE-156 migrated to %+v", access)
	}
	if grants, err := documents.Grants(ctx, publisher); err != nil || len(grants) != 0 {
		t.Fatalf("a migrated registration has grants: %v, %v", grants, err)
	}

	// And the operator's own decision, made after the migration, survives a restart on the file.
	restricted := storage.Access{
		Policy:     storage.RestrictedAccess,
		AuthOrigin: "https://auth.example.com",
	}
	if err := documents.SetAccess(ctx, publisher, restricted); err != nil {
		t.Fatal(err)
	}
	if err := documents.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = reopened.Close() })
	back, err := reopened.Access(ctx, publisher)
	if err != nil || !back.Restricted() || back.AuthOrigin != restricted.AuthOrigin {
		t.Fatalf("the registration did not survive a restart: %+v, %v", back, err)
	}
}
