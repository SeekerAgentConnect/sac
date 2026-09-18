package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// A configuration that starts: the five settings with no default, plus the two credentials.
func complete() map[string]string {
	return map[string]string{
		"PUBLISHER_SERVER_ID":     "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
		"PUBLISHER_GATEWAY_URL":   "https://feeds.example.com",
		"PUBLISHER_ENVIRONMENT":   "production",
		"PUBLISHER_DATABASE_PATH": "/data/publisher.db",
		"PUBLISHER_API_TOKEN":     strings.Repeat("t", 43),
		"BROADCAST_CREDENTIAL":    strings.Repeat("c", 43),
	}
}

func from(environment map[string]string) Lookup {
	return func(name string) (string, bool) {
		value, set := environment[name]
		return value, set
	}
}

func load(t *testing.T, environment map[string]string) *Config {
	t.Helper()
	settings, problems := Load(from(environment))
	if len(problems) > 0 {
		t.Fatalf("unexpected problems: %v", problems)
	}
	return settings
}

func TestACompleteConfigurationNeedsNothingElse(t *testing.T) {
	settings := load(t, complete())
	if settings.APIAddress != DefaultAPIAddress {
		t.Fatalf("api address %q", settings.APIAddress)
	}
	if settings.PublishTimeout != DefaultPublishTimeout {
		t.Fatalf("timeout %s", settings.PublishTimeout)
	}
	if settings.DisplayName != "" {
		t.Fatalf("display name %q: it is optional and there is no default", settings.DisplayName)
	}
}

// Every problem at once, so a first start is fixed in one pass rather than one variable at a time.
func TestAnEmptyEnvironmentNamesEverythingThatIsMissing(t *testing.T) {
	settings, problems := Load(from(map[string]string{}))
	if settings != nil {
		t.Fatal("a configuration was returned for an empty environment")
	}
	for _, name := range []string{
		"PUBLISHER_SERVER_ID", "PUBLISHER_GATEWAY_URL", "PUBLISHER_ENVIRONMENT",
		"PUBLISHER_DATABASE_PATH", "PUBLISHER_API_TOKEN", "BROADCAST_CREDENTIAL",
	} {
		if !mentions(problems, name) {
			t.Fatalf("%s is not named among %d problems: %v", name, len(problems), problems)
		}
	}
}

func TestTheServerIDIsALowercaseUUID(t *testing.T) {
	for _, one := range []struct {
		name string
		id   string
		ok   bool
	}{
		{"a lowercase UUID", "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d", true},
		{"upper case", "3F1B2C4D-5E6F-4A7B-8C9D-0E1F2A3B4C5D", false},
		{"no hyphens", "3f1b2c4d5e6f4a7b8c9d0e1f2a3b4c5d", false},
		{"a word", "copytrading", false},
		{"empty", "", false},
	} {
		t.Run(one.name, func(t *testing.T) {
			environment := complete()
			environment["PUBLISHER_SERVER_ID"] = one.id
			_, problems := Load(from(environment))
			if mentions(problems, "PUBLISHER_SERVER_ID") == one.ok {
				t.Fatalf("%q: %v", one.id, problems)
			}
		})
	}
}

// The environment is a promise about what happens when an owner approves, so there is no default
// and nothing to guess: production or sandbox, and one deployment serves one of them.
func TestTheEnvironmentIsProductionOrSandboxAndHasNoDefault(t *testing.T) {
	for _, one := range []struct {
		value string
		ok    bool
	}{
		{"production", true},
		{"sandbox", true},
		{"PRODUCTION", true}, // case is forgiven; the value is not
		{"staging", false},
		{"devnet", false},
		{"", false},
	} {
		t.Run(one.value, func(t *testing.T) {
			environment := complete()
			environment["PUBLISHER_ENVIRONMENT"] = one.value
			settings, problems := Load(from(environment))
			if mentions(problems, "PUBLISHER_ENVIRONMENT") == one.ok {
				t.Fatalf("%q: %v", one.value, problems)
			}
			if one.ok && settings.Environment.String() != strings.ToLower(one.value) {
				t.Fatalf("environment %q", settings.Environment)
			}
		})
	}
}

