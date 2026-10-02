package io.github.brrenat.seekervault.jupiter

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.SeekerVaultApplication
import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.ActivityRecord
import io.github.brrenat.seekervault.activity.ReviewedOperation
import io.github.brrenat.seekervault.activity.ReviewedSpending
import io.github.brrenat.seekervault.activity.ReviewedTransfer
import io.github.brrenat.seekervault.activity.storage.ActivityStore
import io.github.brrenat.seekervault.plugins.HeldPosition
import io.github.brrenat.seekervault.plugins.JUPITER_PROVIDER
import io.github.brrenat.seekervault.plugins.PREDICTION_BUY_ACTION
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.actionFacts
import io.github.brrenat.seekervault.plugins.actions.PredictionChoice
import io.github.brrenat.seekervault.policy.AssetLimits
import io.github.brrenat.seekervault.policy.DailyCheckScope.Global
import io.github.brrenat.seekervault.policy.DailyTotal
import io.github.brrenat.seekervault.policy.GlobalPolicy
import io.github.brrenat.seekervault.policy.GlobalSpendScope
import io.github.brrenat.seekervault.policy.PolicyAsset
import io.github.brrenat.seekervault.policy.PolicyCheckStatus
import io.github.brrenat.seekervault.policy.PolicyEvaluator
import io.github.brrenat.seekervault.policy.storage.PolicyStore
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.solana.AccountSnapshot
import io.github.brrenat.seekervault.solana.SolanaAccounts
import io.github.brrenat.seekervault.solana.resolveLookups
import io.github.brrenat.seekervault.transactions.DecodeResult
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.transactions.associatedTokenAddress
import io.github.brrenat.seekervault.transactions.decodeTransaction
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/**
 * What Jupiter really answers about a position, and the close and buy it really builds (SEE-172).
 *
 * Captured from the keyless API on 2026-09-28 by `node scripts/capture-jupiter.mjs --positions` for
 * a public trader's open position, with the lookup tables the builds name. Nothing was signed or
 * sent. The tests run offline: the tables are in the fixture, and the adapter is fed the captured
 * bodies over a local server.
 */
@RunWith(AndroidJUnit4::class)
class PositionFixturesTest {
    @get:Rule val folder = TemporaryFolder()

