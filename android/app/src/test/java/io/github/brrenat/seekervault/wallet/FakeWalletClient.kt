package io.github.brrenat.seekervault.wallet

/** A wallet session that broke while it was being asked for something. */
private class SessionBroke(val error: WalletError) : Exception(error.message)

/**
 * A wallet session that answers whatever a test says, without a wallet app, an activity, or Mobile
 * Wallet Adapter (SEE-84). It is the narrow boundary `MwaWalletAdapter` drives a real wallet
 * through, so the adapter's own behaviour — what it does with a reauthorization, what it refuses to
 * put in front of the wallet, and what it makes of a session that ends badly — is exercised here.
 *
 * It records every session it opened and every request it was really asked for, so a test can tell
 * a wallet that was never asked from one that was asked and didn't answer.
 */
class FakeWalletClient(override val network: WalletNetwork = WalletNetwork.Devnet) :
    WalletSessionClient {
    override var authorization: String? = null

    /** The authorization offered at the start of each session, in order. */
    val offered = mutableListOf<String?>()

    /** The messages the wallet was really asked to sign, and the accounts asked to sign them. */
    val signings = mutableListOf<Pair<ByteArray, ByteArray>>()

    /** The transactions the wallet was really handed to sign and send. */
    val sendings = mutableListOf<ByteArray>()

    /** Every authorization this app told the wallet to forget. */
    val closes = mutableListOf<String?>()

    /** What the wallet reports while it reauthorizes this app at the start of a session. */
    var authorizes: WalletAuthorization? = null

    /**
     * Answers without opening a wallet at all: no wallet installed, or no screen to open it from.
     */
    var opens: WalletOutcome<Nothing>? = null

    /** The session ends in this error before the wallet is asked for anything. */
    var failsBeforeAsking: WalletError? = null

    /** The wallet takes the request, and then the session breaks with this error. */
    var failsWhileAsking: WalletError? = null

    /** What the wallet answers when it is asked to sign a message. */
    var signs: List<SignedMessage> = emptyList()

    /** What the wallet answers when it is asked to sign and send: the signatures it reported. */
    var sends: List<ByteArray?> = emptyList()

    /** Answers with [token] and one account on this session's own chain. */
    fun authorizes(address: String, token: String?, chains: List<String> = listOf(network.chain)) {
        authorizes = WalletAuthorization(token, listOf(WalletAccount(address, null, chains)))
    }

    override suspend fun connect(): WalletOutcome<Unit> {
        offered += authorization
        opens?.let {
            return it
        }
        failsBeforeAsking?.let {
            return WalletOutcome.Failed(it, authorizes)
        }
        return WalletOutcome.Answered(Unit, authorizes)
    }

    override suspend fun <T : Any> transact(
        block: suspend WalletRequests.(WalletAuthorization) -> T?
    ): WalletOutcome<T> {
        offered += authorization
        opens?.let {
            return it
        }
        failsBeforeAsking?.let {
            return WalletOutcome.Failed(it, authorizes)
        }
        val reported = authorizes ?: WalletAuthorization(null, emptyList())
        return try {
            WalletOutcome.Answered(Requests().block(reported), authorizes)
        } catch (broke: SessionBroke) {
            WalletOutcome.Failed(broke.error, authorizes)
        }
    }

    override suspend fun close() {
        closes += authorization
    }

    private inner class Requests : WalletRequests {
        override suspend fun signMessages(
            messages: List<ByteArray>,
            addresses: List<ByteArray>,
        ): List<SignedMessage> {
            messages.forEachIndexed { index, message -> signings += message to addresses[index] }
            failsWhileAsking?.let { throw SessionBroke(it) }
            return signs
        }

        override suspend fun signAndSend(transactions: List<ByteArray>): List<ByteArray?> {
            sendings += transactions
            failsWhileAsking?.let { throw SessionBroke(it) }
            return sends
        }
    }
}
