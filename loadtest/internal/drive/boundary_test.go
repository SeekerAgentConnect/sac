package drive

import (
	"go/ast"
	"go/parser"
	"go/printer"
	"go/token"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

// What the load harness is, said as tests over its own source rather than as prose in a README
// (SEE-99, AGENTS.md).
//
// SEE-99 draws three lines: synthetic proposals, a fake or controlled push sender, and no real
// trading or provider traffic. Each of them is a thing that could be crossed in an afternoon by
// someone adding a scenario — "let's point it at the live provider and see" — so each of them is a
// test. The publisher templates' own boundary test is the model
// (`publisher/internal/api/boundary_test.go`).

// root is this module, from this package.
const root = "../.."

// code is every shipped file with its comments removed.
//
// Comments are removed on purpose. This file's tests are about what the harness can *do*, and the
// first version of them failed because `push.go` explains at length that Firebase is the thing it
// stands in for — which is exactly the sentence that should be there. Prose about a boundary is not
// a crossing of it.
func code(t *testing.T) map[string]string {
	t.Helper()
	stripped := map[string]string{}
	for name, contents := range shipped(t) {
		set := token.NewFileSet()
		// Without ParseComments, the comments are simply not in the tree.
		parsed, err := parser.ParseFile(set, name, contents, 0)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		built := &strings.Builder{}
		if err := printer.Fprint(built, set, parsed); err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		stripped[name] = built.String()
	}
	return stripped
}

// literals is every string literal in the shipped code, which is where an address it could open
// would have to be.
func literals(t *testing.T) map[string][]string {
	t.Helper()
	found := map[string][]string{}
	for name, contents := range shipped(t) {
		set := token.NewFileSet()
		parsed, err := parser.ParseFile(set, name, contents, 0)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		ast.Inspect(parsed, func(node ast.Node) bool {
			literal, ok := node.(*ast.BasicLit)
			if !ok || literal.Kind != token.STRING {
				return true
			}
			value, err := strconv.Unquote(literal.Value)
			if err != nil {
				value = literal.Value
			}
			found[name] = append(found[name], value)
			return true
		})
	}
	return found
}

// shipped is every Go file in the module that is not a test: the code a run actually executes.
func shipped(t *testing.T) map[string]string {
	t.Helper()
	files := map[string]string{}
	err := filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		switch {
		case err != nil:
			return err
		case info.IsDir():
			return nil
		case !strings.HasSuffix(path, ".go"):
			return nil
		case strings.HasSuffix(path, "_test.go"):
			return nil
		// Generated from proto/ and never edited by hand; what is in it is settled by
		// buf.gen.loadtest.yaml, which the last test here is about.
		case strings.Contains(path, filepath.Join("internal", "gen")):
			return nil
		}
		contents, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		files[filepath.ToSlash(strings.TrimPrefix(path, root+"/"))] = string(contents)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(files) < 8 {
		t.Fatalf("only %d shipped files were found; this test is not reading the module",
			len(files))
	}
	return files
}

// The harness reaches nothing outside the machine it runs on. Every address it opens is loopback,
// every document it publishes it made up, and there is no host name in it at all — which is the
// same argument the gateway's own boundary test makes about itself.
func TestItNamesNoHostOutsideThisMachine(t *testing.T) {
	forbidden := []string{
		"googleapis.com",
		"fcm.",
		"jup.ag",
		"mainnet",
		"solana.com",
		"helius",
		"quiknode",
		// Nothing it opens is over TLS, because nothing it opens leaves the machine.
		"https://",
	}
	for name, values := range literals(t) {
		for _, value := range values {
			lower := strings.ToLower(value)
			for _, host := range forbidden {
				if strings.Contains(lower, host) {
					t.Errorf("%s has the literal %q, which names %q: a load run reaches "+
						"nothing outside this machine", name, value, host)
				}
			}
			// And every address it does name is loopback. The bare scheme is allowed: the
			// address it is joined to comes from a listener this run opened on 127.0.0.1, which
			// the next test is about.
			if strings.HasPrefix(lower, "http://") && lower != "http://" &&
				!strings.HasPrefix(lower, "http://127.0.0.1") {
				t.Errorf("%s opens %q, and every address in a run is loopback", name, value)
			}
			if strings.Contains(lower, "0.0.0.0") {
				t.Errorf("%s has %q: nothing a run opens is reachable from another machine",
					name, value)
			}
		}
	}
}

// Everything it listens on, it listens on loopback. The gateway, the broker nodes and Redis are
// given loopback addresses by the harness rather than their own defaults, and the push stand-in
// binds one itself — so a load run on a laptop on a café network is not a service on that network.
func TestEverythingItOpensIsOnLoopback(t *testing.T) {
	listening := map[string]bool{}
	for name, contents := range code(t) {
		if strings.Contains(contents, "net.Listen(") {
			listening[name] = true
			if !strings.Contains(contents, `"127.0.0.1:0"`) {
				t.Errorf("%s listens without naming 127.0.0.1", name)
			}
		}
	}
	if len(listening) == 0 {
		t.Fatal("nothing in the harness listens, so this test is not reading the module")
	}
	// The processes it starts are told their own addresses, and those are loopback too.
	deployment, known := code(t)["internal/deploy/deploy.go"]
	if !known {
		t.Fatal("internal/deploy/deploy.go is not in the shipped code")
	}
	for _, address := range []string{
		"BROADCAST_READ_ADDRESS=127.0.0.1:",
		"BROADCAST_PUBLISHER_ADDRESS=127.0.0.1:",
		"--bind",
	} {
		if !strings.Contains(deployment, address) {
			t.Errorf("the deployment does not set %q", address)
		}
	}
}

// Nothing in the harness signs anything, holds a key that matters, or knows what a wallet is. The
// one key it makes is the throwaway RSA key the push stand-in's credential needs, and it is in the
// one file that is allowed to have it.
func TestOnlyThePushStandInMakesAKey(t *testing.T) {
	allowed := "internal/deploy/push.go"
	for name, contents := range code(t) {
		if name == allowed {
			continue
		}
		for _, secret := range []string{"rsa.", "ed25519", "GenerateKey", "PrivateKey", "Sign("} {
			if strings.Contains(contents, secret) {
				t.Errorf("%s uses %q, and only %s has any business with a key",
					name, secret, allowed)
			}
		}
	}
}

// A publisher a run creates promises sandbox, always. A phone that somehow read one of these feeds
// must never be told it is looking at a production publisher (SEE-97).
func TestEveryPublisherItCreatesIsSandbox(t *testing.T) {
	documents := NewSynthetic(ID("boundary"), "http://127.0.0.1:8090", 512, 1)
	manifest := documents.Manifest(1, "load test")
	environments := manifest.GetEnvironments()
	if len(environments) != 1 || environments[0].String() != "SERVER_ENVIRONMENT_SANDBOX" {
		t.Fatalf("a synthetic manifest promises %v", environments)
	}
	for name, contents := range code(t) {
		if strings.Contains(contents, "SERVER_ENVIRONMENT_PRODUCTION") {
			t.Errorf("%s names the production environment", name)
		}
	}
}

// The operation and plugin a synthetic proposal names are the harness's own, and not one of the
// real ones. A document that said `jupiter.swap` could be shown to a person as a swap.
func TestItsDocumentsAreObviouslyATests(t *testing.T) {
	for _, name := range []string{Operation, Plugin} {
		if !strings.HasPrefix(name, "loadtest.") {
			t.Errorf("%q does not say it is a load test's", name)
		}
	}
}

// The harness is a client of both APIs and a server of neither. Generating the handlers is
// unavoidable — they come with the clients — but mounting one would make this a fourth
// implementation of a contract the gateway owns.
func TestItMountsNoHandler(t *testing.T) {
	for name, contents := range code(t) {
		for _, handler := range []string{
			"NewFeedServiceHandler",
			"NewPublisherServiceHandler",
			"NewCentrifugoUniStreamHandler",
		} {
			if strings.Contains(contents, handler) {
				t.Errorf("%s mounts %s: the harness serves none of these procedures",
					name, handler)
			}
		}
	}
}

// The one HTTP server it does run is the push stand-in, and SEE-99 requires it.
func TestTheOnlyServerItRunsIsThePushStandIn(t *testing.T) {
	allowed := map[string]bool{"internal/deploy/push.go": true}
	for name, contents := range code(t) {
		if allowed[name] {
			continue
		}
		if strings.Contains(contents, "http.Server{") ||
			strings.Contains(contents, "http.ListenAndServe") {
			t.Errorf("%s serves HTTP, and the only server in a load run is the push stand-in",
				name)
		}
	}
}

// And the profiles are data the run reads rather than numbers compiled into it, because SEE-99 asks
// for a report that can be reproduced from configuration.
func TestTheProfilesAreOnDisk(t *testing.T) {
	profiles, err := Profiles(filepath.Join(root, "profiles.json"))
	if err != nil {
		t.Fatal(err)
	}
	if len(profiles) < 4 {
		t.Fatalf("there are %d profiles", len(profiles))
	}
	// Every scenario names a profile that exists, or a run would fail at the point where it is
	// least useful: after the deployment is up.
	for _, scenario := range Scenarios() {
		if _, err := Find(profiles, scenario.Profile); err != nil {
			t.Errorf("%s: %v", scenario.Name, err)
		}
	}
}
