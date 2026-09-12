# Stage 5 tests

Stage 5 is local policies and advisory classifications. SAW-025 defined the model and the evaluation semantics, SAW-026 made the phone apply them to a real request and count the day's spending, SAW-027 gave the owner the Rules screen to write a policy on, SAW-028 put the assessment on the request-review screen, with a deliberate step before an answer it warns about, and SAW-029 ran the scenarios end to end and wrote down what the layer does and does not do ([`../guides/policies.md`](../guides/policies.md)).

Nothing in this stage reaches a network, opens a wallet, or spends anything. Every check below runs on the JVM or under Robolectric, apart from the physical-Seeker checks at the bottom, which the owner ran on their own device on 2026-09-13.

## Automated checks

`pnpm check:android` runs all of these. CI runs the same command.

| Area | What the tests cover | Where |
| --- | --- | --- |
| The model | The default policy, an absent list against an empty one, the same mint on two networks, base units at the 64-bit maximum, and every reason a policy is refused | `PolicyTest` |
| The verdict | The conjunction, one failed or unverified check putting the whole request under restrictions, coverage reported apart from the verdict, no rules being no approval, and the two verdicts there are | `PolicyDecisionTest` |
| The stored document | A full round trip, an empty list kept as one and an absent list kept as absent, isolation between connections, a file naming another connection, a later document version, a rule with no name, damaged files, and a policy this app would refuse to write | `PolicyStoreTest` |
| The facts | What the phone establishes about a request, read from the transactions the sidecar really builds in `fixtures/transactions/cases.json`, and never from an agent's prose | `RequestFactsTest` |
| The assessment | Thresholds to the exact base unit, a daily threshold against today plus this request, the unresolved half named separately, a saturated total still reading as over, and an unread instruction withholding the verdict under every arrangement of rules | `PolicyEvaluationTest` |
| The counters | Confirmed against unresolved, what a rejection isn't, local midnight, a phone carried into another time zone, duplicates by signature and by request, two connections on one wallet, and two wallets on one mint | `DailySpendingTest` |
| The rules as they stand | Read again on every call, surviving a restart, each connection assessed against its own rules and its own spending, and an assessment changing nothing on disk | `PolicyEvaluatorTest` |
| The shared case table | Every case's verdict, its reason codes, and the checks it doesn't cover, plus the rule that every reason a request can produce has a case | `PolicyFixturesTest`, `PolicyFixtures.kt` |
| The stage boundary | The policy package held to a pinned list of reads — the editor included — with nothing that opens a wallet, a connection, or a socket, no `BLOCKED`, no branch that acts on a verdict, and the files that speak to a sidecar having never heard of a policy | `StageBoundaryTest` |

These cover SAW-027:

| Area | What the tests cover | Where |
| --- | --- | --- |
| The form | SOL shifted into lamports and a token left in base units, a locale's comma and a sign and an exponent refused, the largest amount a transfer can carry and the first one past it, a threshold of zero, a daily below a per-request, a switch that is off against a list that is empty, a threshold without an asset rule, the round trip from saved policy back to form and out again, and every draft the editor can produce being fit for the store | `PolicyDraftTest` |
| The editor's state | Rules read from disk and surviving a restart, one connection's rules never showing under another, a rotation keeping unsaved edits while leaving and returning doesn't, turning every rule off removing the file, unreadable rules refusing to be edited until the owner starts over, a draft that isn't fit to store never being written, and a save that fails keeping what was typed | `PolicyEditorViewModelTest` |
| The screen | Creating, editing and cancelling, the three states of a switch said in words, amounts refused with a reason and Save off while one is, a token's base units, a mint and a recipient that aren't addresses, an asset or an address listed twice, whole addresses in the list, the remove button naming what it removes, the summary's uncovered checks and its wallet line, and the whole form still operable at twice the system text size | `PolicyEditorScreenTest` |
| The whole path | Rules written on screen landing in this connection's own file and coming back after a restart, a second connection seeing none of them, a removed connection taking its rules with it, and discarding unsaved rules leaving what is stored alone | `PolicyActivityTest` |
| The way in | The **Rules** button reachable however the connection stands, including one the server stopped accepting | `ConnectionDetailsScreenTest` |

These cover SAW-028:

