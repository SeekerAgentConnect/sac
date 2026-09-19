package admin

import (
	"testing"
	"time"
)

func TestASessionCarriesTheNameUntilItExpires(t *testing.T) {
	now := time.Date(2026, 9, 19, 12, 0, 0, 0, time.UTC)
	secret := stringsRepeat("s", 32)
	cookie, err := signSession(secret, "judge1", now)
	if err != nil {
		t.Fatal(err)
	}
	name, ok := verifySession(secret, cookie, now.Add(time.Hour))
	if !ok || name != "judge1" {
		t.Fatalf("name %q ok %v", name, ok)
	}
	if _, ok := verifySession(secret, cookie, now.Add(13*time.Hour)); ok {
		t.Fatal("an expired session was accepted")
	}
	if _, ok := verifySession("other-secret-other-secret-other", cookie, now); ok {
		t.Fatal("a cookie signed with another secret was accepted")
	}
	if _, ok := verifySession(secret, cookie+"x", now); ok {
		t.Fatal("a tampered cookie was accepted")
	}
}

func stringsRepeat(text string, count int) string {
	out := make([]byte, 0, len(text)*count)
	for range count {
		out = append(out, text...)
	}
	return string(out)
}
