package io.github.brrenat.seekervault.jupiter

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.plugins.actions.PredictionChoice
import io.github.brrenat.seekervault.plugins.actions.PredictionPayload
import io.github.brrenat.seekervault.solana.ADDRESS_LOOKUP_TABLE_PROGRAM
import io.github.brrenat.seekervault.solana.AccountSnapshot
import io.github.brrenat.seekervault.solana.LOOKUP_TABLE_HEADER_BYTES
import io.github.brrenat.seekervault.solana.SolanaAccounts
import io.github.brrenat.seekervault.solana.SolanaException
import io.github.brrenat.seekervault.solana.SolanaProblem
import io.github.brrenat.seekervault.transactions.ASSOCIATED_TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.COMPUTE_BUDGET_PROGRAM
import io.github.brrenat.seekervault.transactions.SYSTEM_PROGRAM
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.associatedTokenAddress
import io.github.brrenat.seekervault.wallet.decodeBase58
import org.json.JSONObject

/**
 * Building prediction orders for the tests (SEE-94).
 *
 * The committed fixture is a real order Jupiter built, with the real contents of the lookup tables
 * it names, and it is what proves the reader reads what the provider actually produces. This is the
 * other half: a builder, so a test can change exactly one thing about a well-formed order — the
 * side, the market, the cost, whose account funds it — and show the review refusing it. No provider
 * would supply one of those on request.
 */

/** The prediction market a test names, and the deposit it is staked in. */
const val MARKET_ID: String = "POLY-2772176"

const val EVENT_ID: String = "POLY-659671"

const val MARKET_HASH: String = "e5ecff63f0706f7d16bbb4556674cea8"

const val EXTERNAL_ORDER: String = "9180bcc6cc42464bb5d47d7a7ff66d95"

const val ORDER_PUBKEY: String = "F6yPJ9m8bBSmFc6fp4QGfTPGA2aJqkvxHZbfjVE1ncyq"

const val POSITION_PUBKEY: String = "B4iziSETZtuLunVscfuYwZE1qTSHnMaodwGyXXMZ5e5n"

/** The protocol's own co-signer, whose slot the provider fills before handing the bytes over. */
const val PROTOCOL_SIGNER: String = "5uFXJogUEqBTsVT8AcfuWbC9ZFM1tSzTFJ3eKVeB14yi"

const val MARKET_ACCOUNT: String = "2y9Ad2GD7gwiMkkMu4bBK5216Pv9YJsBkSHAGwN3rBuJ"

const val ORDER_ATA: String = "3fJDBTHZcJzZDtPQFA8rsQxgVqPHSrA6YiDuwhN6789J"

const val TABLE_ONE: String = "6awx2Pq4uxeHsBcmutEqErz79C4TUg4EQEiBX5T5BVfX"

const val TABLE_TWO: String = "GHRJYRqzM15WzpooLAdR4DX6rfK1MLjj6GRXMdbLDtwG"

fun predictionTerms(
    marketId: String = MARKET_ID,
    eventId: String = "",
    provider: String = "",
    depositMint: String = USDC_MINT,
    mostDeposit: ULong? = null,
): PredictionPayload =
    PredictionPayload(
        marketId = marketId,
        eventId = eventId,
        marketProvider = provider,
        depositMint = depositMint,
        depositDecimals = 6,
        depositSymbol = if (depositMint == USDC_MINT) "USDC" else "JupUSD",
        leastDeposit = LEAST_ORDER_DEPOSIT,
        mostDeposit = mostDeposit,
    )

fun openMarket(
    marketId: String = MARKET_ID,
    status: String = PredictionMarket.OPEN,
    result: String? = null,
    eventId: String = EVENT_ID,
    provider: String = "polymarket",
): PredictionMarket =
    PredictionMarket(
        marketId = marketId,
        eventId = eventId,
        provider = provider,
        title = "Barcelona",
        status = status,
        result = result,
        buyYesPriceUsd = 200_000UL,
        buyNoPriceUsd = 810_000UL,
        rules = "Resolves to the team that wins.",
        closeTime = 1_811_721_540L,
    )

