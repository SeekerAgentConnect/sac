package io.github.brrenat.seekervault.feeds

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.ProtocolClientConfig
import com.connectrpc.ResponseMessage
import com.connectrpc.extensions.GoogleJavaLiteProtobufStrategy
import com.connectrpc.impl.ProtocolClient
import com.connectrpc.okhttp.ConnectOkHttpClient
import com.connectrpc.protocols.NetworkProtocol
import com.connectrpc.simpleTimeouts
import io.github.brrenat.seekervault.connections.FeedGateway
import io.github.brrenat.seekervault.connections.FeedManifest
import io.github.brrenat.seekervault.connections.FeedSnapshot
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.connections.ProposalFeed
import io.github.brrenat.seekervault.gateway.v1.FeedServiceClient
import io.github.brrenat.seekervault.gateway.v1.getFeedTopicsRequest
import io.github.brrenat.seekervault.gateway.v1.getProposalRequest
import io.github.brrenat.seekervault.gateway.v1.getServerManifestRequest
import io.github.brrenat.seekervault.gateway.v1.getStreamTicketRequest
import io.github.brrenat.seekervault.gateway.v1.listProposalsRequest
import io.github.brrenat.seekervault.gateway.v1.listRequestsRequest
import io.github.brrenat.seekervault.proposal.v1.Proposal
import io.github.brrenat.seekervault.request.v2.Request
import io.github.brrenat.seekervault.servers.FeedReference
import java.io.IOException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import okhttp3.OkHttpClient

/**
 * What a phone reads from a feed gateway, the permission it asks for to listen (SEE-91), and where
 * it is told hints arrive (SEE-92).
 *
 * Three reads, one grant and one name, all unauthenticated: a feed is a broadcast, its reference
 * can be printed in a README, and holding one grants nothing. Nothing about this phone goes the
 * other way — no credential, no identifier, no report of what it did with anything it read. What
 * the gateway learns from a call is which channel someone is interested in, which is the least a
 * subscription can be made of (docs/security.md).
 *
 * It implements both seams the earlier stages left open ([FeedGateway] for settings, [ProposalFeed]
 * for the current proposals) plus [FeedTickets] and [FeedTopics], because they are one endpoint and
 * one client. The stream is next door and speaks a different protocol; this file never touches it.
 */
