package pushrelay

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"
)

// The contract, version 1 (SEE-144, docs/guides/server-development.md#the-gateway-push-relay).
//
// It is JSON over HTTP rather than another Connect service, and the reason is what it carries: two
// short calls a phone makes about its own registration, and one a server makes to wake a device.
// None of them is a document, none is validated against the feed's rules, and none of them belongs
// in the protocol the phone and the publishers already share — a relay handle in feed.proto would
// be a routing secret in a schema whose whole point is that it holds nothing private. The admin
// surface is plain HTTP for the same kind of reason.
//
// Versioning is in two places on purpose. The path names the contract, so a later one can be
// served beside this one; the body names it too, so a request that reached the right path with the
// wrong idea of what it means is refused rather than half understood.

// Prefix is where the relay's calls live on whichever listener serves them.
const Prefix = "/relay/v1"

// Version is the one version of this contract these handlers implement.
const Version = "1"

// MostBytes is the largest body any of these calls has. Every one of them is a handful of short
// fields; anything near this is not one of them.
const MostBytes = 4 * 1024

// MostTargetBytes is the bound on an FCM registration, matching the sidecar's own
// (server-sdk/src/storage/pairing-store.ts: MAX_FCM_TOKEN_BYTES). The phone hands the same string
// to a directly paired server and to this gateway, so the two must not disagree about what one is.
const MostTargetBytes = 4096

// MostConnectionBytes is the bound on the phone's own name for a direct connection. The gateway
// never parses it, so the only thing it needs to be is short.
const MostConnectionBytes = 128

// enrollRequest is a phone asking this gateway to route for it.
//
// It carries the device's FCM registration and nothing else: no owner, no account, no device name,
// no wallet, and nothing about the servers it is paired with. Which servers may wake it is a
// separate, later, individually authorized decision (bindRequest).
type enrollRequest struct {
	Version string `json:"version"`
	Target  string `json:"target"`
}

// enrollResponse is the one time the installation's secret exists outside the device that asked.
//
// The secret is what proves ownership for every later call: replacing the target, authorizing a
// binding, revoking one, forgetting the installation. The gateway keeps only its SHA-256, so this
// answer is the only copy — a phone that loses it enrolls again, which costs one round trip and
// the bindings it then re-creates.
type enrollResponse struct {
	Version      string `json:"version"`
	Installation string `json:"installation"`
	Secret       string `json:"secret"`
	// BindingSeconds is how long an authorization lasts before the phone has to renew it. It is
	// told rather than assumed so the app's reconciliation schedule follows the deployment's
	// policy instead of a number compiled into the app.
	BindingSeconds int `json:"binding_seconds"`
}

// targetRequest replaces the installation's registration after Firebase rotates it.
type targetRequest struct {
	Version string `json:"version"`
	Target  string `json:"target"`
}

// bindRequest is the authorization itself: this installation agrees that this registered server
// may wake it, for one of the direct connections it holds.
//
// The server is named by the identity the phone learned over the authenticated direct connection
// to that server, never by a hostname and never by something a QR code said. The gateway cannot
// check that pairing — it is direct and private, and the gateway is not in it — so the binding it
// issues is useless to anyone else: the handle only works for whoever can also present that
// server's own relay credential. Naming the wrong server therefore grants that server nothing and
// costs the phone a wake-up it will notice the absence of.
type bindRequest struct {
	Version    string `json:"version"`
	Server     string `json:"server"`
	Connection string `json:"connection"`
}

// bindResponse is the one time the handle exists outside this gateway and the phone.
//
// The phone passes it to the server over the authenticated direct connection it is for. The
// gateway keeps only its SHA-256, so it cannot hand it back — which is why reconciliation
// re-authorizes rather than re-reads, and why a gateway that lost its database cannot silently
// resurrect an authorization nobody made again.
type bindResponse struct {
	Version string `json:"version"`
	Binding string `json:"binding"`
	Handle  string `json:"handle"`
	Expires string `json:"expires"`
}

// installationResponse is the authenticated read an app makes when it starts or reconnects: does
// this gateway still hold my enrollment, and which of my authorizations does it still have?
//
// It is the whole of reconciliation in one call. A 404 means the gateway's state is gone and the
// app enrolls again; a binding the app expected and does not see here is one it re-creates; a
// binding here that the app has no connection for is one it revokes.
type installationResponse struct {
	Version      string        `json:"version"`
	Installation string        `json:"installation"`
	HasTarget    bool          `json:"has_target"`
	Bindings     []bindingView `json:"bindings"`
}

// bindingView is one authorization as its owner sees it. There is no handle in it, because the
// handle was never stored, and no target, because a device does not need to be told its own.
type bindingView struct {
	Binding    string `json:"binding"`
	Server     string `json:"server"`
	Connection string `json:"connection"`
	Expires    string `json:"expires"`
	Revoked    bool   `json:"revoked"`
}

