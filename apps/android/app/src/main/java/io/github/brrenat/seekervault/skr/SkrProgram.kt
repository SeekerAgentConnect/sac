package io.github.brrenat.seekervault.skr

import io.github.brrenat.seekervault.transactions.findProgramAddress
import java.security.MessageDigest

/**
 * The SKR staking deployment this build knows, and the addresses it derives for itself (SEE-146).
 *
 * Two of these are roots of trust and the rest are consequences. The program ID and the SKR mint
 * are compiled in and can only be changed by shipping a new app; the configuration, the vault, a
 * guardian's pool and an owner's stake account are all program-derived, so this phone computes them
 * and compares, rather than reading them out of a request and believing them.
 *
 * That is the whole reason `StakingAction` carries no addresses. A server that could name the
 * program it wants called could have an owner review one program and sign for another, and no
 * amount of care in the review would catch it.
 */

/** The staking program. It is deployed on Solana mainnet and nowhere else. */
const val SKR_STAKING_PROGRAM = "SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ"

/** The SKR mint: a classic SPL token with six decimals, not Token-2022. */
const val SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"

/** SKR's decimal places, as the mint reports them. */
const val SKR_DECIMALS = 6

/**
 * The guardian whose pool this build stakes into.
 *
 * A stake is delegated to a guardian and the stake account is derived per guardian, so this is part
 * of the account's identity rather than a setting. A transaction naming another pool is refused:
 * serving a second guardian is a decision for a later change, not something a server may choose on
 * an owner's behalf.
 */
const val SKR_OFFICIAL_GUARDIAN = "SKRGdBwzb1AtFW2chhBnZpGFnFLj6Mi7HM7iwjXALvw"

/** Share prices are integers scaled by this; nothing here divides in floating point. */
const val SHARE_PRICE_SCALE = 1_000_000_000L

/** An Anchor discriminator: the first eight bytes of a SHA-256 over a namespaced name. */
private fun discriminator(namespace: String, name: String): ByteArray =
    MessageDigest.getInstance("SHA-256")
        .digest("$namespace:$name".toByteArray(Charsets.UTF_8))
        .copyOfRange(0, 8)

/** The four instructions this app reads. Anything else from this program is unrecognized. */
val STAKE_DISCRIMINATOR: ByteArray = discriminator("global", "stake")
val UNSTAKE_DISCRIMINATOR: ByteArray = discriminator("global", "unstake")
val CANCEL_UNSTAKE_DISCRIMINATOR: ByteArray = discriminator("global", "cancel_unstake")
val WITHDRAW_DISCRIMINATOR: ByteArray = discriminator("global", "withdraw")

/** The two accounts this app decodes. */
val STAKE_CONFIG_DISCRIMINATOR: ByteArray = discriminator("account", "StakeConfig")
val USER_STAKE_DISCRIMINATOR: ByteArray = discriminator("account", "UserStake")
val GUARDIAN_POOL_DISCRIMINATOR: ByteArray = discriminator("account", "GuardianDelegationPool")

/** The singleton configuration account: `PDA(["stake_config"])`. */
fun stakeConfigAddress(): String? =
    findProgramAddress(listOf("stake_config".toByteArray(Charsets.UTF_8)), SKR_STAKING_PROGRAM)
        ?.first

/** The vault every staker's principal sits in: `PDA(["stake_vault"])`. */
fun stakeVaultAddress(): String? =
    findProgramAddress(listOf("stake_vault".toByteArray(Charsets.UTF_8)), SKR_STAKING_PROGRAM)
        ?.first

/** Anchor's CPI event authority, which all four instructions name. */
fun eventAuthorityAddress(): String? =
    findProgramAddress(listOf("__event_authority".toByteArray(Charsets.UTF_8)), SKR_STAKING_PROGRAM)
        ?.first

/** A guardian's delegation pool: `PDA(["guardian_pool", stakeConfig, guardian])`. */
fun guardianPoolAddress(stakeConfig: String, guardian: String): String? {
    val config = decodeKey(stakeConfig) ?: return null
    val key = decodeKey(guardian) ?: return null
    return findProgramAddress(
            listOf("guardian_pool".toByteArray(Charsets.UTF_8), config, key),
            SKR_STAKING_PROGRAM,
        )
        ?.first
}

/**
 * One owner's stake account with one guardian: `PDA(["user_stake", stakeConfig, user,
 * guardianPool])`.
 */
fun userStakeAddress(stakeConfig: String, user: String, guardianPool: String): String? {
    val config = decodeKey(stakeConfig) ?: return null
    val owner = decodeKey(user) ?: return null
    val pool = decodeKey(guardianPool) ?: return null
    return findProgramAddress(
            listOf("user_stake".toByteArray(Charsets.UTF_8), config, owner, pool),
            SKR_STAKING_PROGRAM,
        )
        ?.first
}

/**
 * Every address a staking review compares against, derived once for the official guardian.
 *
 * Deriving is the evidence: a set built here cannot contain a mismatched pair, and a review that
 * compares against it is comparing against this app's own arithmetic rather than against anything
 * that arrived over a network.
 */
data class SkrAddresses(
    val program: String,
    val mint: String,
    val stakeConfig: String,
    val stakeVault: String,
    val guardian: String,
    val guardianPool: String,
    val eventAuthority: String,
) {
    companion object {
        /** The set for one guardian, or null if any derivation failed. */
        fun derive(guardian: String = SKR_OFFICIAL_GUARDIAN): SkrAddresses? {
            val config = stakeConfigAddress() ?: return null
            val vault = stakeVaultAddress() ?: return null
            val pool = guardianPoolAddress(config, guardian) ?: return null
            val events = eventAuthorityAddress() ?: return null
            return SkrAddresses(
                program = SKR_STAKING_PROGRAM,
                mint = SKR_MINT,
                stakeConfig = config,
                stakeVault = vault,
                guardian = guardian,
                guardianPool = pool,
                eventAuthority = events,
            )
        }
    }

    /** The stake account this owner's position lives in under this guardian. */
    fun userStake(owner: String): String? = userStakeAddress(stakeConfig, owner, guardianPool)
}

private fun decodeKey(address: String): ByteArray? =
    io.github.brrenat.seekervault.wallet.decodeBase58(address)?.takeIf { it.size == 32 }