// The gateway is addressed by origin, by the same rules the gateway canonicalizes its own and the
// phone reads a feed reference by. The phone compares the two character for character, so
// canonicalizing here is what keeps a deployment from publishing a manifest no phone accepts.
func TestTheGatewayIsAnOriginAndIsCanonicalized(t *testing.T) {
	for _, one := range []struct {
		raw       string
		canonical string
	}{
		{"https://feeds.example.com", "https://feeds.example.com"},
		{"https://Feeds.Example.COM", "https://feeds.example.com"},
		{"https://feeds.example.com:443", "https://feeds.example.com"},
		{"https://feeds.example.com/", "https://feeds.example.com"},
		{"https://feeds.example.com:8443", "https://feeds.example.com:8443"},
		{"http://127.0.0.1:8080", "http://127.0.0.1:8080"},
		{"http://localhost:8080", "http://localhost:8080"},
	} {
		t.Run(one.raw, func(t *testing.T) {
			environment := complete()
			environment["PUBLISHER_GATEWAY_URL"] = one.raw
			settings := load(t, environment)
			if settings.GatewayURL != one.canonical {
				t.Fatalf("%q became %q, expected %q", one.raw, settings.GatewayURL, one.canonical)
			}
		})
	}
	for _, one := range []struct {
		name string
		raw  string
	}{
		{"plain HTTP to a public host", "http://feeds.example.com"},
		{"a path", "https://feeds.example.com/broadcast"},
		{"a query", "https://feeds.example.com?v=1"},
		{"user information", "https://user:pass@feeds.example.com"},
		{"no host", "https://"},
		{"not a URL", "feeds.example.com"},
	} {
		t.Run(one.name, func(t *testing.T) {
			environment := complete()
			environment["PUBLISHER_GATEWAY_URL"] = one.raw
			if _, problems := Load(from(environment)); !mentions(problems,
				"PUBLISHER_GATEWAY_URL") {
				t.Fatalf("%q was accepted: %v", one.raw, problems)
			}
		})
	}
}

// Where publications go is its own setting, and it defaults to the origin phones read from —
// which is what the gateway's own deployment serves both APIs on. It is a weaker rule than an
// origin's on purpose: this address is inside a deployment and nothing compares it.
func TestWherePublicationsGoDefaultsToTheGatewaysOrigin(t *testing.T) {
	settings := load(t, complete())
	if settings.PublishURL != settings.GatewayURL {
		t.Fatalf("publishing to %q, reading from %q", settings.PublishURL, settings.GatewayURL)
	}

	for _, one := range []struct {
		name      string
		raw       string
		canonical string
	}{
		{"the gateway's own second listener", "http://127.0.0.1:8091", "http://127.0.0.1:8091"},
		{"a private host over plain HTTP", "http://broadcast.internal:8091",
			"http://broadcast.internal:8091"},
		{"a compose service name", "http://broadcast:8091", "http://broadcast:8091"},
		// The case is canonicalized and a default port is kept: nothing compares this string, so
		// there is nothing for a port to disagree with — unlike an origin, which a phone compares
		// character for character.
		{"a publishing name of its own", "https://Publish.Example.com:443",
			"https://publish.example.com:443"},
	} {
		t.Run(one.name, func(t *testing.T) {
			environment := complete()
			environment["PUBLISHER_PUBLISH_URL"] = one.raw
			settings := load(t, environment)
			if settings.PublishURL != one.canonical {
				t.Fatalf("%q became %q, expected %q", one.raw, settings.PublishURL, one.canonical)
			}
			// And it changes nothing about what the manifest names.
			if settings.GatewayURL != "https://feeds.example.com" {
				t.Fatalf("the manifest would name %q", settings.GatewayURL)
			}
		})
	}
	for _, one := range []struct {
		name string
		raw  string
	}{
		{"a path", "http://broadcast.internal:8091/publish"},
		{"a query", "http://broadcast.internal:8091?v=1"},
		{"no host", "http://"},
		{"not HTTP at all", "tcp://broadcast.internal:8091"},
	} {
		t.Run(one.name, func(t *testing.T) {
			environment := complete()
			environment["PUBLISHER_PUBLISH_URL"] = one.raw
			if _, problems := Load(from(environment)); !mentions(problems,
				"PUBLISHER_PUBLISH_URL") {
				t.Fatalf("%q was accepted: %v", one.raw, problems)
			}
		})
	}
}

