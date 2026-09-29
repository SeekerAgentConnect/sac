package io.github.brrenat.seekervault.jupiter

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.plugins.ActionInspection
import io.github.brrenat.seekervault.plugins.InspectedAction
import io.github.brrenat.seekervault.plugins.PluginFact
import io.github.brrenat.seekervault.plugins.PluginFinding
import io.github.brrenat.seekervault.plugins.PluginReference
import io.github.brrenat.seekervault.plugins.ReceiptKey
import io.github.brrenat.seekervault.plugins.ServiceFeeStatus
import io.github.brrenat.seekervault.plugins.actions.SwapChoice
import io.github.brrenat.seekervault.plugins.actions.SwapPayload
import io.github.brrenat.seekervault.plugins.actions.WRAPPED_SOL
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.transactions.DecodeFailure
import io.github.brrenat.seekervault.transactions.DecodeResult
import io.github.brrenat.seekervault.transactions.LAMPORT_DECIMALS
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.transactions.associatedTokenAddress
import io.github.brrenat.seekervault.transactions.decodeTransaction
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.wallet.SelectedWallet

/**
 * Checking the transaction a provider built against what the owner actually chose (SEE-93).
 *
 * This is the whole reason a swap can be approved on this phone at all. Jupiter builds the
 * transaction, and Jupiter's account of what it built is not read here: the bytes are decoded, each
 * instruction is read, and every number the owner is shown comes out of the instruction that
 * carries it (docs/security.md#inspecting-a-swap).
 *
 * ## What is established, in order
 *
 * 1. The bytes are a transaction this app can read, whole, with nothing left over and no account
 *    loaded from a lookup table.
 * 2. Nothing is signed yet, the owner's wallet pays, and **nothing else signs**.
 * 3. There is exactly one routing instruction, it is the aggregator's, and the owner authorizes it.
 * 4. The input leaves the owner's own token account for the mint the publisher named, in exactly
 *    the amount the owner entered.
 * 5. The output arrives in the owner's own token account for the mint the publisher named, and the
 *    floor the instruction enforces is the one the quote stated.
 * 6. Nobody takes a cut but the one this build decided on before quoting: with no SAC service fee,
 *    no platform fee account and no fee in basis points; with one, exactly its rate, exactly its
 *    verified receiving account, in the output mint (SEE-173, [SwapFee]).
 * 7. Every other instruction is one of the small set a swap is allowed to contain, and each one is
 *    about the owner's own accounts.
 *
 * Any of those failing means the preparation is not put in front of the owner. It is input
 * validation and not a rule they could overrule
 * (docs/security.md#verification-versus-advisory-rules).
 *
 * ## What is not established
 *
 * The route plan inside the routing instruction, which names the pools the swap will hop through.
 * It is not read, it could not usefully be — every venue encodes its leg differently — and it is
 * not a gap in the review, because it cannot change any of the seven things above: the program
 * takes the input from the account in (4), puts the output in the account in (5), and fails the
 * transaction if the output is under the floor in (5). The phone verifies the bounds; the chain
 * enforces them (docs/wiki/jupiter-swap.md#what-the-review-covers).
 */

