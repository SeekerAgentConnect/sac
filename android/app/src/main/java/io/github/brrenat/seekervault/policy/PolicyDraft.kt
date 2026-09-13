package io.github.brrenat.seekervault.policy

import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.transactions.LAMPORT_DECIMALS
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import java.time.Instant

/**
 * The rules as the owner is typing them (docs/guides/policies.md).
 *
 * A draft is a form, not a policy: it holds text the owner is still editing, including text that
 * isn't a number yet. [review] is the one place it turns into a [ConnectionPolicy], and it turns
 * into one only when the store would accept it — so the editor can never save a document that
 * `policyProblems` refuses, and can never quietly drop a rule while saving.
 *
 * Each list has a switch of its own because **absent is not empty** (docs/policy.md): a switch that
 * is off configures no check at all, and a switch that is on with an empty list allows nothing.
 * Those are different rules and this is where the difference is kept.
 */
data class PolicyDraft(
    val connectionId: String,
    /** Whether this connection's requests are checked against [actions] at all. */
    val restrictActions: Boolean = false,
    val actions: Set<PolicyAction> = emptySet(),
    /** Whether [assets] is an allowlist. It holds the thresholds either way. */
    val restrictAssets: Boolean = false,
    /**
     * The assets the rules are about, each with its own thresholds. Thresholds live on the asset
     * they are about, so a threshold for an asset the list doesn't allow — `LimitForUnlistedAsset`
     * — is not something the owner can write here rather than something they're told off for.
     */
    val assets: List<AssetDraft> = emptyList(),
    val restrictRecipients: Boolean = false,
    val recipients: List<String> = emptyList(),
    val restrictPrograms: Boolean = false,
    val programs: List<String> = emptyList(),
)

/** One asset in the draft, with the two thresholds as the owner typed them. */
data class AssetDraft(
    val network: Network,
    /** The token's mint, or null for native SOL. */
    val mint: String? = null,
    /** The most one request may move. Blank: no threshold, which is not a threshold of zero. */
    val perOperation: String = "",
    /** The most this app may move in a day. Blank: no threshold. */
    val daily: String = "",
) {
    val asset: PolicyAsset
        get() = PolicyAsset(network, mint)

    /**
     * How many decimal places an amount of this asset is typed with.
     *
     * SOL has nine, which this app knows for certain. A mint's decimal count is not something the
     * phone can establish without a transaction that carries it, so a token's thresholds are typed
     * in that mint's own base units rather than against a decimal count this app guessed: a guess
     * three places out is a threshold out by a factor of a thousand.
     */
    val decimals: Int
        get() = if (mint == null) LAMPORT_DECIMALS else 0
}

/** The chains a rule can name. An asset with no chain names nothing to spend. */
val POLICY_NETWORKS =
    listOf(Network.NETWORK_MAINNET, Network.NETWORK_DEVNET, Network.NETWORK_TESTNET)

/** Why a typed threshold isn't an amount. */
enum class AmountProblem {
    /** Not a plain decimal number. No sign, no exponent, and no digit grouping. */
    NotANumber,
    /** More decimal places than the asset has, so the amount couldn't be moved exactly. */
    TooPrecise,
    /** Larger than the largest amount a transfer can carry. */
    TooLarge,
    /** A threshold of zero. A rule that permits nothing is an empty list, not a zero. */
    Zero,
}

/** What one threshold field holds. */
sealed interface AmountEntry {
    /** Blank: no threshold configured. */
    data object None : AmountEntry

    data class Amount(val baseUnits: ULong) : AmountEntry

    data class Problem(val why: AmountProblem) : AmountEntry
}

// A plain decimal number and nothing else: "1", "1.5", ".5", "1." — no sign, no exponent, no
// separators, and nothing a locale would read differently.
private val AMOUNT = Regex("""\d+(\.\d*)?|\.\d+""")

/**
 * Reads one threshold field, typed with [decimals] decimal places, into the base units the rule is
 * stored and compared in. The conversion is exact: digits are shifted, never multiplied, so no
 * floating-point type ever comes between what the owner typed and what the chain will see.
 */
