package io.github.brrenat.seekervault.skr

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.PreparedTransaction
import io.github.brrenat.seekervault.request.v1.StakingOperation
import io.github.brrenat.seekervault.request.v1.action
import io.github.brrenat.seekervault.request.v1.actionRequest
import io.github.brrenat.seekervault.request.v1.preparedTransaction
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.request.v1.stakingAction
import io.github.brrenat.seekervault.solana.AccountSnapshot
import io.github.brrenat.seekervault.solana.SolanaAccounts
import io.github.brrenat.seekervault.solana.SolanaException
import io.github.brrenat.seekervault.solana.SolanaProblem
import io.github.brrenat.seekervault.transactions.ASSOCIATED_TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.COMPUTE_BUDGET_PROGRAM
import io.github.brrenat.seekervault.transactions.SYSTEM_PROGRAM
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.associatedTokenAddress
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.decodeBase58
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant

/**
 * Building staking transactions and staking positions for the tests (SEE-146).
 *
 * Every default here is the honest one — the accounts the program's own IDL puts at each position,
 * the addresses this app derives, a position that makes the operation legal — so a test says the
 * one thing it is about and nothing else. The result is real bytes: serialized, decoded by the
 * app's own decoder and read by the app's own reader, so no test can assert about a transaction the
 * phone would have refused to read at all.
 *
 * Nothing here is taken from the server. A helper that built what the server builds by asking the
 * server would be a test of agreement rather than a test of verification.
 */

/** The owner whose position these tests are about, and somebody else's wallet. */
const val OWNER: String = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"

const val STRANGER: String = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"

/** A real-looking address that is none of the ones this app derives. */
const val ELSEWHERE: String = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

const val OTHER_GUARDIAN: String = "So11111111111111111111111111111111111111112"

/** The addresses this app derives, which every default below is built out of. */
val ADDRESSES: SkrAddresses = checkNotNull(SkrAddresses.derive()) { "the PDAs must derive" }

val OWNER_STAKE: String = checkNotNull(ADDRESSES.userStake(OWNER))

val OWNER_TOKENS: String = checkNotNull(associatedTokenAddress(OWNER, ADDRESSES.mint))

fun wallet(
    address: String = OWNER,
    network: WalletNetwork = WalletNetwork.Mainnet,
): SelectedWallet = SelectedWallet(address = address, network = network, selectedAt = Instant.EPOCH)

/** One instruction, with its accounts named rather than indexed. */
data class Step(val program: String, val accounts: List<String>, val data: ByteArray) {
    override fun equals(other: Any?) =
        other is Step &&
            program == other.program &&
            accounts == other.accounts &&
            data.contentEquals(other.data)

    override fun hashCode() =
        (program.hashCode() * 31 + accounts.hashCode()) * 31 + data.contentHashCode()
}

/**
 * A complete, well-formed staking transaction of the shape the server builds, and then whatever a
 * test changes about it.
 *
 * It is a v0 message with no lookup table, because that is what the server produces: a message it
 * compiled itself out of instructions whose accounts it named in full.
 */
