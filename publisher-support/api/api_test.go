package api_test

import (
	"context"
	"net/http"
	"strings"
	"testing"
	"time"

	"connectrpc.com/connect"

	"github.com/BrRenat/SeekerAgentWallet/publisher-support/api"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/environment"
	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/gateway/v1"
	proposalv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/proposal/v1"
	"github.com/BrRenat/SeekerAgentWallet/publisher-support/signals"
)

func TestTheCommonRequestAPIIsThePrimaryViewOfTheSameDurablePublication(t *testing.T) {
	held := start(t, &fakeGateway{})
	held.ForceIDs = []string{proposal}
	created := held.Call(http.MethodPost, "/v1/requests", swapStatement(), "Idempotency-Key", "common-1")
	if created.Status != http.StatusCreated {
		t.Fatalf("create answered %d: %s", created.Status, created.Raw)
	}
	request, ok := created.Body["request"].(map[string]any)
	if !ok || created.Body["signal"] != nil {
		t.Fatalf("common route answered the wrong document: %s", created.Raw)
	}
	identity, _ := request["identity"].(map[string]any)
	presentation, _ := request["presentation"].(map[string]any)
	audience, _ := request["audience"].(map[string]any)
	result, _ := request["result_handling"].(map[string]any)
	if request["contract_version"] != float64(1) ||
		identity["request_id"] != proposal ||
		presentation["category"] != "PRESENTATION_CATEGORY_SIGNAL" ||
		audience["feed"] == nil || result["mode"] != "RESULT_MODE_DEVICE_LOCAL" {
		t.Fatalf("common request is incomplete: %s", created.Raw)
	}
	for _, private := range []string{"wallet", "decision", "signature", "result", "subscriber"} {
		if strings.Contains(created.Raw, `"`+private+`"`) {
			t.Fatalf("common answer exposes private field %q: %s", private, created.Raw)
		}
	}

	legacy := held.Call(http.MethodGet, "/v1/signals/"+proposal, nil)
	if legacy.Status != http.StatusOK || legacy.Signal()["proposal_id"] != proposal {
		t.Fatalf("compatibility view did not read the same row: %s", legacy.Raw)
	}
}

// A signal published through the API, end to end: the API, the store, the drainer, the Connect
// client, and a gateway that holds what arrives.
func TestASignalIsPublishedOnce(t *testing.T) {
	fake := &fakeGateway{}
	held := start(t, fake)

	answered := held.Create("key-1", swapStatement())
	if answered.Status != http.StatusCreated {
		t.Fatalf("%d: %s", answered.Status, answered.Raw)
	}
	if answered.State() != "published" {
		t.Fatalf("publication %s: %s", answered.State(), answered.Raw)
	}
	signal := answered.Signal()
	for name, expected := range map[string]any{
		"server_id":   server,
		"channel":     "server/" + server,
		"revision":    "1",
		"status":      "open",
		"operation":   "swap",
		"plugin_id":   "jupiter.swap",
		"environment": "production",
		"note":        "trimming SOL into USDC",
	} {
		if signal[name] != expected {
			t.Fatalf("%s is %v, expected %v", name, signal[name], expected)
		}
	}
	if answered.Body["idempotent"] != false {
		t.Fatalf("idempotent %v on a first create", answered.Body["idempotent"])
	}

	// One document reached the gateway, and it is the signal the caller asked for.
	documents := documents(fake)
	if len(documents) != 1 {
		t.Fatalf("%d documents reached the gateway", len(documents))
	}
	document := documents[0]
	if document.GetServerId() != server || document.GetChannel() != "server/"+server {
		t.Fatalf("the document names %s on %s", document.GetServerId(), document.GetChannel())
	}
	if document.GetStatus() != proposalv1.ProposalStatus_PROPOSAL_STATUS_OPEN {
		t.Fatalf("status %s", document.GetStatus())
	}
	if document.GetOperation() != "swap" || document.GetPluginId() != "jupiter.swap" {
		t.Fatalf("%s / %s", document.GetOperation(), document.GetPluginId())
	}
	if document.GetExpiresAt().AsTime() != now.Add(time.Hour) {
		t.Fatalf("expires %s", document.GetExpiresAt().AsTime())
	}
	terms := map[string]string{}
	for _, value := range document.GetValues() {
		terms[value.GetKey()] = value.GetText()
	}
	if terms[signals.InputMint] != signals.WrappedSOL || terms[signals.OutputMint] != usdc {
		t.Fatalf("terms %v", terms)
	}
}

