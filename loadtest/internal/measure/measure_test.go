package measure

import (
	"fmt"
	"sync"
	"testing"
	"time"
)

// What the numbers in a report are made of (SEE-99).
//
// These are the checks that matter most for a load report's honesty: a quantile that is not the
// quantile, a gap that is not counted, or a peak that is really a mean would each turn a
// measurement into a claim.

func TestQuantilesAreTheObservationsThemselves(t *testing.T) {
	series := NewSeries(1)
	for value := 1; value <= 100; value++ {
		series.Add(time.Duration(value) * time.Millisecond)
	}
	got := series.Quantiles()
	for _, one := range []struct {
		what     string
		got      time.Duration
		expected time.Duration
	}{
		{"min", got.Min, time.Millisecond},
		{"p50", got.P50, 50 * time.Millisecond},
		{"p95", got.P95, 95 * time.Millisecond},
		{"p99", got.P99, 99 * time.Millisecond},
		{"max", got.Max, 100 * time.Millisecond},
		{"mean", got.Mean, 50*time.Millisecond + 500*time.Microsecond},
	} {
		if one.got != one.expected {
			t.Errorf("%s is %s, and it should be %s", one.what, one.got, one.expected)
		}
	}
	if got.Count != 100 {
		t.Errorf("it counted %d", got.Count)
	}
	if got.Sampled {
		t.Error("a hundred observations were kept, so nothing was sampled")
	}
	// Nearest rank, never interpolated: every quantile above is a duration that was observed.
	if got.P99 != 99*time.Millisecond {
		t.Errorf("p99 is %s, which nothing observed", got.P99)
	}
}

func TestAnEmptySeriesSaysNothingRatherThanZero(t *testing.T) {
	got := NewSeries(1).Quantiles()
	if got.Count != 0 || got.P50 != 0 || got.Max != 0 {
		t.Fatalf("%+v", got)
	}
}

func TestOneObservationIsEveryQuantile(t *testing.T) {
	series := NewSeries(1)
	series.Add(7 * time.Millisecond)
	got := series.Quantiles()
	if got.Min != got.Max || got.P50 != got.P99 || got.P50 != 7*time.Millisecond {
		t.Fatalf("%+v", got)
	}
}

func TestResetIsHowAWarmUpIsDiscarded(t *testing.T) {
	series := NewSeries(1)
	series.Add(time.Hour)
	series.Reset()
	series.Add(time.Millisecond)
	got := series.Quantiles()
	if got.Count != 1 || got.Max != time.Millisecond {
		t.Fatalf("the warm-up is still in it: %+v", got)
	}
}

func TestSeriesIsSafeToAddToFromEveryListenerAtOnce(t *testing.T) {
	series := NewSeries(1)
	waiting := sync.WaitGroup{}
	for worker := range 8 {
		waiting.Add(1)
		go func(worker int) {
			defer waiting.Done()
			for value := range 500 {
				series.Add(time.Duration(worker*500+value) * time.Microsecond)
			}
		}(worker)
	}
	waiting.Wait()
	if got := series.Count(); got != 4000 {
		t.Fatalf("it counted %d of 4000", got)
	}
}

func TestAChannelCountsWhatTheTransportDid(t *testing.T) {
	channel := &Channel{}
	for _, offset := range []uint64{1, 2, 3} {
		if !channel.Received(offset) {
			t.Fatalf("offset %d was not new", offset)
		}
	}
	// The same offset again: at-least-once delivery, which is the documented contract.
	if channel.Received(3) {
		t.Fatal("the same offset came back as new")
	}
	// One that arrived after a newer one.
	if channel.Received(2) {
		t.Fatal("an older offset came back as new")
	}
	// A skip: offsets 4 and 5 were never seen.
	if !channel.Received(6) {
		t.Fatal("offset 6 was not new")
	}
	gaps, missing, duplicates, backwards := channel.Gaps()
	if gaps != 1 || missing != 2 || duplicates != 1 || backwards != 1 {
		t.Fatalf("%d gaps, %d missing, %d duplicates, %d out of order",
			gaps, missing, duplicates, backwards)
	}
}

func TestAFirstOffsetIsNotAGap(t *testing.T) {
	// A listener joining a channel that is already at offset 900 has missed nothing it was
	// promised: it holds no cursor, so there is nothing for the offsets to be a gap in.
	channel := &Channel{}
	if !channel.Received(900) {
		t.Fatal("the first offset was not new")
	}
	if gaps, missing, _, _ := channel.Gaps(); gaps != 0 || missing != 0 {
		t.Fatalf("%d gaps and %d missing offsets on a first delivery", gaps, missing)
	}
}

