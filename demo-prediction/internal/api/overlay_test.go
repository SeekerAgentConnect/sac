package api_test

import (
	"net/http"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/demo-prediction/internal/jupiter"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

func TestDiscoveryMarketsListsWhatTheQueryMatched(t *testing.T) {
	held := predicting(t, &fakeGateway{}, &provider{events: oneMarket()})

	answered := held.Call(http.MethodGet, "/v1/discovery/markets?category=economics&keywords=fed", nil)
	if answered.Status != http.StatusOK {
		t.Fatalf("%d %s", answered.Status, answered.Raw)
	}
	rows, _ := answered.Body["markets"].([]any)
	if len(rows) != 1 {
		t.Fatalf("wanted 1 market, got %s", answered.Raw)
	}
	row, _ := rows[0].(map[string]any)
	if row["market_id"] != "POLY-2589813" || row["published"] != false {
		t.Fatalf("row %v", row)
	}
}

func TestDiscoveryMarketsNeedTheToken(t *testing.T) {
	held := predicting(t, &fakeGateway{}, &provider{events: oneMarket()})
	answered := held.Call(http.MethodGet, "/v1/discovery/markets", nil, "Authorization", "")
	if answered.Status != http.StatusUnauthorized {
		t.Fatalf("%d %s", answered.Status, answered.Raw)
	}
}

func TestSelectPublishesTheNamedMarket(t *testing.T) {
	listing := &provider{
		events: oneMarket(),
		market: func(id string) (jupiter.Market, error) {
			if id != "POLY-2589813" {
				return jupiter.Market{}, &jupiter.Fault{Problem: jupiter.NoSuchMarket}
			}
			return jupiter.Market{
				MarketID:  "POLY-2589813",
				EventID:   "POLY-606422",
				Provider:  "polymarket",
				Title:     "25 bps increase",
				Status:    jupiter.Open,
				CloseTime: now.Add(72 * time.Hour).Unix(),
			}, nil
		},
	}
	fake := &fakeGateway{}
	held := predicting(t, fake, listing)

	answered := held.Call(http.MethodPost, "/v1/discovery/select", map[string]any{
		"market_id": "POLY-2589813",
	})
	if answered.Status != http.StatusOK {
		t.Fatalf("%d %s", answered.Status, answered.Raw)
	}
	if answered.Body["market_id"] != "POLY-2589813" {
		t.Fatalf("select %s", answered.Raw)
	}
	listed := held.Call(http.MethodGet, "/v1/requests", nil)
	requests, _ := listed.Body["requests"].([]any)
	if listed.Status != http.StatusOK || len(requests) != 1 {
		t.Fatalf("published list %d %s", listed.Status, listed.Raw)
	}
}

func TestSelectRefusesAClosedMarket(t *testing.T) {
	listing := &provider{
		events: oneMarket(),
		market: func(string) (jupiter.Market, error) {
			return jupiter.Market{
				MarketID: "POLY-2589813",
				EventID:  "POLY-606422",
				Provider: "polymarket",
				Title:    "25 bps increase",
				Status:   jupiter.Closed,
			}, nil
		},
	}
	held := predicting(t, &fakeGateway{}, listing)
	answered := held.Call(http.MethodPost, "/v1/discovery/select", map[string]any{
		"market_id": "POLY-2589813",
	})
	if answered.Status != http.StatusConflict || answered.Problem() != "not_tradeable" {
		t.Fatalf("%d %s", answered.Status, answered.Raw)
	}
}

func TestCallersStillCannotWriteARequest(t *testing.T) {
	held := predicting(t, &fakeGateway{}, &provider{events: oneMarket()})
	answered := held.Call(http.MethodPost, "/v1/requests", map[string]any{
		"expires_at": now.Add(time.Hour).Format(time.RFC3339),
		"terms":      map[string]string{signals.MarketID: "POLY-1"},
	}, "Idempotency-Key", "key-1")
	if answered.Status != http.StatusForbidden || answered.Problem() != "written_by_discovery" {
		t.Fatalf("%d %s", answered.Status, answered.Raw)
	}
}

func TestSelectNeedsAMarketID(t *testing.T) {
	held := predicting(t, &fakeGateway{}, &provider{events: oneMarket()})
	answered := held.Call(http.MethodPost, "/v1/discovery/select", map[string]any{})
	if answered.Status != http.StatusBadRequest {
		t.Fatalf("%d %s", answered.Status, answered.Raw)
	}
}