// A secret is either a value or the path of a file holding one, and never both.
func TestASecretMayBeAFile(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "credential")
	if err := os.WriteFile(path, []byte(strings.Repeat("f", 43)+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	environment := complete()
	delete(environment, "BROADCAST_CREDENTIAL")
	environment["BROADCAST_CREDENTIAL_FILE"] = path
	settings := load(t, environment)
	if settings.Credential != strings.Repeat("f", 43) {
		t.Fatalf("credential %q: the trailing newline of a mounted secret is not part of it",
			settings.Credential)
	}

	t.Run("both is a problem", func(t *testing.T) {
		environment := complete()
		environment["BROADCAST_CREDENTIAL_FILE"] = path
		_, problems := Load(from(environment))
		if !mentions(problems, "BROADCAST_CREDENTIAL") {
			t.Fatalf("naming both a secret and its file was accepted: %v", problems)
		}
	})

	t.Run("a missing file names the path and not its contents", func(t *testing.T) {
		environment := complete()
		delete(environment, "PUBLISHER_API_TOKEN")
		environment["PUBLISHER_API_TOKEN_FILE"] = filepath.Join(directory, "absent")
		_, problems := Load(from(environment))
		if !mentions(problems, "PUBLISHER_API_TOKEN_FILE") {
			t.Fatalf("%v", problems)
		}
		if mentions(problems, directory+"/absent: open") {
			t.Fatalf("the message quotes more than the path: %v", problems)
		}
	})
}

// Both credentials are sent in an HTTP header, so whitespace in one is not a typo to forgive: a
// newline in a header value is a second header.
func TestACredentialIsOneWordWithNothingInItAHeaderCannotCarry(t *testing.T) {
	for _, name := range []string{"BROADCAST_CREDENTIAL", "PUBLISHER_API_TOKEN"} {
		for _, one := range []struct {
			label string
			value string
		}{
			{"a space", strings.Repeat("c", 20) + " " + strings.Repeat("c", 22)},
			{"a newline", strings.Repeat("c", 20) + "\n" + strings.Repeat("c", 22)},
			{"a tab", strings.Repeat("c", 20) + "\t" + strings.Repeat("c", 22)},
			{"a control character", strings.Repeat("c", 42) + "\x01"},
			{"something outside ASCII", strings.Repeat("c", 42) + "é"},
		} {
			t.Run(name+" with "+one.label, func(t *testing.T) {
				environment := complete()
				environment[name] = one.value
				if _, problems := Load(from(environment)); !mentions(problems, name) {
					t.Fatalf("%q was accepted: %v", one.value, problems)
				}
			})
		}
	}
}

// The API token is the whole grant to publish through this template, so there is a floor under it.
func TestTheAPITokenHasAFloorUnderItsLength(t *testing.T) {
	environment := complete()
	environment["PUBLISHER_API_TOKEN"] = strings.Repeat("t", LeastTokenLength-1)
	if _, problems := Load(from(environment)); !mentions(problems, "PUBLISHER_API_TOKEN") {
		t.Fatalf("a short token was accepted: %v", problems)
	}
	environment["PUBLISHER_API_TOKEN"] = strings.Repeat("t", LeastTokenLength)
	load(t, environment)
}

// The display name is the connection's default label on the phone, which refuses a manifest whose
// name it cannot show — so the rule is the phone's own.
func TestTheDisplayNameIsALabelAndNotProse(t *testing.T) {
	for _, one := range []struct {
		name  string
		value string
		ok    bool
	}{
		{"a name", "Copy trading desk", true},
		{"at the bound", strings.Repeat("n", 64), true},
		{"over the bound", strings.Repeat("n", 65), false},
		{"a line break", "Copy trading\ndesk", false},
		// Every setting is trimmed before it is read, so a stray space at the end of a line in an
		// .env file is not a configuration problem. One in the middle that is not a space is.
		{"trailing space, which is trimmed", "Copy trading ", true},
		{"a space that is not a space", "Copy\u00a0trading", false},
	} {
		t.Run(one.name, func(t *testing.T) {
			environment := complete()
			environment["PUBLISHER_DISPLAY_NAME"] = one.value
			_, problems := Load(from(environment))
			if mentions(problems, "PUBLISHER_DISPLAY_NAME") == one.ok {
				t.Fatalf("%q: %v", one.value, problems)
			}
		})
	}
}

func TestThePublishTimeoutIsABoundedNumberOfSeconds(t *testing.T) {
	for _, one := range []struct {
		value   string
		seconds int
		ok      bool
	}{
		{"1", 1, true},
		{"120", 120, true},
		{"0", 0, false},
		{"121", 0, false},
		{"ten", 0, false},
	} {
		t.Run(one.value, func(t *testing.T) {
			environment := complete()
			environment["PUBLISHER_PUBLISH_TIMEOUT_SECONDS"] = one.value
			settings, problems := Load(from(environment))
			if mentions(problems, "PUBLISHER_PUBLISH_TIMEOUT_SECONDS") == one.ok {
				t.Fatalf("%q: %v", one.value, problems)
			}
			if one.ok && int(settings.PublishTimeout.Seconds()) != one.seconds {
				t.Fatalf("timeout %s", settings.PublishTimeout)
			}
		})
	}
}

func mentions(problems []string, name string) bool {
	for _, problem := range problems {
		if strings.Contains(problem, name) {
			return true
		}
	}
	return false
}
