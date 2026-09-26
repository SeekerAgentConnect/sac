package admin

import (
	"html/template"
	"strings"
)

var (
	loginPage = template.Must(template.New("login").Parse(loginHTML))
	homePage  = template.Must(template.New("home").Parse(layoutHTML + homeHTML))
)

type loginView struct {
	Path    string
	Message string
}

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

const commonCSS = `
:root { color-scheme: dark; }
* { box-sizing: border-box; }
body { margin: 0; font: 16px/1.45 system-ui, sans-serif; background: #111; color: #eee;
  padding: 24px; max-width: 64rem; }
h1 { font-size: 1.35rem; font-weight: 600; margin: 0 0 0.4rem; }
h2 { font-size: 1.05rem; font-weight: 600; margin: 0 0 0.6rem; }
p, label { margin: 0 0 0.8rem; }
.muted { color: #bbb; }
.card { background: #1b1b1b; border: 1px solid #333; border-radius: 10px; padding: 16px;
  margin: 0 0 16px; }
label { display: block; }
input, select, textarea, button { font: inherit; color: inherit; background: #111;
  border: 1px solid #555; border-radius: 8px; padding: 8px 10px; width: 100%; }
button { width: auto; background: #2a4a7a; border-color: #2a4a7a; cursor: pointer; }
button.secondary { background: #222; }
.row { display: flex; gap: 12px; flex-wrap: wrap; align-items: end; }
.row > * { flex: 1; min-width: 10rem; }
.actions { display: flex; gap: 8px; }
.flash { padding: 10px 12px; border-radius: 8px; margin: 0 0 12px; }
.ok { background: #14351f; }
.bad { background: #3a1515; }
.ref { font-family: ui-monospace, monospace; font-size: 0.85rem; word-break: break-all;
  width: 100%; }
.qr-box { background: #fff; border-radius: 8px; padding: 12px; width: fit-content;
  margin: 12px 0 0; }
.qr-box svg { display: block; }
table { width: 100%; border-collapse: collapse; font-size: 0.92rem; }
th, td { text-align: left; vertical-align: top; padding: 8px 6px; border-top: 1px solid #333; }
th { color: #bbb; font-weight: 500; }
.bar { display: flex; justify-content: space-between; align-items: center; gap: 12px;
  margin: 0 0 16px; }
.busy-mask { display: none; position: fixed; inset: 0; background: rgba(0,0,0,.55);
  color: #eee; align-items: center; justify-content: center; z-index: 20; font-weight: 600; }
body.busy { cursor: wait; }
body.busy .busy-mask { display: flex; }
body.busy button { pointer-events: none; opacity: .7; }
`

const loginHTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Prediction admin</title>
<style>` + commonCSS + `</style>
</head>
<body>
<h1>Prediction admin</h1>
<p class="muted">Sandbox demo. Side and stake stay on the phone. This page never sees the API token.</p>
<div class="card">
{{if .Message}}<p class="flash bad">{{.Message}}</p>{{end}}
<form method="post" action="{{.Path}}/login" autocomplete="username">
<label>Name <input name="name" required maxlength="64" autofocus></label>
<label>Password <input type="password" name="password" required></label>
<p><button type="submit">Log in</button></p>
</form>
</div>
</body>
</html>`

const layoutHTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Prediction admin</title>
<style>` + commonCSS + `</style>
</head>
<body>
`

