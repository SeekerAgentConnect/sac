package admin

import (
	"encoding/json"
	"fmt"
	"html/template"
	"net/http"
	"net/url"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

// The Devices / Feed access page (SEE-156, docs/wiki/restricted-feeds.md#the-operators-page).
//
// This demo's feed is restricted: a phone proves it controls a wallet, and the operator decides
// here whether that device may read. Like the signal page, this is a client of the publisher's own
// token-protected API (/v1/access/...) — the one process that writes the database — so the browser
// never holds the API token, the gateway credential or a session.
//
// What the page promises is what the gateway enforces. A revocation is shown as pending until the
// gateway confirms it, because until then the device can still read; a grant the gateway has not
// confirmed is shown as pending for the same reason in the other direction.

// Device is one access request as the page shows it.
type Device struct {
	RequestID    string `json:"request_id"`
	Wallet       string `json:"wallet"`
	Installation string `json:"installation"`
	Label        string `json:"label"`
	State        string `json:"state"`
	Access       string `json:"access"`
	RequestedAt  string `json:"requested_at"`
	DecidedAt    string `json:"decided_at"`
	DecidedBy    string `json:"decided_by"`
	RevokedAt    string `json:"revoked_at"`
	Invitation   *struct {
		State     string `json:"state"`
		ExpiresAt string `json:"expires_at"`
		Link      string `json:"link"`
	} `json:"invitation"`
	Grant *struct {
		ID        string `json:"id"`
		State     string `json:"state"`
		ExpiresAt string `json:"expires_at"`
		Gateway   string `json:"gateway"`
		Attempts  int    `json:"attempts"`
		Error     string `json:"error"`
	} `json:"grant"`
}

// Sentence is where this device's access stands, in words an operator can act on.
func (d Device) Sentence() string {
	switch d.Access {
	case "pending_approval":
		return "Pending approval"
	case "invited":
		return "Approved — waiting for the device to redeem its invitation"
	case "invitation_expired":
		return "Approved — the invitation expired before the device used it; reissue it"
	case "grant_pending":
		return "Approved — the gateway has not confirmed the grant yet" + d.attempts()
	case "connected":
		return "Connected — the gateway admits this device until " + d.grantUntil()
	case "expired":
		return "Grant expired at the gateway — renewal has not reached it" + d.attempts()
	case "revocation_pending":
		return "Revocation pending at the gateway — this device can still read until the gateway confirms" +
			d.attempts()
	case "revoked":
		return "Revoked — the gateway confirmed"
	case "rejected":
		return "Rejected"
	}
	return d.Access
}

// Bad says the state needs the operator's attention.
func (d Device) Bad() bool {
	return d.Access == "revocation_pending" || d.Access == "grant_pending" || d.Access == "expired" ||
		d.Access == "invitation_expired"
}

func (d Device) attempts() string {
	if d.Grant == nil || d.Grant.Attempts == 0 {
		return ""
	}
	text := fmt.Sprintf(" (attempt %d", d.Grant.Attempts)
	if d.Grant.Error != "" {
		text += ": " + d.Grant.Error
	}
	return text + ")"
}

func (d Device) grantUntil() string {
	if d.Grant == nil {
		return ""
	}
	return d.Grant.ExpiresAt + ", renewed while approved"
}

// Link is the live invitation as a link, or empty.
func (d Device) Link() string {
	if d.Invitation == nil {
		return ""
	}
	return d.Invitation.Link
}

func (d Device) Pending() bool   { return d.State == "pending" }
func (d Device) Approved() bool  { return d.State == "approved" }
func (d Device) Revocable() bool { return d.State == "pending" || d.State == "approved" }

// Reissuable says an approved device has no invitation it could still use and no live grant.
func (d Device) Reissuable() bool {
	return d.State == "approved" && d.Access != "connected" && d.Access != "invited" &&
		d.Access != "grant_pending"
}

type devicesView struct {
	Path    string
	Name    string
	Message string
	Error   string
	Feed    string
	Devices []Device
}

var devicesPage = template.Must(template.New("devices").Parse(layoutHTML + devicesHTML))

// devices lists every request.
func (a *API) devices() (string, []Device, error) {
	status, body, err := a.call(http.MethodGet, "/v1/access/devices", "", nil)
	if err != nil {
		return "", nil, err
	}
	if status < 200 || status > 299 {
		return "", nil, decodeError(status, body)
	}
	var answer struct {
		Feed    string   `json:"feed"`
		Devices []Device `json:"devices"`
	}
	if err := json.Unmarshal(body, &answer); err != nil {
		return "", nil, fmt.Errorf("the publisher answered something unreadable")
	}
	return answer.Feed, answer.Devices, nil
}

// deviceAction is approve, reject, revoke or reissue.
func (a *API) deviceAction(id, action, by string) error {
	if !signals.IsID(id) {
		return fmt.Errorf("that is not a request ID")
	}
	body, _ := json.Marshal(map[string]string{"by": by})
	status, answer, err := a.call(http.MethodPost, "/v1/access/devices/"+id+"/"+action, "", body)
	if err != nil {
		return err
	}
	if status < 200 || status > 299 {
		return decodeError(status, answer)
	}
	return nil
}

func (a *API) revokeWallet(wallet string) (int, error) {
	status, body, err := a.call(http.MethodPost, "/v1/access/wallets/"+url.PathEscape(wallet)+"/revoke",
		"", []byte("{}"))
	if err != nil {
		return 0, err
	}
	if status < 200 || status > 299 {
		return 0, decodeError(status, body)
	}
	var answer struct {
		Revoked int `json:"revoked"`
	}
	_ = json.Unmarshal(body, &answer)
	return answer.Revoked, nil
}

func (s *Server) showDevices(writer http.ResponseWriter, request *http.Request) {
	name, ok := s.identity(request)
	if !ok {
		http.Redirect(writer, request, s.path+"/login", http.StatusSeeOther)
		return
	}
	fail := request.URL.Query().Get("error")
	feed, devices, err := s.api.devices()
	if err != nil && fail == "" {
		fail = err.Error()
	}
	s.page(writer, http.StatusOK, devicesPage, devicesView{
		Path: s.path, Name: name, Message: request.URL.Query().Get("ok"), Error: fail,
		Feed: feed, Devices: devices,
	})
}

var deviceActions = map[string]string{
	"approve": "approved; the device receives a single-use invitation",
	"reject":  "rejected; the device receives no grant",
	"revoke":  "revoked; the gateway is being told — the page says when it confirms",
	"reissue": "a fresh invitation was issued; the previous one no longer works",
}

func (s *Server) actOnDevice(writer http.ResponseWriter, request *http.Request) {
	name, authed := s.guard(writer, request)
	if !authed {
		return
	}
	if !s.mutates.Allow("mutate:" + name) {
		s.devicesErr(writer, request, "too many changes; try later")
		return
	}
	action := request.PathValue("action")
	done, known := deviceActions[action]
	if !known {
		s.devicesErr(writer, request, "that is not an action")
		return
	}
	if err := s.api.deviceAction(request.PathValue("id"), action, name); err != nil {
		s.devicesErr(writer, request, err.Error())
		return
	}
	s.devicesOK(writer, request, done)
}

func (s *Server) revokeWallet(writer http.ResponseWriter, request *http.Request) {
	name, authed := s.guard(writer, request)
	if !authed {
		return
	}
	if !s.mutates.Allow("mutate:" + name) {
		s.devicesErr(writer, request, "too many changes; try later")
		return
	}
	revoked, err := s.api.revokeWallet(request.PathValue("wallet"))
	if err != nil {
		s.devicesErr(writer, request, err.Error())
		return
	}
	s.devicesOK(writer, request, fmt.Sprintf(
		"revoked %d device(s) of that wallet; the gateway is being told", revoked))
}

func (s *Server) devicesOK(writer http.ResponseWriter, request *http.Request, message string) {
	http.Redirect(writer, request, s.path+"/devices?ok="+queryToken(message), http.StatusSeeOther)
}

func (s *Server) devicesErr(writer http.ResponseWriter, request *http.Request, message string) {
	http.Redirect(writer, request, s.path+"/devices?error="+queryToken(message), http.StatusSeeOther)
}

const devicesHTML = `
<div class="bar">
  <div>
    <h1>Devices · feed access</h1>
    <p class="muted">Signed in as {{.Name}}. This feed is restricted: a device reads it only after it proved its wallet and you approved it. <a href="{{.Path}}">Signals</a></p>
  </div>
  <form method="post" action="{{.Path}}/logout"><button class="secondary" type="submit">Log out</button></form>
</div>
{{if .Error}}<p class="flash bad">{{.Error}}</p>{{end}}
{{if .Message}}<p class="flash ok">{{.Message}}</p>{{end}}
<div class="card">
  <p>Every request below was signed by the wallet it names. The device label is what the phone calls itself — a claim, not a proof; the installation is the fingerprint of the device key the wallet signed for. Approving one device never approves another, even of the same wallet.</p>
  {{if not .Devices}}<p class="muted">No device has asked for access yet.</p>{{else}}
  <table>
    <thead><tr><th>Wallet</th><th>Device</th><th>Requested</th><th>Access</th><th></th></tr></thead>
    <tbody>
    {{range .Devices}}
      <tr>
        <td><code title="{{.Wallet}}">{{.Wallet}}</code></td>
        <td>{{.Label}}<div class="muted">label (user-supplied)</div><div class="muted">installation {{.Installation}}</div></td>
        <td>{{.RequestedAt}}{{if .DecidedAt}}<div class="muted">decided {{.DecidedAt}}{{if .DecidedBy}} by {{.DecidedBy}}{{end}}</div>{{end}}{{if .RevokedAt}}<div class="muted">revoked {{.RevokedAt}}</div>{{end}}</td>
        <td><span class="{{if .Bad}}warn{{end}}">{{.Sentence}}</span>
          {{if .Link}}<div class="muted">Invitation (single use, for this device only, until {{.Invitation.ExpiresAt}}):</div><input class="ref" readonly value="{{.Link}}" aria-label="Invitation link">{{end}}
        </td>
        <td>
          <div class="actions">
            {{if .Pending}}
            <form method="post" action="{{$.Path}}/devices/{{.RequestID}}/approve"><button type="submit">Approve</button></form>
            <form method="post" action="{{$.Path}}/devices/{{.RequestID}}/reject"><button class="secondary" type="submit">Reject</button></form>
            {{end}}
            {{if .Reissuable}}
            <form method="post" action="{{$.Path}}/devices/{{.RequestID}}/reissue"><button class="secondary" type="submit">Reissue invitation</button></form>
            {{end}}
            {{if .Revocable}}
            <form method="post" action="{{$.Path}}/devices/{{.RequestID}}/revoke"><button class="secondary" type="submit">Revoke device</button></form>
            <form method="post" action="{{$.Path}}/wallets/{{.Wallet}}/revoke"><button class="secondary" type="submit">Revoke all devices of this wallet</button></form>
            {{end}}
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
