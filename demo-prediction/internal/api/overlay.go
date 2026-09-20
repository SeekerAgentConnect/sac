package api

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/demo-prediction/internal/discovery"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/markets"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// Overlay adds this demo's operator search and select on top of the shared publisher API.
//
// Callers still cannot POST /v1/requests: those stay 403. Selecting a market asks discovery to
// publish that market through the same store path a cycle uses (SEE-138).
func Overlay(next http.Handler, token string, finder Finder, drain Drain) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/discovery/markets", authorize(token, search(finder)))
	mux.HandleFunc("POST /v1/discovery/select", authorize(token, selectMarket(finder, drain)))
	mux.Handle("/", next)
	return mux
}

// Finder is the reconciler as this overlay uses it: search the listing, publish one market, and
// the deployment's filters as form defaults.
type Finder interface {
	Search(ctx context.Context, query discovery.Filters) ([]discovery.Preview, error)
	Select(ctx context.Context, marketID string) (markets.Tracked, error)
	Filters() discovery.Filters
}

// Drain publishes whatever the store says is pending. Select calls it so an operator sees the
// gateway's confirmation rather than "pending" on the market they just picked.
type Drain func(ctx context.Context) error

func search(finder Finder) http.HandlerFunc {
	return func(writer http.ResponseWriter, request *http.Request) {
		query := queryFilters(request, finder.Filters())
		found, err := finder.Search(request.Context(), query)
		if err != nil {
			refuseFinder(writer, err)
			return
		}
		rows := make([]map[string]any, 0, len(found))
		for _, one := range found {
			row := map[string]any{
				"market_id":   one.MarketID,
				"event_id":    one.EventID,
				"title":       one.Title,
				"event_title": one.EventTitle,
				"category":    one.Category,
				"tags":        one.Tags,
				"state":       one.State,
				"source_url":  one.SourceURL,
				"published":   one.Published,
			}
			if one.Tags == nil {
				row["tags"] = []string{}
			}
			if !one.CloseAt.IsZero() {
				row["close_at"] = one.CloseAt.UTC().Format(time.RFC3339)
			}
			rows = append(rows, row)
		}
		writeJSON(writer, http.StatusOK, map[string]any{
			"query":   query.Describe(),
			"markets": rows,
		})
	}
}

func selectMarket(finder Finder, drain Drain) http.HandlerFunc {
	return func(writer http.ResponseWriter, request *http.Request) {
		body, err := io.ReadAll(io.LimitReader(request.Body, 64<<10))
		if err != nil {
			writeJSON(writer, http.StatusBadRequest, problem{
				Error: "bad_request", Detail: "the body could not be read",
			})
			return
		}
		var asked struct {
			MarketID string `json:"market_id"`
		}
		if len(strings.TrimSpace(string(body))) > 0 {
			if err := json.Unmarshal(body, &asked); err != nil {
				writeJSON(writer, http.StatusBadRequest, problem{
					Error: "bad_request", Detail: "the body is not this endpoint's JSON",
				})
				return
			}
		}
		if asked.MarketID == "" {
			asked.MarketID = request.FormValue("market_id")
		}
		if !signals.IsMarketID(asked.MarketID) {
			writeJSON(writer, http.StatusBadRequest, problem{
				Error: "bad_request", Detail: "market_id must be the provider's own identifier",
			})
			return
		}
		tracked, err := finder.Select(request.Context(), asked.MarketID)
		if err != nil {
			refuseFinder(writer, err)
			return
		}
		if drain != nil {
			if err := drain(request.Context()); err != nil {
				writeJSON(writer, http.StatusBadGateway, problem{
					Error: "publication_failed", Detail: err.Error(),
				})
				return
			}
		}
		writeJSON(writer, http.StatusOK, map[string]any{
			"market_id":   tracked.Market.MarketID,
			"event_id":    tracked.Market.EventID,
			"title":       tracked.Market.Title,
			"state":       tracked.Market.State,
			"signal":      tracked.Record.Signal.ProposalID,
			"publication": tracked.Record.Publication.State(tracked.Record.Signal),
		})
	}
}

