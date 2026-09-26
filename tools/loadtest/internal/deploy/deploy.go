// Package deploy is the topology a run measures (SEE-99).
//
// Everything in it is the shipped thing: the `broadcast` binary on its own SQLite file, the pinned
// Centrifugo release on `services/gateway/centrifugo.yaml` unchanged, real Redis on the settings
// `deploy/feed/compose.yaml` gives it, and `feed-gatewayctl` as the only way a publisher comes to exist.
// A load run against a stack assembled for the load run would measure the assembly.
//
// What is **not** here is the proxy. `deploy/ingress/feed/Caddyfile` is in front of all of this in a
// deployment, and there is no Docker daemon on the machine these runs were made on
// (docs/testing/stage-7.md), so the hop is named as excluded in the report rather than folded into
// a latency number. Nothing else in the path is stood in for except Firebase (push.go), which SEE-99
// requires to be stood in for.
//
// # Native, and why that is the honest arrangement rather than a compromise
//
// A container would add a network namespace and a volume to every number. Running the binaries
// directly is what SEE-91's own hand verification did (docs/testing/stage-7-1.md), it is what lets
// a scenario send one node a signal and watch the other keep serving, and it is reproducible from a
// checkout plus two downloaded binaries — which is the whole of what "reproduce the report" needs
// to mean.
package deploy

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Binaries are the programs a run drives. The two Go ones are built by `pnpm test:load`; the two
// services are not vendored and are named by the operator, which is the same arrangement every
// other opt-in check in this repository uses.
type Binaries struct {
	// The built `feed-gateway`, and `feed-gatewayctl`.
	Gateway string
	Control string
	// The pinned Centrifugo release. Empty means no stream: the gateway then keeps its documents,
	// answers every read, and tells a listener there is no stream here — which is a smaller
	// deployment rather than a broken one, and a run that says so rather than passing quietly.
	Broker string
	// `redis-server`. Empty means one broker node on the memory engine, and no second node: Redis
	// is what makes two nodes one broker.
	Redis string
	// The broker configuration to start the nodes on. Empty means this checkout's own
	// `services/gateway/centrifugo.yaml`, which is the only file a measurement should be made against —
	// it is here for an operator running the harness from outside a checkout.
	BrokerConfig string
}

// FromEnvironment reads the four paths, on the variable names the rest of the repository uses.
func FromEnvironment() Binaries {
	return Binaries{
		Gateway: os.Getenv("SEEKERVAULT_FEED_GATEWAY"),
		Control: os.Getenv("SEEKERVAULT_FEED_GATEWAYCTL"),
		Broker:  os.Getenv("SEEKERVAULT_CENTRIFUGO"),
		Redis:   os.Getenv("SEEKERVAULT_REDIS"),

		BrokerConfig: os.Getenv("SEEKERVAULT_CENTRIFUGO_CONFIG"),
	}
}

// The two keys this run's gateway and broker share. Throwaway, generated per run in no sense at
// all: they are constants, because a run is one process on one machine with no network, and a
// constant is one less thing in a report that is not a measurement.
const (
	BrokerAPIKey   = "loadtest-api-key"
	BrokerTokenKey = "loadtest-token-key"
)

// Options is the deployment to bring up.
type Options struct {
	Binaries
	// How many broker nodes. Zero is a gateway with no stream; more than one needs Redis.
	BrokerNodes int
	// The gateway's own bounds, as a deployment sets them. Zero means the gateway's default, which
	// is what a report should be measuring unless it says otherwise.
	PublishRate  float64
	PublishBurst int
	ReadRate     float64
	ReadBurst    int
	MaxProposals int
	// Whether the push relay runs against the stand-in (push.go).
	Push bool
	// The broker configuration to start the nodes on. Empty means `services/gateway/centrifugo.yaml`,
	// found by walking up from the working directory — the shipped one, which is the only one a
	// measurement should be made against.
	BrokerConfig string
	// Where the temporary files go. Empty means a directory of this run's own, removed on Close.
	Dir string
}

// Node is one broker node.
type Node struct {
	*Process
	// The HTTP API origin, which only the gateway calls.
	API string
	// Where a listener consumes the unidirectional gRPC stream.
	Stream string
}

