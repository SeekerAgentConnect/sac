package io.github.brrenat.seekervault.connections

import com.google.protobuf.timestamp
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Asset
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.SubmitResultRequest
import io.github.brrenat.seekervault.request.v1.WalletBinding
import io.github.brrenat.seekervault.request.v1.ackAction
import io.github.brrenat.seekervault.request.v1.action
import io.github.brrenat.seekervault.request.v1.actionRequest
import io.github.brrenat.seekervault.request.v1.asset
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.request.v1.transferAction
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Fake sidecars keyed by URL, each with its own connections and one active phone, like the real
 * one. It records every secret it's sent together with the URL it went to, so tests can check that
 * a credential never leaves its own server, and every result it's sent.
 */
class FakeConnectionGateway : ConnectionGateway {
    class Server(val serverId: String) {
        val pairingTokens = mutableSetOf<String>()
        /** Connection ID to credential, in pairing order. */
        val connections = linkedMapOf<String, String>()
        val revoked = mutableSetOf<String>()
        /** Each connection's PENDING requests, oldest first. */
        val pending = mutableMapOf<String, MutableList<ActionRequest>>()
        /** Each connection's requests that left PENDING, by request ID. */
        val settled = mutableMapOf<String, MutableMap<String, ActionRequest>>()
        // The result the sidecar accepted for each settled request, to recognize a repeat.
        private val accepted = mutableMapOf<String, SubmitResultRequest.ResultCase>()
        /** The wallet the phone published, or null when it published none (SAW-015). */
        var wallet: WalletBinding? = null
        /** How many times the phone published a binding to this server. */
        var publications = 0
        /** When set, every call to this server fails this way. */
        var failure: GatewayException.Kind? = null
        /** The next SubmitResult takes effect, but its response is lost on the way back. */
        var loseNextResponse = false
        /** How many requests one ListPending page holds. */
        var pageSize = 100

        /** A fresh pairing code for this server at [url], as `pnpm pair` shows it. */
        fun issue(url: String): PairingCode {
            val token = newSecret()
            pairingTokens += token
            return PairingCode(url, serverId, token)
        }

        /** Stores a PENDING ack, as an agent does with `vault_request_ack`. */
        fun addPending(
            connectionId: String,
            requestId: String = UUID.randomUUID().toString(),
            text: String = "Deploy finished",
            expiresAt: Instant = Instant.now().plusSeconds(86_400),
        ): ActionRequest =
            request(connectionId, requestId, text, expiresAt = expiresAt).also {
                pending.getOrPut(connectionId) { mutableListOf() } += it
            }

        /**
         * Stores a PENDING transfer, which is bound to a wallet and a network (SAW-015). Only a
         * matching binding covers it; publishing another one cancels it.
         */
        fun addPendingTransfer(
            connectionId: String,
            wallet: String,
            network: Network,
            requestId: String = UUID.randomUUID().toString(),
        ): ActionRequest = actionRequest {
            ref = requestRef {
                this.connectionId = connectionId
                this.requestId = requestId
            }
            action = action {
                transfer = transferAction {
                    this.wallet = wallet
                    this.network = network
                    recipient = wallet
                    asset = asset { nativeSol = Asset.NativeSol.getDefaultInstance() }
                    amount = "1"
                }
            }
            state = RequestState.REQUEST_STATE_PENDING
            createdAt = timestamp { seconds = Instant.now().epochSecond }
            expiresAt = timestamp { seconds = Instant.now().plusSeconds(86_400).epochSecond }
        }
            .also { pending.getOrPut(connectionId) { mutableListOf() } += it }

        /** Cancels a PENDING request, as the agent does with `vault_cancel_request`. */
        fun cancel(connectionId: String, requestId: String) {
            val request =
                pending[connectionId]?.firstOrNull { it.ref.requestId == requestId } ?: return
            pending.getValue(connectionId).remove(request)
            settled.getOrPut(connectionId) { mutableMapOf() }[requestId] =
                request.toBuilder().setState(RequestState.REQUEST_STATE_CANCELLED).build()
        }

