package io.github.brrenat.seekervault.jupiter

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.plugins.ActionInspection
import io.github.brrenat.seekervault.plugins.InspectedAction
import io.github.brrenat.seekervault.plugins.PluginFact
import io.github.brrenat.seekervault.plugins.PluginFinding
import io.github.brrenat.seekervault.plugins.PluginReference
import io.github.brrenat.seekervault.plugins.actions.PredictionChoice
import io.github.brrenat.seekervault.plugins.actions.PredictionPayload
import io.github.brrenat.seekervault.plugins.actions.WRAPPED_SOL
import io.github.brrenat.seekervault.plugins.actions.depositUnit
import io.github.brrenat.seekervault.solana.ResolvedTransaction
import io.github.brrenat.seekervault.solana.SolanaAccounts
import io.github.brrenat.seekervault.solana.resolveLookups
import io.github.brrenat.seekervault.transactions.DecodeFailure
import io.github.brrenat.seekervault.transactions.DecodeResult
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.transactions.associatedTokenAddress
import io.github.brrenat.seekervault.transactions.decodeTransaction
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.wallet.SelectedWallet

/**
 * Checking the order a provider built against the market, the side and the stake the owner chose
 * (SEE-94).
 *
 * The order of the two halves is the whole design. First the transaction's accounts are
 * **resolved** from the chain, because a versioned message names most of them by index into address
 * lookup tables and until those are read an instruction's accounts are numbers with no meaning.
 * Then the instructions are checked, exactly as strictly as a swap's are — because resolving a
 * table proves only which accounts the runtime will use, and nothing whatever about whether they
 * are the right ones (docs/security.md#inspecting-a-prediction-order).
 *
 * ## What is established
 *
 * 1. The bytes are a transaction this app can read, whole, with nothing left over.
 * 2. Every lookup table it names exists, is owned by the lookup table program, is a live table of a
 *    readable shape, and supplies every index the message takes from it.
 * 3. The owner's wallet pays, and **the only signature still missing is theirs**: the provider
 *    pre-signs with the protocol's own account, which is a different thing from something else
 *    signing alongside the owner, and is checked as such.
 * 4. There is exactly one order instruction, it is the prediction program's, and it is the owner's.
 * 5. It buys the side the owner picked, on the market the publisher named, for no more than the
 *    stake they entered, with the cost and the per-contract ceiling the provider quoted.
 * 6. The stake leaves the owner's own token account for the provider's own token, and the contracts
 *    land in the order's own account.
 * 7. Every other instruction is one the funding may contain — the route, at most one account
 *    creation for the owner, the fee settings — and each is about the owner's own accounts.
 *
 * Anything failing means nothing is put in front of the owner. That is input validation and not a
 * rule they could overrule, and there is no parameter-only fallback: an order whose transaction
 * could not be resolved is an order this app does not offer to sign.
 */