const homeHTML = `
<div class="busy-mask" role="status" aria-live="polite">Working…</div>
<div class="bar">
  <div>
    <h1>Prediction admin</h1>
    <p class="muted">Signed in as {{.Name}}. Environment {{.Environment}}. Search live markets and publish one onto the feed; each owner chooses side and stake in SAC.</p>
  </div>
  <form method="post" action="{{.Path}}/logout"><button class="secondary" type="submit">Log out</button></form>
</div>
{{if .Error}}<p class="flash bad">{{.Error}}</p>{{end}}
{{if .Message}}<p class="flash ok">{{.Message}}</p>{{end}}
<div class="card">
  <p>Shared feed reference — add this in SAC. It carries no secret.</p>
  <input class="ref" readonly value="{{.Reference}}" aria-label="Feed reference">
  <div class="qr-box">{{.ReferenceQR}}</div>
  <p class="muted">Or scan it with SAC — Add connection → Scan QR code.</p>
</div>
<div class="card">
  <h2>Published on this feed</h2>
  {{if not .Signals}}<p class="muted">None yet.</p>{{else}}
  <table>
    <thead><tr><th>Market</th><th>State</th><th>Publication</th><th>Expires</th><th></th></tr></thead>
    <tbody>
    {{range .Signals}}
      <tr>
        <td>{{.Market}}{{if .Event}}<div class="muted">{{.Event}}</div>{{end}}{{if .Note}}<div class="muted">{{.Note}}</div>{{end}}<div class="muted">{{.ID}}</div></td>
        <td>{{.Status}}</td>
        <td>{{.Publication}}{{if .Problem}}<div class="muted">{{.Problem}}: {{.Detail}}</div>{{end}}</td>
        <td>{{.ExpiresAt}}</td>
        <td>
          <div class="actions">
            <form method="post" action="{{$.Path}}/signals/{{.ID}}/retry"><button class="secondary" type="submit" data-busy="Retrying…">Retry</button></form>
          </div>
        </td>
      </tr>
    {{end}}
    </tbody>
  </table>
  {{end}}
</div>
<div class="card">
  <h2>Market API</h2>
  <form method="get" action="{{.Path}}">
    <input type="hidden" name="search" value="1">
    <div class="row">
      <label>Source
        <select name="source">
          <option value="polymarket" {{if eq .Query.Source "polymarket"}}selected{{end}}>polymarket</option>
          <option value="kalshi" {{if eq .Query.Source "kalshi"}}selected{{end}}>kalshi</option>
          <option value="bisonfi" {{if eq .Query.Source "bisonfi"}}selected{{end}}>bisonfi</option>
        </select>
      </label>
      <label>Category
        <input name="category" value="{{.Query.Category}}" placeholder="crypto">
      </label>
      <label>Named filter
        <select name="filter">
          <option value="" {{if eq .Query.Filter ""}}selected{{end}}>(none)</option>
          <option value="new" {{if eq .Query.Filter "new"}}selected{{end}}>new</option>
          <option value="live" {{if eq .Query.Filter "live"}}selected{{end}}>live</option>
          <option value="trending" {{if eq .Query.Filter "trending"}}selected{{end}}>trending</option>
          <option value="upcoming" {{if eq .Query.Filter "upcoming"}}selected{{end}}>upcoming</option>
        </select>
      </label>
    </div>
    <div class="row">
      <label>Keywords <input name="keywords" value="{{.Query.Keywords}}" placeholder="eth, sol"></label>
      <label>Tags <input name="tags" value="{{.Query.Tags}}" placeholder="fed-rates"></label>
      <label>State
        <select name="state">
          <option value="open" {{if eq .Query.State "open"}}selected{{end}}>open</option>
          <option value="any" {{if eq .Query.State "any"}}selected{{end}}>any (sandbox)</option>
        </select>
      </label>
    </div>
    <div class="row">
      <label>Soonest close (minutes) <input name="least_close_minutes" value="{{.Query.LeastCloseMinutes}}" placeholder="2"></label>
      <label>Latest close (minutes) <input name="most_close_minutes" value="{{.Query.MostCloseMinutes}}" placeholder="120"></label>
    </div>
    <p><button type="submit" data-busy="Searching…">Search markets</button></p>
  </form>
  {{if .Searched}}
    <p class="muted">Showing up to 10 matching markets.</p>
    {{if not .Markets}}<p class="muted">No markets matched those filters.</p>{{else}}
    <table>
      <thead><tr><th>Market</th><th>Category</th><th>Closes</th><th></th></tr></thead>
      <tbody>
      {{range .Markets}}
        <tr>
          <td>{{if .EventTitle}}{{.EventTitle}} — {{end}}{{.Title}}<div class="muted">{{.MarketID}}{{if .Published}} · already published{{end}}</div></td>
          <td>{{.Category}}{{if .Tags}}<div class="muted">{{.Tags}}</div>{{end}}</td>
          <td>{{.CloseAt}}</td>
          <td>
            {{if not .Published}}
            <form method="post" action="{{$.Path}}/select">
              <input type="hidden" name="market_id" value="{{.MarketID}}">
              <button type="submit" data-busy="Publishing…">Publish to the feed</button>
            </form>
            {{end}}
          </td>
        </tr>
      {{end}}
      </tbody>
    </table>
    {{end}}
  {{end}}
</div>
<script>
document.addEventListener('submit', function (event) {
  document.body.classList.add('busy');
  var mask = document.querySelector('.busy-mask');
  var button = event.submitter;
  if (mask && button && button.getAttribute('data-busy')) {
    mask.textContent = button.getAttribute('data-busy');
  }
});
</script>
</body>
</html>
`

func render(page *template.Template, data any) (string, error) {
	var built strings.Builder
	if err := page.Execute(&built, data); err != nil {
		return "", err
	}
	return built.String(), nil
}
