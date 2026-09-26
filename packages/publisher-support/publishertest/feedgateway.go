// The real feed gateway, as its own process, for the opt-in tests that prove a demo and the
// gateway agree rather than assuming it (SEE-134).
//
// It is here rather than in one demo's tests because both demos need exactly this, and a
// difference between two tests' gateways would be a difference nobody meant. Nothing runs unless
// SEEKERVAULT_FEED_GATEWAY names a built binary:
//
//	cd feed-gateway && go build -o /tmp/feed-gateway ./cmd/feed-gateway \
//	                && go build -o /tmp/feed-gatewayctl ./cmd/feed-gatewayctl

package publishertest

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// Gateway is the real feed gateway, running as its own process with its own database and a
// credential it issued for this template.
type Gateway struct {
	// Origin is where a phone reads.
	Origin string
	// The publisher API, which is a second listener: a deployment that keeps publishing off the
	// internet points a template here and still names the read origin in its manifest.
	PublishTo  string
	Credential string
	Directory  string
}

// RunGateway starts one and registers this template with it. Every opt-in test here needs exactly
// this, so it is written once: a difference between two tests' gateways would be a difference
// nobody meant.
//
// register is passed to `feed-gatewayctl register` as it is, which is how a test registers a
// restricted feed: `--access restricted --auth-origin <origin>` (SEE-156).
func RunGateway(t *testing.T, binary, label string, register ...string) Gateway {
	t.Helper()
	control := os.Getenv("SEEKERVAULT_FEED_GATEWAYCTL")
	if control == "" {
		control = filepath.Join(filepath.Dir(binary), "feed-gatewayctl")
	}
	if _, err := os.Stat(control); err != nil {
		t.Skipf("feed-gatewayctl is not beside the gateway (%v); set SEEKERVAULT_FEED_GATEWAYCTL", err)
	}

	directory := t.TempDir()
	database := filepath.Join(directory, "broadcast.db")
	read, publisher := FreePort(t), FreePort(t)
	origin := fmt.Sprintf("http://127.0.0.1:%d", read)

	// Registering a publisher is a local act with no network surface at all, so the test does what
	// an operator does: it runs the tool.
	registered, err := exec.Command(control, append([]string{"register", "--database", database,
		"--server", ServerID, "--label", label}, register...)...).CombinedOutput()
	if err != nil {
		t.Fatalf("feed-gatewayctl register: %v\n%s", err, registered)
	}
	issued := CredentialFrom(t, string(registered))

	process := exec.Command(binary)
	process.Env = append(os.Environ(),
		"BROADCAST_PUBLIC_URL="+origin,
		"BROADCAST_DATABASE_PATH="+database,
		fmt.Sprintf("BROADCAST_READ_ADDRESS=127.0.0.1:%d", read),
		fmt.Sprintf("BROADCAST_PUBLISHER_ADDRESS=127.0.0.1:%d", publisher),
		// No broker and no push relay: the gateway then holds the documents and answers every
		// read, which is all these tests are about.
		"BROADCAST_STREAM_URL=",
	)
	log := &bytes.Buffer{}
	process.Stdout, process.Stderr = log, log
	if err := process.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = process.Process.Signal(os.Interrupt)
		_, _ = process.Process.Wait()
		if t.Failed() {
			t.Logf("the gateway said:\n%s", log)
		}
	})
	WaitFor(t, origin+"/healthz")
	return Gateway{
		Origin:     origin,
		PublishTo:  fmt.Sprintf("http://127.0.0.1:%d", publisher),
		Credential: issued,
		Directory:  directory,
	}
}

// ReadFeed reads a channel's proposals the way a phone does: the read API, over plain JSON, with no
// credential at all. It deliberately uses no generated client, because none has to be — and it shows that any Connect client will do.
func ReadFeed(t *testing.T, origin, channel string, known map[string]any) string {
	t.Helper()
	body := map[string]any{"channel": channel}
	for name, value := range known {
		body[name] = value
	}
	encoded, err := json.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	request, err := http.NewRequest(http.MethodPost,
		origin+"/seekervault.gateway.v1.FeedService/ListProposals", bytes.NewReader(encoded))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Content-Type", "application/json")
	// A reader of its own, so the two readers in the test above share nothing: not a connection,
	// not a cookie jar, not a cache.
	answer, err := (&http.Client{Timeout: 10 * time.Second}).Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = answer.Body.Close() }()
	contents, err := io.ReadAll(answer.Body)
	if err != nil {
		t.Fatal(err)
	}
	if answer.StatusCode != http.StatusOK {
		t.Fatalf("the feed answered %s: %s", answer.Status, contents)
	}
	return string(contents)
}

// CredentialFrom reads the credential `feed-gatewayctl register` printed. It is shown once and stored
// only as a hash, so this is the only chance to have it — which is exactly what an operator
// experiences.
func CredentialFrom(t *testing.T, printed string) string {
	t.Helper()
	for _, line := range strings.Split(printed, "\n") {
		trimmed := strings.TrimSpace(line)
		// The credential is printed alone, on its own line, after the identifiers.
		if len(trimmed) == 43 && !strings.Contains(trimmed, " ") {
			return trimmed
		}
	}
	t.Fatalf("no credential in:\n%s", printed)
	return ""
}

// FreePort is a port nothing is listening on yet.
func FreePort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	if err := listener.Close(); err != nil {
		t.Fatal(err)
	}
	return port
}

// WaitFor blocks until an address answers 200, or fails the test.
func WaitFor(t *testing.T, address string) {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		answer, err := (&http.Client{Timeout: time.Second}).Get(address)
		if err == nil {
			_ = answer.Body.Close()
			if answer.StatusCode == http.StatusOK {
				return
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("%s never answered", address)
}

// Read calls one FeedService method over plain JSON, the way ReadFeed does, and answers the status
// and the body without judging either — for the restricted-feed tests, where a refusal is the
// answer being tested (SEE-156).
func Read(t *testing.T, origin, method string, body map[string]any) (int, string) {
	t.Helper()
	encoded, err := json.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	request, err := http.NewRequest(http.MethodPost,
		origin+"/seekervault.gateway.v1.FeedService/"+method, bytes.NewReader(encoded))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Content-Type", "application/json")
	answer, err := (&http.Client{Timeout: 10 * time.Second}).Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = answer.Body.Close() }()
	contents, err := io.ReadAll(answer.Body)
	if err != nil {
		t.Fatal(err)
	}
	return answer.StatusCode, string(contents)
}
