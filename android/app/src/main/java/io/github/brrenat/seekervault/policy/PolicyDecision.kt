package io.github.brrenat.seekervault.policy

/**
 * How a policy reaches a verdict (docs/policy.md#evaluation-semantics).
 *
 * The whole procedure is one conjunction of the checks the owner configured. There is no scripting
 * language, no expression tree, and no order of precedence to learn: a check either passes or it
 * doesn't, every configured check has to pass, and a check that isn't configured doesn't run.
 *
 * Two things it deliberately cannot do:
 * - **It cannot approve.** ALLOWED means the parameters matched the rules. The owner still approves
 *   by hand, in the app and again in their wallet.
 * - **It cannot block.** There is no BLOCKED. A request outside the rules is shown with its reasons
 *   and left to the owner, who may go ahead anyway. What stops a request is input validation —
 *   bytes that disagree with the request (SAW-020) — and that is a different thing entirely, judged
 *   before any policy is consulted and never softened by one.
 */

/** The policy's verdict. */
enum class PolicyAssessment(val code: String) {
    /** Every configured check ran, and every one of them matched. */
    Allowed("allowed"),
    /** Something is outside the rules, or couldn't be checked against them. */
    UnderRestrictions("under_restrictions"),
}

/** One thing a policy can check. The order here is the order the reasons are reported in. */
enum class PolicyCheck(val code: String) {
    Action("action"),
    Asset("asset"),
    Recipient("recipient"),
    Program("program"),
    PerOperationLimit("per_operation_limit"),
    DailyLimit("daily_limit"),
}

/** What one check found. */
enum class PolicyCheckStatus(val code: String) {
    /** Configured, and the request matched it. */
    Passed("passed"),
    /** Configured, and the request is outside it. */
    Failed("failed"),
    /**
     * Configured, but the phone couldn't establish the fact it needs — an instruction it can't
     * read, an amount it can't account for. It does not pass. Coverage is not compliance.
     */
    Unverified("unverified"),
    /** The owner configured no such check, so nothing was checked and nothing is claimed. */
    NotConfigured("not_configured"),
}

/**
 * Why a request isn't ALLOWED. Each carries a stable code: the code is what goes into a stored
 * assessment and into `PolicyEvaluation.reasons` (docs/protocol.md), and the screen turns it into
 * the owner's language rather than storing that language.
 */
enum class PolicyReason(val code: String) {
    /** This connection has no rules, so there was nothing to match. */
    NoPolicyConfigured("no_policy_configured"),
    /**
     * The stored rules couldn't be read. Not the same as having none: the owner set rules, and this
     * build can't tell what they are, so nothing about this request has been checked.
     */
    PolicyUnreadable("policy_unreadable"),
    ActionNotAllowed("action_not_allowed"),
    AssetNotAllowed("asset_not_allowed"),
    RecipientNotAllowed("recipient_not_allowed"),
    ProgramNotAllowed("program_not_allowed"),
    OverPerOperationLimit("over_per_operation_limit"),
    OverDailyLimit("over_daily_limit"),
    /** The phone couldn't read what the action does, so the rule couldn't be applied to it. */
    ActionUnverified("action_unverified"),
    AssetUnverified("asset_unverified"),
    /** The bytes don't establish who receives the funds, so no recipient rule can be applied. */
    RecipientUnverified("recipient_unverified"),
    /**
     * The transaction calls something the phone couldn't read, so the programs aren't all known.
     */
    ProgramUnverified("program_unverified"),
    AmountUnverified("amount_unverified"),
    /**
     * The day's total isn't known — a record was unreadable — so the daily rule can't be applied.
     */
    DailyTotalUnverified("daily_total_unverified"),
    /**
     * The phone couldn't account for the whole transaction, so the assessment is withheld whatever
     * the rules made of the part it did read (SAW-026). This is not a check's reason: it is the
     * reason there is no verdict, and it outranks a match.
     */
    RequestUnverified("request_unverified"),
}

