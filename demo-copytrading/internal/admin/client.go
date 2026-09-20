package admin

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/ids"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// API is a client of the CopyTrading template's own JSON API. It holds the publisher token and
// never writes it into a response.
type API struct {
	url, token string
	http       *http.Client
}

func newAPI(url, token string, client *http.Client) *API {
	if client == nil {
		client = &http.Client{Timeout: 15 * time.Second}
	}
	return &API{url: strings.TrimRight(url, "/"), token: token, http: client}
}

// Feed is the public reference and the environment the template promises.
type Feed struct {
	Reference   string
	Environment string
}

// Item is one signal as the trader page shows it: identity, the pair, publication state. No
// wallet, amount, subscriber or result — those fields do not exist on this API.
type Item struct {
	ID          string
	Status      string
	Note        string
	ExpiresAt   string
	Pair        string
	Slippage    string
	Publication string
	Problem     string
	Detail      string
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
	return fmt.Sprintf("the CopyTrading API answered %d", e.Status)
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

type createInput struct {
	Pair      string
	Slippage  string
	ExpiresIn string
	Note      string
}

func (a *API) create(asked createInput) (Item, error) {
	terms, err := pairTerms(asked.Pair)
	if err != nil {
		return Item{}, err
	}
	slippage := strings.TrimSpace(asked.Slippage)
	if slippage == "" {
		slippage = "50"
	}
	terms[signals.MaxSlippageBps] = slippage
	expires, err := expiryOf(asked.ExpiresIn, time.Now())
	if err != nil {
		return Item{}, err
	}
	payload, err := json.Marshal(map[string]any{
		"expires_at": expires,
		"note":       asked.Note,
		"terms":      terms,
	})
	if err != nil {
		return Item{}, err
	}
	status, body, err := a.call(http.MethodPost, "/v1/requests", ids.New(), payload)
	if err != nil {
		return Item{}, err
	}
	if status < 200 || status > 299 {
		return Item{}, decodeError(status, body)
	}
	return decodeItem(body)
}

func (a *API) cancel(id string) (Item, error) {
	return a.mutate(id, "cancel")
}

func (a *API) retry(id string) (Item, error) {
	return a.mutate(id, "retry")
}

func (a *API) mutate(id, action string) (Item, error) {
	if !signals.IsID(id) {
		return Item{}, fmt.Errorf("that is not a signal ID")
	}
	status, body, err := a.call(http.MethodPost, "/v1/requests/"+id+"/"+action, "", nil)
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
		return 0, nil, fmt.Errorf("the CopyTrading API is not answering: %w", err)
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
	in := labelOf(terms, signals.InputSymbol, signals.InputMint)
	out := labelOf(terms, signals.OutputSymbol, signals.OutputMint)
	pair := in + " → " + out
	if in == "" && out == "" {
		pair = ""
	}
	return Item{
		ID:          request.Identity.RequestID,
		Status:      status,
		Note:        request.Presentation.Description,
		ExpiresAt:   request.Lifecycle.ExpiresAt,
		Pair:        pair,
		Slippage:    terms[signals.MaxSlippageBps],
		Publication: envelope.Publication.State,
		Problem:     envelope.Publication.Problem,
		Detail:      envelope.Publication.Detail,
	}, nil
}

func labelOf(terms map[string]string, symbol, mint string) string {
	if terms[symbol] != "" {
		return terms[symbol]
	}
	value := terms[mint]
	if len(value) > 8 {
		return value[:4] + "…" + value[len(value)-4:]
	}
	return value
}

const (
	usdcMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
	pairUSDC = "usdc-sol"
	pairSOL  = "sol-usdc"
)

func pairTerms(pair string) (map[string]string, error) {
	switch pair {
	case pairUSDC, "":
		return map[string]string{
			signals.InputMint:      usdcMint,
			signals.InputDecimals:  "6",
			signals.InputSymbol:    "USDC",
			signals.OutputMint:     signals.WrappedSOL,
			signals.OutputDecimals: "9",
			signals.OutputSymbol:   "SOL",
		}, nil
	case pairSOL:
		return map[string]string{
			signals.InputMint:      signals.WrappedSOL,
			signals.InputDecimals:  "9",
			signals.InputSymbol:    "SOL",
			signals.OutputMint:     usdcMint,
			signals.OutputDecimals: "6",
			signals.OutputSymbol:   "USDC",
		}, nil
	default:
		return nil, fmt.Errorf("choose USDC → SOL or SOL → USDC")
	}
}

func expiryOf(named string, now time.Time) (string, error) {
	var wait time.Duration
	switch named {
	case "15m":
		wait = 15 * time.Minute
	case "1h", "":
		wait = time.Hour
	case "2h":
		wait = 2 * time.Hour
	case "4h":
		wait = 4 * time.Hour
	default:
		return "", fmt.Errorf("choose how long the signal stays actionable")
	}
	return now.UTC().Add(wait).Truncate(time.Second).Format(time.RFC3339), nil
}
