// Package credential is the one place a publishing credential is made, hashed and named
// (SEE-90, SEE-141).
//
// There are two surfaces that create one — the operator's CLI and the operator's admin UI — and
// they must create the same thing: 32 bytes from crypto/rand as 43 base64url characters, kept only
// as its SHA-256. A second copy of that would be a second answer to "what is a credential", and the
// first time the two disagreed a publisher would hold something the gateway cannot resolve.
//
// Nothing here stores anything and nothing here decides anything. The store keeps the hash
// (internal/storage), the interceptor resolves it (internal/gateway/auth.go), and the raw secret
// exists only in the answer that created it.
package credential

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
)

// Bytes is how much randomness a credential is, and Characters is how long it is written. The
// shape matches the sidecar's phone credential, so an operator who has seen one recognizes the
// other.
const (
	Bytes      = 32
	Characters = 43
)

// New mints one: the secret to hand over once, and the hash to keep.
//
// crypto/rand does not fail on any platform this runs on, and if it ever did, handing out a
// predictable credential would be far worse than stopping — so this panics rather than returning a
// weak secret with an error beside it that a caller might log and continue past.
func New() (secret string, hash []byte) {
	raw := make([]byte, Bytes)
	if _, err := rand.Read(raw); err != nil {
		panic("credential: no randomness available: " + err.Error())
	}
	secret = base64.RawURLEncoding.EncodeToString(raw)
	return secret, Hash(secret)
}

// Hash is what the store keeps and what authentication compares against. It is over the written
// secret rather than the bytes behind it, because the written form is what a publisher configures
// and what arrives in a header.
func Hash(secret string) []byte {
	sum := sha256.Sum256([]byte(secret))
	return sum[:]
}

// ID is the operator's handle for one credential: the first four bytes of its hash, in hex. It is
// not the credential and cannot be turned back into one, and it is short enough to name in a
// revocation.
func ID(hash []byte) string {
	if len(hash) < 4 {
		return ""
	}
	return hex.EncodeToString(hash[:4])
}

// Valid says whether a string has a credential's shape. It is a cheap refusal before a lookup, not
// a claim that the credential exists.
func Valid(secret string) bool {
	if len(secret) != Characters {
		return false
	}
	raw, err := base64.RawURLEncoding.DecodeString(secret)
	return err == nil && len(raw) == Bytes
}
