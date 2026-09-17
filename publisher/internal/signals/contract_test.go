package signals

import (
	"os"
	"regexp"
	"strconv"
	"testing"
)

// The gateway's own copy of these rules, which this module deliberately does not import: a
// template is something a developer copies out, and one that compiled against the gateway's
// internals would not be copyable. What that costs is the risk of drift, so this test pays it by
// reading the gateway's source.
//
// It is the same technique as the phone's `push/FeedHintContractTest`, which reads the relay's Go
// source, and for the same reason: a disagreement here would not be an error anywhere. It would be
// a template that publishes documents the gateway refuses, or — worse — accepts documents the
// phone refuses, and the first sign of either would be a signal that nobody can see.
//
// It skips when the file is not there, which is what a copied-out template looks like.
const gatewayRules = "../../../broadcast/internal/rules/rules.go"

func TestTheBoundsAreTheGatewaysOwn(t *testing.T) {
	source, err := os.ReadFile(gatewayRules)
	if err != nil {
		t.Skipf("the gateway's source is not here (%v), which is what a copied-out template "+
			"looks like", err)
	}
	for name, held := range map[string]int{
		"MaxValues":         MaxValues,
		"MaxValueTextBytes": MaxValueTextBytes,
		"MaxNoteBytes":      MaxNoteBytes,
		"MaxNameBytes":      MaxNameBytes,
	} {
		pattern := regexp.MustCompile(`(?m)^\s*` + name + `\s*=\s*(\d+)\s*$`)
		match := pattern.FindSubmatch(source)
		if match == nil {
			t.Fatalf("%s is no longer declared in %s: the gateway's bound moved or was renamed, "+
				"and this template's copy of it is now a guess", name, gatewayRules)
		}
		theirs, err := strconv.Atoi(string(match[1]))
		if err != nil {
			t.Fatal(err)
		}
		if theirs != held {
			t.Fatalf("%s is %d here and %d in the gateway (%s): a template that accepts more "+
				"than the gateway does publishes signals nobody sees", name, held, theirs,
				gatewayRules)
		}
	}
}

// The highest revision either side will order. It is a uint64 on the wire and a signed 64-bit Long
// on the phone, so the bound is not an opinion: above it, a revision arrives on the phone as a
// negative number.
func TestTheRevisionCeilingIsTheGatewaysOwn(t *testing.T) {
	source, err := os.ReadFile(gatewayRules)
	if err != nil {
		t.Skipf("the gateway's source is not here (%v)", err)
	}
	if !regexp.MustCompile(`MaxRevision uint64 = 1<<63 - 1`).Match(source) {
		t.Fatalf("the gateway's MaxRevision is no longer 1<<63 - 1; this template publishes up "+
			"to %d", uint64(MaxRevision))
	}
	if MaxRevision != 1<<63-1 {
		t.Fatalf("MaxRevision is %d here", uint64(MaxRevision))
	}
}

// The protocol version and the channel's shape, which are the two things a manifest and every
// proposal have to agree with the gateway about.
func TestTheChannelIsTheGatewaysOwn(t *testing.T) {
	source, err := os.ReadFile(gatewayRules)
	if err != nil {
		t.Skipf("the gateway's source is not here (%v)", err)
	}
	if !regexp.MustCompile(`channelPrefix = "server/"`).Match(source) {
		t.Fatal("the gateway's channel prefix is no longer \"server/\"")
	}
	if ChannelFor("3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d") !=
		"server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d" {
		t.Fatal("this template's channel is not server/<server_id>")
	}
}
