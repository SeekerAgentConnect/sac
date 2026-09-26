package io.github.brrenat.seekervault.policy

import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.ActivityRecord
import io.github.brrenat.seekervault.activity.ReviewedTransfer
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.transactions.ASSOCIATED_TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.COMPUTE_BUDGET_PROGRAM
import io.github.brrenat.seekervault.transactions.LAMPORT_DECIMALS
import io.github.brrenat.seekervault.transactions.SYSTEM_PROGRAM
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM
import java.time.Instant

/**
 * The fixtures the policy tests share (docs/policy.md#test-fixtures).
 *
 * [POLICY_CASES] is the table: each case is a policy, the facts of one request, the day's counters,
 * and the verdict with every reason code it must carry. `PolicyFixturesTest` runs all of them, so a
 * case added here is a case checked, and a reason code that changes meaning breaks the table rather
 * than passing quietly.
 */
const val CONNECTION = "6a5f0a0e-2f2f-4a07-9a1f-4f0f2b3f9a11"
const val OTHER_CONNECTION = "9c1d7b3a-8e4f-4a52-b0c6-1d2e3f4a5b6c"
const val WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
const val OTHER_WALLET = "So11111111111111111111111111111111111111112"
const val RECIPIENT = "7LCE7pWnYuYKtGMWt2Q4aXKQrqTmGf4c1Kt1PTmAGwSt"
const val STRANGER = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
const val MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
const val OTHER_MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"

/** The mint's own base units. USDC has six decimals, so a million of them is one token. */
const val TOKEN_DECIMALS = 6

val SOL: PolicyAsset = PolicyAsset.sol(Network.NETWORK_MAINNET)
val SOL_ON_DEVNET: PolicyAsset = PolicyAsset.sol(Network.NETWORK_DEVNET)
val USDC: PolicyAsset = PolicyAsset.token(Network.NETWORK_MAINNET, MINT)

/** A plain SOL transfer calls these two, and a policy that lists programs lists both. */
val SOL_PROGRAMS = listOf(SYSTEM_PROGRAM, COMPUTE_BUDGET_PROGRAM)

/** A token transfer that has the chain vouch for the destination calls these. */
val TOKEN_PROGRAMS = listOf(ASSOCIATED_TOKEN_PROGRAM, TOKEN_PROGRAM, COMPUTE_BUDGET_PROGRAM)

/** One SOL, in lamports. */
const val ONE_SOL = 1_000_000_000UL

val NOW: Instant = Instant.parse("2026-09-12T10:00:00Z")

/** A policy with nothing configured, which is what every connection starts with. */
fun policy(): ConnectionPolicy = ConnectionPolicy.default(CONNECTION, NOW)

/** The facts of a SOL transfer the phone read whole. */
fun solFacts(
    connectionId: String = CONNECTION,
    wallet: String? = WALLET,
    recipient: String? = RECIPIENT,
    amount: ULong? = ONE_SOL,
    asset: PolicyAsset? = SOL,
    programs: List<String>? = SOL_PROGRAMS,
    fullyRead: Boolean = true,
): RequestFacts =
    RequestFacts(
        connectionId = connectionId,
        wallet = wallet,
        action = PolicyAction.Transfer,
        movesValue = true,
        asset = asset,
        recipient = recipient,
        programs = programs,
        amount = amount,
        decimals = LAMPORT_DECIMALS,
        fullyRead = fullyRead,
        preparedVersion = 1,
    )

/** The facts of a token transfer the phone read whole. */
fun tokenFacts(
    recipient: String? = RECIPIENT,
    amount: ULong? = 1_500_000UL,
    asset: PolicyAsset? = USDC,
    programs: List<String>? = TOKEN_PROGRAMS,
    fullyRead: Boolean = true,
): RequestFacts =
    RequestFacts(
        connectionId = CONNECTION,
        wallet = WALLET,
        action = PolicyAction.Transfer,
        movesValue = true,
        asset = asset,
        recipient = recipient,
        programs = programs,
        amount = amount,
        decimals = TOKEN_DECIMALS,
        fullyRead = fullyRead,
        preparedVersion = 1,
    )

/** What [facts] would count against, for the tests that need the scope in hand. */
fun scopeOf(facts: RequestFacts): SpendScope = checkNotNull(facts.scope)

