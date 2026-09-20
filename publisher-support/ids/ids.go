// Package ids mints the one kind of identity this protocol has: a lowercase UUID (SEE-95).
//
// Every ID in a manifest or a proposal has this shape — the phone's `isConnectionId`, the
// gateway's `rules.IsID` — and a proposal's own ID is minted here rather than taken from a caller.
// That is deliberate: an API caller that chose the ID could publish over a signal somebody else's
// call created, and its idempotency key is the thing that lets it recognize its own signal again
// (docs/integrations/signal-api.md).
//
// Sixteen bytes from crypto/rand, with the version and variant bits set, and no dependency: a
// template is something a developer copies, and a UUID library would be a line in its go.mod for
// four lines of code.
package ids

import (
	"crypto/rand"
	"encoding/hex"
)

// New is a random (version 4) UUID in the lowercase hyphenated form.
func New() string {
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		// crypto/rand does not fail on any platform this runs on. If it ever did, minting a
		// predictable identity would be worse than stopping: two signals with one ID is one
		// signal published over another.
		panic("ids: no randomness available: " + err.Error())
	}
	raw[6] = (raw[6] & 0x0f) | 0x40 // version 4
	raw[8] = (raw[8] & 0x3f) | 0x80 // variant 1
	text := make([]byte, 0, 36)
	hexed := hex.EncodeToString(raw)
	for index, group := range []int{8, 4, 4, 4, 12} {
		if index > 0 {
			text = append(text, '-')
		}
		text = append(text, hexed[:group]...)
		hexed = hexed[group:]
	}
	return string(text)
}
