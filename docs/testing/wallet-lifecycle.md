# Wallet lifecycle and result delivery

One manual wallet interaction has to produce exactly one accurately reported outcome, whatever
Android does around it (SAW-017). The owner taps **Approve and sign**, the phone leaves for the
wallet app and comes back, and in between the screen can rotate, the app can be pushed out of
memory, the network can drop, and the sidecar can restart. None of that may invent a signature,
ask the wallet twice, lose one this phone holds, or let one connection's reply settle another
connection's request.

From SAW-021 the same holds for a transfer, where the wallet signs **and sends**, with one
difference that runs through everything below: a message that never came back was never signed,
because nothing about a message reaches a network. A transaction that never came back may be on
chain. So the two are reported differently, and neither is guessed at.

This page is what is checked automatically, what only the Seeker can show, and the record of both.

## The rules this keeps

- **The wallet is asked once.** Only `InboxViewModel.approve` and `InboxViewModel.approveTransfer`
  reach the wallet, and only after the sidecar has accepted the owner's approval. Everything that
  sends a result afterwards — a refresh, **Send again**, a retry after a lost response — goes
  through `ConnectionRepository.deliver`, which talks to the sidecar and never to a wallet.
- **One wallet interaction at a time.** Every wallet call takes `WalletRepository`'s lock, so a
  second request can't open a wallet screen while the owner is deciding in the first.
- **What the wallet did is stored before it is sent.** `recordSigning` writes the signature to
  `filesDir/results/` and only then submits it. A dead network, a lost response, or a closed app
  after that point costs nothing: the next send delivers what is already on disk.
- **The first outcome stands.** A second outcome for the same request changes nothing, so a late
  answer can't overwrite what was already reported.
- **A refusal and a failure to send are different things.** Declining in the wallet is a rejection
  and ends the request; a sidecar that couldn't be reached leaves the answer waiting on the phone,
  with what went wrong shown under it.
- **An answer this phone never received is unresolved, never success.** If the app dies while the
  action is with the wallet, or the wallet never answers, the approval is settled as
  `SigningOutcome.Unresolved` and the screen says this phone never learned what the wallet did.
  What the sidecar is told depends on what was with the wallet. For a **message**, FAILED: nothing
  was broadcast, so a signature that never reached this phone exists nowhere. For a **transfer**,
  UNKNOWN: the wallet may have signed and sent it, and a transaction that may be on chain must
  never be reported as one that isn't. Either way the wallet is not asked again, and UNKNOWN is
  not an invitation to retry.
- **An approval the sidecar didn't take is not an approval (SAW-021).** A transfer's approval is
  sent before the wallet is opened, and the wallet is opened only once the sidecar accepts it. An
  approval it refused as stale, or that never reached it, is deleted from the phone: nothing was
  approved anywhere, the request stays PENDING on the sidecar, and the owner reviews a fresh
  preparation. That is what makes "approved, no wallet answer" mean exactly one thing.
- **Nothing unread reaches the wallet.** Only a preparation whose own inspection came back
  `Verified` (SAW-020) can be approved, checked once when the button is offered and again when it
  is tapped.