| Area | What the tests cover | Where |
| --- | --- | --- |
| The assessment on screen | The verdict in words, every check with what it read and what became of it, a threshold and a recipient outside the rules, content that couldn't be checked and why, the checks nothing covered, and the line that never changes under all four verdicts | `PolicyReviewScreenTest` |
| The step before going ahead | Having no rules asking for nothing, unreadable rules asking for it, the button saying what it would do, Reject never waiting, and the answer going through once the owner has said so | `PolicyReviewScreenTest`, `TransferReviewScreenTest` |
| Verification before advice | A transaction that doesn't match its request having no Approve button while the rules say it matches, a tick that brings none back, and the input-validation refusal still said in its own words | `TransferReviewScreenTest`, `InboxViewModelTest` |
| Every program it calls | The programs a transfer's transaction names, on screen, whether or not a rule was written about them | `TransferReviewScreenTest` |
| Reading again | A match, a connection with no rules, rules edited while the request was on screen stopping the answer, a new preparation taking the owner's word with it, and the word being for the reasons and not the request | `InboxViewModelTest` |
| No wallet by looking | Opening, refreshing, re-reading a transaction, assessing, and a rule that matches, all with the wallet untouched | `InboxViewModelTest` |
| The stored snapshot | The codes kept with the record, carried through every later write of it, a record written before there was one, a code this build has no name for, and the whole of it on Activity details | `ActivityLogTest`, `ActivityStoreTest`, `ActivityDetailsScreenTest` |
| Nothing leaves the phone | No reason code, no verdict code, and nothing the rules name in anything submitted to the sidecar | `InboxViewModelTest`, `StageBoundaryTest` |

These cover SAW-029:

| Area | What the tests cover | Where |
| --- | --- | --- |
| The scenarios | Six requests against one connection's rules, each from a transaction the sidecar really built and against rules read off a real store on disk: a transfer inside every rule, one request over the per-request threshold, today's total over the daily one, a recipient the owner never wrote down, an instruction nobody read beside a transfer that matches, and an allowed program carrying an operation that isn't the transfer | `PolicyScenarioTest` |
| Both halves of every verdict | The classification *and* the exact reason codes, in order, *and* the checks the assessment doesn't cover — for every scenario, so a verdict with the wrong reasons fails as a wrong verdict | `PolicyScenarioTest` |
| The agent's own words | A note saying a tenth of what the instruction carries reaching the same decision, byte for byte, as the same transaction with no note; and a familiar ticker beside an unrelated mint being outside the asset rule all the same | `PolicyScenarioTest`, `RequestFactsTest` |
| What no rule decides | The pair: a request the rules warn about that is exactly as approvable as it was, and one that matches every rule the owner wrote — the program list included — with no Approve button at all. The suite asserts both exist, so the demonstration can't be lost | `PolicyScenarioTest` |
| Two connections | Every scenario read again under a second connection that wrote no rules, two connections whose different thresholds read the same transfer differently, and one connection's day never counted against the other's | `PolicyScenarioTest`, `PolicyEvaluatorTest` |
| After a restart | Every scenario reaching the identical decision off a new store over the same directory, which is what the next launch has | `PolicyScenarioTest` |
| The counters' honest limit | A payment this app never recorded being absent from the day's total, and the same amount moved through the app crossing the threshold | `PolicyScenarioTest`, `DailySpendingTest` |
| Staying local | Assessing a scenario three times leaving the stored file byte for byte and timestamp for timestamp as it was, and nothing a verdict produces carrying an address or a number from the rules | `PolicyScenarioTest` |
| What a verdict may claim | Every policy-facing string held against *safe*, *secure*, *automatic*, *blocked*, *denied*, *guaranteed*, *protects* and *prevents*, and both verdicts naming the rules and saying who approves | `PolicyWordingTest` |

These cover what review of the stage PR turned up, in code SAW-026 to SAW-028 had already landed:

| Area | What the tests cover | Where |
| --- | --- | --- |
| A day nobody has read | The history is read off the disk asynchronously and a read can fail, so an empty list is not the same as a day with nothing in it. Unread, the daily check is `daily_total_unverified` rather than a pass; read and empty, it passes on its own terms; and the evaluator the app itself wires up is held to the same thing | `PolicyEvaluatorTest`, `ActivityLogTest`, `PolicyActivityTest` |
| Consent covers the preparation | A transaction prepared again takes the owner's word with it even when what the rules make of it is word for word the same — the version, the hash, the blockhash and a priority fee are the sidecar's to change, and a raised fee is real value no threshold counts | `InboxViewModelTest` |
| Edits typed during a save | The form stays interactive while a slow write runs, and only what actually reached the disk counts as stored, so a later edit is not marked saved and lost on the way out | `PolicyEditorViewModelTest` |

### Deliberate breaks

Each of these was made on purpose, run, and reverted, to check that the test that should fail does ([`../../AGENTS.md`](../../AGENTS.md)):

