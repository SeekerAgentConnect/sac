package relay

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// Direct is the other kind of push this gateway sends (SEE-144): one content-free invalidation to
// one device, on behalf of an independently hosted server that holds no Firebase credential.
//
// # Why it is a second type and not a second method
//
// [Relay] sends to a topic anyone may subscribe to, derived from a channel the gateway itself
// wrote. This one sends to a device target, resolved from a handle a phone authorized. The
// credential is the same and the token cache is the same — there is one Firebase project and one
// access token behind both, and minting two would be the same grant twice. Everything else is
// different: what may be addressed, who may ask, what bounds it, and what a failure means. Keeping
// them apart is what makes "a publisher can name no topic" and "a server can name no device" two
// statements about two types rather than two branches in one function.
//
// # What a server may put in a message
//
// Nothing. The caller names a handle and says whether the update is time-sensitive; this builds
// the rest. There is no field for text, no field for a topic, no field for a target, no field for
// a title, and no way to reach the notification block — an external server cannot make a phone
// display anything, because the phone displays what it read for itself afterwards and this message
// has nothing in it to display.
//
// # What a failure means
//
// A push is a hint. The request it is about was created, committed and answered on the server that
// asked; if this fails, the phone finds out on its next foreground read or its next stream. So a
// send that cannot be made is classified and reported to the caller as something to retry or not,
// and never as a reason a request did not happen.
type Direct struct {
	endpoint string
	project  string
	tokens   *tokens
	client   *http.Client
}

// The invalidation, in full, and it is two constants.
//
// They are the sidecar's own (server-sdk/src/push/invalidation.ts: FCM_INVALIDATION_DATA and
// FCM_INVALIDATION_COLLAPSE_KEY), because this is the same message from a different sender: a
// phone cannot tell whether a wake-up came from a server's own Firebase project or through this
// gateway, and it must not have to. The phone matches the whole map
// (push/SeekerVaultMessagingService.kt), so a message with anything else in it is ignored on
// arrival rather than partly trusted, and a test on each side pins these against the other's.
const (
	RequestKind        = "request_invalidation"
	RequestVersion     = "1"
	RequestCollapseKey = "seeker-vault-request-state-v1"
)

// RequestLifetime is how long Firebase keeps trying, matching the sidecar's own TTL. An
// invalidation older than this has been overtaken by the read the owner's next glance runs.
const RequestLifetime = 5 * time.Minute

// Device is the direct sender for this relay's credential: the same project, the same access
// token, a different way of addressing.
func (r *Relay) Device() *Direct {
	return &Direct{endpoint: r.endpoint, project: r.project, tokens: r.tokens, client: r.client}
}

// Outcome is what one send turned out to be. It is four cases rather than an error because the
// caller does something different with each, and "an error happened" would collapse the one
// distinction that matters: whether this device's target is finished or the attempt was.
type Outcome int

const (
	// Delivered: the push endpoint accepted it. That is not a promise it reached a phone — the
	// phone may be off, and Firebase does not say — which is why nothing downstream treats an
	// accepted send as evidence about a device.
	Delivered Outcome = iota
	// TargetGone: the endpoint says this target is permanently invalid. The installation's target
	// is cleared, compared against the one that failed, and the phone registers again on its own.
	TargetGone
	// Unavailable: nobody knows. Throttling, an outage, a timeout. The caller is told to retry.
	Unavailable
	// Refused: this deployment's own problem — a credential the project does not accept, a
	// project that does not have messaging enabled. It is not the caller's fault and not something
	// a retry fixes, so the caller is answered without being asked to come back.
	Refused
)

// Send is one invalidation to one target.
//
// The one retry is the same one the topic relay makes and for the same reason: a cached access
// token the endpoint has stopped accepting is this service's own state, and it is the only
// condition where trying again immediately can change the answer.
func (d *Direct) Send(ctx context.Context, target string, timeSensitive bool) (Outcome, error) {
	priority := "NORMAL"
	if timeSensitive {
		priority = "HIGH"
	}
	body, err := json.Marshal(deviceEnvelope{Message: deviceMessage{
		Target: target,
		Data:   map[string]string{"kind": RequestKind, "version": RequestVersion},
		Android: android{
			CollapseKey: RequestCollapseKey,
			Priority:    priority,
			Lifetime:    fmt.Sprintf("%ds", int(RequestLifetime.Seconds())),
		},
	}})
	if err != nil {
		return Refused, fmt.Errorf("encode the invalidation")
	}
	token, err := d.tokens.access(ctx)
	if err != nil {
		return Unavailable, err
	}
	status, err := d.post(ctx, body, token)
	if err != nil {
		return Unavailable, err
	}
	if status == http.StatusUnauthorized {
		d.tokens.forget(token)
		fresh, err := d.tokens.access(ctx)
		if err != nil {
			return Unavailable, err
		}
		if status, err = d.post(ctx, body, fresh); err != nil {
			return Unavailable, err
		}
	}
	return outcomeOf(status), nil
}

// outcomeOf reads the status and nothing else.
//
// The body is deliberately not parsed, here as in the topic relay: a refusal from Google is its
// own prose and can name a project, and the status carries the whole of what this service acts on.
// The cost is that 400 is treated as a target that is finished — which is what it is, for a
// message whose every other field this package wrote itself and whose only variable is the target.
func outcomeOf(status int) Outcome {
	switch {
	case status == http.StatusOK:
		return Delivered
	case status == http.StatusNotFound, status == http.StatusBadRequest:
		return TargetGone
	case status == http.StatusTooManyRequests, status >= 500:
		return Unavailable
	default:
		return Refused
	}
}

// post is the call. It mirrors the topic relay's: the answer is read and discarded, because there
// is nothing in it this service acts on and its body can name a project.
func (d *Direct) post(ctx context.Context, body []byte, token string) (int, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodPost,
		d.endpoint+"/v1/projects/"+d.project+"/messages:send", bytes.NewReader(body))
	if err != nil {
		return 0, fmt.Errorf("build the invalidation request")
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer "+token)
	response, err := d.client.Do(request)
	if err != nil {
		return 0, fmt.Errorf("the push endpoint is not reachable")
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 8*1024))
	return response.StatusCode, nil
}

type deviceEnvelope struct {
	Message deviceMessage `json:"message"`
}

// deviceMessage addresses a Firebase installation, which is what the phone's registration actually
// is and what the sidecar already sends to.
//
// `fid` rather than `token` is deliberate and was checked against the installed SDK rather than
// assumed: firebase-admin 14.4.0 treats fid, token, topic and condition as four alternative target
// fields of one message and passes whichever is set through to this same v1 endpoint unchanged
// (messaging-internal.js validateMessage). The phone hands its registration to a paired server as
// one opaque string and that server sends it as `fid`; a gateway that put the same string in
// `token` would be addressing a different kind of thing with the same characters, which is exactly
// the confusion SEE-144 says not to make.
//
// There is no notification member on this struct, and that is the enforcement rather than a
// convention: an external server has no field to put display text in because this type has none.
type deviceMessage struct {
	Target  string            `json:"fid"`
	Data    map[string]string `json:"data"`
	Android android           `json:"android"`
}