/** What the phone found wrong with, or could not account for in, a built swap. */
enum class SwapFinding(val code: String) {
    /** The bytes are not a transaction this app can read at all. */
    Malformed("malformed"),
    /** A message version this app doesn't read. */
    UnsupportedVersion("unsupported_version"),
    /**
     * The message loads accounts from an address lookup table, so what it touches cannot be seen
     * without reading the chain — which this app never does.
     */
    AddressTableLookup("address_table_lookup"),
    /** A signature slot is already filled. The wallet must be handed something unsigned. */
    AlreadySigned("already_signed"),
    /** No wallet is connected, so there is nothing to check the transaction against. */
    NoWallet("no_wallet"),
    /** The wallet is selected for a network this provider does not serve. */
    OtherNetwork("other_network"),
    /** The fee payer isn't the owner's wallet. */
    FeePayerNotTheWallet("fee_payer_not_the_wallet"),
    /** Something other than the owner's wallet would have to sign. */
    ExtraSigner("extra_signer"),
    /** There is no routing instruction, so the transaction does not swap anything. */
    NoRoute("no_route"),
    /** There is more than one, which would be more than one swap. */
    ExtraRoute("extra_route"),
    /** The owner does not authorize the input leaving. */
    NotTheOwnersRoute("not_the_owners_route"),
    /** The input would come from an account that isn't the owner's own for the input mint. */
    SourceNotOwnersAccount("source_not_owners_account"),
    /** The output would go to an account that isn't the owner's own for the output mint. */
    DestinationNotOwnersAccount("destination_not_owners_account"),
    /** The instruction names a different mint than the terms do. */
    MintMismatch("mint_mismatch"),
    /** The input amount in the instruction isn't the amount the owner entered. */
    AmountMismatch("amount_mismatch"),
    /** The slippage in the instruction isn't the one the owner chose. */
    SlippageMismatch("slippage_mismatch"),
    /**
     * The floor the instruction will enforce is not the one the quote stated. Either the offer
     * changed between quoting and building, or the instruction's numbers are not the quote's.
     */
    QuoteMismatch("quote_mismatch"),
    /** Somebody would take a cut of the output, and this swap was to carry none. */
    PlatformFee("platform_fee"),
    /** The fee rate in the transaction, or in the quote, is not the one this build decided on. */
    FeeRateMismatch("fee_rate_mismatch"),
    /** The fee would be paid to an account other than the verified one, or to none. */
    FeeAccountMismatch("fee_account_mismatch"),
    /** The fee would be taken in a mint other than the output mint its account was verified for. */
    FeeMintMismatch("fee_mint_mismatch"),
    /** The route takes more hops than this plugin reads. */
    TooManyLegs("too_many_legs"),
    /** The transaction moves value somewhere a swap has no reason to. */
    ExtraTransfer("extra_transfer"),
    /** A token account would be created for someone other than the owner. */
    AccountCreationForSomeoneElse("account_creation_for_someone_else"),
    /** Wrapped SOL would be handled for an account that isn't the owner's, or sent elsewhere. */
    WrappingNotTheOwners("wrapping_not_the_owners"),
    /** More SOL would be wrapped than the owner is spending. */
    WrapsMoreThanTheAmount("wraps_more_than_the_amount"),
    /** SOL would be wrapped or unwrapped when neither side of the swap is SOL. */
    UnexpectedWrapping("unexpected_wrapping"),
    /**
     * A program that can move value — the aggregator, System, the token programs — is asked to do
     * something this plugin doesn't read. A program being one a swap may call is no reason to
     * accept every instruction it offers.
     */
    UnreadableValueInstruction("unreadable_value_instruction"),
    /** An instruction from somewhere else entirely. It is not thereby safe. */
    UnrecognizedInstruction("unrecognized_instruction"),
}

/**
 * Whether a finding means the bytes must not be approved, or only that the review does not cover
 * all of them.
 *
 * Only [SwapFinding.UnrecognizedInstruction] is the latter, exactly as on the transfer path: an
 * instruction from an unrelated program is something nobody read, while everything else here is a
 * disagreement between the bytes and what the owner chose. Neither is approvable — an approvable
 * preparation is one with no findings at all — and they are kept apart because they are different
 * things to tell someone.
 */
val SwapFinding.invalidates: Boolean
    get() = this != SwapFinding.UnrecognizedInstruction

/** The provider Jupiter serves. Its routes are on one network, and a swap on another is fiction. */
val SWAP_NETWORK: Network = Network.NETWORK_MAINNET