// Deployment is everything that is running.
type Deployment struct {
	Gateway *Process
	// The gateway's read origin, which every manifest must name, and its publisher listener.
	Origin  string
	Publish string
	// The database, so a scenario can restart the gateway on the same state.
	Database string

	Nodes []*Node
	Redis *Process
	Push  *Push

	// What the broker calls a channel of the protocol's. It is the transport's own naming and it
	// is duplicated here rather than imported, because `internal/stream` is the gateway's and a
	// harness that imported it could not notice the two disagreeing (stream_test.go pins the name
	// on that side, and StreamChannelName is pinned against a real ticket on this one).
	dir     string
	own     bool
	control string
	cleanup []func()
}

// StreamChannelName is the broker's name for a protocol channel: `feed:server/<server_id>`.
//
// The gateway answers both names in a ticket (GetStreamTicketResponse.channels), so a run never has
// to derive this — and does not. It is here for the assertion that it did not have to: a listener
// that built the name itself and got it wrong would sit on a silent connection and report a
// delivery problem.
func StreamChannelName(channel string) string { return "feed:" + channel }

// Start brings the deployment up, in the order a deployment comes up: Redis, the broker nodes, the
// push stand-in, then the gateway.
func Start(ctx context.Context, options Options) (*Deployment, error) {
	if options.BrokerNodes > 1 && options.Redis == "" {
		return nil, fmt.Errorf(
			"deploy: %d broker nodes need Redis — set SEEKERVAULT_REDIS, or run one node",
			options.BrokerNodes)
	}
	if options.BrokerNodes > 0 && options.Broker == "" {
		return nil, fmt.Errorf("deploy: a broker node needs SEEKERVAULT_CENTRIFUGO")
	}
	if options.Gateway == "" || options.Control == "" {
		return nil, fmt.Errorf(
			"deploy: the gateway and feed-gatewayctl must be built — see docs/development/load.md")
	}

	dir, own := options.Dir, false
	if dir == "" {
		made, err := os.MkdirTemp("", "seeker-vault-loadtest-")
		if err != nil {
			return nil, fmt.Errorf("deploy: a temporary directory: %w", err)
		}
		dir, own = made, true
	}
	deployment := &Deployment{
		dir:      dir,
		own:      own,
		control:  options.Control,
		Database: filepath.Join(dir, "broadcast.db"),
	}
	started := false
	defer func() {
		if !started {
			deployment.Close()
		}
	}()

	if options.BrokerNodes > 0 && options.Redis != "" {
		err := retrying("redis", func() error {
			port, err := free()
			if err != nil {
				return err
			}
			redis, err := start(ctx, process{
				What:    "redis",
				Command: options.Redis,
				// The settings deploy/feed/compose.yaml gives it: a cache, not a store. Nothing is
				// persisted, because everything in it can be rebuilt by the phones that read the
				// gateway — and a Redis that survived a restart would be a second place a feed's
				// history lived.
				Args: []string{
					"--port", strconv.Itoa(port),
					"--bind", "127.0.0.1",
					"--save", "",
					"--appendonly", "no",
					"--maxmemory", "256mb",
					"--maxmemory-policy", "allkeys-lru",
				},
				Dir:   dir,
				Ready: tcp("127.0.0.1:" + strconv.Itoa(port)),
			})
			if err != nil {
				return err
			}
			redis.address = "redis://127.0.0.1:" + strconv.Itoa(port)
			deployment.Redis = redis
			return nil
		})
		if err != nil {
			return nil, err
		}
	}

	brokerConfig := options.BrokerConfig
	if brokerConfig == "" {
		brokerConfig = options.Binaries.BrokerConfig
	}
	if options.BrokerNodes > 0 && brokerConfig == "" {
		found, err := shippedConfig()
		if err != nil {
			return nil, err
		}
		brokerConfig = found
	}
	for node := range options.BrokerNodes {
		err := retrying(fmt.Sprintf("broker node %d", node+1), func() error {
			api, err := free()
			if err != nil {
				return err
			}
			stream, err := free()
			if err != nil {
				return err
			}
			engine := []string{"CENTRIFUGO_ENGINE_TYPE=memory"}
			if deployment.Redis != nil {
				engine = []string{
					"CENTRIFUGO_ENGINE_TYPE=redis",
					"CENTRIFUGO_ENGINE_REDIS_ADDRESS=" + deployment.Redis.address,
					// One prefix per run, so two runs on one machine never share a history.
					"CENTRIFUGO_ENGINE_REDIS_PREFIX=loadtest-" + filepath.Base(dir),
				}
			}
			broker, err := start(ctx, process{
				What:    fmt.Sprintf("broker node %d", node+1),
				Command: options.Broker,
				// The shipped configuration, unchanged. Only the ports, the two secrets and the engine
				// are overridden, because those are what a deployment sets from its own environment
				// (deploy/feed/compose.yaml does exactly this).
				Args: []string{"-c", brokerConfig},
				Dir:  dir,
				Environment: append(engine,
					"CENTRIFUGO_HTTP_SERVER_PORT="+strconv.Itoa(api),
					"CENTRIFUGO_UNI_GRPC_PORT="+strconv.Itoa(stream),
					"CENTRIFUGO_HTTP_API_KEY="+BrokerAPIKey,
					"CENTRIFUGO_CLIENT_TOKEN_HMAC_SECRET_KEY="+BrokerTokenKey,
					// A node that logged every connection would spend a load run writing about it.
					"CENTRIFUGO_LOG_LEVEL=error",
					// The one setting this harness turns on that the shipped configuration does not,
					// and why is in observe.go: the server API's counters are a sixty-second
					// aggregate, and a fifteen-second window needs live ones. It is an endpoint on
					// the already-private API port, and it changes no transport and no channel rule.
					"CENTRIFUGO_PROMETHEUS_ENABLED=true",
				),
				// Starting it is also how the configuration is checked: `checkconfig` accepts settings
				// the server then refuses to run with (docs/development/feed-gateway.md).
				Ready: healthy("http://127.0.0.1:" + strconv.Itoa(api) + "/health"),
			})
			if err != nil {
				return err
			}
			deployment.Nodes = append(deployment.Nodes, &Node{
				Process: broker,
				API:     "http://127.0.0.1:" + strconv.Itoa(api),
				Stream:  "http://127.0.0.1:" + strconv.Itoa(stream),
			})
			return nil
		})
		if err != nil {
			return nil, err
		}
	}

	if options.Push {
		push, err := StartPush(dir, "sandbox")
		if err != nil {
			return nil, err
		}
		deployment.Push = push
		deployment.cleanup = append(deployment.cleanup, push.Stop)
	}

	if err := retrying("the gateway", func() error {
		read, err := free()
		if err != nil {
			return err
		}
		publish, err := free()
		if err != nil {
			return err
		}
		deployment.Origin = "http://127.0.0.1:" + strconv.Itoa(read)
		deployment.Publish = "http://127.0.0.1:" + strconv.Itoa(publish)
		environment := []string{
			"BROADCAST_PUBLIC_URL=" + deployment.Origin,
			"BROADCAST_READ_ADDRESS=127.0.0.1:" + strconv.Itoa(read),
			"BROADCAST_PUBLISHER_ADDRESS=127.0.0.1:" + strconv.Itoa(publish),
			"BROADCAST_DATABASE_PATH=" + deployment.Database,
		}
		// The gateway publishes to one broker API. With two nodes that is deliberate rather than a
		// simplification: Redis is what makes the other one carry the same publication, and whether it
		// does is what a two-node run is asking.
		if len(deployment.Nodes) > 0 {
			environment = append(environment,
				"BROADCAST_STREAM_URL="+deployment.Nodes[0].API,
				"BROADCAST_STREAM_API_KEY="+BrokerAPIKey,
				"BROADCAST_STREAM_TOKEN_KEY="+BrokerTokenKey)
		}
		if options.Push {
			environment = append(environment,
				"BROADCAST_PUSH_CREDENTIALS="+deployment.Push.CredentialPath,
				"BROADCAST_PUSH_ENDPOINT="+deployment.Push.Endpoint,
				"BROADCAST_PUSH_ENVIRONMENT="+deployment.Push.Environment)
		}
		for name, value := range map[string]float64{
			"BROADCAST_PUBLISH_RATE": options.PublishRate,
			"BROADCAST_READ_RATE":    options.ReadRate,
		} {
			if value > 0 {
				environment = append(environment,
					name+"="+strconv.FormatFloat(value, 'f', -1, 64))
			}
		}
		for name, value := range map[string]int{
			"BROADCAST_PUBLISH_BURST": options.PublishBurst,
			"BROADCAST_READ_BURST":    options.ReadBurst,
			"BROADCAST_MAX_PROPOSALS": options.MaxProposals,
		} {
			if value > 0 {
				environment = append(environment, name+"="+strconv.Itoa(value))
			}
		}
		gateway, err := start(ctx, process{
			What:        "the gateway",
			Command:     options.Gateway,
			Dir:         dir,
			Environment: environment,
			Ready:       healthy(deployment.Origin + "/healthz"),
		})
		if err != nil {
			return err
		}
		deployment.Gateway = gateway
		return nil
	}); err != nil {
		return nil, err
	}

	started = true
	return deployment, nil
}

