package io.github.brrenat.seekervault.jupiter

import io.github.brrenat.seekervault.plugins.ActionCapability
import io.github.brrenat.seekervault.plugins.ActionInspection
import io.github.brrenat.seekervault.plugins.ActionOperation
import io.github.brrenat.seekervault.plugins.ActionResolution
import io.github.brrenat.seekervault.plugins.ActionStatus
import io.github.brrenat.seekervault.plugins.ExecutionProvider
import io.github.brrenat.seekervault.plugins.JUPITER_PREDICTION
import io.github.brrenat.seekervault.plugins.JUPITER_PROVIDER
import io.github.brrenat.seekervault.plugins.JUPITER_SWAP
import io.github.brrenat.seekervault.plugins.PREDICTION_BUY_ACTION
import io.github.brrenat.seekervault.plugins.PREDICTION_BUY_SCHEMA_VERSION
import io.github.brrenat.seekervault.plugins.PROVIDER_CONTRACT
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterForm
import io.github.brrenat.seekervault.plugins.PluginDestination
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginReference
import io.github.brrenat.seekervault.plugins.PreparedOperation
import io.github.brrenat.seekervault.plugins.ProviderCapabilities
import io.github.brrenat.seekervault.plugins.SWAP_ACTION
import io.github.brrenat.seekervault.plugins.SWAP_SCHEMA_VERSION
import io.github.brrenat.seekervault.plugins.actions.ActionPayload
import io.github.brrenat.seekervault.solana.SolanaAccounts
import java.time.Instant

/**
 * `jupiter`: the first bundled implementation of the common Solana execution-provider interface
 * (SEE-145).
 *
 * It is one provider serving two provider-neutral actions — `swap` and `prediction.buy` — where
 * before SEE-145 it was two plugins named `jupiter.swap` and `jupiter.prediction`. Nothing about
 * what it does for the owner changed: the same quote, the same order, the same independent reading
 * of the bytes, the same one-attempt approval. What changed is that *who prepares the operation* is
 * now a separate, explicit thing from *what the owner wants to do*, so a second compatible Solana
 * provider can be registered beside this one without core's review, rules, wallet or history code
 * knowing it happened (docs/wiki/execution-providers.md).
 *
 * Everything Jupiter-specific is behind this file and the ones it calls: the API's shapes
 * ([JupiterProvider], [JupiterPrediction]), its program layouts ([SwapInstructions],
 * [PredictionInstructions]), its errors, its stake tokens, its minimum order and its platform link.
 * The shared Solana transaction and lookup-table machinery in `solana/` stays provider-neutral, and
 * so does the action payload every provider of these actions reads (`plugins/actions/`).
 */
class JupiterExecutionProvider(
    swapApi: JupiterProvider,
    predictionApi: JupiterPrediction,
    chain: SolanaAccounts,
    now: () -> Instant = Instant::now,
) : ExecutionProvider {

    override val capabilities: ProviderCapabilities = JUPITER_CAPABILITIES

    private val swap = JupiterSwapAction(swapApi, SWAP_CAPABILITY, now)

    private val prediction =
        JupiterPredictionAction(predictionApi, chain, PREDICTION_BUY_CAPABILITY, now)

    override fun inputs(operation: ActionOperation): ParameterForm =
        when (val payload = operation.payload) {
            is ActionPayload.Swap -> swap.inputs(payload.payload)
            is ActionPayload.PredictionBuy -> prediction.inputs(payload.payload)
        }

    override suspend fun resolve(operation: ActionOperation): ActionResolution =
        when (val payload = operation.payload) {
            // A swap has nothing live to say before a quote, and a quote is a preparation. Asking
            // for one here would be building an offer nobody asked for, so this answers from what
            // the publisher published and the route is fetched when the owner asks for it.
            is ActionPayload.Swap -> ActionResolution(swap.inputs(payload.payload))
            is ActionPayload.PredictionBuy -> prediction.resolve(payload.payload)
        }

    override suspend fun prepare(
        operation: ActionOperation,
        choice: ParameterChoice,
    ): PreparedOperation =
        when (val payload = operation.payload) {
            is ActionPayload.Swap -> swap.prepare(operation, payload.payload, choice)
            is ActionPayload.PredictionBuy -> prediction.prepare(operation, payload.payload, choice)
        }

    override fun inspect(
        operation: ActionOperation,
        choice: ParameterChoice,
        prepared: PreparedOperation,
    ): ActionInspection =
        when (val payload = operation.payload) {
            is ActionPayload.Swap -> swap.inspect(operation, payload.payload, choice, prepared)
            is ActionPayload.PredictionBuy ->
                prediction.inspect(operation, payload.payload, choice, prepared)
        }

    override fun destinations(
        operation: ActionOperation,
        references: List<PluginReference>,
    ): List<PluginDestination> =
        when (val payload = operation.payload) {
            // A swap ends on chain and the app already links the transaction there. There is
            // nowhere truthful to send anybody afterwards, so it sends them nowhere.
            is ActionPayload.Swap -> emptyList()
            is ActionPayload.PredictionBuy ->
                prediction.destinations(payload.payload, references)
        }

    /**
     * Jupiter answers no status queries, and says so rather than guessing.
     *
     * It has no read this app could turn into a fill without inventing one, and "submitted" must
     * never quietly become "filled" (docs/wiki/jupiter-prediction.md#what-is-out-of-reach). The
     * owner's honest next step is the platform link [destinations] gives them.
     */
    override suspend fun status(
        operation: ActionOperation,
        reference: PluginReference,
    ): ActionStatus = ActionStatus.Unsupported
}

