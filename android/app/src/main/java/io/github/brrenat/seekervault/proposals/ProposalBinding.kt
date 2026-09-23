package io.github.brrenat.seekervault.proposals

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.plugins.ActionId
import io.github.brrenat.seekervault.plugins.ExecutionProviderId
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.ProviderRegistry
import io.github.brrenat.seekervault.plugins.ProviderResolution
import io.github.brrenat.seekervault.plugins.SUPPORTED_PROVIDER_CONTRACTS
import io.github.brrenat.seekervault.plugins.UnsupportedReason
import io.github.brrenat.seekervault.plugins.actions.ActionPayloadResult
import io.github.brrenat.seekervault.plugins.actions.Instrument
import io.github.brrenat.seekervault.plugins.actions.actionPayloadFrom
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.servers.ServerSupport
import io.github.brrenat.seekervault.wallet.SelectedWallet
import java.time.Instant

/**
 * What is bound before the wallet is opened, and the one rule that stops it (SEE-89).
 *
 * A proposal is common to everyone who received it, so the thing an owner executes is never the
 * proposal: it is the proposal's terms *as they stood*, with the parameters this owner chose, from
 * the wallet they had selected, prepared by the plugin this build carries, as a particular set of
 * bytes. All five have to be pinned together, because any one of them changing means the owner is
 * about to sign something other than what they reviewed
 * (docs/wiki/shared-proposals.md#what-a-signature-is-bound-to).
 *
 * The [ExecutionBinding] is written to the record before the wallet is asked anything, and it is
 * the record of what this phone attempted whatever happens next.
 */
data class ExecutionBinding(
    /** The exact proposal revision the terms came from. */
    val revision: Long,
    /**
     * Which promise was being kept when this was bound (SEE-97, docs/wiki/environments.md).
     *
     * It is core's own fact rather than the plugin's: the plugin prepared the same bytes either
     * way, and what the connection promised at the moment of acting is what decides whether they
     * are signed. Pinning it here is what makes a mode switch safe — the check happens on the far
     * side of the wait for the wallet, exactly as the expiry and the wallet selection do, so a
     * binding made under one promise cannot be carried out under the other.
     */
    val environment: PluginEnvironment,
    /** The parameters the owner chose, which must be the ones they reviewed. */
    val choice: ParameterChoice,
    /** The selected wallet's public address. Never a token: that stays in the wallet store. */
    val wallet: String,
    /** The network the wallet was selected for, which is the chain this can happen on. */
    val network: Network,
    /**
     * The execution provider that prepared the bytes, by the ID it declares (SEE-145).
     *
     * It is pinned for the same reason the wallet is. Two providers of the same action are two
     * different venues with two different order books; a review made against one is not a review of
     * the other, and there is no routing, substitution or fallback anywhere in this app that could
     * quietly make it one.
     */
    val provider: ExecutionProviderId,
    /** What was being done, provider-neutrally. */
    val action: ActionId,
    /** Which version of that action's payload schema the terms were read as. */
    val schemaVersion: Int,
    /**
     * Exactly which market or pair this was about (SEE-145).
     *
     * The revision already pins the publisher's terms, so this is belt and braces — but it is the
     * belt that is checked in the owner's own vocabulary. "The market moved under the review" and
     * "the document changed" are the same event and the first is the one worth refusing by name.
     */
    val instrument: Instrument,
    /** The provider's boundary contract version — the version a binding pins (SEE-86). */
    val contract: Int,
    /** Which preparation this is, as the plugin counted it. The first one is 1. */
    val preparedVersion: Int,
    /** SHA-256 of the exact bytes that would be signed, computed by this phone. */
    val contentHash: ByteString,
    /**
     * When those bytes stop being includable, in epoch seconds; null when they carry none. The
     * check belongs here because it belongs on the far side of the wait for the wallet, exactly as
     * a transfer's blockhash window does (SAW-046).
     */
    val expiresAtEpochSeconds: Long? = null,
)

/**
 * Why an operation may not be executed. Each is a separate fact so the owner can be told which one
 * it is, and none of them is a warning to be overruled: an operation that isn't executable has no
 * wallet interaction waiting behind a second tap.
 */
