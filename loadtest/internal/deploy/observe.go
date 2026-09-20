package deploy

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"sort"
	"strconv"
	"strings"
	"time"
)

// What the deployment can be asked about itself while a run is going on (SEE-99).
//
// Two sources, and neither is the harness's own opinion: the broker's server API, which is where a
// node says how many clients it holds and how many publications it sent, and the operating system,
// which is where a process's CPU time and resident memory come from. A load report whose resource
// numbers came from inside the harness would be measuring the harness.

// NodeInfo is one broker node, as it describes itself.
//
// The numbers come from the node's own metrics endpoint rather than from its server API, and that
// is the one setting this harness turns on that `feed-gateway/centrifugo.yaml` does not:
// `prometheus.enabled`. The reason is a measurement one. The server API's `info` answers from an
// aggregate the node refreshes every sixty seconds, so a fifteen-second window reads zero for
// every counter in it; the metrics endpoint answers live. It is on the API port, which is private
// in every deployment this repository ships, it adds no transport and changes no channel rule —
// and the report says it was on (docs/testing/see-99.md).
type NodeInfo struct {
	// Which node this is, as this harness names it: the deployment's own numbering.
	Name string `json:"name"`
	// What it is holding now.
	Clients       int `json:"clients"`
	Channels      int `json:"channels"`
	Subscriptions int `json:"subscriptions"`
	// How many nodes this one can see, which is how a two-node run proves the two became one
	// broker rather than two brokers sharing a laptop.
	Nodes int `json:"nodes_seen"`
	// Publications this node put on the broker — what the gateway published to it — and
	// publications it took off the broker to deliver to its own clients. On a two-node run they
	// are the evidence for the thing Redis is there for: a publication accepted by either node
	// reaches the clients attached to both, so one node accepts them all and both fan them out.
	Accepted  int64 `json:"publications_accepted"`
	FannedOut int64 `json:"publications_fanned_out"`
	// Publications Redis dropped rather than delivered: the broker's own name for the buffer
	// pressure a report has to state honestly rather than discover later.
	Dropped int64 `json:"dropped_by_redis"`
	// The connection bound this node is configured with, so a run that hit it can say so.
	ConnectionLimit int `json:"connection_limit,omitempty"`
}

// Stats asks one node what it is holding.
func (n *Node) Stats(ctx context.Context, name string) (NodeInfo, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, n.API+"/metrics", nil)
	if err != nil {
		return NodeInfo{}, err
	}
	response, err := (&http.Client{Timeout: 5 * time.Second}).Do(request)
	if err != nil {
		return NodeInfo{}, fmt.Errorf("deploy: asking %s for its metrics: %w", name, err)
	}
	defer func() { _ = response.Body.Close() }()
	body, err := io.ReadAll(io.LimitReader(response.Body, 4<<20))
	if err != nil {
		return NodeInfo{}, err
	}
	metrics := parse(string(body))
	return NodeInfo{
		Name:          name,
		Clients:       int(metrics["centrifugo_node_num_clients"]),
		Channels:      int(metrics["centrifugo_node_num_channels"]),
		Subscriptions: int(metrics["centrifugo_node_num_subscriptions"]),
		Nodes:         int(metrics["centrifugo_node_num_nodes"]),
		Accepted: int64(sum(metrics,
			"centrifugo_node_messages_sent_count", `type="publication"`)),
		FannedOut: int64(sum(metrics,
			"centrifugo_node_messages_received_count", `type="publication"`)),
		Dropped: int64(sum(metrics,
			"centrifugo_broker_redis_pub_sub_dropped_messages", `channel_type="client"`)),
		ConnectionLimit: int(metrics["centrifugo_node_client_connection_limit"]),
	}, nil
}

// Info asks every node that is still up. A scenario that has just killed one still wants the
// others' answers, so a node that cannot be reached is absent rather than fatal.
func (d *Deployment) Info(ctx context.Context) ([]NodeInfo, error) {
	var nodes []NodeInfo
	var last error
	for index, node := range d.Nodes {
		name := fmt.Sprintf("broker %d", index+1)
		if !node.Running() {
			nodes = append(nodes, NodeInfo{Name: name + " (stopped)"})
			continue
		}
		stats, err := node.Stats(ctx, name)
		if err != nil {
			last = err
			continue
		}
		nodes = append(nodes, stats)
	}
	if len(nodes) == 0 {
		return nil, last
	}
	return nodes, nil
}

// parse reads the metrics exposition format, which is one metric per line: a name, optional labels
// in braces, and a value. Sorting the labels makes a key stable, because the order they are
// written in is not part of the format.
func parse(body string) map[string]float64 {
	metrics := map[string]float64{}
	for _, line := range strings.Split(body, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		space := strings.LastIndexByte(line, ' ')
		if space <= 0 {
			continue
		}
		value, err := strconv.ParseFloat(line[space+1:], 64)
		if err != nil {
			continue
		}
		metrics[normalize(line[:space])] = value
	}
	return metrics
}

