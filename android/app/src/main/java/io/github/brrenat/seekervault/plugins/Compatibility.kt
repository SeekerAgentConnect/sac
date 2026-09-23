package io.github.brrenat.seekervault.plugins

/**
 * The one place a name written before SEE-145 is turned into a provider and an action.
 *
 * Until SEE-145 a request named a single thing — `jupiter.swap` — that was both the action and who
 * would carry it out. Servers, manifests, proposals already on this phone and records of executions
 * that already happened all say it that way, and every one of them keeps working. Not by guessing:
 * `jupiter.swap` is not read as "the provider called jupiter doing the action called swap", because
 * a name that happens to contain a dot is not a structure. It is read by looking it up here.
 *
 * That is the whole of the compatibility surface. Nothing else in this app splits a plugin ID, and
 * a legacy name this table doesn't carry resolves to nothing at all — which is the same answer the
 * build has always given for a plugin it doesn't have
 * (docs/wiki/execution-providers.md#compatibility).
 */
data class LegacyCapability(
    /** The bundled-plugin name a manifest or a stored proposal uses. */
    val plugin: PluginId,
    /** The operation name that went with it on the wire and in `ProposalStore` rows. */
    val operation: String,
    val provider: ExecutionProviderId,
    val action: ActionId,
    val schemaVersion: Int,
    /**
     * The plugin-boundary contract version this name was published at, which is the number a server
     * manifest's `min_contract..max_contract` range is about.
     *
     * It is **not** [PROVIDER_CONTRACT], and keeping the two apart is what let SEE-145 change the
     * interface inside the APK without invalidating a single published manifest. A server's range
     * is a statement about the agreement it has with a client — what it may put in a proposal, and
     * what a client will do with it — and none of that changed: `jupiter.swap` at `1..1` still
     * means exactly what it meant, and the code behind it being restructured is not the server's
     * business (docs/wiki/execution-providers.md#two-numbers-called-contract).
     */
    val contract: Int = 1,
)

/** The bundled execution provider this release carries. */
val JUPITER_PROVIDER: ExecutionProviderId = ExecutionProviderId("jupiter")

/** The legacy plugin name for Jupiter's swap, which servers still require at contract `1..1`. */
val JUPITER_SWAP: PluginId = PluginId("jupiter.swap")

/** The legacy plugin name for Jupiter's prediction orders. */
val JUPITER_PREDICTION: PluginId = PluginId("jupiter.prediction")

/**
 * Every legacy name this build answers to, written out rather than derived.
 *
 * Adding a provider does not add rows here. A new provider is named explicitly by the documents
 * written for it, and this table exists only for the two names that were published before there was
 * a way to name a provider at all.
 */
val LEGACY_CAPABILITIES: List<LegacyCapability> =
    listOf(
        LegacyCapability(
            plugin = JUPITER_SWAP,
            operation = "swap",
            provider = JUPITER_PROVIDER,
            action = SWAP_ACTION,
            schemaVersion = SWAP_SCHEMA_VERSION,
        ),
        LegacyCapability(
            plugin = JUPITER_PREDICTION,
            operation = "prediction",
            provider = JUPITER_PROVIDER,
            action = PREDICTION_BUY_ACTION,
            schemaVersion = PREDICTION_BUY_SCHEMA_VERSION,
        ),
    )

/** What [plugin] meant, or null when this build has never answered to that name. */
fun legacyCapabilityOf(plugin: PluginId): LegacyCapability? = LEGACY_CAPABILITIES.firstOrNull {
    it.plugin == plugin
}

/**
 * The action a document's capability name asks for.
 *
 * Both spellings are accepted and they mean the same thing, which is what "do not silently break
 * older supported clients" comes to in one function: a publisher that says `prediction` is asking
 * for the same action as one that says `prediction.buy`, and the phone prepares the same order from
 * either. A name that is neither is still an action — an action nothing in this build serves, which
 * is shown as unsupported rather than mistaken for one that is.
 */
fun actionOf(capabilityId: String): ActionId? {
    if (!isDottedName(capabilityId)) return null
    LEGACY_CAPABILITIES.firstOrNull { it.operation == capabilityId }
        ?.let {
            return it.action
        }
    return ActionId(capabilityId)
}

/**
 * How [action] is spelled for something that still reads the old vocabulary — a `ProposalStore` row
 * this build writes and an older build may read back, and the legacy `Proposal.operation` field.
 *
 * An action with no legacy spelling is written as itself. There is nothing to preserve for one that
 * never existed before SEE-145, and writing it under an old name would be inventing compatibility
 * rather than keeping it.
 */
fun legacyOperationOf(action: ActionId): String =
    LEGACY_CAPABILITIES.firstOrNull { it.action == action }?.operation ?: action.value

/**
 * The provider a document meant, given what it named.
 *
 * [named] is what the document said explicitly, and it wins whenever it is there: SEE-145's whole
 * direction is that the execution provider is stated rather than inferred. [plugin] is consulted
 * only when nothing was stated, and only through [LEGACY_CAPABILITIES] — so a document that names
 * neither a provider nor a plugin this build knows resolves to no provider, and an operation with
 * no provider is refused before anything is prepared.
 */
fun providerOf(named: ExecutionProviderId?, plugin: PluginId?): ExecutionProviderId? =
    named ?: plugin?.let { legacyCapabilityOf(it)?.provider }

/**
 * How a provider's action is named for something that still reads the old vocabulary: the bundled
 * plugin name it answered to.
 *
 * It is what an activity record keeps, so that the owner's history of what this phone did reads the
 * same across the upgrade rather than changing under them
 * ([io.github.brrenat.seekervault.activity.ReviewedOperation]).
 */
fun legacyPluginOf(provider: ExecutionProviderId, action: ActionId): PluginId? =
    LEGACY_CAPABILITIES.firstOrNull { it.provider == provider && it.action == action }?.plugin