// The document carries nothing about a subscriber, because there is nothing about one to carry:
// not an amount, not an address, not a decision. Every subscriber reads the same bytes, and what
// each of them chooses is theirs (SEE-89).
func TestThePublishedDocumentSaysNothingAboutASubscriber(t *testing.T) {
	fake := &fakeGateway{}
	held := start(t, fake)
	if answered := held.Create("key-1", swapStatement()); answered.Status != http.StatusCreated {
		t.Fatalf("%d: %s", answered.Status, answered.Raw)
	}
	document := documents(fake)[0]
	// The proposal's whole field set, as the contract has it. A field for a subscriber could not
	// be published because there is none — this pins that the template fills in no more than this.
	for _, value := range document.GetValues() {
		switch value.GetKey() {
		case signals.InputMint, signals.InputDecimals, signals.OutputMint,
			signals.OutputDecimals, signals.MaxSlippageBps, signals.LeastInput,
			signals.MostInput, signals.InputSymbol, signals.OutputSymbol:
		default:
			t.Fatalf("the document carries a term nothing asked for: %s", value.GetKey())
		}
	}
	if document.GetPublisherNote() != "trimming SOL into USDC" {
		t.Fatalf("note %q", document.GetPublisherNote())
	}
}

// A create that is retried is the same signal, and nothing is published twice. It is the case a
// strategy engine actually hits: the call succeeded and the answer never arrived.
func TestARetriedCreateIsTheSameSignal(t *testing.T) {
	fake := &fakeGateway{}
	held := start(t, fake)

	first := held.Create("key-1", swapStatement())
	if first.Status != http.StatusCreated {
		t.Fatalf("%d: %s", first.Status, first.Raw)
	}
	replay := held.Create("key-1", swapStatement())
	if replay.Status != http.StatusOK {
		t.Fatalf("a replay answered %d: %s", replay.Status, replay.Raw)
	}
	if replay.Body["idempotent"] != true {
		t.Fatalf("idempotent %v", replay.Body["idempotent"])
	}
	if replay.Signal()["proposal_id"] != first.Signal()["proposal_id"] {
		t.Fatalf("the replay created %v beside %v", replay.Signal()["proposal_id"],
			first.Signal()["proposal_id"])
	}
	if replay.Signal()["revision"] != "1" {
		t.Fatalf("revision %v", replay.Signal()["revision"])
	}
	if documents := documents(fake); len(documents) != 1 {
		t.Fatalf("%d documents reached the gateway", len(documents))
	}
	if answered := held.Call(http.MethodGet, "/v1/signals", nil); len(
		answered.Body["signals"].([]any)) != 1 {
		t.Fatalf("%s", answered.Raw)
	}
}

// The same key for a different signal is a conflict, not a duplicate: two different statements
// cannot be one signal, and answering 200 with the first one would hide the second for ever.
func TestTheSameKeyForADifferentSignalIsAConflict(t *testing.T) {
	fake := &fakeGateway{}
	held := start(t, fake)
	if answered := held.Create("key-1", swapStatement()); answered.Status != http.StatusCreated {
		t.Fatalf("%d: %s", answered.Status, answered.Raw)
	}
	moved := swapStatement()
	moved["terms"].(map[string]string)[signals.MaxSlippageBps] = "80"
	answered := held.Create("key-1", moved)
	if answered.Status != http.StatusConflict || answered.Problem() != "key_reused" {
		t.Fatalf("%d %s: %s", answered.Status, answered.Problem(), answered.Raw)
	}
	if documents := documents(fake); len(documents) != 1 {
		t.Fatalf("%d documents reached the gateway", len(documents))
	}
}

