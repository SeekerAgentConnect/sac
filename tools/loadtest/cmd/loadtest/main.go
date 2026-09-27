// The load, isolation and failover harness (SEE-99).
//
//	loadtest --list                      what there is to run
//	loadtest                             every scenario this machine can
//	loadtest --scenario drain            one of them
//	loadtest --profile hot --scenario steady --listeners 50 --measure 5s
//	loadtest --report out/see-99.json    the evidence, as JSON
//
// It needs the gateway and `feed-gatewayctl` built, and the pinned broker to run anything that
// streams:
//
//	SEEKERVAULT_FEED_GATEWAY=…/feed-gateway SEEKERVAULT_FEED_GATEWAYCTL=…/feed-gatewayctl \
//	SEEKERVAULT_CENTRIFUGO=…/centrifugo SEEKERVAULT_REDIS=…/redis-server loadtest
//
// `pnpm test:load` builds the two Go binaries and passes those four paths in, which is the way to
// run it; everything here works on its own for someone pointing the harness at a machine of their
// own (docs/development/load.md).
//
// A scenario that cannot run says so and does not pass. That is the same rule
// `scripts/test-integration.mjs` follows and the reason `docs/testing/see-99.md` can be trusted:
// every line in it is PASS, FAIL or NOT RUN.
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/loadtest/internal/deploy"
	"github.com/BrRenat/SeekerAgentWallet/loadtest/internal/drive"
	"github.com/BrRenat/SeekerAgentWallet/loadtest/internal/report"
)

func main() {
	profiles := flag.String("profiles", "", "the profile file (default: tools/loadtest/profiles.json)")
	which := flag.String("scenario", "", "one scenario, or a comma-separated list; empty is all")
	profile := flag.String("profile", "", "run this profile instead of the scenario's own")
	listeners := flag.Int("listeners", 0, "override the profile's listener count")
	measured := flag.Duration("measure", 0, "override the profile's measured window")
	nodes := flag.Int("broker-nodes", 0, "override the number of broker nodes")
	config := flag.String("broker-config", "",
		"the broker configuration (default: services/gateway/centrifugo.yaml, found by walking up)")
	out := flag.String("report", "", "write the JSON report here")
	settle := flag.Duration("settle", 5*time.Second,
		"how long to wait between scenarios, for the kernel's ephemeral ports to drain")
	list := flag.Bool("list", false, "print the scenarios and profiles, and run nothing")
	flag.Parse()

	path := *profiles
	if path == "" {
		found, err := profileFile()
		if err != nil {
			fail("%v", err)
		}
		path = found
	}
	all, err := drive.Profiles(path)
	if err != nil {
		fail("%v", err)
	}
	scenarios := drive.Scenarios()

	if *list {
		fmt.Printf("Scenarios (%s):\n", path)
		for _, scenario := range scenarios {
			fmt.Printf("  %-15s %s\n", scenario.Name, scenario.Note)
		}
		fmt.Println("\nProfiles:")
		for _, one := range all {
			fmt.Printf("  %-15s %d listeners, %s, %s payload, one every %s — %s\n",
				one.Name, one.Listeners, many(one.Publishers, "publisher"),
				bytes(one.PayloadBytes), one.PublishEvery, one.Note)
		}
		return
	}

	chosen, err := choose(scenarios, *which)
	if err != nil {
		fail("%v", err)
	}
	binaries := deploy.FromEnvironment()
	if *config != "" {
		binaries.BrokerConfig = *config
	}
	summary := report.New("SEE-99", revision())
	summary.Versions = versions(binaries)

	if binaries.Gateway == "" || binaries.Control == "" {
		fail("SEEKERVAULT_FEED_GATEWAY and SEEKERVAULT_FEED_GATEWAYCTL must name the built binaries.\n" +
			"Run `pnpm test:load`, which builds them, or see docs/development/load.md.")
	}

	started := time.Now()
	ctx, stop := context.WithCancel(context.Background())
	defer stop()
	for index, scenario := range chosen {
		if index > 0 && *settle > 0 {
			// Every scenario leaves thousands of closed sockets behind, and a loopback ephemeral
			// port is held for half a minute after it closes. Waiting here costs seconds and saves
			// a scenario that would otherwise fail to connect to anything at all.
			time.Sleep(*settle)
		}
		if reason := cannot(scenario, all, binaries); reason != "" {
			summary.NotRun[scenario.Name] = reason
			fmt.Printf("NOT RUN %s — %s\n", scenario.Name, reason)
			continue
		}
		selected := scenario.Profile
		if *profile != "" {
			selected = *profile
		}
		one, err := drive.Find(all, selected)
		if err != nil {
			fail("%v", err)
		}
		if *listeners > 0 {
			one.Listeners = *listeners
			one.Stages = nil
		}
		if *measured > 0 {
			one.Measure = drive.Duration(*measured)
		}
		if *nodes > 0 {
			one.BrokerNodes = *nodes
		}
		one, err = one.Resolve()
		if err != nil {
			fail("%v", err)
		}
		fmt.Printf("── %s (%s) ", scenario.Name, one.Name)
		result, err := drive.Execute(ctx, binaries, one, scenario)
		if err != nil {
			// A scenario that could not be run is a failure of this run rather than the end of
			// it: losing ten minutes of measurement because the eleventh scenario could not get a
			// port is not a trade anybody would make on purpose.
			fmt.Printf("DID NOT RUN — %v\n", err)
			result.Scenario = scenario.Name
			result.Note = scenario.Note
			result.Failures = append(result.Failures, err.Error())
			summary.Results = append(summary.Results, result)
			continue
		}
		if result.Passed() {
			fmt.Printf("PASS in %s\n", time.Duration(result.Took).Round(time.Second))
		} else {
			fmt.Printf("FAIL in %s\n", time.Duration(result.Took).Round(time.Second))
		}
		summary.Results = append(summary.Results, result)
	}
	summary.Took = drive.Duration(time.Since(started))
	summary.Summary(os.Stdout)
	if *out != "" {
		if err := summary.Write(*out); err != nil {
			fail("%v", err)
		}
		fmt.Printf("The evidence is in %s\n", *out)
	}
	for _, result := range summary.Results {
		if !result.Passed() {
			os.Exit(1)
		}
	}
}

