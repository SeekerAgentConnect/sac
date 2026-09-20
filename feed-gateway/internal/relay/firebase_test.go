// The other half of the hint contract: that Firebase accepts exactly what this package sends
// (SEE-92).
//
// relay_test.go pins the message against a fake endpoint. This one sends a real hint to a real
// project, which is the only way to know that the grant, the scope, the path and the message shape
// are the ones Google's own service accepts — a fake endpoint agrees with whatever it is given.
//
// It is opt-in, because it needs a credential and it wakes whoever is subscribed:
//
//	SEEKERVAULT_FCM_CREDENTIALS=/path/to/service-account.json \
//	SEEKERVAULT_FCM_SERVER=00000000-0000-4000-8000-000000000000 \
//	go test ./internal/relay/ -run Firebase -v
//
// The server ID is deliberately a parameter rather than a constant: it decides the topic, so an
// operator running this chooses a publisher of their own — or an ID nobody is subscribed to, which
// sends a hint into an empty topic and still proves everything above. The endpoint is the real one
// by default and can be pointed elsewhere with SEEKERVAULT_FCM_ENDPOINT.
//
// What it cannot prove is delivery. Firebase accepting a message is not a phone receiving one, and
// no automated test on a laptop can answer that: the device run in docs/testing/stage-7-1.md is
// where that question is settled.
package relay

import (
	"context"
	"io"
	"log/slog"
	"os"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

func TestFirebaseAcceptsTheHintThisRelaySends(t *testing.T) {
	path, set := os.LookupEnv("SEEKERVAULT_FCM_CREDENTIALS")
	if !set {
		t.Skip("set SEEKERVAULT_FCM_CREDENTIALS to a service account file to run this")
	}
	serverID, set := os.LookupEnv("SEEKERVAULT_FCM_SERVER")
	if !set {
		t.Skip("set SEEKERVAULT_FCM_SERVER to the publisher whose topic to send to")
	}
	endpoint := os.Getenv("SEEKERVAULT_FCM_ENDPOINT")
	if endpoint == "" {
		endpoint = "https://fcm.googleapis.com"
	}

	credentials, err := ReadCredentials(path)
	if err != nil {
		t.Fatal(err)
	}
	hints, err := New(Options{
		Endpoint:    endpoint,
		Credentials: credentials,
		Environment: Sandbox,
		Rate:        1,
		Burst:       1,
		Now:         time.Now,
		Log:         slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	if err != nil {
		t.Fatal(err)
	}

	topic := hints.Topic("server/" + serverID)
	if topic == "" {
		t.Fatalf("%q is not a publisher ID", serverID)
	}
	ctx, stop := context.WithTimeout(context.Background(), 30*time.Second)
	defer stop()
	// send rather than Dispatch, because here the failure is the answer: Dispatch swallows one by
	// design, which is right in production and useless in a test.
	if err := hints.send(ctx, topic, storage.ProposalNotice); err != nil {
		t.Fatalf("Firebase refused the hint: %v", err)
	}
	t.Logf("Firebase accepted a hint on %s", topic)
}