fun readAmount(text: String, decimals: Int): AmountEntry {
    val typed = text.trim()
    if (typed.isEmpty()) return AmountEntry.None
    if (!AMOUNT.matches(typed)) return AmountEntry.Problem(AmountProblem.NotANumber)
    val whole = typed.substringBefore('.')
    val fraction = typed.substringAfter('.', "")
    if (fraction.length > decimals) return AmountEntry.Problem(AmountProblem.TooPrecise)
    val digits = (whole + fraction.padEnd(decimals, '0')).trimStart('0').ifEmpty { "0" }
    val amount = digits.toULongOrNull() ?: return AmountEntry.Problem(AmountProblem.TooLarge)
    if (amount == 0UL) return AmountEntry.Problem(AmountProblem.Zero)
    return AmountEntry.Amount(amount)
}

/** What is wrong with one asset's thresholds, as they are being typed. */
data class AssetProblems(
    val perOperation: AmountProblem? = null,
    val daily: AmountProblem? = null,
    /** The daily threshold is below the per-request one, so the per-request one can never bind. */
    val dailyBelowPerOperation: Boolean = false,
) {
    val none: Boolean
        get() = perOperation == null && daily == null && !dailyBelowPerOperation
}

fun problemsOf(asset: AssetDraft): AssetProblems {
    val perOperation = readAmount(asset.perOperation, asset.decimals)
    val daily = readAmount(asset.daily, asset.decimals)
    return AssetProblems(
        perOperation = (perOperation as? AmountEntry.Problem)?.why,
        daily = (daily as? AmountEntry.Problem)?.why,
        dailyBelowPerOperation =
            perOperation is AmountEntry.Amount &&
                daily is AmountEntry.Amount &&
                daily.baseUnits < perOperation.baseUnits,
    )
}

/** What the editor makes of the whole draft. */
sealed interface DraftReview {
    /** Fit to store, exactly as it stands. */
    data class Ready(val policy: ConnectionPolicy) : DraftReview

    /**
     * The draft configures no check at all. Saving it removes this connection's rules rather than
     * storing a document that says nothing: a policy that configures nothing and no policy at all
     * are assessed identically (docs/policy.md#defaults), so there is nothing to keep.
     */
    data object NoRules : DraftReview

    /** Not fit to store yet. */
    data class Problems(
        /** By the asset's position in [PolicyDraft.assets]. */
        val assets: Map<Int, AssetProblems>,
        /**
         * What the store would refuse. The editor doesn't let the owner type any of these, so a
         * non-empty list here is a draft that was built rather than typed.
         */
        val policy: List<PolicyProblem>,
    ) : DraftReview
}

/** What saving this draft at [at] would do. */
fun PolicyDraft.review(at: Instant): DraftReview {
    val problems =
        assets
            .mapIndexed { index, asset -> index to problemsOf(asset) }
            .filterNot { it.second.none }
            .toMap()
    if (problems.isNotEmpty()) return DraftReview.Problems(problems, emptyList())
    val policy = policyOf(at)
    val refused = policyProblems(policy)
    if (refused.isNotEmpty()) return DraftReview.Problems(emptyMap(), refused)
    if (policy.configuresNothing) return DraftReview.NoRules
    return DraftReview.Ready(policy)
}

/**
 * The draft this policy was saved from, so the editor reopens showing what is stored. An asset the
 * owner listed appears before one that only carries a threshold, and both keep the order they were
 * stored in, so reopening and saving again writes the same document back.
 */
fun draftOf(connectionId: String, policy: ConnectionPolicy?): PolicyDraft {
    if (policy == null) return PolicyDraft(connectionId)
    val listed = policy.assets?.values.orEmpty().toList()
    val assets =
        (listed + policy.limits.keys.filterNot { it in listed }).map { asset ->
            val limits = policy.limitsFor(asset)
            val decimals = AssetDraft(asset.network, asset.mint).decimals
            AssetDraft(
                network = asset.network,
                mint = asset.mint,
                perOperation = limits.perOperation?.let { formatBaseUnits(it, decimals) }.orEmpty(),
                daily = limits.daily?.let { formatBaseUnits(it, decimals) }.orEmpty(),
            )
        }
    return PolicyDraft(
        connectionId = connectionId,
        restrictActions = policy.actions != null,
        actions = policy.actions?.values.orEmpty(),
        restrictAssets = policy.assets != null,
        assets = assets,
        restrictRecipients = policy.recipients != null,
        recipients = policy.recipients?.values.orEmpty().toList(),
        restrictPrograms = policy.programs != null,
        programs = policy.programs?.values.orEmpty().toList(),
    )
}

