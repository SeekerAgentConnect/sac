# Wallet lifecycle and result delivery

One manual wallet interaction has to produce exactly one accurately reported outcome, whatever
Android does around it (SAW-017). The owner taps **Approve and sign**, the phone leaves for the
wallet app and comes back, and in between the screen can rotate, the app can be pushed out of
memory, the network can drop, and the sidecar can restart. None of that may invent a signature,
ask the wallet twice, lose one this phone holds, or let one connection's reply settle another
connection's request.

This page is what is checked automatically, what only the Seeker can show, and the record of both.

## The rules this keeps

- **The wallet is asked once.** Only `InboxViewModel.approve` reaches the wallet, and only after the
  sidecar has accepted the owner's approval. Everything that sends a result afterwards —
  a refresh, **Send again**, a retry after a lost response — goes through
  `ConnectionRepository.deliver`, which talks to the sidecar and never to a wallet.
- **What the wallet did is stored before it is sent.** `recordSigning` writes the signature to
  `filesDir/results/` and only then submits it. A dead network, a lost response, or a closed app
  after that point costs nothing: the next send delivers what is already on disk.
- **The first outcome stands.** A second outcome for the same request changes nothing, so a late
  answer can't overwrite what was already reported.
- **A refusal and a failure to send are different things.** Declining in the wallet is a rejection
  and ends the request; a sidecar that couldn't be reached leaves the answer waiting on the phone,
  with what went wrong shown under it.
- **An answer this phone never received is unresolved, never success.** If the app dies while the
  message is with the wallet, or the wallet never answers, the approval is settled as
  `SigningOutcome.Unresolved`: the screen says this phone never learned what the wallet did, and
  the sidecar is told the request failed. Nothing was broadcast, so a signature that never reached
  this phone exists nowhere. The wallet is not asked again.
- **A reply belongs to its connection.** An answer is keyed by connection ID and request ID, it is
  sent to that connection's own URL with that connection's own credential, and the sidecar refuses
  a reference that names a request another connection owns.
- **A repeat settles nothing twice.** The sidecar recognizes a result identical to one it already
  accepted and returns the same terminal request, before and after a restart.

## Automated checks

`pnpm check` runs the sidecar's tests and `pnpm check:android` the app's; CI runs both.

| What | Where |
| --- | --- |
| Rapid taps: Approve twice and Reject while the wallet is in front produce one approval, one wallet call, and one signature | `InboxViewModelTest.approvesOnceHoweverFastTheOwnerTaps` |
| Coming back from the wallet leaves a signing that is still in flight alone, and settles one whose answer never arrived | `InboxViewModelTest.settlesAnApprovalWhoseAnswerNeverArrivedWhenTheAppComesBack`, `InboxTest.leavesASigningThatIsStillWithTheWalletAlone` |
| A wallet that never answers at all times out, and the request is reported as unresolved | `InboxViewModelTest.givesUpOnAWalletThatNeverAnswersAtAll` |
| The app closing between the approval and the wallet's answer | `InboxTest.recordsAnApprovalTheWalletNeverAnsweredAsUnresolvedWhenTheAppOpensAgain` |
| Network loss after the signature: it is stored, sent again, and the wallet is asked exactly once | `InboxViewModelTest.sendsTheSignatureAgainAfterALostResponseWithoutAskingTheWalletTwice` |
| A signature stored while an earlier send was still running still reaches the sidecar | `InboxTest.sendsASignatureStoredWhileAnEarlierSendWasStillRunning` |
| A refresh crossing a send doesn't start a second one, and an explicit send waits for the one in flight | `InboxTest.sendsOneAnswerAtATimeWhenARefreshOverlapsTheTap` |
| A reply for one connection never settles another's, with the same request ID on both | `InboxViewModelTest.neverSettlesAnotherConnectionsRequestWithThisOnesReply`, `InboxTest.keepsIdenticalRequestIdsOnTwoServersApart` |
| Rotation: the activity is recreated while the wallet holds the message, and the request, the approval, and the signing survive it | `InboxActivityTest` |
| Wallet cancellation stays a rejection; a wallet that couldn't sign stays a failure | `InboxViewModelTest.recordsAWalletThatDeclined`, `…CouldNotSign` |
| Every outcome across a restart of the app's storage, including the unresolved one | `ResultStoreTest` |
| Repeated `SubmitResult`, for an approval and for a signature, before and after a sidecar restart | `sidecar/src/storage/request-store.test.ts` |
| A result naming another connection's request is refused | `sidecar/src/storage/request-store.test.ts`, "keeps the phone to its own connection's requests" |

### What the automated checks deliberately can't show

Every wallet answer above comes from `FakeWalletAdapter`. No test starts a real wallet app, and no
automated test has ever seen the Android system kill this app while Seed Vault Wallet was in front.
The transition itself — the wallet's own activity, the association over Mobile Wallet Adapter, and
what the system does to a backgrounded app under memory pressure — is the owner's check below.