fun stakingTransaction(
    operation: StakingOperation,
    amount: ULong = 25_000_000UL,
    shares: BigInteger = BigInteger.valueOf(25_000_000L),
    owner: String = OWNER,
    payer: String = owner,
    user: String = owner,
    signers: List<String> = listOf(payer),
    signed: Boolean = false,
    userStake: String = checkNotNull(ADDRESSES.userStake(owner)),
    stakeConfig: String = ADDRESSES.stakeConfig,
    stakeVault: String = ADDRESSES.stakeVault,
    guardianPool: String = ADDRESSES.guardianPool,
    mint: String = ADDRESSES.mint,
    eventAuthority: String = ADDRESSES.eventAuthority,
    programId: String = ADDRESSES.program,
    tokenProgram: String = TOKEN_PROGRAM,
    systemProgram: String = SYSTEM_PROGRAM,
    ownerTokenAccount: String = checkNotNull(associatedTokenAddress(owner, ADDRESSES.mint)),
    discriminator: ByteArray? = null,
    arguments: ByteArray? = null,
    accounts: List<String>? = null,
    budget: Boolean = true,
    createsAccount: Boolean = operation == StakingOperation.STAKING_OPERATION_WITHDRAW,
    createIdempotent: Boolean = true,
    createFor: String = owner,
    createPayer: String = owner,
    createMint: String = ADDRESSES.mint,
    extra: List<Step> = emptyList(),
): ByteString {
    val steps = mutableListOf<Step>()
    if (budget) {
        steps += Step(COMPUTE_BUDGET_PROGRAM, emptyList(), byteArrayOf(3) + u64(20_000UL))
    }
    if (createsAccount) {
        steps +=
            Step(
                ASSOCIATED_TOKEN_PROGRAM,
                listOf(
                    createPayer,
                    checkNotNull(associatedTokenAddress(createFor, createMint)),
                    createFor,
                    createMint,
                    SYSTEM_PROGRAM,
                    TOKEN_PROGRAM,
                ),
                byteArrayOf(if (createIdempotent) 1 else 0),
            )
    }
    val defaultAccounts =
        when (operation) {
            StakingOperation.STAKING_OPERATION_STAKE ->
                listOf(
                    userStake,
                    stakeConfig,
                    guardianPool,
                    payer,
                    user,
                    ownerTokenAccount,
                    stakeVault,
                    mint,
                    tokenProgram,
                    systemProgram,
                    eventAuthority,
                    programId,
                )
            StakingOperation.STAKING_OPERATION_UNSTAKE ->
                listOf(
                    userStake,
                    stakeConfig,
                    guardianPool,
                    user,
                    stakeVault,
                    mint,
                    eventAuthority,
                    programId,
                )
            StakingOperation.STAKING_OPERATION_CANCEL_UNSTAKE ->
                listOf(
                    userStake,
                    stakeConfig,
                    guardianPool,
                    user,
                    stakeVault,
                    eventAuthority,
                    programId,
                )
            else ->
                listOf(
                    userStake,
                    stakeConfig,
                    user,
                    stakeVault,
                    ownerTokenAccount,
                    tokenProgram,
                    eventAuthority,
                    programId,
                )
        }
    val defaultArguments =
        when (operation) {
            StakingOperation.STAKING_OPERATION_STAKE -> u64(amount)
            StakingOperation.STAKING_OPERATION_UNSTAKE -> u128(shares)
            else -> ByteArray(0)
        }
    steps +=
        Step(
            programId,
            accounts ?: defaultAccounts,
            (discriminator ?: discriminatorFor(operation)) + (arguments ?: defaultArguments),
        )
    steps += extra
    return versioned(payer = payer, signers = signers, signed = signed, steps = steps)
}

fun discriminatorFor(operation: StakingOperation): ByteArray =
    when (operation) {
        StakingOperation.STAKING_OPERATION_STAKE -> STAKE_DISCRIMINATOR
        StakingOperation.STAKING_OPERATION_UNSTAKE -> UNSTAKE_DISCRIMINATOR
        StakingOperation.STAKING_OPERATION_CANCEL_UNSTAKE -> CANCEL_UNSTAKE_DISCRIMINATOR
        else -> WITHDRAW_DISCRIMINATOR
    }

/** Serializes a v0 message that names every account itself, which is what the server compiles. */
fun versioned(
    payer: String,
    signers: List<String>,
    signed: Boolean,
    steps: List<Step>,
): ByteString {
    val statics = LinkedHashSet<String>()
    statics += payer
    statics += signers
    steps.forEach { step -> step.accounts.forEach { statics += it } }
    steps.forEach { statics += it.program }
    val ordered = statics.toList()
    val out = ArrayList<Byte>()
    out += compact(signers.size)
    repeat(signers.size) { out += ByteArray(64) { if (signed) 9 else 0 }.toList() }
    out += 0x80.toByte()
    // Every signer is writable, and everything else is listed as a writable non-signer: which it is
    // changes nothing about how these tests read, and one rule is simpler than a table of them.
    out += listOf(signers.size.toByte(), 0.toByte(), 0.toByte())
    out += compact(ordered.size)
    ordered.forEach { out += checkNotNull(decodeBase58(it)) { "not an address: $it" }.toList() }
    out += ByteArray(32) { 4 }.toList()
    out += compact(steps.size)
    for (step in steps) {
        out += ordered.indexOf(step.program).toByte()
        out += compact(step.accounts.size)
        step.accounts.forEach { out += ordered.indexOf(it).toByte() }
        out += compact(step.data.size)
        out += step.data.toList()
    }
    // No lookup tables: the server compiles messages that stand on their own.
    out += compact(0)
    return ByteString.copyFrom(out.toByteArray())
}

