package drive

import (
	"context"
	"fmt"
	"sort"
	"sync"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/loadtest/internal/deploy"
	"github.com/BrRenat/SeekerAgentWallet/loadtest/internal/measure"
)

// One run of one profile (SEE-99).
//
// The shape is: register the publishers, publish their manifests, bring up the listeners a stage
// asks for, let everything settle, empty the measurements, measure for as long as the profile says,
// let what is in flight arrive, and then judge the stage against the profile's own health rule. A
// ramp repeats that with more listeners each time and stops at the first stage that cannot hold it.
//
// Two things are deliberate about the ordering. The warm-up is not measured, because the first
// second of a thousand listeners connecting is a measurement of connecting. And the settle after the
// window is, because a publication issued in the last millisecond of a window has not arrived yet
// and counting it as undelivered would be an arithmetic mistake dressed up as a finding.

// Settle is how long after the publishers stop that deliveries are still counted. Two seconds is an
// order of magnitude more than any publish-to-receive this harness has measured on loopback, and it
// is stated in the report rather than left as a detail.
const Settle = 2 * time.Second

// Interrupt is something a scenario does in the middle of a measured window: a node drained, a
// process killed, a publisher let off the leash.
type Interrupt struct {
	// How far into the measured window.
	At time.Duration
	// What it is, for the report.
	What string
	Do   func(ctx context.Context, run *Run) error
}

// Run is one profile being driven.
type Run struct {
	Profile    Profile
	deployment *deploy.Deployment
	journal    *journal

	// Publish-to-receive; the publish call itself; a listener's recovery read, which is the load a
	// reconnect puts on the unary API; an ordinary reader's walk; and a ticket request.
	Delivery  *measure.Series
	Publishes *measure.Series
	Snapshots *measure.Series
	Reads     *measure.Series
	Tickets   *measure.Series
	Counters  *measure.Counters

	// What a scenario wants done mid-window, and what it wants noted.
	Interrupts []Interrupt
	// Listeners that deliberately stop reading, and for how long: the first `Stalling` of them,
	// once [Run.Stall] has been called. It is a signal rather than something they do on their first
	// connection, because a stall that happened during the warm-up would not be in the window that
	// is measured — which is exactly the mistake the first version of this made.
	Stalling  int
	StallFor  time.Duration
	stalling  chan struct{}
	stallOnce sync.Once

	mutex sync.Mutex
	// Publications accepted in the current window, per channel.
	published map[string]int64
	// How many listeners hold each channel.
	subscribers map[string]int
	// What happened during the window that a person should read.
	notes []string

	writers   []*Writer
	documents []*Synthetic
	// Where each publisher has got to. It is kept on the run rather than in the goroutine because
	// a publisher is one publisher for the whole run: a ramp stops its publishers at the end of
	// every window so the last publications can settle, and a goroutine that started its count
	// again would republish revision 1 of a proposal the gateway already holds — which it refuses,
	// with `revision_conflict`, which is what the first ramp measured instead of a second stage.
	progress  []*progress
	listeners []*listener
	// The context every listener runs under, so a later stage's listeners join the same one.
	listenerContext context.Context
	// Started by Measure and stopped when the run is done.
	stopPublishers context.CancelFunc
	publishing     sync.WaitGroup
	stopListeners  context.CancelFunc
	listening      sync.WaitGroup
	stopReaders    context.CancelFunc
	reading        sync.WaitGroup
}