- **Sending is not succeeding, and a check settles it or nothing (SAW-022).** A transfer the
  wallet sent stops at SUBMITTED until the chain says otherwise. Only the sidecar reads the chain,
  and only when the agent reads the request or the owner taps **Check status**; the phone reaches
  no chain and opens no wallet to check one. A check reports CONFIRMED only when the transaction on
  chain under that signature is byte for byte the one the approval named, and an endpoint that
  didn't answer, a status that isn't there yet, and a signature naming something else all leave the
  request exactly as it was. Nothing anywhere builds a replacement transaction.
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
| **Transfers (SAW-021)** | |
| The approval names the reviewed version and hash, the wallet gets exactly those bytes, and the result is a transaction submission | `InboxViewModelTest.approvingHandsTheWalletExactlyTheBytesThatWereReviewed` |
| A sidecar that rebuilt the transaction after the approval doesn't change what the wallet signs | `InboxViewModelTest.theWalletGetsTheApprovedBytesEvenWhenTheServerHasBuiltANewerVersionSince` |
| Double taps: three taps while the wallet is in front produce one approval and one wallet call | `InboxViewModelTest.aSecondTapNeverOpensTheWalletTwice` |
| A stale preparation is refused, nothing is stored, nothing reaches the wallet, and it is read again | `InboxViewModelTest.aStalePreparationIsRefusedAndReadAgainRatherThanApproved` |
| A version read again while the owner was looking is not the one they approve | `InboxViewModelTest.aVersionReadAgainWhileTheOwnerWasLookingIsNotTheOneTheyApprove` |
| A transaction the phone couldn't account for never reaches the wallet, in the model and in the screen | `InboxViewModelTest.aTransactionThisPhoneCouldNotAccountForNeverReachesTheWallet`, `TransferReviewScreenTest.offersNoApprovalFor…` |
| A switched wallet, and a wallet other than the one on screen, stop the approval | `InboxViewModelTest.aWalletOtherThanTheOneOnScreenStopsTheApproval`, `…connectingAnotherWalletTakesTheTransferOffThisPhoneEntirely` |
| An approval the sidecar never took opens no wallet and is not kept | `InboxViewModelTest.anApprovalTheServerNeverTookOpensNoWalletAndIsNotKept` |
| A lost wallet callback settles as UNKNOWN, and no second wallet call happens, then or on **Send again** | `InboxViewModelTest.aLostWalletCallbackIsUnknownAndTheWalletIsNeverAskedAgain` |
| A wallet that can't say whether it sent leaves the outcome UNKNOWN | `InboxViewModelTest.aWalletThatCannotSayWhetherItSentLeavesTheOutcomeUnknown` |
| Declining in the wallet is a rejection; rejecting in the app never touches the wallet | `InboxViewModelTest.decliningInTheWalletRejectsTheTransfer…`, `…rejectingATransferInTheAppNeverTouchesTheWallet` |
| Activity recreation while the wallet has the transaction | `InboxActivityTest.aRotationWhileTheWalletHasTheTransactionKeepsTheApprovalAndAsksItOnlyOnce` |
| One wallet interaction at a time | `WalletRepositoryTest.runsOneWalletInteractionAtATime` |
| The approved transaction survives a restart of the app's storage | `ResultStoreTest.keepsTheTransactionAnApprovedTransferIsBoundToAcrossARestart` |
| **Confirmation (SAW-022)** | |
| A confirmed signature is checked against the approved bytes before anything is called CONFIRMED | `sidecar/src/requests/confirmation.test.ts`, "confirms only what it found on chain and checked against the approved bytes" |
| A delayed confirmation: `processed` is not a result, and the next look settles it | `…test.ts`, "waits through a delayed confirmation rather than calling processed a result" |
| A transaction that ran and failed on chain, with the chain's own error kept | `…test.ts`, "fails a transaction that ran on chain and failed, keeping the chain's own error" |
| A signature the endpoint hasn't seen: open while it could still land, failed only past the window and after a ledger search | `…test.ts`, "keeps a signature the endpoint hasn't seen open…", "fails a transaction that never landed, but only past its blockhash window" |
| A signature found only in the ledger is the result it is | `…test.ts`, "takes a signature found only in the ledger as the result it is" |
| A signature naming a transaction nobody approved settles nothing | `…test.ts`, "settles nothing when the transaction under that signature isn't the approved one" |
| An endpoint that timed out changes nothing, and the next check still settles it | `…test.ts`, "treats an endpoint that stopped answering as no news, and never as a failure" |
| Status retries send nothing again, add no second spending record, and never return a request to PENDING | `…test.ts`, "reports the same signature, and records no second spending", "never returns an unsettled request to PENDING…" |
| A sidecar restart after sending keeps the signature and the unresolved attempt | `…test.ts`, "keeps the signature and the unresolved attempt across a restart" |
| An UNKNOWN transfer has nothing to look up, and is told so rather than settled | `…test.ts`, "explains an unknown outcome instead of inventing one, and asks the chain nothing" |
| The bytes comparison itself: a signed copy matches, one byte's difference doesn't, and unparsable bytes never do | `sidecar/src/solana/confirmation.test.ts` |
| On the phone: a confirmation is kept, no wallet opens, and no second result is sent | `InboxViewModelTest.checkingAConfirmationKeepsWhatTheServerReadAndOpensNoWallet` |
| A chain failure is kept with its reason, and nothing is re-sent | `InboxViewModelTest.aTransactionThatFailedOnChainIsKeptAsAFailureWithItsReason` |
| A check that settles nothing, a second tap while one runs, and a server that couldn't be reached | `InboxViewModelTest.aTransferTheChainCannotSettleStaysExactlyWhereItWas`, `…aSecondTapWhileAChecksIsRunningAsksOnlyOnce`, `…aServerThatCannotBeReachedChangesNothingAboutTheTransaction` |
| A settled transfer is not checked again; an UNKNOWN one is never re-sent to the wallet | `InboxViewModelTest.aSettledTransferIsNotCheckedAgain`, `…aTransferTheWalletNeverAnsweredIsNeverSentAgainToSettleIt` |
| The signature and what the chain said of it survive a restart of the app's storage | `ResultStoreTest.keepsASentTransactionsIdAndWhatTheChainSaidOfItAcrossARestart` |
| The status text and **Check status**, including who checked | `RequestDetailsScreenTest.saysATransactionIsSentAndNotConfirmedYet`, `…saysWhoCheckedAConfirmedTransferAndOffersNoFurtherCheck`, `…saysWhyATransactionFailedOnTheNetwork` |

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