/** One check's result, with what it read, for the screen to show beside the verdict. */
data class PolicyCheckResult(
    val check: PolicyCheck,
    val status: PolicyCheckStatus,
    /** Why it failed, or what it couldn't verify. Null when it passed or wasn't configured. */
    val reason: PolicyReason? = null,
    /** What the check read, in the owner's terms. Display only; never parsed. */
    val detail: String? = null,
    /** The effective document that supplied the rule; legacy flat decisions have no source. */
    val source: RuleSource = RuleSource.NotConfigured,
) {
    init {
        val needsReason =
            status == PolicyCheckStatus.Failed || status == PolicyCheckStatus.Unverified
        require(needsReason == (reason != null)) {
            "$status must ${if (needsReason) "carry" else "carry no"} a reason"
        }
    }

    companion object {
        fun passed(
            check: PolicyCheck,
            detail: String? = null,
            source: RuleSource = RuleSource.NotConfigured,
        ) = PolicyCheckResult(check, PolicyCheckStatus.Passed, detail = detail, source = source)

        fun failed(
            check: PolicyCheck,
            reason: PolicyReason,
            detail: String? = null,
            source: RuleSource = RuleSource.NotConfigured,
        ) = PolicyCheckResult(check, PolicyCheckStatus.Failed, reason, detail, source)

        fun unverified(
            check: PolicyCheck,
            reason: PolicyReason,
            detail: String? = null,
            source: RuleSource = RuleSource.NotConfigured,
        ) = PolicyCheckResult(check, PolicyCheckStatus.Unverified, reason, detail, source)

        fun notConfigured(
            check: PolicyCheck,
            source: RuleSource = RuleSource.NotConfigured,
        ) = PolicyCheckResult(check, PolicyCheckStatus.NotConfigured, source = source)
    }
}

/** Which independently enforced daily threshold one result describes. */
enum class DailyCheckScope(val code: String) {
    Global("global"),
    Connection("connection"),
}

/**
 * One daily result with its scope and typed inputs retained for review. Both entries stay in a
 * decision even when the first one already failed; the two rules are independent and advisory.
 */
data class DailyPolicyCheck(
    val scope: DailyCheckScope,
    val result: PolicyCheckResult,
    val limit: ULong?,
    val total: DailyTotal?,
    val currentAmount: ULong?,
) {
    init {
        require(result.check == PolicyCheck.DailyLimit) { "not a daily check" }
    }

    val projected: ULong?
        get() = total?.projected?.let { before -> currentAmount?.let { before saturatingPlus it } }
}

/**
 * What the policy made of one request: the verdict, and every check behind it.
 *
 * It holds no decision about what happens next. Nothing here approves, rejects, invokes a wallet,
 * or changes a request's state — the assessment is something the owner reads, and the reading is
 * the whole of its effect.
 */
