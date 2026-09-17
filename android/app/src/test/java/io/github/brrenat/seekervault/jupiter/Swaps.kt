package io.github.brrenat.seekervault.jupiter

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.transactions.ASSOCIATED_TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.COMPUTE_BUDGET_PROGRAM
import io.github.brrenat.seekervault.transactions.SYSTEM_PROGRAM
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.associatedTokenAddress
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.decodeBase58
import java.time.Instant

/**
 * Building swap transactions for the tests (SEE-93).
 *
 * The four committed fixtures are real answers from Jupiter, and they are what proves the reader
 * reads what the provider actually builds. This is the other half: a builder, so a test can take
 * exactly one thing about a well-formed swap — the amount in the routing instruction, the account
 * the output goes to, the fee in basis points — and change it, then show that the review refuses
 * it. There is no way to ask a provider for a transaction that cheats you.
 */
const val SOL_MINT: String = WRAPPED_SOL

const val USDC_MINT: String = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

const val JUP_MINT: String = "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN"

const val OWNER: String = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"

const val SOMEONE_ELSE: String = "2VDW9dFE1ZXz4zWAbaBDQFynNVdRpQ73HyfSHMzBSL6Z"

/** A pool account, which is any address that isn't one of the above. */
const val POOL: String = "GMCJvYGf5Ex2ARiMquaBDqU6iKM8uiEQkB8jCnoNfHpC"

fun wallet(
    address: String = OWNER,
    network: WalletNetwork = WalletNetwork.Mainnet,
): SelectedWallet = SelectedWallet(address = address, network = network, selectedAt = Instant.EPOCH)

fun usdcTerms(
    inputMint: String = USDC_MINT,
    outputMint: String = SOL_MINT,
    maxSlippageBps: Int = 100,
    leastInput: ULong = 0UL,
    mostInput: ULong? = null,
): SwapTerms =
    SwapTerms(
        inputMint = inputMint,
        inputDecimals = if (inputMint == SOL_MINT) 9 else 6,
        outputMint = outputMint,
        outputDecimals = if (outputMint == SOL_MINT) 9 else 6,
        maxSlippageBps = maxSlippageBps,
        leastInput = leastInput,
        mostInput = mostInput,
        inputSymbol = if (inputMint == SOL_MINT) "SOL" else "USDC",
        outputSymbol = if (outputMint == SOL_MINT) "SOL" else "USDC",
    )

/**
 * A quote for exactly what a route instruction will say, so the two agree unless a test parts them.
 */
fun quoteFor(
    terms: SwapTerms,
    amount: ULong,
    outAmount: ULong = 1_000_000UL,
    slippageBps: Int = 50,
): JupiterQuote =
    JupiterQuote(
        inputMint = terms.inputMint,
        outputMint = terms.outputMint,
        inAmount = amount,
        outAmount = outAmount,
        // The provider's own arithmetic, which is what the instruction's numbers have to reproduce.
        minimumOut = outAmount - outAmount * slippageBps.toULong() / 10_000UL,
        slippageBps = slippageBps,
        legs = 1,
        raw = """{"quote":"$amount"}""",
    )

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
 * A complete, well-formed swap, of exactly the shape Jupiter builds — and then whatever a test
 * changes about it.
 *
 * Everything is a parameter with the honest default, so each test says the one thing it is about
 * and nothing else. The result is real bytes: serialized, decoded by the app's own decoder, and
 * read by the app's own reader, so a test can never accidentally assert about a transaction the
 * phone would have refused to read at all.
 */