/**
 * Reads [transaction] and checks it against [terms], the owner's [choice], the [quote] they were
 * shown, and the [wallet] they selected.
 *
 * The order matters and is the one the transfer path uses: the bytes are read first and judged
 * afterwards, so nothing the provider said about them can steer the reading.
 */
fun inspectSwap(
    terms: SwapPayload,
    choice: SwapChoice,
    quote: JupiterQuote,
    wallet: SelectedWallet?,
    transaction: ByteString,
    version: Int,
    fee: SwapFee = SwapFee.Disabled,
): ActionInspection {
    val findings = mutableListOf<SwapFinding>()
    val decoded =
        when (val result = decodeTransaction(transaction.toByteArray())) {
            is DecodeResult.Decoded -> result.transaction
            is DecodeResult.Failed -> {
                findings +=
                    when (result.failure) {
                        DecodeFailure.Malformed -> SwapFinding.Malformed
                        DecodeFailure.UnsupportedVersion -> SwapFinding.UnsupportedVersion
                        DecodeFailure.AddressTableLookup -> SwapFinding.AddressTableLookup
                    }
                return nothing(findings, version)
            }
        }

    if (!decoded.unsigned) findings += SwapFinding.AlreadySigned
    val owner = wallet?.address
    if (wallet == null) findings += SwapFinding.NoWallet
    else if (wallet.network.network != SWAP_NETWORK) findings += SwapFinding.OtherNetwork
    // The owner's wallet pays, and nothing else signs. A second signer would be somebody else's
    // approval riding along with theirs.
    if (owner == null || decoded.feePayer != owner) findings += SwapFinding.FeePayerNotTheWallet
    if (owner == null || decoded.signers != listOf(owner)) findings += SwapFinding.ExtraSigner

    val read = decoded.instructions.map { decoded.readSwapStep(it) }
    if (read.any { it == null }) {
        findings += SwapFinding.Malformed
        return nothing(findings, version)
    }
    val steps = read.filterNotNull()
    val routes = steps.filterIsInstance<SwapStep.Route>()
    if (routes.size > 1) findings += SwapFinding.ExtraRoute
    val route = routes.firstOrNull()
    if (route == null) {
        findings += SwapFinding.NoRoute
        return nothing(findings, version)
    }
    // Everything the owner risks is in this one instruction, so every field of it that bounds the
    // outcome is checked against something the owner or the offer actually said.
    if (owner == null || route.authority != owner) findings += SwapFinding.NotTheOwnersRoute
    val source = owner?.let { associatedTokenAddress(it, terms.inputMint) }
    val destination = owner?.let { associatedTokenAddress(it, terms.outputMint) }
    if (source == null || route.source != source) findings += SwapFinding.SourceNotOwnersAccount
    if (destination == null || route.destination != destination) {
        findings += SwapFinding.DestinationNotOwnersAccount
    }
    if (route.destinationMint != terms.outputMint) findings += SwapFinding.MintMismatch
    // The shared-accounts variant names the source mint too. The other does not, and needs no
    // exception made for it: the source account was derived from the owner and the input mint,
    // which says the same thing.
    route.sourceMint?.let { if (it != terms.inputMint) findings += SwapFinding.MintMismatch }
    if (route.inAmount != choice.amount) findings += SwapFinding.AmountMismatch
    if (route.slippageBps != choice.slippageBps) findings += SwapFinding.SlippageMismatch
    // The offer the owner was shown, and the floor the chain will enforce, have to be the same two
    // numbers. If a later version of the program moved these fields, this is where it shows up.
    if (route.quotedOutAmount != quote.outAmount || route.minimumOut != quote.minimumOut) {
        findings += SwapFinding.QuoteMismatch
    }
    checkFee(route, quote, terms, fee, findings)
    if (route.legs != MOST_LEGS) findings += SwapFinding.TooManyLegs

    checkOthers(steps, terms, choice, owner, findings)

    val verdict =
        when {
            findings.any { it.invalidates } -> Verdict.Invalid
            findings.isNotEmpty() -> Verdict.Unverified
            else -> Verdict.Verified
        }
    return ActionInspection(
        verdict = verdict,
        findings = findings.distinct().map(SwapFinding::finding),
        facts =
            InspectedAction(
                wallet = decoded.feePayer,
                movesValue = true,
                // A swap of native SOL spends SOL, whatever the pool needs it wrapped into. A rule
                // about SOL has to cover it, so the asset is what the owner holds and not what the
                // route touches.
                mint = terms.inputMint.takeIf { it != WRAPPED_SOL },
                // Where the output provably goes, which in a swap is the owner. Unlike a transfer,
                // the destination here is not an address somebody else named: it is derived from
                // the owner's own key and the mint the publisher named, so there is no third
                // party's claim about it to check (docs/wiki/jupiter-swap.md#the-owner-receives).
                recipient =
                    owner.takeIf { destination != null && route.destination == destination },
                programs = decoded.instructions.mapNotNull(decoded::programOf).distinct(),
                amount = route.inAmount,
                decimals =
                    if (terms.inputMint == WRAPPED_SOL) LAMPORT_DECIMALS else terms.inputDecimals,
                instructionCount = steps.size,
                recognizedInstructions = steps.count { it !is SwapStep.Unread },
            ),
        version = version,
        details = details(terms, route, steps, quote, fee),
        receipt = swapReceipt(terms, route, quote, fee),
    )
}

