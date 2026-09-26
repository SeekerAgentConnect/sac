package drive

import (
	"encoding/json"
	"fmt"
	"os"
	"slices"
	"strings"
	"time"
)

// A workload profile: what to publish, how many phones are listening, and for how long (SEE-99).
//
// Profiles are **data**, in `tools/loadtest/profiles.json`, and not code. SEE-99 asks that scripts and
// configuration reproduce the report from a documented revision, which means the numbers behind
// every line of `docs/testing/see-99.md` have to be readable without reading Go — and that someone
// asking "what happens at four times the payload" can answer it by editing a file rather than by
// recompiling a harness and then wondering whether anything else moved.
//
// Every field a profile does not set has a documented default here, and the report echoes the
// resolved profile back, so a number in a report is never the result of a default nobody saw.

// Profile is one workload.
type Profile struct {
	Name string `json:"name"`
	// What this profile is for, printed above its numbers.
	Note string `json:"note"`

	// How many publishers, each owning one channel. A profile with one is the shared hot channel;
	// with many, the listeners are divided between them.
	Publishers int `json:"publishers"`
	// How many simulated phones. For a ramp, the starting count — Stages is what climbs.
	Listeners int `json:"listeners"`
	// How many channels one listener holds a ticket for. The gateway grants at most 32
	// (BROADCAST_MAX_CHANNELS), and a phone holds the feeds its owner added.
	ChannelsPerListener int `json:"channelsPerListener"`
	// The listener counts a ramp climbs through. Empty means one stage at Listeners.
	Stages []int `json:"stages"`

	// The document size to aim for, in bytes, before protobuf framing.
	PayloadBytes int `json:"payloadBytes"`
	// How often each publisher acts.
	PublishEvery Duration `json:"publishEvery"`
	// How many proposal identities each publisher cycles through, and how many revisions of each
	// it publishes before withdrawing it.
	Proposals int `json:"proposals"`
	Revisions int `json:"revisions"`
	// Whether a proposal is withdrawn after its last revision, and how often a publisher bumps its
	// manifest (in publications; zero never).
	Cancel        bool `json:"cancel"`
	ManifestEvery int  `json:"manifestEvery"`

	// How many snapshot readers walk pages while the feed moves, how often, and in what page size.
	// They are the unary half of the contract, and the load a mass reconnect puts on the gateway.
	Readers   int      `json:"readers"`
	ReadEvery Duration `json:"readEvery"`
	PageSize  int      `json:"pageSize"`

	// How long to run before measuring, and how long to measure.
	WarmUp  Duration `json:"warmUp"`
	Measure Duration `json:"measure"`

	// How many broker nodes to run. Two needs Redis.
	BrokerNodes int `json:"brokerNodes"`
	// Whether each listener presents its own forwarded address, as a proxy in front of a
	// deployment would set one. False is the honest measurement of a whole office behind one
	// address: every reader shares one token bucket.
	SharedAddress bool `json:"sharedAddress"`
	// Whether the push relay runs against the controlled stand-in.
	Push bool `json:"push"`

	// The gateway's own bounds, when a profile needs them wider than the defaults a shared service
	// ships with. Zero means the gateway's default, and the report prints which was in force.
	PublishRate  float64 `json:"publishRate"`
	PublishBurst int     `json:"publishBurst"`
	ReadRate     float64 `json:"readRate"`
	ReadBurst    int     `json:"readBurst"`
	MaxProposals int     `json:"maxProposals"`

	// The health rule a stage has to hold for a ramp to climb past it. A stage that fails it is
	// where the report says the machine stopped.
	Health Health `json:"health"`
}

// Health is what a stage must hold. Every field is optional, and a rule that is not set is not
// checked — a profile says what it is asserting rather than inheriting a judgement.
type Health struct {
	// The highest publish-to-receive p99 a stage may have.
	MostP99 Duration `json:"mostP99"`
	// The share of published documents that must reach every listener, as a fraction.
	LeastDelivered float64 `json:"leastDelivered"`
	// The most listeners that may have been closed or have failed to connect, as a fraction.
	MostLost float64 `json:"mostLost"`
}

// Duration is a duration written the way Go writes one ("250ms", "30s"), because a report that
// says 30s and a profile that says 30000000000 are the same number badly.
type Duration time.Duration

func (d Duration) MarshalJSON() ([]byte, error) {
	return json.Marshal(time.Duration(d).String())
}

func (d *Duration) UnmarshalJSON(raw []byte) error {
	var text string
	if err := json.Unmarshal(raw, &text); err != nil {
		return fmt.Errorf("a duration must be written as a string like \"250ms\": %w", err)
	}
	parsed, err := time.ParseDuration(text)
	if err != nil {
		return fmt.Errorf("%q is not a duration: %w", text, err)
	}
	*d = Duration(parsed)
	return nil
}

// Every is the duration, as the rest of the harness uses it.
func (d Duration) Every() time.Duration { return time.Duration(d) }

// String is Go's own spelling, so a profile printed in a summary reads the way it was written.
func (d Duration) String() string { return time.Duration(d).String() }

