package io.github.brrenat.seekervault.plugins

import androidx.annotation.StringRes
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.wallet.SelectedWallet

/**
 * The boundary a bundled client plugin is written against (SEE-86, docs/wiki/client-plugins.md).
 *
 * A plugin is the part of an action this app doesn't know how to do: where the execution data comes
 * from, what the parameters of the operation are, and what the resulting bytes have to say for
 * themselves. Everything else stays where it already is. Core keeps the owner's rules, their manual
 * approval, the one wallet interaction at a time, and the local record of what happened
 * ([io.github.brrenat.seekervault.inbox.InboxViewModel],
 * [io.github.brrenat.seekervault.wallet.WalletRepository],
 * [io.github.brrenat.seekervault.activity.ActivityLog]).
 *
 * What a plugin is not given is the whole point of the interface. It receives an [ActionSubject]:
 * the request, the operation asked of it, the environment, and the public address of the wallet the
 * owner selected. It receives no credential, no wallet authorization token, no way to reach a
 * sidecar, and no means of approving or sending anything. It hands back bytes and an inspection of
 * them, and the owner's hand on the wallet is still the only thing that executes either.
 *
 * Nothing here downloads code. A plugin is compiled into this build and chosen at build time
 * ([PluginRegistry.bundled]); a server can name one it requires, and a plugin this build doesn't
 * have is reported as missing rather than fetched.
 */
interface ActionPlugin {
    /** What this plugin is, which operations it serves, and where. */
    val descriptor: PluginDescriptor

    /**
     * The parameters this operation leaves to the person using it — the amount, the outcome they
     * pick — as a description of the fields and not as a screen. The app owns its own presentation
     * (AGENTS.md#ui); a plugin says what has to be collected, and core decides how it looks.
     *
     * A shared proposal is common to everyone who received it, and what each owner chooses here is
     * theirs (SEE-89): it stays on the phone, and no choice is reported back to a publisher.
     */
    fun parameters(subject: ActionSubject): ParameterForm

    /**
     * Fetches whatever the operation needs to be executable now — a route, a quote, a market's
     * current terms — and returns the exact bytes that would be signed.
     *
     * It is called with the owner's own [choice], on their phone, and it is the plugin's own
     * business who it asks: a provider's endpoint is the plugin's, not core's. It may not sign,
     * send, or approve, and a preparation is not consent to anything.
     */
    suspend fun prepare(subject: ActionSubject, choice: ParameterChoice): PluginPreparation

    /**
     * Reads the bytes of [prepared] and says what they establish, as typed facts the owner's rules
     * can be applied to ([ActionInspection]).
     *
     * The order is the one the transfer path already uses (docs/security.md#inspecting-a-transfer):
     * the bytes are read first and judged afterwards. What the provider said it built is a claim
     * about the bytes and never evidence about them, and an instruction the plugin can't account
     * for is a gap in the review rather than a byte that turned out to be safe.
     */
    fun inspect(subject: ActionSubject, prepared: PluginPreparation): ActionInspection
}

/**
 * What a plugin is, as a stable identity a server manifest can name (SEE-88) and a build can
 * select.
 *
 * [contract] is the version of *this* interface the plugin was written against, not the plugin's
 * own release number. It is compared with [SUPPORTED_PLUGIN_CONTRACTS], so a plugin built for a
 * boundary this app doesn't speak is reported as unsupported instead of being called through a
 * contract neither side agrees on.
 */
data class PluginDescriptor(
    val id: PluginId,
    val contract: Int,
    /** The operations it serves. A plugin that serves none can't be resolved for anything. */
    val operations: Set<OperationId>,
    /**
     * The environments it serves them in. Sandbox and production are different promises about what
     * happens when the owner approves (SEE-97), and a plugin says which ones it can keep.
     */
    val environments: Set<PluginEnvironment>,
) {
    /** Whether this plugin's contract is one this build of the boundary can call. */
    val contractSupported: Boolean
        get() = contract in SUPPORTED_PLUGIN_CONTRACTS
}

/**
 * The version of this boundary. It goes up when a change to [ActionPlugin] would make an older
 * plugin wrong to call, and [SUPPORTED_PLUGIN_CONTRACTS] then names the ones still callable.
 */
const val PLUGIN_CONTRACT: Int = 1

/** The contract versions this build calls. A plugin outside it is unsupported, not adapted. */
val SUPPORTED_PLUGIN_CONTRACTS: IntRange = PLUGIN_CONTRACT..PLUGIN_CONTRACT

/**
 * A plugin's stable identity, as a manifest writes it: lowercase, dot-separated segments, such as
 * `jupiter.swap`. It is a name and nothing else — never a URL, a package, or anything loadable.
 */
@JvmInline
value class PluginId(val value: String) {
    init {
        require(isPluginId(value)) { "not a plugin ID: $value" }
    }

    override fun toString(): String = value
}

/**
 * What a request asks for, named at the protocol's own level (docs/protocol.md#actions): `swap`,
 * not the provider that serves it.
 *
 * That is deliberate. Core says which operation a request is for, and a plugin claims the ones it
 * can do, so nothing in core has to know that Jupiter is what makes `swap` work.
 */
