package admin

import (
	"fmt"
	"strings"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// Description is a feed's public catalog description (SEE-176), normalized, or an error naming
// what is wrong with it. The admin page and feed-gatewayctl both read it through here, so the two
// surfaces store the same text for the same input.
//
// It is plain text written to be read by anyone browsing the app's Discover catalog: line breaks
// are kept (as "\n", whatever the browser sent), surrounding space is trimmed, and nothing is
// interpreted as markup anywhere it is shown. It is not the label — the operator's own note, never
// served — and not the host. An empty description is valid and leaves a listed feed out of the
// catalog until one is written.
func Description(raw string) (string, error) {
	text := strings.TrimSpace(strings.ReplaceAll(strings.ReplaceAll(raw, "\r\n", "\n"), "\r", "\n"))
	if !(storage.Listing{Description: text}).Valid() {
		return "", fmt.Errorf("a public description is plain text of at most %d characters, "+
			"with no control characters but line breaks", storage.MaxDescriptionRunes)
	}
	return text, nil
}

// ListingState says whether a listed feed is actually in the catalog, and if not, the first thing
// that keeps it out. It is the admin page's explanation of "incomplete metadata": the flag is the
// operator's opt-in, and the catalog also needs a feed that can publish, a public description and
// a manifest with a display name.
func ListingState(publisher storage.Publisher, displayName string, hasManifest bool) (bool, string) {
	switch {
	case !publisher.Listing.Recommended:
		return false, "Not shown in app recommendations."
	case !publisher.Publishing:
		return false, "Listed, but not shown: this server is not enabled for publishing, so it has " +
			"no feed to recommend."
	case strings.TrimSpace(publisher.Listing.Description) == "":
		return false, "Listed, but not shown: write a public description first."
	case !hasManifest:
		return false, "Listed, but not shown: the publisher has not published a manifest yet."
	case strings.TrimSpace(displayName) == "":
		return false, "Listed, but not shown: the publisher's manifest has no display name."
	}
	return true, "Shown in app recommendations as “" + strings.TrimSpace(displayName) + "”."
}
