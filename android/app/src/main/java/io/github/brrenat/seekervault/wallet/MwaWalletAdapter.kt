package io.github.brrenat.seekervault.wallet

import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.Blockchain
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.AuthorizationResult
import com.solana.mobilewalletadapter.common.ProtocolContract

/**
 * [WalletAdapter] over Mobile Wallet Adapter. It associates with the wallet the owner already has,
 * such as Seed Vault Wallet on the Seeker, from the current activity: [sender] gives the
 * `ActivityResultSender` that `MainActivity` registered. No separate activity and no foreground
 * service is needed, and the app never becomes a wallet itself.
 */
class MwaWalletAdapter(
    private val identity: ConnectionIdentity,
    private val sender: () -> ActivityResultSender?,
    private val adapters: (ConnectionIdentity) -> MobileWalletAdapter = ::MobileWalletAdapter,
) : WalletAdapter {
    override suspend fun connect(network: WalletNetwork, authToken: String?): WalletResult {
        val activity = sender() ?: return WalletResult.Failed(NO_ACTIVITY)
        val adapter =
            adapters(identity).apply {
                blockchain = network.blockchain()
                this.authToken = authToken
            }
        return when (val result = adapter.connect(activity)) {
            is TransactionResult.Success -> connected(result.authResult)
            is TransactionResult.NoWalletFound -> WalletResult.NoWallet
            is TransactionResult.Failure -> classify(result.e, hadAuthorization = authToken != null)
        }
    }

    override suspend fun disconnect(authToken: String) {
        val activity = sender() ?: return
        val adapter = adapters(identity).apply { this.authToken = authToken }
        // Whatever the wallet says, the phone forgets the authorization; there is nothing to undo.
        adapter.disconnect(activity)
    }

    private companion object {
        const val NO_ACTIVITY = "the app's screen closed before the wallet answered"

        fun WalletNetwork.blockchain(): Blockchain =
            when (this) {
                WalletNetwork.Mainnet -> Solana.Mainnet
                WalletNetwork.Devnet -> Solana.Devnet
                WalletNetwork.Testnet -> Solana.Testnet
            }

        fun connected(auth: AuthorizationResult?): WalletResult {
            val account =
                auth?.accounts?.firstOrNull()
                    ?: return WalletResult.Failed("the wallet returned no account")
            if (account.publicKey.size != PUBLIC_KEY_BYTES) {
                return WalletResult.Failed("the wallet returned an address of the wrong size")
            }
            val token = auth.authToken
            if (token.isNullOrEmpty()) {
                return WalletResult.Failed("the wallet returned no authorization")
            }
            return WalletResult.Connected(
                WalletAccount(
                    address = encodeBase58(account.publicKey),
                    label = account.accountLabel?.takeIf { it.isNotBlank() },
                    chains = account.chains?.filterNotNull().orEmpty(),
                ),
                token,
            )
        }

        const val PUBLIC_KEY_BYTES = 32

        /**
         * What the wallet's error means for the owner. The wallet reports a refusal and an
         * authorization it no longer honours the same way, as AUTHORIZATION_FAILED, so
         * [hadAuthorization] tells them apart: a stored authorization was refused, and a fresh
         * request the owner saw was declined.
         */
        fun classify(error: Exception?, hadAuthorization: Boolean): WalletResult {
            val remote =
                generateSequence(error as Throwable?) { it.cause }
                    .filterIsInstance<JsonRpc20Client.JsonRpc20RemoteException>()
                    .firstOrNull()
            return when (remote?.code) {
                ProtocolContract.ERROR_AUTHORIZATION_FAILED ->
                    if (hadAuthorization) WalletResult.AuthorizationExpired
                    else WalletResult.Declined
                ProtocolContract.ERROR_CLUSTER_NOT_SUPPORTED -> WalletResult.NetworkUnsupported
                else -> WalletResult.Failed(error?.message)
            }
        }
    }
}