// Close stops everything, youngest first, and removes what this run created.
func (d *Deployment) Close() {
	if d.Gateway != nil {
		_ = d.Gateway.Stop()
	}
	for _, node := range d.Nodes {
		_ = node.Stop()
	}
	if d.Redis != nil {
		_ = d.Redis.Stop()
	}
	for _, stop := range d.cleanup {
		stop()
	}
	if d.own {
		_ = os.RemoveAll(d.dir)
	}
}

// Register creates a publisher and returns the credential the gateway prints once.
//
// There is no RPC that could do this, which is the point: a publisher exists because an operator
// said so, and a load run with a thousand publishers is a thousand deliberate local acts.
func (d *Deployment) Register(serverID, label string) (string, error) {
	printed, err := d.Control("register", "--server", serverID, "--label", label)
	if err != nil {
		return "", err
	}
	credential := credentialPattern.FindString(printed)
	if credential == "" {
		return "", fmt.Errorf("deploy: no credential in what feed-gatewayctl printed:\n%s", printed)
	}
	return credential, nil
}

var credentialPattern = regexp.MustCompile(`(?m)^[A-Za-z0-9_-]{43}$`)

// Control runs `feed-gatewayctl` against this deployment's database. The database goes after the
// subcommand, which is where the CLI's own examples put it.
func (d *Deployment) Control(args ...string) (string, error) {
	if len(args) == 0 {
		return "", fmt.Errorf("deploy: feed-gatewayctl needs a subcommand")
	}
	command := exec.Command(d.control, append(
		[]string{args[0], "--database", d.Database}, args[1:]...)...)
	command.Env = []string{"PATH=" + os.Getenv("PATH")}
	output, err := command.CombinedOutput()
	if err != nil {
		return string(output), fmt.Errorf("deploy: feed-gatewayctl %s: %w: %s",
			strings.Join(args, " "), err, output)
	}
	return string(output), nil
}