// A create with no key is refused. A bot that retries without one publishes twice, and there is no
// way for this template to tell that second call from a second signal — so the header is required
// rather than optional.
func TestACreateNeedsAnIdempotencyKey(t *testing.T) {
	held := start(t, &fakeGateway{})
	for _, one := range []struct {
		name string
		key  string
	}{
		{"absent", ""},
		{"a space", "key one"},
		{"too long", string(make([]byte, 0, 201)) + string(bytes201())},
	} {
		t.Run(one.name, func(t *testing.T) {
			answered := held.Call(http.MethodPost, "/v1/signals", swapStatement(),
				"Idempotency-Key", one.key)
			if answered.Status != http.StatusBadRequest ||
				answered.Problem() != "bad_idempotency_key" {
				t.Fatalf("%d %s: %s", answered.Status, answered.Problem(), answered.Raw)
			}
		})
	}
}

// Every route but the health probe needs the token, and every way of failing gets the same answer:
// a caller learns that it may not publish, never whether what it presented used to work.
func TestEveryRouteButHealthNeedsTheToken(t *testing.T) {
	held := start(t, &fakeGateway{})
	for _, one := range []struct{ method, path string }{
		{http.MethodGet, "/v1/status"},
		{http.MethodGet, "/v1/manifest"},
		{http.MethodGet, "/v1/signals"},
		{http.MethodPost, "/v1/signals"},
		{http.MethodGet, "/v1/signals/" + proposal},
		{http.MethodPut, "/v1/signals/" + proposal},
		{http.MethodPost, "/v1/signals/" + proposal + "/cancel"},
		{http.MethodPost, "/v1/signals/" + proposal + "/retry"},
	} {
		for _, presented := range []string{"", "Bearer wrong", "wrong", "bearer " + token,
			"Basic " + token} {
			answered := held.Call(one.method, one.path, nil, "Authorization", "-")
			if presented != "" {
				answered = held.Call(one.method, one.path, nil, "Authorization", presented)
			}
			if answered.Status != http.StatusUnauthorized {
				t.Fatalf("%s %s with %q answered %d: %s", one.method, one.path, presented,
					answered.Status, answered.Raw)
			}
			if answered.Problem() != "unauthorized" {
				t.Fatalf("problem %q", answered.Problem())
			}
		}
	}
	// And the health probe says the process is up and nothing else.
	answered := held.Call(http.MethodGet, "/healthz", nil, "Authorization", "-")
	if answered.Status != http.StatusOK || answered.Body["status"] != "ok" {
		t.Fatalf("%d: %s", answered.Status, answered.Raw)
	}
	if len(answered.Body) != 1 {
		t.Fatalf("the health probe says more than that it is up: %s", answered.Raw)
	}
}

// A malformed signal is refused with the term it was about, and nothing is stored: a publisher's
// mistake is not a document on everybody's phone.
func TestAMalformedSignalIsRefusedWithTheTermItWasAbout(t *testing.T) {
	fake := &fakeGateway{}
	held := start(t, fake)
	for _, one := range []struct {
		name    string
		apply   func(map[string]any)
		problem string
		term    string
	}{
		{"a ticker for a mint", func(body map[string]any) {
			body["terms"].(map[string]string)[signals.InputMint] = "SOL"
		}, "not_a_mint", signals.InputMint},
		{"no output mint", func(body map[string]any) {
			delete(body["terms"].(map[string]string), signals.OutputMint)
		}, "missing", signals.OutputMint},
		{"the same mint twice", func(body map[string]any) {
			body["terms"].(map[string]string)[signals.OutputMint] = signals.WrappedSOL
		}, "one_asset", signals.OutputMint},
		{"a slippage that is not basis points", func(body map[string]any) {
			body["terms"].(map[string]string)[signals.MaxSlippageBps] = "20000"
		}, "bad_number", signals.MaxSlippageBps},
		{"an expiry that has passed", func(body map[string]any) {
			body["expires_at"] = now.Add(-time.Hour).Format(time.RFC3339)
		}, "past_expiry", ""},
		{"an expiry that is not an instant", func(body map[string]any) {
			body["expires_at"] = "in two hours"
		}, "bad_expiry", ""},
		{"an expiry that is a duration", func(body map[string]any) {
			body["expires_at"] = "2h"
		}, "bad_expiry", ""},
		{"no expiry at all", func(body map[string]any) {
			delete(body, "expires_at")
		}, "bad_expiry", ""},
		{"no terms", func(body map[string]any) {
			delete(body, "terms")
		}, "missing", "terms"},
		{"a note with a control character", func(body map[string]any) {
			body["note"] = "one\x01two"
		}, "bad_note", ""},
		{"a term nothing reads", func(body map[string]any) {
			body["terms"].(map[string]string)["urgency"] = "high"
		}, "unknown_term", "urgency"},
	} {
		t.Run(one.name, func(t *testing.T) {
			body := swapStatement()
			one.apply(body)
			answered := held.Create("key-"+strings.ReplaceAll(one.name, " ", "-"), body)
			if answered.Status != http.StatusBadRequest {
				t.Fatalf("%d: %s", answered.Status, answered.Raw)
			}
			if answered.Problem() != one.problem || answered.Term() != one.term {
				t.Fatalf("%s (%s), expected %s (%s): %s", answered.Problem(), answered.Term(),
					one.problem, one.term, answered.Raw)
			}
			if answered.Body["detail"] == "" {
				t.Fatal("a refusal with no sentence in it")
			}
		})
	}
	if documents := documents(fake); len(documents) != 0 {
		t.Fatalf("%d documents reached the gateway", len(documents))
	}
}

