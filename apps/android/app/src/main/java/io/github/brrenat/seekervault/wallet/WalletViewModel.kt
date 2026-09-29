package io.github.brrenat.seekervault.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionWallets
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

/** What the last binding of one connection came to, for that connection's screen to say. */
data class BindingNotice(val connectionId: String, val outcome: BindOutcome)

/** Everything the Wallets screen, and a connection's wallet picker, show. */
data class WalletUiState(
    /** Every saved wallet profile (SEE-174). */
    val profiles: List<WalletProfile> = emptyList(),
    /** Where each connection stands with its own profile, by connection ID. */
    val readiness: Map<String, WalletReadiness> = emptyMap(),
    /**
     * The wallet apps installed on this phone, or null until they have been asked for. With more
     * than one the owner picks which to add from, here, instead of Android asking them at every
     * approval afterwards (SEE-159).
     */
    val apps: List<InstalledWallet>? = null,
    /** The app the owner picked to add from. Only ever needed when several are installed. */
    val chosen: InstalledWallet? = null,
    /** False until the stored profiles have been read. */
    val loaded: Boolean = false,
    /** The network the owner is about to add a wallet on. */
    val network: WalletNetwork = WalletNetwork.Mainnet,
    val connecting: Boolean = false,
    /** A profile being reconnected, renamed or removed. */
    val working: String? = null,
    val problem: WalletProblem? = null,
    /** What the wallet said, shown under [WalletProblem.Failed] only. */
    val detail: String? = null,
    /** Direct connections whose server couldn't be told its wallet; the owner can try again. */
    val unpublished: List<Connection> = emptyList(),
    /** Every connection, for the profiles' impact summaries. */
    val connections: List<Connection> = emptyList(),
    /** The profiles the last addition saved, which a connection's picker offers first. */
    val added: List<String> = emptyList(),
    /** What binding a connection last came to. */
    val binding: BindingNotice? = null,
    /** The profile the owner asked to remove, while they confirm. */
    val removing: String? = null,
    /** The profile the owner is renaming. */
    val renaming: String? = null,
    /**
     * A connection just added, whose wallet the owner chooses next (SEE-174). Its detail sheet
     * opens the picker while this names it; choosing or cancelling ends it.
     */
    val setup: String? = null,
) {
    val busy: Boolean
        get() = connecting || working != null

    /**
     * Whether adding can go ahead. The owner picks the wallet app when this phone has several,
     * because adding without one is what leaves Android asking them at every approval afterwards;
     * with one installed, or none this phone could list, there is nothing to decide.
     */
    val canConnect: Boolean
        get() = apps == null || apps.size <= 1 || chosen != null

    /** The wallet [connectionId] signs with, when it is ready — never another's. */
    fun walletFor(connectionId: String): SelectedWallet? =
        (readiness[connectionId] as? WalletReadiness.Ready)?.profile?.selected()

    /** The connections bound to [profileId]. */
    fun usersOf(profileId: String): List<Connection> = connections.filter {
        it.walletProfileId == profileId
    }
}

/**
 * State and actions of the Wallets screen and of a connection's wallet picker (SEE-174). It adds,
 * renames, reconnects and removes wallet profiles, and binds one connection at a time to one of
 * them. It never creates a wallet, never signs, and never asks for a key.
 */