fun swapTransaction(
    terms: SwapTerms,
    amount: ULong,
    quote: JupiterQuote,
    owner: String = OWNER,
    payer: String = owner,
    signers: List<String> = listOf(payer),
    shared: Boolean = true,
    authority: String = owner,
    source: String? = null,
    destination: String? = null,
    sourceMint: String? = null,
    destinationMint: String? = null,
    platformFee: String? = null,
    platformFeeBps: Int = 0,
    inAmount: ULong = amount,
    quotedOut: ULong = quote.outAmount,
    slippageBps: Int = quote.slippageBps,
    legs: Int = 1,
    plan: ByteArray = ByteArray(4) { 7 },
    routes: Int = 1,
    wrap: ULong? = null,
    wrapTo: String? = null,
    unwrap: Boolean? = null,
    unwrapTo: String = owner,
    createFor: String? = null,
    createMint: String? = null,
    extra: List<Step> = emptyList(),
    budget: Boolean = true,
    signed: Boolean = false,
): ByteString {
    val sourceAccount = source ?: checkNotNull(associatedTokenAddress(owner, terms.inputMint))
    val destinationAccount =
        destination ?: checkNotNull(associatedTokenAddress(owner, terms.outputMint))
    val wrappedAccount = checkNotNull(associatedTokenAddress(owner, WRAPPED_SOL))
    val steps = mutableListOf<Step>()
    if (budget) {
        steps += Step(COMPUTE_BUDGET_PROGRAM, emptyList(), byteArrayOf(2) + u32(1_400_000U))
        steps += Step(COMPUTE_BUDGET_PROGRAM, emptyList(), byteArrayOf(3) + u64(71_428UL))
    }
    // Native SOL in has to be wrapped, and by default that is done exactly as the provider does it.
    val wrapped = wrap ?: amount.takeIf { terms.inputMint == WRAPPED_SOL }
    if (wrapped != null) {
        steps +=
            Step(
                SYSTEM_PROGRAM,
                listOf(owner, wrapTo ?: wrappedAccount),
                u32(2U) + u64(wrapped),
            )
        steps += Step(TOKEN_PROGRAM, listOf(wrapTo ?: wrappedAccount), byteArrayOf(17))
    }
    if (createFor != null || createMint != null) {
        val mint = createMint ?: terms.outputMint
        val forWhom = createFor ?: owner
        steps +=
            Step(
                ASSOCIATED_TOKEN_PROGRAM,
                listOf(
                    owner,
                    checkNotNull(associatedTokenAddress(forWhom, mint)),
                    forWhom,
                    mint,
                    SYSTEM_PROGRAM,
                    TOKEN_PROGRAM,
                ),
                byteArrayOf(1),
            )
    }
    repeat(routes) {
        steps +=
            route(
                shared = shared,
                authority = authority,
                source = sourceAccount,
                destination = destinationAccount,
                sourceMint = sourceMint ?: terms.inputMint,
                destinationMint = destinationMint ?: terms.outputMint,
                platformFee = platformFee,
                legs = legs,
                plan = plan,
                inAmount = inAmount,
                quotedOut = quotedOut,
                slippageBps = slippageBps,
                platformFeeBps = platformFeeBps,
            )
    }
    val closing = unwrap ?: (terms.inputMint == WRAPPED_SOL || terms.outputMint == WRAPPED_SOL)
    if (closing) {
        steps += Step(TOKEN_PROGRAM, listOf(wrappedAccount, unwrapTo, owner), byteArrayOf(9))
    }
    steps += extra
    return serialize(payer, signers, steps, signed)
}

/** The aggregator's routing instruction, in either of its two layouts. */
fun route(
    shared: Boolean,
    authority: String,
    source: String,
    destination: String,
    sourceMint: String,
    destinationMint: String,
    platformFee: String?,
    legs: Int,
    plan: ByteArray,
    inAmount: ULong,
    quotedOut: ULong,
    slippageBps: Int,
    platformFeeBps: Int,
): Step {
    // An absent optional account is written as the program's own address, which is how Anchor says
    // "none". The layouts are the ones SwapInstructions.kt reads, spelled out again here so a
    // change to either has to be made twice on purpose.
    val none = JUPITER_PROGRAM
    val accounts =
        if (shared)
            listOf(
                TOKEN_PROGRAM,
                POOL,
                authority,
                source,
                POOL,
                POOL,
                destination,
                sourceMint,
                destinationMint,
                platformFee ?: none,
                none,
                POOL,
                none,
            )
        else
            listOf(
                TOKEN_PROGRAM,
                authority,
                source,
                destination,
                none,
                destinationMint,
                platformFee ?: none,
                POOL,
                none,
            )
    val discriminator =
        if (shared) byteArrayOf(-63, 32, -101, 51, 65, -42, -100, -127)
        else byteArrayOf(-27, 23, -53, -105, 122, -29, -83, 42)
    val data =
        discriminator +
            (if (shared) byteArrayOf(0) else ByteArray(0)) +
            u32(legs.toUInt()) +
            plan +
            u64(inAmount) +
            u64(quotedOut) +
            u16(slippageBps) +
            byteArrayOf(platformFeeBps.toByte())
    return Step(JUPITER_PROGRAM, accounts, data)
}

