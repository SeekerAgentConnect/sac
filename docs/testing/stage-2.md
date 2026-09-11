# Stage 2 tests

Stage 2 makes requests durable: agents' requests are stored, and the owner reviews them later on the phone, from one or several sidecars. It covers SAW-009 to SAW-013. SAW-014 is the acceptance gate: [the acceptance scenario](#the-acceptance-scenario-saw-014) runs in CI, and [the acceptance report](#acceptance-report-saw-014) is at the end of this page.

## Automated checks

`pnpm check` runs the sidecar's and the test agent's tests. `pnpm test:queue` runs the Stage 2 acceptance scenario from the agent's side. `pnpm check:android` runs the app's JVM and Robolectric tests, which include its side of the scenario. CI runs all three, plus the device tests on an emulator, through `pnpm test:hello --device`.

| Task | What the tests cover | Where |
| --- | --- | --- |
| SAW-009: the contract | The lifecycle, results, idempotency, and validation rules, plus fixtures that both runtimes decode the same way | `sidecar/src/requests/*.test.ts`; `RequestProtocolFixturesTest` |
| SAW-010: the queue | SQLite storage and migrations, the MCP tools, the phone's `RequestService`, and restarts after SIGKILL | `store.test.ts`, `endpoints.test.ts`, `restart.test.ts`, `database.test.ts` |
| SAW-011: pairing and roles | Pairing tokens, the role matrix, revocation, TLS, and redaction | `sidecar/src/pairing/*.test.ts` |
| SAW-012: connections on the phone | The code parser, the Keystore vault, isolation between connections, the real sidecar, and TLS | `connections/` in `android/app/src/test`; `CredentialVaultDeviceTest` |
| SAW-013: the inbox | See the list below | See the list below |
| SAW-014: the acceptance gate | Two sidecars, restarts, expiry, rejection, revocation, isolation between connections, the live diagnostic, and the demo tool | [The acceptance scenario](#the-acceptance-scenario-saw-014) |

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

## The acceptance scenario (SAW-014)

Two suites run the same scenario, one from each side. Each starts two sidecars as real processes, each with its own throwaway database, and neither reads your `.env`.

**`pnpm test:queue`** (`test-agent/src/stage2.acceptance.ts`) runs it from the agent's side. The real CLI (`pnpm agent`) is the agent, and the sidecar's Connect test client is a phone paired with both sidecars. The cases:

1. **A request queued while the app is closed** survives a sidecar restart with SIGKILL, and completes once the phone opens. The agent reads COMPLETED after another restart, and a retry returns the same request.
2. **A rejection** on the other sidecar reaches its agent as REJECTED, and a later acknowledgement can't change it.
3. **Expiry while the sidecar is down.** The sidecar restarts with its clock two minutes ahead. A one-minute request comes back EXPIRED, the phone no longer fetches it, and a late answer gets `INVALID_STATE`. A request with a day to go can still be answered.
4. **Revocation.** `pnpm pair revoke` cancels the connection's PENDING requests, and its credential stops working, before and after a restart. The agent gets `NOT_PAIRED`. The phone's connection to the other sidecar is untouched, and pairing again makes a new, empty connection.
5. **Isolation.** None of these can read or answer a request:
   - the other sidecar's phone credential
   - a reference that names another connection, or another connection's request
   - the credential of a connection that pairing replaced
   - the agent's MCP token

   The new connection can't reach the replaced one's request either, and a phone credential doesn't open `/mcp`. None of these attempts changes the request.
6. **The live Stage 1 diagnostic stays live.** `pnpm agent hello` with no live-test screen gets `OFFLINE`, and nothing is stored. A screen that connects later, before or after a restart, receives nothing. An answered live command isn't stored either, and its ID isn't a request.
7. **No secret in any output:** the CLI's, `pnpm pair`'s, and both sidecars' logs.

**`Stage2AcceptanceTest`**, in `pnpm check:android`, runs it from the app's side. The app's own repository and storage are paired with two real sidecars, and a new repository over the same files is the app starting again. The cases:

1. Requests queued while the app is closed survive both sidecars' restarts, one with SIGKILL. The reopened app finds each under its own connection, acknowledges one and rejects the other, and shows both outcomes after another restart.
2. An answer given while its sidecar is down waits through an app restart, and goes out once the sidecar is back.
3. A request that expires while its sidecar is down is marked superseded when the owner answers it from the list already open. The next refresh drops it.
4. `pnpm pair revoke` on one sidecar marks that connection revoked, and its waiting answer undeliverable. The other connection keeps working.

**Deterministic clocks:**

- In-process tests give the sidecar a fake clock (`SidecarOptions.now`), which moves only when the test moves it. `endpoints.test.ts` uses one to expire a request over MCP and Connect at its exact deadline, and to refuse a pairing code from its exact expiry on.
- The suites above restart a sidecar process with `--import sidecar/src/testing/clock.ts`, which runs its clock ahead by a fixed amount. Shipped code never loads it.

## Owner-run check: the queued acknowledgement

The test agent stands in for an agent here. The same flow from Hermes is in [the Hermes guide](../integrations/hermes.md#4-queued-requests-create-now-read-the-result-later).

Before you start:

- The sidecar runs: `pnpm dev:sidecar`. Its `.env` has `MCP_DEMO_TOOLS=true`, as `.env.example` does; without it, `pnpm agent ack` exits 3.
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

## Owner-run check: two sidecars, restarts, expiry, and revocation

This runs the acceptance scenario on the Seeker. It needs a second sidecar on the Mac, B, with its own port and database. Start it in a second terminal, from the repository root:

```bash
export SIDECAR_PORT=8081 DATABASE_PATH=/tmp/seeker-vault-b.db MCP_URL=http://127.0.0.1:8081/mcp
pnpm dev:sidecar
```

Variables set in the shell take precedence over `.env`, so B keeps the tokens from `.env`, with its own port and database. Open a third terminal, export the same three variables, and run `adb reverse tcp:8081 tcp:8081`, then `pnpm pair`, and add the connection in the app. Commands in that terminal go to B, and commands in a terminal without the variables go to the first sidecar, A.

1. Close the app. Queue one request on each sidecar: `pnpm --silent agent ack "For A"` in A's terminal, and `pnpm --silent agent ack "For B"` in B's.
2. Restart A: Ctrl+C, then `pnpm dev:sidecar`.
3. Open the app. **Pending requests** shows both requests, each labeled with its connection.
4. Acknowledge A's request, and reject B's. `pnpm --silent agent get <request_id>` prints `"status":"COMPLETED"` in A's terminal and `"status":"REJECTED"` in B's.
5. In A's terminal, run `pnpm --silent agent ack "Expires soon" --expires 60`. Close the app, wait two minutes, and open it again. The request isn't under **Waiting for you**, and `pnpm --silent agent get <request_id>` prints `"status":"EXPIRED"`.
6. In B's terminal, run `pnpm --silent agent ack "Revoked first"`, then `pnpm pair revoke`. Tap **Refresh** in the app. Connection B says "The server no longer accepts this phone. Pair again to reconnect.", and its request is gone. `pnpm --silent agent get <request_id>` prints `"status":"CANCELLED"`. Connection A still refreshes.
7. With Hermes, run the [queued request example](../integrations/hermes.md#4-queued-requests-create-now-read-the-result-later), and answer on the Seeker.

When you're done, stop B, run `adb reverse --remove tcp:8081`, delete `/tmp/seeker-vault-b.db*`, and remove connection B in the app.

**Record:** note the commit, the Seeker's Android version, and PASS, FAIL, or NOT RUN for each step.

## Verification record: SAW-013

Run on 2026-09-11 on macOS 26.5.2 (Apple silicon), with the versions in [`docs/development/toolchain.md`](../development/toolchain.md).

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, `buf format`, ESLint, `buf lint`, and `tsc`. 235/235 sidecar tests, and 19/19 test-agent tests, 4 of them new (`ack`, `get`, and `cancel`). |
| `pnpm check:android` | PASS: Spotless, 181/181 unit tests (39 new), lint with no issues, and the debug and instrumentation APKs |
| `pnpm test:hello`, `pnpm check:generated` | PASS: the 9/9 Stage 1 acceptance cases, unchanged, and the generated code is current |
| Deliberate breaks | NOT RUN (timed out): the run hung during its third break, in `InboxTest`, and was stopped before it reported. The file that break had changed was restored. |
| Owner-run check on the physical Seeker | NOT RUN: no device was attached. The steps are above. |

## Acceptance report: SAW-014

**Every automated Stage 2 check passes, from both sides.** The durable workflow survives sidecar and app restarts, expiry, rejection, and revocation. Two connections stay isolated, and the live diagnostic still stores and replays nothing. Hermes's own MCP client created a request and read its result later. The owner-run checks on the physical Seeker are NOT RUN. They close the stage, as the owner's Seeker run closed Stage 1.

### What was tested

| Item | Value |
| --- | --- |
| Commit | The SEE-21 commit on `develop`, on top of `e8cba8b` (SAW-013). This report is part of it. |
| Mac | macOS 26.5.2 (Apple silicon), Node.js 24.21.0, pnpm 12.3.4, Gradle 9.7.1 on Temurin 21, AGP 9.4.0 |
| CI | GitHub Actions runs the same checks on the pushed commit, `pnpm test:queue` included. The result is on the pull request. |
| Hermes | v0.21.1 (2026.9.7), from its release tag `v2026.9.7`, in a scratch Python 3.13 environment with a scratch `HERMES_HOME`. Its MCP client is `mcp` 2.0.0, and the sidecar's is the MCP TypeScript SDK 1.30.0. |
| Seeker | Not used |
| Credentials | Fixed test tokens, or throwaway random ones. None was printed or recorded. |

### Results by case

| Case | Agent side (`pnpm test:queue`) | App side (`Stage2AcceptanceTest`) | Physical Seeker |
| --- | --- | --- | --- |
| A request queued while the app is closed survives a sidecar restart, and completes once the app opens | PASS, with SIGKILL | PASS, with SIGKILL and SIGTERM | NOT RUN |
| An answer given while the sidecar is down goes out after the app and the sidecar restart | Not applicable: the test phone keeps no answers | PASS | NOT RUN |
| Rejection | PASS | PASS | NOT RUN |
| Expiry while the sidecar is down | PASS: EXPIRED, no longer fetched, and a late answer refused | PASS: the owner's late answer marked superseded | NOT RUN |
| Revoked pairing | PASS: requests cancelled, the credential refused after a restart, the other sidecar untouched, and pairing again | PASS: the connection marked revoked, its waiting answer undeliverable, and the other connection untouched | NOT RUN |
| No result read or submitted with another connection's identity | PASS | Not in this test. `ConnectionRepositoryTest` and `InboxTest` check that each credential and answer goes only to its own sidecar. | NOT RUN |
| The live diagnostic stores and replays nothing | PASS | Not applicable | NOT RUN |
| Hermes creates a request and reads the result later | PASS with Hermes's own client and a test phone; see below | Not applicable | NOT RUN |

### The Hermes run

The tool calls went through Hermes's own dispatch path (`discover_mcp_tools`, then `handle_function_call`), which is the path a model's call takes, without an LLM. The sidecar was a real process with a throwaway database, and the sidecar's Connect test client was the paired phone. The config was `examples/hermes.config.yaml`, with only the port changed.

| Check | Result |
| --- | --- |
| `hermes mcp test seeker_vault` with `MCP_DEMO_TOOLS=true` | PASS: `✓ Connected` and `Tools discovered: 4` |
| `vault_request_ack` for `seeker-check-002` | PASS: PENDING at once, with its `request_id`. A retry returned the same request, and the sidecar logged `returned again for its idempotency key`. |
| `vault_get_request` before and after the phone acknowledged | PASS: PENDING, then COMPLETED with `terminal: true`, read from a new Python process, as a later session would |
| A rejection, and `vault_cancel_request` | PASS: REJECTED with `"The owner rejected the request."`, and CANCELLED with `"The agent cancelled the request."` |
| An unknown request ID | PASS: `{"error": "NOT_FOUND: no such request"}` |
| The sidecar restarted without `MCP_DEMO_TOOLS` | PASS. `hermes mcp test` discovered 3 tools, while `hermes mcp list` still said `4 selected`, because it counts the `include` list. A call to `vault_request_ack` got `Unknown tool` from Hermes, and `vault_get_request` still returned COMPLETED. |
| Tokens | PASS: no token or phone credential in the sidecar's logs or in Hermes's output |

A regular `pip install` of Hermes v0.21.1 refuses to build a wheel, so it was installed in editable mode (`pip install -e`).

### Other checks

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, `buf format`, ESLint, `buf lint`, and `tsc`. 239/239 sidecar tests, 4 of them new: the demo tool, two on a fake clock, and `MCP_DEMO_TOOLS` in the configuration. 20/20 test agent tests, 1 of them new: `ack` without the demo tool. |
| `pnpm test:queue` | PASS: 7/7, in about 7 seconds |
| `pnpm check:android` | PASS: Spotless, 185/185 unit tests (4 of them new, in `Stage2AcceptanceTest`), lint with no issues, and the debug and instrumentation APKs |
| `pnpm test:hello` | PASS: the 9/9 Stage 1 acceptance cases, unchanged |
| `pnpm check:generated`, `pnpm build` | PASS: the generated code is current, and `sidecar/dist` builds |
| The demo tool | PASS. Without `MCP_DEMO_TOOLS=true`, the sidecar doesn't list `vault_request_ack`, and a call to it fails as an unknown tool (`endpoints.test.ts`). `pnpm agent ack` then exits 3 and names the variable (`cli.test.ts`). |
| Clocks | PASS. On a fake clock, `endpoints.test.ts` expired a request over MCP and Connect exactly at its deadline, and refused a pairing code exactly at its expiry. |
| Deliberate breaks | Each break was caught, and each file was restored byte for byte afterwards. Each break ran alone, under a time limit:<ul><li>`vault_request_ack` served without `MCP_DEMO_TOOLS`: 4 tests failed, in `endpoints.test.ts`, `server.test.ts`, and `cli.test.ts`</li><li>`pnpm agent ack` without its tool check: the exit code 3 test failed</li><li>the test clock not moved ahead: the `pnpm test:queue` expiry case failed</li><li>revocation that leaves PENDING requests: the revocation and isolation cases failed</li><li>a request looked up without its connection: the isolation case failed</li><li>the app never resending a waiting answer: `Stage2AcceptanceTest`'s answer-while-down case failed</li></ul> |
