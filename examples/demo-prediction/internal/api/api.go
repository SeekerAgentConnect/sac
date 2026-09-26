// Package api is how this demo's discovery joins the shared publisher business API.
//
// The API itself — the token, the routing, the strict decoding, the refusal shape and every
// endpoint an operator calls — is the support library's, because both demos serve exactly the same
// one and a second copy would be a second set of rules about what a publisher will say
// (publisher-support/api). What differs between the two demos is who writes their signals, and
// that is the whole of what this package supplies: a publisher whose proposals come from a
// provider's listing refuses a caller that posts one, and answers two endpoints of its own about
// what its discovery is doing.
//
// Nothing about a subscriber is in any of it. The endpoints here say what this publisher is
// looking for and what it has found; they never learn who is reading, what anyone chose, or what
// came of it (docs/security.md).
package api

import (
	"github.com/BrRenat/SeekerAgentWallet/demo-prediction/internal/discovery"
	support "github.com/BrRenat/SeekerAgentWallet/publisher-support/api"
)

// Cycles is this demo's reconciler as the shared API frame uses it.
//
// The frame asks for the filters already described rather than for the filters themselves: what a
// publisher looks for is a provider's vocabulary — its venues, its buckets, its named filters —
// and the frame is shared with a demo that has no provider at all. So it renders what this demo
// says it is looking for without knowing what any of it means.
type Cycles struct{ *discovery.Reconciler }

// Filters is what this deployment asked for, as an operator reads it back.
func (c Cycles) Filters() map[string]any { return c.Reconciler.Filters().Describe() }

// The reconciler and the store are what a discovering publisher's API needs, said at compile time
// rather than discovered on the first call.
var _ support.Cycles = Cycles{}
