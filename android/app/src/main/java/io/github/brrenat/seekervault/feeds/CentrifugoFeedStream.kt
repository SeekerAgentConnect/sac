package io.github.brrenat.seekervault.feeds

import centrifugal.centrifugo.unistream.CentrifugoUniStreamClient
import centrifugal.centrifugo.unistream.ConnectRequest
import centrifugal.centrifugo.unistream.Publication
import centrifugal.centrifugo.unistream.Push
import centrifugal.centrifugo.unistream.SubscribeRequest
import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.ProtocolClientConfig
import com.connectrpc.exceptionOrNull
import com.connectrpc.extensions.GoogleJavaLiteProtobufStrategy
import com.connectrpc.impl.ProtocolClient
import com.connectrpc.okhttp.ConnectOkHttpClient
import com.connectrpc.protocols.NetworkProtocol
import com.connectrpc.simpleTimeouts
import com.google.protobuf.InvalidProtocolBufferException
import io.github.brrenat.seekervault.gateway.v1.FeedEvent
import java.io.IOException
import java.net.UnknownServiceException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol

/**
 * [FeedStream] over Centrifugo's unidirectional gRPC transport — **the only file in the app that
 * knows which broker is behind a feed** (SEE-91).
 *
 * Everything it does is translation: our request into a connect request, the broker's pushes into
 * [FeedStreamEvent]s, its failures into [FeedStreamException]s. No decision is made here. Whether
 * to reconnect, whether a channel's continuity was proven, what to do with a document — all of that
 * is [FeedRecovery] and [ForegroundFeedManager], where it can be tested without a socket.
 *
 * ## What the transport does and does not give us
 *
 * The stream is one call: a connect request goes up, pushes come down, and nothing goes up again.
 * That is why the ticket carries the channels (the phone cannot subscribe) and why the cursors go
 * in the request (there is no way to ask for history later).
 *
 * It also sends **no periodic pings**. The connect answer carries a ping interval, and the pinned
 * release does not honour it on this transport — an idle stream is silent. So liveness is HTTP/2's:
 * the client below sends protocol pings and fails the connection when they go unanswered, which is
 * what turns a connection that died quietly into an ordinary reconnect.
 *
 * ## The two clients
 *
 * A stream needs no read timeout, so the shared client (which has none) is the base. On top of it:
 * protocol pings, and for a loopback development gateway on plain HTTP, prior-knowledge HTTP/2 —
 * gRPC needs HTTP/2, and without TLS there is no negotiation to discover it with. The same pair the
 * direct path's transport keeps, for the same reasons (`sync/ConnectUpdateTransport.kt`).
 */
