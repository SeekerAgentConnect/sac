package discovery_test

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/demo-prediction/internal/discovery"
	"github.com/BrRenat/SeekerAgentWallet/demo-prediction/internal/jupiter"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/markets"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

func TestSearchUsesTheQueryNotTheDeploymentFilters(t *testing.T) {
	provider := &source{events: listing(event("POLY-1", market("POLY-m1", noon.Add(72*time.Hour))))}
	held := start(t, provider)

	query := held.filters
	query.Keywords = []string{"ethereum"}
	found, err := held.reconciler.Search(context.Background(), query)
	if err != nil {
		t.Fatal(err)
	}
	if len(found) != 0 {
		t.Fatalf("deployment keywords should not leak into an ethereum query: %+v", found)
	}

	query.Keywords = []string{"fed"}
	found, err = held.reconciler.Search(context.Background(), query)
	if err != nil {
		t.Fatal(err)
	}
	if len(found) != 1 || found[0].MarketID != "POLY-m1" || found[0].Published {
		t.Fatalf("search %+v", found)
	}
	if len(held.signals()) != 0 {
		t.Fatal("search must not persist a signal")
	}
}

func TestSearchCapsTheListingAtTen(t *testing.T) {
	events := make([]jupiter.Event, 0, 15)
	for i := 1; i <= 15; i++ {
		id := fmt.Sprintf("POLY-m%02d", i)
		events = append(events, event(fmt.Sprintf("POLY-e%02d", i), market(id, noon.Add(72*time.Hour))))
	}
	held := start(t, &source{events: listing(events...)})
	query := held.filters
	query.PageSize = 25
	query.MostPages = 1
	found, err := held.reconciler.Search(context.Background(), query)
	if err != nil {
		t.Fatal(err)
	}
	if len(found) != 10 {
		t.Fatalf("wanted 10 markets, got %d", len(found))
	}
	if found[0].MarketID != "POLY-m01" || found[9].MarketID != "POLY-m10" {
		t.Fatalf("order %+v", found)
	}
}

func TestSelectPublishesAMarketTheFilterWouldSkip(t *testing.T) {
	chosen := market("POLY-m2", noon.Add(48*time.Hour))
	chosen.Title = "Will ETH hit 10k?"
	provider := &source{
		events: listing(event("POLY-1", market("POLY-m1", noon.Add(72*time.Hour)))),
		market: func(id string) (jupiter.Market, error) {
			if id != chosen.MarketID {
				return jupiter.Market{}, &jupiter.Fault{Problem: jupiter.NoSuchMarket}
			}
			return chosen, nil
		},
	}
	held := start(t, provider, func(h *harness) {
		h.filters.Keywords = []string{"fed"}
	})

	tracked, err := held.reconciler.Select(context.Background(), chosen.MarketID)
	if err != nil {
		t.Fatal(err)
	}
	if tracked.Market.MarketID != chosen.MarketID {
		t.Fatalf("selected %+v", tracked.Market)
	}
	if held.woken == 0 {
		t.Fatal("select must wake the drainer")
	}
	open := held.signals()
	if len(open) != 1 || open[0].Signal.Status != signals.Open {
		t.Fatalf("signals %+v", open)
	}
}

func TestSelectRefusesAMarketThatCannotBeTraded(t *testing.T) {
	closed := market("POLY-m3", noon.Add(time.Hour))
	closed.Status = jupiter.Closed
	provider := &source{
		market: func(string) (jupiter.Market, error) { return closed, nil },
	}
	held := start(t, provider)
	_, err := held.reconciler.Select(context.Background(), closed.MarketID)
	if err != discovery.ErrNotTradeable {
		t.Fatalf("err %v", err)
	}
}

func TestSelectIsBusyWhileACycleRuns(t *testing.T) {
	started := make(chan struct{})
	block := make(chan struct{})
	provider := &source{
		events: func(jupiter.Query) (jupiter.Page, error) {
			close(started)
			<-block
			return jupiter.Page{}, nil
		},
		market: func(string) (jupiter.Market, error) {
			return market("POLY-m1", noon.Add(72*time.Hour)), nil
		},
	}
	held := start(t, provider)
	done := make(chan struct{})
	go func() {
		defer close(done)
		_, _ = held.reconciler.Pass(context.Background())
	}()
	<-started
	_, err := held.reconciler.Select(context.Background(), "POLY-m1")
	close(block)
	<-done
	if err != markets.ErrBusy {
		t.Fatalf("err %v", err)
	}
}
