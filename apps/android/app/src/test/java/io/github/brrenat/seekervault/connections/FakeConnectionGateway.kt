package io.github.brrenat.seekervault.connections

import com.google.protobuf.ByteString
import com.google.protobuf.timestamp
import io.github.brrenat.seekervault.push.RelayCoordinates
import io.github.brrenat.seekervault.push.RelayHandleUpdate
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Asset
import io.github.brrenat.seekervault.request.v1.ConfirmationLevel
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.PreparedTransaction
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.StakingOperation
import io.github.brrenat.seekervault.request.v1.SubmitResultRequest
import io.github.brrenat.seekervault.request.v1.WalletBinding
import io.github.brrenat.seekervault.request.v1.ackAction
import io.github.brrenat.seekervault.request.v1.action
import io.github.brrenat.seekervault.request.v1.actionRequest
import io.github.brrenat.seekervault.request.v1.asset
import io.github.brrenat.seekervault.request.v1.confirmation
import io.github.brrenat.seekervault.request.v1.preparedTransaction
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.request.v1.signMessageAction
import io.github.brrenat.seekervault.request.v1.stakingAction
import io.github.brrenat.seekervault.request.v1.swapAction
import io.github.brrenat.seekervault.request.v1.transferAction
import io.github.brrenat.seekervault.server.v1.ServerManifest
import java.security.MessageDigest
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
    /** How many times each request has been prepared: every call is a new version. */
    val preparations = mutableMapOf<RequestKey, Int>()
    /** The transaction bytes to hand over for a request, when a test supplies real ones. */
    val transactions = mutableMapOf<RequestKey, ByteArray>()

    class Server(val serverId: String) {
        val pairingTokens = mutableSetOf<String>()
        /** Connection ID to credential, in pairing order. */
        val connections = linkedMapOf<String, String>()
        val revoked = mutableSetOf<String>()
        /** Each connection's PENDING requests, oldest first. */
        val pending = mutableMapOf<String, MutableList<ActionRequest>>()
        /** Each connection's requests that left PENDING, by request ID. */
        val settled = mutableMapOf<String, MutableMap<String, ActionRequest>>()
        /** The latest prepared version per request ID: only that one can be approved. */
        val preparedVersions = mutableMapOf<String, Int>()
        // The result the sidecar accepted for each settled request, to recognize a repeat.
        private val accepted = mutableMapOf<String, SubmitResultRequest.ResultCase>()
        /** The wallet the phone published, or null when it published none (SAW-015). */
        var wallet: WalletBinding? = null
        /** How many times the phone published a binding to this server. */
        var publications = 0
        /** Each active connection's current private FCM target. */
        val fcmTokens = mutableMapOf<String, String>()
        /**
         * Each active connection's current gateway push handle (SEE-144). A separate map from
         * [fcmTokens] because they are separate values on the real server too: one addresses a
         * device, the other one authorization at one gateway.
         */
        val relayHandles = mutableMapOf<String, String>()
        /** What this server advertises about its relay, or null when it sends its own push. */
        var relay: RelayCoordinates? = null
        /** When set, every call to this server fails this way. */
        var failure: GatewayException.Kind? = null
        /**
         * What this server publishes about itself (SEE-88), or null for one that predates manifests
         * and answers UNIMPLEMENTED. Null is the default, so a test that says nothing about
         * manifests is testing a legacy direct server, exactly as it did before.
         */
        var manifest: ServerManifest? = null
        /** The next SubmitResult takes effect, but its response is lost on the way back. */
        var loseNextResponse = false
        /** How many requests one ListPending page holds. */
        var pageSize = 100
        /** What the chain will say about a request the next time the phone checks (SAW-022). */
        val onChain = mutableMapOf<String, Confirmed>()
        /** How many times the phone has asked this server to check a status. */
        var checks = 0
        /** How many ListPending pages the phone has asked this server for. */
        var lists = 0

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
            recipient: String = wallet,
            amount: String = "1",
            tokenMint: String? = null,
        ): ActionRequest = actionRequest {
            ref = requestRef {
                this.connectionId = connectionId
                this.requestId = requestId
            }
            action = action {
                transfer = transferAction {
                    this.wallet = wallet
                    this.network = network
                    this.recipient = recipient
                    asset = asset {
                        if (tokenMint == null) nativeSol = Asset.NativeSol.getDefaultInstance()
                        else this.tokenMint = tokenMint
                    }
                    this.amount = amount
                }
            }
            state = RequestState.REQUEST_STATE_PENDING
            createdAt = timestamp { seconds = Instant.now().epochSecond }
            expiresAt = timestamp { seconds = Instant.now().plusSeconds(86_400).epochSecond }
        }
            .also { pending.getOrPut(connectionId) { mutableListOf() } += it }

        /** Stores a PENDING staking action, bound to a wallet and to a network (SEE-146). */
        fun addPendingStaking(
            connectionId: String,
            wallet: String,
            network: Network,
            operation: StakingOperation,
            amount: String = "",
            requestId: String = UUID.randomUUID().toString(),
        ): ActionRequest = actionRequest {
            ref = requestRef {
                this.connectionId = connectionId
                this.requestId = requestId
            }
            action = action {
                staking = stakingAction {
                    this.wallet = wallet
                    this.network = network
                    this.operation = operation
                    if (amount.isNotEmpty()) this.amount = amount
                }
            }
            state = RequestState.REQUEST_STATE_PENDING
            createdAt = timestamp { seconds = Instant.now().epochSecond }
            expiresAt = timestamp { seconds = Instant.now().plusSeconds(86_400).epochSecond }
        }
            .also { pending.getOrPut(connectionId) { mutableListOf() } += it }

        /**
         * Stores a PENDING sign_message, which is bound to a wallet but to no network (SAW-016).
         */
        fun addPendingMessage(
            connectionId: String,
            wallet: String,
            text: String = "Sign in to Example",
            requestId: String = UUID.randomUUID().toString(),
            expiresAt: Instant = Instant.now().plusSeconds(86_400),
        ): ActionRequest = actionRequest {
            ref = requestRef {
                this.connectionId = connectionId
                this.requestId = requestId
            }
            action = action {
                signMessage = signMessageAction {
                    this.wallet = wallet
                    this.text = text
                }
            }
            state = RequestState.REQUEST_STATE_PENDING
            createdAt = timestamp { seconds = Instant.now().epochSecond }
            this.expiresAt = timestamp { seconds = expiresAt.epochSecond }
        }
            .also { pending.getOrPut(connectionId) { mutableListOf() } += it }

        /**
         * Stores a PENDING swap, which a client plugin would carry out (SEE-86). Nothing in this
         * build serves the operation, so it exists here to prove the phone establishes nothing
         * about it rather than to exercise a swap.
         */
        fun addPendingSwap(
            connectionId: String,
            wallet: String,
            network: Network,
            requestId: String = UUID.randomUUID().toString(),
            outputMint: String = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            inputAmount: String = "250",
        ): ActionRequest = actionRequest {
            ref = requestRef {
                this.connectionId = connectionId
                this.requestId = requestId
            }
            action = action {
                swap = swapAction {
                    this.wallet = wallet
                    this.network = network
                    inputAsset = asset { nativeSol = Asset.NativeSol.getDefaultInstance() }
                    outputAsset = asset { tokenMint = outputMint }
                    this.inputAmount = inputAmount
                    slippageBps = 50
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
            fcmTokens.remove(connectionId)
        }

        fun authenticate(credential: String): String? =
            connections.entries.firstOrNull { it.value == credential && it.key !in revoked }?.key

        /**
         * What one look at the chain finds. The real sidecar works this out from the signature's
         * status and the approved bytes; the fake is simply told the answer.
         */
        class Confirmed(
            val state: RequestState,
            val detail: String = "",
            val level: ConfirmationLevel = ConfirmationLevel.CONFIRMATION_LEVEL_FINALIZED,
            val endpoint: String = "rpc.test.invalid",
            val matchesApproval: Boolean = true,
        )

        /** Says what the chain holds for a request, for the phone's next status check. */
        fun putOnChain(connectionId: String, requestId: String, found: Confirmed) {
            onChain["$connectionId/$requestId"] = found
        }

        /**
         * One status check: the sidecar reads the chain and answers with the request as it stands.
         * Nothing here signs or sends, exactly as on the real one.
         */
        fun check(connectionId: String, requestId: String): ActionRequest {
            checks++
            val now =
                settled[connectionId]?.get(requestId)
                    ?: pending[connectionId]?.firstOrNull { it.ref.requestId == requestId }
                    ?: throw GatewayException(GatewayException.Kind.NotFound, "no such request")
            if (
                now.state != RequestState.REQUEST_STATE_SUBMITTED &&
                    now.state != RequestState.REQUEST_STATE_UNKNOWN
            ) {
                throw GatewayException(
                    GatewayException.Kind.InvalidState,
                    "nothing left to check on chain",
                    request = now,
                )
            }
            val found =
                onChain["$connectionId/$requestId"]
                    ?: Confirmed(
                        now.state,
                        "The endpoint has no status for this signature yet.",
                        ConfirmationLevel.CONFIRMATION_LEVEL_NOT_FOUND,
                        matchesApproval = false,
                    )
            val outcome =
                now.outcome
                    .toBuilder()
                    .setConfirmation(
                        confirmation {
                            level = found.level
                            endpoint = found.endpoint
                            matchesApproval = found.matchesApproval
                            detail = found.detail
                            checks = this@Server.checks
                        }
                    )
                    .also { if (found.state != now.state) it.detail = found.detail }
                    .build()
            val after = now.toBuilder().setState(found.state).setOutcome(outcome).build()
            settled.getOrPut(connectionId) { mutableMapOf() }[requestId] = after
            return after
        }

        fun stateOf(connectionId: String, requestId: String): RequestState? =
            settled[connectionId]?.get(requestId)?.state
                ?: pending[connectionId]
                    ?.firstOrNull { it.ref.requestId == requestId }
                    ?.let { RequestState.REQUEST_STATE_PENDING }

        // The lifecycle this fake serves: an ack is acknowledged or rejected, and a message or a
        // transfer is approved (PROCESSING) and then settled by what the wallet did. A transfer's
        // approval must name the latest prepared version, as the real sidecar requires. A repeat
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
                        // An approval doesn't end a wallet request: it hands it to the wallet.
                        SubmitResultRequest.ResultCase.APPROVAL ->
                            when {
                                waiting.action.hasSignMessage() ->
                                    RequestState.REQUEST_STATE_PROCESSING
                                // A transfer or a staking action: both are approved by naming the
                                // exact preparation the owner reviewed, and a stale one is refused
                                // here the way the real sidecar refuses it (SEE-146).
                                waiting.action.hasTransfer() || waiting.action.hasStaking() -> {
                                    val latest = preparedVersions[requestId]
                                    if (submission.approval.preparedVersion != latest) {
                                        throw GatewayException(
                                            GatewayException.Kind.StalePreparation,
                                            "version ${submission.approval.preparedVersion} " +
                                                "isn't the latest ($latest)",
                                            request = waiting,
                                        )
                                    }
                                    RequestState.REQUEST_STATE_PROCESSING
                                }
                                else ->
                                    throw GatewayException(
                                        GatewayException.Kind.Rejected,
                                        "nothing to approve",
                                    )
                            }
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
            // An approved message or transfer is PROCESSING, and what the wallet did settles it.
            // An outcome the phone couldn't determine leaves it UNKNOWN, which a later report can
            // still settle, exactly as the real lifecycle allows.
            if (
                now.state == RequestState.REQUEST_STATE_PROCESSING ||
                    now.state == RequestState.REQUEST_STATE_UNKNOWN
            ) {
                val state =
                    when (submission.resultCase) {
                        SubmitResultRequest.ResultCase.MESSAGE_SIGNATURE ->
                            RequestState.REQUEST_STATE_COMPLETED
                        SubmitResultRequest.ResultCase.TRANSACTION_SUBMISSION ->
                            RequestState.REQUEST_STATE_SUBMITTED
                        SubmitResultRequest.ResultCase.REJECTION ->
                            RequestState.REQUEST_STATE_REJECTED
                        SubmitResultRequest.ResultCase.EXECUTION_FAILURE ->
                            RequestState.REQUEST_STATE_FAILED
                        SubmitResultRequest.ResultCase.UNKNOWN_OUTCOME ->
                            if (now.state == RequestState.REQUEST_STATE_PROCESSING)
                                RequestState.REQUEST_STATE_UNKNOWN
                            else
                                throw GatewayException(
                                    GatewayException.Kind.InvalidState,
                                    "already unknown",
                                    request = now,
                                )
                        else ->
                            throw GatewayException(
                                GatewayException.Kind.InvalidState,
                                "not from ${now.state}",
                                request = now,
                            )
                    }
                val after = now.toBuilder().setState(state).build()
                settled.getValue(connectionId)[requestId] = after
                accepted[key] = submission.resultCase
                return after
            }
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
    /** Runs before a status check answers, so a test can hold one open (SAW-022). */
    var beforeCheck: suspend () -> Unit = {}
    /** Runs before a wallet publication is applied, so a test can hold a binding open. */
    var beforePublishWallet: suspend () -> Unit = {}

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

    override suspend fun serverManifest(
        serverUrl: String,
        credential: String,
        connectionId: String,
    ): ServerManifest? {
        val server = reach(serverUrl, credential)
        authenticated(server, credential, connectionId)
        return server.manifest
    }

    override suspend fun listPending(
        serverUrl: String,
        credential: String,
        connectionId: String,
        pageToken: String,
    ): PendingRequests {
        val server = reach(serverUrl, credential)
        val id = authenticated(server, credential, connectionId)
        server.lists++
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

    override suspend fun prepareRequest(
        serverUrl: String,
        credential: String,
        key: RequestKey,
    ): PreparedTransaction {
        val server = reach(serverUrl, credential)
        val id = authenticated(server, credential, key.connectionId)
        val request =
            server.pending[id]?.firstOrNull { it.ref.requestId == key.requestId }
                ?: throw GatewayException(GatewayException.Kind.NotFound, "no such request")
        // A transfer or a staking action: the two kinds a server builds a transaction for
        // (SEE-146). Everything else has nothing to prepare.
        if (!request.action.hasTransfer() && !request.action.hasStaking()) {
            throw GatewayException(
                GatewayException.Kind.Rejected,
                "this request has nothing to prepare",
            )
        }
        // A new version every time, as the real sidecar does: each preparation supersedes the last.
        val version = preparations.merge(key, 1) { old, _ -> old + 1 } ?: 1
        server.preparedVersions[key.requestId] = version
        val bytes = prepared(key, version)
        return preparedTransaction {
            ref = request.ref
            this.version = version
            transaction = ByteString.copyFrom(bytes)
            contentHash = ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(bytes))
            feeLamports = 5_000L
            rentLamports = 0L
            preparedExpiry?.let {
                estimatedExpiry = timestamp {
                    seconds = it.epochSecond
                    nanos = it.nano
                }
            }
        }
    }

    /**
     * When the preparations this fake hands out say their blockhash window closes. Null leaves it
     * unset, which is what most tests want: the freshness check then has nothing to judge.
     */
    var preparedExpiry: Instant? = null

    /**
     * The bytes this fake hands over. Tests that read them supply their own through [transactions].
     */
    private fun prepared(key: RequestKey, version: Int): ByteArray =
        transactions[key] ?: "not a transaction, version $version".toByteArray()

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

    override suspend fun checkStatus(
        serverUrl: String,
        credential: String,
        key: RequestKey,
    ): ActionRequest {
        val server = reach(serverUrl, credential)
        val id = authenticated(server, credential, key.connectionId)
        beforeCheck()
        return server.check(id, key.requestId)
    }

    override suspend fun publishWallet(
        serverUrl: String,
        credential: String,
        connectionId: String,
        binding: WalletBinding?,
    ): List<String> {
        val server = reach(serverUrl, credential)
        val id = authenticated(server, credential, connectionId)
        beforePublishWallet()
        published += serverUrl to binding
        server.publications++
        if (binding == server.wallet) return emptyList()
        server.wallet = binding
        // The sidecar cancels the PENDING requests the new binding no longer fits.
        val unfitting =
            server.pending[id].orEmpty().filterNot { fits(it, binding) }.map { it.ref.requestId }
        unfitting.forEach { server.cancel(id, it) }
        return unfitting
    }

    override suspend fun revoke(serverUrl: String, credential: String, connectionId: String) {
        val server = reach(serverUrl, credential)
        server.revoke(authenticated(server, credential, connectionId))
    }

    override suspend fun setFcmToken(
        serverUrl: String,
        credential: String,
        connectionId: String,
        update: FcmTokenUpdate,
    ) {
        val server = reach(serverUrl, credential)
        val id = authenticated(server, credential, connectionId)
        when (update) {
            is FcmTokenUpdate.Register -> server.fcmTokens[id] = update.target
            is FcmTokenUpdate.ClearIfCurrent ->
                if (server.fcmTokens[id] == update.target) server.fcmTokens.remove(id)
        }
    }

    override suspend fun relay(
        serverUrl: String,
        credential: String,
        connectionId: String,
    ): RelayCoordinates? {
        val server = reach(serverUrl, credential)
        authenticated(server, credential, connectionId)
        return server.relay
    }

    override suspend fun setRelayHandle(
        serverUrl: String,
        credential: String,
        connectionId: String,
        update: RelayHandleUpdate,
    ) {
        val server = reach(serverUrl, credential)
        val id = authenticated(server, credential, connectionId)
        when (update) {
            is RelayHandleUpdate.Register -> server.relayHandles[id] = update.handle
            is RelayHandleUpdate.ClearIfCurrent ->
                if (server.relayHandles[id] == update.handle) server.relayHandles.remove(id)
        }
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

    /** Whether a binding covers a request, the way the sidecar's rule does. */
    private fun fits(request: ActionRequest, binding: WalletBinding?): Boolean =
        when {
            request.action.hasAck() -> true
            binding == null -> false
            // A signature over bytes has no network, so only the wallet has to match.
            request.action.hasSignMessage() -> request.action.signMessage.wallet == binding.wallet
            request.action.hasTransfer() ->
                request.action.transfer.wallet == binding.wallet &&
                    request.action.transfer.network == binding.network
            else -> false
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