/**
 * The policy this draft stands for. A list is written only when its switch is on, and an asset
 * carries a limit only when a threshold was typed for it, so an asset the owner listed without a
 * threshold restricts what may move and says nothing about how much.
 */
private fun PolicyDraft.policyOf(at: Instant): ConnectionPolicy {
    val limits = mutableMapOf<PolicyAsset, AssetLimits>()
    for (asset in assets) {
        val perOperation = readAmount(asset.perOperation, asset.decimals).baseUnits
        val daily = readAmount(asset.daily, asset.decimals).baseUnits
        if (perOperation != null || daily != null) {
            limits[asset.asset] = AssetLimits(perOperation, daily)
        }
    }
    return ConnectionPolicy(
        connectionId = connectionId,
        actions = if (restrictActions) Allowlist(actions) else null,
        assets = if (restrictAssets) Allowlist(assets.map { it.asset }.toSet()) else null,
        recipients = if (restrictRecipients) Allowlist(recipients.toSet()) else null,
        programs = if (restrictPrograms) Allowlist(programs.toSet()) else null,
        limits = limits,
        updatedAt = at,
    )
}

private val AmountEntry.baseUnits: ULong?
    get() = (this as? AmountEntry.Amount)?.baseUnits

/** A valid, phone-local ID used only while the global form reuses [PolicyDraft]'s validation. */
const val GLOBAL_DRAFT_ID = "00000000-0000-4000-8000-000000000000"

/** The global document as the existing rules form expects it. */
fun globalDraftOf(policy: GlobalPolicy?): PolicyDraft =
    draftOf(
        GLOBAL_DRAFT_ID,
        policy?.let {
            ConnectionPolicy(
                connectionId = GLOBAL_DRAFT_ID,
                actions = it.actions,
                assets = it.assets,
                recipients = it.recipients,
                programs = it.programs,
                limits = it.limits,
                updatedAt = it.updatedAt,
            )
        },
    )

/** What one connection is editing for one asset, independently of its asset allowlist. */
data class ConnectionAssetDraft(
    val network: Network,
    val mint: String? = null,
    /** False inherits the global per-request threshold. True replaces it, or clears it if blank. */
    val overridePerOperation: Boolean = false,
    val perOperation: String = "",
    /** A separate connection daily threshold. Blank removes only this local threshold. */
    val daily: String = "",
) {
    val asset: PolicyAsset
        get() = PolicyAsset(network, mint)

    val decimals: Int
        get() = if (mint == null) LAMPORT_DECIMALS else 0

    val configuresSomething: Boolean
        get() = overridePerOperation || daily.isNotBlank()

    fun asAssetDraft() = AssetDraft(network, mint, perOperation, daily)
}

/**
 * One connection's form. Each allowlist has an outer inheritance choice and its familiar inner
 * switch. With Override selected, an off switch is an explicit no-check and an on empty list is an
 * explicit list that allows nothing. Those three states must never collapse into one another.
 */
data class ConnectionPolicyDraft(
    val connectionId: String,
    val overrideActions: Boolean = false,
    val restrictActions: Boolean = false,
    val actions: Set<PolicyAction> = emptySet(),
    val overrideAssets: Boolean = false,
    val restrictAssets: Boolean = false,
    /** Values in the connection replacement, not values inherited from the global document. */
    val assets: List<PolicyAsset> = emptyList(),
    val overrideRecipients: Boolean = false,
    val restrictRecipients: Boolean = false,
    val recipients: List<String> = emptyList(),
    val overridePrograms: Boolean = false,
    val restrictPrograms: Boolean = false,
    val programs: List<String> = emptyList(),
    /** Local threshold choices. Global context is held separately in [PolicyUiState]. */
    val limits: List<ConnectionAssetDraft> = emptyList(),
)

/** What the connection form makes of the current draft. */
sealed interface ConnectionDraftReview {
    data class Ready(val overrides: ConnectionPolicyOverrides) : ConnectionDraftReview

    /** Every section and threshold inherits, so saving deletes the override document only. */
    data object InheritAll : ConnectionDraftReview

    data class Problems(
        val assets: Map<Int, AssetProblems>,
        val policy: List<PolicyProblem>,
    ) : ConnectionDraftReview
}

