package io.github.brrenat.seekervault.skr

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the staking program's accounts, and refusing to read anything else (SEE-146).
 *
 * The share price, the cooldown and a share count are what an unstake or a withdrawal means
 * something in terms of. A decoder that read a field from the wrong offset, or read a struct that
 * was only partly there, would make the review confidently wrong — which is worse than a review
 * that says it could not read the position.
 */
class SkrAccountsTest {

    @Test
    fun readsTheConfigurationTheProgramKeeps() {
        val read = checkNotNull(decodeStakeConfig(stakeConfigAccount()))
        assertEquals(ADDRESSES.mint, read.mint)
        assertEquals(ADDRESSES.stakeVault, read.stakeVault)
        assertEquals(1_000_000UL, read.minStakeAmount)
        // 48 hours, read from the account rather than assumed.
        assertEquals(172_800UL, read.cooldownSeconds)
        assertEquals(BigInteger.valueOf(SHARE_PRICE_SCALE), read.sharePrice)
    }

    @Test
    fun refusesAConfigurationWithAnotherAccountsDiscriminator() {
        assertNull(decodeStakeConfig(stakeConfigAccount(discriminator = USER_STAKE_DISCRIMINATOR)))
    }

    @Test
    fun refusesAConfigurationOfTheWrongLength() {
        val short = stakeConfigAccount().copyOfRange(0, STAKE_CONFIG_BYTES - 1)
        assertNull(decodeStakeConfig(short))
        assertNull(decodeStakeConfig(stakeConfigAccount() + byteArrayOf(0)))
    }

    @Test
    fun refusesAZeroSharePrice() {
        // Nothing in this package divides by the share price after this: it never gets a zero.
        assertNull(decodeStakeConfig(stakeConfigAccount(sharePrice = BigInteger.ZERO)))
    }

    @Test
    fun readsAPositionAndWhetherAnythingIsPending() {
        val idle = checkNotNull(decodeUserStake(userStakeAccount()))
        assertEquals(OWNER, idle.user)
        assertEquals(ADDRESSES.stakeConfig, idle.stakeConfig)
        assertEquals(ADDRESSES.guardianPool, idle.guardianPool)
        assertEquals(BigInteger.valueOf(100_000_000L), idle.shares)
        assertFalse(idle.hasPendingUnstake)

        val pending =
            checkNotNull(
                decodeUserStake(
                    userStakeAccount(unstakingAmount = 5_000_000UL, unstakeTimestamp = 1_700_000L)
                )
            )
        assertTrue(pending.hasPendingUnstake)
        assertEquals(5_000_000UL, pending.unstakingAmount)
        assertEquals(1_700_000L, pending.unstakeTimestamp)
    }

    @Test
    fun readsASharesFieldThatDoesNotFitInALong() {
        // Shares are a u128 and are held as one. A decoder that narrowed them would report a
        // position that is not the owner's.
        val huge = BigInteger.TWO.pow(100).add(BigInteger.ONE)
        val read = checkNotNull(decodeUserStake(userStakeAccount(shares = huge)))
        assertEquals(huge, read.shares)
    }

    @Test
    fun refusesAPositionOfTheWrongLength() {
        assertNull(decodeUserStake(userStakeAccount().copyOfRange(0, USER_STAKE_BYTES - 8)))
    }

    @Test
    fun readsWhetherAPoolStillTakesStake() {
        assertTrue(checkNotNull(decodeGuardianPool(guardianPoolAccount())).active)
        assertFalse(checkNotNull(decodeGuardianPool(guardianPoolAccount(active = false))).active)
        assertEquals(
            ADDRESSES.guardian,
            checkNotNull(decodeGuardianPool(guardianPoolAccount())).guardian,
        )
    }

    @Test
    fun refusesAPoolWhoseFlagIsNeitherTrueNorFalse() {
        // A Borsh bool is 0 or 1. Anything else is not this account, however plausible it looks.
        assertNull(decodeGuardianPool(guardianPoolAccount(activeByte = 2)))
    }

    @Test
    fun readsTheOwnersSkrBalance() {
        val read = checkNotNull(decodeSkrTokenAccount(tokenAccount(amount = 12_345_678UL)))
        assertEquals(ADDRESSES.mint, read.mint)
        assertEquals(OWNER, read.owner)
        assertEquals(12_345_678UL, read.amount)
    }

    @Test
    fun refusesAnUninitializedTokenAccount() {
        // An account that exists but was never initialized holds no balance to read, and reading
        // its amount field would report a zero balance as a fact.
        assertNull(decodeSkrTokenAccount(tokenAccount(state = 0)))
    }

    @Test
    fun refusesATokenAccountOfAnotherShape() {
        // Token-2022 and extension-carrying accounts are a different length. The staking program
        // names the classic token program, so an account of any other shape is not one it accepts.
        assertNull(decodeSkrTokenAccount(tokenAccount() + ByteArray(16)))
    }

    @Test
    fun theProgramsAddressesAreDerivedAndNotBelieved() {
        // Only the program ID and the mint are compiled in. Everything else follows from them, so
        // a test that pinned the derived values would only be pinning today's copy of them; what
        // is worth pinning is that they derive at all and that they are all different accounts.
        assertEquals(SKR_STAKING_PROGRAM, ADDRESSES.program)
        assertEquals(SKR_MINT, ADDRESSES.mint)
        assertEquals(SKR_OFFICIAL_GUARDIAN, ADDRESSES.guardian)
        val derived =
            listOf(
                ADDRESSES.stakeConfig,
                ADDRESSES.stakeVault,
                ADDRESSES.guardianPool,
                ADDRESSES.eventAuthority,
                OWNER_STAKE,
            )
        assertEquals(derived.size, derived.distinct().size)
        // A stake account is per owner: somebody else's is a different account entirely.
        assertFalse(OWNER_STAKE == ADDRESSES.userStake(STRANGER))
    }
}
