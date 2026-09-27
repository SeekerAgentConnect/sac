// The other half of the publish contract: that a real broker accepts exactly what this package
// sends (SEE-91).
//
// stream_test.go pins the request against a fake. This drives the pinned Centrifugo release, with
// **the configuration this repository ships** (services/gateway/centrifugo.yaml), so what is proven is the
// pair rather than either side's opinion: the API path, the key header, the base64 payload field,
// the channel name the namespace accepts, and the idempotency key a retry must reuse.
//
// It is opt-in, because a broker is a service and CI has none:
//
//	SEEKERVAULT_CENTRIFUGO=/path/to/centrifugo go test ./internal/stream/ -run Broker -v
//
// The phone's own integration test starts two nodes and real Redis and drives the transport with
// the real client (apps/android/.../feeds/CentrifugoStreamIntegrationTest.kt). This one needs neither:
// what it asks is whether a publication is accepted, which one node and no Redis can answer.
package stream

import (
	"context"
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

// centrifugo starts the pinned broker with the shipped configuration and returns its API origin.
func centrifugo(t *testing.T) string {
	t.Helper()
	binary, set := os.LookupEnv("SEEKERVAULT_CENTRIFUGO")
	if !set {
		t.Skip("set SEEKERVAULT_CENTRIFUGO to the centrifugo binary to run this")
	}
	config, err := filepath.Abs(filepath.Join("..", "..", "centrifugo.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	api, stream := free(t), free(t)
	broker := exec.Command(binary, "-c", config)
	broker.Env = append(os.Environ(),
		// The memory engine: this test is about one node accepting a publication, and Redis is what
		// makes two nodes one broker rather than what makes a publication valid.
		"CENTRIFUGO_ENGINE_TYPE=memory",
		fmt.Sprintf("CENTRIFUGO_HTTP_SERVER_PORT=%d", api),
		fmt.Sprintf("CENTRIFUGO_UNI_GRPC_PORT=%d", stream),
		"CENTRIFUGO_HTTP_API_KEY=test-api-key",
		"CENTRIFUGO_CLIENT_TOKEN_HMAC_SECRET_KEY=test-token-key",
		"CENTRIFUGO_LOG_LEVEL=error")
	output := &strings.Builder{}
	broker.Stdout, broker.Stderr = output, output
	if err := broker.Start(); err != nil {
		t.Fatalf("starting the broker failed: %v", err)
	}
	t.Cleanup(func() {
		_ = broker.Process.Kill()
		_, _ = broker.Process.Wait()
		if t.Failed() && output.Len() > 0 {
			t.Logf("the broker said:\n%s", output)
		}
	})
	origin := fmt.Sprintf("http://127.0.0.1:%d", api)
	// Starting is also how the configuration is checked: `checkconfig` accepts settings the server
	// then refuses to run with, so a broker that does not become healthy fails this test.
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		response, err := http.Get(origin + "/health")
		if err == nil {
			_ = response.Body.Close()
			if response.StatusCode == http.StatusOK {
				return origin
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("the broker did not become healthy:\n%s", output)
	return ""
}

func free(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = listener.Close() }()
	return listener.Addr().(*net.TCPAddr).Port
}

// position asks the broker where a channel is, which is the one thing its JSON API can say about a
// channel carrying binary publications: `history` with a limit of zero answers the epoch and the
// offset and no payloads. Reading the payloads back over JSON answers 500, because a protobuf
// document is not JSON — a limitation of the pairing that is written down rather than discovered
// (docs/wiki/feed-gateway.md#the-stream).
func position(t *testing.T, origin, channel string) (string, uint64) {
	t.Helper()
	body := strings.NewReader(fmt.Sprintf(`{"channel":%q,"limit":0}`, channel))
	request, err := http.NewRequest(http.MethodPost, origin+"/api/history", body)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("X-API-Key", "test-api-key")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = response.Body.Close() }()
	raw, _ := io.ReadAll(response.Body)
	var answer struct {
		Result struct {
			Epoch  string `json:"epoch"`
			Offset uint64 `json:"offset"`
		} `json:"result"`
		Error *struct {
			Code uint32 `json:"code"`
		} `json:"error"`
	}
	if err := json.Unmarshal(raw, &answer); err != nil {
		t.Fatalf("the broker answered %q", raw)
	}
	if answer.Error != nil {
		t.Fatalf("asking for the position failed with code %d", answer.Error.Code)
	}
	return answer.Result.Epoch, answer.Result.Offset
}

func TestBrokerAcceptsWhatTheGatewaySends(t *testing.T) {
	origin := centrifugo(t)
	client, err := New(Options{
		URL:          origin,
		APIKey:       "test-api-key",
		TokenKey:     "test-token-key",
		Lifetime:     time.Hour,
		MostChannels: 8,
	})
	if err != nil {
		t.Fatal(err)
	}
	channel := client.StreamChannel(channelA)

	// A publication lands, with history: the channel has a position afterwards, which is what a
	// reconnecting listener recovers from. No position would mean the namespace was not the one the
	// configuration declares — the silent failure the constant exists to prevent.
	if err := client.Dispatch(context.Background(), delivery(1, "first-event")); err != nil {
		t.Fatalf("a real broker refused a publication: %v", err)
	}
	epoch, offset := position(t, origin, channel)
	if epoch == "" || offset != 1 {
		t.Fatalf("the channel is at epoch %q offset %d", epoch, offset)
	}

	// A second document advances it.
	if err := client.Dispatch(context.Background(), delivery(2, "second-event")); err != nil {
		t.Fatal(err)
	}
	if _, offset = position(t, origin, channel); offset != 2 {
		t.Fatalf("two publications left the channel at %d", offset)
	}

	// And the retry the outbox makes after a crash between delivering and clearing the notice is
	// one publication, not two: the same document at the same revision carries the same
	// idempotency key, and the broker's window is long enough for a retry to fall inside it.
	if err := client.Dispatch(context.Background(), delivery(2, "second-event")); err != nil {
		t.Fatal(err)
	}
	if _, offset = position(t, origin, channel); offset != 2 {
		t.Fatalf("a retried delivery became publication %d", offset)
	}

	// A different document at the same revision is not the same publication, whatever else is
	// equal: the key names the channel, the kind and the document.
	other := delivery(2, "second-event")
	other.ProposalID = "a1b2c3d4-e5f6-4789-abcd-0123456789ab"
	if err := client.Dispatch(context.Background(), other); err != nil {
		t.Fatal(err)
	}
	if _, offset = position(t, origin, channel); offset != 3 {
		t.Fatalf("another proposal left the channel at %d", offset)
	}
}

// The key is the whole of the gateway's authority over the broker, and a wrong one has to be a
// failure the outbox retries rather than a publication nobody sees.
func TestBrokerRefusesAWrongKey(t *testing.T) {
	origin := centrifugo(t)
	client, err := New(Options{
		URL:          origin,
		APIKey:       "not-the-api-key",
		TokenKey:     "test-token-key",
		Lifetime:     time.Hour,
		MostChannels: 8,
	})
	if err != nil {
		t.Fatal(err)
	}

	if err := client.Dispatch(context.Background(), delivery(1, "event")); err == nil {
		t.Fatal("a real broker accepted a publication with the wrong key")
	}
}
