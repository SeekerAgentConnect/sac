# Stage 2 tests

Stage 2 makes requests durable: agents' requests are stored, and the owner reviews them later on the phone, from one or several sidecars. It covers SAW-009 to SAW-013; SAW-014 is the acceptance gate.

## Automated checks

`pnpm check` runs the sidecar's and the test agent's tests. `pnpm check:android` runs the app's JVM and Robolectric tests. CI also runs the device tests on an emulator, through `pnpm test:hello --device`.

| Task | What the tests cover | Where |
| --- | --- | --- |
| SAW-009: the contract | The lifecycle, results, idempotency, and validation rules, plus fixtures that both runtimes decode the same way | `sidecar/src/requests/*.test.ts`; `RequestProtocolFixturesTest` |
| SAW-010: the queue | SQLite storage and migrations, the MCP tools, the phone's `RequestService`, and restarts after SIGKILL | `store.test.ts`, `endpoints.test.ts`, `restart.test.ts`, `database.test.ts` |
| SAW-011: pairing and roles | Pairing tokens, the role matrix, revocation, TLS, and redaction | `sidecar/src/pairing/*.test.ts` |
| SAW-012: connections on the phone | The code parser, the Keystore vault, isolation between connections, the real sidecar, and TLS | `connections/` in `android/app/src/test`; `CredentialVaultDeviceTest` |
| SAW-013: the inbox | See the list below | See the list below |

The SAW-013 tests:

- **`InboxRealSidecarTest`** runs against the real sidecar. It covers:
  - a request an agent made while the app was closed, fetched when the app opens, acknowledged, and read back as COMPLETED
  - a rejection, and an answer that arrives after the agent cancelled
- **`InboxTest`** runs against fake sidecars. It covers:
  - every page fetched, and nothing answered by the fetch
  - one answer per request
  - an answer kept through an unreachable server and a restart
  - a lost response sent again and recognized
  - a refresh that overlaps a tap
  - identical request IDs on two servers
  - a cancelled or revoked request
  - a removed connection's answers
  - pruning of settled answers
- **`InboxViewModelTest`** covers rapid second taps, refreshing every connection, sending again, and requests that aren't pending.
- **`PendingRequestsScreenTest`** and **`RequestDetailsScreenTest`**, on Robolectric, cover:
  - the source, action, age, and expiry of each request
  - the empty, no-server, and offline states
  - buttons disabled while an answer is sent
  - the stored outcome instead of buttons
  - a waiting answer's **Send again**
  - expired and superseded requests
- **`InboxActivityTest`** runs the activity with the app's own storage. A request is fetched when the app opens, answered, and still answered after a rotation. A connection's details open only that connection's requests.
- **`ResultStoreTest`** covers stored answers across a restart, identical request IDs on two connections, and damaged files.
- **`StageBoundaryTest`** checks that there's no background component and no push library (Firebase Messaging, GCM).
- **The test agent's `cli.test.ts`** covers `pnpm agent ack`, `get`, and `cancel`: NOT_PAIRED before pairing, retries with the same key, and reading back an answered request.

## Owner-run check: the queued acknowledgement

The test agent stands in for Hermes, whose configuration allows only the Stage 1 tool until SAW-014.

Before you start:

- The sidecar runs: `pnpm dev:sidecar`.
- A debug build of the app is on the phone, and `adb reverse tcp:8080 tcp:8080` is set up ([`macbook-seeker-quickstart.md`](../guides/macbook-seeker-quickstart.md)).
- The phone is paired: run `pnpm pair`, then **Add connection** in the app ([`pairing.md`](../guides/pairing.md)).

**A request made while the app is closed:**

1. Close the app: swipe it away from the recent apps.
2. On the Mac, run `pnpm --silent agent ack "Deploy finished"`. It prints the request as JSON, with `"status":"PENDING"`. Note its `request_id`.
3. Open the app. The **Pending requests** row reads "Waiting for you: 1".
4. Open **Pending requests**. The request shows the connection's name, "Acknowledge a message", how long ago it was made, and when it expires. Open it, and check that the message is exactly "Deploy finished".
5. Tap **Acknowledge**. The status reads "You acknowledged this. The agent can read your answer."
6. On the Mac, run `pnpm --silent agent get <request_id>`. It prints `"status":"COMPLETED"` and `"terminal":true`.
7. Go back, and open the request again from **Answered**. The status shows your answer, and there are no buttons.

**An answer given while the sidecar is down:**

8. Run `pnpm --silent agent ack "Offline test"`. Open the app, or tap **Refresh**, and open the request.
9. Stop the sidecar with Ctrl+C. Tap **Reject**. The status says your answer is saved on this phone, and the request appears under **Waiting to be sent**.
10. Start the sidecar again, and tap **Refresh** in Pending requests. The request moves to **Answered**. `pnpm --silent agent get <request_id>` prints `"status":"REJECTED"`.

**A request the agent cancels first:**

11. Run `pnpm --silent agent ack "Cancel me"`, refresh, and open the request.
12. On the Mac, run `pnpm --silent agent cancel <request_id>`. It prints `"status":"CANCELLED"`.
13. Tap **Acknowledge**. The status reads "The agent cancelled this request before your answer arrived."

**Nothing in the background:**

14. With the app closed, run `pnpm --silent agent ack "Quiet"`. No notification appears. `adb shell dumpsys activity services io.github.brrenat.seekervault` lists no running service of the app.

## Verification record: SAW-013

Run on 2026-09-11 on macOS 26.5.2 (Apple silicon), with the versions in [`docs/development/toolchain.md`](../development/toolchain.md).

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, `buf format`, ESLint, `buf lint`, and `tsc`. 235/235 sidecar tests, and 19/19 test-agent tests, 4 of them new (`ack`, `get`, and `cancel`). |
| `pnpm check:android` | PASS: Spotless, 181/181 unit tests (39 new), lint with no issues, and the debug and instrumentation APKs |
| `pnpm test:hello`, `pnpm check:generated` | PASS: the 9/9 Stage 1 acceptance cases, unchanged, and the generated code is current |
| Deliberate breaks | NOT RUN (timed out): the run hung during its third break, in `InboxTest`, and was stopped before it reported. The file that break had changed was restored. |
| Owner-run check on the physical Seeker | NOT RUN: no device was attached. The steps are above. |
