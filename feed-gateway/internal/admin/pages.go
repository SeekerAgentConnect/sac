package admin

import (
	"context"
	"embed"
	"fmt"
	"html/template"
	"net/http"
	"strings"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/rules"
	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/storage"
)

// The pages and the two files they load, compiled into the binary.
//
// Everything this surface serves ships inside the gateway's own image: one stylesheet, one script,
// no font service, no CDN and no analytics. That is what lets the content policy be
// `default-src 'none'`, and it is why an air-gapped deployment's admin page looks the same as a
// public one's.
//
//go:embed templates/*.html
var templateFiles embed.FS

//go:embed assets/admin.css assets/admin.js
var assetFiles embed.FS

type asset struct {
	body []byte
	kind string
}

var servedAssets = map[string]string{
	"admin.css": "text/css; charset=utf-8",
	"admin.js":  "text/javascript; charset=utf-8",
}

func assetFor(name string) (asset, bool) {
	kind, served := servedAssets[name]
	if !served {
		return asset{}, false
	}
	body, err := assetFiles.ReadFile("assets/" + name)
	if err != nil {
		return asset{}, false
	}
	return asset{body: body, kind: kind}, true
}

type pages struct{ templates *template.Template }

func newPages(at string) (*pages, error) {
	parsed, err := template.New("admin").Funcs(template.FuncMap{
		"instant": func(value time.Time) string {
			return value.UTC().Format("2006-01-02 15:04 UTC")
		},
		"at": func(rest ...string) string { return at + strings.Join(rest, "") },
	}).ParseFS(templateFiles, "templates/*.html")
	if err != nil {
		return nil, fmt.Errorf("admin: %w", err)
	}
	return &pages{templates: parsed}, nil
}

// render writes one page. A template that fails half way through has already written a status and
// part of a body, so the failure is logged by the caller's own error path rather than turned into
// a second answer on the same response.
func (p *pages) render(writer http.ResponseWriter, status int, name string, data any) {
	writer.Header().Set("Content-Type", "text/html; charset=utf-8")
	writer.WriteHeader(status)
	_ = p.templates.ExecuteTemplate(writer, name+".html", data)
}

// Common is what every page has: where it is served, the token its forms carry, and one line about
// what just happened.
type Common struct {
	Path     string
	CSRF     string
	Notice   string
	Problem  string
	LoggedIn bool
	// GatewayURL is this gateway's canonical public origin, shown so an operator can see at a
	// glance which deployment they are administering.
	GatewayURL string
}

func (s *Server) common(at *visit) Common {
	common := Common{Path: s.options.Path, GatewayURL: s.options.PublicURL}
	if at == nil {
		return common
	}
	common.LoggedIn = true
	common.CSRF = at.session.csrf
	common.Notice, at.session.notice = at.session.notice, ""
	return common
}

// MessageView is a page that says one thing: a refusal, a 404, or a failure whose reason is in the
// log rather than on the screen.
type MessageView struct {
	Common
	Title string
	Body  string
	Back  bool
	Link  string
}

func (s *Server) message(title, body string, back bool, link string) MessageView {
	return MessageView{Common: s.common(nil), Title: title, Body: body, Back: back, Link: link}
}

// LoginView is the login form, with the one thing it will say when it refuses.
type LoginView struct{ Common }

func (s *Server) loginView(problem string) LoginView {
	common := s.common(nil)
	common.Problem = problem
	return LoginView{Common: common}
}

// PublisherRow is one registered publisher as the operator sees it: what the operator recorded,
// and what the store can actually prove about it.
//
// There is nothing here about who is subscribed, and there could not be: no row in this database
// names a subscriber. "Publishing enabled" means a credential exists that would be accepted, not
// that anything is connected — a publisher calls an HTTP API when it has something to say and
// holds no connection in between, so a page that said "online" would be inventing it.
type PublisherRow struct {
	ServerID string
	Label    string
	Host     string
	Channel  string
	Created  time.Time
	Active   int
	Revoked  int

	// What the feed itself holds for this publisher.
	DisplayName  string
	HasManifest  bool
	ManifestAt   time.Time
	Publications int
	Sequence     uint64
}

// Credentials is the sentence about whether this publisher could publish right now.
func (p PublisherRow) Credentials() string {
	if p.Active > 0 {
		return "Publishing enabled"
	}
	return "No active credentials"
}

// Publications names what the gateway durably holds from this publisher.
func (p PublisherRow) Published() string {
	switch {
	case !p.HasManifest && p.Publications == 0:
		return "No publications yet"
	case !p.HasManifest:
		return fmt.Sprintf("%d publication(s), no manifest yet", p.Publications)
	case p.Publications == 0:
		return "Manifest received, no feed items yet"
	default:
		return fmt.Sprintf("Manifest received, %d feed item(s)", p.Publications)
	}
}

// CredentialRow is one credential, without the credential.
type CredentialRow struct {
	ID      string
	Label   string
	Created time.Time
	Revoked *time.Time
}

func (c CredentialRow) State() string {
	if c.Revoked != nil {
		return "revoked " + c.Revoked.UTC().Format("2006-01-02 15:04 UTC")
	}
	return "in use"
}

// Connection is what a developer needs from the operator, and nothing they do not: the identity
// that names them, the addresses their backend uses, and the feed reference anyone may share.
//
// The two origins are different things and are labelled as such. GatewayURL is the public identity
// every manifest must name and every phone compares; PublisherURL is where the publication itself
// is sent. A deployment that keeps its publisher listener on a private network sets the second one
// explicitly, which is why this is configuration rather than a string built from a listener's bind
// address: handing an external developer a container-local hostname is the one addressing mistake
// with no error message at either end.
type Connection struct {
	ServerID     string
	Channel      string
	GatewayURL   string
	PublisherURL string
	ManifestCall string
	PublishCall  string
}