@JvmInline
value class OperationId(val value: String) {
    init {
        require(isOperationId(value)) { "not an operation: $value" }
    }

    override fun toString(): String = value
}

/** Lowercase dot-separated segments, each starting with a letter: `jupiter.swap`. */
private val ID_PATTERN = Regex("""[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*""")

fun isPluginId(value: String): Boolean =
    value.length in 1..128 && ID_PATTERN.matches(value) && value.contains('.')

fun isOperationId(value: String): Boolean = value.length in 1..64 && ID_PATTERN.matches(value)

/**
 * Which promise the app is keeping when the owner approves (SEE-97 makes it their choice; until
 * then core asks for [Production] and nothing selects the other).
 */
enum class PluginEnvironment(val code: String) {
    /** A real operation, on the network the owner's wallet is selected for. */
    Production("production"),

    /**
     * Live public market data where it exists, and execution that is clearly simulated. It is not a
     * claim that a provider runs a test trading service.
     */
    Sandbox("sandbox"),
}

/**
 * Everything core hands a plugin, and the list is exhaustive on purpose (SEE-86).
 *
 * [wallet] is the selection as this phone holds it: a public address, the network it was selected
 * for, and nothing else — the wallet's authorization token stays in
 * [io.github.brrenat.seekervault.wallet.storage.WalletStore] and reaches no plugin (SEE-84). Null
 * when no wallet is connected, which a plugin must handle by establishing nothing rather than by
 * guessing an address.
 */
data class ActionSubject(
    /** The connection the request came from. A plugin never reaches it. */
    val connectionId: String,
    val operation: OperationId,
    val environment: PluginEnvironment,
    /** The structured request, as the protocol carries it. */
    val request: ActionRequest,
    val wallet: SelectedWallet?,
)

/**
 * The fields one operation leaves to the owner. Every field has a stable [ParameterKey], so what
 * they chose can be kept and compared without depending on how it was shown.
 */
data class ParameterForm(val fields: List<ParameterField> = emptyList()) {
    val isEmpty: Boolean
        get() = fields.isEmpty()
}

data class ParameterField(
    val key: ParameterKey,
    /** The plugin's own string resource. Text stays in resources, not in code. */
    @StringRes val label: Int,
    val kind: ParameterKind,
)

/** A field's stable name within its plugin, such as `input_amount`. */
@JvmInline
value class ParameterKey(val value: String) {
    init {
        require(isOperationId(value)) { "not a parameter: $value" }
    }

    override fun toString(): String = value
}

/** What kind of value a field takes. Base units throughout: nothing here rounds anything. */
sealed interface ParameterKind {
    /**
     * An amount in the asset's own base units — lamports for SOL, the mint's units for a token —
     * which is what the transaction carries and what a rule is written in
     * (docs/policy.md#advisory-thresholds). [decimals] is for display only, and the chain is not
     * named here: it is the one the owner's wallet is selected for, and core supplies it.
     */
    data class Amount(
        /** The SPL mint the amount is in, or null for native SOL. */
        val mint: String?,
        val decimals: Int,
        val most: ULong? = null,
    ) : ParameterKind

    /** One of a fixed set: the outcome of a market, the side of a signal. */
    data class Choice(val options: List<ParameterOption>) : ParameterKind

    /** A bounded whole number, such as a slippage tolerance in basis points. */
    data class Count(val least: UInt, val most: UInt) : ParameterKind
}

data class ParameterOption(val key: ParameterKey, @StringRes val label: Int)

/**
 * What the owner chose, on their own phone. It is never sent to a publishing server or to a shared
 * gateway (SEE-89): a shared proposal is common, and the decision about it is not.
 */
data class ParameterChoice(val values: Map<ParameterKey, ParameterValue> = emptyMap()) {
    operator fun get(key: ParameterKey): ParameterValue? = values[key]
}

sealed interface ParameterValue {
    data class Amount(val baseUnits: ULong) : ParameterValue

    data class Selected(val option: ParameterKey) : ParameterValue

    data class Count(val value: UInt) : ParameterValue
}

/**
 * What a plugin prepared: the exact bytes that would be signed, and the version they are the
 * plugin's answer for.
 *
 * The bytes are the whole of it. There is no description of them here for anything to read instead,
 * because the account a provider gives of what it built is not evidence about what it built — the
 * inspection reads the bytes ([ActionPlugin.inspect]).
 */
data class PluginPreparation(
    val transaction: ByteString,
    /**
     * Which preparation this is, counted by the plugin. An approval names the version it was given
     * for, so a transaction prepared again is a different thing to approve (SAW-021).
     */
    val version: Int,
    /**
     * When the bytes stop being includable, in epoch seconds; null when they carry no deadline.
     *
     * It is part of the contract rather than the plugin's own business because the freshness check
     * belongs where the wallet is opened: a transfer's window is re-checked on the far side of the
     * wait for the wallet lock, with the sidecar's own margin, and a plugin's preparation has to be
     * checkable the same way (`InboxViewModel.stillFresh`, docs/guides/transfers.md). A preparation
     * with no deadline is taken as fresh, so a plugin whose bytes do expire must state when.
     */
    val expiresAtEpochSeconds: Long? = null,
)
