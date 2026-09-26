package access

import (
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"math/big"
	"strconv"
	"strings"
	"time"
)

// The proofs a restricted feed's onboarding rests on (SEE-156, docs/wiki/restricted-feeds.md).
//
// Two keys are involved, and they are kept apart on purpose:
//
//   - the **wallet**, an Ed25519 Solana key the owner holds in their wallet app. It signs one thing:
//     a plain-text challenge that says it is not a transaction. That signature proves the owner
//     controls the wallet, and it binds this device's key to the attempt.
//   - the **device key**, a P-256 key the app generated for this feed and keeps in the phone's
//     keystore. It is what makes an installation this installation — a label or an identifier the
//     phone reports is a claim, a signature from a key the wallet bound is a proof — and it signs
//     every later step (asking for the decision, redeeming the invitation), so the owner is asked
//     for exactly one wallet signature.
//
// The challenge text is built from fields, never taken from somebody else's text: the publisher
// and the phone each rebuild it from the same values and must arrive at the same bytes. A test on
// each side pins the same fixture (fixtures/restricted-feeds/challenge.json).

// MessageVersion names the challenge format. It is the first line's last word, so a change to the
// format is a change to what is signed.
const MessageVersion = "1"

// Challenge is what the wallet signs, as fields.
type Challenge struct {
	// The registered authentication origin: the publisher this proof is for.
	AuthOrigin string
	// The feed's channel, "server/<server_id>".
	Channel string
	// The wallet's base58 address.
	Wallet string
	// The device key's fingerprint: the installation this proof binds.
	Installation string
	// The attempt, a lowercase UUID, and a fresh random nonce.
	Attempt string
	Nonce   string
	// When the challenge was issued and when it stops being accepted, to the second.
	IssuedAt  time.Time
	ExpiresAt time.Time
}

// Message is the exact text the wallet signs, as UTF-8 bytes. It is ASCII by construction — every
// field is an origin, an identifier or a timestamp — so no normalization can make the two sides
// disagree.
func (c Challenge) Message() []byte {
	var text strings.Builder
	text.WriteString("Seeker Agent Connect feed access v" + MessageVersion + "\n")
	text.WriteString("\n")
	text.WriteString(c.AuthOrigin + " asks you to prove that you control this wallet, so it can decide whether this device may read its restricted feed.\n")
	text.WriteString("\n")
	text.WriteString("This is not a transaction. Signing it moves no funds and approves nothing.\n")
	text.WriteString("\n")
	text.WriteString("Wallet: " + c.Wallet + "\n")
	text.WriteString("Feed: " + c.Channel + "\n")
	text.WriteString("Device key: " + c.Installation + "\n")
	text.WriteString("Attempt: " + c.Attempt + "\n")
	text.WriteString("Nonce: " + c.Nonce + "\n")
	text.WriteString("Issued: " + c.IssuedAt.UTC().Format(time.RFC3339) + "\n")
	text.WriteString("Expires: " + c.ExpiresAt.UTC().Format(time.RFC3339))
	return []byte(text.String())
}

// StatusStatement is what the device key signs to ask for its decision: the request and the moment,
// so a recorded question cannot be asked again later than the skew allows.
func StatusStatement(requestID string, atMillis int64) []byte {
	return []byte("seekervault-feed-access-status:v1\n" + requestID + "\n" + strconv.FormatInt(atMillis, 10))
}

// RedeemStatement is what the device key signs to redeem an invitation. The invitation is single
// use, so the moment only bounds how long a recorded redemption is worth anything.
func RedeemStatement(channel, invitation string, atMillis int64) []byte {
	return []byte("seekervault-feed-access-redeem:v1\n" + channel + "\n" + invitation + "\n" +
		strconv.FormatInt(atMillis, 10))
}

var (
	errNotAWallet    = errors.New("not a Solana wallet address")
	errNotADeviceKey = errors.New("not a P-256 public key")
)

// WalletKey decodes a base58 Solana address into the Ed25519 key it is.
func WalletKey(address string) (ed25519.PublicKey, error) {
	raw, err := base58(address)
	if err != nil || len(raw) != ed25519.PublicKeySize {
		return nil, errNotAWallet
	}
	return ed25519.PublicKey(raw), nil
}

// VerifyWallet says whether the wallet signed exactly this message.
func VerifyWallet(address string, message, signature []byte) bool {
	key, err := WalletKey(address)
	if err != nil || len(signature) != ed25519.SignatureSize {
		return false
	}
	return ed25519.Verify(key, message, signature)
}

// DeviceKey parses a device key: an X.509 SubjectPublicKeyInfo holding an uncompressed P-256 key,
// which is what an Android keystore key's getEncoded() answers.
func DeviceKey(der []byte) (*ecdsa.PublicKey, error) {
	if len(der) == 0 || len(der) > 256 {
		return nil, errNotADeviceKey
	}
	parsed, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, errNotADeviceKey
	}
	key, ok := parsed.(*ecdsa.PublicKey)
	if !ok || key.Curve != elliptic.P256() {
		return nil, errNotADeviceKey
	}
	return key, nil
}

// VerifyDevice says whether the device key signed exactly this message (ECDSA over SHA-256, the
// DER signature Android's SHA256withECDSA answers).
func VerifyDevice(der, message, signature []byte) bool {
	key, err := DeviceKey(der)
	if err != nil || len(signature) == 0 || len(signature) > 128 {
		return false
	}
	sum := sha256.Sum256(message)
	return ecdsa.VerifyASN1(key, sum[:], signature)
}

// Installation is a device key's fingerprint: the first 10 bytes of the SHA-256 of its encoding,
// in hex. It is what the operator's page names a device by, and what the wallet's signature binds.
func Installation(der []byte) string {
	sum := sha256.Sum256(der)
	return hex.EncodeToString(sum[:10])
}

const alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

// base58 is Bitcoin's alphabet, which is Solana's. Thirty lines rather than a dependency, for the
// same reason the gateway signs its own tickets by hand: this is the whole of what is needed.
func base58(text string) ([]byte, error) {
	if text == "" || len(text) > 64 {
		return nil, errNotAWallet
	}
	value := new(big.Int)
	radix := big.NewInt(58)
	for _, character := range []byte(text) {
		digit := strings.IndexByte(alphabet, character)
		if digit < 0 {
			return nil, errNotAWallet
		}
		value.Mul(value, radix)
		value.Add(value, big.NewInt(int64(digit)))
	}
	decoded := value.Bytes()
	zeros := 0
	for zeros < len(text) && text[zeros] == '1' {
		zeros++
	}
	return append(make([]byte, zeros), decoded...), nil
}

// encode58 is the inverse, for tests and for the one place an address is rebuilt from a key.
func encode58(raw []byte) string {
	value := new(big.Int).SetBytes(raw)
	radix := big.NewInt(58)
	var digits []byte
	remainder := new(big.Int)
	for value.Sign() > 0 {
		value.DivMod(value, radix, remainder)
		digits = append(digits, alphabet[remainder.Int64()])
	}
	for _, one := range raw {
		if one != 0 {
			break
		}
		digits = append(digits, '1')
	}
	for left, right := 0, len(digits)-1; left < right; left, right = left+1, right-1 {
		digits[left], digits[right] = digits[right], digits[left]
	}
	return string(digits)
}

// Address is a wallet key written as the base58 address a wallet app shows.
func Address(key ed25519.PublicKey) string { return encode58(key) }