/** What the phone found wrong with, or could not account for in, a built order. */
enum class PredictionFinding(val code: String) {
    /** The bytes are not a transaction this app can read at all. */
    Malformed("malformed"),
    /** A message version this app does not read. */
    UnsupportedVersion("unsupported_version"),
    /** The chain could not be read, so the accounts stayed unresolved. */
    TablesUnread("tables_unread"),
    /** A table the message names is missing, is not a table, is malformed, or is deactivated. */
    TablesUnusable("tables_unusable"),
    /** The message takes an index a table does not have, or names an account beyond the list. */
    TablesInconsistent("tables_inconsistent"),
    /** No wallet is connected, so there is nothing to check the transaction against. */
    NoWallet("no_wallet"),
    /** The wallet is selected for a network this provider does not serve. */
    OtherNetwork("other_network"),
    /** The owner's signature is not the one still missing. */
    NotTheOwnersToSign("not_the_owners_to_sign"),
    /** More than one signature is still missing, so somebody else would sign alongside them. */
    ExtraSigner("extra_signer"),
    /** The fee payer is not the owner's wallet. */
    FeePayerNotTheWallet("fee_payer_not_the_wallet"),
    /** There is no order instruction, so the transaction does not place an order. */
    NoOrder("no_order"),
    /** There is more than one. */
    ExtraOrder("extra_order"),
    /** The order is not the owner's own. */
    NotTheOwnersOrder("not_the_owners_order"),
    /** It sells rather than buys, where a buy was asked for. */
    NotBuying("not_buying"),
    /** It buys rather than sells, where a sale was asked for (SEE-172). */
    NotSelling("not_selling"),
    /** The order is about another position than the one the owner opened the sale from. */
    PositionMismatch("position_mismatch"),
    /** It sells other than exactly the contracts the position holds. */
    QuantityMismatch("quantity_mismatch"),
    /** The sale's floor is missing, or lets the contracts go for far less than the market bids. */
    WeakFloor("weak_floor"),
    /** The proceeds would land in an account that is not the owner's. */
    ProceedsNotOwners("proceeds_not_owners"),
    /** The fees would take a share nobody should approve. */
    ExcessiveFee("excessive_fee"),
    /** It buys the other side from the one the owner picked. */
    OutcomeMismatch("outcome_mismatch"),
    /** The market in the bytes is not the market the provider said it built for. */
    MarketMismatch("market_mismatch"),
    /** The order's identifier is not the one the provider returned. */
    OrderMismatch("order_mismatch"),
    /** The stake in the transaction is not the one the owner entered. */
    DepositMismatch("deposit_mismatch"),
    /** The cost, the contracts or the ceiling are not what the provider quoted. */
    QuoteMismatch("quote_mismatch"),
    /** The stake would come from an account that is not the owner's for the order's token. */
    FundingNotOwners("funding_not_owners"),
    /** The order instruction names a token other than the provider's own. */
    MintMismatch("mint_mismatch"),
    /** The funding swap does not put the stake where the order takes it from. */
    FundingMismatch("funding_mismatch"),
    /** The transaction moves value somewhere an order has no reason to. */
    ExtraTransfer("extra_transfer"),
    /** A token account would be created for someone other than the owner. */
    AccountCreationForSomeoneElse("account_creation_for_someone_else"),
    /** A program that can move value is asked to do something this plugin does not read. */
    UnreadableValueInstruction("unreadable_value_instruction"),
    /** An instruction from somewhere else entirely. It is not thereby safe. */
    UnrecognizedInstruction("unrecognized_instruction"),
}

/**
 * Whether a finding means the bytes must not be approved, or only that the review does not cover
 * all of them. As on the other two paths, only an instruction from an unrelated program is the
 * latter — and neither is approvable.
 */
val PredictionFinding.invalidates: Boolean
    get() = this != PredictionFinding.UnrecognizedInstruction

/**
 * Reads [transaction] and checks it against the market, the owner's [choice], the [order] the
 * provider says it built, and the [wallet] they selected — resolving the message's lookup tables
 * through [chain] first.
 *
 * Throws [io.github.brrenat.seekervault.solana.SolanaException] when the chain could not be read
 * and [io.github.brrenat.seekervault.solana.LookupException] when a table could not be used.
 * Neither is a finding, because neither leaves anything to review: the caller turns them into a
 * refusal to prepare, with the reason the owner is shown.
 */
