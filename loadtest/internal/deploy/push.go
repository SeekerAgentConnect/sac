package deploy

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Push is the controlled stand-in for the push sender (SEE-99).
//
// SEE-99 says it in as many words: a fake or controlled push sender for scale runs, and no mass FCM
// traffic to user devices. So this is a server on loopback that answers the two requests the relay
// makes — the token exchange and the send — and counts them. Nothing leaves the machine.
//
// It is not a mock of the relay: the **real** relay runs in the gateway, mints a real RS256
// assertion, exchanges it, applies its own per-topic quota and sends a real request. What is
// standing in is Firebase, which is the part a load run must not touch. That is a seam the gateway
// already has for its own development — `BROADCAST_PUSH_ENDPOINT` names where the API is, and a
// credential's `token_uri` may be a loopback HTTP one (internal/relay/token.go) — so nothing here
// is a special case built for a test.
//
// What it therefore cannot tell you is how long a hint takes to reach a phone. That is Firebase's
// and the device's, it is measured in seconds rather than milliseconds, and the report says so
// instead of implying a number: transport capacity and push latency are different questions
// (docs/testing/see-99.md).
type Push struct {
	// The origin the gateway is pointed at, and the credential file it reads.
	Endpoint       string
	CredentialPath string
	// Which environment its topics are scoped to.
	Environment string

	server   *http.Server
	listener net.Listener

	mutex sync.Mutex
	// How many hints arrived, per topic.
	hints map[string]int
	// How many token exchanges the relay made. One per hour of run is the expectation: the relay
	// caches, and a number much larger than that would be a finding rather than a detail.
	exchanges int
	// Every send that named something other than a topic. Always zero, and asserted: a hint is
	// addressed to a feed's topic and never to a device token, which is the whole reason a
	// subscriber is unknown to this deployment.
	addressed []string
}

// The project the throwaway credential claims. It is not a real project, and the stand-in answers
// only for this one.
const pushProject = "seeker-vault-loadtest"

// StartPush brings up the stand-in and writes the credential the gateway will read.
func StartPush(dir, environment string) (*Push, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("deploy: listening for the push stand-in: %w", err)
	}
	push := &Push{
		Endpoint:    "http://" + listener.Addr().String(),
		Environment: environment,
		listener:    listener,
		hints:       map[string]int{},
	}
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, fmt.Errorf("deploy: making a throwaway push key: %w", err)
	}
	pkcs8, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return nil, fmt.Errorf("deploy: encoding the throwaway push key: %w", err)
	}
	credential := map[string]string{
		"type":         "service_account",
		"project_id":   pushProject,
		"client_id":    "0",
		"client_email": "loadtest@" + pushProject + ".iam.gserviceaccount.invalid",
		"private_key": string(pem.EncodeToMemory(
			&pem.Block{Type: "PRIVATE KEY", Bytes: pkcs8})),
		// Loopback HTTP, which the relay allows for exactly this: a development endpoint on a
		// machine whose operator wrote the credential file.
		"token_uri": push.Endpoint + "/token",
	}
	body, err := json.MarshalIndent(credential, "", "  ")
	if err != nil {
		return nil, fmt.Errorf("deploy: encoding the throwaway credential: %w", err)
	}
	push.CredentialPath = filepath.Join(dir, "push-credential.json")
	if err := os.WriteFile(push.CredentialPath, body, 0o600); err != nil {
		return nil, fmt.Errorf("deploy: writing the throwaway credential: %w", err)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("POST /token", push.token)
	mux.HandleFunc("POST /v1/projects/"+pushProject+"/messages:send", push.send)
	push.server = &http.Server{Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	go func() { _ = push.server.Serve(listener) }()
	return push, nil
}

// Stop closes the stand-in.
func (p *Push) Stop() {
	if p.server != nil {
		_ = p.server.Close()
	}
}

// Hints is how many hints arrived, per topic, and how many token exchanges it took.
func (p *Push) Hints() (map[string]int, int) {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	hints := make(map[string]int, len(p.hints))
	for topic, count := range p.hints {
		hints[topic] = count
	}
	return hints, p.exchanges
}

// Addressed is every send that named anything but a topic. It must always be empty.
func (p *Push) Addressed() []string {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	return append([]string(nil), p.addressed...)
}

func (p *Push) token(writer http.ResponseWriter, request *http.Request) {
	// The assertion is read and discarded: whether it is a well-formed RS256 grant is
	// internal/relay/token_test.go's question, and answering it here would be a second opinion
	// about the gateway's own code inside a harness that is measuring throughput.
	_ = request.ParseForm()
	p.mutex.Lock()
	p.exchanges++
	p.mutex.Unlock()
	writer.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(writer).Encode(map[string]any{
		"access_token": "loadtest-access-token",
		"token_type":   "Bearer",
		"expires_in":   3600,
	})
}

func (p *Push) send(writer http.ResponseWriter, request *http.Request) {
	var sent struct {
		Message struct {
			Topic string `json:"topic"`
			Token string `json:"token"`
			Data  map[string]string
		} `json:"message"`
	}
	if err := json.NewDecoder(request.Body).Decode(&sent); err != nil {
		http.Error(writer, `{"error":{"status":"INVALID_ARGUMENT"}}`, http.StatusBadRequest)
		return
	}
	p.mutex.Lock()
	if sent.Message.Topic == "" || sent.Message.Token != "" {
		p.addressed = append(p.addressed,
			fmt.Sprintf("topic %q token %q", sent.Message.Topic, sent.Message.Token))
	}
	if sent.Message.Topic != "" {
		p.hints[sent.Message.Topic]++
	}
	p.mutex.Unlock()
	writer.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(writer).Encode(map[string]any{
		"name": "projects/" + pushProject + "/messages/1",
	})
}
