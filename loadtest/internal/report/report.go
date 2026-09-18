// Package report is what a run leaves behind (SEE-99).
//
// Two forms of the same thing: a JSON document, which is the evidence
// `docs/testing/see-99.md` is written from and the thing to diff between two revisions, and a
// summary a person reads while the run is still going.
//
// The rule both follow: **a number that was not measured is not printed**. A stage that was never
// reached is a line saying so, a counter that was never incremented is absent rather than zero, and
// a resource nothing could sample is "not measured" rather than a blank that reads as none. Every
// load report that has ever misled anybody did it by printing a zero.
package report

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/loadtest/internal/drive"
	"github.com/BrRenat/SeekerAgentWallet/loadtest/internal/measure"
)

// Report is every scenario that ran, and what it ran on.
type Report struct {
	// What the report is of: the ticket, the revision, and the machine.
	Ticket   string            `json:"ticket"`
	Revision string            `json:"revision"`
	Machine  Machine           `json:"machine"`
	Versions map[string]string `json:"versions"`

	Started string         `json:"started"`
	Took    drive.Duration `json:"took"`
	Results []drive.Result `json:"results"`
	// Scenarios that did not run, and why. A leg that could not run is never silently a pass.
	NotRun map[string]string `json:"not_run,omitempty"`
}

// Machine is what the run was made on, in the detail a reader needs to judge a number.
type Machine struct {
	OS        string `json:"os"`
	Arch      string `json:"arch"`
	CPUs      int    `json:"cpus"`
	GoVersion string `json:"go"`
	Model     string `json:"model,omitempty"`
	Memory    string `json:"memory,omitempty"`
	// The descriptor limit, because it is the first thing that stops a run of this shape.
	OpenFiles string `json:"open_files_limit,omitempty"`
}

// New starts a report.
func New(ticket, revision string) *Report {
	return &Report{
		Ticket:   ticket,
		Revision: revision,
		Machine:  machine(),
		Versions: map[string]string{},
		Started:  time.Now().UTC().Format(time.RFC3339),
		NotRun:   map[string]string{},
	}
}

// Write saves the JSON.
func (r *Report) Write(path string) error {
	if directory := filepath.Dir(path); directory != "" {
		if err := os.MkdirAll(directory, 0o755); err != nil {
			return fmt.Errorf("report: %w", err)
		}
	}
	body, err := json.MarshalIndent(r, "", "  ")
	if err != nil {
		return fmt.Errorf("report: %w", err)
	}
	return os.WriteFile(path, append(body, '\n'), 0o644)
}

