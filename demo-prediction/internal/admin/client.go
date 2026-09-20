package admin

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// API is a client of the Prediction template's own JSON API. It holds the publisher token and
// never writes it into a response.
type API struct {
	url, token string
	http       *http.Client
}

func newAPI(url, token string, client *http.Client) *API {
	if client == nil {
		client = &http.Client{Timeout: 45 * time.Second}
	}
	return &API{url: strings.TrimRight(url, "/"), token: token, http: client}
}

// Feed is the public reference and the environment the template promises.
type Feed struct {
	Reference   string
	Environment string
}

// Item is one published signal as the admin page shows it. No wallet, amount, side, subscriber or
// result — those fields do not exist on this API.
type Item struct {
	ID          string
	Status      string
	Note        string
	ExpiresAt   string
	Market      string
	Event       string
	Source      string
	Publication string
	Problem     string
	Detail      string
}

// Market is one listing row from the provider, as the search form shows it.
type Market struct {
	MarketID   string
	EventID    string
	Title      string
	EventTitle string
	Category   string
	Tags       string
	State      string
	CloseAt    string
	Published  bool
}

// Query is the operator's listing filters, as the search form posts them.
type Query struct {
	Source            string
	Category          string
	Filter            string
	Keywords          string
	Tags              string
	LeastCloseMinutes string
	MostCloseMinutes  string
	State             string
}

type apiError struct {
	Status int
	Code   string
	Detail string
}

func (e *apiError) Error() string {
	if e.Detail != "" {
		return e.Detail
	}
	if e.Code != "" {
		return e.Code
	}
	return fmt.Sprintf("the Prediction API answered %d", e.Status)
}

func (a *API) feed() (Feed, error) {
	status, body, err := a.call(http.MethodGet, "/v1/manifest", "", nil)
	if err != nil {
		return Feed{}, err
	}
	if status != http.StatusOK {
		return Feed{}, decodeError(status, body)
	}
	var read struct {
		Reference string `json:"reference"`
		Manifest  struct {
			Environments []string `json:"environments"`
		} `json:"manifest"`
	}
	if err := json.Unmarshal(body, &read); err != nil {
		return Feed{}, err
	}
	environment := ""
	if len(read.Manifest.Environments) > 0 {
		environment = read.Manifest.Environments[0]
	}
	return Feed{Reference: read.Reference, Environment: environment}, nil
}

func (a *API) defaults() Query {
	status, body, err := a.call(http.MethodGet, "/v1/discovery", "", nil)
	if err != nil || status != http.StatusOK {
		return Query{}
	}
	var read struct {
		Filters map[string]any `json:"filters"`
	}
	if err := json.Unmarshal(body, &read); err != nil {
		return Query{}
	}
	query := Query{
		Source:   stringOf(read.Filters["source"]),
		Filter:   stringOf(read.Filters["filter"]),
		Keywords: joinAny(read.Filters["keywords"]),
		Tags:     joinAny(read.Filters["tags"]),
		State:    "open",
	}
	categories := joinAny(read.Filters["categories"])
	if i := strings.IndexByte(categories, ','); i > 0 {
		query.Category = strings.TrimSpace(categories[:i])
	} else {
		query.Category = categories
	}
	if query.Filter == "" {
		query.Filter = ""
	}
	return query
}

