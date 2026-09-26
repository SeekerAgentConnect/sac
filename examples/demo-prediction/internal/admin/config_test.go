package admin

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestACompleteAdminConfigurationNeedsTheTokenAndThePasswordFile(t *testing.T) {
	dir := t.TempDir()
	token := filepath.Join(dir, "api-token")
	secret := filepath.Join(dir, "session")
	if err := os.WriteFile(token, []byte(strings.Repeat("t", 43)), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(secret, []byte(strings.Repeat("s", 43)), 0o600); err != nil {
		t.Fatal(err)
	}
	settings, problems := Load(func(name string) (string, bool) {
		switch name {
		case "ADMIN_API_URL":
			return "http://127.0.0.1:8092", true
		case "ADMIN_PASSWORDS_FILE":
			return filepath.Join(dir, "admin-passwords"), true
		case "PUBLISHER_API_TOKEN_FILE":
			return token, true
		case "ADMIN_SESSION_SECRET_FILE":
			return secret, true
		default:
			return "", false
		}
	})
	if len(problems) > 0 {
		t.Fatalf("problems %v", problems)
	}
	if settings.PublicPath != DefaultPublicPath || settings.ListenAddress != DefaultListenAddress {
		t.Fatalf("%+v", settings)
	}
	if settings.APIToken != strings.Repeat("t", 43) {
		t.Fatal("token was not read from the file")
	}
}

func TestAdminPasswordsEnvIsTheFileBody(t *testing.T) {
	dir := t.TempDir()
	settings, problems := Load(func(name string) (string, bool) {
		switch name {
		case "ADMIN_API_URL":
			return "http://127.0.0.1:8092", true
		case "ADMIN_PASSWORDS":
			return "judge1:$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUV", true
		case "PUBLISHER_API_TOKEN":
			return strings.Repeat("t", 43), true
		case "ADMIN_SESSION_SECRET":
			return strings.Repeat("s", 43), true
		default:
			return "", false
		}
	})
	if len(problems) > 0 {
		t.Fatalf("problems %v", problems)
	}
	if settings.PasswordsFile == "" {
		t.Fatal("expected a written passwords file")
	}
	_ = dir
	body, err := os.ReadFile(settings.PasswordsFile)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(body), "judge1:") {
		t.Fatalf("file %q", body)
	}
}

func TestAnEmptyAdminEnvironmentNamesWhatIsMissing(t *testing.T) {
	_, problems := Load(func(string) (string, bool) { return "", false })
	for _, name := range []string{
		"ADMIN_API_URL", "ADMIN_PASSWORDS", "PUBLISHER_API_TOKEN", "ADMIN_SESSION_SECRET",
	} {
		found := false
		for _, problem := range problems {
			if strings.Contains(problem, name) {
				found = true
				break
			}
		}
		if !found {
			t.Fatalf("%s is not named among %v", name, problems)
		}
	}
}
