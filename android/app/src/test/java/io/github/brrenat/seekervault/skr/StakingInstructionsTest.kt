package io.github.brrenat.seekervault.skr

import io.github.brrenat.seekervault.request.v1.StakingOperation
import io.github.brrenat.seekervault.transactions.ReadInstruction
import io.github.brrenat.seekervault.transactions.SYSTEM_PROGRAM
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the staking program's four instructions, and naming everything else (SEE-146).
 *
 * Accounts are positional, so the reading is only worth anything if each position is the one the
 * program's IDL puts there. What these cases hold is that: the right count, the right argument
 * width, and an instruction of this program that is none of the four read as the dangerous thing it
 * is rather than as an unknown.
 */
class StakingInstructionsTest {

    private fun accountsFor(operation: StakingOperation) =
        when (operation) {
            StakingOperation.STAKING_OPERATION_STAKE ->
                listOf(
                    OWNER_STAKE,
                    ADDRESSES.stakeConfig,
                    ADDRESSES.guardianPool,
                    OWNER,
                    OWNER,
                    OWNER_TOKENS,
                    ADDRESSES.stakeVault,
                    ADDRESSES.mint,
                    TOKEN_PROGRAM,
                    SYSTEM_PROGRAM,
                    ADDRESSES.eventAuthority,
                    ADDRESSES.program,
                )
            StakingOperation.STAKING_OPERATION_UNSTAKE ->
                listOf(
                    OWNER_STAKE,
                    ADDRESSES.stakeConfig,
                    ADDRESSES.guardianPool,
                    OWNER,
                    ADDRESSES.stakeVault,
                    ADDRESSES.mint,
                    ADDRESSES.eventAuthority,
                    ADDRESSES.program,
                )
            StakingOperation.STAKING_OPERATION_CANCEL_UNSTAKE ->
                listOf(
                    OWNER_STAKE,
                    ADDRESSES.stakeConfig,
                    ADDRESSES.guardianPool,
                    OWNER,
                    ADDRESSES.stakeVault,
                    ADDRESSES.eventAuthority,
                    ADDRESSES.program,
                )
            else ->
                listOf(
                    OWNER_STAKE,
                    ADDRESSES.stakeConfig,
                    OWNER,
                    ADDRESSES.stakeVault,
                    OWNER_TOKENS,
                    TOKEN_PROGRAM,
                    ADDRESSES.eventAuthority,
                    ADDRESSES.program,
                )
        }

    private val stake = StakingOperation.STAKING_OPERATION_STAKE
    private val unstake = StakingOperation.STAKING_OPERATION_UNSTAKE
    private val cancel = StakingOperation.STAKING_OPERATION_CANCEL_UNSTAKE
    private val withdraw = StakingOperation.STAKING_OPERATION_WITHDRAW

    private fun read(
        operation: StakingOperation,
        data: ByteArray,
        accounts: List<String> = accountsFor(operation),
    ) = stakingStep(SKR_STAKING_PROGRAM, accounts, data)

    @Test
    fun readsAStakeAtEveryPosition() {
        val step = read(stake, STAKE_DISCRIMINATOR + u64(7_500_000UL)) as StakingStep.Stake
        assertEquals(OWNER_STAKE, step.userStake)
        assertEquals(ADDRESSES.stakeConfig, step.stakeConfig)
        assertEquals(ADDRESSES.guardianPool, step.guardianPool)
        assertEquals(OWNER, step.payer)
        assertEquals(OWNER, step.user)
        assertEquals(OWNER_TOKENS, step.userTokenAccount)
        assertEquals(ADDRESSES.stakeVault, step.stakeVault)
        assertEquals(ADDRESSES.mint, step.mint)
        // The positions nobody has a reason to change are read too, so the review can say so.
        assertEquals(TOKEN_PROGRAM, step.tokenProgram)
        assertEquals(SYSTEM_PROGRAM, step.systemProgram)
        assertEquals(ADDRESSES.eventAuthority, step.eventAuthority)
        assertEquals(ADDRESSES.program, step.programId)
        assertEquals(7_500_000UL, step.amount)
    }

