package io.github.brrenat.seekervault.skr

import io.github.brrenat.seekervault.wallet.encodeBase58
import java.math.BigInteger

/**
 * Reading the staking program's accounts on the phone (SEE-146).
 *
 * The server reads the same accounts to build a transaction. This phone reads them again to check
 * the transaction, and that is the point: a share count, a share price and a cooldown are the facts
 * an unstake or a withdrawal means something in terms of, and taking the server's word for any of
 * them would leave the review verifying the server's arithmetic against itself.
 *
 * Every decoder checks the discriminator and the exact length, and returns null rather than a
 * partly-read struct. A field that could not be read is absent, never zero: zero shares and unread
 * shares are different facts and only one of them is safe to act on.
 */

/** The byte length each account occupies on chain, discriminator included. */
const val STAKE_CONFIG_BYTES = 193
const val USER_STAKE_BYTES = 169
const val GUARDIAN_POOL_BYTES = 188
const val SPL_TOKEN_ACCOUNT_BYTES = 165

/** The program's one configuration account, as far as a review needs it. */
data class SkrStakeConfig(
    val mint: String,
    val stakeVault: String,
    /** The smallest stake the program accepts, in base units. */
    val minStakeAmount: ULong,
    /** How long an unstake waits. Read from here, so a deployment that changes it is followed. */
    val cooldownSeconds: ULong,
    /** Scaled by [SHARE_PRICE_SCALE]. A u128, so it is a [BigInteger]. */
    val sharePrice: BigInteger,
)

/** One owner's stake with one guardian. */
data class SkrUserStake(
    val stakeConfig: String,
    val user: String,
    val guardianPool: String,
    /** Shares still earning. A u128. */
    val shares: BigInteger,
    /** Base units waiting out a cooldown, fixed when the unstake was made. */
    val unstakingAmount: ULong,
    /** When that cooldown started; 0 when nothing is pending. */
    val unstakeTimestamp: Long,
) {
    /** Whether anything is waiting to be withdrawn or cancelled. */
    val hasPendingUnstake: Boolean
        get() = unstakingAmount > 0UL
}

/** A guardian's pool, read only to establish that it is one this program still takes stake into. */
data class SkrGuardianPool(val stakeConfig: String, val guardian: String, val active: Boolean)

/** An SPL token account, read for the owner's own SKR balance. */
data class SkrTokenAccount(val mint: String, val owner: String, val amount: ULong)

/** Reads a [SkrStakeConfig], or null when the bytes are not one. */
fun decodeStakeConfig(data: ByteArray): SkrStakeConfig? {
    if (data.size != STAKE_CONFIG_BYTES) return null
    val reader = AccountReader(data, STAKE_CONFIG_DISCRIMINATOR) ?: return null
    reader.skip(1) ?: return null // bump
    reader.key() ?: return null // authority
    val mint = reader.key() ?: return null
    val vault = reader.key() ?: return null
    val minimum = reader.u64() ?: return null
    val cooldown = reader.u64() ?: return null
    reader.u128() ?: return null // total_shares
    val price = reader.u128() ?: return null
    // The program refuses a zero share price itself; refusing it here means no arithmetic in this
    // package has to guard against dividing by it.
    if (price.signum() <= 0) return null
    return SkrStakeConfig(mint, vault, minimum, cooldown, price)
}

/** Reads a [SkrUserStake], or null when the bytes are not one. */
fun decodeUserStake(data: ByteArray): SkrUserStake? {
    if (data.size != USER_STAKE_BYTES) return null
    val reader = AccountReader(data, USER_STAKE_DISCRIMINATOR) ?: return null
    reader.skip(1) ?: return null // bump
    val config = reader.key() ?: return null
    val user = reader.key() ?: return null
    val pool = reader.key() ?: return null
    val shares = reader.u128() ?: return null
    reader.u128() ?: return null // cost_basis
    reader.u128() ?: return null // cumulative_commission_before_staking
    val unstaking = reader.u64() ?: return null
    val timestamp = reader.i64() ?: return null
    return SkrUserStake(config, user, pool, shares, unstaking, timestamp)
}

