package io.github.brrenat.seekervault.wallet

import com.google.protobuf.ByteString

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
    data class Connected(
        val account: WalletAccount,
        val authToken: String,
        /**
         * How to reach this wallet app again (SEE-159): what the owner aimed the connection at,
         * with the association URI the wallet reported folded in. It is stored beside the account,
         * and every later signing is routed by it.
         */
        val route: WalletRouting = WalletRouting.Untargeted,
        /**
         * Every account the wallet authorized in this one authorization, [account] first (SEE-174).
         * Mobile Wallet Adapter lets a wallet authorize several at once; each becomes a profile of
         * its own, sharing the one [authToken].
         */
        val accounts: List<WalletAccount> = listOf(account),
    ) : WalletResult {
        override fun toString() =
            "Connected(accounts=$accounts, authToken=<redacted>, route=$route)"
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
 * What asking the wallet to sign a message produced (docs/guides/message-signing.md). The wallet
 * either signs or it doesn't: nothing is broadcast either way, so there is no uncertain outcome.
 */
sealed interface SignResult {
    /**
     * The wallet signed. [message] is what it reported signing, which the caller checks against
     * what it asked for, and [address] the account it signed with.
     */
    data class Signed(
        val message: ByteString,
        val address: String,
        val signature: ByteString,
    ) : SignResult

    /** No wallet app that speaks Mobile Wallet Adapter is installed. */
    data object NoWallet : SignResult

    /** The owner declined in the wallet. Nothing was signed. */
    data object Declined : SignResult

    /** The stored authorization no longer works; the owner connects the wallet again. */
    data object AuthorizationExpired : SignResult

    /** Anything else the wallet reported. [message] is for display, never for parsing. */
    data class Failed(val message: String?) : SignResult

    /**
     * The app didn't ask the wallet anything: no wallet is connected on this phone. [WalletAdapter]
     * never returns it, only [WalletRepository].
     */
    data object NotConnected : SignResult

    /**
     * Nothing was signed, because the account that would have signed isn't the one the owner
     * reviewed: either this phone's selection changed under the request, or the wallet's own
     * reauthorization no longer names that account (SEE-84). Either way the request needs another
     * look, and the owner connects the wallet again.
     */
    data object Changed : SignResult
}

/**
 * What asking the wallet to sign and send a transaction produced (docs/guides/transfers.md). Unlike
 * a message, a transaction can reach the network, so there is an outcome that is neither success
 * nor failure: the wallet may have sent it and this phone may never learn so. That outcome is
 * [Unknown], and it is never treated as a failure that could be tried again.
 */
sealed interface SendResult {
    /**
     * The wallet signed the transaction and sent it. [signature] is its first signature, which is
     * its ID on chain. It says the wallet submitted it, not that it succeeded: confirmation is
     * SAW-022.
     */
    data class Sent(val signature: ByteString) : SendResult

    /** No wallet app that speaks Mobile Wallet Adapter is installed. */
    data object NoWallet : SendResult

    /** The owner declined in the wallet. Nothing was signed, and nothing was sent. */
    data object Declined : SendResult

    /** The stored authorization no longer works; the owner connects the wallet again. */
    data object AuthorizationExpired : SendResult

    /**
     * Nothing was sent, and this phone can say so: the wallet refused before it signed anything, or
     * the session ended before the transaction was ever put to the wallet. [message] is for
     * display, never for parsing.
     */
    data class Failed(val message: String?) : SendResult

    /**
     * Whether the transaction was sent isn't known here: the wallet reported that it signed but
     * couldn't submit, or the call ended without an answer this phone can read. A signed
     * transaction stays valid until its blockhash expires, so "not submitted here" is not "never
     * sent". Nothing is asked of the wallet again on this outcome.
     */
    data class Unknown(val message: String?) : SendResult

    /** The app didn't ask the wallet anything: no wallet is connected on this phone. */
    data object NotConnected : SendResult

    /**
     * Nothing was signed and nothing was sent, because the account that would have signed isn't the
     * one the owner reviewed: either this phone's selection changed under the request, or the
     * wallet's own reauthorization no longer names that account (SEE-84).
     */
    data object Changed : SendResult
}

/**
 * What the wallet answered when it was asked to sign: the [result], and the authorization it
 * reported while answering.
 *
 * Mobile Wallet Adapter reauthorizes this app at the start of every wallet session, and the wallet
 * may hand back a replacement authorization. [authToken] is the one to keep from now on, whatever
 * the wallet then did with the message: it is null when the wallet reported none, and it is a
 * secret like any other, so it never leaves the phone.
 */
data class SigningAnswer(
    val result: SignResult,
    val authToken: String? = null,
    /**
     * The association URI the wallet reported while it reauthorized, or null when it reported none
     * (SEE-159). Like [authToken] it is worth keeping whatever the wallet then did with the
     * message: a wallet that moved its endpoint has said so, and the next signing has to go there.
     */
    val uriBase: String? = null,
) {
    override fun toString() =
        "SigningAnswer(result=$result, " +
            "authToken=${if (authToken == null) "none" else "<redacted>"}, uriBase=$uriBase)"
}

/**
 * What the wallet answered when it was asked to sign and send a transaction: the [result], and the
 * authorization it reported while answering.
 *
 * A wallet reauthorizes this app before it sends, exactly as it does before it signs a message, and
 * it may hand back a replacement authorization then. [authToken] is the one to keep from now on,
 * whatever the wallet then did with the transaction — a declined transfer, and one whose outcome
 * nobody here knows, both carry a perfectly good authorization (SEE-84). It is null when the wallet
 * reported none, and it is a secret like any other, so it never leaves the phone.
 */
data class SendingAnswer(
    val result: SendResult,
    val authToken: String? = null,
    /** The same as [SigningAnswer.uriBase], reported while the wallet reauthorized to send. */
    val uriBase: String? = null,
) {
    override fun toString() =
        "SendingAnswer(result=$result, " +
            "authToken=${if (authToken == null) "none" else "<redacted>"}, uriBase=$uriBase)"
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
     * The wallet apps installed on this phone, as `PackageManager` reports them, in its order
     * (SEE-159). It is how the owner is offered a wallet to connect without Android's chooser, and
     * it is the only source of a wallet's package name: nothing here is guessed or hard-coded.
     */
    suspend fun installed(): List<InstalledWallet>

    /**
     * Asks the wallet for the account to use on [network]. [authToken] is the authorization from an
     * earlier connection, which lets the wallet skip asking again; pass null to start afresh.
     * [route] aims the association at one wallet app, and null leaves the choice to Android.
     *
     * Connecting is the owner's own choice of wallet, so a [route] whose app is no longer installed
     * does not fail here the way a signing does: it falls back to asking Android, which is what
     * changing the wallet means.
     */
    suspend fun connect(
        network: WalletNetwork,
        authToken: String?,
        route: WalletRouting? = null,
    ): WalletResult

    /**
     * Tells the wallet this app no longer needs [authToken], which was [wallet]'s, over [route].
     * Failures are not reported: the phone forgets the authorization either way.
     */
    suspend fun disconnect(wallet: SelectedWallet, authToken: String, route: WalletRouting? = null)

    /**
     * Asks the wallet to sign exactly [message] with [wallet]'s account, using the authorization
     * [authToken] from the owner's earlier connection. It is called only after the owner has
     * approved the request on this phone, and it opens the wallet once: the answer carries both
     * what the wallet did and the authorization it reported, so nothing has to ask again.
     *
     * [route] is the wallet app the owner connected, and the association goes to it and to no other
     * (SEE-159). A route whose app has gone answers [SignResult.NoWallet] without opening anything:
     * another wallet must never inherit an approval the owner gave for this one.
     */
    suspend fun signMessage(
        message: ByteString,
        wallet: SelectedWallet,
        authToken: String,
        route: WalletRouting? = null,
    ): SigningAnswer

    /**
     * Asks the wallet to sign exactly [transaction] with [wallet]'s account and send it, using the
     * authorization [authToken] from the owner's earlier connection. The wallet does the sending:
     * this app reaches no network of its own, and builds nothing. It is called only after the owner
     * has approved this exact transaction on this phone, and the sidecar has accepted the approval.
     * Like [signMessage] it opens the wallet once, and the answer carries both what the wallet did
     * and the authorization it reported, so nothing has to ask again — and like it, [route] aims
     * the association at the wallet app the owner connected and nowhere else.
     */
    suspend fun signAndSendTransaction(
        transaction: ByteString,
        wallet: SelectedWallet,
        authToken: String,
        route: WalletRouting? = null,
    ): SendingAnswer
}
