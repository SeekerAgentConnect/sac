package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/credential"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage/sqlite"
)

const publisher = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

// The tool and the gateway have to agree about what a credential is: one creates it, the other
// resolves it, and neither ever holds the thing itself. These tests are that agreement.
func command(t *testing.T, arguments ...string) string {
	t.Helper()
	out := &bytes.Buffer{}
	if err := run(arguments, out); err != nil {
		t.Fatalf("%v failed: %v", arguments, err)
	}
	return out.String()
}

func refuses(t *testing.T, arguments ...string) error {
	t.Helper()
	err := run(arguments, &bytes.Buffer{})
	if err == nil {
		t.Fatalf("%v was accepted", arguments)
	}
	return err
}

// credentialOf reads the credential out of what register printed, which is the only place it ever
// appears.
func credentialOf(t *testing.T, printed string) string {
	t.Helper()
	for _, line := range strings.Split(printed, "\n") {
		line = strings.TrimSpace(line)
		// 32 random bytes as base64url, which is the shape the sidecar's own credentials have.
		if len(line) == 43 && !strings.Contains(line, " ") {
			return line
		}
	}
	t.Fatalf("no credential was printed:\n%s", printed)
	return ""
}

func TestRegisteringAPublisherGrantsExactlyOneThing(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")
	printed := command(t, "register", "--database", path, "--server", publisher, "--label", "demo")
	credential := credentialOf(t, printed)
	if !strings.Contains(printed, "server/"+publisher) {
		t.Fatalf("register did not say which channel was granted:\n%s", printed)
	}

	documents, err := sqlite.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = documents.Close() }()
	sum := sha256.Sum256([]byte(credential))
	// What the gateway will do with the credential the publisher was given.
	serverID, err := documents.PublisherFor(context.Background(), sum[:])
	if err != nil || serverID != publisher {
		t.Fatalf("the credential resolved to %q (%v)", serverID, err)
	}
	// And the credential itself is nowhere in the file: only its hash is.
	if strings.Contains(fileText(t, path), credential) {
		t.Fatal("the credential was stored, not just its hash")
	}
}

func TestRotatingAndRevoking(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")
	first := credentialOf(t, command(t, "register", "--database", path, "--server", publisher))
	second := credentialOf(t, command(t, "rotate", "--database", path, "--server", publisher))
	if first == second {
		t.Fatal("rotation printed the same credential again")
	}

	documents, err := sqlite.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	resolves := func(credential string) string {
		sum := sha256.Sum256([]byte(credential))
		serverID, err := documents.PublisherFor(ctx, sum[:])
		if err != nil {
			t.Fatal(err)
		}
		return serverID
	}
	if resolves(first) != publisher || resolves(second) != publisher {
		t.Fatal("both credentials should work while the new one is being deployed")
	}
	listed := command(t, "list", "--database", path, "--server", publisher)
	if strings.Count(listed, "in use") != 2 {
		t.Fatalf("list says:\n%s", listed)
	}
	handle := strings.Fields(strings.Split(listed, "\n")[0])[0]
	_ = documents.Close()

	command(t, "revoke", "--database", path, "--credential", handle)
	documents, err = sqlite.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = documents.Close() }()
	if resolves(first) != "" {
		t.Fatal("a revoked credential still publishes")
	}
	if resolves(second) != publisher {
		t.Fatal("revoking one credential stopped the other")
	}
	// Revoking something that is not in use says so rather than reporting success.
	if err := refuses(t, "revoke", "--database", path, "--credential", "00000000"); !strings.Contains(
		err.Error(), "no credential") {
		t.Fatalf("revoking an unknown credential said %v", err)
	}
}

func TestForgettingIsDeliberate(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")
	command(t, "register", "--database", path, "--server", publisher)

	// It deletes documents, so it takes more than a typo.
	if err := refuses(t, "forget", "--database", path, "--server", publisher); !strings.Contains(
		err.Error(), "--yes") {
		t.Fatalf("forget without confirmation said %v", err)
	}
	printed := command(t, "forget", "--database", path, "--server", publisher, "--yes")
	if !strings.Contains(printed, "Phones that already read") {
		t.Fatalf("forget did not say what it does not do:\n%s", printed)
	}
	if listed := command(t, "list", "--database", path); !strings.Contains(
		listed, "no publishers") {
		t.Fatalf("the publisher is still listed:\n%s", listed)
	}
}

func TestWhatTheToolRefuses(t *testing.T) {
	path := filepath.Join(t.TempDir(), "broadcast.db")
	for _, arguments := range [][]string{
		{},
		{"register", "--server", publisher}, // no database
		{"register", "--database", path, "--server", "copytrading"},
		{"register", "--database", path},
		{"revoke", "--database", path},
		{"rotate", "--database", path, "--server", publisher}, // not registered
		{"something-else", "--database", path},
	} {
		refuses(t, arguments...)
	}
}

func fileText(t *testing.T, path string) string {
	t.Helper()
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(content)
}

// SEE-141 gave the operator a second surface. The tool keeps every semantic it had, gains the two
// things the surface needed — a host to record and a password hash to configure — and refuses the
// one thing that used to be ambiguous.

