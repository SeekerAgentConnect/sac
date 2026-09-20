package gateway

import (
	"context"
	"crypto/sha256"
	"net"
	"net/netip"
	"regexp"
	"strings"

	"connectrpc.com/connect"

	gatewayv1 "github.com/BrRenat/SeekerAgentWallet/feed-gateway/internal/gen/seekervault/gateway/v1"
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
// request came from a configured proxy — the address the proxy says it came from.
//
// Loopback is always trusted. An ordinary container proxy must be in one of the deployment's
// explicit prefixes; forwarded headers from every other peer are ignored. Without that condition,
// every read behind Caddy would count against one address. Without the allow-list, any container or
// remote client could choose its own rate-limit identity.
//
// It is still only a backstop. A reverse proxy sees a client before the gateway does and is where
// a serious limit belongs; this one bounds what one address can cost the store.
func caller(peer string, forwarded string, trusted []netip.Prefix) string {
	host := peer
	if parsed, _, err := net.SplitHostPort(peer); err == nil {
		host = parsed
	}
	if !isTrustedProxy(host, trusted) || forwarded == "" {
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

func isTrustedProxy(host string, trusted []netip.Prefix) bool {
	if isLoopback(host) {
		return true
	}
	address, err := netip.ParseAddr(strings.Trim(host, "[]"))
	if err != nil {
		return false
	}
	for _, prefix := range trusted {
		if prefix.Contains(address) {
			return true
		}
	}
	return false
}

func isLoopback(host string) bool {
	if host == "localhost" {
		return true
	}
	address := net.ParseIP(strings.Trim(host, "[]"))
	return address != nil && address.IsLoopback()
}
