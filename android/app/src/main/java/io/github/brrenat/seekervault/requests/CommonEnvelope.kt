package io.github.brrenat.seekervault.requests

import io.github.brrenat.seekervault.proposals.OwnerInputKind
import io.github.brrenat.seekervault.proposals.Proposal
import io.github.brrenat.seekervault.proposals.ProposalStatus
import io.github.brrenat.seekervault.proposals.ProposalValueKind
import io.github.brrenat.seekervault.request.v1.Action
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Asset
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v2.ActionCapability
import io.github.brrenat.seekervault.request.v2.Audience
import io.github.brrenat.seekervault.request.v2.FeedAudience
import io.github.brrenat.seekervault.request.v2.InputOption
import io.github.brrenat.seekervault.request.v2.OwnerInput
import io.github.brrenat.seekervault.request.v2.OwnerInputKind as WireInputKind
import io.github.brrenat.seekervault.request.v2.Presentation
import io.github.brrenat.seekervault.request.v2.PresentationCategory
import io.github.brrenat.seekervault.request.v2.PrivateAudience
import io.github.brrenat.seekervault.request.v2.Request
import io.github.brrenat.seekervault.request.v2.RequestIdentity
import io.github.brrenat.seekervault.request.v2.RequestLifecycle
import io.github.brrenat.seekervault.request.v2.RequestStatus
import io.github.brrenat.seekervault.request.v2.ResultHandling
import io.github.brrenat.seekervault.request.v2.ResultMode
import io.github.brrenat.seekervault.request.v2.Value
import java.util.Base64

/**
 * The two legacy stores adapt into this generated common contract before shared collection and
 * presentation. The adapter is intentionally lossless for source-authored action data; decisions,
 * wallet execution and outcomes remain in their existing private/local records.
 */
fun ActionRequest.commonEnvelope(): Request {
    val capability = actionCapability(action)
    return Request.newBuilder()
        .setContractVersion(COMMON_REQUEST_CONTRACT)
        .setIdentity(
            RequestIdentity.newBuilder()
                .setSourceId(ref.connectionId)
                .setScope("private/${ref.connectionId}")
                .setRequestId(ref.requestId)
        )
        .setLifecycle(
            RequestLifecycle.newBuilder()
                // The legacy direct contract has immutable request content and no content
                // revision, so its adapter has one stable revision.
                .setRevision(1)
                .setStatus(commonStatus(state))
                .setCreatedAt(createdAt)
                .setUpdatedAt(updatedAt)
                .setExpiresAt(expiresAt)
        )
        .setPresentation(
            Presentation.newBuilder()
                .setTitle(title(action.kindCase))
                .setDescription(agentNote)
                .setCategory(PresentationCategory.PRESENTATION_CATEGORY_REQUEST)
        )
        .setAction(capability)
        .setAudience(
            Audience.newBuilder()
                .setPrivate(PrivateAudience.newBuilder().setRecipientId(ref.connectionId))
        )
        .setResultHandling(
            ResultHandling.newBuilder().setMode(ResultMode.RESULT_MODE_RETURN_TO_ORIGIN)
        )
        .build()
}

fun Proposal.commonEnvelope(): Request =
    Request.newBuilder()
        .setContractVersion(contractVersion)
        .setIdentity(
            RequestIdentity.newBuilder()
                .setSourceId(key.serverId)
                .setScope(key.channel)
                .setRequestId(key.proposalId)
        )
        .setLifecycle(
            RequestLifecycle.newBuilder()
                .setRevision(revision)
                .setStatus(
                    when (status) {
                        ProposalStatus.Open -> RequestStatus.REQUEST_STATUS_OPEN
                        ProposalStatus.Cancelled -> RequestStatus.REQUEST_STATUS_CANCELLED
                    }
                )
                .setCreatedAt(timestamp(createdAt))
                .setUpdatedAt(timestamp(updatedAt))
                .setExpiresAt(timestamp(expiresAt))
        )
        .setPresentation(
            Presentation.newBuilder()
                .setTitle(title.ifEmpty { operation.value })
                .setDescription(note)
                .setCategory(PresentationCategory.PRESENTATION_CATEGORY_SIGNAL)
        )
        .setAction(
            ActionCapability.newBuilder()
                .setCapabilityId(operation.value)
                .setCapabilityVersion(capabilityVersion)
                .setPluginId(plugin.value)
                .addAllParameters(
                    values.map {
                        Value.newBuilder()
                            .setKey(it.key)
                            .apply {
                                when (it.kind) {
                                    ProposalValueKind.Text -> text = it.text
                                    ProposalValueKind.Integer -> integer = it.text
                                    ProposalValueKind.Flag -> flag = it.text.toBooleanStrict()
                                    ProposalValueKind.Opaque ->
                                        opaque =
                                            com.google.protobuf.ByteString.copyFrom(
                                                Base64.getDecoder().decode(it.text)
                                            )
                                }
                            }
                            .build()
                    }
                )
        )
        .addAllOwnerInputs(
            ownerInputs.map { input ->
                OwnerInput.newBuilder()
                    .setKey(input.key)
                    .setLabel(input.label)
                    .setKind(
                        when (input.kind) {
                            OwnerInputKind.Amount -> WireInputKind.OWNER_INPUT_KIND_AMOUNT
                            OwnerInputKind.Count -> WireInputKind.OWNER_INPUT_KIND_COUNT
                            OwnerInputKind.Choice -> WireInputKind.OWNER_INPUT_KIND_CHOICE
                        }
                    )
                    .setRequired(input.required)
                    .setMinimum(input.minimum)
                    .setMaximum(input.maximum)
                    .setHelp(input.help)
                    .addAllOptions(
                        input.options.map {
                            InputOption.newBuilder().setValue(it.value).setLabel(it.label).build()
                        }
                    )
                    .build()
            }
        )
        .setAudience(
            Audience.newBuilder().setFeed(FeedAudience.newBuilder().setChannel(key.channel))
        )
        .setResultHandling(ResultHandling.newBuilder().setMode(ResultMode.RESULT_MODE_DEVICE_LOCAL))
        .build()

