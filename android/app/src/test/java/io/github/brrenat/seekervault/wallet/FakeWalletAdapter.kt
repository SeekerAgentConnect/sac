package io.github.brrenat.seekervault.wallet

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
}
