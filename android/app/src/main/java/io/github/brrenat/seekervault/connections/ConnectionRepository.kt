package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.activity.ActivityLog
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ProposalStore
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.policy.storage.PolicyStore
import io.github.brrenat.seekervault.proposals.settled
import io.github.brrenat.seekervault.push.RelayCoordinates
import io.github.brrenat.seekervault.push.RelayHandleUpdate
import io.github.brrenat.seekervault.request.v1.Acknowledgement
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.PreparedTransaction
import io.github.brrenat.seekervault.request.v1.Rejection
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.SubmitResultRequest
import io.github.brrenat.seekervault.request.v1.WalletBinding
import io.github.brrenat.seekervault.request.v1.executionFailure
import io.github.brrenat.seekervault.request.v1.messageSignature
import io.github.brrenat.seekervault.request.v1.submitResultRequest
import io.github.brrenat.seekervault.request.v1.transactionSubmission
import io.github.brrenat.seekervault.request.v1.unknownOutcome
import io.github.brrenat.seekervault.server.v1.ServerManifest as WireManifest
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.FeedReference
import io.github.brrenat.seekervault.servers.ManifestExpectation
import io.github.brrenat.seekervault.servers.ManifestProblem
import io.github.brrenat.seekervault.servers.ManifestResult
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.manifest
import io.github.brrenat.seekervault.servers.manifestFrom
import io.github.brrenat.seekervault.sync.ConnectionSyncState
import io.github.brrenat.seekervault.sync.LocalRequestState
import io.github.brrenat.seekervault.sync.SyncConnection
import io.github.brrenat.seekervault.sync.SynchronizationHost
import io.github.brrenat.seekervault.sync.SynchronizationRepository
import io.github.brrenat.seekervault.sync.SynchronizeOutcome
import io.github.brrenat.seekervault.sync.UpdateTransport
import io.github.brrenat.seekervault.sync.storage.SyncStore
import io.github.brrenat.seekervault.transactions.transfer
import java.io.IOException
import java.security.GeneralSecurityException
import java.time.Duration
import java.time.Instant
import java.util.UUID
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

/** What adding a publisher's feed came to (SEE-88). */
sealed interface FeedOutcome {
    data class Added(val connection: Connection) : FeedOutcome

    /** The phone already reads this publisher's feed; a reference scanned twice adds nothing. */
    data class Already(val connection: Connection) : FeedOutcome

    /** The gateway answered with a manifest this phone refused, and which rule it broke. */
    data class Refused(val problem: ManifestProblem) : FeedOutcome

    /** The gateway couldn't be reached, or answered with something unusable. */
    data class Failed(val outcome: CheckOutcome) : FeedOutcome

    /**
     * This build has no gateway to resolve a feed through. It is said plainly because the
     * alternative is worse: a feed that looks added and reads nothing (SEE-90 supplies the
     * gateway).
     */
    data object NoGateway : FeedOutcome
}

/**
 * What came of moving a feed between the environments its server serves (SEE-97).
 *
 * Each answer is a separate fact because each is a different thing to tell the owner, and none of
 * them is a partial success: either the connection now keeps the other promise, or it keeps the one
 * it had.
 */
enum class EnvironmentOutcome {
    /**
     * The connection now keeps the other promise, and nothing prepared under the old one stands.
     */
    Changed,
    /** It already kept that one. Nothing was written and nothing was invalidated. */
    Unchanged,
    /** Its server does not serve that environment, so no connection to it could keep it. */
    NotServed,
    /**
     * It is a direct connection, which is always production: an agent waiting for a signature
     * cannot be handed a simulation.
     */
    NotAFeed,
    /** There is no such connection on this phone any more. */
    Gone,
}

/** What a publisher's settings came to, from a read or from the stream (SEE-91). */
sealed interface FeedSettings {
    /** The settings moved forward, and the connection now holds them. */
    data class Stored(val manifest: ServerManifest) : FeedSettings

    /** What arrived is what is already held. */
    data object Unchanged : FeedSettings

    /**
     * A manifest that broke a rule, and nothing was written.
     *
     * A feed keeps the settings it validated until a manifest passes every rule at a higher
     * revision, which is why a contradiction is reported rather than stored: the phone has no way
     * to tell which version the publisher meant, and the one it already checked is the one its
     * proposals were checked against.
     */
    data class Refused(val problem: ManifestProblem) : FeedSettings

    /** The gateway couldn't be reached, or answered with something unusable. */
    data class Failed(val outcome: CheckOutcome) : FeedSettings

    /** This build has no gateway to read settings through. */
    data object NoGateway : FeedSettings

