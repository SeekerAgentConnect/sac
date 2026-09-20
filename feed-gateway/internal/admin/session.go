package admin

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"sync"
	"time"
)

// MostSessions is how many logins this process remembers at once. One operator needs a handful;
// the bound is here so that a login loop cannot spend the process's memory, and a login past it is
// refused after the expired ones have been dropped rather than evicting a session somebody is
// using.
const MostSessions = 64

// A session is server-side state, and the cookie carries nothing but an opaque random token.
//
// It is deliberately not a signed cookie. A signed cookie cannot be withdrawn — logging out would
// only ask the browser to forget something that still verifies — and it would need signing material
// configured, kept and rotated for a surface that has exactly one process. Holding sessions here
// makes logout a real revocation, makes a restart end every session, and leaves nothing to
// configure. The cost is that sessions do not survive a restart, which for one operator is a second
// login.
//
// The token is stored as its SHA-256, the same way a publishing credential is: a heap dump, a core
// file or a careless log of this map hands over nothing that can be presented.
type session struct {
	csrf    string
	expires time.Time
	// notice is one line about what just happened, carried across a redirect so that a destructive
	// action can answer on the page it returns to rather than on the one it left.
	notice string
	// reveal is a credential that has been created and not yet shown. It is held for exactly one
	// GET, by the session that created it, so the secret never travels in a URL, a redirect or a
	// referrer, and a reload of the page it was shown on cannot mint a second one.
	reveal *Reveal
}

type sessions struct {
	ttl  time.Duration
	now  func() time.Time
	most int

	mutex sync.Mutex
	held  map[string]*session
}

func newSessions(ttl time.Duration, now func() time.Time) *sessions {
	return &sessions{ttl: ttl, now: now, most: MostSessions, held: map[string]*session{}}
}

// begin mints a session and returns the token the cookie carries. An empty token means the process
// is holding as many live sessions as it will.
func (s *sessions) begin() string {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		panic("admin: no randomness available: " + err.Error())
	}
	token := base64.RawURLEncoding.EncodeToString(raw)

	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.forgetExpired()
	if len(s.held) >= s.most {
		return ""
	}
	s.held[key(token)] = &session{csrf: mintCSRF(), expires: s.now().Add(s.ttl)}
	return token
}

// lookup is the session a token names, or nil. The expiry is absolute rather than sliding: a
// session lasts as long as it was granted for, so an unattended browser does not keep one alive
// indefinitely by being open.
func (s *sessions) lookup(token string) *session {
	if token == "" {
		return nil
	}
	s.mutex.Lock()
	defer s.mutex.Unlock()
	held, known := s.held[key(token)]
	if !known {
		return nil
	}
	if !s.now().Before(held.expires) {
		delete(s.held, key(token))
		return nil
	}
	return held
}

// end is logout, and is also what a session that has been tampered with gets. It is idempotent.
func (s *sessions) end(token string) {
	if token == "" {
		return
	}
	s.mutex.Lock()
	defer s.mutex.Unlock()
	delete(s.held, key(token))
}

// take is the one read of a pending credential. It hands the reveal over and forgets it in the
// same lock, so a second tab, a reload or a back button gets the ordinary page instead.
func (s *sessions) take(token string) *Reveal {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	held, known := s.held[key(token)]
	if !known || held.reveal == nil {
		return nil
	}
	reveal := held.reveal
	held.reveal = nil
	return reveal
}

func (s *sessions) hold(token string, reveal *Reveal) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	if held, known := s.held[key(token)]; known {
		held.reveal = reveal
	}
}

// live is how many sessions are held, for the tests that prove logout and expiry actually remove
// them.
func (s *sessions) live() int {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.forgetExpired()
	return len(s.held)
}

func (s *sessions) forgetExpired() {
	at := s.now()
	for token, held := range s.held {
		if !at.Before(held.expires) {
			delete(s.held, token)
		}
	}
}

func key(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func mintCSRF() string {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		panic("admin: no randomness available: " + err.Error())
	}
	return base64.RawURLEncoding.EncodeToString(raw)
}

// sameToken compares a submitted CSRF token with the session's, in constant time.
func sameToken(submitted, held string) bool {
	return subtle.ConstantTimeCompare([]byte(submitted), []byte(held)) == 1
}
