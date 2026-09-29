package io.github.brrenat.seekervault.wallet

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionWallets
import io.github.brrenat.seekervault.request.v1.WalletBinding
import io.github.brrenat.seekervault.request.v1.walletBinding
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.manifest
import io.github.brrenat.seekervault.wallet.storage.StoredAuthorization
import io.github.brrenat.seekervault.wallet.storage.WalletProfiles
import io.github.brrenat.seekervault.wallet.storage.WalletStore
import java.io.IOException
import java.security.GeneralSecurityException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Storing the wallet failed on this phone; nothing was saved, and nothing was published. */
class WalletStorageException(cause: Throwable) : Exception(cause.message, cause)

/**
 * The wallet, for a caller that already holds the one wallet lock through
 * [WalletRepository.withWallet]. It carries no state of its own: it is the permission to reach the
 * wallet, handed out once per lock.
 */
interface WalletSession {
    /** The same call as [WalletRepository.signAndSend], with the lock already held. */
    suspend fun signAndSend(
        transaction: ByteString,
        reviewed: SelectedWallet,
        connectionId: String? = null,
    ): SendResult
}

/**
 * Whether one connection has a wallet it may sign with, and when it hasn't, why not (SEE-174,
 * docs/guides/wallet-setup.md#one-wallet-per-connection).
 *
 * It is derived and never stored: from the profile the connection names, the networks its server
 * declared, and — for a direct server — whether the server has been told about that profile. Only
 * [Ready] may reach a wallet; every other state is something to show the owner, with the profile it
 * is about when there is one, and never a reason to fall back to another profile.
 */
sealed interface WalletReadiness {
    /** The profile this state is about, when the connection names one that exists. */
    val profile: WalletProfile?

    /** The connection's own profile, on a network its server declared, and ready to sign. */
    data class Ready(override val profile: WalletProfile) : WalletReadiness

    /** The owner hasn't chosen a wallet for this connection. */
    data object NoProfile : WalletReadiness {
        override val profile: WalletProfile? = null
    }

    /** The profile the connection named is gone. Nothing was chosen in its place. */
    data object ProfileMissing : WalletReadiness {
        override val profile: WalletProfile? = null
    }

    /** The wallet refused this profile's authorization: the owner reconnects it. */
    data class NeedsReconnect(override val profile: WalletProfile) : WalletReadiness

    /**
     * The server hasn't declared the Solana networks it supports — it is older than SEE-174, its
     * manifest hasn't been read, or it declares none because nothing it sends reaches a wallet.
     * That is never read as Mainnet: nothing is signed until the server says.
     */
    data class NetworksUnknown(override val profile: WalletProfile?) : WalletReadiness

    /** The server doesn't support the profile's network any more. The owner decides what now. */
    data class NetworkUnsupported(
        override val profile: WalletProfile,
        val supported: Set<WalletNetwork>,
    ) : WalletReadiness

    /**
     * A direct server hasn't confirmed it holds this profile's binding yet: nothing is signed
     * against a binding the server may not have, and publishing is tried again.
     */
    data class PublicationPending(override val profile: WalletProfile) : WalletReadiness

    /** There is no such connection, or it is retired. */
    data object NoConnection : WalletReadiness {
        override val profile: WalletProfile? = null
    }
}

val WalletReadiness.ready: Boolean
    get() = this is WalletReadiness.Ready

/**
 * What one direct server was last told about its connection's wallet (SEE-174), by connection. It
 * is kept per connection and per binding: a binding that changes is published again to that server
 * alone, and one that was published is never sent again just because another connection changed.
 */
data class Publication(
    /** The binding as `address|network`, or null for "no wallet". */
    val binding: String?,
    val state: State,
    /** How many PENDING requests the server cancelled when it took this binding. */
    val cancelled: Int = 0,
) {
    enum class State {
        Pending,
        Published,
        Failed,
    }
}

/**
 * What adding or reconnecting a wallet produced: the wallet's answer, and the profiles it saved.
 */
data class ProfileConnection(val result: WalletResult, val profiles: List<WalletProfile>)

/** What binding a connection to a profile came to. */
sealed interface BindOutcome {
    /** Bound; a feed has nothing to publish, so there is nothing more to it. */
    data object Bound : BindOutcome

