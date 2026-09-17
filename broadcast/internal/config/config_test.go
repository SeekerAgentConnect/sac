package config

import (
	"strings"
	"testing"
	"time"
)

func environment(values map[string]string) Lookup {
	return func(name string) (string, bool) {
		value, set := values[name]
		return value, set
	}
}

func complete() map[string]string {
	return map[string]string{
		"BROADCAST_PUBLIC_URL":    "https://feeds.example.com",
		"BROADCAST_DATABASE_PATH": "/data/broadcast.db",
	}
}

func TestTheTwoSettingsThatHaveNoDefault(t *testing.T) {
	settings, problems := Load(environment(nil))
	if settings != nil {
		t.Fatal("a gateway started with no configuration at all")
	}
	// Both at once, so a first start is one pass rather than one variable at a time.
	if len(problems) != 2 {
		t.Fatalf("expected both problems, got %v", problems)
	}
	joined := strings.Join(problems, "\n")
	for _, name := range []string{"BROADCAST_PUBLIC_URL", "BROADCAST_DATABASE_PATH"} {
		if !strings.Contains(joined, name) {
			t.Fatalf("%s was not named: %v", name, problems)
		}
	}
}

func TestWhatIsConfiguredWhenNothingElseIs(t *testing.T) {
	settings, problems := Load(environment(complete()))
	if problems != nil {
		t.Fatalf("a complete configuration was refused: %v", problems)
	}
	// The listeners are loopback until a deployment says otherwise: a gateway that was started
	// without being told where to listen is not one that should be on every interface.
	if settings.ReadAddress != DefaultReadAddress ||
		settings.PublisherAddress != DefaultPublisherAddress {
		t.Fatalf("the default addresses are %s and %s",
			settings.ReadAddress, settings.PublisherAddress)
	}
	if settings.Retention != DefaultRetention || settings.MaxProposals != DefaultMaxProposals {
		t.Fatalf("the defaults are %v and %d", settings.Retention, settings.MaxProposals)
	}
	if settings.ReadRate != DefaultReadRate || settings.PublishRate != DefaultPublishRate {
		t.Fatalf("the default rates are %v and %v", settings.ReadRate, settings.PublishRate)
	}
}

func TestTheTwoListenersMustBeTwo(t *testing.T) {
	values := complete()
	values["BROADCAST_READ_ADDRESS"] = "127.0.0.1:9000"
	values["BROADCAST_PUBLISHER_ADDRESS"] = "127.0.0.1:9000"
	_, problems := Load(environment(values))
	if len(problems) != 1 || !strings.Contains(problems[0], "separate listeners") {
		t.Fatalf("one socket served both APIs: %v", problems)
	}
}

func TestANumberOutsideItsRangeIsAProblemAndNotASilentDefault(t *testing.T) {
	for name, value := range map[string]string{
		"BROADCAST_RETENTION_HOURS": "0",
		"BROADCAST_MAX_PROPOSALS":   "0",
		"BROADCAST_READ_RATE":       "-1",
		"BROADCAST_PUBLISH_BURST":   "not a number",
	} {
		values := complete()
		values[name] = value
		_, problems := Load(environment(values))
		if len(problems) != 1 || !strings.Contains(problems[0], name) {
			t.Fatalf("%s=%q was accepted: %v", name, value, problems)
		}
	}
}

func TestRetentionIsReadInHours(t *testing.T) {
	values := complete()
	values["BROADCAST_RETENTION_HOURS"] = "48"
	settings, problems := Load(environment(values))
	if problems != nil {
		t.Fatal(problems)
	}
	if settings.Retention != 48*time.Hour {
		t.Fatalf("retention is %v", settings.Retention)
	}
}

func TestAnOriginIsWrittenTheWayThePhoneWritesIt(t *testing.T) {
	for raw, canonical := range map[string]string{
		"https://feeds.example.com":      "https://feeds.example.com",
		"https://Feeds.Example.COM":      "https://feeds.example.com",
		"HTTPS://feeds.example.com/":     "https://feeds.example.com",
		"https://feeds.example.com:443":  "https://feeds.example.com",
		"https://feeds.example.com:8443": "https://feeds.example.com:8443",
		"http://127.0.0.1:8090":          "http://127.0.0.1:8090",
		"http://localhost":               "http://localhost",
		"http://[::1]:8090":              "http://[::1]:8090",
	} {
		got, err := Origin(raw)
		if err != nil {
			t.Fatalf("%q was refused: %v", raw, err)
		}
		if got != canonical {
			t.Fatalf("%q became %q, expected %q", raw, got, canonical)
		}
	}
}

func TestWhatCannotBeAGatewayOrigin(t *testing.T) {
	for _, raw := range []string{
		"",
		"feeds.example.com",
		"https://",
		// A path would make one host two gateways to a phone comparing the string.
		"https://feeds.example.com/gateway",
		"https://feeds.example.com?v=1",
		"https://feeds.example.com#feed",
		"https://someone:secret@feeds.example.com",
		// Plain HTTP anywhere but loopback: a feed read over it could be rewritten in transit.
		"http://feeds.example.com",
		"ftp://feeds.example.com",
		"https://feeds.example.com:0",
		"https://feeds.example.com:99999",
	} {
		if got, err := Origin(raw); err == nil {
			t.Fatalf("%q was accepted as %q", raw, got)
		}
	}
}

func TestAnOriginIsValidatedBeforeItIsUsed(t *testing.T) {
	values := complete()
	values["BROADCAST_PUBLIC_URL"] = "http://feeds.example.com"
	settings, problems := Load(environment(values))
	if settings != nil || len(problems) != 1 {
		t.Fatalf("plain HTTP was accepted as a public origin: %v", problems)
	}
	if !strings.Contains(problems[0], "BROADCAST_PUBLIC_URL") {
		t.Fatalf("the problem did not name the variable: %v", problems)
	}
}