class ConnectFeedGateway(private val httpClient: OkHttpClient) :
    FeedGateway, ProposalFeed, FeedTickets, FeedTopics {

    override suspend fun resolve(reference: FeedReference, knownRevision: Long): FeedManifest {
        val answer =
            call(reference.gatewayUrl) {
                it.getServerManifest(
                    getServerManifestRequest {
                        serverId = reference.serverId
                        knownSettingsRevision = knownRevision
                    }
                )
            }
        // A gateway may only withhold a document the caller said it holds. Answering "unchanged" to
        // a phone that asked with nothing would leave it with no settings at all, so it is read as
        // an answer to a different question rather than as an answer.
        if (answer.unchanged) {
            if (knownRevision == 0L) {
                throw GatewayException(
                    GatewayException.Kind.BadResponse,
                    "unchanged without a revision",
                )
            }
            return FeedManifest.Unchanged(answer.settingsRevision)
        }
        if (!answer.hasManifest()) {
            throw GatewayException(GatewayException.Kind.BadResponse, "no manifest")
        }
        return FeedManifest.Held(answer.manifest)
    }

    /**
     * The whole current feed, in pages, joined here.
     *
     * The walk is bounded twice over: a page size the gateway also enforces, and a page count, so a
     * feed that grew without limit — or a gateway that kept handing out tokens — costs a known
     * amount of memory and a known number of round trips rather than an open-ended number of both.
     *
     * Every page of one walk reports the same sequence, and a page that reports a different one
     * means the walk is not a walk any more; it is refused rather than stitched together, because a
     * phone that silently accepted it could not say what its snapshot was a snapshot of.
     */
    override suspend fun snapshot(reference: FeedReference, knownSequence: Long): FeedSnapshot =
        try {
            commonSnapshot(reference, knownSequence)
        } catch (failure: GatewayException) {
            if (failure.kind != GatewayException.Kind.Unimplemented) throw failure
            legacySnapshot(reference, knownSequence)
        }

    private suspend fun commonSnapshot(
        reference: FeedReference,
        knownSequence: Long,
    ): FeedSnapshot {
        val requests = mutableListOf<Request>()
        var token = ""
        var sequence = 0L
        for (page in 0 until MAX_PAGES) {
            val answer =
                call(reference.gatewayUrl) {
                    it.listRequests(
                        listRequestsRequest {
                            channel = reference.channel
                            pageSize = PAGE_SIZE
                            pageToken = token
                            if (page == 0) knownSnapshotSequence = knownSequence
                        }
                    )
                }
            if (page == 0) {
                if (answer.unchanged) return FeedSnapshot.Unchanged(answer.snapshotSequence)
                sequence = answer.snapshotSequence
            } else if (answer.snapshotSequence != sequence) {
                throw GatewayException(
                    GatewayException.Kind.BadResponse,
                    "the walk changed its boundary",
                )
            }
            requests += answer.requestsList
            token = answer.nextPageToken
            if (token.isEmpty()) return FeedSnapshot.Read(sequence, requests = requests.toList())
            if (requests.size > MAX_PROPOSALS) break
        }
        throw GatewayException(GatewayException.Kind.BadResponse, "a feed that does not end")
    }

    private suspend fun legacySnapshot(
        reference: FeedReference,
        knownSequence: Long,
    ): FeedSnapshot {
        val proposals = mutableListOf<Proposal>()
        var token = ""
        var sequence = 0L
        for (page in 0 until MAX_PAGES) {
            val answer =
                call(reference.gatewayUrl) {
                    it.listProposals(
                        listProposalsRequest {
                            channel = reference.channel
                            pageSize = PAGE_SIZE
                            pageToken = token
                            // Only ever on the first page: a sequence is about the channel, not
                            // about a position in a walk.
                            if (page == 0) knownSnapshotSequence = knownSequence
                        }
                    )
                }
            if (page == 0) {
                if (answer.unchanged) return FeedSnapshot.Unchanged(answer.snapshotSequence)
                sequence = answer.snapshotSequence
            } else if (answer.snapshotSequence != sequence) {
                throw GatewayException(
                    GatewayException.Kind.BadResponse,
                    "the walk changed its boundary",
                )
            }
            proposals += answer.proposalsList
            token = answer.nextPageToken
            if (token.isEmpty()) return FeedSnapshot.Read(sequence, proposals.toList())
            if (proposals.size > MAX_PROPOSALS) break
        }
        throw GatewayException(GatewayException.Kind.BadResponse, "a feed that does not end")
    }

    /** One proposal, for a phone that learned its ID from somewhere other than a page. */
    suspend fun proposal(reference: FeedReference, proposalId: String): Proposal {
        val answer =
            call(reference.gatewayUrl) {
                it.getProposal(
                    getProposalRequest {
                        channel = reference.channel
                        this.proposalId = proposalId
                    }
                )
            }
        if (!answer.hasProposal()) {
            throw GatewayException(GatewayException.Kind.BadResponse, "no proposal")
        }
        return answer.proposal
    }

    override suspend fun ticket(gatewayUrl: String, channels: List<String>): FeedGrant {
        val asked = channels.distinct()
        val answer =
            call(gatewayUrl) {
                it.getStreamTicket(getStreamTicketRequest { this.channels += asked })
            }
        if (answer.ticket.isEmpty() || answer.channelsList.isEmpty()) {
            throw GatewayException(GatewayException.Kind.BadResponse, "an empty grant")
        }
        // A grant naming a channel nobody asked about is not a grant for this phone. It would mean
        // listening to a publisher the owner never added, so the whole answer is refused rather
        // than filtered: at that point nothing about it can be relied on.
        val granted =
            answer.channelsList.map {
                if (it.channel !in asked || it.streamChannel.isEmpty()) {
                    throw GatewayException(
                        GatewayException.Kind.BadResponse,
                        "a grant for a channel that was not asked about",
                    )
                }
                GrantedChannel(it.channel, it.streamChannel)
            }
        return FeedGrant(answer.ticket, granted, answer.lifetimeSeconds.seconds)
    }

    /**
     * Where hints about these feeds arrive (SEE-92).
     *
     * The same asymmetry as a ticket's, for the same reason: a channel the gateway does not name is
     * left out of the answer rather than fatal, and an answer naming a channel nobody asked about
     * is refused whole. Subscribing to a topic for a publisher the owner never added would be this
     * phone being told to listen for somebody else's feed, and at that point nothing about the
     * answer can be relied on.
     */
    override suspend fun topics(
        gatewayUrl: String,
        channels: List<String>,
    ): List<FeedChannelTopic> {
        val asked = channels.distinct()
        val answer =
            call(gatewayUrl) { it.getFeedTopics(getFeedTopicsRequest { this.channels += asked }) }
        return answer.topicsList.map {
            if (it.channel !in asked || it.topic.isEmpty()) {
                throw GatewayException(
                    GatewayException.Kind.BadResponse,
                    "a topic for a channel that was not asked about",
                )
            }
            FeedChannelTopic(it.channel, it.topic)
        }
    }

    private fun client(gatewayUrl: String) =
        FeedServiceClient(
            ProtocolClient(
                httpClient = ConnectOkHttpClient(httpClient),
                config =
                    ProtocolClientConfig(
                        host = gatewayUrl,
                        serializationStrategy = GoogleJavaLiteProtobufStrategy(),
                        networkProtocol = NetworkProtocol.CONNECT,
                        timeoutOracle =
                            simpleTimeouts(unaryTimeout = TIMEOUT, streamTimeout = null),
                    ),
            )
        )

    private suspend fun <T> call(
        gatewayUrl: String,
        block: suspend (FeedServiceClient) -> ResponseMessage<T>,
    ): T {
        val response =
            try {
                block(client(gatewayUrl))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw classify(e)
            }
        return when (response) {
            is ResponseMessage.Success -> response.message
            is ResponseMessage.Failure -> throw classify(response.cause)
        }
    }

    private companion object {
        val TIMEOUT: Duration = 15.seconds
        /**
         * What one page holds. The gateway's own maximum is higher; this is what a phone asks for.
         */
        const val PAGE_SIZE = 100
        /** The walk's bounds: pages, and documents. */
        const val MAX_PAGES = 100
        const val MAX_PROPOSALS = 5_000

        /**
         * The same classification the direct path uses, for the same reason: a TLS refusal must
         * never be reported as "not reachable", because this app treats those completely
         * differently (SAW-013). The walk over causes keeps an IPv6 refusal from masking the
         * certificate that was actually rejected.
         */
        fun classify(error: Throwable): GatewayException {
            val causes = generateSequence(error) { it.cause }.take(32).toList()
            val code = causes.firstNotNullOfOrNull { (it as? ConnectException)?.code }
            val kind =
                when {
                    causes.any {
                        it is SSLHandshakeException ||
                            it is SSLPeerUnverifiedException ||
                            it is CertificateException ||
                            it is CertPathValidatorException
                    } -> GatewayException.Kind.CertificateRejected
                    causes.any {
                        it is UnknownServiceException && "CLEARTEXT" in it.message.orEmpty()
                    } -> GatewayException.Kind.CleartextBlocked
                    code == Code.UNAUTHENTICATED -> GatewayException.Kind.Unauthenticated
                    code == Code.UNIMPLEMENTED -> GatewayException.Kind.Unimplemented
                    code == Code.NOT_FOUND -> GatewayException.Kind.NotFound
                    code == Code.PERMISSION_DENIED || code == Code.INVALID_ARGUMENT ->
                        GatewayException.Kind.Rejected
                    code == Code.FAILED_PRECONDITION -> GatewayException.Kind.InvalidState
                    code == Code.RESOURCE_EXHAUSTED -> GatewayException.Kind.Unreachable
                    code == Code.UNAVAILABLE ||
                        code == Code.DEADLINE_EXCEEDED ||
                        causes.any { it is IOException } -> GatewayException.Kind.Unreachable
                    else -> GatewayException.Kind.Other
                }
            return GatewayException(kind, error.message, error)
        }
    }
}

/**
 * Permission to listen, which only the gateway can give (SEE-91).
 *
 * It is its own seam because it is the only call the session makes that is not a read: everything
 * else the phone asks the gateway for is a document, and this is a grant. A build wired without a
 * gateway has nothing to listen to, which is the same thing it already says about reading one.
 */
interface FeedTickets {
    /**
     * A grant for [channels] at [gatewayUrl] — each channel one the phone holds a feed reference
     * for.
     *
     * Throws [GatewayException] with [GatewayException.Kind.Unimplemented] when the gateway serves
     * no stream at all, which is a smaller deployment rather than a failure: the phone reads the
     * same documents over unary calls.
     */
    suspend fun ticket(gatewayUrl: String, channels: List<String>): FeedGrant
}