/**
 * What the owner's record keeps about who routed this and what it cost (SEE-173): the routing
 * integration's name, and the SAC service fee exactly as the bytes carry it — or why there is none.
 */
internal fun swapReceipt(
    terms: SwapPayload,
    route: SwapStep.Route,
    quote: JupiterQuote,
    fee: SwapFee,
): List<PluginReference> = buildList {
    add(PluginReference(ReceiptKey.SWAP_ROUTING, SWAP_ROUTING_NAME))
    when (fee) {
        is SwapFee.Charged -> {
            add(PluginReference(ReceiptKey.SERVICE_FEE_STATUS, ServiceFeeStatus.CHARGED))
            add(PluginReference(ReceiptKey.SERVICE_FEE_BPS, route.platformFeeBps.toString()))
            add(
                PluginReference(
                    ReceiptKey.SERVICE_FEE_ESTIMATE,
                    labelled(
                        quote.platformFeeAmount,
                        terms.outputDecimals,
                        terms.outputSymbol.ifEmpty { terms.outputMint },
                    ),
                )
            )
            add(PluginReference(ReceiptKey.SERVICE_FEE_MINT, fee.mint))
            add(PluginReference(ReceiptKey.SERVICE_FEE_SIDE, ServiceFeeStatus.SIDE_OUTPUT))
            add(PluginReference(ReceiptKey.SERVICE_FEE_RECIPIENT, fee.account))
        }
        SwapFee.Disabled ->
            add(PluginReference(ReceiptKey.SERVICE_FEE_STATUS, ServiceFeeStatus.NONE))
        is SwapFee.NotForPair ->
            add(PluginReference(ReceiptKey.SERVICE_FEE_STATUS, ServiceFeeStatus.NOT_FOR_PAIR))
        is SwapFee.Unverified -> {
            add(PluginReference(ReceiptKey.SERVICE_FEE_STATUS, ServiceFeeStatus.UNVERIFIED))
            add(PluginReference(ReceiptKey.SERVICE_FEE_REASON, fee.reason))
        }
    }
}

/**
 * The fee in the bytes against the fee decided before quoting (SEE-173).
 *
 * With none decided, any fee account or any rate is somebody's cut ([SwapFinding.PlatformFee]).
 * With one, the rate, the account and the mint all have to be exactly the decided ones — a fee that
 * is missing is as much a disagreement as one that is larger, because either way the review would
 * describe a transaction other than this one.
 */