func TestOpenedKeepsThePositionARecoveryEchoedBack(t *testing.T) {
	channel := &Channel{}
	channel.Received(5)
	// A successful recovery echoes the *requested* offset, so a listener that took its position
	// from the answer would go backwards. The cursor stays where the last applied document was.
	channel.Opened("e1", 3, true)
	if epoch, offset := channel.Cursor(); epoch != "e1" || offset != 5 {
		t.Fatalf("the cursor is %s/%d", epoch, offset)
	}
	// When nothing was recovered, the channel's current position is the honest place to resume
	// from: what was missed is read from the snapshot instead.
	channel.Opened("e2", 40, false)
	if epoch, offset := channel.Cursor(); epoch != "e2" || offset != 40 {
		t.Fatalf("the cursor is %s/%d", epoch, offset)
	}
	// And the jump from 5 to 40 is not counted as a gap, because it was not one.
	if gaps, _, _, _ := channel.Gaps(); gaps != 0 {
		t.Fatalf("%d gaps", gaps)
	}
}

func TestAChangedEpochResetsThePosition(t *testing.T) {
	// The scenario that found this stopped Redis under a two-node broker: the history was replaced,
	// the new one started again from a small offset, and a channel still holding the old one read
	// every publication that followed as older than what it had. Every listener then looked short
	// of a withdrawal, which reads exactly like a lost publication in the product.
	channel := &Channel{}
	for _, offset := range []uint64{48, 49, 50} {
		channel.Received(offset)
	}
	channel.Opened("e1", 50, false)
	channel.Opened("e2", 1, false)
	if epoch, offset := channel.Cursor(); epoch != "e2" || offset != 1 {
		t.Fatalf("the cursor is %s/%d, and the old history's offset means nothing in the new one",
			epoch, offset)
	}
	if !channel.Received(2) {
		t.Fatal("the first publication of the new history was read as older than the old one")
	}
	if channel.Epochs() != 1 {
		t.Errorf("%d epoch changes", channel.Epochs())
	}
	// And the reset is not a gap: nothing was missed in a history that did not exist yet.
	if gaps, _, _, backwards := channel.Gaps(); gaps != 0 || backwards != 0 {
		t.Errorf("%d gaps and %d out of order", gaps, backwards)
	}
}

func TestCountersAreAbsentRatherThanZero(t *testing.T) {
	counters := NewCounters()
	if _, known := counters.Get("publish.refused.too_many_requests"); known {
		t.Fatal("a counter nothing touched is known")
	}
	counters.Count("publish.accepted")
	counters.Add("publish.accepted", 2)
	if value, known := counters.Get("publish.accepted"); !known || value != 3 {
		t.Fatalf("%d, known %v", value, known)
	}
	if names := counters.Names(); len(names) != 1 || names[0] != "publish.accepted" {
		t.Fatalf("%v", names)
	}
	counters.Reset()
	if len(counters.All()) != 0 {
		t.Fatal("reset left something behind")
	}
}

func TestUsageKeepsThePeakAndTheProcessorTimeOfTheWindow(t *testing.T) {
	usage := &Usage{Name: "gateway"}
	usage.Observe(100, 2*time.Second)
	usage.Observe(300, 3*time.Second)
	usage.Observe(200, 5*time.Second)
	if usage.Resident != 300 {
		t.Errorf("the peak is %d", usage.Resident)
	}
	// Processor time is what was spent *during* the window, so the first sample is the baseline: a
	// process that had already run for an hour must not have the hour attributed to this run.
	if usage.CPU != 3*time.Second {
		t.Errorf("the processor time is %s", usage.CPU)
	}
	if usage.Samples != 3 {
		t.Errorf("%d samples", usage.Samples)
	}
}

func TestRateAndBytesReadTheWayAReportPrintsThem(t *testing.T) {
	if got := Rate(100, 2*time.Second); got != 50 {
		t.Errorf("%g", got)
	}
	if got := Rate(100, 0); got != 0 {
		t.Errorf("a rate over no time is %g", got)
	}
	for size, expected := range map[int64]string{
		512:           "512 B",
		1024:          "1.0 KiB",
		1536:          "1.5 KiB",
		1 << 20:       "1.0 MiB",
		3 * (1 << 30): "3.0 GiB",
	} {
		if got := Bytes(size); got != expected {
			t.Errorf("%d reads as %q, and it should be %q", size, got, expected)
		}
	}
}

func TestPastTheBoundTheQuantilesAreFromASampleAndSaySo(t *testing.T) {
	// The bound is two million, which is too many to add in a unit test; what is checked here is
	// the accounting rather than the number — the series says how many it saw, and says that what
	// it kept is a sample.
	series := NewSeries(2)
	kept := 0
	for value := range MostSamples + 1000 {
		series.Add(time.Duration(value))
		kept++
	}
	got := series.Quantiles()
	if got.Count != kept {
		t.Fatalf("it saw %d of %d", got.Count, kept)
	}
	if !got.Sampled {
		t.Fatal("more than the bound was added, and it does not say it sampled")
	}
	if got.Max == 0 {
		t.Fatal("nothing was kept")
	}
}

func ExampleBytes() {
	fmt.Println(Bytes(74 * 1024 * 1024))
	// Output: 74.0 MiB
}
