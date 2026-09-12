package io.github.brrenat.seekervault.wallet

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.request.v1.WalletBinding
import io.github.brrenat.seekervault.request.v1.walletBinding
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
     * The connections this binding has already reached, so opening the app re-sends only what's
     * missing.
     */
    private val published = mutableSetOf<String>()

    /** Reads the stored selection. A selection whose authorization is gone is dropped. */
    suspend fun load() = lock.withLock {
        val stored = withContext(io) { store.selected() }
        val usable = stored != null && withContext(io) { store.authorization() } != null
        if (stored != null && !usable) withContext(io) { store.clear() }
        _wallet.value = if (usable) stored else null
    }

    /**
     * Asks the wallet for the account to use on [network] and, when the owner picks one, stores it
     * and publishes it. Any other outcome leaves the stored wallet as it was, except an
     * authorization the wallet refused, which is forgotten.
     */
    suspend fun connect(network: WalletNetwork): WalletResult = lock.withLock {
        val authorization = withContext(io) { store.authorization() }
        val result = adapter.connect(network, authorization)
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
                    withContext(io) { store.put(selected, result.authToken) }
                } catch (e: GeneralSecurityException) {
                    withContext(io) { store.clear() }
                    throw WalletStorageException(e)
                } catch (e: IOException) {
                    withContext(io) { store.clear() }
                    throw WalletStorageException(e)
                }
                setWallet(selected)
            }
            WalletResult.AuthorizationExpired -> {
                // The wallet no longer honours what this phone stored: start afresh next time.
                withContext(io) { store.clear() }
                setWallet(null)
            }
            else -> Unit
        }
        result
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
        val authorization =
            withContext(io) { store.authorization() } ?: return@withLock SignResult.NotConnected
        val answer = adapter.signMessage(message, selected, authorization)
        if (answer.result == SignResult.AuthorizationExpired) {
            withContext(io) { store.clear() }
            setWallet(null)
            return@withLock answer.result
        }
        // The wallet may replace this phone's authorization while it signs, and the replacement is
        // what the next signing has to use, whatever the wallet then did with the message: a
        // declined signature carries a perfectly good one. The selection stays exactly as it is,
        // so the wallet, address, and network the owner reviewed don't change, and the token goes
        // no further than [store].
        keepRefreshed(selected, answer.authToken, offered = authorization)
        answer.result
    }

    /**
     * Replaces the stored authorization when the wallet handed back a new one. If this phone can't
     * store it, the wallet's answer still stands: the next signing is refused with the old
     * authorization, and the owner connects the wallet again, which is what an expired one does
     * anyway.
     */
    private suspend fun keepRefreshed(
        selected: SelectedWallet,
        refreshed: String?,
        offered: String,
    ) {
        if (refreshed == null || refreshed == offered) return
        try {
            withContext(io) { store.put(selected, refreshed) }
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
        val authorization =
            withContext(io) { store.authorization() } ?: return SendResult.NotConnected
        val result = adapter.signAndSendTransaction(transaction, selected, authorization)
        if (result == SendResult.AuthorizationExpired) {
            withContext(io) { store.clear() }
            setWallet(null)
        }
        return result
    }

    /** Tells the wallet, forgets the selection and its authorization, and publishes "no wallet". */
    suspend fun disconnect() = lock.withLock {
        withContext(io) { store.authorization() }?.let { adapter.disconnect(it) }
        withContext(io) { store.clear() }
        setWallet(null)
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

    private suspend fun setWallet(selected: SelectedWallet?) {
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