// A gateway that is not answering is not a failed signal: the template holds it, says so, and
// keeps trying. This is the acceptance criterion about gateway unavailability.
func TestAGatewayThatIsDownHoldsTheSignalAndKeepsTrying(t *testing.T) {
	down := true
	fake := &fakeGateway{Refuse: func(string) error {
		if down {
			return connect.NewError(connect.CodeUnavailable, context.DeadlineExceeded)
		}
		return nil
	}}
	held := start(t, fake)

	answered := held.Create("key-1", swapStatement())
	if answered.Status != http.StatusAccepted {
		t.Fatalf("%d: %s", answered.Status, answered.Raw)
	}
	if answered.State() != "pending" {
		t.Fatalf("publication %s", answered.State())
	}
	if answered.Publication()["next_attempt_at"] == nil {
		t.Fatalf("nothing says when it will be tried again: %s", answered.Raw)
	}
	id := answered.Signal()["proposal_id"].(string)

	// It is held, and a reader is told the truth about it.
	shown := held.Call(http.MethodGet, "/v1/signals/"+id, nil)
	if shown.Status != http.StatusOK || shown.State() != "pending" {
		t.Fatalf("%d %s: %s", shown.Status, shown.State(), shown.Raw)
	}
	if status := held.Call(http.MethodGet, "/v1/status", nil); status.Body["pending"] != 1.0 {
		t.Fatalf("pending %v: %s", status.Body["pending"], status.Raw)
	}

	// When the gateway comes back, the next pass publishes it — with no second signal created.
	down = false
	held.NowValue = now.Add(2 * time.Minute)
	if _, err := held.Drainer.Pass(context.Background()); err != nil {
		t.Fatal(err)
	}
	shown = held.Call(http.MethodGet, "/v1/signals/"+id, nil)
	if shown.State() != "published" {
		t.Fatalf("publication %s: %s", shown.State(), shown.Raw)
	}
	if documents := documents(fake); len(documents) != 1 {
		t.Fatalf("%d documents reached the gateway", len(documents))
	}
}

