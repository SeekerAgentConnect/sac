package admin

import (
	"embed"
	"html/template"
	"net/url"
	"strings"
)

// The pages and the files they load, compiled into the binary.
//
// The look is the feed gateway's administration (SEE-164), copied rather than shared: this demo
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
	Path      string
	Name      string
	Message   string
	Error     string
	Reference string
	Networks  string
	Query     Query
	// The proposals on the feed now, and the ones withdrawn or expired.
	Signals  []Item
	Past     []Item
	Coverage *Coverage
	Markets  []Market
	Searched bool
	Presets  []Preset
}

// Preset is a named search the form offers as one click: a link that runs it.
type Preset struct {
	Label string
	Query Query
}

// presets are the searches an operator reaches for often enough to name.
var presets = []Preset{
	{Label: "Sports — NBA", Query: Query{Source: "polymarket", Category: "sports", Keywords: "nba",
		State: "open"}},
}

// Href is the preset as a search link on this page.
func (p Preset) Href(path string) template.URL {
	values := url.Values{"search": {"1"}}
	set := func(key, value string) {
		if value != "" {
			values.Set(key, value)
		}
	}
	set("source", p.Query.Source)
	set("category", p.Query.Category)
	set("filter", p.Query.Filter)
	set("keywords", p.Query.Keywords)
	set("tags", p.Query.Tags)
	set("least_close_minutes", p.Query.LeastCloseMinutes)
	set("most_close_minutes", p.Query.MostCloseMinutes)
	set("state", p.Query.State)
	// The path is the deployment's own configured prefix and the values are encoded here, so the
	// link is safe to mark as a URL.
	return template.URL(path + "?" + values.Encode() + "#market-search")
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
