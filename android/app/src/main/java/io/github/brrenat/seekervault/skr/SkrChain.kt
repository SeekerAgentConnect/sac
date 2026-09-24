package io.github.brrenat.seekervault.skr

import io.github.brrenat.seekervault.solana.SolanaAccounts
import io.github.brrenat.seekervault.transactions.associatedTokenAddress

/**
 * Reading one owner's staking position from this phone's own endpoint (SEE-146).
 *
 * This is the "validate any external state independently" half of a staking review. The server read
 * the same accounts to build the transaction; reading them again here is what makes the review
 * evidence rather than a restatement. All five are fetched in one call so they describe one moment:
 * a share price from one slot and a share count from another would produce a position that was
 * never true.
 *
 * It returns null rather than a partial reading. A position half-read is not a weaker fact, it is a
 * different one, and the review refuses to interpret an unstake or a withdrawal without it.
 */
suspend fun readSkrPosition(
    chain: SolanaAccounts,
    owner: String,
    addresses: SkrAddresses? = SkrAddresses.derive(),
): SkrReading? {
    if (addresses == null) return null
    val stakeAccount = addresses.userStake(owner) ?: return null
    val ownerTokenAccount = associatedTokenAddress(owner, addresses.mint) ?: return null
    val snapshots =
        runCatching {
            chain.accounts(
                listOf(
                    addresses.stakeConfig,
                    addresses.guardianPool,
                    stakeAccount,
                    ownerTokenAccount,
                )
            )
        }
            .getOrNull() ?: return null
    if (snapshots.size < 4) return null

    val configAccount = snapshots[0] ?: return null
    // Owned by the staking program, or it is not the program's account whatever it decodes as.
    if (configAccount.owner != addresses.program) return null
    val config = decodeStakeConfig(configAccount.data) ?: return null

    val poolAccount = snapshots[1] ?: return null
    if (poolAccount.owner != addresses.program) return null
    val guardian = decodeGuardianPool(poolAccount.data) ?: return null

    // No stake account is a real answer: the owner has never staked with this guardian. A stake
    // account owned by something else is not, and is refused rather than read.
    val stake =
        snapshots[2]?.let { snapshot ->
            if (snapshot.owner != addresses.program) return null
            val decoded = decodeUserStake(snapshot.data) ?: return null
            if (decoded.user != owner) return null
            if (decoded.guardianPool != addresses.guardianPool) return null
            if (decoded.stakeConfig != addresses.stakeConfig) return null
            decoded
        }

    // Likewise an absent token account: it means no SKR, not an error.
    val tokens =
        snapshots[3]?.let { snapshot ->
            val decoded = decodeSkrTokenAccount(snapshot.data) ?: return null
            if (decoded.owner != owner || decoded.mint != addresses.mint) return null
            decoded
        }

    return SkrReading(
        addresses = addresses,
        config = config,
        guardian = guardian,
        stake = stake,
        ownerTokenAccount = tokens,
    )
}
