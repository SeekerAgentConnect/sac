package io.github.brrenat.seekervault.wallet

import com.google.protobuf.ByteString

/**
 * A wallet that answers whatever a test says, without an activity or an installed wallet app. It
 * records what it was asked, so a test can check that the stored authorization is reused and that
 * disconnecting reaches the wallet.
 */
class FakeWalletAdapter(private var next: () -> WalletResult = { WalletResult.NoWallet }) :
    WalletAdapter {
    /** Every connect: the network asked for, and the authorization the phone offered. */
    val connects = mutableListOf<Pair<WalletNetwork, String?>>()
    /** Every authorization the phone told the wallet to forget. */
    val disconnects = mutableListOf<String>()
    /** Every signing: the bytes, the wallet they were for, and the authorization offered. */
    val signings = mutableListOf<Triple<ByteString, SelectedWallet, String>>()
    /** Every sign-and-send: the exact transaction bytes, the wallet, and the authorization. */
    val sendings = mutableListOf<Triple<ByteString, SelectedWallet, String>>()

    private var nextSignature: (ByteString) -> SignResult = { SignResult.NoWallet }
    private var nextSend: (ByteString) -> SendResult = { SendResult.NoWallet }

    /**
     * The authorization the wallet reports while it signs, as a real one does when it replaces the
     * app's. Null means it reported none, and the phone keeps what it had.
     */
    var refreshedAuthorization: String? = null

    /**
     * Runs after a signing is recorded and before it answers, so a test can hold the wallet open
     * the way the owner deciding in it does, or make something happen while it is in front.
     */
    var beforeSigning: suspend () -> Unit = {}

    /** The same hold for a transaction the wallet has been handed to sign and send. */
    var beforeSending: suspend () -> Unit = {}

    fun answer(result: WalletResult) {
        next = { result }
    }

    /** Answers with the account [address], and an authorization the wallet made up. */
    fun answerConnected(
        address: String,
        authToken: String = "authorization-$address",
        label: String? = null,
        chains: List<String> = emptyList(),
    ) = answer(WalletResult.Connected(WalletAccount(address, label, chains), authToken))

    override suspend fun connect(network: WalletNetwork, authToken: String?): WalletResult {
        connects += network to authToken
        return next()
    }

    override suspend fun disconnect(authToken: String) {
        disconnects += authToken
    }

    /** The next signing answers with [result], whatever it is asked to sign. */
    fun answerSigning(result: SignResult) {
        nextSignature = { result }
    }

    /** The next signing succeeds, over exactly the bytes it was given. */
    fun signWith(signature: ByteString) {
        nextSignature = { message ->
            SignResult.Signed(message, signings.last().second.address, signature)
        }
    }

    override suspend fun signMessage(
        message: ByteString,
        wallet: SelectedWallet,
        authToken: String,
    ): SigningAnswer {
        signings += Triple(message, wallet, authToken)
        beforeSigning()
        return SigningAnswer(nextSignature(message), refreshedAuthorization)
    }

    /** The next sign-and-send answers with [result], whatever it is handed. */
    fun answerSending(result: SendResult) {
        nextSend = { result }
    }

    /** The next sign-and-send succeeds, reporting [signature] as the transaction's ID. */
    fun sendWith(signature: ByteString) {
        nextSend = { SendResult.Sent(signature) }
    }

    override suspend fun signAndSendTransaction(
        transaction: ByteString,
        wallet: SelectedWallet,
        authToken: String,
    ): SendResult {
        sendings += Triple(transaction, wallet, authToken)
        beforeSending()
        return nextSend(transaction)
    }
}
