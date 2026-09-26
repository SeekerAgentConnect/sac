package drive

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// The profiles, and the refusals that keep a report from being built on a default nobody saw
// (SEE-99).

func write(t *testing.T, body string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "profiles.json")
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestADurationIsWrittenTheWayGoWritesOne(t *testing.T) {
	var parsed struct {
		Every Duration `json:"every"`
	}
	if err := json.Unmarshal([]byte(`{"every":"250ms"}`), &parsed); err != nil {
		t.Fatal(err)
	}
	if parsed.Every.Every() != 250*time.Millisecond {
		t.Fatalf("%s", parsed.Every)
	}
	// And it comes back out the same way, so a report and a profile say the same thing.
	body, err := json.Marshal(parsed)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != `{"every":"250ms"}` {
		t.Fatalf("%s", body)
	}
	for _, bad := range []string{`{"every":250}`, `{"every":"soon"}`, `{"every":"250"}`} {
		if err := json.Unmarshal([]byte(bad), &parsed); err == nil {
			t.Errorf("%s was accepted", bad)
		}
	}
}

func TestAMisspelledFieldIsRefusedRatherThanDefaulted(t *testing.T) {
	path := write(t, `{"profiles":[{"name":"x","listners":10}]}`)
	if _, err := Profiles(path); err == nil {
		t.Fatal("a misspelled field was accepted, and the report would have said 0 listeners")
	}
}

func TestACommentIsAllowedBecauseJSONHasNone(t *testing.T) {
	path := write(t, `{"$comment":["why this file looks like this"],
		"profiles":[{"name":"x","listeners":10}]}`)
	if _, err := Profiles(path); err != nil {
		t.Fatal(err)
	}
}

func TestTwoProfilesCannotShareAName(t *testing.T) {
	path := write(t, `{"profiles":[{"name":"x"},{"name":"x"}]}`)
	if _, err := Profiles(path); err == nil {
		t.Fatal("two profiles called x were accepted")
	}
}

func TestAnEmptyFileIsAFailureRatherThanAnEmptyRun(t *testing.T) {
	path := write(t, `{"profiles":[]}`)
	if _, err := Profiles(path); err == nil {
		t.Fatal("a file with no profiles was accepted")
	}
	if _, err := Profiles(filepath.Join(t.TempDir(), "nothing.json")); err == nil {
		t.Fatal("a missing file was accepted")
	}
}

func TestTheDefaultsAreTheOnesTheDocumentationNames(t *testing.T) {
	resolved, err := Profile{Name: "bare"}.Resolve()
	if err != nil {
		t.Fatal(err)
	}
	for _, one := range []struct {
		what     string
		got      any
		expected any
	}{
		{"publishers", resolved.Publishers, 1},
		{"channels per listener", resolved.ChannelsPerListener, 1},
		{"payload", resolved.PayloadBytes, 1024},
		{"publish interval", resolved.PublishEvery.Every(), time.Second},
		{"proposals", resolved.Proposals, 8},
		{"revisions", resolved.Revisions, 3},
		{"page size", resolved.PageSize, 50},
		{"measured window", resolved.Measure.Every(), 10 * time.Second},
		{"broker nodes", resolved.BrokerNodes, 1},
	} {
		if one.got != one.expected {
			t.Errorf("%s defaults to %v, and the documentation says %v",
				one.what, one.got, one.expected)
		}
	}
	// One stage, at the profile's own listener count, so a profile and a ramp are one code path.
	if len(resolved.Stages) != 1 || resolved.Stages[0] != resolved.Listeners {
		t.Errorf("the stages are %v", resolved.Stages)
	}
}

func TestAProfileThatCannotBeRunIsRefused(t *testing.T) {
	for what, profile := range map[string]Profile{
		"no name":                            {},
		"negative listeners":                 {Name: "x", Listeners: -1},
		"more channels than feeds":           {Name: "x", Publishers: 2, ChannelsPerListener: 3},
		"past the gateway's grant":           {Name: "x", Publishers: 40, ChannelsPerListener: 33},
		"a page the gateway will not answer": {Name: "x", PageSize: 500},
		"stages out of order":                {Name: "x", Stages: []int{100, 50}},
		"a negative stage":                   {Name: "x", Stages: []int{-1}},
		"negative broker nodes":              {Name: "x", BrokerNodes: -1},
		"a delivery share that is not one": {
			Name: "x", Health: Health{LeastDelivered: 2},
		},
		"a loss share that is not one": {Name: "x", Health: Health{MostLost: -1}},
	} {
		if _, err := profile.Resolve(); err == nil {
			t.Errorf("%s was accepted", what)
		}
	}
}

func TestTheShippedProfilesResolveAndAreWhatTheScenariosNeed(t *testing.T) {
	profiles, err := Profiles(filepath.Join(root, "profiles.json"))
	if err != nil {
		t.Fatal(err)
	}
	for _, profile := range profiles {
		if profile.Note == "" {
			t.Errorf("%s says nothing about what it is for", profile.Name)
		}
		// Every profile's publishers must be able to publish at the rate it asks for, or what the
		// run measures is the gateway's rate limiter rather than its transport. The flood scenario
		// is the deliberate exception, and it sets the rate to the gateway's default itself.
		if profile.PublishRate > 0 {
			asked := 1 / profile.PublishEvery.Every().Seconds()
			if asked > profile.PublishRate {
				t.Errorf("%s publishes %.1f a second against a limit of %g",
					profile.Name, asked, profile.PublishRate)
			}
		}
		// And a channel cannot hold more proposals than the gateway allows it to.
		most := profile.MaxProposals
		if most == 0 {
			most = 200
		}
		if profile.Proposals > most {
			t.Errorf("%s cycles %d proposals against a bound of %d",
				profile.Name, profile.Proposals, most)
		}
	}
	if _, err := Find(profiles, "nothing-called-this"); err == nil {
		t.Fatal("a profile that does not exist was found")
	}
}

func TestARampsStagesAreTheListenerCountsItClimbs(t *testing.T) {
	profile, err := Profile{Name: "r", Listeners: 10, Stages: []int{10, 20, 30}}.Resolve()
	if err != nil {
		t.Fatal(err)
	}
	for index, expected := range []int{10, 20, 30} {
		if got := profile.stage(index); got != expected {
			t.Errorf("stage %d has %d listeners", index, got)
		}
	}
	// A stage index nothing asked for falls back to the profile's own count rather than panicking
	// in the middle of a run.
	if got := profile.stage(9); got != 10 {
		t.Errorf("stage 9 has %d listeners", got)
	}
}