/** A day's counters with nothing in them. */
fun nothingToday(facts: RequestFacts): DailyTotal =
    DailyTotal.none(scopeOf(facts), NOW.atZone(java.time.ZoneOffset.UTC).toLocalDate())

/** A day's counters holding [confirmed] settled and [unresolved] still in the air. */
fun spentToday(
    facts: RequestFacts,
    confirmed: ULong = 0UL,
    unresolved: ULong = 0UL,
    unreadable: Int = 0,
): DailyTotal =
    DailyTotal(
        scope = scopeOf(facts),
        day = NOW.atZone(java.time.ZoneOffset.UTC).toLocalDate(),
        confirmed = confirmed,
        unresolved = unresolved,
        confirmedCount = if (confirmed > 0UL) 1 else 0,
        unresolvedCount = if (unresolved > 0UL) 1 else 0,
        unreadable = unreadable,
    )

/** One of the owner's own records, as the counters read it. */
fun record(
    requestId: String,
    connectionId: String = CONNECTION,
    wallet: String = WALLET,
    network: Network = Network.NETWORK_MAINNET,
    mint: String? = null,
    amount: String = ONE_SOL.toString(),
    outcome: ActivityOutcome = ActivityOutcome.Confirmed,
    answeredAt: Instant = NOW,
    signature: String? = null,
    kind: ActivityKind = ActivityKind.Transfer,
): ActivityRecord =
    ActivityRecord(
        connectionId = connectionId,
        requestId = requestId,
        source = "Hermes",
        serverHost = "sidecar.example",
        kind = kind,
        answeredAt = answeredAt,
        recordedAt = answeredAt,
        outcome = outcome,
        transfer =
            if (kind == ActivityKind.Transfer) {
                ReviewedTransfer(
                    wallet = wallet,
                    network = network,
                    recipient = RECIPIENT,
                    amount = amount,
                    mint = mint,
                    preparedVersion = 1,
                )
            } else null,
        signature = signature,
    )

/** One row of the fixture table. */
data class PolicyCase(
    val name: String,
    val policy: ConnectionPolicy,
    val facts: RequestFacts,
    val spentToday: DailyTotal?,
    val assessment: PolicyAssessment,
    /** Every reason code, in order. Empty for ALLOWED. */
    val reasons: List<String>,
    /** The checks the assessment doesn't cover, in order. */
    val notChecked: List<PolicyCheck> = emptyList(),
)

private val EVERY_CHECK = PolicyCheck.entries.toList()

/**
 * The cases every policy assessment is held to. They are written as data on purpose: a rule that
 * only exists in a test's prose can be argued with, and a rule in this table either holds for every
 * case or fails one.
 */