// retrying starts one service, and tries again for the two things a machine that has just run a
// load test does to the next one.
//
// **The port it chose was taken between the choosing and the binding.** `free` finds a port by
// binding it and letting go, and a broker node that then bound it found `address already in use` —
// which ended a scenario ten minutes into a measurement. Choosing again is the honest fix; there is
// no way to hand a listening socket to a child that binds its own.
//
// **The kernel had no local port to give.** Ten thousand listeners is twenty thousand sockets, and
// a loopback ephemeral range is sixteen thousand ports held for thirty seconds after they close
// (`net.inet.ip.portrange`, `net.inet.tcp.msl` on this machine). A run that follows a large one
// too closely fails to *connect* with `can't assign requested address`, which is not this
// deployment's problem and is not a reason to lose the measurement — so it waits for the range to
// drain rather than giving up. It is also worth knowing when reading a report: the ramp is best run
// on its own (docs/development/load.md).
func retrying(what string, start func() error) error {
	const attempts = 4
	const drain = 15 * time.Second
	var last error
	for attempt := range attempts {
		last = start()
		switch {
		case last == nil:
			return nil
		case strings.Contains(last.Error(), "address already in use"):
			// Choose again, immediately.
		case strings.Contains(last.Error(), "can't assign requested address"),
			strings.Contains(last.Error(), "cannot assign requested address"):
			if attempt < attempts-1 {
				time.Sleep(drain)
			}
		default:
			return last
		}
	}
	return fmt.Errorf("deploy: %s could not get a port in %d tries: %w", what, attempts, last)
}

// free is a port nothing is listening on. There is a window between closing this listener and the
// process binding it; on a loopback interface with one process asking, it has not been a problem,
// and a collision is a start-up failure with the binary's own message rather than a mystery.
func free() (int, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, fmt.Errorf("deploy: finding a free port: %w", err)
	}
	defer func() { _ = listener.Close() }()
	return listener.Addr().(*net.TCPAddr).Port, nil
}

