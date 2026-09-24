package io.github.brrenat.seekervault.skr

import io.github.brrenat.seekervault.transactions.DecodedInstruction
import io.github.brrenat.seekervault.transactions.DecodedTransaction
import io.github.brrenat.seekervault.transactions.ReadInstruction
import io.github.brrenat.seekervault.transactions.readInstruction
import java.math.BigInteger

/**
 * Reading the SKR staking program's instructions out of a transaction (SEE-146).
 *
 * Accounts are positional, so each of these carries the accounts at the positions the program's own
 * IDL puts them in. Reading them by position rather than by searching is what lets the review say
 * "the vault this pays into" rather than "a vault appears somewhere in here".
 *
 * An instruction of this program that is not one of the four is [StakingStep.UnreadableStaking],
 * not "fine". A program being the right program is no reason to accept every instruction it offers.
 *
 * The trailing accounts — the token and system programs, the event authority and the program's own
 * ID — are carried rather than skipped over. They are the positions a caller has no reason to
 * change, which is exactly why a review that ignored them would be ignoring the cheapest thing to
 * check: every one of them has a value this app already knows or derives.
 */
sealed interface StakingStep {

    /** `stake`: SKR leaves the owner's token account for the vault, and shares are minted. */
    data class Stake(
        val userStake: String,
        val stakeConfig: String,
        val guardianPool: String,
        val payer: String,
        val user: String,
        val userTokenAccount: String,
        val stakeVault: String,
        val mint: String,
        val tokenProgram: String,
        val systemProgram: String,
        val eventAuthority: String,
        val programId: String,
        val amount: ULong,
    ) : StakingStep

    /** `unstake`: shares are burned and their value starts a cooldown. Nothing moves yet. */
    data class Unstake(
        val userStake: String,
        val stakeConfig: String,
        val guardianPool: String,
        val user: String,
        val stakeVault: String,
        val mint: String,
        val eventAuthority: String,
        val programId: String,
        val shares: BigInteger,
    ) : StakingStep

    /** `cancel_unstake`: the whole pending amount goes back to work. It takes no argument. */
    data class CancelUnstake(
        val userStake: String,
        val stakeConfig: String,
        val guardianPool: String,
        val user: String,
        val stakeVault: String,
        val eventAuthority: String,
        val programId: String,
    ) : StakingStep

    /** `withdraw`: the cooled-down amount leaves the vault for the owner. It takes no argument. */
    data class Withdraw(
        val userStake: String,
        val stakeConfig: String,
        val user: String,
        val stakeVault: String,
        val userTokenAccount: String,
        val tokenProgram: String,
        val eventAuthority: String,
        val programId: String,
    ) : StakingStep

    /**
     * The staking program asked to do something else. Named separately from an unknown program
     * because it is worse: this program can move the owner's whole position.
     */
    data class UnreadableStaking(val discriminator: String, val accounts: List<String>) :
        StakingStep

    /** Anything not this program, read by the shared reader. */
    data class Other(val read: ReadInstruction) : StakingStep
}

/**
 * Reads one instruction, or null when its account indexes point outside the transaction's account
 * list — which makes the whole transaction malformed rather than this instruction unknown.
 */
fun DecodedTransaction.readStakingStep(instruction: DecodedInstruction): StakingStep? {
    val program = programOf(instruction) ?: return null
    val accounts = accountsOf(instruction) ?: return null
    return stakingStep(program, accounts, instruction.data)
}

/**
 * The same reading over a program, its accounts and its data.
 *
 * Split out for the same reason the shared reader is: what an instruction means does not depend on
 * whether its accounts came from the message or from a lookup table, so it is decided in one place
 * for both.
 */
