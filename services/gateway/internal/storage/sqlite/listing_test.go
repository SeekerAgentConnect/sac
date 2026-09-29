package sqlite

import (
	"context"
	"database/sql"
	"errors"
	"path/filepath"
	"testing"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// Version 8 adds a feed's Discover listing (SEE-176). A database written by version 7 opens with
// every registration unlisted and undescribed, so an upgrade recommends nothing nobody chose, and
// changes nothing about who may read a feed.
func TestTheListingColumnsMigrateEveryRegistrationToUnlisted(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")
	older, err := sql.Open("sqlite", "file:"+path+"?_pragma=foreign_keys(1)")
	if err != nil {
		t.Fatal(err)
	}
	for _, statement := range []string{
		schemaV1, schemaV2, schemaV3, schemaV4, schemaV5, schemaV6, schemaV7,
		`PRAGMA user_version = 7`,
	} {
		if _, err := older.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := older.Exec(
		`INSERT INTO publisher (server_id, label, created_at_ms, host, publishing, relaying,
		                        access_policy, auth_origin, access_epoch)
		 VALUES (?, ?, ?, '', 1, 0, 'restricted', 'https://auth.example.com', 3)`,
		publisher, "from an older gateway", milliseconds(published)); err != nil {
		t.Fatal(err)
	}
	if _, err := older.Exec(
		`INSERT INTO manifest (server_id, settings_revision, document, updated_at_ms)
		 VALUES (?, 1, ?, ?)`, publisher, mustMarshal(t, manifest(publisher, 1)),
		milliseconds(published)); err != nil {
		t.Fatal(err)
	}
	if err := older.Close(); err != nil {
		t.Fatal(err)
	}

	documents, err := Open(path)
	if err != nil {
		t.Fatalf("a version 7 database could not be opened: %v", err)
	}
	t.Cleanup(func() { _ = documents.Close() })
	ctx := context.Background()
	held, err := documents.Publisher(ctx, publisher)
	if err != nil || held == nil {
		t.Fatalf("the existing registration is gone: %v", err)
	}
	if held.Listing != (storage.Listing{}) {
		t.Fatalf("a registration made before SEE-176 migrated to %+v", held.Listing)
	}
	if !held.Access.Restricted() || held.Access.Epoch != 3 {
		t.Fatalf("the migration moved the access policy: %+v", held.Access)
	}
	if listed, err := documents.Listed(ctx); err != nil || len(listed) != 0 {
		t.Fatalf("an upgrade recommends %v (%v)", listed, err)
	}
}

// The operator's listing survives a restart, a republication and a change of access, and changing
// it moves nothing else.
func TestAListingIsTheOperatorsAndSurvivesARestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")
	documents, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
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
	// A republication writes the manifest table and not the registration.
	putManifest(t, documents, 2)
	if err := documents.Close(); err != nil {
		t.Fatal(err)
	}

	reopened, err := Open(path)
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
	for _, bad := range []string{"a\x00b", "tab\there", string(make([]rune, storage.MaxDescriptionRunes+1))} {
		if err := reopened.SetListing(ctx, publisher, storage.Listing{Description: bad}); err == nil {
			t.Fatalf("the description %q was stored", bad)
		}
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
