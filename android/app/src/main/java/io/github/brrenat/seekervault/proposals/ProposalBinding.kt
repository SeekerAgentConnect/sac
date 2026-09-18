package io.github.brrenat.seekervault.proposals

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.plugins.PluginRegistry
import io.github.brrenat.seekervault.plugins.PluginResolution
import io.github.brrenat.seekervault.plugins.SUPPORTED_PLUGIN_CONTRACTS
import io.github.brrenat.seekervault.plugins.UnsupportedReason
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
    /** The plugin that prepared the bytes, by the ID it declares. */
    val plugin: PluginId,
    /** That plugin's boundary contract version — the "plugin version" a binding pins (SEE-86). */
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
    /** The bytes were prepared by a plugin other than the one the proposal was written for. */
    OtherPlugin("other_plugin"),
    /** By a plugin written against a boundary version this build doesn't call. */
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
    if (binding.plugin != record.proposal.plugin) return BindingProblem.OtherPlugin
    if (record.proposal.capabilityVersion != SUPPORTED_CAPABILITY_VERSION) {
        return BindingProblem.OtherContract
    }
    if (binding.contract !in SUPPORTED_PLUGIN_CONTRACTS) return BindingProblem.OtherContract
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
 * Which plugin this build would use for [proposal], or why none would.
 *
 * The publisher names the plugin it wrote the proposal for, and this checks that name against what
 * the build actually resolves for the operation. It never uses the name to choose: a document
 * cannot select code, and a build that resolves something else for the operation refuses the
 * proposal rather than handing the terms to whatever it happens to carry.
 *
 * It answers with an ID and not with a plugin, so nothing in this package ever holds something it
 * could call.
 */
sealed interface ProposalPlugin {
    /** This build resolves exactly the plugin the publisher wrote it for. */
    data class Serving(val id: PluginId) : ProposalPlugin

    /** It resolves a different plugin for the operation, which is not the same agreement. */
    data class OtherPlugin(val resolved: PluginId) : ProposalPlugin

    /** Nothing in this build serves the operation here, and why (SEE-86). */
    data class Unserved(val reason: UnsupportedReason) : ProposalPlugin
}

fun proposalPlugin(
    proposal: Proposal,
    plugins: PluginRegistry,
    environment: PluginEnvironment,
): ProposalPlugin {
    // A capability version this build does not interpret is the same kind of gap as a plugin
    // written against another boundary: readable, and never a reason to sign bytes with a
    // version-1 reader. The check is here rather than in validation so the document stays a
    // proposal the owner can dismiss.
    if (proposal.capabilityVersion != SUPPORTED_CAPABILITY_VERSION) {
        return ProposalPlugin.Unserved(UnsupportedReason.ContractUnsupported)
    }
    return when (val resolution = plugins.resolve(proposal.operation, environment)) {
        is PluginResolution.Unsupported -> ProposalPlugin.Unserved(resolution.reason)
        is PluginResolution.Supported -> {
            val id = resolution.plugin.descriptor.id
            if (id == proposal.plugin) ProposalPlugin.Serving(id)
            else ProposalPlugin.OtherPlugin(id)
        }
    }
}
