package io.github.brrenat.seekervault.jupiter

import io.github.brrenat.seekervault.solana.AccountSnapshot
import io.github.brrenat.seekervault.solana.SolanaAccounts
import io.github.brrenat.seekervault.solana.SolanaException
import io.github.brrenat.seekervault.solana.SolanaProblem
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM
import io.github.brrenat.seekervault.wallet.encodeBase58
import io.github.brrenat.seekervault.wallet.isSolanaAddress

/**
 * The SAC service fee on swaps: an optional integrator fee this build's owner configured (SEE-173).
 *
 * It is the **build's** policy and nothing else's. The rate and the accounts come from
 * `BuildConfig` (docs/development/swap-fee-config.md); a publisher's signal, a server's request, a
 * QR payload or the provider's answer has no field that can raise it, lower it or redirect it. The
 * default build charges nothing.
 *
 * ## What it can take, and where it goes
 *
 * Metis (Jupiter's Swap API v1) takes a platform fee in basis points on the quote and the account
 * that receives it on the build. For an exact-input swap the fee is taken **from the output**, in
 * the output mint, and the quote's `outAmount` is already net of it — which is the only shape this
 * app reads. So a fee is charged on a swap only when:
 *
 * 1. this build has a nonzero rate and a receiving token account configured for the swap's
 *    **output** mint (the policy: one account per mint, never one address for every pair);
 * 2. that account was read from the chain just before quoting, through the app's own endpoint, and
 *    it is a classic SPL Token account for that mint, owned by the configured recipient,
 *    initialized and not frozen ([verifyFeeAccount]).
 *
 * Anything else is a swap with **no** fee, and the review says which of those it was
 * ([SwapFee.NotForPair], [SwapFee.Unverified]). Nothing here can produce a fee the owner was not
 * shown, because the inspection then checks the transaction's fee account, rate and mint against
 * exactly this decision ([inspectSwap]).
 */
data class SwapFeePolicy(
    /** Basis points of the output, `0` for none. Bounded by [MOST_SWAP_FEE_BPS]. */
    val bps: Int,
    /** The public wallet that owns every receiving token account. */
    val owner: String?,
    /** Output mint → the public token account that receives the fee in that mint. */
    val accounts: Map<String, String>,
) {
    /** Whether this build charges anything at all. */
    val enabled: Boolean
        get() = bps > 0 && owner != null && accounts.isNotEmpty()

    companion object {
        /** The default build: no fee, on any pair. */
        val Off: SwapFeePolicy = SwapFeePolicy(0, null, emptyMap())

        /**
         * Reads the build's fields. The Gradle build already refused a malformed configuration, so
         * this only fails for a build that bypassed it — and then it fails closed, to no fee.
         */
        fun fromBuild(bps: Int, owner: String, accounts: String): SwapFeePolicy {
            if (bps <= 0) return Off
            if (bps > MOST_SWAP_FEE_BPS || !isSolanaAddress(owner)) return Off
            val pairs =
                accounts.split(',').map(String::trim).filter(String::isNotEmpty).map { entry ->
                    val parts = entry.split('=', limit = 2).map(String::trim)
                    if (
                        parts.size != 2 || !isSolanaAddress(parts[0]) || !isSolanaAddress(parts[1])
                    ) {
                        return Off
                    }
                    parts[0] to parts[1]
                }
            if (pairs.isEmpty() || pairs.map { it.first }.toSet().size != pairs.size) return Off
            if (pairs.any { (mint, account) -> account == owner || account == mint }) return Off
            return SwapFeePolicy(bps, owner, pairs.toMap())
        }
    }
}

/**
 * The most a build may charge: 100 basis points, one percent of the output.
 *
 * The program's own field is a single byte (at most 255); this is the app's tighter bound, so a
 * typo in a release build cannot become a two-and-a-half percent cut. The Gradle build enforces the
 * same number.
 */
const val MOST_SWAP_FEE_BPS: Int = 100

/**
 * What one swap will carry, decided before it is quoted and bound to the bytes it was built into.
 */
sealed interface SwapFee {
    /** This build charges nothing on any swap. */
    data object Disabled : SwapFee

    /** This build charges, but has no account for this swap's output mint: nothing is charged. */
    data class NotForPair(val bps: Int, val outputMint: String) : SwapFee

