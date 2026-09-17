package io.github.brrenat.seekervault.plugins

/**
 * The plugins this build has, and the one place an operation is matched to one (SEE-86).
 *
 * The list is fixed when the app is built ([bundled]). Nothing is downloaded, nothing is discovered
 * at runtime, and a server that names a plugin this build doesn't carry gets told so: a missing
 * plugin is a reported state and never a reason to sign something blind
 * (docs/wiki/client-plugins.md).
 *
 * It holds no state beyond that list. Resolving is a lookup and has no effect.
 */
class PluginRegistry private constructor(private val plugins: Map<PluginId, ActionPlugin>) {

    /** What this build carries, in the order it was registered in. */
    val descriptors: List<PluginDescriptor>
        get() = plugins.values.map { it.descriptor }

    /** The plugin with that ID, whatever it serves; null when this build doesn't carry it. */
    fun byId(id: PluginId): ActionPlugin? = plugins[id]

    /**
     * The plugin that serves [operation] in [environment], or why none does.
     *
     * The reasons are kept apart because they are different things to tell someone: this build
     * carries nothing for the operation, it carries something written against a boundary this build
     * doesn't speak, or it carries one that doesn't serve this environment. All three are refusals,
     * and none of them is a verdict about the operation itself.
     */
    fun resolve(operation: OperationId, environment: PluginEnvironment): PluginResolution {
        val serving = plugins.values.filter { operation in it.descriptor.operations }
        if (serving.isEmpty()) {
            return PluginResolution.Unsupported(operation, UnsupportedReason.NoPlugin)
        }
        val callable = serving.filter { it.descriptor.contractSupported }
        if (callable.isEmpty()) {
            return PluginResolution.Unsupported(operation, UnsupportedReason.ContractUnsupported)
        }
        val here = callable.firstOrNull { environment in it.descriptor.environments }
        return here?.let(PluginResolution::Supported)
            ?: PluginResolution.Unsupported(operation, UnsupportedReason.EnvironmentUnsupported)
    }

    companion object {
        /**
         * A registry of exactly [plugins], which is the whole of how a build selects them.
         *
         * The selection itself is made where the app is composed rather than here
         * (`SeekerVaultApplication`). SEE-86 kept a `bundled()` in this file, which could only ever
         * list plugins that need nothing to be constructed; the first real one needs an HTTP client
         * (SEE-93), and this package holds no client and reaches no transport. So the list is named
         * where every other dependency in this app is named, and this stays data and pure functions
         * (docs/wiki/client-plugins.md#selecting-plugins-at-build-time).
         *
         * Two plugins with the same ID is a mistake in the build's own list, not a state to report:
         * an ID is what a manifest names, and a name that means two things means nothing.
         */
        fun of(vararg plugins: ActionPlugin): PluginRegistry {
            val byId = LinkedHashMap<PluginId, ActionPlugin>(plugins.size)
            for (plugin in plugins) {
                val id = plugin.descriptor.id
                require(byId.put(id, plugin) == null) { "two plugins registered as $id" }
            }
            return PluginRegistry(byId)
        }
    }
}

/** Whether this build can serve an operation, and when it can't, why not. */
sealed interface PluginResolution {
    data class Supported(val plugin: ActionPlugin) : PluginResolution

    data class Unsupported(
        val operation: OperationId,
        val reason: UnsupportedReason,
    ) : PluginResolution
}

/** Why no plugin serves an operation. Each is stated to the owner as itself (SEE-88). */
enum class UnsupportedReason(val code: String) {
    /** This build carries no plugin for the operation. */
    NoPlugin("no_plugin"),

    /**
     * One exists, but it was written against a version of the boundary this build doesn't call.
     * Calling it anyway would be guessing at a contract neither side agreed to.
     */
    ContractUnsupported("contract_unsupported"),

    /** One exists and is callable, but not for the environment the app is running in. */
    EnvironmentUnsupported("environment_unsupported"),
}