data class PolicyDecision(
    val assessment: PolicyAssessment,
    /** Every check, one entry each, in [PolicyCheck] order. */
    val checks: List<PolicyCheckResult>,
    /** Set when there were no rules to apply at all. */
    val reason: PolicyReason? = null,
    /** Global then connection daily results, when this came from an effective Stage 5.1 policy. */
    val dailyChecks: List<DailyPolicyCheck> = emptyList(),
    /** Stored documents this build could not read, in global then connection order. */
    val unreadableSources: List<RuleSource> = emptyList(),
) {
    init {
        require(
            unreadableSources.all {
                it == RuleSource.Global || it == RuleSource.ConnectionOverride
            }
        ) {
            "only stored rule documents can be unreadable"
        }
        require(unreadableSources.distinct() == unreadableSources) {
            "an unreadable rule document is named once"
        }
    }

    val allowed: Boolean
        get() = assessment == PolicyAssessment.Allowed

    /**
     * Whether this assessment has something to warn the owner about, which is what the review
     * screen asks them to go past on purpose (SAW-028).
     *
     * UNDER_RESTRICTIONS for want of any rules is not a warning. Nothing was checked because
     * nothing was configured, and a phone whose owner has written no rules would otherwise warn
     * about every request it ever shows — which is the surest way to teach someone to tick past a
     * warning without reading it. Rules that are stored and can't be read are the other way round:
     * the owner did write something, and this build can't tell them what.
     */
    val warns: Boolean
        get() =
            assessment == PolicyAssessment.UnderRestrictions &&
                reason != PolicyReason.NoPolicyConfigured

    /** Every reason behind the verdict, in check order, with [reason] first when there is one. */
    val reasons: List<PolicyReason>
        get() =
            listOfNotNull(reason) +
                checks
                    .filterNot { dailyChecks.isNotEmpty() && it.check == PolicyCheck.DailyLimit }
                    .mapNotNull { it.reason } +
                dailyChecks.mapNotNull { it.result.reason }

    /** The codes of [reasons]: what a stored or displayed assessment carries. */
    val reasonCodes: List<String>
        get() = reasons.map { it.code }

    /**
     * The checks that didn't run because the owner configured none. This is coverage, not a
     * verdict: it says what the assessment does *not* cover, so ALLOWED is never read as a
     * statement about a parameter nobody wrote a rule for.
     */
    val notChecked: List<PolicyCheck>
        get() = checks.filter { it.status == PolicyCheckStatus.NotConfigured }.map { it.check }

    /** The checks that were configured but couldn't be applied to what the phone could read. */
    val unverified: List<PolicyCheck>
        get() = checks.filter { it.status == PolicyCheckStatus.Unverified }.map { it.check }

    /**
     * Whether this assessment covers every check the policy model has. A partial assessment is
     * still an assessment; it just says less, and the review says so.
     */
    val coversEveryCheck: Boolean
        get() = notChecked.isEmpty()
}

/**
 * The conjunction: [checks] produce ALLOWED only if at least one of them was configured and every
 * configured one passed. A failure and an unverified check count the same here — both mean the rule
 * wasn't matched — and both carry their own reason so the difference stays visible to the owner.
 *
 * [checks] must name each [PolicyCheck] exactly once, so that a check can never be left out of an
 * assessment by being left out of the list.
 */
fun assess(
    checks: List<PolicyCheckResult>,
    dailyChecks: List<DailyPolicyCheck> = emptyList(),
): PolicyDecision {
    require(checks.map { it.check } == PolicyCheck.entries.toList()) {
        "every check is assessed, once, in order"
    }
    require(
        dailyChecks.isEmpty() ||
            dailyChecks.map { it.scope } ==
                listOf(DailyCheckScope.Global, DailyCheckScope.Connection)
    ) {
        "daily checks are retained once each, global then connection"
    }
    val ran = checks.filter { it.status != PolicyCheckStatus.NotConfigured }
    // Nothing configured is not a match. A policy that asks nothing of a request has said nothing
    // about it, and saying nothing must never read as approval.
    if (ran.isEmpty()) {
        return PolicyDecision(
            assessment = PolicyAssessment.UnderRestrictions,
            checks = checks,
            reason = PolicyReason.NoPolicyConfigured,
            dailyChecks = dailyChecks,
        )
    }
    val assessment =
        if (ran.all { it.status == PolicyCheckStatus.Passed }) PolicyAssessment.Allowed
        else PolicyAssessment.UnderRestrictions
    return PolicyDecision(assessment, checks, dailyChecks = dailyChecks)
}

/**
 * The assessment for a connection with no rules to apply: [PolicyReason.NoPolicyConfigured] when
 * the owner has configured none, [PolicyReason.PolicyUnreadable] when rules are stored and this
 * build couldn't read them.
 *
 * Both are UNDER_RESTRICTIONS, and for the same reason: an assessment that checked nothing is not a
 * safe one, and the owner is told which of the two it was rather than being shown a blank.
 */
fun noPolicy(
    reason: PolicyReason,
    unreadableSources: List<RuleSource> = emptyList(),
): PolicyDecision {
    require(reason == PolicyReason.NoPolicyConfigured || reason == PolicyReason.PolicyUnreadable) {
        "not a reason for having no rules to apply"
    }
    return PolicyDecision(
        assessment = PolicyAssessment.UnderRestrictions,
        checks = PolicyCheck.entries.map(PolicyCheckResult::notConfigured),
        reason = reason,
        unreadableSources = unreadableSources,
    )
}