func queryFilters(request *http.Request, fallback discovery.Filters) discovery.Filters {
	query := fallback
	values := request.URL.Query()
	if source := strings.TrimSpace(values.Get("source")); source != "" {
		query.Source = source
	}
	if category := strings.TrimSpace(values.Get("category")); category != "" {
		query.Categories = []string{category}
	}
	if named := strings.TrimSpace(values.Get("filter")); named != "" {
		query.Filter = named
	}
	if keywords := splitCSV(values.Get("keywords")); keywords != nil {
		query.Keywords = keywords
	}
	if tags := splitCSV(values.Get("tags")); tags != nil {
		query.Tags = tags
	}
	if minutes, ok := intQuery(values.Get("least_close_minutes")); ok {
		query.LeastCloseIn = time.Duration(minutes) * time.Minute
	}
	if minutes, ok := intQuery(values.Get("most_close_minutes")); ok {
		query.MostCloseIn = time.Duration(minutes) * time.Minute
	}
	if pages, ok := intQuery(values.Get("pages")); ok {
		query.MostPages = pages
	}
	if size, ok := intQuery(values.Get("page_size")); ok {
		query.PageSize = size
	}
	switch strings.ToLower(strings.TrimSpace(values.Get("state"))) {
	case "any", "closed":
		query.Closed = true
	case "open":
		query.Closed = false
	}
	return query
}

func splitCSV(raw string) []string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil
	}
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, one := range parts {
		one = strings.TrimSpace(one)
		if one != "" {
			out = append(out, one)
		}
	}
	return out
}

func intQuery(raw string) (int, bool) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return 0, false
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < 0 {
		return 0, false
	}
	return value, true
}

func authorize(token string, next http.HandlerFunc) http.HandlerFunc {
	return func(writer http.ResponseWriter, request *http.Request) {
		presented, found := strings.CutPrefix(request.Header.Get("Authorization"), "Bearer ")
		if !found || subtle.ConstantTimeCompare([]byte(presented), []byte(token)) != 1 {
			writeJSON(writer, http.StatusUnauthorized, problem{
				Error:  "unauthorized",
				Detail: "present the template's API token as `Authorization: Bearer <token>`",
			})
			return
		}
		next(writer, request)
	}
}

func refuseFinder(writer http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, markets.ErrBusy):
		writeJSON(writer, http.StatusConflict, problem{
			Error: "busy", Detail: "a discovery cycle is already running; ask again when it has finished",
		})
	case errors.Is(err, discovery.ErrNotTradeable):
		writeJSON(writer, http.StatusConflict, problem{
			Error: "not_tradeable", Detail: err.Error(),
		})
	default:
		var fault *jupiterFault
		if asJupiterFault(err, &fault) {
			status := http.StatusBadGateway
			if fault.Problem == "no_such_market" {
				status = http.StatusNotFound
			}
			writeJSON(writer, status, problem{Error: fault.Problem, Detail: fault.Detail})
			return
		}
		writeJSON(writer, http.StatusBadGateway, problem{
			Error: "provider_unusable", Detail: err.Error(),
		})
	}
}

// jupiterFault is the overlay's reading of a provider fault without importing the provider
// package. The host is allowed in one file; this package is not that file.
type jupiterFault struct {
	Problem string
	Detail  string
}

func asJupiterFault(err error, into **jupiterFault) bool {
	if err == nil {
		return false
	}
	// The provider fault's Error() starts with the stable problem code. This package does not
	// import the provider: the host is allowed in one file, and this is not that file.
	text := err.Error()
	problem, _, _ := strings.Cut(text, ":")
	problem = strings.TrimSpace(problem)
	switch problem {
	case "provider_unreachable", "provider_rate_limited", "no_such_market",
		"provider_refused", "provider_unusable":
		*into = &jupiterFault{Problem: problem, Detail: text}
		return true
	default:
		return false
	}
}

type problem struct {
	Error  string `json:"error"`
	Detail string `json:"detail"`
}

func writeJSON(writer http.ResponseWriter, status int, body any) {
	writer.Header().Set("Content-Type", "application/json; charset=utf-8")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(body)
}
