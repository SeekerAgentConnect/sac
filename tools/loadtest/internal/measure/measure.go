// Package measure is what a run counts and how it says so (SEE-99).
//
// # What a latency number here means
//
// Publish-to-receive, on one clock. The publisher and every listener are goroutines in one process,
// so the send time is taken with [time.Now] just before the publish call returns and the arrival
// time is taken with [time.Now] when the bytes are decoded — one machine, one monotonic clock, one
// subtraction. Nothing is compared across hosts and no clock synchronisation is assumed, which is
// the one measurement assumption a report of this kind has to state.
//
// What the number therefore *includes* is stated too, because it is not only the transport: the
// gateway's own commit, the outbox drainer's pass (internal/dispatch wakes on a publication and
// batches sixty-four notices), the broker's fan-out, and this process's own decode. It is the
// latency a phone would see minus the phone — which is the honest way to separate transport
// capacity from what a device and a mobile network add.
//
// # Why exact quantiles rather than a digest
//
// Because they fit. A run keeps one [Sample] per delivery, sorts them at the end, and reads the
// index: no approximation, no estimator to explain, and no argument about whether p99 is p99. The
// cost is memory, which is bounded on purpose ([Reservoir]) — past the bound a run keeps a uniform
// sample of the whole rather than the first million deliveries, and the report says so.
package measure

import (
	"fmt"
	"math"
	"math/rand/v2"
	"slices"
	"sort"
	"sync"
	"time"
)

// MostSamples is how many durations one series keeps before it starts sampling. Two million
// float64s is sixteen megabytes, which is nothing beside ten thousand listeners, and a run that
// delivers more than that has enough of a tail to describe without keeping all of it.
const MostSamples = 2_000_000

// Series is a bounded, thread-safe collection of durations.
type Series struct {
	mutex   sync.Mutex
	kept    []time.Duration
	seen    int
	sampled bool
	source  *rand.Rand
}

// NewSeries builds one. The seed makes a sampled series reproducible, like everything else a run
// does.
func NewSeries(seed uint64) *Series {
	return &Series{
		kept:   make([]time.Duration, 0, 1024),
		source: rand.New(rand.NewPCG(seed, 0x5eed)),
	}
}

// Add records one observation.
func (s *Series) Add(took time.Duration) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.seen++
	if len(s.kept) < MostSamples {
		s.kept = append(s.kept, took)
		return
	}
	// Reservoir sampling: every observation is equally likely to be one of the kept ones, so the
	// quantiles describe the whole run rather than its first minute.
	s.sampled = true
	if index := s.source.IntN(s.seen); index < len(s.kept) {
		s.kept[index] = took
	}
}

// Reset forgets everything, which is how a warm-up is discarded: the series a listener holds a
// pointer to is the series the measured window uses, so the window begins by emptying it rather
// than by swapping it under a thousand goroutines.
func (s *Series) Reset() {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.kept = s.kept[:0]
	s.seen = 0
	s.sampled = false
}

// Count is how many observations there were, sampled or not.
func (s *Series) Count() int {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	return s.seen
}

// Quantiles is the summary a report prints.
type Quantiles struct {
	Count int           `json:"count"`
	Min   time.Duration `json:"min"`
	P50   time.Duration `json:"p50"`
	P95   time.Duration `json:"p95"`
	P99   time.Duration `json:"p99"`
	Max   time.Duration `json:"max"`
	Mean  time.Duration `json:"mean"`
	// True when the quantiles are from a uniform sample rather than from every observation.
	Sampled bool `json:"sampled,omitempty"`
}

// Quantiles reads the series. It sorts a copy, so a run can ask more than once.
func (s *Series) Quantiles() Quantiles {
	s.mutex.Lock()
	kept := slices.Clone(s.kept)
	summary := Quantiles{Count: s.seen, Sampled: s.sampled}
	s.mutex.Unlock()
	if len(kept) == 0 {
		return summary
	}
	slices.Sort(kept)
	var total time.Duration
	for _, one := range kept {
		total += one
	}
	summary.Min = kept[0]
	summary.Max = kept[len(kept)-1]
	summary.Mean = total / time.Duration(len(kept))
	summary.P50 = quantile(kept, 0.50)
	summary.P95 = quantile(kept, 0.95)
	summary.P99 = quantile(kept, 0.99)
	return summary
}

// quantile is the nearest-rank one: the smallest kept value at or above the fraction. No
// interpolation, because an interpolated p99 is a number nothing observed.
func quantile(sorted []time.Duration, fraction float64) time.Duration {
	if len(sorted) == 0 {
		return 0
	}
	index := int(math.Ceil(fraction*float64(len(sorted)))) - 1
	return sorted[min(max(index, 0), len(sorted)-1)]
}

// Counters are the named things a run counts. A counter that was never incremented is absent from
// a report rather than zero: "no publication was refused" and "refusals were not counted" are
// different statements.
type Counters struct {
	mutex sync.Mutex
	held  map[string]int64
}

func NewCounters() *Counters { return &Counters{held: map[string]int64{}} }

// Add increments one.
func (c *Counters) Add(name string, by int64) {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	c.held[name] += by
}

// Count increments one by one.
func (c *Counters) Count(name string) { c.Add(name, 1) }

// Reset forgets every counter, for the same reason [Series.Reset] exists.
func (c *Counters) Reset() {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	c.held = map[string]int64{}
}

