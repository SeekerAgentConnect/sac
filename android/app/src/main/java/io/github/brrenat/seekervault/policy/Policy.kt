package io.github.brrenat.seekervault.policy

import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.wallet.isSolanaAddress
import java.time.Instant

/**
 * The rules the owner set for one connection (docs/policy.md).
 *
 * A policy lives on this phone and nowhere else. No agent can read it, and no agent can change it:
 * the sidecar is never sent the rules, nor the assessment they produce. It is the owner's own note
 * to themselves about what one agent is expected to ask for, and it decides nothing — every request
 * still needs their hand on the wallet, whatever the assessment says.
 */
data class ConnectionPolicy(
    /** The connection these rules belong to. Rules are never shared between connections. */
    val connectionId: String,
    /** Which kinds of action this connection may ask for. */
    val actions: Allowlist<PolicyAction>? = null,
    /** Which assets may move, each on one network. */
    val assets: Allowlist<PolicyAsset>? = null,
    /** Which wallets may receive funds. For a token this is the owner, never a token account. */
    val recipients: Allowlist<String>? = null,
    /** Which programs the transaction may call. */
    val programs: Allowlist<String>? = null,
    /**
     * What may move per asset, in that asset's own base units. An asset with no entry here has no
     * amount configured, which is not the same as a limit of zero.
     */
    val limits: Map<PolicyAsset, AssetLimits> = emptyMap(),
    /** When the owner last saved these rules. */
    val updatedAt: Instant,
) {
    /**
     * Whether the owner has configured no check at all. Such a policy is treated exactly like a
     * connection with no policy stored: it carries no rule to match, so it can never produce
     * ALLOWED (see [noPolicy]).
     */
    val configuresNothing: Boolean
        get() =
            actions == null &&
                assets == null &&
                recipients == null &&
                programs == null &&
                limits.values.none { it.configuresSomething }

    /** The limits for [asset], or none configured. */
    fun limitsFor(asset: PolicyAsset): AssetLimits = limits[asset] ?: AssetLimits()

    companion object {
        /**
         * The policy a connection starts with: nothing configured. It restricts nothing and
         * approves nothing, and every request under it is UNDER_RESTRICTIONS for want of rules.
         */
        fun default(connectionId: String, at: Instant): ConnectionPolicy =
            ConnectionPolicy(connectionId = connectionId, updatedAt = at)
    }
}

/**
 * The rules that apply by default to every connection.
 *
 * A global policy is still only the owner's phone-local note. It has no connection ID because it
 * belongs to the phone rather than to an agent, and it has no way to approve, reject, sign, or send
 * anything. [ConnectionPolicyOverrides] says where one connection differs from it.
 */
data class GlobalPolicy(
    val actions: Allowlist<PolicyAction>? = null,
    val assets: Allowlist<PolicyAsset>? = null,
    val recipients: Allowlist<String>? = null,
    val programs: Allowlist<String>? = null,
    val limits: Map<PolicyAsset, AssetLimits> = emptyMap(),
    val updatedAt: Instant,
) {
    val configuresNothing: Boolean
        get() =
            actions == null &&
                assets == null &&
                recipients == null &&
                programs == null &&
                limits.values.none { it.configuresSomething }

    fun limitsFor(asset: PolicyAsset): AssetLimits = limits[asset] ?: AssetLimits()

    companion object {
        fun default(at: Instant): GlobalPolicy = GlobalPolicy(updatedAt = at)
    }
}

/**
 * One connection's choice for a rule supplied by the global policy.
 *
 * [Inherit] means use the global rule. [NoCheck] deliberately replaces it with no check, which is
 * observably different from inheriting when a global rule exists. [Replace] replaces the whole
 * rule; for an [Allowlist], an empty value is a configured list that allows nothing.
 */
sealed interface RuleOverride<out T : Any> {
    data object Inherit : RuleOverride<Nothing>

    data object NoCheck : RuleOverride<Nothing>

    data class Replace<T : Any>(val value: T) : RuleOverride<T>
}

/** One connection's thresholds for one asset. */
data class ConnectionAssetLimits(
    /**
     * Per-request thresholds inherit, deliberately configure no check, or replace the global one.
     */
    val perOperation: RuleOverride<ULong> = RuleOverride.Inherit,
    /** A connection daily threshold is an additional check, never an override of the global one. */
    val daily: ULong? = null,
) {
    val configuresSomething: Boolean
        get() = perOperation != RuleOverride.Inherit || daily != null
}

/**
 * The rules one connection changes from the phone's global defaults.
 *
 * A missing document means the same thing as [inheritAll]: new connections inherit without a file
 * being created. Deleting this document resets only this connection. It never deletes the global
 * document or changes another connection.
 */
