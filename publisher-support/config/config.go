// Package config reads a publisher template's settings from the environment (SEE-95).
//
// It follows the rules the sidecar and the feed gateway follow, because an operator running
// any two of them should not have to learn two habits: every setting is one environment variable,
// a problem names the variable rather than guessing a value, and every problem is reported at once
// so a first start is fixed in one pass. Nothing here has a default that opens something — the API
// binds loopback unless it is told otherwise, and the five settings a deployment cannot get wrong
// have no default at all.
//
// # The two credentials, and why they are different things
//
// A template holds two, and mixing them up is the mistake worth designing against:
//
//   - `BROADCAST_CREDENTIAL` is the grant the **gateway** issued this publisher (`feed-gatewayctl
//     register` prints it once). It says which server this template publishes as, and the gateway
//     checks every document against it rather than against what the document claims.
//   - `PUBLISHER_API_TOKEN` is who may call **this** template. It is the whole of the grant to
//     publish through it, so a strategy engine that holds it can say anything this publisher can
//     say.
//
// Both have to be usable rather than compared, so both are configuration — and both may instead be
// a path (`…_FILE`) for a deployment that mounts secrets as files, which is what SEE-92 does with
// the gateway's push credential. Neither is ever logged, and a refusal never quotes one.
package config

import (
	"fmt"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/environment"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// Config is the validated deployment.
type Config struct {
	// The publisher's lasting ID: a lowercase UUID, and the one an operator registered with
	// `feed-gatewayctl register --server`. Its channel is "server/<this>", and it is the identity in
	// the manifest and in every proposal.
	ServerID string
	// The shared gateway's canonical origin. Every manifest this template publishes has to name
	// it, and the phone compares it with the feed reference the feed was added from, so it must be
	// the origin that gateway publishes as (its own BROADCAST_PUBLIC_URL).
	GatewayURL string
	// Where this template *sends* its documents: the gateway's publisher API.
	//
	// It is usually [Config.GatewayURL] — the deployment the gateway ships with puts both APIs
	// behind one proxy on one origin — and it is its own setting because the two are not the same
	// question. The manifest names where *phones read*, which is public and compared character for
	// character. This is where a publication *goes*, which may be a private address on a tunnel,
	// on a VPN, or the gateway's own second port on a machine running both: an operator who keeps
	// publishing off the internet deletes the proxy route and points this at it (feed-gateway/
	// Caddyfile).
	//
	// Getting it wrong is the one mistake with no error message on the gateway's side: a read
	// origin answers 404 to a publication, because that listener has no handler that could write
	// anything.
	PublishURL string
	// The credential the gateway issued this publisher.
	Credential string
	// Which promise this deployment's signals carry when an owner approves one (SEE-97). It is in
	// the manifest, it is stamped into the database, and it is in every answer the API gives,
	// because the way this gets confused is a copied compose file.
	Environment environment.Environment
	// The SQLite file: the signals this template holds, and what the gateway has confirmed about
	// each of them.
	DatabasePath string
	// Where this template's own API listens. Loopback by default: the token on it is the whole
	// grant to publish, so reaching it should be a deliberate act of the deployment
	// (docs/development/demos.md).
	APIAddress string
	// The token a caller of that API presents.
	APIToken string
	// The name this server calls itself, for a connection's default label. Optional, and never
	// verified: the owner can rename any connection.
	DisplayName string
	// How long one publication to the gateway may take before it is treated as unreachable and
	// retried.
	PublishTimeout time.Duration
	// The most new creates this process will accept in one hour. Zero (the default) is unlimited,
	// so existing tests and deployments keep their previous behaviour. The CopyTrading demo sets
	// a cap so a public trader UI cannot fill the store (SEE-126).
	CreateLimit int
}

// Defaults every setting that has one.
const (
	// One past the gateway's own two, so a laptop running all three needs no configuration at all.
	DefaultAPIAddress     = "127.0.0.1:8092"
	DefaultPublishTimeout = 10 * time.Second
	// The API token is the whole grant to publish through this template, so there is a floor under
	// it. It is the length of what `feed-gatewayctl` mints (32 random bytes as 43 base64url
	// characters), which is also what `openssl rand -base64 32` gives.
	LeastTokenLength = 32
)

// Lookup is os.LookupEnv, injected so the tests configure a template without touching the process.
type Lookup func(name string) (string, bool)

// Load reads the environment and returns the configuration, or every problem it found.
//
// A caller that gets problems prints all of them and stops. A template that started with half a
// configuration would either publish as the wrong server or serve an API with no token on it, and
// both are worse than not starting.
func Load(lookup Lookup) (*Config, []string) {
	read := NewReader(lookup)
	note, text, secret := read.Note, read.Text, read.Secret

	config := &Config{
		ServerID:       text("PUBLISHER_SERVER_ID", ""),
		DatabasePath:   text("PUBLISHER_DATABASE_PATH", ""),
		APIAddress:     text("PUBLISHER_API_ADDRESS", DefaultAPIAddress),
		DisplayName:    text("PUBLISHER_DISPLAY_NAME", ""),
		PublishTimeout: DefaultPublishTimeout,
	}

	if !signals.IsID(config.ServerID) {
		note("PUBLISHER_SERVER_ID must be the lowercase UUID this publisher was registered " +
			"with, for example 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d: it is the identity in " +
			"the manifest and in every proposal, and the gateway checks it against the " +
			"credential")
	}
	switch gateway := text("PUBLISHER_GATEWAY_URL", ""); {
	case gateway == "":
		note("PUBLISHER_GATEWAY_URL must be set to the shared gateway's origin, for example " +
			"https://feeds.example.com - it is the gateway's own BROADCAST_PUBLIC_URL, and " +
			"every manifest published to it has to name it exactly")
	default:
		canonical, err := Origin(gateway)
		if err != nil {
			note("PUBLISHER_GATEWAY_URL %v", err)
		} else {
			config.GatewayURL = canonical
		}
	}
	// Where publications go. Empty means the same origin phones read from, which is what the
	// gateway's own deployment serves both APIs on.
	switch publish := text("PUBLISHER_PUBLISH_URL", ""); {
	case publish == "":
		config.PublishURL = config.GatewayURL
	default:
		canonical, err := Reachable(publish)
		if err != nil {
			note("PUBLISHER_PUBLISH_URL %v", err)
		} else {
			config.PublishURL = canonical
		}
	}
	if config.DatabasePath == "" {
		note("PUBLISHER_DATABASE_PATH must be set to the file this template keeps its signals in, " +
			"for example /data/publisher.db")
	}
	// The one setting with no default at all: a deployment that did not say which promise it
	// keeps does not start, because the alternative is a demonstration that quietly spends money.
	if named, ok := environment.Parse(strings.ToLower(text("PUBLISHER_ENVIRONMENT", ""))); ok {
		config.Environment = named
	} else {
		note("PUBLISHER_ENVIRONMENT must be production or sandbox: it is what a phone is told " +
			"this server promises when its owner approves a signal, and one deployment serves " +
			"one of them")
	}
	// The phone's own rule for a server's name, which is a label on one line rather than prose.
	if config.DisplayName != "" &&
		!signals.Printable(config.DisplayName, signals.MaxNameBytes, false) {
		note("PUBLISHER_DISPLAY_NAME must be at most 64 bytes of printable text on one line: " +
			"it is the connection's default label, and a phone refuses a manifest whose name it " +
			"cannot show")
	}

	config.Credential = secret("BROADCAST_CREDENTIAL")
	switch {
	case config.Credential == "":
		note("BROADCAST_CREDENTIAL must be set to the credential the gateway's operator issued " +
			"this publisher (`feed-gatewayctl register --server <uuid>` prints it once), or " +
			"BROADCAST_CREDENTIAL_FILE to the path of a file holding it")
	case !HeaderSafe(config.Credential):
		// It is sent as `Authorization: Bearer <credential>`, so whitespace in it is not a typo to
		// forgive: it is a request header with a value nobody wrote.
		note("BROADCAST_CREDENTIAL must be one word with no whitespace or control characters: " +
			"it is sent as a bearer credential")
	}

	config.APIToken = secret("PUBLISHER_API_TOKEN")
	switch {
	case config.APIToken == "":
		note("PUBLISHER_API_TOKEN must be set to the token a caller of this template's API " +
			"presents (`openssl rand -base64 32`), or PUBLISHER_API_TOKEN_FILE to the path of " +
			"a file holding it: holding it is the whole of the grant to publish through this " +
			"template, and there is no way to run the API without one")
	case !HeaderSafe(config.APIToken):
		note("PUBLISHER_API_TOKEN must be one word with no whitespace or control characters")
	case len(config.APIToken) < LeastTokenLength:
		note("PUBLISHER_API_TOKEN must be at least %d characters: it is the whole grant to "+
			"publish through this template", LeastTokenLength)
	}

	if raw := text("PUBLISHER_PUBLISH_TIMEOUT_SECONDS", ""); raw != "" {
		seconds, err := strconv.Atoi(raw)
		if err != nil || seconds < 1 || seconds > 120 {
			note("PUBLISHER_PUBLISH_TIMEOUT_SECONDS must be a whole number of seconds " +
				"between 1 and 120")
		} else {
			config.PublishTimeout = time.Duration(seconds) * time.Second
		}
	}

	config.CreateLimit = read.Whole("PUBLISHER_CREATE_LIMIT", 0, 0, 1_000_000,
		"the most new signals this process will accept in one hour; 0 is unlimited")

	if len(read.Problems()) > 0 {
		return nil, read.Problems()
	}
	return config, nil
}

// Reader is the environment, as every setting is read from it: one variable per setting, a problem
// that names the variable rather than guessing a value, and every problem collected so that a first
// start is fixed in one pass.
//
// It is a type rather than a closure, and it is exported, because there are two loaders — this
// one, and the Prediction demo's own half (demo-prediction/internal/config) — and a secret read
// two slightly different ways would be a deployment's worst kind of surprise. A demo that adds
// settings of its own reads them through this, so that a missing variable, a `_FILE` secret and a
// number out of range are reported identically wherever they are configured.
type Reader struct {
	lookup   Lookup
	problems []string
}

// NewReader is a reader over one environment.
func NewReader(lookup Lookup) *Reader { return &Reader{lookup: lookup} }

// Problems is every problem noted so far, in the order they were found. A loader that reads more
// settings of its own appends these to its own, so that a first start is fixed in one pass rather
// than one variable per restart.
func (r *Reader) Problems() []string { return r.problems }

func (r *Reader) Note(format string, argument ...any) {
	r.problems = append(r.problems, fmt.Sprintf(format, argument...))
}

func (r *Reader) Text(name, fallback string) string {
	value, set := r.lookup(name)
	if !set || strings.TrimSpace(value) == "" {
		return fallback
	}
	return strings.TrimSpace(value)
}

// secret is either a value or the path of a file holding one, never both: a deployment that set
// both would have two answers and no way to tell which one was used.
func (r *Reader) Secret(name string) string {
	value := r.Text(name, "")
	path := r.Text(name+"_FILE", "")
	switch {
	case value != "" && path != "":
		r.Note("%s and %s_FILE must not both be set: name the secret or the file it is in, "+
			"not both", name, name)
		return ""
	case path != "":
		contents, err := os.ReadFile(path)
		if err != nil {
			// The error names the path and never any part of the contents.
			r.Note("%s_FILE %s cannot be read: %v", name, path, pathError(err))
			return ""
		}
		return strings.TrimSpace(string(contents))
	default:
		return value
	}
}

// whole reads a setting that is a whole number in a range, with a default for the deployments that
// have no opinion about it. The message says the range, because a number outside it is almost
// always somebody reading a different unit than the one the variable is in.
func (r *Reader) Whole(name string, fallback, least, most int, about string) int {
	raw := r.Text(name, "")
	if raw == "" {
		return fallback
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < least || value > most {
		r.Note("%s must be a whole number between %d and %d: %s", name, least, most, about)
		return fallback
	}
	return value
}

// list reads a comma-separated setting: each item trimmed, empties dropped, and the order kept.
func (r *Reader) List(name string) []string {
	raw := r.Text(name, "")
	if raw == "" {
		return nil
	}
	items := []string{}
	for _, one := range strings.Split(raw, ",") {
		if trimmed := strings.TrimSpace(one); trimmed != "" {
			items = append(items, trimmed)
		}
	}
	return items
}

var loopback = map[string]bool{"127.0.0.1": true, "localhost": true, "::1": true}

// Origin is the canonical form of the gateway's origin, by the same rules the gateway canonicalizes
// its own (config.Origin) and the phone reads a feed reference by (FeedReferences): HTTPS, or plain
// HTTP on a loopback host for development; a lowercase scheme and host; no default port; and no
// path, query, fragment or user information at all.
//
// It matters that this is the same function on both sides. The phone compares the manifest's
// gateway URL with the reference it added the feed from character for character, so
// `https://Feeds.Example.com:443/` and `https://feeds.example.com` have to be one string before
// either side publishes anything — and a URL with a path is a second gateway to the phone and one
// gateway to the operator who deployed it.
func Origin(raw string) (string, error) {
	parsed, err := url.Parse(raw)
	if err != nil {
		return "", fmt.Errorf("is not a URL: %w", err)
	}
	scheme := strings.ToLower(parsed.Scheme)
	host := strings.ToLower(parsed.Hostname())
	switch {
	case host == "":
		return "", fmt.Errorf("must name a host")
	case parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "":
		return "", fmt.Errorf("must have no user information, query or fragment")
	case strings.Trim(parsed.Path, "/") != "":
		return "", fmt.Errorf("must have no path: a gateway is addressed by origin")
	case scheme == "https":
	case scheme == "http" && loopback[host]:
	default:
		return "", fmt.Errorf("must use HTTPS; plain HTTP is allowed only on loopback, " +
			"for development")
	}
	port := parsed.Port()
	if (scheme == "https" && port == "443") || (scheme == "http" && port == "80") {
		port = ""
	}
	if port != "" {
		if number, err := strconv.Atoi(port); err != nil || number < 1 || number > 65535 {
			return "", fmt.Errorf("has a port that is not a port")
		}
	}
	written := host
	if strings.Contains(host, ":") {
		written = "[" + host + "]"
	}
	if port != "" {
		written += ":" + port
	}
	return scheme + "://" + written, nil
}

// Reachable is the canonical form of an address this template calls, rather than one anybody
// calls it by. It is a different check from [Origin] on purpose, and the difference is who the
// address is for.
//
// A gateway origin is a public promise: a phone compares it with the feed reference it added the
// feed from, so plain HTTP is allowed only on loopback and there is no room for a path. A publisher
// API address is a link inside a deployment — a compose service name, a private host, a port on a
// tunnel — that no phone ever sees and nothing compares. So any host may be named over plain HTTP
// here, and the reason to say so out loud is that it is a weaker rule: what keeps the credential off
// the network is the network it is on, which is the deployment's job and is documented as such
// (docs/development/demos.md). The gateway applies the same distinction to its broker's address
// (feed-gateway/internal/config).
//
// Still no path, query, fragment or user information: this is an origin the client appends its own
// procedure paths to, and a base URL carrying half a request would produce requests nobody meant.
func Reachable(raw string) (string, error) {
	parsed, err := url.Parse(raw)
	if err != nil {
		return "", fmt.Errorf("is not a URL: %w", err)
	}
	scheme := strings.ToLower(parsed.Scheme)
	host := strings.ToLower(parsed.Hostname())
	switch {
	case host == "":
		return "", fmt.Errorf("must name a host")
	case scheme != "http" && scheme != "https":
		return "", fmt.Errorf("must use HTTP or HTTPS")
	case parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "":
		return "", fmt.Errorf("must have no user information, query or fragment")
	case strings.Trim(parsed.Path, "/") != "":
		return "", fmt.Errorf("must have no path: the client appends its own")
	}
	port := parsed.Port()
	if port != "" {
		if number, err := strconv.Atoi(port); err != nil || number < 1 || number > 65535 {
			return "", fmt.Errorf("has a port that is not a port")
		}
	}
	written := host
	if strings.Contains(host, ":") {
		written = "[" + host + "]"
	}
	if port != "" {
		written += ":" + port
	}
	return scheme + "://" + written, nil
}

// pathError keeps a file's contents out of a message: os.ReadFile's error names the path, which is
// configuration, and never what was in it, which is a secret.
func pathError(err error) error {
	if pathErr, ok := err.(*os.PathError); ok {
		return pathErr.Err
	}
	return err
}

// isHeaderSafe is the rule for a secret that is sent in an HTTP header: one word, no whitespace,
// no control characters, and nothing outside printable ASCII. A newline in a header value is not a
// typo, it is a second header.
func HeaderSafe(value string) bool {
	for _, character := range value {
		if character < 0x21 || character > 0x7e {
			return false
		}
	}
	return value != ""
}