func TestRegisteringAnIdentityTwiceIsRefusedRatherThanRotated(t *testing.T) {
	database := filepath.Join(t.TempDir(), "broadcast.db")
	command(t, "register", "--database", database, "--server", publisher, "--label", "first")
	err := refuses(t, "register", "--database", database, "--server", publisher, "--label", "second")
	if !strings.Contains(err.Error(), "already registered") ||
		!strings.Contains(err.Error(), "rotate") {
		t.Fatalf("the refusal does not point at rotation: %v", err)
	}
	// Nothing was added: a second `register` must not hand out the ability to publish as an
	// existing publisher under another name.
	documents, err := sqlite.Open(database)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = documents.Close() }()
	credentials, err := documents.Credentials(context.Background(), publisher)
	if err != nil {
		t.Fatal(err)
	}
	if len(credentials) != 1 {
		t.Fatalf("the refused registration left %d credential(s)", len(credentials))
	}
	if held, err := documents.Publisher(context.Background(), publisher); err != nil ||
		held.Label != "first" {
		t.Fatalf("the existing registration was replaced: %+v", held)
	}
}

// A host is a note about which developer a registration belongs to. It is recorded, canonicalized
// and listed, and nothing ever fetches it.
func TestAHostIsRecordedAsMetadata(t *testing.T) {
	database := filepath.Join(t.TempDir(), "broadcast.db")
	command(t, "register", "--database", database, "--server", publisher,
		"--label", "copy trading", "--host", "https://Example.com/pub/")
	listed := command(t, "list", "--database", database)
	if !strings.Contains(listed, "host https://example.com/pub") {
		t.Fatalf("the host is not listed:\n%s", listed)
	}
	for _, raw := range []string{"javascript:alert(1)", "example.com", "https://a:b@example.com"} {
		if err := refuses(t, "register", "--database", filepath.Join(t.TempDir(), "x.db"),
			"--server", publisher, "--label", "x", "--host", raw); !strings.Contains(
			err.Error(), "--host") {
			t.Fatalf("--host %q was refused as %v", raw, err)
		}
	}
	// Rotation adds a credential and changes nothing else about a publisher.
	if err := refuses(t, "rotate", "--database", database, "--server", publisher,
		"--host", "https://elsewhere.example"); !strings.Contains(err.Error(), "--host belongs") {
		t.Fatalf("rotate --host answered %v", err)
	}
}

// The password command is the admin surface's bootstrap: it turns a password into the one line a
// deployment configures, touches no database, and prints nothing that can be turned back into the
// password.
func TestThePasswordCommandPrintsAHashAndNothingElse(t *testing.T) {
	const chosen = "a-long-enough-operator-password"
	printed := command(t, "password", "--password", chosen)
	if strings.Contains(printed, chosen) {
		t.Fatal("the password itself was printed")
	}
	hash := strings.Fields(printed)[0]
	parsed, err := credential.ParsePassword(hash)
	if err != nil {
		t.Fatalf("what was printed is not a password hash: %v", err)
	}
	if !parsed.Verify(chosen) {
		t.Fatal("the printed hash does not verify the password it was made from")
	}
	if !strings.Contains(printed, "BROADCAST_ADMIN_PASSWORD_HASH") {
		t.Fatal("the command does not say where the hash goes")
	}
	// It needs no database at all, which is what makes it usable before one exists.
	if strings.Contains(printed, "database") {
		t.Fatal("the password command mentions a database")
	}
	if err := refuses(t, "password", "--password", "short"); !strings.Contains(
		err.Error(), "at least") {
		t.Fatalf("a short password answered %v", err)
	}
}

// A restricted feed is registered with the origin its subscribers authenticate at, and that origin
// is held to the same rule as the gateway's own (SEE-156): it is the one address a phone sends a
// wallet proof to because this registration vouches for it.
func TestARestrictedFeedIsRegisteredWithItsAuthenticationOrigin(t *testing.T) {
	database := filepath.Join(t.TempDir(), "broadcast.db")
	printed := command(t, "register", "--database", database, "--server", publisher,
		"--label", "copy trading", "--access", "restricted",
		"--auth-origin", "https://Auth.Example.com/")
	if !strings.Contains(printed, "restricted, authenticated at https://auth.example.com") {
		t.Fatalf("register did not say the feed is restricted:\n%s", printed)
	}
	documents, err := sqlite.Open(database)
	if err != nil {
		t.Fatal(err)
	}
	access, err := documents.Access(context.Background(), publisher)
	_ = documents.Close()
	if err != nil || !access.Restricted() || access.AuthOrigin != "https://auth.example.com" {
		t.Fatalf("stored %+v (%v)", access, err)
	}
	if listed := command(t, "list", "--database", database); !strings.Contains(listed, "0 live grant(s)") {
		t.Fatalf("the list does not show the policy:\n%s", listed)
	}

	for _, arguments := range [][]string{
		{"--access", "restricted"},
		{"--access", "restricted", "--auth-origin", "http://auth.example.com"},
		{"--access", "restricted", "--auth-origin", "https://auth.example.com/login"},
		{"--access", "public", "--auth-origin", "https://auth.example.com"},
		{"--access", "secret"},
	} {
		refuses(t, append([]string{"register", "--database", filepath.Join(t.TempDir(), "x.db"),
			"--server", publisher, "--label", "x"}, arguments...)...)
	}

	command(t, "access", "--database", database, "--server", publisher, "--access", "public")
	documents, err = sqlite.Open(database)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = documents.Close() }()
	if access, err := documents.Access(context.Background(), publisher); err != nil || access.Restricted() {
		t.Fatalf("switching to public left %+v (%v)", access, err)
	}
	refuses(t, "access", "--database", database, "--server", publisher)
	refuses(t, "rotate", "--database", database, "--server", publisher, "--access", "restricted")
}
