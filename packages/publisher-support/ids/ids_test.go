package ids

import (
	"regexp"
	"testing"
)

// The one shape this protocol's identities have: a lowercase hyphenated UUID, which is the phone's
// `isConnectionId` and the gateway's `rules.IsID`. A proposal ID that is not one is refused by both
// of them, so it is worth pinning here rather than discovering in a feed.
var uuid = regexp.MustCompile(
	`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)

func TestAnIDIsALowercaseVersionFourUUID(t *testing.T) {
	for range 100 {
		minted := New()
		if !uuid.MatchString(minted) {
			t.Fatalf("%q is not a lowercase version 4 UUID", minted)
		}
	}
}

// Identities are minted here rather than taken from a caller, so two of them must not collide: two
// signals with one ID would be one signal published over another.
func TestEveryIDIsItsOwn(t *testing.T) {
	seen := map[string]bool{}
	for range 10_000 {
		minted := New()
		if seen[minted] {
			t.Fatalf("%s was minted twice", minted)
		}
		seen[minted] = true
	}
}
