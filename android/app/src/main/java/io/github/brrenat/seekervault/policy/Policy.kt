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
    if (policy.recipients?.values.orEmpty().any { !isSolanaAddress(it) }) {
        problems += PolicyProblem.NotAnAddress
    }
    if (policy.programs?.values.orEmpty().any { !isSolanaAddress(it) }) {
        problems += PolicyProblem.NotAnAddress
    }
    val assets = policy.assets?.values.orEmpty() + policy.limits.keys
    if (assets.any { it.mint != null && !isSolanaAddress(it.mint) })
        problems += PolicyProblem.NotAMint
    if (assets.any { it.network == Network.NETWORK_UNSPECIFIED })
        problems += PolicyProblem.NoNetwork
    for ((asset, limits) in policy.limits) {
        if (limits.perOperation == 0UL || limits.daily == 0UL) problems += PolicyProblem.ZeroLimit
        val perOperation = limits.perOperation
        val daily = limits.daily
        if (perOperation != null && daily != null && daily < perOperation) {
            problems += PolicyProblem.DailyBelowPerOperation
        }
        // A limit on an asset the list doesn't allow is dead text: the asset check fails first, and
        // the owner would be reading a threshold that can never be reached.
        val allowed = policy.assets
        if (limits.configuresSomething && allowed != null && asset !in allowed) {
            problems += PolicyProblem.LimitForUnlistedAsset
        }
    }
    return problems.distinct()
}
