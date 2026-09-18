package drive

import (
	"context"
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/loadtest/internal/deploy"
	"github.com/BrRenat/SeekerAgentWallet/loadtest/internal/listen"
)

// The experiments (SEE-99).
//
// A profile says what the workload is; a scenario says what is done to it and what must still be
// true afterwards. They are separate because the interesting questions are the same question asked
// of different workloads — "does anything get lost when a node goes away" is worth asking of one
// hot channel and of forty quiet ones — and because a scenario that carried its own workload would
// be a number nobody could compare with the baseline.
//
// Every scenario's check returns the sentences that were **not** true. An empty answer is a pass,
// and a failure says what it expected in the same breath as what it found, because a load report
// nobody can read is a load report nobody will act on.

// Scenario is one named experiment.
type Scenario struct {
	Name string
	Note string
	// The profile it runs, by name in profiles.json.
	Profile string
	// Adjusts the profile before the deployment is built: a scenario that needs two broker nodes
	// says so here rather than depending on a profile that happens to have them.
	Prepare func(profile *Profile)
	// Arranges the interruptions, after the publishers are registered and before the window.
	Arrange func(run *Run)
	// How long to wait for the listeners to catch up afterwards. Zero means ten seconds, which is
	// plenty when nothing was broken.
	Converge time.Duration
	// What must be true. Every string it returns is something that was not.
	Check func(result Result) []string
}

// Result is one scenario's run, and the whole of what a report is written from.
type Result struct {
	Scenario string   `json:"scenario"`
	Note     string   `json:"note"`
	Profile  Profile  `json:"profile"`
	Topology Topology `json:"topology"`

	Stages      []Stage     `json:"stages"`
	Convergence Convergence `json:"convergence"`
	// The controlled push stand-in's record, when the profile ran the relay.
	Hints     map[string]int `json:"hints,omitempty"`
	Exchanges int            `json:"token_exchanges,omitempty"`
	// Anything a stray send named that was not a topic. Always empty, and asserted.
	Addressed []string `json:"addressed,omitempty"`

	// For a ramp: the largest stage that held its health rule, and where it stopped.
	Reached int    `json:"listeners_reached"`
	Stopped string `json:"stopped_because,omitempty"`
	// How many listeners were told to stop reading, and for how long. The slow scenario is the one
	// that sets them, and its check needs to know which listeners are allowed to be behind.
	Stalled  int      `json:"stalled_listeners,omitempty"`
	StallFor Duration `json:"stalled_for,omitempty"`

	// What the scenario's own checks found. Empty is a pass.
	Failures []string `json:"failures,omitempty"`
	Started  string   `json:"started"`
	Took     Duration `json:"took"`
}

// Passed says whether every check held.
func (r Result) Passed() bool { return len(r.Failures) == 0 }

// Last is the final stage, which for a single-stage profile is the only one.
func (r Result) Last() Stage {
	if len(r.Stages) == 0 {
		return Stage{}
	}
	return r.Stages[len(r.Stages)-1]
}

// Counter is one counter from the last stage.
func (r Result) Counter(name string) int64 { return r.Last().Counters[name] }

// Counters is every counter from the last stage whose name begins with `prefix`.
func (r Result) CountersWith(prefix string) map[string]int64 {
	matching := map[string]int64{}
	for name, value := range r.Last().Counters {
		if strings.HasPrefix(name, prefix) {
			matching[name] = value
		}
	}
	return matching
}

// Total is the sum of every counter whose name begins with `prefix`.
func (r Result) Total(prefix string) int64 {
	total := int64(0)
	for _, value := range r.CountersWith(prefix) {
		total += value
	}
	return total
}

// Topology is what was running, for the report: every version, every address, and what was not
// there.
type Topology struct {
	Gateway     string   `json:"gateway"`
	BrokerNodes int      `json:"broker_nodes"`
	Broker      string   `json:"broker,omitempty"`
	Redis       string   `json:"redis,omitempty"`
	Engine      string   `json:"broker_engine"`
	Push        string   `json:"push,omitempty"`
	Proxy       string   `json:"proxy"`
	Limits      []string `json:"gateway_limits"`
}