    /** No such connection, or one that is not a feed. */
    data object NotAFeed : FeedSettings
}

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
    /**
     * The owner's own history, which every answer is written to as well (SAW-023). It is optional
     * because it is not part of answering: a phone without one still answers, sends, and settles.
     */
    private val history: ActivityLog? = null,
    /**
     * The owner's overrides for each connection (SAW-027, SAW-043). Optional for the same reason
     * [history] is: a phone with none still pairs, answers, and removes. Nothing here reads a
     * policy — it only makes sure a removed connection's overrides go with it, without touching
     * global rules.
     */
    private val rules: PolicyStore? = null,
    /**
     * A feed's proposals (SEE-89). Optional for the same reason [rules] is, and used for one thing
     * only: a removed connection's proposals go with it, in the same place its answers and its
     * rules do, so nothing of a feed the owner removed is left on the disk. Nothing here reads a
     * proposal — [ProposalRepository] owns them.
     */
    private val proposals: ProposalStore? = null,
    /**
     * How a publisher's feed is resolved, when this build has a gateway to resolve it through
     * (SEE-88). It is optional because the gateway is SEE-90: without one, [addFeed] says so and
     * adds nothing. Nothing else here uses it, and a direct connection never touches it.
     */
    private val feeds: FeedGateway? = null,
    private val deviceName: String,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    syncStore: SyncStore? = null,
    updateTransport: UpdateTransport? = null,
    private val onConnectionUnavailable: (String) -> Unit = {},
) : SynchronizationHost {
    private val lock = Mutex()
    private val loading = Mutex()
    private val _loaded = MutableStateFlow(false)
    /** True only after connection metadata and credentials have been reconciled from disk. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
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
     * Publishes one private FCM target update to exactly [id]'s URL with exactly [id]'s encrypted
     * credential. Push registration is best-effort: an unavailable or older sidecar changes none of
     * the Stage 5.2 update paths. A rejected credential follows the existing revocation path.
     */
    suspend fun setFcmToken(id: String, update: FcmTokenUpdate): Boolean {
        val connection = find(id)?.takeIf { it.usable } ?: return false
        val credential =
            withContext(io) { vault.get(id) }
                ?: run {
                    forgetCredential(id)
                    return false
                }
        return try {
            gateway.setFcmToken(connection.serverUrl, credential, id, update)
            true
        } catch (e: GatewayException) {
            if (e.kind == GatewayException.Kind.Unauthenticated) markRevoked(id)
            false
        }
    }

    /**
     * Where this connection's own server asks for its wake-ups to come from (SEE-144), or null when
     * it sends its own push, none, or does not know the call.
     *
     * It is read over the authenticated direct connection, which is what makes the identity in it
     * the identity of the server this phone actually paired with.
     */
    suspend fun relayCoordinates(id: String): RelayCoordinates? {
        val connection = find(id)?.takeIf { it.usable } ?: return null
        val credential =
            withContext(io) { vault.get(id) }
                ?: run {
                    forgetCredential(id)
                    return null
                }
        return try {
            gateway.relay(connection.serverUrl, credential, id)
        } catch (e: GatewayException) {
            if (e.kind == GatewayException.Kind.Unauthenticated) markRevoked(id)
            null
        }
    }

    /**
     * Hands this connection's server the handle the gateway issued for it, or compare-clears one. A
     * separate call from [setFcmToken] because the two values are not interchangeable.
     */
    suspend fun setRelayHandle(id: String, update: RelayHandleUpdate): Boolean {
        val connection = find(id)?.takeIf { it.usable } ?: return false
        val credential =
            withContext(io) { vault.get(id) }
                ?: run {
                    forgetCredential(id)
                    return false
                }
        return try {
            gateway.setRelayHandle(connection.serverUrl, credential, id, update)
            true
        } catch (e: GatewayException) {
            if (e.kind == GatewayException.Kind.Unauthenticated) markRevoked(id)
            false
        }
    }

    /**
     * The single application-scoped synchronization component. Tests and legacy-only callers may
     * omit it; the production application always supplies both dependencies.
     */
    val synchronization: SynchronizationRepository? =
        if (syncStore != null && updateTransport != null) {
            SynchronizationRepository(syncStore, updateTransport, this, now, io)
        } else {
            require(syncStore == null && updateTransport == null) {
                "sync storage and transport must be supplied together"
            }
            null
        }

    /**
     * Reads the stored connections and answers. It deletes credentials and answers that no
     * connection owns, and answers that settled more than a week ago.
     */
    suspend fun load() = loading.withLock {
        if (_loaded.value) return@withLock
        val retired = locked {
            val retiredConnections = store.migrateRetired()
            retiredConnections.forEach { connection ->
                vault.delete(connection.id)
                results
                    .listFor(connection.id)
                    .filter { it.delivery == Delivery.Waiting }
                    .forEach {
                        results.put(it.copy(delivery = Delivery.Undeliverable, settledAt = now()))
                    }
                proposals
                    ?.listFor(connection.id)
                    ?.filter {
                        it.dismissed == null && it.execution?.outcome?.settled != true
                    }
                    ?.forEach { proposals.delete(connection.id, it.key.proposalId) }
                _inbox.update { it.copy(pending = it.pending - connection.id) }
            }
            val ids = store.list().map { it.id }.toSet()
            vault.ids().filter { it !in ids }.forEach(vault::delete)
            results.connectionIds().filter { it !in ids }.forEach(results::deleteConnection)
            val cutoff = now().minus(SETTLED_RETENTION)
            results
                .list()
                .filter {
                    it.delivery != Delivery.Waiting && (it.settledAt ?: it.answeredAt) < cutoff
                }
                .forEach { results.delete(it.connectionId, it.requestId) }
            assignMissingColours()
            publish()
            // The app closed while an action was with the wallet: whatever the wallet did, this
            // phone never learned it, so the approval is settled as unresolved rather than left
            // open. An approved transfer the sidecar never accepted is a different thing: the
            // wallet is opened only after it does, so that one was never asked anything, and it is
            // dropped instead.
            uncommittedApprovals().forEach { results.delete(it.connectionId, it.requestId) }
            abandonedSignings(emptySet()).forEach {
                save(it.copy(signing = SigningOutcome.Unresolved(it.lostDetail(appClosed = true))))
            }
            publish()
            retiredConnections.map { it.id }
        }
        retired.forEach {
            synchronization?.remove(it)
            onConnectionUnavailable(it)
        }
        synchronization?.load()
        _loaded.value = true
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
                .onEach(::save)
                .also { if (it.isNotEmpty()) publish() }
        }
        abandoned.forEach { deliver(it.key) }
        // And an approval whose answer was lost is asked about, rather than left in the dark: the
        // sidecar may be holding a PROCESSING request only this phone can settle.
        locked { unreconciledApprovals(except) }.forEach { deliver(it) }
        return abandoned.map { it.key }
    }

    // Approvals with no wallet answer and nothing settled yet. Call it under the lock. An approval
    // whose own fate is unknown isn't one: no wallet was opened for it, so there is nothing to
    // report until the sidecar has said whether it took the approval at all.
    private fun abandonedSignings(except: Set<RequestKey>): List<LocalResult> =
        results.list().filter {
            it.answer == Answer.Approve &&
                it.signing == null &&
                it.delivery == Delivery.Waiting &&
                it.key !in except &&
                !it.uncommittedTransfer &&
                !it.approvalUncertain
        }

    // Approvals this phone sent and never got an answer to. Call it under the lock.
    private fun unreconciledApprovals(except: Set<RequestKey>): List<RequestKey> =
        results
            .list()
            .filter {
                it.approvalUncertain && it.delivery == Delivery.Waiting && it.key !in except
            }
            .map { it.key }

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
        val drafted =
            Connection(
                id = paired.connectionId,
                label = PairingCodes.hostOf(code.serverUrl),
                serverUrl = code.serverUrl,
                serverId = code.serverId,
                deviceName = deviceName,
                pairedAt = now(),
            )
        val connection = locked {
            if (store.get(drafted.id) != null) {
                throw GatewayException(GatewayException.Kind.BadResponse, "a known connection ID")
            }
            val coloured = drafted.copy(colour = nextServerColour(store.list()))
            try {
                vault.put(coloured.id, paired.credential)
                store.put(coloured)
            } catch (e: GeneralSecurityException) {
                vault.delete(coloured.id)
                throw StorageException(e)
            } catch (e: IOException) {
                vault.delete(coloured.id)
                throw StorageException(e)
            }
            publish()
            coloured
        }
        // What the server says about itself, asked with the credential it just issued. A server
        // that answers nothing is the legacy path and the pairing stands either way: the manifest
        // is what the phone knows about the server, never what makes the connection valid.
        resolveManifest(connection.id)
        return find(connection.id) ?: connection
    }

    /**
     * Adds a publisher's feed from [reference], by resolving its manifest through the shared
     * gateway (SEE-88).
     *
     * The publisher's own server is never contacted — there is nothing in a reference to contact —
     * and no credential is created or stored, because a feed is a broadcast the phone subscribes to
     * rather than a server it calls. Everything the gateway hands back is checked against the
     * reference before anything is written: the identity, the gateway origin, and the channel,
     * which a publisher may only own.
     */
    suspend fun addFeed(reference: FeedReference): FeedOutcome {
        val gateway = feeds ?: return FeedOutcome.NoGateway
        find { it.serverId == reference.serverId && it.mode == ConnectionMode.GatewayFeed }
            ?.let {
                return FeedOutcome.Already(it)
            }
        val message =
            try {
                when (val answer = gateway.resolve(reference)) {
                    is FeedManifest.Held -> answer.manifest
                    // Nothing was asked to be spared, so nothing may be withheld: a gateway that
                    // answers "unchanged" to a phone holding no revision is not answering this
                    // request, and a feed added from it would have no settings at all.
                    is FeedManifest.Unchanged ->
                        return FeedOutcome.Failed(GatewayException.Kind.BadResponse.toOutcome())
                }
            } catch (e: GatewayException) {
                return FeedOutcome.Failed(e.kind.toOutcome())
            }
        val manifest =
            when (
                val result =
                    manifestFrom(
                        message,
                        ManifestExpectation(
                            serverId = reference.serverId,
                            mode = ConnectionMode.GatewayFeed,
                            origin = reference.gatewayUrl,
                        ),
                    )
            ) {
                is ManifestResult.Valid -> result.manifest
                is ManifestResult.Invalid -> return FeedOutcome.Refused(result.problem)
            }
        // The phone's own ID for a connection it wasn't given one for. A feed pairs with nothing,
        // so there is no server to assign it and no device name to have told anyone.
        val drafted =
            Connection(
                id = UUID.randomUUID().toString(),
                label = manifest.name.ifEmpty { PairingCodes.hostOf(reference.gatewayUrl) },
                serverUrl = reference.gatewayUrl,
                serverId = manifest.serverId,
                deviceName = "",
                pairedAt = now(),
                hasCredential = false,
                mode = ConnectionMode.GatewayFeed,
                server = ServerRecord.Known(manifest),
                // Sandbox whenever this publisher offers one, and the owner's to change afterwards
                // (SEE-97). A feed that is added straight into production is one whose publisher
                // serves nothing else.
                environment = startingEnvironment(ServerRecord.Known(manifest)),
            )
        val connection = locked {
            if (store.get(drafted.id) != null) {
                throw GatewayException(GatewayException.Kind.BadResponse, "a known connection ID")
            }
            val coloured = drafted.copy(colour = nextServerColour(store.list()))
            try {
                store.put(coloured)
            } catch (e: IOException) {
                throw StorageException(e)
            }
            publish()
            coloured
        }
        return FeedOutcome.Added(connection)
    }

    /**
     * Reads a feed's settings and applies them, telling the gateway which revision is already held
     * so an unchanged one costs one small answer (SEE-91).
     */
    suspend fun refreshSettings(id: String): FeedSettings {
        val connection =
            find(id)?.takeIf { it.mode == ConnectionMode.GatewayFeed }
                ?: return FeedSettings.NotAFeed
        val gateway = feeds ?: return FeedSettings.NoGateway
        val held = connection.server.manifest?.settingsRevision ?: 0L
        val answer =
            try {
                gateway.resolve(FeedReference(connection.serverUrl, connection.serverId), held)
            } catch (e: GatewayException) {
                return FeedSettings.Failed(e.kind.toOutcome())
            }
        return when (answer) {
            is FeedManifest.Unchanged -> FeedSettings.Unchanged
            is FeedManifest.Held -> applySettings(id, answer.manifest)
        }
    }

    /**
     * Applies a publisher's settings to a feed, from wherever they arrived.
     *
     * The stream delivers the same document a read would (SEE-91), so it goes through this one
     * path: the same validator, the same expectation built from the feed's own reference, and the
     * same contradiction rule. A streamed manifest is therefore not trusted more than a read one —
     * it is only faster.
     *
     * The owner's own label for the connection is left alone. It was theirs to set, and a publisher
     * renaming its server is not a reason to rename what someone called it on their phone.
     */
    suspend fun applySettings(id: String, message: WireManifest): FeedSettings {
        val connection =
            find(id)?.takeIf { it.mode == ConnectionMode.GatewayFeed }
                ?: return FeedSettings.NotAFeed
        val result =
            manifestFrom(
                message,
                ManifestExpectation(
                    serverId = connection.serverId,
                    mode = ConnectionMode.GatewayFeed,
                    origin = connection.serverUrl,
                    heldRevision = connection.server.manifest?.settingsRevision,
                ),
            )
        val manifest =
            when (result) {
                is ManifestResult.Valid -> result.manifest
                is ManifestResult.Invalid -> return FeedSettings.Refused(result.problem)
            }
        if (manifest == connection.server.manifest) return FeedSettings.Unchanged
        return when (val record = validated(manifest, connection.server)) {
            is ServerRecord.Known -> {
                update(id) { it.copy(server = record) }
                FeedSettings.Stored(record.manifest)
            }
            // validated refuses one thing and one thing only: content that moved while the
            // revision stood still. A feed must hold a manifest it checked, so nothing is written.
            else -> FeedSettings.Refused(ManifestProblem.ChangedWithoutRevision)
        }
    }

    /**
     * Reads what the connection's server says about itself and caches it (SEE-88).
     *
     * The cache is keyed by the server's identity and its settings revision: a manifest that
     * repeats what the phone holds is not written again, and one with a higher revision replaces
     * it. Anything the phone refuses — another identity, another origin, another mode, a revision
     * that went backwards — is recorded as a refusal rather than followed, so the credential keeps
     * going exactly where it always did.
     *
     * A server that couldn't be reached leaves the record alone. Not hearing an answer is not an
     * answer: what the phone last validated stands until the server says something else.
     *
     * Only a direct connection is read here, because a feed's manifest is the gateway's to answer
     * and this build has no gateway to ask (SEE-90). A feed keeps the manifest it was added with.
     */
    suspend fun resolveManifest(id: String) {
        val connection = find(id)?.takeIf { it.usable } ?: return
        val credential = withContext(io) { vault.get(id) } ?: return
        val message =
            try {
                gateway.serverManifest(connection.serverUrl, credential, id)
            } catch (e: GatewayException) {
                return
            }
        val record =
            if (message == null) ServerRecord.Legacy
            else
                when (
                    val result =
                        manifestFrom(
                            message,
                            ManifestExpectation(
                                serverId = connection.serverId,
                                mode = ConnectionMode.Direct,
                                origin = connection.serverUrl,
                                heldRevision = connection.server.manifest?.settingsRevision,
                            ),
                        )
                ) {
                    is ManifestResult.Valid -> validated(result.manifest, connection.server)
                    is ManifestResult.Invalid -> ServerRecord.Refused(result.problem)
                }
        if (record == connection.server) return // the same revision, and nothing to rewrite
        update(id) { it.copy(server = record) }
    }

    /**
     * The record for a manifest that passed every rule, against the one the phone already holds.
     *
     * The last rule can only be applied here, because it is about the cache rather than about the
     * document: a revision is the server's promise about its content, so content that changed while
     * the revision stood still is a contradiction. The phone keeps neither version, because it has
     * no way to tell which one the server meant.
     */
    private fun validated(manifest: ServerManifest, held: ServerRecord): ServerRecord {
        val known = held.manifest ?: return ServerRecord.Known(manifest)
        if (manifest.settingsRevision == known.settingsRevision && manifest != known) {
            return ServerRecord.Refused(ManifestProblem.ChangedWithoutRevision)
        }
        return ServerRecord.Known(manifest)
    }

    /**
     * Fetches the connection's pending requests (docs/protocol.md#phone-api). It first reads what
     * the server says about itself, then sends the owner's answers that are still waiting, then
     * reads every page. If the sidecar no longer accepts the credential, the connection is marked
     * revoked and the credential deleted. One fetch per connection runs at a time.
     */
    suspend fun refresh(id: String) =
        fetching
            .computeIfAbsent(id) { Mutex() }
            .withLock {
                // Asked every time rather than once: a server's settings can change under a
                // connection, and the phone finds out by reading the revision again. It is one
                // small call the server answers without touching its database.
                resolveManifest(id)
                when (val outcome = synchronization?.synchronize(id)) {
                    null,
                    is SynchronizeOutcome.Legacy -> fetch(id)
                    is SynchronizeOutcome.Updated,
                    is SynchronizeOutcome.Failed,
                    SynchronizeOutcome.Removed -> Unit
                }
            }

    /** Headless entry point for the later background caller; no Activity or ViewModel is needed. */
    suspend fun synchronizeAll(): Map<String, SynchronizeOutcome> {
        load()
        return synchronization?.synchronizeAll().orEmpty()
    }

    /** Recovery entry point that leaves connections with healthy foreground streams alone. */
    suspend fun synchronizeConnections(ids: Set<String>): Map<String, SynchronizeOutcome> {
        load()
        return synchronization?.synchronizeConnections(ids).orEmpty()
    }

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
                        save(it)
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
                        save(it)
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
            // A refusal is an answer: the sidecar has the approval and turned it down, or the call
            // never left this phone. Nothing was approved, so nothing is kept.
            //
            // A failure that isn't an answer is a different thing. The sidecar may have committed
            // the approval and lost the response on the way back, and a request it moved to
            // PROCESSING is one only this phone can settle. Deleting the approval would strand it
            // there for good, with the owner's reviewed bytes gone too, so it is kept and marked
            // as unknown; delivery asks the sidecar what became of it (send).
            if (e.kind.answersTheApproval) {
                locked {
                    results.delete(key.connectionId, key.requestId)
                    publish()
                }
            } else {
                locked {
                    stillStored(stored)?.let { save(it.copy(approvalUncertain = true)) }
                    publish()
                }
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
                    save(it)
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

    /**
     * Asks the connection's sidecar what became of a transfer this phone's wallet sent, and keeps
     * what it answers (SAW-022). No wallet is opened, nothing is signed, nothing is sent again, and
     * no second record of the spending is made: the only thing that changes here is this phone's
     * copy of a request the sidecar already had.
     *
     * Returns the stored answer as it is afterwards, or null when there is nothing to check.
     */
    suspend fun checkStatus(key: RequestKey): LocalResult? {
        val stored = locked { results.get(key.connectionId, key.requestId) } ?: return null
        if (!stored.awaitingChain) return stored
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
        val checked =
            try {
                gateway.checkStatus(connection.serverUrl, credential, key)
            } catch (e: GatewayException) {
                if (e.kind == GatewayException.Kind.Unauthenticated) markRevoked(key.connectionId)
                // A request the sidecar says has moved on is still news: keep what it reported.
                e.request ?: throw e
            }
        return locked {
            val current = stillStored(stored) ?: return@locked null
            // Only the phone's copy of the request changes. The owner's answer, the wallet's
            // outcome, and how it was delivered are what happened here, and they stand.
            current
                .copy(
                    request =
                        checked.takeIf { canAdvanceServerState(current.request, it) }
                            ?: current.request
                )
                .also {
                    save(it)
                    publish()
                }
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
                if (current.approvalUncertain) {
                    // An approval this phone never got an answer to. The approval is never sent
                    // again: reading the request says whether the sidecar took it, and that is the
                    // whole question. Whatever it says, there is no approval left to submit here.
                    current =
                        reconcileApproval(current, connection.serverUrl, credential) ?: return null
                    if (current.delivery != Delivery.Waiting || !current.approved) return current
                } else {
                    // An approved transfer that reaches here was never committed, so there is
                    // nothing to send: approveTransfer removes such an approval, and load() cleans
                    // up one a crash left behind. Sending it now would approve a transaction
                    // nobody reviewed afresh.
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
                            save(it)
                            publish()
                        }
                    }
            }
        }
    }

    /**
     * Finds out what became of an approval whose answer this phone never got, by reading the
     * request rather than sending anything (SAW-021). The approval is never submitted a second time
     * from here: the owner reviewed one preparation, and the only open question is whether the
     * sidecar took it.
     * - Still PENDING: it never arrived. The approval is dropped and null is returned, so the owner
     *   sees the request pending again and reviews the preparation as it is then.
     * - PROCESSING: it arrived, and the wallet was never opened — this phone opens it only for an
     *   approval the sidecar answered. So nothing was signed and nothing was sent, and that is what
     *   the answer now says, rather than leaving the request PROCESSING for ever.
     * - Anything else: the request has moved on, and what the sidecar reports stands.
     *
     * A sidecar that can't be asked throws, and [send] treats that like any other failed delivery:
     * the approval waits, and the next refresh asks again.
     */
    private suspend fun reconcileApproval(
        result: LocalResult,
        serverUrl: String,
        credential: String,
    ): LocalResult? {
        val checked =
            try {
                gateway.checkStatus(serverUrl, credential, result.key)
            } catch (e: GatewayException) {
                // The sidecar refuses to look on chain for a request that has nothing there yet —
                // one still PENDING, or PROCESSING and waiting for a wallet — and sends the
                // request itself along with the refusal. That request is the whole answer here.
                if (e.kind == GatewayException.Kind.Unauthenticated) throw e
                e.request ?: throw e
            }
        if (checked.state == RequestState.REQUEST_STATE_PENDING) {
            return locked<LocalResult?> {
                stillStored(result)?.let { results.delete(it.connectionId, it.requestId) }
                publish()
                null
            }
        }
        if (checked.state != RequestState.REQUEST_STATE_PROCESSING) {
            return settle(result, Delivery.Superseded, checked)
        }
        return locked<LocalResult?> {
            val stored = stillStored(result) ?: return@locked null
            stored
                .copy(
                    approved = true,
                    approvalUncertain = false,
                    request = checked,
                    signing = stored.signing ?: SigningOutcome.Failed(APPROVAL_ANSWER_LOST),
                )
                .also {
                    save(it)
                    publish()
                }
        }
    }

    suspend fun rename(id: String, label: String) {
        require(labelProblem(label) == null) { "invalid label" }
        update(id) { it.copy(label = label.trim()) }
    }

    /**
     * Stores the owner's marker colour for one connection (SEE-83). The choice is local: nothing is
     * sent to the server. [publish] replaces the in-memory list, so every open screen that reads it
     * shows the new colour without being reloaded.
     */
    suspend fun setColour(id: String, colour: ServerColour) {
        update(id) { it.copy(colour = colour) }
    }

    /**
     * Moves a gateway connection between the environments its server serves (SEE-97,
     * docs/wiki/environments.md), and returns what became of the request.
     *
     * Three things are true of every switch. It is the owner's: nothing a publisher republishes
     * reaches this method. It is only ever to an environment the server actually names, so a
     * connection cannot be put into a promise its server never made. And it invalidates what was
     * prepared — the review a phone is holding was prepared for the other environment, and a
     * preparation, a quote and an approval all belong to the environment they were made in, so the
     * owner reviews again rather than carrying one across.
     *
     * A direct or retired connection is refused: only a public feed has an environment choice.
     */
    suspend fun setEnvironment(id: String, environment: PluginEnvironment): EnvironmentOutcome {
        val connection = find(id) ?: return EnvironmentOutcome.Gone
        if (connection.mode != ConnectionMode.GatewayFeed) return EnvironmentOutcome.NotAFeed
        if (connection.environment == environment) return EnvironmentOutcome.Unchanged
        val served = connection.server.manifest?.environments.orEmpty()
        if (environment !in served) return EnvironmentOutcome.NotServed
        update(id) { it.copy(environment = environment) }
        // Nothing else is written, and nothing is deleted. What was in hand for the other promise
        // stops counting by itself: an open review on screen carries the environment it was opened
        // in, so the preparation under it is dropped, and a binding carries it too, so one made
        // under the other promise is refused inside the wallet's own lock (SEE-97). An
        // invalidation that had to be remembered here is one that could be forgotten here.
        //
        // What is kept is the amount the owner typed, which is neither an approval nor a quote:
        // they still prepare again, read the fresh review, acknowledge again and approve again.
        return EnvironmentOutcome.Changed
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
     * Removes the connection from this phone only: its credential first, then its answers, the
     * rules the owner wrote for it, the proposals it read (SEE-89), and its metadata.
     *
     * What outlives it is the owner's own Activity: what this phone did is worth keeping after the
     * connection that asked for it is gone (SAW-023, docs/wiki/shared-proposals.md#retention).
     */
    suspend fun remove(id: String) {
        synchronization?.remove(id)
        locked {
            vault.delete(id)
            results.deleteConnection(id)
            rules?.delete(id)
            proposals?.deleteConnection(id)
            store.delete(id)
            _inbox.update { it.copy(pending = it.pending - id) }
            publish()
        }
        onConnectionUnavailable(id)
    }

    // The sidecar no longer accepts the credential: nothing waiting for it can be sent any more.
    private suspend fun markRevoked(id: String, removeSync: Boolean = true) {
        if (removeSync) synchronization?.remove(id)
        locked {
            vault.delete(id)
            store.get(id)?.let { store.put(it.copy(revokedAt = now(), lastCheck = null)) }
            results
                .listFor(id)
                .filter { it.delivery == Delivery.Waiting }
                .forEach { save(it.copy(delivery = Delivery.Undeliverable, settledAt = now())) }
            _inbox.update { it.copy(pending = it.pending - id) }
            publish()
        }
        onConnectionUnavailable(id)
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
                // A sync event may have advanced the same result while this response was in
                // flight. Delivery is still settled, but stale server state cannot roll it back.
                request =
                    request.takeIf { canAdvanceServerState(current.request, it) }
                        ?: current.request,
                lastFailure = null,
                settledAt = now(),
            )
        save(settled)
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
            save(it)
            publish()
        }
    }

    /**
     * Writes an answer, and records what this phone did in the owner's history at the same time
     * (SAW-023). Every write of an answer goes through here, so a record can never be forgotten at
     * one call site and written at another.
     */
    private fun save(result: LocalResult) {
        results.put(result)
        history?.record(result, store.get(result.connectionId))
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

    private fun find(match: (Connection) -> Boolean): Connection? =
        _connections.value.firstOrNull(match)

    // Runs [block] under the lock on the I/O dispatcher.
    private suspend fun <T> locked(block: () -> T): T = lock.withLock {
        withContext(io) { block() }
    }

    /**
     * Gives every active connection that has no colour the next free palette entry, oldest first,
     * and writes only the records that changed. A retired record is not shown as a server, so it
     * takes none (SEE-83).
     */
    private fun assignMissingColours() {
        val current = store.list()
        val assigned = mutableListOf<Connection>()
        current.forEach { connection ->
            if (connection.colour != null || connection.retirement != null) {
                assigned += connection
            } else {
                val coloured = connection.copy(colour = nextServerColour(assigned))
                store.put(coloured)
                assigned += coloured
            }
        }
    }

    private fun publish() {
        _connections.value =
            store.list().map {
                // A feed holds no credential and never did, which is not the same as one having
                // gone missing: the mode is what the app reads, and it says so itself.
                it.copy(
                    hasCredential =
                        it.mode == ConnectionMode.Direct &&
                            it.revokedAt == null &&
                            vault.contains(it.id)
                )
            }
        val direct =
            _connections.value.filter { it.mode == ConnectionMode.Direct }.map { it.id }.toSet()
        _inbox.update {
            it.copy(results = results.list().filter { result -> result.connectionId in direct })
        }
    }

    // SynchronizationHost exposes only reads, retries of already-stored answers, and monotonic
    // reconciliation. In particular, it has no preparation, policy, or wallet operation.
    override suspend fun connectionIds(): Set<String> = locked {
        store.list().filter { it.mode == ConnectionMode.Direct }.map { it.id }.toSet()
    }

    override suspend fun access(connectionId: String): SyncConnection? {
        val connection = find(connectionId)?.takeIf { it.usable } ?: return null
        val credential = withContext(io) { vault.get(connectionId) } ?: return null
        return SyncConnection(connection.serverUrl, credential)
    }

    override suspend fun retryRecordedResults(connectionId: String) {
        val waiting = locked {
            results.listFor(connectionId).filter { it.delivery == Delivery.Waiting }.map { it.key }
        }
        for (key in waiting) {
            if (deliver(key, waitForTheOneInFlight = false)?.delivery == Delivery.Waiting) break
        }
    }

    override suspend fun nonterminalActivity(connectionId: String): List<LocalRequestState> {
        val local = locked { results.listFor(connectionId).associateBy { it.requestId } }
        val log = history
        if (log != null && !log.loaded.value) withContext(io) { log.load() }
        val fromActivity =
            log?.records
                ?.value
                .orEmpty()
                .filter { it.connectionId == connectionId }
                .mapNotNull { record ->
                    val state =
                        local[record.requestId]?.request?.state
                            ?: when (record.outcome) {
                                ActivityOutcome.Waiting -> RequestState.REQUEST_STATE_UNSPECIFIED
                                ActivityOutcome.Sent -> RequestState.REQUEST_STATE_SUBMITTED
                                ActivityOutcome.Unknown -> RequestState.REQUEST_STATE_UNKNOWN
                                else -> null
                            }
                    state?.let { LocalRequestState(record.key, it) }
                }
        val fromResults =
            local.values
                .filter { it.request.state !in SETTLED_REQUEST_STATES }
                .map { LocalRequestState(it.key, it.request.state) }
        return (fromActivity + fromResults).distinctBy { it.key }
    }

    override suspend fun authoritativeRequests(connectionId: String): Map<String, ActionRequest> =
        locked {
            results.listFor(connectionId).associate { it.requestId to it.request }
        }

    override suspend fun applyCache(state: ConnectionSyncState) = locked {
        // A result holds the owner's decision, wallet outcome, and reviewed bytes. Sync may advance
        // only its copy of server state and then records that through the existing Activity path.
        state.requests.values
            .mapNotNull { it.request }
            .forEach { observed ->
                results.get(state.connectionId, observed.ref.requestId)?.let { result ->
                    if (canAdvanceServerState(result.request, observed)) {
                        save(result.copy(request = observed))
                    }
                }
                history?.reconcile(observed)
            }
        val settled =
            results
                .listFor(state.connectionId)
                .filter { it.delivery != Delivery.Waiting }
                .map { it.requestId }
                .toSet()
        val visiblePending = state.pending.filterNot { request -> request.ref.requestId in settled }
        _inbox.update {
            it.copy(pending = it.pending + (state.connectionId to visiblePending))
        }
        state.lastSuccessfulSync?.let { at ->
            store.get(state.connectionId)?.let { connection ->
                store.put(
                    connection.copy(
                        lastCheck = Connection.Check(at, CheckOutcome.Ok, visiblePending.size)
                    )
                )
            }
        }
        publish()
    }

    override suspend fun recordFailure(connectionId: String, failure: CheckOutcome) {
        update(connectionId) { it.copy(lastCheck = Connection.Check(now(), failure)) }
    }

    override suspend fun revoke(connectionId: String) {
        markRevoked(connectionId, removeSync = false)
    }

    private companion object {
        /** At most this many pages of 100 PENDING requests are read per connection. */
        const val MAX_PAGES = 10
        val SETTLED_RETENTION: Duration = Duration.ofDays(7)
        const val APP_CLOSED = "The app closed before the wallet answered, so nothing was signed."
        const val APPROVAL_ANSWER_LOST =
            "The approval reached the server, but its answer never reached this phone, so the " +
                "wallet was never opened. Nothing was signed and nothing was sent."

        const val WALLET_LOST =
            "The wallet's answer never reached this phone, so nothing was signed."
        // A transfer can't say that: the wallet may have sent the transaction before it went.
        const val APP_CLOSED_SENDING =
            "The app closed while the transaction was with the wallet, so this phone never " +
                "learned whether it was sent."
        const val WALLET_LOST_SENDING =
            "The wallet's answer never reached this phone, so it never learned whether the " +
                "transaction was sent."

        val SETTLED_REQUEST_STATES =
            setOf(
                RequestState.REQUEST_STATE_CONFIRMED,
                RequestState.REQUEST_STATE_COMPLETED,
                RequestState.REQUEST_STATE_REJECTED,
                RequestState.REQUEST_STATE_CANCELLED,
                RequestState.REQUEST_STATE_EXPIRED,
                RequestState.REQUEST_STATE_FAILED,
            )

        fun canAdvanceServerState(before: ActionRequest, after: ActionRequest): Boolean {
            if (
                before.ref != after.ref ||
                    before.action != after.action ||
                    before.agentNote != after.agentNote ||
                    before.createdAt != after.createdAt ||
                    before.expiresAt != after.expiresAt
            ) {
                return false
            }
            if (before.state == after.state) return true
            if (before.state in SETTLED_REQUEST_STATES) return false
            return requestStateRank(after.state) >= requestStateRank(before.state)
        }

        fun requestStateRank(state: RequestState): Int =
            when (state) {
                RequestState.REQUEST_STATE_UNSPECIFIED -> -1
                RequestState.REQUEST_STATE_PENDING -> 0
                RequestState.REQUEST_STATE_PROCESSING -> 1
                RequestState.REQUEST_STATE_SUBMITTED,
                RequestState.REQUEST_STATE_UNKNOWN -> 2
                else -> 3
            }

        /**
         * Whether a failed approval is an answer about the approval itself. A refusal, and a call
         * that never left this phone, both say the approval was not taken. Everything else — a
         * connection that dropped, a response this app couldn't read — says nothing, and an
         * approval nobody answered must not be thrown away (approveTransfer).
         */
        val GatewayException.Kind.answersTheApproval: Boolean
            get() =
                when (this) {
                    GatewayException.Kind.Unauthenticated,
                    GatewayException.Kind.Rejected,
                    GatewayException.Kind.NotFound,
                    GatewayException.Kind.InvalidState,
                    GatewayException.Kind.StalePreparation,
                    GatewayException.Kind.CertificateRejected,
                    GatewayException.Kind.CleartextBlocked,
                    // A server that doesn't know the call understood it and stored nothing.
                    GatewayException.Kind.Unimplemented -> true
                    GatewayException.Kind.Unreachable,
                    GatewayException.Kind.BadResponse,
                    GatewayException.Kind.Other -> false
                }

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
