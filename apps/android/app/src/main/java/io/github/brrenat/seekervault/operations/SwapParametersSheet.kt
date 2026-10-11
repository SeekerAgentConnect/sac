package io.github.brrenat.seekervault.operations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.designsystem.OwnerParametersNumber
import io.github.brrenat.seekervault.designsystem.OwnerParametersSheet
import io.github.brrenat.seekervault.designsystem.OwnerParametersSheetCallbacks
import io.github.brrenat.seekervault.designsystem.OwnerParametersSheetState
import io.github.brrenat.seekervault.designsystem.OwnerParametersSheetTags
import io.github.brrenat.seekervault.designsystem.OwnerParametersWallet
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.actions.ActionPayload
import io.github.brrenat.seekervault.policy.AmountEntry
import io.github.brrenat.seekervault.policy.readAmount
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.networkText
import io.github.brrenat.seekervault.wallet.walletNetworkOf

object SwapParametersTags {
    const val SHEET = "swap.params"
    const val USE = "swap.params.use"
    const val AMOUNT = "swap.params.amount"
    const val SLIPPAGE = "swap.params.slippage"
    const val WALLET = "swap.params.wallet"
}

/**
 * The owner's part of a swap signal, stacked over its review as the prediction's side and stake
 * are: `[review, params]` (design/navigation.md). The wallet that signs is chosen here too, through
 * the same picker stacked over this sheet, so the amount is never typed for an account nobody
 * picked.
 *
 * What is typed stays here until **Use these**. Then it is checked against the signal's bounds,
 * handed to the review in base units and whole basis points, and the review quotes again from it.
 */
@Composable
fun SwapParametersSheet(
    review: OperationReview,
    wallet: SelectedWallet?,
    onUse: (List<Pair<ParameterKey, ParameterValue>>) -> Unit,
    onChooseWallet: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val amountField = review.form.fields.firstOrNull { it.kind is ParameterKind.Amount }
    val slippageField = review.form.fields.firstOrNull { it.kind is ParameterKind.Count }
    val amountKind = amountField?.kind as? ParameterKind.Amount
    val slippageKind = slippageField?.kind as? ParameterKind.Count
    val unit = (review.payload as? ActionPayload.Swap)?.payload?.inputSymbol.orEmpty()
    var typed by
        rememberSaveable(review.proposalId) {
            mutableStateOf(
                amountKind
                    ?.let { kind ->
                        (review.choice.values[amountField.key] as? ParameterValue.Amount)?.let {
                            formatBaseUnits(it.baseUnits, kind.decimals)
                        }
                    }
                    .orEmpty()
            )
        }
    var slippage by
        rememberSaveable(review.proposalId) {
            mutableStateOf(
                slippageKind
                    ?.let { kind ->
                        ((review.choice.values[slippageField.key] as? ParameterValue.Count)?.value
                                ?: kind.initial)
                            .toString()
                    }
                    .orEmpty()
            )
        }
    val entry = amountKind?.let { readAmount(typed, it.decimals) }
    val amount = (entry as? AmountEntry.Amount)?.baseUnits
    val amountInBounds =
        amount != null &&
            amountKind.let { amount >= it.least && (it.most == null || amount <= it.most) }
    val slippageRead = slippage.toUIntOrNull()
    val slippageValid =
        slippageKind == null ||
            (slippageRead != null &&
                slippageRead >= slippageKind.least &&
                slippageRead <= slippageKind.most)
    val network = review.network?.let(::walletNetworkOf)
    fun said(baseUnits: ULong, decimals: Int) =
        "${formatBaseUnits(baseUnits, decimals)} $unit".trim()
    val amountHelp =
        amountKind?.let { kind ->
            val most = kind.most
            when {
                most != null ->
                    stringResource(
                        R.string.operation_amount_between,
                        said(kind.least, kind.decimals),
                        said(most, kind.decimals),
                    )
                kind.least > 0UL ->
                    stringResource(
                        R.string.swap_params_amount_least,
                        said(kind.least, kind.decimals),
                    )
                else -> null
            }
        } ?: stringResource(R.string.operation_amount_note)
    // Said as soon as it is wrong: a malformed amount, or one outside the signal's bounds.
    val amountError =
        when {
            entry is AmountEntry.Problem -> stringResource(R.string.operation_amount_invalid)
            amount != null && !amountInBounds -> amountHelp
            else -> null
        }
    val slippageHelp = slippageKind?.let {
        stringResource(
            R.string.operation_count_between,
            it.least.toString(),
            it.most.toString(),
        )
    }
    OwnerParametersSheet(
        state =
            OwnerParametersSheetState(
                title = stringResource(R.string.swap_params_title),
                wallet =
                    network?.let {
                        OwnerParametersWallet(
                            label = stringResource(R.string.swap_params_wallet, networkText(it)),
                            value = wallet?.let(::walletName),
                            placeholder = stringResource(R.string.swap_params_wallet_none),
                            actionLabel =
                                stringResource(
                                    if (wallet != null) R.string.swap_params_wallet_change
                                    else R.string.swap_params_wallet_choose
                                ),
                        )
                    },
                amountLabel = amountField?.let { stringResource(it.label) }.orEmpty(),
                amount = typed,
                amountHelp = amountHelp,
                amountError = amountError,
                number =
                    slippageField?.let { field ->
                        OwnerParametersNumber(
                            label = stringResource(field.label),
                            value = slippage,
                            help = slippageHelp.orEmpty(),
                            error = if (slippageValid) null else slippageHelp,
                        )
                    },
                note = stringResource(R.string.operation_your_part_note),
                useLabel = stringResource(R.string.prediction_params_use),
                canUse = wallet != null && amountInBounds && slippageValid,
            ),
        callbacks =
            OwnerParametersSheetCallbacks(
                onChoose = {},
                onAmountChange = { typed = it },
                onNumberChange = { slippage = it },
                onWallet = onChooseWallet,
                onUse = {
                    if (amount != null && amountInBounds && slippageValid) {
                        onUse(
                            buildList {
                                if (amountField != null)
                                    add(amountField.key to ParameterValue.Amount(amount))
                                if (slippageField != null && slippageRead != null)
                                    add(slippageField.key to ParameterValue.Count(slippageRead))
                            }
                        )
                    }
                },
                onClose = onClose,
            ),
        tags =
            OwnerParametersSheetTags(
                use = SwapParametersTags.USE,
                amount = SwapParametersTags.AMOUNT,
                number = SwapParametersTags.SLIPPAGE,
                wallet = SwapParametersTags.WALLET,
            ),
        focusAmount = wallet != null,
        modifier = modifier.testTag(SwapParametersTags.SHEET),
    )
}

/** The wallet's own name for the account, or its address with the middle left out. */
internal fun walletName(wallet: SelectedWallet): String =
    wallet.label?.takeIf { it.isNotBlank() } ?: wallet.address.middle()