// Execute runs one scenario end to end and answers with everything the report needs.
func Execute(
	ctx context.Context, binaries deploy.Binaries, profile Profile, scenario Scenario,
) (Result, error) {
	if scenario.Prepare != nil {
		scenario.Prepare(&profile)
	}
	resolved, err := profile.Resolve()
	if err != nil {
		return Result{}, err
	}
	profile = resolved

	started := time.Now()
	deployment, err := deploy.Start(ctx, deploy.Options{
		Binaries:     binaries,
		BrokerNodes:  profile.BrokerNodes,
		PublishRate:  profile.PublishRate,
		PublishBurst: profile.PublishBurst,
		ReadRate:     profile.ReadRate,
		ReadBurst:    profile.ReadBurst,
		MaxProposals: profile.MaxProposals,
		Push:         profile.Push,
	})
	if err != nil {
		return Result{}, err
	}
	defer deployment.Close()

	run, err := Start(ctx, deployment, profile)
	if err != nil {
		return Result{}, err
	}
	defer run.Stop()
	if scenario.Arrange != nil {
		scenario.Arrange(run)
	}

	result := Result{
		Stalled:  run.Stalling,
		StallFor: Duration(run.StallFor),
		Scenario: scenario.Name,
		Note:     scenario.Note,
		Profile:  profile,
		Topology: topology(deployment, profile),
		Started:  started.UTC().Format(time.RFC3339),
	}
	for index := range profile.Stages {
		stage, err := run.Measure(ctx, profile.stage(index))
		if err != nil {
			return result, err
		}
		result.Stages = append(result.Stages, stage)
		if !stage.Healthy {
			result.Stopped = strings.Join(stage.Unhealthy, "; ")
			break
		}
		result.Reached = stage.Listeners
	}

	within := scenario.Converge
	if within <= 0 {
		within = 10 * time.Second
	}
	convergence, err := run.Converge(ctx, within)
	if err != nil {
		return result, err
	}
	result.Convergence = convergence
	if deployment.Push != nil {
		result.Hints, result.Exchanges = deployment.Push.Hints()
		result.Addressed = deployment.Push.Addressed()
	}
	result.Took = Duration(time.Since(started))
	if scenario.Check != nil {
		result.Failures = scenario.Check(result)
	}
	return result, nil
}

// topology is what was actually running, asked of the processes rather than assumed.
func topology(deployment *deploy.Deployment, profile Profile) Topology {
	engine := "memory"
	if deployment.Redis != nil {
		engine = "redis"
	}
	limits := []string{}
	for name, value := range map[string]float64{
		"publish rate": profile.PublishRate,
		"read rate":    profile.ReadRate,
	} {
		if value > 0 {
			limits = append(limits, fmt.Sprintf("%s %g/s", name, value))
		} else {
			limits = append(limits, name+" the gateway's default")
		}
	}
	for name, value := range map[string]int{
		"publish burst": profile.PublishBurst,
		"read burst":    profile.ReadBurst,
		"max proposals": profile.MaxProposals,
	} {
		if value > 0 {
			limits = append(limits, fmt.Sprintf("%s %d", name, value))
		} else {
			limits = append(limits, name+" the gateway's default")
		}
	}
	sort.Strings(limits)
	push := ""
	if deployment.Push != nil {
		push = "the controlled stand-in on " + deployment.Push.Endpoint
	}
	return Topology{
		Gateway:     deployment.Origin,
		BrokerNodes: len(deployment.Nodes),
		Engine:      engine,
		Push:        push,
		// Said out loud because it is the one part of the deployment that is not in the path.
		Proxy:  "none: broadcast/Caddyfile is not in this path (no Docker daemon on this machine)",
		Limits: limits,
	}
}

