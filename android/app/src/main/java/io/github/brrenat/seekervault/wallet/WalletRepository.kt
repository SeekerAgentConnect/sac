package io.github.brrenat.seekervault.wallet

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.request.v1.WalletBinding
import io.github.brrenat.seekervault.request.v1.walletBinding
import io.github.brrenat.seekervault.wallet.storage.StoredSession
import io.github.brrenat.seekervault.wallet.storage.WalletStore
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

/** Storing the wallet failed on this phone; nothing was saved, and nothing was published. */
class WalletStorageException(cause: Throwable) : Exception(cause.message, cause)

/**
 * The wallet, for a caller that already holds the one wallet lock through
 * [WalletRepository.withWallet]. It carries no state of its own: it is the permission to reach the
 * wallet, handed out once per lock.
 */
interface WalletSession {
    /** The same call as [WalletRepository.signAndSend], with the lock already held. */
    suspend fun signAndSend(transaction: ByteString, reviewed: SelectedWallet): SendResult
}

/**
 * The wallet the owner selected, and what every paired sidecar knows about it
 * (docs/guides/wallet-setup.md).
 * - Connecting asks the installed wallet through [adapter], stores the selection, and publishes it
 *   to every connection that can be reached.
 * - Disconnecting tells the wallet, forgets the authorization, and publishes "no wallet" the same
 *   way. A sidecar then answers `vault_get_address` with WALLET_NOT_CONNECTED.
 * - Signing asks the wallet for a signature over exact bytes, and only for the selection the owner
 *   reviewed (SAW-016). It is never called before the owner approves.
 * - Signing and sending hands the wallet an approved transaction, which the wallet signs and
 *   submits itself (SAW-021). This app reaches no chain, and each of these is one interaction.
 * - The address is public and goes to the sidecars; the wallet's authorization token stays in
 *   [store] and goes nowhere.
 *
 * Storage runs on [io]; one wallet operation runs at a time.
 */
