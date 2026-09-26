package gateway

import (
	"fmt"

	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"
)

// strictJSON is the JSON codec both listeners use, and it differs from the default in one way: a
// field the contract does not have is an error rather than something to ignore.
//
// That matters most for the thing this gateway must never accept. A client that believes the
// gateway keeps execution records would send a wallet, a chosen amount or a signature alongside a
// proposal; with the permissive default it would get 200 and quietly have those fields dropped,
// and would go on believing it. Refusing says what is true: there is no field here for any of it,
// and there is no endpoint that would take it (docs/security.md).
//
// It also catches the ordinary version of the same mistake — a misspelled field in a publisher
// template — at the first call rather than in a subscriber's confusion later.
//
// The binary protocol cannot be made strict the same way: protobuf keeps what it cannot parse as
// unknown fields, and a gateway that refused those could be broken by any later version of the
// contract. So the guarantee there is the other one: every document is rebuilt from the fields
// that were validated, so an unknown field is never stored and never relayed (internal/rules).
type strictJSON struct{}

// Name is "json", which replaces Connect's own codec of that name for these handlers.
func (strictJSON) Name() string { return "json" }

func (strictJSON) Marshal(message any) ([]byte, error) {
	protocol, ok := message.(proto.Message)
	if !ok {
		return nil, fmt.Errorf("%T is not a protobuf message", message)
	}
	return protojson.Marshal(protocol)
}

func (strictJSON) Unmarshal(data []byte, message any) error {
	protocol, ok := message.(proto.Message)
	if !ok {
		return fmt.Errorf("%T is not a protobuf message", message)
	}
	// DiscardUnknown stays false, which is the whole point of this type.
	return protojson.Unmarshal(data, protocol)
}
