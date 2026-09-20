package sdk

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestCreateRequestUsesTheOneDeveloperEndpoint(t *testing.T) {
	var path, key, authorization string
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		path, key, authorization = request.URL.Path, request.Header.Get("Idempotency-Key"), request.Header.Get("Authorization")
		writer.Header().Set("Content-Type", "application/json")
		writer.WriteHeader(http.StatusCreated)
		_, _ = writer.Write([]byte(`{"request":{"contract_version":1},"publication":{"state":"published"}}`))
	}))
	defer server.Close()
	client, err := New(Options{URL: server.URL, Token: "secret"})
	if err != nil {
		t.Fatal(err)
	}
	answer, err := client.CreateRequest(context.Background(), CreateRequest{
		IdempotencyKey: "strategy-42", ExpiresAt: time.Date(2026, 9, 18, 18, 0, 0, 0, time.UTC),
		Description: "one request", Parameters: map[string]string{"market_id": "m1"},
	})
	if err != nil || len(answer.Request) == 0 {
		t.Fatalf("create: answer=%v err=%v", answer, err)
	}
	if path != "/v1/requests" || key != "strategy-42" || authorization != "Bearer secret" {
		t.Fatalf("call was path=%q key=%q auth=%q", path, key, authorization)
	}
}
