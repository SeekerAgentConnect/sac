// Package gateway serves the shared gateway's feed and publisher APIs (SEE-90,
// docs/wiki/broadcast-gateway.md).
//
// The two APIs are built here as separate handlers on separate listeners, with separate
// interceptors, and the only thing they share is the store underneath them. That separation is the
// deployment's as well as the code's: a read port has no handler that could change anything, so no
// routing mistake in front of it can turn a read endpoint into a write one.
//
// Nothing in this package calls out. The gateway is called — by publishers, and by phones — and it
// contacts no publisher, no chain, no provider and no phone. A boundary test fails if an HTTP
// client appears in shipped code.
package gateway

import (
	"errors"
	"fmt"
	"strings"

	"connectrpc.com/connect"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1"
	"github.com/BrRenat/SeekerAgentWallet/broadcast/internal/rules"
)

// refuse turns the one rule a call broke into the error its caller sees: a Connect code, a short
// message naming the rule and the field, and the GatewayErrorDetail that carries both as data.
//
// The message never contains what was refused. A publisher's mistake is theirs to look at, and a
// gateway that echoed values would write other people's documents into whatever reads its errors.
func refuse(fault *rules.Fault) *connect.Error {
	code := codeOf(fault.Problem)
	message := problemCode(fault.Problem)
	if fault.Field != "" {
		message = fmt.Sprintf("%s (%s)", message, fault.Field)
	}
	failure := connect.NewError(code, errors.New(message))
	detail, err := connect.NewErrorDetail(&gatewayv1.GatewayErrorDetail{
		Problem:      fault.Problem,
		Field:        fault.Field,
		HeldRevision: fault.Held,
	})
	if err == nil {
		failure.AddDetail(detail)
	}
	return failure
}

// problemCode is the enum value as a caller reads it in a message: "foreign_channel" rather than
// "GATEWAY_PROBLEM_FOREIGN_CHANNEL". It is the same spelling the phone's own problem codes use,
// which matters when the two are being compared in one investigation.
func problemCode(problem gatewayv1.GatewayProblem) string {
	return strings.ToLower(strings.TrimPrefix(problem.String(), "GATEWAY_PROBLEM_"))
}

// codeOf maps a rule to a Connect code, and the grouping is the point rather than the mapping: a
// caller can tell "you may not" from "not while this is true" from "that is not how it is spelled"
// without reading the detail, which is what a retry policy needs.
func codeOf(problem gatewayv1.GatewayProblem) connect.Code {
	switch problem {
	case gatewayv1.GatewayProblem_GATEWAY_PROBLEM_UNAUTHENTICATED:
		return connect.CodeUnauthenticated
	case gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_SERVER,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_FOREIGN_CHANNEL:
		// Not an argument problem: the document is well formed and names something the caller has
		// no claim on.
		return connect.CodePermissionDenied
	case gatewayv1.GatewayProblem_GATEWAY_PROBLEM_STALE_REVISION,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_REVISION_CONFLICT,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_CANCELLED,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_PROPOSALS,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_OTHER_ENVIRONMENT:
		// True of the call only while the gateway holds what it holds. Nothing was written.
		//
		// The environment is in this group rather than among the malformed documents because the
		// document is well formed: it is this server ID that has already promised something else
		// (SEE-97), and a publisher reading the refusal needs to know that nothing about the
		// spelling would fix it.
		return connect.CodeFailedPrecondition
	case gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_SERVER,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_SUCH_PROPOSAL:
		return connect.CodeNotFound
	case gatewayv1.GatewayProblem_GATEWAY_PROBLEM_TOO_MANY_REQUESTS:
		return connect.CodeResourceExhausted
	case gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_STREAM,
		gatewayv1.GatewayProblem_GATEWAY_PROBLEM_NO_PUSH:
		// Not a mistake in the request: the method is there and this deployment does not implement
		// it. It is the same answer a sidecar without live updates gives the phone (SEE-66), and
		// the phone already reads it as "this one does not do that" rather than as a failure.
		return connect.CodeUnimplemented
	default:
		// Every remaining problem is a document or a request the gateway could not read.
		return connect.CodeInvalidArgument
	}
}

// problem is the shorthand for refusing with a problem and a field and nothing held.
func problem(code gatewayv1.GatewayProblem, field string) *connect.Error {
	return refuse(&rules.Fault{Problem: code, Field: field})
}
