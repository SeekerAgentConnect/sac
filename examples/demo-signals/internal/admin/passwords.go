package admin

import (
	"bufio"
	"bytes"
	"fmt"
	"os"
	"strings"
	"sync"
	"time"
	"unicode"

	"golang.org/x/crypto/bcrypt"
)

// HashCost is bcrypt's default. A helper that used a cheaper cost would produce a file the
// running UI could not treat as production-grade.
const HashCost = bcrypt.DefaultCost

// File is a named bcrypt password list. Every request re-reads it when the mtime moves, so
// deleting a line logs that name out of existing sessions on the next request (SEE-126).
type File struct {
	mu      sync.Mutex
	path    string
	mtime   time.Time
	hashes  map[string][]byte
	dummy   []byte
	loadErr error
}

// OpenFile reads the password file once so a missing path fails at start rather than at login.
func OpenFile(path string) (*File, error) {
	dummy, err := bcrypt.GenerateFromPassword([]byte("not-a-password"), HashCost)
	if err != nil {
		return nil, err
	}
	held := &File{path: path, dummy: dummy}
	if err := held.reload(true); err != nil {
		return nil, err
	}
	return held, nil
}

// Check reports whether name and password match a current line. Unknown names still run bcrypt
// against a dummy hash, and the message a caller shows is the same either way.
func (f *File) Check(name, password string) bool {
	if f == nil {
		return false
	}
	_ = f.reload(false)
	f.mu.Lock()
	hash, known := f.hashes[name]
	dummy := f.dummy
	f.mu.Unlock()
	if !known {
		_ = bcrypt.CompareHashAndPassword(dummy, []byte(password))
		return false
	}
	return bcrypt.CompareHashAndPassword(hash, []byte(password)) == nil
}

// Known reports whether name is still on the file. Sessions call this on every request.
func (f *File) Known(name string) bool {
	if f == nil || name == "" {
		return false
	}
	_ = f.reload(false)
	f.mu.Lock()
	defer f.mu.Unlock()
	_, known := f.hashes[name]
	return known
}

func (f *File) reload(required bool) error {
	info, err := os.Stat(f.path)
	if err != nil {
		if required {
			return fmt.Errorf("read %s: %w", f.path, err)
		}
		return nil
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if !required && info.ModTime().Equal(f.mtime) && f.loadErr == nil {
		return nil
	}
	hashes, err := parsePasswords(f.path)
	f.mtime = info.ModTime()
	f.loadErr = err
	if err != nil {
		if required {
			return err
		}
		return nil
	}
	f.hashes = hashes
	return nil
}

func parsePasswords(path string) (map[string][]byte, error) {
	contents, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read %s: %w", path, err)
	}
	hashes := map[string][]byte{}
	scanner := bufio.NewScanner(bytes.NewReader(contents))
	lineNumber := 0
	for scanner.Scan() {
		lineNumber++
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		name, hash, found := strings.Cut(line, ":")
		name, hash = strings.TrimSpace(name), strings.TrimSpace(hash)
		if !found || !ValidName(name) || !validHash(hash) {
			return nil, fmt.Errorf("%s:%d must be name:bcrypt, with a bcrypt hash", path, lineNumber)
		}
		if _, exists := hashes[name]; exists {
			return nil, fmt.Errorf("%s:%d repeats the name %q", path, lineNumber, name)
		}
		hashes[name] = []byte(hash)
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("read %s: %w", path, err)
	}
	return hashes, nil
}

// ValidName is a judge's login name: short, one token, safe in a cookie and a form field.
func ValidName(name string) bool {
	if name == "" || len(name) > 64 {
		return false
	}
	for _, character := range name {
		if unicode.IsLetter(character) || unicode.IsDigit(character) ||
			character == '.' || character == '_' || character == '-' {
			continue
		}
		return false
	}
	return true
}

func validHash(hash string) bool {
	return strings.HasPrefix(hash, "$2a$") || strings.HasPrefix(hash, "$2b$") ||
		strings.HasPrefix(hash, "$2y$")
}

// HashLine is what `copytrading-admin hash` prints: one file line, never a clear password.
func HashLine(name, password string) (string, error) {
	if !ValidName(name) {
		return "", fmt.Errorf("a name is 1 to 64 letters, digits, '.', '_' or '-'")
	}
	if password == "" {
		return "", fmt.Errorf("a password is required")
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), HashCost)
	if err != nil {
		return "", err
	}
	return name + ":" + string(hash), nil
}