| Break | What failed |
| --- | --- |
| A threshold of zero accepted | `PolicyDraftTest.aThresholdOfZeroIsNotARule`, `PolicyEditorScreenTest.anAmountThatIsntOneIsRefusedInWordsAndCantBeSaved` |
| A switch that is off writing an empty list | Three cases in `PolicyDraftTest`, two in `PolicyEditorViewModelTest` |
| A blank form opened over unreadable rules | Two cases in `PolicyEditorViewModelTest` |
| Rules left behind when the connection is removed | `PolicyActivityTest.aConnectionsRulesGoWhenTheConnectionDoes` |
| The policy package importing a way to speak | `StageBoundaryTest.aPolicyDecidesNothingAndNeverLeavesThePhone` |
| Having no rules at all counted as a warning | `PolicyDecisionTest.havingNoRulesAtAllIsNotSomethingToWarnAbout`, `PolicyReviewScreenTest.havingNoRulesAtAllAsksForNoWordFromTheOwner`, and 38 cases in `InboxViewModelTest` |
| An answer sent without the owner's word about a warning | Two cases in `InboxViewModelTest` |
| The assessment on screen trusted instead of read again | `InboxViewModelTest.rulesChangedWhileTheRequestWasOnScreenStopTheAnswerRatherThanBeingReadPastIt` |
| A later write of a record dropping the assessment | `ActivityLogTest.keepsTheAssessmentTheOwnerReadThroughEveryLaterWriteOfTheRecord` |
| Approve enabled with a warning unticked | `PolicyReviewScreenTest.theAnswerWaitsForTheOwnersWordAndTheButtonSaysWhatItWouldDo`, `TransferReviewScreenTest.aTransferOutsideTheRulesWaitsForTheOwnersWordBeforeItCanBeApproved` |
| A matching policy putting back the Approve button input validation took away | `TransferReviewScreenTest.noRuleEverPutsBackTheApproveButtonInputValidationTookAway` |
| A file that speaks to a sidecar importing a policy | `StageBoundaryTest.aPolicyDecidesNothingAndNeverLeavesThePhone` |
| A daily threshold compared against this request alone, ignoring today's total | 13 cases: three in `PolicyEvaluationTest`, four in `PolicyEvaluatorTest`, `PolicyFixturesTest.everyCaseReachesTheVerdictItNames`, and five in `PolicyScenarioTest` |
| An unread instruction downgraded to a coverage gap when every configured rule matched | Seven cases: two in `PolicyEvaluationTest`, `PolicyFixturesTest.everyCaseReachesTheVerdictItNames`, `RequestFactsTest.unknownCoverageCannotProduceAllowedHoweverWellTheRestMatches`, and three in `PolicyScenarioTest` |
| A transaction with an instruction nobody read left approvable | Six cases: `PolicyScenarioTest.everyScenarioReachesItsVerdictAndSaysExactlyWhy` and `.theRulesNeverDecideWhetherATransferCanBeApproved`, `RequestFactsTest.aTransactionWithAnInstructionNobodyReadIsNotFullyRead`, two in `TransactionFixturesTest`, and `TransferReviewScreenTest.offersNoApprovalForATransactionItCouldNotReadWhole` |
| Every connection's rules written to one file | 20 cases, across `PolicyStoreTest`, `PolicyEditorViewModelTest`, `PolicyActivityTest`, `PolicyEvaluatorTest`, and three in `PolicyScenarioTest` |
| Rules never written to disk, so nothing survives a restart | 37 cases, across `PolicyStoreTest`, `PolicyEditorViewModelTest`, `PolicyActivityTest`, `PolicyEvaluatorTest`, `InboxViewModelTest`, and six in `PolicyScenarioTest` |
| The words *safe to approve* added to the ALLOWED verdict | `PolicyWordingTest.noVerdictIsCalledSafeAutomaticOrBlocked` |
| The app's evaluator reading the records cache whether or not it had been loaded | `PolicyActivityTest.theAppsOwnEvaluatorTreatsAnUnreadHistoryAsUnknownRatherThanAsNothingSpent` |
| An acknowledgement compared on the decision alone, ignoring the preparation | `InboxViewModelTest.aTransactionPreparedAgainTakesTheOwnersWordWithItHoweverTheRulesRead` |
| A save marking the draft on screen as stored rather than the one it wrote | `PolicyEditorViewModelTest.editsTypedWhileASaveIsInFlightAreNotMarkedAsSaved` |

## Physical-Seeker checks

**PASS**, 2026-09-13, all 23, on the owner's own Seeker. A Robolectric run and a successful APK build are not a device pass; these are the owner's own run on the device, walked from this table.

