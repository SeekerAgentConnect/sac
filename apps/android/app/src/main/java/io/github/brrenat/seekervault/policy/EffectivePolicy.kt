package io.github.brrenat.seekervault.policy

import io.github.brrenat.seekervault.connections.isConnectionId

/** Where one effective rule came from. The code is stable when kept in an Activity snapshot. */
enum class RuleSource(val code: String) {
    Global("global"),
    ConnectionOverride("connection"),
    NotConfigured("not_configured"),
}

/**
 * One effective rule and the document that supplied it. A null value means no check is configured.
 */
data class EffectiveRule<T : Any>(val value: T?, val source: RuleSource) {
    init {
        require(source != RuleSource.Global || value != null) {
            "a global source must supply a rule"
        }
        require(source != RuleSource.NotConfigured || value == null) {
            "an unconfigured rule has no value"
        }
    }

    val configured: Boolean
        get() = value != null

    companion object {
        fun <T : Any> notConfigured(): EffectiveRule<T> =
            EffectiveRule(null, RuleSource.NotConfigured)
    }
}

/**
 * The three independently resolved thresholds for one asset.
 *
 * A per-request threshold is inherited or replaced. Daily thresholds are deliberately not folded
 * together: the global threshold is an all-connections check and the connection threshold is an
 * additional local check. Keeping both here makes it impossible for a local override to erase the
 * global daily rule accidentally.
 */
data class EffectiveAssetLimits(
    val perOperation: EffectiveRule<ULong> = EffectiveRule.notConfigured(),
    val globalDaily: EffectiveRule<ULong> = EffectiveRule.notConfigured(),
    val connectionDaily: EffectiveRule<ULong> = EffectiveRule.notConfigured(),
)

/** The deterministic rules for one connection, with the source of every effective value. */
data class EffectivePolicy(
    val connectionId: String,
    val actions: EffectiveRule<Allowlist<PolicyAction>>,
    val assets: EffectiveRule<Allowlist<PolicyAsset>>,
    val recipients: EffectiveRule<Allowlist<String>>,
    val programs: EffectiveRule<Allowlist<String>>,
    val limits: Map<PolicyAsset, EffectiveAssetLimits>,
) {
    fun limitsFor(asset: PolicyAsset): EffectiveAssetLimits =
        limits[asset] ?: EffectiveAssetLimits()
}

/**
 * Resolves [global] and [connection] without reading, writing, or consulting a request.
 *
 * Allowlist sections replace in full; they are never unioned. Per-request thresholds resolve per
 * asset independently from that asset section. Daily thresholds remain separate, so replacing an
 * asset list, choosing no local per-request check, or resetting all connection overrides cannot
 * remove a global daily check.
 */
fun resolveEffectivePolicy(
    connectionId: String,
    global: GlobalPolicy?,
    connection: ConnectionPolicyOverrides?,
): EffectivePolicy {
    require(isConnectionId(connectionId)) { "not a connection ID" }
    require(connection == null || connection.connectionId == connectionId) {
        "a connection's overrides resolve only for that connection"
    }
    require(global == null || policyProblems(global).isEmpty()) { "invalid global policy" }
    require(connection == null || policyProblems(connection).isEmpty()) {
        "invalid connection overrides"
    }

    val assets = linkedSetOf<PolicyAsset>()
    assets += global?.limits.orEmpty().keys
    assets += connection?.limits.orEmpty().keys
    val limits = assets.associateWith { asset ->
        val globalLimits = global?.limitsFor(asset) ?: AssetLimits()
        val localLimits = connection?.limitsFor(asset) ?: ConnectionAssetLimits()
        EffectiveAssetLimits(
            perOperation = effective(globalLimits.perOperation, localLimits.perOperation),
            globalDaily =
                globalLimits.daily?.let { EffectiveRule(it, RuleSource.Global) }
                    ?: EffectiveRule.notConfigured(),
            connectionDaily =
                localLimits.daily?.let {
                    EffectiveRule(it, RuleSource.ConnectionOverride)
                } ?: EffectiveRule.notConfigured(),
        )
    }

    return EffectivePolicy(
        connectionId = connectionId,
        actions = effective(global?.actions, connection?.actions ?: RuleOverride.Inherit),
        assets = effective(global?.assets, connection?.assets ?: RuleOverride.Inherit),
        recipients = effective(global?.recipients, connection?.recipients ?: RuleOverride.Inherit),
        programs = effective(global?.programs, connection?.programs ?: RuleOverride.Inherit),
        limits = limits,
    )
}

private fun <T : Any> effective(global: T?, override: RuleOverride<T>): EffectiveRule<T> =
    when (override) {
        RuleOverride.Inherit ->
            global?.let { EffectiveRule(it, RuleSource.Global) } ?: EffectiveRule.notConfigured()
        RuleOverride.NoCheck -> EffectiveRule(null, RuleSource.ConnectionOverride)
        is RuleOverride.Replace -> EffectiveRule(override.value, RuleSource.ConnectionOverride)
    }