fun predictionOrder(
    transaction: ByteString,
    yes: Boolean = true,
    contractsMicro: ULong = 23_700_000UL,
    maxBuyPriceUsd: ULong = 200_000UL,
    orderCostUsd: ULong = 4_996_705UL,
    slippageBps: Int = 0,
    owner: String = OWNER,
    orderPubkey: String = ORDER_PUBKEY,
    positionPubkey: String = POSITION_PUBKEY,
    marketIdHash: String = MARKET_HASH,
    externalOrderId: String = EXTERNAL_ORDER,
): PredictionOrder =
    PredictionOrder(
        transaction = transaction,
        orderPubkey = orderPubkey,
        positionPubkey = positionPubkey,
        externalOrderId = externalOrderId,
        marketIdHash = marketIdHash,
        isYes = yes,
        isBuy = true,
        contractsMicro = contractsMicro,
        maxBuyPriceUsd = maxBuyPriceUsd,
        orderCostUsd = orderCostUsd,
        payoutUsd = contractsMicro,
        totalFeeUsd = 256_743UL,
        slippageBps = slippageBps,
        requiredSigners = listOf(owner),
    )

/**
 * A complete, well-formed order, of the shape the provider builds — and then whatever a test
 * changes about it.
 *
 * It is a **versioned** message with a lookup table, because that is the only shape this provider
 * produces: a test that built a self-contained one would be testing a transaction the plugin will
 * never see. The table's contents are supplied beside it ([tableFor]), so a test resolves offline.
 */
fun orderTransaction(
    terms: PredictionPayload = predictionTerms(),
    yes: Boolean = true,
    deposit: ULong = 5_000_000UL,
    owner: String = OWNER,
    payer: String = owner,
    protocolSigns: Boolean = true,
    ownerSigns: Boolean = false,
    orderOwner: String = owner,
    market: String = MARKET_ACCOUNT,
    position: String = POSITION_PUBKEY,
    order: String = ORDER_PUBKEY,
    funding: String? = null,
    mint: String = JUP_USD_MINT,
    marketHash: String = MARKET_HASH,
    externalOrderId: String = EXTERNAL_ORDER,
    buying: Boolean = true,
    contractsMicro: ULong = 23_700_000UL,
    maxPrice: ULong = 200_000UL,
    cost: ULong = 4_996_705UL,
    slippageBps: Int = 0,
    maxSlippageBps: Int? = null,
    route: Boolean = true,
    routeIn: ULong? = null,
    routeSource: String? = null,
    routeDestination: String? = null,
    orders: Int = 1,
    createFor: String? = null,
    createMint: String? = null,
    extra: List<Step> = emptyList(),
    trailing: ByteArray = ByteArray(0),
): OrderBytes {
    val fundingAccount = funding ?: checkNotNull(associatedTokenAddress(orderOwner, mint))
    val depositAccount = checkNotNull(associatedTokenAddress(owner, terms.depositMint))
    val steps = mutableListOf<Step>()
    steps += Step(COMPUTE_BUDGET_PROGRAM, emptyList(), byteArrayOf(3) + u64(50_000UL))
    steps += Step(COMPUTE_BUDGET_PROGRAM, emptyList(), byteArrayOf(2) + u32(750_000U))
    if (createFor != null || createMint != null) {
        val forWhom = createFor ?: owner
        val created = createMint ?: mint
        steps +=
            Step(
                ASSOCIATED_TOKEN_PROGRAM,
                listOf(
                    owner,
                    checkNotNull(associatedTokenAddress(forWhom, created)),
                    forWhom,
                    created,
                    SYSTEM_PROGRAM,
                    TOKEN_PROGRAM,
                ),
                byteArrayOf(1),
            )
    }
    if (route && terms.depositMint != mint) {
        val quote = quoteFor(usdcTerms(terms.depositMint, mint), deposit, outAmount = cost)
        steps +=
            route(
                shared = true,
                authority = owner,
                source = routeSource ?: depositAccount,
                destination = routeDestination ?: fundingAccount,
                sourceMint = terms.depositMint,
                destinationMint = mint,
                platformFee = null,
                legs = 1,
                plan = ByteArray(4) { 3 },
                inAmount = routeIn ?: deposit,
                quotedOut = quote.outAmount,
                slippageBps = quote.slippageBps,
                platformFeeBps = 0,
            )
    }
    repeat(orders) {
        steps +=
            Step(
                PREDICTION_PROGRAM,
                listOf(
                    payer,
                    orderOwner,
                    PROTOCOL_SIGNER,
                    market,
                    position,
                    order,
                    fundingAccount,
                    mint,
                    ORDER_ATA,
                    TOKEN_PROGRAM,
                    ASSOCIATED_TOKEN_PROGRAM,
                    SYSTEM_PROGRAM,
                ),
                orderData(
                    externalOrderId = externalOrderId,
                    marketHash = marketHash,
                    buying = buying,
                    yes = yes,
                    contractsMicro = contractsMicro,
                    maxPrice = maxPrice,
                    cost = cost,
                    slippageBps = slippageBps,
                    maxSlippageBps = maxSlippageBps,
                ) + trailing,
            )
    }
    steps += extra
    // The protocol is always a required signer — that is two slots — and [protocolSigns] is about
    // whether the provider has already filled its own, which is the thing worth varying.
    val signers = listOf(payer, PROTOCOL_SIGNER)
    return versioned(
        payer = payer,
        signers = signers,
        // Slot 0 is the owner's and is left empty; slot 1 is the protocol's and the provider fills
        // it before handing the bytes over. A test can swap that round.
        filled =
            buildList {
                if (ownerSigns) add(0)
                if (protocolSigns) add(1)
            },
        steps = steps,
    )
}