## The owner's checks on the Seeker

Run these on the physical Seeker with Seed Vault Wallet set up and a wallet connected, and record
PASS, FAIL, or NOT RUN. An emulator or a successful APK build never counts.

Have `pnpm agent sign "…"` and `pnpm agent get <id>` ready on the computer that runs the sidecar.

| # | Step | Expected |
| --- | --- | --- |
| 1 | Ask for a signature, open the request, and rotate the phone before approving | The request stays on screen, with the same message, byte count, and wallet. Nothing has been sent. |
| 2 | Tap **Approve and sign**, and rotate the phone while Seed Vault Wallet is in front | The wallet stays in front and still asks you to sign. Nothing is dismissed. |
| 3 | Approve in the wallet | The app comes back, says your wallet signed it, and `pnpm agent get <id>` reads COMPLETED with `"signature_verified":true`. There is exactly one request, not two. |
| 4 | Ask for another, tap **Approve and sign**, and tap it again the instant the screen comes back | Nothing happens twice: one signature, one COMPLETED request, and no second wallet prompt. |
| 5 | Ask for another and decline in the wallet | The app says you declined and nothing was signed; the agent reads REJECTED, not FAILED. |
| 6 | Ask for another, turn on airplane mode, then approve and sign | The app says your wallet signed it and the signature is saved on this phone. The agent still reads PROCESSING. |
| 7 | Turn airplane mode off and tap **Refresh**, or **Send again** | The signature goes through, and the agent reads COMPLETED. The wallet is not opened again. |
| 8 | Ask for another, approve and sign, and restart the sidecar (`Ctrl+C`, `pnpm dev:sidecar`) before the app sends the result | The result is delivered on the next refresh, and the agent reads COMPLETED once. |
| 9 | With the result already delivered, tap **Send again** if it is still offered, or refresh twice | The request stays COMPLETED with the same signature. Nothing is refused, and nothing changes. |
| 10 | Ask for another, tap **Approve and sign**, and, while the wallet is in front, force-stop the app from Android's app info screen | Reopen the app: the request says this phone never learned what the wallet did, and the agent reads FAILED. It does not say signed. |
| 11 | Repeat step 10, but use the app switcher to swipe the app away instead | The same: unresolved, reported as failed, and the wallet is never asked again. |
| 12 | Pair a second sidecar, have both ask for a signature, and approve one | Only that one is answered. The other stays PENDING on its own sidecar, and nothing was sent to it. |
| 13 | Ask for a signature, tap **Approve and sign**, and, while the wallet is in front, change the wallet on the **Wallet** screen afterwards | Whatever the wallet answered is reported for the wallet that was reviewed, or the request is reported as not signed. No signature is ever attributed to the new wallet. |
| 14 | Check the sidecar's log and database | Each request has one terminal state and one outcome. No token, credential, or wallet authorization appears anywhere. |

## Verification record: SAW-017

Run on 2026-09-12 on macOS 26.5.2 (Apple silicon), with the versions in
[`toolchain.md`](../development/toolchain.md).

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, `buf format`, ESLint, `buf lint`, `tsc`, 282/282 sidecar tests, and 23/23 test agent tests |
| `pnpm check:generated` | PASS: SAW-017 changed no `.proto` file, and the committed generated code and fixtures match a fresh generation |
| `pnpm test:hello` | PASS: 9/9 Stage 1 acceptance cases |
| `pnpm test:queue` | PASS: 7/7 Stage 2 acceptance cases |
| `pnpm check:android` | PASS: Spotless, 293/293 unit tests (10 more than before), Android lint with no issues, and the debug and instrumentation APKs |
| A delivery retry never starts a second signature | PASS: `FakeWalletAdapter` counts every call, and the count is 1 after a lost response and a resend, and after a rotation |
| A reply for one connection can't complete another's request | PASS, on both sides: the phone sends only to the connection's own URL with its own credential, and the sidecar refuses a reference naming another connection's request |
| Repeated `SubmitResult` returns the same terminal result | PASS, for an approval and a signature, and after reopening the database |
| Deliberate breaks | Each break failed the matching tests, and each file was restored byte for byte afterwards:<ul><li>Settling no abandoned signing failed `settlesAnApprovalTheWalletNeverAnsweredWhenTheAppComesBackToTheForeground` and `settlesAnApprovalWhoseAnswerNeverArrivedWhenTheAppComesBack`.</li><li>Dropping the wallet timeout failed `givesUpOnAWalletThatNeverAnswersAtAll`.</li><li>Ignoring the sidecar's stored results failed the four repeat tests, including the restart one.</li></ul> |
| The owner's checks on the Seeker, steps 1 to 14 | NOT RUN: no device was attached |
