package io.github.brrenat.seekervault.inbox

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import com.google.protobuf.timestamp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.SeekerVaultTheme
import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityLog
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.ActivityRecord
import io.github.brrenat.seekervault.activity.ReviewedTransfer
import io.github.brrenat.seekervault.activity.storage.ActivityStore
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.policy.Allowlist
import io.github.brrenat.seekervault.policy.AssetLimits
import io.github.brrenat.seekervault.policy.ConnectionAssetLimits
import io.github.brrenat.seekervault.policy.ConnectionPolicy
import io.github.brrenat.seekervault.policy.ConnectionPolicyOverrides
import io.github.brrenat.seekervault.policy.DailyCheckScope
import io.github.brrenat.seekervault.policy.GlobalPolicy
import io.github.brrenat.seekervault.policy.PolicyAction
import io.github.brrenat.seekervault.policy.PolicyAssessment
import io.github.brrenat.seekervault.policy.PolicyAsset
import io.github.brrenat.seekervault.policy.PolicyCheck
import io.github.brrenat.seekervault.policy.PolicyCheckStatus
import io.github.brrenat.seekervault.policy.PolicyEvaluator
import io.github.brrenat.seekervault.policy.PolicyReason
import io.github.brrenat.seekervault.policy.RuleOverride
import io.github.brrenat.seekervault.policy.RuleSource
import io.github.brrenat.seekervault.policy.evaluate
import io.github.brrenat.seekervault.policy.policyFacts
import io.github.brrenat.seekervault.policy.storage.PolicyStore
import io.github.brrenat.seekervault.policy.storage.StoredGlobalPolicy
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Asset
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.PreparedTransaction
import io.github.brrenat.seekervault.request.v1.action
import io.github.brrenat.seekervault.request.v1.actionRequest
import io.github.brrenat.seekervault.request.v1.asset
import io.github.brrenat.seekervault.request.v1.preparedTransaction
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.request.v1.transferAction
import io.github.brrenat.seekervault.transactions.SYSTEM_PROGRAM
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.TransferInspection
import io.github.brrenat.seekervault.transactions.inspectTransfer
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Stage 5.1 acceptance through the owner's actual path (SAW-047).
 *
 * These are deliberately not another pure resolver table. Every review starts with transaction
 * bytes built by the sidecar fixture, lets the phone inspect them, reads global and connection
 * documents from a real [PolicyStore], reloads real [ActivityStore] files, and ends with the
 * assessment and manual actions on Request details. The focused model, storage, editor, and
 * lifecycle tests retain the smaller failure boundaries behind this suite.
 */
@RunWith(AndroidJUnit4::class)
class Stage51PolicyScenarioTest {
    @get:Rule val folder = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sol = PolicyAsset.sol(Network.NETWORK_DEVNET)
    private val fixture by lazy { transactionCase("sol_transfer") }
    private val payer by lazy { fixture.getJSONObject("request").getString("wallet") }
    private val recipient by lazy { fixture.getJSONObject("request").getString("recipient") }

    private data class Review(
        val request: ActionRequest,
        val prepared: PreparedTransaction,
        val wallet: SelectedWallet,
        val inspection: TransferInspection,
        val assessment: RequestAssessment,
    )

    private data class Movement(
        val connectionId: String,
        val amount: ULong,
        val outcome: ActivityOutcome = ActivityOutcome.Confirmed,
        val wallet: String? = null,
        val network: Network = Network.NETWORK_DEVNET,
    )

    private data class DailyScenario(
        val name: String,
        val globalLimit: ULong,
        val connectionLimit: ULong?,
        val movements: List<Movement>,
        val statuses: List<PolicyCheckStatus>,
        val reasons: List<String>,
        val confirmed: List<ULong?>,
        val unresolved: List<ULong?>,
        val projected: List<ULong?>,
    )

