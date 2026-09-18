package io.github.brrenat.seekervault.proposals

import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.plugins.OperationId
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.servers.channelFor
import java.time.Instant

/**
 * What a publisher broadcast, as this phone validated it (SEE-89, docs/wiki/shared-proposals.md).
 *
 * Stage 7.1's second kind of server proposes an operation once and everyone subscribed through the
 * shared gateway receives the same document. That makes a proposal a different thing from the
 * private `ActionRequest` the owner's own sidecar stores: a request is addressed to one phone and
 * its state is the server's, while a proposal is addressed to nobody in particular and its state is
 * only ever the publisher's own — open until it says otherwise, and until it expires.
 *
 * Everything the owner does about one is theirs and stays here ([ProposalRecord]). Nothing on this
 * side is sent back: a publisher never learns that this phone received a proposal, let alone what
 * was chosen or whether anything was executed.
 *
 * This is the validated form, and nothing reaches it unchecked: [proposalFrom] turns the protocol
 * message into one of these or says which rule it broke.
 */
data class Proposal(
    val key: ProposalKey,
    /**
     * The publisher's revision of this proposal. It is the publisher's promise about the content:
     * the same revision means the same terms, a higher one means the terms moved, and anything this
     * device decided about the older terms no longer describes these ([ProposalReview]).
     */
    val revision: Long,
    /** The common envelope version. Legacy Proposal rows adapt to version 1 on read. */
    val contractVersion: Int = 1,
    /** Source-authored title; Signal is presentation, not an operation type. */
    val title: String = "",
    /**
     * What is proposed, at the protocol's own level (`swap`). Which plugin serves it is this
     * build's business ([proposalPlugin]), not the document's.
     */
    val operation: OperationId,
    /**
     * The plugin the publisher wrote the proposal for. It is checked against the one this build
     * resolves for [operation] rather than followed: a name is not a way to choose code, and a
     * build that resolves something else refuses the proposal instead of handing the document to
     * whatever it happens to carry.
     */
    val plugin: PluginId,
    val status: ProposalStatus,
    /** When the publisher first published it, by the publisher's clock. */
    val createdAt: Instant,
    /** When it last changed, by the same clock; [createdAt] until it does. */
    val updatedAt: Instant,
    /**
     * The instant from which nothing is executed from it. Absolute rather than a duration, so a
     * phone that was offline for a day reaches the same conclusion as one that was not.
     */
    val expiresAt: Instant,
    /**
     * The publisher's own description, or empty. It is unverified prose and is shown apart from
     * everything the phone established for itself, exactly as an agent's note is (SAW-013).
     */
    val note: String = "",
    /**
     * The operation's common terms. The plugin that serves the operation reads the keys it knows;
     * core carries them and interprets none of them.
     */
    val values: List<ProposalValue> = emptyList(),
    /** Shapes the source declared for values chosen locally; never the owner's answers. */
    val ownerInputs: List<OwnerInputDeclaration> = emptyList(),
) {
    /** The term named [name], or null when the publisher gave none. */
    fun value(name: String): String? = values.firstOrNull { it.key == name }?.text
}

data class OwnerInputDeclaration(
    val key: String,
    val label: String,
    val kind: OwnerInputKind,
    val required: Boolean,
    val minimum: String = "",
    val maximum: String = "",
    val options: List<OwnerInputOption> = emptyList(),
    val help: String = "",
)

enum class OwnerInputKind {
    Amount,
    Count,
    Choice,
}

data class OwnerInputOption(val value: String, val label: String)

/**
 * What makes a proposal the one it is: the publisher, the channel it was published on, and the
 * publisher's own ID for it. Every local record is keyed by all three, so the same document
 * delivered twice — replayed, pushed again, or read back in a snapshot — is recognized as one thing
 * (SEE-89).
 *
 * The channel is part of the identity and is also the ownership rule, which is why it is checked
 * here rather than remembered somewhere: a publisher owns `server/<its own ID>` and no other, so a
 * key that doesn't say that could not have come from the publisher it names.
 */
data class ProposalKey(val serverId: String, val channel: String, val proposalId: String) {
    init {
        require(isConnectionId(serverId)) { "not a server ID: $serverId" }
        require(channel == channelFor(serverId)) { "not $serverId's own channel: $channel" }
        require(isConnectionId(proposalId)) { "not a proposal ID: $proposalId" }
    }
}

/** Whether the publisher still stands behind a proposal. */
enum class ProposalStatus(val code: String) {
    /** The publisher stands behind it. */
    Open("open"),

    /**
     * The publisher withdrew it. It is still readable, and nothing new is executed from it. A
     * record of an execution that already happened stays exactly as it is: what this phone did
     * happened, and a publisher cannot unsay it.
     */
    Cancelled("cancelled"),
}

/** One of the operation's common terms, as the publisher wrote it. */
data class ProposalValue(
    val key: String,
    /** Canonical display/plugin value; opaque values use standard padded base64. */
    val text: String,
    /** Preserves the common envelope's typed value across the device-local store. */
    val kind: ProposalValueKind = ProposalValueKind.Text,
)

enum class ProposalValueKind {
    Text,
    Integer,
    Flag,
    Opaque,
}

/** A proposal is bounded data: this much of it, and no more. */
const val MAX_PROPOSAL_VALUES: Int = 32

const val MAX_PROPOSAL_TEXT_BYTES: Int = 512

const val MAX_PROPOSAL_NOTE_BYTES: Int = 1024
