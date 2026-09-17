package io.github.brrenat.seekervault.wallet

import com.google.protobuf.ByteString
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.AdapterOperations
import com.solana.mobilewalletadapter.clientlib.Blockchain
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.AuthorizationResult
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.NotSubmittedException
import com.solana.mobilewalletadapter.common.ProtocolContract
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [WalletAdapter] over Mobile Wallet Adapter. It associates with the wallet the owner already has,
 * such as Seed Vault Wallet on the Seeker, from the current activity: the session's [sender] gives
 * the `ActivityResultSender` that `MainActivity` registered, waiting for the next one while a
 * rotation replaces the screen (SAW-017). No separate activity and no foreground service is needed,
 * and the app never becomes a wallet itself.
 *
 * One wallet session is one [WalletSessionClient], kept across connecting, signing and sending
 * (SEE-84): Mobile Wallet Adapter's client learns where the wallet answered from while it
 * authorizes, and a client made afresh for every operation throws that away. The session is dropped
 * when the owner disconnects, when the wallet refuses this phone's authorization, and when the
 * network changes — never silently reused for another wallet or another chain.
 */
class MwaWalletAdapter(
    private val identity: ConnectionIdentity,
    private val sender: suspend () -> ActivityResultSender?,
    private val clients: (WalletNetwork) -> WalletSessionClient = { network ->
        MwaSession(identity, sender, network)
    },
) : WalletAdapter {
    /**
     * One wallet interaction at a time, and one session for it. [WalletRepository] serializes
     * wallet calls too; this keeps the session's own state right whoever holds the adapter.
     */
    private val lock = Mutex()
    private var open: WalletSessionClient? = null

    override suspend fun connect(network: WalletNetwork, authToken: String?): WalletResult =
        lock.withLock {
            val session = session(network, authToken)
            val result =
                when (val outcome = session.connect()) {
                    is WalletOutcome.Answered -> connected(outcome.authorization)
                    WalletOutcome.NoWallet -> WalletResult.NoWallet
                    WalletOutcome.NoActivity -> WalletResult.Failed(NO_ACTIVITY)
                    is WalletOutcome.Failed ->
                        classify(outcome.error, hadAuthorization = authToken != null)
                }
            // An authorization the wallet refused ends the session it belonged to: the next
            // attempt starts one afresh, and asks the owner.
            if (result == WalletResult.AuthorizationExpired) open = null
            result
        }

    override suspend fun signMessage(
        message: ByteString,
        wallet: SelectedWallet,
        authToken: String,
    ): SigningAnswer = lock.withLock {
        val account =
            decodeBase58(wallet.address)?.takeIf { it.size == PUBLIC_KEY_BYTES }
                ?: return@withLock SigningAnswer(
                    SignResult.Failed("the selected wallet isn't an address")
                )
        val asked = message.toByteArray()
        // The wallet reauthorizes this app at the start of the session, before it is asked for
        // a signature, and it may replace this phone's authorization then. It is read here,
        // inside the one session the owner sees, so a signature the wallet then declines still
        // carries it and the wallet is never opened a second time just to learn it.
        var refused: SignResult? = null
        val outcome =
            session(wallet.network, authToken).transact { authorization ->
                // The account the owner reviewed has to be one the wallet still authorizes.
                // Nothing is put in front of them for another account, on any outcome.
                if (authorizes(authorization, wallet) != AccountCheck.Matches) {
                    refused = SignResult.Changed
                    null
                } else {
                    signMessages(listOf(asked), listOf(account)).firstOrNull()
                }
            }
        val stopped = refused
        val result =
            when (outcome) {
                is WalletOutcome.Answered ->
                    stopped ?: signed(outcome.payload, wallet.address, account, asked)
                WalletOutcome.NoWallet -> SignResult.NoWallet
                WalletOutcome.NoActivity -> SignResult.Failed(NO_ACTIVITY)
                is WalletOutcome.Failed -> classifySigning(outcome.error)
            }
        if (result == SignResult.AuthorizationExpired) open = null
        SigningAnswer(result, outcome.reported()?.token)
    }

    override suspend fun signAndSendTransaction(
        transaction: ByteString,
        wallet: SelectedWallet,
        authToken: String,
    ): SendingAnswer = lock.withLock {
        var refused: SendResult? = null
        // Whether the request reached the wallet at all. Until it does, a failure proves
        // nothing was sent; from the moment it does, this phone can no longer rule a
        // submission out, whatever went wrong afterwards.
        var dispatched = false
        val outcome =
            session(wallet.network, authToken).transact { authorization ->
                if (authorizes(authorization, wallet) != AccountCheck.Matches) {
                    refused = SendResult.Changed
                    null
                } else {
                    dispatched = true
                    // The bytes go over as they are. This app hands the wallet exactly what
                    // the owner approved, and the wallet signs and submits it; nothing here
                    // builds or alters one.
                    signAndSend(listOf(transaction.toByteArray()))
                }
            }
        val stopped = refused
        val result =
            when (outcome) {
                is WalletOutcome.Answered -> stopped ?: sent(outcome.payload)
                WalletOutcome.NoWallet -> SendResult.NoWallet
                // The screen was gone before the wallet was opened, so nothing was sent.
                WalletOutcome.NoActivity -> SendResult.Failed(NO_ACTIVITY)
                is WalletOutcome.Failed -> classifySending(outcome.error, dispatched)
            }
        if (result == SendResult.AuthorizationExpired) open = null
        // The wallet reauthorizes before it sends, exactly as it does before it signs a
        // message, and the replacement it hands back is the one to keep whatever it then did
        // with the transaction (SEE-84). Losing it here would leave the next operation
        // offering a token the wallet has already replaced.
        SendingAnswer(result, outcome.reported()?.token)
    }

    override suspend fun disconnect(wallet: SelectedWallet, authToken: String) = lock.withLock {
        // Whatever the wallet says, the phone forgets the authorization; there is nothing to
        // undo. The session goes with it, so nothing is left targeting that wallet.
        session(wallet.network, authToken).close()
        open = null
    }

    /**
     * The session for [network], which is the one already open when it is for the same network.
     * Another network is another session: a client is bound to the chain it authorized on.
     */
    private fun session(network: WalletNetwork, authToken: String?): WalletSessionClient {
        val session = open?.takeIf { it.network == network } ?: clients(network).also { open = it }
        session.authorization = authToken
        return session
    }

    // Internal, not private: every one of these checks the wallet's own answer, and each is worth
    // a test of its own (`MwaWalletAdapterTest`). Nothing here reaches the wallet or the network.
    internal companion object {
        const val NO_ACTIVITY = "the app's screen closed before the wallet answered"
        const val BEFORE_THE_WALLET = "the wallet session ended before the transaction reached it"

        /**
         * Whether the wallet just authorized the very account the owner reviewed (SEE-84). The
         * address is compared whole, so an account the wallet substituted is never signed with, and
         * the chains the wallet listed for it must not contradict the reviewed network — a wallet
         * that lists none has said nothing, which is not a contradiction and not a confirmation
         * either.
         */
        fun authorizes(authorization: WalletAuthorization, wallet: SelectedWallet): AccountCheck {
            val account =
                authorization.accounts.firstOrNull { it.address == wallet.address }
                    ?: return AccountCheck.NotAuthorized
            if (account.chains.isNotEmpty() && wallet.network.chain !in account.chains) {
                return AccountCheck.ChainMismatch
            }
            return AccountCheck.Matches
        }

        fun connected(authorization: WalletAuthorization?): WalletResult {
            val account =
                authorization?.accounts?.firstOrNull()
                    ?: return WalletResult.Failed("the wallet returned no account")
            if (decodeBase58(account.address)?.size != PUBLIC_KEY_BYTES) {
                return WalletResult.Failed("the wallet returned an address of the wrong size")
            }
            val token =
                authorization.token
                    ?: return WalletResult.Failed("the wallet returned no authorization")
            return WalletResult.Connected(account, token)
        }

        const val PUBLIC_KEY_BYTES = 32
        const val SIGNATURE_BYTES = 64

        /** The authorization an outcome carries, from wherever in it the wallet reported one. */
        fun WalletOutcome<*>.reported(): WalletAuthorization? =
            when (this) {
                is WalletOutcome.Answered -> authorization
                is WalletOutcome.Failed -> authorization
                else -> null
            }

        /**
         * The wallet's answer, checked before it's believed: one signed message, signed by the
         * account that was asked, with a signature of the right size — and one that verifies as
         * [key]'s over exactly [asked]. What it says it signed is returned as it is, so the caller
         * can compare it with what it sent.
         *
         * The signature is verified here because the sidecar verifies every signature it is sent
         * and refuses anything else with INVALID_PARAMETERS, and the first outcome stored for an
         * approval stands: believing 64 bytes that aren't a signature would send that answer again
         * for ever, and leave the request PROCESSING with nothing able to settle it. A wallet whose
         * answer doesn't verify has failed to sign, which is an outcome both the owner and the
         * agent can be told once and be done with.
         */
        fun signed(
            signed: SignedMessage?,
            expected: String,
            key: ByteArray,
            asked: ByteArray,
        ): SignResult {
            if (signed == null) return SignResult.Failed("the wallet returned no signed message")
            val signature =
                signed.signatures.firstOrNull()
                    ?: return SignResult.Failed("the wallet returned no signature")
            if (signature.size != SIGNATURE_BYTES) {
                return SignResult.Failed("the wallet returned a signature of the wrong size")
            }
            val signer = signed.addresses.firstOrNull()?.let(::encodeBase58)
            if (signer != null && signer != expected) {
                return SignResult.Failed("the wallet signed with $signer, not the selected wallet")
            }
            if (!verifiesSignature(key, asked, signature)) {
                return SignResult.Failed(
                    "the wallet's answer is not this wallet's signature over this message"
                )
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
        fun sent(signatures: List<ByteArray?>?): SendResult {
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
         * stopped before it signed are failures, and so is a session that ended before the
         * transaction was ever put to the wallet — [dispatched] is false then, and nothing this app
         * asked for can have reached a network. Everything else leaves this phone unable to tell
         * whether the transaction reached the network, and an outcome nobody knows must be reported
         * as unknown rather than guessed at: a signed transaction stays valid until its blockhash
         * expires, so "the wallet says it didn't submit it" isn't "it can never land".
         */
        fun classifySending(error: WalletError, dispatched: Boolean): SendResult =
            when (error.code) {
                ProtocolContract.ERROR_NOT_SIGNED -> SendResult.Declined
                ProtocolContract.ERROR_AUTHORIZATION_FAILED -> SendResult.AuthorizationExpired
                ProtocolContract.ERROR_INVALID_PAYLOADS ->
                    SendResult.Failed("the wallet would not take this transaction")
                ProtocolContract.ERROR_TOO_MANY_PAYLOADS ->
                    SendResult.Failed("the wallet would not take this transaction")
                ProtocolContract.ERROR_CLUSTER_NOT_SUPPORTED ->
                    SendResult.Failed("the wallet doesn't serve this network")
                ProtocolContract.ERROR_NOT_SUBMITTED -> SendResult.Unknown(notSubmitted(error))
                // No code of the wallet's own: the session ended without an answer. Which side of
                // the wallet's sending that happened on is the whole question, and this phone can
                // answer it for itself — it knows whether it ever got as far as asking.
                else ->
                    if (dispatched) SendResult.Unknown(error.message)
                    else SendResult.Failed(error.message ?: BEFORE_THE_WALLET)
            }

        /** What the wallet said when it signed a transaction but reported no submission. */
        fun notSubmitted(error: WalletError): String =
            if (error.notSubmitted) {
                "the wallet signed the transaction but reported that it didn't send it"
            } else {
                error.message.orEmpty()
            }

        /**
         * What a failure to sign means for the owner. The wallet reports a refused signature as
         * NOT_SIGNED; an authorization it no longer honours comes back as AUTHORIZATION_FAILED, and
         * this app only ever signs with one it stored.
         */
        fun classifySigning(error: WalletError): SignResult =
            when (error.code) {
                ProtocolContract.ERROR_NOT_SIGNED -> SignResult.Declined
                ProtocolContract.ERROR_AUTHORIZATION_FAILED -> SignResult.AuthorizationExpired
                ProtocolContract.ERROR_INVALID_PAYLOADS ->
                    SignResult.Failed("the wallet would not take this message")
                else -> SignResult.Failed(error.message)
            }

        /**
         * What the wallet's error means for the owner. The wallet reports a refusal and an
         * authorization it no longer honours the same way, as AUTHORIZATION_FAILED, so
         * [hadAuthorization] tells them apart: a stored authorization was refused, and a fresh
         * request the owner saw was declined.
         */
        fun classify(error: WalletError, hadAuthorization: Boolean): WalletResult {
            return when (error.code) {
                ProtocolContract.ERROR_AUTHORIZATION_FAILED ->
                    if (hadAuthorization) WalletResult.AuthorizationExpired
                    else WalletResult.Declined
                ProtocolContract.ERROR_CLUSTER_NOT_SUPPORTED -> WalletResult.NetworkUnsupported
                else -> WalletResult.Failed(error.message)
            }
        }

        /** The wallet's own error code, from wherever in the chain of causes it is. */
        fun remoteCode(error: Exception?): Int? =
            causes(error)
                .filterIsInstance<JsonRpc20Client.JsonRpc20RemoteException>()
                .firstOrNull()
                ?.code

        /** What Mobile Wallet Adapter reported, in the terms this app classifies. */
        fun walletError(error: Exception?): WalletError =
            WalletError(
                code = remoteCode(error),
                notSubmitted = causes(error).any { it is NotSubmittedException },
                message = error?.message,
            )

        private fun causes(error: Exception?): Sequence<Throwable> =
            generateSequence(error as Throwable?) { it.cause }
    }
}

/** What a wallet's reauthorization says about the account the owner reviewed (SEE-84). */
internal enum class AccountCheck {
    /**
     * The wallet still authorizes that exact account, and contradicts nothing about its network.
     */
    Matches,
    /** The wallet no longer lists the reviewed account at all. */
    NotAuthorized,
    /** The wallet lists the account, and says it isn't on the reviewed network. */
    ChainMismatch,
}

private fun WalletNetwork.blockchain(): Blockchain =
    when (this) {
        WalletNetwork.Mainnet -> Solana.Mainnet
        WalletNetwork.Devnet -> Solana.Devnet
        WalletNetwork.Testnet -> Solana.Testnet
    }

/**
 * The authorization as this app reads it. The label a wallet leaves blank is no label, and the
 * chains it lists are taken exactly as they are: an empty list says nothing about any network.
 */
internal fun authorizationOf(result: AuthorizationResult?): WalletAuthorization =
    WalletAuthorization(
        token = result?.authToken?.takeIf { it.isNotEmpty() },
        accounts =
            result?.accounts?.filterNotNull().orEmpty().map { account ->
                WalletAccount(
                    address = encodeBase58(account.publicKey),
                    label = account.accountLabel?.takeIf { it.isNotBlank() },
                    chains = account.chains?.filterNotNull().orEmpty(),
                )
            },
    )

/**
 * One wallet session over Mobile Wallet Adapter (SEE-84). The client is made once and kept: it
 * learns the wallet's own endpoint while it authorizes, and that knowledge lives exactly as long as
 * this object does. It holds no transport open between calls — each one associates, does its work,
 * and closes — and the endpoint it learned is the client's own private state, which no server and
 * no stored file can set (docs/testing/wallet-lifecycle.md#wallet-targeting).
 */
private class MwaSession(
    identity: ConnectionIdentity,
    private val sender: suspend () -> ActivityResultSender?,
    override val network: WalletNetwork,
) : WalletSessionClient {
    private val client = MobileWalletAdapter(identity).apply { blockchain = network.blockchain() }

    override var authorization: String?
        get() = client.authToken
        set(value) {
            client.authToken = value
        }

    override suspend fun connect(): WalletOutcome<Unit> {
        val activity = sender() ?: return WalletOutcome.NoActivity
        return when (val result = client.connect(activity)) {
            is TransactionResult.Success ->
                WalletOutcome.Answered(Unit, authorizationOf(result.authResult))
            is TransactionResult.NoWalletFound -> WalletOutcome.NoWallet
            is TransactionResult.Failure ->
                WalletOutcome.Failed(MwaWalletAdapter.walletError(result.e), null)
        }
    }

    override suspend fun <T : Any> transact(
        block: suspend WalletRequests.(WalletAuthorization) -> T?
    ): WalletOutcome<T> {
        val activity = sender() ?: return WalletOutcome.NoActivity
        // What the wallet reported while reauthorizing, read inside the session rather than after
        // it: a session that then fails still tells this phone which token to keep.
        var reported: WalletAuthorization? = null
        val result =
            client.transact<T?>(activity) { authorization ->
                val seen = authorizationOf(authorization)
                reported = seen
                MwaRequests(this).block(seen)
            }
        val seen = reported
        return when (result) {
            is TransactionResult.Success ->
                WalletOutcome.Answered(result.payload, seen ?: authorizationOf(result.authResult))
            is TransactionResult.NoWalletFound -> WalletOutcome.NoWallet
            is TransactionResult.Failure ->
                WalletOutcome.Failed(MwaWalletAdapter.walletError(result.e), seen)
        }
    }

    override suspend fun close() {
        val activity = sender() ?: return
        client.disconnect(activity)
    }
}

/** What this app asks a wallet for, over the session Mobile Wallet Adapter opened. */
private class MwaRequests(private val operations: AdapterOperations) : WalletRequests {
    override suspend fun signMessages(
        messages: List<ByteArray>,
        addresses: List<ByteArray>,
    ): List<SignedMessage> {
        val result =
            operations.signMessagesDetached(messages.toTypedArray(), addresses.toTypedArray())
        return result.messages.filterNotNull().map { signed ->
            SignedMessage(
                message = signed.message,
                signatures = signed.signatures.toList(),
                addresses = signed.addresses.toList(),
            )
        }
    }

    override suspend fun signAndSend(transactions: List<ByteArray>): List<ByteArray?> =
        operations.signAndSendTransactions(transactions.toTypedArray()).signatures.toList()
}