// Start registers the publishers and publishes their manifests. Nothing is listening yet.
func Start(ctx context.Context, deployment *deploy.Deployment, profile Profile) (*Run, error) {
	run := &Run{
		Profile:     profile,
		deployment:  deployment,
		journal:     newJournal(),
		Delivery:    measure.NewSeries(1),
		Publishes:   measure.NewSeries(2),
		Snapshots:   measure.NewSeries(3),
		Reads:       measure.NewSeries(4),
		Tickets:     measure.NewSeries(5),
		Counters:    measure.NewCounters(),
		published:   map[string]int64{},
		subscribers: map[string]int{},
		stalling:    make(chan struct{}),
	}
	for index := range profile.Publishers {
		server := ID(fmt.Sprintf("%s/publisher/%d", profile.Name, index))
		credential, err := deployment.Register(server,
			fmt.Sprintf("%s publisher %d", profile.Name, index))
		if err != nil {
			return nil, err
		}
		documents := NewSynthetic(server, deployment.Origin,
			profile.PayloadBytes, uint64(index)+1)
		writer := NewWriter(deployment.Publish, server, credential)
		// A publisher's first act, and until it happens nobody can subscribe.
		if _, err := writer.Manifest(ctx,
			documents.Manifest(1, fmt.Sprintf("load test %d", index))); err != nil {
			return nil, fmt.Errorf("drive: publishing publisher %d's manifest: %w (%s)",
				index, err, Problem(err))
		}
		run.writers = append(run.writers, writer)
		run.documents = append(run.documents, documents)
		// Every proposal of this publisher's is created at the same instant, because the rules say
		// a proposal's creation time cannot move: a publication that changes it is describing a
		// different proposal under the same identity (rules.AdvanceProposal).
		run.progress = append(run.progress, &progress{created: time.Now(), settings: 1})
	}
	return run, nil
}

// Deployment is what this run is driving, for a scenario that has to reach into it.
func (r *Run) Deployment() *deploy.Deployment { return r.deployment }

// Writers are the publishers, for a scenario that needs one of its own.
func (r *Run) Writers() []*Writer { return r.writers }

// Documents are the document makers, one per publisher.
func (r *Run) Documents() []*Synthetic { return r.documents }

// Stall tells the listeners that are meant to stop reading to stop reading. It is what the slow
// scenario's interrupt calls, so the stall is inside the measured window.
func (r *Run) Stall() {
	r.stallOnce.Do(func() { close(r.stalling) })
}

// stalling says whether the stall has been called for.
func (r *Run) stalled() bool {
	select {
	case <-r.stalling:
		return true
	default:
		return false
	}
}

// Note records something a person should read in the report next to the numbers.
func (r *Run) Note(what string, argument ...any) {
	r.mutex.Lock()
	defer r.mutex.Unlock()
	r.notes = append(r.notes, fmt.Sprintf(what, argument...))
}

// Listeners brings the run up to `count` phones, keeping the ones already listening.
//
// Channels are handed out round-robin, so a profile with one publisher is every phone on one
// channel and a profile with many divides them evenly. Streams are handed out round-robin too, over
// however many broker nodes are running, which is what something in front of two of them would do.
func (r *Run) Listeners(ctx context.Context, count int) {
	if count <= len(r.listeners) {
		return
	}
	if r.stopListeners == nil {
		listening, stop := context.WithCancel(ctx)
		r.listenerContext, r.stopListeners = listening, stop
	}
	ctx = r.listenerContext
	for index := len(r.listeners); index < count; index++ {
		channels := make([]string, 0, r.Profile.ChannelsPerListener)
		for held := range r.Profile.ChannelsPerListener {
			channel := r.documents[(index+held)%len(r.documents)].Channel()
			channels = append(channels, channel)
		}
		stream := ""
		if nodes := r.deployment.Nodes; len(nodes) > 0 {
			stream = nodes[index%len(nodes)].Stream
		}
		one := r.newListener(index, channels, stream)
		if index < r.Stalling {
			one.stalls, one.stallFor = true, r.StallFor
		}
		r.mutex.Lock()
		for _, channel := range channels {
			r.subscribers[channel]++
		}
		r.listeners = append(r.listeners, one)
		r.mutex.Unlock()
		r.listening.Add(1)
		go func() {
			defer r.listening.Done()
			one.listen(ctx)
		}()
	}
}

// Publish starts the publishers. They run until the window ends, which is where they are stopped
// so that the last publications have somewhere to arrive ([Run.Measure]); the next window starts
// another set.
func (r *Run) Publish(ctx context.Context) {
	if r.stopPublishers != nil {
		return
	}
	publishing, stop := context.WithCancel(ctx)
	r.stopPublishers = stop
	for index, writer := range r.writers {
		r.publishing.Add(1)
		go func(index int, writer *Writer) {
			defer r.publishing.Done()
			r.publisher(publishing, index, writer)
		}(index, writer)
	}
}