| # | Check | Result |
| --- | --- | --- |
| 56 | Open a connection's **Rules**, with no rules stored, and read the summary | PASS |
| 57 | Write a rule for each list and one threshold, save, leave, and reopen it | PASS |
| 58 | Turn every switch off, save, and confirm the connection is back to no rules | PASS |
| 59 | Repeat check 57 with the system text size at its largest, confirming nothing is cut off and every control can still be reached and operated | PASS |
| 60 | Type an amount with a comma, a zero, and a daily below a per-request, and read what each says | PASS |
| 61 | Add a recipient by pasting an address, and read the whole address back off the list | PASS |
| 62 | Walk the screen with TalkBack, confirming each switch, checkbox and remove button says what it is and what it does | PASS |
| 63 | Rotate the phone with unsaved edits, and confirm they are still there | PASS |
| 64 | Remove a connection that has rules, pair again, and confirm the new connection starts with none | PASS |
| 65 | Open a request from a connection with no rules and read the assessment: no warning, no tick, and Acknowledge available in one tap | PASS |
| 66 | Write a rule the request matches, open it, and read every check and the line under them | PASS |
| 67 | Write a recipient rule the transfer doesn't match, open it, and confirm Approve waits for the tick and says **Approve and send despite warnings** | PASS |
| 68 | Reject a request the assessment warns about, without ticking anything | PASS |
| 69 | With the request open, edit the rules, come back, and confirm the answer is stopped and the review made again | PASS |
| 70 | Approve one despite a warning, then open **Activity** and read what the record kept | PASS |
| 71 | Repeat check 66 with the system text size at its largest, confirming every check line and the tick can be read and reached | PASS |
| 72 | Walk the assessment with TalkBack, confirming the verdict, each check and the tick say what they are | PASS |
| 73 | Write the rules from the worked examples in [`../guides/policies.md`](../guides/policies.md#worked-examples), and walk examples 1–6 on the device, confirming each screen says what the guide says it says | PASS |
| 74 | Demonstrate **Matches your rules** end to end: approve by hand, sign in the wallet, and read what **Activity** kept about the assessment | PASS |
| 75 | Demonstrate **Outside your rules** end to end: reject it without ticking anything, and read what **Activity** kept | PASS |
| 76 | Pair a second connection, give it different thresholds, and confirm the same kind of request reads differently under each | PASS |
| 77 | Force-stop the app between writing the rules and answering, reopen, and confirm the assessment is made again and reads the same | PASS |
| 78 | Read every policy screen for a word claiming a verdict is safe, automatic, or blocked, and confirm there is none | PASS |

## Verification record: SAW-029

SAW-029, 2026-09-13, on macOS 26.5 (Apple silicon), with Node 24.21.0 and the other versions in [`../development/toolchain.md`](../development/toolchain.md). It adds no feature: one shared fixture, two test suites, and the documentation — plus the three defects review of the stage PR turned up, in code SAW-026 to SAW-028 had already landed. No network and no cluster was reached by any check.

| Check | Result |
| --- | --- |
| `pnpm check` | PASS: Prettier, `buf format`, ESLint, `buf lint`, `tsc`, 396/396 sidecar tests and 29/29 test-agent tests. SAW-029 adds a fixture case rather than a sidecar test, so both counts are unchanged. |
| `pnpm check:android` | PASS: Spotless, lint with no issues, both APKs, and 662/662 unit tests — 20 more than before: ten scenarios, two about what a verdict may claim, and eight for the three defects review turned up. |
| `pnpm check:generated` | PASS: SAW-029 changed no `.proto` file, and the committed generated code and fixtures match a fresh generation. |
| `pnpm test:hello`, `pnpm test:queue`, `pnpm test:transfer` | PASS: 9/9, 7/7, and 7/7 with the opt-in devnet case skipped. **No transaction was sent to any cluster.** |
| The shared fixture | PASS: `node sidecar/src/testing/transaction-fixtures.ts` rewrote `fixtures/transactions/cases.json` with one case added, and `sidecar/src/solana/fixtures.test.ts` accepts the committed file as the one the builder produces now. |
| Deliberate breaks | Nine, each applied, run, confirmed to fail the tests in the table above and nothing else, and reverted — every source file restored byte for byte. |
| Device checks 56–78 | **PASS**, 2026-09-13, all 23 on the owner's own Seeker. They are what this record could not otherwise show: that the assessment reads the way the guide says it does on a real screen at a real text size, and that a real wallet still asks for a real approval after a verdict of either kind. |

## Verification record: the device checks

**2026-09-13, checks 56–78, all PASS**, on the owner's own Seeker, walked from the table above. That closes the two things no automated check in this stage can show: that the assessment reads the way [`../guides/policies.md`](../guides/policies.md#worked-examples) says it does on a real screen, including at the largest system text size and under TalkBack, and that a real wallet still asks for a real approval after a verdict of either kind — **Matches your rules** and manual approval in check 74, **Outside your rules** and rejection in check 75.

Stage 5's device checks are therefore complete. Checks 41–55 (Stage 4) stand as recorded in [`stage-4.md`](stage-4.md).