// Scenarios is every experiment, in the order a report reads best: the baseline, then the failures,
// then the things that must not be possible.
func Scenarios() []Scenario {
	return []Scenario{
		{
			Name:    "steady",
			Note:    "the baseline: one hot channel, one broker node, nothing interrupted",
			Profile: "hot",
			Check:   converged,
		},
		{
			Name:    "spread",
			Note:    "many publishers, one channel each, listeners divided between them",
			Profile: "spread",
			Check:   converged,
		},
		{
			Name:    "mixed",
			Note:    "proposals, manifest revisions and withdrawals, with snapshot readers walking",
			Profile: "mixed",
			Check:   converged,
		},
		{
			Name:    "two-nodes",
			Note:    "the same workload across two broker nodes on one Redis",
			Profile: "hot",
			Prepare: func(profile *Profile) { profile.BrokerNodes = 2 },
			Check: func(result Result) []string {
				failures := converged(result)
				serving := 0
				for _, node := range result.Last().Nodes {
					if node.Clients > 0 {
						serving++
					}
				}
				if serving < 2 {
					failures = append(failures, fmt.Sprintf(
						"listeners landed on %d of the 2 nodes, so this run did not compare them",
						serving))
				}
				return failures
			},
		},
		{
			Name: "drain",
			Note: "one broker node drained with SIGTERM mid-window, and started again",
			// A listener keeps the node it was given. There is no proxy in this path to move it to
			// the surviving one, so this is the worst case on purpose: the drained node's
			// listeners wait for it to come back, and the p99 below is what that waiting costs.
			Profile: "hot",
			Prepare: recovering(2),
			Arrange: func(run *Run) {
				run.Interrupts = []Interrupt{
					{
						At:   3 * time.Second,
						What: "broker node 2 drained with SIGTERM",
						Do: func(_ context.Context, run *Run) error {
							return run.Deployment().Nodes[1].Stop()
						},
					},
					{
						At:   8 * time.Second,
						What: "broker node 2 started again",
						Do: func(ctx context.Context, run *Run) error {
							return run.Deployment().Nodes[1].Start(ctx)
						},
					},
				}
			},
			Converge: 30 * time.Second,
			Check: func(result Result) []string {
				failures := recovered(result)
				if result.Counter("listener.reconnect") == 0 {
					failures = append(failures,
						"no listener reconnected, so nothing was actually drained")
				}
				if _, said := result.Convergence.Closes[fmt.Sprint(listen.Shutdown)]; !said {
					failures = append(failures, fmt.Sprintf(
						"no listener was closed with %d, which is what a graceful drain sends",
						listen.Shutdown))
				}
				return failures
			},
		},
		{
			Name:    "kill",
			Note:    "one broker node killed with SIGKILL mid-window, and started again",
			Profile: "hot",
			Prepare: recovering(2),
			Arrange: func(run *Run) {
				run.Interrupts = []Interrupt{
					{
						At:   3 * time.Second,
						What: "broker node 2 killed with SIGKILL",
						Do: func(_ context.Context, run *Run) error {
							return run.Deployment().Nodes[1].Kill()
						},
					},
					{
						At:   8 * time.Second,
						What: "broker node 2 started again",
						Do: func(ctx context.Context, run *Run) error {
							return run.Deployment().Nodes[1].Start(ctx)
						},
					},
				}
			},
			Converge: 30 * time.Second,
			Check: func(result Result) []string {
				failures := recovered(result)
				if result.Counter("listener.reconnect") == 0 {
					failures = append(failures, "no listener reconnected after a node was killed")
				}
				return failures
			},
		},
		{
			Name: "redis",
			Note: "Redis stopped under a two-node broker mid-window, and started again",
			// The delivered share is deliberately not asserted here, and the first run of this
			// scenario is why: losing Redis moves deliveries from the stream to the snapshot, by
			// design. A quarter of the window's publications reached their listeners as an
			// authoritative read rather than as a publication, every listener ended up holding
			// everything, and a rule that called that a failure would be calling the design one.
			Profile: "hot",
			Prepare: func(profile *Profile) {
				recovering(2)(profile)
				profile.Health.LeastDelivered = 0
			},
			Arrange: func(run *Run) {
				run.Interrupts = []Interrupt{
					{
						At:   3 * time.Second,
						What: "Redis stopped",
						Do: func(_ context.Context, run *Run) error {
							return run.Deployment().Redis.Stop()
						},
					},
					{
						At:   8 * time.Second,
						What: "Redis started again",
						Do: func(ctx context.Context, run *Run) error {
							return run.Deployment().Redis.Start(ctx)
						},
					},
				}
			},
			// The gateway defers a notice it could not fan out and retries with backoff, doubling
			// to a minute (internal/dispatch.Backoff), so catching up can take that long.
			Converge: 120 * time.Second,
			Check:    fellBack,
		},
		{
			Name:    "gateway",
			Note:    "the gateway restarted mid-window on the same database",
			Profile: "hot",
			Prepare: recovering(1),
			Arrange: func(run *Run) {
				run.Interrupts = []Interrupt{
					{
						At:   3 * time.Second,
						What: "the gateway stopped",
						Do: func(_ context.Context, run *Run) error {
							return run.Deployment().Gateway.Stop()
						},
					},
					{
						At:   6 * time.Second,
						What: "the gateway started again on the same database",
						Do: func(ctx context.Context, run *Run) error {
							return run.Deployment().Gateway.Start(ctx)
						},
					},
				}
			},
			Converge: 60 * time.Second,
			Check: func(result Result) []string {
				failures := recovered(result)
				// Publications during the restart must have failed rather than been silently
				// dropped: a publisher that was told nothing would have no reason to retry.
				if result.Total("publish.") == result.Counter("publish.accepted") {
					failures = append(failures,
						"every publication was accepted, so the gateway was never actually down")
				}
				return failures
			},
		},
		{
			Name: "slow",
			Note: "a fifth of the listeners stop reading, on a feed fast and fat enough to notice",
			// Its own profile: the largest document the rules allow, fifty a second. A slow
			// consumer cannot be demonstrated on a kilobyte every two hundred milliseconds, and a
			// scenario that pretended otherwise would be reporting that nothing happened.
			Profile: "slow",
			Prepare: recovering(1),
			Arrange: func(run *Run) {
				run.Stalling = max(run.Profile.Listeners/5, 1)
				run.StallFor = 6 * time.Second
				run.Interrupts = []Interrupt{{
					At:   2 * time.Second,
					What: "a fifth of the listeners stopped reading their socket",
					Do: func(_ context.Context, run *Run) error {
						run.Stall()
						return nil
					},
				}}
			},
			Converge: 60 * time.Second,
			Check:    slowly,
		},
		{
			Name: "flood",
			Note: "one publisher publishes far over its rate while another publishes normally",
			// The default bounds, because the refusal is the measurement.
			Profile: "spread",
			Prepare: func(profile *Profile) {
				profile.PublishRate = 0
				profile.PublishBurst = 0
				profile.PublishEvery = Duration(5 * time.Millisecond)
				// No steady-state rule. The workload here is deliberately a hundred times the
				// rate the gateway allows, so a latency and a delivered share measured across it
				// are measurements of a refusal — which is the point, and which the checks below
				// assert directly. A profile's health rule judged against this would report the
				// limiter working as a fault.
				profile.Health = Health{}
			},
			Check: func(result Result) []string {
				var failures []string
				refused := result.Total("publish.refused.")
				if refused == 0 {
					failures = append(failures, fmt.Sprintf(
						"nothing was refused at %v per publication, so the publish limit did "+
							"not apply", result.Profile.PublishEvery))
				}
				if _, named := result.Last().Counters["publish.refused.too_many_requests"]; !named {
					failures = append(failures, fmt.Sprintf(
						"the refusals were %v, and none of them was the rate limit",
						result.CountersWith("publish.refused.")))
				}
				return failures
			},
		},
		{
			Name:    "isolation",
			Note:    "a publisher tries to write another's channel, with and without its grant",
			Profile: "spread",
			// What this scenario asserts is refusals and what no listener saw, and its convergence.
			// The delivered share is not one of them: it counts publications a listener that was
			// still connecting had not subscribed to yet, and a burst of hundreds of listeners
			// really does take a moment.
			Prepare:  func(profile *Profile) { profile.Health = Health{} },
			Check:    isolated,
			Converge: 15 * time.Second,
			Arrange: func(run *Run) {
				run.Interrupts = []Interrupt{{
					At:   2 * time.Second,
					What: "one publisher tried three ways into another's channel",
					Do:   trespass,
				}}
			},
		},
		{
			Name:    "reconnect",
			Note:    "every listener's stream cut at once, by restarting the node they are on",
			Profile: "hot",
			Prepare: recovering(1),
			Arrange: func(run *Run) {
				run.Interrupts = []Interrupt{{
					At:   4 * time.Second,
					What: "the only broker node restarted, cutting every stream at once",
					Do: func(ctx context.Context, run *Run) error {
						node := run.Deployment().Nodes[0]
						if err := node.Stop(); err != nil {
							return err
						}
						return node.Start(ctx)
					},
				}}
			},
			Converge: 60 * time.Second,
			Check: func(result Result) []string {
				failures := recovered(result)
				reconnects := result.Counter("listener.reconnect")
				if reconnects < int64(result.Last().Listeners) {
					failures = append(failures, fmt.Sprintf(
						"%d reconnects for %d listeners: not every stream was cut",
						reconnects, result.Last().Listeners))
				}
				return failures
			},
		},
		{
			Name: "shared-address",
			Note: "every listener behind one address, as a whole office behind one NAT would be",
			// No forwarded address, so the gateway's read limiter counts every listener as the
			// same caller. This is not a mistake being measured: it is what a deployment really
			// does to a building full of phones, and the number is worth having.
			Profile:  "shared-address",
			Converge: 15 * time.Second,
			Check: func(result Result) []string {
				var failures []string
				// Any refusal, from any read. The first version of this looked only at the ticket
				// and missed the point: a hundred listeners that back off and retry do all get a
				// grant within a few seconds, and what the limiter refuses first is whichever read
				// happened to be next.
				refused := result.Counter("listener.ticket.refused") +
					result.Total("snapshot.refused.") + result.Total("read.refused.")
				if refused == 0 {
					failures = append(failures, fmt.Sprintf(
						"nothing was refused with %d listeners sharing one address, so either "+
							"the limiter did not apply or the forwarded address leaked in",
						result.Last().Listeners))
				}
				// Convergence is deliberately *not* asserted. Most of these listeners never get a
				// grant, which is the finding rather than a fault, and the report says how many
				// did.
				return failures
			},
		},
		{
			Name:     "ramp",
			Note:     "the same hot channel, climbing until a stage cannot hold its health rule",
			Profile:  "ramp",
			Converge: 60 * time.Second,
			Check: func(result Result) []string {
				if result.Reached == 0 {
					return []string{"not one stage held its health rule"}
				}
				// A ramp that stops early is the finding rather than a failure: what it must not
				// do is lose a document at a size it said it was healthy at.
				return converged(result)
			},
		},
	}
}