// A refusal the gateway will repeat is answered as one, with its own problem code, and it is not
// retried until somebody asks — at which point it is.
func TestARefusedSignalSaysWhyAndIsRetriedOnlyWhenAsked(t *testing.T) {
	refusing := true
	fake := &fakeGateway{Refuse: func(procedure string) error {
		if refusing && procedure == "PublishProposal" {
			return refusal(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_PROPOSALS,
				connect.CodeFailedPrecondition)("")
		}
		return nil
	}}
	held := start(t, fake)

	answered := held.Create("key-1", swapStatement())
	if answered.Status != http.StatusBadGateway {
		t.Fatalf("%d: %s", answered.Status, answered.Raw)
	}
	if answered.State() != "refused" ||
		answered.Publication()["problem"] != "too_many_proposals" {
		t.Fatalf("%s", answered.Raw)
	}
	id := answered.Signal()["proposal_id"].(string)

	// Nothing retries it on its own.
	if _, err := held.Drainer.Pass(context.Background()); err != nil {
		t.Fatal(err)
	}
	if documents := documents(fake); len(documents) != 0 {
		t.Fatalf("a refused signal was published: %d", len(documents))
	}

	// And the way out is a request, because something has to have changed.
	refusing = false
	retried := held.Call(http.MethodPost, "/v1/signals/"+id+"/retry", nil)
	if retried.Status != http.StatusOK || retried.State() != "published" {
		t.Fatalf("%d %s: %s", retried.Status, retried.State(), retried.Raw)
	}
	if retried.Body["cleared"] != true {
		t.Fatalf("cleared %v", retried.Body["cleared"])
	}
	again := held.Call(http.MethodPost, "/v1/signals/"+id+"/retry", nil)
	if again.Status != http.StatusOK || again.Body["cleared"] != false {
		t.Fatalf("%d %v", again.Status, again.Body["cleared"])
	}
}

// An update is the whole statement, and one that changes nothing changes nothing: no revision, no
// publication, and no phone woken. A strategy engine may re-post its view every minute.
func TestAnUpdateThatChangesNothingPublishesNothing(t *testing.T) {
	fake := &fakeGateway{}
	held := start(t, fake)
	created := held.Create("key-1", swapStatement())
	id := created.Signal()["proposal_id"].(string)

	same := held.Call(http.MethodPut, "/v1/signals/"+id, swapStatement())
	if same.Status != http.StatusOK || same.Body["changed"] != false {
		t.Fatalf("%d changed %v: %s", same.Status, same.Body["changed"], same.Raw)
	}
	if same.Signal()["revision"] != "1" {
		t.Fatalf("revision %v", same.Signal()["revision"])
	}
	if documents := documents(fake); len(documents) != 1 {
		t.Fatalf("%d documents reached the gateway", len(documents))
	}

	moved := swapStatement()
	moved["terms"].(map[string]string)[signals.MaxSlippageBps] = "80"
	moved["note"] = "widening the slippage I will have this acted on with"
	held.NowValue = now.Add(time.Minute)
	changed := held.Call(http.MethodPut, "/v1/signals/"+id, moved)
	if changed.Status != http.StatusOK || changed.Body["changed"] != true {
		t.Fatalf("%d changed %v: %s", changed.Status, changed.Body["changed"], changed.Raw)
	}
	if changed.Signal()["revision"] != "2" || changed.State() != "published" {
		t.Fatalf("%s", changed.Raw)
	}
	documents := documents(fake)
	if len(documents) != 2 {
		t.Fatalf("%d documents reached the gateway", len(documents))
	}
	if documents[1].GetRevision() != 2 {
		t.Fatalf("the second document is revision %d", documents[1].GetRevision())
	}
	if documents[1].GetCreatedAt().AsTime() != documents[0].GetCreatedAt().AsTime() {
		t.Fatal("the creation time moved, which would describe a different proposal")
	}
}

// A withdrawal is final: it is published as a transition, withdrawing again is not an event, and
// nothing can reopen the identity.
func TestAWithdrawalIsFinal(t *testing.T) {
	fake := &fakeGateway{}
	held := start(t, fake)
	created := held.Create("key-1", swapStatement())
	id := created.Signal()["proposal_id"].(string)

	held.NowValue = now.Add(time.Minute)
	cancelled := held.Call(http.MethodPost, "/v1/signals/"+id+"/cancel", nil)
	if cancelled.Status != http.StatusOK || cancelled.Body["changed"] != true {
		t.Fatalf("%d changed %v: %s", cancelled.Status, cancelled.Body["changed"], cancelled.Raw)
	}
	if cancelled.Signal()["status"] != "cancelled" || cancelled.Signal()["revision"] != "2" {
		t.Fatalf("%s", cancelled.Raw)
	}
	if withdrawn := fake.Held(id); withdrawn.GetStatus() !=
		proposalv1.ProposalStatus_PROPOSAL_STATUS_CANCELLED {
		t.Fatalf("the gateway holds %s", withdrawn.GetStatus())
	}

	again := held.Call(http.MethodPost, "/v1/signals/"+id+"/cancel", nil)
	if again.Status != http.StatusOK || again.Body["changed"] != false {
		t.Fatalf("%d changed %v", again.Status, again.Body["changed"])
	}
	update := held.Call(http.MethodPut, "/v1/signals/"+id, swapStatement())
	if update.Status != http.StatusConflict || update.Problem() != "cancelled" {
		t.Fatalf("%d %s: %s", update.Status, update.Problem(), update.Raw)
	}
}

