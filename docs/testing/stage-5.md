# Stage 5 tests

Stage 5 is local policies and advisory classifications. SAW-025 defined the model and the evaluation semantics, SAW-026 made the phone apply them to a real request and count the day's spending, SAW-027 gave the owner the Rules screen to write a policy on, and SAW-028 put the assessment on the request-review screen, with a deliberate step before an answer it warns about ([`../guides/policies.md`](../guides/policies.md)).

Nothing in this stage reaches a network, opens a wallet, or spends anything. Every check below runs on the JVM or under Robolectric, apart from the physical-Seeker checks at the bottom, which have not been run.

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

## Physical-Seeker checks

**NOT RUN.** Nothing below has been done on the device. A Robolectric run and a successful APK build are not a device pass.

| # | Check | Result |
| --- | --- | --- |
| 56 | Open a connection's **Rules**, with no rules stored, and read the summary | NOT RUN |
| 57 | Write a rule for each list and one threshold, save, leave, and reopen it | NOT RUN |
| 58 | Turn every switch off, save, and confirm the connection is back to no rules | NOT RUN |
| 59 | Repeat check 57 with the system text size at its largest, confirming nothing is cut off and every control can still be reached and operated | NOT RUN |
| 60 | Type an amount with a comma, a zero, and a daily below a per-request, and read what each says | NOT RUN |
| 61 | Add a recipient by pasting an address, and read the whole address back off the list | NOT RUN |
| 62 | Walk the screen with TalkBack, confirming each switch, checkbox and remove button says what it is and what it does | NOT RUN |
| 63 | Rotate the phone with unsaved edits, and confirm they are still there | NOT RUN |
| 64 | Remove a connection that has rules, pair again, and confirm the new connection starts with none | NOT RUN |
| 65 | Open a request from a connection with no rules and read the assessment: no warning, no tick, and Acknowledge available in one tap | NOT RUN |
| 66 | Write a rule the request matches, open it, and read every check and the line under them | NOT RUN |
| 67 | Write a recipient rule the transfer doesn't match, open it, and confirm Approve waits for the tick and says **Approve and send despite warnings** | NOT RUN |
| 68 | Reject a request the assessment warns about, without ticking anything | NOT RUN |
| 69 | With the request open, edit the rules, come back, and confirm the answer is stopped and the review made again | NOT RUN |
| 70 | Approve one despite a warning, then open **Activity** and read what the record kept | NOT RUN |
| 71 | Repeat check 66 with the system text size at its largest, confirming every check line and the tick can be read and reached | NOT RUN |
| 72 | Walk the assessment with TalkBack, confirming the verdict, each check and the tick say what they are | NOT RUN |