    private val fixture: JSONObject by lazy {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/jupiter/positions.json")) {
                    "fixtures/jupiter/positions.json is missing; run " +
                        "`node scripts/capture-jupiter.mjs --positions`"
                }
                .use { it.readBytes().decodeToString() }
        JSONObject(text)
    }

    private val owner
        get() = fixture.getString("owner")

    private val position
        get() = fixture.getJSONObject("position")

    private val close
        get() = fixture.getJSONObject("close")

    private val buyNo
        get() = fixture.getJSONObject("buyNo")

    private fun tables(build: JSONObject): SolanaAccounts {
        val held =
            build.getJSONArray("tables").let { array ->
                (0 until array.length()).associate { index ->
                    val table = array.getJSONObject(index)
                    table.getString("address") to
                        AccountSnapshot(
                            owner = table.getString("owner"),
                            data = Base64.getDecoder().decode(table.getString("data")),
                            executable = false,
                        )
                }
            }
        return object : SolanaAccounts {
            override suspend fun accounts(addresses: List<String>) = addresses.map { held[it] }
        }
    }

    private fun bytes(build: JSONObject): ByteString =
        ByteString.copyFrom(Base64.getDecoder().decode(build.getString("transaction")))

    private fun orderOf(build: JSONObject): OrderStep.Order = runBlocking {
        val decoded =
            (decodeTransaction(bytes(build).toByteArray(), resolvable = true)
                    as DecodeResult.Decoded)
                .transaction
        val resolved = resolveLookups(decoded, tables(build))
        resolved.transaction.instructions
            .mapNotNull { resolved.readOrderStep(it) }
            .filterIsInstance<OrderStep.Order>()
            .single()
    }

    /** The adapter, reading the captured bodies exactly as the wire sent them. */
    private fun <T> adapter(
        vararg bodies: Pair<Int, String>,
        block: suspend HttpJupiterPrediction.() -> T,
    ): T {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            bodies.forEach { (status, body) ->
                server.enqueue(
                    MockResponse.Builder()
                        .code(status)
                        .setHeader("content-type", "application/json")
                        .body(body)
                        .build()
                )
            }
            return runBlocking {
                HttpJupiterPrediction(OkHttpClient(), "http://127.0.0.1:${server.port}").block()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun theOrderInstructionsFlagsAreTheSideThenTheDirection() {
        // A NO buy and a sale were captured precisely because a YES buy writes 1, 1 and cannot tell
        // the two orders apart. Read the other way round, every NO buy was refused as "not buying".
        val sale = orderOf(close)
        assertEquals(close.getJSONObject("order").getBoolean("isYes"), sale.yes)
        assertFalse(sale.buying)
        assertEquals(
            close.getJSONObject("order").getString("contractsMicro").toULong(),
            sale.contractsMicro,
        )
        // A sale's price field is its floor, and it costs nothing.
        assertEquals(
            close.getJSONObject("order").getString("minSellPriceUsd").toULong(),
            sale.maxPrice,
        )
        assertEquals(0UL, sale.cost)
        assertEquals(associatedTokenAddress(owner, JUP_USD_MINT), sale.funding)

        val buy = orderOf(buyNo)
        assertFalse(buy.yes)
        assertTrue(buy.buying)
    }

    @Test
    fun theRealGaslessNoBuyIsVerified() {
        val order = buyNo.getJSONObject("order")
        val inspection = runBlocking {
            inspectPrediction(
                terms = predictionTerms(marketId = order.getString("marketId")),
                choice = PredictionChoice(yes = false, deposit = 5_000_000UL),
                order =
                    PredictionOrder(
                        transaction = bytes(buyNo),
                        orderPubkey = order.getString("orderPubkey"),
                        positionPubkey = order.getString("positionPubkey"),
                        externalOrderId = order.getString("externalOrderId"),
                        marketIdHash = order.getString("marketIdHash"),
                        isYes = false,
                        isBuy = true,
                        contractsMicro = order.getString("contractsMicro").toULong(),
                        maxBuyPriceUsd = order.getString("maxBuyPriceUsd").toULong(),
                        orderCostUsd = order.getString("orderCostUsd").toULong(),
                        payoutUsd = order.getString("payoutUsd").toULong(),
                        totalFeeUsd = order.getString("estimatedTotalFeeUsd").toULong(),
                        slippageBps = order.getInt("slippageBps"),
                        requiredSigners = listOf(buyNo.getString("owner")),
                    ),
                wallet = wallet(buyNo.getString("owner")),
                transaction = bytes(buyNo),
                version = 1,
                chain = tables(buyNo),
            )
        }
        assertEquals(
            "the review refused a real NO buy: ${inspection.findings.map { it.code }}",
            Verdict.Verified,
            inspection.verdict,
        )
    }

    @Test
    fun theRealGaslessBuyIsTheOwnersSpendingAndNeverTheSponsors() {
        // Jupiter's sponsor pays the fee; the deposit leaves the owner (SEE-181). The day's
        // counters, the review's evaluation and the record pinned before the wallet all have to
        // name the owner, or a gasless buy and the owner's own transfers would never add up.
        val order = buyNo.getJSONObject("order")
        val buyer = buyNo.getString("owner")
        val sponsor =
            checkNotNull(
                (decodeTransaction(bytes(buyNo).toByteArray(), resolvable = true)
                        as DecodeResult.Decoded)
                    .transaction
                    .feePayer
            )
        assertTrue("the capture is gasless", buyNo.getBoolean("isGasless"))
        assertTrue("the sponsor is not the owner", sponsor != buyer)
        val stake = 5_000_000UL
        val inspection = runBlocking {
            inspectPrediction(
                terms = predictionTerms(marketId = order.getString("marketId")),
                choice = PredictionChoice(yes = false, deposit = stake),
                order =
                    PredictionOrder(
                        transaction = bytes(buyNo),
                        orderPubkey = order.getString("orderPubkey"),
                        positionPubkey = order.getString("positionPubkey"),
                        externalOrderId = order.getString("externalOrderId"),
                        marketIdHash = order.getString("marketIdHash"),
                        isYes = false,
                        isBuy = true,
                        contractsMicro = order.getString("contractsMicro").toULong(),
                        maxBuyPriceUsd = order.getString("maxBuyPriceUsd").toULong(),
                        orderCostUsd = order.getString("orderCostUsd").toULong(),
                        payoutUsd = order.getString("payoutUsd").toULong(),
                        totalFeeUsd = order.getString("estimatedTotalFeeUsd").toULong(),
                        slippageBps = order.getInt("slippageBps"),
                        requiredSigners = listOf(buyer),
                    ),
                wallet = wallet(buyer),
                transaction = bytes(buyNo),
                version = 1,
                chain = tables(buyNo),
            )
        }
        assertEquals(Verdict.Verified, inspection.verdict)
        assertEquals(buyer, inspection.facts?.wallet)

        // The facts the review evaluates, read the way the feed's review reads them.
        val registry =
            (RuntimeEnvironment.getApplication() as SeekerVaultApplication).providerRegistry
        val facts =
            actionFacts(
                connectionId = CONNECTION,
                proposalId = BUY_ID,
                action = PREDICTION_BUY_ACTION,
                network = Network.NETWORK_MAINNET,
                resolution =
                    registry.resolve(
                        provider = JUPITER_PROVIDER,
                        action = PREDICTION_BUY_ACTION,
                        schemaVersion = 1,
                        network = Network.NETWORK_MAINNET,
                        environment = PluginEnvironment.Production,
                    ),
                inspection = inspection,
            )
        val usdc = PolicyAsset(Network.NETWORK_MAINNET, USDC_MINT)
        val spending = ReviewedSpending.Outgoing(buyer, Network.NETWORK_MAINNET, USDC_MINT, stake)
        assertEquals(buyer, facts.scope?.wallet)
        assertEquals(spending, facts.spending())

        // Under an 8 USDC global threshold, the owner already sent 4 USDC today from another
        // connection: this 5 USDC buy goes over, because it is counted against the owner.
        val policies = PolicyStore(folder.newFolder("policies"))
        val activity = folder.newFolder("activity")
        policies.putGlobal(
            GlobalPolicy.default(NOW).copy(limits = mapOf(usdc to AssetLimits(daily = 8_000_000UL)))
        )
        val evaluator =
            PolicyEvaluator(
                policies,
                records = { ActivityStore(activity).snapshot().records },
                now = { NOW },
                zone = { ZoneOffset.UTC },
            )
        ActivityStore(activity).put(transfer(buyer, 4_000_000UL))
        val buying = evaluator.evaluate(facts).dailyChecks.single { it.scope == Global }
        assertEquals(9_000_000UL, buying.projected)
        assertEquals(PolicyCheckStatus.Failed, buying.result.status)

        // And once it is recorded — the binding's spending, persisted and read back — the owner's
        // next transfer sees it, and the sponsor's scope has spent nothing at all.
        ActivityStore(activity).put(buy(buyer, spending))
        val owners = checkNotNull(evaluator.spentToday(GlobalSpendScope(buyer, usdc)))
        assertEquals(9_000_000UL, owners.confirmed)
        assertEquals(
            DailyTotal.none(GlobalSpendScope(sponsor, usdc), owners.day),
            evaluator.spentToday(GlobalSpendScope(sponsor, usdc)),
        )
    }

    /** A direct USDC transfer the owner made today through another connection. */
    private fun transfer(owner: String, amount: ULong) =
        ActivityRecord(
            connectionId = OTHER_CONNECTION,
            requestId = TRANSFER_ID,
            source = "Hermes",
            serverHost = "sidecar.example",
            kind = ActivityKind.Transfer,
            answeredAt = NOW,
            recordedAt = NOW,
            outcome = ActivityOutcome.Confirmed,
            transfer =
                ReviewedTransfer(
                    wallet = owner,
                    network = Network.NETWORK_MAINNET,
                    recipient = "7LCE7pWnYuYKtGMWt2Q4aXKQrqTmGf4c1Kt1PTmAGwSt",
                    amount = amount.toString(),
                    mint = USDC_MINT,
                    preparedVersion = 1,
                ),
            signature = "transfer-signature",
        )

    /** The gasless buy's own record, with the spending its binding pinned. */
    private fun buy(owner: String, spending: ReviewedSpending) =
        ActivityRecord(
            connectionId = CONNECTION,
            requestId = BUY_ID,
            source = "Signals",
            serverHost = "gateway.example",
            kind = ActivityKind.Operation,
            answeredAt = NOW,
            recordedAt = NOW,
            outcome = ActivityOutcome.Confirmed,
            operation =
                ReviewedOperation(
                    operation = PREDICTION_BUY_ACTION.value,
                    plugin = JUPITER_PROVIDER.value,
                    contract = 1,
                    revision = 1,
                    wallet = owner,
                    network = Network.NETWORK_MAINNET,
                    environment = PluginEnvironment.Production,
                    preparedVersion = 1,
                    spending = spending,
                ),
            signature = "buy-signature",
        )

    @Test
    fun theRealCloseIsVerifiedForItsOwnerAndRefusedForAnyoneElse() {
        val stated =
            adapter(200 to close.toString()) {
                closePosition(position.getString("pubkey"), owner)
            }
        val now = adapter(200 to position.toString()) { position(position.getString("pubkey")) }
        val held =
            HeldPosition(
                provider = JUPITER_PROVIDER,
                owner = owner,
                network = Network.NETWORK_MAINNET,
                account = now.positionPubkey,
                marketId = now.marketId,
                yes = now.isYes,
            )
        val read = runBlocking {
            inspectPredictionSale(held, now, stated, wallet(owner), 1, tables(close))
        }
        assertEquals(
            "the review refused a real close: ${read.inspection.findings.map { it.code }}",
            Verdict.Verified,
            read.inspection.verdict,
        )
        assertEquals(now.contractsMicro, read.terms.contractsMicro)
        // Gasless: Jupiter's own account pays and has already signed.
        assertTrue(stated.gasless)
        assertEquals(null, stated.executionModel)
        assertEquals("create_order", stated.executionType)
        assertTrue(read.terms.sponsor != null && read.terms.sponsor != owner)

        val stranger = runBlocking {
            inspectPredictionSale(
                held,
                now,
                stated,
                wallet("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"),
                2,
                tables(close),
            )
        }
        assertFalse(stranger.inspection.approvable)
    }

    @Test
    fun theRealPositionIsReadWithEveryFigureTheProviderSent() {
        val read = adapter(200 to position.toString()) { position(position.getString("pubkey")) }
        assertEquals(position.getString("contractsMicro").toULong(), read.contractsMicro)
        assertEquals(position.getString("totalCostUsd").toULong(), read.totalCostUsd)
        assertEquals(position.getString("sellPriceUsd").toULong(), read.sellPriceUsd)
        assertEquals(position.getString("pnlUsd").toLong(), read.pnlUsd)
        assertEquals("open", read.marketStatus)
        assertNull(read.marketResult)
        assertEquals("polymarket", read.venue)
        assertEquals(
            io.github.brrenat.seekervault.plugins.MarketStanding.Open,
            read.reading().market,
        )
    }

    @Test
    fun aPositionTheProviderHasNoRecordOfIsNotFoundAndNeverZero() {
        val missing = fixture.getJSONObject("missing")
        assertEquals(404, missing.getInt("status"))
        val problem =
            try {
                adapter(404 to missing.getJSONObject("body").toString()) {
                    position("11111111111111111111111111111111")
                }
                null
            } catch (e: PredictionException) {
                e.problem
            }
        assertEquals(PredictionProblem.NotFound, problem)
    }

    @Test
    fun theRealPartialFillReadsAsPartlyFilledAndFinished() {
        val status = fixture.getJSONObject("orderStatus")
        val read =
            adapter(200 to status.toString()) { orderStatus(status.getString("orderPubkey")) }
        assertEquals(OrderFill.PartiallyFilledClosed, read.fill)
        assertTrue(read.finished)
        assertEquals(65_660_000UL, read.contractsMicro)
        assertEquals(63_550_000UL, read.filledContractsMicro)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-28T12:00:00Z")
        const val CONNECTION = "11111111-2222-4333-8444-555555555555"
        const val OTHER_CONNECTION = "22222222-3333-4444-8555-666666666666"
        const val BUY_ID = "0b6f5a1e-3c2d-4e8f-9a7b-1c2d3e4f5a6b"
        const val TRANSFER_ID = "5e4d3c2b-1a09-4f8e-8d7c-6b5a49382716"
    }
}
