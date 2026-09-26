package gateway

import (
	"testing"
	"time"

	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/server/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/publishertest"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// The fake gateway, the credential and the documents are the support library's shared test
// material, so that this client, the drainer and both demos are tested against one opinion of what
// the real gateway does (packages/publisher-support/publishertest).

const (
	credential = publishertest.Credential
	server     = publishertest.ServerID
)

var now = publishertest.Now

func swap() signals.Signal                                { return publishertest.Swap() }
func manifestAt(revision uint64) *serverv1.ServerManifest { return publishertest.ManifestAt(revision) }

// serve starts the fake and returns a client for it.
func serve(t *testing.T, fake *publishertest.FakeGateway) *Gateway {
	t.Helper()
	server := publishertest.Serve(t, fake)
	client, err := New(Options{
		URL:        server.URL,
		Credential: credential,
		Timeout:    5 * time.Second,
		HTTP:       server.Client(),
	})
	if err != nil {
		t.Fatal(err)
	}
	return client
}