data class ConnectionPolicyOverrides(
    val connectionId: String,
    val actions: RuleOverride<Allowlist<PolicyAction>> = RuleOverride.Inherit,
    val assets: RuleOverride<Allowlist<PolicyAsset>> = RuleOverride.Inherit,
    val recipients: RuleOverride<Allowlist<String>> = RuleOverride.Inherit,
    val programs: RuleOverride<Allowlist<String>> = RuleOverride.Inherit,
    val limits: Map<PolicyAsset, ConnectionAssetLimits> = emptyMap(),
    val updatedAt: Instant,
) {
    val hasNoOverrides: Boolean
        get() =
            actions == RuleOverride.Inherit &&
                assets == RuleOverride.Inherit &&
                recipients == RuleOverride.Inherit &&
                programs == RuleOverride.Inherit &&
                limits.values.none { it.configuresSomething }

    fun limitsFor(asset: PolicyAsset): ConnectionAssetLimits =
        limits[asset] ?: ConnectionAssetLimits()

    companion object {
        fun inheritAll(connectionId: String, at: Instant): ConnectionPolicyOverrides =
            ConnectionPolicyOverrides(connectionId = connectionId, updatedAt = at)
    }
}

/**
 * A list the owner configured. Its absence — a `null` in [ConnectionPolicy] — is not an empty list:
 * an absent list means the owner configured no such check, so nothing is checked and the review
 * says the parameter wasn't covered. An empty list means the owner configured the check and put
 * nothing in it, so every value fails it. The two must never be stored, shown, or evaluated alike.
 */
data class Allowlist<T : Any>(val values: Set<T>) {
    /** Configured, and matching nothing. */
    val allowsNothing: Boolean
        get() = values.isEmpty()

    operator fun contains(value: T): Boolean = value in values

    companion object {
        fun <T : Any> of(vararg values: T): Allowlist<T> = Allowlist(values.toSet())

        /** Configured to allow nothing. Not the same as no list at all. */
        fun <T : Any> nothing(): Allowlist<T> = Allowlist(emptySet())
    }
}

/**
 * A kind of action a policy can name. It mirrors the protocol's `Action` kinds
 * (docs/protocol.md#actions) as a name of this app's own, so that a stored policy keeps its meaning
 * when the protocol adds a kind.
 */
enum class PolicyAction(val code: String) {
    /** Display-only text the owner acknowledges. No wallet, and nothing moves. */
    Acknowledgement("ack"),
    /** A message the wallet signs. It reaches no network and moves nothing. */
    MessageSignature("sign_message"),
    Transfer("transfer"),
    Swap("swap");

