package discovery_test

import (
	"context"
	"fmt"
	"strings"
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

// A selected market is titled by its event, read from the provider: a yes-or-no market's own title is
// just "Yes", and a proposal named by it alone said nothing (the Curry report, 2026-10-11).
func TestSelectTitlesTheProposalByItsEvent(t *testing.T) {
	chosen := market("POLY-2044124", noon.Add(72*time.Hour))
	chosen.EventID, chosen.Title = "POLY-403296", "Yes"
	provider := &source{
		market: func(string) (jupiter.Market, error) { return chosen, nil },
		event: func(id string) (jupiter.Event, error) {
			if id != chosen.EventID {
				return jupiter.Event{}, &jupiter.Fault{Problem: jupiter.NoSuchMarket}
			}
			return jupiter.Event{EventID: id, Title: "NBA: Stephen Curry to leave Warriors?",
				Category: "sports", Markets: []jupiter.Market{chosen}}, nil
		},
	}
	held := start(t, provider)
	if _, err := held.reconciler.Select(context.Background(), chosen.MarketID); err != nil {
		t.Fatal(err)
	}
	records := held.signals()
	if len(records) != 1 {
		t.Fatalf("%d signals", len(records))
	}
	signal := records[0].Signal
	if !strings.Contains(signal.Title, "Stephen Curry") ||
		!strings.Contains(signal.Note, "NBA: Stephen Curry to leave Warriors? — Yes") {
		t.Fatalf("titled %q, noted %q", signal.Title, signal.Note)
	}
}

// An operator's name chooses which market, never whether an owner could get out of it again.
func TestSelectRefusesAMarketNobodyBidsFor(t *testing.T) {
	stuck := market("POLY-m4", noon.Add(72*time.Hour))
	stuck.BuyYes, stuck.BuyNo, stuck.SellNo = 1_000, jupiter.OneDollar-1, 999_000
	provider := &source{
		market: func(string) (jupiter.Market, error) { return stuck, nil },
	}
	held := start(t, provider, func(h *harness) { h.filters.MostSpread = 50_000 })
	_, err := held.reconciler.Select(context.Background(), stuck.MarketID)
	if err != discovery.ErrIlliquid {
		t.Fatalf("err %v", err)
	}
}

func TestSelectWaitsForARunningCycle(t *testing.T) {
	started := make(chan struct{})
	block := make(chan struct{})
	provider := &source{
		events: func(jupiter.Query) (jupiter.Page, error) {
			select {
			case <-started:
			default:
				close(started)
			}
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

	// A caller that gives up while the cycle runs is answered busy.
	gaveUp, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	if _, err := held.reconciler.Select(gaveUp, "POLY-m1"); err != markets.ErrBusy {
		t.Fatalf("a caller that gave up: %v", err)
	}

	// One that waits gets its market once the cycle is done.
	selected := make(chan error, 1)
	go func() {
		_, err := held.reconciler.Select(context.Background(), "POLY-m1")
		selected <- err
	}()
	time.Sleep(20 * time.Millisecond)
	close(block)
	<-done
	if err := <-selected; err != nil {
		t.Fatalf("a waiting select: %v", err)
	}
}