fun stakingStep(program: String, accounts: List<String>, data: ByteArray): StakingStep {
    if (program != SKR_STAKING_PROGRAM) {
        return StakingStep.Other(readInstruction(program, accounts, data))
    }
    if (data.size < 8) return unreadable(data, accounts)
    val discriminator = data.copyOfRange(0, 8)
    val arguments = data.copyOfRange(8, data.size)
    return when {
        discriminator.contentEquals(STAKE_DISCRIMINATOR) ->
            readStake(arguments, accounts) ?: unreadable(data, accounts)
        discriminator.contentEquals(UNSTAKE_DISCRIMINATOR) ->
            readUnstake(arguments, accounts) ?: unreadable(data, accounts)
        discriminator.contentEquals(CANCEL_UNSTAKE_DISCRIMINATOR) ->
            readCancelUnstake(arguments, accounts) ?: unreadable(data, accounts)
        discriminator.contentEquals(WITHDRAW_DISCRIMINATOR) ->
            readWithdraw(arguments, accounts) ?: unreadable(data, accounts)
        else -> unreadable(data, accounts)
    }
}

private const val STAKE_ACCOUNTS = 12
private const val UNSTAKE_ACCOUNTS = 8
private const val CANCEL_UNSTAKE_ACCOUNTS = 7
private const val WITHDRAW_ACCOUNTS = 8

private fun readStake(arguments: ByteArray, accounts: List<String>): StakingStep? {
    if (accounts.size != STAKE_ACCOUNTS) return null
    val amount = u64(arguments) ?: return null
    return StakingStep.Stake(
        userStake = accounts[0],
        stakeConfig = accounts[1],
        guardianPool = accounts[2],
        payer = accounts[3],
        user = accounts[4],
        userTokenAccount = accounts[5],
        stakeVault = accounts[6],
        mint = accounts[7],
        tokenProgram = accounts[8],
        systemProgram = accounts[9],
        eventAuthority = accounts[10],
        programId = accounts[11],
        amount = amount,
    )
}

private fun readUnstake(arguments: ByteArray, accounts: List<String>): StakingStep? {
    if (accounts.size != UNSTAKE_ACCOUNTS) return null
    if (arguments.size != 16) return null
    return StakingStep.Unstake(
        userStake = accounts[0],
        stakeConfig = accounts[1],
        guardianPool = accounts[2],
        user = accounts[3],
        stakeVault = accounts[4],
        mint = accounts[5],
        eventAuthority = accounts[6],
        programId = accounts[7],
        shares = BigInteger(1, arguments.reversedArray()),
    )
}

private fun readCancelUnstake(arguments: ByteArray, accounts: List<String>): StakingStep? {
    if (accounts.size != CANCEL_UNSTAKE_ACCOUNTS) return null
    // No argument at all. Trailing bytes would mean this is not the instruction it claims to be.
    if (arguments.isNotEmpty()) return null
    return StakingStep.CancelUnstake(
        userStake = accounts[0],
        stakeConfig = accounts[1],
        guardianPool = accounts[2],
        user = accounts[3],
        stakeVault = accounts[4],
        eventAuthority = accounts[5],
        programId = accounts[6],
    )
}

private fun readWithdraw(arguments: ByteArray, accounts: List<String>): StakingStep? {
    if (accounts.size != WITHDRAW_ACCOUNTS) return null
    if (arguments.isNotEmpty()) return null
    return StakingStep.Withdraw(
        userStake = accounts[0],
        stakeConfig = accounts[1],
        user = accounts[2],
        stakeVault = accounts[3],
        userTokenAccount = accounts[4],
        tokenProgram = accounts[5],
        eventAuthority = accounts[6],
        programId = accounts[7],
    )
}

private fun unreadable(data: ByteArray, accounts: List<String>) =
    StakingStep.UnreadableStaking(
        discriminator = data.take(8).joinToString("") { "%02x".format(it) },
        accounts = accounts,
    )

/** A little-endian u64, and only when the argument bytes are exactly one. */
private fun u64(arguments: ByteArray): ULong? {
    if (arguments.size != 8) return null
    var value = 0UL
    for (index in 7 downTo 0) {
        value = (value shl 8) or (arguments[index].toULong() and 0xffUL)
    }
    return value
}
