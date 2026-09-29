package postgres

import (
	"bytes"
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"os"
	"testing"
	"time"

	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"

	proposalv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/proposal/v1"
	serverv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// These run against a real Postgres, named by BROADCAST_TEST_DATABASE_URL, and skip when there is
// none. A fake would prove nothing here: what is being checked is that this dialect answers the
// same questions the SQLite store answers, and the only thing that knows is the database.
//
// Every case below is one the two implementations must agree on, and most of them are where the
// port could have gone quietly wrong — the upsert that advances a channel's sequence, the byte
// comparison a credential ID is matched by, the collation a paged walk is ordered under, the
// partial index a rebinding depends on. The behaviours that are the same code in both stores
// (which proto encoding a row holds) are covered once, in the SQLite tests.

const (
	publisher = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
	stranger  = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
	proposalA = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
	proposalB = "a1b2c3d4-e5f6-4789-abcd-0123456789ab"
	channelA  = "server/" + publisher
	channelB  = "server/" + stranger
)

var published = time.Date(2026, 9, 17, 9, 0, 0, 0, time.UTC)

// openStore opens the configured database and empties it. The tables are truncated rather than
// dropped so the schema is created once and every case still starts from nothing; they do not run
// in parallel, and the store's own advisory lock is what keeps a truncation away from a write.
func openStore(t *testing.T) *Store {
	t.Helper()
	dsn := os.Getenv("BROADCAST_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("set BROADCAST_TEST_DATABASE_URL to a Postgres database to run the store tests")
	}
	ctx := context.Background()
	documents, err := Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = documents.Close() })
	// relay_installation cascades to relay_binding and publisher to everything it owns, but naming
	// them all keeps the reset independent of which foreign key happens to exist.
	if _, err := documents.writer.ExecContext(ctx,
		`TRUNCATE `+Schema+`.relay_binding, `+Schema+`.relay_installation, `+Schema+`.notice,
		         `+Schema+`.proposal, `+Schema+`.manifest, `+Schema+`.publisher_credential,
		         `+Schema+`.channel_sequence, `+Schema+`.publisher RESTART IDENTITY CASCADE`); err != nil {
		t.Fatal(err)
	}
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
		Operation:  "transfer",
		PluginId:   "core.transfer",
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

func TestTheSchemaIsCreatedOnceAndOpeningAgainChangesNothing(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)
	put(t, documents, proposal(publisher, proposalA, 1, published.Add(time.Hour)))

	// A second Open finds the version already stamped and leaves every row where it was, which is
	// what makes a migration-on-start safe to run on every start and on every instance.
	again, err := Open(ctx, os.Getenv("BROADCAST_TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = again.Close() }()
	held, err := again.Proposal(ctx, channelA, proposalA)
	if err != nil || held == nil {
		t.Fatalf("the proposal did not survive a second open: %v", err)
	}
	var version int
	if err := again.reader.QueryRowContext(ctx,
		`SELECT version FROM `+Schema+`.schema_version`).Scan(&version); err != nil {
		t.Fatal(err)
	}
	if version != Version {
		t.Fatalf("the schema is at version %d, this build writes %d", version, Version)
	}
}

func TestADatabaseFromALaterVersionIsRefused(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	if _, err := documents.writer.ExecContext(ctx,
		`UPDATE `+Schema+`.schema_version SET version = $1`, Version+1); err != nil {
		t.Fatal(err)
	}
	// Put it back however this ends, so one refused open does not fail every later case.
	t.Cleanup(func() {
		_, _ = documents.writer.ExecContext(context.Background(),
			`UPDATE `+Schema+`.schema_version SET version = $1`, Version)
	})
	later, err := Open(ctx, os.Getenv("BROADCAST_TEST_DATABASE_URL"))
	if err == nil {
		_ = later.Close()
		t.Fatal("a database written by a newer gateway was opened")
	}
	if !errors.Is(err, ErrNewerSchema) {
		t.Fatalf("refused with %v, expected ErrNewerSchema", err)
	}
}

func TestACredentialIsRotatedWithoutAnOutageAndRevokedForGood(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)

	first := hashOf(publisher)
	second := hashOf("rotated")
	id, err := documents.AddCredential(ctx, publisher, "rotated", storage.Publishing, second, published)
	if err != nil {
		t.Fatal(err)
	}
	// Both work in between: that is what makes rotation two steps rather than an outage.
	for _, hash := range [][]byte{first, second} {
		who, err := documents.PublisherFor(ctx, hash)
		if err != nil || who != publisher {
			t.Fatalf("a live credential resolved to %q (%v)", who, err)
		}
	}
	// Revoking by the ID the operator is shown, which is a prefix of the hash rather than the hash.
	revoked, err := documents.Revoke(ctx, id, published.Add(time.Minute))
	if err != nil || revoked != 1 {
		t.Fatalf("revoked %d credentials (%v), expected 1", revoked, err)
	}
	who, err := documents.PublisherFor(ctx, second)
	if err != nil || who != "" {
		t.Fatalf("a revoked credential resolved to %q (%v)", who, err)
	}
	if who, err := documents.PublisherFor(ctx, first); err != nil || who != publisher {
		t.Fatalf("revoking one credential took another with it: %q (%v)", who, err)
	}
	// A revoked credential is kept, so an operator can still see that it existed.
	credentials, err := documents.Credentials(ctx, publisher)
	if err != nil || len(credentials) != 2 {
		t.Fatalf("the publisher holds %d credentials (%v), expected 2", len(credentials), err)
	}
	for _, one := range credentials {
		if one.ID == id && one.RevokedAt == nil {
			t.Fatal("the revoked credential has no revocation instant")
		}
	}
}

