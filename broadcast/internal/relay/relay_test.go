// What the relay sends, and what it refuses to send (SEE-92).
//
// A fake push endpoint rather than a real one, because what these tests are about is the message:
// the path, the bearer, the topic, and — the part that matters most — that there is nothing in it.
// A hint that grew a proposal ID would still be delivered, still be accepted by Firebase, and still
// look right in a log; the only thing that would notice is a test that reads the body.
package relay

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/dispatch"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/store"
)

const (
	serverA  = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
	serverB  = "7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d"
	channelA = "server/" + serverA
	channelB = "server/" + serverB
)

// A relay is the drainer's dispatcher, checked where it costs nothing: if that stops being true,
// this file stops compiling.
var _ dispatch.Dispatcher = (*Relay)(nil)

// hint is one message as the push endpoint received it.
type hint struct {
	path   string
	bearer string
	raw    string
	Topic  string            `json:"topic"`
	Data   map[string]string `json:"data"`
	Extra  map[string]any    `json:"-"`
	Andro  struct {
		CollapseKey string `json:"collapse_key"`
		Priority    string `json:"priority"`
		TTL         string `json:"ttl"`
	} `json:"android"`
}

// pusher is a push endpoint that records what it was sent and answers what it is told to.
type pusher struct {
	server *httptest.Server

	mutex    sync.Mutex
	got      []hint
	statuses []int
	status   int
}

func (p *pusher) all() []hint {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	return append([]hint(nil), p.got...)
}

func pushEndpoint(t *testing.T, change ...func(*pusher)) *pusher {
	t.Helper()
	answering := &pusher{status: http.StatusOK}
	for _, apply := range change {
		apply(answering)
	}
	answering.server = httptest.NewServer(http.HandlerFunc(
		func(writer http.ResponseWriter, request *http.Request) {
			answering.mutex.Lock()
			defer answering.mutex.Unlock()
			raw, _ := io.ReadAll(request.Body)
			var envelope struct {
				Message json.RawMessage `json:"message"`
			}
			_ = json.Unmarshal(raw, &envelope)
			one := hint{
				path:   request.URL.Path,
				bearer: request.Header.Get("Authorization"),
				raw:    string(raw),
			}
			_ = json.Unmarshal(envelope.Message, &one)
			_ = json.Unmarshal(envelope.Message, &one.Extra)
			answering.got = append(answering.got, one)
			status := answering.status
			if len(answering.statuses) > 0 {
				status = answering.statuses[min(len(answering.got)-1, len(answering.statuses)-1)]
			}
			writer.WriteHeader(status)
			_, _ = writer.Write([]byte(`{"name":"projects/seeker-broadcast-test/messages/1"}`))
		}))
	t.Cleanup(answering.server.Close)
	return answering
}

// relaying builds a relay against a fake push endpoint and a fake token endpoint, on a clock the
// test moves.
func relaying(
	t *testing.T,
	push *pusher,
	token *minter,
	at *clock,
	change ...func(*Options),
) *Relay {
	t.Helper()
	options := Options{
		Endpoint:    push.server.URL,
		Credentials: credentials(t, token.server.URL),
		Environment: Production,
		Rate:        testRate,
		Burst:       testBurst,
		Now:         at.now,
		Log:         slog.New(slog.NewTextHandler(io.Discard, nil)),
	}
	for _, apply := range change {
		apply(&options)
	}
	// The endpoints are plain HTTP on loopback, so the relay's own client reaches them and nothing
	// has to be injected: what a real deployment needs instead is the CA bundle in the image.
	built, err := New(options)
	if err != nil {
		t.Fatal(err)
	}
	return built
}

// What a test asks for unless it is the one about the quota.
const (
	testRate  = 100
	testBurst = 100
)

func delivery(channel string, kind store.NoticeKind) dispatch.Delivery {
	return dispatch.Delivery{
		Channel:    channel,
		Kind:       kind,
		ProposalID: "9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d",
		Revision:   7,
		Sequence:   12,
		Event:      []byte("the document, which is not sent"),
	}
}

