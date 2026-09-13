package io.github.brrenat.seekervault.policy

import io.github.brrenat.seekervault.activity.ActivityRecord
import io.github.brrenat.seekervault.policy.storage.PolicyStore
import io.github.brrenat.seekervault.policy.storage.StoredConnectionOverrides
import io.github.brrenat.seekervault.policy.storage.StoredGlobalPolicy
import io.github.brrenat.seekervault.policy.storage.StoredPolicy
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Applying one connection's rules to one request (docs/policy.md#evaluation-semantics).
 *
 * Everything here is a function of what was handed to it: the same rules, the same facts, and the
 * same day's counters give the same verdict, every time, with no clock read behind the caller's
 * back and no network touched. That is what makes an assessment explainable — every reason comes
 * from a check, and every check names what it read.
 *
 * It still decides nothing. Nothing in this file approves, rejects, opens a wallet, sends anything,
 * or writes anything down.
 */

/** A check that passed because the request does nothing the rule is about. */
private const val NOTHING_MOVES = "nothing moves"

/**
 * The verdict on [facts] under [policy], with [spentToday] the day's counters for the request's own
 * scope, or null when the phone couldn't establish which counter it belongs to.
 *
 * The policy must be the request's own connection's: rules are never applied across connections,
 * and handing over another's is a mistake in the caller, not a request to assess.
 */
fun evaluate(
    policy: ConnectionPolicy,
    facts: RequestFacts,
    spentToday: DailyTotal? = null,
): PolicyDecision {
    require(policy.connectionId == facts.connectionId) {
        "a connection's rules are applied to its own requests only"
    }
    require(spentToday == null || spentToday.scope == facts.scope) {
        "the day's counters are the request's own scope's"
    }
    val decision =
        assess(
            listOf(
                actionCheck(policy, facts),
                assetCheck(policy, facts),
                recipientCheck(policy, facts),
                programCheck(policy, facts),
                perOperationCheck(policy, facts),
                dailyCheck(policy, facts, spentToday),
            )
        )
    // Coverage is not compliance. A transaction the phone couldn't account for whole is never
    // ALLOWED, however well the part it did read matched the rules: no rule was written about what
    // is in the gap, so matching the rest establishes nothing about it. A request that is under
    // restrictions already keeps the reasons it has; this one exists to withhold a match.
    return decision.withVerificationPrecedence(facts)
}

/** The two daily histories supplied to an effective-policy evaluation. */
data class DailyTotals(
    val global: DailyTotal?,
    val connection: DailyTotal?,
)

/**
 * The verdict on [facts] under the resolved global and connection rules.
 *
 * Rule source metadata follows every effective check. The two daily checks are retained in global,
 * then connection order even when the first one fails; [checks] also carries their conjunction as
 * the Stage 5-compatible daily row until SAW-046 renders the scoped rows directly.
 */
fun evaluate(
    policy: EffectivePolicy,
    facts: RequestFacts,
    totals: DailyTotals = DailyTotals(null, null),
): PolicyDecision {
    require(policy.connectionId == facts.connectionId) {
        "effective rules are applied to their own connection only"
    }
    val expectedConnection = facts.scope
    val expectedGlobal = expectedConnection?.let {
        GlobalSpendScope(wallet = it.wallet, asset = it.asset)
    }
    require(totals.connection == null || totals.connection.scope == expectedConnection) {
        "the connection total is the request's own connection scope"
    }
    require(totals.global == null || totals.global.scope == expectedGlobal) {
        "the global total is the request's own global scope"
    }

    val daily =
        listOf(
            effectiveDailyCheck(DailyCheckScope.Global, policy, facts, totals.global),
            effectiveDailyCheck(
                DailyCheckScope.Connection,
                policy,
                facts,
                totals.connection,
            ),
        )
    val decision =
        assess(
            checks =
                listOf(
                    effectiveActionCheck(policy.actions, facts),
                    effectiveAssetCheck(policy.assets, facts),
                    effectiveRecipientCheck(policy.recipients, facts),
                    effectiveProgramCheck(policy.programs, facts),
                    effectivePerOperationCheck(policy, facts),
                    combinedDailyCheck(daily),
                ),
            dailyChecks = daily,
        )
    return decision.withVerificationPrecedence(facts)
}