/**
 * The mints Jupiter takes a prediction deposit in: its own dollar token, and USDC.
 *
 * A closed set, because a deposit mint is not a free parameter — a publisher naming something else
 * would be naming a token Jupiter will not accept, and the honest moment to say so is when the
 * signal is read rather than when the order is refused. It is a fact about *this venue* and lives
 * in its capability rather than in the action's schema, which is the separation SEE-145 is about.
 */
const val JUP_USD_MINT: String = "JuprjznTrTSp2UFa3ZBUFgwdAmtZCq4MQCwysN55USD"

/** The other one, which Jupiter swaps into its own before the order is placed. */
const val USDC_MINT: String = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

val DEPOSIT_MINTS: Set<String> = setOf(JUP_USD_MINT, USDC_MINT)

/**
 * The smallest order Jupiter accepts: five dollars, in the deposit mint's base units.
 *
 * It is the venue's rule rather than the publisher's or the app's, and it is enforced on this side
 * of the boundary so that an amount too small to act on is said before anything is asked of anybody
 * (docs/integrations/jupiter.md#prediction-orders).
 */
const val LEAST_ORDER_DEPOSIT: ULong = 5_000_000UL

/**
 * `swap`, as Jupiter serves it: mainnet, any pair the payload can name, no floor and no ceiling of
 * Jupiter's own — the owner's wallet is the real ceiling, and the publisher states any others.
 */
val SWAP_CAPABILITY: ActionCapability =
    ActionCapability(
        action = SWAP_ACTION,
        schemaVersions = SWAP_SCHEMA_VERSION..SWAP_SCHEMA_VERSION,
        networks = setOf(SWAP_NETWORK),
    )

/** `prediction.buy`, as Jupiter serves it: mainnet, its two stake tokens, its five-dollar floor. */
val PREDICTION_BUY_CAPABILITY: ActionCapability =
    ActionCapability(
        action = PREDICTION_BUY_ACTION,
        schemaVersions = PREDICTION_BUY_SCHEMA_VERSION..PREDICTION_BUY_SCHEMA_VERSION,
        networks = setOf(SWAP_NETWORK),
        depositAssets = DEPOSIT_MINTS,
        leastDeposit = LEAST_ORDER_DEPOSIT,
    )

/**
 * What this build promises about Jupiter.
 *
 * Both environments, and the same work in each: what a sandbox owner reviews is the route or the
 * order this provider actually built, and core is what stops before the wallet (SEE-97,
 * docs/wiki/environments.md). One network, in both of them, because there is no devnet Jupiter.
 *
 * [ProviderCapabilities.legacyPlugins] is how a server manifest written before SEE-145 still
 * resolves: it requires `jupiter.swap` at contract `1..1`, and this provider is what answers to
 * that name ([io.github.brrenat.seekervault.plugins.LEGACY_CAPABILITIES]).
 */
val JUPITER_CAPABILITIES: ProviderCapabilities =
    ProviderCapabilities(
        id = JUPITER_PROVIDER,
        contract = PROVIDER_CONTRACT,
        actions = listOf(SWAP_CAPABILITY, PREDICTION_BUY_CAPABILITY),
        environments = setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox),
        statusQueries = false,
        legacyPlugins = setOf(JUPITER_SWAP, JUPITER_PREDICTION),
    )