// Read starts the snapshot readers, once, for the whole run.
//
// Once, and the reason is a bug worth keeping written down: starting them alongside the publishers
// meant a ramp started another set at every stage and overwrote the cancel for the previous one, so
// the readers of every earlier stage kept walking pages nobody was measuring — and the run's own
// Stop waited for goroutines whose context nothing would ever cancel. A ramp that should have taken
// two minutes hung instead.
func (r *Run) Read(ctx context.Context) {
	if r.stopReaders != nil || r.Profile.Readers == 0 {
		return
	}
	reading, stop := context.WithCancel(ctx)
	r.stopReaders = stop
	for index := range r.Profile.Readers {
		r.reading.Add(1)
		go func(index int) {
			defer r.reading.Done()
			r.walker(reading, index)
		}(index)
	}
}

// Stop ends everything, publishers first so nothing new is published while the last deliveries
// arrive.
func (r *Run) Stop() {
	if r.stopPublishers != nil {
		r.stopPublishers()
		r.publishing.Wait()
	}
	if r.stopReaders != nil {
		r.stopReaders()
		r.reading.Wait()
	}
	if r.stopListeners != nil {
		r.stopListeners()
		r.listening.Wait()
	}
}

// progress is how far one publisher has got, across every window of the run.
type progress struct {
	tick     int
	created  time.Time
	settings uint64
}

// publisher is one synthetic publisher: a proposal identity per tick, a revision per pass over
// them, a withdrawal at the end of each identity's life, and a manifest bump on the profile's own
// interval.
func (r *Run) publisher(ctx context.Context, index int, writer *Writer) {
	documents := r.documents[index]
	state := r.progress[index]
	ticker := time.NewTicker(r.Profile.PublishEvery.Every())
	defer ticker.Stop()
	created := state.created
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
		tick := state.tick
		state.tick++
		// One identity per tick, in turn, so a pass over them is `proposals` ticks. What happens
		// on the next pass is the one workload decision here, and both answers are things a real
		// feed does:
		//
		//   - Without `cancel`, the same proposals are revised, for ever. A copy-trading
		//     proposal's terms really do move, and this is what keeps a long run — a ramp's
		//     hundred seconds — publishing all the way through without filling the channel.
		//   - With it, each generation of identities is published `revisions` times and then
		//     withdrawn, and the next generation is new identities. That is the lifecycle, and it
		//     accumulates documents: a channel holds at most `maxProposals` of them (200 by
		//     default), so a profile that withdraws is a profile with a bounded window.
		which := tick % r.Profile.Proposals
		pass := tick / r.Profile.Proposals
		channel := documents.Channel()
		if !r.Profile.Cancel {
			revision := uint64(pass) + 1
			id := ID(fmt.Sprintf("%s/%d/%d", channel, index, which))
			proposal := documents.Proposal(id, revision, created, time.Hour)
			r.timed(ctx, channel, "proposal", id, revision, func() error {
				_, status, err := writer.Publish(ctx, proposal)
				if err == nil && status.String() == "PUBLISH_STATUS_UNCHANGED" {
					r.Counters.Count("publish.unchanged")
				}
				return err
			})
		} else {
			generation := pass / (r.Profile.Revisions + 1)
			step := pass % (r.Profile.Revisions + 1)
			id := ID(fmt.Sprintf("%s/%d/%d/%d", channel, index, generation, which))
			if step < r.Profile.Revisions {
				revision := uint64(step) + 1
				proposal := documents.Proposal(id, revision, created, time.Hour)
				r.timed(ctx, channel, "proposal", id, revision, func() error {
					_, status, err := writer.Publish(ctx, proposal)
					if err == nil && status.String() == "PUBLISH_STATUS_UNCHANGED" {
						r.Counters.Count("publish.unchanged")
					}
					return err
				})
			} else {
				// A withdrawal is final, at a revision above the last one published
				// (rules.Cancelled), and it is the last thing this identity ever hears.
				revision := uint64(r.Profile.Revisions) + 1
				r.timed(ctx, channel, "proposal", id, revision, func() error {
					_, _, err := writer.Cancel(ctx, id, revision)
					return err
				})
			}
		}
		if r.Profile.ManifestEvery > 0 && tick > 0 && tick%r.Profile.ManifestEvery == 0 {
			state.settings++
			manifest := documents.Manifest(state.settings, fmt.Sprintf("load test %d rev %d",
				index, state.settings))
			r.timed(ctx, documents.Channel(), "manifest", "", state.settings, func() error {
				_, err := writer.Manifest(ctx, manifest)
				return err
			})
		}
	}
}