### Transfers (SAW-021)

Run these on **devnet** with a funded devnet wallet, and never on mainnet. Have
`pnpm agent transfer …` and `pnpm agent get <id>` ready. Record PASS, FAIL, or NOT RUN.

| # | Step | Expected |
| --- | --- | --- |
| 15 | Ask for a small devnet transfer and open it | The screen reads the transaction here: amount in base units, recipient, the wallet that pays, the blockhash, "Not evaluated" for policy, and the server's fee estimate labelled as the server's. |
| 16 | Tap **Read it again**, then **Approve and send** | The wallet opens with the version now on screen. The approval reached the sidecar first: the agent reads PROCESSING while the wallet is in front. |
| 17 | Approve in the wallet | The app shows the transaction's ID, and the agent reads SUBMITTED with the same signature. Exactly one transaction appears on chain. |
| 18 | Ask for another, tap **Approve and send**, and tap again the instant the screen comes back | One wallet prompt, one transaction, one submission. |
| 19 | Ask for another and decline in the wallet | The agent reads REJECTED. Nothing is on chain. |
| 20 | Ask for another, open it, wait for the blockhash window to run down (about a minute past `estimated_expiry`), then approve | Nothing reaches the wallet. The app says the server has a newer transaction and has read it again; the agent still reads PENDING. |
| 21 | Ask for another, tap **Approve and send**, and force-stop the app while the wallet is in front | Reopen: the app says it never learned what the wallet did, and the agent reads UNKNOWN, not FAILED and not SUBMITTED. The wallet is never asked again. |
| 22 | Look the fee payer up on a devnet explorer for the request in step 21 | Either the transaction is there or it isn't. Either way the app and the agent still say UNKNOWN rather than guessing, and no second transaction was sent. Tapping **Check status** on it says there is no signature to look up. |
| 23 | Ask for another, turn on airplane mode, and tap **Approve and send** | Nothing opens the wallet. The app says nothing was approved and it can be approved again. |
| 24 | Ask for another and change the wallet on the **Wallet** screen while the review is open | The request is cancelled by the sidecar, and the screen says so. No approval is possible. |

### Confirmation (SAW-022)

Also **devnet only**, continuing from the transfers above.

| # | Step | Expected |
| --- | --- | --- |
| 25 | Right after step 17, tap **Check status** | Within a few seconds the screen says the transfer went through on the network, and names the host that checked. `pnpm agent get <id>` reads CONFIRMED with `confirmation: "confirmed"` or `"finalized"`, a slot, and `checked_with`. |
| 26 | Compare the slot on a devnet explorer | The slot and the signature are the ones on chain, and the transaction there is the one the review showed. |
| 27 | Ask for a transfer of more than the wallet holds, approve it, and send it | The wallet sends it and it fails on chain. The screen quotes the network's own reason, and the agent reads FAILED with `chain_error`. No replacement is built anywhere. |
| 28 | Stop the sidecar's Solana RPC endpoint (or point `SOLANA_RPC_URL` at a dead port) and tap **Check status** on a sent transfer | The app says the status couldn't be checked and that nothing about the transaction changed. The request is still SUBMITTED, not FAILED. |
| 29 | Restart the sidecar with a SUBMITTED transfer outstanding, then read it with `pnpm agent get <id>` | The signature and the previous check are still there, and reading it now settles it. Nothing ran during the restart. |
| 30 | Poll `pnpm agent get <id>` in a tight loop on a SUBMITTED transfer | It answers every time; the sidecar's log shows it reaching the endpoint at most once every couple of seconds. |

