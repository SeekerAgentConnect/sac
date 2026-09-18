// Package sdk is the developer-facing Server SDK. Client writes common public-feed requests to a
// publisher template; Gateway creates private invitations and routes common requests through the
// shared gateway. Neither surface knows a wallet, approval, store, or plugin implementation.
package sdk

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type Options struct {
	URL   string
	Token string
	HTTP  *http.Client
}

type Client struct {
	url, token string
	http       *http.Client
}

func New(options Options) (*Client, error) {
	if strings.TrimSpace(options.URL) == "" || strings.TrimSpace(options.Token) == "" {
		return nil, errors.New("publisher sdk: URL and token are required")
	}
	client := options.HTTP
	if client == nil {
		client = &http.Client{Timeout: 10 * time.Second}
	}
	return &Client{url: strings.TrimRight(options.URL, "/"), token: options.Token, http: client}, nil
}

// CreateRequest is the source-authored portion a feed publisher accepts. The template supplies
// its registered action capability and feed audience; owner answers never cross this API.
type CreateRequest struct {
	IdempotencyKey string
	ExpiresAt      time.Time
	Description    string
	Parameters     map[string]string
}

type CreateResponse struct {
	Request     json.RawMessage `json:"request"`
	Publication json.RawMessage `json:"publication"`
	Idempotent  bool            `json:"idempotent"`
}

type Error struct {
	Status int
	Body   string
}

func (e *Error) Error() string { return fmt.Sprintf("publisher API answered %d: %s", e.Status, e.Body) }

// CreateRequest publishes one request through POST /v1/requests. Retrying the same key and body is
// idempotent; using the key for another body is refused by the template.
func (c *Client) CreateRequest(ctx context.Context, asked CreateRequest) (*CreateResponse, error) {
	if strings.TrimSpace(asked.IdempotencyKey) == "" || asked.ExpiresAt.IsZero() || asked.Parameters == nil {
		return nil, errors.New("publisher sdk: idempotency key, expiry and parameters are required")
	}
	body, err := json.Marshal(map[string]any{
		"expires_at": asked.ExpiresAt.UTC().Format(time.RFC3339),
		"note":       asked.Description,
		"terms":      asked.Parameters,
	})
	if err != nil {
		return nil, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, c.url+"/v1/requests", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Authorization", "Bearer "+c.token)
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Idempotency-Key", asked.IdempotencyKey)
	response, err := c.http.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	answer, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return nil, &Error{Status: response.StatusCode, Body: strings.TrimSpace(string(answer))}
	}
	var created CreateResponse
	if err := json.Unmarshal(answer, &created); err != nil {
		return nil, fmt.Errorf("publisher sdk: decode create response: %w", err)
	}
	return &created, nil
}