/** The order instruction's own arguments, in the layout the real program uses. */
fun orderData(
    externalOrderId: String = EXTERNAL_ORDER,
    marketHash: String = MARKET_HASH,
    buying: Boolean = true,
    yes: Boolean = true,
    contractsMicro: ULong = 23_700_000UL,
    maxPrice: ULong = 200_000UL,
    cost: ULong = 4_996_705UL,
    slippageBps: Int = 0,
    maxSlippageBps: Int? = null,
): ByteArray =
    byteArrayOf(-115, 54, 37, -49, -19, -46, -6, -41) +
        borshString(externalOrderId) +
        borshString(marketHash) +
        byteArrayOf(if (yes) 1 else 0, if (buying) 1 else 0) +
        u64(contractsMicro) +
        u64(maxPrice) +
        u64(cost) +
        u16(slippageBps) +
        (maxSlippageBps?.let { byteArrayOf(1) + u16(it) } ?: byteArrayOf(0))

private fun borshString(text: String): ByteArray =
    u32(text.length.toUInt()) + text.encodeToByteArray()

/** A versioned message with one lookup table, and the bytes of that table beside it. */
data class OrderBytes(val transaction: ByteString, val tables: Map<String, List<String>>)

/**
 * Serializes a v0 message that keeps as many accounts as it can in one lookup table.
 *
 * The split is deliberate and is what makes these tests about the real thing: the payer, the
 * signers and the programs stay in the message, because a message that hid its programs could not
 * be read at all, and every other account goes into the table — so every test here exercises the
 * resolution rather than sidestepping it.
 */
fun versioned(
    payer: String,
    signers: List<String>,
    filled: List<Int>,
    steps: List<Step>,
    table: String = TABLE_ONE,
): OrderBytes {
    val statics = LinkedHashSet<String>()
    statics += payer
    statics += signers
    steps.forEach { statics += it.program }
    val tabled = LinkedHashSet<String>()
    steps.forEach { step -> step.accounts.forEach { if (it !in statics) tabled += it } }
    val ordered = statics.toList()
    val fromTable = tabled.toList()
    val resolved = ordered + fromTable
    val out = ArrayList<Byte>()
    out += compact(signers.size)
    repeat(signers.size) { slot ->
        out += ByteArray(64) { if (slot in filled) 7 else 0 }.toList()
    }
    out += 0x80.toByte()
    // Every signer is writable and every static non-signer is readonly, which is what a real
    // message looks like once its programs are in it.
    val readonlyStatic = ordered.size - signers.size
    out += listOf(signers.size.toByte(), 0.toByte(), readonlyStatic.toByte())
    out += compact(ordered.size)
    ordered.forEach { out += checkNotNull(decodeBase58(it)) { "not an address: $it" }.toList() }
    out += ByteArray(32) { 5 }.toList()
    out += compact(steps.size)
    for (step in steps) {
        out += resolved.indexOf(step.program).toByte()
        out += compact(step.accounts.size)
        step.accounts.forEach { out += resolved.indexOf(it).toByte() }
        out += compact(step.data.size)
        out += step.data.toList()
    }
    // One table, supplying every account the message did not write down. They are all listed as
    // writable, because which they are does not change how they resolve — only the order does, and
    // one table with only writable indexes is the simplest order there is.
    out += compact(1)
    out += checkNotNull(decodeBase58(table)).toList()
    out += compact(fromTable.size)
    fromTable.indices.forEach { out += it.toByte() }
    out += compact(0)
    return OrderBytes(ByteString.copyFrom(out.toByteArray()), mapOf(table to fromTable))
}