suspend fun inspectPrediction(
    terms: PredictionPayload,
    choice: PredictionChoice,
    order: PredictionOrder,
    wallet: SelectedWallet?,
    transaction: ByteString,
    version: Int,
    chain: SolanaAccounts,
): ActionInspection {
    val findings = mutableListOf<PredictionFinding>()
    // `resolvable` is how this reader says it can read the chain. Every other reviewer in this app
    // leaves it alone and keeps refusing a message it could not account for on its own.
    val decoded =
        when (val result = decodeTransaction(transaction.toByteArray(), resolvable = true)) {
            is DecodeResult.Decoded -> result.transaction
            is DecodeResult.Failed -> {
                findings +=
                    when (result.failure) {
                        DecodeFailure.UnsupportedVersion -> PredictionFinding.UnsupportedVersion
                        // A message that needs tables is no longer a decode failure here; anything
                        // else that stops the decoder is the bytes themselves.
                        else -> PredictionFinding.Malformed
                    }
                return nothing(findings, version)
            }
        }
    // Both of these propagate rather than becoming findings, and that is the owner's own rule:
    // an operation whose accounts could not be resolved is **not prepared at all**, with its own
    // stated reason, instead of arriving as a review that happens to be unapprovable. A review the
    // owner can read is a review of something the phone could see (SEE-94).
    val resolved = resolveLookups(decoded, chain)

    val owner = wallet?.address
    if (wallet == null) findings += PredictionFinding.NoWallet
    else if (wallet.network.network != SWAP_NETWORK) findings += PredictionFinding.OtherNetwork
    val sponsor = checkSigners(decoded, owner, findings)

    val read = decoded.instructions.map { resolved.readOrderStep(it) }
    if (read.any { it == null }) {
        findings += PredictionFinding.Malformed
        return nothing(findings, version)
    }
    val steps = read.filterNotNull()
    // What could not be read at all is said before anything else, because it is the more useful
    // thing to hear: an order instruction in a shape this plugin does not know shows up as "there
    // is no order" otherwise, which is true and unhelpful.
    for (step in steps.filterIsInstance<OrderStep.Unread>()) {
        findings +=
            if (step.program in ORDER_PROGRAMS) PredictionFinding.UnreadableValueInstruction
            else PredictionFinding.UnrecognizedInstruction
    }
    val orders = steps.filterIsInstance<OrderStep.Order>()
    if (orders.size > 1) findings += PredictionFinding.ExtraOrder
    val placed = orders.firstOrNull()
    if (placed == null) {
        findings += PredictionFinding.NoOrder
        return nothing(findings, version)
    }
    check(placed, terms, choice, order, owner, sponsor, findings)
    checkFunding(steps, terms, choice, placed, owner, sponsor, findings)

    val verdict =
        when {
            findings.any { it.invalidates } -> Verdict.Invalid
            findings.isNotEmpty() -> Verdict.Unverified
            else -> Verdict.Verified
        }
    return ActionInspection(
        verdict = verdict,
        findings = findings.distinct().map(PredictionFinding::finding),
        facts =
            InspectedAction(
                // Whose balance the stake leaves: the owner's, checked above as the order's owner,
                // the funding authority and the one signature still missing. Never the fee payer,
                // which for a gasless order is the provider's sponsor — counting the deposit
                // against it would put the owner's spending in somebody else's day (SEE-181).
                wallet = owner,
                movesValue = true,
                // What the owner spends is the deposit token they chose, whatever the order's own
                // token is: a rule about that asset has to cover this.
                mint = terms.depositMint.takeIf { it != WRAPPED_SOL },
                // Where the stake provably goes: the order's own account. It is not the owner's,
                // and saying it were would be the one dishonest thing available here.
                recipient = placed.order,
                programs = decoded.instructions.mapNotNull(resolved::programOf).distinct(),
                amount = choice.deposit,
                decimals = terms.depositDecimals,
                instructionCount = steps.size,
                recognizedInstructions = steps.count { it !is OrderStep.Unread },
            ),
        version = version,
        details = details(terms, choice, placed, order, resolved),
        // What the owner's record keeps: which order, and which position. Read out of the bytes
        // rather than copied from the answer, so the record names what was actually submitted.
        references =
            listOf(
                PluginReference(ORDER_ACCOUNT, placed.order),
                PluginReference(POSITION_ACCOUNT, placed.position),
                PluginReference(MARKET, terms.marketId),
            ),
        receipt = PREDICTION_RECEIPT,
    )
}

/** The keys an order's record is kept under. Stable, because a record outlives a build. */
const val ORDER_ACCOUNT: String = "order_account"

const val POSITION_ACCOUNT: String = "position_account"

const val MARKET: String = "market_id"

/**
 * Who signs, and who pays the network fee — the same rule for a buy and a sale (SEE-172).
 *
 * The provider fills some slots itself, so the rule is not "nothing else signs" but **"the only
 * signature still missing is the owner's"**. Since 2026-09 Jupiter also builds *gasless*
 * transactions: the fee payer is an account of its own that has already signed, and pays the fee
 * and any rent. That is accepted exactly when the payer's signature is already there — a payer
 * still waiting to sign would be somebody signing alongside the owner — and the payer is returned
 * as the [sponsor] so the instructions can be checked against it: it may pay for the order's
 * accounts and nothing else.
 *
 * Returns the sponsor, or null when the owner pays their own fee.
 */