func TestNothingIsHeldUnderAnUnknownID(t *testing.T) {
	held := start(t, &fakeGateway{})
	unknown := "0e1f2a3b-4c5d-4e6f-8a7b-8c9d0e1f2a3b"
	for _, one := range []struct {
		method, path string
		Body         map[string]any
	}{
		{http.MethodGet, "/v1/signals/" + unknown, nil},
		{http.MethodPut, "/v1/signals/" + unknown, swapStatement()},
		{http.MethodPost, "/v1/signals/" + unknown + "/cancel", nil},
		{http.MethodPost, "/v1/signals/" + unknown + "/retry", nil},
	} {
		answered := held.Call(one.method, one.path, one.Body)
		if answered.Status != http.StatusNotFound || answered.Problem() != "no_such_signal" {
			t.Fatalf("%s %s answered %d %s", one.method, one.path, answered.Status,
				answered.Problem())
		}
	}
}

// Every route names its method, so the wrong one is answered by the router rather than by a
// handler that has to remember to check.
func TestAWrongMethodIsRefused(t *testing.T) {
	held := start(t, &fakeGateway{})
	for _, one := range []struct{ method, path string }{
		{http.MethodDelete, "/v1/signals/" + proposal},
		{http.MethodGet, "/v1/signals/" + proposal + "/cancel"},
		{http.MethodPut, "/v1/signals"},
		{http.MethodPost, "/v1/status"},
		{http.MethodPost, "/healthz"},
	} {
		answered := held.Call(one.method, one.path, nil)
		if answered.Status != http.StatusMethodNotAllowed ||
			answered.Problem() != "method_not_allowed" {
			t.Fatalf("%s %s answered %d %s", one.method, one.path, answered.Status,
				answered.Raw)
		}
	}
	// And a route that does not exist is refused in the same shape, not in the router's own words.
	for _, path := range []string{"/", "/v1", "/v2/signals", "/v1/signals/x/y/z"} {
		answered := held.Call(http.MethodGet, path, nil)
		if answered.Status != http.StatusNotFound || answered.Problem() != "no_such_route" {
			t.Fatalf("GET %s answered %d %s", path, answered.Status, answered.Raw)
		}
	}
}