    companion object {
        fun byCode(code: String): PolicyAction? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One asset on one network: native SOL, or an SPL token by its mint. The network is part of the
 * identity because an asset on devnet and the same mint on mainnet are not the same thing to spend,
 * and a rule written for one must never be read as covering the other.
 */
data class PolicyAsset(val network: Network, val mint: String? = null) {
    /** True for native SOL, whose amounts are lamports. */
    val isNativeSol: Boolean
        get() = mint == null

    companion object {
        fun sol(network: Network): PolicyAsset = PolicyAsset(network)

        fun token(network: Network, mint: String): PolicyAsset = PolicyAsset(network, mint)
    }
}

/**
 * What one asset may move, in that asset's base units — lamports for SOL, the mint's own units for
 * a token. Base units are what the transaction carries, so a threshold written in them is checked
 * against the same number the chain sees, with no decimal count read from anywhere and no rounding.
 *
 * Both are advisory (docs/policy.md#advisory-thresholds): passing one approves nothing, and
 * exceeding one blocks nothing.
 */
data class AssetLimits(
    /** The most one request may move. Null: not configured. */
    val perOperation: ULong? = null,
    /** The most this app may move in a day, counted by SAW-026. Null: not configured. */
    val daily: ULong? = null,
) {
    val configuresSomething: Boolean
        get() = perOperation != null || daily != null
}

/** Why a policy, or a value the owner typed into one, isn't accepted. */
enum class PolicyProblem {
    /** The connection ID isn't one the sidecar could have assigned. */
    NotAConnection,
    /** An address isn't base58 for 32 bytes. */
    NotAnAddress,
    /** A mint isn't an address. */
    NotAMint,
    /** An asset names no network, so there is no chain the rule is about. */
    NoNetwork,
    /** A limit is zero. A rule that permits nothing is written as an empty list, not as zero. */
    ZeroLimit,
    /** The daily limit is below the per-operation one, so the per-operation one can never bind. */
    DailyBelowPerOperation,
    /** A limit is set for an asset the asset list doesn't allow, so it could never apply. */
    LimitForUnlistedAsset,
}

/**
 * Everything wrong with [policy], in a fixed order, or empty when it is fit to store. The editor
 * (SAW-027) shows these; the store refuses to write a policy that has any.
 */
fun policyProblems(policy: ConnectionPolicy): List<PolicyProblem> {
    val problems = mutableListOf<PolicyProblem>()
    if (!isConnectionId(policy.connectionId)) problems += PolicyProblem.NotAConnection
    problems +=
        ruleProblems(
            recipients = policy.recipients,
            programs = policy.programs,
            assets = policy.assets,
            limits = policy.limits,
        )
    return problems.distinct()
}

/** Everything wrong with a global document. It has no connection ID to validate. */
fun policyProblems(policy: GlobalPolicy): List<PolicyProblem> =
    ruleProblems(policy.recipients, policy.programs, policy.assets, policy.limits)

/** Everything wrong with one connection's explicit overrides. */
fun policyProblems(policy: ConnectionPolicyOverrides): List<PolicyProblem> {
    val problems = mutableListOf<PolicyProblem>()
    if (!isConnectionId(policy.connectionId)) problems += PolicyProblem.NotAConnection
    val limits =
        policy.limits.mapValues { (_, value) ->
            AssetLimits(
                perOperation = value.perOperation.replacement,
                daily = value.daily,
            )
        }
    problems +=
        ruleProblems(
            recipients = policy.recipients.replacement,
            programs = policy.programs.replacement,
            assets = policy.assets.replacement,
            limits = limits,
        )
    return problems.distinct()
}

private fun ruleProblems(
    recipients: Allowlist<String>?,
    programs: Allowlist<String>?,
    assets: Allowlist<PolicyAsset>?,
    limits: Map<PolicyAsset, AssetLimits>,
): List<PolicyProblem> {
    val problems = mutableListOf<PolicyProblem>()
    if (recipients?.values.orEmpty().any { !isSolanaAddress(it) }) {
        problems += PolicyProblem.NotAnAddress
    }
    if (programs?.values.orEmpty().any { !isSolanaAddress(it) }) {
        problems += PolicyProblem.NotAnAddress
    }
    val namedAssets = assets?.values.orEmpty() + limits.keys
    if (namedAssets.any { it.mint != null && !isSolanaAddress(it.mint) })
        problems += PolicyProblem.NotAMint
    if (namedAssets.any { it.network == Network.NETWORK_UNSPECIFIED })
        problems += PolicyProblem.NoNetwork
    for ((asset, value) in limits) {
        if (value.perOperation == 0UL || value.daily == 0UL) problems += PolicyProblem.ZeroLimit
        val perOperation = value.perOperation
        val daily = value.daily
        if (perOperation != null && daily != null && daily < perOperation) {
            problems += PolicyProblem.DailyBelowPerOperation
        }
        // A limit on an asset the list doesn't allow is dead text: the asset check fails first, and
        // the owner would be reading a threshold that can never be reached.
        if (value.configuresSomething && assets != null && asset !in assets) {
            problems += PolicyProblem.LimitForUnlistedAsset
        }
    }
    return problems.distinct()
}

/** The replacement value, or null for inheritance and an explicit no-check override. */
val <T : Any> RuleOverride<T>.replacement: T?
    get() = (this as? RuleOverride.Replace<T>)?.value

/**
 * Stage 5's flat connection policy represented as Stage 5.1 overrides.
 *
 * Every value Stage 5 configured becomes a connection replacement. Every absent field inherits, and
 * an existing daily threshold stays a connection-scoped daily check. With no global document,
 * resolving this produces the same effective values Stage 5 evaluated.
 */
fun overridesOf(policy: ConnectionPolicy): ConnectionPolicyOverrides =
    ConnectionPolicyOverrides(
        connectionId = policy.connectionId,
        actions = policy.actions.asOverride(),
        assets = policy.assets.asOverride(),
        recipients = policy.recipients.asOverride(),
        programs = policy.programs.asOverride(),
        limits =
            policy.limits.mapValues { (_, value) ->
                ConnectionAssetLimits(
                    perOperation = value.perOperation.asOverride(),
                    daily = value.daily,
                )
            },
        updatedAt = policy.updatedAt,
    )

/**
 * The local-only compatibility view used by the completed Stage 5 editor and evaluator.
 *
 * Inheritance and an explicit no-check both appear as an absent local rule here. Code that needs to
 * distinguish them uses [ConnectionPolicyOverrides] and the effective-policy resolver instead.
 */
fun localPolicyOf(overrides: ConnectionPolicyOverrides): ConnectionPolicy =
    ConnectionPolicy(
        connectionId = overrides.connectionId,
        actions = overrides.actions.replacement,
        assets = overrides.assets.replacement,
        recipients = overrides.recipients.replacement,
        programs = overrides.programs.replacement,
        limits =
            overrides.limits.mapValues { (_, value) ->
                AssetLimits(perOperation = value.perOperation.replacement, daily = value.daily)
            },
        updatedAt = overrides.updatedAt,
    )

private fun <T : Any> T?.asOverride(): RuleOverride<T> =
    if (this == null) RuleOverride.Inherit else RuleOverride.Replace(this)
