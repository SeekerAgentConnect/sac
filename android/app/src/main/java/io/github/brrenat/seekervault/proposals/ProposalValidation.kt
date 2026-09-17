package io.github.brrenat.seekervault.proposals

import com.google.protobuf.Timestamp
import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.plugins.OperationId
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.plugins.isOperationId
import io.github.brrenat.seekervault.plugins.isPluginId
import io.github.brrenat.seekervault.proposal.v1.Proposal as WireProposal
import io.github.brrenat.seekervault.proposal.v1.ProposalStatus as WireStatus
import java.time.DateTimeException
import java.time.Instant

/**
 * Reading a proposal a publisher broadcast (SEE-89, docs/wiki/shared-proposals.md).
 *
 * A proposal is a statement by a developer's server that this phone has no relationship with: it
 * holds no credential for it, never contacted it, and reads its documents through a gateway shared
 * with everyone else. So every rule here exists so the phone can refuse one without being confused
 * by it, and they fall into three groups.
 *
 * - **Identity.** It must be from the publisher whose feed this is, on the channel that publisher
 *   owns, with an ID of the publisher's own. Nothing here can move a proposal between feeds, and a
 *   document that names another publisher's channel is claiming another publisher's audience.
 * - **Orderability.** A revision that can be compared and does not go backwards, so a replayed
 *   older document cannot restore terms the publisher has moved past, and times that make sense
 *   against each other.
 * - **Boundedness.** At most [MAX_PROPOSAL_VALUES] named terms, each named once and short enough to
 *   show, and a note short enough to read.
 *
 * What it cannot check is what the terms *mean*: that is the plugin's, which is the same division
 * as everywhere else in this app — core establishes what a document is, and what it says about an
 * operation is read by the code written for that operation (SEE-86).
 */
data class ProposalExpectation(
    /**
     * The publisher whose feed this proposal arrived on. The channel is not a second field here
     * because it is not a second fact: a publisher owns `server/<its own ID>`, so a document from
     * this publisher on its own channel is on the expected channel by construction.
     */
    val serverId: String,
    /**
     * The revision the phone already holds for this proposal, if any. A document may repeat it or
     * exceed it; one below it is refused.
     */
    val heldRevision: Long? = null,
)

sealed interface ProposalResult {
    data class Valid(val proposal: Proposal) : ProposalResult

    data class Invalid(val problem: ProposalProblem) : ProposalResult
}

/** Which rule a proposal broke. Each is a separate fact, and none of them is a guess. */
enum class ProposalProblem(val code: String) {
    /** The publisher's identity isn't a lowercase UUID. */
    BadServerId("bad_server_id"),
    /** A well-formed identity, but not the publisher whose feed this is. */
    OtherServer("other_server"),
    /** A channel this document's own publisher doesn't own. */
    ForeignChannel("foreign_channel"),
    /** The proposal's own ID isn't a lowercase UUID, so nothing could be keyed by it. */
    BadProposalId("bad_proposal_id"),
    /** Revision zero, or one this phone can't order: a proposal that can't be compared. */
    NoRevision("no_revision"),
    /** A revision below the one the phone already holds for this proposal. */
    StaleRevision("stale_revision"),
    /**
     * The terms changed while the revision stood still. The revision is the publisher's promise
     * about the content, so the two disagreeing is a contradiction rather than an update, and the
     * phone keeps neither: it has no way to tell which one the publisher meant.
     */
    ChangedWithoutRevision("changed_without_revision"),
    /** No explicit status. A missing one is never read as open. */
    NoStatus("no_status"),
    /** The operation isn't a name this protocol uses for one. */
    BadOperation("bad_operation"),
    /** The plugin ID is malformed. It is a name, and a name that isn't one names nothing. */
    BadPlugin("bad_plugin"),
    /** A time the document needs is missing: a proposal with no expiry would be open for ever. */
    NoTimes("no_times"),
    /** Times that contradict each other: changed before it existed, or expired when it began. */
    BadTimes("bad_times"),
    /** A term's name is malformed, or its text is too long to show. */
    BadValue("bad_value"),
    /** The same term twice, which says two things under one name. */
    DuplicateValue("duplicate_value"),
    /** More terms than a proposal may carry. */
    TooManyValues("too_many_values"),
    /** A note too long, or one that isn't printable text. */
    BadNote("bad_note"),
}

/**
 * Validates [message] against the feed it arrived on ([expect]), and returns the proposal or the
 * first rule it broke.
 *
 * The checks run in the order they are written, so the problem reported is the most fundamental
 * one: who published it comes before whether it can be ordered, which comes before what it says.
 */
