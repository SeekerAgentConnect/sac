package main

import (
	"strings"
	"testing"

	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/admin"
)

func TestHashPrintsANamedBcryptLine(t *testing.T) {
	var out strings.Builder
	if err := hash([]string{"judge1"}, strings.NewReader("correct-horse\n"), &out); err != nil {
		t.Fatal(err)
	}
	line := strings.TrimSpace(out.String())
	name, rest, found := strings.Cut(line, ":")
	if !found || name != "judge1" || !strings.HasPrefix(rest, "$2") {
		t.Fatalf("line %q", line)
	}
	if err := hash([]string{}, strings.NewReader("x\n"), &strings.Builder{}); err == nil {
		t.Fatal("hash with no name succeeded")
	}
	if !admin.ValidName("judge1") {
		t.Fatal("judge1 should be a valid name")
	}
}