// The hint carries two constant fields and nothing else. Not the proposal, not its revision, not
// the sequence, not the channel — which feed changed is the topic, and a topic is a routing field
// rather than payload, the same line SAW-056 drew for the private path.
func TestTheHintCarriesNothingButItsKindAndVersion(t *testing.T) {
	push := pushEndpoint(t)
	token := tokenEndpoint(t)
	at := &clock{at: time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)}
	hints := relaying(t, push, token, at)

	if err := hints.Dispatch(context.Background(), delivery(channelA, store.ProposalNotice)); err != nil {
		t.Fatal(err)
	}

	sent := push.all()
	if len(sent) != 1 {
		t.Fatalf("%d hints were sent", len(sent))
	}
	one := sent[0]
	if one.path != "/v1/projects/seeker-broadcast-test/messages:send" {
		t.Fatalf("the hint went to %q", one.path)
	}
	if one.bearer != "Bearer access-token-1" {
		t.Fatalf("the hint was sent with %q", one.bearer)
	}
	if one.Topic != "feed.production."+serverA {
		t.Fatalf("the hint went to topic %q", one.Topic)
	}
	if len(one.Data) != 2 || one.Data["kind"] != "feed_invalidation" || one.Data["version"] != "1" {
		t.Fatalf("the hint carries %v", one.Data)
	}
	if one.Andro.CollapseKey != CollapseKey || one.Andro.TTL != "300s" {
		t.Fatalf("the hint's delivery is %+v", one.Andro)
	}
	// One more time, as bytes: nothing about the document is anywhere in the message, however it
	// was nested or spelled.
	for _, forbidden := range []string{
		"9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d", // the proposal
		"server/",                              // the channel
		"revision", "sequence", "proposal",
		"the document, which is not sent",
	} {
		if strings.Contains(one.raw, forbidden) {
			t.Fatalf("the hint carries %q: %s", forbidden, one.raw)
		}
	}
	// And the message names only the fields this relay sets.
	var fields []string
	for name := range one.Extra {
		fields = append(fields, name)
	}
	slices.Sort(fields)
	if strings.Join(fields, ",") != "android,data,topic" {
		t.Fatalf("the message carries %v", fields)
	}
}

// A proposal is worth waking a device for, because it can expire while nobody is looking. A
// settings change is not, and saying so is Android's business rather than the app's: the payload is
// identical either way.
func TestOnlyAProposalIsWorthWakingADeviceFor(t *testing.T) {
	push := pushEndpoint(t)
	at := &clock{at: time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)}
	hints := relaying(t, push, tokenEndpoint(t), at)

	for _, kind := range []store.NoticeKind{store.ProposalNotice, store.ManifestNotice} {
		if err := hints.Dispatch(context.Background(), delivery(channelA, kind)); err != nil {
			t.Fatal(err)
		}
	}

	sent := push.all()
	if len(sent) != 2 {
		t.Fatalf("%d hints were sent", len(sent))
	}
	if sent[0].Andro.Priority != "HIGH" {
		t.Fatalf("a proposal was sent at %q", sent[0].Andro.Priority)
	}
	if sent[1].Andro.Priority != "NORMAL" {
		t.Fatalf("a settings change was sent at %q", sent[1].Andro.Priority)
	}
	// Both carry exactly the same payload: the priority is a delivery instruction, not a hint about
	// what changed.
	if sent[0].Data["kind"] != sent[1].Data["kind"] || len(sent[1].Data) != 2 {
		t.Fatalf("the two payloads differ: %v and %v", sent[0].Data, sent[1].Data)
	}
}

// The topic is derived from the channel the gateway itself wrote, and scoped to the deployment's
// environment. A publisher has no way to name one: there is no field for it anywhere, and this is
// the only function that produces one.
func TestTheTopicIsDerivedFromTheChannelAndScopedToTheEnvironment(t *testing.T) {
	push := pushEndpoint(t)
	at := &clock{at: time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)}
	production := relaying(t, push, tokenEndpoint(t), at)
	sandbox := relaying(t, push, tokenEndpoint(t), at, func(o *Options) { o.Environment = Sandbox })

	if got := production.Topic(channelA); got != "feed.production."+serverA {
		t.Fatalf("production names %q", got)
	}
	if got := sandbox.Topic(channelA); got != "feed.sandbox."+serverA {
		t.Fatalf("sandbox names %q", got)
	}
	// The same publisher in two deployments is two topics, which is the whole reason the
	// environment is in the name: a sandbox publication must not wake a production subscriber.
	if production.Topic(channelA) == sandbox.Topic(channelA) {
		t.Fatal("the two environments share a topic")
	}
	// Two publishers are never one topic.
	if production.Topic(channelA) == production.Topic(channelB) {
		t.Fatal("two publishers share a topic")
	}
	// And anything that is not one of this gateway's channels has no topic at all.
	for _, name := range []string{
		"", "server/", "server/not-a-uuid", "SERVER/" + serverA, serverA,
		"feed:server/" + serverA, "server/" + serverA + "/extra",
	} {
		if got := production.Topic(name); got != "" {
			t.Fatalf("%q was named %q", name, got)
		}
	}
}

