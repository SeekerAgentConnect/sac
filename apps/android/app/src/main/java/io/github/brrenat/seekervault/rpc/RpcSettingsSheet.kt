package io.github.brrenat.seekervault.rpc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.designsystem.NetworkChipNetwork
import io.github.brrenat.seekervault.designsystem.RpcEndpointCardModel
import io.github.brrenat.seekervault.designsystem.RpcEndpointStatus
import io.github.brrenat.seekervault.designsystem.SolanaRpcSheet
import io.github.brrenat.seekervault.designsystem.SolanaRpcSheetCard
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.solana.SolanaProblem

/** Test tags for the Solana RPC sheet (SEE-184). */
object RpcTags {
    const val SHEET = "rpcSheet"
    const val CLOSE = "rpcClose"

    fun card(network: Network) = "rpcCard:${network.name}"

    fun field(network: Network) = "rpcField:${network.name}"

    fun status(network: Network) = "rpcStatus:${network.name}"

    fun save(network: Network) = "rpcSave:${network.name}"

    fun reset(network: Network) = "rpcReset:${network.name}"
}

/** The Solana RPC sheet, over the Wallet tab. Each network's endpoint is checked when it opens. */
@Composable
fun RpcSettingsRoute(
    viewModel: RpcSettingsViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    val byId = state.networks.associateBy { it.network.name }
    fun network(id: String) = checkNotNull(byId[id]).network
    SolanaRpcSheet(
        title = stringResource(R.string.rpc_title),
        explanation = stringResource(R.string.rpc_explanation),
        cards =
            state.networks.map {
                SolanaRpcSheetCard(it.network.name, cardModel(it), statusOf(it))
            },
        caption = stringResource(R.string.rpc_caption),
        closeLabel = stringResource(R.string.rpc_close),
        onValueChange = { id, text -> viewModel.edit(network(id), text) },
        onSave = { id -> viewModel.save(network(id)) },
        onReset = { id -> viewModel.reset(network(id)) },
        onClose = onClose,
        modifier = modifier.testTag(RpcTags.SHEET),
        cardModifier = { Modifier.testTag(RpcTags.card(network(it))) },
        fieldModifier = { Modifier.testTag(RpcTags.field(network(it))) },
        statusModifier = { Modifier.testTag(RpcTags.status(network(it))) },
        saveModifier = { Modifier.testTag(RpcTags.save(network(it))) },
        resetModifier = { Modifier.testTag(RpcTags.reset(network(it))) },
        closeTag = RpcTags.CLOSE,
    )
}

@Composable
private fun cardModel(state: RpcNetworkState): RpcEndpointCardModel {
    val name = networkName(state.network)
    val endpoint = state.endpoint
    return RpcEndpointCardModel(
        network = chipOf(state.network),
        inUse =
            when (endpoint?.source) {
                null -> stringResource(R.string.rpc_in_use_none)
                RpcSource.Owner -> stringResource(R.string.rpc_in_use_owner, endpoint.host)
                RpcSource.Build -> stringResource(R.string.rpc_in_use_build, endpoint.host)
                RpcSource.General -> stringResource(R.string.rpc_in_use_general, endpoint.host)
            },
        statusText =
            when {
                state.busy -> stringResource(R.string.rpc_status_checking)
                else -> state.check?.let { checkText(it, state.network) }
            },
        fieldLabel = stringResource(R.string.rpc_field_label, name),
        fieldValue = state.draft,
        fieldPlaceholder = stringResource(R.string.rpc_field_placeholder),
        fieldError =
            state.refused?.let {
                stringResource(R.string.rpc_not_saved, checkText(it, state.network))
            },
        saveLabel = stringResource(R.string.rpc_save),
        resetLabel = stringResource(R.string.rpc_reset),
        canSave = !state.busy && state.draft.isNotBlank(),
        canReset = !state.busy && state.ownSetting,
    )
}

private fun statusOf(state: RpcNetworkState): RpcEndpointStatus =
    when {
        state.busy -> RpcEndpointStatus.Checking
        else ->
            when (state.check) {
                null -> RpcEndpointStatus.Unknown
                is RpcCheck.Serves -> RpcEndpointStatus.Serves
                RpcCheck.NotSet,
                is RpcCheck.Invalid,
                is RpcCheck.OtherNetwork,
                is RpcCheck.Failed -> RpcEndpointStatus.Problem
            }
    }

/** What a check found, in a sentence that names the networks involved. */
@Composable
private fun checkText(check: RpcCheck, network: Network): String =
    when (check) {
        RpcCheck.NotSet -> stringResource(R.string.rpc_status_none, networkName(network))
        is RpcCheck.Serves ->
            if (check.localValidator) stringResource(R.string.rpc_status_local_validator)
            else stringResource(R.string.rpc_status_serves, networkName(network))
        is RpcCheck.OtherNetwork ->
            check.served?.let {
                stringResource(
                    R.string.rpc_status_other_network,
                    networkName(it),
                    networkName(network),
                )
            } ?: stringResource(R.string.rpc_status_unknown_chain, networkName(network))
        is RpcCheck.Failed ->
            stringResource(
                when (check.problem) {
                    SolanaProblem.NoEndpoint,
                    SolanaProblem.Unreachable -> R.string.rpc_status_unreachable
                    SolanaProblem.RateLimited -> R.string.rpc_status_rate_limited
                    SolanaProblem.Refused -> R.string.rpc_status_refused
                    SolanaProblem.WrongNetwork,
                    SolanaProblem.Unusable -> R.string.rpc_status_unusable
                }
            )
        is RpcCheck.Invalid ->
            stringResource(
                when (check.problem) {
                    RpcUrlProblem.Empty -> R.string.rpc_url_empty
                    RpcUrlProblem.NotAUrl -> R.string.rpc_url_not_a_url
                    RpcUrlProblem.NotHttps -> R.string.rpc_url_not_https
                    RpcUrlProblem.Credentials -> R.string.rpc_url_credentials
                }
            )
    }

@Composable
private fun networkName(network: Network): String =
    stringResource(
        when (network) {
            Network.NETWORK_DEVNET -> R.string.rpc_network_devnet
            Network.NETWORK_TESTNET -> R.string.rpc_network_testnet
            else -> R.string.rpc_network_mainnet
        }
    )

private fun chipOf(network: Network): NetworkChipNetwork =
    when (network) {
        Network.NETWORK_DEVNET -> NetworkChipNetwork.Devnet
        Network.NETWORK_TESTNET -> NetworkChipNetwork.Testnet
        else -> NetworkChipNetwork.Mainnet
    }
