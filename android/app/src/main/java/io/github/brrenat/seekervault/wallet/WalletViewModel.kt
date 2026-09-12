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
        viewModelScope.launch { publish() }
    }

    /** The network the owner will connect on. It can't change while a wallet is connected. */
    fun chooseNetwork(network: WalletNetwork) {
        if (_state.value.wallet != null || _state.value.busy) return
        _state.update { it.copy(network = network, problem = null, detail = null) }
    }

    /** Asks the wallet for an account on the chosen network. */
    fun connect() {
        val state = _state.value
        if (state.busy) return
        _state.update { it.copy(connecting = true, problem = null, detail = null) }
        viewModelScope.launch {
            val problem =
                try {
                    problemOf(repository.connect(state.network))
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