    @Test
    fun globalProgramsAndConnectionRecipientsReachReviewTogether() {
        val root = scope("mixed-sections")
        val store = policies(root)
        store.putGlobal(GlobalPolicy.default(NOW).copy(programs = Allowlist.of(SYSTEM_PROGRAM)))
        store.putOverrides(
            ConnectionPolicyOverrides.inheritAll(CONNECTION, NOW)
                .copy(recipients = RuleOverride.Replace(Allowlist.of(recipient)))
        )

        val review = review(root)
        val decision = review.assessment.decision

        assertEquals(PolicyAssessment.Allowed, decision.assessment)
        assertEquals(emptyList<String>(), decision.reasonCodes)
        assertEquals(
            listOf(
                PolicyCheck.Action to RuleSource.NotConfigured,
                PolicyCheck.Asset to RuleSource.NotConfigured,
                PolicyCheck.Recipient to RuleSource.ConnectionOverride,
                PolicyCheck.Program to RuleSource.Global,
                PolicyCheck.PerOperationLimit to RuleSource.NotConfigured,
                PolicyCheck.DailyLimit to RuleSource.NotConfigured,
            ),
            decision.checks.map { it.check to it.source },
        )
        assertEquals(
            listOf(
                PolicyCheck.Action,
                PolicyCheck.Asset,
                PolicyCheck.PerOperationLimit,
                PolicyCheck.DailyLimit,
            ),
            decision.notChecked,
        )
        assertTrue(review.inspection.approvable)
        assertFalse(decision.warns)

        show(review)
        // The compact v4 verdict still shows every configured check, what it read, and which
        // document supplied it.
        compose
            .onNodeWithTag(InboxTags.policyCheck(PolicyCheck.Program))
            .assertTextContains(SYSTEM_PROGRAM, substring = true)
            .assertTextContains(context.getString(R.string.policy_source_global), substring = true)
        compose
            .onNodeWithTag(InboxTags.policyCheck(PolicyCheck.Recipient))
            .assertTextContains(recipient, substring = true)
            .assertTextContains(
                context.getString(R.string.policy_source_connection),
                substring = true,
            )
        compose.onNodeWithTag(InboxTags.POLICY_VERDICT).assertExists()
        compose.onNodeWithTag(InboxTags.POLICY_ACKNOWLEDGE).assertDoesNotExist()
        compose.onNodeWithTag(InboxTags.TRANSFER_APPROVE).assertIsEnabled()
        compose.onNodeWithTag(InboxTags.REJECT).assertIsEnabled()
    }

    @Test
    fun aProgramOverrideReplacesGlobalAndResetAndNewPairingRestoreInheritance() {
        val root = scope("replacement-reset")
        val store = policies(root)
        store.putGlobal(GlobalPolicy.default(NOW).copy(programs = Allowlist.of(SYSTEM_PROGRAM)))
        store.putOverrides(
            ConnectionPolicyOverrides.inheritAll(CONNECTION, NOW)
                .copy(programs = RuleOverride.Replace(Allowlist.of(TOKEN_PROGRAM)))
        )

        val replaced = review(root)
        assertEquals(PolicyAssessment.UnderRestrictions, replaced.assessment.decision.assessment)
        assertEquals(listOf("program_not_allowed"), replaced.assessment.decision.reasonCodes)
        assertEquals(
            RuleSource.ConnectionOverride,
            replaced.assessment.decision.checks.single { it.check == PolicyCheck.Program }.source,
        )
        assertTrue(replaced.inspection.approvable)
        assertTrue(replaced.assessment.decision.warns)

        val acknowledged = mutableStateOf(false)
        show(replaced, acknowledged)
        compose.onNodeWithTag(InboxTags.TRANSFER_APPROVE).assertIsNotEnabled()
        compose.onNodeWithTag(InboxTags.REJECT).assertIsEnabled()
        compose.onNodeWithTag(InboxTags.POLICY_ACKNOWLEDGE).performClick()
        compose.onNodeWithTag(InboxTags.TRANSFER_APPROVE).assertIsEnabled()

        store.delete(CONNECTION)
        val reset = review(root)
        assertEquals(PolicyAssessment.Allowed, reset.assessment.decision.assessment)
        assertEquals(
            RuleSource.Global,
            reset.assessment.decision.checks.single { it.check == PolicyCheck.Program }.source,
        )
        assertEquals(emptyList<String>(), reset.assessment.decision.reasonCodes)

        assertFalse(OTHER_CONNECTION in store.connectionIds())
        val newlyPaired = review(root, OTHER_CONNECTION)
        assertEquals(PolicyAssessment.Allowed, newlyPaired.assessment.decision.assessment)
        assertEquals(
            RuleSource.Global,
            newlyPaired.assessment.decision.checks
                .single { it.check == PolicyCheck.Program }
                .source,
        )
        assertEquals(emptyList<String>(), newlyPaired.assessment.decision.reasonCodes)
    }

