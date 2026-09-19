package io.github.brrenat.seekervault.reviews

import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.designsystem.EnvChipEnvironment
import io.github.brrenat.seekervault.designsystem.EnvChipVerbosity
import io.github.brrenat.seekervault.designsystem.FactRowValueStyle
import io.github.brrenat.seekervault.designsystem.NetworkChipNetwork
import io.github.brrenat.seekervault.designsystem.OwnerInputCardKind
import io.github.brrenat.seekervault.designsystem.OwnerInputCardState
import io.github.brrenat.seekervault.designsystem.ReviewSheetAction
import io.github.brrenat.seekervault.designsystem.ReviewSheetDailySpend
import io.github.brrenat.seekervault.designsystem.ReviewSheetFactRow
import io.github.brrenat.seekervault.designsystem.ReviewSheetHeaderChip
import io.github.brrenat.seekervault.designsystem.ReviewSheetInfoBlock
import io.github.brrenat.seekervault.designsystem.ReviewSheetNote
import io.github.brrenat.seekervault.designsystem.ReviewSheetState
import io.github.brrenat.seekervault.designsystem.ReviewSheetSublineStyle
import io.github.brrenat.seekervault.designsystem.ReviewSheetVerdict
import io.github.brrenat.seekervault.designsystem.ReviewSheetYourPart
import io.github.brrenat.seekervault.inbox.instant
import io.github.brrenat.seekervault.inbox.relativeTime
import io.github.brrenat.seekervault.operations.OperationReview
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v2.PresentationCategory
import io.github.brrenat.seekervault.request.v2.Request
import io.github.brrenat.seekervault.request.v2.Value
import io.github.brrenat.seekervault.requests.commonEnvelope
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.time.Instant

/**
 * Runtime facts that are established outside the common request/signal envelope. The app maps those
 * facts here and the design-system boundary receives only [ReviewSheetState].
 */
data class ReviewSheetMappingContent(
    val environment: EnvChipEnvironment,
    val verdict: ReviewSheetVerdict,
    val infoBlocks: List<ReviewSheetInfoBlock>,
    val selectedWallet: SelectedWallet? = null,
    val dailySpend: ReviewSheetDailySpend? = null,
    val additionalFacts: List<ReviewSheetFactRow> = emptyList(),
    val ownerSummary: String? = null,
    val subline: String? = null,
    val primaryEnabled: Boolean = true,
)

/** Thin direct-request adapter into the SEE-108 common envelope. */
fun ActionRequest.toReviewSheetState(
    connection: Connection,
    content: ReviewSheetMappingContent,
    now: Instant,
): ReviewSheetState =
    commonEnvelope()
        .toReviewSheetState(
            sourceName = connection.label,
            environment = content.environment,
            content = content,
            now = now,
        )

/** Thin signal adapter into the same SEE-108 common-envelope mapper. */
fun OperationReview.toReviewSheetState(
    connection: Connection,
    content: ReviewSheetMappingContent,
    now: Instant,
): ReviewSheetState =
    record.proposal
        .commonEnvelope()
        .toReviewSheetState(
            sourceName = connection.label,
            environment = content.environment,
            content = content,
            now = now,
        )

private fun Request.toReviewSheetState(
    sourceName: String,
    environment: EnvChipEnvironment,
    content: ReviewSheetMappingContent,
    now: Instant,
): ReviewSheetState {
    val signal = presentation.category == PresentationCategory.PRESENTATION_CATEGORY_SIGNAL
    val capability = action.capabilityId
    val network = network(content.selectedWallet)
    val ownerKind =
        when (capability) {
            "swap" -> OwnerInputCardKind.Swap
            "prediction" -> OwnerInputCardKind.Prediction
            else -> null
        }
    val hasOwnerInputs = ownerInputsCount > 0 && ownerKind != null
    val warning = content.verdict.warnings.isNotEmpty()
    val asset = asset(capability)
    val wallet = content.selectedWallet?.address ?: parameter("wallet")

    return ReviewSheetState(
        title = title(capability),
        headline = headline(capability),
        subline = content.subline ?: subline(capability, sourceName),
        sublineStyle =
            if (capability == "transfer" || capability == "sign_message") {
                ReviewSheetSublineStyle.Mono
            } else {
                ReviewSheetSublineStyle.Plain
            },
        headerChips = headerChips(signal, sourceName, environment, network?.chip),
        sandboxNotice = if (environment == EnvChipEnvironment.Sandbox) SandboxNotice else null,
        yourPart =
            ownerKind
                ?.takeIf { hasOwnerInputs }
                ?.let {
                    ReviewSheetYourPart(
                        kind = it,
                        state =
                            if (content.ownerSummary == null) OwnerInputCardState.Unchosen
                            else OwnerInputCardState.Chosen,
                        summary = content.ownerSummary,
                    )
                },
        verdict = content.verdict,
        dailySpend = content.dailySpend,
        infoBlocks = content.infoBlocks,
        factRows =
            buildList {
                add(ReviewSheetFactRow("From", sourceName))
                wallet?.let {
                    add(
                        ReviewSheetFactRow(
                            "Wallet",
                            it.shortAddress(),
                            FactRowValueStyle.Mono,
                        )
                    )
                }
                network?.let { add(ReviewSheetFactRow("Network", it.label)) }
                asset?.let { add(ReviewSheetFactRow("Asset", it)) }
                addAll(content.additionalFacts)
            },
        note =
            presentation.description.takeIf(String::isNotBlank)?.let {
                ReviewSheetNote(
                    label =
                        if (signal) "The publisher’s note · not verified"
                        else "The agent’s note · not verified",
                    body = it,
                )
            },
        expiry = relativeTime(lifecycle.expiresAt.instant(), now).replaceFirstChar(Char::lowercase),
        confirmationCheckbox =
            if (warning) "I have read the warning and want to approve anyway" else null,
        primaryAction =
            ReviewSheetAction(
                label = primaryLabel(capability, environment),
                enabled =
                    content.primaryEnabled && (!hasOwnerInputs || content.ownerSummary != null),
            ),
        secondaryAction = ReviewSheetAction(if (signal) "Dismiss" else "Reject"),
        footerCaption =
            if (signal) {
                "Dismissing keeps the decision on this phone. $sourceName is never told either way."
            } else {
                "Rejecting tells $sourceName you said no."
            },
    )
}

