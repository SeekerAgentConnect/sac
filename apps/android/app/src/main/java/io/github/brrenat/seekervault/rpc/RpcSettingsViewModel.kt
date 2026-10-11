package io.github.brrenat.seekervault.rpc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.brrenat.seekervault.request.v1.Network
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One network's endpoint as the Solana RPC sheet shows it (SEE-184). */
data class RpcNetworkState(
    val network: Network,
    /** Where the network is asked now, or null when nothing is set for it. */
    val endpoint: RpcEndpoint?,
    /** What the owner has typed. Starts as their own setting, or empty. */
    val draft: String = "",
    /** What asking the endpoint in use found; null until asked. */
    val check: RpcCheck? = null,
    /** Why the last save was refused; cleared by the next edit. */
    val refused: RpcCheck? = null,
    val busy: Boolean = false,
) {
    val ownSetting: Boolean
        get() = endpoint?.source == RpcSource.Owner
}

data class RpcSettingsState(val networks: List<RpcNetworkState>)

/**
 * The Solana RPC sheet's state and actions (SEE-184): each network on its own, never an "active"
 * one. Saving checks the endpoint's genesis hash first and changes nothing unless it names the
 * network; resetting goes back to this build's own endpoint. Both apply at once, everywhere in the
 * process, through [SolanaRpc].
 */
class RpcSettingsViewModel(
    private val rpc: SolanaRpc,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            RpcSettingsState(
                RPC_NETWORKS.map { network ->
                    val endpoint = rpc.endpoint(network)
                    RpcNetworkState(
                        network = network,
                        endpoint = endpoint,
                        draft = rpc.settings.value[network].orEmpty(),
                    )
                }
            )
        )
    val state: StateFlow<RpcSettingsState> = _state.asStateFlow()

    /** Asks every network's endpoint which network it serves, all at once. */
    fun refresh() {
        RPC_NETWORKS.forEach(::check)
    }

    fun edit(network: Network, text: String) {
        update(network) { it.copy(draft = text, refused = null) }
    }

    fun save(network: Network) {
        val current = find(network) ?: return
        if (current.busy) return
        update(network) { it.copy(busy = true, refused = null) }
        viewModelScope.launch {
            val result = withContext(io) { rpc.save(network, current.draft) }
            update(network) {
                if (result is RpcCheck.Serves) {
                    it.copy(
                        endpoint = rpc.endpoint(network),
                        draft = rpc.settings.value[network].orEmpty(),
                        check = result,
                        busy = false,
                    )
                } else {
                    it.copy(refused = result, busy = false)
                }
            }
        }
    }

    fun reset(network: Network) {
        val current = find(network) ?: return
        if (current.busy || !current.ownSetting) return
        viewModelScope.launch {
            withContext(io) { rpc.reset(network) }
            update(network) {
                it.copy(endpoint = rpc.endpoint(network), draft = "", check = null, refused = null)
            }
            check(network)
        }
    }

    private fun check(network: Network) {
        if (find(network)?.busy == true) return
        update(network) { it.copy(busy = true) }
        viewModelScope.launch {
            val result = withContext(io) { rpc.check(network) }
            update(network) {
                it.copy(endpoint = rpc.endpoint(network), check = result, busy = false)
            }
        }
    }

    private fun find(network: Network) = _state.value.networks.firstOrNull { it.network == network }

    private fun update(network: Network, change: (RpcNetworkState) -> RpcNetworkState) {
        _state.update { state ->
            state.copy(
                networks = state.networks.map { if (it.network == network) change(it) else it }
            )
        }
    }
}
