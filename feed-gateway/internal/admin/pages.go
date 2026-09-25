package admin

import (
	"context"
	"embed"
	"fmt"
	"html/template"
	"net/http"
	"strings"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/pushrelay"
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

	// What the operator has enabled, and how many relay credentials would be accepted. The two
	// counts are separate because the two grants are (SEE-144).
	Publishing  bool
	Relaying    bool
	ActiveRelay int

	// What the feed itself holds for this publisher.
	DisplayName  string
	HasManifest  bool
	ManifestAt   time.Time
	Publications int
	Sequence     uint64

	// What the relay holds for this server: counts and instants, never a target, a handle or an
	// installation identity.
	Relay storage.RelayStatus

	// Who may read the feed (SEE-156), and how many devices a restricted one admits right now: a
	// count, never a list of anyone. Set with feed-gatewayctl access.
	Access storage.Access
	Grants int
}

// AccessSentence says who may read this feed.
func (p PublisherRow) AccessSentence() string {
	if p.Access.Restricted() {
		return fmt.Sprintf("Restricted: approved devices only (%d live grant(s)); subscribers "+
			"authenticate at %s", p.Grants, p.Access.AuthOrigin)
	}
	return "Public: anyone holding the feed reference"
}

// Capabilities is the sentence about what this server is allowed to do at all. It is the
// operator's own switch, and it is said before anything about credentials because a credential for
// a capability that is off does nothing.
func (p PublisherRow) Capabilities() string {
	switch {
	case p.Publishing && p.Relaying:
		return "Feed publishing and push relay"
	case p.Publishing:
		return "Feed publishing"
	case p.Relaying:
		return "Push relay"
	default:
		return "Nothing enabled"
	}
}

// Credentials is the sentence about whether this server could do what it is enabled for right now.
//
// It says "enabled", never "connected" or "online", and the distinction is not pedantry: a
// publisher calls an HTTP API when it has something to say, and a relay server calls one when a
// request of its own changed. Neither holds a connection to this gateway in between, so there is
// nothing here that could know whether either is running.
func (p PublisherRow) Credentials() string {
	parts := []string{}
	if p.Publishing {
		if p.Active > 0 {
			parts = append(parts, "publishing enabled")
		} else {
			parts = append(parts, "publishing: no active credential")
		}
	}
	if p.Relaying {
		if p.ActiveRelay > 0 {
			parts = append(parts, "relay enabled")
		} else {
			parts = append(parts, "relay: no active credential")
		}
	}
	if len(parts) == 0 {
		return "No capability enabled"
	}
	sentence := strings.Join(parts, ", ")
	return strings.ToUpper(sentence[:1]) + sentence[1:]
}

// Working says whether every capability this server is enabled for has a credential behind it. It
// is what decides whether the row reads as good or as needing attention, and it is deliberately
// not "has any credential": a server enabled for both with only one is half set up.
func (p PublisherRow) Working() bool {
	if !p.Publishing && !p.Relaying {
		return false
	}
	return (!p.Publishing || p.Active > 0) && (!p.Relaying || p.ActiveRelay > 0)
}

