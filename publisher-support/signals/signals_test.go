package signals

import (
	"strings"
	"testing"
	"time"

	"google.golang.org/protobuf/proto"
)

var now = time.Date(2026, 9, 17, 12, 0, 0, 0, time.UTC)

const server = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

// A swap's terms, as a caller would send them, for the tests that are not about the terms.
func swapTerms() map[string]string {
	return map[string]string{
		InputMint:      WrappedSOL,
		InputDecimals:  "9",
		OutputMint:     "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
		OutputDecimals: "6",
		MaxSlippageBps: "50",
	}
}

func TestAnExpiryMustBeAnInstantInTheFuture(t *testing.T) {
	kind := Swap{}
	for _, one := range []struct {
		name    string
		expires time.Time
		code    string
	}{
		{"absent", time.Time{}, "no_expiry"},
		{"already past", now.Add(-time.Second), "past_expiry"},
		{"this very second", now, "past_expiry"},
	} {
		t.Run(one.name, func(t *testing.T) {
			_, _, _, fault := Check(kind, "", one.expires, now, swapTerms())
			if fault == nil || fault.Code != one.code {
				t.Fatalf("expected %s, got %v", one.code, fault)
			}
		})
	}
	if _, expires, _, fault := Check(kind, "", now.Add(time.Hour), now, swapTerms()); fault != nil {
		t.Fatalf("an hour from now is a future instant: %v", fault)
	} else if !expires.Equal(now.Add(time.Hour)) {
		t.Fatalf("expiry %s", expires)
	}
}

// An expiry with a fraction of a second is truncated, because every time in a published document
// is, and a retry has to reproduce the same bytes.
func TestAnExpiryIsTruncatedToTheSecond(t *testing.T) {
	asked := now.Add(time.Hour).Add(750 * time.Millisecond)
	_, expires, _, fault := Check(Swap{}, "", asked, now, swapTerms())
	if fault != nil {
		t.Fatal(fault)
	}
	if expires.Nanosecond() != 0 || !expires.Equal(now.Add(time.Hour)) {
		t.Fatalf("expiry %s", expires.Format(time.RFC3339Nano))
	}
}

func TestANoteIsProseAndIsBounded(t *testing.T) {
	kind := Swap{}
	future := now.Add(time.Hour)
	for _, one := range []struct {
		name string
		note string
		ok   bool
	}{
		{"empty", "", true},
		{"prose with a line break", "trimming SOL\ninto USDC", true},
		{"at the bound", strings.Repeat("a", MaxNoteBytes), true},
		{"over the bound", strings.Repeat("a", MaxNoteBytes+1), false},
		{"trailing whitespace", "hello ", false},
		{"a control character", "hello", false},
		{"a tab", "hello\tthere", false},
	} {
		t.Run(one.name, func(t *testing.T) {
			_, _, _, fault := Check(kind, one.note, future, now, swapTerms())
			if (fault == nil) != one.ok {
				t.Fatalf("note %q: fault %v", one.note, fault)
			}
			if fault != nil && fault.Code != "bad_note" {
				t.Fatalf("expected bad_note, got %s", fault.Code)
			}
		})
	}
}

// The document's terms are in key order, always. A repeated field's order is part of the bytes,
// and the gateway compares a republication with what it holds field by field — so a template that
// ordered its terms by chance would turn its own retry into a revision conflict.
func TestTheDocumentsTermsAreInKeyOrder(t *testing.T) {
	signal := Signal{
		ProposalID: "8c9d0e1f-2a3b-4c5d-8e6f-7a8b9c0d1e2f",
		Revision:   1,
		Status:     Open,
		Operation:  "swap",
		PluginID:   "jupiter.swap",
		CreatedAt:  now,
		UpdatedAt:  now,
		ExpiresAt:  now.Add(time.Hour),
		Terms:      swapTerms(),
	}
	document := Proposal(server, signal)
	keys := []string{}
	for _, value := range document.GetValues() {
		keys = append(keys, value.GetKey())
	}
	expected := []string{InputDecimals, InputMint, MaxSlippageBps, OutputDecimals, OutputMint}
	if strings.Join(keys, ",") != strings.Join(expected, ",") {
		t.Fatalf("terms in %v, expected %v", keys, expected)
	}
	// And the same signal serializes to the same bytes, however the map was built.
	first, err := proto.MarshalOptions{Deterministic: true}.Marshal(document)
	if err != nil {
		t.Fatal(err)
	}
	shuffled := Signal{}
	shuffled = signal
	shuffled.Terms = map[string]string{}
	for _, key := range []string{MaxSlippageBps, OutputMint, InputMint, OutputDecimals, InputDecimals} {
		shuffled.Terms[key] = signal.Terms[key]
	}
	second, err := proto.MarshalOptions{Deterministic: true}.Marshal(Proposal(server, shuffled))
	if err != nil {
		t.Fatal(err)
	}
	if string(first) != string(second) {
		t.Fatal("the same signal produced two different documents")
	}
}