private fun PolicyDecision.withVerificationPrecedence(facts: RequestFacts): PolicyDecision {
    // Coverage is not compliance. The checks keep what they found; the verdict alone is withheld.
    if (facts.fullyRead || !allowed) return this
    return copy(
        assessment = PolicyAssessment.UnderRestrictions,
        reason = PolicyReason.RequestUnverified,
    )
}

/**
 * The verdict under whatever this phone has stored for the connection. Rules that can't be read are
 * not the absence of rules, and neither state is ever ALLOWED.
 */
fun evaluate(
    stored: StoredPolicy,
    facts: RequestFacts,
    spentToday: DailyTotal? = null,
): PolicyDecision =
    when (stored) {
        StoredPolicy.None -> noPolicy(PolicyReason.NoPolicyConfigured)
        is StoredPolicy.Unreadable -> noPolicy(PolicyReason.PolicyUnreadable)
        is StoredPolicy.Policy -> evaluate(stored.policy, facts, spentToday)
    }

private fun actionCheck(policy: ConnectionPolicy, facts: RequestFacts): PolicyCheckResult {
    val allowed = policy.actions ?: return PolicyCheckResult.notConfigured(PolicyCheck.Action)
    val action =
        facts.action
            ?: return PolicyCheckResult.unverified(
                PolicyCheck.Action,
                PolicyReason.ActionUnverified,
                "an action this app has no name for",
            )
    return if (action in allowed) PolicyCheckResult.passed(PolicyCheck.Action, action.code)
    else PolicyCheckResult.failed(PolicyCheck.Action, PolicyReason.ActionNotAllowed, action.code)
}

private fun assetCheck(policy: ConnectionPolicy, facts: RequestFacts): PolicyCheckResult {
    val allowed = policy.assets ?: return PolicyCheckResult.notConfigured(PolicyCheck.Asset)
    if (!facts.movesValue) return PolicyCheckResult.passed(PolicyCheck.Asset, NOTHING_MOVES)
    val asset =
        facts.asset
            ?: return PolicyCheckResult.unverified(
                PolicyCheck.Asset,
                PolicyReason.AssetUnverified,
                "the asset couldn't be established",
            )
    val label = assetLabel(asset)
    return if (asset in allowed) PolicyCheckResult.passed(PolicyCheck.Asset, label)
    else PolicyCheckResult.failed(PolicyCheck.Asset, PolicyReason.AssetNotAllowed, label)
}

private fun recipientCheck(policy: ConnectionPolicy, facts: RequestFacts): PolicyCheckResult {
    val allowed = policy.recipients ?: return PolicyCheckResult.notConfigured(PolicyCheck.Recipient)
    if (!facts.movesValue) return PolicyCheckResult.passed(PolicyCheck.Recipient, NOTHING_MOVES)
    // For a token, an address the funds are sent to is not yet a wallet the funds reach: only a
    // transaction that has the chain vouch for the destination account establishes one (SAW-020).
    val recipient =
        facts.recipient
            ?: return PolicyCheckResult.unverified(
                PolicyCheck.Recipient,
                PolicyReason.RecipientUnverified,
                "the bytes don't establish who receives it",
            )
    return if (recipient in allowed) PolicyCheckResult.passed(PolicyCheck.Recipient, recipient)
    else
        PolicyCheckResult.failed(
            PolicyCheck.Recipient,
            PolicyReason.RecipientNotAllowed,
            recipient,
        )
}

private fun programCheck(policy: ConnectionPolicy, facts: RequestFacts): PolicyCheckResult {
    val allowed = policy.programs ?: return PolicyCheckResult.notConfigured(PolicyCheck.Program)
    if (!facts.movesValue) return PolicyCheckResult.passed(PolicyCheck.Program, NOTHING_MOVES)
    val programs =
        facts.programs
            ?: return PolicyCheckResult.unverified(
                PolicyCheck.Program,
                PolicyReason.ProgramUnverified,
                "the transaction couldn't be read",
            )
    val outside = programs.filterNot { it in allowed }
    return if (outside.isEmpty())
        PolicyCheckResult.passed(PolicyCheck.Program, programs.joinToString(", "))
    else
        PolicyCheckResult.failed(
            PolicyCheck.Program,
            PolicyReason.ProgramNotAllowed,
            outside.joinToString(", "),
        )
}