// timed publishes one document, records when it was issued, and counts what the gateway said.
//
// The send time is taken before the call rather than after it, because publish-to-receive is a
// question about what a publisher waits for: the commit is inside this call, and so is the outbox
// wake-up it triggers.
func (r *Run) timed(
	ctx context.Context, channel, kind, id string, revision uint64, send func() error,
) {
	if ctx.Err() != nil {
		return
	}
	key := identity(channel, kind, id, revision)
	issued := time.Now()
	r.journal.sent(key, issued)
	err := send()
	took := time.Since(issued)
	switch {
	case err == nil:
		r.Publishes.Add(took)
		r.Counters.Count("publish.accepted")
		r.mutex.Lock()
		r.published[channel]++
		r.mutex.Unlock()
	case ctx.Err() != nil:
		// The run ended mid-call. Not a refusal and not a failure.
		r.Counters.Count("publish.abandoned")
	default:
		if problem := Problem(err); problem != "" {
			r.Counters.Add("publish.refused."+problem, 1)
		} else {
			r.Counters.Count("publish.failed")
		}
	}
}

// walker is a snapshot reader: a phone that is up to date asking whether anything moved, and
// walking the pages when it has.
//
// It is the unary half of the contract under load, and it is deliberately using the sequence it
// already holds: the answer to "nothing has changed" is one round trip, which is the behaviour the
// protocol was designed for and therefore the one a report should measure.
func (r *Run) walker(ctx context.Context, index int) {
	channel := r.documents[index%len(r.documents)].Channel()
	reader := NewReader(r.deployment.Origin, fmt.Sprintf("198.19.%d.%d",
		(index/254)%256, index%254+1))
	ticker := time.NewTicker(r.Profile.ReadEvery.Every())
	defer ticker.Stop()
	held := uint64(0)
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
		started := time.Now()
		page, err := reader.Proposals(ctx, channel, uint32(r.Profile.PageSize), "", held)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			if problem := Problem(err); problem != "" {
				r.Counters.Add("read.refused."+problem, 1)
			} else {
				r.Counters.Count("read.failed")
			}
			continue
		}
		if page.Unchanged {
			r.Counters.Count("read.unchanged")
			r.Reads.Add(time.Since(started))
			continue
		}
		pages := 1
		token := page.NextToken
		for token != "" {
			next, err := reader.Proposals(ctx, channel, uint32(r.Profile.PageSize), token, 0)
			if err != nil {
				if ctx.Err() == nil {
					r.Counters.Count("read.walk.failed")
				}
				break
			}
			pages++
			token = next.NextToken
		}
		held = page.Sequence
		r.Reads.Add(time.Since(started))
		r.Counters.Count("read.walked")
		r.Counters.Add("read.pages", int64(pages))
	}
}

// Stage is one measured window.
type Stage struct {
	Listeners  int      `json:"listeners"`
	Publishers int      `json:"publishers"`
	Window     Duration `json:"window"`
	Settle     Duration `json:"settle"`
	Published  int64    `json:"published"`
	Expected   int64    `json:"expected_deliveries"`
	// Publications that reached a listener, whatever its apply rule then did with them.
	Delivered int64 `json:"delivered"`
	// The ones it kept, and the ones the rule ignored because it already held that revision.
	Applied  int64             `json:"applied"`
	Ignored  int64             `json:"ignored"`
	Delivery measure.Quantiles `json:"delivery"`
	Publish  measure.Quantiles `json:"publish_call"`
	// A listener's recovery read, and an ordinary reader's walk. They are separate because they
	// are different questions: one is what a reconnect costs the unary API, the other is what a
	// phone that is up to date pays to stay that way.
	Snapshot  measure.Quantiles `json:"recovery_read"`
	Read      measure.Quantiles `json:"reader_walk"`
	Ticket    measure.Quantiles `json:"ticket"`
	Counters  map[string]int64  `json:"counters"`
	Nodes     []deploy.NodeInfo `json:"broker_nodes,omitempty"`
	Usage     []measure.Usage   `json:"usage"`
	Notes     []string          `json:"notes,omitempty"`
	Healthy   bool              `json:"healthy"`
	Unhealthy []string          `json:"unhealthy,omitempty"`
}

