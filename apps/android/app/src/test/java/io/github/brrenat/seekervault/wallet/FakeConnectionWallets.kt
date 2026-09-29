package io.github.brrenat.seekervault.wallet

import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionWallets
import io.github.brrenat.seekervault.request.v1.WalletBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The connections a [WalletRepository] binds, over a plain list (SEE-174). Every binding a direct
 * server is told is recorded in [published], per connection, so a test can say exactly which server
 * heard which wallet — and [reachable] decides whether a server answers.
 */
class FakeConnectionWallets(
    val flow: MutableStateFlow<List<Connection>> = MutableStateFlow(emptyList())
) : ConnectionWallets {
    override val connections: StateFlow<List<Connection>> = flow

    /** Every binding each connection's server was told, in order. Null is "no wallet". */
    val published = mutableMapOf<String, MutableList<WalletBinding?>>()

    /** Whether each server answers; absent is reachable. */
    val reachable = mutableMapOf<String, Boolean>()

    /** What each server cancels when told a binding. */
    var cancels: (String, WalletBinding?) -> Int = { _, _ -> 0 }

    override fun connection(id: String): Connection? = flow.value.firstOrNull { it.id == id }

    override suspend fun load() = Unit

    override suspend fun setWalletProfile(id: String, profileId: String?): Boolean {
        connection(id) ?: return false
        update(id) { it.copy(walletProfileId = profileId) }
        return true
    }

    override suspend fun adoptLegacyWallet(profileId: String?) {
        flow.value =
            flow.value.map {
                if (it.walletProfileId == Connection.LEGACY_WALLET_PROFILE) {
                    it.copy(walletProfileId = profileId.takeIf { _ -> it.retirement == null })
                } else {
                    it
                }
            }
    }

    override suspend fun clearWalletProfile(profileId: String): List<Connection> {
        val bound = flow.value.filter { it.walletProfileId == profileId }
        flow.value =
            flow.value.map {
                if (it.walletProfileId == profileId) it.copy(walletProfileId = null) else it
            }
        return bound
    }

    override suspend fun publishWalletCancelling(id: String, binding: WalletBinding?): Int? {
        val connection = connection(id) ?: return null
        if (!connection.usable || reachable[id] == false) return null
        published.getOrPut(id) { mutableListOf() } += binding
        return cancels(id, binding)
    }

    fun update(id: String, change: (Connection) -> Connection) {
        flow.value = flow.value.map { if (it.id == id) change(it) else it }
    }
}