        /** Revokes a connection, as `pnpm pair revoke` does. */
        fun revoke(connectionId: String) {
            revoked += connectionId
            pending.remove(connectionId)
        }

        fun authenticate(credential: String): String? =
            connections.entries.firstOrNull { it.value == credential && it.key !in revoked }?.key

        fun stateOf(connectionId: String, requestId: String): RequestState? =
            settled[connectionId]?.get(requestId)?.state
                ?: pending[connectionId]
                    ?.firstOrNull { it.ref.requestId == requestId }
                    ?.let { RequestState.REQUEST_STATE_PENDING }

        // The lifecycle for acks: acknowledgement → COMPLETED, rejection → REJECTED, and a repeat
        // of the accepted result returns the request unchanged.
        fun apply(connectionId: String, submission: SubmitResultRequest): ActionRequest {
            val requestId = submission.ref.requestId
            val key = "$connectionId/$requestId"
            val waiting = pending[connectionId]?.firstOrNull { it.ref.requestId == requestId }
            if (waiting != null) {
                val state =
                    when (submission.resultCase) {
                        SubmitResultRequest.ResultCase.ACKNOWLEDGEMENT ->
                            RequestState.REQUEST_STATE_COMPLETED
                        SubmitResultRequest.ResultCase.REJECTION ->
                            RequestState.REQUEST_STATE_REJECTED
                        else ->
                            throw GatewayException(GatewayException.Kind.Rejected, "not for acks")
                    }
                val after = waiting.toBuilder().setState(state).build()
                pending.getValue(connectionId).remove(waiting)
                settled.getOrPut(connectionId) { mutableMapOf() }[requestId] = after
                accepted[key] = submission.resultCase
                return after
            }
            val now =
                settled[connectionId]?.get(requestId)
                    ?: throw GatewayException(GatewayException.Kind.NotFound, "no such request")
            if (accepted[key] == submission.resultCase) return now
            throw GatewayException(GatewayException.Kind.InvalidState, "moved on", request = now)
        }
    }

    private val servers = mutableMapOf<String, Server>()

    /** Every wallet binding a server was told, with its URL; null means "no wallet". */
    val published = mutableListOf<Pair<String, WalletBinding?>>()

    /** Every call's (URL, secret): pairing tokens for Pair, credentials for the rest. */
    val sent = mutableListOf<Pair<String, String>>()

    /** Every SubmitResult that reached a server, with its URL. */
    val submits = mutableListOf<Pair<String, SubmitResultRequest>>()

    /** Changes the next PairResponse, to test the app's checks of it. */
    var tamperPair: ((PairedConnection) -> PairedConnection)? = null

    /** Requests every ListPending also returns, as if the sidecar mixed connections up. */
    val foreignRequests = mutableListOf<ActionRequest>()

    /** Runs before each SubmitResult is applied, to hold it or to check what's going on. */
    var beforeSubmit: suspend () -> Unit = {}

    /**
     * Runs after ListPending reads its page and before it returns it, to hold a page going stale.
     */
    var afterList: suspend () -> Unit = {}

    /** A server reachable at [url]. Passing an existing [server] makes it reachable there too. */
    fun serve(url: String, server: Server = Server(UUID.randomUUID().toString())): Server {
        servers[url] = server
        return server
    }

    override suspend fun pair(code: PairingCode, deviceName: String): PairedConnection {
        val server = reach(code.serverUrl, code.token)
        if (!server.pairingTokens.remove(code.token)) {
            throw GatewayException(GatewayException.Kind.Unauthenticated, "unknown pairing token")
        }
        server.connections.keys.forEach { server.revoke(it) } // one active phone
        val paired =
            PairedConnection(UUID.randomUUID().toString(), newSecret(), server.serverId).also {
                server.connections[it.connectionId] = it.credential
            }
        return tamperPair?.let {
            tamperPair = null
            it(paired)
        } ?: paired
    }