// sum adds up every series of one metric whose labels include `label`.
//
// It is a substring match on the labels rather than an exact key, because a metric's label set is
// not a contract: this release writes `channel_namespace=""` beside the type on the message
// counters, and a harness that matched the whole set would silently read zero the day another label
// appears — which is the failure mode this whole file is trying to avoid.
func sum(metrics map[string]float64, name, label string) float64 {
	total := 0.0
	for key, value := range metrics {
		if strings.HasPrefix(key, name+"{") && strings.Contains(key, label) {
			total += value
		}
		if key == name && label == "" {
			total += value
		}
	}
	return total
}

// normalize sorts a metric's labels so `a{x="1",y="2"}` and `a{y="2",x="1"}` are one key.
func normalize(name string) string {
	open := strings.IndexByte(name, '{')
	if open < 0 || !strings.HasSuffix(name, "}") {
		return name
	}
	labels := strings.Split(name[open+1:len(name)-1], ",")
	sort.Strings(labels)
	return name[:open] + "{" + strings.Join(labels, ",") + "}"
}

// Position is where a channel is on the broker: its epoch and its offset.
//
// `history` with a limit of zero is the one thing the broker's JSON API can say about a channel
// carrying protobuf publications — reading the payloads back answers 500, because a protobuf
// document is not JSON (internal/stream, docs/wiki/feed-gateway.md). It is also all a run
// needs: whether the channel moved, and by how much.
func Position(ctx context.Context, api, streamChannel string) (string, uint64, error) {
	body, err := json.Marshal(map[string]any{"channel": streamChannel, "limit": 0})
	if err != nil {
		return "", 0, err
	}
	request, err := http.NewRequestWithContext(ctx,
		http.MethodPost, api+"/api/history", bytes.NewReader(body))
	if err != nil {
		return "", 0, err
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("X-API-Key", BrokerAPIKey)
	response, err := (&http.Client{Timeout: 5 * time.Second}).Do(request)
	if err != nil {
		return "", 0, err
	}
	defer func() { _ = response.Body.Close() }()
	var answer struct {
		Result struct {
			Epoch  string `json:"epoch"`
			Offset uint64 `json:"offset"`
		} `json:"result"`
		Error *struct {
			Code uint32 `json:"code"`
		} `json:"error"`
	}
	if err := json.NewDecoder(response.Body).Decode(&answer); err != nil {
		return "", 0, err
	}
	if answer.Error != nil {
		return "", 0, fmt.Errorf("deploy: asking for a position failed with code %d",
			answer.Error.Code)
	}
	return answer.Result.Epoch, answer.Result.Offset, nil
}

// Usage is what the operating system says about one process.
type Usage struct {
	// Resident memory, in bytes.
	Resident int64
	// Processor time used since it started.
	CPU time.Duration
}

// Sample asks the operating system about a process.
//
// `ps` rather than a library: it is on every machine this can run on, the three numbers are the
// three a report needs, and a dependency for reading /proc — which macOS does not have — would be
// the wrong trade for a test tool. A process that has gone is not an error: a scenario kills one on
// purpose, and the sampler records a gap rather than failing the run.
func Sample(pid int) (Usage, bool) {
	if pid <= 0 {
		return Usage{}, false
	}
	// rss in kilobytes, and cputime as [dd-]hh:mm:ss.
	output, err := exec.Command("ps", "-o", "rss=,time=", "-p", strconv.Itoa(pid)).Output()
	if err != nil {
		return Usage{}, false
	}
	fields := strings.Fields(string(output))
	if len(fields) < 2 {
		return Usage{}, false
	}
	kilobytes, err := strconv.ParseInt(fields[0], 10, 64)
	if err != nil {
		return Usage{}, false
	}
	return Usage{Resident: kilobytes * 1024, CPU: cpuTime(fields[1])}, true
}

// cpuTime reads ps's own duration spelling: [[dd-]hh:]mm:ss[.ss].
func cpuTime(text string) time.Duration {
	days := 0
	if dash := strings.IndexByte(text, '-'); dash >= 0 {
		days, _ = strconv.Atoi(text[:dash])
		text = text[dash+1:]
	}
	parts := strings.Split(text, ":")
	seconds := 0.0
	for _, part := range parts {
		value, err := strconv.ParseFloat(part, 64)
		if err != nil {
			return 0
		}
		seconds = seconds*60 + value
	}
	seconds += float64(days) * 24 * 60 * 60
	return time.Duration(seconds * float64(time.Second))
}

// OpenFiles counts a process's descriptors, which for these processes is very nearly its connection
// count: a broker node holding ten thousand listeners is holding ten thousand sockets.
//
// It is deliberately **not** part of [Sample]. `lsof` takes seconds on a process holding ten
// thousand of them, so asking every two seconds would make the sampler the load — it is asked once
// at the end of a stage instead. A zero means "not measured" rather than "none", and the report
// says which.
func OpenFiles(pid int) int {
	if pid <= 0 {
		return 0
	}
	output, err := exec.Command("lsof", "-p", strconv.Itoa(pid), "-n", "-P").Output()
	if err != nil {
		return 0
	}
	return max(bytes.Count(output, []byte{'\n'})-1, 0)
}

// processID is this process, for the sampler's own test.
func processID() int { return os.Getpid() }
