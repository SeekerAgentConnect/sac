package io.github.brrenat.seekervault.policy

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.activity.ActivityRecord
import io.github.brrenat.seekervault.policy.storage.PolicyStore
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Asset
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.action
import io.github.brrenat.seekervault.request.v1.actionRequest
import io.github.brrenat.seekervault.request.v1.asset
import io.github.brrenat.seekervault.request.v1.preparedTransaction
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.request.v1.transferAction
import io.github.brrenat.seekervault.transactions.ASSOCIATED_TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.SYSTEM_PROGRAM
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.TransferInspection
import io.github.brrenat.seekervault.transactions.inspectTransfer
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.io.File
import java.time.ZoneId
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The scenarios the policy builder is demonstrated by (SAW-029, docs/testing/stage-5.md).
 *
 * A scenario is the whole path, not another unit test. It starts at bytes the sidecar really built
 * (`fixtures/transactions/cases.json`), inspects them the way the review screen does, reads the
 * connection's rules off a real store on this phone's disk, counts the day from the owner's own
 * Activity records, and ends at the verdict. Every one of them asserts both halves — the
 * classification *and* the exact reason codes, in order — because a verdict with the wrong reasons
 * is a wrong verdict, and the codes are what a stored assessment carries.
 *
 * Each scenario also records what does *not* happen: whether the transfer was approvable at all,
 * which no rule decides, and whether the review asks the owner to go past a warning on purpose. A
 * scenario that quietly stops being a warning fails here rather than changing what the owner sees.
 */
private const val REQUEST_ID = "f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19"

