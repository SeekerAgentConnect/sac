package signals

import (
	"strings"
	"testing"
)

// The addresses a signal may name, and — the half that matters — the ones it may not.
//
// Everything refused here is refused because it is not somewhere to continue an order: a page
// without the guarantee, code, inline content, this phone's own storage, another app's, or an
// Android intent, which is an arbitrary component with arbitrary extras dressed as a link.
func TestWhatMayBeNamedAsADestination(t *testing.T) {
	for _, allowed := range []string{
		"https://jup.ag/prediction/fed-decision-in-october",
		"https://jup.ag/prediction/portfolio?tab=open",
		"https://sub.example.com/",
		"venue://market/POLY-2589813",
		"venue:market/POLY-2589813",
	} {
		if !IsProviderLink(allowed) {
			t.Fatalf("%q is a destination and was refused", allowed)
		}
	}
	for refused, why := range map[string]string{
		"":                           "nothing at all",
		"   ":                        "whitespace",
		"jup.ag/prediction/x":        "no scheme, so nothing knows what to do with it",
		"http://jup.ag/prediction/x": "the same page without the guarantee",
		"javascript:alert(1)":        "code",
		"data:text/html,<b>hi</b>":   "inline content",
		"file:///data/data/app/x":    "this phone's own storage",
		"content://media/external/1": "another app's storage",
		"intent://x#Intent;end":      "an arbitrary component",
		"https://user:pw@jup.ag/x":   "a credential nothing needs",
		"https:///prediction/x":      "no host to tell anybody's property from",
		"https://jup.ag/a b":         "a space, so it was assembled rather than written",
		"https://jup.ag/a\nb":        "a control character, likewise",
	} {
		if IsProviderLink(refused) {
			t.Fatalf("%q is %s and was allowed", refused, why)
		}
	}
	if IsProviderLink("https://jup.ag/" + strings.Repeat("x", MostLinkBytes)) {
		t.Fatalf("a destination longer than %d bytes was allowed", MostLinkBytes)
	}
}