val POLICY_CASES: List<PolicyCase> =
    listOf(
        PolicyCase(
            name = "no rules at all is not a match",
            policy = policy(),
            facts = solFacts(),
            spentToday = null,
            assessment = PolicyAssessment.UnderRestrictions,
            reasons = listOf("no_policy_configured"),
            notChecked = EVERY_CHECK,
        ),
        PolicyCase(
            name = "one rule, matched",
            policy = policy().copy(actions = Allowlist.of(PolicyAction.Transfer)),
            facts = solFacts(),
            spentToday = null,
            assessment = PolicyAssessment.Allowed,
            reasons = emptyList(),
            notChecked = EVERY_CHECK - PolicyCheck.Action,
        ),
        PolicyCase(
            name = "one rule, not matched",
            policy = policy().copy(actions = Allowlist.of(PolicyAction.Acknowledgement)),
            facts = solFacts(),
            spentToday = null,
            assessment = PolicyAssessment.UnderRestrictions,
            reasons = listOf("action_not_allowed"),
            notChecked = EVERY_CHECK - PolicyCheck.Action,
        ),
        PolicyCase(
            name = "a list configured to allow nothing fails everything",
            policy = policy().copy(recipients = Allowlist.nothing()),
            facts = solFacts(),
            spentToday = null,
            assessment = PolicyAssessment.UnderRestrictions,
            reasons = listOf("recipient_not_allowed"),
            notChecked = EVERY_CHECK - PolicyCheck.Recipient,
        ),
        PolicyCase(
            name = "every configured rule matched",
            policy =
                policy()
                    .copy(
                        actions = Allowlist.of(PolicyAction.Transfer),
                        assets = Allowlist.of(SOL),
                        recipients = Allowlist.of(RECIPIENT),
                        programs = Allowlist(SOL_PROGRAMS.toSet()),
                        limits = mapOf(SOL to AssetLimits(perOperation = ONE_SOL, daily = ONE_SOL)),
                    ),
            facts = solFacts(),
            spentToday = spentToday(solFacts()),
            assessment = PolicyAssessment.Allowed,
            reasons = emptyList(),
        ),
        PolicyCase(
            name = "an amount exactly on the per-operation threshold matches",
            policy =
                policy()
                    .copy(
                        assets = Allowlist.of(SOL),
                        limits = mapOf(SOL to AssetLimits(perOperation = ONE_SOL)),
                    ),
            facts = solFacts(amount = ONE_SOL),
            spentToday = null,
            assessment = PolicyAssessment.Allowed,
            reasons = emptyList(),
            notChecked =
                listOf(
                    PolicyCheck.Action,
                    PolicyCheck.Recipient,
                    PolicyCheck.Program,
                    PolicyCheck.DailyLimit,
                ),
        ),
        PolicyCase(
            name = "one base unit over the per-operation threshold does not",
            policy =
                policy()
                    .copy(
                        assets = Allowlist.of(SOL),
                        limits = mapOf(SOL to AssetLimits(perOperation = ONE_SOL)),
                    ),
            facts = solFacts(amount = ONE_SOL + 1UL),
            spentToday = null,
            assessment = PolicyAssessment.UnderRestrictions,
            reasons = listOf("over_per_operation_limit"),
            notChecked =
                listOf(
                    PolicyCheck.Action,
                    PolicyCheck.Recipient,
                    PolicyCheck.Program,
                    PolicyCheck.DailyLimit,
                ),
        ),
        PolicyCase(
            name = "the day's unresolved amounts count towards the daily threshold",
            policy =
                policy()
                    .copy(
                        assets = Allowlist.of(SOL),
                        limits = mapOf(SOL to AssetLimits(daily = 2UL * ONE_SOL)),
                    ),
            facts = solFacts(amount = ONE_SOL),
            spentToday = spentToday(solFacts(), unresolved = 2UL * ONE_SOL),
            assessment = PolicyAssessment.UnderRestrictions,
            reasons = listOf("over_daily_limit"),
            notChecked =
                listOf(
                    PolicyCheck.Action,
                    PolicyCheck.Recipient,
                    PolicyCheck.Program,
                    PolicyCheck.PerOperationLimit,
                ),
        ),
        PolicyCase(
            name = "a day whose records didn't all read back can't be checked",
            policy =
                policy()
                    .copy(
                        assets = Allowlist.of(SOL),
                        limits = mapOf(SOL to AssetLimits(daily = 2UL * ONE_SOL)),
                    ),
            facts = solFacts(amount = 1UL),
            spentToday = spentToday(solFacts(), unreadable = 1),
            assessment = PolicyAssessment.UnderRestrictions,
            reasons = listOf("daily_total_unverified"),
            notChecked =
                listOf(
                    PolicyCheck.Action,
                    PolicyCheck.Recipient,
                    PolicyCheck.Program,
                    PolicyCheck.PerOperationLimit,
                ),
        ),
        PolicyCase(
            name = "a token whose recipient the bytes don't establish",
            policy = policy().copy(recipients = Allowlist.of(RECIPIENT)),
            facts = tokenFacts(recipient = null),
            spentToday = null,
            assessment = PolicyAssessment.UnderRestrictions,
            reasons = listOf("recipient_unverified"),
            notChecked = EVERY_CHECK - PolicyCheck.Recipient,
        ),
        PolicyCase(
            name = "a program the transaction calls that no rule allows",
            policy = policy().copy(programs = Allowlist.of(SYSTEM_PROGRAM)),
            facts = solFacts(),
            spentToday = null,
            assessment = PolicyAssessment.UnderRestrictions,
            reasons = listOf("program_not_allowed"),
            notChecked = EVERY_CHECK - PolicyCheck.Program,
        ),
        PolicyCase(
            name = "the same mint on another chain is another asset",
            policy = policy().copy(assets = Allowlist.of(USDC)),
            facts = tokenFacts(asset = PolicyAsset.token(Network.NETWORK_DEVNET, MINT)),
            spentToday = null,
            assessment = PolicyAssessment.UnderRestrictions,
            reasons = listOf("asset_not_allowed"),
            notChecked = EVERY_CHECK - PolicyCheck.Asset,
        ),
        PolicyCase(
            name = "an unread instruction withholds the verdict from a matching request",
            policy = policy().copy(actions = Allowlist.of(PolicyAction.Transfer)),
            facts = solFacts(fullyRead = false),
            spentToday = null,
            assessment = PolicyAssessment.UnderRestrictions,
            reasons = listOf("request_unverified"),
            notChecked = EVERY_CHECK - PolicyCheck.Action,
        ),
        PolicyCase(
            name = "an acknowledgement satisfies the rules about what moves by moving nothing",
            policy =
                policy()
                    .copy(
                        actions = Allowlist.of(PolicyAction.Acknowledgement),
                        assets = Allowlist.of(SOL),
                        recipients = Allowlist.of(RECIPIENT),
                    ),
            facts = RequestFacts.movesNothing(CONNECTION, PolicyAction.Acknowledgement),
            spentToday = null,
            assessment = PolicyAssessment.Allowed,
            reasons = emptyList(),
            notChecked =
                listOf(
                    PolicyCheck.Program,
                    PolicyCheck.PerOperationLimit,
                    PolicyCheck.DailyLimit,
                ),
        ),
        PolicyCase(
            name = "a swap this stage can't read is never allowed",
            policy = policy().copy(actions = Allowlist.of(PolicyAction.Swap)),
            facts = RequestFacts.unread(CONNECTION, PolicyAction.Swap),
            spentToday = null,
            assessment = PolicyAssessment.UnderRestrictions,
            reasons = listOf("request_unverified"),
            notChecked = EVERY_CHECK - PolicyCheck.Action,
        ),
        PolicyCase(
            name = "an action this build has no name for can't be matched",
            policy = policy().copy(actions = Allowlist.of(PolicyAction.Transfer)),
            facts = RequestFacts.unread(CONNECTION, null),
            spentToday = null,
            assessment = PolicyAssessment.UnderRestrictions,
            reasons = listOf("action_unverified"),
            notChecked = EVERY_CHECK - PolicyCheck.Action,
        ),
        PolicyCase(
            name = "no wallet connected leaves the asset unestablished",
            policy = policy().copy(assets = Allowlist.of(SOL)),
            facts = solFacts(asset = null, wallet = null),
            spentToday = null,
            assessment = PolicyAssessment.UnderRestrictions,
            reasons = listOf("asset_unverified"),
            notChecked = EVERY_CHECK - PolicyCheck.Asset,
        ),
        PolicyCase(
            name = "a threshold can't be read for an asset the phone couldn't establish",
            policy =
                policy()
                    .copy(
                        limits =
                            mapOf(SOL to AssetLimits(perOperation = ONE_SOL, daily = 2UL * ONE_SOL))
                    ),
            facts = solFacts(asset = null, wallet = null),
            spentToday = null,
            assessment = PolicyAssessment.UnderRestrictions,
            reasons = listOf("asset_unverified", "asset_unverified"),
            notChecked =
                listOf(
                    PolicyCheck.Action,
                    PolicyCheck.Asset,
                    PolicyCheck.Recipient,
                    PolicyCheck.Program,
                ),
        ),
        PolicyCase(
            name = "an amount the phone couldn't read meets no threshold",
            policy =
                policy()
                    .copy(
                        assets = Allowlist.of(SOL),
                        limits =
                            mapOf(
                                SOL to AssetLimits(perOperation = ONE_SOL, daily = 2UL * ONE_SOL)
                            ),
                    ),
            facts = solFacts(amount = null),
            spentToday = spentToday(solFacts()),
            assessment = PolicyAssessment.UnderRestrictions,
            reasons = listOf("amount_unverified", "amount_unverified"),
            notChecked = listOf(PolicyCheck.Action, PolicyCheck.Recipient, PolicyCheck.Program),
        ),
        PolicyCase(
            name = "a transaction the phone couldn't read names no programs",
            policy = policy().copy(programs = Allowlist(SOL_PROGRAMS.toSet())),
            facts = solFacts(programs = null, fullyRead = false),
            spentToday = null,
            assessment = PolicyAssessment.UnderRestrictions,
            reasons = listOf("program_unverified"),
            notChecked = EVERY_CHECK - PolicyCheck.Program,
        ),
    )
