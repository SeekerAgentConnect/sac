package io.github.brrenat.seekervault.wallet

import com.google.protobuf.ByteString
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.Blockchain
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.AuthorizationResult
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.NotSubmittedException
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.SignAndSendTransactionsResult
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.SignMessagesResult
import com.solana.mobilewalletadapter.common.ProtocolContract

/**
 * [WalletAdapter] over Mobile Wallet Adapter. It associates with the wallet the owner already has,
 * such as Seed Vault Wallet on the Seeker, from the current activity: [sender] gives the
 * `ActivityResultSender` that `MainActivity` registered, waiting for the next one while a rotation
 * replaces the screen (SAW-017). No separate activity and no foreground service is needed, and the
 * app never becomes a wallet itself.
 */
class MwaWalletAdapter(
    private val identity: ConnectionIdentity,
    private val sender: suspend () -> ActivityResultSender?,
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

    override suspend fun signMessage(
        message: ByteString,
        wallet: SelectedWallet,
        authToken: String,
    ): SigningAnswer {
        val activity = sender() ?: return SigningAnswer(SignResult.Failed(NO_ACTIVITY))
        val account =
            decodeBase58(wallet.address)?.takeIf { it.size == PUBLIC_KEY_BYTES }
                ?: return SigningAnswer(SignResult.Failed("the selected wallet isn't an address"))
        val adapter =
            adapters(identity).apply {
                // The same chain the owner connected on, so the wallet reauthorizes as it did then.
                blockchain = wallet.network.blockchain()
                this.authToken = authToken
            }
        // The wallet reauthorizes this app at the start of the session, before it is asked for a
        // signature, and it may replace this phone's authorization then. It is read here, inside
        // the one session the owner sees, so a signature the wallet then declines still carries it
        // and the wallet is never opened a second time just to learn it.
        var refreshed: String? = null
        val result =
            adapter.transact(activity) { authorization ->
                refreshed = tokenOf(authorization)
                signMessagesDetached(arrayOf(message.toByteArray()), arrayOf(account))
            }
        val outcome =
            when (result) {
                is TransactionResult.Success -> signed(result.payload, wallet.address)
                is TransactionResult.NoWalletFound -> SignResult.NoWallet
                is TransactionResult.Failure -> classifySigning(result.e)
            }
        return SigningAnswer(outcome, refreshed)
    }

    override suspend fun signAndSendTransaction(
        transaction: ByteString,
        wallet: SelectedWallet,
        authToken: String,
    ): SendResult {
        val activity = sender() ?: return SendResult.Failed(NO_ACTIVITY)
        val adapter =
            adapters(identity).apply {
                // The network the owner connected on, which is the request's network: the
                // inspection refused the transaction otherwise (SAW-020).
                blockchain = wallet.network.blockchain()
                this.authToken = authToken
            }
        // The bytes go over as they are. This app hands the wallet exactly what the owner
        // approved, and the wallet signs and submits it; nothing here builds or alters one.
        val result =
            adapter.transact(activity) {
                signAndSendTransactions(arrayOf(transaction.toByteArray()))
            }
        return when (result) {
            is TransactionResult.Success -> sent(result.payload)
            is TransactionResult.NoWalletFound -> SendResult.NoWallet
            is TransactionResult.Failure -> classifySending(result.e)
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
            val token =
                tokenOf(auth) ?: return WalletResult.Failed("the wallet returned no authorization")
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
        const val SIGNATURE_BYTES = 64

        /** The authorization an answer carries, or null when the wallet reported none. */
        fun tokenOf(auth: AuthorizationResult?): String? =
            auth?.authToken?.takeIf { it.isNotEmpty() }

        /**
         * The wallet's answer, checked before it's believed: one signed message, signed by the
         * account that was asked, with a signature of the right size. What it says it signed is
         * returned as it is, so the caller can compare it with what it sent.
         */
        fun signed(result: SignMessagesResult?, expected: String): SignResult {
            val signed =
                result?.messages?.firstOrNull()
                    ?: return SignResult.Failed("the wallet returned no signed message")
            val signature =
                signed.signatures?.firstOrNull()
                    ?: return SignResult.Failed("the wallet returned no signature")
            if (signature.size != SIGNATURE_BYTES) {
                return SignResult.Failed("the wallet returned a signature of the wrong size")
            }
            val signer = signed.addresses?.firstOrNull()?.let(::encodeBase58)
            if (signer != null && signer != expected) {
                return SignResult.Failed("the wallet signed with $signer, not the selected wallet")
            }
            return SignResult.Signed(
                message = ByteString.copyFrom(signed.message ?: ByteArray(0)),
                address = expected,
                signature = ByteString.copyFrom(signature),
            )
        }

        /**
         * The wallet's answer about a transaction it sent, checked before it is believed: exactly
         * one signature, of the right size. The signature is the transaction's ID on chain.
         */
        fun sent(result: SignAndSendTransactionsResult?): SendResult {
            val signatures = result?.signatures
            // The wallet was given one transaction, so anything but one signature is an answer
            // this phone can't match to what it asked, and it may still have been sent.
            if (signatures == null || signatures.size != 1) {
                return SendResult.Unknown("the wallet returned no signature for this transaction")
            }
            val signature = signatures.single()
            if (signature == null || signature.size != SIGNATURE_BYTES) {
                return SendResult.Unknown("the wallet returned a signature of the wrong size")
            }
            return SendResult.Sent(ByteString.copyFrom(signature))
        }

        /**
         * What a failure to sign and send means for the owner. Only the codes that say the wallet
         * stopped before it signed are failures. Everything else leaves this phone unable to tell
         * whether the transaction reached the network, and an outcome nobody knows must be reported
         * as unknown rather than guessed at: a signed transaction stays valid until its blockhash
         * expires, so "the wallet says it didn't submit it" isn't "it can never land".
         */
        fun classifySending(error: Exception?): SendResult =
            when (remoteCode(error)) {
                ProtocolContract.ERROR_NOT_SIGNED -> SendResult.Declined
                ProtocolContract.ERROR_AUTHORIZATION_FAILED -> SendResult.AuthorizationExpired
                ProtocolContract.ERROR_INVALID_PAYLOADS ->
                    SendResult.Failed("the wallet would not take this transaction")
                ProtocolContract.ERROR_TOO_MANY_PAYLOADS ->
                    SendResult.Failed("the wallet would not take this transaction")
                ProtocolContract.ERROR_CLUSTER_NOT_SUPPORTED ->
                    SendResult.Failed("the wallet doesn't serve this network")
                ProtocolContract.ERROR_NOT_SUBMITTED -> SendResult.Unknown(notSubmitted(error))
                // No code of the wallet's own: the session ended without an answer, which can
                // happen on either side of the wallet sending the transaction.
                else -> SendResult.Unknown(error?.message)
            }

        /** What the wallet said when it signed a transaction but reported no submission. */
        fun notSubmitted(error: Exception?): String =
            generateSequence(error as Throwable?) { it.cause }
                .filterIsInstance<NotSubmittedException>()
                .firstOrNull()
                ?.let { "the wallet signed the transaction but reported that it didn't send it" }
                ?: error?.message.orEmpty()

        /**
         * What a failure to sign means for the owner. The wallet reports a refused signature as
         * NOT_SIGNED; an authorization it no longer honours comes back as AUTHORIZATION_FAILED, and
         * this app only ever signs with one it stored.
         */
        fun classifySigning(error: Exception?): SignResult =
            when (remoteCode(error)) {
                ProtocolContract.ERROR_NOT_SIGNED -> SignResult.Declined
                ProtocolContract.ERROR_AUTHORIZATION_FAILED -> SignResult.AuthorizationExpired
                ProtocolContract.ERROR_INVALID_PAYLOADS ->
                    SignResult.Failed("the wallet would not take this message")
                else -> SignResult.Failed(error?.message)
            }

        /**
         * What the wallet's error means for the owner. The wallet reports a refusal and an
         * authorization it no longer honours the same way, as AUTHORIZATION_FAILED, so
         * [hadAuthorization] tells them apart: a stored authorization was refused, and a fresh
         * request the owner saw was declined.
         */
        fun classify(error: Exception?, hadAuthorization: Boolean): WalletResult {
            return when (remoteCode(error)) {
                ProtocolContract.ERROR_AUTHORIZATION_FAILED ->
                    if (hadAuthorization) WalletResult.AuthorizationExpired
                    else WalletResult.Declined
                ProtocolContract.ERROR_CLUSTER_NOT_SUPPORTED -> WalletResult.NetworkUnsupported
                else -> WalletResult.Failed(error?.message)
            }
        }

        /** The wallet's own error code, from wherever in the chain of causes it is. */
        fun remoteCode(error: Exception?): Int? =
            generateSequence(error as Throwable?) { it.cause }
                .filterIsInstance<JsonRpc20Client.JsonRpc20RemoteException>()
                .firstOrNull()
                ?.code
    }
}
