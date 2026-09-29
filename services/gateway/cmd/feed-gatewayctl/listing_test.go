package main

import (
	"context"
	"path/filepath"
	"strings"
	"testing"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage/sqlite"
)

func listingOf(t *testing.T, path string) storage.Listing {
	t.Helper()
	documents, err := sqlite.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = documents.Close() }()
	held, err := documents.Publisher(context.Background(), publisher)
	if err != nil || held == nil {
		t.Fatalf("no registration: %v", err)
	}
	return held.Listing
}

// The CLI does what the admin page does (SEE-176): register may list a feed, `listing` edits the
// flag and the description independently, and neither touches who may read it.
func TestListingAFeedFromTheCommandLine(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")
	printed := command(t, "register", "--database", path, "--server", publisher,
		"--access", "restricted", "--auth-origin", "https://signals.example.com")
	if !strings.Contains(printed, "unlisted in app recommendations") {
		t.Fatalf("register did not say the feed is unlisted:\n%s", printed)
	}
	if listing := listingOf(t, path); listing != (storage.Listing{}) {
		t.Fatalf("a registration that said nothing is listed: %+v", listing)
	}

	command(t, "listing", "--database", path, "--server", publisher,
		"--description", "  Daily swap ideas.  ")
	if listing := listingOf(t, path); listing != (storage.Listing{Description: "Daily swap ideas."}) {
		t.Fatalf("editing the description holds %+v", listing)
	}
	printed = command(t, "listing", "--database", path, "--server", publisher, "--recommend", "yes")
	if !strings.Contains(printed, "Who may read the feed has not changed") {
		t.Fatalf("listing did not say what it left alone:\n%s", printed)
	}
	if listing := listingOf(t, path); listing !=
		(storage.Listing{Recommended: true, Description: "Daily swap ideas."}) {
		t.Fatalf("switching the flag holds %+v", listing)
	}
	if !strings.Contains(command(t, "list", "--database", path), "listed in app recommendations") {
		t.Fatal("list does not show the listing")
	}
	command(t, "listing", "--database", path, "--server", publisher, "--recommend", "no")
	if listing := listingOf(t, path); listing != (storage.Listing{Description: "Daily swap ideas."}) {
		t.Fatalf("unlisting holds %+v", listing)
	}

	documents, err := sqlite.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	held, err := documents.Publisher(context.Background(), publisher)
	_ = documents.Close()
	if err != nil || !held.Access.Restricted() || held.Access.Epoch != 0 {
		t.Fatalf("listing moved the access policy: %+v, %v", held.Access, err)
	}

	refuses(t, "listing", "--database", path, "--server", publisher)
	refuses(t, "listing", "--database", path, "--server", publisher, "--recommend", "maybe")
	refuses(t, "listing", "--database", path, "--server", publisher, "--description", "bell\a")
	refuses(t, "rotate", "--database", path, "--server", publisher, "--recommend", "yes")
}

func TestARelayOnlyServerCannotBeRecommended(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")
	refuses(t, "register", "--database", path, "--server", publisher, "--for", "relay",
		"--recommend", "yes")
	command(t, "register", "--database", path, "--server", publisher, "--for", "relay")
	refuses(t, "listing", "--database", path, "--server", publisher, "--recommend", "yes")
	printed := command(t, "register", "--database", filepath.Join(t.TempDir(), "b.db"),
		"--server", publisher, "--recommend", "yes", "--description", "Listed at birth.")
	if !strings.Contains(printed, `listed in app recommendations, described as "Listed at birth."`) {
		t.Fatalf("register did not say the feed is listed:\n%s", printed)
	}
}
