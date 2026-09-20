package gateway_test

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"testing"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1/gatewayv1connect"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
)

// This is the documented publisher path without generated Go bindings: ordinary JSON requests,
// bearer authentication and the Connect procedure URLs. It keeps the gateway usable from any
// language and makes the README's curl sequence executable as a test.
func TestAPlainHTTPClientPublishesUpdatesAndWithdrawsAFeedItem(t *testing.T) {
	gateway := newGateway(t)
	credential := gateway.register(publisherA)
	channel := rules.ChannelFor(publisherA)

	manifest := `{"manifest":{"serverId":"` + publisherA + `","protocolVersion":1,` +
		`"settingsRevision":"1","mode":"CONNECTION_MODE_GATEWAY_FEED",` +
		`"environments":["SERVER_ENVIRONMENT_PRODUCTION"],"displayName":"HTTP fixture",` +
		`"feed":{"gatewayUrl":"` + gatewayURL + `","channel":"` + channel + `"}}}`
	stored := postJSON(t, gateway.publish.URL+gatewayv1connect.PublisherServicePublishManifestProcedure,
		credential, manifest)
	requireJSON(t, stored, "status", "PUBLISH_STATUS_STORED")
	requireJSON(t, stored, "settingsRevision", "1")

	proposal := func(revision, updated, price string) string {
		return `{"proposal":{"serverId":"` + publisherA + `","channel":"` + channel + `",` +
			`"proposalId":"` + proposalA + `","revision":"` + revision + `",` +
			`"operation":"swap","pluginId":"jupiter.swap","status":"PROPOSAL_STATUS_OPEN",` +
			`"createdAt":"2026-09-17T09:00:00Z","updatedAt":"` + updated + `",` +
			`"expiresAt":"2026-09-17T12:00:00Z","publisherNote":"plain HTTP",` +
			`"values":[{"key":"input_mint","text":"So11111111111111111111111111111111111111112"},` +
			`{"key":"published_price","text":"` + price + `"}]}}`
	}
	created := postJSON(t, gateway.publish.URL+gatewayv1connect.PublisherServicePublishProposalProcedure,
		credential, proposal("1", "2026-09-17T09:30:00Z", "139420000"))
	requireJSON(t, created, "status", "PUBLISH_STATUS_STORED")
	requireJSON(t, created, "revision", "1")

	updated := postJSON(t, gateway.publish.URL+gatewayv1connect.PublisherServicePublishProposalProcedure,
		credential, proposal("2", "2026-09-17T10:00:00Z", "141000000"))
	requireJSON(t, updated, "status", "PUBLISH_STATUS_STORED")
	requireJSON(t, updated, "revision", "2")

	withdrawn := postJSON(t, gateway.publish.URL+gatewayv1connect.PublisherServiceCancelProposalProcedure,
		credential, `{"proposalId":"`+proposalA+`","revision":"3"}`)
	requireJSON(t, withdrawn, "status", "PUBLISH_STATUS_STORED")
	proposalAnswer, ok := withdrawn["proposal"].(map[string]any)
	if !ok {
		t.Fatalf("withdrawal has no proposal: %v", withdrawn)
	}
	requireJSON(t, proposalAnswer, "status", "PROPOSAL_STATUS_CANCELLED")
	requireJSON(t, proposalAnswer, "revision", "3")

	read := postJSON(t, gateway.read.URL+gatewayv1connect.FeedServiceGetProposalProcedure, "",
		`{"channel":"`+channel+`","proposalId":"`+proposalA+`"}`)
	held, ok := read["proposal"].(map[string]any)
	if !ok {
		t.Fatalf("the authoritative read has no proposal: %v", read)
	}
	requireJSON(t, held, "status", "PROPOSAL_STATUS_CANCELLED")
	requireJSON(t, held, "revision", "3")
}

func postJSON(t *testing.T, url, credential, body string) map[string]any {
	t.Helper()
	request, err := http.NewRequest(http.MethodPost, url, bytes.NewBufferString(body))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Content-Type", "application/json")
	if credential != "" {
		request.Header.Set("Authorization", "Bearer "+credential)
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	raw, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("POST %s answered %d: %s", url, response.StatusCode, raw)
	}
	var decoded map[string]any
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatalf("POST %s did not answer JSON: %v\n%s", url, err, raw)
	}
	return decoded
}

func requireJSON(t *testing.T, document map[string]any, field string, expected any) {
	t.Helper()
	if document[field] != expected {
		t.Fatalf("%s is %v, expected %v in %v", field, document[field], expected, document)
	}
}