    /** Bound, and the direct server took it; it cancelled [cancelled] PENDING requests. */
    data class Published(val cancelled: Int) : BindOutcome

    /**
     * Bound, but the direct server couldn't be told; it is retried, and nothing signs meanwhile.
     */
    data object PublicationFailed : BindOutcome

    /** The profile's network isn't one the server declared. Nothing changed. */
    data object Incompatible : BindOutcome

    /** The connection or the profile is gone. Nothing changed. */
    data object Gone : BindOutcome
}

/**
 * The wallet profiles the owner saved, and which one each connection uses (SEE-174,
 * docs/guides/wallet-setup.md).
 * - Adding asks the installed wallet for a fresh authorization on one network and saves every
 *   account it authorizes as a profile. The same account in the same app on the same network is one
 *   profile, refreshed rather than duplicated.
 * - Binding names the profile one connection uses. A direct server is told about its own
 *   connection's profile and nothing else; a feed is told nothing, because its binding is a local
 *   execution setting.
 * - Signing asks the wallet for a signature over exact bytes, and only with the profile the owner
 *   reviewed — and, when it was reviewed for a connection, only while that connection still names
 *   it and is ready (SAW-016). It is never called before the owner approves.
 * - Signing and sending hands the wallet an approved transaction, which the wallet signs and
 *   submits itself (SAW-021). This app reaches no chain, and each of these is one interaction.
 * - Addresses are public and go to the servers bound to them; authorization tokens stay in [store]
 *   and go nowhere.
 *
 * Storage runs on [io]; one wallet operation runs at a time, however many profiles there are.
 */
