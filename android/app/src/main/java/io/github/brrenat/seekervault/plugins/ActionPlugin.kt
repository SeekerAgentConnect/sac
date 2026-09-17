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
 * the operation asked of it, the environment, either a request or a broadcast proposal's terms, and
 * the public address of the wallet the owner selected. It receives no credential, no wallet
 * authorization token, no way to reach a sidecar, and no means of approving or sending anything. It
 * hands back bytes and an inspection of them, and the owner's hand on the wallet is still the only
 * thing that executes either.
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
     *
     * Raises [PluginFailure] when it cannot produce the exact bytes — the provider is unreachable
     * or rate-limited, there is no route, the environment does not execute — because there is no
     * approximate preparation to fall back on (SEE-93).
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
     *
     * [choice] is passed in rather than remembered, and it is core's copy — the one the owner
     * reviewed and the one an execution is bound to (`ProposalReview`, `ExecutionBinding`). So the
     * question being answered is "do these bytes do what *the owner* chose", and a plugin that
     * asked its provider for something else is caught by the app rather than trusted about it.
     */
    fun inspect(
        subject: ActionSubject,
        choice: ParameterChoice,
        prepared: PluginPreparation,
    ): ActionInspection

    /**
     * Where the owner can carry on with this operation outside the app, if anywhere truthful exists
     * (SEE-94).
     *
     * Some operations end where this app's part of them ends. A market order is one: the app opens
     * a position and does not follow it — no fill, no settlement, no payout, no profit or loss — so
     * the honest finish is a link to the provider's own platform rather than a screen that pretends
     * to know more.
     *
     * Two rules. A destination is built from something this plugin validated, never from a
     * publisher's prose. And a destination that does not exist is not invented: a provider with no
     * address for a position gets no position link, and the owner is sent to the market instead
     * (docs/wiki/jupiter-prediction.md#where-the-owner-continues).
     *
     * It is defaulted because most operations have nowhere to send anybody, and an empty list is
     * the truthful answer for them.
     */
    fun destinations(subject: ActionSubject): List<PluginDestination> = emptyList()
}

/**
 * Somewhere the owner may continue, outside this app (SEE-94).
 *
 * [label] is the plugin's own string resource, so the words stay in resources, and [url] is built
 * at the moment it is shown rather than stored: a link read back off disk is a link something else
 * could have written, and this app hands nothing to a browser that it did not just construct itself
 * (`activity/Explorer.kt` does the same with the one address it knows).
 */
data class PluginDestination(@StringRes val label: Int, val url: String)

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
 *
 * It is still 1 after SEE-93, and that is a decision rather than an oversight. SEE-86 landed the
 * boundary before anything was written against it and SEE-93 is the first thing written against it,
 * so what contract 1 *means* — that a subject may be a broadcast proposal's terms instead of a
 * request, and that preparing can fail with a [PluginFailure] — is settled here rather than changed
 * here. Nothing outside this stage could be affected: the app has never carried a plugin, nothing
 * had been published against it, and the manifests that require `jupiter.swap` at `1..1` are this
 * stage's own. Publishers exist now — SEE-95's template publishes `jupiter.swap` at `1..1` and
 * SEE-96's publishes `jupiter.prediction` at `1..1` — so the next change to [ActionPlugin] raises
 * this number rather than redefining it.
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
    /** The connection the request or the feed came from. A plugin never reaches it. */
    val connectionId: String,
    val operation: OperationId,
    val environment: PluginEnvironment,
    /**
     * The structured request, as the protocol carries it — or null, because a broadcast proposal
     * has none (SEE-89).
     *
     * The two kinds of thing a plugin can be asked about are genuinely different, and the
     * difference is not a detail: a request is addressed to this phone by a server it is paired
     * with, and names the wallet it expects; a proposal is addressed to nobody in particular, is
     * identical for everyone who received it, and names no subscriber at all. Exactly one of this
     * and [terms] describes what is being asked.
     */
    val request: ActionRequest?,
    val wallet: SelectedWallet?,
    /**
     * A broadcast proposal's common terms, as its publisher wrote them; empty for a request.
     *
     * Core carries them and interprets none of them (`Proposal.values`): which keys mean what
     * belongs to the plugin that serves the operation, and the publisher's prose is never evidence
     * about anything. They are bounded before they get here — at most 32 of them, each short enough
     * to show — because a publisher is a stranger.
     */
    val terms: Map<String, String> = emptyMap(),
)

/**
 * Why a plugin could not prepare anything (SEE-93).
 *
 * [ActionPlugin.prepare] reaches a provider, and reaching a provider fails: it is unreachable,
 * rate-limited, has no route for the pair, or answers with something unusable. None of that is a
 * verdict on the operation and none of it is a reason to carry on with something approximate, so
 * preparing either produces the exact bytes or raises this.
 *
 * [code] is stable and is what a record keeps; [explanation] is the plugin's own string resource,
 * so the words stay in resources — it is not called `message`, because [Throwable] already has one
 * and that one is for a log; [detail] is a provider's own words when it gave any, for display only
 * and never for parsing.
 */
class PluginFailure(
    val code: String,
    @StringRes val explanation: Int,
    val detail: String? = null,
) : Exception("plugin could not prepare: $code")

/**
 * The fields one operation leaves to the owner. Every field has a stable [ParameterKey], so what
 * they chose can be kept and compared without depending on how it was shown.
 */
data class ParameterForm(
    val fields: List<ParameterField> = emptyList(),
    /**
     * Why there is nothing to collect, when that is the reason: the terms a publisher broadcast
     * cannot be read as this operation at all (SEE-93).
     *
     * It is told apart from an empty form because the two are opposite situations. An operation
     * with no parameters is ready to prepare; a document the plugin cannot make sense of is one
     * nothing will ever be prepared from, and the owner is owed the reason — which term, and what
     * was wrong with it. Core shows it and offers no preparation.
     */
    val problem: PluginFinding? = null,
) {
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
        /**
         * The least that may be entered, in the same base units. Zero unless the operation has a
         * floor of its own — a publisher's minimum, a provider's dust limit — and it is a bound on
         * the field rather than advice about it: below it there is nothing to prepare.
         */
        val least: ULong = 0UL,
    ) : ParameterKind

    /** One of a fixed set: the outcome of a market, the side of a signal. */
    data class Choice(val options: List<ParameterOption>) : ParameterKind

    /** A bounded whole number, such as a slippage tolerance in basis points. */
    data class Count(
        val least: UInt,
        val most: UInt,
        /**
         * What the field starts at before the owner touches it. A plugin states it because a
         * sensible starting point is knowledge about the operation — the usual slippage for a pair
         * — and core has none of that; it is a starting point and never a choice, which is still
         * only ever [ParameterChoice].
         */
        val initial: UInt = least,
    ) : ParameterKind
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
