package io.github.brrenat.seekervault.wallet

/** One account as the wallet app reported it. */
data class WalletAccount(
    /** The account's base58 address. */
    val address: String,
    /** The wallet app's own name for it, if it gave one. */
    val label: String?,
    /** The chains the wallet says the account works on, such as `solana:devnet`. */
    val chains: List<String>,
)

/** What connecting to the wallet produced. */
sealed interface WalletResult {
    /**
     * The owner picked an account. [authToken] is the wallet's authorization for this app; it's a
     * secret, it never leaves the phone, and it never reaches a sidecar or a log.
     */
    data class Connected(val account: WalletAccount, val authToken: String) : WalletResult {
        override fun toString() = "Connected(account=$account, authToken=<redacted>)"
    }

    /** No wallet app is installed that speaks Mobile Wallet Adapter. */
    data object NoWallet : WalletResult

    /** The owner declined in the wallet, or left it without deciding. */
    data object Declined : WalletResult

    /** The stored authorization no longer works; connecting again asks the owner afresh. */
    data object AuthorizationExpired : WalletResult

    /** The wallet doesn't serve the network the owner picked. */
    data object NetworkUnsupported : WalletResult

    /** Anything else the wallet reported. [message] is for display, never for parsing. */
    data class Failed(val message: String?) : WalletResult
}

/**
 * The phone's boundary to the installed wallet (docs/architecture.md#the-wallet-adapter-boundary).
 * The app talks to a wallet only through this interface, so the screens and the repository can be
 * tested without one. [MwaWalletAdapter] is the real implementation, over Mobile Wallet Adapter.
 *
 * Nothing here creates a wallet or holds a key: the wallet app owns the keys, and this app only
 * learns which public address the owner chose.
 */
interface WalletAdapter {
    /**
     * Asks the wallet for the account to use on [network]. [authToken] is the authorization from an
     * earlier connection, which lets the wallet skip asking again; pass null to start afresh.
     */
    suspend fun connect(network: WalletNetwork, authToken: String?): WalletResult

    /**
     * Tells the wallet this app no longer needs [authToken]. Failures are not reported: the phone
     * forgets the authorization either way.
     */
    suspend fun disconnect(authToken: String)
}