func (a *API) list() ([]Item, error) {
	status, body, err := a.call(http.MethodGet, "/v1/requests", "", nil)
	if err != nil {
		return nil, err
	}
	if status != http.StatusOK {
		return nil, decodeError(status, body)
	}
	var read struct {
		Requests []json.RawMessage `json:"requests"`
	}
	if err := json.Unmarshal(body, &read); err != nil {
		return nil, err
	}
	items := make([]Item, 0, len(read.Requests))
	for _, raw := range read.Requests {
		item, err := decodeItem(raw)
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, nil
}

func (a *API) search(query Query) ([]Market, error) {
	values := url.Values{}
	set := func(key, value string) {
		if strings.TrimSpace(value) != "" {
			values.Set(key, strings.TrimSpace(value))
		}
	}
	set("source", query.Source)
	set("category", query.Category)
	set("filter", query.Filter)
	set("keywords", query.Keywords)
	set("tags", query.Tags)
	set("least_close_minutes", query.LeastCloseMinutes)
	set("most_close_minutes", query.MostCloseMinutes)
	set("state", query.State)
	path := "/v1/discovery/markets"
	if encoded := values.Encode(); encoded != "" {
		path += "?" + encoded
	}
	status, body, err := a.call(http.MethodGet, path, "", nil)
	if err != nil {
		return nil, err
	}
	if status != http.StatusOK {
		return nil, decodeError(status, body)
	}
	var read struct {
		Markets []struct {
			MarketID   string   `json:"market_id"`
			EventID    string   `json:"event_id"`
			Title      string   `json:"title"`
			EventTitle string   `json:"event_title"`
			Category   string   `json:"category"`
			Tags       []string `json:"tags"`
			State      string   `json:"state"`
			CloseAt    string   `json:"close_at"`
			Published  bool     `json:"published"`
		} `json:"markets"`
	}
	if err := json.Unmarshal(body, &read); err != nil {
		return nil, err
	}
	markets := make([]Market, 0, len(read.Markets))
	for _, one := range read.Markets {
		markets = append(markets, Market{
			MarketID:   one.MarketID,
			EventID:    one.EventID,
			Title:      one.Title,
			EventTitle: one.EventTitle,
			Category:   one.Category,
			Tags:       strings.Join(one.Tags, ", "),
			State:      one.State,
			CloseAt:    one.CloseAt,
			Published:  one.Published,
		})
	}
	return markets, nil
}

func (a *API) selectMarket(id string) (Market, error) {
	payload, err := json.Marshal(map[string]string{"market_id": id})
	if err != nil {
		return Market{}, err
	}
	status, body, err := a.call(http.MethodPost, "/v1/discovery/select", "", payload)
	if err != nil {
		return Market{}, err
	}
	if status < 200 || status > 299 {
		return Market{}, decodeError(status, body)
	}
	var read struct {
		MarketID string `json:"market_id"`
		Title    string `json:"title"`
		State    string `json:"state"`
	}
	if err := json.Unmarshal(body, &read); err != nil {
		return Market{}, err
	}
	return Market{MarketID: read.MarketID, Title: read.Title, State: read.State}, nil
}

func (a *API) retry(id string) (Item, error) {
	if !signals.IsID(id) {
		return Item{}, fmt.Errorf("that is not a signal ID")
	}
	status, body, err := a.call(http.MethodPost, "/v1/requests/"+id+"/retry", "", nil)
	if err != nil {
		return Item{}, err
	}
	if status < 200 || status > 299 {
		return Item{}, decodeError(status, body)
	}
	return decodeItem(body)
}

func (a *API) call(method, path, key string, body []byte) (int, []byte, error) {
	var payload io.Reader
	if body != nil {
		payload = bytes.NewReader(body)
	}
	request, err := http.NewRequest(method, a.url+path, payload)
	if err != nil {
		return 0, nil, err
	}
	request.Header.Set("Authorization", "Bearer "+a.token)
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	if key != "" {
		request.Header.Set("Idempotency-Key", key)
	}
	answer, err := a.http.Do(request)
	if err != nil {
		return 0, nil, fmt.Errorf("the Prediction API is not answering: %w", err)
	}
	defer func() { _ = answer.Body.Close() }()
	contents, err := io.ReadAll(io.LimitReader(answer.Body, 1<<20))
	if err != nil {
		return answer.StatusCode, nil, err
	}
	return answer.StatusCode, contents, nil
}

func decodeError(status int, body []byte) error {
	var read struct {
		Error  string `json:"error"`
		Detail string `json:"detail"`
		Term   string `json:"term"`
	}
	_ = json.Unmarshal(body, &read)
	detail := read.Detail
	if read.Term != "" && detail != "" {
		detail = read.Term + ": " + detail
	}
	return &apiError{Status: status, Code: read.Error, Detail: detail}
}

func decodeItem(raw []byte) (Item, error) {
	var envelope struct {
		Request     json.RawMessage `json:"request"`
		Publication struct {
			State   string `json:"state"`
			Problem string `json:"problem"`
			Detail  string `json:"detail"`
		} `json:"publication"`
	}
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return Item{}, err
	}
	document := envelope.Request
	if len(document) == 0 {
		document = raw
	}
	var request struct {
		Identity struct {
			RequestID string `json:"request_id"`
		} `json:"identity"`
		Lifecycle struct {
			Status    string `json:"status"`
			ExpiresAt string `json:"expires_at"`
		} `json:"lifecycle"`
		Presentation struct {
			Description string `json:"description"`
		} `json:"presentation"`
		Action struct {
			Parameters []struct {
				Key  string `json:"key"`
				Text string `json:"text"`
			} `json:"parameters"`
		} `json:"action"`
	}
	if err := json.Unmarshal(document, &request); err != nil {
		return Item{}, err
	}
	terms := map[string]string{}
	for _, parameter := range request.Action.Parameters {
		terms[parameter.Key] = parameter.Text
	}
	status := strings.TrimPrefix(request.Lifecycle.Status, "REQUEST_STATUS_")
	status = strings.ToLower(status)
	return Item{
		ID:          request.Identity.RequestID,
		Status:      status,
		Note:        request.Presentation.Description,
		ExpiresAt:   request.Lifecycle.ExpiresAt,
		Market:      terms[signals.MarketID],
		Event:       terms[signals.EventID],
		Source:      terms[signals.SourceProvider],
		Publication: envelope.Publication.State,
		Problem:     envelope.Publication.Problem,
		Detail:      envelope.Publication.Detail,
	}, nil
}

func stringOf(value any) string {
	text, _ := value.(string)
	return text
}

func joinAny(value any) string {
	switch held := value.(type) {
	case []any:
		parts := make([]string, 0, len(held))
		for _, one := range held {
			if text, ok := one.(string); ok && text != "" {
				parts = append(parts, text)
			}
		}
		return strings.Join(parts, ",")
	case []string:
		return strings.Join(held, ",")
	case string:
		return held
	default:
		return ""
	}
}
