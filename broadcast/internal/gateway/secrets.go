package gateway

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
)

func randomToken() (string, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", fmt.Errorf("mint token: %w", err)
	}
	return base64.RawURLEncoding.EncodeToString(raw), nil
}

func randomID() (string, error) {
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		return "", fmt.Errorf("mint id: %w", err)
	}
	raw[6] = (raw[6] & 0x0f) | 0x40
	raw[8] = (raw[8] & 0x3f) | 0x80
	written := hex.EncodeToString(raw)
	return fmt.Sprintf("%s-%s-%s-%s-%s", written[0:8], written[8:12], written[12:16],
		written[16:20], written[20:32]), nil
}

func validToken(token string) bool {
	if len(token) != 43 {
		return false
	}
	raw, err := base64.RawURLEncoding.DecodeString(token)
	return err == nil && len(raw) == 32
}
