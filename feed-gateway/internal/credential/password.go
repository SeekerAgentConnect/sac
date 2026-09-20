package credential

// The operator's administrator password lives here too, for the same reason the publishing
// credential does: there is more than one surface that needs it — the CLI that prints a hash to
// configure, the configuration that validates one at startup, and the admin surface that verifies
// a login — and one algorithm with one encoding is the only way those three agree.
//
// An administrator password and a publishing credential are separate authorities that never meet.
// A publishing credential is 32 random bytes compared by SHA-256, because it is generated and
// never typed; a password is chosen by a person, so it is stretched. Neither is accepted where the
// other belongs.

import (
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
	"strconv"
	"strings"
)

// The password hash, as one line an operator can paste into a deployment secret:
//
//	pbkdf2-sha256.<iterations>.<salt base64url>.<derived key base64url>
//
// The separator is a dot rather than the `$` of the usual PHC spelling, and that is a deliberate
// change made after watching it break: Docker Compose interpolates `$name` inside an env file, so a
// `$`-separated hash pasted into deploy/feed/.env arrives at the container truncated at its first
// separator, and the only symptom is a login that never succeeds. A dot is outside base64url's
// alphabet, survives .env files, YAML, shells and app-platform consoles unescaped, and costs
// nothing — the encoding is this service's own, read only by this file.
//
// PBKDF2-HMAC-SHA256 is the one password hash in Go's standard library (crypto/pbkdf2, Go 1.24).
// Choosing it keeps the gateway's dependency list at the three modules it has had since SEE-90 —
// adding golang.org/x/crypto for bcrypt or Argon2 would put a module in the image for one function
// — and it is an established, specified construction (RFC 8018) rather than something invented
// here. The iteration count is OWASP's current recommendation for this construction; it is part of
// the encoded hash, so raising it later verifies old hashes unchanged.
const (
	scheme          = "pbkdf2-sha256"
	separator       = "."
	Iterations      = 600_000
	saltBytes       = 16
	keyBytes        = 32
	MinimumLength   = 12
	MaximumLength   = 256
	encodedParts    = 4
	leastIterations = 100_000
)

// Password is a parsed password hash, ready to verify against. It holds no password.
type Password struct {
	iterations int
	salt       []byte
	key        []byte
}

// HashPassword derives a new hash for password, with a fresh random salt.
func HashPassword(password string) (string, error) {
	if err := ValidPassword(password); err != nil {
		return "", err
	}
	salt := make([]byte, saltBytes)
	if _, err := rand.Read(salt); err != nil {
		return "", fmt.Errorf("hash password: %w", err)
	}
	key, err := pbkdf2.Key(sha256.New, password, salt, Iterations, keyBytes)
	if err != nil {
		return "", fmt.Errorf("hash password: %w", err)
	}
	return strings.Join([]string{
		scheme,
		strconv.Itoa(Iterations),
		base64.RawURLEncoding.EncodeToString(salt),
		base64.RawURLEncoding.EncodeToString(key),
	}, separator), nil
}

// ValidPassword is the one bound on what an operator may choose. It is a length and nothing else:
// composition rules push people towards predictable substitutions, and this password protects one
// administrative surface behind a rate limit rather than a public account.
func ValidPassword(password string) error {
	switch {
	case len(password) < MinimumLength:
		return fmt.Errorf("an administrator password must be at least %d characters", MinimumLength)
	case len(password) > MaximumLength:
		return fmt.Errorf("an administrator password must be at most %d characters", MaximumLength)
	}
	return nil
}

// ParsePassword reads an encoded hash. A deployment that configured something that is not one is a
// problem at startup, where an operator is looking, rather than a login that can never succeed.
func ParsePassword(encoded string) (*Password, error) {
	parts := strings.Split(strings.TrimSpace(encoded), separator)
	if len(parts) != encodedParts || parts[0] != scheme {
		return nil, fmt.Errorf("must be %[1]s%[2]s<iterations>%[2]s<salt>%[2]s<hash>, "+
			"as `feed-gatewayctl password` prints it", scheme, separator)
	}
	iterations, err := strconv.Atoi(parts[1])
	if err != nil || iterations < leastIterations {
		return nil, fmt.Errorf("must name at least %d iterations", leastIterations)
	}
	salt, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil || len(salt) < 8 {
		return nil, fmt.Errorf("has a salt that is not base64url, or is shorter than 8 bytes")
	}
	key, err := base64.RawURLEncoding.DecodeString(parts[3])
	if err != nil || len(key) != keyBytes {
		return nil, fmt.Errorf("has a hash that is not %d base64url bytes", keyBytes)
	}
	return &Password{iterations: iterations, salt: salt, key: key}, nil
}

// Verify says whether password is the one this hash was made from.
//
// The comparison is constant-time, and the derivation runs whatever the answer will be: returning
// early on a malformed input would make "this deployment has no such operator" measurably faster
// than "that was the wrong password", which is the one thing a login must not leak.
func (p *Password) Verify(password string) bool {
	key, err := pbkdf2.Key(sha256.New, password, p.salt, p.iterations, keyBytes)
	if err != nil {
		return false
	}
	return subtle.ConstantTimeCompare(key, p.key) == 1
}
