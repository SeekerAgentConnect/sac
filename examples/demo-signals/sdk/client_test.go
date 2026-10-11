package sdk

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestCreateRequestUsesTheOneDeveloperEndpoint(t *testing.T) {
	var path, key, authorization string
	var body map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		path, key, authorization = request.URL.Path, request.Header.Get("Idempotency-Key"), request.Header.Get("Authorization")
		_ = json.NewDecoder(request.Body).Decode(&body)
		writer.Header().Set("Content-Type", "application/json")
		writer.WriteHeader(http.StatusCreated)
		_, _ = writer.Write([]byte(`{"request":{"contract_version":2},"publication":{"state":"published"}}`))
	}))
	defer server.Close()
	client, err := New(Options{URL: server.URL, Token: "secret"})
	if err != nil {
		t.Fatal(err)
	}
	answer, err := client.CreateRequest(context.Background(), CreateRequest{
		IdempotencyKey: "strategy-42", Network: "mainnet", ExpiresAt: time.Date(2026, 9, 18, 18, 0, 0, 0, time.UTC),
		Description: "one request", Parameters: map[string]string{"market_id": "m1"},
	})
	if err != nil || len(answer.Request) == 0 {
		t.Fatalf("create: answer=%v err=%v", answer, err)
	}
	if path != "/v1/requests" || key != "strategy-42" || authorization != "Bearer secret" {
		t.Fatalf("call was path=%q key=%q auth=%q", path, key, authorization)
	}
	if body["network"] != "mainnet" {
		t.Fatalf("the body names network %v", body["network"])
	}
}

// A request that names no network is not sent: the template would refuse it, and nothing here
// picks one (SEE-185).
func TestCreateRequestNeedsANetwork(t *testing.T) {
	called := false
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		called = true
	}))
	defer server.Close()
	client, err := New(Options{URL: server.URL, Token: "secret"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.CreateRequest(context.Background(), CreateRequest{
		IdempotencyKey: "strategy-43", ExpiresAt: time.Date(2026, 9, 18, 18, 0, 0, 0, time.UTC),
		Parameters: map[string]string{"market_id": "m1"},
	}); err == nil || called {
		t.Fatalf("a request with no network was sent (err %v, called %v)", err, called)
	}
}