/** Serializes a legacy message: the payer signs, and nothing else is a signer unless asked. */
fun serialize(
    payer: String,
    signers: List<String>,
    steps: List<Step>,
    signed: Boolean = false,
): ByteString {
    val accounts = LinkedHashSet<String>()
    accounts += payer
    accounts += signers
    steps.forEach {
        accounts += it.accounts
        accounts += it.program
    }
    val ordered = accounts.toList()
    val out = ArrayList<Byte>()
    out += compact(signers.size)
    repeat(signers.size) { out += ByteArray(64) { if (signed) 3 else 0 }.toList() }
    out += listOf(signers.size.toByte(), 0.toByte(), 0.toByte())
    out += compact(ordered.size)
    ordered.forEach { out += checkNotNull(decodeBase58(it)) { "not an address: $it" }.toList() }
    // Any blockhash: the review reads what a transaction does, never when it was built.
    out += ByteArray(32) { 5 }.toList()
    out += compact(steps.size)
    for (step in steps) {
        out += ordered.indexOf(step.program).toByte()
        out += compact(step.accounts.size)
        step.accounts.forEach { out += ordered.indexOf(it).toByte() }
        out += compact(step.data.size)
        out += step.data.toList()
    }
    return ByteString.copyFrom(out.toByteArray())
}

/**
 * The same swap as a versioned message that loads one account from a lookup table.
 *
 * A provider asked for a legacy transaction should never answer with one of these, which is exactly
 * why the review has to refuse it rather than assume it: the phone cannot see what a table holds.
 */
fun withLookupTable(steps: List<Step>, payer: String = OWNER): ByteString {
    val accounts = LinkedHashSet<String>()
    accounts += payer
    steps.forEach {
        accounts += it.accounts
        accounts += it.program
    }
    val ordered = accounts.toList()
    val out = ArrayList<Byte>()
    out += compact(1)
    out += ByteArray(64).toList()
    out += 0x80.toByte()
    out += listOf(1.toByte(), 0.toByte(), 0.toByte())
    out += compact(ordered.size)
    ordered.forEach { out += checkNotNull(decodeBase58(it)).toList() }
    out += ByteArray(32) { 5 }.toList()
    out += compact(steps.size)
    for (step in steps) {
        out += ordered.indexOf(step.program).toByte()
        out += compact(step.accounts.size)
        step.accounts.forEach { out += ordered.indexOf(it).toByte() }
        out += compact(step.data.size)
        out += step.data.toList()
    }
    // One table, one writable index, no readonly ones.
    out += compact(1)
    out += checkNotNull(decodeBase58(POOL)).toList()
    out += compact(1)
    out += 0.toByte()
    out += compact(0)
    return ByteString.copyFrom(out.toByteArray())
}

fun compact(value: Int): List<Byte> {
    var left = value
    val out = ArrayList<Byte>()
    while (true) {
        val part = left and 0x7f
        left = left shr 7
        if (left == 0) {
            out += part.toByte()
            return out
        }
        out += (part or 0x80).toByte()
    }
}

fun u16(value: Int): ByteArray =
    byteArrayOf((value and 0xff).toByte(), ((value shr 8) and 0xff).toByte())

fun u32(value: UInt): ByteArray = ByteArray(4) { ((value shr (it * 8)) and 0xffU).toByte() }

fun u64(value: ULong): ByteArray = ByteArray(8) { ((value shr (it * 8)) and 0xffUL).toByte() }
