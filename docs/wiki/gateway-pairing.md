# Gateway pairing invitations

SEE-109 adds a private adapter to the shared gateway without turning it into a SAC account
service. An independent server uses the existing Go Server SDK to create a temporary invitation;
the owner confirms it in SAC; the gateway then routes that server's common requests to the one
device binding created by that confirmation.

This is a third connection mode. It does not replace either existing path:

| Mode | Onboarding | Audience | Result |
| --- | --- | --- | --- |
| `direct` | the sidecar's legacy pairing code | one paired phone | returned to that sidecar |
| `gateway_feed` | public `seekervault://feed` reference | every subscriber | device-local |
| `gateway_private` | temporary gateway invitation | one confirmed binding | returned only to the authenticated originating server |

## The flow

1. An authenticated server publishes a `gateway_private` manifest naming the gateway's configured
   public origin.
2. Its backend calls `PublisherService.CreateInvitation` with an opaque, server-scoped `user_ref`
   and a lifetime (default 15 minutes, at most 24 hours).
3. The SDK returns an invitation ID for authenticated status polling, a gateway-hosted page URL,
   and a `seekervault://invite` URI suitable for a QR code. The publisher credential is in none of
   them.
4. Visiting the page, loading its QR image, opening SAC, resolving metadata, or cancelling the SAC
   confirmation writes no binding and does not consume the invitation.
   The authenticated server may permanently revoke a still-pending invitation; after that its page,
   resolve and redeem paths expose it only as invalid.
5. SAC validates the invitation's manifest and shows the server name, gateway and expiry. Only the
   owner's explicit confirmation calls `RedeemInvitation`.
6. Redemption consumes the invitation and creates the binding in one SQLite transaction. The
   gateway returns a new device credential once; SAC stores it in the existing encrypted credential
   vault. A racing second redemption of the same invitation loses. A fresh invitation creates a
   separate binding even when the server uses the same user reference; it never silently replaces
   another device.
7. `GetInvitation` changes from `PENDING` to `CONNECTED` and reports the connection ID over the
   authenticated backend API. A website may poll it, a bot may wait with `WaitForConnection`, and a
   CLI may do either. There is no browser callback carrying a credential.
8. `SendRequest` accepts the SEE-108 common request only with a private audience and
   `RETURN_TO_ORIGIN`. The server supplies both its opaque user reference and the completed
   connection ID; the gateway verifies that exact active binding and pins the request to it. SAC
   reads it through `DeviceService`, runs the same plugin, policy, manual
   review, exact binding and wallet flow, then returns the declared owner inputs and final outcome.

The hosted page is `https://<gateway>/invite/<temporary token>`. Its QR and button carry:

```text
seekervault://invite?v=1&gateway=https%3A%2F%2F<gateway>&token=<temporary token>
```

The page is `no-store`, has a locked-down content security policy and sends no referrer. The proxy
skips access logging for `/invite/*`, and application errors never print the token. The capability
is deliberately temporary, but it is still a secret until it expires or is consumed.

## Identity and authority

`user_ref` belongs to one authenticated server. It may be an internal user ID or a freshly minted
onboarding-session ID; the gateway validates only that it is bounded printable text. It is not a
gateway identity, is not shared across servers and does not create a SAC account. The invitation
preview does not return it.

A fresh single-use invitation may add another device for the same `(server_id, user_ref)`. The
server stores the connection ID returned by completion and supplies it with that user reference on
every request. The two values must identify the same active binding, which prevents accidental
routing to another device and prevents one server from naming another's binding. Revocation affects
only the named connection; sibling devices, public feed subscriptions, another server's bindings
and the owner's local activity remain intact.

Pairing authorizes only the private transport. It selects no wallet, approves no rule, opens no
wallet and signs nothing. The gateway stores the minimum routing association, requests addressed to
it, and results that the source explicitly marked `RETURN_TO_ORIGIN`; it has no central user profile
and learns no wallet authorization token or history. The device credential is hashed at rest at the
gateway and never appears in a URL.

## Process boundaries

The gateway has three listeners:

- the public feed listener is anonymous and read-only;
- the publisher listener authenticates the originating server and creates invitations/requests;
- the client listener serves invitation pages and RPCs plus device-authenticated request/result
  calls. It cannot publish a manifest or create a server request.

The SQLite migration adds `invitation`, `device_binding` and `private_request`. Invitation
consumption and binding creation share one transaction. A private request stores the explicitly
selected connection, so a later revocation or another binding cannot move an already-addressed
request.
The existing public feed tables still contain no subscriber column and public reads still write
nothing.

The Android app accepts invitation URIs on cold and warm starts, and accepts the same URI or hosted
URL through the existing scan/paste surface. A successful confirmation stores the connection and
immediately starts the authoritative request read; no restart is needed. Pending, connected,
expired, invalid and failed states remain distinct.

See the [developer onboarding guide](../guides/gateway-onboarding.md) for SDK calls and runnable
examples, and [the common request contract](common-requests.md) for action and result policy.