// Relayed is what the relay can honestly say about this server.
//
// A binding is a standing authorization by one app installation, not a connection and not a device
// that is reachable. An accepted send is Firebase having taken the message, which is not a phone
// having been woken. Both sentences are written to be true when the device is switched off.
func (p PublisherRow) Relayed() string {
	if !p.Relaying {
		return "Push relay is not enabled"
	}
	switch {
	case p.Relay.Bindings == 0 && p.Relay.Revoked == 0:
		return "No device has authorized this server yet"
	case p.Relay.Bindings == 0:
		return fmt.Sprintf("No current authorizations (%d ended)", p.Relay.Revoked)
	default:
		return fmt.Sprintf("%d device authorization(s), %d accepted wake-up(s)",
			p.Relay.Bindings, p.Relay.Sends)
	}
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

// CredentialRow is one credential, without the credential. The capability is shown because the
// two kinds are not interchangeable and an operator reading a list of IDs has no other way to tell
// which is which.
type CredentialRow struct {
	ID         string
	Label      string
	Capability storage.Capability
	Created    time.Time
	Revoked    *time.Time
}

// Kind is what this credential may be used for, in the words the page uses elsewhere.
func (c CredentialRow) Kind() string {
	if c.Capability == storage.Relaying {
		return "push relay"
	}
	return "feed publishing"
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
	// RelayCall is where an independently hosted direct server asks for a wake-up, and RelayURL
	// is the origin the phone must already be configured to trust. Both are shown because a
	// developer configures the first in their server and reads the second to check that it is the
	// gateway their owner's app knows — a phone never registers with a relay a server named
	// (SEE-144).
	RelayURL  string
	RelayCall string
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
		RelayURL:     s.options.PublicURL,
		// The relay's send is on the publisher listener, beside the publications, because its
		// caller is the same party: a developer's own backend. The phone-facing half is on the
		// read listener, which is why the origin above is the public one.
		RelayCall: publisher + pushrelay.Prefix + "/notify",
	}
}

// RegistrationForm is the "Add server" form, kept so a refusal re-renders what was typed rather
// than making the operator type it again.
type RegistrationForm struct {
	ServerID   string
	Label      string
	Host       string
	Generate   bool
	Publishing bool
	Relaying   bool
}

func registrationForm(request *http.Request) RegistrationForm {
	return RegistrationForm{
		ServerID:   strings.TrimSpace(request.PostFormValue("server")),
		Label:      request.PostFormValue("label"),
		Host:       request.PostFormValue("host"),
		Generate:   request.PostFormValue("generate") != "",
		Publishing: request.PostFormValue("publishing") != "",
		Relaying:   request.PostFormValue("relaying") != "",
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

	// At least one capability, because a registration that can do nothing is a row nobody asked
	// for. Both is allowed and neither is not; which one it is decides what the first credential
	// is issued for, and a relay-only registration needs no manifest and no feed content at all.
	if !f.Publishing && !f.Relaying {
		return registration, generated, "Choose what this server may do: publish a public feed, " +
			"relay push for its own paired phones, or both."
	}
	registration.Publishing, registration.Relaying = f.Publishing, f.Relaying

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
	Capability   storage.Capability
	CredentialID string
	Secret       string
	Connection   Connection
}

// Relay says whether the credential just shown is a relay credential, which decides which half of
// the page a developer is told to configure. A publishing credential and a relay credential look
// identical and do entirely different things, so the page never shows one without saying which.
func (r Reveal) Relay() bool { return r.Capability == storage.Relaying }

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
			ID: one.ID, Label: one.Label, Capability: one.Capability,
			Created: one.CreatedAt, Revoked: one.RevokedAt,
		})
		if one.RevokedAt != nil {
			view.Publisher.Revoked++
		}
	}
	view.Connection = s.connection(registrationOf(*publisher))
	return view, nil
}

// row joins what the operator recorded with what the feed actually holds. Both reads are of
// durable state: a manifest row and a count of publications, which is the only evidence this
// service has that a publisher has ever said anything.
func (s *Server) row(ctx context.Context, publisher storage.Publisher) (PublisherRow, error) {
	var err error
	channel := rules.ChannelFor(publisher.ServerID)
	row := PublisherRow{
		ServerID:    publisher.ServerID,
		Label:       publisher.Label,
		Host:        publisher.Host,
		Channel:     channel,
		Created:     publisher.CreatedAt,
		Active:      publisher.Active,
		ActiveRelay: publisher.ActiveRelay,
		Publishing:  publisher.Publishing,
		Relaying:    publisher.Relaying,
		Access:      publisher.Access,
		Grants:      publisher.Grants,
	}
	if row.Relay, err = s.options.Store.RelayStatus(ctx, publisher.ServerID); err != nil {
		return row, err
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