    @Test
    fun readsAnUnstakeAsShares() {
        // The program burns shares, not SKR: a u128 argument, and a reader that narrowed it would
        // agree with the server about a number neither of them had read.
        val shares = BigInteger.TWO.pow(70).add(BigInteger.valueOf(3))
        val step = read(unstake, UNSTAKE_DISCRIMINATOR + u128(shares)) as StakingStep.Unstake
        assertEquals(shares, step.shares)
        assertEquals(ADDRESSES.mint, step.mint)
        assertEquals(ADDRESSES.program, step.programId)
    }

    @Test
    fun readsCancelAndWithdrawAsTakingNoArgument() {
        val cancelled = read(cancel, CANCEL_UNSTAKE_DISCRIMINATOR) as StakingStep.CancelUnstake
        assertEquals(ADDRESSES.stakeVault, cancelled.stakeVault)
        val withdrawn = read(withdraw, WITHDRAW_DISCRIMINATOR) as StakingStep.Withdraw
        assertEquals(OWNER_TOKENS, withdrawn.userTokenAccount)
        assertEquals(TOKEN_PROGRAM, withdrawn.tokenProgram)
    }

    @Test
    fun refusesAnArgumentOfTheWrongWidth() {
        // A stake's amount is exactly eight bytes and an unstake's shares exactly sixteen. Trailing
        // bytes mean this is not the instruction it says it is, whatever the first eight say.
        assertTrue(
            read(stake, STAKE_DISCRIMINATOR + u64(1UL) + byteArrayOf(0))
                is StakingStep.UnreadableStaking
        )
        assertTrue(read(unstake, UNSTAKE_DISCRIMINATOR + u64(1UL)) is StakingStep.UnreadableStaking)
        assertTrue(
            read(cancel, CANCEL_UNSTAKE_DISCRIMINATOR + byteArrayOf(0))
                is StakingStep.UnreadableStaking
        )
        assertTrue(
            read(withdraw, WITHDRAW_DISCRIMINATOR + u64(9UL)) is StakingStep.UnreadableStaking
        )
    }

    @Test
    fun refusesTheRightInstructionWithTheWrongNumberOfAccounts() {
        val short = accountsFor(stake).dropLast(1)
        assertTrue(
            read(stake, STAKE_DISCRIMINATOR + u64(1UL), short) is StakingStep.UnreadableStaking
        )
        val long = accountsFor(withdraw) + ELSEWHERE
        assertTrue(read(withdraw, WITHDRAW_DISCRIMINATOR, long) is StakingStep.UnreadableStaking)
    }

    @Test
    fun namesAnInstructionOfThisProgramThatIsNoneOfTheFour() {
        // Worse than an unknown program, and read as such: this program can move the whole
        // position, so a fifth instruction is something the owner is being asked to sign blind.
        val step = read(stake, ByteArray(8) { 0x5a } + u64(1UL)) as StakingStep.UnreadableStaking
        assertEquals("5a5a5a5a5a5a5a5a", step.discriminator)
        assertEquals(accountsFor(stake), step.accounts)
    }

    @Test
    fun refusesDataTooShortToCarryADiscriminator() {
        assertTrue(read(stake, byteArrayOf(1, 2, 3)) is StakingStep.UnreadableStaking)
    }

    @Test
    fun handsAnythingElseToTheSharedReader() {
        // One decoder for the message and one reader for the instructions everybody else's
        // programs contain: `skr/` adds a reading of its own program and borrows the rest.
        val transfer = tokenTransfer(OWNER_TOKENS, ADDRESSES.stakeVault, OWNER, 1_000UL)
        val step =
            stakingStep(transfer.program, transfer.accounts, transfer.data) as StakingStep.Other
        assertTrue(step.read is ReadInstruction.TokenTransfer)
    }
}