class WalletRepository(
    private val store: WalletStore,
    private val adapter: WalletAdapter,
    private val connections: ConnectionRepository,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private val _wallet = MutableStateFlow<SelectedWallet?>(null)
    val wallet: StateFlow<SelectedWallet?> = _wallet.asStateFlow()

    /**
     * The name of the wallet *app* the selection belongs to, as `PackageManager` gave it, or null
     * when this phone was never told which app answered (SEE-159). It is what a screen shows so an
     * account's own label can't be mistaken for the wallet it lives in; it is display only, and it
     * never decides anything.
     */
    private val _walletApp = MutableStateFlow<String?>(null)
    val walletApp: StateFlow<String?> = _walletApp.asStateFlow()

    /**
     * The connections this binding has already reached, so opening the app re-sends only what's
     * missing.
     */
    private val published = mutableSetOf<String>()

    /**
     * Reads the stored session. Anything but a whole one — a selection whose authorization is gone,
     * an authorization this phone can no longer decrypt, half of the storage an older build left
     * behind — is not a wallet, and is forgotten rather than half-used (SEE-84).
     */
    suspend fun load() = lock.withLock {
        val stored = withContext(io) { store.session() }
        if (stored == null) withContext(io) { store.clear() }
        _walletApp.value = stored?.route?.appLabel
        _wallet.value = stored?.wallet
    }

    /**
     * The wallet apps installed on this phone, so the owner can be offered one without Android's
     * chooser (SEE-159). It opens nothing and takes no wallet lock: it is a question for the
     * system, not for a wallet.
     */
    suspend fun installedWallets(): List<InstalledWallet> = adapter.installed()

    /**
     * Asks the wallet for the account to use on [network] and, when the owner picks one, stores it
     * and publishes it. Any other outcome leaves the stored wallet as it was, except an
     * authorization the wallet refused, which is forgotten.
     *
     * [app] is the wallet app the owner chose here, from [installedWallets]; it is stored with the
     * account, and every later signing opens that app and no other (SEE-159). Without one, the app
     * the owner already had is opened again.
     */
    suspend fun connect(network: WalletNetwork, app: InstalledWallet? = null): WalletResult =
        lock.withLock {
            val stored = withContext(io) { store.session() }
            val result = adapter.connect(network, stored?.authToken, aim(app, stored?.route))
            connected(network, result)
        }

    /**
     * Where a connection is aimed. [app] is the wallet the owner picked on this phone, and picking
     * one replaces whatever was stored — including the association URI, which belonged to the app
     * they are leaving. Without a pick, the wallet they already had is asked again, and failing
     * that a phone with exactly one wallet app on it needs nobody to choose: there is one answer,
     * and the system gave it.
     */
    private suspend fun aim(app: InstalledWallet?, stored: WalletRouting?): WalletRouting? {
        if (app != null) return WalletRouting(packageName = app.packageName, appLabel = app.label)
        if (stored != null && stored.targeted) return stored
        return adapter.installed().singleOrNull()?.let {
            WalletRouting(packageName = it.packageName, appLabel = it.label)
        }
    }

    // The body of connect, with the lock already held.
    private suspend fun connected(network: WalletNetwork, result: WalletResult): WalletResult {
        when (result) {
            is WalletResult.Connected -> {
                val selected =
                    SelectedWallet(
                        address = result.account.address,
                        network = network,
                        label = result.account.label,
                        selectedAt = now(),
                        // The wallet lists the chains it serves for the account; an empty list
                        // means it said nothing, which isn't a contradiction.
                        networkConfirmed =
                            result.account.chains.isEmpty() ||
                                result.account.chains.contains(network.chain),
                    )
                try {
                    withContext(io) { store.put(selected, result.authToken, result.route) }
                } catch (e: GeneralSecurityException) {
                    withContext(io) { store.clear() }
                    throw WalletStorageException(e)
                } catch (e: IOException) {
                    withContext(io) { store.clear() }
                    throw WalletStorageException(e)
                }
                setWallet(selected, result.route.appLabel)
            }
            WalletResult.AuthorizationExpired -> {
                // The wallet no longer honours what this phone stored: start afresh next time.
                withContext(io) { store.clear() }
                setWallet(null, null)
            }
            else -> Unit
        }
        return result
    }

    /**
     * Asks the wallet to sign exactly [message] with the wallet the owner selected, which must
     * still be [reviewed]: the one they saw when they approved. A selection that has changed, or
     * gone, is reported without asking the wallet anything, so nothing is ever signed for a wallet
     * or network the owner didn't review. An authorization the wallet refuses is forgotten, the
     * same way connecting does, and one it replaces is kept.
     */
    suspend fun sign(message: ByteString, reviewed: SelectedWallet): SignResult = lock.withLock {
        val selected = _wallet.value ?: return@withLock SignResult.NotConnected
        if (selected.address != reviewed.address || selected.network != reviewed.network) {
            return@withLock SignResult.Changed
        }
        val stored = held(selected) ?: return@withLock SignResult.NotConnected
        val answer = adapter.signMessage(message, selected, stored.authToken, stored.route)
        // A refused authorization, and a wallet that no longer authorizes the reviewed account,
        // both leave this phone with nothing it may sign with: the session is forgotten, and the
        // owner connects the wallet again and reviews the request afresh (SEE-84).
        if (
            answer.result == SignResult.AuthorizationExpired || answer.result == SignResult.Changed
        ) {
            forget()
            return@withLock answer.result
        }
        // The wallet may replace this phone's authorization while it signs, and the replacement is
        // what the next signing has to use, whatever the wallet then did with the message: a
        // declined signature carries a perfectly good one. The selection stays exactly as it is,
        // so the wallet, address, and network the owner reviewed don't change, and the token goes
        // no further than [store].
        keepRefreshed(selected, stored, answer.authToken, answer.uriBase)
        answer.result
    }

    /**
     * The stored session, when it is the one [selected] names. A record whose account or network
     * isn't the selection this app is holding is not this wallet's, and nothing is signed with it:
     * that is the pair the single stored record exists to keep true (SEE-84).
     */
    private suspend fun held(selected: SelectedWallet): StoredSession? =
        withContext(io) { store.session() }
            ?.takeIf {
                it.wallet.address == selected.address && it.wallet.network == selected.network
            }

    /** Forgets the wallet on this phone, and tells every sidecar there is none. */
    private suspend fun forget() {
        withContext(io) { store.clear() }
        setWallet(null, null)
    }

    /**
     * Replaces the stored authorization when the wallet handed back a new one, and the route when
     * the wallet said where it now lives, as one record with the selection they belong to. A wallet
     * that reported neither has said nothing, and nothing is written.
     *
     * If this phone can't store it, the wallet's answer still stands: nothing is asked of the
     * wallet again, the outcome that was reported is reported, and the next operation is refused
     * with the old authorization, after which the owner connects the wallet again — which is what
     * an expired one does anyway.
     */
    private suspend fun keepRefreshed(
        selected: SelectedWallet,
        stored: StoredSession,
        refreshed: String?,
        uriBase: String?,
    ) {
        val token = refreshed ?: stored.authToken
        val route = stored.route.withReported(uriBase)
        if (token == stored.authToken && route == stored.route) return
        try {
            withContext(io) { store.put(selected, token, route) }
        } catch (e: GeneralSecurityException) {
            // Kept as it was; see above.
        } catch (e: IOException) {
            // Kept as it was; see above.
        }
    }

    /**
     * Asks the wallet to sign exactly [transaction] and send it, with the wallet the owner
     * selected, which must still be [reviewed] (docs/guides/transfers.md). The checks are the same
     * ones signing takes, and for the same reason: nothing is ever put in front of the wallet for a
     * selection the owner didn't review. It is called only after the owner approved this exact
     * transaction and the sidecar accepted the approval.
     *
     * It takes the same lock as every other wallet call, so only one wallet interaction runs at a
     * time however many screens ask for one.
     */
    suspend fun signAndSend(transaction: ByteString, reviewed: SelectedWallet): SendResult =
        lock.withLock {
            send(transaction, reviewed)
        }

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
            ): SendResult = send(transaction, reviewed)
        }

    // The body of signAndSend, with the lock already held.
    private suspend fun send(transaction: ByteString, reviewed: SelectedWallet): SendResult {
        val selected = _wallet.value ?: return SendResult.NotConnected
        if (selected.address != reviewed.address || selected.network != reviewed.network) {
            return SendResult.Changed
        }
        val stored = held(selected) ?: return SendResult.NotConnected
        val answer =
            adapter.signAndSendTransaction(transaction, selected, stored.authToken, stored.route)
        if (
            answer.result == SendResult.AuthorizationExpired || answer.result == SendResult.Changed
        ) {
            forget()
            return answer.result
        }
        // A wallet reauthorizes this app before it sends, just as it does before it signs, and the
        // replacement it hands back has to survive the transfer — sent, declined, or with an
        // outcome nobody knows (SEE-84). Storing it can only replace the token: it never touches
        // what the wallet did with the transaction, and the wallet is not asked anything again.
        keepRefreshed(selected, stored, answer.authToken, answer.uriBase)
        return answer.result
    }

    /** Tells the wallet, forgets the selection and its authorization, and publishes "no wallet". */
    suspend fun disconnect() = lock.withLock {
        withContext(io) { store.session() }
            ?.let { adapter.disconnect(it.wallet, it.authToken, it.route) }
        forget()
    }

    /**
     * Publishes the current binding to the connections that haven't got it yet, which is every
     * connection the first time and a newly paired one afterwards. Returns the connections the
     * sidecar couldn't be told about; a later call tries them again.
     */
    suspend fun publish(): List<String> = lock.withLock { publishMissing() }

    /** Publishes to every connection again, whatever they were told before. */
    suspend fun publishAgain(): List<String> = lock.withLock {
        published.clear()
        publishMissing()
    }

    private suspend fun setWallet(selected: SelectedWallet?, app: String?) {
        _walletApp.value = app
        _wallet.value = selected
        published.clear()
        publishMissing()
    }

    private suspend fun publishMissing(): List<String> {
        val binding = _wallet.value?.toBinding()
        val failed = mutableListOf<String>()
        for (connection in connections.connections.value.filter { it.usable }) {
            if (connection.id in published) continue
            if (connections.publishWallet(connection.id, binding)) published += connection.id
            else failed += connection.id
        }
        return failed
    }
}

/**
 * The selection as the protocol carries it. `bound_at` is the sidecar's; the phone doesn't set it.
 */
fun SelectedWallet.toBinding(): WalletBinding = walletBinding {
    wallet = address
    network = this@toBinding.network.network
}