internal fun checkSigners(
    decoded: io.github.brrenat.seekervault.transactions.DecodedTransaction,
    owner: String?,
    findings: MutableList<PredictionFinding>,
): String? {
    val missing = decoded.emptySignatures
    if (missing.size != 1) findings += PredictionFinding.ExtraSigner
    val ownerSlot = owner?.let { decoded.accounts.indexOf(it) }?.takeIf { it >= 0 }
    if (owner == null || ownerSlot == null || missing.singleOrNull() != ownerSlot) {
        findings += PredictionFinding.NotTheOwnersToSign
    }
    val payer = decoded.feePayer
    return when {
        owner == null -> null.also { findings += PredictionFinding.FeePayerNotTheWallet }
        payer == owner -> null
        // Slot 0 is the fee payer's. Filled means the provider's account has already signed and
        // pays; empty means somebody other than the owner still has to sign, and that is refused.
        0 !in missing -> payer
        else -> null.also { findings += PredictionFinding.FeePayerNotTheWallet }
    }
}

/** The order instruction against what the owner chose and what the provider said. */
private fun check(
    placed: OrderStep.Order,
    terms: PredictionPayload,
    choice: PredictionChoice,
    order: PredictionOrder,
    owner: String?,
    sponsor: String?,
    findings: MutableList<PredictionFinding>,
) {
    // The order is the owner's, and whoever funds its accounts is the owner or the gasless sponsor.
    if (owner == null || placed.payer !in setOfNotNull(owner, sponsor) || placed.owner != owner) {
        findings += PredictionFinding.NotTheOwnersOrder
    }
    if (!placed.buying) findings += PredictionFinding.NotBuying
    // The side the owner picked, read out of the instruction rather than out of the answer.
    if (placed.yes != choice.yes) findings += PredictionFinding.OutcomeMismatch
    // The market can only be cross-checked: the provider's hash is not a digest of the identifier,
    // so what this establishes is that the bytes are for the market the provider answered about.
    if (placed.marketHash != order.marketIdHash) findings += PredictionFinding.MarketMismatch
    if (placed.externalOrderId != order.externalOrderId) {
        findings += PredictionFinding.OrderMismatch
    }
    if (placed.order != order.orderPubkey || placed.position != order.positionPubkey) {
        findings += PredictionFinding.OrderMismatch
    }
    // The three numbers that bound the owner's outcome, each against the quote they were shown.
    if (placed.contractsMicro != order.contractsMicro) findings += PredictionFinding.QuoteMismatch
    if (placed.maxPrice != order.maxBuyPriceUsd) findings += PredictionFinding.QuoteMismatch
    if (placed.cost != order.orderCostUsd) findings += PredictionFinding.QuoteMismatch
    if (placed.slippageBps != order.slippageBps) findings += PredictionFinding.QuoteMismatch
    // The cost is in the provider's own token, and the stake the owner entered is in theirs. They
    // are the same number of base units only when the deposit already is that token; otherwise the
    // route is what converts, and its own input amount is what bounds the stake ([checkFunding]).
    if (terms.depositMint == placed.mint && placed.cost > choice.deposit) {
        findings += PredictionFinding.DepositMismatch
    }
    if (placed.mint != JUP_USD_MINT) findings += PredictionFinding.MintMismatch
    val funding = owner?.let { associatedTokenAddress(it, placed.mint) }
    if (funding == null || placed.funding != funding) findings += PredictionFinding.FundingNotOwners
}

/**
 * Everything else the transaction does: the swap that funds the order, the account it creates, and
 * the fee the owner pays to be picked up.
 *
 * The funding is read by the swap reader, which means the same checks that make a swap approvable
 * apply here — and one more that is specific to an order: what the swap produces has to land in the
 * account the order then spends from.
 */
