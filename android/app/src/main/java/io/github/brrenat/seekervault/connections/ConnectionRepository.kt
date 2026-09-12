package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.request.v1.Acknowledgement
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.PreparedTransaction
import io.github.brrenat.seekervault.request.v1.Rejection
import io.github.brrenat.seekervault.request.v1.SubmitResultRequest
import io.github.brrenat.seekervault.request.v1.WalletBinding
import io.github.brrenat.seekervault.request.v1.executionFailure
import io.github.brrenat.seekervault.request.v1.messageSignature
import io.github.brrenat.seekervault.request.v1.submitResultRequest
import io.github.brrenat.seekervault.request.v1.transactionSubmission
import io.github.brrenat.seekervault.request.v1.unknownOutcome
import io.github.brrenat.seekervault.transactions.transfer
import java.io.IOException
import java.security.GeneralSecurityException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Storing a new connection failed on this phone; nothing was saved. */
class StorageException(cause: Throwable) : Exception(cause.message, cause)

/** What the inbox shows (docs/guides/pending-requests.md). */
data class Inbox(
    /**
     * Each connection's PENDING requests from its last successful fetch, only its own. They're kept
     * in memory: the sidecar has them.
     */
    val pending: Map<String, List<ActionRequest>> = emptyMap(),
    /** The owner's answers, stored on the phone: waiting to be sent, or settled. */
    val results: List<LocalResult> = emptyList(),
) {
    fun result(key: RequestKey): LocalResult? = results.firstOrNull { it.key == key }

    fun pendingRequest(key: RequestKey): ActionRequest? =
        pending[key.connectionId]?.firstOrNull { it.ref.requestId == key.requestId }
}

/**
 * The phone's connections and their requests (docs/security.md#local-storage-and-recovery):
 * pairing, refreshing, answering, renaming, and disconnecting, over [store] (metadata), [vault]
 * (credentials), [results] (answers), and [gateway] (the sidecars).
 * - A connection's credential goes only to that connection's URL, which never changes.
 * - A pairing code always makes a new connection; it never changes an existing one.
 * - A credential the sidecar stops accepting is deleted, and the connection must be paired again.
 * - An answer is stored before it's sent, and sent again until the sidecar settles it.
 * - An approved message takes two sends: the owner's approval, and then what the wallet did. The
 *   wallet is asked only in between, and only by the caller (SAW-016).
 * - An approved transfer takes the same two sends, but the first one commits: [approveTransfer]
 *   returns only once the sidecar has accepted the approval, and an approval it did not accept is
 *   removed rather than kept, so the wallet is never opened for one (SAW-021).
 * - Nothing is answered unless the owner answers: fetching only reads.
 *
 * Network calls run outside the lock; each storage change runs under it.
 */
