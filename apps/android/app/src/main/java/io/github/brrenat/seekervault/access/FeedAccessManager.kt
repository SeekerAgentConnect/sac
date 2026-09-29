package io.github.brrenat.seekervault.access

import io.github.brrenat.seekervault.access.storage.FeedAccessStore
import io.github.brrenat.seekervault.access.storage.FeedAccessStore.State
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.servers.FeedAccess
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.servers.feedAccess
import io.github.brrenat.seekervault.servers.manifest
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.SignResult
import io.github.brrenat.seekervault.wallet.decodeBase58
import io.github.brrenat.seekervault.wallet.verifiesSignature
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString

/**
 * What the gateway client needs from restricted-feed access (SEE-156): the session to present for a
 * channel, and where to report that the gateway refused one.
 */
interface FeedSessions {
    /** The session this phone holds for a restricted [channel], or null. */
    fun sessionFor(channel: String): String?

    /** The gateway refused [channel]'s session for [denial]. Must not block. */
    fun denied(channel: String, denial: Denial)

    enum class Denial {
        /** No session, or one the gateway does not hold for the channel. */
        Required,
        /** The publisher revoked it. Final. */
        Revoked,
        /** Its grant ran out. The publisher may renew it. */
        Expired,
    }

    companion object {
        /** No restricted access at all: every channel is read as public. */
        val None: FeedSessions =
            object : FeedSessions {
                override fun sessionFor(channel: String): String? = null

                override fun denied(channel: String, denial: Denial) = Unit
            }
    }
}

/** What asking for access, checking it, or redeeming an invitation came to. */
sealed interface AccessResult {
    data class Done(val record: FeedAccessStore.Record) : AccessResult

    /** The connection is not a restricted feed. */
    data object NotRestricted : AccessResult

    /** No request was made from this phone yet. */
    data object NotRequested : AccessResult

    /** The feed names no wallet profile on this phone (SEE-174). */
    data object NoWallet : AccessResult

    /** The owner declined in the wallet, or the wallet could not sign. Nothing was sent. */
    data class WalletDidNotSign(val result: SignResult) : AccessResult

    /** The publisher's challenge was not the text this phone would sign. Nothing was signed. */
    data object BadChallenge : AccessResult

    /** The publisher could not be reached. Nothing changed; it can be tried again. */
    data object Unreachable : AccessResult

    /** The publisher answered no, with its own code. */
    data class Refused(val code: String) : AccessResult
}

/**
 * Restricted-feed access on this phone (SEE-156, docs/wiki/restricted-feeds.md#on-the-phone).
 *
 * The flow, for one feed connection:
 * 1. **Request.** A device key is created for the feed, the publisher's challenge is fetched from
 *    the authentication origin the gateway stamped on the manifest, rebuilt here and compared, and
 *    the owner's wallet signs it — the one wallet signature in the whole flow, over text that says
 *    it is not a transaction. The device key signs the same bytes, which binds this installation.
 * 2. **Wait.** The request is pending until the publisher decides. [check] asks, signed with the
 *    device key, and never with the wallet again.
 * 3. **Redeem.** An approval arrives as a single-use invitation bound to this device key; this
 *    phone redeems it at once (or from a link), and receives the session the gateway enforces.
 * 4. **Read.** Every read of the feed carries the session. A refusal from the gateway — revoked, or
 *    expired — lands in [denied], and the state says so.
 *
 * Access belongs to the wallet it was proven with, and each feed proves its own (SEE-174): [wallet]
 * answers the profile one connection is bound to, so two restricted feeds bound to two different
 * wallets are both readable at once, and a request, a refusal or a revocation on one touches
 * nothing of the other. A feed rebound to another address does not inherit the access its old
 * wallet proved: asking again with the new one starts a new request and drops the old session.
 *
 * The proof is a signature over an address and nothing else — no network enters it — so a feed
 * rebound to the same address on another network keeps its access (docs/wiki/restricted-feeds.md).
 * A wallet app's own authorization token is never part of it.
 */