// The manifest is what the phone reads about this server, and the reference is the one string an
// operator passes on. Both are built from the deployment rather than from anything a caller said.
func TestTheManifestAndTheReferenceAreTheDeploymentsOwn(t *testing.T) {
	fake := &fakeGateway{}
	held := start(t, fake)
	ctx := context.Background()
	if _, err := held.Store.ManifestRevision(ctx, "fingerprint-a", now); err != nil {
		t.Fatal(err)
	}

	answered := held.Call(http.MethodGet, "/v1/manifest", nil)
	if answered.Status != http.StatusOK {
		t.Fatalf("%d: %s", answered.Status, answered.Raw)
	}
	document := answered.Body["manifest"].(map[string]any)
	for name, expected := range map[string]any{
		"server_id":         server,
		"protocol_version":  1.0,
		"settings_revision": "1",
		"mode":              "gateway_feed",
		"display_name":      "Copy trading desk",
	} {
		if document[name] != expected {
			t.Fatalf("%s is %v, expected %v", name, document[name], expected)
		}
	}
	if environments := document["environments"].([]any); len(environments) != 1 ||
		environments[0] != "production" {
		t.Fatalf("environments %v: one deployment serves one of them", environments)
	}
	// And it is the document's own answer rather than the setting beside it (SEE-97): one fact,
	// one source, and the source is the one a phone will read.
	for _, one := range []struct {
		named    environment.Environment
		expected string
	}{
		{environment.Production, "production"},
		{environment.Sandbox, "sandbox"},
		// A setting nobody validated does not become the promise with the money attached to it:
		// the document says unspecified, the gateway refuses it, and the answer says so too.
		{"staging", "unspecified"},
	} {
		deployment := startIn(t, &fakeGateway{}, one.named)
		manifested := deployment.Call(http.MethodGet, "/v1/manifest", nil)
		document := manifested.Body["manifest"].(map[string]any)
		environments := document["environments"].([]any)
		if len(environments) != 1 || environments[0] != one.expected {
			t.Fatalf("%q answered %v, expected [%s]", one.named, environments, one.expected)
		}
		// The status answers the word itself, which is what an operator reads.
		status := deployment.Call(http.MethodGet, "/v1/status", nil)
		if status.Body["environment"] != one.named.String() {
			t.Fatalf("status said %v", status.Body["environment"])
		}
	}
	plugins := document["required_plugins"].([]any)
	if len(plugins) != 1 {
		t.Fatalf("%d required plugins", len(plugins))
	}
	plugin := plugins[0].(map[string]any)
	if plugin["plugin_id"] != "jupiter.swap" || plugin["min_contract"] != 1.0 ||
		plugin["max_contract"] != 1.0 {
		t.Fatalf("plugin %v", plugin)
	}
	feed := document["feed"].(map[string]any)
	if feed["gateway_url"] != "https://feeds.example.com" ||
		feed["channel"] != "server/"+server {
		t.Fatalf("feed %v", feed)
	}
	expected := "seekervault://feed?v=1&gateway=https%3A%2F%2Ffeeds.example.com&server=" + server
	if answered.Body["reference"] != expected {
		t.Fatalf("reference %v, expected %s", answered.Body["reference"], expected)
	}
}

func TestStatusSaysWhatThisPublisherIs(t *testing.T) {
	held := start(t, &fakeGateway{})
	answered := held.Call(http.MethodGet, "/v1/status", nil)
	if answered.Status != http.StatusOK {
		t.Fatalf("%d: %s", answered.Status, answered.Raw)
	}
	for name, expected := range map[string]any{
		"server_id":   server,
		"channel":     "server/" + server,
		"gateway_url": "https://feeds.example.com",
		"environment": "production",
		"operation":   "swap",
		"plugin_id":   "jupiter.swap",
		"pending":     0.0,
	} {
		if answered.Body[name] != expected {
			t.Fatalf("%s is %v, expected %v", name, answered.Body[name], expected)
		}
	}
}

// The body has to be JSON, one object, bounded, and nothing but the contract's own fields.
func TestABodyIsStrictlyRead(t *testing.T) {
	held := start(t, &fakeGateway{})
	for _, one := range []struct {
		name   string
		Body   string
		Status int
	}{
		{"not JSON at all", "expires_at=tomorrow", http.StatusBadRequest},
		{"a JSON array", `[{"expires_at":"2026-09-17T21:00:00Z"}]`, http.StatusBadRequest},
		{"two objects", `{"expires_at":"2026-09-17T21:00:00Z"} {"note":"and another"}`,
			http.StatusBadRequest},
		{"a number for a term", `{"expires_at":"2026-09-17T21:00:00Z","terms":{"input_decimals":9}}`,
			http.StatusBadRequest},
		{"empty", ``, http.StatusBadRequest},
	} {
		t.Run(one.name, func(t *testing.T) {
			answered := held.Raw(http.MethodPost, "/v1/signals", one.Body)
			if answered.Status != one.Status {
				t.Fatalf("%d: %s", answered.Status, answered.Raw)
			}
		})
	}
	t.Run("a body that is not declared JSON", func(t *testing.T) {
		answered := held.Raw(http.MethodPost, "/v1/signals", `{}`,
			"Content-Type", "application/x-www-form-urlencoded")
		if answered.Status != http.StatusUnsupportedMediaType {
			t.Fatalf("%d: %s", answered.Status, answered.Raw)
		}
	})
	t.Run("a body larger than the bound", func(t *testing.T) {
		big := `{"expires_at":"2026-09-17T21:00:00Z","note":"` +
			string(make([]byte, 0)) + repeat("a", api.MostBodyBytes) + `"}`
		answered := held.Raw(http.MethodPost, "/v1/signals", big)
		if answered.Status != http.StatusBadRequest {
			t.Fatalf("%d", answered.Status)
		}
	})
}