// Get is one counter, and whether it was ever touched.
func (c *Counters) Get(name string) (int64, bool) {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	value, known := c.held[name]
	return value, known
}

// All is every counter, sorted, for a report.
func (c *Counters) All() map[string]int64 {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	all := make(map[string]int64, len(c.held))
	for name, value := range c.held {
		all[name] = value
	}
	return all
}

// Names is every counter's name in order, so a printed summary is stable between runs.
func (c *Counters) Names() []string {
	all := c.All()
	names := make([]string, 0, len(all))
	for name := range all {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}

// Channel is what one listener saw on one channel: the offsets it received, in the order they
// arrived, reduced to the three questions a report asks about them.
//
// A gap is an offset the broker skipped from the listener's point of view, and it is **not** by
// itself a lost publication: a gap the broker proved it had replayed is recovery working, and a gap
// followed by a snapshot read is the documented fallback. What would be a lost publication is a gap
// with neither — so gaps are counted separately from the recoveries and snapshots that answer them,
// and the run's own invariant is the applied set, not the offset sequence.
type Channel struct {
	mutex sync.Mutex
	// The highest offset applied, which is what a cursor holds.
	offset uint64
	epoch  string
	// Offsets seen more than once. At-least-once delivery makes these ordinary (internal/dispatch),
	// and the phone's revision-ordered apply is what makes them harmless — so they are counted, not
	// complained about.
	duplicates int64
	// Offsets that arrived below the highest already applied: out-of-order delivery, which the
	// transport does not promise not to do.
	backwards int64
	// How many offsets were skipped, and how many times a skip happened.
	missing uint64
	gaps    int64
	// How many times the broker's history was replaced under this listener.
	epochs int64
}

// Received records one publication's offset and says whether it is new.
func (c *Channel) Received(offset uint64) bool {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	switch {
	case offset == c.offset:
		c.duplicates++
		return false
	case offset < c.offset:
		c.backwards++
		return false
	}
	if c.offset != 0 && offset > c.offset+1 {
		c.gaps++
		c.missing += offset - c.offset - 1
	}
	c.offset = offset
	return true
}

// Opened records what the connect answer said, and returns the epoch it replaced.
//
// **A changed epoch resets the position**, and that is not a detail. An offset is a place in one
// history; when the broker's history is replaced — Redis restarted, in the scenario that found this
// — the new one starts again from a small number, and a listener still holding an offset from the
// old one would read every publication that followed as "older than what I have" and apply none of
// them. The run that made that mistake reported two hundred listeners short of a withdrawal and
// looked like a lost publication in the product; it was the harness comparing two histories'
// numbers with each other. The phone does not make it: its cursor is the pair, and a changed epoch
// invalidates it (`feeds/FeedRecovery.kt`).
func (c *Channel) Opened(epoch string, offset uint64, recovered bool) string {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	was := c.epoch
	c.epoch = epoch
	switch {
	case was != "" && was != epoch:
		c.epochs++
		c.offset = offset
	case !recovered && offset > c.offset:
		// Nothing was replayed, so the listener's position is the channel's current one and what
		// it missed is read from the snapshot rather than counted as a gap.
		c.offset = offset
	}
	return was
}

// Cursor is what this channel would resume from.
func (c *Channel) Cursor() (string, uint64) {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	return c.epoch, c.offset
}

// Gaps is what the report says about continuity on this channel.
func (c *Channel) Gaps() (gaps int64, missing uint64, duplicates int64, backwards int64) {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	return c.gaps, c.missing, c.duplicates, c.backwards
}

// Epochs is how many times this channel's history was replaced under the listener.
func (c *Channel) Epochs() int64 {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	return c.epochs
}

// Usage is a process's resource use over a run: the highest resident memory seen, and the processor
// time between the first and last sample.
type Usage struct {
	Name     string        `json:"name"`
	Resident int64         `json:"resident_bytes_peak"`
	CPU      time.Duration `json:"cpu"`
	Samples  int           `json:"samples"`
	// Descriptors held at the end of the run, or zero when nothing counted them.
	Files int `json:"open_files,omitempty"`
	// True when the process went away during the run, which for a failover scenario is the point.
	Ended bool `json:"ended,omitempty"`

	first time.Duration
	seen  bool
}

// Observe folds one sample in.
func (u *Usage) Observe(resident int64, cpu time.Duration) {
	if !u.seen {
		u.first, u.seen = cpu, true
	}
	u.Resident = max(u.Resident, resident)
	if cpu >= u.first {
		u.CPU = cpu - u.first
	}
	u.Samples++
}

// Rate is a count over a duration, as a report prints one.
func Rate(count int64, over time.Duration) float64 {
	if over <= 0 {
		return 0
	}
	return float64(count) / over.Seconds()
}

// Bytes is a size a person can read.
func Bytes(size int64) string {
	switch {
	case size >= 1<<30:
		return fmt.Sprintf("%.1f GiB", float64(size)/(1<<30))
	case size >= 1<<20:
		return fmt.Sprintf("%.1f MiB", float64(size)/(1<<20))
	case size >= 1<<10:
		return fmt.Sprintf("%.1f KiB", float64(size)/(1<<10))
	default:
		return fmt.Sprintf("%d B", size)
	}
}
