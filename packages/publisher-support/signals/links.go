package signals

import (
	"net/url"
	"strings"
	"unicode"
)

// MostLinkBytes is the longest destination a signal may name. It is the protocol's own cap on one
// value, said here so a document that is within the protocol and outside this rule is refused for
// the rule it actually broke.
const MostLinkBytes = 512

// refusedSchemes are not destinations at all. `http` is the same page without the guarantee, and
// the rest are code, inline content, this phone's storage, another app's storage, and — in
// `intent`'s case — an arbitrary component with arbitrary extras. The phone's own list, in
// `plugins/actions/ProviderLink.kt`, is the same one.
var refusedSchemes = map[string]bool{
	"http": true, "javascript": true, "data": true, "file": true, "content": true,
	"intent": true, "android-app": true, "jar": true, "about": true,
}

// IsProviderLink is the shape of a destination a signal may name (SEE-157): where the provider's
// own app or site keeps this market, so an owner can carry on there rather than be handed to a
// browser and left to find it.
//
// It is the phone's `isProviderLink`, reimplemented here for the same reason `IsMarketID` is: a
// template that published a destination no phone would open would publish a signal that looks fine
// and quietly loses the one thing it was added for.
//
// What it does *not* check is whose address it is. That belongs to whoever executes the action —
// the phone asks its provider's adapter, which knows its own property — because this module has no
// opinion about which venue a publisher is describing (docs/wiki/jupiter-prediction.md).
func IsProviderLink(value string) bool {
	if value == "" || len(value) > MostLinkBytes {
		return false
	}
	for _, character := range value {
		if unicode.IsSpace(character) || unicode.IsControl(character) {
			return false
		}
	}
	parsed, err := url.Parse(value)
	if err != nil || parsed.Scheme == "" {
		return false
	}
	scheme := strings.ToLower(parsed.Scheme)
	if !isSchemeName(scheme) || refusedSchemes[scheme] {
		return false
	}
	// A credential in a URL is either a leak or a lure, and no destination needs one.
	if parsed.User != nil {
		return false
	}
	if scheme == "https" {
		// The web's scheme is meaningless without an authority, and an address whose host cannot
		// be read is one nothing can tell a provider's own property from.
		return parsed.Host != "" && parsed.Hostname() != ""
	}
	// A private scheme is the app's own, and so is its shape. The one thing asked of it is that
	// there is something after the colon to open.
	return len(value) > len(scheme)+1
}

// isSchemeName is RFC 3986's scheme production, bounded: a letter, then letters, digits and three
// punctuation marks.
func isSchemeName(scheme string) bool {
	if scheme == "" || len(scheme) > 32 || scheme[0] < 'a' || scheme[0] > 'z' {
		return false
	}
	for _, character := range scheme[1:] {
		switch {
		case character >= 'a' && character <= 'z',
			character >= '0' && character <= '9',
			character == '+', character == '.', character == '-':
		default:
			return false
		}
	}
	return true
}