private fun perOperationCheck(policy: ConnectionPolicy, facts: RequestFacts): PolicyCheckResult {
    val check = PolicyCheck.PerOperationLimit
    val asset = facts.limitedAsset ?: return thresholdPreamble(policy, facts, check)
    val limit =
        policy.limitsFor(asset).perOperation ?: return PolicyCheckResult.notConfigured(check)
    val amount = facts.amount ?: return amountUnverified(check)
    val detail = "${facts.show(amount)} of ${facts.show(limit)}"
    // The threshold is the largest amount that still matches: a rule of one SOL allows one SOL.
    return if (amount <= limit) PolicyCheckResult.passed(check, detail)
    else PolicyCheckResult.failed(check, PolicyReason.OverPerOperationLimit, detail)
}

private fun dailyCheck(
    policy: ConnectionPolicy,
    facts: RequestFacts,
    spentToday: DailyTotal?,
): PolicyCheckResult {
    val check = PolicyCheck.DailyLimit
    val asset = facts.limitedAsset ?: return thresholdPreamble(policy, facts, check)
    val limit = policy.limitsFor(asset).daily ?: return PolicyCheckResult.notConfigured(check)
    val amount = facts.amount ?: return amountUnverified(check)
    val today =
        spentToday
            ?: return PolicyCheckResult.unverified(
                check,
                PolicyReason.DailyTotalUnverified,
                "today's total wasn't read",
            )
    // One unreadable record is enough: a total missing an amount reads as more room than there is.
    if (!today.known) {
        return PolicyCheckResult.unverified(
            check,
            PolicyReason.DailyTotalUnverified,
            "${today.unreadable} of today's records didn't read back",
        )
    }
    val projected = today.projected saturatingPlus amount
    // What the owner is told, and the whole of why: what the chain confirmed, what the wallet was
    // handed and never accounted for, and this request on top. The unresolved part is in the
    // warning because it may already be spent, and it is named separately because it may not be.
    val detail =
        "${facts.show(projected)} of ${facts.show(limit)} today — " +
            "${facts.show(today.confirmed)} confirmed, " +
            "${facts.show(today.unresolved)} not yet settled, " +
            "${facts.show(amount)} now"
    // Compared by how much room is left rather than by the sum, because a sum that saturated is
    // the largest amount there is, and the largest amount there is reads as under a threshold set
    // to it. The saturated number is fit to show and not to compare with.
    val within = today.projected <= limit && amount <= limit - today.projected
    return if (within) PolicyCheckResult.passed(check, detail)
    else PolicyCheckResult.failed(check, PolicyReason.OverDailyLimit, detail)
}

/**
 * The asset a threshold would be read for, or null when there isn't one to read: either nothing
 * moves, or the phone couldn't establish what does. [thresholdPreamble] says which.
 */
private val RequestFacts.limitedAsset: PolicyAsset?
    get() = if (movesValue) asset else null

private fun thresholdPreamble(
    policy: ConnectionPolicy,
    facts: RequestFacts,
    check: PolicyCheck,
): PolicyCheckResult {
    // A policy with no thresholds anywhere has configured no threshold check, whatever the request
    // turns out to be about.
    if (policy.limits.values.none { it.configuresSomething }) {
        return PolicyCheckResult.notConfigured(check)
    }
    if (!facts.movesValue) return PolicyCheckResult.passed(check, NOTHING_MOVES)
    // Thresholds are per asset, so an asset the phone couldn't establish leaves it unable to say
    // which threshold applies — not free of all of them.
    return PolicyCheckResult.unverified(
        check,
        PolicyReason.AssetUnverified,
        "the asset couldn't be established, so no threshold could be read",
    )
}

private fun amountUnverified(check: PolicyCheck): PolicyCheckResult =
    PolicyCheckResult.unverified(
        check,
        PolicyReason.AmountUnverified,
        "the amount couldn't be read",
    )

private fun effectiveActionCheck(
    rule: EffectiveRule<Allowlist<PolicyAction>>,
    facts: RequestFacts,
): PolicyCheckResult {
    val allowed =
        rule.value ?: return PolicyCheckResult.notConfigured(PolicyCheck.Action, rule.source)
    val action =
        facts.action
            ?: return PolicyCheckResult.unverified(
                PolicyCheck.Action,
                PolicyReason.ActionUnverified,
                "an action this app has no name for",
                rule.source,
            )
    return if (action in allowed) {
        PolicyCheckResult.passed(PolicyCheck.Action, action.code, rule.source)
    } else {
        PolicyCheckResult.failed(
            PolicyCheck.Action,
            PolicyReason.ActionNotAllowed,
            action.code,
            rule.source,
        )
    }
}