private fun checkFee(
    route: SwapStep.Route,
    quote: JupiterQuote,
    terms: SwapPayload,
    fee: SwapFee,
    findings: MutableList<SwapFinding>,
) {
    if (fee !is SwapFee.Charged) {
        if (route.platformFee != null || route.platformFeeBps != 0 || quote.platformFeeBps != 0) {
            findings += SwapFinding.PlatformFee
        }
        return
    }
    if (route.platformFeeBps != fee.bps || quote.platformFeeBps != fee.bps) {
        findings += SwapFinding.FeeRateMismatch
    }
    if (route.platformFee != fee.account) findings += SwapFinding.FeeAccountMismatch
    // The account was verified for the output mint, and the output is the only side this app
    // lets a fee be taken from: an input-mint fee would not be the one the quote was made with.
    if (fee.mint != terms.outputMint || route.destinationMint != fee.mint) {
        findings += SwapFinding.FeeMintMismatch
    }
}

/**
 * The numbers the owner actually wants: what they will get at worst, what they were quoted, how far
 * apart those are, and what the transaction will cost to be picked up.
 *
 * All of it read out of the bytes rather than out of the provider's answer, which is the difference
 * between showing somebody a review and showing them a receipt somebody else wrote.
 */
private fun details(
    terms: SwapPayload,
    route: SwapStep.Route,
    steps: List<SwapStep>,
    quote: JupiterQuote,
    fee: SwapFee,
): List<PluginFact> {
    val out = terms.outputDecimals
    val facts =
        mutableListOf(
            // Who routes this swap, named as what it is: Jupiter's Metis routing through its v1
            // Swap API — not "Jupiter Ultra", and not the same thing as Jupiter's own web app.
            PluginFact(R.string.jupiter_fact_routing, SWAP_ROUTING_NAME),
            PluginFact(
                R.string.jupiter_fact_minimum_out,
                labelled(route.minimumOut, out, terms.outputSymbol),
            ),
            PluginFact(
                R.string.jupiter_fact_quoted_out,
                labelled(route.quotedOutAmount, out, terms.outputSymbol),
            ),
            PluginFact(R.string.jupiter_fact_slippage, percent(route.slippageBps)),
        )
    facts += feeFacts(terms, route, quote, fee)
    // What a priority fee actually costs, in lamports: the limit the transaction asks for times the
    // price per unit. Both are in the bytes; neither is the provider's word for it.
    val limit = steps.filterIsInstance<SwapStep.Budget>().firstNotNullOfOrNull { it.unitLimit }
    val price =
        steps.filterIsInstance<SwapStep.Budget>().firstNotNullOfOrNull { it.microLamportsPerUnit }
    if (limit != null && price != null) {
        val lamports = limit.toULong() * price / 1_000_000UL
        facts +=
            PluginFact(
                R.string.jupiter_fact_priority_fee,
                formatBaseUnits(lamports, LAMPORT_DECIMALS),
            )
    }
    // Rent for a token account the owner does not have yet. It is theirs afterwards, and it is
    // still money leaving today, so it is said rather than left to be noticed on chain.
    if (steps.any { it is SwapStep.Account }) {
        facts +=
            PluginFact(
                R.string.jupiter_fact_new_account,
                terms.outputSymbol.ifEmpty { terms.outputMint },
            )
    }
    return facts
}

private fun labelled(amount: ULong, decimals: Int, symbol: String): String {
    val written = formatBaseUnits(amount, decimals)
    return if (symbol.isEmpty()) written else "$written $symbol"
}

/** Basis points as a percentage, exactly: 50 is "0.5%", 1 is "0.01%". */
internal fun percent(bps: Int): String = formatBaseUnits(bps.toULong(), 2) + "%"

/**
 * The SAC service fee, as the owner should read it before signing (SEE-173).
 *
 * Always present, so a build that charges nothing says "0%" rather than nothing — while never
 * claiming the swap is free: the priority fee, the network's base fee and the route's pool fees
 * (already inside the quote) are separate and still apply. When a fee is charged, the rate and
 * where it is taken from are on the label, the estimate is the value, and the recipient account is
 * its own row.
 */
