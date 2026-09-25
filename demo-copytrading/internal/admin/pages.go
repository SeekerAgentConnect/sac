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
	Signals     []Item
}

const commonCSS = `
:root { color-scheme: dark; }
* { box-sizing: border-box; }
body { margin: 0; font: 16px/1.45 system-ui, sans-serif; background: #111; color: #eee;
  padding: 24px; max-width: 52rem; }
h1 { font-size: 1.35rem; font-weight: 600; margin: 0 0 0.4rem; }
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
.warn { color: #f0b429; }
code { word-break: break-all; }
.ref { font-family: ui-monospace, monospace; font-size: 0.85rem; word-break: break-all;
  width: 100%; }
table { width: 100%; border-collapse: collapse; font-size: 0.92rem; }
th, td { text-align: left; vertical-align: top; padding: 8px 6px; border-top: 1px solid #333; }
th { color: #bbb; font-weight: 500; }
.bar { display: flex; justify-content: space-between; align-items: center; gap: 12px;
  margin: 0 0 16px; }
`

const loginHTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CopyTrading trader</title>
<style>` + commonCSS + `</style>
</head>
<body>
<h1>CopyTrading trader</h1>
<p class="muted">Sandbox demo for judges. Amounts stay on the phone. This page never sees the API token.</p>
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
<title>CopyTrading trader</title>
<style>` + commonCSS + `</style>
</head>
<body>
`

const homeHTML = `
<div class="bar">
  <div>
    <h1>CopyTrading trader</h1>
    <p class="muted">Signed in as {{.Name}}. Environment {{.Environment}}. Create a sandbox swap; each owner chooses their own amount in SAC. <a href="{{.Path}}/devices">Devices · feed access</a></p>
  </div>
  <form method="post" action="{{.Path}}/logout"><button class="secondary" type="submit">Log out</button></form>
</div>
{{if .Error}}<p class="flash bad">{{.Error}}</p>{{end}}
{{if .Message}}<p class="flash ok">{{.Message}}</p>{{end}}
<div class="card">
  <p>Feed reference — add this in SAC. It carries no secret and grants nothing: this feed is restricted, so each device is verified and then waits for your approval on the Devices page.</p>
  <input class="ref" readonly value="{{.Reference}}" aria-label="Feed reference">
</div>
<div class="card">
  <form method="post" action="{{.Path}}/create">
    <div class="row">
      <label>Pair
        <select name="pair">
          <option value="usdc-sol">USDC → SOL</option>
          <option value="sol-usdc">SOL → USDC</option>
        </select>
      </label>
      <label>Max slippage (bps)
        <input name="slippage" type="number" min="1" max="10000" value="50" required>
      </label>
      <label>Actionable for
        <select name="expires">
          <option value="15m">15 minutes</option>
          <option value="1h" selected>1 hour</option>
          <option value="2h">2 hours</option>
          <option value="4h">4 hours</option>
        </select>
      </label>
    </div>
    <label>Note <input name="note" maxlength="1024" placeholder="optional, shown as the trader's words"></label>
    <p><button type="submit">Publish sandbox swap</button></p>
  </form>
</div>
<div class="card">
  <p>Signals this publisher currently holds.</p>
  {{if not .Signals}}<p class="muted">None yet.</p>{{else}}
  <table>
    <thead><tr><th>Pair</th><th>State</th><th>Publication</th><th>Expires</th><th></th></tr></thead>
    <tbody>
    {{range .Signals}}
      <tr>
        <td>{{.Pair}}{{if .Note}}<div class="muted">{{.Note}}</div>{{end}}<div class="muted">{{.ID}}</div></td>
        <td>{{.Status}}</td>
        <td>{{.Publication}}{{if .Problem}}<div class="muted">{{.Problem}}: {{.Detail}}</div>{{end}}</td>
        <td>{{.ExpiresAt}}</td>
        <td>
          <div class="actions">
            <form method="post" action="{{$.Path}}/signals/{{.ID}}/cancel"><button class="secondary" type="submit">Cancel</button></form>
            <form method="post" action="{{$.Path}}/signals/{{.ID}}/retry"><button class="secondary" type="submit">Retry</button></form>
          </div>
        </td>
      </tr>
    {{end}}
    </tbody>
  </table>
  {{end}}
</div>
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
