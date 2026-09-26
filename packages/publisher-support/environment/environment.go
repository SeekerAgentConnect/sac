// Package environment is what a deployment promises when an owner approves one of its signals
// (SEE-97, docs/wiki/environments.md).
//
// There are two promises and no third. **Production** means the operation is real: the owner's
// wallet signs and sends it on the network their wallet is selected for. **Sandbox** means the
// phone does everything up to that and then stops — live public market data where it exists, the
// same review, and an execution that is simulated and unmistakably labelled as one. Neither is a
// Solana cluster and neither chooses one: the cluster is the owner's wallet selection, and Jupiter
// has no test network to point either of them at.
//
// It is one type in one package because both templates, the configuration that validates it, the
// manifest that publishes it, the store that stamps it and the API that answers it must all mean
// the same thing by the same word. Four places comparing string literals is how one of them ends
// up disagreeing.
//
// The type is deliberately not a string alias with a zero value that means something: an
// [Environment] that came from anywhere but [Parse] has no [Wire] value, so a deployment that
// somehow held one would publish a manifest the gateway refuses rather than a production promise
// nobody configured.
package environment

import (
	serverv1 "github.com/BrRenat/SeekerAgentWallet/publisher-support/gen/seekervault/server/v1"
)

// Environment is one of the two promises, spelled as the configuration and every answer spell it.
type Environment string

const (
	// Production is a real operation, on the network the owner's wallet is selected for.
	Production Environment = "production"
	// Sandbox is live public data and an execution that is simulated on the phone and said to be.
	// It is not a claim that a provider runs a test trading service, because none of them does.
	Sandbox Environment = "sandbox"
)

// Parse reads the one word a deployment configures, and says whether it is one of the two.
//
// There is no default on purpose. A deployment that forgot to say which promise it keeps is a
// configuration problem the operator has to fix, not one this package resolves in their favour:
// the way sandbox and production get mixed up is a copied file, and a default is what makes that
// silent.
func Parse(text string) (Environment, bool) {
	switch Environment(text) {
	case Production:
		return Production, true
	case Sandbox:
		return Sandbox, true
	default:
		return "", false
	}
}

// Wire is the manifest's own value for this promise (`ServerManifest.environments`).
//
// Anything that is not one of the two is `UNSPECIFIED`, which the gateway refuses
// (`GATEWAY_PROBLEM_BAD_ENVIRONMENT`) and no phone would act on. That is the fail-closed direction:
// a value nobody validated must not become the promise with the money attached to it.
func (e Environment) Wire() serverv1.ServerEnvironment {
	switch e {
	case Production:
		return serverv1.ServerEnvironment_SERVER_ENVIRONMENT_PRODUCTION
	case Sandbox:
		return serverv1.ServerEnvironment_SERVER_ENVIRONMENT_SANDBOX
	default:
		return serverv1.ServerEnvironment_SERVER_ENVIRONMENT_UNSPECIFIED
	}
}

// String is the word itself, for a log line, a JSON answer and the database's own stamp.
func (e Environment) String() string { return string(e) }
