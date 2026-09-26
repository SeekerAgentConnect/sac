// Package publisherctl is the operator's client of a demo's own business API (SEE-95, SEE-134,
// docs/integrations/signal-api.md).
//
// It is a client and nothing else: every command here is one HTTP call that any other program
// could make, which is the point of it. A strategy engine, a cron job or a person with `curl` all
// reach the template the same way, through the same validation, the same idempotency and the same
// one path to the gateway — so this tool is a worked example as much as it is a convenience, and
// there is no privileged back door into the store for it to use.
//
//	publishctl status
//	publishctl reference
//	publishctl create --in 2h --note "trimming SOL into USDC" \
//	                  --term input_mint=So11111111111111111111111111111111111111112 \
//	                  --term input_decimals=9 \
//	                  --term output_mint=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v \
//	                  --term output_decimals=6 \
//	                  --term max_slippage_bps=50
//	publishctl update <id> --in 1h --term …
//	publishctl cancel <id>
//	publishctl retry  <id>
//	publishctl list
//	publishctl show   <id>
//	publishctl discovery            # the Prediction template: what it looks for and what it found
//	publishctl poll                 # …and a cycle now, rather than at the next interval
//
// The template's address is --url or PUBLISHER_API_URL; the token is --token or
// PUBLISHER_API_TOKEN, which is the same token the template was started with. The answer is JSON
// on stdout, so it can be piped; what a person reads is on stderr.
// It is a library with each demo's `cmd/publishctl` as a three-line main, rather than one binary
// in one demo, because both demos answer the same API and neither may import the other: two copies
// of this would be two subtly different clients of one contract. It ships no command of its own —
// this library is not deployable, and a demo's own module is what builds and images the tool
// (packages/publisher-support/go.mod).
package publisherctl

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/ids"
)

// Main is the whole of a demo's `publishctl` command: read the arguments, print the answer on
// stdout and whatever a person reads on stderr, and exit non-zero on a refusal.
func Main() {
	if err := Run(os.Args[1:], os.Stdout, os.Stderr); err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		os.Exit(1)
	}
}

const usage = `publishctl publishes signals through a publisher template's API.

  status                what this publisher is, and whether anything is unpublished
  reference             the seekervault://feed reference a phone adds this feed from
  create                publish a new signal
  update <id>           replace a signal's whole statement
  cancel <id>           withdraw one
  retry  <id>           try a refused publication again
  list                  every signal this template holds
  show   <id>           one signal, and what the gateway has confirmed

On a template that discovers its own signals (the Prediction one, SEE-96):
  discovery             the filters in force, the last cycle, and every market it tracks
  poll                  run a discovery cycle now and publish what it finds

  --url <origin>        the template's API, or PUBLISHER_API_URL (default http://127.0.0.1:8092)
  --token <token>       its API token, or PUBLISHER_API_TOKEN

For create and update:
  --expires <RFC3339>   when it stops being actionable, absolute
  --in <duration>       the same instant, as "2h" from now: this tool works out the instant,
                        because the API takes nothing but an absolute one
  --note <text>         the publisher's own words, shown to the owner as theirs
  --term key=value      one of the operation's terms; repeat it
  --terms-file <path>   a JSON object of terms, for a program that already has one
  --key <key>           the idempotency key (create only). Left out, one is minted and printed:
                        pass the same one to retry the identical create safely
`