    /**
     * This build charges, and has an account for the output mint, but it could not be verified on
     * chain: nothing is charged, and the review says why. [reason] is a stable code.
     */
    data class Unverified(
        val bps: Int,
        val account: String,
        val mint: String,
        val reason: String,
    ) : SwapFee

    /** [bps] of the output, in [mint], to [account], which [owner] holds. Verified on chain. */
    data class Charged(
        val bps: Int,
        val account: String,
        val owner: String,
        val mint: String,
    ) : SwapFee

    /** The rate this swap actually carries: nonzero only when it is [Charged]. */
    val chargedBps: Int
        get() = (this as? Charged)?.bps ?: 0
}

/** Why a configured fee account was not used. Each is a stable code the History keeps. */
object FeeAccountProblem {
    /** This build has no Solana endpoint to read the account from. */
    const val NO_RPC = "fee_account_no_rpc"
    /** The endpoint could not answer. */
    const val UNREADABLE = "fee_account_unreadable"
    /** There is no such account on chain: it was never created. */
    const val MISSING = "fee_account_missing"
    /** It is not a classic SPL Token account (another program, or Token-2022). */
    const val NOT_TOKEN_ACCOUNT = "fee_account_not_token_account"
    /** It holds another mint than the swap's output. */
    const val OTHER_MINT = "fee_account_other_mint"
    /** It is held by someone other than the configured recipient. */
    const val OTHER_OWNER = "fee_account_other_owner"
    /** It exists but is not initialized, or it is frozen and cannot receive. */
    const val NOT_USABLE = "fee_account_not_usable"
}

/**
 * Decides the fee for a swap into [outputMint] under [policy], reading the receiving account from
 * [chain] when there is one to read.
 *
 * It never throws: an endpoint that cannot answer is [SwapFee.Unverified], which charges nothing.
 */
suspend fun decideSwapFee(
    policy: SwapFeePolicy,
    outputMint: String,
    chain: SolanaAccounts,
): SwapFee {
    if (!policy.enabled) return SwapFee.Disabled
    val owner = policy.owner ?: return SwapFee.Disabled
    val account = policy.accounts[outputMint] ?: return SwapFee.NotForPair(policy.bps, outputMint)
    val snapshot =
        try {
            chain.accounts(listOf(account)).single()
        } catch (e: SolanaException) {
            val reason =
                if (e.problem == SolanaProblem.NoEndpoint) {
                    FeeAccountProblem.NO_RPC
                } else {
                    FeeAccountProblem.UNREADABLE
                }
            return SwapFee.Unverified(policy.bps, account, outputMint, reason)
        }
    return when (val problem = verifyFeeAccount(snapshot, outputMint, owner)) {
        null -> SwapFee.Charged(policy.bps, account, owner, outputMint)
        else -> SwapFee.Unverified(policy.bps, account, outputMint, problem)
    }
}

/**
 * Whether [snapshot] is a token account that can receive a fee in [mint] for [owner], or the
 * [FeeAccountProblem] code that says why not.
 *
 * The classic SPL Token account layout: mint (32), owner (32), amount (8), delegate option (36),
 * state (1) — `1` initialized, `2` frozen — and the rest. Token-2022 is refused on purpose: the
 * swap inspector reads the classic program only, and a fee account is not a reason to broaden it.
 */
fun verifyFeeAccount(snapshot: AccountSnapshot?, mint: String, owner: String): String? {
    if (snapshot == null) return FeeAccountProblem.MISSING
    if (snapshot.owner != TOKEN_PROGRAM || snapshot.executable) {
        return FeeAccountProblem.NOT_TOKEN_ACCOUNT
    }
    val data = snapshot.data
    if (data.size != TOKEN_ACCOUNT_BYTES) return FeeAccountProblem.NOT_TOKEN_ACCOUNT
    if (encodeBase58(data.copyOfRange(0, 32)) != mint) return FeeAccountProblem.OTHER_MINT
    if (encodeBase58(data.copyOfRange(32, 64)) != owner) return FeeAccountProblem.OTHER_OWNER
    if (data[TOKEN_ACCOUNT_STATE].toInt() != STATE_INITIALIZED) return FeeAccountProblem.NOT_USABLE
    return null
}

/** The size of a classic SPL Token account. */
private const val TOKEN_ACCOUNT_BYTES = 165

/** Where its state byte sits: after mint, owner, amount and the delegate's option. */
private const val TOKEN_ACCOUNT_STATE = 108

private const val STATE_INITIALIZED = 1
