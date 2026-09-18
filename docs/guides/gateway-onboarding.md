# Onboard one user through the gateway

Use this path when you run an independent backend and want one SAC owner to receive private
requests without running a sidecar for that owner. Public signals still use a feed reference, and an
owner-operated sidecar still uses direct pairing.

## 1. Register the server and keep its credential in the backend

The gateway operator registers a server exactly as for a publisher:

```sh
cd broadcast
go run ./cmd/broadcastctl register --database ./broadcast.db \
  --server 3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d --label "independent server"
```

Put the shown credential in backend secret storage. It is sent only as an Authorization header to
the publisher listener. Do not put it in HTML, JavaScript, an app, a URL or a QR code.

## 2. Create the SDK client and publish the private manifest

```go
gateway, err := sdk.NewGateway(sdk.GatewayOptions{
    URL:      os.Getenv("GATEWAY_URL"),
    Token:    os.Getenv("GATEWAY_TOKEN"),
    ServerID: os.Getenv("SERVER_ID"),
})
```

Publish a version-1 manifest with mode `CONNECTION_MODE_GATEWAY_PRIVATE`, a
`GatewayPrivate{GatewayUrl: ...}` reference, every environment the server serves, and the plugins
its operations require. The origin must exactly equal the gateway's `BROADCAST_PUBLIC_URL`. Mode
and served environments cannot be changed by a later revision; use a distinct server ID for a
different deployment promise. SAC preserves the selected manifest environment: it starts in
sandbox whenever sandbox is offered, otherwise production, and the owner may switch only between
environments the manifest names.

The complete compiling example is
[`publisher/examples/gateway-onboarding/main.go`](../../publisher/examples/gateway-onboarding/main.go):

```sh
cd publisher
GATEWAY_URL=https://gateway.example \
GATEWAY_TOKEN="$BACKEND_ONLY_SECRET" \
SERVER_ID=3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d \
go run ./examples/gateway-onboarding --kind trading --environment sandbox \
  --user account-42 --wait --send
```

This is also the no-website bot/agent/CLI example: send the printed hosted URL in chat or render the
printed app URI as a QR. The gateway owns the page and install guidance, so a backend with no web
frontend needs no second service.

The command has complete trading, prediction and MCP-backed-agent profiles:

```sh
go run ./examples/gateway-onboarding --kind trading --user account-42 --wait --send
go run ./examples/gateway-onboarding --kind prediction --user account-42 --wait --send
go run ./examples/gateway-onboarding --kind mcp --user account-42 --wait --send
```

For a developer website, run the backend-only example and open its local form:

```sh
SERVER_KIND=trading SERVER_ENVIRONMENT=sandbox LISTEN_ADDR=127.0.0.1:8090 \
go run ./examples/gateway-website
```

Both examples default to sandbox; production is an explicit `--environment production` or
`SERVER_ENVIRONMENT=production`. Set `SERVER_KIND` to `prediction` or `mcp` to advertise the
corresponding plugin. The page submits
only an opaque user reference to this backend. The backend calls the SDK and embeds the gateway's
hosted page/QR; the gateway publisher credential is never rendered into HTML or browser code.

## 3. Put the invitation on a website—or do not

```go
invite, err := gateway.CreateInvitation(ctx, opaqueUserRef, 15*time.Minute)
```

For a website, return `invite.GetInvitationUrl()` from an authenticated backend endpoint and use it
as a normal link. For a QR, encode `invite.GetAppUri()` as QR data. For a bot or CLI, print either.
The hosted page already renders a QR, an **Open in SAC** button and install guidance.

Creating an invitation does not bind a device. Page views, QR image requests, SAC previews and
cancellation do not bind one either. The invitation is consumed only after the owner confirms in
SAC. A zero lifetime selects 15 minutes; the allowed range is one minute through 24 hours.

If the onboarding session is cancelled at the server, call
`gateway.RevokeInvitation(ctx, invite.GetInvitationId())`. Revocation is permanent and idempotent;
the public page, resolve and redeem paths thereafter treat that temporary capability as invalid. A
completed invitation is already a device connection, so revoke it with `RevokeConnection` instead.

Use a server-scoped opaque reference. A new onboarding session is a good default:

```go
invite, err := gateway.CreateInvitation(ctx, "onboarding-session-42", 15*time.Minute)
```

Do not use a wallet address. The value is routing state for your server, not a SAC or gateway user
account.

## 4. Observe completion on the backend

Poll by invitation ID over the authenticated SDK transport:

```go
status, err := gateway.WaitForConnection(ctx, invite.GetInvitationId(), time.Second)
switch status.GetStatus() {
case gatewayv1.InvitationStatus_INVITATION_STATUS_CONNECTED:
    connectionID := status.GetConnectionId()
case gatewayv1.InvitationStatus_INVITATION_STATUS_EXPIRED:
    // Offer a newly created invitation. Never extend or reuse this one.
}
```