func TestACredentialCannotBeAddedToAPublisherThatIsNotThere(t *testing.T) {
	documents := openStore(t)
	_, err := documents.AddCredential(context.Background(), stranger, "x",
		storage.Publishing, hashOf("x"), published)
	if !errors.Is(err, ErrNoPublisher) {
		t.Fatalf("adding to an unregistered publisher gave %v", err)
	}
}

func TestRegisteringAnIdentityThatIsAlreadyHeldIsRefused(t *testing.T) {
	documents := openStore(t)
	register(t, documents, publisher)
	_, err := documents.Register(context.Background(),
		Registration{ServerID: publisher, Label: "again", Publishing: true},
		storage.Publishing, hashOf("again"), published)
	if !errors.Is(err, ErrPublisherExists) {
		t.Fatalf("registering a held identity gave %v", err)
	}
}

func TestADocumentAndItsNoticeCommitTogetherOrNotAtAll(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)

	refused := errors.New("the handler changed its mind")
	err := documents.Write(ctx, func(tx storage.PublicationTx) error {
		if _, err := tx.PutProposal(ctx, proposal(publisher, proposalA, 1, published.Add(time.Hour)), published); err != nil {
			return err
		}
		return refused
	})
	if !errors.Is(err, refused) {
		t.Fatalf("the write returned %v", err)
	}
	held, err := documents.Proposal(ctx, channelA, proposalA)
	if err != nil || held != nil {
		t.Fatalf("a rolled-back publication is visible (%v)", err)
	}
	pending, err := documents.Pending(ctx)
	if err != nil || pending != 0 {
		t.Fatalf("a rolled-back publication left %d notices (%v)", pending, err)
	}
	// The sequence is part of the same transaction, so it did not move either.
	sequence, err := documents.Sequence(ctx, channelA)
	if err != nil || sequence != 0 {
		t.Fatalf("a rolled-back publication moved the sequence to %d (%v)", sequence, err)
	}
}

func TestTwoPublicationsWaitingToBeSentAreOneNotice(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)
	put(t, documents, proposal(publisher, proposalA, 1, published.Add(time.Hour)))
	put(t, documents, proposal(publisher, proposalA, 2, published.Add(time.Hour)))

	pending, err := documents.Pending(ctx)
	if err != nil || pending != 1 {
		t.Fatalf("two publications left %d notices (%v), expected 1", pending, err)
	}
	notices, err := documents.Notices(ctx, published.Add(time.Minute), 10)
	if err != nil || len(notices) != 1 {
		t.Fatalf("read %d notices (%v), expected 1", len(notices), err)
	}
	if notices[0].Revision != 2 {
		t.Fatalf("the collapsed notice is about revision %d, expected the later one", notices[0].Revision)
	}
	// Conditional on the revision: the one that was sent, not whatever the row says now.
	removed, err := documents.NoticeSent(ctx, notices[0].ID, 1)
	if err != nil || removed {
		t.Fatalf("a notice that had moved on was removed by a stale send (%v)", err)
	}
	removed, err = documents.NoticeSent(ctx, notices[0].ID, 2)
	if err != nil || !removed {
		t.Fatalf("the notice that was sent was not removed (%v)", err)
	}
}