private fun checkFunding(
    steps: List<OrderStep>,
    terms: PredictionPayload,
    choice: PredictionChoice,
    placed: OrderStep.Order,
    owner: String?,
    sponsor: String?,
    findings: MutableList<PredictionFinding>,
) {
    val routes = steps.mapNotNull { (it as? OrderStep.Funding)?.step as? SwapStep.Route }
    if (routes.size > 1) findings += PredictionFinding.FundingMismatch
    routes.firstOrNull()?.let { route ->
        if (owner == null || route.authority != owner)
            findings += PredictionFinding.FundingNotOwners
        val source = owner?.let { associatedTokenAddress(it, terms.depositMint) }
        if (source == null || route.source != source) findings += PredictionFinding.FundingNotOwners
        // The swap's output must be the very account the order spends from, or the order would be
        // funded by something this transaction did not put there.
        if (route.destination != placed.funding) findings += PredictionFinding.FundingMismatch
        route.sourceMint?.let {
            if (it != terms.depositMint) findings += PredictionFinding.MintMismatch
        }
        if (route.destinationMint != placed.mint) findings += PredictionFinding.MintMismatch
        // And it may take no more of the owner's deposit token than they said they would stake.
        if (route.inAmount > choice.deposit) findings += PredictionFinding.DepositMismatch
        if (route.platformFee != null || route.platformFeeBps != 0) {
            findings += PredictionFinding.FundingMismatch
        }
    }
    // No route at all is the shape of a deposit that is already the provider's own token, and then
    // the stake has to be exactly what the order costs.
    if (routes.isEmpty() && terms.depositMint != placed.mint) {
        findings += PredictionFinding.FundingMismatch
    }
    for (step in steps) {
        when (step) {
            is OrderStep.Order -> Unit
            // Already classified, before the order was looked for.
            is OrderStep.Unread -> Unit
            is OrderStep.Funding ->
                when (val funding = step.step) {
                    is SwapStep.Route -> Unit
                    is SwapStep.Budget -> Unit
                    is SwapStep.Account -> {
                        val expected = owner?.let { associatedTokenAddress(it, funding.mint) }
                        if (
                            owner == null ||
                                funding.owner != owner ||
                                funding.payer !in setOfNotNull(owner, sponsor) ||
                                expected == null ||
                                funding.account != expected ||
                                funding.mint !in setOf(terms.depositMint, placed.mint)
                        ) {
                            findings += PredictionFinding.AccountCreationForSomeoneElse
                        }
                    }
                    // Wrapping is a swap's business with native SOL. An order's deposit token is a
                    // dollar token, so none of it belongs here.
                    is SwapStep.Wrap,
                    is SwapStep.Sync,
                    is SwapStep.Unwrap -> findings += PredictionFinding.ExtraTransfer
                    is SwapStep.Moves -> findings += PredictionFinding.ExtraTransfer
                    is SwapStep.Unread -> findings += PredictionFinding.UnreadableValueInstruction
                }
        }
    }
}

/**
 * The numbers the owner wants: what they stake, what they get if the side wins, the price they are
 * paying, and what it costs to be picked up.
 *
 * Every one read out of the bytes. The payout is arithmetic on the contracts the order buys and is
 * **not** a claim that anything will be paid: whether the side wins, whether the market settles the
 * way anyone expects and whether a payout is ever claimed are all outside this app.
 */
private fun details(
    terms: PredictionPayload,
    choice: PredictionChoice,
    placed: OrderStep.Order,
    order: PredictionOrder,
    resolved: ResolvedTransaction,
): List<PluginFact> {
    val units = terms.depositDecimals
    // Every amount of money carries its unit (SEE-158). The fee is the provider's own figure in
    // dollars, which it settles in the stake token, so it is shown in the stake token too.
    val unit = terms.depositUnit()
    fun money(baseUnits: ULong): String = "${formatBaseUnits(baseUnits, units)} $unit"
    val facts =
        mutableListOf(
            PluginFact(
                if (placed.yes) R.string.jupiter_fact_side_yes else R.string.jupiter_fact_side_no,
                placed.contractsMicro.let { formatBaseUnits(it, CONTRACT_DECIMALS) },
            ),
            PluginFact(R.string.jupiter_fact_stake, money(placed.cost)),
            PluginFact(R.string.jupiter_fact_payout, money(placed.payout)),
            PluginFact(R.string.jupiter_fact_max_price, money(placed.maxPrice), technical = true),
        )
    if (order.totalFeeUsd > 0UL) {
        facts += PluginFact(R.string.jupiter_fact_provider_fee, money(order.totalFeeUsd))
    }
    val budget = resolved.transaction.instructions.size
    facts += PluginFact(R.string.jupiter_fact_instructions, budget.toString(), technical = true)
    facts +=
        PluginFact(
            R.string.jupiter_fact_resolved_accounts,
            "${resolved.accounts.size - resolved.static}",
            technical = true,
        )
    return facts
}