    @Test
    fun bothDailyScopesMeetEveryBoundaryAndNeitherCanBypassTheOther() {
        val scenarios =
            listOf(
                DailyScenario(
                    name = "global exceeded while connection passes",
                    globalLimit = 10UL * ONE_SOL,
                    connectionLimit = 8UL * ONE_SOL,
                    movements =
                        listOf(
                            Movement(OTHER_CONNECTION, 6UL * ONE_SOL),
                            Movement(
                                REMOVED_CONNECTION,
                                2UL * ONE_SOL,
                                ActivityOutcome.Unknown,
                            ),
                        ),
                    statuses = listOf(PolicyCheckStatus.Failed, PolicyCheckStatus.Passed),
                    reasons = listOf("over_daily_limit"),
                    confirmed = listOf(6UL * ONE_SOL, 0UL),
                    unresolved = listOf(2UL * ONE_SOL, 0UL),
                    projected = listOf(10_500_000_000UL, 2_500_000_000UL),
                ),
                DailyScenario(
                    name = "connection exceeded while global passes",
                    globalLimit = 10UL * ONE_SOL,
                    connectionLimit = 4UL * ONE_SOL,
                    movements =
                        listOf(
                            Movement(CONNECTION, ONE_SOL),
                            Movement(CONNECTION, ONE_SOL, ActivityOutcome.Unknown),
                        ),
                    statuses = listOf(PolicyCheckStatus.Passed, PolicyCheckStatus.Failed),
                    reasons = listOf("over_daily_limit"),
                    confirmed = listOf(ONE_SOL, ONE_SOL),
                    unresolved = listOf(ONE_SOL, ONE_SOL),
                    projected = listOf(4_500_000_000UL, 4_500_000_000UL),
                ),
                DailyScenario(
                    name = "both exceeded",
                    globalLimit = 4UL * ONE_SOL,
                    connectionLimit = 3UL * ONE_SOL,
                    movements = listOf(Movement(CONNECTION, 2UL * ONE_SOL)),
                    statuses = listOf(PolicyCheckStatus.Failed, PolicyCheckStatus.Failed),
                    reasons = listOf("over_daily_limit", "over_daily_limit"),
                    confirmed = listOf(2UL * ONE_SOL, 2UL * ONE_SOL),
                    unresolved = listOf(0UL, 0UL),
                    projected = listOf(4_500_000_000UL, 4_500_000_000UL),
                ),
                DailyScenario(
                    name = "both equal boundaries",
                    globalLimit = 10UL * ONE_SOL,
                    connectionLimit = 8UL * ONE_SOL,
                    movements =
                        listOf(
                            Movement(CONNECTION, 5_500_000_000UL),
                            Movement(OTHER_CONNECTION, 2UL * ONE_SOL),
                        ),
                    statuses = listOf(PolicyCheckStatus.Passed, PolicyCheckStatus.Passed),
                    reasons = emptyList(),
                    confirmed = listOf(7_500_000_000UL, 5_500_000_000UL),
                    unresolved = listOf(0UL, 0UL),
                    projected = listOf(10UL * ONE_SOL, 8UL * ONE_SOL),
                ),
                DailyScenario(
                    name = "higher connection threshold cannot bypass global",
                    globalLimit = 4UL * ONE_SOL,
                    connectionLimit = 10UL * ONE_SOL,
                    movements = listOf(Movement(OTHER_CONNECTION, 2UL * ONE_SOL)),
                    statuses = listOf(PolicyCheckStatus.Failed, PolicyCheckStatus.Passed),
                    reasons = listOf("over_daily_limit"),
                    confirmed = listOf(2UL * ONE_SOL, 0UL),
                    unresolved = listOf(0UL, 0UL),
                    projected = listOf(4_500_000_000UL, 2_500_000_000UL),
                ),
                DailyScenario(
                    name = "absent connection threshold cannot bypass global",
                    globalLimit = 4UL * ONE_SOL,
                    connectionLimit = null,
                    movements = listOf(Movement(OTHER_CONNECTION, 2UL * ONE_SOL)),
                    statuses = listOf(PolicyCheckStatus.Failed, PolicyCheckStatus.NotConfigured),
                    reasons = listOf("over_daily_limit"),
                    confirmed = listOf(2UL * ONE_SOL, null),
                    unresolved = listOf(0UL, null),
                    projected = listOf(4_500_000_000UL, null),
                ),
            )

        scenarios.forEachIndexed { index, scenario ->
            val root = scope("daily-$index")
            val store = policies(root)
            store.putGlobal(
                GlobalPolicy.default(NOW)
                    .copy(limits = mapOf(sol to AssetLimits(daily = scenario.globalLimit)))
            )
            scenario.connectionLimit?.let { limit ->
                store.putOverrides(
                    ConnectionPolicyOverrides.inheritAll(CONNECTION, NOW)
                        .copy(limits = mapOf(sol to ConnectionAssetLimits(daily = limit)))
                )
            }
            writeMovements(root, scenario.movements)

            val review = review(root)
            val decision = review.assessment.decision
            val daily = decision.dailyChecks

            assertEquals(
                "${scenario.name}: scopes",
                listOf(DailyCheckScope.Global, DailyCheckScope.Connection),
                daily.map { it.scope },
            )
            assertEquals(
                "${scenario.name}: statuses",
                scenario.statuses,
                daily.map { it.result.status },
            )
            assertEquals("${scenario.name}: reasons", scenario.reasons, decision.reasonCodes)
            assertEquals(
                "${scenario.name}: row reasons",
                scenario.statuses.map {
                    if (it == PolicyCheckStatus.Failed) PolicyReason.OverDailyLimit else null
                },
                daily.map { it.result.reason },
            )
            assertEquals(
                "${scenario.name}: sources",
                listOf(
                    RuleSource.Global,
                    if (scenario.connectionLimit == null) RuleSource.NotConfigured
                    else RuleSource.ConnectionOverride,
                ),
                daily.map { it.result.source },
            )
            assertEquals(
                "${scenario.name}: confirmed",
                scenario.confirmed,
                daily.map { it.total?.confirmed },
            )
            assertEquals(
                "${scenario.name}: unresolved",
                scenario.unresolved,
                daily.map { it.total?.unresolved },
            )
            assertEquals(
                "${scenario.name}: projected",
                scenario.projected,
                daily.map { it.projected },
            )
            assertEquals(
                "${scenario.name}: verdict",
                if (scenario.reasons.isEmpty()) PolicyAssessment.Allowed
                else PolicyAssessment.UnderRestrictions,
                decision.assessment,
            )
            assertEquals("${scenario.name}: warning", scenario.reasons.isNotEmpty(), decision.warns)
            assertTrue("${scenario.name}: input validation", review.inspection.approvable)
        }
    }