// notifyRequest is the whole of what an external server may say.
//
// A handle, which it was given, and a hint, which is one of two words. There is no text field, no
// title, no topic, no target, no priority number, no TTL and no options block — the invalidation
// is built by the gateway from constants (internal/relay.Direct), so the most an authorized server
// can cause is that a phone wakes up and reads the server's own authenticated API for itself.
type notifyRequest struct {
	Version string `json:"version"`
	Handle  string `json:"handle"`
	Hint    string `json:"hint"`
}

// The two hints. They exist because one of them is worth waking a sleeping phone for and the other
// is not: a request that has just appeared is waiting on its owner, and a state change on one they
// have already decided is not. The hint chooses Android's delivery priority and nothing else — it
// is never in the payload, so the phone cannot read it and does not act on it.
const (
	Created = "created"
	Updated = "updated"
)

// notifyResponse says what became of the call. It never says anything about the device.
//
// "accepted" means the push endpoint took the message, which is not a promise it reached a phone —
// FCM does not make that promise and neither does this. "coalesced" means this binding is already
// being woken as fast as it is useful to wake it, and the wake-up in flight carries the same news
// as this one, because the news is always "read your own server".
type notifyResponse struct {
	Version string `json:"version"`
	Status  string `json:"status"`
}

const (
	accepted  = "accepted"
	coalesced = "coalesced"
)

// problem is every refusal this surface gives, in one shape.
//
// The reason is a short constant phrase chosen from this file. Nothing from the store, from the
// push endpoint or from the request body is ever in it: an error is the one place a service is
// most tempted to publish its own state, and a caller here is an external server or a phone that
// is entitled to know only whether it may proceed.
type problem struct {
	Version string `json:"version"`
	Problem string `json:"problem"`
}

// The refusals, by name. Several failures deliberately share one: every way a handle fails to
// authorize a send is "that handle does not authorize this server", because a server may learn
// that it may not send and never whether the handle exists, was revoked, expired, or belongs to
// somebody else.
const (
	malformed       = "that request could not be read"
	wrongVersion    = "this relay speaks version " + Version
	unauthenticated = "that credential was not accepted"
	unauthorized    = "that handle does not authorize this server"
	noInstallation  = "that installation was not found"
	notPermitted    = "that server may not be sent to through this relay"
	tooMany         = "too many requests"
	unavailable     = "the relay cannot send right now"
	failed          = "the relay could not complete that"
)

// read decodes one request body, bounded and strict.
//
// Unknown fields are refused rather than ignored, which is the opposite of what a tolerant reader
// does and the right thing here: a caller that sent a field this version does not know is a caller
// with a different idea of what it is asking for, and the cases where that matters — a priority, a
// TTL, a target, a topic — are exactly the ones this contract exists to refuse.
func read[T any](writer http.ResponseWriter, request *http.Request, into *T) bool {
	request.Body = http.MaxBytesReader(writer, request.Body, MostBytes)
	decoder := json.NewDecoder(request.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(into); err != nil {
		refuse(writer, http.StatusBadRequest, malformed)
		return false
	}
	// Exactly one document, so a body with a second one appended is not half accepted.
	if err := decoder.Decode(new(json.RawMessage)); !errors.Is(err, io.EOF) {
		refuse(writer, http.StatusBadRequest, malformed)
		return false
	}
	return true
}

func answer(writer http.ResponseWriter, status int, body any) {
	writer.Header().Set("Content-Type", "application/json")
	writer.Header().Set("Cache-Control", "no-store")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(body)
}

func refuse(writer http.ResponseWriter, status int, reason string) {
	answer(writer, status, problem{Version: Version, Problem: reason})
}

// bearer is the credential a caller presented, or "". It is the same header shape the publisher
// API uses, so an operator configuring a server sets one kind of thing in one kind of place.
func bearer(request *http.Request) string {
	value := request.Header.Get("Authorization")
	rest, found := strings.CutPrefix(value, "Bearer ")
	if !found {
		return ""
	}
	return strings.TrimSpace(rest)
}

// validTarget is what an FCM registration may look like: opaque, bounded, and safe to put in a
// JSON body and a header-shaped world.
//
// It is the sidecar's own rule (invalidFcmTokenReason), repeated here because both ends hold the
// same string and neither trusts the other about it. The refusal never repeats the value.
func validTarget(target string) bool {
	if len(target) == 0 || len(target) > MostTargetBytes {
		return false
	}
	return printableASCII(target)
}

func validConnection(reference string) bool {
	if len(reference) == 0 || len(reference) > MostConnectionBytes {
		return false
	}
	return printableASCII(reference)
}

func printableASCII(value string) bool {
	for index := range len(value) {
		if value[index] < 0x21 || value[index] > 0x7e {
			return false
		}
	}
	return true
}

func instant(at time.Time) string { return at.UTC().Format(time.RFC3339) }
