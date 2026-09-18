package environment

import (
	"os"
	"regexp"
	"testing"

	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher/internal/gen/seekervault/server/v1"
)

func TestOnlyTheTwoWordsAreAnEnvironment(t *testing.T) {
	for _, one := range []struct {
		text   string
		named  Environment
		wanted bool
	}{
		{"production", Production, true},
		{"sandbox", Sandbox, true},
		// Everything else, including the ways an operator nearly gets it right. None of them is
		// resolved in the deployment's favour: an environment nobody configured is a startup
		// problem, not a promise.
		{"", "", false},
		{"staging", "", false},
		{"prod", "", false},
		{"Production", "", false},
		{"production ", "", false},
		{"sandbox,production", "", false},
	} {
		named, ok := Parse(one.text)
		if ok != one.wanted {
			t.Fatalf("Parse(%q) was %v", one.text, ok)
		}
		if named != one.named {
			t.Fatalf("Parse(%q) was %q, expected %q", one.text, named, one.named)
		}
	}
}

// The direction a mistake has to fall. An [Environment] that came from anywhere but Parse — a
// struct literal in a test, a field left at its zero value — must not publish itself as the
// promise with the money attached to it.
func TestAnEnvironmentFromNowhereIsNotProduction(t *testing.T) {
	for _, one := range []Environment{"", "staging", "PRODUCTION", "sandbox "} {
		if wire := one.Wire(); wire != serverv1.ServerEnvironment_SERVER_ENVIRONMENT_UNSPECIFIED {
			t.Fatalf("%q published itself as %v", one, wire)
		}
	}
	if Production.Wire() != serverv1.ServerEnvironment_SERVER_ENVIRONMENT_PRODUCTION {
		t.Fatal("production is not production")
	}
	if Sandbox.Wire() != serverv1.ServerEnvironment_SERVER_ENVIRONMENT_SANDBOX {
		t.Fatal("sandbox is not sandbox")
	}
}

// The phone's own spelling of the same two promises (SEE-97).
//
// These words cross no wire — the manifest carries the enum, not the text — so a disagreement
// would never be a protocol error. It would be quieter than that: a `PUBLISHER_ENVIRONMENT` an
// operator copied out of the app, or a log line and a screen that name the same deployment
// differently, and nobody would find out from a failure. It is the same technique
// `internal/signals/contract_test.go` uses on the phone's term rules, and it skips when the
// phone's source is not there, which is what a copied-out template looks like.
func TestTheWordsAreThePhonesOwn(t *testing.T) {
	const boundary = "../../../android/app/src/main/java/io/github/brrenat/seekervault/" +
		"plugins/ActionPlugin.kt"
	source, err := os.ReadFile(boundary)
	if err != nil {
		t.Skipf("the phone's source is not here (%v), which is what a copied-out template "+
			"looks like", err)
	}
	codes := regexp.MustCompile(`(?m)^\s*(Production|Sandbox)\("([a-z]+)"\),`).
		FindAllStringSubmatch(string(source), -1)
	if len(codes) != 2 {
		t.Fatalf("PluginEnvironment no longer declares two codes in %s: it declares %d, so this "+
			"module's two words are a guess", boundary, len(codes))
	}
	held := map[string]Environment{"Production": Production, "Sandbox": Sandbox}
	for _, code := range codes {
		if want := held[code[1]]; want.String() != code[2] {
			t.Fatalf("the phone calls %s %q and this module calls it %q",
				code[1], code[2], want)
		}
	}
}
