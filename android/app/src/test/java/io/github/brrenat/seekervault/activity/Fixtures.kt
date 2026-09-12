package io.github.brrenat.seekervault.activity

import com.google.protobuf.ByteString
import com.google.protobuf.timestamp
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.ApprovedTransaction
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.connections.SigningOutcome
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Asset
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.ackAction
import io.github.brrenat.seekervault.request.v1.action
import io.github.brrenat.seekervault.request.v1.actionRequest
import io.github.brrenat.seekervault.request.v1.asset
import io.github.brrenat.seekervault.request.v1.confirmation
import io.github.brrenat.seekervault.request.v1.outcome
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.request.v1.signMessageAction
import io.github.brrenat.seekervault.request.v1.transferAction
import java.time.Instant

/** Fixtures the Activity tests share: one connection, one wallet, and requests of each kind. */
const val CONNECTION = "5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f"
const val REQUEST = "f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19"
const val OTHER_REQUEST = "0b8e2b1c-3f4d-4e5a-9b6c-7d8e9f0a1b2c"
const val WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
const val RECIPIENT = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
const val MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

val ANSWERED_AT: Instant = Instant.parse("2026-09-11T12:00:00.250Z")

fun connection(label: String = "Hermes") =
    Connection(
        id = CONNECTION,
        label = label,
        serverUrl = "https://sidecar.example:8443",
        serverId = "server-1",
        deviceName = "Test phone",
        pairedAt = ANSWERED_AT,
    )

fun transferRequest(
    requestId: String = REQUEST,
    network: Network = Network.NETWORK_DEVNET,
    amount: String = "1500000",
    mint: String? = null,
    state: RequestState = RequestState.REQUEST_STATE_SUBMITTED,
    signature: ByteString? = null,
    endpoint: String? = null,
    detail: String = "",
): ActionRequest = actionRequest {
    ref = requestRef {
        connectionId = CONNECTION
        this.requestId = requestId
    }
    action = action {
        transfer = transferAction {
            wallet = WALLET
            this.network = network
            recipient = RECIPIENT
            asset = asset {
                if (mint == null) nativeSol = Asset.NativeSol.getDefaultInstance()
                else tokenMint = mint
            }
            this.amount = amount
        }
    }
    this.state = state
    createdAt = timestamp { seconds = ANSWERED_AT.epochSecond }
    expiresAt = timestamp { seconds = ANSWERED_AT.plusSeconds(3600).epochSecond }
    if (signature != null || endpoint != null || detail.isNotEmpty()) {
        outcome = outcome {
            if (signature != null) this.signature = signature
            if (detail.isNotEmpty()) this.detail = detail
            if (endpoint != null) {
                confirmation = confirmation { this.endpoint = endpoint }
            }
        }
    }
}

fun messageRequest(requestId: String = OTHER_REQUEST): ActionRequest = actionRequest {
    ref = requestRef {
        connectionId = CONNECTION
        this.requestId = requestId
    }
    action = action {
        signMessage = signMessageAction {
            wallet = WALLET
            text = "Sign in to Example"
        }
    }
    state = RequestState.REQUEST_STATE_COMPLETED
    createdAt = timestamp { seconds = ANSWERED_AT.epochSecond }
    expiresAt = timestamp { seconds = ANSWERED_AT.plusSeconds(3600).epochSecond }
}

fun ackRequest(requestId: String = OTHER_REQUEST): ActionRequest = actionRequest {
    ref = requestRef {
        connectionId = CONNECTION
        this.requestId = requestId
    }
    action = action { ack = ackAction { text = "Deploy finished" } }
    state = RequestState.REQUEST_STATE_COMPLETED
    createdAt = timestamp { seconds = ANSWERED_AT.epochSecond }
    expiresAt = timestamp { seconds = ANSWERED_AT.plusSeconds(3600).epochSecond }
}

fun signatureBytes(fill: Int = 7): ByteString =
    ByteString.copyFrom(ByteArray(64) { (it + fill).toByte() })

fun result(
    request: ActionRequest,
    answer: Answer = Answer.Approve,
    delivery: Delivery = Delivery.Accepted,
    signing: SigningOutcome? = null,
    approved: Boolean = true,
    approvedTransaction: ApprovedTransaction? = null,
): LocalResult =
    LocalResult(
        connectionId = request.ref.connectionId,
        requestId = request.ref.requestId,
        answer = answer,
        answeredAt = ANSWERED_AT,
        request = request,
        delivery = delivery,
        approved = approved,
        signing = signing,
        approvedTransaction = approvedTransaction,
    )

fun record(
    requestId: String = REQUEST,
    kind: ActivityKind = ActivityKind.Transfer,
    outcome: ActivityOutcome = ActivityOutcome.Confirmed,
    network: Network = Network.NETWORK_DEVNET,
    mint: String? = null,
    signature: String? = "5Yb4Dn9mFakeSignatureForTestsOnly1111111111111111111111111111",
    checkedWith: String? = "api.devnet.solana.com",
    detail: String? = null,
    answeredAt: Instant = ANSWERED_AT,
    policy: ReviewedPolicy? = null,
): ActivityRecord =
    ActivityRecord(
        connectionId = CONNECTION,
        requestId = requestId,
        source = "Hermes",
        serverHost = "sidecar.example:8443",
        kind = kind,
        answeredAt = answeredAt,
        recordedAt = answeredAt.plusSeconds(5),
        outcome = outcome,
        transfer =
            if (kind != ActivityKind.Transfer) null
            else
                ReviewedTransfer(
                    wallet = WALLET,
                    network = network,
                    recipient = RECIPIENT,
                    amount = "1500000",
                    mint = mint,
                    preparedVersion = 2,
                ),
        policy = policy,
        signature = signature,
        detail = detail,
        checkedWith = checkedWith,
    )

/** The assessment a review showed, as one is kept with a record (SAW-028). */
fun reviewedPolicy(
    assessment: String = "under_restrictions",
    reasons: List<String> = listOf("over_daily_limit"),
    notChecked: List<String> = listOf("program"),
    approvedAnyway: Boolean = true,
): ReviewedPolicy =
    ReviewedPolicy(
        assessment = assessment,
        reasons = reasons,
        notChecked = notChecked,
        assessedAt = ANSWERED_AT.minusSeconds(30),
        approvedAnyway = approvedAnyway,
    )