    override suspend fun listPending(
        serverUrl: String,
        credential: String,
        connectionId: String,
        pageToken: String,
    ): PendingRequests {
        val server = reach(serverUrl, credential)
        val id = authenticated(server, credential, connectionId)
        val all = server.pending[id].orEmpty() + foreignRequests
        val start = pageToken.toIntOrNull() ?: 0
        val end = start + server.pageSize
        val page =
            PendingRequests(
                all.subList(start, minOf(end, all.size)),
                if (end < all.size) "$end" else "",
            )
        afterList()
        return page
    }

    override suspend fun submitResult(
        serverUrl: String,
        credential: String,
        submission: SubmitResultRequest,
    ): ActionRequest {
        val server = reach(serverUrl, credential)
        val id = authenticated(server, credential, submission.ref.connectionId)
        beforeSubmit()
        submits += serverUrl to submission
        val after = server.apply(id, submission)
        if (server.loseNextResponse) {
            server.loseNextResponse = false
            throw GatewayException(GatewayException.Kind.Unreachable, "the response was lost")
        }
        return after
    }

    override suspend fun publishWallet(
        serverUrl: String,
        credential: String,
        connectionId: String,
        binding: WalletBinding?,
    ): List<String> {
        val server = reach(serverUrl, credential)
        val id = authenticated(server, credential, connectionId)
        published += serverUrl to binding
        server.publications++
        if (binding == server.wallet) return emptyList()
        server.wallet = binding
        // The sidecar cancels the PENDING requests the new binding no longer fits. The fake queues
        // only acks, which no binding covers, so nothing is cancelled unless a test says otherwise.
        val unfitting =
            server.pending[id].orEmpty().filter { !it.action.hasAck() }.map { it.ref.requestId }
        unfitting.forEach { server.cancel(id, it) }
        return unfitting
    }

    override suspend fun revoke(serverUrl: String, credential: String, connectionId: String) {
        val server = reach(serverUrl, credential)
        server.revoke(authenticated(server, credential, connectionId))
    }

    private fun reach(url: String, secret: String): Server {
        sent += url to secret
        val server =
            servers[url] ?: throw GatewayException(GatewayException.Kind.Unreachable, "no server")
        server.failure?.let { throw GatewayException(it, "fake $it") }
        return server
    }

    private fun authenticated(server: Server, credential: String, connectionId: String): String {
        val id =
            server.authenticate(credential)
                ?: throw GatewayException(GatewayException.Kind.Unauthenticated, "revoked")
        if (id != connectionId) throw GatewayException(GatewayException.Kind.NotFound, "no such")
        return id
    }

    companion object {
        /** A PENDING ack request with its connection's and its own ID. */
        fun request(
            connectionId: String,
            requestId: String = UUID.randomUUID().toString(),
            text: String = "Deploy finished",
            createdAt: Instant = Instant.now(),
            expiresAt: Instant = createdAt.plusSeconds(86_400),
        ): ActionRequest = actionRequest {
            ref = requestRef {
                this.connectionId = connectionId
                this.requestId = requestId
            }
            action = action { ack = ackAction { this.text = text } }
            state = RequestState.REQUEST_STATE_PENDING
            this.createdAt = timestamp {
                seconds = createdAt.epochSecond
                nanos = createdAt.nano
            }
            this.expiresAt = timestamp {
                seconds = expiresAt.epochSecond
                nanos = expiresAt.nano
            }
        }
    }
}

/** A pairing token or credential: 32 random bytes in base64url, as the sidecar makes them. */
fun newSecret(): String =
    Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

/** An AES key in software, standing in for the Keystore key that Robolectric doesn't have. */
fun softwareKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
