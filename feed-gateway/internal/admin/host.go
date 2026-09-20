package admin

import (
	"fmt"
	"net/url"
	"strconv"
	"strings"
)

// MaxHostBytes bounds the administrative metadata an operator may record. A base URL is short; the
// bound is here so a field that is only ever displayed cannot become a place to store something.
const MaxHostBytes = 256

// Host is the developer-supplied base URL of a publisher's own backend, canonicalized, or an error
// naming what is wrong with it. An empty value is valid and means no host was given — most
// registrations made before SEE-141 have none, and a publisher that runs behind a private network
// legitimately has no address to record.
//
// # What this value is not
//
// It is not the authentication identity: the server UUID identifies a publisher and its credential
// authenticates a publication. Knowing, claiming or typing a host grants nothing. It is not a
// callback address either — publishing is outbound to the gateway, the gateway never fetches this
// URL, and no phone is ever told to contact it. It is a note about who a registration belongs to,
// so an operator looking at a list of UUIDs can tell which developer each one is.
//
// Because it is only ever displayed, the rules are about what is safe to display and follow with a
// deliberate click: an http or https origin with an optional path, and nothing that could make a
// rendered link do something other than open a page. A `javascript:` or `data:` URL is refused
// here rather than escaped later, so the refusal is one rule in one place instead of a property of
// every template that shows it.
func Host(raw string) (string, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "", nil
	}
	if len(trimmed) > MaxHostBytes {
		return "", fmt.Errorf("a host may be at most %d characters", MaxHostBytes)
	}
	if strings.ContainsAny(trimmed, " \t\r\n") {
		return "", fmt.Errorf("a host may not contain spaces")
	}
	parsed, err := url.Parse(trimmed)
	if err != nil {
		return "", fmt.Errorf("that is not a URL")
	}
	scheme := strings.ToLower(parsed.Scheme)
	host := strings.ToLower(parsed.Hostname())
	switch {
	case scheme != "http" && scheme != "https":
		return "", fmt.Errorf("a host must be an http:// or https:// URL")
	case host == "":
		return "", fmt.Errorf("a host must name a machine")
	case parsed.User != nil:
		return "", fmt.Errorf("a host may not carry user information: " +
			"this is a note about where a publisher lives, never a credential")
	case parsed.RawQuery != "" || parsed.Fragment != "":
		return "", fmt.Errorf("a host may not carry a query or a fragment")
	}
	port := parsed.Port()
	if port != "" {
		number, err := strconv.Atoi(port)
		if err != nil || number < 1 || number > 65535 {
			return "", fmt.Errorf("a host has a port that is not a port")
		}
	}
	if (scheme == "https" && port == "443") || (scheme == "http" && port == "80") {
		port = ""
	}
	written := host
	if strings.Contains(host, ":") {
		written = "[" + host + "]"
	}
	if port != "" {
		written += ":" + port
	}
	// A path is kept — a publisher may live under one — with its trailing slash removed so that two
	// operators typing the same address record the same string.
	path := strings.TrimRight(parsed.EscapedPath(), "/")
	return scheme + "://" + written + path, nil
}
