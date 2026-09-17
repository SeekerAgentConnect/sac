// What the gateway sends the broker, and what it does when the broker will not take it (SEE-91).
//
// These tests answer with a fake broker rather than a real one, because what they are about is the
// request: the path, the key, the channel's transport name, the payload, and the idempotency key
// that makes a retried delivery one publication. broker_test.go drives a real Centrifugo for the
// other half — that a real server accepts exactly this.
package stream

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/dispatch"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/store"
)

const channelA = "server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

// A broker is the drainer's dispatcher, checked where it costs nothing: if that stops being
// true, this file stops compiling.
var _ dispatch.Dispatcher = (*Broker)(nil)

// taken is one publication as the broker received it.
type taken struct {
	path    string
	key     string
	Channel string `json:"channel"`
	Data    string `json:"b64data"`
	Idem    string `json:"idempotency_key"`
}

// fake is a broker that records what it was sent and answers what it is told to.
type fake struct {
	server *httptest.Server
	got    []taken
	status int
	body   string
}

func broker(t *testing.T, change ...func(*fake)) (*Broker, *fake) {
	t.Helper()
	answering := &fake{status: http.StatusOK, body: `{"result":{"offset":1,"epoch":"abcd"}}`}
	for _, apply := range change {
		apply(answering)
	}
	answering.server = httptest.NewServer(http.HandlerFunc(
		func(writer http.ResponseWriter, request *http.Request) {
			raw, _ := io.ReadAll(request.Body)
			one := taken{path: request.URL.Path, key: request.Header.Get("X-API-Key")}
			_ = json.Unmarshal(raw, &one)
			answering.got = append(answering.got, one)
			writer.WriteHeader(answering.status)
			_, _ = writer.Write([]byte(answering.body))
		}))
	t.Cleanup(answering.server.Close)
	client, err := New(Options{
		URL:          answering.server.URL,
		APIKey:       "the-api-key",
		TokenKey:     "the-token-key",
		Lifetime:     time.Hour,
		MostChannels: 4,
	})
	if err != nil {
		t.Fatal(err)
	}
	return client, answering
}

func delivery(revision uint64, event string) dispatch.Delivery {
	return dispatch.Delivery{
		Channel:    channelA,
		Kind:       store.ProposalNotice,
		ProposalID: "7c9e6679-7425-40de-944b-e07fc1f90ae7",
		Revision:   revision,
		Sequence:   revision,
		Event:      []byte(event),
	}
}

func TestAPublicationCarriesTheEventTheChannelAndNothingElse(t *testing.T) {
	client, answering := broker(t)

	if err := client.Dispatch(context.Background(), delivery(7, "the-event-bytes")); err != nil {
		t.Fatalf("publishing failed: %v", err)
	}

	if len(answering.got) != 1 {
		t.Fatalf("%d publications were sent", len(answering.got))
	}
	one := answering.got[0]
	if one.path != "/api/publish" || one.key != "the-api-key" {
		t.Fatalf("the request went to %s with key %q", one.path, one.key)
	}
	// The transport's name for the channel, not the protocol's: the namespace is what scopes
	// history and recovery to feed channels, and the document still says "server/<id>" inside.
	if one.Channel != "feed:"+channelA {
		t.Fatalf("the publication named %q", one.Channel)
	}
	payload, err := base64.StdEncoding.DecodeString(one.Data)
	if err != nil || string(payload) != "the-event-bytes" {
		t.Fatalf("the payload was %q (%v)", one.Data, err)
	}
	// Nothing else is sent. A tag, a user, a note of who it is for — anything the broker would
	// keep beside the publication — would be data about a channel's subscribers that no contract
	// describes, so a publication is exactly three fields and this is the shape of them.
	body, _ := json.Marshal(publication{Channel: "c", Data: "d", Key: "k"})
	if got := string(body); got != `{"channel":"c","b64data":"d","idempotency_key":"k"}` {
		t.Fatalf("a publication is %s", got)
	}
}