class CentrifugoFeedStream(httpClient: OkHttpClient) : FeedStream {
    private val streaming =
        httpClient.newBuilder().pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS).build()
    private val cleartext =
        streaming.newBuilder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).build()

    override fun listen(
        gatewayUrl: String,
        ticket: String,
        resume: Map<String, FeedCursor>,
    ): Flow<FeedStreamEvent> = flow {
        val client = CentrifugoUniStreamClient(protocolClient(gatewayUrl))
        val stream = client.consume(emptyMap())
        try {
            stream.sendAndClose(request(ticket, resume)).onFailure { throw classify(it) }
            val pushes = stream.responseChannel()
            while (true) {
                val result = pushes.receiveCatching()
                val push = result.getOrNull()
                if (push == null) {
                    // A stream the broker ended cleanly: it has already told us why in a disconnect
                    // push, or it simply went away, which the caller treats as a reconnect.
                    val cause = result.exceptionOrNull() ?: break
                    if (cause is CancellationException) throw cause
                    throw classify(cause)
                }
                for (event in eventsOf(push)) emit(event)
            }
        } finally {
            withContext(NonCancellable) { stream.receiveClose() }
        }
    }

    /**
     * The connect request: the ticket, and a cursor per channel.
     *
     * The channels themselves are **not** in here, and cannot be. The `subs` map looks like a
     * subscription request and is not one — the broker reads only the recovery position from it and
     * takes the channels from the ticket, so a request naming a channel the ticket does not grant
     * is answered with a connection that receives nothing from it. (Probed against the pinned
     * release; the connect answer came back with no subscriptions at all.)
     *
     * The name and version are for the broker's own logs. They say which application is connected,
     * never which phone: there is nothing here derived from the device, the installation or the
     * owner, and there is nothing in the ticket either (docs/security.md).
     */
    private fun request(ticket: String, resume: Map<String, FeedCursor>): ConnectRequest {
        val builder = ConnectRequest.newBuilder().setToken(ticket).setName(NAME).setVersion(VERSION)
        for ((channel, cursor) in resume) {
            builder.putSubs(
                channel,
                SubscribeRequest.newBuilder()
                    .setRecover(true)
                    .setEpoch(cursor.epoch)
                    .setOffset(cursor.offset)
                    .build(),
            )
        }
        return builder.build()
    }

    /**
     * One push, as zero or more of our events.
     *
     * A connect push becomes the opening plus every document the broker replayed, in order, so that
     * catching up and keeping up are one code path in the caller. Everything else is one event, and
     * a push carrying something this version does not know becomes [FeedStreamEvent.Alive] — a
     * frame that is not a document must never be read as one.
     */
    private fun eventsOf(push: Push): List<FeedStreamEvent> =
        when {
            push.hasConnect() -> {
                val connect = push.connect
                val opened =
                    connect.subsMap.mapValues { (_, result) ->
                        FeedSubscription(
                            epoch = result.epoch,
                            offset = result.offset,
                            recoverable = result.recoverable,
                            recovered = result.recovered,
                            wasRecovering = result.wasRecovering,
                        )
                    }
                val replayed =
                    connect.subsMap.entries.flatMap { (channel, result) ->
                        result.publicationsList.map { published(channel, it) }
                    }
                listOf(FeedStreamEvent.Opened(opened)) + replayed
            }
            push.hasPub() -> listOf(published(push.channel, push.pub))
            push.hasUnsubscribe() ->
                listOf(
                    FeedStreamEvent.Dropped(
                        push.channel,
                        push.unsubscribe.code,
                        push.unsubscribe.reason,
                    )
                )
            push.hasDisconnect() ->
                listOf(FeedStreamEvent.Closed(push.disconnect.code, push.disconnect.reason))
            else -> listOf(FeedStreamEvent.Alive)
        }

    /**
     * One publication, as a document or as something unreadable.
     *
     * The payload is a [FeedEvent] — our own envelope, the same one a read would have given us — so
     * what comes off the stream goes through the phone's own validators afterwards exactly like a
     * document that was read. Bytes that are not one are reported rather than dropped: the channel
     * moved either way, and the caller reads the snapshot instead of assuming nothing happened.
     */
    private fun published(channel: String, publication: Publication): FeedStreamEvent =
        try {
            FeedStreamEvent.Published(
                channel,
                publication.offset,
                FeedEvent.parseFrom(publication.data),
            )
        } catch (e: InvalidProtocolBufferException) {
            FeedStreamEvent.Unreadable(channel, publication.offset)
        }

    private fun protocolClient(gatewayUrl: String) =
        ProtocolClient(
            httpClient =
                ConnectOkHttpClient(if (gatewayUrl.startsWith("http://")) cleartext else streaming),
            config =
                ProtocolClientConfig(
                    host = gatewayUrl,
                    serializationStrategy = GoogleJavaLiteProtobufStrategy(),
                    // gRPC, not Connect: this is the broker's own contract, and the broker speaks
                    // gRPC. The gateway's unary API next door speaks Connect, and both go through
                    // one origin and one certificate (docs/wiki/feed-gateway.md#deployment).
                    networkProtocol = NetworkProtocol.GRPC,
                    // No stream deadline: a listener stays open for as long as the screen is on.
                    timeoutOracle = simpleTimeouts(unaryTimeout = 30.seconds, streamTimeout = null),
                ),
        )

    /**
     * Why listening failed, in the caller's terms.
     *
     * The walk over causes is the direct path's, and so is the reason for it: an IPv6 refusal must
     * not be allowed to mask a certificate that was rejected, because those two get very different
     * answers from this app (SAW-013).
     */
    private fun classify(error: Throwable): FeedStreamException {
        val causes = generateSequence(error) { it.cause }.take(CAUSE_DEPTH).toList()
        val code = causes.firstNotNullOfOrNull { (it as? ConnectException)?.code }
        val kind =
            when {
                causes.any {
                    it is SSLHandshakeException ||
                        it is SSLPeerUnverifiedException ||
                        it is CertificateException
                } -> FeedStreamException.Kind.CertificateRejected
                causes.any {
                    it is UnknownServiceException && "CLEARTEXT" in it.message.orEmpty()
                } -> FeedStreamException.Kind.CleartextBlocked
                code == Code.UNAUTHENTICATED || code == Code.PERMISSION_DENIED ->
                    FeedStreamException.Kind.Unauthenticated
                // Nothing is serving the broker's procedure at that origin: a gateway deployed
                // without a stream, or one older than this app.
                code == Code.UNIMPLEMENTED -> FeedStreamException.Kind.Unsupported
                code == Code.UNAVAILABLE ||
                    code == Code.DEADLINE_EXCEEDED ||
                    causes.any { it is IOException } -> FeedStreamException.Kind.Unreachable
                code == Code.INTERNAL_ERROR || code == Code.UNKNOWN ->
                    FeedStreamException.Kind.BadResponse
                else -> FeedStreamException.Kind.Other
            }
        return FeedStreamException(kind, error.message, error)
    }

    private companion object {
        /**
         * How often the HTTP/2 layer checks that the connection is still there. It is the only
         * liveness this transport has, and it is well inside the time a mobile network takes to
         * forget an idle connection without telling either end.
         */
        const val PING_INTERVAL_SECONDS = 20L
        const val CAUSE_DEPTH = 32
        /** What the broker writes in its logs about who is connected. The app, never the phone. */
        const val NAME = "seekervault"
        const val VERSION = "1"
    }
}