/** A preparation whose hash is the hash of its own bytes, unless a test breaks exactly that. */
fun preparedFor(
    transaction: ByteString,
    version: Int = 1,
    hash: ByteString? = null,
): PreparedTransaction = preparedTransaction {
    this.version = version
    this.transaction = transaction
    contentHash =
        hash
            ?: ByteString.copyFrom(
                MessageDigest.getInstance("SHA-256").digest(transaction.toByteArray())
            )
}

fun stakingRequest(
    operation: StakingOperation,
    amount: String = "25000000",
    owner: String = OWNER,
    network: Network = Network.NETWORK_MAINNET,
): ActionRequest = actionRequest {
    ref = requestRef {
        connectionId = "7c1f2e3d-4a5b-4c6d-8e9f-0a1b2c3d4e5f"
        requestId = "b3a29180-d5c4-4b3a-9180-e6d5c4b3a291"
    }
    action = action {
        staking = stakingAction {
            wallet = owner
            this.network = network
            this.operation = operation
            if (
                operation == StakingOperation.STAKING_OPERATION_STAKE ||
                    operation == StakingOperation.STAKING_OPERATION_UNSTAKE
            ) {
                this.amount = amount
            }
        }
    }
}

/** A position that makes every operation legal, and then whatever a test changes about it. */
fun reading(
    sharePrice: Long = SHARE_PRICE_SCALE,
    minimumStake: ULong = 1_000_000UL,
    cooldownSeconds: ULong = 172_800UL,
    shares: BigInteger? = BigInteger.valueOf(100_000_000L),
    unstakingAmount: ULong = 0UL,
    unstakeTimestamp: Long = 0L,
    balance: ULong? = 500_000_000UL,
    active: Boolean = true,
    owner: String = OWNER,
    mint: String = ADDRESSES.mint,
    stakeVault: String = ADDRESSES.stakeVault,
    guardian: String = ADDRESSES.guardian,
    poolConfig: String = ADDRESSES.stakeConfig,
    addresses: SkrAddresses = ADDRESSES,
): SkrReading =
    SkrReading(
        addresses = addresses,
        config =
            SkrStakeConfig(
                mint = mint,
                stakeVault = stakeVault,
                minStakeAmount = minimumStake,
                cooldownSeconds = cooldownSeconds,
                sharePrice = BigInteger.valueOf(sharePrice),
            ),
        guardian = SkrGuardianPool(poolConfig, guardian, active),
        stake =
            shares?.let {
                SkrUserStake(
                    stakeConfig = addresses.stakeConfig,
                    user = owner,
                    guardianPool = addresses.guardianPool,
                    shares = it,
                    unstakingAmount = unstakingAmount,
                    unstakeTimestamp = unstakeTimestamp,
                )
            },
        ownerTokenAccount = balance?.let { SkrTokenAccount(mint, owner, it) },
    )

/** The bytes the chain holds for a `StakeConfig`, so the decoder is run over a real layout. */
fun stakeConfigAccount(
    mint: String = ADDRESSES.mint,
    vault: String = ADDRESSES.stakeVault,
    minimum: ULong = 1_000_000UL,
    cooldown: ULong = 172_800UL,
    sharePrice: BigInteger = BigInteger.valueOf(SHARE_PRICE_SCALE),
    discriminator: ByteArray = STAKE_CONFIG_DISCRIMINATOR,
): ByteArray =
    // The account is longer than the fields a review needs, so the tail is padded rather than
    // invented: what matters is that every field this app reads is at the offset the program
    // writes it to, and that the whole account is the length the program allocates.
    pad(
        discriminator +
            byteArrayOf(254.toByte()) +
            key(ELSEWHERE) +
            key(mint) +
            key(vault) +
            u64(minimum) +
            u64(cooldown) +
            u128(BigInteger.valueOf(1_000_000_000L)) +
            u128(sharePrice),
        STAKE_CONFIG_BYTES,
    )