    @Test
    @Config(fontScale = 2.0f)
    fun bothDailyRowsAndManualActionsRemainReachableAtLargeText() {
        assertEquals(2.0f, context.resources.configuration.fontScale, 0.0f)
        val root = scope("large-text")
        val store = policies(root)
        store.putGlobal(
            GlobalPolicy.default(NOW)
                .copy(limits = mapOf(sol to AssetLimits(daily = 4UL * ONE_SOL)))
        )
        store.putOverrides(
            ConnectionPolicyOverrides.inheritAll(CONNECTION, NOW)
                .copy(limits = mapOf(sol to ConnectionAssetLimits(daily = 3UL * ONE_SOL)))
        )
        writeMovements(root, listOf(Movement(CONNECTION, 2UL * ONE_SOL)))
        val review = review(root)
        val acknowledged = mutableStateOf(false)

        show(review, acknowledged)

        compose
            .onNodeWithTag(InboxTags.policyDaily(DailyCheckScope.Global.code))
            .performScrollTo()
            .assertTextContains(context.getString(R.string.policy_source_global), substring = true)
            .assertTextContains("Confirmed: 2", substring = true)
            .assertTextContains("Not yet settled: 0", substring = true)
            .assertTextContains("Projected with this request: 4.5", substring = true)
        compose
            .onNodeWithTag(InboxTags.policyDaily(DailyCheckScope.Connection.code))
            .performScrollTo()
            .assertTextContains(
                context.getString(R.string.policy_source_connection),
                substring = true,
            )
            .assertTextContains("Confirmed: 2", substring = true)
            .assertTextContains("Projected with this request: 4.5", substring = true)
        compose.onNodeWithTag(InboxTags.TRANSFER_APPROVE).assertIsNotEnabled()
        compose.onNodeWithTag(InboxTags.REJECT).assertIsEnabled()
        compose.onNodeWithTag(InboxTags.POLICY_ACKNOWLEDGE).performClick()
        compose.onNodeWithTag(InboxTags.TRANSFER_APPROVE).assertIsEnabled()
    }

