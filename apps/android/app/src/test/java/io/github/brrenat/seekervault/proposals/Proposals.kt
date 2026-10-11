package io.github.brrenat.seekervault.proposals

import com.google.protobuf.ByteString
import com.google.protobuf.timestamp
import io.github.brrenat.seekervault.connections.FeedSnapshot
import io.github.brrenat.seekervault.plugins.ActionId
import io.github.brrenat.seekervault.plugins.ExecutionProviderId
import io.github.brrenat.seekervault.plugins.PROVIDER_CONTRACT
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.actions.ActionPayloadResult
import io.github.brrenat.seekervault.plugins.actions.Instrument
import io.github.brrenat.seekervault.plugins.actions.actionPayloadFrom
import io.github.brrenat.seekervault.proposal.v1.Proposal as WireProposal
import io.github.brrenat.seekervault.proposal.v1.ProposalStatus as WireStatus
import io.github.brrenat.seekervault.proposal.v1.proposal as wireProposal
import io.github.brrenat.seekervault.proposal.v1.proposalValue
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v2.Request as WireRequest
import io.github.brrenat.seekervault.requests.commonEnvelope
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.SWAP_PLUGIN
import io.github.brrenat.seekervault.servers.channelFor
import java.time.Instant

/**
 * Proposals as a publisher broadcasts them, for the tests that read one (SEE-89).
 *
 * These build the protocol message rather than the validated model, for the same reason the
 * manifest builders do: what the phone makes of a document is the thing under test, so a test that
 * wants a bad one has to be able to write a bad one.
 *
 * The publisher is the same one the manifest fixtures use ([SERVER_B]), because it is the same
 * server: a feed is added from its manifest and then read for its proposals.
 */
const val PROPOSAL_A = "7c9e6679-7425-40de-944b-e07fc1f90ae7"

const val PROPOSAL_B = "a1b2c3d4-e5f6-4789-abcd-0123456789ab"

/** The operation at the protocol's own level, which is what a proposal names. */
const val SWAP = "swap"

const val PREDICTION = "prediction"

/** Two real mints, spelled here so this file depends on no provider's package. */
const val TEST_USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

const val TEST_SOL = "So11111111111111111111111111111111111111112"

/** A wallet's address, as a phone holds one: a public key and nothing else. */
const val WALLET = "6xJ8QGkQ6Qx1e8YpQ2CqZ9bJ7jY7N3tFh5T9Jr2vQ4dM"

val PUBLISHED: Instant = Instant.parse("2026-09-17T09:00:00Z")

val AMOUNT: ParameterKey = ParameterKey("input_amount")

fun wireProposal(
    serverId: String = SERVER_B,
    channel: String = channelFor(serverId),
    proposalId: String = PROPOSAL_A,
    revision: Long = 1,
    operation: String = SWAP,
    plugin: String = SWAP_PLUGIN,
    status: WireStatus = WireStatus.PROPOSAL_STATUS_OPEN,
    createdAt: Instant = PUBLISHED,
    updatedAt: Instant = createdAt,
    expiresAt: Instant = createdAt.plusSeconds(3600),
    note: String = "",
    /**
     * Terms a `swap` can actually be read out of, plus one the action's reader ignores.
     *
     * Both halves matter. The gate between a review and the wallet reads the terms as the action
     * they claim to be and refuses a document it cannot ([BindingProblem.UnreadableTerms]), so a
     * fixture that could not be read would exercise that path and nothing else; and a publisher
     * saying more than the action reads is normal, so one such term stays here.
     */
    values: List<Pair<String, String>> =
        listOf(
            "input_mint" to TEST_USDC,
            "input_decimals" to "6",
            "output_mint" to TEST_SOL,
            "output_decimals" to "9",
            "max_slippage_bps" to "100",
            "published_price" to "139420000",
        ),
): WireProposal = wireProposal {
    this.serverId = serverId
    this.channel = channel
    this.proposalId = proposalId
    this.revision = revision
    this.operation = operation
    pluginId = plugin
    this.status = status
    this.createdAt = timestamp { seconds = createdAt.epochSecond }
    this.updatedAt = timestamp { seconds = updatedAt.epochSecond }
    this.expiresAt = timestamp { seconds = expiresAt.epochSecond }
    publisherNote = note
    this.values.addAll(
        values.map {
            proposalValue {
                key = it.first
                text = it.second
            }
        }
    )
}

