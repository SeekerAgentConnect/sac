package io.github.brrenat.seekervault.plugins

import androidx.annotation.StringRes
import io.github.brrenat.seekervault.plugins.actions.ActionPayload
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.wallet.SelectedWallet

/**
 * The boundary a bundled Solana execution provider is written against (SEE-145, SEE-86,
 * docs/wiki/execution-providers.md).
 *
 * A provider is the part of an action this app doesn't know how to do: where the execution data
 * comes from, what the current terms of the operation are, and what the resulting bytes have to say
 * for themselves. Everything else stays where it already is. Core keeps the owner's rules, their
 * manual approval, the one wallet interaction at a time, and the local record of what happened
 * ([io.github.brrenat.seekervault.inbox.InboxViewModel],
 * [io.github.brrenat.seekervault.wallet.WalletRepository],
 * [io.github.brrenat.seekervault.activity.ActivityLog]).
 *
 * ## What SEE-145 separated
 *
 * Until this stage the two things a request names were one thing: `jupiter.swap` was both *what the
 * owner wants to do* and *who prepares it*. They are now separate, and the separation is the whole
 * point of this file:
 *
 * - An **action** is provider-neutral and versioned: `swap` at schema 1, `prediction.buy` at schema
 *   1 ([ActionId], [io.github.brrenat.seekervault.plugins.actions.ActionPayload]). It says what is
 *   being done, in terms every provider of it has to mean the same thing by.
 * - An **execution provider** is [ExecutionProviderId] — `jupiter` — and it is named explicitly.
 *   Nothing routes between providers, nothing falls back to a second one, and a prediction market
 *   at one venue is never treated as the same instrument as a similarly named market at another
 *   ([io.github.brrenat.seekervault.plugins.actions.Instrument]).
 *
 * The legacy `jupiter.swap` and `jupiter.prediction` names keep working, through one explicit table
 * and nowhere else ([LEGACY_CAPABILITIES]).
 *
 * ## What a provider is not given, which is the point of the interface
 *
 * It receives an [ActionOperation]: the action asked of it, its own identity, the environment, the
 * network the wallet is selected for, the typed payload, and the public address of the wallet the
 * owner selected. It receives no credential, no wallet authorization token, no way to reach a
 * sidecar, and no means of approving or sending anything. It hands back bytes and an inspection of
 * them, and the owner's hand on the wallet is still the only thing that executes either.
 *
 * Nothing here downloads code. A provider is compiled into this build and chosen at build time
 * (`SeekerVaultApplication.providers`); a server can name one it requires, and a provider this
 * build doesn't have is reported as missing rather than fetched.
 */
interface ExecutionProvider {
    /** Who this provider is, which actions it serves, where, and within what limits. */
    val capabilities: ProviderCapabilities

    /**
     * The parameters this operation leaves to the person using it — the amount, the outcome they
     * pick — as a description of the fields and not as a screen. The app owns its own presentation
     * (AGENTS.md#ui); a provider says what has to be collected, and core decides how it looks.
     *
     * It reaches nothing and cannot fail, so a review opens with the fields already on it. What the
     * *market* currently says is [resolve]'s, and it arrives afterwards.
     *
     * A shared proposal is common to everyone who received it, and what each owner chooses here is
     * theirs (SEE-89): it stays on the phone, and no choice is reported back to a publisher.
     */
    fun inputs(operation: ActionOperation): ParameterForm

    /**
     * Fetches what the provider currently says about this action — a market's state, an asset's
     * current constraints — and returns the input constraints as they stand now.
     *
     * It is a read and nothing else: no order is placed, no wallet is touched, and nothing about it
     * is binding. Its answer refines what [inputs] already returned and is shown beside the fields;
     * a provider with nothing live to say keeps the default and answers immediately.
     *
     * Raises [PluginFailure] when the provider cannot be read at all, which core shows and which
     * leaves the declared constraints in place rather than inventing tighter ones.
     */
    suspend fun resolve(operation: ActionOperation): ActionResolution =
        ActionResolution(inputs(operation))

