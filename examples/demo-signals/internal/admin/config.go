package admin

import (
	"fmt"
	"os"
	"strings"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/config"
)

// Config is the trader UI's deployment (SEE-126). It is a client of the CopyTrading API, not a
// second writer: the token here is presented to that API, never to a browser.
type Config struct {
	ListenAddress string
	PublicPath    string
	APIURL        string
	APIToken      string
	PasswordsFile string
	SessionSecret string
}

const (
	DefaultListenAddress = "127.0.0.1:8096"
	DefaultPublicPath    = "/trader"
	LeastSecretLength    = 32
)

// Load reads the environment. Every problem is reported at once, same habit as the templates.
func Load(lookup config.Lookup) (*Config, []string) {
	read := &reader{lookup: lookup}
	settings := &Config{
		ListenAddress: read.text("ADMIN_LISTEN_ADDRESS", DefaultListenAddress),
		PublicPath:    read.text("ADMIN_PUBLIC_PATH", DefaultPublicPath),
		APIURL:        strings.TrimRight(read.text("ADMIN_API_URL", ""), "/"),
		PasswordsFile: read.text("ADMIN_PASSWORDS_FILE", ""),
	}
	if settings.PublicPath == "" || settings.PublicPath[0] != '/' || strings.Contains(settings.PublicPath, "//") {
		read.note("ADMIN_PUBLIC_PATH must be an absolute path such as /trader, with no host")
	} else {
		settings.PublicPath = strings.TrimRight(settings.PublicPath, "/")
		if settings.PublicPath == "" {
			settings.PublicPath = "/"
		}
	}
	if settings.APIURL == "" {
		read.note("ADMIN_API_URL must be the CopyTrading API origin this UI calls, for example " +
			"http://127.0.0.1:8092")
	} else if _, err := config.Reachable(settings.APIURL); err != nil {
		read.note("ADMIN_API_URL %v", err)
	}
	passwords := read.text("ADMIN_PASSWORDS", "")
	switch {
	case passwords != "" && settings.PasswordsFile != "":
		read.note("ADMIN_PASSWORDS and ADMIN_PASSWORDS_FILE must not both be set: name the file contents or the file, not both")
	case settings.PasswordsFile == "" && passwords == "":
		read.note("ADMIN_PASSWORDS_FILE must be the path of the named bcrypt password file, or ADMIN_PASSWORDS the file's contents")
	case passwords != "":
		path, err := writePasswords(passwords)
		if err != nil {
			read.note("ADMIN_PASSWORDS cannot be written: %v", err)
		} else {
			settings.PasswordsFile = path
		}
	}
	settings.APIToken = read.secret("PUBLISHER_API_TOKEN")
	switch {
	case settings.APIToken == "":
		read.note("PUBLISHER_API_TOKEN must be set, or PUBLISHER_API_TOKEN_FILE to the path of " +
			"the file holding it: this UI presents it to the CopyTrading API and never to a browser")
	case !headerSafe(settings.APIToken):
		read.note("PUBLISHER_API_TOKEN must be one word with no whitespace or control characters")
	case len(settings.APIToken) < config.LeastTokenLength:
		read.note("PUBLISHER_API_TOKEN must be at least %d characters", config.LeastTokenLength)
	}
	settings.SessionSecret = read.secret("ADMIN_SESSION_SECRET")
	switch {
	case settings.SessionSecret == "":
		read.note("ADMIN_SESSION_SECRET must be set, or ADMIN_SESSION_SECRET_FILE to the path of " +
			"the file holding it")
	case len(settings.SessionSecret) < LeastSecretLength:
		read.note("ADMIN_SESSION_SECRET must be at least %d characters", LeastSecretLength)
	}
	if len(read.problems) > 0 {
		return nil, read.problems
	}
	return settings, nil
}

type reader struct {
	lookup   config.Lookup
	problems []string
}

func (r *reader) note(format string, argument ...any) {
	r.problems = append(r.problems, fmt.Sprintf(format, argument...))
}

func (r *reader) text(name, fallback string) string {
	value, set := r.lookup(name)
	if !set || strings.TrimSpace(value) == "" {
		return fallback
	}
	return strings.TrimSpace(value)
}

func (r *reader) secret(name string) string {
	value := r.text(name, "")
	path := r.text(name+"_FILE", "")
	switch {
	case value != "" && path != "":
		r.note("%s and %s_FILE must not both be set: name the secret or the file it is in, not both",
			name, name)
		return ""
	case path != "":
		contents, err := os.ReadFile(path)
		if err != nil {
			r.note("%s_FILE %s cannot be read: %v", name, path, err)
			return ""
		}
		return strings.TrimSpace(string(contents))
	default:
		return value
	}
}

func writePasswords(contents string) (string, error) {
	dir := "/data"
	if _, err := os.Stat(dir); err != nil {
		dir = os.TempDir()
	}
	path := dir + "/admin-passwords"
	if err := os.WriteFile(path, []byte(contents+"\n"), 0o600); err != nil {
		return "", err
	}
	return path, nil
}

func headerSafe(value string) bool {
	for _, character := range value {
		if character < 0x21 || character > 0x7e {
			return false
		}
	}
	return value != ""
}