class WalletRepository(
    private val store: WalletStore,
    private val adapter: WalletAdapter,
    private val connections: ConnectionWallets,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Mutex()

    /** What is stored, as last read or written. Changed only under [lock]. */
    @Volatile private var held = WalletProfiles.Empty

    private val _profiles = MutableStateFlow<List<WalletProfile>>(emptyList())
    /** Every saved profile, in the order they were added. */
    val profiles: StateFlow<List<WalletProfile>> = _profiles.asStateFlow()

    private val _publications = MutableStateFlow<Map<String, Publication>>(emptyMap())
    /** What each direct server was last told, by connection. */
    val publications: StateFlow<Map<String, Publication>> = _publications.asStateFlow()

    /**
     * Reads the stored profiles — migrating the single wallet an older version kept — and binds
     * every connection stored before profiles existed to the profile that wallet became, or to none
     * when there wasn't a usable one. Both halves can run again after an interruption and do the
     * same thing (SEE-174).
     */
    suspend fun load() {
        lock.withLock {
            val stored = withContext(io) { store.profiles() }
            held = stored
            _profiles.value = stored.profiles
        }
        connections.load()
        connections.adoptLegacyWallet(held.legacyProfileId?.takeIf { held.profile(it) != null })
    }

    /** The profile [id] names, or null. */
    fun profile(id: String?): WalletProfile? = held.profile(id)

    /** The connections that name [profileId], for the impact summary before it is removed. */
    fun usersOf(profileId: String): List<Connection> =
        connections.connections.value.filter { it.walletProfileId == profileId }

    /**
     * The wallet apps installed on this phone, so the owner can be offered one without Android's
     * chooser (SEE-159). It opens nothing and takes no wallet lock: it is a question for the
     * system, not for a wallet.
     */
    suspend fun installedWallets(): List<InstalledWallet> = adapter.installed()

    /** [connectProfiles], answering only what the wallet said. */
    suspend fun connect(network: WalletNetwork, app: InstalledWallet? = null): WalletResult =
        connectProfiles(network, app).result

    /**
     * Asks [app] — or the one wallet app on this phone, or failing that whichever one Android
     * resolves — for a fresh authorization on [network], and saves every account it authorizes.
     *
     * It is always fresh: no stored token is offered, so the wallet asks the owner which account to
     * authorize rather than silently handing back the one it authorized before, which is what
     * adding a second account from the same wallet needs. An account already saved for the same app
     * and network is refreshed in place — same ID, so every connection naming it still does — and
     * anything else is a new profile. Nothing else is touched: no other profile, no connection, and
     * nothing is published, because adding a profile binds it to nothing.
     */
    suspend fun connectProfiles(
        network: WalletNetwork,
        app: InstalledWallet? = null,
    ): ProfileConnection = lock.withLock {
        val result = adapter.connect(network, null, aim(app))
        ProfileConnection(result, saved(network, result, only = null))
    }

    /**
     * Connects [profileId]'s account again, in the wallet app it lives in, offering the
     * authorization it has while the wallet still honours it. Only that profile is refreshed: a
     * wallet that no longer authorizes its account leaves it as it was, and the other accounts the
     * wallet may name are not saved from here.
     */
    suspend fun reconnect(profileId: String): ProfileConnection = lock.withLock {
        val profile =
            held.profile(profileId)
                ?: return@withLock ProfileConnection(WalletResult.Failed(REMOVED), emptyList())
        val authorization = held.authorization(profile.authorizationId)
        val offered = authorization?.token?.takeIf { profile.authorized }
        val result =
            adapter.connect(profile.network, offered, authorization?.route ?: profile.route)
        when {
            result == WalletResult.AuthorizationExpired -> authorization?.let { expire(it.id) }
            result is WalletResult.Connected &&
                result.accounts.none { it.address == profile.address } ->
                return@withLock ProfileConnection(
                    WalletResult.Failed(OTHER_ACCOUNT),
                    emptyList(),
                )
        }
        ProfileConnection(result, saved(profile.network, result, only = profile))
    }

    /**
     * Where a new authorization is aimed: the app the owner picked, or the one wallet app on this
     * phone. With several and no pick, Android resolves it and the route learns nothing.
     */
    private suspend fun aim(app: InstalledWallet?): WalletRouting? {
        if (app != null) return WalletRouting(packageName = app.packageName, appLabel = app.label)
        return adapter.installed().singleOrNull()?.let {
            WalletRouting(packageName = it.packageName, appLabel = it.label)
        }
    }

    // The body of connecting, with the lock held: stores the new authorization and the profiles
    // for the accounts it authorized, or just [only]'s when reconnecting one.
    private suspend fun saved(
        network: WalletNetwork,
        result: WalletResult,
        only: WalletProfile?,
    ): List<WalletProfile> {
        if (result !is WalletResult.Connected) return emptyList()
        val authorization =
            StoredAuthorization(
                id = newId(),
                token = result.authToken,
                network = network,
                route = result.route,
            )
        val at = now()
        val profiles = held.profiles.toMutableList()
        val saved = mutableListOf<WalletProfile>()
        val accounts =
            if (only == null) result.accounts
            else result.accounts.filter { it.address == only.address }
        for (account in accounts) {
            val confirmed = account.chains.isEmpty() || network.chain in account.chains
            val index =
                if (only != null) profiles.indexOfFirst { it.id == only.id }
                else existing(profiles, account.address, network, result.route.packageName)
            val profile =
                if (index >= 0) {
                    profiles[index].copy(
                        accountLabel = account.label ?: profiles[index].accountLabel,
                        route = result.route,
                        authorizationId = authorization.id,
                        connectedAt = at,
                        networkConfirmed = confirmed,
                        authorized = true,
                    )
                } else {
                    WalletProfile(
                        id = newId(),
                        address = account.address,
                        network = network,
                        accountLabel = account.label,
                        route = result.route,
                        authorizationId = authorization.id,
                        connectedAt = at,
                        networkConfirmed = confirmed,
                    )
                }
            if (index >= 0) profiles[index] = profile else profiles += profile
            saved += profile
        }
        if (saved.isEmpty()) return emptyList()
        commit(
            held.copy(profiles = profiles, authorizations = held.authorizations + authorization),
            strict = true,
        )
        return saved
    }

    /**
     * The profile an account on [network] in the app [packageName] already is. The same app, or —
     * for a profile saved before this phone learned which app answered — no app at all, which the
     * new authorization then names.
     */
    private fun existing(
        profiles: List<WalletProfile>,
        address: String,
        network: WalletNetwork,
        packageName: String?,
    ): Int =
        profiles.indexOfFirst { it.sameAccount(address, network, packageName) }.takeIf { it >= 0 }
            ?: profiles.indexOfFirst {
                it.address == address && it.network == network && it.route.packageName == null
            }

    /** Renames a profile. A blank name goes back to the wallet's own. */
    suspend fun rename(profileId: String, label: String): Unit = lock.withLock {
        held.profile(profileId) ?: return@withLock
        val name = label.trim().takeIf { it.isNotEmpty() }?.take(MAX_PROFILE_LABEL)
        commit(
            held.copy(
                profiles =
                    held.profiles.map { if (it.id == profileId) it.copy(label = name) else it }
            ),
            strict = true,
        )
    }

    /**
     * Removes a profile (SEE-174). Every connection naming it is left without a wallet — nothing is
     * chosen in its place — and a direct one's server is told there is none, which cancels its own
     * PENDING requests the way any other change of binding does. Its authorization goes only when
     * no other profile uses it, and only then is the wallet asked to forget it. Returns the
     * connections that were bound to it.
     */
    suspend fun remove(profileId: String): List<Connection> {
        val removed =
            lock.withLock {
                val profile = held.profile(profileId) ?: return@withLock null
                val remaining = held.copy(profiles = held.profiles.filterNot { it.id == profileId })
                val orphan =
                    remaining.authorization(profile.authorizationId)?.takeIf { authorization ->
                        remaining.profiles.none { it.authorizationId == authorization.id }
                    }
                commit(remaining, strict = true)
                // After this phone stopped holding it, so a wallet that fails to answer changes
                // nothing, and never while another profile still signs with it.
                orphan?.let { adapter.disconnect(profile.selected(), it.token, it.route) }
                profile
            } ?: return emptyList()
        val affected = connections.clearWalletProfile(removed.id)
        lock.withLock { affected.filter { it.usable }.forEach { publishOne(it.id) } }
        return affected
    }

    /**
     * Binds [connectionId] to [profileId], or to none (SEE-174). A profile on a network the server
     * doesn't declare is refused; a server that declares none may still be bound — a restricted
     * feed proves its reader's address with it — but nothing is signed for it until the server
     * declares a network ([WalletReadiness.NetworksUnknown]).
     *
     * A direct server is told at once, and only that server: it replaces the binding it held and
     * cancels its own PENDING requests that don't fit the new one, which is what the owner was told
     * before choosing. A feed is told nothing; what it prepared for the old profile stops counting
     * because every review captures the profile it was prepared for.
     */
    suspend fun bind(connectionId: String, profileId: String?): BindOutcome = lock.withLock {
        val connection =
            connections.connection(connectionId)?.takeIf { it.retirement == null }
                ?: return@withLock BindOutcome.Gone
        val profile = profileId?.let { held.profile(it) ?: return@withLock BindOutcome.Gone }
        val supported = connection.server.manifest?.supportedNetworks.orEmpty()
        if (profile != null && supported.isNotEmpty() && profile.network !in supported) {
            return@withLock BindOutcome.Incompatible
        }
        if (!connections.setWalletProfile(connectionId, profileId)) {
            return@withLock BindOutcome.Gone
        }
        if (connection.mode != ConnectionMode.Direct) return@withLock BindOutcome.Bound
        if (!connection.usable) return@withLock BindOutcome.PublicationFailed
        when (val cancelled = publishOne(connectionId)) {
            null -> BindOutcome.PublicationFailed
            else -> BindOutcome.Published(cancelled)
        }
    }

    /**
     * Where [connectionId] stands (SEE-174). It is computed from what is held now, which is why the
     * wallet calls read it again inside the lock rather than trusting a value a screen captured.
     */
    fun readiness(connectionId: String): WalletReadiness =
        walletReadiness(
            connections.connection(connectionId),
            held,
            _publications.value[connectionId],
        )

    /**
     * [readiness] for every connection, whenever a connection, a profile or a publication moves.
     */
    fun readinessByConnection(): Flow<Map<String, WalletReadiness>> =
        combine(connections.connections, _profiles, _publications) { list, _, published ->
            list.associate { it.id to walletReadiness(it, held, published[it.id]) }
        }

    /**
     * The wallet [connectionId] signs with, when it is ready to — and null otherwise, which every
     * review reads as "no wallet" and refuses to approve on. There is no fallback: a connection
     * whose own profile isn't ready has no wallet, whatever else is saved.
     */
    fun walletFor(connectionId: String): SelectedWallet? =
        (readiness(connectionId) as? WalletReadiness.Ready)?.profile?.selected()

    /**
     * The profile a restricted feed proves its reader with (SEE-156, SEE-174): the connection's
     * own, whatever the server's networks. The proof is a message signature over the address, which
     * no network enters, so a feed that executes nothing can still be read — and nothing is ever
     * signed with another connection's wallet.
     */
    fun accessWalletFor(connectionId: String): SelectedWallet? =
        connections
            .connection(connectionId)
            ?.walletProfileId
            ?.let(held::profile)
            ?.takeIf { it.authorized }
            ?.selected()

    /**
     * The profile that owns [address] on [network] — the original owner of a position or a
     * transaction, which a follow-up has to use and nothing else may stand in for (SEE-172,
     * SEE-174). Null when no authorized profile is that account.
     */
    fun ownerProfile(address: String, network: WalletNetwork): SelectedWallet? =
        held.profiles
            .firstOrNull { it.address == address && it.network == network && it.authorized }
            ?.selected()

    /**
     * Asks the wallet to sign exactly [message] with the profile the owner reviewed. When it was
     * reviewed for [connectionId], that connection must still name the same profile and be ready,
     * checked here inside the lock — after any wait for another wallet interaction, not before it.
     * A profile that changed, went, or was rebound is reported without asking the wallet anything.
     */
    suspend fun sign(
        message: ByteString,
        reviewed: SelectedWallet,
        connectionId: String? = null,
    ): SignResult = lock.withLock {
        val (profile, authorization) =
            when (val use = usable(reviewed, connectionId)) {
                is Use.Refused ->
                    return@withLock when (use.why) {
                        Refusal.NotConnected -> SignResult.NotConnected
                        Refusal.Changed -> SignResult.Changed
                        Refusal.Expired -> SignResult.AuthorizationExpired
                    }
                is Use.Granted -> use.profile to use.authorization
            }
        val answer =
            adapter.signMessage(
                message,
                profile.selected(),
                authorization.token,
                authorization.route,
            )
        // The wallet refused the authorization — every profile using it needs reconnecting — or no
        // longer authorizes this account, which is this profile's alone. Nothing else changes: no
        // other profile, and no connection's binding (SEE-84, SEE-174).
        when (answer.result) {
            SignResult.AuthorizationExpired -> expire(authorization.id)
            SignResult.Changed -> expireProfile(profile.id)
            else -> keepRefreshed(authorization, answer.authToken, answer.uriBase)
        }
        answer.result
    }

    /**
     * Asks the wallet to sign exactly [transaction] and send it, with the profile the owner
     * reviewed, under the same checks [sign] makes (docs/guides/transfers.md). It is called only
     * after the owner approved this exact transaction and, for a direct request, the sidecar
     * accepted the approval.
     */
    suspend fun signAndSend(
        transaction: ByteString,
        reviewed: SelectedWallet,
        connectionId: String? = null,
    ): SendResult = lock.withLock { send(transaction, reviewed, connectionId) }

    /**
     * Runs [block] holding the one wallet lock, and lets it reach the wallet through the
     * [WalletSession] it is handed.
     *
     * A transfer needs this because the lock is where the waiting happens: another wallet
     * interaction can hold it for as long as the owner is in the wallet app, and a prepared
     * transaction's blockhash window closes while it waits. Approving and checking that the
     * approval can still land have to happen on this side of that wait, not before it, or the
     * wallet is handed bytes that can no longer be included (docs/guides/transfers.md).
     */
    suspend fun <T> withWallet(block: suspend (WalletSession) -> T): T = lock.withLock {
        block(session)
    }

    private val session =
        object : WalletSession {
            override suspend fun signAndSend(
                transaction: ByteString,
                reviewed: SelectedWallet,
                connectionId: String?,
            ): SendResult = send(transaction, reviewed, connectionId)
        }

    // The body of signAndSend, with the lock already held.
    private suspend fun send(
        transaction: ByteString,
        reviewed: SelectedWallet,
        connectionId: String?,
    ): SendResult {
        val (profile, authorization) =
            when (val use = usable(reviewed, connectionId)) {
                is Use.Refused ->
                    return when (use.why) {
                        Refusal.NotConnected -> SendResult.NotConnected
                        Refusal.Changed -> SendResult.Changed
                        Refusal.Expired -> SendResult.AuthorizationExpired
                    }
                is Use.Granted -> use.profile to use.authorization
            }
        val answer =
            adapter.signAndSendTransaction(
                transaction,
                profile.selected(),
                authorization.token,
                authorization.route,
            )
        // A wallet reauthorizes this app before it sends, just as it does before it signs, and the
        // replacement it hands back has to survive the transfer — sent, declined, or with an
        // outcome nobody knows (SEE-84). Storing it can only replace the token: it never touches
        // what the wallet did with the transaction, and the wallet is not asked anything again.
        when (answer.result) {
            SendResult.AuthorizationExpired -> expire(authorization.id)
            SendResult.Changed -> expireProfile(profile.id)
            else -> keepRefreshed(authorization, answer.authToken, answer.uriBase)
        }
        return answer.result
    }

    private sealed interface Use {
        data class Granted(val profile: WalletProfile, val authorization: StoredAuthorization) : Use

        data class Refused(val why: Refusal) : Use
    }

    private enum class Refusal {
        NotConnected,
        Changed,
        Expired,
    }

    /**
     * The profile and authorization a signing for [reviewed] would use, or why there is none.
     * Called under the lock, so what it checks is what the wallet is then asked with.
     */
    private fun usable(reviewed: SelectedWallet, connectionId: String?): Use {
        val profile =
            when (val id = reviewed.profileId) {
                // A reviewed profile that is gone was removed while the review was open.
                null ->
                    connectionId
                        ?.let { connections.connection(it)?.walletProfileId }
                        ?.let(held::profile) ?: return Use.Refused(Refusal.NotConnected)
                else -> held.profile(id) ?: return Use.Refused(Refusal.Changed)
            }
        if (profile.address != reviewed.address || profile.network != reviewed.network) {
            return Use.Refused(Refusal.Changed)
        }
        if (connectionId != null) {
            val readiness = readiness(connectionId)
            if (readiness.profile?.id != profile.id) return Use.Refused(Refusal.Changed)
            if (readiness is WalletReadiness.NeedsReconnect) return Use.Refused(Refusal.Expired)
            if (readiness !is WalletReadiness.Ready) return Use.Refused(Refusal.Changed)
        }
        if (!profile.authorized) return Use.Refused(Refusal.Expired)
        val authorization =
            held.authorization(profile.authorizationId) ?: return Use.Refused(Refusal.NotConnected)
        return Use.Granted(profile, authorization)
    }

    /** Every profile using [authorizationId] needs reconnecting; nothing else changes. */
    private suspend fun expire(authorizationId: String) {
        commit(
            held.copy(
                profiles =
                    held.profiles.map {
                        if (it.authorizationId == authorizationId) it.copy(authorized = false)
                        else it
                    }
            ),
            strict = false,
        )
    }

    private suspend fun expireProfile(profileId: String) {
        commit(
            held.copy(
                profiles =
                    held.profiles.map {
                        if (it.id == profileId) it.copy(authorized = false) else it
                    }
            ),
            strict = false,
        )
    }

    /**
     * Replaces the stored authorization's token when the wallet handed back a new one, and its
     * route when the wallet said where it now lives. Every profile sharing it shares the
     * replacement, because it is the same grant. A wallet that reported neither has said nothing.
     *
     * If this phone can't store it, the wallet's answer still stands: nothing is asked of the
     * wallet again, the outcome that was reported is reported, and the next operation is refused
     * with the old authorization, after which the owner reconnects — which is what an expired one
     * does anyway.
     */
    private suspend fun keepRefreshed(
        authorization: StoredAuthorization,
        refreshed: String?,
        uriBase: String?,
    ) {
        val token = refreshed ?: authorization.token
        val route = authorization.route.withReported(uriBase)
        if (token == authorization.token && route == authorization.route) return
        commit(
            held.copy(
                authorizations =
                    held.authorizations.map {
                        if (it.id == authorization.id) it.copy(token = token, route = route) else it
                    }
            ),
            strict = false,
        )
    }

    /**
     * Writes [next], dropping authorizations nothing uses, and makes it what is held. A [strict]
     * write is the owner's own change, and a failure to store it is reported with nothing changed;
     * any other keeps the change in memory for this process, as the single session always did.
     */
    private suspend fun commit(next: WalletProfiles, strict: Boolean) {
        val used =
            next.authorizations.filter { held ->
                next.profiles.any { it.authorizationId == held.id }
            }
        val state = next.copy(authorizations = used)
        try {
            withContext(io) { store.putProfiles(state) }
        } catch (e: GeneralSecurityException) {
            if (strict) throw WalletStorageException(e)
        } catch (e: IOException) {
            if (strict) throw WalletStorageException(e)
        }
        held = state.routed()
        _profiles.value = held.profiles
    }

    /**
     * Tells every direct server whose connection's binding it hasn't confirmed yet. On the first
     * call after a start that is all of them — with the same binding they already hold, so nothing
     * is cancelled — and afterwards only a connection whose binding changed or failed. Returns the
     * connections that couldn't be told; a later call tries them again.
     */
    suspend fun publish(): List<String> = lock.withLock { publishMissing() }

    /** Publishes to every direct server again, whatever they were told before. */
    suspend fun publishAgain(): List<String> = lock.withLock {
        _publications.value = emptyMap()
        publishMissing()
    }

    private suspend fun publishMissing(): List<String> {
        val failed = mutableListOf<String>()
        for (connection in connections.connections.value.filter { it.usable }) {
            val published = _publications.value[connection.id]
            if (
                published?.state == Publication.State.Published &&
                    published.binding == contentOf(bindingOf(connection))
            ) {
                continue
            }
            if (publishOne(connection.id) == null) failed += connection.id
        }
        return failed
    }

    /**
     * Tells [connectionId]'s server the binding its connection names, and records the answer for
     * that connection and that binding. Returns how many requests the server cancelled, or null
     * when it couldn't be told.
     */
    private suspend fun publishOne(connectionId: String): Int? {
        val connection = connections.connection(connectionId) ?: return null
        val binding = bindingOf(connection)
        val content = contentOf(binding)
        _publications.update {
            it + (connectionId to Publication(content, Publication.State.Pending))
        }
        val cancelled = connections.publishWalletCancelling(connectionId, binding)
        _publications.update {
            it +
                (connectionId to
                    if (cancelled == null) Publication(content, Publication.State.Failed)
                    else Publication(content, Publication.State.Published, cancelled))
        }
        return cancelled
    }

    /**
     * The binding [connection]'s server is told: its own profile's address and network, whether or
     * not that profile's authorization still works — an expired one is reconnected, not unpublished
     * — and "no wallet" when it names none.
     */
    private fun bindingOf(connection: Connection): WalletBinding? =
        connection.walletProfileId?.let(held::profile)?.selected()?.toBinding()

    private companion object {
        const val MAX_PROFILE_LABEL = 64
        const val REMOVED = "this wallet profile was removed"
        const val OTHER_ACCOUNT = "the wallet didn't authorize this profile's account"

        fun contentOf(binding: WalletBinding?): String? = binding?.let {
            "${it.wallet}|${it.network.name}"
        }
    }
}

