package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.request.v1.WalletBinding
import kotlinx.coroutines.flow.StateFlow

/**
 * What the wallet repository needs from the connections (SEE-174): which profile each one names,
 * the owner's changes to that, and telling one direct server its own binding.
 * [ConnectionRepository] is the implementation; the interface exists so the wallet's rules can be
 * exercised over any set of connections, and so nothing else of a connection is reachable from the
 * wallet.
 */
interface ConnectionWallets {
    val connections: StateFlow<List<Connection>>

    fun connection(id: String): Connection?

    /** Reads the stored connections, once. */
    suspend fun load()

    /** See [ConnectionRepository.setWalletProfile]. */
    suspend fun setWalletProfile(id: String, profileId: String?): Boolean

    /** See [ConnectionRepository.adoptLegacyWallet]. */
    suspend fun adoptLegacyWallet(profileId: String?)

    /** See [ConnectionRepository.clearWalletProfile]. */
    suspend fun clearWalletProfile(profileId: String): List<Connection>

    /** See [ConnectionRepository.publishWalletCancelling]. */
    suspend fun publishWalletCancelling(id: String, binding: WalletBinding?): Int?
}