    @Test
    fun globalHistoryIncludesOtherAndRemovedConnectionsButSeparatesWalletAndNetwork() {
        val root = scope("scope-dimensions")
        val store = policies(root)
        store.putGlobal(
            GlobalPolicy.default(NOW)
                .copy(limits = mapOf(sol to AssetLimits(daily = 5UL * ONE_SOL)))
        )
        store.putOverrides(
            ConnectionPolicyOverrides.inheritAll(CONNECTION, NOW)
                .copy(limits = mapOf(sol to ConnectionAssetLimits(daily = 5UL * ONE_SOL)))
        )
        writeMovements(
            root,
            listOf(
                Movement(OTHER_CONNECTION, ONE_SOL),
                // No policy or live-connection document names this ID. Activity is retained.
                Movement(REMOVED_CONNECTION, 2UL * ONE_SOL),
                Movement(OTHER_CONNECTION, 50UL * ONE_SOL, wallet = OTHER_WALLET),
                Movement(
                    OTHER_CONNECTION,
                    50UL * ONE_SOL,
                    network = Network.NETWORK_MAINNET,
                ),
            ),
        )

        val review = review(root)
        val daily = review.assessment.decision.dailyChecks

        assertFalse(REMOVED_CONNECTION in store.connectionIds())
        assertEquals(3UL * ONE_SOL, daily[0].total?.confirmed)
        assertEquals(5_500_000_000UL, daily[0].projected)
        assertEquals(PolicyCheckStatus.Failed, daily[0].result.status)
        assertEquals(0UL, daily[1].total?.confirmed)
        assertEquals(2_500_000_000UL, daily[1].projected)
        assertEquals(PolicyCheckStatus.Passed, daily[1].result.status)
        assertEquals(listOf("over_daily_limit"), review.assessment.decision.reasonCodes)
    }