// recovering is what a failover scenario expects instead of a steady one's latency.
//
// A window with a node failure in it does not have a steady-state p99: a publication issued while a
// listener's node was away is delivered when the listener comes back, so the number measures
// **recovery**, and the bound on it is the phone's own backoff ceiling
// (`listen.MostBackoff` — 30 seconds, `FeedRecovery.MAX_BACKOFF_MILLIS`) plus the reconnect and the
// snapshot read it may need. Judging such a window by the steady profile's two seconds would be
// reporting the scenario as a fault.
//
// Every delivery is still expected to arrive, which is the part that matters: the bound moves, the
// completeness does not.
func recovering(nodes int) func(*Profile) {
	return func(profile *Profile) {
		profile.BrokerNodes = nodes
		profile.Health.MostP99 = Duration(45 * time.Second)
	}
}

// recovered is converged, plus the profile's own recovery bound having held.
func recovered(result Result) []string {
	failures := converged(result)
	if last := result.Last(); !last.Healthy {
		failures = append(failures, strings.Join(last.Unhealthy, "; "))
	}
	return failures
}

// converged is the check every scenario shares: every listener holds what the gateway holds.
func converged(result Result) []string {
	var failures []string
	convergence := result.Convergence
	if convergence.Listeners == 0 {
		return []string{"there were no listeners, so nothing was measured"}
	}
	if len(convergence.Short) > 0 {
		shown := convergence.Short
		if len(shown) > 5 {
			shown = shown[:5]
		}
		failures = append(failures, fmt.Sprintf(
			"%d of %d listeners are short of what the gateway holds: %s",
			len(convergence.Short), convergence.Listeners, strings.Join(shown, "; ")))
	}
	if unexpected := result.Total("listener.unexpected."); unexpected > 0 {
		failures = append(failures, fmt.Sprintf(
			"%d publications arrived on channels a listener was not granted", unexpected))
	}
	if result.Counter("listener.unreadable") > 0 {
		failures = append(failures, fmt.Sprintf("%d publications were not a FeedEvent",
			result.Counter("listener.unreadable")))
	}
	if len(result.Addressed) > 0 {
		failures = append(failures, fmt.Sprintf(
			"the push stand-in was sent %d messages that named something other than a topic: %v",
			len(result.Addressed), result.Addressed))
	}
	return failures
}

