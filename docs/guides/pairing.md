# Pairing the phone with a sidecar

The app talks to each self-hosted sidecar through a **connection**. You pair once per sidecar, and the phone keeps every connection separate: its own address, its own credential, and its own requests. [`docs/security.md`](../security.md) explains the model, and [`docs/protocol.md`](../protocol.md#pairing) the format.

## Before you start

- The MCP server is running: `pnpm dev:mcp-server` ([`docs/development/mcp-server.md`](../development/mcp-server.md)).
- The app is installed on the phone ([`macbook-seeker-quickstart.md`](macbook-seeker-quickstart.md)).
- The phone can reach the sidecar at `SIDECAR_PUBLIC_URL`, the address the pairing code carries:
  - **Over USB, with a debug build:** leave `SIDECAR_PUBLIC_URL` unset. The code then carries `http://127.0.0.1:8080`, and `adb reverse tcp:8080 tcp:8080` forwards it to the Mac. Only a debug build accepts that plain HTTP address, and only to `127.0.0.1` or `localhost`.
  - **From another network:** put a trusted HTTPS endpoint in front of the sidecar, such as Tailscale Serve, and set `SIDECAR_PUBLIC_URL` to its `https://` URL; see [a trusted endpoint](../security.md#a-trusted-endpoint-for-this-stages-remote-test). A self-signed certificate won't work: the app checks certificates the normal way.

## Pair

1. On the computer that runs the sidecar, run `pnpm pair`, or from a connected agent call `vault_create_pairing_link`. Either prints (or returns) a `seekervault://pair?` deep link and an `https://<origin>/pair?` landing page that opens the same link. The code works once, for 10 minutes, and a newer code replaces it. Opening the deep link or the HTTPS page on Android goes to the same confirmation screen and does not contact the server until the owner taps **Pair**. You can also paste either URI under **Add connection**.
2. In the app, on **Connections**, tap **Add connection**.
3. Tap **Scan QR code**. The first time, Android asks for camera access; allow it. Point the camera at the QR code.

   If you'd rather not use the camera, or the QR code won't scan (light terminal themes can make that hard), type or paste the `seekervault://pair?` line into **Pairing code** and tap **Continue**.
4. **Check the server.** The app shows the address it's about to contact and the server's ID. Pair only with a sidecar you run: whoever controls it can send the phone requests to review. If the code is for a server the phone already knows, the app says so.
5. Tap **Pair**. The new connection's details open, named after the server's host.

Pairing makes a new connection every time. It never changes a connection you already have. Because a sidecar has one paired phone at a time, pairing again revokes that sidecar's previous connection, whether it was on this phone or another one.

## Managing connections

**Connections** lists every sidecar the phone is paired with: its name, its address, and its status. Tap one to open its details.

| Action | What it does |
| --- | --- |
| **Refresh** | Asks the sidecar for the connection's pending requests. The app also does this when it opens and when you open a connection. |
| **Rename** | Changes the connection's name on this phone, up to 64 characters. The sidecar never sees it. |
| **Disconnect** | The sidecar revokes this phone's credential and cancels the connection's pending requests. Then the app removes the connection. If the sidecar can't be reached, the app says so and offers to remove the connection from the phone anyway. The credential then keeps working on the sidecar until you run `pnpm pair revoke` there. |
| **Remove from this phone** | For a connection the sidecar no longer accepts. There's nothing left to revoke, so the app just deletes it. |

Several sidecars work the same way: pair with each one. Removing one connection never touches another, and a connection never shows another connection's requests.

The **Live test** button on Connections opens the Stage 1 live-test screen, which still uses `PHONE_TOKEN` ([`docs/development/android.md`](../development/android.md)).

## Statuses

| Status | Meaning | What to do |
| --- | --- | --- |
| Connected. Pending requests: N. | The last refresh reached the sidecar. | Nothing |
| Not checked yet. | No refresh since pairing. | Tap **Refresh**. |
| Couldn't reach the server. | The sidecar isn't running, or the phone can't reach its address. | Start the sidecar. Over USB, run `adb reverse` again after reconnecting the cable. |
| The server's certificate isn't trusted, or it's for another host name. | TLS failed. | Use a trusted HTTPS endpoint whose certificate matches the address. |
| This build allows plain HTTP only to 127.0.0.1. | A plain HTTP address that isn't loopback, or a release build. | Use HTTPS. |
| The server no longer accepts this phone. Pair again to reconnect. | The sidecar revoked the connection: `pnpm pair revoke`, another pairing, or a reset database. The app deleted the credential. | Pair again, then remove the old connection. |
| This phone no longer has the credential for this connection. Pair again to reconnect. | The phone can't read the credential, for example after Android's keystore lost its key. | Pair again, then remove the old connection. |

## When pairing fails

| The app says | Why | Fix |
| --- | --- | --- |
| The server refused the code… | The code was used, it expired, or a newer one replaced it. | Run `pnpm pair` again and use the new code. |
| The server says this code was issued for a different address… | The phone reached the sidecar at an address other than the code's. | Scan the code again. Check that `SIDECAR_PUBLIC_URL` is the address the phone uses. |
| The server's certificate isn't trusted… | TLS failed. | See the statuses above. |
| Couldn't reach … | The sidecar isn't reachable at that address. | Start it, or fix the address. You can tap **Try again**. |
| That isn't a pairing code… or another message about the code | The text isn't a complete `seekervault://pair?` line. | Copy the whole line again, or scan the QR code. |
| The code's server URL doesn't use HTTPS… | A plain HTTP address that the build doesn't allow. | Use HTTPS, or `127.0.0.1` over `adb reverse` with a debug build. |
| Camera access is off… | Android's camera permission is denied. | Tap **Open settings** and allow the camera, or enter the code instead. |

## A new phone, or a lost one

- **Nothing is backed up or moved to a new phone.** On a new phone, or after reinstalling the app, pair with each sidecar again. The sidecar revokes the old phone's connection when the new one pairs.
- **A lost or stolen phone:** run `pnpm pair revoke` on each sidecar it was paired with. Its credential stops working at once, and its pending requests are cancelled.

[`docs/security.md`](../security.md#local-storage-and-recovery) has the details of what the phone stores and how.