func TestADocumentNamesItsOwnChannelAndNothingElse(t *testing.T) {
	document := Proposal(server, Signal{ProposalID: "x", Status: Open, Terms: swapTerms()})
	if document.GetServerId() != server {
		t.Fatalf("server %q", document.GetServerId())
	}
	if document.GetChannel() != "server/"+server {
		t.Fatalf("channel %q", document.GetChannel())
	}
}

func TestARequestUsesTheSourceTitleWithoutAddingAnAppPrefix(t *testing.T) {
	signal := Signal{
		ProposalID: "8c9d0e1f-2a3b-4c5d-8e6f-7a8b9c0d1e2f",
		Revision:   1,
		Status:     Open,
		Operation:  "prediction",
		PluginID:   "jupiter.prediction",
		CreatedAt:  now,
		UpdatedAt:  now,
		ExpiresAt:  now.Add(time.Hour),
		Title:      "Will the Fed cut rates?",
		Terms:      map[string]string{},
	}
	if title := Request(server, signal).GetPresentation().GetTitle(); title != signal.Title {
		t.Fatalf("title %q, expected the provider's %q", title, signal.Title)
	}
	withoutTitle := signal
	withoutTitle.Title = ""
	if title := Request(server, withoutTitle).GetPresentation().GetTitle(); title != "Prediction market" {
		t.Fatalf("fallback title %q", title)
	}
	if Statement(signal) == Statement(withoutTitle) {
		t.Fatal("the source title was absent from the idempotent statement")
	}
}

// The fingerprint is the content, and the two fields that are not content are left out of it: the
// revision, which says the content changed, and the update time, which says when.
func TestTheFingerprintIsTheContentAndNotTheRevision(t *testing.T) {
	signal := Signal{
		ProposalID: "8c9d0e1f-2a3b-4c5d-8e6f-7a8b9c0d1e2f",
		Revision:   1,
		Status:     Open,
		CreatedAt:  now,
		UpdatedAt:  now,
		ExpiresAt:  now.Add(time.Hour),
		Note:       "trimming",
		Terms:      swapTerms(),
	}
	held := Fingerprint(server, signal)

	moved := signal
	moved.Revision = 99
	moved.UpdatedAt = now.Add(time.Minute)
	if Fingerprint(server, moved) != held {
		t.Fatal("a revision and an update time changed the fingerprint")
	}
	for _, one := range []struct {
		name  string
		apply func(*Signal)
	}{
		{"the note", func(s *Signal) { s.Note = "something else" }},
		{"the title", func(s *Signal) { s.Title = "A source question" }},
		{"the expiry", func(s *Signal) { s.ExpiresAt = now.Add(2 * time.Hour) }},
		{"a term", func(s *Signal) { s.Terms[MaxSlippageBps] = "51" }},
		{"the status", func(s *Signal) { s.Status = Cancelled }},
		{"the creation time", func(s *Signal) { s.CreatedAt = now.Add(-time.Hour) }},
	} {
		t.Run(one.name, func(t *testing.T) {
			changed := signal
			changed.Terms = swapTerms()
			one.apply(&changed)
			if Fingerprint(server, changed) == held {
				t.Fatalf("%s did not change the fingerprint", one.name)
			}
		})
	}
	// And a different publisher is a different document, even with the same content.
	if Fingerprint("0e1f2a3b-4c5d-4e6f-8a7b-8c9d0e1f2a3b", signal) == held {
		t.Fatal("two publishers produced one fingerprint")
	}
}