    @Test
    fun stage5UpgradeAndRestartKeepTheSameRequestReview() {
        val root = scope("upgrade-restart")
        val policyDir = File(root, "policies").apply { mkdirs() }
        val old =
            ConnectionPolicy.default(CONNECTION, NOW)
                .copy(
                    actions = Allowlist.of(PolicyAction.Transfer),
                    programs = Allowlist.of(SYSTEM_PROGRAM),
                    limits =
                        mapOf(
                            sol to
                                AssetLimits(
                                    perOperation = 5UL * ONE_SOL,
                                    daily = 6UL * ONE_SOL,
                                )
                        ),
                )
        File(policyDir, "$CONNECTION.json")
            .writeText(
                JSONObject()
                    .put("version", 1)
                    .put("connectionId", CONNECTION)
                    .put("updatedAt", NOW.toString())
                    .put("actions", JSONArray(listOf("transfer")))
                    .put("programs", JSONArray(listOf(SYSTEM_PROGRAM)))
                    .put(
                        "limits",
                        JSONArray(
                            listOf(
                                JSONObject()
                                    .put(
                                        "asset",
                                        JSONObject().put("network", "NETWORK_DEVNET"),
                                    )
                                    .put("perOperation", (5UL * ONE_SOL).toString())
                                    .put("daily", (6UL * ONE_SOL).toString())
                            )
                        ),
                    )
                    .toString()
            )

        val afterUpgrade = review(root)
        val legacy =
            evaluate(
                old,
                afterUpgrade.assessment.facts,
                afterUpgrade.assessment.decision.dailyChecks[1].total,
            )

        assertEquals(legacy.assessment, afterUpgrade.assessment.decision.assessment)
        assertEquals(legacy.reasonCodes, afterUpgrade.assessment.decision.reasonCodes)
        assertEquals(legacy.notChecked, afterUpgrade.assessment.decision.notChecked)
        assertEquals(
            RuleSource.ConnectionOverride,
            afterUpgrade.assessment.decision.checks
                .single { it.check == PolicyCheck.Program }
                .source,
        )
        assertEquals(
            RuleSource.ConnectionOverride,
            afterUpgrade.assessment.decision.dailyChecks[1].result.source,
        )
        assertEquals(
            2,
            JSONObject(File(policyDir, "$CONNECTION.json").readText()).getInt("version"),
        )
        assertEquals(StoredGlobalPolicy.None, PolicyStore(policyDir).getGlobal())

        val restarted = review(root)
        assertEquals(afterUpgrade.assessment.decision, restarted.assessment.decision)
        assertEquals(afterUpgrade.assessment.facts, restarted.assessment.facts)
        assertEquals(afterUpgrade.prepared, restarted.prepared)
    }

    @Test
    fun unreadableRulesAndHistoryNeverBecomeMissingOrEmpty() {
        val rulesRoot = scope("unreadable-rules")
        val store = policies(rulesRoot)
        store.putGlobal(
            GlobalPolicy.default(NOW).copy(actions = Allowlist.of(PolicyAction.Transfer))
        )
        val policyDir = File(rulesRoot, "policies")
        val localFile = File(policyDir, "$CONNECTION.json")
        localFile.writeText("{ not json")

        val localUnreadable = review(rulesRoot).assessment.decision
        assertEquals(listOf("policy_unreadable"), localUnreadable.reasonCodes)
        assertEquals(listOf(RuleSource.ConnectionOverride), localUnreadable.unreadableSources)

        File(policyDir, "global.json").writeText("{ not json")
        val bothUnreadable = review(rulesRoot).assessment.decision
        assertEquals(
            listOf(RuleSource.Global, RuleSource.ConnectionOverride),
            bothUnreadable.unreadableSources,
        )

        localFile.delete()
        val globalUnreadable = review(rulesRoot).assessment.decision
        assertEquals(listOf("policy_unreadable"), globalUnreadable.reasonCodes)
        assertEquals(listOf(RuleSource.Global), globalUnreadable.unreadableSources)

        val historyRoot = scope("unreadable-history")
        policies(historyRoot)
            .putGlobal(
                GlobalPolicy.default(NOW)
                    .copy(limits = mapOf(sol to AssetLimits(daily = 10UL * ONE_SOL)))
            )
        val damaged = File(historyRoot, "activity/$OTHER_CONNECTION")
        damaged.mkdirs()
        File(damaged, "$ACTIVITY_REQUEST_1.json").writeText("{ not json")

        val historyUnreadable = review(historyRoot).assessment.decision
        assertEquals(listOf("daily_total_unverified"), historyUnreadable.reasonCodes)
        assertEquals(PolicyCheckStatus.Unverified, historyUnreadable.dailyChecks[0].result.status)
        assertEquals(
            PolicyReason.DailyTotalUnverified,
            historyUnreadable.dailyChecks[0].result.reason,
        )
        assertEquals(1, historyUnreadable.dailyChecks[0].total?.unreadable)
        assertNull(historyUnreadable.dailyChecks[1].total)
    }