    /**
     * Builds the operation for the owner's explicit [choice] and returns the exact bytes that would
     * be signed.
     *
     * It is called with the owner's own choice, on their phone, and it is the provider's own
     * business who it asks: a provider's endpoint is the provider's, not core's. It may not sign,
     * send, or approve, and a preparation is not consent to anything.
     *
     * Raises [PluginFailure] when it cannot produce the exact bytes — the provider is unreachable
     * or rate-limited, there is no route, the market has closed — because there is no approximate
     * preparation to fall back on (SEE-93).
     */
    suspend fun prepare(operation: ActionOperation, choice: ParameterChoice): PreparedOperation

    /**
     * Reads the bytes of [prepared] and says what they establish, as typed facts the owner's rules
     * can be applied to ([ActionInspection]).
     *
     * The order is the one the transfer path already uses (docs/security.md#inspecting-a-transfer):
     * the bytes are read first and judged afterwards. What the provider said it built is a claim
     * about the bytes and never evidence about them, and an instruction the provider can't account
     * for is a gap in the review rather than a byte that turned out to be safe.
     *
     * [choice] is passed in rather than remembered, and it is core's copy — the one the owner
     * reviewed and the one an execution is bound to (`ProposalReview`, `ExecutionBinding`). So the
     * question being answered is "do these bytes do what *the owner* chose", and a provider that
     * asked its API for something else is caught by the app rather than trusted about it.
     */
    fun inspect(
        operation: ActionOperation,
        choice: ParameterChoice,
        prepared: PreparedOperation,
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
     * Two rules. A destination is built from something this provider validated, never from a
     * publisher's prose — a publisher may *name* one (SEE-157), and it counts for nothing until
     * this provider recognizes it as its own. And a destination that does not exist is not
     * invented: a provider with no address for a thing gets no link to it, and says so by returning
     * none (docs/wiki/jupiter-prediction.md#where-the-owner-continues).
     *
     * [references] are the identifiers this provider itself named for an operation that was already
     * submitted ([ActionInspection.references], kept in the owner's record). They are how a
     * destination can be about *this order* rather than about the market it was placed on, and they
     * are empty before anything has been prepared — so a provider that needs one returns nothing
     * until it has one, which is what "do not offer an action there is no destination for" means.
     */
    fun destinations(
        operation: ActionOperation,
        references: List<PluginReference> = emptyList(),
    ): List<PluginDestination> = emptyList()

    /**
     * What the provider says has become of an operation this phone already submitted, when the
     * provider can answer that truthfully ([ProviderCapabilities.statusQueries]).
     *
     * Contract 1 defines the question and answers it honestly for the one provider that exists:
     * Jupiter declares no status queries and returns [ActionStatus.Unsupported], because it has no
     * read that would let this app turn "submitted" into "filled" without guessing. **Nothing in
     * this app polls it.** There is no fill monitoring, no positions screen and no settlement here,
     * and SEE-145 deliberately did not add any: the method exists so that a provider that genuinely
     * can answer has somewhere to say so, and so that the shape of that answer is decided in the
     * open rather than in whichever screen first wanted it.
     */
    suspend fun status(
        operation: ActionOperation,
        reference: PluginReference,
    ): ActionStatus = ActionStatus.Unsupported
}

/**
 * What a provider currently says about one action: the constraints on the owner's inputs, and
 * whatever it established while reading them.
 *
 * [details] are for the owner to read and never for a rule to evaluate — the same rule as
 * [ActionInspection.details]. [problem] is how a provider says the action cannot be served as it
 * stands, such as a market that has closed: core shows it and offers no preparation.
 */
data class ActionResolution(
    val form: ParameterForm,
    val details: List<PluginFact> = emptyList(),
    val problem: PluginFinding? = null,
)

/**
 * What a provider can say about an operation this phone already submitted
 * ([ExecutionProvider.status]).
 */
sealed interface ActionStatus {
    /** This provider does not answer status queries, and does not pretend to. */
    data object Unsupported : ActionStatus

    /** It answers them, and has nothing to say about this one. */
    data object Unknown : ActionStatus

    /**
     * The provider's own words about it, as a stable [code] and a display-only [detail]. It is
     * never turned into a fill, a settlement or a profit by anything in this app.
     */
    data class Reported(val code: String, val detail: String? = null) : ActionStatus
}

/**
 * Somewhere the owner may continue, outside this app (SEE-94).
 *
 * [label] is the provider's own string resource, so the words stay in resources, and both addresses
 * are built at the moment they are shown rather than stored: a link read back off disk is a link
 * something else could have written, and this app hands nothing to another app that it did not just
 * construct itself (`activity/Explorer.kt` does the same with the one address it knows).
 *
 * [deepLink] is where the provider's own app answers, when the provider has one and this build
 * knows the address; [url] is the same place on the web. Opening a destination tries the app first
 * and the web only if no app took it, so an owner with the provider installed lands in it rather
 * than in a browser (SEE-157, `activity/Explorer.kt`). A provider with only a web address leaves
 * [deepLink] null, which is the ordinary case and not a lesser one.
 */
data class PluginDestination(
    @StringRes val label: Int,
    val url: String,
    val deepLink: String? = null,
)

/**
 * What a provider is, as a stable identity a server manifest can name (SEE-88) and a build can
 * select.
 *
 * [contract] is the version of *this* interface the provider was written against, not the
 * provider's own release number. It is compared with [SUPPORTED_PROVIDER_CONTRACTS], so a provider
 * built for a boundary this app doesn't speak is reported as unsupported instead of being called
 * through a contract neither side agrees on.
 */
data class ProviderCapabilities(
    val id: ExecutionProviderId,
    val contract: Int,
    /**
     * The actions it serves, each with the schema versions, networks and limits it serves them at.
     */
    val actions: List<ActionCapability>,
    /**
     * The environments it serves them in. Sandbox and production are different promises about what
     * happens when the owner approves (SEE-97), and a provider says which ones it can keep.
     *
     * A provider that serves both does the same work in each: whether the bytes it prepared are
     * signed is core's decision, at the wallet, and a provider is never asked to enforce it. The
     * field is here for the provider that genuinely cannot serve one — one with no public read to
     * rehearse against, say — and [ProviderRegistry.resolve] reports that as unsupported rather
     * than letting it be called for something it said it could not do.
     *
     * **An environment is not a network.** A shared interface must not imply that a provider has a
     * testnet; which Solana clusters it serves is [ActionCapability.networks] and is checked
     * separately and always.
     */
    val environments: Set<PluginEnvironment>,
    /** Whether [ExecutionProvider.status] answers anything. Nothing in this app polls it. */
    val statusQueries: Boolean = false,
    /**
     * The bundled-plugin names this provider answers to, for servers and documents written before
     * SEE-145 ([LEGACY_CAPABILITIES]). It is a compatibility claim and never a way to select code.
     */
    val legacyPlugins: Set<PluginId> = emptySet(),
) {
    /** Whether this provider's contract is one this build of the boundary can call. */
    val contractSupported: Boolean
        get() = contract in SUPPORTED_PROVIDER_CONTRACTS

    /** What it says about [action], or null when it does not serve it at all. */
    fun forAction(action: ActionId): ActionCapability? = actions.firstOrNull { it.action == action }
}

/**
 * One action a provider serves, and the whole of what it promises about it (SEE-145).
 *
 * Everything here is checked before anything is prepared, so an owner is told that their wallet is
 * on the wrong cluster, or that a publisher named a stake token this provider will not take, while
 * it is still a sentence on a screen rather than a refusal from an API halfway through an order.
 */
data class ActionCapability(
    val action: ActionId,
    /**
     * The action payload schema versions it reads. An action is versioned independently of this
     * interface: the boundary can stay at contract 1 while `swap` gains a schema 2, and a provider
     * says which of them it understands.
     */
    val schemaVersions: IntRange,
    /**
     * The Solana clusters it serves the action on. Deliberately explicit and deliberately not
     * derived from the environment: Jupiter routes liquidity that exists on mainnet and there is no
     * devnet Jupiter, in sandbox or out of it.
     */
    val networks: Set<Network>,
    /**
     * The mints it accepts as the asset put in — the deposit of a prediction order, the input of a
     * swap — or null when it takes any mint the action's payload can name.
     *
     * It is the provider's rule and lives here rather than in the action's schema, because "this
     * venue settles in these two dollar tokens" is a fact about the venue. A publisher naming
     * something else has named a token this provider will not take, and the honest moment to say so
     * is when the signal is read.
     */
    val depositAssets: Set<String>? = null,
    /** The least it will act on, in the deposit asset's base units. Zero when it sets no floor. */
    val leastDeposit: ULong = 0UL,
    /** The most, or null when the provider sets no ceiling of its own. */
    val mostDeposit: ULong? = null,
) {
    /** Whether this capability covers [schemaVersion] of the action's payload. */
    fun reads(schemaVersion: Int): Boolean = schemaVersion in schemaVersions
}

/**
 * The version of this boundary. It goes up when a change to [ExecutionProvider] would make an older
 * provider wrong to call, and [SUPPORTED_PROVIDER_CONTRACTS] then names the ones still callable.
 *
 * **SEE-145 raises it to 2**, and this is the first raise. Contract 1 was the plugin boundary
 * SEE-86 landed and SEE-93/94/97 settled: a plugin declared a `PluginId`, a set of operations and a
 * set of environments, and was called through `parameters`/`prepare`/`inspect`/`destinations`.
 * Every one of those is different now — the identity is a provider rather than a
 * provider-and-action, an action carries a schema version, capabilities carry networks and limits,
 * `parameters` became [inputs] beside a new [resolve], and the operation handed over carries a
 * typed payload rather than a map of publisher strings. A contract-1 plugin cannot be called
 * through any of that, which is exactly what a contract change means.
 *
 * Publishers are unaffected by the raise, and that is not a coincidence: what a *server* declares
 * is still a plugin name and a contract range (`jupiter.swap` at `1..1`), and those keep resolving
 * through [LEGACY_CAPABILITIES] against the provider that answers to them. The number in this file
 * is about the code inside the APK; the number in a manifest is about the agreement with a server,
 * and SEE-145 deliberately did not move the second one (docs/wiki/execution-providers.md).
 */
const val PROVIDER_CONTRACT: Int = 2

/** The contract versions this build calls. A provider outside it is unsupported, not adapted. */
val SUPPORTED_PROVIDER_CONTRACTS: IntRange = PROVIDER_CONTRACT..PROVIDER_CONTRACT

/**
 * Everything core hands a provider, and the list is exhaustive on purpose (SEE-86, SEE-145).
 *
 * [wallet] is the selection as this phone holds it: a public address, the network it was selected
 * for, and nothing else — the wallet's authorization token stays in
 * [io.github.brrenat.seekervault.wallet.storage.WalletStore] and reaches no provider (SEE-84). Null
 * when no wallet is connected, which a provider must handle by establishing nothing rather than by
 * guessing an address.
 */
data class ActionOperation(
    /** The connection the request or the feed came from. A provider never reaches it. */
    val connectionId: String,
    /** What is being done, provider-neutrally. */
    val action: ActionId,
    /** Which version of that action's payload schema [payload] was read as. */
    val schemaVersion: Int,
    /** Who was asked to do it. A provider may assert that this is itself; nothing routes. */
    val provider: ExecutionProviderId,
    val environment: PluginEnvironment,
    /**
     * The chain this would happen on, which is the one the owner's wallet is selected for. It is
     * core's fact rather than the provider's, exactly as it is for a rule
     * (docs/policy.md#what-is-evaluated), and a provider that does not serve it was refused before
     * it was ever called ([ProviderRegistry.resolve]).
     */
    val network: Network,
    /**
     * The action's payload, already read and validated against its own schema by core
     * ([io.github.brrenat.seekervault.plugins.actions.actionPayloadFrom]).
     *
     * SEE-145's replacement for the bag of publisher strings a plugin used to parse for itself. A
     * publisher is a stranger, and every provider of an action has to refuse the same malformed
     * document in the same way; doing that once, before any provider is consulted, is the only way
     * that stays true when there is more than one of them.
     */
    val payload: ActionPayload,
    /**
     * The structured request, as the protocol carries it — or null, because a broadcast proposal
     * has none (SEE-89).
     *
     * The two kinds of thing a provider can be asked about are genuinely different, and the
     * difference is not a detail: a request is addressed to this phone by a server it is paired
     * with, and names the wallet it expects; a proposal is addressed to nobody in particular, is
     * identical for everyone who received it, and names no subscriber at all.
     */
    val request: ActionRequest? = null,
    val wallet: SelectedWallet? = null,
)

/**
 * Why a provider could not prepare anything (SEE-93).
 *
 * [ExecutionProvider.prepare] reaches a provider's API, and reaching one fails: it is unreachable,
 * rate-limited, has no route for the pair, or answers with something unusable. None of that is a
 * verdict on the operation and none of it is a reason to carry on with something approximate, so
 * preparing either produces the exact bytes or raises this.
 *
 * [code] is stable and is what a record keeps; [explanation] is the provider's own string resource,
 * so the words stay in resources — it is not called `message`, because [Throwable] already has one
 * and that one is for a log; [detail] is a provider's own words when it gave any, for display only
 * and never for parsing.
 */
class PluginFailure(
    val code: String,
    @StringRes val explanation: Int,
    val detail: String? = null,
) : Exception("provider could not prepare: $code")

/**
 * Which promise is being kept when the owner approves (SEE-97, docs/wiki/environments.md).
 *
 * It belongs to a connection rather than to the app or to a provider. A server's manifest says
 * which environments it *serves*; the connection records the one it *keeps*, and only the owner
 * changes that — so no document a publisher republishes can move a demonstration onto real money,
 * and one phone holds a sandbox feed and a production one at the same time without either affecting
 * the other.
 *
 * Neither value is a Solana cluster and neither selects one. The cluster is the network the owner's
 * wallet is selected for, it is checked separately and always ([ActionOperation.network]), and
 * which clusters a provider serves is its own declaration ([ActionCapability.networks]): there is
 * no Jupiter test network to point [Sandbox] at, and inventing one would be worse than saying so.
 *
 * [code] is the word the publisher's own configuration, its API and its logs use, so an operator
 * and an owner are reading the same two words about the same deployment
 * (`publisher/internal/environment`).
 */
enum class PluginEnvironment(val code: String) {
    /** A real operation, on the network the owner's wallet is selected for. */
    Production("production"),

    /**
     * Live public market data where it exists, the same review, and an execution that is simulated
     * and said to be: nothing is signed, nothing is sent, and no signature or explorer link is
     * invented for something that did not happen.
     *
     * It is not a claim that a provider runs a test trading service — none of them does — and it is
     * not a smaller kind of approval. A provider prepares identically in both environments, because
     * a rehearsal of something other than the real thing would demonstrate nothing; what stops is
     * core, at the wallet.
     */
    Sandbox("sandbox"),
}

/**
 * What a provider prepared: the exact bytes that would be signed, and the version they are the
 * provider's answer for.
 *
 * The bytes are the whole of it. There is no description of them here for anything to read instead,
 * because the account a provider gives of what it built is not evidence about what it built — the
 * inspection reads the bytes ([ExecutionProvider.inspect]).
 */
data class PreparedOperation(
    val transaction: com.google.protobuf.ByteString,
    /**
     * Which preparation this is, counted by the provider. An approval names the version it was
     * given for, so a transaction prepared again is a different thing to approve (SAW-021).
     */
    val version: Int,
    /**
     * When the bytes stop being includable, in epoch seconds; null when they carry no deadline.
     *
     * It is part of the contract rather than the provider's own business because the freshness
     * check belongs where the wallet is opened: a transfer's window is re-checked on the far side
     * of the wait for the wallet lock, with the sidecar's own margin, and a provider's preparation
     * has to be checkable the same way (`InboxViewModel.stillFresh`, docs/guides/transfers.md). A
     * preparation with no deadline is taken as fresh, so a provider whose bytes do expire must
     * state when.
     */
    val expiresAtEpochSeconds: Long? = null,
)
