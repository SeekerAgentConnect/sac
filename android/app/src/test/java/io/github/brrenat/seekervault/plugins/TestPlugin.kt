package io.github.brrenat.seekervault.plugins

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.transactions.Verdict

/**
 * A plugin written against the boundary and nothing else (SEE-86).
 *
 * It exists so the registry, the resolution reasons and the facts mapping can be exercised without
 * a provider, a network call, or a real operation — and so the acceptance holds honestly: a plugin
 * can be registered, and rejected as unsupported, with nothing provider-specific anywhere in core.
 * `jupiter.swap` (SEE-93) and `jupiter.prediction` (SEE-94) are written the same way.
 */
class TestPlugin(
    id: String = "test.operation",
    contract: Int = PLUGIN_CONTRACT,
    operations: Set<OperationId> = setOf(SWAP_OPERATION),
    environments: Set<PluginEnvironment> = setOf(PluginEnvironment.Production),
    /** What [inspect] will say about the bytes; nothing established by default. */
    private val inspection: ActionInspection = ActionInspection.nothingEstablished(),
    private val form: ParameterForm = ParameterForm(),
) : ActionPlugin {
    override val descriptor =
        PluginDescriptor(
            id = PluginId(id),
            contract = contract,
            operations = operations,
            environments = environments,
        )

    /** Every call the boundary made, so a test can assert what core did and didn't ask for. */
    val calls = mutableListOf<String>()

    override fun parameters(subject: ActionSubject): ParameterForm {
        calls += "parameters"
        return form
    }

    override suspend fun prepare(
        subject: ActionSubject,
        choice: ParameterChoice,
    ): PluginPreparation {
        calls += "prepare"
        return PluginPreparation(ByteString.copyFromUtf8("test"), version = 1)
    }

    override fun inspect(
        subject: ActionSubject,
        choice: ParameterChoice,
        prepared: PluginPreparation,
    ): ActionInspection {
        calls += "inspect"
        return inspection
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