    @Test
    fun liveGlobalEditsAndCrossConnectionSpendingChangeConsent() {
        val root = scope("stale-review")
        val store = policies(root)
        store.putGlobal(
            GlobalPolicy.default(NOW)
                .copy(
                    recipients = Allowlist.of(OTHER_RECIPIENT),
                    limits = mapOf(sol to AssetLimits(daily = 4UL * ONE_SOL)),
                )
        )
        val shown = review(root).assessment
        assertEquals(listOf("recipient_not_allowed"), shown.decision.reasonCodes)

        // The rendered decision is deliberately unchanged, but the applicable allowlist is not.
        store.putGlobal(
            GlobalPolicy.default(NOW.plusSeconds(1))
                .copy(
                    recipients = Allowlist.of(OTHER_RECIPIENT, THIRD_RECIPIENT),
                    limits = mapOf(sol to AssetLimits(daily = 4UL * ONE_SOL)),
                )
        )
        val afterEdit = review(root).assessment
        assertEquals(shown.decision, afterEdit.decision)
        assertNotEquals(shown.applicablePolicy, afterEdit.applicablePolicy)
        assertNotEquals(shown.consent, afterEdit.consent)

        writeMovements(root, listOf(Movement(OTHER_CONNECTION, 2UL * ONE_SOL)))
        val afterSpending = review(root).assessment
        assertEquals(
            listOf("recipient_not_allowed", "over_daily_limit"),
            afterSpending.decision.reasonCodes,
        )
        assertEquals(2UL * ONE_SOL, afterSpending.decision.dailyChecks[0].total?.confirmed)
        assertNotEquals(afterEdit.consent, afterSpending.consent)
    }

    private fun scope(name: String) = File(folder.root, name).apply { mkdirs() }

    private fun policies(root: File) = PolicyStore(File(root, "policies"))

    private fun review(root: File, connectionId: String = CONNECTION): Review {
        val request = requestOf(connectionId)
        val prepared = preparedFromFixture()
        val wallet =
            SelectedWallet(
                address = payer,
                network = WalletNetwork.Devnet,
                selectedAt = NOW,
            )
        val inspection = inspectTransfer(request, prepared, wallet)
        val facts = policyFacts(connectionId, request, Network.NETWORK_DEVNET, inspection)
        val history = ActivityLog(ActivityStore(File(root, "activity")), { NOW })
        try {
            history.load()
        } catch (_: IOException) {
            // The production request review handles an unavailable store the same way: loaded is
            // false, so a configured daily threshold is unverified rather than read as zero.
        } catch (_: SecurityException) {}
        val evaluator =
            PolicyEvaluator(
                policies(root),
                records = { history.records.value.takeIf { history.loaded.value } },
                now = { NOW },
                zone = { ZoneId.of("UTC") },
                unreadableRecords = { history.unreadableRecords.value },
            )
        val current = evaluator.evaluateCurrent(facts)
        return Review(
            request = request,
            prepared = prepared,
            wallet = wallet,
            inspection = inspection,
            assessment =
                RequestAssessment(
                    decision = current.decision,
                    facts = facts,
                    at = NOW,
                    applicablePolicy = current.applicablePolicy,
                    preparation = prepared,
                ),
        )
    }

    private fun requestOf(connectionId: String): ActionRequest {
        val fields = fixture.getJSONObject("request")
        return actionRequest {
            ref = requestRef {
                this.connectionId = connectionId
                requestId = REQUEST
            }
            createdAt = timestamp { seconds = NOW.minusSeconds(60).epochSecond }
            expiresAt = timestamp { seconds = NOW.plusSeconds(3_600).epochSecond }
            action = action {
                transfer = transferAction {
                    wallet = fields.getString("wallet")
                    network = Network.NETWORK_DEVNET
                    recipient = fields.getString("recipient")
                    amount = fields.getString("amount")
                    asset = asset { nativeSol = Asset.NativeSol.getDefaultInstance() }
                }
            }
        }
    }