// cannot says why a scenario has to be skipped, or nothing at all.
func cannot(scenario drive.Scenario, profiles []drive.Profile, binaries deploy.Binaries) string {
	one, err := drive.Find(profiles, scenario.Profile)
	if err != nil {
		return err.Error()
	}
	if scenario.Prepare != nil {
		scenario.Prepare(&one)
	}
	if one.BrokerNodes > 0 && binaries.Broker == "" {
		return "SEEKERVAULT_CENTRIFUGO is not set, and this scenario streams"
	}
	if one.BrokerNodes > 1 && binaries.Redis == "" {
		return "SEEKERVAULT_REDIS is not set, and two broker nodes are one broker only through it"
	}
	return ""
}

// choose is the scenarios named, in the order they were named, or all of them.
func choose(all []drive.Scenario, named string) ([]drive.Scenario, error) {
	if strings.TrimSpace(named) == "" {
		return all, nil
	}
	byName := map[string]drive.Scenario{}
	for _, scenario := range all {
		byName[scenario.Name] = scenario
	}
	var chosen []drive.Scenario
	for _, want := range strings.Split(named, ",") {
		want = strings.TrimSpace(want)
		scenario, known := byName[want]
		if !known {
			names := make([]string, 0, len(all))
			for _, one := range all {
				names = append(names, one.Name)
			}
			return nil, fmt.Errorf("no scenario called %q; there is %s",
				want, strings.Join(names, ", "))
		}
		chosen = append(chosen, scenario)
	}
	return chosen, nil
}

// profileFile finds tools/loadtest/profiles.json by walking up from the working directory, so the harness
// runs from anywhere in the checkout.
func profileFile() (string, error) {
	at, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for {
		candidate := filepath.Join(at, "tools", "loadtest", "profiles.json")
		if _, err := os.Stat(candidate); err == nil {
			return candidate, nil
		}
		parent := filepath.Dir(at)
		if parent == at {
			return "", fmt.Errorf(
				"tools/loadtest/profiles.json is not above the working directory; name it with --profiles")
		}
		at = parent
	}
}

// revision is the commit this report is of, which is the first thing SEE-99 asks a report to name.
func revision() string {
	described, err := exec.Command("git", "rev-parse", "HEAD").Output()
	if err != nil {
		return "unknown: this was not run from a git checkout"
	}
	revision := strings.TrimSpace(string(described))
	if dirty, err := exec.Command("git", "status", "--porcelain").Output(); err == nil {
		if strings.TrimSpace(string(dirty)) != "" {
			return revision + " (with uncommitted changes)"
		}
	}
	return revision
}

// versions asks each binary what it is, so a report names what ran rather than what was pinned.
func versions(binaries deploy.Binaries) map[string]string {
	found := map[string]string{}
	if binaries.Broker != "" {
		if output, err := exec.Command(binaries.Broker, "version").Output(); err == nil {
			found["broker"] = strings.TrimSpace(string(output))
		}
	}
	if binaries.Redis != "" {
		if output, err := exec.Command(binaries.Redis, "--version").Output(); err == nil {
			found["redis"] = strings.TrimSpace(string(output))
		}
	}
	return found
}

// many is a count with its noun, singular when it is one. A summary that says "1 publishers" is a
// summary nobody proofread.
func many(count int, noun string) string {
	if count == 1 {
		return fmt.Sprintf("%d %s", count, noun)
	}
	return fmt.Sprintf("%d %ss", count, noun)
}

func bytes(size int) string {
	if size >= 1024 {
		return fmt.Sprintf("%.1f KiB", float64(size)/1024)
	}
	return fmt.Sprintf("%d B", size)
}

func fail(format string, argument ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", argument...)
	os.Exit(1)
}