private fun effectiveAssetCheck(
    rule: EffectiveRule<Allowlist<PolicyAsset>>,
    facts: RequestFacts,
): PolicyCheckResult {
    val allowed =
        rule.value ?: return PolicyCheckResult.notConfigured(PolicyCheck.Asset, rule.source)
    if (!facts.movesValue) {
        return PolicyCheckResult.passed(PolicyCheck.Asset, NOTHING_MOVES, rule.source)
    }
    val asset =
        facts.asset
            ?: return PolicyCheckResult.unverified(
                PolicyCheck.Asset,
                PolicyReason.AssetUnverified,
                "the asset couldn't be established",
                rule.source,
            )
    val label = assetLabel(asset)
    return if (asset in allowed) {
        PolicyCheckResult.passed(PolicyCheck.Asset, label, rule.source)
    } else {
        PolicyCheckResult.failed(
            PolicyCheck.Asset,
            PolicyReason.AssetNotAllowed,
            label,
            rule.source,
        )
    }
}

private fun effectiveRecipientCheck(
    rule: EffectiveRule<Allowlist<String>>,
    facts: RequestFacts,
): PolicyCheckResult {
    val allowed =
        rule.value ?: return PolicyCheckResult.notConfigured(PolicyCheck.Recipient, rule.source)
    if (!facts.movesValue) {
        return PolicyCheckResult.passed(PolicyCheck.Recipient, NOTHING_MOVES, rule.source)
    }
    val recipient =
        facts.recipient
            ?: return PolicyCheckResult.unverified(
                PolicyCheck.Recipient,
                PolicyReason.RecipientUnverified,
                "the bytes don't establish who receives it",
                rule.source,
            )
    return if (recipient in allowed) {
        PolicyCheckResult.passed(PolicyCheck.Recipient, recipient, rule.source)
    } else {
        PolicyCheckResult.failed(
            PolicyCheck.Recipient,
            PolicyReason.RecipientNotAllowed,
            recipient,
            rule.source,
        )
    }
}

private fun effectiveProgramCheck(
    rule: EffectiveRule<Allowlist<String>>,
    facts: RequestFacts,
): PolicyCheckResult {
    val allowed =
        rule.value ?: return PolicyCheckResult.notConfigured(PolicyCheck.Program, rule.source)
    if (!facts.movesValue) {
        return PolicyCheckResult.passed(PolicyCheck.Program, NOTHING_MOVES, rule.source)
    }
    val programs =
        facts.programs
            ?: return PolicyCheckResult.unverified(
                PolicyCheck.Program,
                PolicyReason.ProgramUnverified,
                "the transaction couldn't be read",
                rule.source,
            )
    val outside = programs.filterNot { it in allowed }
    return if (outside.isEmpty()) {
        PolicyCheckResult.passed(PolicyCheck.Program, programs.joinToString(", "), rule.source)
    } else {
        PolicyCheckResult.failed(
            PolicyCheck.Program,
            PolicyReason.ProgramNotAllowed,
            outside.joinToString(", "),
            rule.source,
        )
    }
}

private fun effectivePerOperationCheck(
    policy: EffectivePolicy,
    facts: RequestFacts,
): PolicyCheckResult {
    val check = PolicyCheck.PerOperationLimit
    val asset =
        facts.limitedAsset
            ?: return effectiveThresholdPreamble(
                configured = policy.limits.values.any { it.perOperation.configured },
                facts = facts,
                check = check,
            )
    val rule = policy.limitsFor(asset).perOperation
    val limit = rule.value ?: return PolicyCheckResult.notConfigured(check, rule.source)
    val amount =
        facts.amount
            ?: return PolicyCheckResult.unverified(
                check,
                PolicyReason.AmountUnverified,
                "the amount couldn't be read",
                rule.source,
            )
    val detail = "${facts.show(amount)} of ${facts.show(limit)}"
    return if (amount <= limit) PolicyCheckResult.passed(check, detail, rule.source)
    else
        PolicyCheckResult.failed(
            check,
            PolicyReason.OverPerOperationLimit,
            detail,
            rule.source,
        )
}