enum class BindingProblem(val code: String) {
    /** This device already executed this proposal, and there is never a second time. */
    AlreadyExecuted("already_executed"),
    /** The owner hid it here. */
    Dismissed("dismissed"),
    /** The publisher contradicted itself about the terms, so neither version is acted on. */
    ProposalRefused("proposal_refused"),
    /** The publisher withdrew it. */
    ProposalCancelled("proposal_cancelled"),
    /** Its expiry has passed. */
    ProposalExpired("proposal_expired"),
    /** This build doesn't support the publisher's server (SEE-88). */
    ServerUnsupported("server_unsupported"),
    /** Nothing was reviewed here, so there is no decision to act on. */
    NotReviewed("not_reviewed"),
    /**
     * The terms moved. Either the binding isn't for the revision the phone holds, or the review was
     * of an older one: the owner reviews the terms as they are now, and their earlier answer is
     * never applied to terms they never saw.
     */
    ProposalChanged("proposal_changed"),
    /** The parameters being bound aren't the ones the owner reviewed. */
    ChoiceChanged("choice_changed"),
    /** The bytes were prepared by an execution provider other than the one bound. */
    OtherProvider("other_provider"),
    /** They were prepared for a different action than the proposal is for. */
    OtherAction("other_action"),
    /** For a different version of that action's payload schema. */
    OtherSchema("other_schema"),
    /** For a different market or pair than the proposal names. */
    OtherInstrument("other_instrument"),
    /** The proposal's terms can no longer be read as its action, so nothing can be compared. */
    UnreadableTerms("unreadable_terms"),
    /** By a provider written against a boundary version this build doesn't call. */
    OtherContract("other_contract"),
    /** Nothing was prepared, or what was prepared has no content hash to bind to. */
    NothingPrepared("nothing_prepared"),
    /** What was prepared can no longer be included, so it is prepared again and reviewed again. */
    PreparationExpired("preparation_expired"),
    /** No wallet is connected on this phone, so there is nothing to sign with. */
    NoWallet("no_wallet"),
    /** The wallet selected now isn't the one the binding names. */
    OtherWallet("other_wallet"),
    /** The wallet is selected for another network than the one the binding names. */
    OtherNetwork("other_network"),
    /**
     * The binding was made for the other promise (SEE-97). The owner switched the connection's
     * environment while this was in hand, so what they reviewed was a rehearsal and what they would
     * be doing is real, or the other way round. They review again; nothing is carried over.
     */
    OtherEnvironment("other_environment"),
}

/**
 * The one rule that stops [binding] being executed for [record], or null when nothing does.
 *
 * Every rule is here rather than spread between a repository and a screen, because a rule in two
 * places is a rule that gets forgotten in one of them. The checks run from the most fundamental to
 * the most specific: what this device has already done, then whether the proposal still stands,
 * then which promise is being kept, then whether the owner reviewed these terms, then whether the
 * plugin and the wallet are the ones bound, and last whether the bytes are still includable.
 */
