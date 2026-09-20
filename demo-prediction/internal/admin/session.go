package admin

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"strconv"
	"strings"
	"time"
)

const (
	cookieName     = "trader_session"
	sessionTTL     = 12 * time.Hour
	sessionVersion = "1"
)

func signSession(secret, name string, now time.Time) (string, error) {
	if !ValidName(name) {
		return "", fmt.Errorf("invalid session name")
	}
	expiry := now.Add(sessionTTL).Unix()
	payload := sessionVersion + "|" + name + "|" + strconv.FormatInt(expiry, 10)
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write([]byte(payload))
	return encode(payload) + "." + encode(string(mac.Sum(nil))), nil
}

func verifySession(secret, cookie string, now time.Time) (string, bool) {
	payload, signature, found := strings.Cut(cookie, ".")
	if !found {
		return "", false
	}
	raw, err := decode(payload)
	if err != nil {
		return "", false
	}
	want, err := decode(signature)
	if err != nil {
		return "", false
	}
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write([]byte(raw))
	if !hmac.Equal(mac.Sum(nil), []byte(want)) {
		return "", false
	}
	parts := strings.Split(raw, "|")
	if len(parts) != 3 || parts[0] != sessionVersion {
		return "", false
	}
	expiry, err := strconv.ParseInt(parts[2], 10, 64)
	if err != nil || now.Unix() >= expiry {
		return "", false
	}
	name := parts[1]
	if !ValidName(name) {
		return "", false
	}
	return name, true
}

func encode(value string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(value))
}

func decode(value string) (string, error) {
	raw, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil {
		return "", err
	}
	return string(raw), nil
}