// Every answer says not to cache it. What a publisher currently proposes is the answer, and a
// proxy deciding how long that stays true would be a second opinion about it.
func TestNoAnswerIsCacheable(t *testing.T) {
	held := start(t, &fakeGateway{})
	request, err := http.NewRequest(http.MethodGet, held.API.URL+"/v1/status", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+token)
	response, err := held.API.Client().Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = response.Body.Close() }()
	if response.Header.Get("Cache-Control") != "no-store" {
		t.Fatalf("Cache-Control %q", response.Header.Get("Cache-Control"))
	}
	if response.Header.Get("Content-Type") != "application/json" {
		t.Fatalf("Content-Type %q", response.Header.Get("Content-Type"))
	}
}

func repeat(text string, count int) string {
	built := make([]byte, 0, len(text)*count)
	for range count {
		built = append(built, text...)
	}
	return string(built)
}

func bytes201() []byte {
	key := make([]byte, 201)
	for index := range key {
		key[index] = 'k'
	}
	return key
}

func TestACreateLimitRefusesNewSignalsAndLeavesReplaysUncounted(t *testing.T) {
	held := startWith(t, &fakeGateway{}, signals.Swap{}, func(_ *template, plan *api.Plan) {
		plan.CreateLimit = 2
	})
	first := held.Create("key-1", swapStatement())
	if first.Status != http.StatusCreated {
		t.Fatalf("first create answered %d: %s", first.Status, first.Raw)
	}
	replay := held.Create("key-1", swapStatement())
	if replay.Status != http.StatusOK || replay.Body["idempotent"] != true {
		t.Fatalf("a replay under the cap must still succeed: %d %s", replay.Status, replay.Raw)
	}
	second := held.Create("key-2", swapStatement())
	if second.Status != http.StatusCreated {
		t.Fatalf("second create answered %d: %s", second.Status, second.Raw)
	}
	third := held.Create("key-3", swapStatement())
	if third.Status != http.StatusTooManyRequests || third.Problem() != "rate_limited" {
		t.Fatalf("the cap must refuse the next new signal: %d %s", third.Status, third.Raw)
	}
	replayAtLimit := held.Create("key-1", swapStatement())
	if replayAtLimit.Status != http.StatusOK || replayAtLimit.Body["idempotent"] != true ||
		replayAtLimit.Signal()["proposal_id"] != first.Signal()["proposal_id"] {
		t.Fatalf("a replay after the cap is full must return the first signal: %d %s",
			replayAtLimit.Status, replayAtLimit.Raw)
	}
	moved := swapStatement()
	moved["terms"].(map[string]string)[signals.MaxSlippageBps] = "80"
	conflict := held.Create("key-1", moved)
	if conflict.Status != http.StatusConflict || conflict.Problem() != "key_reused" {
		t.Fatalf("the cap must not mask an idempotency conflict: %d %s",
			conflict.Status, conflict.Raw)
	}
	stillLimited := held.Create("key-4", swapStatement())
	if stillLimited.Status != http.StatusTooManyRequests || stillLimited.Problem() != "rate_limited" {
		t.Fatalf("a replay must not free capacity for another signal: %d %s",
			stillLimited.Status, stillLimited.Raw)
	}
}

func TestNoCreateLimitIsTheDefault(t *testing.T) {
	held := start(t, &fakeGateway{})
	for i := 0; i < 5; i++ {
		answered := held.Create("unlimited-"+string(rune('a'+i)), swapStatement())
		if answered.Status != http.StatusCreated {
			t.Fatalf("create %d answered %d: %s", i, answered.Status, answered.Raw)
		}
	}
}