/** Contracts are carried in millionths, and shown as contracts. */
private const val CONTRACT_DECIMALS = 6

private fun nothing(
    findings: List<PredictionFinding>,
    version: Int,
    detail: String? = null,
): ActionInspection =
    ActionInspection.nothingEstablished(
        version,
        findings.distinct().map { it.finding(detail) },
    )

/** The plugin's own wording for one finding. The code is stable; the words live in resources. */
internal fun PredictionFinding.finding(detail: String? = null): PluginFinding =
    PluginFinding(
        code = if (detail == null) code else "$code:$detail",
        message = message,
        invalidates = invalidates,
    )

private val PredictionFinding.message: Int
    get() =
        when (this) {
            PredictionFinding.Malformed -> R.string.jupiter_finding_malformed
            PredictionFinding.UnsupportedVersion -> R.string.jupiter_finding_unsupported_version
            PredictionFinding.TablesUnread -> R.string.jupiter_finding_tables_unread
            PredictionFinding.TablesUnusable -> R.string.jupiter_finding_tables_unusable
            PredictionFinding.TablesInconsistent -> R.string.jupiter_finding_tables_inconsistent
            PredictionFinding.NoWallet -> R.string.jupiter_finding_no_wallet
            PredictionFinding.OtherNetwork -> R.string.jupiter_finding_other_network
            PredictionFinding.NotTheOwnersToSign -> R.string.jupiter_finding_not_the_owners_to_sign
            PredictionFinding.ExtraSigner -> R.string.jupiter_finding_extra_signer
            PredictionFinding.FeePayerNotTheWallet -> R.string.jupiter_finding_fee_payer
            PredictionFinding.NoOrder -> R.string.jupiter_finding_no_order
            PredictionFinding.ExtraOrder -> R.string.jupiter_finding_extra_order
            PredictionFinding.NotTheOwnersOrder -> R.string.jupiter_finding_not_the_owners_order
            PredictionFinding.NotBuying -> R.string.jupiter_finding_not_buying
            PredictionFinding.NotSelling -> R.string.jupiter_finding_not_selling
            PredictionFinding.PositionMismatch -> R.string.jupiter_finding_position
            PredictionFinding.QuantityMismatch -> R.string.jupiter_finding_quantity
            PredictionFinding.WeakFloor -> R.string.jupiter_finding_weak_floor
            PredictionFinding.ProceedsNotOwners -> R.string.jupiter_finding_proceeds
            PredictionFinding.ExcessiveFee -> R.string.jupiter_finding_excessive_fee
            PredictionFinding.OutcomeMismatch -> R.string.jupiter_finding_outcome
            PredictionFinding.MarketMismatch -> R.string.jupiter_finding_market
            PredictionFinding.OrderMismatch -> R.string.jupiter_finding_order
            PredictionFinding.DepositMismatch -> R.string.jupiter_finding_deposit
            PredictionFinding.QuoteMismatch -> R.string.jupiter_finding_prediction_quote
            PredictionFinding.FundingNotOwners -> R.string.jupiter_finding_funding_not_owners
            PredictionFinding.MintMismatch -> R.string.jupiter_finding_mint
            PredictionFinding.FundingMismatch -> R.string.jupiter_finding_funding
            PredictionFinding.ExtraTransfer -> R.string.jupiter_finding_extra_transfer
            PredictionFinding.AccountCreationForSomeoneElse ->
                R.string.jupiter_finding_account_creation
            PredictionFinding.UnreadableValueInstruction ->
                R.string.jupiter_finding_unreadable_value
            PredictionFinding.UnrecognizedInstruction -> R.string.jupiter_finding_unrecognized
        }
