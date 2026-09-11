package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import java.io.IOException
import java.security.GeneralSecurityException
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Storing a new connection failed on this phone; nothing was saved. */
class StorageException(cause: Throwable) : Exception(cause.message, cause)

/**
 * The phone's connections (docs/security.md#local-storage-and-recovery): pairing, refreshing,
 * renaming, and disconnecting, over [store] (metadata), [vault] (credentials), and [gateway] (the
 * sidecars).
 * - A connection's credential goes only to that connection's URL, which never changes.
 * - A pairing code always makes a new connection; it never changes an existing one.
 * - A credential the sidecar stops accepting is deleted, and the connection must be paired again.
 *
 * Network calls run outside the lock; each storage change runs under it, and only if its connection
 * still exists.
 */
class ConnectionRepository(
    private val store: ConnectionStore,
    private val vault: CredentialVault,
    private val gateway: ConnectionGateway,
    private val deviceName: String,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private val _connections = MutableStateFlow<List<Connection>>(emptyList())
    val connections: StateFlow<List<Connection>> = _connections.asStateFlow()

    /** Reads the stored connections, and deletes any credential that no connection owns. */
    suspend fun load() = locked {
        val ids = store.list().map { it.id }.toSet()
        vault.ids().filter { it !in ids }.forEach(vault::delete)
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
     * Asks the connection's sidecar for its pending requests. If the sidecar no longer accepts the
     * credential, the connection is marked revoked and the credential deleted.
     */
    suspend fun refresh(id: String) {
        val connection = find(id) ?: return
        if (!connection.usable) return
        val credential = withContext(io) { vault.get(id) }
        if (credential == null) return forgetCredential(id)
        val check =
            try {
                val pending = gateway.listPending(connection.serverUrl, credential, id)
                // Only this connection's requests count, whatever the sidecar sent.
                val own = pending.requests.filter { it.ref.connectionId == id }
                Connection.Check(now(), CheckOutcome.Ok, own.size, pending.more)
            } catch (e: GatewayException) {
                if (e.kind == GatewayException.Kind.Unauthenticated) return markRevoked(id)
                Connection.Check(now(), e.kind.toOutcome())
            }
        update(id) { it.copy(lastCheck = check) }
    }

    fun connection(id: String): Connection? = find(id)

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

    /** Removes the connection from this phone only: its credential first, then its metadata. */
    suspend fun remove(id: String) = locked {
        vault.delete(id)
        store.delete(id)
        publish()
    }

    private suspend fun markRevoked(id: String) = locked {
        vault.delete(id)
        store.get(id)?.let { store.put(it.copy(revokedAt = now(), lastCheck = null)) }
        publish()
    }

    // A credential that can't be decrypted is useless; drop it so the screen says to pair again.
    private suspend fun forgetCredential(id: String) = locked {
        vault.delete(id)
        publish()
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
    }
}