// Delivered is the share of expected deliveries that arrived, as a fraction.
func (s Stage) Share() float64 {
	if s.Expected <= 0 {
		return 0
	}
	return float64(s.Delivered) / float64(s.Expected)
}

// Measure runs one window: warm up, empty the measurements, measure, settle, and judge.
func (r *Run) Measure(ctx context.Context, listeners int) (Stage, error) {
	r.Listeners(ctx, listeners)
	r.Publish(ctx)
	r.Read(ctx)
	if warmUp := r.Profile.WarmUp.Every(); warmUp > 0 {
		if !sleepFor(ctx, warmUp) {
			return Stage{}, ctx.Err()
		}
	}

	r.mutex.Lock()
	r.published = map[string]int64{}
	r.notes = nil
	r.mutex.Unlock()
	r.Delivery.Reset()
	r.Publishes.Reset()
	r.Snapshots.Reset()
	r.Reads.Reset()
	r.Tickets.Reset()
	r.Counters.Reset()

	// The broker's counters are cumulative for the life of the process, so the window's own numbers
	// are a difference. Without this a fifteen-second window would report the warm-up as well, and
	// a ramp's later stages would report every earlier one.
	before, _ := r.deployment.Info(ctx)

	window := r.Profile.Measure.Every()
	sampling, stopSampling := context.WithCancel(ctx)
	sampled := r.sample(sampling)
	interrupts := r.interrupt(ctx, window)
	if !sleepFor(ctx, window) {
		stopSampling()
		return Stage{}, ctx.Err()
	}
	<-interrupts

	// The publishers stop and the listeners keep going, so the last publications have somewhere to
	// arrive. A run that counted them as undelivered would be reporting its own arithmetic.
	if r.stopPublishers != nil {
		r.stopPublishers()
		r.publishing.Wait()
		r.stopPublishers = nil
	}
	sleepFor(ctx, Settle)
	stopSampling()
	usage := <-sampled

	r.mutex.Lock()
	published := int64(0)
	expected := int64(0)
	for channel, count := range r.published {
		published += count
		expected += count * int64(r.subscribers[channel])
	}
	notes := append([]string(nil), r.notes...)
	r.mutex.Unlock()

	counters := r.Counters.All()
	stage := Stage{
		Listeners:  len(r.listeners),
		Publishers: len(r.writers),
		Window:     Duration(window),
		Settle:     Duration(Settle),
		Published:  published,
		Expected:   expected,
		Delivered:  counters["delivery.arrived"],
		Applied:    counters["delivery.applied"],
		Ignored:    counters["delivery.ignored"],
		Delivery:   r.Delivery.Quantiles(),
		Publish:    r.Publishes.Quantiles(),
		Snapshot:   r.Snapshots.Quantiles(),
		Read:       r.Reads.Quantiles(),
		Ticket:     r.Tickets.Quantiles(),
		Counters:   counters,
		Usage:      usage,
		Notes:      notes,
	}
	if nodes, err := r.deployment.Info(ctx); err == nil {
		stage.Nodes = since(before, nodes)
	}
	stage.Healthy, stage.Unhealthy = r.healthy(stage)
	return stage, nil
}

// since turns the broker's cumulative counters into this window's own, and leaves the gauges alone:
// how many clients a node is holding is a fact about now, and how many publications it fanned out is
// a fact about a period.
func since(before, after []deploy.NodeInfo) []deploy.NodeInfo {
	baseline := map[string]deploy.NodeInfo{}
	for _, node := range before {
		baseline[node.Name] = node
	}
	windowed := make([]deploy.NodeInfo, 0, len(after))
	for _, node := range after {
		if was, known := baseline[node.Name]; known {
			// A node that was restarted mid-window has counters that went backwards. Its own
			// numbers from the restart are the honest answer there, so the baseline is dropped
			// rather than subtracted into a negative.
			if node.Accepted >= was.Accepted {
				node.Accepted -= was.Accepted
			}
			if node.FannedOut >= was.FannedOut {
				node.FannedOut -= was.FannedOut
			}
			if node.Dropped >= was.Dropped {
				node.Dropped -= was.Dropped
			}
		}
		windowed = append(windowed, node)
	}
	return windowed
}

