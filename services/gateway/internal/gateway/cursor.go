package gateway

import (
	"encoding/base64"
	"strconv"
	"strings"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
)

// A page token is this gateway's note to itself about where a walk had got to. It is opaque on
// purpose — a client echoes it and never builds one — and it carries exactly two things beyond the
// channel it belongs to: the sequence the walk began at, which every page of that walk reports as
// its consistency boundary, and the last proposal ID that was returned.
//
// The ID is what makes the walk stable. Paging by an offset would skip or repeat documents as the
// feed moved underneath it, and paging by sequence would move a document between pages every time
// it was republished; an identity does not change for as long as the proposal exists, so "after
// this ID, in ID order" describes the same place in the feed however much has happened since.
//
// Nothing is signed. There is nothing in a token that a client could gain by writing its own: the
// channel is checked against the request, the sequence only labels the page, and an ID it does not
// hold yet is a position it could have reached by asking. What a made-up token cannot do is see
// another channel.
const cursorVersion = "1"

const cursorSeparator = "\x1f"

func encodeCursor(channel string, snapshot uint64, after string) string {
	parts := strings.Join(
		[]string{cursorVersion, channel, strconv.FormatUint(snapshot, 10), after},
		cursorSeparator)
	return base64.RawURLEncoding.EncodeToString([]byte(parts))
}

// decodeCursor reads a token, and refuses one that is not this gateway's, is from another version
// of the format, or names another channel than the request does. The last of those is the one that
// matters: a token is the only part of a read request that a client did not have to say out loud,
// and it must not be a way to read a channel it did not ask for.
func decodeCursor(token, channel string) (snapshot uint64, after string, fault *rules.Fault) {
	bad := &rules.Fault{
		Problem: gatewayv1.GatewayProblem_GATEWAY_PROBLEM_BAD_CURSOR,
		Field:   "page_token",
	}
	raw, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil {
		return 0, "", bad
	}
	parts := strings.Split(string(raw), cursorSeparator)
	if len(parts) != 4 || parts[0] != cursorVersion || parts[1] != channel {
		return 0, "", bad
	}
	snapshot, err = strconv.ParseUint(parts[2], 10, 64)
	if err != nil {
		return 0, "", bad
	}
	if parts[3] != "" && !rules.IsID(parts[3]) {
		return 0, "", bad
	}
	return snapshot, parts[3], nil
}
