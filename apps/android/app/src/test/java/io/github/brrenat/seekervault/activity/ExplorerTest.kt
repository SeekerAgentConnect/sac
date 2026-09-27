package io.github.brrenat.seekervault.activity

import io.github.brrenat.seekervault.request.v1.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The explorer link. The two things that must never happen: a link that names the wrong cluster,
 * and a link at all for a signature over a message.
 */
class ExplorerTest {
    private val signature = "5Yb4Dn9mFakeSignatureForTestsOnly1111111111111111111111111111"

    @Test
    fun namesTheClusterTheTransferWasBoundTo() {
        assertEquals(
            "https://explorer.solana.com/tx/$signature?cluster=devnet",
            explorerUrl(record(network = Network.NETWORK_DEVNET, signature = signature)),
        )
        assertEquals(
            "https://explorer.solana.com/tx/$signature?cluster=testnet",
            explorerUrl(record(network = Network.NETWORK_TESTNET, signature = signature)),
        )
        // Mainnet is the explorer's own default, so it takes no cluster.
        assertEquals(
            "https://explorer.solana.com/tx/$signature",
            explorerUrl(record(network = Network.NETWORK_MAINNET, signature = signature)),
        )
    }

    @Test
    fun aStakingActionLinksToItsOwnClusterAndALegacyOneWithoutContextGetsNone() {
        // SEE-165: a staking transaction is a transaction, and its record now says which cluster.
        val staking =
            record(kind = ActivityKind.Staking, signature = signature)
                .copy(
                    transfer = null,
                    staking =
                        ReviewedStaking(
                            wallet = WALLET,
                            network = Network.NETWORK_MAINNET,
                            operation = "STAKING_OPERATION_STAKE",
                            amount = "5",
                        ),
                )
        assertEquals("https://explorer.solana.com/tx/$signature", explorerUrl(staking))
        // One written before its terms were kept names no cluster, and a guessed link is wrong.
        assertNull(explorerUrl(staking.copy(staking = null)))
    }

    @Test
    fun offersNothingForASignatureOverAMessage() {
        // The signature is there, it is 64 bytes, and it is not a transaction. No explorer has it,
        // and a link would say it was a payment.
        val signed =
            record(
                kind = ActivityKind.MessageSignature,
                outcome = ActivityOutcome.MessageSigned,
                signature = signature,
            )
        assertEquals(false, signed.signatureIsTransaction)
        assertNull(explorerUrl(signed))
    }

    @Test
    fun offersNothingWithoutASignatureOrWithoutACluster() {
        assertNull(explorerUrl(record(signature = null)))
        assertNull(
            explorerUrl(record(network = Network.NETWORK_UNSPECIFIED, signature = signature))
        )
        assertNull(
            explorerUrl(
                record(kind = ActivityKind.Acknowledgement, outcome = ActivityOutcome.Acknowledged)
            )
        )
    }
}