func (s *Server) connection(registration storage.Registration) Connection {
	publisher := s.options.PublisherURL
	if publisher == "" {
		publisher = s.options.PublicURL
	}
	return Connection{
		ServerID:     registration.ServerID,
		Channel:      rules.ChannelFor(registration.ServerID),
		GatewayURL:   s.options.PublicURL,
		PublisherURL: publisher,
		ManifestCall: publisher + "/seekervault.gateway.v1.PublisherService/PublishManifest",
		PublishCall:  publisher + "/seekervault.gateway.v1.PublisherService/PublishRequest",
	}
}

// RegistrationForm is the "Add server" form, kept so a refusal re-renders what was typed rather
// than making the operator type it again.
type RegistrationForm struct {
	ServerID string
	Label    string
	Host     string
	Generate bool
}

func registrationForm(request *http.Request) RegistrationForm {
	return RegistrationForm{
		ServerID: strings.TrimSpace(request.PostFormValue("server")),
		Label:    request.PostFormValue("label"),
		Host:     request.PostFormValue("host"),
		Generate: request.PostFormValue("generate") != "",
	}
}

// parse turns the form into a registration, says whether the ID was minted here, or names the one
// thing that is wrong with it.
func (f RegistrationForm) parse() (storage.Registration, bool, string) {
	var registration storage.Registration
	generated := false
	switch {
	case f.Generate && f.ServerID != "":
		return registration, false, "Either give the publisher's existing server ID or ask for a " +
			"new one — not both."
	case f.Generate:
		registration.ServerID, generated = newServerID(), true
	case !rules.IsID(f.ServerID):
		return registration, false, "A server ID is a lowercase UUID, which is what the " +
			"publisher's manifest and every publication of its own has to name. " +
			"Tick “generate one” if this publisher does not have one yet."
	default:
		registration.ServerID = f.ServerID
	}

	note, problem := label(f.Label, "")
	if problem != "" {
		return registration, generated, problem
	}
	if note == "" {
		return registration, generated, "Give this publisher a label, so the list is readable."
	}
	registration.Label = note

	host, err := Host(f.Host)
	if err != nil {
		return registration, generated, strings.ToUpper(err.Error()[:1]) + err.Error()[1:] + "."
	}
	registration.Host = host
	return registration, generated, ""
}

// Reveal is the one-time credential page: the secret, the identity it belongs to, and everything
// the developer needs to configure alongside it.
type Reveal struct {
	Common
	Action       string
	Registration storage.Registration
	Generated    bool
	CredentialID string
	Secret       string
	Connection   Connection
}

// ServersView is the list, with the "Add server" form under it.
type ServersView struct {
	Common
	Publishers []PublisherRow
	Form       RegistrationForm
}

// ServerView is one publisher: what is recorded, what it has published, and the three things that
// can be done to it.
type ServerView struct {
	Common
	Publisher   PublisherRow
	Credentials []CredentialRow
	Connection  Connection
}

func (s *Server) serversView(ctx context.Context, at *visit) (ServersView, error) {
	view := ServersView{Common: s.common(at)}
	publishers, err := s.options.Store.Publishers(ctx)
	if err != nil {
		return view, err
	}
	for _, publisher := range publishers {
		row, err := s.row(ctx, publisher)
		if err != nil {
			return view, err
		}
		view.Publishers = append(view.Publishers, row)
	}
	return view, nil
}

func (s *Server) serverView(ctx context.Context, at *visit, serverID string) (ServerView, error) {
	view := ServerView{Common: s.common(at)}
	if !rules.IsID(serverID) {
		return view, storage.ErrNoPublisher
	}
	publisher, err := s.options.Store.Publisher(ctx, serverID)
	if err != nil {
		return view, err
	}
	if publisher == nil {
		return view, storage.ErrNoPublisher
	}
	if view.Publisher, err = s.row(ctx, *publisher); err != nil {
		return view, err
	}
	credentials, err := s.options.Store.Credentials(ctx, serverID)
	if err != nil {
		return view, err
	}
	for _, one := range credentials {
		view.Credentials = append(view.Credentials, CredentialRow{
			ID: one.ID, Label: one.Label, Created: one.CreatedAt, Revoked: one.RevokedAt,
		})
		if one.RevokedAt != nil {
			view.Publisher.Revoked++
		}
	}
	view.Connection = s.connection(storage.Registration{
		ServerID: publisher.ServerID, Label: publisher.Label, Host: publisher.Host,
	})
	return view, nil
}

// row joins what the operator recorded with what the feed actually holds. Both reads are of
// durable state: a manifest row and a count of publications, which is the only evidence this
// service has that a publisher has ever said anything.
func (s *Server) row(ctx context.Context, publisher storage.Publisher) (PublisherRow, error) {
	channel := rules.ChannelFor(publisher.ServerID)
	row := PublisherRow{
		ServerID: publisher.ServerID,
		Label:    publisher.Label,
		Host:     publisher.Host,
		Channel:  channel,
		Created:  publisher.CreatedAt,
		Active:   publisher.Active,
	}
	manifest, err := s.options.Store.Manifest(ctx, publisher.ServerID)
	if err != nil {
		return row, err
	}
	if manifest != nil {
		row.HasManifest = true
		row.DisplayName = manifest.Document.GetDisplayName()
		row.ManifestAt = manifest.UpdatedAt
	}
	if row.Publications, err = s.options.Store.Publications(ctx, channel); err != nil {
		return row, err
	}
	if row.Sequence, err = s.options.Store.Sequence(ctx, channel); err != nil {
		return row, err
	}
	return row, nil
}