## Verification record: SAW-021

Run on 2026-09-12 on macOS 26.5.2 (Apple silicon), with the versions in
[`toolchain.md`](../development/toolchain.md).

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, `buf format`, ESLint, `buf lint`, `tsc`, 362/362 sidecar tests, and 27/27 test agent tests |
| `pnpm check:generated` | PASS: SAW-021 changed no `.proto` file, and the committed generated code and fixtures match a fresh generation |
| `pnpm test:hello` | PASS: 9/9 Stage 1 acceptance cases |
| `pnpm test:queue` | PASS: 7/7 Stage 2 acceptance cases |
| `pnpm check:android` | PASS: Spotless, 365/365 unit tests (24 more than before), Android lint with no issues, and the debug and instrumentation APKs |
| The wallet is handed the approved bytes | PASS: `FakeWalletAdapter` records every call's exact bytes, and they are the stored approval's even when the sidecar rebuilt the transaction meanwhile |
| No wallet call before the sidecar accepts the approval | PASS: an unreachable sidecar and a stale preparation each leave `sendings` empty, and store nothing |
| A lost wallet callback is UNKNOWN | PASS: `unknown_outcome` reaches the sidecar, the request is UNKNOWN, and `Send again` opens no wallet |
| Deliberate breaks | Each break failed the matching tests, and each file was restored byte for byte afterwards:<ul><li>Dropping `WalletRepository`'s lock failed `runsOneWalletInteractionAtATime`.</li><li>Fetching the transaction again instead of using the approved bytes failed seven transfer tests, including `approvingHandsTheWalletExactlyTheBytesThatWereReviewed`.</li><li>Reporting an unresolved transfer as an execution failure failed both UNKNOWN tests.</li><li>Treating a refused approval as accepted failed `anApprovalTheServerNeverTookOpensNoWalletAndIsNotKept` and `aStalePreparationIsRefusedAndReadAgainRatherThanApproved`.</li><li>Offering **Approve and send** whatever the verdict failed both `offersNoApprovalFor…` screen tests.</li><li>Naming `signAndSendTransactions` outside `MwaWalletAdapter` failed `StageBoundaryTest.nothingSpendsSwapsOrAsksForABiometricOfItsOwn`.</li></ul> |
| The owner's checks on the Seeker, steps 15 to 24 | NOT RUN: no device was attached, and no transaction was ever sent to any cluster |

## Verification record: SAW-022

Run on 2026-09-12 on macOS 26.5.2 (Apple silicon), with the versions in
[`toolchain.md`](../development/toolchain.md).

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, `buf format`, ESLint, `buf lint`, `tsc`, 384/384 sidecar tests (21 more than before), and 27/27 test agent tests |
| `pnpm check:generated` | PASS: `Outcome.confirmation`, `Confirmation`, `ConfirmationLevel`, and `RequestService.CheckStatus` were added to `proto/`, and the committed generated code and fixtures are a fresh generation of them |
| `pnpm test:hello` | PASS: 9/9 Stage 1 acceptance cases |
| `pnpm test:queue` | PASS: 7/7 Stage 2 acceptance cases |
| `pnpm check:android` | PASS: Spotless, 379/379 unit tests (14 more than before), Android lint with no issues, and the debug and instrumentation APKs |
| Nothing is confirmed without checking the approved bytes | PASS: the fake chain serves a different transaction under the reported signature, and the request stays SUBMITTED with `matches_approval` false |
| Silence settles nothing | PASS: a timeout, a missing status inside the window, and a status without a transaction each leave the request where it was |
| No chain method beyond reading | PASS: `stage-boundary.test.ts` names every JSON-RPC method the client calls, and the two new ones are `getSignatureStatuses` and `getTransaction` |
| Deliberate breaks | Each break failed the matching tests, and each file was restored byte for byte afterwards. They are listed with their failures in the SEE-31 record. |
| The owner's checks on the Seeker, steps 25 to 30 | NOT RUN: no device was attached, and no transaction was ever sent to any cluster |

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
