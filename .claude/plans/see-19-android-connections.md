# SEE-19 / SAW-012 — Add Android pairing and multiple connection management

Linear: https://linear.app/seekeragentwallet/issue/SEE-19 · Branch: `develop` · One PR `develop` → `master` after SEE-21

## Checklist

- [x] Pairing code parser (`connections/PairingCode.kt`): the same rules as the sidecar's `parsePairingUri`, with plain HTTP only where the platform's network security policy permits cleartext (debug builds, loopback)
- [x] Storage (`connections/storage/`), keyed by connection ID:
  - [x] metadata: one JSON file per connection in `filesDir/connections/`, written atomically
  - [x] credentials: AES-256-GCM with a non-exportable Android Keystore key; one file per connection in `noBackupFilesDir/credentials/`, with the connection ID as associated data, so a credential can't move to another connection
  - [x] backups stay off: `allowBackup="false"`, and every domain excluded from cloud backup and device transfer (MWA material later goes in the same store)
- [x] Network (`ConnectConnectionGateway`): `Pair`, `ListPending`, `RevokeConnection` over Connect-Kotlin/OkHttp, with normal certificate and hostname checks; errors classified (revoked, certificate rejected, cleartext blocked, unreachable, rejected code)
- [x] `ConnectionRepository`: pair (validate the response: UUID connection ID, credential format, matching server ID), refresh, rename, disconnect (revoke, then delete), remove locally, reconcile at start
  - [x] a credential is sent only to its own connection's URL; a code never changes an existing connection
  - [x] `UNAUTHENTICATED` on refresh marks the connection revoked and deletes its credential: pair again
  - [x] requests from another connection are never attached to this one
- [x] UI (stock Material 3): Connections list, Connection details (refresh, rename, disconnect/remove), Add connection (scan or enter the code), a confirmation that shows the server before pairing, and the Stage 1 live test kept reachable
- [x] QR scanning: CameraX preview and analysis with ZXing's QR decoder; camera permission requested at scan time; denial and a missing camera fall back to entering the code; malformed codes say why
- [x] Guards: `StageBoundaryTest` (renamed) allows `CAMERA` and storage/Keystore only in `connections/storage/`; still no background components, wallet, Room, DataStore, or WorkManager
- [x] Tests:
  - [x] parser: every malformed case, and redaction in `toString`
  - [x] vault and store: round trip, no plaintext on disk, isolation (deleting A keeps B; A's credential file fails as B's), restart
  - [x] repository and ViewModel: two connections, rename, restart, revocation, re-pairing the same server at a new URL, response validation, deleting A without touching B or attaching A's requests to B, no secrets in logs
  - [x] real sidecar (JVM): a code printed by `pnpm pair`, parsed in Kotlin, paired, listed, revoked by the phone and by `pnpm pair revoke`, and two sidecars at once
  - [x] TLS (JVM, MockWebServer): an untrusted certificate and a hostname mismatch are refused, a trusted one works
  - [x] Compose: list, details, rename, disconnect dialogs, manual entry errors, camera denial (through the activity result registry), no credential on screen
  - [x] QR decoding of a generated code
  - [x] device (emulator in CI): the real Keystore key round trip, not exportable
- [x] Docs: `docs/guides/pairing.md`; `docs/security.md` (local storage and recovery); `docs/development/android.md`; README, AGENTS, CODEBASE, toolchain, the quickstart, changelog, decisions
- [x] Verify: `pnpm check`, `pnpm test:hello`, `pnpm check:android`, `pnpm check:generated`, deliberate breaks
- [ ] Commit and push on `develop`, and CI green after the push (its emulator job runs the device tests)
- [ ] Linear: tick the SEE-19 checklist, add a summary, and move the issue to In Review

## Design

- **Connections are keyed by the sidecar's connection ID** (a lowercase UUID, checked before it names a file). Each connection has its own metadata file and credential file, so deleting one can't touch another.
- **A connection's URL never changes.** To use a new address, the owner pairs again; the old credential is only ever sent to the old URL. A code whose server ID matches a known connection still makes a new connection, and the app then refreshes the old one, which the sidecar has revoked.
- **Nothing is backed up.** Restoring to a new phone restores no connections; the owner pairs again (`pnpm pair`), which revokes the old phone's connection. A lost phone is revoked with `pnpm pair revoke`.
- **The pairing token lives only in memory** (the ViewModel), never in saved instance state.

## Review

Everything above is done except the commit, push, and CI, and the Linear update, which follow this review.

- **What changed:**
  - The app opens on Connections, with Add connection and Connection details, and the Stage 1 live test one tap away.
  - `connections/` holds the parser, the gateway, the repository, the ViewModel, the screens, and the scanner. `connections/storage/` is the app's only storage.
  - The manifest adds `CAMERA` (optional). `StageOneBoundaryTest` became `StageBoundaryTest`.
  - New libraries: CameraX 1.6.2 and ZXing 3.5.4; test-only, MockWebServer and okhttp-tls 5.4.0.
- **Verified (PASS):**
  - `pnpm check:android`: 142/142 unit tests, 81 new, and lint clean after the API 33 `URLDecoder` fix
  - `pnpm check`: 235 sidecar and 15 test agent tests
  - `pnpm test:hello`: 9/9
  - `pnpm check:generated`
  - nine deliberate breaks, each caught and restored
  - the real-sidecar tests, including two sidecars at once
- **NOT RUN:**
  - the device tests locally, for want of a device or emulator; CI's emulator job runs them
  - the physical Seeker
- **Caveats:**
  - No automated test drives the camera itself: Robolectric has none, and the emulator test doesn't scan. QR decoding is tested on generated frames, and the permission flows through the activity result registry.
  - If pairing succeeds on the sidecar but the phone can't store the credential, the sidecar has already revoked the previous connection. Pairing again recovers.
  - Refresh counts only the first page, up to 100 pending requests. The inbox is SAW-013.
