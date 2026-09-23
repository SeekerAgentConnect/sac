package io.github.brrenat.seekervault.plugins

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.transactions.Verdict
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * A second, **test-only** execution provider (SEE-145).
 *
 * It is the acceptance criterion made executable: another provider can be registered beside
 * Jupiter, resolved for the same provider-neutral actions, and driven through exactly the same core
 * review, rules, binding and wallet path — without one line of core dispatch knowing it exists.
 * `jupiter` is written against the same interface and gets no special case anywhere.
 *
 * **It cannot be selected in a production build, and the reason is structural rather than a flag:**
 * it lives in `src/test`, so it is compiled into no APK at all — not the release one, not the debug
 * one. There is nothing to disable, nothing to strip, and no build-type condition that could be got
 * wrong. What ships is whatever `SeekerVaultApplication.providers` lists, and that lists Jupiter
 * (asserted by `BundledProvidersTest`).
 */
class TestExecutionProvider(
    id: String = "test",
    contract: Int = PROVIDER_CONTRACT,
    actions: List<ActionCapability> = listOf(testAction()),
    environments: Set<PluginEnvironment> = setOf(PluginEnvironment.Production),
    legacyPlugins: Set<PluginId> = emptySet(),
    statusQueries: Boolean = false,
    /** What [inspect] will say about the bytes; nothing established by default. */
    private val inspection: ActionInspection = ActionInspection.nothingEstablished(),
    private val form: ParameterForm = ParameterForm(),
    /** What [resolve] reports; a `var` so a test can tell one answer from the next. */
    var details: List<PluginFact> = emptyList(),
) : ExecutionProvider {
    override val capabilities =
        ProviderCapabilities(
            id = ExecutionProviderId(id),
            contract = contract,
            actions = actions,
            environments = environments,
            statusQueries = statusQueries,
            legacyPlugins = legacyPlugins,
        )

    /** Every call the boundary made, so a test can assert what core did and didn't ask for. */
    val calls = mutableListOf<String>()

    override fun inputs(operation: ActionOperation): ParameterForm {
        calls += "inputs"
        return form
    }

    /**
     * Holds [resolve] until a test releases it, for the cases about an answer that lands late.
     *
     * The wait is deliberately [NonCancellable]: a real provider read is an HTTP call whose
     * response may already be in flight when the review it was for is replaced, and a stand-in that
     * stopped the moment its job was cancelled could not exercise what happens then.
     */
    var releases: CompletableDeferred<Unit>? = null

    /** What [resolve] throws instead of answering, for the failure half of those cases. */
    var refuses: PluginFailure? = null

    override suspend fun resolve(operation: ActionOperation): ActionResolution {
        calls += "resolve"
        // What this call will answer is fixed when it starts, so a test can queue one answer
        // behind another and still tell which read produced which.
        val answer = ActionResolution(form, details)
        val refusal = refuses
        releases?.let { withContext(NonCancellable) { it.await() } }
        refusal?.let { throw it }
        return answer
    }

    override suspend fun prepare(
        operation: ActionOperation,
        choice: ParameterChoice,
    ): PreparedOperation {
        calls += "prepare"
        return PreparedOperation(ByteString.copyFromUtf8("test"), version = 1)
    }

    override fun inspect(
        operation: ActionOperation,
        choice: ParameterChoice,
        prepared: PreparedOperation,
    ): ActionInspection {
        calls += "inspect"
        return inspection
    }

    override suspend fun status(
        operation: ActionOperation,
        reference: PluginReference,
    ): ActionStatus {
        calls += "status"
        return if (capabilities.statusQueries) ActionStatus.Reported("submitted")
        else ActionStatus.Unsupported
    }

    companion object {
        /** A form with one field of each kind, to keep the parameter types exercised. */
        fun form(): ParameterForm =
            ParameterForm(
                listOf(
                    ParameterField(
                        ParameterKey("input_amount"),
                        R.string.app_name,
                        ParameterKind.Amount(mint = null, decimals = 9, most = 1_000_000_000uL),
                    ),
                    ParameterField(
                        ParameterKey("outcome"),
                        R.string.app_name,
                        ParameterKind.Choice(
                            listOf(
                                ParameterOption(ParameterKey("yes"), R.string.app_name),
                                ParameterOption(ParameterKey("no"), R.string.app_name),
                            )
                        ),
                    ),
                    ParameterField(
                        ParameterKey("slippage_bps"),
                        R.string.app_name,
                        ParameterKind.Count(least = 1u, most = 10_000u),
                    ),
                )
            )

        /** Bytes read whole, with every fact established. */
        fun verified(
            wallet: String = WALLET,
            recipient: String = RECIPIENT,
            amount: ULong = 5uL,
            mint: String? = null,
            version: Int = 1,
        ): ActionInspection =
            ActionInspection(
                verdict = Verdict.Verified,
                findings = emptyList(),
                facts =
                    InspectedAction(
                        wallet = wallet,
                        movesValue = true,
                        mint = mint,
                        recipient = recipient,
                        programs = listOf(PROGRAM),
                        amount = amount,
                        decimals = 9,
                        instructionCount = 2,
                        recognizedInstructions = 2,
                    ),
                version = version,
            )

        /** Bytes that matched as far as they were read, with one instruction left unread. */
        fun partlyRead(version: Int = 1): ActionInspection =
            verified(version = version).let {
                it.copy(
                    verdict = Verdict.Unverified,
                    findings =
                        listOf(PluginFinding("unread", R.string.app_name, invalidates = false)),
                    facts = it.facts?.copy(instructionCount = 3, recognizedInstructions = 2),
                )
            }

        const val WALLET = "7EqQdEULxWcraVx3mXKFjc84LhCkMGZCkRuDpvcMwJeK"
        const val RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        const val PROGRAM = "11111111111111111111111111111111"
    }
}