private fun actionCapability(action: Action): ActionCapability {
    val values = mutableListOf<Value>()
    fun text(key: String, value: String) {
        values += Value.newBuilder().setKey(key).setText(value).build()
    }
    fun integer(key: String, value: String) {
        values += Value.newBuilder().setKey(key).setInteger(value).build()
    }
    fun flag(key: String, value: Boolean) {
        values += Value.newBuilder().setKey(key).setFlag(value).build()
    }
    when (action.kindCase) {
        Action.KindCase.ACK -> text("text", action.ack.text)
        Action.KindCase.SIGN_MESSAGE -> {
            text("wallet", action.signMessage.wallet)
            when (action.signMessage.contentCase) {
                io.github.brrenat.seekervault.request.v1.SignMessageAction.ContentCase.TEXT ->
                    text("text", action.signMessage.text)
                io.github.brrenat.seekervault.request.v1.SignMessageAction.ContentCase.DATA ->
                    values +=
                        Value.newBuilder().setKey("data").setOpaque(action.signMessage.data).build()
                else -> Unit
            }
        }
        Action.KindCase.TRANSFER -> {
            text("wallet", action.transfer.wallet)
            text("network", action.transfer.network.name)
            text("recipient", action.transfer.recipient)
            asset(values, "asset", action.transfer.asset)
            integer("amount", action.transfer.amount)
        }
        Action.KindCase.SWAP -> {
            text("wallet", action.swap.wallet)
            text("network", action.swap.network.name)
            asset(values, "input_asset", action.swap.inputAsset)
            asset(values, "output_asset", action.swap.outputAsset)
            integer("input_amount", action.swap.inputAmount)
            integer("slippage_bps", action.swap.slippageBps.toString())
        }
        else -> flag("missing", true)
    }
    val id = capabilityId(action.kindCase)
    return ActionCapability.newBuilder()
        .setCapabilityId(id)
        .setCapabilityVersion(1)
        .setPluginId("core.$id")
        .addAllParameters(values)
        .build()
}

private fun asset(values: MutableList<Value>, prefix: String, asset: Asset) {
    when (asset.kindCase) {
        Asset.KindCase.NATIVE_SOL ->
            values += Value.newBuilder().setKey("${prefix}_native_sol").setFlag(true).build()
        Asset.KindCase.TOKEN_MINT ->
            values += Value.newBuilder().setKey("${prefix}_mint").setText(asset.tokenMint).build()
        else -> Unit
    }
}

private fun commonStatus(state: RequestState): RequestStatus =
    when (state) {
        RequestState.REQUEST_STATE_PENDING -> RequestStatus.REQUEST_STATUS_OPEN
        RequestState.REQUEST_STATE_PROCESSING -> RequestStatus.REQUEST_STATUS_PROCESSING
        RequestState.REQUEST_STATE_SUBMITTED -> RequestStatus.REQUEST_STATUS_SUBMITTED
        RequestState.REQUEST_STATE_CONFIRMED -> RequestStatus.REQUEST_STATUS_CONFIRMED
        RequestState.REQUEST_STATE_COMPLETED -> RequestStatus.REQUEST_STATUS_COMPLETED
        RequestState.REQUEST_STATE_REJECTED -> RequestStatus.REQUEST_STATUS_REJECTED
        RequestState.REQUEST_STATE_CANCELLED -> RequestStatus.REQUEST_STATUS_CANCELLED
        RequestState.REQUEST_STATE_EXPIRED -> RequestStatus.REQUEST_STATUS_EXPIRED
        RequestState.REQUEST_STATE_FAILED -> RequestStatus.REQUEST_STATUS_FAILED
        RequestState.REQUEST_STATE_UNKNOWN -> RequestStatus.REQUEST_STATUS_UNKNOWN
        else -> RequestStatus.REQUEST_STATUS_UNSPECIFIED
    }

private fun capabilityId(kind: Action.KindCase): String =
    when (kind) {
        Action.KindCase.ACK -> "ack"
        Action.KindCase.SIGN_MESSAGE -> "sign_message"
        Action.KindCase.TRANSFER -> "transfer"
        Action.KindCase.SWAP -> "swap"
        else -> "unknown"
    }

private fun title(kind: Action.KindCase): String =
    when (kind) {
        Action.KindCase.ACK -> "Acknowledgement"
        Action.KindCase.SIGN_MESSAGE -> "Sign message"
        Action.KindCase.TRANSFER -> "Transfer"
        Action.KindCase.SWAP -> "Swap"
        else -> "Request"
    }

private fun timestamp(value: java.time.Instant): com.google.protobuf.Timestamp =
    com.google.protobuf.Timestamp.newBuilder()
        .setSeconds(value.epochSecond)
        .setNanos(value.nano)
        .build()

const val COMMON_REQUEST_CONTRACT = 1