class FeedAccessManager(
    private val connections: () -> List<Connection>,
    private val store: FeedAccessStore,
    private val sessions: CredentialVault,
    private val keys: DeviceKeys,
    private val api: FeedAccessApi,
    /** The wallet profile [connectionId] is bound to, or null. Never another connection's. */
    private val wallet: (connectionId: String) -> SelectedWallet?,
    private val sign: suspend (okio.ByteString, SelectedWallet) -> SignResult,
    private val label: () -> String,
    /** Registers a push target for a connected feed; best effort. */
    private val pushTarget:
        suspend (gatewayUrl: String, channel: String, session: String, target: String) -> Unit =
        { _, _, _, _ ->
        },
    /** A feed just became readable: read it now rather than waiting for the next pass. */
    private val onConnected: (connectionId: String) -> Unit = {},
    /**
     * How long to wait before each further attempt at a push registration the gateway refused — a
     * redemption answered before the publisher installed the grant at the gateway is refused until
     * it does.
     */
    private val pushRetries: List<Duration> = PUSH_RETRIES,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + io),
) : FeedSessions {
    private val lock = Mutex()

    /** Every record this phone holds, whichever wallet it was proven with. */
    private val _records = MutableStateFlow<Map<String, FeedAccessStore.Record>>(emptyMap())

    private val _states = MutableStateFlow<Map<String, FeedAccessStore.Record>>(emptyMap())

    /**
     * Every restricted feed's access for the wallet that feed is bound to now, by connection ID.
     * Access proven with another wallet is left out: it does not read the feed while that wallet is
     * not the feed's, so to the screens it is a feed nothing has been asked of yet.
     */
    val states: StateFlow<Map<String, FeedAccessStore.Record>> = _states.asStateFlow()

    /** Sessions by channel, so the gateway client can read one without a suspension. */
    private val byChannel = ConcurrentHashMap<String, String>()

    @Volatile private var target: String? = null

    /**
     * Connected feeds whose push registration did not land, by connection ID. They are registered
     * again on a backoff, when a check finds the grant usable, and on the next registration.
     */
    private val pendingPush = ConcurrentHashMap.newKeySet<String>()

    /** Reads what is stored. */
    suspend fun load() = lock.withLock {
        withContext(io) {
            val records = store.all()
            byChannel.clear()
            for (record in records) {
                if (record.state == State.Connected || record.state == State.Expired) {
                    sessions.get(record.connectionId)?.let {
                        byChannel[channelFor(record.serverId)] = it
                    }
                }
            }
            _records.value = records.associateBy { it.connectionId }
            publish()
        }
    }

    override fun sessionFor(channel: String): String? =
        byChannel[channel]?.takeIf { recordFor(channel)?.let(::isActive) == true }

    /**
     * A feed's wallet binding changed, or the profiles were loaded or removed: the feeds whose own
     * wallet proved their access are the ones readable now, and only their sessions are presented.
     */
    fun onWalletChanged() = publish()

    /** Whether [record] was proven with the wallet its own feed is bound to now. */
    private fun isActive(record: FeedAccessStore.Record): Boolean =
        record.wallet == wallet(record.connectionId)?.address

    private fun recordFor(channel: String): FeedAccessStore.Record? =
        _records.value.values.firstOrNull { channelFor(it.serverId) == channel }

    private fun publish() {
        _states.value = _records.value.filterValues(::isActive)
    }

    override fun denied(channel: String, denial: FeedSessions.Denial) {
        scope.launch {
            lock.withLock {
                // A refusal of a feed this phone did not present a session for, because the
                // session belongs to another wallet, says nothing about that session.
                val record = recordFor(channel)?.takeIf(::isActive) ?: return@withLock
                when (denial) {
                    FeedSessions.Denial.Revoked -> {
                        // Final: the session is dropped, so nothing on this phone presents it
                        // again.
                        dropSession(record)
                        save(record.copy(state = State.Revoked, updatedAt = now()))
                    }
                    FeedSessions.Denial.Expired,
                    FeedSessions.Denial.Required ->
                        if (record.state == State.Connected) {
                            save(record.copy(state = State.Expired, updatedAt = now()))
                        }
                }
            }
        }
    }

    /**
     * Asks the publisher for access with the wallet profile [connectionId] is bound to. It opens
     * the wallet once, to sign the challenge.
     */
    suspend fun requestAccess(connectionId: String): AccessResult {
        val (connection, origin) = restricted(connectionId) ?: return AccessResult.NotRestricted
        val selected = wallet(connectionId) ?: return AccessResult.NoWallet
        val held = lock.withLock { _records.value[connectionId] }
        if (
            held != null &&
                held.wallet == selected.address &&
                held.state != State.Rejected &&
                held.state != State.Revoked
        ) {
            // Already asked with this wallet: asking again would be a second request for the same
            // device, which the publisher answers with the first one anyway.
            return check(connectionId)
        }
        val alias = DeviceKeys.aliasFor(connectionId)
        val channel = channelFor(connection.serverId)
        return guarded {
            val deviceKey = withContext(io) { keys.publicKey(alias) }
            val answer = api.challenge(origin, channel, selected.address, deviceKey, label())
            val challenge = answer.challenge
            val built = FeedAccessProof.message(challenge)
            val lifetime = Duration.between(challenge.issuedAt, challenge.expiresAt)
            if (
                challenge.authOrigin != origin ||
                    challenge.channel != channel ||
                    challenge.wallet != selected.address ||
                    challenge.installation != FeedAccessProof.installation(deviceKey) ||
                    !challenge.expiresAt.isAfter(now()) ||
                    lifetime.isNegative ||
                    lifetime > MOST_CHALLENGE_LIFETIME ||
                    !built.contentEquals(answer.message.toByteArray(Charsets.UTF_8))
            ) {
                return@guarded AccessResult.BadChallenge
            }
            val signed = sign(built.toByteString(), selected)
            if (
                signed !is SignResult.Signed ||
                    signed.message.toByteArray().contentEquals(built).not() ||
                    !verifiesSignature(
                        decodeBase58(selected.address) ?: return@guarded AccessResult.BadChallenge,
                        built,
                        signed.signature.toByteArray(),
                    )
            ) {
                return@guarded AccessResult.WalletDidNotSign(signed)
            }
            val deviceSignature = withContext(io) { keys.sign(alias, built) }
            val requested =
                api.request(
                    origin,
                    challenge.attempt,
                    signed.signature.toByteArray(),
                    deviceSignature,
                )
            lock.withLock {
                // A new request replaces whatever this phone held for the feed, including access
                // proven with another wallet: that one does not carry over.
                held?.let { dropSession(it) }
                save(
                    FeedAccessStore.Record(
                        connectionId = connectionId,
                        serverId = connection.serverId,
                        wallet = selected.address,
                        installation = challenge.installation,
                        requestId = requested.requestId,
                        state = stateOf(requested.state),
                        updatedAt = now(),
                    )
                )
            }
            if (stateOf(requested.state) == State.Approved) check(connectionId)
            else AccessResult.Done(checkNotNull(_records.value[connectionId]))
        }
    }

    /**
     * Asks the publisher where this phone's request stands, signed with the device key, and redeems
     * an invitation when there is one.
     */
    suspend fun check(connectionId: String): AccessResult {
        val (connection, origin) = restricted(connectionId) ?: return AccessResult.NotRestricted
        val record =
            lock.withLock { _records.value[connectionId] } ?: return AccessResult.NotRequested
        val alias = DeviceKeys.aliasFor(connectionId)
        return guarded {
            val at = now().toEpochMilli()
            val signature =
                withContext(io) {
                    keys.sign(alias, FeedAccessProof.statusStatement(record.requestId, at))
                }
            val status = api.status(origin, record.requestId, at, signature)
            val state = stateOf(status.state)
            when {
                state == State.Approved && status.invitation != null ->
                    redeem(connectionId, status.invitation)
                state == State.Approved && status.connected && sessions.contains(connectionId) -> {
                    val done = lock.withLock {
                        // Still approved and the publisher holds a live grant: the session stands.
                        // If the gateway refused it a moment ago, the grant was renewed since.
                        byChannel[channelFor(connection.serverId)] =
                            checkNotNull(sessions.get(connectionId))
                        AccessResult.Done(
                            save(record.copy(state = State.Connected, updatedAt = now()))
                        )
                    }
                    // Readable again after a refusal: a stream ticketed meanwhile left it out.
                    if (record.state != State.Connected) onConnected(connectionId)
                    // The grant is usable now, so a registration it refused earlier can land.
                    val session = byChannel[channelFor(connection.serverId)]
                    val current = target
                    if (connectionId in pendingPush && session != null && current != null) {
                        registerPush(connection, session, current)
                    }
                    done
                }
                else ->
                    lock.withLock {
                        if (state == State.Rejected || state == State.Revoked) dropSession(record)
                        AccessResult.Done(save(record.copy(state = state, updatedAt = now())))
                    }
            }
        }
    }

    /** Redeems an invitation — from a status answer, or from a link — for this device. */
    suspend fun redeem(connectionId: String, invitation: String): AccessResult {
        val (connection, origin) = restricted(connectionId) ?: return AccessResult.NotRestricted
        val record =
            lock.withLock { _records.value[connectionId] } ?: return AccessResult.NotRequested
        val alias = DeviceKeys.aliasFor(connectionId)
        val channel = channelFor(connection.serverId)
        return guarded {
            val at = now().toEpochMilli()
            val signature =
                withContext(io) {
                    keys.sign(alias, FeedAccessProof.redeemStatement(channel, invitation, at))
                }
            val answer = api.redeem(origin, channel, invitation, at, signature)
            val saved = lock.withLock {
                withContext(io) { sessions.put(connectionId, answer.session) }
                byChannel[channel] = answer.session
                save(
                    record.copy(
                        state = State.Connected,
                        updatedAt = now(),
                        grantUntil = answer.until,
                    )
                )
            }
            onConnected(connectionId)
            target?.let {
                // A redemption the gateway has not synced yet (answer.synced == false) holds a
                // session the gateway refuses until the publisher installs the grant, so the
                // registration is retried until it lands rather than dropped.
                if (!registerPush(connection, answer.session, it)) retryPush(connectionId)
            }
            AccessResult.Done(saved)
        }
    }

    /** A connection was removed: its record, session and device key go with it. */
    suspend fun forget(connectionId: String) = lock.withLock {
        val record = _records.value[connectionId]
        record?.let { dropSession(it) }
        withContext(io) {
            store.delete(connectionId)
            runCatching { keys.delete(DeviceKeys.aliasFor(connectionId)) }
        }
        _records.value = _records.value - connectionId
        publish()
    }

    /** A new push registration: every connected restricted feed is told where its hints go. */
    fun onRegistered(newTarget: String) {
        target = newTarget
        scope.launch {
            for (record in
                _records.value.values.filter { it.state == State.Connected && isActive(it) }) {
                val connection =
                    connections().firstOrNull { it.id == record.connectionId } ?: continue
                val session = byChannel[channelFor(record.serverId)] ?: continue
                registerPush(connection, session, newTarget)
            }
        }
    }

    /** Registers [target] for [connection]'s feed, and answers whether the gateway took it. */
    private suspend fun registerPush(
        connection: Connection,
        session: String,
        target: String,
    ): Boolean {
        val landed = runCatching {
            pushTarget(connection.serverUrl, channelFor(connection.serverId), session, target)
        }
            .isSuccess
        if (landed) pendingPush.remove(connection.id) else pendingPush.add(connection.id)
        return landed
    }

    /**
     * Registers [connectionId]'s feed again on [pushRetries], until it lands, the feed stops being
     * connected with its own wallet, or another path (a check, a new registration) landed it.
     */
    private fun retryPush(connectionId: String) {
        scope.launch {
            for (wait in pushRetries) {
                delay(wait.toMillis())
                if (connectionId !in pendingPush) return@launch
                val record = _records.value[connectionId] ?: return@launch
                if (record.state != State.Connected || !isActive(record)) return@launch
                val connection =
                    connections().firstOrNull { it.id == connectionId } ?: return@launch
                val session = byChannel[channelFor(record.serverId)] ?: return@launch
                val current = target ?: return@launch
                if (registerPush(connection, session, current)) return@launch
            }
        }
    }

    /** The feed connection and its authentication origin, when it is a restricted feed. */
    private fun restricted(connectionId: String): Pair<Connection, String>? {
        val connection = connections().firstOrNull { it.id == connectionId } ?: return null
        val access = connection.server.manifest?.feedAccess as? FeedAccess.Restricted ?: return null
        return connection to access.authOrigin
    }

    private suspend fun save(record: FeedAccessStore.Record): FeedAccessStore.Record {
        withContext(io) { store.put(record) }
        _records.value = _records.value + (record.connectionId to record)
        publish()
        return record
    }

    private suspend fun dropSession(record: FeedAccessStore.Record) {
        pendingPush.remove(record.connectionId)
        byChannel.remove(channelFor(record.serverId))
        withContext(io) { sessions.delete(record.connectionId) }
    }

    /** Turns the endpoint's failures into results the screen can say. */
    private inline fun guarded(block: () -> AccessResult): AccessResult =
        try {
            block()
        } catch (e: FeedAccessException) {
            when (e.kind) {
                FeedAccessException.Kind.Unreachable -> AccessResult.Unreachable
                FeedAccessException.Kind.BadResponse -> AccessResult.BadChallenge
                FeedAccessException.Kind.Refused -> AccessResult.Refused(e.code)
            }
        }

    private companion object {
        /** A challenge that claims to last longer than this is not one this phone signs. */
        val MOST_CHALLENGE_LIFETIME: Duration = Duration.ofMinutes(30)

        /**
         * The publisher retries its grant with a backoff that reaches a minute; this outlasts it.
         */
        val PUSH_RETRIES: List<Duration> =
            listOf(5L, 15L, 30L, 60L, 120L, 300L, 600L, 1800L).map(Duration::ofSeconds)

        fun stateOf(state: String): State =
            when (state) {
                "approved" -> State.Approved
                "rejected" -> State.Rejected
                "revoked" -> State.Revoked
                else -> State.Pending
            }
    }
}
