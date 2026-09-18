package deploy

import (
	"testing"
	"time"
)

// What the broker and the operating system are asked, and how their answers are read (SEE-99).

const exposition = `
# HELP centrifugo_node_num_clients Number of clients.
# TYPE centrifugo_node_num_clients gauge
centrifugo_node_num_clients 20
centrifugo_node_num_subscriptions 20
centrifugo_node_num_nodes 2
centrifugo_node_messages_sent_count{channel_namespace="",type="control"} 4
centrifugo_node_messages_sent_count{channel_namespace="",type="publication"} 601
centrifugo_node_messages_received_count{type="publication",channel_namespace=""} 601
centrifugo_node_messages_received_count{channel_namespace="feed",type="publication"} 7
centrifugo_broker_redis_pub_sub_dropped_messages{channel_type="client"} 3
centrifugo_client_command_duration_seconds{method="connect",quantile="0.99"} 0.001
not a metric line
`

func TestTheMetricsAreReadWhateverOrderTheLabelsAreIn(t *testing.T) {
	metrics := parse(exposition)
	if got := metrics["centrifugo_node_num_clients"]; got != 20 {
		t.Errorf("clients: %g", got)
	}
	// The two label orders are one key, because the order they are written in is not part of the
	// format — and a harness that matched on the written form would read zero the day it changed.
	one := metrics[`centrifugo_node_messages_received_count{channel_namespace="",type="publication"}`]
	if one != 601 {
		t.Errorf("received: %g", one)
	}
	if _, present := metrics["not a metric line"]; present {
		t.Error("a line that is not a metric became one")
	}
	if _, present := metrics["# HELP centrifugo_node_num_clients Number of clients."]; present {
		t.Error("a comment became a metric")
	}
}

func TestSumAddsEverySeriesWithALabel(t *testing.T) {
	metrics := parse(exposition)
	// Two namespaces, one type: a node's publication count is the sum, and a report that read one
	// of them would understate the fan-out the day namespace labels are turned on.
	if got := sum(metrics, "centrifugo_node_messages_received_count", `type="publication"`); got != 608 {
		t.Errorf("%g", got)
	}
	if got := sum(metrics, "centrifugo_node_messages_sent_count", `type="publication"`); got != 601 {
		t.Errorf("%g", got)
	}
	if got := sum(metrics, "centrifugo_broker_redis_pub_sub_dropped_messages",
		`channel_type="client"`); got != 3 {
		t.Errorf("%g", got)
	}
	// A metric that is not there is zero, and a label that matches nothing is zero: both are
	// "nothing was reported", which is what a node that has not done anything yet looks like.
	if got := sum(metrics, "centrifugo_node_messages_sent_count", `type="join"`); got != 0 {
		t.Errorf("%g", got)
	}
	if got := sum(metrics, "centrifugo_nothing", `type="x"`); got != 0 {
		t.Errorf("%g", got)
	}
}

func TestProcessorTimeIsReadTheWayPsWritesIt(t *testing.T) {
	for text, expected := range map[string]time.Duration{
		"0:00.05":    50 * time.Millisecond,
		"1:02.50":    62*time.Second + 500*time.Millisecond,
		"10:00":      10 * time.Minute,
		"1:00:00":    time.Hour,
		"2-01:00:00": 49 * time.Hour,
		"nonsense":   0,
	} {
		if got := cpuTime(text); got != expected {
			t.Errorf("%q reads as %s, and it should be %s", text, got, expected)
		}
	}
}

func TestSamplingSomethingThatIsNotThereIsNotAFailure(t *testing.T) {
	// A scenario kills a process on purpose, and the sampler has to record a gap rather than end
	// the run.
	if _, ok := Sample(0); ok {
		t.Error("pid 0 was sampled")
	}
	if _, ok := Sample(1 << 30); ok {
		t.Error("a pid nothing owns was sampled")
	}
	if got := OpenFiles(0); got != 0 {
		t.Errorf("%d descriptors for pid 0", got)
	}
}

func TestSamplingThisProcessWorks(t *testing.T) {
	usage, ok := Sample(processID())
	if !ok {
		t.Skip("ps did not answer about this process")
	}
	if usage.Resident <= 0 {
		t.Errorf("this test is using %d bytes", usage.Resident)
	}
}

func TestTheBrokerChannelNameIsTheTransportsOwn(t *testing.T) {
	// The gateway answers both names in a ticket, so nothing in a run derives this. It is pinned
	// because a listener that built the name itself and got it wrong would sit on a silent
	// connection and look like a delivery problem.
	if got := StreamChannelName("server/abc"); got != "feed:server/abc" {
		t.Fatalf("%q", got)
	}
}