private fun Request.headerChips(
    signal: Boolean,
    sourceName: String,
    environment: EnvChipEnvironment,
    network: NetworkChipNetwork?,
): List<ReviewSheetHeaderChip> = buildList {
    if (signal) {
        add(ReviewSheetHeaderChip.Signal)
        add(ReviewSheetHeaderChip.Feed(sourceName))
    }
    add(
        ReviewSheetHeaderChip.Environment(
            value = environment,
            verbosity =
                if (signal && environment == EnvChipEnvironment.Sandbox) EnvChipVerbosity.Short
                else EnvChipVerbosity.Full,
        )
    )
    network?.let { add(ReviewSheetHeaderChip.Network(it)) }
}

private fun Request.title(capability: String): String =
    when (capability) {
        "transfer" -> "Transfer"
        "swap" -> "Swap"
        "prediction" -> "Prediction"
        "sign_message" -> "Signature"
        "ack" -> "Acknowledge"
        else -> presentation.title.ifBlank { "Review" }
    }

private fun Request.headline(capability: String): String =
    when (capability) {
        "transfer" -> "${parameter("amount").orEmpty()} ${asset(capability).orEmpty()}".trim()
        "sign_message" -> "${messageBytes()} bytes"
        "ack" -> parameter("text") ?: presentation.title
        else -> presentation.title
    }

private fun Request.subline(capability: String, sourceName: String): String =
    when (capability) {
        "transfer" -> "to ${parameter("recipient").orEmpty()}".trimEnd()
        "sign_message" -> parameter("text") ?: "Message bytes"
        "ack" -> "$sourceName asks"
        else -> "$sourceName proposes ${presentation.title}."
    }

private fun Request.primaryLabel(
    capability: String,
    environment: EnvChipEnvironment,
): String =
    when {
        environment == EnvChipEnvironment.Sandbox && capability == "swap" -> "Simulate the swap"
        environment == EnvChipEnvironment.Sandbox && capability == "prediction" ->
            "Simulate the stake"
        capability == "transfer" -> "Approve and send"
        capability == "sign_message" -> "Approve and sign"
        capability == "ack" -> "Acknowledge"
        else -> "Approve"
    }

private fun Request.asset(capability: String): String? {
    val prefix = if (capability == "swap") "input_asset" else "asset"
    return when {
        parameter("${prefix}_native_sol") == "true" -> "SOL"
        parameter("${prefix}_mint") != null -> parameter("${prefix}_mint")?.shortAddress()
        else -> null
    }
}

private fun Request.messageBytes(): Int =
    action.parametersList.firstOrNull { it.key == "data" }?.opaque?.size()
        ?: parameter("text")?.encodeToByteArray()?.size
        ?: 0

private fun Request.parameter(key: String): String? =
    action.parametersList.firstOrNull { it.key == key }?.displayValue()

private fun Value.displayValue(): String? =
    when (valueCase) {
        Value.ValueCase.TEXT -> text
        Value.ValueCase.INTEGER -> integer
        Value.ValueCase.FLAG -> flag.toString()
        Value.ValueCase.OPAQUE -> null
        else -> null
    }

private fun Request.network(wallet: SelectedWallet?): ReviewNetwork? {
    wallet?.network?.let {
        return when (it) {
            WalletNetwork.Devnet -> ReviewNetwork("Solana devnet", NetworkChipNetwork.Devnet)
            WalletNetwork.Mainnet -> ReviewNetwork("Solana mainnet", NetworkChipNetwork.Mainnet)
            WalletNetwork.Testnet -> ReviewNetwork("Solana testnet", null)
        }
    }
    return when (parameter("network")) {
        Network.NETWORK_DEVNET.name -> ReviewNetwork("Solana devnet", NetworkChipNetwork.Devnet)
        Network.NETWORK_MAINNET.name -> ReviewNetwork("Solana mainnet", NetworkChipNetwork.Mainnet)
        Network.NETWORK_TESTNET.name -> ReviewNetwork("Solana testnet", null)
        else -> null
    }
}

private fun String.shortAddress(): String =
    if (length <= ShortAddressVisibleCharacters) this
    else take(ShortAddressPrefixCharacters) + "…" + takeLast(ShortAddressSuffixCharacters)

private data class ReviewNetwork(val label: String, val chip: NetworkChipNetwork?)

private const val ShortAddressPrefixCharacters = 8
private const val ShortAddressSuffixCharacters = 7
private const val ShortAddressVisibleCharacters =
    ShortAddressPrefixCharacters + ShortAddressSuffixCharacters

private const val SandboxNotice =
    "Sandbox. Everything above is real — the live market, the exact transaction and this phone’s " +
        "reading of it. The last step is not: no funds will move, nothing is signed and nothing " +
        "is sent."