// A notice whose channel is not a channel is not sent anywhere. It cannot come from a publication
// this gateway accepted; if it ever does, the relay says so in a log rather than inventing a topic.
func TestANoticeThatIsNotAChannelIsNotSent(t *testing.T) {
	push := pushEndpoint(t)
	at := &clock{at: time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)}
	hints := relaying(t, push, tokenEndpoint(t), at)

	if err := hints.Dispatch(context.Background(), delivery("not-a-channel", store.ProposalNotice)); err != nil {
		t.Fatal(err)
	}
	if sent := push.all(); len(sent) != 0 {
		t.Fatalf("%d hints were sent for a channel that is not one", len(sent))
	}
}

// The quota bounds how often one feed's subscribers are woken, and it is a bucket rather than a
// window: publishing three proposals at once is ordinary, and doing it every second is not.
//
// What is dropped is a wake-up, never a publication: the documents are all stored, the stream
// carried them, and the next hint wakes a phone that reads everything (sync/FeedSynchronization).
func TestTheQuotaBoundsHowOftenOneTopicIsWoken(t *testing.T) {
	push := pushEndpoint(t)
	at := &clock{at: time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)}
	hints := relaying(t, push, tokenEndpoint(t), at, func(o *Options) {
		o.Rate = 0.1 // one every ten seconds
		o.Burst = 2
	})

	for range 5 {
		if err := hints.Dispatch(context.Background(), delivery(channelA, store.ProposalNotice)); err != nil {
			t.Fatal(err)
		}
	}
	if sent := push.all(); len(sent) != 2 {
		t.Fatalf("the burst let %d hints through", len(sent))
	}

	// A second is not enough for another token; ten is.
	at.advance(time.Second)
	_ = hints.Dispatch(context.Background(), delivery(channelA, store.ProposalNotice))
	if sent := push.all(); len(sent) != 2 {
		t.Fatalf("a hint went out after one second (%d in total)", len(sent))
	}
	at.advance(10 * time.Second)
	_ = hints.Dispatch(context.Background(), delivery(channelA, store.ProposalNotice))
	if sent := push.all(); len(sent) != 3 {
		t.Fatalf("no hint went out after eleven seconds (%d in total)", len(sent))
	}

	// And one feed's quota is not another's: a busy publisher cannot silence a quiet one.
	_ = hints.Dispatch(context.Background(), delivery(channelB, store.ProposalNotice))
	sent := push.all()
	if len(sent) != 4 || sent[3].Topic != "feed.production."+serverB {
		t.Fatalf("the other publisher's hint was refused: %d sent", len(sent))
	}
}

// A failure is never reported to the drainer. A hint that could defer a notice would make a
// Firebase outage into the broker re-publishing every document it had already delivered, for as
// long as somebody else's service was down.
func TestAFailedHintIsNeverReportedToTheDrainer(t *testing.T) {
	push := pushEndpoint(t, func(p *pusher) { p.status = http.StatusInternalServerError })
	at := &clock{at: time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)}
	hints := relaying(t, push, tokenEndpoint(t), at)

	if err := hints.Dispatch(context.Background(), delivery(channelA, store.ProposalNotice)); err != nil {
		t.Fatalf("a failed hint was reported as a failed delivery: %v", err)
	}
	// The same is true when the endpoint is not there at all, and when no token can be had.
	unreachable := relaying(t, push, tokenEndpoint(t), at, func(o *Options) {
		o.Endpoint = "http://127.0.0.1:1"
	})
	if err := unreachable.Dispatch(context.Background(), delivery(channelA, store.ProposalNotice)); err != nil {
		t.Fatalf("an unreachable endpoint was reported as a failed delivery: %v", err)
	}
	refused := tokenEndpoint(t, func(m *minter) { m.status = http.StatusUnauthorized })
	unauthorized := relaying(t, push, refused, at)
	if err := unauthorized.Dispatch(context.Background(), delivery(channelA, store.ProposalNotice)); err != nil {
		t.Fatalf("a refused token was reported as a failed delivery: %v", err)
	}
}

// The one retry that is worth making: a cached token the endpoint no longer accepts. Anything else
// is somebody else's state, and the next publication is the retry.
func TestARefusedTokenIsReplacedOnceAndTheHintGoesOut(t *testing.T) {
	push := pushEndpoint(t, func(p *pusher) {
		p.statuses = []int{http.StatusUnauthorized, http.StatusOK}
	})
	token := tokenEndpoint(t, func(m *minter) {
		m.tokens = []string{"access-token-1", "access-token-2"}
	})
	at := &clock{at: time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)}
	hints := relaying(t, push, token, at)

	if err := hints.send(context.Background(), "feed.production."+serverA, store.ProposalNotice); err != nil {
		t.Fatalf("the retry did not deliver: %v", err)
	}
	sent := push.all()
	if len(sent) != 2 {
		t.Fatalf("%d attempts were made", len(sent))
	}
	if sent[0].bearer == sent[1].bearer {
		t.Fatalf("the same token was used twice: %q", sent[1].bearer)
	}
	if token.asked() != 2 {
		t.Fatalf("the token endpoint was asked %d times", token.asked())
	}

	// And it is one retry, not a loop: an endpoint that refuses every token is reported rather
	// than asked for ever.
	always := pushEndpoint(t, func(p *pusher) { p.status = http.StatusUnauthorized })
	stubborn := relaying(t, always, tokenEndpoint(t), at)
	err := stubborn.send(context.Background(), "feed.production."+serverA, store.ProposalNotice)
	if err == nil {
		t.Fatal("a permanently refused hint was reported as sent")
	}
	if len(always.all()) != 2 {
		t.Fatalf("%d attempts were made against an endpoint that refuses everything",
			len(always.all()))
	}
}