fun proposalFrom(message: WireProposal, expect: ProposalExpectation): ProposalResult {
    if (!isConnectionId(message.serverId)) return invalid(ProposalProblem.BadServerId)
    if (message.serverId != expect.serverId) return invalid(ProposalProblem.OtherServer)
    val key =
        try {
            ProposalKey(message.serverId, message.channel, message.proposalId)
        } catch (e: IllegalArgumentException) {
            // The key holds the channel-ownership rule, so the two ways it can fail are told
            // apart here rather than guessed at: a channel the publisher doesn't own, or an ID
            // nothing could be keyed by.
            return invalid(
                if (!isConnectionId(message.proposalId)) ProposalProblem.BadProposalId
                else ProposalProblem.ForeignChannel
            )
        }
    // A uint64 above Long.MAX_VALUE arrives here as a negative number, which is refused with
    // everything else that can't be compared: a revision the phone can't order is no revision.
    if (message.revision <= 0L) return invalid(ProposalProblem.NoRevision)
    if (expect.heldRevision != null && message.revision < expect.heldRevision) {
        return invalid(ProposalProblem.StaleRevision)
    }
    val status =
        when (message.status) {
            WireStatus.PROPOSAL_STATUS_OPEN -> ProposalStatus.Open
            WireStatus.PROPOSAL_STATUS_CANCELLED -> ProposalStatus.Cancelled
            // Unspecified, or a status from a later version of the format. Either way this phone
            // doesn't know what the publisher means, and it doesn't pick the permissive reading.
            else -> return invalid(ProposalProblem.NoStatus)
        }
    if (!isOperationId(message.operation)) return invalid(ProposalProblem.BadOperation)
    if (!isPluginId(message.pluginId)) return invalid(ProposalProblem.BadPlugin)
    if (!message.hasCreatedAt() || !message.hasUpdatedAt() || !message.hasExpiresAt()) {
        return invalid(ProposalProblem.NoTimes)
    }
    val createdAt = message.createdAt.instant() ?: return invalid(ProposalProblem.BadTimes)
    val updatedAt = message.updatedAt.instant() ?: return invalid(ProposalProblem.BadTimes)
    val expiresAt = message.expiresAt.instant() ?: return invalid(ProposalProblem.BadTimes)
    if (updatedAt < createdAt || !expiresAt.isAfter(createdAt)) {
        return invalid(ProposalProblem.BadTimes)
    }
    if (message.valuesCount > MAX_PROPOSAL_VALUES) return invalid(ProposalProblem.TooManyValues)
    val values = mutableListOf<ProposalValue>()
    for (value in message.valuesList) {
        // A term's name is held to the same rule as an operation's and a parameter's: lowercase
        // segments, and nothing that could be a path, a URL, or anything loadable.
        if (!isOperationId(value.key)) return invalid(ProposalProblem.BadValue)
        if (!printableText(value.text, MAX_PROPOSAL_TEXT_BYTES)) {
            return invalid(ProposalProblem.BadValue)
        }
        if (values.any { it.key == value.key }) return invalid(ProposalProblem.DuplicateValue)
        values += ProposalValue(value.key, value.text)
    }
    if (!printableText(message.publisherNote, MAX_PROPOSAL_NOTE_BYTES)) {
        return invalid(ProposalProblem.BadNote)
    }
    return ProposalResult.Valid(
        Proposal(
            key = key,
            revision = message.revision,
            operation = OperationId(message.operation),
            plugin = PluginId(message.pluginId),
            status = status,
            createdAt = createdAt,
            updatedAt = updatedAt,
            expiresAt = expiresAt,
            note = message.publisherNote,
            values = values.toList(),
        )
    )
}

/**
 * Whether [text] is something the app can show: short enough, and text rather than control
 * characters, which a publisher could otherwise use to make its own words read as something else. A
 * line break is text — a note is prose — and everything else that isn't printable is not.
 */
private fun printableText(text: String, mostBytes: Int): Boolean =
    text.toByteArray(Charsets.UTF_8).size <= mostBytes &&
        text.none { it.isISOControl() && it != '\n' } &&
        text == text.trim()

/**
 * The instant this timestamp names, or null when it names none this phone can hold. The protocol
 * carries a signed 64-bit second count, which reaches further than an [Instant] does, and a
 * publisher's document is not a reason to throw: a time that can't be read is a malformed document
 * like any other.
 */
private fun Timestamp.instant(): Instant? =
    try {
        Instant.ofEpochSecond(seconds, nanos.toLong())
    } catch (e: DateTimeException) {
        null
    } catch (e: ArithmeticException) {
        null
    }

private fun invalid(problem: ProposalProblem) = ProposalResult.Invalid(problem)