// Run is one invocation, with its streams injected so a test can read what it sent.
func Run(arguments []string, out, messages io.Writer) error {
	if len(arguments) == 0 {
		fmt.Fprint(messages, usage)
		return fmt.Errorf("name a command")
	}
	command, arguments := arguments[0], arguments[1:]

	flags := flag.NewFlagSet("publishctl "+command, flag.ContinueOnError)
	flags.SetOutput(messages)
	address := flags.String("url", os.Getenv("PUBLISHER_API_URL"), "the template's API")
	token := flags.String("token", os.Getenv("PUBLISHER_API_TOKEN"), "its API token")
	expires := flags.String("expires", "", "an absolute RFC 3339 instant")
	within := flags.Duration("in", 0, "the same instant, as a duration from now")
	note := flags.String("note", "", "the publisher's own words")
	key := flags.String("key", "", "the idempotency key for a create")
	termsFile := flags.String("terms-file", "", "a JSON object of terms")
	var terms termList
	flags.Var(&terms, "term", "one term, as key=value; repeat it")

	// Flags and the signal ID may come in either order, because `publishctl cancel <id> --token x`
	// is how a person types it and Go's flag package stops at the first argument that is not a
	// flag. Parsing in a loop, taking one plain argument each time round, is the documented way to
	// allow both.
	var named []string
	rest := arguments
	for {
		if err := flags.Parse(rest); err != nil {
			return err
		}
		if flags.NArg() == 0 {
			break
		}
		named = append(named, flags.Arg(0))
		rest = flags.Args()[1:]
	}
	if *address == "" {
		*address = "http://127.0.0.1:8092"
	}
	*address = strings.TrimRight(*address, "/")
	if strings.TrimSpace(*token) == "" {
		return fmt.Errorf("--token, or PUBLISHER_API_TOKEN, must be the token this template " +
			"was started with")
	}

	identity := func() (string, error) {
		if len(named) != 1 {
			return "", fmt.Errorf("%s takes one signal ID", command)
		}
		return named[0], nil
	}

	client := &caller{address: *address, token: *token, out: out, messages: messages}

	switch command {
	case "status":
		return client.call(http.MethodGet, "/v1/status", "", nil)

	case "reference":
		// One line, on stdout, and nothing else: it is meant to be copied into a README, a QR
		// code, or a message to whoever is subscribing.
		return client.reference()

	case "create", "update":
		statement, err := statementOf(*expires, *within, *note, terms, *termsFile)
		if err != nil {
			return err
		}
		if command == "update" {
			id, err := identity()
			if err != nil {
				return err
			}
			return client.call(http.MethodPut, "/v1/requests/"+id, "", statement)
		}
		created := *key
		if created == "" {
			// A create with no key is a create that cannot be retried safely, so one is minted
			// rather than left out — and printed, because the caller needs it to retry.
			created = ids.New()
			fmt.Fprintf(messages, "idempotency key %s (pass --key %s to retry this exact create)\n",
				created, created)
		}
		return client.call(http.MethodPost, "/v1/requests", created, statement)

	case "cancel", "retry":
		id, err := identity()
		if err != nil {
			return err
		}
		return client.call(http.MethodPost, "/v1/requests/"+id+"/"+command, "", nil)

	case "list":
		return client.call(http.MethodGet, "/v1/requests", "", nil)

	case "discovery":
		// Only a template that discovers its own signals has this; the other answers 404 with
		// `no_such_route`, which is the honest answer to asking a CopyTrading publisher what it is
		// looking for (packages/publisher-support/api).
		return client.call(http.MethodGet, "/v1/discovery", "", nil)

	case "poll":
		return client.call(http.MethodPost, "/v1/discovery/poll", "", nil)

	case "show":
		id, err := identity()
		if err != nil {
			return err
		}
		return client.call(http.MethodGet, "/v1/requests/"+id, "", nil)

	default:
		fmt.Fprint(messages, usage)
		return fmt.Errorf("unknown command %q", command)
	}
}

// statement is the body a create or an update sends, which is the whole of what a caller may say.
type statement struct {
	ExpiresAt string            `json:"expires_at"`
	Note      string            `json:"note"`
	Terms     map[string]string `json:"terms"`
}

func statementOf(expires string, within time.Duration, note string, terms termList,
	termsFile string) (*statement, error) {
	said := &statement{Note: note, Terms: map[string]string{}}
	switch {
	case expires != "" && within != 0:
		return nil, fmt.Errorf("--expires and --in say the same thing; pass one")
	case expires != "":
		if _, err := time.Parse(time.RFC3339, expires); err != nil {
			return nil, fmt.Errorf("--expires must be an RFC 3339 instant, " +
				"for example 2026-09-17T21:00:00Z")
		}
		said.ExpiresAt = expires
	case within > 0:
		said.ExpiresAt = time.Now().UTC().Add(within).Truncate(time.Second).Format(time.RFC3339)
	default:
		return nil, fmt.Errorf("pass --expires <RFC3339> or --in <duration>: a signal says " +
			"until when it is actionable, and it says it absolutely")
	}
	if termsFile != "" {
		contents, err := os.ReadFile(termsFile)
		if err != nil {
			return nil, err
		}
		if err := json.Unmarshal(contents, &said.Terms); err != nil {
			return nil, fmt.Errorf("%s must be a JSON object of text values: %w", termsFile, err)
		}
	}
	for name, value := range terms {
		said.Terms[name] = value
	}
	if len(said.Terms) == 0 {
		return nil, fmt.Errorf("pass --term key=value for the operation's terms, " +
			"or --terms-file <path>")
	}
	return said, nil
}

