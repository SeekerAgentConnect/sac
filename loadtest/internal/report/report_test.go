package report

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/loadtest/internal/drive"
	"github.com/BrRenat/SeekerAgentWallet/loadtest/internal/measure"
)

// The report (SEE-99).
//
// One rule is being checked, and it is the rule every misleading load report has broken: a number
// that was not measured is not printed as a number. A leg that did not run says so, a quantile with
// no observations behind it says "not measured", and a resource nothing could sample says so too.

func TestALegThatDidNotRunIsNeverAPass(t *testing.T) {
	report := New("SEE-99", "abc123")
	report.NotRun["two-nodes"] = "SEEKERVAULT_REDIS is not set"
	printed := summary(t, report)
	if !strings.Contains(printed, "NOT RUN two-nodes — SEEKERVAULT_REDIS is not set") {
		t.Fatalf("the summary does not say what was skipped:\n%s", printed)
	}
	if strings.Contains(printed, "PASS") {
		t.Fatalf("a report with nothing in it printed a pass:\n%s", printed)
	}
}

func TestAQuantileWithNothingBehindItSaysSo(t *testing.T) {
	report := New("SEE-99", "abc123")
	report.Results = []drive.Result{{
		Scenario: "steady",
		Note:     "the baseline",
		Profile:  drive.Profile{Name: "hot", Listeners: 1, PayloadBytes: 1024},
		Stages: []drive.Stage{{
			Listeners:  1,
			Publishers: 1,
			// Nothing was published, so nothing was timed.
			Counters: map[string]int64{},
			Usage:    []measure.Usage{{Name: "gateway", Resident: 1 << 20}},
		}},
		Convergence: drive.Convergence{Listeners: 1, Converged: 1, Documents: 3},
	}}
	printed := summary(t, report)
	if !strings.Contains(printed, "publish call  not measured") {
		t.Fatalf("an empty series printed a number:\n%s", printed)
	}
	if !strings.Contains(printed, "descriptors not measured") {
		t.Fatalf("an unmeasured descriptor count printed as none:\n%s", printed)
	}
	// One listener, one publisher: singular, because a report nobody proofread is a report nobody
	// trusts.
	if !strings.Contains(printed, "1 listener, 1 publisher") {
		t.Fatalf("the counts do not read as English:\n%s", printed)
	}
}

func TestAStageThatFailedItsHealthRuleSaysWhichOne(t *testing.T) {
	report := New("SEE-99", "abc123")
	report.Results = []drive.Result{{
		Scenario: "ramp",
		Profile:  drive.Profile{Name: "ramp", PayloadBytes: 1024},
		Stages: []drive.Stage{
			{Listeners: 250, Published: 10, Delivered: 2500, Expected: 2500, Healthy: true,
				Counters: map[string]int64{}},
			{Listeners: 500, Published: 10, Delivered: 100, Expected: 5000,
				Unhealthy: []string{"0.0200 of the expected deliveries arrived"},
				Counters:  map[string]int64{}},
		},
		Reached:     250,
		Stopped:     "0.0200 of the expected deliveries arrived",
		Convergence: drive.Convergence{Listeners: 500, Converged: 500, Documents: 8},
		Failures:    []string{"a stage lost documents"},
	}}
	printed := summary(t, report)
	for _, expected := range []string{
		"UNHEALTHY: 0.0200 of the expected deliveries arrived",
		"reached 250 listeners; the next stage stopped it",
		"FAIL",
		"· a stage lost documents",
	} {
		if !strings.Contains(printed, expected) {
			t.Errorf("the summary does not say %q:\n%s", expected, printed)
		}
	}
}

func TestTheJSONIsWhatTheDocumentIsWrittenFrom(t *testing.T) {
	report := New("SEE-99", "abc123")
	report.Versions["broker"] = "Centrifugo v6.9.6"
	report.Results = []drive.Result{{
		Scenario: "steady",
		Profile:  drive.Profile{Name: "hot", Measure: drive.Duration(15 * time.Second)},
		Stages: []drive.Stage{{
			Listeners: 200, Delivered: 4400, Expected: 4400,
			Delivery: measure.Quantiles{Count: 4400, P99: 7 * time.Millisecond},
			Counters: map[string]int64{"delivery.applied": 4400},
		}},
		Convergence: drive.Convergence{Listeners: 200, Converged: 200, Documents: 8},
	}}
	path := filepath.Join(t.TempDir(), "nested", "see-99.json")
	if err := report.Write(path); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var read Report
	if err := json.Unmarshal(raw, &read); err != nil {
		t.Fatal(err)
	}
	if read.Revision != "abc123" || read.Ticket != "SEE-99" {
		t.Fatalf("%+v", read)
	}
	if read.Machine.OS == "" || read.Machine.CPUs == 0 {
		t.Fatalf("the machine is not described: %+v", read.Machine)
	}
	if len(read.Results) != 1 || read.Results[0].Last().Delivered != 4400 {
		t.Fatalf("%+v", read.Results)
	}
	// Durations survive the round trip as the strings a profile is written in.
	if got := read.Results[0].Profile.Measure.Every(); got != 15*time.Second {
		t.Fatalf("the measured window came back as %s", got)
	}
}

func summary(t *testing.T, report *Report) string {
	t.Helper()
	built := &strings.Builder{}
	report.Summary(built)
	return built.String()
}