/**
 * The validated proposal a publisher's document becomes, for the tests that start from one.
 *
 * As a current publisher sends it (SEE-185): the request contract that names the network, and
 * [network] declared — Mainnet unless a test says otherwise. Exactly what [signal] delivers, so a
 * record read from a delivery and one built here are the same proposal. A test about a document
 * that declares no network reads the legacy message itself ([proposalFrom]), or passes null.
 */
fun proposal(
    message: WireProposal = wireProposal(),
    serverId: String = message.serverId,
    network: Network? = Network.NETWORK_MAINNET,
): Proposal =
    (proposalFrom(message, ProposalExpectation(serverId = serverId)) as ProposalResult.Valid)
        .proposal
        .let {
            if (network == null) it
            else
                it.copy(
                    contractVersion = NETWORK_CONTRACT,
                    network = SignalNetwork.Declared(network),
                )
        }

/**
 * [message] as a current publisher delivers it (SEE-185): the common envelope at the request
 * contract that names the network, declaring [network]. The terms are the legacy document's, so
 * every fixture that builds one can be delivered as a signal with a network.
 */
fun signal(
    message: WireProposal = wireProposal(),
    network: Network = Network.NETWORK_MAINNET,
): WireRequest = proposal(message, network = network).commonEnvelope()

/**
 * A feed's answer of [messages] as a current gateway delivers them (SEE-185): each document that
 * reads as one goes out as a [signal] declaring [network]; one that does not is delivered as
 * itself, so a test about a refusal still gets that refusal.
 */
fun delivered(
    sequence: Long,
    messages: List<WireProposal>,
    network: Network = Network.NETWORK_MAINNET,
): FeedSnapshot.Read {
    val readable = messages.filter {
        proposalFrom(it, ProposalExpectation(serverId = it.serverId)) is ProposalResult.Valid
    }
    return FeedSnapshot.Read(
        sequence,
        proposals = messages - readable.toSet(),
        requests = readable.map { signal(it, network) },
    )
}

/** What one owner chose on their own phone. */
fun choice(baseUnits: ULong): ParameterChoice =
    ParameterChoice(mapOf(AMOUNT to ParameterValue.Amount(baseUnits)))

/**
 * A binding over [proposal] and [choice]: the terms as they stood, the parameters this owner chose,
 * the wallet they had selected, the plugin that prepared the bytes, and those bytes' own hash.
 */
fun binding(
    proposal: Proposal,
    choice: ParameterChoice,
    environment: PluginEnvironment = PluginEnvironment.Production,
    wallet: String = WALLET,
    network: Network = Network.NETWORK_MAINNET,
    provider: ExecutionProviderId? = proposal.provider,
    action: ActionId = proposal.action,
    schemaVersion: Int = proposal.capabilityVersion,
    instrument: Instrument = instrumentOf(proposal),
    contract: Int = PROVIDER_CONTRACT,
    preparedVersion: Int = 1,
    contentHash: ByteString = hash(1),
    expiresAtEpochSeconds: Long? = null,
): ExecutionBinding =
    ExecutionBinding(
        revision = proposal.revision,
        environment = environment,
        choice = choice,
        wallet = wallet,
        network = network,
        provider = checkNotNull(provider) { "the proposal names no execution provider" },
        action = action,
        schemaVersion = schemaVersion,
        instrument = instrument,
        contract = contract,
        preparedVersion = preparedVersion,
        contentHash = contentHash,
        expiresAtEpochSeconds = expiresAtEpochSeconds,
    )

/** What a proposal is about, read the way core reads it before it binds anything. */
fun instrumentOf(proposal: Proposal): Instrument =
    (actionPayloadFrom(proposal.action, proposal.capabilityVersion, proposal.terms())
            as? ActionPayloadResult.Valid)
        ?.payload
        ?.instrument ?: Instrument("", "")

/** Thirty-two bytes standing in for the SHA-256 of what a plugin prepared. */
fun hash(seed: Int): ByteString =
    ByteString.copyFrom(ByteArray(CONTENT_HASH_BYTES) { (it + seed).toByte() })
