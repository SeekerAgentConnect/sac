package postgres

import (
	"context"
	"errors"
	"os"
	"testing"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// Version 4 adds a feed's Discover listing (SEE-176), with the SQLite store's version 8 as the
// reference. A database at version 3 — rebuilt here by taking the two columns away again — opens
// with every registration unlisted and undescribed and its access policy untouched.
func TestTheListingColumnsMigrateEveryRegistrationToUnlisted(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)
	restricted := storage.Access{Policy: storage.RestrictedAccess, AuthOrigin: "https://auth.example.com"}
	if err := documents.SetAccess(ctx, publisher, restricted); err != nil {
		t.Fatal(err)
	}
	for _, statement := range []string{
		`ALTER TABLE ` + Schema + `.publisher DROP COLUMN show_in_recommendations`,
		`ALTER TABLE ` + Schema + `.publisher DROP COLUMN public_description`,
		`UPDATE ` + Schema + `.schema_version SET version = 3`,
	} {
		if _, err := documents.writer.ExecContext(ctx, statement); err != nil {
			t.Fatal(err)
		}
	}
	if err := documents.Close(); err != nil {
		t.Fatal(err)
	}
	reopened, err := Open(ctx, os.Getenv("BROADCAST_TEST_DATABASE_URL"))
	if err != nil {
		t.Fatalf("a version 3 database could not be opened: %v", err)
	}
	t.Cleanup(func() { _ = reopened.Close() })
	held, err := reopened.Publisher(ctx, publisher)
	if err != nil || held == nil {
		t.Fatalf("the existing registration is gone: %v", err)
	}
	if held.Listing != (storage.Listing{}) {
		t.Fatalf("a registration made before SEE-176 migrated to %+v", held.Listing)
	}
	if !held.Access.Restricted() || held.Access.AuthOrigin != restricted.AuthOrigin {
		t.Fatalf("the migration moved the access policy: %+v", held.Access)
	}
}

// The operator's listing survives a restart and a republication, and changing it moves nothing
// else.
func TestAListingIsTheOperatorsAndSurvivesARestart(t *testing.T) {
	documents := openStore(t)
	ctx := context.Background()
	register(t, documents, publisher)
	putManifest(t, documents, 1)
	listing := storage.Listing{Recommended: true, Description: "Two lines.\nOf plain text."}
	if err := documents.SetListing(ctx, publisher, listing); err != nil {
		t.Fatal(err)
	}
	before, err := documents.Access(ctx, publisher)
	if err != nil {
		t.Fatal(err)
	}
	putManifest(t, documents, 2)
	if err := documents.Close(); err != nil {
		t.Fatal(err)
	}

	reopened, err := Open(ctx, os.Getenv("BROADCAST_TEST_DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = reopened.Close() })
	held, err := reopened.Publisher(ctx, publisher)
	if err != nil || held.Listing != listing {
		t.Fatalf("the listing did not survive: %+v, %v", held, err)
	}
	after, err := reopened.Access(ctx, publisher)
	if err != nil || after != before {
		t.Fatalf("a listing moved the access policy: %+v → %+v (%v)", before, after, err)
	}
	listed, err := reopened.Listed(ctx)
	if err != nil || len(listed) != 1 || listed[0].ServerID != publisher ||
		listed[0].Description != listing.Description ||
		listed[0].Manifest.GetSettingsRevision() != 2 {
		t.Fatalf("the catalog candidates are %+v (%v)", listed, err)
	}
	if err := reopened.SetListing(ctx, stranger, listing); !errors.Is(err, storage.ErrNoPublisher) {
		t.Fatalf("listing an unregistered server answered %v", err)
	}
	if err := reopened.SetListing(ctx, publisher, storage.Listing{Description: "a\x00b"}); err == nil {
		t.Fatal("a control character was stored")
	}
	if err := reopened.SetListing(ctx, publisher, storage.Listing{}); err != nil {
		t.Fatal(err)
	}
	if listed, err := reopened.Listed(ctx); err != nil || len(listed) != 0 {
		t.Fatalf("an unlisted feed is still a candidate: %v (%v)", listed, err)
	}
}

func putManifest(t *testing.T, documents *Store, revision uint64) {
	t.Helper()
	if err := documents.Write(context.Background(), func(tx storage.PublicationTx) error {
		_, err := tx.PutManifest(context.Background(), manifest(publisher, revision), published)
		return err
	}); err != nil {
		t.Fatal(err)
	}
}
