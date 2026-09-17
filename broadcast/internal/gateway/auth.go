package gateway

import (
	"context"
	"crypto/sha256"
	"net"
	"regexp"
	"strings"

	"connectrpc.com/connect"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/broadcast/internal/gen/seekervault/gateway/v1"
)

// Publishers is the part of the store authentication uses: a credential's hash resolves to the
// server it publishes as, or to nothing. That is the whole of a grant — there is one thing a
// publisher can do, and it is do it to its own server — so there is no permission to look up and
// none to get wrong.
type Publishers interface {
	PublisherFor(ctx context.Context, hash []byte) (string, error)
}

type publisherKey struct{}

// publisherOf is the server the caller publishes as, put there by the interceptor. A handler that
// reads it is reading the credential's claim, never the document's.
func publisherOf(ctx context.Context) string {
	serverID, _ := ctx.Value(publisherKey{}).(string)
	return serverID
}

var bearerPattern = regexp.MustCompile(`^Bearer (\S+)$`)

// Authenticating resolves the caller's credential once, for every method on the publisher API, and
// refuses the call if it resolves to nothing.
//
// The comparison is against SHA-256, which is what the store keeps — the same thing the sidecar
// does with a phone's credential (storage/pairing-store.ts). Nothing here compares the credential
// itself, so the database cannot hand anyone the ability to publish, and what a lookup's timing
// could reveal is a property of a hash rather than of a secret.
//
// There is one refusal for every way this can fail: no header, a header that is not exactly
// `Bearer <credential>`, an unknown credential, and a revoked one all answer the same
// unauthenticated problem. A caller learns that it may not publish, and never whether the thing it
// presented used to work.
func Authenticating(publishers Publishers) connect.UnaryInterceptorFunc {
	return func(next connect.UnaryFunc) connect.UnaryFunc {
		return func(ctx context.Context, request connect.AnyRequest) (connect.AnyResponse, error) {
			match := bearerPattern.FindStringSubmatch(request.Header().Get("Authorization"))
			if match == nil {
				return nil, unauthenticated()
			}
			sum := sha256.Sum256([]byte(match[1]))
			serverID, err := publishers.PublisherFor(ctx, sum[:])
			if err != nil {
				return nil, connect.NewError(connect.CodeInternal, err)
			}
			if serverID == "" {
				return nil, unauthenticated()
			}
			return next(context.WithValue(ctx, publisherKey{}, serverID), request)
		}
	}
}

func unauthenticated() *connect.Error {
	return problem(gatewayv1.GatewayProblem_GATEWAY_PROBLEM_UNAUTHENTICATED, "")
}

// caller is who a rate limit counts against: the address the request came from, or — when the
// request came from a proxy on this machine — the address the proxy says it came from.
//
// The forwarded address is trusted only from loopback, and that is what makes trusting it safe: a
// remote client cannot make its own connection appear to come from 127.0.0.1, and the deployment
// this gateway ships with puts Caddy in front of it on the same host (broadcast/Caddyfile). Without
// the rule, every read in that deployment would count against one address and the limit would be a
// limit on the gateway rather than on a client.
//
// It is still only a backstop. A reverse proxy sees a client before the gateway does and is where
// a serious limit belongs; this one bounds what one address can cost the store.
func caller(peer string, forwarded string) string {
	host := peer
	if parsed, _, err := net.SplitHostPort(peer); err == nil {
		host = parsed
	}
	if !isLoopback(host) || forwarded == "" {
		return host
	}
	// The last entry is the one this hop received the request from, which is the only one that
	// cannot have been written by a client further away.
	parts := strings.Split(forwarded, ",")
	last := strings.TrimSpace(parts[len(parts)-1])
	if last == "" {
		return host
	}
	if parsed, _, err := net.SplitHostPort(last); err == nil {
		return parsed
	}
	return last
}

func isLoopback(host string) bool {
	if host == "localhost" {
		return true
	}
	address := net.ParseIP(strings.Trim(host, "[]"))
	return address != nil && address.IsLoopback()
}