func TestATermNameIsAShortLowercaseWord(t *testing.T) {
	for _, one := range []struct {
		name string
		key  string
		ok   bool
	}{
		{"a word", "market_id", true},
		{"dotted", "jupiter.market", true},
		{"upper case", "Market", false},
		{"leading digit", "1market", false},
		{"a space", "market id", false},
		{"empty", "", false},
		{"too long", strings.Repeat("a", 65), false},
	} {
		t.Run(one.name, func(t *testing.T) {
			if IsOperation(one.key) != one.ok {
				t.Fatalf("%q", one.key)
			}
		})
	}
}

func TestAPluginIDIsANameAndNothingLoadable(t *testing.T) {
	for _, one := range []struct {
		id string
		ok bool
	}{
		{"jupiter.swap", true},
		{"jupiter.prediction", true},
		{"swap", false},                              // a plugin ID has at least one dot
		{"https://example.com/plugin.js", false},     // never a URL
		{"io.github.brrenat.seekervault.swap", true}, // dotted segments
		{"Jupiter.Swap", false},
	} {
		t.Run(one.id, func(t *testing.T) {
			if IsPluginID(one.id) != one.ok {
				t.Fatalf("%q", one.id)
			}
		})
	}
}

func TestAPublicationIsPendingUntilTheGatewayConfirmsTheRevision(t *testing.T) {
	signal := Signal{Revision: 4}
	for _, one := range []struct {
		name  string
		state Publication
		want  string
	}{
		{"nothing confirmed", Publication{}, "pending"},
		{"an older revision confirmed", Publication{ConfirmedRevision: 3}, "pending"},
		{"this revision confirmed", Publication{ConfirmedRevision: 4}, "published"},
		{"refused", Publication{Problem: "foreign_channel"}, "refused"},
		{"refused even once confirmed", Publication{ConfirmedRevision: 4, Problem: "x"}, "refused"},
	} {
		t.Run(one.name, func(t *testing.T) {
			if got := one.state.State(signal); got != one.want {
				t.Fatalf("%s, expected %s", got, one.want)
			}
		})
	}
}

func TestTooManyTermsIsRefusedWhole(t *testing.T) {
	// A kind that passes whatever it is given, so the bound above the kinds is the thing tested.
	terms := map[string]string{}
	for index := range MaxValues + 1 {
		terms[termName(index)] = "x"
	}
	_, _, _, fault := Check(anyTerms{}, "", now.Add(time.Hour), now, terms)
	if fault == nil || fault.Code != "too_many_terms" {
		t.Fatalf("expected too_many_terms, got %v", fault)
	}
}

func TestATermsTextIsBoundedAndOnOneLine(t *testing.T) {
	for _, one := range []struct {
		name string
		text string
		code string
	}{
		{"at the bound", strings.Repeat("a", MaxValueTextBytes), ""},
		{"over the bound", strings.Repeat("a", MaxValueTextBytes+1), "bad_term"},
		{"a line break", "one\ntwo", "bad_term"},
	} {
		t.Run(one.name, func(t *testing.T) {
			_, _, _, fault := Check(anyTerms{}, "", now.Add(time.Hour), now,
				map[string]string{"anything": one.text})
			switch {
			case one.code == "" && fault != nil:
				t.Fatalf("unexpected %v", fault)
			case one.code != "" && (fault == nil || fault.Code != one.code):
				t.Fatalf("expected %s, got %v", one.code, fault)
			}
		})
	}
}

// anyTerms is a [Kind] that accepts any terms, for the rules that live above a kind rather than
// inside one.
type anyTerms struct{}

func (anyTerms) Operation() string { return "anything" }

func (anyTerms) Requirement() Requirement {
	return Requirement{PluginID: "test.anything", MinContract: 1, MostContract: 1}
}

func (anyTerms) Terms(raw map[string]string) (map[string]string, *Fault) { return raw, nil }

func termName(index int) string {
	return "term_" + string(rune('a'+index/26)) + string(rune('a'+index%26))
}
