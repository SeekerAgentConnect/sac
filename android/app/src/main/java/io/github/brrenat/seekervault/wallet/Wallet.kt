package io.github.brrenat.seekervault.wallet

import io.github.brrenat.seekervault.request.v1.Network
import java.time.Instant

/**
 * The Solana network a wallet is selected for. `chain` is the Mobile Wallet Adapter chain
 * identifier the wallet app knows it by, and [network] the value the protocol carries
 * (docs/protocol.md#the-wallet-binding).
 */
enum class WalletNetwork(val chain: String, val network: Network) {
    Mainnet("solana:mainnet", Network.NETWORK_MAINNET),
    Devnet("solana:devnet", Network.NETWORK_DEVNET),
    Testnet("solana:testnet", Network.NETWORK_TESTNET),
}

/**
 * The wallet the owner selected and the network they selected it for, as this phone holds it. It is
 * a public address and nothing else: no seed phrase, no private key, and not the wallet's
 * authorization token, which stays in the [storage.WalletStore] and never leaves the phone.
 */
data class SelectedWallet(
    /** The wallet's base58 address, an Ed25519 public key. */
    val address: String,
    val network: WalletNetwork,
    /** The wallet app's own name for the account, for display only. Null when it gave none. */
    val label: String? = null,
    /** When the owner connected it on this phone. */
    val selectedAt: Instant,
    /**
     * False when the wallet didn't list [network]'s chain for the account. The owner can still use
     * it, and the app says the wallet didn't confirm the network.
     */
    val networkConfirmed: Boolean = true,
)