fun userStakeAccount(
    owner: String = OWNER,
    stakeConfig: String = ADDRESSES.stakeConfig,
    guardianPool: String = ADDRESSES.guardianPool,
    shares: BigInteger = BigInteger.valueOf(100_000_000L),
    unstakingAmount: ULong = 0UL,
    unstakeTimestamp: Long = 0L,
    discriminator: ByteArray = USER_STAKE_DISCRIMINATOR,
): ByteArray =
    discriminator +
        byteArrayOf(253.toByte()) +
        key(stakeConfig) +
        key(owner) +
        key(guardianPool) +
        u128(shares) +
        u128(BigInteger.ZERO) +
        u128(BigInteger.ZERO) +
        u64(unstakingAmount) +
        u64(unstakeTimestamp.toULong())

fun guardianPoolAccount(
    stakeConfig: String = ADDRESSES.stakeConfig,
    guardian: String = ADDRESSES.guardian,
    active: Boolean = true,
    activeByte: Byte? = null,
    discriminator: ByteArray = GUARDIAN_POOL_DISCRIMINATOR,
): ByteArray =
    pad(
        discriminator +
            key(stakeConfig) +
            key(guardian) +
            key(ELSEWHERE) +
            u128(BigInteger.ZERO) +
            u128(BigInteger.ZERO) +
            u128(BigInteger.ZERO) +
            u128(BigInteger.ZERO) +
            byteArrayOf(0, 0) +
            byteArrayOf(252.toByte()) +
            byteArrayOf(activeByte ?: if (active) 1 else 0),
        GUARDIAN_POOL_BYTES,
    )

fun tokenAccount(
    mint: String = ADDRESSES.mint,
    owner: String = OWNER,
    amount: ULong = 500_000_000UL,
    state: Byte = 1,
): ByteArray {
    val data = ByteArray(SPL_TOKEN_ACCOUNT_BYTES)
    key(mint).copyInto(data, 0)
    key(owner).copyInto(data, 32)
    u64(amount).copyInto(data, 64)
    data[108] = state
    return data
}

/** A chain that serves exactly the accounts a test gives it, and records what it was asked. */
class FakeChain(private val accounts: Map<String, AccountSnapshot?> = emptyMap()) : SolanaAccounts {
    val asked = mutableListOf<List<String>>()
    var fails: SolanaProblem? = null
    var truncates = false

    override suspend fun accounts(addresses: List<String>): List<AccountSnapshot?> {
        asked += addresses
        fails?.let { throw SolanaException(it) }
        val answers = addresses.map { accounts[it] }
        return if (truncates) answers.dropLast(1) else answers
    }
}

fun owned(data: ByteArray, owner: String = SKR_STAKING_PROGRAM): AccountSnapshot =
    AccountSnapshot(owner = owner, data = data, executable = false)

/** An account of the length the program allocates, with the fields a review reads written in. */
private fun pad(fields: ByteArray, size: Int): ByteArray {
    check(fields.size <= size) { "${fields.size} bytes do not fit in $size" }
    return fields + ByteArray(size - fields.size)
}

/** A `TransferChecked`, which is the only token movement this app's reader recognizes as one. */
fun tokenTransfer(
    source: String,
    destination: String,
    authority: String,
    amount: ULong,
    mint: String = ADDRESSES.mint,
    decimals: Byte = SKR_DECIMALS.toByte(),
): Step =
    Step(
        TOKEN_PROGRAM,
        listOf(source, mint, destination, authority),
        byteArrayOf(12) + u64(amount) + byteArrayOf(decimals),
    )

fun key(address: String): ByteArray = checkNotNull(decodeBase58(address)) { "not an address" }

fun u64(value: ULong): ByteArray = ByteArray(8) { ((value shr (it * 8)) and 0xffUL).toByte() }

fun u128(value: BigInteger): ByteArray {
    val out = ByteArray(16)
    var rest = value
    val byte = BigInteger.valueOf(0xffL)
    for (index in 0 until 16) {
        out[index] = rest.and(byte).toInt().toByte()
        rest = rest.shiftRight(8)
    }
    return out
}

private fun compact(value: Int): List<Byte> {
    var rest = value
    val out = ArrayList<Byte>()
    while (true) {
        if (rest < 0x80) {
            out += rest.toByte()
            return out
        }
        out += ((rest and 0x7f) or 0x80).toByte()
        rest = rest shr 7
    }
}