// termList collects repeated --term key=value flags.
type termList map[string]string

func (t termList) String() string { return "" }

func (t *termList) Set(value string) error {
	name, text, found := strings.Cut(value, "=")
	if !found || strings.TrimSpace(name) == "" {
		return errors.New("a term is key=value")
	}
	if *t == nil {
		*t = termList{}
	}
	(*t)[strings.TrimSpace(name)] = text
	return nil
}

// caller is one HTTP call to the template, and the shape of every command here.
type caller struct {
	address  string
	token    string
	out      io.Writer
	messages io.Writer
}

func (c *caller) call(method, path, key string, body any) error {
	var payload io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			return err
		}
		payload = bytes.NewReader(encoded)
	}
	request, err := http.NewRequest(method, c.address+path, payload)
	if err != nil {
		return err
	}
	request.Header.Set("Authorization", "Bearer "+c.token)
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	if key != "" {
		request.Header.Set("Idempotency-Key", key)
	}
	answer, err := (&http.Client{Timeout: 30 * time.Second}).Do(request)
	if err != nil {
		return fmt.Errorf("%s is not answering: %w", c.address, err)
	}
	defer func() { _ = answer.Body.Close() }()
	contents, err := io.ReadAll(io.LimitReader(answer.Body, 1<<20))
	if err != nil {
		return err
	}
	// The answer as it came, on stdout, so a script can read it with anything.
	fmt.Fprintln(c.out, strings.TrimRight(string(contents), "\n"))
	summarize(c.messages, answer.StatusCode, contents)
	if answer.StatusCode < 200 || answer.StatusCode > 299 {
		return fmt.Errorf("the template answered %s", answer.Status)
	}
	return nil
}

// reference prints the feed reference alone, which is the one string an operator has to pass on.
func (c *caller) reference() error {
	request, err := http.NewRequest(http.MethodGet, c.address+"/v1/manifest", nil)
	if err != nil {
		return err
	}
	request.Header.Set("Authorization", "Bearer "+c.token)
	answer, err := (&http.Client{Timeout: 30 * time.Second}).Do(request)
	if err != nil {
		return fmt.Errorf("%s is not answering: %w", c.address, err)
	}
	defer func() { _ = answer.Body.Close() }()
	contents, err := io.ReadAll(io.LimitReader(answer.Body, 1<<20))
	if err != nil {
		return err
	}
	if answer.StatusCode != http.StatusOK {
		fmt.Fprintln(c.out, strings.TrimRight(string(contents), "\n"))
		return fmt.Errorf("the template answered %s", answer.Status)
	}
	var read struct {
		Reference string `json:"reference"`
	}
	if err := json.Unmarshal(contents, &read); err != nil {
		return err
	}
	fmt.Fprintln(c.out, read.Reference)
	return nil
}

// summarize is the line a person reads: what happened to the signal, and what state its
// publication is in. It is on stderr so that stdout stays the answer itself.
func summarize(to io.Writer, status int, contents []byte) {
	var read struct {
		Signal struct {
			ProposalID string `json:"proposal_id"`
			Revision   string `json:"revision"`
			Status     string `json:"status"`
		} `json:"signal"`
		Publication struct {
			State   string `json:"state"`
			Problem string `json:"problem"`
			Detail  string `json:"detail"`
		} `json:"publication"`
		Error  string `json:"error"`
		Term   string `json:"term"`
		Detail string `json:"detail"`
	}
	if err := json.Unmarshal(contents, &read); err != nil {
		return
	}
	switch {
	case read.Error != "":
		line := read.Error
		if read.Term != "" {
			line += " (" + read.Term + ")"
		}
		fmt.Fprintf(to, "%s: %s\n", line, read.Detail)
	case read.Signal.ProposalID != "":
		fmt.Fprintf(to, "signal %s revision %s %s, publication %s\n",
			read.Signal.ProposalID, read.Signal.Revision, read.Signal.Status,
			read.Publication.State)
		if read.Publication.Problem != "" {
			fmt.Fprintf(to, "  the gateway refused it: %s - %s\n",
				read.Publication.Problem, read.Publication.Detail)
		} else if read.Publication.Detail != "" {
			fmt.Fprintf(to, "  %s\n", read.Publication.Detail)
		}
	}
	if status == http.StatusAccepted {
		fmt.Fprintln(to, "  it is stored here and will be published when the gateway answers")
	}
}
