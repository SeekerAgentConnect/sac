package io.github.brrenat.seekervault.feeds

import centrifugal.centrifugo.unistream.Connect
import centrifugal.centrifugo.unistream.ConnectRequest
import centrifugal.centrifugo.unistream.Disconnect
import centrifugal.centrifugo.unistream.Publication
import centrifugal.centrifugo.unistream.Push
import centrifugal.centrifugo.unistream.SubscribeResult
import centrifugal.centrifugo.unistream.Unsubscribe
import com.connectrpc.okhttp.ConnectOkHttpClient
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.gateway.v1.FeedEvent
import io.github.brrenat.seekervault.proposal.v1.Proposal
import java.net.InetAddress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * That the phone's own client speaks the broker's transport — over TLS and HTTP/2, with the schema
 * that was vendored, before anything is built on top of it (SEE-91).
 *
 * The ticket asks for this proof first, and for good reason: the transport is unidirectional, so
 * the usual way of finding out whether a client works — send something and see — does not exist.
 * What can be proven without a broker is exactly this: that the generated client, the connect
 * request it sends, and the pushes it decodes are the ones the protocol describes.
 *
 * So the server here is a real HTTPS/HTTP-2 endpoint answering with **hand-built gRPC frames**: a
 * length-prefixed protobuf stream and a `grpc-status` trailer, which is all a gRPC response is.
 * Nothing about the client, the schema or the adapter is stood in for.
 * `CentrifugoStreamIntegrationTest` then drives the real broker, and `docs/testing/stage-7.1.md`
 * records the run on the device.
 */
class UniStreamInteropTest {
    private val certificate =
        HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
    private val server = MockWebServer()

    /** A client that trusts the test certificate, as the phone trusts a real one's. */
    private val httpClient: OkHttpClient =
        HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build().let {
            ConnectOkHttpClient.configureClient(
                    OkHttpClient.Builder().sslSocketFactory(it.sslSocketFactory(), it.trustManager)
                )
                .build()
        }

    private val stream = CentrifugoFeedStream(httpClient)