func TestAFailedNoticeComesBackLater(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)
	put(t, documents, proposal(publisher, proposalA, 1, published.Add(time.Hour)))

	notices, err := documents.Notices(ctx, published, 10)
	if err != nil || len(notices) != 1 {
		t.Fatalf("read %d notices (%v)", len(notices), err)
	}
	later := published.Add(time.Hour)
	if err := documents.NoticeDeferred(ctx, notices[0].ID, later); err != nil {
		t.Fatal(err)
	}
	if due, err := documents.Notices(ctx, published.Add(time.Minute), 10); err != nil || len(due) != 0 {
		t.Fatalf("a deferred notice is due early: %d (%v)", len(due), err)
	}
	due, err := documents.Notices(ctx, later, 10)
	if err != nil || len(due) != 1 {
		t.Fatalf("a deferred notice did not come back: %d (%v)", len(due), err)
	}
	if due[0].Attempts != 1 {
		t.Fatalf("the attempt was not counted: %d", due[0].Attempts)
	}
	if err := documents.NoticeDropped(ctx, due[0].ID); err != nil {
		t.Fatal(err)
	}
	if pending, err := documents.Pending(ctx); err != nil || pending != 0 {
		t.Fatalf("a dropped notice is still pending: %d (%v)", pending, err)
	}
}

func TestEachChannelCountsItsOwnPublications(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)
	register(t, documents, stranger)

	// The upsert that advances a sequence has to read the row it is replacing rather than the one
	// it proposed. Reading the proposed row would make every publication the channel's first, and
	// this is the case that would say so.
	for revision := uint64(1); revision <= 3; revision++ {
		if sequence := put(t, documents, proposal(publisher, proposalA, revision, published.Add(time.Hour))); sequence != revision {
			t.Fatalf("publication %d was given sequence %d", revision, sequence)
		}
	}
	if sequence := put(t, documents, proposal(stranger, proposalB, 1, published.Add(time.Hour))); sequence != 1 {
		t.Fatalf("another channel's first publication was given sequence %d", sequence)
	}
	held, err := documents.Sequence(ctx, channelA)
	if err != nil || held != 3 {
		t.Fatalf("channel A is at %d (%v), expected 3", held, err)
	}
	other, err := documents.Sequence(ctx, channelB)
	if err != nil || other != 1 {
		t.Fatalf("channel B is at %d (%v), expected 1", other, err)
	}
	if none, err := documents.Sequence(ctx, "server/nobody"); err != nil || none != 0 {
		t.Fatalf("an unpublished channel is at %d (%v), expected 0", none, err)
	}
}

func TestAManifestIsReadBackAndMovesTheChannelsSequence(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)

	err := documents.Write(ctx, func(tx storage.PublicationTx) error {
		sequence, err := tx.PutManifest(ctx, manifest(publisher, 1), published)
		if err != nil {
			return err
		}
		if sequence != 1 {
			return fmt.Errorf("the manifest was given sequence %d", sequence)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	held, err := documents.Manifest(ctx, publisher)
	if err != nil || held == nil {
		t.Fatalf("the manifest was not read back (%v)", err)
	}
	if held.Document.GetSettingsRevision() != 1 {
		t.Fatalf("the manifest came back at revision %d", held.Document.GetSettingsRevision())
	}
	// The whole document, not the one column the table also keeps: the stored bytes are the only
	// copy of everything else, the supported networks included (SEE-174).
	if !proto.Equal(held.Document, manifest(publisher, 1)) {
		t.Fatalf("the manifest came back changed:\n%v", held.Document)
	}
	if none, err := documents.Manifest(ctx, stranger); err != nil || none != nil {
		t.Fatalf("a server that published no manifest has one (%v)", err)
	}
}

func TestAPageIsOrderedByIdentityAndStopsWhereItIsAsked(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)

	// Identities chosen so that a locale-aware collation would order them differently from bytes:
	// a hyphen and an underscore are punctuation most locales ignore at the first pass, and a
	// walk whose ORDER BY disagreed with its own cursor would skip a document.
	ids := []string{"a-1", "a_2", "ab3", "b-4"}
	for _, id := range ids {
		put(t, documents, proposal(publisher, id, 1, published.Add(time.Hour)))
	}
	var walked []string
	after := ""
	for {
		page, err := documents.Page(ctx, channelA, after, 2)
		if err != nil {
			t.Fatal(err)
		}
		if len(page) == 0 {
			break
		}
		for _, one := range page {
			walked = append(walked, one.Document.GetProposalId())
		}
		after = page[len(page)-1].Document.GetProposalId()
	}
	if fmt.Sprint(walked) != fmt.Sprint(ids) {
		t.Fatalf("the walk read\n%v\nexpected\n%v", walked, ids)
	}
}

func TestRetentionRemovesWhatExpiredAndKeepsWhatHasNot(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)
	put(t, documents, proposal(publisher, proposalA, 1, published.Add(time.Hour)))
	put(t, documents, proposal(publisher, proposalB, 1, published.Add(48*time.Hour)))

	removed, err := documents.Sweep(ctx, published.Add(24*time.Hour))
	if err != nil || removed != 1 {
		t.Fatalf("the sweep removed %d (%v), expected 1", removed, err)
	}
	if gone, err := documents.Proposal(ctx, channelA, proposalA); err != nil || gone != nil {
		t.Fatalf("the expired proposal is still served (%v)", err)
	}
	if kept, err := documents.Proposal(ctx, channelA, proposalB); err != nil || kept == nil {
		t.Fatalf("a proposal that had not expired was swept (%v)", err)
	}
	// Retention does not move the public sequence.
	if sequence, err := documents.Sequence(ctx, channelA); err != nil || sequence != 2 {
		t.Fatalf("the sweep moved the sequence to %d (%v)", sequence, err)
	}
}