/** Reopens exactly the local choices in [overrides], without copying inherited values into them. */
fun connectionDraftOf(
    connectionId: String,
    overrides: ConnectionPolicyOverrides?,
): ConnectionPolicyDraft {
    if (overrides == null) return ConnectionPolicyDraft(connectionId)
    return ConnectionPolicyDraft(
        connectionId = connectionId,
        overrideActions = overrides.actions != RuleOverride.Inherit,
        restrictActions = overrides.actions is RuleOverride.Replace,
        actions = overrides.actions.replacement?.values.orEmpty(),
        overrideAssets = overrides.assets != RuleOverride.Inherit,
        restrictAssets = overrides.assets is RuleOverride.Replace,
        assets = overrides.assets.replacement?.values.orEmpty().toList(),
        overrideRecipients = overrides.recipients != RuleOverride.Inherit,
        restrictRecipients = overrides.recipients is RuleOverride.Replace,
        recipients = overrides.recipients.replacement?.values.orEmpty().toList(),
        overridePrograms = overrides.programs != RuleOverride.Inherit,
        restrictPrograms = overrides.programs is RuleOverride.Replace,
        programs = overrides.programs.replacement?.values.orEmpty().toList(),
        limits =
            overrides.limits.map { (asset, value) ->
                val replacement = value.perOperation.replacement
                ConnectionAssetDraft(
                    network = asset.network,
                    mint = asset.mint,
                    overridePerOperation = value.perOperation != RuleOverride.Inherit,
                    perOperation =
                        replacement
                            ?.let {
                                formatBaseUnits(
                                    it,
                                    if (asset.mint == null) LAMPORT_DECIMALS else 0,
                                )
                            }
                            .orEmpty(),
                    daily =
                        value.daily
                            ?.let {
                                formatBaseUnits(
                                    it,
                                    if (asset.mint == null) LAMPORT_DECIMALS else 0,
                                )
                            }
                            .orEmpty(),
                )
            },
    )
}

/** Validates and materializes one connection override form without consulting inherited values. */
fun ConnectionPolicyDraft.review(at: Instant): ConnectionDraftReview {
    val assetProblems =
        limits
            .mapIndexed { index, asset ->
                val base = problemsOf(asset.asAssetDraft())
                index to
                    base.copy(
                        perOperation = if (asset.overridePerOperation) base.perOperation else null,
                        dailyBelowPerOperation =
                            asset.overridePerOperation && base.dailyBelowPerOperation,
                    )
            }
            .filterNot { it.second.none }
            .toMap()
    if (assetProblems.isNotEmpty()) {
        return ConnectionDraftReview.Problems(assetProblems, emptyList())
    }
    val overrides =
        ConnectionPolicyOverrides(
            connectionId = connectionId,
            actions = allowlistOverride(overrideActions, restrictActions, actions),
            assets = allowlistOverride(overrideAssets, restrictAssets, assets.toSet()),
            recipients =
                allowlistOverride(overrideRecipients, restrictRecipients, recipients.toSet()),
            programs = allowlistOverride(overridePrograms, restrictPrograms, programs.toSet()),
            limits =
                limits
                    .filter { it.configuresSomething }
                    .associate { draft ->
                        val perOperation =
                            if (!draft.overridePerOperation) {
                                RuleOverride.Inherit
                            } else {
                                when (val amount = readAmount(draft.perOperation, draft.decimals)) {
                                    AmountEntry.None -> RuleOverride.NoCheck
                                    is AmountEntry.Amount -> RuleOverride.Replace(amount.baseUnits)
                                    is AmountEntry.Problem ->
                                        error("validated amount became invalid")
                                }
                            }
                        val daily =
                            (readAmount(draft.daily, draft.decimals) as? AmountEntry.Amount)
                                ?.baseUnits
                        draft.asset to ConnectionAssetLimits(perOperation, daily)
                    },
            updatedAt = at,
        )
    val policyProblems = policyProblems(overrides)
    if (policyProblems.isNotEmpty()) {
        return ConnectionDraftReview.Problems(emptyMap(), policyProblems)
    }
    return if (overrides.hasNoOverrides) ConnectionDraftReview.InheritAll
    else ConnectionDraftReview.Ready(overrides)
}

private fun <T : Any> allowlistOverride(
    overrides: Boolean,
    restricts: Boolean,
    values: Set<T>,
): RuleOverride<Allowlist<T>> =
    when {
        !overrides -> RuleOverride.Inherit
        !restricts -> RuleOverride.NoCheck
        else -> RuleOverride.Replace(Allowlist(values))
    }
