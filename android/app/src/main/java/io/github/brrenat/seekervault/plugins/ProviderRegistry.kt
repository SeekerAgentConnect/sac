package io.github.brrenat.seekervault.plugins

import io.github.brrenat.seekervault.plugins.actions.ActionPayload
import io.github.brrenat.seekervault.request.v1.Network

/**
 * The execution providers this build has, and the one place an action is matched to one (SEE-145,
 * SEE-86).
 *
 * The list is fixed when the app is built (`SeekerVaultApplication.providers`). Nothing is
 * downloaded, nothing is discovered at runtime, and a server that names a provider this build
 * doesn't carry gets told so: a missing provider is a reported state and never a reason to sign
 * something blind (docs/wiki/execution-providers.md).
 *
 * It holds no state beyond that list. Resolving is a lookup and has no effect.
 */
class ProviderRegistry
private constructor(private val providers: Map<ExecutionProviderId, ExecutionProvider>) {

    /** What this build carries, in the order it was registered in. */
    val capabilities: List<ProviderCapabilities>
        get() = providers.values.map { it.capabilities }

    /** The provider with that ID, whatever it serves; null when this build doesn't carry it. */
    fun byId(id: ExecutionProviderId): ExecutionProvider? = providers[id]

    /**
     * The provider that answers to a bundled-plugin name a server manifest requires (SEE-88).
     *
     * A manifest written before SEE-145 says `jupiter.swap`, and what satisfies it is the provider
     * that declares that legacy name ([ProviderCapabilities.legacyPlugins]) — matched, never
     * parsed.
     */
    fun byLegacyPlugin(plugin: PluginId): ExecutionProvider? =
        providers.values.firstOrNull { plugin in it.capabilities.legacyPlugins }

    /**
     * The provider that would serve this operation, or exactly why none would.
     *
     * [provider] is what the document named, and it is not optional in spirit even though it is
     * nullable in type: null means the document named no provider this build could identify, which
     * is [UnsupportedReason.NoProvider] and not a licence to pick one. There is no routing here, no
     * "the only provider that serves swap", and no fallback — two providers of the same action are
     * two different venues, and choosing between them is the owner's and the publisher's business
     * rather than a registry's.
     *
     * The checks run from the most fundamental to the most specific, because each of them is a
     * different thing to tell someone: this build carries no such provider; it carries it but it
     * was written against a boundary this build doesn't speak; it speaks the boundary but serves no
     * such action; it serves the action but not at this payload schema version; not on the cluster
     * the owner's wallet is selected for; not in the environment this connection keeps; not in the
     * asset the document names. All seven are refusals, none is a verdict about the action itself,
     * and every one of them happens before anything is prepared and long before anything is signed.
     */
    fun resolve(
        provider: ExecutionProviderId?,
        action: ActionId,
        schemaVersion: Int,
        network: Network,
        environment: PluginEnvironment,
        /**
         * What the document is about, when it has been read. The venue's accepted stake tokens are
         * part of what it promises, so an operation in a token it will not take is refused here
         * rather than by its API halfway through an order. Null where there is nothing to check
         * against — a private request the app has never prepared, a document that would not parse —
         * and a null payload skips only this last check.
         */
        payload: ActionPayload? = null,
    ): ProviderResolution {
        val serving =
            provider?.let(providers::get)
                ?: return ProviderResolution.Unsupported(action, UnsupportedReason.NoProvider)
        val capabilities = serving.capabilities
        if (!capabilities.contractSupported) {
            return ProviderResolution.Unsupported(action, UnsupportedReason.ContractUnsupported)
        }
        val capability =
            capabilities.forAction(action)
                ?: return ProviderResolution.Unsupported(
                    action,
                    UnsupportedReason.ActionUnsupported,
                )
        if (!capability.reads(schemaVersion)) {
            return ProviderResolution.Unsupported(action, UnsupportedReason.SchemaUnsupported)
        }
        // An owner with no wallet connected has no cluster yet, and "you have not connected a
        // wallet" is not "this provider does not serve your network". The unestablished case is
        // left to the one place that can say it plainly — preparing, which fails with `no_wallet` —
        // and to the binding, which re-checks the cluster against the selected wallet on the far
        // side of the wait (`bindingProblem`). So the review still opens and still shows what would
        // have to be chosen.
        if (network != Network.NETWORK_UNSPECIFIED && network !in capability.networks) {
            return ProviderResolution.Unsupported(action, UnsupportedReason.NetworkUnsupported)
        }
        if (environment !in capabilities.environments) {
            return ProviderResolution.Unsupported(action, UnsupportedReason.EnvironmentUnsupported)
        }
        val accepted = capability.depositAssets
        if (payload != null && accepted != null && payload.depositAsset !in accepted) {
            return ProviderResolution.Unsupported(action, UnsupportedReason.AssetUnsupported)
        }
        return ProviderResolution.Supported(serving, capability)
    }

    companion object {
        /**
         * A registry of exactly [providers], which is the whole of how a build selects them.
         *
         * The selection itself is made where the app is composed rather than here
         * (`SeekerVaultApplication`), because a real provider needs an HTTP client and this package
         * holds none and reaches no transport
         * (docs/wiki/execution-providers.md#selecting-providers-at-build-time).
         *
         * Two providers with the same ID, or two claiming the same legacy plugin name, is a mistake
         * in the build's own list rather than a state to report: a name that means two things means
         * nothing, and that is as true of `jupiter.swap` as it is of `jupiter`.
         */
        fun of(vararg providers: ExecutionProvider): ProviderRegistry {
            val byId = LinkedHashMap<ExecutionProviderId, ExecutionProvider>(providers.size)
            val legacy = mutableSetOf<PluginId>()
            for (provider in providers) {
                val id = provider.capabilities.id
                require(byId.put(id, provider) == null) { "two providers registered as $id" }
                for (plugin in provider.capabilities.legacyPlugins) {
                    require(legacy.add(plugin)) { "two providers answer to $plugin" }
                }
            }
            return ProviderRegistry(byId)
        }
    }
}

