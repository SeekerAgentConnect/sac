package io.github.brrenat.seekervault.skr

import io.github.brrenat.seekervault.plugins.ActionOwner
import io.github.brrenat.seekervault.plugins.actionOwner
import io.github.brrenat.seekervault.request.v1.StakingOperation
import io.github.brrenat.seekervault.solana.SolanaProblem
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.associatedTokenAddress
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading one owner's position for ourselves, and the line this package does not cross (SEE-146).
 *
 * The server read the same accounts to build the transaction. Reading them again here is what makes
 * the review evidence rather than a restatement — and a reading that is half done is not a weaker
 * fact but a different one, so it is refused rather than interpreted.
 */
class SkrChainTest {

    private val ownerTokens = checkNotNull(associatedTokenAddress(OWNER, ADDRESSES.mint))

    private fun chain(
        config: ByteArray? = stakeConfigAccount(),
        pool: ByteArray? = guardianPoolAccount(),
        stake: ByteArray? = userStakeAccount(),
        tokens: ByteArray? = tokenAccount(),
        stakeOwner: String = SKR_STAKING_PROGRAM,
        configOwner: String = SKR_STAKING_PROGRAM,
    ) =
        FakeChain(
            buildMap {
                put(ADDRESSES.stakeConfig, config?.let { owned(it, configOwner) })
                put(ADDRESSES.guardianPool, pool?.let { owned(it) })
                put(OWNER_STAKE, stake?.let { owned(it, stakeOwner) })
                put(ownerTokens, tokens?.let { owned(it, TOKEN_PROGRAM) })
            }
        )

    @Test
    fun readsThePositionInOneCallSoItDescribesOneMoment() {
        val chain = chain()
        val read = checkNotNull(runBlocking { readSkrPosition(chain, OWNER) })
        assertEquals(ADDRESSES.mint, read.config.mint)
        assertEquals(BigIntegerHundredMillion, read.stake?.shares)
        assertEquals(500_000_000UL, read.ownerTokenAccount?.amount)
        assertTrue(read.guardian.active)
        // A share price from one slot and a share count from another would be a position that was
        // never true, so all four accounts are asked for together.
        assertEquals(1, chain.asked.size)
        assertEquals(
            listOf(ADDRESSES.stakeConfig, ADDRESSES.guardianPool, OWNER_STAKE, ownerTokens),
            chain.asked.single(),
        )
    }

    @Test
    fun anAbsentStakeOrTokenAccountIsAnAnswerRatherThanAFailure() {
        // Never having staked, and holding no SKR, are both real answers about a position.
        val read =
            checkNotNull(runBlocking { readSkrPosition(chain(stake = null, tokens = null), OWNER) })
        assertNull(read.stake)
        assertNull(read.ownerTokenAccount)
    }

    @Test
    fun refusesAStakeAccountOwnedBySomethingOtherThanTheProgram() {
        // Whatever it decodes as, an account another program owns is not the program's account.
        assertNull(runBlocking { readSkrPosition(chain(stakeOwner = TOKEN_PROGRAM), OWNER) })
        assertNull(runBlocking { readSkrPosition(chain(configOwner = TOKEN_PROGRAM), OWNER) })
    }

    @Test
    fun refusesAStakeAccountThatIsNotThisOwnersOwn() {
        val elsewhere = chain(stake = userStakeAccount(owner = STRANGER))
        assertNull(runBlocking { readSkrPosition(elsewhere, OWNER) })
    }

    @Test
    fun refusesAStakeAccountPointedAtAnotherConfigurationOrPool() {
        assertNull(
            runBlocking {
                readSkrPosition(chain(stake = userStakeAccount(stakeConfig = ELSEWHERE)), OWNER)
            }
        )
        assertNull(
            runBlocking {
                readSkrPosition(chain(stake = userStakeAccount(guardianPool = ELSEWHERE)), OWNER)
            }
        )
    }

    @Test
    fun refusesATokenAccountForAnotherMintOrOwner() {
        assertNull(
            runBlocking { readSkrPosition(chain(tokens = tokenAccount(mint = ELSEWHERE)), OWNER) }
        )
        assertNull(
            runBlocking { readSkrPosition(chain(tokens = tokenAccount(owner = STRANGER)), OWNER) }
        )
    }

    @Test
    fun aConfigurationOrPoolThatIsNotThereMeansNoReadingAtAll() {
        assertNull(runBlocking { readSkrPosition(chain(config = null), OWNER) })
        assertNull(runBlocking { readSkrPosition(chain(pool = null), OWNER) })
    }

    @Test
    fun anEndpointThatCannotBeReachedIsNoReadingRatherThanAnEmptyOne() {
        val failing = chain().apply { fails = SolanaProblem.Unreachable }
        assertNull(runBlocking { readSkrPosition(failing, OWNER) })
        // And a short answer is not a partial position either.
        val short = chain().apply { truncates = true }
        assertNull(runBlocking { readSkrPosition(short, OWNER) })
    }

    @Test
    fun aStakingActionIsCoresOwnAndNoProvidersToPrepare() {
        // Nothing is registered in the proposal registry and no plugin is consulted: this app
        // reads the bytes itself, against a program it carries the addresses for. A staking feed
        // is out of scope, so there is nothing for a publisher to propose.
        assertEquals(
            ActionOwner.Core,
            actionOwner(stakingRequest(StakingOperation.STAKING_OPERATION_STAKE)),
        )
    }

    @Test
    fun nothingInThisPackageSignsSendsOrHoldsAKey() {
        // The sandbox promise this ticket must not weaken is that no server, and no part of this
        // app but the wallet hand-off, can sign or broadcast. `skr/` reads: it turns bytes and
        // accounts into facts, and there is nothing in it that could do anything else.
        val repoRoot =
            File(
                checkNotNull(System.getProperty("seekervault.repoRoot")) {
                    "run this test through Gradle"
                }
            )
        val sources =
            File(repoRoot, "android/app/src/main/java/io/github/brrenat/seekervault/skr")
                .walk()
                .filter { it.extension == "kt" }
                .toList()
        assertTrue(sources.isNotEmpty())
        val acting =
            Regex(
                """signAndSendTransactions|signTransactions|sendTransaction|sendRawTransaction|""" +
                    """simulateTransaction|getLatestBlockhash|KeyPairGenerator|PrivateKey|""" +
                    """Signature\.getInstance|\bsign\("""
            )
        assertEquals(
            emptyList<String>(),
            sources.filter { acting.containsMatchIn(it.readText()) }.map { it.name },
        )
    }

    private companion object {
        val BigIntegerHundredMillion: java.math.BigInteger =
            java.math.BigInteger.valueOf(100_000_000L)
    }
}