func TestForgettingAPublisherTakesEverythingItPublished(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)
	register(t, documents, stranger)
	put(t, documents, proposal(publisher, proposalA, 1, published.Add(time.Hour)))
	put(t, documents, proposal(stranger, proposalB, 1, published.Add(time.Hour)))

	if err := documents.Forget(ctx, publisher, channelA); err != nil {
		t.Fatal(err)
	}
	if known, err := documents.PublisherExists(ctx, publisher); err != nil || known {
		t.Fatalf("the publisher is still registered (%v)", err)
	}
	if gone, err := documents.Proposal(ctx, channelA, proposalA); err != nil || gone != nil {
		t.Fatalf("its proposal is still served (%v)", err)
	}
	if sequence, err := documents.Sequence(ctx, channelA); err != nil || sequence != 0 {
		t.Fatalf("its channel sequence survived at %d (%v)", sequence, err)
	}
	if who, err := documents.PublisherFor(ctx, hashOf(publisher)); err != nil || who != "" {
		t.Fatalf("its credential still resolves to %q (%v)", who, err)
	}
	// The other publisher is untouched, which is the whole point of naming one.
	if kept, err := documents.Proposal(ctx, channelB, proposalB); err != nil || kept == nil {
		t.Fatalf("forgetting one publisher took another's document (%v)", err)
	}
	if err := documents.Forget(ctx, publisher, channelA); !errors.Is(err, ErrNoPublisher) {
		t.Fatalf("forgetting an unregistered publisher gave %v", err)
	}
}

func TestPublicationsCountsWhatAChannelHolds(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)
	if count, err := documents.Publications(ctx, channelA); err != nil || count != 0 {
		t.Fatalf("an empty channel holds %d (%v)", count, err)
	}
	put(t, documents, proposal(publisher, proposalA, 1, published.Add(time.Hour)))
	put(t, documents, proposal(publisher, proposalB, 1, published.Add(time.Hour)))
	put(t, documents, proposal(publisher, proposalA, 2, published.Add(time.Hour)))
	count, err := documents.Publications(ctx, channelA)
	if err != nil || count != 2 {
		t.Fatalf("the channel holds %d (%v), expected 2 — a republication is not a second document", count, err)
	}
}

func TestACapabilityIsASwitchAndRevocationIsNot(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)

	if err := documents.SetCapabilities(ctx, publisher, false, false); err != nil {
		t.Fatal(err)
	}
	if who, err := documents.PublisherFor(ctx, hashOf(publisher)); err != nil || who != "" {
		t.Fatalf("a disabled publisher's credential resolved to %q (%v)", who, err)
	}
	if err := documents.SetCapabilities(ctx, publisher, true, false); err != nil {
		t.Fatal(err)
	}
	if who, err := documents.PublisherFor(ctx, hashOf(publisher)); err != nil || who != publisher {
		t.Fatalf("enabling again did not restore the same credential: %q (%v)", who, err)
	}
	if err := documents.SetCapabilities(ctx, stranger, true, true); !errors.Is(err, ErrNoPublisher) {
		t.Fatalf("setting capabilities on an unregistered server gave %v", err)
	}
	held, err := documents.Publisher(ctx, publisher)
	if err != nil || held == nil {
		t.Fatalf("the registration is not readable (%v)", err)
	}
	if !held.Publishing || held.Relaying || held.Active != 1 || held.ActiveRelay != 0 {
		t.Fatalf("the registration reads %+v", *held)
	}
	listed, err := documents.Publishers(ctx)
	if err != nil || len(listed) != 1 || listed[0].ServerID != publisher {
		t.Fatalf("the listing is %+v (%v)", listed, err)
	}
	if none, err := documents.Publisher(ctx, stranger); err != nil || none != nil {
		t.Fatalf("an unregistered server is listed (%v)", err)
	}
}