/** Reads a [SkrGuardianPool], or null when the bytes are not one. */
fun decodeGuardianPool(data: ByteArray): SkrGuardianPool? {
    if (data.size != GUARDIAN_POOL_BYTES) return null
    val reader = AccountReader(data, GUARDIAN_POOL_DISCRIMINATOR) ?: return null
    val config = reader.key() ?: return null
    val guardian = reader.key() ?: return null
    reader.key() ?: return null // authority
    reader.u128() ?: return null // total_shares
    reader.u128() ?: return null // cumulative_commission_per_share
    reader.u128() ?: return null // last_share_price
    reader.u128() ?: return null // accrued_commission
    reader.skip(2) ?: return null // commission_bps
    reader.skip(1) ?: return null // bump
    val active = reader.bool() ?: return null
    return SkrGuardianPool(config, guardian, active)
}

/**
 * Reads a classic SPL token account. A Token-2022 account or one carrying extensions is a different
 * length and is refused: the staking program names the classic token program, so an account of any
 * other shape is not one it would accept either.
 */
fun decodeSkrTokenAccount(data: ByteArray): SkrTokenAccount? {
    if (data.size != SPL_TOKEN_ACCOUNT_BYTES) return null
    // Byte 108 is the account's state; 0 is uninitialized, which has no balance to read.
    if (data[108].toInt() == 0) return null
    val reader = AccountReader(data, null) ?: return null
    val mint = reader.key() ?: return null
    val owner = reader.key() ?: return null
    val amount = reader.u64() ?: return null
    return SkrTokenAccount(mint, owner, amount)
}

/**
 * A little-endian cursor over one account, refusing to read past the end.
 *
 * Separate from `transactions/Reader.kt` because that one reads a transaction's wire format and
 * this one reads Borsh account data; sharing a class would tie two formats owned by different
 * people to one file's idea of what a field is.
 */
private class AccountReader
private constructor(private val data: ByteArray, private var offset: Int) {
    companion object {
        /**
         * A reader positioned past the discriminator, or null when it is not the expected one. A
         * null [expected] starts at the beginning, for an account that carries no discriminator.
         */
        operator fun invoke(data: ByteArray, expected: ByteArray?): AccountReader? {
            if (expected == null) return AccountReader(data, 0)
            if (data.size < expected.size) return null
            if (!data.copyOfRange(0, expected.size).contentEquals(expected)) return null
            return AccountReader(data, expected.size)
        }
    }

    fun skip(count: Int): Unit? = take(count)?.let {}

    fun bool(): Boolean? {
        val byte = take(1)?.get(0)?.toInt()?.and(0xff) ?: return null
        return if (byte > 1) null else byte == 1
    }

    fun key(): String? = take(32)?.let(::encodeBase58)

    fun u64(): ULong? = take(8)?.let { little -> littleEndian(it = little).toULongOrNull() }

    fun i64(): Long? {
        val raw = take(8) ?: return null
        var value = 0L
        for (index in 7 downTo 0) {
            value = (value shl 8) or (raw[index].toLong() and 0xff)
        }
        return value
    }

    fun u128(): BigInteger? = take(16)?.let { littleEndian(it) }

    private fun take(count: Int): ByteArray? {
        if (count < 0 || data.size - offset < count) return null
        val slice = data.copyOfRange(offset, offset + count)
        offset += count
        return slice
    }
}

/** Little-endian bytes as a non-negative integer. */
private fun littleEndian(it: ByteArray): BigInteger = BigInteger(1, it.reversedArray())

/** A [BigInteger] as a [ULong], or null when it does not fit one. */
private fun BigInteger.toULongOrNull(): ULong? {
    if (signum() < 0 || bitLength() > 64) return null
    return toLong().toULong()
}