private fun feeFacts(
    terms: SwapPayload,
    route: SwapStep.Route,
    quote: JupiterQuote,
    fee: SwapFee,
): List<PluginFact> =
    when (fee) {
        is SwapFee.Charged ->
            listOf(
                PluginFact(R.string.jupiter_fact_service_fee_rate, percent(route.platformFeeBps)),
                PluginFact(
                    R.string.jupiter_fact_service_fee_estimate,
                    labelled(
                        quote.platformFeeAmount,
                        terms.outputDecimals,
                        terms.outputSymbol.ifEmpty { terms.outputMint },
                    ),
                ),
                PluginFact(R.string.jupiter_fact_service_fee_recipient, fee.account),
            )
        SwapFee.Disabled -> listOf(PluginFact(R.string.jupiter_fact_service_fee_none, percent(0)))
        is SwapFee.NotForPair ->
            listOf(PluginFact(R.string.jupiter_fact_service_fee_not_for_pair, percent(0)))
        is SwapFee.Unverified ->
            listOf(PluginFact(R.string.jupiter_fact_service_fee_unverified, percent(0)))
    }

/**
 * The name the review gives the routing integration.
 *
 * Jupiter's API agreement requires an integration of its v1 Swap API to be labelled "Metis" — not
 * "Jupiter Ultra", and not "Jupiter" as though it were Jupiter's own web app — and requires
 * "Powered by Jupiter" to be displayed (docs/integrations/jupiter.md#attribution). It is a name, so
 * it is not translated.
 */
const val SWAP_ROUTING_NAME: String = "Metis · Powered by Jupiter"

/**
 * The rest of the transaction: the wrap, the unwrap, the account creation and the fee settings.
 *
 * None of them is optional to read. Each one touches the owner's money — a close sends what is left
 * in an account somewhere, a creation spends rent — and each is allowed only in the one shape a
 * swap has a reason for.
 */
private fun checkOthers(
    steps: List<SwapStep>,
    terms: SwapPayload,
    choice: SwapChoice,
    owner: String?,
    findings: MutableList<SwapFinding>,
) {
    val wrapped = owner?.let { associatedTokenAddress(it, WRAPPED_SOL) }
    val nativeInvolved = terms.inputMint == WRAPPED_SOL || terms.outputMint == WRAPPED_SOL
    for (step in steps) {
        when (step) {
            is SwapStep.Route -> Unit
            is SwapStep.Budget -> Unit
            is SwapStep.Wrap -> {
                if (!nativeInvolved || terms.inputMint != WRAPPED_SOL) {
                    findings += SwapFinding.UnexpectedWrapping
                }
                if (owner == null || step.from != owner || wrapped == null || step.to != wrapped) {
                    findings += SwapFinding.WrappingNotTheOwners
                }
                // It may wrap what the owner is spending, and never more: the amount is the whole
                // of what they agreed to part with.
                if (step.lamports > choice.amount) findings += SwapFinding.WrapsMoreThanTheAmount
            }
            is SwapStep.Sync -> {
                if (!nativeInvolved) findings += SwapFinding.UnexpectedWrapping
                if (wrapped == null || step.account != wrapped) {
                    findings += SwapFinding.WrappingNotTheOwners
                }
            }
            is SwapStep.Unwrap -> {
                if (!nativeInvolved) findings += SwapFinding.UnexpectedWrapping
                // Closing an account sends its lamports somewhere. The account has to be the
                // owner's own wrapped-SOL account, and what is in it has to come back to them.
                if (
                    wrapped == null ||
                        step.account != wrapped ||
                        owner == null ||
                        step.to != owner ||
                        step.authority != owner
                ) {
                    findings += SwapFinding.WrappingNotTheOwners
                }
            }
            is SwapStep.Account -> {
                val expected = owner?.let { associatedTokenAddress(it, step.mint) }
                if (
                    owner == null ||
                        step.owner != owner ||
                        step.payer != owner ||
                        expected == null ||
                        step.account != expected ||
                        step.mint !in setOf(terms.inputMint, terms.outputMint)
                ) {
                    findings += SwapFinding.AccountCreationForSomeoneElse
                }
            }
            is SwapStep.Moves -> findings += SwapFinding.ExtraTransfer
            is SwapStep.Unread ->
                findings +=
                    if (step.program in SWAP_PROGRAMS) SwapFinding.UnreadableValueInstruction
                    else SwapFinding.UnrecognizedInstruction
        }
    }
}