class WalletViewModel(
    private val repository: WalletRepository,
    connections: ConnectionWallets,
) : ViewModel() {
    private val _state = MutableStateFlow(WalletUiState())
    val state: StateFlow<WalletUiState> = _state.asStateFlow()

    // Whether the app has left the foreground since it last published on opening.
    private var hidden = false

    // The usable connections this screen has already seen, so a new one can be told at once.
    private var known = emptySet<String>()

    init {
        viewModelScope.launch {
            repository.profiles.collect { profiles ->
                _state.update { it.copy(profiles = profiles) }
            }
        }
        viewModelScope.launch {
            repository.readinessByConnection().collect { readiness ->
                _state.update { it.copy(readiness = readiness) }
            }
        }
        viewModelScope.launch { readInstalled() }
        viewModelScope.launch {
            connections.connections.collect { list ->
                _state.update { it.copy(connections = list) }
                // A direct server paired without leaving the app is told its binding — which is
                // "no wallet" until the owner chooses one — rather than waiting for the next
                // return to the foreground. Nothing is published before the profiles are read.
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

    /** The app is back in the foreground: a binding that didn't reach its server is sent now. */
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

    /** The wallet app the owner will add from. Picking one clears any earlier complaint. */
    fun chooseWalletApp(packageName: String) {
        val state = _state.value
        if (state.busy) return
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

    /** The network the owner will add a wallet on. */
    fun chooseNetwork(network: WalletNetwork) {
        if (_state.value.busy) return
        _state.update { it.copy(network = network, problem = null, detail = null) }
    }

    /**
     * Asks the wallet for a fresh authorization on [network] — the chosen one by default, or the
     * one a connection's picker asked for — and saves the accounts it authorizes. Cancelling in the
     * wallet changes nothing: no profile, no connection.
     */
    fun connect(network: WalletNetwork = _state.value.network) {
        val state = _state.value
        if (state.busy || !state.canConnect) return
        _state.update {
            it.copy(connecting = true, problem = null, detail = null, added = emptyList())
        }
        viewModelScope.launch {
            val (problem, added) =
                try {
                    val connected = repository.connectProfiles(network, state.chosen)
                    problemOf(connected.result) to connected.profiles.map { it.id }
                } catch (e: WalletStorageException) {
                    (WalletProblem.Storage to null) to emptyList()
                }
            _state.update {
                it.copy(
                    connecting = false,
                    problem = problem.first,
                    detail = problem.second,
                    added = added,
                )
            }
        }
    }

    /** Connects [profileId]'s account again, in its own wallet app. */
    fun reconnect(profileId: String) =
        working(profileId) {
            val connected = repository.reconnect(profileId)
            val problem = problemOf(connected.result)
            _state.update { it.copy(problem = problem.first, detail = problem.second) }
            publish()
        }

    fun startRename(profileId: String) = _state.update { it.copy(renaming = profileId) }

    fun cancelRename() = _state.update { it.copy(renaming = null) }

    fun rename(profileId: String, label: String) =
        working(profileId) {
            repository.rename(profileId, label)
            _state.update { it.copy(renaming = null) }
        }

    /** The owner asked to remove [profileId]; the screen shows who uses it first. */
    fun askToRemove(profileId: String) = _state.update { it.copy(removing = profileId) }

    fun cancelRemove() = _state.update { it.copy(removing = null) }

    /** Removes the profile the owner confirmed, leaving its connections without a wallet. */
    fun confirmRemove() {
        val profileId = _state.value.removing ?: return
        _state.update { it.copy(removing = null) }
        working(profileId) { repository.remove(profileId) }
    }

    /**
     * Binds [connectionId] to [profileId] (SEE-174). [then] runs after a binding that stands — a
     * restricted feed then asks for access with its new wallet.
     */
    fun bind(connectionId: String, profileId: String?, then: () -> Unit = {}) {
        if (_state.value.busy) return
        _state.update { it.copy(working = connectionId, binding = null) }
        viewModelScope.launch {
            try {
                val outcome = repository.bind(connectionId, profileId)
                _state.update { state ->
                    state.copy(
                        binding = BindingNotice(connectionId, outcome),
                        // A server that couldn't be told is named with the others at once, and
                        // one that just heard its binding is no longer one of them.
                        unpublished =
                            when (outcome) {
                                BindOutcome.PublicationFailed ->
                                    (state.unpublished.filterNot { it.id == connectionId } +
                                        state.connections.filter { it.id == connectionId })
                                is BindOutcome.Published ->
                                    state.unpublished.filterNot { it.id == connectionId }
                                else -> state.unpublished
                            },
                    )
                }
                if (outcome != BindOutcome.Incompatible && outcome != BindOutcome.Gone) then()
            } finally {
                _state.update { it.copy(working = null) }
            }
        }
    }

    fun bindingShown() = _state.update { it.copy(binding = null) }

    /** A connection was just added: its wallet is chosen next. */
    fun beginSetup(connectionId: String) = _state.update {
        it.copy(setup = connectionId, added = emptyList(), problem = null)
    }

    /** The owner chose a wallet for the new connection, or put it off. */
    fun endSetup() = _state.update { it.copy(setup = null) }

    /** Tells every direct server again, after one couldn't be reached. */
    fun publishAgain() {
        viewModelScope.launch { report(repository.publishAgain()) }
    }

    fun problemShown() = _state.update { it.copy(problem = null, detail = null) }

    private fun working(id: String, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(working = id, problem = null, detail = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: WalletStorageException) {
                _state.update { it.copy(problem = WalletProblem.Storage) }
            } finally {
                _state.update { it.copy(working = null) }
            }
        }
    }

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