// The same document at the same revision is the same publication, however many times the outbox
// sends it — that is what lets a broker with a dedup window turn an at-least-once retry into one
// publication. Two revisions of the same proposal are two.
func TestARetryIsTheSamePublicationAndANewRevisionIsNot(t *testing.T) {
	client, answering := broker(t)

	for range 2 {
		if err := client.Dispatch(context.Background(), delivery(7, "same")); err != nil {
			t.Fatal(err)
		}
	}
	if err := client.Dispatch(context.Background(), delivery(8, "newer")); err != nil {
		t.Fatal(err)
	}

	first, again, newer := answering.got[0].Idem, answering.got[1].Idem, answering.got[2].Idem
	if first != again {
		t.Fatalf("a retry asked to be a second publication: %q then %q", first, again)
	}
	if newer == first {
		t.Fatalf("revision 8 reused revision 7's key %q", newer)
	}
	if !strings.Contains(first, channelA) || !strings.HasSuffix(first, "/7") {
		t.Fatalf("the key does not say which document it is about: %q", first)
	}
}

// A manifest and a proposal on the same channel are different documents, so a manifest publication
// must never deduplicate against a proposal's.
func TestAManifestAndAProposalAreDifferentPublications(t *testing.T) {
	proposal := delivery(3, "proposal")
	manifest := dispatch.Delivery{
		Channel:  channelA,
		Kind:     store.ManifestNotice,
		Revision: 3,
		Sequence: 3,
		Event:    []byte("manifest"),
	}
	if IdempotencyKey(proposal) == IdempotencyKey(manifest) {
		t.Fatalf("both are %q", IdempotencyKey(proposal))
	}
}

func TestEveryWayTheBrokerCanRefuseIsAFailureToRetry(t *testing.T) {
	for _, one := range []struct {
		name   string
		status int
		body   string
	}{
		{"a key the broker does not accept", http.StatusUnauthorized, "unauthorized"},
		{"a broker that is broken", http.StatusInternalServerError, "oh dear"},
		{"an answer that is not the broker's", http.StatusOK, "<html>proxy</html>"},
		{"a refusal in the answer", http.StatusOK, `{"error":{"code":102,"message":"unknown channel"}}`},
	} {
		t.Run(one.name, func(t *testing.T) {
			client, _ := broker(t, func(f *fake) {
				f.status = one.status
				f.body = one.body
			})
			err := client.Dispatch(context.Background(), delivery(1, "event"))
			if err == nil {
				t.Fatal("the delivery was reported as made")
			}
			// The notice stays pending on any error (internal/dispatch), which is what makes a
			// misconfigured broker a delay rather than a hole. And what the gateway says about it
			// names the channel and the status, never the answer's body or its own key.
			if strings.Contains(err.Error(), "the-api-key") ||
				strings.Contains(err.Error(), one.body) {
				t.Fatalf("the error repeats something it should not: %v", err)
			}
		})
	}
}

func TestAnEventTooBigToSendIsNotSent(t *testing.T) {
	client, answering := broker(t)
	huge := delivery(1, strings.Repeat("x", MostPayloadBytes+1))

	if err := client.Dispatch(context.Background(), huge); err == nil {
		t.Fatal("an oversized event was reported as delivered")
	}
	if len(answering.got) != 0 {
		t.Fatalf("it was sent anyway: %d requests", len(answering.got))
	}
}

func TestABrokerThatIsNotThereIsAFailureAndNotAPanic(t *testing.T) {
	client, answering := broker(t)
	answering.server.Close()

	if err := client.Dispatch(context.Background(), delivery(1, "event")); err == nil {
		t.Fatal("publishing to a closed broker succeeded")
	}
}

func TestHalfAConfigurationIsNoConfiguration(t *testing.T) {
	full := Options{
		URL:          "http://127.0.0.1:8000",
		APIKey:       "key",
		TokenKey:     "token",
		Lifetime:     time.Hour,
		MostChannels: 8,
	}
	if _, err := New(full); err != nil {
		t.Fatalf("a complete configuration was refused: %v", err)
	}
	for name, change := range map[string]func(*Options){
		"no URL":       func(o *Options) { o.URL = "" },
		"no API key":   func(o *Options) { o.APIKey = "" },
		"no token key": func(o *Options) { o.TokenKey = "" },
		"no lifetime":  func(o *Options) { o.Lifetime = 0 },
		"no bound":     func(o *Options) { o.MostChannels = 0 },
	} {
		t.Run(name, func(t *testing.T) {
			options := full
			change(&options)
			if _, err := New(options); err == nil {
				t.Fatal("it was accepted")
			}
		})
	}
}
