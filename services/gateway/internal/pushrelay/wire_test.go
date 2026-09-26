// The relay's contract as a shape (SEE-144). What it will read, what it refuses to read, and the
// one rule every refusal on this surface is held to: that it says nothing back.
//
// The behaviour behind these — who may enroll, who may send, what is bounded — is exercised end to
// end against the real handlers on the real listeners in internal/gateway/relay_test.go.
package pushrelay

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// Every refusal this surface can give, read as text. None of them may carry anything the caller
// did not already know: an error is the one place a service is most tempted to publish its own
// state, and a caller here is an external server or a phone that is entitled to know only whether
// it may proceed.
func TestNoRefusalSaysAnythingBackToTheCaller(t *testing.T) {
	for _, reason := range []string{
		malformed, wrongVersion, unauthenticated, unauthorized,
		noInstallation, notPermitted, tooMany, unavailable, failed,
	} {
		switch {
		case reason == "":
			t.Fatal("a refusal with nothing to say")
		case strings.ContainsAny(reason, "{}<>"):
			// A reason that could carry a rendered value is a reason somebody will put one in.
			t.Fatalf("%q looks like a template", reason)
		}
	}
	// And the two a caller could confuse are deliberately the same sentence for several causes.
	if unauthorized == notPermitted {
		t.Fatal("a phone's refusal and a server's are one string")
	}
}

// A body this version does not fully understand is refused rather than half read.
//
// It is the opposite of what a tolerant reader does, and the right thing here: a caller that sent
// a field this contract has no room for is a caller with a different idea of what it is asking
// for — and the fields that would matter are exactly the ones this surface exists to refuse.
func TestOnlyAWholeKnownBodyIsRead(t *testing.T) {
	for _, body := range []string{
		`{"version":"1","hint":"created","handle":"x","priority":"HIGH"}`,
		`{"version":"1","hint":"created","handle":"x","notification":"pay me"}`,
		`{"version":"1","hint":"created","handle":"x"}{"version":"1","hint":"created","handle":"x"}`,
		`{"version":"1",`,
		`[]`,
		strings.Repeat("x", MostBytes+1),
	} {
		writer := httptest.NewRecorder()
		request := httptest.NewRequest(http.MethodPost, Prefix+"/notify", strings.NewReader(body))
		var asked notifyRequest
		if read(writer, request, &asked) {
			t.Fatalf("%.60s was read", body)
		}
		if writer.Code != http.StatusBadRequest {
			t.Fatalf("%.60s answered %d", body, writer.Code)
		}
	}

	writer := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, Prefix+"/notify",
		strings.NewReader(`{"version":"1","handle":"x","hint":"created"}`))
	var asked notifyRequest
	if !read(writer, request, &asked) {
		t.Fatal("a whole body was refused")
	}
	if asked.Handle != "x" || asked.Hint != Created {
		t.Fatalf("the body read as %+v", asked)
	}
}

// The credential header, in the one shape the publisher API already uses, so an operator
// configuring a server sets one kind of thing in one kind of place.
func TestOnlyABearerIsACredential(t *testing.T) {
	for header, expected := range map[string]string{
		"Bearer abc":  "abc",
		"Bearer  abc": "abc",
		"bearer abc":  "",
		"abc":         "",
		"Basic abc":   "",
		"":            "",
	} {
		request := httptest.NewRequest(http.MethodPost, Prefix+"/notify", nil)
		if header != "" {
			request.Header.Set("Authorization", header)
		}
		if got := bearer(request); got != expected {
			t.Fatalf("%q read as %q, expected %q", header, got, expected)
		}
	}
}

// Both sides hold the same registration string, and neither trusts the other about it. The bound
// and the shape are the sidecar's own, and a refusal never repeats the value.
func TestATargetIsBoundedAndOpaque(t *testing.T) {
	for target, valid := range map[string]bool{
		"":                             false,
		"fid-of-a-phone":               true,
		"has a space":                  false,
		"has\na newline":               false,
		strings.Repeat("f", 4096):      true,
		strings.Repeat("f", 4097):      false,
		"é":                            false,
		"~!@#$%^&*()_+-=[]{}|;:'\",.<": true,
	} {
		if got := validTarget(target); got != valid {
			t.Fatalf("%.20q read as valid=%v", target, got)
		}
	}
	// A connection reference is the phone's own name for one of its connections. The gateway never
	// parses it, so the only thing it has to be is short.
	if validConnection("") || validConnection(strings.Repeat("c", MostConnectionBytes+1)) {
		t.Fatal("a connection reference is not bounded")
	}
	if !validConnection("00000000-0000-4000-8000-00000000000a") {
		t.Fatal("an ordinary connection reference was refused")
	}
}
