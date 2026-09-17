package io.github.brrenat.seekervault.wallet

/**
 * The authorization a wallet reported while a session was open: the token to keep from now on, and
 * the accounts the wallet says this app may use.
 *
 * Mobile Wallet Adapter reauthorizes this app at the start of every session, so this is what the
 * wallet believes about the app *now*, not what this phone stored earlier. [token] is a secret: it
 * never leaves the phone, and it is never written to a log.
 */
data class WalletAuthorization(val token: String?, val accounts: List<WalletAccount>) {
    override fun toString(): String {
        val held = if (token == null) "none" else "<redacted>"
        return "WalletAuthorization(accounts=$accounts, token=$held)"
    }
}

/**
 * One message the wallet says it signed, as it reported it. Nothing here is believed until it is
 * checked: the caller compares the signer and verifies the signature over the exact bytes it asked
 * for (`MwaWalletAdapter.signed`).
 */
class SignedMessage(
    val message: ByteArray?,
    val signatures: List<ByteArray?>,
    val addresses: List<ByteArray?>,
)

/**
 * What this app asks for inside one wallet session, and the whole of it. A session that asks
 * nothing — because the wallet reauthorized an account the owner never reviewed — is an ordinary
 * outcome, not an error: see [WalletSessionClient.transact].
 */
interface WalletRequests {
    /**
     * Asks the wallet to sign [messages], each with the account at the same index in [addresses].
     */
    suspend fun signMessages(
        messages: List<ByteArray>,
        addresses: List<ByteArray>,
    ): List<SignedMessage>

    /**
     * Asks the wallet to sign [transactions] and send them itself. The answer is the signatures the
     * wallet reported, which are the transactions' IDs on chain.
     */
    suspend fun signAndSend(transactions: List<ByteArray>): List<ByteArray?>
}

/**
 * A wallet's own error, as much of it as this app reads. [code] is the Mobile Wallet Adapter
 * protocol code when the wallet gave one, and [notSubmitted] says the wallet reported that it
 * signed a transaction but did not send it. [message] is for display, never for parsing.
 */
data class WalletError(
    val code: Int? = null,
    val notSubmitted: Boolean = false,
    val message: String? = null,
)

/**
 * What one wallet session produced. It is the shape of every answer the app's boundary to Mobile
 * Wallet Adapter gives, so the adapter can be exercised without a wallet app or an activity.
 */
sealed interface WalletOutcome<out T> {
    /**
     * The session ran to its end. [payload] is what the request produced, and null when the app
     * deliberately asked for nothing. [authorization] is what the wallet reported while
     * reauthorizing, which is worth keeping whatever the payload says.
     */
    data class Answered<out T>(val payload: T?, val authorization: WalletAuthorization?) :
        WalletOutcome<T>

    /** No wallet app that speaks Mobile Wallet Adapter is installed. */
    data object NoWallet : WalletOutcome<Nothing>

    /**
     * The app's own screen is gone, so nothing was put to a wallet at all. It is the one failure
     * this app can prove happened before anything was asked of a wallet.
     */
    data object NoActivity : WalletOutcome<Nothing>

    /**
     * The session ended in an error. [authorization] is what the wallet had reported before it went
     * wrong, when it got that far, and null when the session failed before reauthorizing.
     */
    data class Failed(val error: WalletError, val authorization: WalletAuthorization?) :
        WalletOutcome<Nothing>
}

/**
 * The app's boundary to one wallet session (docs/architecture.md#the-wallet-adapter-boundary).
 * [MwaWalletAdapter] drives a wallet only through this, so authorization, the token the wallet
 * hands back, signing, sending, and cleanup are all exercised in tests without a wallet app.
 *
 * One client is one session with one wallet on one [network]. Mobile Wallet Adapter's own client
 * learns where the wallet answered from while it authorizes and keeps it for as long as the client
 * lives, so the client is kept for the session's lifetime rather than made again per operation
 * (docs/testing/wallet-lifecycle.md#wallet-targeting). It holds no transport open between calls.
 */
interface WalletSessionClient {
    /** The network this session is for. A session is never reused for another one. */
    val network: WalletNetwork

    /**
     * The authorization offered to the wallet on the next call, or null to ask the owner afresh.
     * The wallet may replace it, and the replacement comes back in the outcome.
     */
    var authorization: String?

    /** Asks the wallet to authorize this app, and nothing else. */
    suspend fun connect(): WalletOutcome<Unit>

    /**
     * Opens the wallet, lets it reauthorize this app, and runs [block] in that one session. The
     * authorization the wallet reported is handed to [block] so it can check it before asking for
     * anything, and returning null from [block] asks the wallet for nothing at all.
     */
    suspend fun <T : Any> transact(
        block: suspend WalletRequests.(WalletAuthorization) -> T?
    ): WalletOutcome<T>

    /** Tells the wallet this app no longer needs its authorization. */
    suspend fun close()
}