// Profiles reads a profile file.
func Profiles(path string) ([]Profile, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("drive: reading the profiles: %w", err)
	}
	var file struct {
		// JSON has no comments, so the file carries one under a key: the convention every schema
		// that has had to solve this uses, and a field here so that an actual misspelling is still
		// refused.
		Comment  []string  `json:"$comment"`
		Profiles []Profile `json:"profiles"`
	}
	decoder := json.NewDecoder(strings.NewReader(string(raw)))
	// A misspelled field in a profile would otherwise be a silent default, and a report built on a
	// default nobody meant is worse than a run that refused to start.
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&file); err != nil {
		return nil, fmt.Errorf("drive: %s: %w", path, err)
	}
	if len(file.Profiles) == 0 {
		return nil, fmt.Errorf("drive: %s names no profiles", path)
	}
	names := map[string]bool{}
	resolved := make([]Profile, 0, len(file.Profiles))
	for _, profile := range file.Profiles {
		if names[profile.Name] {
			return nil, fmt.Errorf("drive: %s names %q twice", path, profile.Name)
		}
		names[profile.Name] = true
		filled, err := profile.Resolve()
		if err != nil {
			return nil, fmt.Errorf("drive: %s: %w", path, err)
		}
		resolved = append(resolved, filled)
	}
	return resolved, nil
}

// Find is one profile by name, with the names that do exist when it is not one of them.
func Find(profiles []Profile, name string) (Profile, error) {
	for _, profile := range profiles {
		if profile.Name == name {
			return profile, nil
		}
	}
	known := make([]string, 0, len(profiles))
	for _, profile := range profiles {
		known = append(known, profile.Name)
	}
	return Profile{}, fmt.Errorf("drive: no profile called %q; there is %s",
		name, strings.Join(known, ", "))
}

// Resolve fills in the defaults and refuses a profile that cannot be run.
//
// The defaults are modest on purpose. A profile that does not say how long to measure measures for
// ten seconds, which is long enough to be a shape and short enough that a mistake is noticed in the
// first run rather than the first hour.
func (p Profile) Resolve() (Profile, error) {
	if strings.TrimSpace(p.Name) == "" {
		return p, fmt.Errorf("a profile needs a name")
	}
	if p.Publishers <= 0 {
		p.Publishers = 1
	}
	if p.Listeners < 0 {
		return p, fmt.Errorf("%s: listeners cannot be negative", p.Name)
	}
	if p.ChannelsPerListener <= 0 {
		p.ChannelsPerListener = 1
	}
	if p.ChannelsPerListener > p.Publishers {
		return p, fmt.Errorf(
			"%s: a listener cannot hold %d channels when there are %d publishers",
			p.Name, p.ChannelsPerListener, p.Publishers)
	}
	// The gateway's own bound on one grant (config.DefaultMostChannels). A profile that asked for
	// more would be measuring a refusal.
	if p.ChannelsPerListener > 32 {
		return p, fmt.Errorf("%s: the gateway grants at most 32 channels in one ticket", p.Name)
	}
	if p.PayloadBytes <= 0 {
		p.PayloadBytes = 1024
	}
	if p.PublishEvery <= 0 {
		p.PublishEvery = Duration(time.Second)
	}
	if p.Proposals <= 0 {
		p.Proposals = 8
	}
	if p.Revisions <= 0 {
		p.Revisions = 3
	}
	if p.PageSize <= 0 {
		p.PageSize = 50
	}
	if p.PageSize > 200 {
		return p, fmt.Errorf("%s: the gateway's largest page is 200", p.Name)
	}
	if p.Readers > 0 && p.ReadEvery <= 0 {
		p.ReadEvery = Duration(2 * time.Second)
	}
	if p.Measure <= 0 {
		p.Measure = Duration(10 * time.Second)
	}
	if p.BrokerNodes < 0 {
		return p, fmt.Errorf("%s: brokerNodes cannot be negative", p.Name)
	}
	if p.BrokerNodes == 0 {
		p.BrokerNodes = 1
	}
	if len(p.Stages) == 0 {
		p.Stages = []int{p.Listeners}
	}
	if !slices.IsSorted(p.Stages) {
		return p, fmt.Errorf("%s: the stages climb, so they have to be written in order", p.Name)
	}
	for _, stage := range p.Stages {
		if stage < 0 {
			return p, fmt.Errorf("%s: a stage cannot have %d listeners", p.Name, stage)
		}
	}
	if p.Health.LeastDelivered < 0 || p.Health.LeastDelivered > 1 {
		return p, fmt.Errorf("%s: leastDelivered is a fraction", p.Name)
	}
	if p.Health.MostLost < 0 || p.Health.MostLost > 1 {
		return p, fmt.Errorf("%s: mostLost is a fraction", p.Name)
	}
	return p, nil
}

// Listeners a stage runs, which is the stage's own count.
func (p Profile) stage(index int) int {
	if index < 0 || index >= len(p.Stages) {
		return p.Listeners
	}
	return p.Stages[index]
}