/** A table account as the chain holds one: the program's own header, then the addresses. */
fun tableFor(addresses: List<String>, deactivated: Boolean = false): AccountSnapshot {
    val header = ByteArray(LOOKUP_TABLE_HEADER_BYTES)
    header[0] = 1
    // The deactivation slot: every bit set means it has never been deactivated.
    for (index in 4 until 12) header[index] = if (deactivated) 0 else 0xff.toByte()
    return AccountSnapshot(
        owner = ADDRESS_LOOKUP_TABLE_PROGRAM,
        data = header + addresses.flatMap { checkNotNull(decodeBase58(it)).toList() }.toByteArray(),
        executable = false,
    )
}

/** A chain that serves exactly these tables, and records every address it was asked about. */
class FakeChain(private val tables: Map<String, List<String>> = emptyMap()) : SolanaAccounts {
    val asked = mutableListOf<List<String>>()
    var fails: SolanaProblem? = null
    var missing: Set<String> = emptySet()
    var owner: String = ADDRESS_LOOKUP_TABLE_PROGRAM
    var deactivated: Set<String> = emptySet()
    var truncate: Set<String> = emptySet()
    var replace: Map<String, List<String>> = emptyMap()

    override suspend fun accounts(addresses: List<String>): List<AccountSnapshot?> {
        asked += addresses
        fails?.let { throw SolanaException(it) }
        return addresses.map { address ->
            if (address in missing) return@map null
            val held = replace[address] ?: tables[address] ?: return@map null
            val snapshot = tableFor(held, deactivated = address in deactivated)
            val data =
                if (address in truncate) snapshot.data.copyOfRange(0, snapshot.data.size - 3)
                else snapshot.data
            AccountSnapshot(owner, data, snapshot.executable)
        }
    }
}

/** A provider that answers what a test tells it to, and records what it was asked. */
class FakePrediction : JupiterPrediction {
    val asked = mutableListOf<String>()
    var answersMarket: (String) -> PredictionMarket = { openMarket(it) }
    var answersOrder: (PredictionPayload, PredictionChoice, String) -> PredictionOrder =
        { terms, choice, wallet ->
            predictionOrder(
                orderTransaction(
                        terms = terms,
                        yes = choice.yes,
                        deposit = choice.deposit,
                        owner = wallet,
                    )
                    .transaction,
                yes = choice.yes,
                owner = wallet,
            )
        }

    override suspend fun market(marketId: String): PredictionMarket {
        asked += "market $marketId"
        return answersMarket(marketId)
    }

    override suspend fun order(
        terms: PredictionPayload,
        choice: PredictionChoice,
        wallet: String,
    ): PredictionOrder {
        asked += "order ${terms.marketId} yes=${choice.yes} ${choice.deposit} $wallet"
        return answersOrder(terms, choice, wallet)
    }

    var answersPosition: (String) -> PredictionPosition = {
        throw PredictionException(PredictionProblem.NotFound)
    }
    var answersStatus: (String) -> PredictionOrderStatus = {
        throw PredictionException(PredictionProblem.NotFound)
    }
    var answersClose: (String, String) -> PredictionClose = { _, _ ->
        throw PredictionException(PredictionProblem.Refused)
    }

    override suspend fun position(positionPubkey: String): PredictionPosition {
        asked += "position $positionPubkey"
        return answersPosition(positionPubkey)
    }

    override suspend fun orderStatus(orderPubkey: String): PredictionOrderStatus {
        asked += "status $orderPubkey"
        return answersStatus(orderPubkey)
    }

    override suspend fun closePosition(positionPubkey: String, owner: String): PredictionClose {
        asked += "close $positionPubkey $owner"
        return answersClose(positionPubkey, owner)
    }
}

/** What a market detail looks like on the wire, for the adapter's own tests. */
fun marketBody(
    marketId: String = MARKET_ID,
    status: String = "open",
    result: String? = null,
    eventId: String = EVENT_ID,
): String =
    JSONObject()
        .put("marketId", marketId)
        .put("eventId", eventId)
        .put("provider", "polymarket")
        .put("title", "Barcelona")
        .put("status", status)
        // An explicit JSON null, which is what the wire really sends for an unresolved market —
        // and the case `optString` would read as the four characters "null".
        .put("result", result ?: JSONObject.NULL)
        .put("rulesPrimary", "Resolves to the team that wins.")
        .put("closeTime", 1_811_721_540L)
        .put(
            "pricing",
            JSONObject().put("buyYesPriceUsd", 200_000).put("buyNoPriceUsd", 810_000),
        )
        .toString()
