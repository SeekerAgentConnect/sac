package admin

import (
	"embed"
	"html/template"
	"strings"
)

// The pages and the files they load, compiled into the binary.
//
// The look is the feed gateway's administration, copied rather than shared: this demo
// builds and ships without the gateway's source, so it carries its own stylesheet, script and the
// two fonts they use. Nothing is fetched from a font service or a CDN, which is what lets the
// content policy stay `default-src 'none'` with only this origin's own files allowed.
//
//go:embed templates/*.html
var templateFiles embed.FS

//go:embed assets/admin.css assets/admin.js assets/roboto-variable.ttf assets/roboto-mono-variable.ttf
var assetFiles embed.FS

var servedAssets = map[string]string{
	"admin.css":                "text/css; charset=utf-8",
	"admin.js":                 "text/javascript; charset=utf-8",
	"roboto-variable.ttf":      "font/ttf",
	"roboto-mono-variable.ttf": "font/ttf",
}

// assetFor is one embedded file and its content type, or false for anything not on the list.
func assetFor(name string) ([]byte, string, bool) {
	kind, served := servedAssets[name]
	if !served {
		return nil, "", false
	}
	body, err := assetFiles.ReadFile("assets/" + name)
	if err != nil {
		return nil, "", false
	}
	return body, kind, true
}

var (
	pageSet   = template.Must(template.ParseFS(templateFiles, "templates/*.html"))
	loginPage = pageSet.Lookup("login.html")
	homePage  = pageSet.Lookup("home.html")
)

type loginView struct {
	Path    string
	Message string
}

// LoggedIn decides whether the frame draws the sidebar.
func (loginView) LoggedIn() bool { return false }

type homeView struct {
	Path        string
	Name        string
	Message     string
	Error       string
	Reference   string
	Environment string
	Query       Query
	Signals     []Item
	Markets     []Market
	Searched    bool
}

// LoggedIn decides whether the frame draws the sidebar.
func (homeView) LoggedIn() bool { return true }

func render(page *template.Template, data any) (string, error) {
	var built strings.Builder
	if err := page.Execute(&built, data); err != nil {
		return "", err
	}
	return built.String(), nil
}
