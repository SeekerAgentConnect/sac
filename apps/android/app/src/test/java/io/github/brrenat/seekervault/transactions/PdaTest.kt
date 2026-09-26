package io.github.brrenat.seekervault.transactions

import io.github.brrenat.seekervault.wallet.decodeBase58
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deriving a token account here (SAW-020). This is the only thing that ties a token account to an
 * owner without asking a server, so it has to agree exactly with what the chain does — and with
 * what the sidecar derived when it built the transaction.
 */
class PdaTest {
    private val owner = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    private val usdc = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private val wrappedSol = "So11111111111111111111111111111111111111112"

    @Test
    fun derivesTheSameAccountTheSidecarDoes() {
        // The MCP server froze this same pair in mcp-server/src/solana/token.test.ts, derived by a
        // different implementation. Two independent derivations agreeing is the check.
        assertEquals(
            "FGETo8T8wMcN2wCjav8VK6eh3dLk63evNDPxzLSJra8B",
            associatedTokenAddress(owner, usdc),
        )
    }

    @Test
    fun givesEachOwnerAndEachMintItsOwnAccount() {
        val account = associatedTokenAddress(owner, usdc)
        assertNotEquals(account, associatedTokenAddress(owner, wrappedSol))
        assertNotEquals(account, associatedTokenAddress(wrappedSol, usdc))
        assertEquals(account, associatedTokenAddress(owner, usdc))
    }

    @Test
    fun derivesAnAddressNoKeyCanSignFor() {
        val account = checkNotNull(associatedTokenAddress(owner, usdc))
        assertTrue(!isOnCurve(checkNotNull(decodeBase58(account))))
    }

    @Test
    fun tellsARealPublicKeyFromADerivedAddress() {
        // A wallet address is a public key, so it is on the curve; a derived one never is.
        assertTrue(isOnCurve(checkNotNull(decodeBase58(owner))))
        assertTrue(isOnCurve(checkNotNull(decodeBase58(wrappedSol))))
        val derived = checkNotNull(associatedTokenAddress(owner, usdc))
        assertTrue(!isOnCurve(checkNotNull(decodeBase58(derived))))
    }

    @Test
    fun refusesBytesThatAreNotAPoint() {
        assertTrue(!isOnCurve(ByteArray(31)))
        assertTrue(!isOnCurve(ByteArray(33)))
        // y above the field prime is not a canonical encoding of anything.
        assertTrue(!isOnCurve(ByteArray(32) { if (it == 31) 0x7f else 0xff.toByte() }))
    }

    @Test
    fun returnsNullForAnAddressThatIsNotOne() {
        assertNull(associatedTokenAddress("not-an-address", usdc))
        assertNull(associatedTokenAddress(owner, "not-a-mint"))
        assertNull(associatedTokenAddress(owner, "1"))
    }

    @Test
    fun reportsTheBumpItUsed() {
        val (address, bump) =
            checkNotNull(
                findProgramAddress(
                    listOf(
                        checkNotNull(decodeBase58(owner)),
                        checkNotNull(decodeBase58(TOKEN_PROGRAM)),
                        checkNotNull(decodeBase58(usdc)),
                    ),
                    ASSOCIATED_TOKEN_PROGRAM,
                )
            )
        assertEquals(associatedTokenAddress(owner, usdc), address)
        // The runtime takes the highest bump that works, so a canonical one is near the top.
        assertTrue("bump $bump", bump in 240..255)
    }
}
