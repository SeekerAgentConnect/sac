package admin

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestHashLineIsANamedBcryptRow(t *testing.T) {
	line, err := HashLine("judge1", "correct-horse")
	if err != nil {
		t.Fatal(err)
	}
	name, hash, found := strings.Cut(line, ":")
	if !found || name != "judge1" || !validHash(hash) {
		t.Fatalf("line %q", line)
	}
	file := passwordsFile(t, line+"\n")
	if !file.Check("judge1", "correct-horse") {
		t.Fatal("the hashed password was not accepted")
	}
	if file.Check("judge1", "wrong") {
		t.Fatal("a wrong password was accepted")
	}
}

func TestUnknownNamesUseTheSameRefusalAsAWrongPassword(t *testing.T) {
	file := passwordsFile(t, mustHash(t, "alice", "secret")+"\n")
	if file.Check("bob", "secret") {
		t.Fatal("an unknown name was accepted")
	}
	if file.Check("alice", "nope") {
		t.Fatal("a wrong password was accepted")
	}
}

func TestDeletingALineRevokesThatName(t *testing.T) {
	path := filepath.Join(t.TempDir(), "admin-passwords")
	if err := os.WriteFile(path, []byte(mustHash(t, "alice", "secret")+"\n"+mustHash(t, "bob", "other")+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	file, err := OpenFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !file.Known("alice") || !file.Known("bob") {
		t.Fatal("both names should be present")
	}
	time.Sleep(time.Millisecond)
	if err := os.WriteFile(path, []byte(mustHash(t, "bob", "other")+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(path, time.Now(), time.Now()); err != nil {
		t.Fatal(err)
	}
	if file.Known("alice") {
		t.Fatal("deleting alice's line must revoke her")
	}
	if !file.Known("bob") {
		t.Fatal("bob must still be known")
	}
}

func TestANameMustBeASingleToken(t *testing.T) {
	for _, name := range []string{"", "has space", "a:b", strings.Repeat("n", 65), "ok!"} {
		if ValidName(name) {
			t.Fatalf("%q was accepted", name)
		}
	}
	if !ValidName("judge-1") || !ValidName("Ada.Lovelace") {
		t.Fatal("a reasonable name was refused")
	}
}

// Named for what it returns rather than for what it does, because `writePasswords` is already a
// function in this package (config.go) and a test file shares its package: two declarations of one
// name stop the package's tests compiling, which is how this was found.
func passwordsFile(t *testing.T, contents string) *File {
	t.Helper()
	path := filepath.Join(t.TempDir(), "admin-passwords")
	if err := os.WriteFile(path, []byte(contents), 0o600); err != nil {
		t.Fatal(err)
	}
	file, err := OpenFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return file
}

func mustHash(t *testing.T, name, password string) string {
	t.Helper()
	line, err := HashLine(name, password)
	if err != nil {
		t.Fatal(err)
	}
	return line
}