// healthy judges a stage against the profile's own rules, and says which one it failed.
func (r *Run) healthy(stage Stage) (bool, []string) {
	var failed []string
	health := r.Profile.Health
	if most := health.MostP99.Every(); most > 0 && stage.Delivery.P99 > most {
		failed = append(failed, fmt.Sprintf("publish-to-receive p99 was %s, over the %s this "+
			"profile allows", stage.Delivery.P99.Round(time.Millisecond), most))
	}
	if least := health.LeastDelivered; least > 0 {
		if share := stage.Share(); share < least {
			failed = append(failed, fmt.Sprintf(
				"%.4f of the expected deliveries arrived (%d of %d), under the %.4f this "+
					"profile allows", share, stage.Delivered, stage.Expected, least))
		}
	}
	if most := health.MostLost; most > 0 && len(r.listeners) > 0 {
		lost := 0
		for _, one := range r.listeners {
			if _, _, stopped := one.continuity(); stopped {
				lost++
			}
		}
		if share := float64(lost) / float64(len(r.listeners)); share > most {
			failed = append(failed, fmt.Sprintf(
				"%d of %d listeners stopped for good, over the %.4f this profile allows",
				lost, len(r.listeners), most))
		}
	}
	return len(failed) == 0, failed
}

// interrupt schedules what a scenario asked for, and closes the channel when the last one has run.
func (r *Run) interrupt(ctx context.Context, window time.Duration) <-chan struct{} {
	done := make(chan struct{})
	go func() {
		defer close(done)
		elapsed := time.Duration(0)
		for _, interrupt := range r.Interrupts {
			at := min(interrupt.At, window)
			if !sleepFor(ctx, at-elapsed) {
				return
			}
			elapsed = at
			started := time.Now()
			if err := interrupt.Do(ctx, r); err != nil {
				r.Note("%s failed: %v", interrupt.What, err)
				r.Counters.Count("interrupt.failed")
				continue
			}
			r.Note("%s, %s into the window (it took %s)", interrupt.What,
				at.Round(time.Millisecond), time.Since(started).Round(time.Millisecond))
			r.Counters.Count("interrupt.done")
		}
	}()
	return done
}

// sample watches what each process is costing, and answers with the summary when it is stopped.
func (r *Run) sample(ctx context.Context) <-chan []measure.Usage {
	const every = 2 * time.Second
	answer := make(chan []measure.Usage, 1)
	watched := map[string]*deploy.Process{"gateway": r.deployment.Gateway}
	for index, node := range r.deployment.Nodes {
		watched[fmt.Sprintf("broker %d", index+1)] = node.Process
	}
	if r.deployment.Redis != nil {
		watched["redis"] = r.deployment.Redis
	}
	go func() {
		usage := map[string]*measure.Usage{}
		for name := range watched {
			usage[name] = &measure.Usage{Name: name}
		}
		ticker := time.NewTicker(every)
		defer ticker.Stop()
		for {
			for name, process := range watched {
				if !process.Running() {
					usage[name].Ended = true
					continue
				}
				if sampled, ok := deploy.Sample(process.PID()); ok {
					usage[name].Observe(sampled.Resident, sampled.CPU)
				}
			}
			select {
			case <-ctx.Done():
				// One last look at the descriptors, which is the connection count and is far too
				// slow to ask for on every sample.
				summary := make([]measure.Usage, 0, len(usage))
				for name, process := range watched {
					usage[name].Files = deploy.OpenFiles(process.PID())
					summary = append(summary, *usage[name])
					_ = name
				}
				sort.Slice(summary, func(a, b int) bool { return summary[a].Name < summary[b].Name })
				answer <- summary
				return
			case <-ticker.C:
			}
		}
	}()
	return answer
}