@RunWith(AndroidJUnit4::class)
class PolicyScenarioTest {
    @get:Rule val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "files/policies").apply { mkdirs() } }

    private val cases =
        JSONObject(
                checkNotNull(javaClass.getResourceAsStream("/transactions/cases.json")) {
                        "fixtures/transactions/cases.json is missing; run `node mcp-server/src/testing/transaction-fixtures.ts`"
                    }
                    .use { it.readBytes().decodeToString() }
            )
            .getJSONArray("cases")

    private fun case(name: String): JSONObject =
        (0 until cases.length())
            .map { cases.getJSONObject(it) }
            .first { it.getString("name") == name }

    /** The addresses the fixtures are built for, taken from the fixtures themselves. */
    private val payer by lazy { case("sol_transfer").getJSONObject("request").getString("wallet") }
    private val payee by lazy {
        case("sol_transfer").getJSONObject("request").getString("recipient")
    }
    private val stranger by lazy {
        case("changed_recipient").getJSONObject("facts").getString("recipient")
    }
    private val someMint by lazy {
        case("token_transfer_creates_account").getJSONObject("request").getString("tokenMint")
    }

    /** One scenario: rules, records, a real transaction, and everything it must produce. */
    private data class Scenario(
        val name: String,
        /** The case in `fixtures/transactions/cases.json` the request is prepared from. */
        val fixture: String,
        val rules: ConnectionPolicy,
        /** The owner's own records for the day, which is all a counter ever sees. */
        val records: List<ActivityRecord> = emptyList(),
        val assessment: PolicyAssessment,
        /** Every reason code, in order. Empty for ALLOWED. */
        val reasons: List<String>,
        /** The checks the assessment doesn't cover, in order. */
        val notChecked: List<PolicyCheck> = emptyList(),
        /** Whether input validation leaves the transfer approvable. No rule decides this. */
        val approvable: Boolean,
        /** Whether the review asks the owner to go past a warning on purpose. */
        val warns: Boolean,
    )

    private fun rules(
        actions: Allowlist<PolicyAction>? = Allowlist.of(PolicyAction.Transfer),
        assets: Allowlist<PolicyAsset>? = Allowlist.of(PolicyAsset.sol(Network.NETWORK_DEVNET)),
        recipients: Allowlist<String>? = Allowlist.of(payee),
        programs: Allowlist<String>? = Allowlist.of(SYSTEM_PROGRAM),
        limits: Map<PolicyAsset, AssetLimits> =
            mapOf(
                PolicyAsset.sol(Network.NETWORK_DEVNET) to
                    AssetLimits(perOperation = 5UL * ONE_SOL, daily = 10UL * ONE_SOL)
            ),
    ): ConnectionPolicy =
        ConnectionPolicy.default(CONNECTION, NOW)
            .copy(
                actions = actions,
                assets = assets,
                recipients = recipients,
                programs = programs,
                limits = limits,
            )

    private fun solLimits(perOperation: ULong? = null, daily: ULong? = null) =
        mapOf(
            PolicyAsset.sol(Network.NETWORK_DEVNET) to
                AssetLimits(perOperation = perOperation, daily = daily)
        )

    /** A transfer this app really made today, which is the only thing a counter can see. */
    private fun spentEarlier(amount: ULong): List<ActivityRecord> =
        listOf(
            record(
                "earlier",
                wallet = payer,
                network = Network.NETWORK_DEVNET,
                amount = amount.toString(),
            )
        )

    private fun scenarios(): List<Scenario> =
        listOf(
            Scenario(
                name = "a transfer inside every rule the owner wrote",
                fixture = "sol_transfer",
                rules = rules(),
                assessment = PolicyAssessment.Allowed,
                reasons = emptyList(),
                approvable = true,
                warns = false,
            ),
            Scenario(
                name = "one request over the per-request threshold",
                fixture = "sol_transfer",
                rules = rules(limits = solLimits(perOperation = ONE_SOL, daily = 10UL * ONE_SOL)),
                assessment = PolicyAssessment.UnderRestrictions,
                reasons = listOf("over_per_operation_limit"),
                approvable = true,
                warns = true,
            ),
            Scenario(
                name = "today's total, plus this request, over the daily threshold",
                fixture = "sol_transfer",
                rules =
                    rules(limits = solLimits(perOperation = 3UL * ONE_SOL, daily = 3UL * ONE_SOL)),
                records = spentEarlier(ONE_SOL),
                assessment = PolicyAssessment.UnderRestrictions,
                reasons = listOf("over_daily_limit"),
                approvable = true,
                warns = true,
            ),
            Scenario(
                name = "a recipient the owner never wrote down",
                fixture = "sol_transfer",
                rules = rules(recipients = Allowlist.of(stranger)),
                assessment = PolicyAssessment.UnderRestrictions,
                reasons = listOf("recipient_not_allowed"),
                approvable = true,
                warns = true,
            ),
            Scenario(
                name = "an instruction nobody read, beside a transfer that matches",
                fixture = "unknown_program_alongside_the_transfer",
                // No program rule: every rule the owner did write is matched, and the verdict is
                // still withheld, because no rule was written about what is in the gap.
                rules = rules(programs = null),
                assessment = PolicyAssessment.UnderRestrictions,
                reasons = listOf("request_unverified"),
                notChecked = listOf(PolicyCheck.Program),
                approvable = false,
                warns = true,
            ),
            Scenario(
                name = "a note that says a tenth of what the instruction carries",
                fixture = "note_disagrees_with_the_amount",
                rules = rules(limits = solLimits(perOperation = ONE_SOL, daily = 10UL * ONE_SOL)),
                assessment = PolicyAssessment.UnderRestrictions,
                reasons = listOf("over_per_operation_limit"),
                approvable = true,
                warns = true,
            ),
            Scenario(
                name = "a familiar ticker in the note, and an unrelated mint in the bytes",
                fixture = "fake_ticker_in_the_note",
                // The owner listed the mint the ticker belongs to. The transfer is of another.
                rules =
                    rules(
                        assets = Allowlist.of(PolicyAsset.token(Network.NETWORK_DEVNET, MINT)),
                        programs = null,
                        limits = emptyMap(),
                    ),
                assessment = PolicyAssessment.UnderRestrictions,
                reasons = listOf("asset_not_allowed"),
                notChecked =
                    listOf(
                        PolicyCheck.Program,
                        PolicyCheck.PerOperationLimit,
                        PolicyCheck.DailyLimit,
                    ),
                approvable = true,
                warns = true,
            ),
            Scenario(
                name = "an allowed program carrying an operation that isn't the transfer",
                fixture = "token_delegate_alongside_the_transfer",
                // Every program the transaction calls is on the owner's list, and every other rule
                // matches too. A program's name is not permission for every instruction it offers.
                rules =
                    rules(
                        assets = Allowlist.of(PolicyAsset.token(Network.NETWORK_DEVNET, someMint)),
                        programs = Allowlist.of(ASSOCIATED_TOKEN_PROGRAM, TOKEN_PROGRAM),
                        limits = emptyMap(),
                    ),
                assessment = PolicyAssessment.UnderRestrictions,
                reasons = listOf("request_unverified"),
                notChecked = listOf(PolicyCheck.PerOperationLimit, PolicyCheck.DailyLimit),
                approvable = false,
                warns = true,
            ),
        )

    private fun requestOf(connectionId: String, fields: JSONObject): ActionRequest = actionRequest {
        ref = requestRef {
            this.connectionId = connectionId
            requestId = REQUEST_ID
        }
        // Whatever the agent wrote about it. Nothing below reads this.
        agentNote = fields.optString("note")
        action = action {
            transfer = transferAction {
                wallet = fields.getString("wallet")
                network = Network.NETWORK_DEVNET
                recipient = fields.getString("recipient")
                amount = fields.getString("amount")
                asset =
                    if (fields.has("tokenMint")) {
                        asset { tokenMint = fields.getString("tokenMint") }
                    } else {
                        asset { nativeSol = Asset.NativeSol.getDefaultInstance() }
                    }
            }
        }
    }

    private fun inspect(case: JSONObject, request: ActionRequest): TransferInspection {
        val prepared = preparedTransaction {
            version = case.getInt("version")
            transaction =
                ByteString.copyFrom(Base64.getDecoder().decode(case.getString("transaction")))
            contentHash =
                ByteString.copyFrom(Base64.getDecoder().decode(case.getString("contentHash")))
        }
        val wallet =
            SelectedWallet(
                address = case.getJSONObject("request").getString("wallet"),
                network = WalletNetwork.Devnet,
                selectedAt = NOW,
            )
        return inspectTransfer(request, prepared, wallet)
    }

    /** The review as the owner would reach it: bytes, then facts, then the rules on disk. */
    private fun review(
        scenario: Scenario,
        connectionId: String = CONNECTION,
        store: PolicyStore = PolicyStore(dir),
    ): Pair<TransferInspection, PolicyDecision> {
        val case = case(scenario.fixture)
        val request = requestOf(connectionId, case.getJSONObject("request"))
        val inspection = inspect(case, request)
        val facts = policyFacts(connectionId, request, Network.NETWORK_DEVNET, inspection)
        val evaluator = PolicyEvaluator(store, { scenario.records }, { NOW }, { ZoneId.of("UTC") })
        return inspection to evaluator.evaluate(facts)
    }

    private fun save(policy: ConnectionPolicy) = PolicyStore(dir).put(policy)

    @Test
    fun everyScenarioReachesItsVerdictAndSaysExactlyWhy() {
        for (scenario in scenarios()) {
            save(scenario.rules)
            val (inspection, decision) = review(scenario)

            assertEquals(scenario.name, scenario.assessment, decision.assessment)
            assertEquals("${scenario.name}: reasons", scenario.reasons, decision.reasonCodes)
            assertEquals("${scenario.name}: coverage", scenario.notChecked, decision.notChecked)
            assertEquals(
                "${scenario.name}: approvable",
                scenario.approvable,
                inspection.approvable,
            )
            assertEquals("${scenario.name}: warns", scenario.warns, decision.warns)
        }
    }

    @Test
    fun everyScenarioReadsTheSameAfterARestart() {
        for (scenario in scenarios()) {
            save(scenario.rules)
            val before = review(scenario).second

            // A new store over the same directory is what the next launch has. Nothing is cached
            // between the two: the rules are read off the disk again, and the day recounted.
            val after = review(scenario, store = PolicyStore(dir)).second

            assertEquals(scenario.name, before, after)
            // And it is still the scenario's own verdict, not two readings of an empty disk.
            assertEquals("${scenario.name}: after a restart", scenario.reasons, after.reasonCodes)
        }
    }

    @Test
    fun noScenarioIsAssessedAgainstAnotherConnectionsRules() {
        for (scenario in scenarios()) {
            save(scenario.rules)
            // The second connection is paired and has written no rules of its own.
            val other = review(scenario, connectionId = OTHER_CONNECTION).second

            assertEquals(scenario.name, PolicyAssessment.UnderRestrictions, other.assessment)
            assertEquals(
                "${scenario.name}: other",
                listOf("no_policy_configured"),
                other.reasonCodes,
            )
            // And having read the other connection's request changed nothing about this one's.
            assertEquals(scenario.name, scenario.reasons, review(scenario).second.reasonCodes)
        }
    }

    @Test
    fun twoConnectionsWithDifferentRulesReadTheSameTransferDifferently() {
        val strict =
            ConnectionPolicy.default(CONNECTION, NOW)
                .copy(
                    assets = Allowlist.of(PolicyAsset.sol(Network.NETWORK_DEVNET)),
                    limits = solLimits(perOperation = ONE_SOL),
                )
        val roomy =
            ConnectionPolicy.default(OTHER_CONNECTION, NOW)
                .copy(
                    assets = Allowlist.of(PolicyAsset.sol(Network.NETWORK_DEVNET)),
                    limits =
                        mapOf(
                            PolicyAsset.sol(Network.NETWORK_DEVNET) to
                                AssetLimits(perOperation = 5UL * ONE_SOL)
                        ),
                )
        save(strict)
        save(roomy)
        val scenario = scenarios().first()

        val here = review(scenario).second
        val there = review(scenario, connectionId = OTHER_CONNECTION).second

        assertEquals(listOf("over_per_operation_limit"), here.reasonCodes)
        assertTrue(there.allowed)
    }

    @Test
    fun oneConnectionsSpendingIsNeverCountedAgainstTheOthers() {
        val daily = { id: String ->
            ConnectionPolicy.default(id, NOW)
                .copy(
                    assets = Allowlist.of(PolicyAsset.sol(Network.NETWORK_DEVNET)),
                    limits = solLimits(daily = 3UL * ONE_SOL),
                )
        }
        save(daily(CONNECTION))
        save(daily(OTHER_CONNECTION))
        // The day's spending is this connection's, on the same wallet and the same asset.
        val scenario =
            scenarios()[2].copy(rules = daily(CONNECTION), records = spentEarlier(ONE_SOL))

        assertEquals(listOf("over_daily_limit"), review(scenario).second.reasonCodes)
        assertTrue(review(scenario, connectionId = OTHER_CONNECTION).second.allowed)
    }

    @Test
    fun theSameTransactionReadsTheSameWhateverTheAgentWroteAboutIt() {
        val overThreshold =
            rules(limits = solLimits(perOperation = ONE_SOL, daily = 10UL * ONE_SOL))
        save(overThreshold)
        val plain =
            Scenario(
                name = "no note",
                fixture = "sol_transfer",
                rules = overThreshold,
                assessment = PolicyAssessment.UnderRestrictions,
                reasons = listOf("over_per_operation_limit"),
                approvable = true,
                warns = true,
            )
        val claimed = plain.copy(fixture = "note_disagrees_with_the_amount")
        // The two cases are the same transaction. One carries a note saying a tenth of what the
        // instruction moves, which is the number the owner would have in their head.
        assertEquals(
            case("sol_transfer").getString("transaction"),
            case("note_disagrees_with_the_amount").getString("transaction"),
        )
        assertEquals(
            "Sending 0.25 SOL for the test run",
            case("note_disagrees_with_the_amount").getJSONObject("request").getString("note"),
        )

        assertEquals(review(plain).second, review(claimed).second)
    }

    @Test
    fun aPaymentThisAppNeverSawIsNotInTheDaysTotal() {
        // The owner's own wallet moved four SOL today, directly, with no request and no record
        // here. There is nothing for this app to count it from, and it does not pretend otherwise:
        // a counter is a floor on the day's spending, never a ceiling
        // (docs/policy.md#known-limits).
        val scenario =
            scenarios()[2].copy(
                rules = rules(limits = solLimits(daily = 3UL * ONE_SOL)),
                records = emptyList(),
            )
        save(scenario.rules)

        val decision = review(scenario).second

        assertTrue(decision.allowed)
        // The same amount, moved through this app, is counted and does cross the threshold.
        val recorded = scenario.copy(records = spentEarlier(ONE_SOL))
        assertEquals(listOf("over_daily_limit"), review(recorded).second.reasonCodes)
    }

    @Test
    fun theRulesNeverDecideWhetherATransferCanBeApproved() {
        val all = scenarios()
        // The demonstration is the pair: one the rules warn about that is approvable all the same,
        // and one that matches every rule the owner wrote and has no Approve button at all.
        assertTrue(all.any { it.warns && it.approvable })
        assertTrue(all.any { it.reasons == listOf("request_unverified") && !it.approvable })
        for (scenario in all) {
            save(scenario.rules)
            val (inspection, _) = review(scenario)

            assertEquals(
                "${scenario.name}: input validation decides this, and only it",
                case(scenario.fixture).getString("verdict") == "verified",
                inspection.approvable,
            )
        }
    }

    @Test
    fun assessingAScenarioWritesNothing() {
        for (scenario in scenarios()) {
            save(scenario.rules)
            val file = checkNotNull(dir.listFiles()).single()
            val before = file.readText()
            val modified = file.lastModified()

            repeat(3) { review(scenario) }

            assertEquals(scenario.name, before, file.readText())
            assertEquals(scenario.name, modified, file.lastModified())
            assertEquals(scenario.name, 1, checkNotNull(dir.listFiles()).size)
            file.delete()
        }
    }

    @Test
    fun whatAScenarioProducesCarriesNothingTheOwnerWroteDown() {
        // A verdict and its reasons are codes. No address and no threshold from the rules is in
        // them, which is what lets the assessment be stored with a record, and shown, without a
        // second copy of the rules existing anywhere (SAW-028).
        for (scenario in scenarios()) {
            save(scenario.rules)
            val decision = review(scenario).second
            val produced = decision.reasonCodes + decision.notChecked.map { it.code }

            for (code in produced) {
                assertFalse("${scenario.name}: $code", code.contains(payee))
                assertFalse("${scenario.name}: $code", code.contains(stranger))
                assertFalse("${scenario.name}: $code", code.any(Char::isDigit))
            }
        }
    }
}
