package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.request.v1.Acknowledgement
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Rejection
import io.github.brrenat.seekervault.request.v1.submitResultRequest
import java.io.IOException
import java.security.GeneralSecurityException
import java.time.Duration
import java.time.Instant
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

    // The answers being sent right now: an answer is never sent twice at once.
    private val sending = mutableSetOf<RequestKey>()

    /**
     * Reads the stored connections and answers. It deletes credentials and answers that no
     * connection owns, and settled answers older than a week.
     */
    suspend fun load() = locked {
        val ids = store.list().map { it.id }.toSet()
        vault.ids().filter { it !in ids }.forEach(vault::delete)
        results.connectionIds().filter { it !in ids }.forEach(results::deleteConnection)
        val cutoff = now().minus(SETTLED_RETENTION)
        results
            .list()
            .filter { it.delivery != Delivery.Waiting && it.answeredAt < cutoff }
            .forEach { results.delete(it.connectionId, it.requestId) }
        publish()
    }

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
     * accepts the credential, the connection is marked revoked and the credential deleted.
     */
    suspend fun refresh(id: String) {
        val connection = find(id) ?: return
        if (!connection.usable) return
        val credential = withContext(io) { vault.get(id) }
        if (credential == null) return forgetCredential(id)
        val waiting =
            _inbox.value.results.filter { it.connectionId == id && it.delivery == Delivery.Waiting }
        for (result in waiting) {
            // Stop at the first the sidecar didn't take; the fetch below records why.
            if (deliver(result.key)?.delivery == Delivery.Waiting) break
        }
        if (find(id)?.usable != true) return // revoked while the answers were sent
        val check =
            try {
                val own = mutableListOf<ActionRequest>()
                var token = ""
                var pages = 0
                do {
                    val page = gateway.listPending(connection.serverUrl, credential, id, token)
                    // Only this connection's requests count, whatever the sidecar sent.
                    own += page.requests.filter { it.ref.connectionId == id }
                    token = page.nextPageToken
                    pages++
                } while (token.isNotEmpty() && pages < MAX_PAGES)
                _inbox.update { it.copy(pending = it.pending + (id to own.toList())) }
                Connection.Check(now(), CheckOutcome.Ok, own.size, morePending = token.isNotEmpty())
            } catch (e: GatewayException) {
                if (e.kind == GatewayException.Kind.Unauthenticated) return markRevoked(id)
                Connection.Check(now(), e.kind.toOutcome())
            }
        update(id) { it.copy(lastCheck = check) }
    }

    fun connection(id: String): Connection? = find(id)

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
                    require(request.action.hasAck()) { "only an acknowledgement can be answered" }
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
     * Sends a waiting answer now, and returns it as it stands afterwards, or null if there's none
     * or it's being sent already. The sidecar recognizes a repeat, so sending again after a lost
     * response is safe.
     */
    suspend fun deliver(key: RequestKey): LocalResult? {
        if (!synchronized(sending) { sending.add(key) }) return null
        try {
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
            val submission = submitResultRequest {
                ref = result.request.ref
                when (result.answer) {
                    Answer.Acknowledge -> acknowledgement = Acknowledgement.getDefaultInstance()
                    Answer.Reject -> rejection = Rejection.getDefaultInstance()
                }
            }
            return try {
                val after = gateway.submitResult(connection.serverUrl, credential, submission)
                settle(result, Delivery.Accepted, after)
            } catch (e: GatewayException) {
                when (e.kind) {
                    // The agent cancelled it, or it expired, before the answer arrived.
                    GatewayException.Kind.InvalidState ->
                        settle(result, Delivery.Superseded, e.request ?: result.request)
                    GatewayException.Kind.NotFound ->
                        settle(result, Delivery.Undeliverable, result.request)
                    GatewayException.Kind.Unauthenticated -> {
                        markRevoked(key.connectionId)
                        locked { results.get(key.connectionId, key.requestId) } ?: result
                    }
                    // Unreachable, or another failure: keep it, and send it again on refresh.
                    else ->
                        locked {
                            result.copy(lastFailure = e.kind.toOutcome()).also {
                                results.put(it)
                                publish()
                            }
                        }
                }
            }
        } finally {
            synchronized(sending) { sending.remove(key) }
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
            .forEach { results.put(it.copy(delivery = Delivery.Undeliverable)) }
        _inbox.update { it.copy(pending = it.pending - id) }
        publish()
    }

    // A credential that can't be decrypted is useless; drop it so the screen says to pair again.
    private suspend fun forgetCredential(id: String) = locked {
        vault.delete(id)
        publish()
    }

    // Records how the sidecar settled an answer, and takes its request off the pending list.
    private suspend fun settle(
        result: LocalResult,
        delivery: Delivery,
        request: ActionRequest,
    ): LocalResult = locked {
        val settled = result.copy(delivery = delivery, request = request, lastFailure = null)
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
    }
}