/** Nothing was established, which is never approvable and is not a verdict on the operation. */
private fun nothing(findings: List<SwapFinding>, version: Int): ActionInspection =
    ActionInspection.nothingEstablished(version, findings.distinct().map(SwapFinding::finding))

/** The plugin's own wording for one finding. The code is stable; the words live in resources. */
internal fun SwapFinding.finding(): PluginFinding =
    PluginFinding(code = code, message = message, invalidates = invalidates)

private val SwapFinding.message: Int
    get() =
        when (this) {
            SwapFinding.Malformed -> R.string.jupiter_finding_malformed
            SwapFinding.UnsupportedVersion -> R.string.jupiter_finding_unsupported_version
            SwapFinding.AddressTableLookup -> R.string.jupiter_finding_lookup_table
            SwapFinding.AlreadySigned -> R.string.jupiter_finding_already_signed
            SwapFinding.NoWallet -> R.string.jupiter_finding_no_wallet
            SwapFinding.OtherNetwork -> R.string.jupiter_finding_other_network
            SwapFinding.FeePayerNotTheWallet -> R.string.jupiter_finding_fee_payer
            SwapFinding.ExtraSigner -> R.string.jupiter_finding_extra_signer
            SwapFinding.NoRoute -> R.string.jupiter_finding_no_route
            SwapFinding.ExtraRoute -> R.string.jupiter_finding_extra_route
            SwapFinding.NotTheOwnersRoute -> R.string.jupiter_finding_not_the_owners
            SwapFinding.SourceNotOwnersAccount -> R.string.jupiter_finding_source
            SwapFinding.DestinationNotOwnersAccount -> R.string.jupiter_finding_destination
            SwapFinding.MintMismatch -> R.string.jupiter_finding_mint
            SwapFinding.AmountMismatch -> R.string.jupiter_finding_amount
            SwapFinding.SlippageMismatch -> R.string.jupiter_finding_slippage
            SwapFinding.QuoteMismatch -> R.string.jupiter_finding_quote
            SwapFinding.PlatformFee -> R.string.jupiter_finding_platform_fee
            SwapFinding.FeeRateMismatch -> R.string.jupiter_finding_fee_rate
            SwapFinding.FeeAccountMismatch -> R.string.jupiter_finding_fee_account
            SwapFinding.FeeMintMismatch -> R.string.jupiter_finding_fee_mint
            SwapFinding.TooManyLegs -> R.string.jupiter_finding_legs
            SwapFinding.ExtraTransfer -> R.string.jupiter_finding_extra_transfer
            SwapFinding.AccountCreationForSomeoneElse -> R.string.jupiter_finding_account_creation
            SwapFinding.WrappingNotTheOwners -> R.string.jupiter_finding_wrapping
            SwapFinding.WrapsMoreThanTheAmount -> R.string.jupiter_finding_wraps_more
            SwapFinding.UnexpectedWrapping -> R.string.jupiter_finding_unexpected_wrapping
            SwapFinding.UnreadableValueInstruction -> R.string.jupiter_finding_unreadable_value
            SwapFinding.UnrecognizedInstruction -> R.string.jupiter_finding_unrecognized
        }