`GetInvitation`/`WaitForConnection` is the completion transport. There is intentionally no redirect
or browser webhook: a browser never receives a long-lived credential. Store the connection ID
beside the opaque user reference. It is the durable target for this device; the gateway checks both
values under your authenticated server identity on every request.

## 5. Send the common request

```go
record, err := gateway.SendRequest(ctx, sdk.PrivateRequest{
    RequestID:         requestID,
    Revision:          1,
    UserRef:           "onboarding-session-42",
    ConnectionID:      connectionID,
    ExpiresAt:         time.Now().Add(10 * time.Minute),
    Title:             "Review swap",
    Description:       "Strategy rebalance",
    CapabilityID:      "swap",
    CapabilityVersion: 1,
    PluginID:          "jupiter.swap",
    Parameters:        swapParameters,
    OwnerInputs:       swapOwnerInputs,
})
```

Use the exact operation parameters documented by the client plugin. A prediction server uses
`prediction` / `jupiter.prediction`; a trading server uses `swap` / `jupiter.swap`. An MCP tool on
your backend may call the same SDK method—the request's origin is your authenticated server, not the
UI or agent that triggered your code. MCP does not receive a device credential and does not become
an approval surface.

Apply that same call according to what your backend does:

- **Trading or copy trading:** publish a manifest requiring `jupiter.swap` contract 1, send the
  `swap` capability with the mint/decimal/slippage terms from
  [`jupiter-swap.md`](../wiki/jupiter-swap.md), and declare the owner's amount input. Unlike a public
  signal, the explicitly declared choice and terminal outcome return to this authenticated server.
- **Prediction:** require `jupiter.prediction` contract 1, send the `prediction` capability with a
  provider market ID, and declare the owner's side and stake inputs. SAC still obtains current
  market data and resolves address lookup tables itself; onboarding does not make the server's
  claims trusted ([`jupiter-prediction.md`](../wiki/jupiter-prediction.md)).
- **MCP bot or agent:** keep both the MCP credential and gateway publisher credential in the
  backend. The tool handler calls `SendRequest`, returns its source request ID, and later calls
  `Request` for the result. The agent receives neither invitation nor device credentials and cannot
  confirm, approve, select a wallet, or sign on the owner's behalf.

The executable [`gateway-onboarding`](../../publisher/examples/gateway-onboarding/main.go) command
is the no-website form. A developer website uses the same `CreateInvitation` response and may show
the hosted link directly; it must not proxy the publisher credential into browser code.

Poll `gateway.Request(ctx, requestID)` for the source document and eventual result. Repeating the
same revision with identical content is unchanged; changing content without raising the revision is
a conflict. `CancelRequest` raises the revision and closes the source request. The gateway pins a
request to the explicitly selected binding when it accepts it. A missing, revoked, foreign or
user-mismatched connection is `NO_BINDING`; the gateway never falls back to another device.

SAC still performs plugin compatibility checks, collects the declared inputs locally, prepares and
inspects exact bytes, evaluates policy, asks for manual approval and opens the wallet only after all
of those gates. Connecting is none of those actions. A feed keeps results device-local; this private
request returns only because it explicitly carries `RESULT_MODE_RETURN_TO_ORIGIN`.

## 6. Additional devices and revocation

Every additional device needs a fresh single-use invitation. It may use the same server-scoped user
reference and creates a distinct connection ID; it never replaces an existing binding. Store each
completed connection ID and choose it explicitly in `SendRequest`. SAC's Disconnect revokes only
that device before removing its local credential, and `RevokeConnection` lets the authenticated
server revoke the same one independently. Sibling devices remain active. Publisher credential
rotation is separate and does not revoke device bindings.

## Troubleshooting

- `INVITATION_EXPIRED`: create a fresh invitation; never recycle the old token.
- `INVITATION_USED`: the single redemption already happened. Check authenticated status by ID.
- A revoked invitation resolves as `INVALID_INVITATION`; only the authenticated backend knows it
  deliberately revoked that temporary capability.
- `NO_BINDING`: the named connection is absent, revoked, belongs to another user reference, or
  belongs to another server. Use the connection ID returned for this invitation; never route
  elsewhere.
- `RESULT_CONFLICT`: a terminal outcome already stands. Never ask the wallet again.
- A page opens but SAC cannot connect: verify the manifest mode and that its gateway origin exactly
  matches the page origin, including scheme and port.

The security and storage rationale is in
[`docs/wiki/gateway-pairing.md`](../wiki/gateway-pairing.md).
