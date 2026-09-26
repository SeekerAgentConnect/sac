package io.github.brrenat.seekervault.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why connecting a wallet didn't work. */
enum class WalletProblem {
    /** No wallet app that speaks Mobile Wallet Adapter is installed. */
    NoWallet,
    /** The owner declined in the wallet. */
    Declined,
    /** The wallet refused the authorization this phone had stored. */
    AuthorizationExpired,
    /** The wallet doesn't serve the network the owner picked. */
    NetworkUnsupported,
    /** This phone couldn't store the wallet's authorization. */
    Storage,
    Failed,
}

/** Everything the Wallet screen shows. */
data class WalletUiState(
    val wallet: SelectedWallet? = null,
    /**
     * The name of the wallet *app* the selection belongs to, as the system gives it, or null when
     * this phone was never told which app answered (SEE-159). It is what the screen calls the
     * wallet, so an account's own label can't be read as the app that holds it.
     */
    val walletApp: String? = null,
    /**
     * The wallet apps installed on this phone, or null until they have been asked for. With more
     * than one the owner picks which to connect, here, instead of Android asking them at every
     * approval afterwards (SEE-159).
     */
    val apps: List<InstalledWallet>? = null,
    /** The app the owner picked to connect. Only ever needed when several are installed. */
    val chosen: InstalledWallet? = null,
    /** False until the stored wallet has been read. */
    val loaded: Boolean = false,
    /** The network the owner is about to connect on; the connected wallet's, once there is one. */
    val network: WalletNetwork = WalletNetwork.Mainnet,
    val connecting: Boolean = false,
    val disconnecting: Boolean = false,
    val problem: WalletProblem? = null,
    /** What the wallet said, shown under [WalletProblem.Failed] only. */
    val detail: String? = null,
    /** Connections whose sidecar couldn't be told; the owner can publish again. */
    val unpublished: List<Connection> = emptyList(),
    /** Connections this phone could publish to at all. */
    val connections: List<Connection> = emptyList(),
) {
    val busy: Boolean
        get() = connecting || disconnecting

    /**
     * Whether connecting can go ahead. The owner picks the wallet app when this phone has several,
     * because connecting without one is what leaves Android asking them at every approval
     * afterwards; with one installed, or none this phone could list, there is nothing to decide. A
     * list that hasn't come back yet holds nothing up — the owner's own button is never disabled on
     * the strength of a read this app hasn't finished.
     */
    val canConnect: Boolean
        get() = apps == null || apps.size <= 1 || chosen != null
}

/**
 * State and actions of the Wallet screen. It connects the wallet the owner already has, shows the
 * address and network it selected, and publishes that to every paired sidecar. It never creates a
 * wallet, never signs, and never asks for a key.
 */
class WalletViewModel(
    private val repository: WalletRepository,
    connections: ConnectionRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(WalletUiState())
    val state: StateFlow<WalletUiState> = _state.asStateFlow()

    // Whether the app has left the foreground since it last published on opening.
    private var hidden = false

    // The usable connections this screen has already seen, so a new one can be told at once.
    private var known = emptySet<String>()

    init {
        viewModelScope.launch {
            repository.wallet.collect { selected ->
                _state.update {
                    it.copy(wallet = selected, network = selected?.network ?: it.network)
                }
            }
        }
        viewModelScope.launch {
            repository.walletApp.collect { app -> _state.update { it.copy(walletApp = app) } }
        }
        viewModelScope.launch { readInstalled() }
        viewModelScope.launch {
            connections.connections.collect { list ->
                _state.update { it.copy(connections = list) }
                // The owner pairs a sidecar without leaving the app, so waiting for the next
                // return to the foreground would leave it answering WALLET_NOT_CONNECTED while
                // this screen says every connection was told. Nothing is published before the
                // stored wallet has been read; opening the app publishes to all of them anyway.
                val usable = list.filter { it.usable }.map { it.id }.toSet()
                val fresh = usable - known
                known = usable
                if (fresh.isNotEmpty() && _state.value.loaded) publish()
            }
        }
        viewModelScope.launch {
            repository.load()
            _state.update { it.copy(loaded = true) }
            publish()
        }
    }

    /** The app left the foreground. A rotation doesn't count. */
    fun onAppHidden() {
        hidden = true
    }

    /** The app is back in the foreground: a connection paired meanwhile learns the wallet now. */
    fun onAppVisible() {
        if (!hidden) return
        hidden = false
        viewModelScope.launch {
            // A wallet app may have been installed or removed while the app was away, and what the
            // owner can be offered is only ever what the system says is there now.
            readInstalled()
            publish()
        }
    }

    /** The wallet app the owner will connect. Picking one clears any earlier complaint. */
    fun chooseWalletApp(packageName: String) {
        val state = _state.value
        if (state.wallet != null || state.busy) return
        val app = state.apps?.firstOrNull { it.packageName == packageName } ?: return
        _state.update { it.copy(chosen = app, problem = null, detail = null) }
    }

    private suspend fun readInstalled() {
        val apps = repository.installedWallets()
        _state.update { state ->
            // A pick the system no longer lists is no pick at all.
            state.copy(apps = apps, chosen = state.chosen?.takeIf { it in apps })
        }
    }

    /** The network the owner will connect on. It can't change while a wallet is connected. */
    fun chooseNetwork(network: WalletNetwork) {
        if (_state.value.wallet != null || _state.value.busy) return
        _state.update { it.copy(network = network, problem = null, detail = null) }
    }

    /** Asks the wallet the owner picked for an account on the chosen network. */
    fun connect() {
        val state = _state.value
        if (state.busy || !state.canConnect) return
        _state.update { it.copy(connecting = true, problem = null, detail = null) }
        viewModelScope.launch {
            val problem =
                try {
                    problemOf(repository.connect(state.network, state.chosen))
                } catch (e: WalletStorageException) {
                    WalletProblem.Storage to null
                }
            _state.update {
                it.copy(connecting = false, problem = problem.first, detail = problem.second)
            }
            publish()
        }
    }

    /** Forgets the wallet here and at the wallet app, and tells every sidecar. */
    fun disconnect() {
        if (_state.value.busy) return
        _state.update { it.copy(disconnecting = true, problem = null, detail = null) }
        viewModelScope.launch {
            try {
                repository.disconnect()
            } finally {
                _state.update { it.copy(disconnecting = false) }
            }
            publish()
        }
    }

    /** Tells every connection again, after one couldn't be reached. */
    fun publishAgain() {
        viewModelScope.launch { report(repository.publishAgain()) }
    }

    fun problemShown() = _state.update { it.copy(problem = null, detail = null) }

    private suspend fun publish() = report(repository.publish())

    private fun report(failed: List<String>) = _state.update { state ->
        state.copy(unpublished = state.connections.filter { it.id in failed })
    }

    private companion object {
        fun problemOf(result: WalletResult): Pair<WalletProblem?, String?> =
            when (result) {
                is WalletResult.Connected -> null to null
                WalletResult.NoWallet -> WalletProblem.NoWallet to null
                WalletResult.Declined -> WalletProblem.Declined to null
                WalletResult.AuthorizationExpired -> WalletProblem.AuthorizationExpired to null
                WalletResult.NetworkUnsupported -> WalletProblem.NetworkUnsupported to null
                is WalletResult.Failed -> WalletProblem.Failed to result.message
            }
    }
}