// shippedConfig finds `services/gateway/centrifugo.yaml` by walking up from the working directory.
//
// It is deliberately not compiled in and not derived from the executable's path: `pnpm test:load`
// builds the harness into a temporary directory, and a run against a configuration that was not
// this checkout's would be the one mistake this whole package exists to avoid. An operator running
// the binary somewhere else names the file with --broker-config.
func shippedConfig() (string, error) {
	at, err := os.Getwd()
	if err != nil {
		return "", fmt.Errorf("deploy: finding the broker configuration: %w", err)
	}
	for {
		candidate := filepath.Join(at, "services", "gateway", "centrifugo.yaml")
		if _, err := os.Stat(candidate); err == nil {
			return candidate, nil
		}
		parent := filepath.Dir(at)
		if parent == at {
			return "", fmt.Errorf(
				"deploy: services/gateway/centrifugo.yaml is not above %s — run this from the "+
					"repository, or name the file with --broker-config", at)
		}
		at = parent
	}
}

// healthy polls an HTTP endpoint until it answers.
func healthy(url string) func(context.Context) error {
	client := &http.Client{Timeout: 2 * time.Second}
	return func(ctx context.Context) error {
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
		if err != nil {
			return err
		}
		response, err := client.Do(request)
		if err != nil {
			return err
		}
		defer func() { _ = response.Body.Close() }()
		if response.StatusCode != http.StatusOK {
			return fmt.Errorf("%s answered %s", url, response.Status)
		}
		return nil
	}
}

// tcp is the readiness of something that speaks no HTTP: it accepts a connection.
func tcp(address string) func(context.Context) error {
	return func(ctx context.Context) error {
		dialer := &net.Dialer{Timeout: time.Second}
		connection, err := dialer.DialContext(ctx, "tcp", address)
		if err != nil {
			return err
		}
		return connection.Close()
	}
}

// process is one child to run.
type process struct {
	What        string
	Command     string
	Args        []string
	Dir         string
	Environment []string
	// Called until it returns nil, or the process dies, or the deadline passes.
	Ready func(context.Context) error
}

// Process is a child this harness started, and the control a scenario needs over it.
type Process struct {
	settings process
	address  string

	mutex  sync.Mutex
	child  *exec.Cmd
	output *collected
	// Closed when the child exits, so Stop and Wait do not race the reaper.
	done chan struct{}
}

// Everything still running, and one handler that stops them.
//
// A load run is servers: a Ctrl+C or a CI timeout would otherwise leave a gateway, two broker nodes
// and a Redis behind, holding ports and memory. A signal that cannot be caught (SIGKILL) still
// leaks them, so a killed run is worth a look at `lsof -nP -iTCP -sTCP:LISTEN`.
var (
	livingMutex sync.Mutex
	living      = map[*Process]bool{}
	signalsOnce sync.Once
)

func watchSignals() {
	signalsOnce.Do(func() {
		signals := make(chan os.Signal, 1)
		signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
		go func() {
			<-signals
			livingMutex.Lock()
			for one := range living {
				_ = one.Kill()
			}
			livingMutex.Unlock()
			os.Exit(1)
		}()
	})
}

func start(ctx context.Context, settings process) (*Process, error) {
	watchSignals()
	one := &Process{settings: settings}
	if err := one.Start(ctx); err != nil {
		return nil, err
	}
	return one, nil
}

// Start runs the process, again if it has already run once: the same settings, the same state, the
// same ports. It is how `gateway` and `drain` scenarios put back what they took down.
func (p *Process) Start(ctx context.Context) error {
	p.mutex.Lock()
	if p.child != nil && p.child.Process != nil {
		select {
		case <-p.done:
		default:
			p.mutex.Unlock()
			return fmt.Errorf("deploy: %s is already running", p.settings.What)
		}
	}
	output := &collected{}
	if p.output != nil {
		output.restarted(p.settings.What, p.output.String())
	}
	child := exec.Command(p.settings.Command, p.settings.Args...)
	child.Dir = p.settings.Dir
	// Only what was asked for: a process here never sees this machine's environment, so a stray
	// BROADCAST_ or CENTRIFUGO_ variable in a developer's shell cannot change what was measured.
	child.Env = append([]string{"PATH=" + os.Getenv("PATH"), "HOME=" + os.Getenv("HOME")},
		p.settings.Environment...)
	child.Stdout, child.Stderr = output, output
	if err := child.Start(); err != nil {
		p.mutex.Unlock()
		return fmt.Errorf("deploy: starting %s: %w", p.settings.What, err)
	}
	done := make(chan struct{})
	p.child, p.output, p.done = child, output, done
	p.mutex.Unlock()

	livingMutex.Lock()
	living[p] = true
	livingMutex.Unlock()

	go func() {
		_ = child.Wait()
		close(done)
		livingMutex.Lock()
		delete(living, p)
		livingMutex.Unlock()
	}()

	deadline := time.Now().Add(20 * time.Second)
	for {
		select {
		case <-done:
			return fmt.Errorf("deploy: %s exited while starting:\n%s",
				p.settings.What, output.String())
		default:
		}
		if p.settings.Ready == nil {
			return nil
		}
		if err := p.settings.Ready(ctx); err == nil {
			return nil
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("deploy: %s never became ready:\n%s",
				p.settings.What, output.String())
		}
		if err := sleep(ctx, 50*time.Millisecond); err != nil {
			return err
		}
	}
}