// Convergence is what every listener ended up holding, against what the gateway holds.
//
// This is the run's own invariant and the answer to SEE-99's "killing a node does not lose
// committed shared state; clients converge without duplicate local execution": the gateway is the
// authority, every listener's applied set is compared with it, and a listener short of a document
// is named. Duplicates are counted rather than treated as failures, because at-least-once delivery
// is the documented contract (internal/dispatch) and the apply rule is what makes it harmless.
type Convergence struct {
	Listeners int `json:"listeners"`
	// Listeners holding every document the gateway holds on every channel they were granted.
	Converged int `json:"converged"`
	// The ones that are not, with what they are missing, and which listeners they were — a
	// scenario that expects *some* listener to be behind (the slow one does) has to be able to say
	// which.
	Short          []string `json:"short,omitempty"`
	ShortListeners []int    `json:"short_listeners,omitempty"`
	// Listeners that stopped for good, and the codes they stopped on.
	Stopped int            `json:"stopped"`
	Closes  map[string]int `json:"closes,omitempty"`
	// Why continuity had to be read from a snapshot, across every listener.
	Fallbacks map[string]int `json:"fallbacks,omitempty"`
	// What the transport did: offsets skipped, offsets repeated, offsets that arrived late.
	Gaps       int64 `json:"gaps"`
	Missing    uint64
	Duplicates int64 `json:"duplicate_offsets"`
	Backwards  int64 `json:"out_of_order_offsets"`
	// How many times the broker's history was replaced under a listener, which is what a restarted
	// recovery cache looks like from the outside.
	Epochs int64 `json:"history_replaced"`
	// The authoritative state every listener was compared with.
	Documents int `json:"documents_held_by_the_gateway"`
}

// Converge waits for the listeners to catch up and then compares them with the gateway.
func (r *Run) Converge(ctx context.Context, within time.Duration) (Convergence, error) {
	authority := map[string]map[string]uint64{}
	reader := NewReader(r.deployment.Origin, "198.18.255.254")
	documents := 0
	for _, maker := range r.documents {
		held := map[string]uint64{}
		token := ""
		for {
			page, err := reader.Proposals(ctx, maker.Channel(),
				uint32(r.Profile.PageSize), token, 0)
			if err != nil {
				return Convergence{}, fmt.Errorf(
					"drive: reading the authoritative state of %s: %w (%s)",
					maker.Channel(), err, Problem(err))
			}
			for _, proposal := range page.Proposals {
				held[proposal.GetProposalId()] = proposal.GetRevision()
			}
			token = page.NextToken
			if token == "" {
				break
			}
		}
		authority[maker.Channel()] = held
		documents += len(held)
	}

	// The listeners have the settle window plus this to catch up. A listener whose continuity was
	// broken reads the snapshot itself, which is the phone's own path, so what is being waited for
	// is that path finishing rather than a delivery arriving twice.
	deadline := time.Now().Add(within)
	var short []string
	var indexes []int
	for {
		short, indexes = r.short(authority)
		if len(short) == 0 || time.Now().After(deadline) {
			break
		}
		if !sleepFor(ctx, 250*time.Millisecond) {
			break
		}
	}

	convergence := Convergence{
		Listeners:      len(r.listeners),
		Converged:      len(r.listeners) - len(short),
		Short:          short,
		ShortListeners: indexes,
		Documents:      documents,
		Closes:         map[string]int{},
		Fallbacks:      map[string]int{},
	}
	for _, one := range r.listeners {
		fallbacks, closes, stopped := one.continuity()
		if stopped {
			convergence.Stopped++
		}
		for why, count := range fallbacks {
			convergence.Fallbacks[why.String()] += count
		}
		for code, count := range closes {
			convergence.Closes[fmt.Sprintf("%d", code)] += count
		}
		gaps, missing, duplicates, backwards, epochs := one.gaps()
		convergence.Gaps += gaps
		convergence.Missing += missing
		convergence.Duplicates += duplicates
		convergence.Backwards += backwards
		convergence.Epochs += epochs
	}
	return convergence, nil
}

// short is every listener missing something the gateway holds on a channel it was granted.
func (r *Run) short(authority map[string]map[string]uint64) ([]string, []int) {
	var short []string
	var indexes []int
	for _, one := range r.listeners {
		holdings := one.holdings()
		var missing []string
		for _, channel := range one.channels {
			held := holdings[channel]
			for id, revision := range authority[channel] {
				switch mine, known := held[id]; {
				case !known:
					missing = append(missing, fmt.Sprintf("%s has nothing for %s", channel, id))
				case mine < revision:
					missing = append(missing, fmt.Sprintf("%s holds %s at %d, not %d",
						channel, id, mine, revision))
				}
			}
		}
		if len(missing) > 0 {
			sort.Strings(missing)
			short = append(short, fmt.Sprintf("listener %d: %s", one.index, missing[0]))
			indexes = append(indexes, one.index)
		}
	}
	sort.Strings(short)
	sort.Ints(indexes)
	return short, indexes
}