    private fun preparedFromFixture() = preparedTransaction {
        version = fixture.getInt("version")
        transaction =
            ByteString.copyFrom(Base64.getDecoder().decode(fixture.getString("transaction")))
        contentHash =
            ByteString.copyFrom(Base64.getDecoder().decode(fixture.getString("contentHash")))
    }

    private fun transactionCase(name: String): JSONObject {
        val cases =
            JSONObject(
                    checkNotNull(javaClass.getResourceAsStream("/transactions/cases.json")).use {
                        it.readBytes().decodeToString()
                    }
                )
                .getJSONArray("cases")
        return (0 until cases.length())
            .map { cases.getJSONObject(it) }
            .first { it.getString("name") == name }
    }

    private fun writeMovements(root: File, movements: List<Movement>) {
        val store = ActivityStore(File(root, "activity"))
        movements.forEachIndexed { index, movement ->
            store.put(
                ActivityRecord(
                    connectionId = movement.connectionId,
                    requestId = ACTIVITY_REQUESTS[index],
                    source = "Stage 5.1 fixture",
                    serverHost = "sidecar.example",
                    kind = ActivityKind.Transfer,
                    answeredAt = NOW.minusSeconds((index + 1).toLong()),
                    recordedAt = NOW,
                    outcome = movement.outcome,
                    transfer =
                        ReviewedTransfer(
                            wallet = movement.wallet ?: payer,
                            network = movement.network,
                            recipient = recipient,
                            amount = movement.amount.toString(),
                            mint = null,
                            preparedVersion = 1,
                        ),
                )
            )
        }
    }

    private fun show(
        review: Review,
        acknowledged: androidx.compose.runtime.MutableState<Boolean> = mutableStateOf(false),
    ) {
        val source =
            Connection(
                id = review.request.ref.connectionId,
                label = "Stage 5.1 fixture",
                serverUrl = "https://sidecar.example",
                serverId = SERVER,
                deviceName = "Seeker",
                pairedAt = NOW.minusSeconds(86_400),
                lastCheck = Connection.Check(NOW, CheckOutcome.Ok, pending = 1),
            )
        compose.setContent {
            SeekerVaultTheme {
                RequestDetailsScreen(
                    request = review.request,
                    source = source,
                    result = null,
                    sending = false,
                    now = NOW,
                    onAnswer = {},
                    onApprove = {},
                    onSendAgain = {},
                    onBack = {},
                    wallet = review.wallet,
                    preparation =
                        Preparation.Ready(review.prepared, review.inspection, review.wallet),
                    onApproveTransfer = {},
                    assessment = review.assessment,
                    acknowledged = acknowledged.value,
                    onAcknowledge = { acknowledged.value = it },
                )
            }
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-13T12:00:00Z")
        const val ONE_SOL = 1_000_000_000UL
        const val CONNECTION = "0b8e2b1c-3f4d-4e5a-9b6c-7d8e9f0a1b2c"
        const val OTHER_CONNECTION = "9c1d7b3a-8e4f-4a52-b0c6-1d2e3f4a5b6c"
        const val REMOVED_CONNECTION = "a1111111-1111-4111-8111-111111111111"
        const val REQUEST = "f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19"
        const val SERVER = "9fda5035-f3b4-4ec3-a68a-5e6caa02397a"
        const val OTHER_WALLET = "So11111111111111111111111111111111111111112"
        const val OTHER_RECIPIENT = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
        const val THIRD_RECIPIENT = "7LCE7pWnYuYKtGMWt2Q4aXKQrqTmGf4c1Kt1PTmAGwSt"
        const val ACTIVITY_REQUEST_1 = "b1111111-1111-4111-8111-111111111111"
        val ACTIVITY_REQUESTS =
            listOf(
                ACTIVITY_REQUEST_1,
                "b2222222-2222-4222-8222-222222222222",
                "b3333333-3333-4333-8333-333333333333",
                "b4444444-4444-4444-8444-444444444444",
            )
    }
}