// Summary prints what a person wants to see: one block per scenario, the numbers that matter, and
// the failures spelled out.
func (r *Report) Summary(to io.Writer) {
	say := func(format string, argument ...any) {
		_, _ = fmt.Fprintf(to, format+"\n", argument...)
	}
	say("")
	say("%s — the broadcast transport under load", r.Ticket)
	say("")
	say("  revision  %s", r.Revision)
	say("  machine   %s/%s, %d processors%s", r.Machine.OS, r.Machine.Arch, r.Machine.CPUs,
		optional(", ", r.Machine.Model))
	if r.Machine.Memory != "" || r.Machine.OpenFiles != "" {
		say("            %s%s", r.Machine.Memory, optional(", ", r.Machine.OpenFiles+" descriptors"))
	}
	for _, name := range sorted(r.Versions) {
		say("  %-9s %s", name, r.Versions[name])
	}
	for _, result := range r.Results {
		say("")
		say("── %s ── %s", result.Scenario, result.Note)
		last := result.Last()
		say("   %s, %s, %s, %s payload, one every %s",
			result.Profile.Name, many(last.Listeners, "listener"),
			many(last.Publishers, "publisher"),
			measure.Bytes(int64(result.Profile.PayloadBytes)), result.Profile.PublishEvery)
		say("   %s on the %s engine; proxy: %s",
			many(result.Topology.BrokerNodes, "broker node"),
			result.Topology.Engine, result.Topology.Proxy)
		for _, stage := range result.Stages {
			say("   %s", stageLine(stage))
		}
		if len(result.Stages) > 1 {
			if result.Stopped == "" {
				say("   reached %d listeners, and every stage held its health rule",
					result.Reached)
			} else {
				say("   reached %d listeners; the next stage stopped it: %s",
					result.Reached, result.Stopped)
			}
		}
		say("   publish call  %s", quantiles(last.Publish))
		if last.Snapshot.Count > 0 {
			say("   recovery read %s", quantiles(last.Snapshot))
		}
		if last.Read.Count > 0 {
			say("   reader walk   %s", quantiles(last.Read))
		}
		if last.Ticket.Count > 0 {
			say("   ticket        %s", quantiles(last.Ticket))
		}
		for _, node := range last.Nodes {
			say("   %-9s holding %d clients on %d channels (%d subscriptions), %d publications "+
				"accepted and %d fanned out, %d nodes in the cluster%s",
				node.Name, node.Clients, node.Channels, node.Subscriptions,
				node.Accepted, node.FannedOut, node.Nodes, dropped(node.Dropped))
		}
		for _, usage := range last.Usage {
			say("   %-9s using %s resident at its peak, %s of processor time%s%s",
				usage.Name, measure.Bytes(usage.Resident), usage.CPU.Round(time.Millisecond),
				files(usage.Files), ended(usage.Ended))
		}
		say("   %s", convergence(result.Convergence))
		for _, note := range last.Notes {
			say("   · %s", note)
		}
		if interesting := interestingCounters(last.Counters); len(interesting) > 0 {
			say("   %s", strings.Join(interesting, ", "))
		}
		if len(result.Hints) > 0 {
			say("   %d hints on %d topics through the controlled stand-in, %d token exchanges",
				total(result.Hints), len(result.Hints), result.Exchanges)
		}
		if result.Passed() {
			say("   PASS")
			continue
		}
		say("   FAIL")
		for _, failure := range result.Failures {
			say("     · %s", failure)
		}
	}
	if len(r.NotRun) > 0 {
		say("")
		for _, name := range sortedStrings(r.NotRun) {
			say("   NOT RUN %s — %s", name, r.NotRun[name])
		}
	}
	say("")
}

func stageLine(stage drive.Stage) string {
	health := "healthy"
	if !stage.Healthy {
		health = "UNHEALTHY: " + strings.Join(stage.Unhealthy, "; ")
	}
	return fmt.Sprintf(
		"%6d listeners  %5d published  %8d/%8d delivered (%.4f)  p50 %-8s p95 %-8s p99 %-8s  %s",
		stage.Listeners, stage.Published, stage.Delivered, stage.Expected, stage.Share(),
		stage.Delivery.P50.Round(time.Millisecond*1),
		stage.Delivery.P95.Round(time.Millisecond*1),
		stage.Delivery.P99.Round(time.Millisecond*1),
		health)
}

func quantiles(q measure.Quantiles) string {
	if q.Count == 0 {
		return "not measured"
	}
	sampled := ""
	if q.Sampled {
		sampled = " (from a uniform sample)"
	}
	return fmt.Sprintf("n=%d  min %s  p50 %s  p95 %s  p99 %s  max %s%s",
		q.Count,
		q.Min.Round(time.Microsecond), q.P50.Round(time.Microsecond),
		q.P95.Round(time.Microsecond), q.P99.Round(time.Microsecond),
		q.Max.Round(time.Microsecond), sampled)
}