class ConnectionRepository(
    private val store: ConnectionStore,
    private val vault: CredentialVault,
    private val results: ResultStore,
    private val gateway: ConnectionGateway,
    private val deviceName: String,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private val _connections = MutableStateFlow<List<Connection>>(emptyList())
    val connections: StateFlow<List<Connection>> = _connections.asStateFlow()
    private val _inbox = MutableStateFlow(Inbox())
    val inbox: StateFlow<Inbox> = _inbox.asStateFlow()

    // One send per answer at a time. A send that arrives while another is running waits for it and
    // then sees what it settled, so nothing is dropped and nothing is sent twice (SAW-017).
    private val sending = ConcurrentHashMap<RequestKey, Mutex>()

    // One fetch per connection at a time, so an older fetch can't overwrite a newer one's list.
    private val fetching = ConcurrentHashMap<String, Mutex>()

    /**
     * Reads the stored connections and answers. It deletes credentials and answers that no
     * connection owns, and answers that settled more than a week ago.
     */
    suspend fun load() = locked {
        val ids = store.list().map { it.id }.toSet()
        vault.ids().filter { it !in ids }.forEach(vault::delete)
        results.connectionIds().filter { it !in ids }.forEach(results::deleteConnection)
        val cutoff = now().minus(SETTLED_RETENTION)
        results
            .list()
            .filter { it.delivery != Delivery.Waiting && (it.settledAt ?: it.answeredAt) < cutoff }
            .forEach { results.delete(it.connectionId, it.requestId) }
        publish()
        // The app closed while an action was with the wallet: whatever the wallet did, this phone
        // never learned it, so the approval is settled as unresolved rather than left open. An
        // approved transfer the sidecar never accepted is a different thing: the wallet is opened
        // only after it does, so that one was never asked anything, and it is dropped instead.
        uncommittedApprovals().forEach { results.delete(it.connectionId, it.requestId) }
        abandonedSignings(emptySet()).forEach {
            results.put(
                it.copy(signing = SigningOutcome.Unresolved(it.lostDetail(appClosed = true)))
            )
        }
        publish()
    }

    /**
     * Settles every approval this phone never got a wallet answer for, and sends it (SAW-017). The
     * keys in [except] are the signings in flight in this process, whose wallet is still being
     * asked; everything else was abandoned when the app or the activity went away. Nothing here
     * reaches the wallet: an unresolved signing is reported, never signed again. Returns the
     * requests it settled.
     */
    suspend fun resolveAbandonedSignings(except: Set<RequestKey>): List<RequestKey> {
        val abandoned = locked {
            // An approval the sidecar never accepted was never put to the wallet, so it is dropped
            // rather than settled: the request is still the sidecar's, and still the owner's to
            // review afresh.
            uncommittedApprovals().forEach { results.delete(it.connectionId, it.requestId) }
            abandonedSignings(except)
                .map {
                    it.copy(signing = SigningOutcome.Unresolved(it.lostDetail(appClosed = false)))
                }
                .onEach(results::put)
                .also { if (it.isNotEmpty()) publish() }
        }
        abandoned.forEach { deliver(it.key) }
        return abandoned.map { it.key }
    }

    // Approvals with no wallet answer and nothing settled yet. Call it under the lock.
    private fun abandonedSignings(except: Set<RequestKey>): List<LocalResult> =
        results.list().filter {
            it.answer == Answer.Approve &&
                it.signing == null &&
                it.delivery == Delivery.Waiting &&
                it.key !in except &&
                !it.uncommittedTransfer
        }

    /**
     * Approved transfers the sidecar never accepted. Nothing was asked of the wallet for one, and
     * its request is still the sidecar's to hand back, so it is removed rather than reported: the
     * owner sees it pending again and reviews the preparation as it is then.
     */
    private fun uncommittedApprovals(): List<LocalResult> =
        results.list().filter { it.uncommittedTransfer && it.delivery == Delivery.Waiting }

    /**
     * Pairs with [code]'s sidecar and stores the new connection. Throws [GatewayException] if the
     * sidecar refused or couldn't be reached, or answered with something unusable, and
     * [StorageException] if this phone couldn't store the credential. Either way, nothing is saved.
     */
    suspend fun pair(code: PairingCode): Connection {
        val paired = gateway.pair(code, deviceName)
        // The connection ID names local files, and the credential goes into a bearer header.
        if (
            !isConnectionId(paired.connectionId) ||
                !isSecret(paired.credential) ||
                paired.serverId != code.serverId
        ) {
            throw GatewayException(GatewayException.Kind.BadResponse, "unusable PairResponse")
        }
        val connection =
            Connection(
                id = paired.connectionId,
                label = PairingCodes.hostOf(code.serverUrl),
                serverUrl = code.serverUrl,
                serverId = code.serverId,
                deviceName = deviceName,
                pairedAt = now(),
            )
        locked {
            if (store.get(connection.id) != null) {
                throw GatewayException(GatewayException.Kind.BadResponse, "a known connection ID")
            }
            try {
                vault.put(connection.id, paired.credential)
                store.put(connection)
            } catch (e: GeneralSecurityException) {
                vault.delete(connection.id)
                throw StorageException(e)
            } catch (e: IOException) {
                vault.delete(connection.id)
                throw StorageException(e)
            }
            publish()
        }
        return connection
    }

    /**
     * Fetches the connection's pending requests (docs/protocol.md#phone-api). It first sends the
     * owner's answers that are still waiting, then reads every page. If the sidecar no longer
     * accepts the credential, the connection is marked revoked and the credential deleted. One
     * fetch per connection runs at a time.
     */
    suspend fun refresh(id: String) =
        fetching.computeIfAbsent(id) { Mutex() }.withLock { fetch(id) }

    private suspend fun fetch(id: String) {
        val connection = find(id) ?: return
        if (!connection.usable) return
        val credential = withContext(io) { vault.get(id) }
        if (credential == null) return forgetCredential(id)
        val waiting =
            _inbox.value.results.filter { it.connectionId == id && it.delivery == Delivery.Waiting }
        for (result in waiting) {
            // Stop at the first the sidecar didn't take; the fetch below records why.
            if (deliver(result.key, waitForTheOneInFlight = false)?.delivery == Delivery.Waiting) {
                break
            }
        }
        if (find(id)?.usable != true) return // revoked while the answers were sent
        val check =
            try {
                val own = mutableListOf<ActionRequest>()
                var token = ""
                var pages = 0
                do {
                    val page = gateway.listPending(connection.serverUrl, credential, id, token)
                    // Only this connection's requests count, whatever the sidecar sent, and only
                    // with a request ID that an answer can be stored under.
                    own +=
                        page.requests.filter {
                            it.ref.connectionId == id && isConnectionId(it.ref.requestId)
                        }
                    token = page.nextPageToken
                    pages++
                } while (token.isNotEmpty() && pages < MAX_PAGES)
                val current = locked {
                    // The connection may have been removed while the pages were on their way.
                    if (store.get(id) == null) return@locked emptyList<ActionRequest>()
                    // An answer that settled while the pages were on their way took its request
                    // off the sidecar's list, so pages read earlier mustn't bring it back.
                    val settled =
                        results
                            .listFor(id)
                            .filter { it.delivery != Delivery.Waiting }
                            .map { it.requestId }
                            .toSet()
                    own.filterNot { it.ref.requestId in settled }
                        .also { list ->
                            _inbox.update { it.copy(pending = it.pending + (id to list)) }
                        }
                }
                Connection.Check(
                    now(),
                    CheckOutcome.Ok,
                    current.size,
                    morePending = token.isNotEmpty(),
                )
            } catch (e: GatewayException) {
                if (e.kind == GatewayException.Kind.Unauthenticated) return markRevoked(id)
                Connection.Check(now(), e.kind.toOutcome())
            }
        update(id) { it.copy(lastCheck = check) }
    }

    fun connection(id: String): Connection? = find(id)

    /**
     * Tells one connection's sidecar which wallet the owner selected, or, with a null [binding],
     * that none is (docs/protocol.md#the-wallet-binding). The sidecar cancels the PENDING requests
     * the new binding no longer fits, and those come off the inbox here. Returns false if the
     * sidecar couldn't be told; publishing again later is safe, since an unchanged binding changes
     * nothing.
     */
    suspend fun publishWallet(id: String, binding: WalletBinding?): Boolean {
        val connection = find(id) ?: return false
        if (!connection.usable) return false
        val credential = withContext(io) { vault.get(id) }
        if (credential == null) {
            forgetCredential(id)
            return false
        }
        return try {
            val cancelled = gateway.publishWallet(connection.serverUrl, credential, id, binding)
            if (cancelled.isNotEmpty()) dropPending(id, cancelled.toSet())
            true
        } catch (e: GatewayException) {
            if (e.kind == GatewayException.Kind.Unauthenticated) markRevoked(id)
            false
        }
    }

    // Takes requests the sidecar cancelled off the connection's pending list, and off its count.
    private suspend fun dropPending(id: String, requestIds: Set<String>) = locked {
        val remaining =
            _inbox.value.pending[id]?.filterNot { it.ref.requestId in requestIds } ?: return@locked
        _inbox.update { it.copy(pending = it.pending + (id to remaining)) }
        store.get(id)?.let { connection ->
            val check = connection.lastCheck
            if (check?.outcome == CheckOutcome.Ok) {
                store.put(connection.copy(lastCheck = check.copy(pending = remaining.size)))
            }
        }
        publish()
    }

    /**
     * The owner's answer to a pending acknowledgement: stored first, then sent. A request gets one
     * answer, and asking again returns the stored one. If sending fails, the answer waits, and each
     * refresh sends it again until the sidecar settles it.
     */
    suspend fun answer(key: RequestKey, answer: Answer): LocalResult {
        val stored = locked {
            results.get(key.connectionId, key.requestId)
                ?: run {
                    val request =
                        checkNotNull(_inbox.value.pendingRequest(key)) { "not a pending request" }
                    require(answer.applies(request)) { "this answer doesn't apply to this request" }
                    LocalResult(key.connectionId, key.requestId, answer, now(), request).also {
                        results.put(it)
                        publish()
                    }
                }
        }
        if (stored.delivery != Delivery.Waiting) return stored
        return deliver(key) ?: stored
    }

    /**
     * The owner's approval of a transfer, which is the commit point before the wallet is opened
     * (docs/architecture.md#approval-binding). [approved] is the exact transaction they reviewed,
     * and it is written to this phone before anything is sent, so the bytes the wallet is handed
     * are the bytes that were approved.
     *
     * It returns the stored answer only once the sidecar has accepted the approval, which is when
     * the request is PROCESSING and the wallet may be asked. Anything else removes the approval
     * again: nothing was approved anywhere, the request stays the sidecar's, and the owner reviews
     * a fresh preparation rather than carrying this decision over to another transaction. It
     * reaches no wallet itself.
     */
    suspend fun approveTransfer(key: RequestKey, approved: ApprovedTransaction): ApprovalOutcome {
        val mutex = sending.computeIfAbsent(key) { Mutex() }
        return try {
            mutex.withLock { commit(key, approved) }
        } finally {
            sending.computeIfPresent(key) { _, running -> running.takeIf { it.isLocked } }
        }
    }

    private suspend fun commit(key: RequestKey, approved: ApprovedTransaction): ApprovalOutcome {
        val existing = locked { results.get(key.connectionId, key.requestId) }
        if (existing != null) {
            // One answer per request: an approval already stored is the one that counts.
            return if (existing.approved) ApprovalOutcome.Accepted(existing)
            else ApprovalOutcome.Refused(CheckOutcome.Failed)
        }
        val connection =
            find(key.connectionId)?.takeIf { it.usable }
                ?: return ApprovalOutcome.Refused(CheckOutcome.Failed)
        val credential = withContext(io) { vault.get(key.connectionId) }
        if (credential == null) {
            forgetCredential(key.connectionId)
            return ApprovalOutcome.Refused(CheckOutcome.Failed)
        }
        val stored =
            locked {
                val request = _inbox.value.pendingRequest(key) ?: return@locked null
                if (request.transfer() == null) return@locked null
                LocalResult(
                        key.connectionId,
                        key.requestId,
                        Answer.Approve,
                        now(),
                        request,
                        approvedTransaction = approved,
                    )
                    .also {
                        results.put(it)
                        publish()
                    }
            } ?: return ApprovalOutcome.Refused(CheckOutcome.Failed)
        return try {
            gateway.submitResult(
                connection.serverUrl,
                credential,
                submitResultRequest {
                    ref = stored.request.ref
                    approval = approved.toApproval()
                },
            )
            markApproved(stored)?.let { ApprovalOutcome.Accepted(it) }
                ?: ApprovalOutcome.Refused(CheckOutcome.Failed)
        } catch (e: GatewayException) {
            // Nothing was approved, so nothing is kept. The wallet was never opened.
            locked {
                results.delete(key.connectionId, key.requestId)
                publish()
            }
            if (e.kind == GatewayException.Kind.Unauthenticated) markRevoked(key.connectionId)
            when (e.kind) {
                GatewayException.Kind.StalePreparation -> ApprovalOutcome.Stale
                GatewayException.Kind.InvalidState ->
                    ApprovalOutcome.Superseded(e.request ?: stored.request)
                else -> ApprovalOutcome.Refused(e.kind.toOutcome(), e.message)
            }
        }
    }

    /**
     * Records what the wallet did with an approved message, and sends it. The first outcome stored
     * stands: a later one changes nothing, so a signature can't be replaced by anything else.
     */
    suspend fun recordSigning(key: RequestKey, outcome: SigningOutcome): LocalResult? {
        val stored =
            locked {
                val current = results.get(key.connectionId, key.requestId) ?: return@locked null
                if (current.answer != Answer.Approve || current.signing != null)
                    return@locked current
                current.copy(signing = outcome).also {
                    results.put(it)
                    publish()
                }
            } ?: return null
        if (stored.delivery != Delivery.Waiting) return stored
        return deliver(key) ?: stored
    }

    /**
     * Sends a waiting answer now, and returns it as it stands afterwards. It returns null if
     * there's no answer, or if its connection was removed while it was sent. A send that finds
     * another send of the same answer running waits for it and then returns what that one settled,
     * so an answer stored while a send is in the air still reaches the sidecar and nothing is sent
     * twice at once (SAW-017). The sidecar recognizes a repeat, so sending again after a lost
     * response is safe. A reply is written only if the connection and the answer are both still
     * there, checked under the lock that removal holds, so a late reply can't undo a removal.
     *
     * Delivery never reaches the wallet: the wallet is asked once, between the approval and the
     * signing outcome, and only by the caller (SAW-016).
     */
    suspend fun deliver(key: RequestKey): LocalResult? = deliver(key, waitForTheOneInFlight = true)

    // Sends the answer, or, with [waitForTheOneInFlight] false, leaves it to the send already
    // running and returns null. A refresh takes that route: it has other answers and pages to get
    // through, and waiting for a send that is already on its way would gain it nothing.
    private suspend fun deliver(key: RequestKey, waitForTheOneInFlight: Boolean): LocalResult? {
        val mutex = sending.computeIfAbsent(key) { Mutex() }
        if (!waitForTheOneInFlight && mutex.isLocked) return null
        return try {
            mutex.withLock { send(key) }
        } finally {
            // Keep the map to the sends still running.
            sending.computeIfPresent(key) { _, running -> running.takeIf { it.isLocked } }
        }
    }

    /**
     * Asks the connection's sidecar to build a fresh transaction for one of its PENDING transfers.
     * It only fetches: the bytes are checked, and the owner decides, elsewhere. Nothing is stored,
     * because a preparation is only good while its blockhash is, and a stale one must never be read
     * back from disk and shown as current.
     */
    suspend fun prepare(key: RequestKey): PreparedTransaction {
        val connection =
            find(key.connectionId)?.takeIf { it.usable }
                ?: throw GatewayException(
                    GatewayException.Kind.NotFound,
                    "this connection can't be used",
                )
        val credential =
            withContext(io) { vault.get(key.connectionId) }
                ?: run {
                    forgetCredential(key.connectionId)
                    throw GatewayException(
                        GatewayException.Kind.Unauthenticated,
                        "this phone has no credential for the connection any more",
                    )
                }
        return try {
            gateway.prepareRequest(connection.serverUrl, credential, key)
        } catch (e: GatewayException) {
            if (e.kind == GatewayException.Kind.Unauthenticated) markRevoked(key.connectionId)
            throw e
        }
    }

    private suspend fun send(key: RequestKey): LocalResult? {
        val result = locked { results.get(key.connectionId, key.requestId) } ?: return null
        if (result.delivery != Delivery.Waiting) return result
        val connection = find(key.connectionId)
        if (connection == null || !connection.usable) {
            return settle(result, Delivery.Undeliverable, result.request)
        }
        val credential = withContext(io) { vault.get(key.connectionId) }
        if (credential == null) {
            forgetCredential(key.connectionId)
            return settle(result, Delivery.Undeliverable, result.request)
        }
        var current = result
        return try {
            // The owner's approval goes first: the sidecar takes the wallet's result only for a
            // request the approval has already moved to PROCESSING.
            if (current.answer == Answer.Approve && !current.approved) {
                // An approved transfer that reaches here was never committed, so there is nothing
                // to send: approveTransfer removes such an approval, and load() cleans up one a
                // crash left behind. Sending it now would approve a transaction nobody reviewed
                // afresh.
                if (current.uncommittedTransfer) {
                    return settle(current, Delivery.Undeliverable, current.request)
                }
                val approval =
                    current.request.messageApproval()
                        ?: return settle(current, Delivery.Undeliverable, current.request)
                gateway.submitResult(
                    connection.serverUrl,
                    credential,
                    submitResultRequest {
                        ref = current.request.ref
                        this.approval = approval
                    },
                )
                current = markApproved(current) ?: return null
            }
            // Nothing more to send until the wallet has answered.
            val submission = submissionFor(current) ?: return current
            val after = gateway.submitResult(connection.serverUrl, credential, submission)
            settle(current, Delivery.Accepted, after)
        } catch (e: GatewayException) {
            when (e.kind) {
                // The agent cancelled it, or it expired, before the answer arrived.
                GatewayException.Kind.InvalidState ->
                    settle(current, Delivery.Superseded, e.request ?: current.request)
                GatewayException.Kind.NotFound ->
                    settle(current, Delivery.Undeliverable, current.request)
                GatewayException.Kind.Unauthenticated -> {
                    markRevoked(key.connectionId)
                    locked { results.get(key.connectionId, key.requestId) }
                }
                // Unreachable, or another failure: keep it, and send it again on refresh. If a
                // revocation settled the answer meanwhile, it stays settled.
                else ->
                    locked<LocalResult?> {
                        val stored = stillStored(current) ?: return@locked null
                        if (stored.delivery != Delivery.Waiting) return@locked stored
                        stored.copy(lastFailure = e.kind.toOutcome()).also {
                            results.put(it)
                            publish()
                        }
                    }
            }
        }
    }

    suspend fun rename(id: String, label: String) {
        require(labelProblem(label) == null) { "invalid label" }
        update(id) { it.copy(label = label.trim()) }
    }

    /**
     * Revokes the connection at its sidecar, then removes it from this phone. Throws
     * [GatewayException] if the sidecar couldn't be told; the connection then stays, and the owner
     * can still [remove] it.
     */
    suspend fun disconnect(id: String) {
        val connection = find(id) ?: return
        val credential = if (connection.usable) withContext(io) { vault.get(id) } else null
        if (credential != null) {
            try {
                gateway.revoke(connection.serverUrl, credential, id)
            } catch (e: GatewayException) {
                // Already revoked, or unknown to the sidecar: nothing is left to end there.
                if (
                    e.kind != GatewayException.Kind.Unauthenticated &&
                        e.kind != GatewayException.Kind.NotFound
                ) {
                    throw e
                }
            }
        }
        remove(id)
    }

    /**
     * Removes the connection from this phone only: its credential first, then its answers and its
     * metadata.
     */
    suspend fun remove(id: String) = locked {
        vault.delete(id)
        results.deleteConnection(id)
        store.delete(id)
        _inbox.update { it.copy(pending = it.pending - id) }
        publish()
    }

    // The sidecar no longer accepts the credential: nothing waiting for it can be sent any more.
    private suspend fun markRevoked(id: String) = locked {
        vault.delete(id)
        store.get(id)?.let { store.put(it.copy(revokedAt = now(), lastCheck = null)) }
        results
            .listFor(id)
            .filter { it.delivery == Delivery.Waiting }
            .forEach { results.put(it.copy(delivery = Delivery.Undeliverable, settledAt = now())) }
        _inbox.update { it.copy(pending = it.pending - id) }
        publish()
    }

    // A credential that can't be decrypted is useless; drop it so the screen says to pair again.
    private suspend fun forgetCredential(id: String) = locked {
        vault.delete(id)
        publish()
    }

    // Records how the sidecar settled an answer, and takes its request off the pending list. If the
    // connection or the answer was removed meanwhile, it writes nothing and returns null.
    private suspend fun settle(
        result: LocalResult,
        delivery: Delivery,
        request: ActionRequest,
    ): LocalResult? = locked {
        val current = stillStored(result) ?: return@locked null
        val settled =
            current.copy(
                delivery = delivery,
                request = request,
                lastFailure = null,
                settledAt = now(),
            )
        results.put(settled)
        val remaining =
            _inbox.value.pending[result.connectionId]?.filterNot {
                it.ref.requestId == result.requestId
            }
        if (remaining != null) {
            _inbox.update { it.copy(pending = it.pending + (result.connectionId to remaining)) }
            store.get(result.connectionId)?.let { connection ->
                val check = connection.lastCheck
                if (check?.outcome == CheckOutcome.Ok) {
                    store.put(connection.copy(lastCheck = check.copy(pending = remaining.size)))
                }
            }
        }
        publish()
        settled
    }

    // Records that the sidecar accepted the approval, so a retry doesn't send it again.
    private suspend fun markApproved(result: LocalResult): LocalResult? = locked {
        val current = stillStored(result) ?: return@locked null
        if (current.approved) return@locked current
        current.copy(approved = true).also {
            results.put(it)
            publish()
        }
    }

    // The answer as it's stored now, or null if it or its connection was removed while it was on
    // its way. Call it under the lock, just before writing a delivery outcome. Removal holds the
    // same lock, so a late reply can't recreate what removal deleted.
    private fun stillStored(result: LocalResult): LocalResult? =
        store.get(result.connectionId)?.let { results.get(result.connectionId, result.requestId) }

    private suspend fun update(id: String, change: (Connection) -> Connection) = locked {
        store.get(id)?.let { store.put(change(it)) }
        publish()
    }

    private fun find(id: String): Connection? = _connections.value.firstOrNull { it.id == id }

    // Runs [block] under the lock on the I/O dispatcher.
    private suspend fun <T> locked(block: () -> T): T = lock.withLock {
        withContext(io) { block() }
    }

    private fun publish() {
        _connections.value =
            store.list().map {
                it.copy(hasCredential = it.revokedAt == null && vault.contains(it.id))
            }
        _inbox.update { it.copy(results = results.list()) }
    }

    private companion object {
        /** At most this many pages of 100 PENDING requests are read per connection. */
        const val MAX_PAGES = 10
        val SETTLED_RETENTION: Duration = Duration.ofDays(7)
        const val APP_CLOSED = "The app closed before the wallet answered, so nothing was signed."
        const val WALLET_LOST =
            "The wallet's answer never reached this phone, so nothing was signed."
        // A transfer can't say that: the wallet may have sent the transaction before it went.
        const val APP_CLOSED_SENDING =
            "The app closed while the transaction was with the wallet, so this phone never " +
                "learned whether it was sent."
        const val WALLET_LOST_SENDING =
            "The wallet's answer never reached this phone, so it never learned whether the " +
                "transaction was sent."

        /**
         * What an outcome this phone never learned means, which depends on what was with the
         * wallet.
         */
        fun LocalResult.lostDetail(appClosed: Boolean): String =
            when {
                request.transfer() != null ->
                    if (appClosed) APP_CLOSED_SENDING else WALLET_LOST_SENDING
                appClosed -> APP_CLOSED
                else -> WALLET_LOST
            }

        /** Which answers a request can be given: the owner can always refuse. */
        fun Answer.applies(request: ActionRequest) =
            when (this) {
                Answer.Acknowledge -> request.action.hasAck()
                Answer.Reject ->
                    request.action.hasAck() ||
                        request.signMessage() != null ||
                        request.transfer() != null
                // A transfer is approved through approveTransfer, which binds the approval to the
                // preparation the owner reviewed; there is nothing to approve without one.
                Answer.Approve -> request.signMessage() != null
            }

        /**
         * The result to send for an answer, or null when there is nothing to send yet: an approved
         * message waiting for the wallet. A wallet that declined is the owner declining, which is a
         * rejection; a wallet that couldn't sign is an execution failure, and neither signed
         * anything.
         */
        fun submissionFor(result: LocalResult): SubmitResultRequest? {
            val requestRef = result.request.ref
            val transfer = result.request.transfer() != null
            return when (result.answer) {
                Answer.Acknowledge ->
                    submitResultRequest {
                        ref = requestRef
                        acknowledgement = Acknowledgement.getDefaultInstance()
                    }
                Answer.Reject ->
                    submitResultRequest {
                        ref = requestRef
                        rejection = Rejection.getDefaultInstance()
                    }
                Answer.Approve ->
                    when (val outcome = result.signing) {
                        null -> null
                        is SigningOutcome.Signed ->
                            submitResultRequest {
                                ref = requestRef
                                messageSignature = messageSignature {
                                    signature = outcome.signature
                                }
                            }
                        is SigningOutcome.Sent ->
                            submitResultRequest {
                                ref = requestRef
                                transactionSubmission = transactionSubmission {
                                    signature = outcome.signature
                                }
                            }
                        SigningOutcome.Declined ->
                            submitResultRequest {
                                ref = requestRef
                                rejection = Rejection.getDefaultInstance()
                            }
                        is SigningOutcome.Failed ->
                            submitResultRequest {
                                ref = requestRef
                                executionFailure = executionFailure { detail = outcome.detail }
                            }
                        // For a message, nothing reached this phone, so nothing was signed and
                        // nothing is in doubt: the agent is told the request failed. For a
                        // transfer, the wallet may have sent the transaction, and an outcome
                        // nobody knows is reported as unknown rather than guessed at.
                        is SigningOutcome.Unresolved ->
                            if (transfer)
                                submitResultRequest {
                                    ref = requestRef
                                    unknownOutcome = unknownOutcome { detail = outcome.detail }
                                }
                            else
                                submitResultRequest {
                                    ref = requestRef
                                    executionFailure = executionFailure { detail = outcome.detail }
                                }
                    }
            }
        }
    }
}
