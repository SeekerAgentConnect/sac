package io.github.brrenat.seekervault.servers

import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.plugins.ProviderRegistry
import io.github.brrenat.seekervault.plugins.legacyCapabilityOf

/**
 * Whether this build supports a server, and when it doesn't, why not (SEE-88).
 *
 * This is derived and never stored. A verdict written to disk would outlive the build that reached
 * it: installing a version of the app that carries a plugin would leave yesterday's "missing"
 * sitting in a file, and the owner would be told something that stopped being true. The manifest is
 * cached, because it is the server's own data; the match against the compiled registry is made
 * again every time it is read.
 *
 * A server this build doesn't support is still readable: a feed can be looked at without the plugin
 * that would act on it, and a request can be read and rejected. What it cannot do is reach a wallet
 * ([executable], docs/wiki/server-manifests.md#viewing-without-executing).
 */
sealed interface ServerSupport {
    /** The server's contract, environment and required plugins are all ones this build has. */
    data object Supported : ServerSupport

    /**
     * A direct server that publishes no manifest: the documented legacy path. It requires nothing,
     * so there is nothing to be incompatible with, and it behaves exactly as it always has.
     */
    data object LegacyDirect : ServerSupport

    /**
     * The server hasn't been asked yet. Nothing is claimed either way, and it can only ever
     * describe a direct connection: a feed exists only because a manifest was read for it.
     */
    data object Unknown : ServerSupport

    /** The server published a manifest this phone refused ([ManifestProblem]). */
    data class ManifestRefused(val problem: ManifestProblem) : ServerSupport

    /** The server speaks a phone–server contract this build doesn't: the app needs an update. */
    data class ProtocolUnsupported(val version: Int) : ServerSupport

    /** The server doesn't serve the environment the app is running in. */
    data class EnvironmentUnsupported(val environment: PluginEnvironment) : ServerSupport

    /** This build carries no plugin with one of the required IDs. */
    data class PluginMissing(val plugins: List<PluginId>) : ServerSupport

    /**
     * This build carries the plugin, but its contract version is one the server doesn't work with
     * (or one this build no longer calls). Calling it anyway would be guessing at a boundary
     * neither side agreed on.
     */
    data class PluginIncompatible(val plugins: List<PluginId>) : ServerSupport
}

/**
 * Whether anything from this server may be executed. A state that isn't executable is not a smaller
 * kind of approval: there is no Approve button, no preparation, and no wallet interaction, and
 * nothing falls back to signing a message or a transaction the app couldn't account for.
 *
 * [ServerSupport.Unknown] is executable, which is deliberate rather than lenient. It describes a
 * direct connection the owner paired and could always act on, and its requests are the actions the
 * app carries out itself; a server that has published nothing it needs cannot be found wanting for
 * it. An operation a plugin *would* serve is still resolved for each request
 * ([ProviderRegistry.resolve]), and one nothing serves establishes nothing whichever state this is
 * in (SEE-86).
 *
 * The check is exhaustive so that a state added later has to be decided about rather than
 * inheriting an answer.
 */
val ServerSupport.executable: Boolean
    get() =
        when (this) {
            is ServerSupport.Supported,
            is ServerSupport.LegacyDirect,
            is ServerSupport.Unknown -> true
            is ServerSupport.ManifestRefused,
            is ServerSupport.ProtocolUnsupported,
            is ServerSupport.EnvironmentUnsupported,
            is ServerSupport.PluginMissing,
            is ServerSupport.PluginIncompatible -> false
        }

/**
 * What [record] means for this build, matched against the plugins compiled into it and the
 * [environment] the app is running in.
 *
 * The checks are ordered from the most fundamental to the most specific: a manifest that was
 * refused says nothing further, a contract this build doesn't speak makes its other claims
 * unreadable, an environment the server doesn't serve is a different promise altogether, and only
 * then are the plugins it names looked for. A plugin this build doesn't carry is reported before
 * one it carries at the wrong version, because the two are different things to be told and the
 * first is the plainer fact.
 *
 * Only the IDs and contract ranges are matched here. Whether a plugin serves a particular operation
 * in a particular environment is resolved for each request by the registry itself
 * ([ProviderRegistry.resolve]), because that is a question about one request and not about the
 * server.
 */
fun serverSupport(
    record: ServerRecord,
    plugins: ProviderRegistry,
    environment: PluginEnvironment,
): ServerSupport {
    val manifest =
        when (record) {
            is ServerRecord.Unknown -> return ServerSupport.Unknown
            is ServerRecord.Legacy -> return ServerSupport.LegacyDirect
            is ServerRecord.Refused -> return ServerSupport.ManifestRefused(record.problem)
            is ServerRecord.Known -> record.manifest
        }
    if (manifest.protocolVersion !in SUPPORTED_SERVER_PROTOCOLS) {
        return ServerSupport.ProtocolUnsupported(manifest.protocolVersion)
    }
    if (environment !in manifest.environments) {
        return ServerSupport.EnvironmentUnsupported(environment)
    }
    // A manifest names bundled plugins, and what answers to one of those names is the execution
    // provider that declares it ([ProviderCapabilities.legacyPlugins]) — matched, never parsed out
    // of the name (SEE-145, docs/wiki/execution-providers.md#compatibility).
    val missing = manifest.required.filterNot { plugins.byLegacyPlugin(it.id) != null }
    if (missing.isNotEmpty()) return ServerSupport.PluginMissing(missing.map { it.id })
    val incompatible =
        manifest.required.filterNot { requirement ->
            // The number a server names is the *published* contract of that plugin name, which
            // SEE-145 did not move: restructuring the code behind `jupiter.swap` is not a change to
            // the agreement a server has with a client, so a manifest requiring `1..1` is satisfied
            // exactly as before ([LegacyCapability.contract]).
            val carried = checkNotNull(plugins.byLegacyPlugin(requirement.id)).capabilities
            // Two different ways it can fail to be callable, and both are this one state: the
            // provider itself is written against a boundary this build no longer calls, or the
            // published contract of the name it answers to is outside the range the server works
            // with. A provider with no legacy row is matched on its own contract, because then
            // there is no published number to be about.
            val published = legacyCapabilityOf(requirement.id)?.contract ?: carried.contract
            carried.contractSupported && published in requirement.contracts
        }
    if (incompatible.isNotEmpty()) {
        return ServerSupport.PluginIncompatible(incompatible.map { it.id })
    }
    return ServerSupport.Supported
}