/** Whether this build can serve an action for a provider, and when it can't, why not. */
sealed interface ProviderResolution {
    data class Supported(
        val provider: ExecutionProvider,
        /** What that provider promises about this action: its schemas, clusters and limits. */
        val capability: ActionCapability,
    ) : ProviderResolution

    data class Unsupported(
        val action: ActionId,
        val reason: UnsupportedReason,
    ) : ProviderResolution
}

/** Why no provider serves an action here. Each is stated to the owner as itself (SEE-88). */
enum class UnsupportedReason(val code: String) {
    /** This build carries no provider with that ID, or the document named none. */
    NoProvider("no_provider"),

    /**
     * One exists, but it was written against a version of the boundary this build doesn't call.
     * Calling it anyway would be guessing at a contract neither side agreed to.
     */
    ContractUnsupported("contract_unsupported"),

    /** It exists and is callable, but does not serve this action at all. */
    ActionUnsupported("action_unsupported"),

    /** It serves the action, but not this version of the action's payload schema. */
    SchemaUnsupported("schema_unsupported"),

    /**
     * It serves the action, but not on the cluster the owner's wallet is selected for. It is a
     * separate reason from the environment on purpose: a provider having no devnet is not the same
     * fact as a provider having no sandbox, and telling an owner the wrong one of those would send
     * them to change the wrong setting (docs/wiki/environments.md).
     */
    NetworkUnsupported("network_unsupported"),

    /** It serves the action, but not in the environment this connection keeps. */
    EnvironmentUnsupported("environment_unsupported"),

    /**
     * It serves the action here, but not in the asset the document names: a stake token this venue
     * does not settle in, or an input it will not take. It is the venue's rule rather than the
     * action's, which is why it is answered here and not by the payload reader.
     */
    AssetUnsupported("asset_unsupported"),
}