fun bindingProblem(
    record: ProposalRecord,
    binding: ExecutionBinding,
    wallet: SelectedWallet?,
    support: ServerSupport,
    /** The promise the connection keeps *now*, which the binding's own must still be (SEE-97). */
    environment: PluginEnvironment,
    /**
     * The execution provider **this build** would serve the proposal with, or null when none would
     * ([proposalProvider]).
     *
     * It is passed in rather than looked up, because this file holds no registry — and it is the
     * right thing to compare against for two reasons at once: a binding made for a provider the
     * document did not name is refused, and so is one made by a build that has since stopped
     * carrying it (SEE-145).
     */
    serving: ExecutionProviderId?,
    now: Instant,
): BindingProblem? {
    // The standing is consulted rather than re-derived, so what the owner is shown and what the
    // gate allows can never drift apart. An exhaustive `when` makes a standing added later a
    // decision here.
    when (proposalStanding(record, support, now)) {
        is ProposalStanding.Open -> Unit
        is ProposalStanding.Executed -> return BindingProblem.AlreadyExecuted
        is ProposalStanding.Dismissed -> return BindingProblem.Dismissed
        is ProposalStanding.Refused -> return BindingProblem.ProposalRefused
        is ProposalStanding.Cancelled -> return BindingProblem.ProposalCancelled
        is ProposalStanding.Expired -> return BindingProblem.ProposalExpired
        is ProposalStanding.Unsupported -> return BindingProblem.ServerUnsupported
    }
    // Before anything about the terms, because it is not a question about them: it is which of the
    // two things the owner is about to do.
    if (binding.environment != environment) return BindingProblem.OtherEnvironment
    val review = record.review ?: return BindingProblem.NotReviewed
    val revision = record.proposal.revision
    if (binding.revision != revision || review.revision != revision) {
        return BindingProblem.ProposalChanged
    }
    if (binding.choice != review.choice) return BindingProblem.ChoiceChanged
    // Who, what, at which schema, about which instrument — each refused by name, because each of
    // them changing means the owner is about to sign something other than what they reviewed
    // (SEE-145).
    if (serving == null || binding.provider != serving) return BindingProblem.OtherProvider
    if (binding.action != record.proposal.action) return BindingProblem.OtherAction
    if (binding.schemaVersion != record.proposal.capabilityVersion) {
        return BindingProblem.OtherSchema
    }
    val payload =
        actionPayloadFrom(
            record.proposal.action,
            record.proposal.capabilityVersion,
            record.proposal.terms(),
        )
    if (payload !is ActionPayloadResult.Valid) return BindingProblem.UnreadableTerms
    if (binding.instrument != payload.payload.instrument) return BindingProblem.OtherInstrument
    if (binding.contract !in SUPPORTED_PROVIDER_CONTRACTS) return BindingProblem.OtherContract
    if (binding.preparedVersion < 1 || binding.contentHash.size() != CONTENT_HASH_BYTES) {
        return BindingProblem.NothingPrepared
    }
    val selected = wallet ?: return BindingProblem.NoWallet
    if (binding.wallet != selected.address) return BindingProblem.OtherWallet
    if (binding.network != selected.network.network) return BindingProblem.OtherNetwork
    binding.expiresAtEpochSeconds?.let {
        if (now.epochSecond >= it) return BindingProblem.PreparationExpired
    }
    return null
}

/** SHA-256, which is what a content hash is here as everywhere else in this protocol. */
const val CONTENT_HASH_BYTES: Int = 32

/**
 * Which execution provider this build would use for [proposal], or why none would (SEE-145).
 *
 * The publisher names a provider — directly, or through the bundled-plugin name it was written for
 * — and this asks the registry for exactly that one. It never treats the name as a hint: a document
 * cannot select code, and a build that does not carry the named provider refuses the proposal
 * rather than handing the terms to whatever else it happens to have for the action.
 *
 * It answers with an ID and not with a provider, so nothing in this package ever holds something it
 * could call.
 */
sealed interface ProposalProvider {
    /** This build carries exactly the provider the publisher named, for this action, here. */
    data class Serving(val id: ExecutionProviderId) : ProposalProvider

    /** Nothing in this build serves it here, and why (SEE-86, SEE-145). */
    data class Unserved(val reason: UnsupportedReason) : ProposalProvider
}

/**
 * The execution provider a document names, given what this build carries.
 *
 * The document's own field wins when it has one. Otherwise the bundled-plugin name it claims is
 * matched against the providers registered in *this build* — so a provider added later answers to
 * the names it declares without a line of core changing, and only the two names published before
 * SEE-145 need a row in [io.github.brrenat.seekervault.plugins.LEGACY_CAPABILITIES] at all.
 */
fun namedProvider(proposal: Proposal, providers: ProviderRegistry): ExecutionProviderId? =
    proposal.provider ?: providers.byLegacyPlugin(proposal.plugin)?.capabilities?.id

fun proposalProvider(
    proposal: Proposal,
    providers: ProviderRegistry,
    network: Network,
    environment: PluginEnvironment,
): ProposalProvider {
    // The terms are read before anything is resolved, so a document that cannot be read as its own
    // action is unserved for that reason rather than for a provider's. It stays a proposal the
    // owner can look at and dismiss either way.
    val payload = actionPayloadFrom(proposal.action, proposal.capabilityVersion, proposal.terms())
    val resolution =
        providers.resolve(
            provider = namedProvider(proposal, providers),
            action = proposal.action,
            schemaVersion = proposal.capabilityVersion,
            network = network,
            environment = environment,
            payload = (payload as? ActionPayloadResult.Valid)?.payload,
        )
    return when (resolution) {
        is ProviderResolution.Unsupported -> ProposalProvider.Unserved(resolution.reason)
        is ProviderResolution.Supported ->
            ProposalProvider.Serving(resolution.provider.capabilities.id)
    }
}