    @Before
    fun start() {
        server.useHttps(
            HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory()
        )
        // HTTP/2 first, because gRPC is an HTTP/2 protocol and the deployed path negotiates it over
        // TLS (broadcast/Caddyfile routes the one gRPC path to the broker as h2c behind it).
        server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After fun stop() = server.close()

    private val url: String
        get() = "https://localhost:${server.port}"

    private fun listen(resume: Map<String, FeedCursor> = emptyMap()): List<FeedStreamEvent> =
        runBlocking {
            withTimeout(30_000) { stream.listen(url, TICKET, resume).toList() }
        }

    /** A gRPC response: length-prefixed messages, then the status in a trailer. */
    private fun answer(vararg pushes: Push, status: Int = 0) {
        val body = Buffer()
        for (push in pushes) {
            val bytes = push.toByteArray()
            body.writeByte(0) // not compressed
            body.writeInt(bytes.size)
            body.write(bytes)
        }
        server.enqueue(
            MockResponse.Builder()
                .setHeader("content-type", "application/grpc")
                .body(body)
                .trailers(headersOf("grpc-status", status.toString()))
                .build()
        )
    }

    /** The connect request the phone sent, decoded from its own gRPC framing. */
    private fun sent(): ConnectRequest {
        val body = Buffer().write(server.takeRequest().body!!)
        body.readByte() // the compression flag
        body.readInt() // the length
        return ConnectRequest.parseFrom(body.readByteArray())
    }

    /**
     * What goes up, in full.
     *
     * The ticket, the channels' positions, and a name that says which application is connected. No
     * device identifier, no installation, no address, nothing derived from the owner — a listener
     * is anonymous, and this is where that is either true or not (docs/security.md).
     */
    @Test
    fun theRequestCarriesTheTicketThePositionsAndNothingAboutThePhone() {
        answer(opened(recovered = false))

        listen(mapOf(CHANNEL to FeedCursor("epoch-7", 41)))

        val request = sent()
        assertEquals(TICKET, request.token)
        assertEquals(setOf(CHANNEL), request.subsMap.keys)
        val position = request.subsMap.getValue(CHANNEL)
        assertTrue("recovery is asked for", position.recover)
        assertEquals("epoch-7", position.epoch)
        assertEquals(41L, position.offset)
        // Every other field of the request, empty. `data` is where a client would put its own
        // connection payload, and this one has nothing to say about itself.
        assertEquals("seekervault", request.name)
        assertEquals(ByteString.EMPTY, request.data)
        assertEquals(emptyMap<String, String>(), request.headersMap)
    }

    /**
     * The connect answer, as the events the app acts on: the opening with the broker's verdict per
     * channel, then the documents it replayed — in order, and as our own envelopes.
     */
    @Test
    fun aRecoveredSubscriptionArrivesAsTheOpeningAndThenTheDocumentsItReplayed() {
        answer(opened(recovered = true, replayed = listOf(42L to 5L, 43L to 6L)))

        val events = listen(mapOf(CHANNEL to FeedCursor(EPOCH, 41)))

        val opening = events.first() as FeedStreamEvent.Opened
        val subscription = opening.subscriptions.getValue(CHANNEL)
        assertEquals(EPOCH, subscription.epoch)
        assertTrue(subscription.recovered)
        assertTrue(subscription.wasRecovering)
        assertTrue(subscription.recoverable)
        // And that is the case where nothing has to be read from the gateway.
        assertEquals(Continuity.Recovered, continuity(FeedCursor(EPOCH, 41), subscription))

        val replayed = events.drop(1).map { it as FeedStreamEvent.Published }
        assertEquals(listOf(42L, 43L), replayed.map { it.offset })
        assertEquals(listOf(5L, 6L), replayed.map { it.event.proposal.revision })
        assertEquals(listOf(CHANNEL, CHANNEL), replayed.map { it.streamChannel })
        assertEquals(PROPOSAL, replayed.first().event.proposal.proposalId)
    }

    @Test
    fun aLivePublicationArrivesWithItsOffsetAndItsDocument() {
        answer(
            opened(recovered = false),
            Push.newBuilder()
                .setChannel(CHANNEL)
                .setPub(
                    Publication.newBuilder()
                        .setOffset(7)
                        .setData(event(revision = 9).toByteString())
                        .build()
                )
                .build(),
        )

        val published = listen().filterIsInstance<FeedStreamEvent.Published>().single()

        assertEquals(CHANNEL, published.streamChannel)
        assertEquals(7L, published.offset)
        assertEquals(9L, published.event.proposal.revision)
        assertEquals(3L, published.event.sequence)
    }

    /**
     * The two ways the broker ends something, kept apart: one channel's subscription, and the whole
     * stream. The code is what the caller's policy reads, so it arrives unchanged.
     */
    @Test
    fun anUnsubscribeAndADisconnectArriveAsThemselvesWithTheirCodes() {
        answer(
            opened(recovered = false),
            Push.newBuilder()
                .setChannel(CHANNEL)
                .setUnsubscribe(
                    Unsubscribe.newBuilder().setCode(2500).setReason("insufficient state").build()
                )
                .build(),
            Push.newBuilder()
                .setDisconnect(Disconnect.newBuilder().setCode(3001).setReason("shutdown").build())
                .build(),
        )

        val events = listen()

        val dropped = events.filterIsInstance<FeedStreamEvent.Dropped>().single()
        assertEquals(CHANNEL, dropped.streamChannel)
        assertEquals(2500, dropped.code)
        val closed = events.filterIsInstance<FeedStreamEvent.Closed>().single()
        assertEquals(3001, closed.code)
        // And a shutdown is something to come back from, which is the policy that reads it.
        assertEquals(AfterClose.Reconnect(reticket = false), afterClose(closed.code))
    }

    /**
     * A payload this version cannot read is reported, not dropped.
     *
     * Silence would be the dangerous answer: the channel moved, and a listener that ignored the
     * event would carry on believing it was up to date. Reporting it is what makes the session read
     * the snapshot instead.
     */
    @Test
    fun bytesThatAreNotAnEnvelopeAreReportedRatherThanIgnored() {
        answer(
            opened(recovered = false),
            Push.newBuilder()
                .setChannel(CHANNEL)
                .setPub(
                    Publication.newBuilder()
                        .setOffset(11)
                        .setData(ByteString.copyFromUtf8("  not a document"))
                        .build()
                )
                .build(),
        )

        val unreadable = listen().filterIsInstance<FeedStreamEvent.Unreadable>().single()

        assertEquals(CHANNEL, unreadable.streamChannel)
        assertEquals(11L, unreadable.offset)
    }

    /** A frame carrying nothing the protocol has yet is never read as a document. */
    @Test
    fun aPushThisVersionDoesNotKnowIsNotADocument() {
        answer(opened(recovered = false), Push.newBuilder().setChannel(CHANNEL).build())

        val events = listen()

        assertEquals(1, events.count { it == FeedStreamEvent.Alive })
        assertTrue(events.none { it is FeedStreamEvent.Published })
    }

    /**
     * A grant the server would not take is reported as a grant problem, not as a network one: the
     * session asks for a new ticket rather than waiting for a gateway that is perfectly fine.
     */
    @Test
    fun aRefusedGrantIsAnAuthenticationFailureAndNotAnOutage() {
        // gRPC's UNAUTHENTICATED, which is what a broker answers a token it will not read.
        answer(status = 16)

        val failure =
            try {
                listen()
                error("listening succeeded")
            } catch (e: FeedStreamException) {
                e
            }

        assertEquals(FeedStreamException.Kind.Unauthenticated, failure.kind)
    }

    /**
     * Nothing serving the broker's procedure at that origin — an older gateway, or one deployed
     * without a broker — is its own answer, because the phone's reaction is different: read unary,
     * and stop trying to listen.
     */
    @Test
    fun noStreamAtTheOriginIsSaidAsSuchRatherThanRetriedForEver() {
        answer(status = 12) // UNIMPLEMENTED

        val failure =
            try {
                listen()
                error("listening succeeded")
            } catch (e: FeedStreamException) {
                e
            }

        assertEquals(FeedStreamException.Kind.Unsupported, failure.kind)
    }

    /** And the one call it makes is the broker's, at the path the generated client knows. */
    @Test
    fun itCallsTheBrokersOwnProcedure() {
        answer(opened(recovered = false))

        listen()

        assertEquals(
            "/centrifugal.centrifugo.unistream.CentrifugoUniStream/Consume",
            server.takeRequest().target,
        )
    }

    private fun opened(recovered: Boolean, replayed: List<Pair<Long, Long>> = emptyList()): Push {
        val result =
            SubscribeResult.newBuilder()
                .setEpoch(EPOCH)
                .setOffset(if (recovered) 41 else 44)
                .setRecoverable(true)
                .setPositioned(true)
                .setRecovered(recovered)
                .setWasRecovering(true)
        for ((offset, revision) in replayed) {
            result.addPublications(
                Publication.newBuilder()
                    .setOffset(offset)
                    .setData(event(revision).toByteString())
                    .build()
            )
        }
        return Push.newBuilder()
            .setConnect(
                Connect.newBuilder()
                    .setClient("11111111-2222-3333-4444-555555555555")
                    .setVersion("6.9.6 OSS")
                    .putSubs(CHANNEL, result.build())
                    .build()
            )
            .build()
    }

    /** One of our own envelopes, which is what a publication on a feed channel carries. */
    private fun event(revision: Long): FeedEvent =
        FeedEvent.newBuilder()
            .setSequence(3)
            .setProposal(
                Proposal.newBuilder()
                    .setServerId(SERVER)
                    .setChannel("server/$SERVER")
                    .setProposalId(PROPOSAL)
                    .setRevision(revision)
                    .build()
            )
            .build()

    private companion object {
        const val TICKET = "a-ticket-the-gateway-minted"
        const val SERVER = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val PROPOSAL = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        const val CHANNEL = "feed:server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val EPOCH = "hKsZ1p"
    }
}