private fun effectiveDailyCheck(
    scope: DailyCheckScope,
    policy: EffectivePolicy,
    facts: RequestFacts,
    total: DailyTotal?,
): DailyPolicyCheck {
    val configured =
        policy.limits.values.any {
            when (scope) {
                DailyCheckScope.Global -> it.globalDaily.configured
                DailyCheckScope.Connection -> it.connectionDaily.configured
            }
        }
    val asset = facts.limitedAsset
    if (asset == null) {
        return DailyPolicyCheck(
            scope = scope,
            result = effectiveThresholdPreamble(configured, facts, PolicyCheck.DailyLimit),
            limit = null,
            total = total.takeIf { configured },
            currentAmount = facts.amount,
        )
    }
    val rule =
        when (scope) {
            DailyCheckScope.Global -> policy.limitsFor(asset).globalDaily
            DailyCheckScope.Connection -> policy.limitsFor(asset).connectionDaily
        }
    val limit = rule.value
    if (limit == null) {
        return DailyPolicyCheck(
            scope,
            PolicyCheckResult.notConfigured(PolicyCheck.DailyLimit, rule.source),
            null,
            null,
            facts.amount,
        )
    }
    val amount = facts.amount
    if (amount == null) {
        return DailyPolicyCheck(
            scope,
            PolicyCheckResult.unverified(
                PolicyCheck.DailyLimit,
                PolicyReason.AmountUnverified,
                "the amount couldn't be read",
                rule.source,
            ),
            limit,
            total,
            null,
        )
    }
    if (total == null) {
        return DailyPolicyCheck(
            scope,
            PolicyCheckResult.unverified(
                PolicyCheck.DailyLimit,
                PolicyReason.DailyTotalUnverified,
                "today's ${scope.code} total wasn't read",
                rule.source,
            ),
            limit,
            null,
            amount,
        )
    }
    if (!total.known) {
        return DailyPolicyCheck(
            scope,
            PolicyCheckResult.unverified(
                PolicyCheck.DailyLimit,
                PolicyReason.DailyTotalUnverified,
                "${total.unreadable} of today's records didn't read back",
                rule.source,
            ),
            limit,
            total,
            amount,
        )
    }
    val projected = total.projected saturatingPlus amount
    val detail =
        "${facts.show(projected)} of ${facts.show(limit)} today — " +
            "${facts.show(total.confirmed)} confirmed, " +
            "${facts.show(total.unresolved)} not yet settled, " +
            "${facts.show(amount)} now"
    val within = total.projected <= limit && amount <= limit - total.projected
    val result =
        if (within) PolicyCheckResult.passed(PolicyCheck.DailyLimit, detail, rule.source)
        else
            PolicyCheckResult.failed(
                PolicyCheck.DailyLimit,
                PolicyReason.OverDailyLimit,
                detail,
                rule.source,
            )
    return DailyPolicyCheck(scope, result, limit, total, amount)
}

private fun effectiveThresholdPreamble(
    configured: Boolean,
    facts: RequestFacts,
    check: PolicyCheck,
): PolicyCheckResult {
    if (!configured) return PolicyCheckResult.notConfigured(check)
    if (!facts.movesValue) return PolicyCheckResult.passed(check, NOTHING_MOVES)
    return PolicyCheckResult.unverified(
        check,
        PolicyReason.AssetUnverified,
        "the asset couldn't be established, so no threshold could be read",
    )
}

/** One Stage 5-compatible row whose status is the conjunction of both scoped daily rows. */
private fun combinedDailyCheck(daily: List<DailyPolicyCheck>): PolicyCheckResult {
    val ran = daily.map { it.result }.filter { it.status != PolicyCheckStatus.NotConfigured }
    if (ran.isEmpty()) return PolicyCheckResult.notConfigured(PolicyCheck.DailyLimit)
    val chosen =
        ran.firstOrNull { it.status == PolicyCheckStatus.Failed }
            ?: ran.firstOrNull { it.status == PolicyCheckStatus.Unverified }
            ?: ran.first()
    return chosen.copy(source = if (ran.size == 1) chosen.source else RuleSource.NotConfigured)
}

