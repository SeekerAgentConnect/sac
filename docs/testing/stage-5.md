# Stage 5 tests

Stage 5 is local policies and advisory classifications. SAW-025 defined the model and the evaluation semantics, SAW-026 made the phone apply them to a real request and count the day's spending, and SAW-027 gave the owner the Rules screen to write a policy on ([`../guides/policies.md`](../guides/policies.md)). SAW-028 will put the assessment on the request-review screen; until then a verdict is computed and shown nowhere.

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
| The stage boundary | The policy package held to a pinned list of reads — the editor included — with nothing that opens a wallet, a connection, or a socket, no `BLOCKED`, and no branch that acts on a verdict | `StageBoundaryTest` |

These cover SAW-027:

| Area | What the tests cover | Where |
| --- | --- | --- |
| The form | SOL shifted into lamports and a token left in base units, a locale's comma and a sign and an exponent refused, the largest amount a transfer can carry and the first one past it, a threshold of zero, a daily below a per-request, a switch that is off against a list that is empty, a threshold without an asset rule, the round trip from saved policy back to form and out again, and every draft the editor can produce being fit for the store | `PolicyDraftTest` |
| The editor's state | Rules read from disk and surviving a restart, one connection's rules never showing under another, a rotation keeping unsaved edits while leaving and returning doesn't, turning every rule off removing the file, unreadable rules refusing to be edited until the owner starts over, a draft that isn't fit to store never being written, and a save that fails keeping what was typed | `PolicyEditorViewModelTest` |
| The screen | Creating, editing and cancelling, the three states of a switch said in words, amounts refused with a reason and Save off while one is, a token's base units, a mint and a recipient that aren't addresses, an asset or an address listed twice, whole addresses in the list, the remove button naming what it removes, the summary's uncovered checks and its wallet line, and the whole form still operable at twice the system text size | `PolicyEditorScreenTest` |
| The whole path | Rules written on screen landing in this connection's own file and coming back after a restart, a second connection seeing none of them, a removed connection taking its rules with it, and discarding unsaved rules leaving what is stored alone | `PolicyActivityTest` |
| The way in | The **Rules** button reachable however the connection stands, including one the server stopped accepting | `ConnectionDetailsScreenTest` |

### Deliberate breaks

Each of these was made on purpose, run, and reverted, to check that the test that should fail does ([`../../AGENTS.md`](../../AGENTS.md)):

| Break | What failed |
| --- | --- |
| A threshold of zero accepted | `PolicyDraftTest.aThresholdOfZeroIsNotARule`, `PolicyEditorScreenTest.anAmountThatIsntOneIsRefusedInWordsAndCantBeSaved` |
| A switch that is off writing an empty list | Three cases in `PolicyDraftTest`, two in `PolicyEditorViewModelTest` |
| A blank form opened over unreadable rules | Two cases in `PolicyEditorViewModelTest` |
| Rules left behind when the connection is removed | `PolicyActivityTest.aConnectionsRulesGoWhenTheConnectionDoes` |
| The policy package importing a way to speak | `StageBoundaryTest.aPolicyDecidesNothingAndNeverLeavesThePhone` |

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