func convergence(c drive.Convergence) string {
	parts := []string{fmt.Sprintf("%d of %d listeners hold every one of the gateway's %d documents",
		c.Converged, c.Listeners, c.Documents)}
	if c.Stopped > 0 {
		parts = append(parts, fmt.Sprintf("%d stopped for good", c.Stopped))
	}
	if c.Gaps > 0 {
		parts = append(parts, fmt.Sprintf("%d offset gaps (%d offsets)", c.Gaps, c.Missing))
	}
	if c.Duplicates > 0 {
		parts = append(parts, fmt.Sprintf("%d repeated offsets", c.Duplicates))
	}
	if c.Backwards > 0 {
		parts = append(parts, fmt.Sprintf("%d out of order", c.Backwards))
	}
	if c.Epochs > 0 {
		parts = append(parts, fmt.Sprintf("the history was replaced %d times", c.Epochs))
	}
	if len(c.Fallbacks) > 0 {
		parts = append(parts, "snapshot reads: "+counts(c.Fallbacks))
	}
	if len(c.Closes) > 0 {
		parts = append(parts, "closes: "+counts(c.Closes))
	}
	return strings.Join(parts, "; ")
}

// interestingCounters is the ones a reader should see without the twenty that are always there.
func interestingCounters(all map[string]int64) []string {
	var shown []string
	for _, name := range sortedInt64(all) {
		switch {
		case strings.HasPrefix(name, "publish.refused."),
			strings.HasPrefix(name, "read.refused."),
			strings.HasPrefix(name, "snapshot.refused."),
			strings.HasPrefix(name, "isolation."),
			strings.HasPrefix(name, "listener.closed."),
			strings.HasPrefix(name, "continuity.snapshot."),
			name == "delivery.duplicate", name == "delivery.ignored",
			name == "delivery.untimed", name == "listener.unreadable",
			name == "listener.stopped", name == "listener.stream.failed",
			name == "listener.ticket.refused", name == "publish.failed",
			name == "read.failed", name == "snapshot.failed":
			shown = append(shown, fmt.Sprintf("%s %d", name, all[name]))
		}
	}
	return shown
}

// many is a count with its noun, singular when it is one.
func many(count int, noun string) string {
	if count == 1 {
		return fmt.Sprintf("%d %s", count, noun)
	}
	return fmt.Sprintf("%d %ss", count, noun)
}

func machine() Machine {
	found := Machine{
		OS:        runtime.GOOS,
		Arch:      runtime.GOARCH,
		CPUs:      runtime.NumCPU(),
		GoVersion: runtime.Version(),
	}
	found.Model = command("sysctl", "-n", "machdep.cpu.brand_string")
	if bytes := command("sysctl", "-n", "hw.memsize"); bytes != "" {
		found.Memory = bytes + " bytes of memory"
	}
	found.OpenFiles = command("sh", "-c", "ulimit -n")
	return found
}

func command(name string, argument ...string) string {
	output, err := run(name, argument...)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(output)
}

func optional(separator, value string) string {
	if strings.TrimSpace(value) == "" {
		return ""
	}
	return separator + value
}

func files(count int) string {
	if count == 0 {
		return ", descriptors not measured"
	}
	return fmt.Sprintf(", %d descriptors", count)
}

func ended(did bool) string {
	if did {
		return " (it was stopped during the run)"
	}
	return ""
}

func dropped(count int64) string {
	if count == 0 {
		return ""
	}
	return fmt.Sprintf(", %d dropped by Redis", count)
}

func counts[T comparable](all map[T]int) string {
	parts := make([]string, 0, len(all))
	for key, value := range all {
		parts = append(parts, fmt.Sprintf("%v×%d", key, value))
	}
	sort.Strings(parts)
	return strings.Join(parts, " ")
}

func total(all map[string]int) int {
	sum := 0
	for _, value := range all {
		sum += value
	}
	return sum
}

func sorted(all map[string]string) []string {
	names := make([]string, 0, len(all))
	for name := range all {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}

func sortedStrings(all map[string]string) []string { return sorted(all) }

func sortedInt64(all map[string]int64) []string {
	names := make([]string, 0, len(all))
	for name := range all {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}