/** An amount with its decimal point, in the terms the owner reviewed it in. */
private fun RequestFacts.show(amount: ULong): String = formatBaseUnits(amount, decimals)

/** An asset in the owner's terms: the mint, or SOL, and the chain it is on. */
fun assetLabel(asset: PolicyAsset): String =
    "${asset.mint ?: "SOL"} on ${asset.network.name.removePrefix("NETWORK_").lowercase()}"

/**
 * The phone's assessments, made from what is stored right now.
 *
 * Nothing is cached and nothing is precomputed: every call re-reads the connection's rules and the
 * app's own records, so asking again immediately before the owner proceeds is the whole of
 * re-evaluating (docs/policy.md#re-evaluation). A verdict read a minute ago is never the one acted
 * on, because there is no stored verdict to act on.
 *
 * It reads. It writes nothing, answers nothing, and reaches no wallet and no server.
 */
class PolicyEvaluator(
    private val policies: PolicyStore,
    /**
     * The owner's own records, read afresh each time: the counters are what this app did.
     *
     * **Null when the history isn't known** — it hasn't been read off the disk yet, or reading it
     * failed. That is not the same as a day with nothing in it, and the difference decides whether
     * a daily threshold can be applied at all. An empty list here means the app looked and there
     * was nothing; null means it couldn't look, and a threshold measured against a total nobody
     * read would report the day as empty and pass anything (docs/policy.md#counters).
     */
    private val records: () -> List<ActivityRecord>?,
    private val now: () -> Instant = Instant::now,
    /** The phone's time zone, read at the moment of the assessment. */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** Activity files found but not decoded by the last complete history read. */
    private val unreadableRecords: () -> Int = { 0 },
) {
    /** The local day the phone is in. */
    fun today(): LocalDate = now().atZone(zone()).toLocalDate()

    /** What [scope] has moved today, or null when the history isn't known. */
    fun spentToday(scope: DailySpendScope): DailyTotal? {
        val history = records() ?: return null
        val assessedZone = zone()
        return dailyTotal(
            spends = spendsOf(history),
            scope = scope,
            day = now().atZone(assessedZone).toLocalDate(),
            zone = assessedZone,
            unreadableHistory = unreadableRecords(),
        )
    }

    /** The verdict on [facts], under the rules and the records as they stand now. */
    fun evaluate(facts: RequestFacts): PolicyDecision {
        // Both documents are read on every evaluation. Missing means inheritance; unreadable means
        // the effective policy cannot be established and must never fall back to a passing subset.
        val storedGlobal = policies.getGlobal()
        val storedConnection = policies.getOverrides(facts.connectionId)
        if (
            storedGlobal is StoredGlobalPolicy.Unreadable ||
                storedConnection is StoredConnectionOverrides.Unreadable
        ) {
            return noPolicy(PolicyReason.PolicyUnreadable)
        }
        val global = (storedGlobal as? StoredGlobalPolicy.Policy)?.policy
        val connection = (storedConnection as? StoredConnectionOverrides.Policy)?.overrides
        val effective = resolveEffectivePolicy(facts.connectionId, global, connection)

        val localScope = facts.scope
        val limits = localScope?.let { effective.limitsFor(it.asset) }
        val needsHistory =
            limits?.globalDaily?.configured == true || limits?.connectionDaily?.configured == true
        val history = if (needsHistory) records() else null
        val totals =
            if (localScope == null || history == null) {
                DailyTotals(null, null)
            } else {
                // One history snapshot, clock read, and zone read feed both scopes. Activity on a
                // removed connection remains in this all-records list and therefore in global.
                val assessedZone = zone()
                val day = now().atZone(assessedZone).toLocalDate()
                val spends = spendsOf(history)
                val excluding = facts.requestId?.let { SpendRequest(facts.connectionId, it) }
                val unreadable = unreadableRecords()
                DailyTotals(
                    global =
                        dailyTotal(
                            spends,
                            GlobalSpendScope(localScope.wallet, localScope.asset),
                            day,
                            assessedZone,
                            excluding,
                            unreadable,
                        ),
                    connection =
                        dailyTotal(
                            spends,
                            localScope,
                            day,
                            assessedZone,
                            excluding,
                            unreadable,
                        ),
                )
            }
        return evaluate(effective, facts, totals)
    }
}