/**
 * Where [connection] stands with [profiles], given what its server was last told ([publication]).
 * Pure, so every rule is a test of its own.
 */
fun walletReadiness(
    connection: Connection?,
    profiles: WalletProfiles,
    publication: Publication?,
): WalletReadiness {
    if (connection == null || connection.retirement != null) return WalletReadiness.NoConnection
    val id = connection.walletProfileId ?: return WalletReadiness.NoProfile
    val profile = profiles.profile(id) ?: return WalletReadiness.ProfileMissing
    val supported = connection.server.manifest?.supportedNetworks.orEmpty()
    if (supported.isEmpty()) return WalletReadiness.NetworksUnknown(profile)
    if (profile.network !in supported) {
        return WalletReadiness.NetworkUnsupported(profile, supported)
    }
    if (!profile.authorized) return WalletReadiness.NeedsReconnect(profile)
    if (connection.mode == ConnectionMode.Direct) {
        val binding = "${profile.address}|${profile.network.network.name}"
        if (publication?.state != Publication.State.Published || publication.binding != binding) {
            return WalletReadiness.PublicationPending(profile)
        }
    }
    return WalletReadiness.Ready(profile)
}

/**
 * The selection as the protocol carries it. `bound_at` is the sidecar's; the phone doesn't set it.
 */
fun SelectedWallet.toBinding(): WalletBinding = walletBinding {
    wallet = address
    network = this@toBinding.network.network
}