func TestACredentialIdIsAHandleAndNotACredential(t *testing.T) {
	documents := openStore(t)
	register(t, documents, publisher)
	credentials, err := documents.Credentials(context.Background(), publisher)
	if err != nil || len(credentials) != 1 {
		t.Fatalf("read %d credentials (%v)", len(credentials), err)
	}
	hash := hashOf(publisher)
	if id := credentials[0].ID; id == fmt.Sprintf("%x", hash) || len(id) >= len(hash)*2 {
		t.Fatalf("the credential ID is the whole hash: %s", id)
	}
	if bytes.Contains([]byte(credentials[0].ID), hash) {
		t.Fatal("the credential ID carries the hash")
	}
}

// The live schema, in full, pinned the way the SQLite store's is by the gateway's boundary test.
// Nothing may be added to it without being argued for here, by name, where someone would ask why.
//
// relay_installation and relay_binding are SEE-144's private push routing. They hold an
// installation identity this gateway minted, the hash of the secret that proves ownership of it,
// where to send a wake-up, and which registered servers one device agreed may wake it. There is no
// column here for a request, an approval, a signature, a result, a wallet, an amount or anything
// an owner decided, and a phone woken through this relay goes and reads its own server for all of
// that.
//
// schema_version is this implementation's own and is not state about anyone: it is the one row
// that says which migration has run, and it replaces SQLite's PRAGMA user_version. It is sealed
// with the rest all the same, so "which tables have RLS" has a boring answer.
func TestTheStoreKeepsOnlyPublicFeedStateAndTheRelaysRouting(t *testing.T) {
	documents := openStore(t)
	rows, err := documents.reader.QueryContext(context.Background(),
		`SELECT table_name FROM information_schema.tables
		  WHERE table_schema = $1 AND table_type = 'BASE TABLE' ORDER BY table_name`, Schema)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = rows.Close() }()
	var names []string
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			t.Fatal(err)
		}
		names = append(names, name)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	expected := []string{
		"channel_sequence", "manifest", "notice", "proposal", "publisher", "publisher_credential",
		"relay_binding", "relay_installation", "schema_version",
	}
	if fmt.Sprint(names) != fmt.Sprint(expected) {
		t.Fatalf("the store holds %v, expected %v", names, expected)
	}
}

// Nothing of this service's is in the public schema, and every table it does own has row-level
// security on with no policy.
//
// This is the difference between "a database nobody should read" and "a database nobody can". A
// Supabase project serves its public schema over PostgREST to whoever holds the publishable key,
// which is a key that is meant to be published — and this store holds credential hashes and the
// current push target of every enrolled phone. A table in another schema is not reachable that
// way at all; row-level security underneath it is what still holds if somebody exposes the schema
// later.
func TestNothingIsReachableThroughThePublicSchema(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()

	var loose int
	if err := documents.reader.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM information_schema.tables
		  WHERE table_schema = 'public' AND table_name IN (
		    'publisher', 'publisher_credential', 'manifest', 'proposal', 'channel_sequence',
		    'notice', 'relay_installation', 'relay_binding', 'schema_version')`).Scan(&loose); err != nil {
		t.Fatal(err)
	}
	if loose != 0 {
		t.Fatalf("%d of this store's tables are in the public schema", loose)
	}

	rows, err := documents.reader.QueryContext(ctx,
		`SELECT c.relname, c.relrowsecurity,
		        (SELECT COUNT(*) FROM pg_policy p WHERE p.polrelid = c.oid)
		   FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
		  WHERE n.nspname = $1 AND c.relkind = 'r' ORDER BY c.relname`, Schema)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = rows.Close() }()
	var unprotected []string
	for rows.Next() {
		var (
			name     string
			enabled  bool
			policies int
		)
		if err := rows.Scan(&name, &enabled, &policies); err != nil {
			t.Fatal(err)
		}
		// Every table, with no exception: schema_version holds no state about anyone, but a table
		// that is the exception is a table somebody has to remember is the exception.
		if !enabled || policies != 0 {
			unprotected = append(unprotected,
				fmt.Sprintf("%s (rls=%v, policies=%d)", name, enabled, policies))
		}
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	if len(unprotected) > 0 {
		t.Fatalf("these tables are not sealed: %v", unprotected)
	}
}