/** What a test provider serves unless a test says otherwise: `swap` v1, on mainnet, unbounded. */
fun testAction(
    action: ActionId = SWAP_ACTION,
    schemaVersions: IntRange = SWAP_SCHEMA_VERSION..SWAP_SCHEMA_VERSION,
    networks: Set<Network> = setOf(Network.NETWORK_MAINNET),
    depositAssets: Set<String>? = null,
    leastDeposit: ULong = 0uL,
    mostDeposit: ULong? = null,
): ActionCapability =
    ActionCapability(
        action = action,
        schemaVersions = schemaVersions,
        networks = networks,
        depositAssets = depositAssets,
        leastDeposit = leastDeposit,
        mostDeposit = mostDeposit,
    )

/**
 * A test provider standing exactly where Jupiter stands: the same ID, the legacy plugin name a
 * manifest written before SEE-145 requires, and one cluster.
 *
 * It is what lets the tests about *core* — a server's requirements, a proposal's standing, a
 * binding's gate — stay about core rather than about Jupiter's API. That they reach the same
 * answers about this as they do about the shipped provider is the acceptance criterion, not a
 * convenience.
 */
fun jupiterLike(
    contract: Int = PROVIDER_CONTRACT,
    actions: List<ActionCapability> =
        listOf(
            testAction(),
            testAction(
                action = PREDICTION_BUY_ACTION,
                schemaVersions = PREDICTION_BUY_SCHEMA_VERSION..PREDICTION_BUY_SCHEMA_VERSION,
            ),
        ),
    environments: Set<PluginEnvironment> =
        setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox),
    legacyPlugins: Set<PluginId> = setOf(JUPITER_SWAP),
    inspection: ActionInspection = ActionInspection.nothingEstablished(),
    form: ParameterForm = ParameterForm(),
): TestExecutionProvider =
    TestExecutionProvider(
        id = JUPITER_PROVIDER.value,
        contract = contract,
        actions = actions,
        environments = environments,
        legacyPlugins = legacyPlugins,
        inspection = inspection,
        form = form,
    )