// fellBack is what an outage of the **recovery cache** must look like: nothing lost, and the
// listeners that could not be caught up from history having read the gateway instead.
//
// Redis is a cache and not the source of truth — the gateway's SQLite file is that
// (broadcast/compose.yaml says so where it declares the service) — so the thing to assert is the
// fallback, not the stream. What would be serious is a listener that ended the run short of a
// document, or one that neither recovered nor read a snapshot.
func fellBack(result Result) []string {
	failures := recovered(result)
	fallbacks := result.Convergence.Fallbacks
	if fallbacks["epoch changed"] == 0 && fallbacks["too far behind"] == 0 {
		failures = append(failures, fmt.Sprintf(
			"no listener had to read a snapshot, so the recovery cache was never actually "+
				"interrupted: the fallbacks were %v", fallbacks))
	}
	if result.Convergence.Documents == 0 {
		failures = append(failures, "the gateway held nothing at the end, so nothing was compared")
	}
	return failures
}

// slowly is what the slow scenario must find, and it is deliberately not "the broker closed them".
//
// What the shipped configuration does about a listener that stops reading was measured by hand for
// SEE-91 and written down: `queue_max_size: 65536` protects the **broker's** memory, and the
// transport's own receive window absorbs megabytes before that queue grows at all — a non-reading
// client survived eight megabytes (docs/testing/stage-7-1.md,
// docs/wiki/broadcast-gateway.md#the-transport-and-what-it-cannot-do). So a close is an outcome
// this scenario reports rather than one it requires; what it asserts is the part that would be
// serious either way.
func slowly(result Result) []string {
	var failures []string
	if result.Counter("listener.stalled") == 0 {
		failures = append(failures, "no listener ever stopped reading, so nothing was measured")
	}
	// The listeners that kept reading must have lost nothing. A stalled listener being behind is
	// the scenario; a healthy one being behind would be the finding.
	for _, index := range result.Convergence.ShortListeners {
		if index >= result.Stalled {
			failures = append(failures, fmt.Sprintf(
				"listener %d kept reading and is still short of the gateway's state (%d were "+
					"stalled)", index, result.Stalled))
		}
	}
	if unexpected := result.Total("listener.unexpected."); unexpected > 0 {
		failures = append(failures, fmt.Sprintf(
			"%d publications arrived on channels a listener was not granted", unexpected))
	}
	return failures
}