// Every failure says what happened and quotes nothing. The endpoint's own body can name a project
// or echo the request, and the bearer is a credential — neither belongs in a log an operator
// pastes into an issue.
func TestAFailureSaysWhatHappenedAndQuotesNothing(t *testing.T) {
	push := pushEndpoint(t, func(p *pusher) { p.status = http.StatusForbidden })
	token := tokenEndpoint(t)
	at := &clock{at: time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)}
	hints := relaying(t, push, token, at)

	err := hints.send(context.Background(), "feed.production."+serverA, store.ProposalNotice)
	if err == nil {
		t.Fatal("a refusal was accepted")
	}
	if !strings.Contains(err.Error(), "403") {
		t.Fatalf("the error does not say what happened: %v", err)
	}
	for _, forbidden := range []string{
		"access-token-1", "PRIVATE KEY", "projects/seeker-broadcast-test/messages",
	} {
		if strings.Contains(err.Error(), forbidden) {
			t.Fatalf("the error carries %q: %v", forbidden, err)
		}
	}
}

// A relay configured half way is refused, for the same reason the broker's client is: it would look
// like it works, and the first sign of it would be phones that never wake.
func TestAPartialConfigurationIsRefused(t *testing.T) {
	push := pushEndpoint(t)
	token := tokenEndpoint(t)
	complete := Options{
		Endpoint:    push.server.URL,
		Credentials: credentials(t, token.server.URL),
		Environment: Production,
		Rate:        1,
		Burst:       1,
	}
	if _, err := New(complete); err != nil {
		t.Fatalf("a complete configuration was refused: %v", err)
	}

	for name, break_ := range map[string]func(*Options){
		"no endpoint":    func(o *Options) { o.Endpoint = "" },
		"no environment": func(o *Options) { o.Environment = "" },
		"an environment nobody named": func(o *Options) {
			o.Environment = "staging"
		},
		"no rate":       func(o *Options) { o.Rate = 0 },
		"no burst":      func(o *Options) { o.Burst = 0 },
		"no credential": func(o *Options) { o.Credentials = Credentials{} },
		"a credential with no project": func(o *Options) {
			o.Credentials.ProjectID = ""
		},
	} {
		options := complete
		break_(&options)
		if _, err := New(options); err == nil {
			t.Fatalf("a relay with %s was accepted", name)
		}
	}
}

// The bound on what the quota remembers, and what happens at it: a topic whose bucket has refilled
// completely behaves exactly as a new one, so the full ones are dropped to make room. A gateway
// hosting more publishers than the map holds still relays for all of them.
func TestTheQuotaForgetsTheTopicsThatHaveRefilled(t *testing.T) {
	at := &clock{at: time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)}
	metered := &quota{rate: 1, burst: 1, now: at.now}

	// Fill it, using each bucket once so none of them is full.
	for i := range mostTopicsRemembered {
		if !metered.allow(topicNumber(i)) {
			t.Fatalf("the first hint for topic %d was refused", i)
		}
	}
	if len(metered.buckets) != mostTopicsRemembered {
		t.Fatalf("the quota holds %d buckets", len(metered.buckets))
	}
	// Nothing has refilled yet, so a new topic is let through unmetered rather than refused: a
	// dropped hint to protect a map would be protecting the wrong thing.
	if !metered.allow("feed.production.one-more") {
		t.Fatal("a hint was dropped to keep the map small")
	}
	// Once they have refilled, the room is made and the new topic is metered like any other.
	at.advance(time.Hour)
	if !metered.allow("feed.production.another") {
		t.Fatal("a refilled quota refused a new topic")
	}
	if len(metered.buckets) > mostTopicsRemembered {
		t.Fatalf("the quota grew to %d buckets", len(metered.buckets))
	}
}

func topicNumber(i int) string {
	return fmt.Sprintf("feed.production.topic-%d", i)
}