// Output is everything it has printed, stdout and stderr in the order they arrived. A process that
// refused a publication for a reason an assertion cannot see is otherwise a mystery, and the log
// line naming the problem is always there.
func (p *Process) Output() string {
	p.mutex.Lock()
	output := p.output
	p.mutex.Unlock()
	if output == nil {
		return ""
	}
	return output.String()
}

// Running says whether it is still up.
func (p *Process) Running() bool {
	p.mutex.Lock()
	done := p.done
	p.mutex.Unlock()
	if done == nil {
		return false
	}
	select {
	case <-done:
		return false
	default:
		return true
	}
}

// Stop is the graceful one: SIGTERM, and wait. The gateway makes a last fan-out pass and closes its
// database; a broker node stops accepting, closes its streams with `3001 shutdown`, and exits.
func (p *Process) Stop() error { return p.signal(syscall.SIGTERM, 35*time.Second) }

// Kill is the ungraceful one, and the interesting one: nothing is drained, no stream is closed with
// a reason, and every listener finds out by the connection going away.
func (p *Process) Kill() error { return p.signal(syscall.SIGKILL, 10*time.Second) }

func (p *Process) signal(which syscall.Signal, wait time.Duration) error {
	p.mutex.Lock()
	child, done := p.child, p.done
	p.mutex.Unlock()
	if child == nil || child.Process == nil || done == nil {
		return nil
	}
	select {
	case <-done:
		return nil
	default:
	}
	if err := child.Process.Signal(which); err != nil && !errors.Is(err, os.ErrProcessDone) {
		return fmt.Errorf("deploy: signalling %s: %w", p.settings.What, err)
	}
	select {
	case <-done:
		return nil
	case <-time.After(wait):
		_ = child.Process.Kill()
		return fmt.Errorf("deploy: %s did not exit within %s of %s",
			p.settings.What, wait, which)
	}
}

// PID is what the resource sampler asks the operating system about.
func (p *Process) PID() int {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	if p.child == nil || p.child.Process == nil {
		return 0
	}
	return p.child.Process.Pid
}

func sleep(ctx context.Context, how time.Duration) error {
	timer := time.NewTimer(how)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

// collected is a process's output, bounded. A broker that decided to log every connection in a
// ten-thousand-client run would otherwise be the thing that ran the machine out of memory, and the
// interesting lines are the first ones — the configuration it started with — and the last, which is
// why both ends are kept.
type collected struct {
	mutex sync.Mutex
	head  strings.Builder
	tail  []byte
}

const (
	keepHead = 64 * 1024
	keepTail = 64 * 1024
)

func (c *collected) Write(chunk []byte) (int, error) {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	if c.head.Len() < keepHead {
		room := min(keepHead-c.head.Len(), len(chunk))
		c.head.Write(chunk[:room])
	}
	c.tail = append(c.tail, chunk...)
	if len(c.tail) > keepTail {
		c.tail = append([]byte(nil), c.tail[len(c.tail)-keepTail:]...)
	}
	return len(chunk), nil
}

func (c *collected) String() string {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	head := c.head.String()
	tail := string(c.tail)
	if strings.HasSuffix(head, tail) || strings.Contains(head, tail) {
		return head
	}
	return head + "\n…\n" + tail
}

func (c *collected) restarted(what, before string) {
	c.mutex.Lock()
	defer c.mutex.Unlock()
	c.head.WriteString(before)
	c.head.WriteString(fmt.Sprintf("\n--- %s restarted ---\n", what))
}