// trespass is the isolation experiment: three ways one publisher might reach another's channel.
func trespass(ctx context.Context, run *Run) error {
	writers := run.Writers()
	documents := run.Documents()
	if len(writers) < 2 {
		return fmt.Errorf("isolation needs two publishers")
	}
	mine, theirs := writers[0], writers[1]
	// One: my grant, their channel. The document names their server, and the credential names me.
	stolen := documents[1].Proposal(ID("trespass/1"), 1, time.Now(), time.Hour)
	if _, _, err := mine.Publish(ctx, stolen); err == nil {
		run.Counters.Count("isolation.accepted.their_channel_my_grant")
	} else {
		run.Counters.Add("isolation.refused."+Problem(err), 1)
	}
	// Two: their grant, my channel. A credential that leaked does not become a way into a channel
	// it does not name.
	asThem := mine.As(theirs.credential)
	ofMine := documents[0].Proposal(ID("trespass/2"), 1, time.Now(), time.Hour)
	if _, _, err := asThem.Publish(ctx, ofMine); err == nil {
		run.Counters.Count("isolation.accepted.my_channel_their_grant")
	} else {
		run.Counters.Add("isolation.refused."+Problem(err), 1)
	}
	// Three: their grant, their channel, from me. This one is *allowed* — a credential is the
	// whole of the authority, which is worth stating as a measurement rather than assuming: what
	// isolation rests on is that credentials are not guessable and are not shared, and the
	// gateway's job is to check the claim rather than to know who typed it.
	theirOwn := documents[1].Proposal(ID("trespass/3"), 1, time.Now(), time.Hour)
	if _, _, err := asThem.Publish(ctx, theirOwn); err == nil {
		run.Counters.Count("isolation.accepted.their_channel_their_grant")
	} else {
		run.Counters.Add("isolation.refused."+Problem(err), 1)
	}
	// Four: a credential that never existed.
	nobody := mine.As(strings.Repeat("a", 43))
	if _, _, err := nobody.Publish(ctx, ofMine); err == nil {
		run.Counters.Count("isolation.accepted.no_grant")
	} else {
		run.Counters.Add("isolation.refused."+Problem(err), 1)
	}
	return nil
}

// isolated is what the isolation scenario must find.
func isolated(result Result) []string {
	failures := converged(result)
	for _, accepted := range []string{
		"isolation.accepted.their_channel_my_grant",
		"isolation.accepted.my_channel_their_grant",
		"isolation.accepted.no_grant",
	} {
		if count := result.Counter(accepted); count > 0 {
			failures = append(failures, fmt.Sprintf("%s: %d", accepted, count))
		}
	}
	if result.Counter("isolation.accepted.their_channel_their_grant") == 0 {
		failures = append(failures, "a publisher could not publish to its own channel with its "+
			"own credential, so this run proved nothing about the refusals beside it")
	}
	refusals := result.CountersWith("isolation.refused.")
	for _, expected := range []string{
		"isolation.refused.other_server",
		"isolation.refused.unauthenticated",
	} {
		if _, named := refusals[expected]; !named {
			failures = append(failures, fmt.Sprintf(
				"no refusal was %s; they were %v", expected, refusals))
		}
	}
	return failures
}
