package io.github.brrenat.seekervault.requests

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.google.protobuf.Timestamp
import com.google.protobuf.timestamp
import io.github.brrenat.seekervault.request.v1.Action
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Approval
import io.github.brrenat.seekervault.request.v1.Asset
import io.github.brrenat.seekervault.request.v1.ConfirmationLevel
import io.github.brrenat.seekervault.request.v1.ListPendingResponse
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.Outcome
import io.github.brrenat.seekervault.request.v1.PreparedTransaction
import io.github.brrenat.seekervault.request.v1.PublishWalletRequest
import io.github.brrenat.seekervault.request.v1.Rejection
import io.github.brrenat.seekervault.request.v1.RequestError
import io.github.brrenat.seekervault.request.v1.RequestErrorDetail
import io.github.brrenat.seekervault.request.v1.RequestRef
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.StakingOperation
import io.github.brrenat.seekervault.request.v1.SubmitResultRequest
import io.github.brrenat.seekervault.request.v1.TransactionSubmission
import io.github.brrenat.seekervault.request.v1.WalletBinding
import io.github.brrenat.seekervault.request.v1.ackAction
import io.github.brrenat.seekervault.request.v1.action
import io.github.brrenat.seekervault.request.v1.actionRequest
import io.github.brrenat.seekervault.request.v1.approval
import io.github.brrenat.seekervault.request.v1.asset
import io.github.brrenat.seekervault.request.v1.confirmation
import io.github.brrenat.seekervault.request.v1.listPendingResponse
import io.github.brrenat.seekervault.request.v1.outcome
import io.github.brrenat.seekervault.request.v1.preparedTransaction
import io.github.brrenat.seekervault.request.v1.publishWalletRequest
import io.github.brrenat.seekervault.request.v1.requestErrorDetail
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.request.v1.signMessageAction
import io.github.brrenat.seekervault.request.v1.stakingAction
import io.github.brrenat.seekervault.request.v1.submitResultRequest
import io.github.brrenat.seekervault.request.v1.swapAction
import io.github.brrenat.seekervault.request.v1.transferAction
import io.github.brrenat.seekervault.request.v1.walletBinding
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The Kotlin half of the durable request fixtures in
 * packages/protocol/proto/fixtures/seekervault/request/v1. `buf convert` writes the bytes, and the
 * sidecar's requests/fixtures.test.ts checks them too. Each test builds the message in Kotlin and
 * requires the same bytes in both directions.
 */
class RequestProtocolFixturesTest {
    @Test
    fun ackPending() = check("ActionRequest/ack_pending", ActionRequest::parseFrom, ACK_PENDING)

    @Test
    fun sameIdOtherConnection() {
        val other = ACK_PENDING.toBuilder().setRef(refOf(ACK_ID, CONNECTION_B)).build()
        check("ActionRequest/same_id_other_connection", ActionRequest::parseFrom, other)
        // The same request ID under another connection is another request. The phone keys its
        // records by both IDs, so the two never collide.
        assertEquals(ACK_PENDING.ref.requestId, other.ref.requestId)
        assertNotEquals(ACK_PENDING, other)
        val keys = listOf(ACK_PENDING, other).map { it.ref.connectionId to it.ref.requestId }
        assertEquals(2, keys.toSet().size)
    }

    @Test
    fun transferMaxAmount() {
        val request =
            request(
                TRANSFER_MAX_ID,
                transfer(Network.NETWORK_DEVNET, SOL, "18446744073709551615"),
                RequestState.REQUEST_STATE_PENDING,
                created = "2026-09-11T12:00:00Z",
                expires = "2026-09-11T12:10:00Z",
            )
        check("ActionRequest/transfer_max_amount", ActionRequest::parseFrom, request)
        // Amounts are strings on the wire: the u64 maximum doesn't fit in a Long.
        assertEquals(ULong.MAX_VALUE, request.action.transfer.amount.toULong())
    }

    @Test
    fun transferConfirmed() =
        check(
            "ActionRequest/transfer_confirmed",
            ActionRequest::parseFrom,
            request(
                TRANSFER_ID,
                transfer(Network.NETWORK_MAINNET, USDC_ASSET, "1500000"),
                RequestState.REQUEST_STATE_CONFIRMED,
                created = "2026-09-11T12:00:00Z",
                expires = "2026-09-11T13:00:00Z",
                updated = "2026-09-11T12:03:07.250Z",
                note = "Invoice 2026-042",
                outcome =
                    outcome {
                        approval = APPROVAL_V2
                        signature = SIGNATURE
                        // What the sidecar read from the chain, and whose word it rests on
                        // (SAW-022). A confirmed transfer never carries less than this.
                        confirmation = confirmation {
                            level = ConfirmationLevel.CONFIRMATION_LEVEL_FINALIZED
                            slot = 298_471_553L
                            checkedAt = at("2026-09-11T12:03:07.250Z")
                            checks = 3
                            endpoint = "api.mainnet-beta.solana.com"
                            matchesApproval = true
                            detail =
                                "The approved transaction succeeded on chain in slot " +
                                    "298471553, as api.mainnet-beta.solana.com reports it."
                        }
                    },
            ),
        )

    @Test
    fun signMessageText() {
        val messageApproval = approval { contentHash = sha256(MESSAGE_TEXT.toByteArray()) }
        check(
            "ActionRequest/sign_message_text",
            ActionRequest::parseFrom,
            request(
                SIGN_TEXT_ID,
                action {
                    signMessage = signMessageAction {
                        wallet = WALLET
                        text = MESSAGE_TEXT
                    }
                },
                RequestState.REQUEST_STATE_COMPLETED,
                created = "2026-09-11T12:00:00Z",
                expires = "2026-09-12T12:00:00Z",
                updated = "2026-09-11T12:01:00Z",
                outcome =
                    outcome {
                        approval = messageApproval
                        signature = SIGNATURE
                    },
            ),
        )
        // The text is kept exactly: normalizing it would change the bytes the wallet signs.
        assertNotEquals(MESSAGE_TEXT, Normalizer.normalize(MESSAGE_TEXT, Normalizer.Form.NFC))
        assertEquals(43, MESSAGE_TEXT.toByteArray().size)
    }

    @Test
    fun signMessageData() =
        check(
            "ActionRequest/sign_message_data",
            ActionRequest::parseFrom,
            request(
                SIGN_DATA_ID,
                action {
                    signMessage = signMessageAction {
                        wallet = WALLET
                        data = ByteString.copyFrom(byteArrayOf(0x00, -1, 0x00, 0x7F, -128, 0x0A))
                    }
                },
                RequestState.REQUEST_STATE_PENDING,
                created = "2026-09-11T12:00:00Z",
                expires = "2026-09-12T12:00:00Z",
            ),
        )

    @Test
    fun swapPending() = check("ActionRequest/swap_pending", ActionRequest::parseFrom, SWAP_PENDING)

    @Test
    fun stakingUnstakePending() {
        check(
            "ActionRequest/staking_unstake_pending",
            ActionRequest::parseFrom,
            request(
                STAKING_UNSTAKE_ID,
                action {
                    staking = stakingAction {
                        wallet = WALLET
                        network = Network.NETWORK_MAINNET
                        operation = StakingOperation.STAKING_OPERATION_UNSTAKE
                        // A whole-position unstake is asked for as "more than you have", so the
                        // fixture pins that a u64 max survives the string field it travels in.
                        amount = ULong.MAX_VALUE.toString()
                    }
                },
                RequestState.REQUEST_STATE_PENDING,
                created = "2026-09-24T09:00:00.002Z",
                expires = "2026-09-24T09:30:00.002Z",
                note = "Close the whole SKR position before the vesting review",
                connectionId = STAKING_CONNECTION,
            ),
        )
        val parsed = ActionRequest.parseFrom(bytes("ActionRequest/staking_unstake_pending"))
        assertEquals(Action.KindCase.STAKING, parsed.action.kindCase)
        assertEquals(ULong.MAX_VALUE, parsed.action.staking.amount.toULong())
    }

    @Test
    fun stakingWithdrawConfirmed() {
        check(
            "ActionRequest/staking_withdraw_confirmed",
            ActionRequest::parseFrom,
            request(
                STAKING_WITHDRAW_ID,
                action {
                    staking = stakingAction {
                        wallet = WALLET
                        network = Network.NETWORK_MAINNET
                        operation = StakingOperation.STAKING_OPERATION_WITHDRAW
                    }
                },
                RequestState.REQUEST_STATE_CONFIRMED,
                created = "2026-09-24T09:00:00.003Z",
                expires = "2026-09-24T09:30:00.003Z",
                updated = "2026-09-24T09:02:11.003Z",
                note = "The cooldown finished, so take the SKR back",
                connectionId = STAKING_CONNECTION,
                outcome =
                    outcome {
                        approval = approval {
                            preparedVersion = 1
                            contentHash = STAKING_CONTENT_HASH
                        }
                        signature = STAKING_SIGNATURE
                        detail = "Withdrawn after the cooldown."
                        confirmation = confirmation {
                            level = ConfirmationLevel.CONFIRMATION_LEVEL_FINALIZED
                            slot = 318_000_001L
                            checkedAt = at("2026-09-24T09:02:11.003Z")
                            checks = 2
                            endpoint = "api.mainnet-beta.solana.com"
                            matchesApproval = true
                            detail = "Finalized on chain."
                        }
                    },
            ),
        )
        // Withdrawing takes no amount at all: the program pays out what it recorded, and an empty
        // field is how the wire says nobody chose a number (SEE-146).
        val parsed = ActionRequest.parseFrom(bytes("ActionRequest/staking_withdraw_confirmed"))
        assertEquals("", parsed.action.staking.amount)
    }

    @Test
    fun emptyRequest() {
        check("ActionRequest/empty", ActionRequest::parseFrom, ActionRequest.getDefaultInstance())
        // Absent fields stay absent: a missing action is not an empty one.
        val parsed = ActionRequest.parseFrom(bytes("ActionRequest/empty"))
        assertFalse(parsed.hasRef())
        assertFalse(parsed.hasAction())
        assertEquals(Action.KindCase.KIND_NOT_SET, parsed.action.kindCase)
        assertEquals(RequestState.REQUEST_STATE_UNSPECIFIED, parsed.state)
    }

    @Test
    fun preparedTransactionV2() =
        check("PreparedTransaction/v2", PreparedTransaction::parseFrom, PREPARED_V2)

    @Test
    fun preparedTransactionMaxValues() {
        check(
            "PreparedTransaction/max_values",
            PreparedTransaction::parseFrom,
            preparedTransaction {
                ref = refOf(TRANSFER_ID)
                version = UInt.MAX_VALUE.toInt()
                lastValidBlockHeight = ULong.MAX_VALUE.toLong()
                feeLamports = ULong.MAX_VALUE.toLong()
                rentLamports = ULong.MAX_VALUE.toLong()
            },
        )
        // Kotlin reads uint32 and uint64 as a signed Int and Long: convert before comparing them.
        val parsed = PreparedTransaction.parseFrom(bytes("PreparedTransaction/max_values"))
        assertEquals(-1L, parsed.lastValidBlockHeight)
        assertEquals(ULong.MAX_VALUE, parsed.lastValidBlockHeight.toULong())
        assertEquals(ULong.MAX_VALUE, parsed.feeLamports.toULong())
        assertEquals(ULong.MAX_VALUE, parsed.rentLamports.toULong())
        assertEquals(UInt.MAX_VALUE, parsed.version.toUInt())
    }

    @Test
    fun submitApproval() =
        check(
            "SubmitResultRequest/approval",
            SubmitResultRequest::parseFrom,
            submitResultRequest {
                ref = refOf(TRANSFER_ID)
                approval = APPROVAL_V2
            },
        )

    @Test
    fun submitRejection() {
        check(
            "SubmitResultRequest/rejection",
            SubmitResultRequest::parseFrom,
            submitResultRequest {
                ref = refOf(ACK_ID)
                rejection = Rejection.getDefaultInstance()
            },
        )
        // An empty result is still a result.
        assertEquals(
            SubmitResultRequest.ResultCase.REJECTION,
            SubmitResultRequest.parseFrom(bytes("SubmitResultRequest/rejection")).resultCase,
        )
        assertEquals(
            SubmitResultRequest.ResultCase.RESULT_NOT_SET,
            SubmitResultRequest.parseFrom(ByteArray(0)).resultCase,
        )
    }

    @Test
    fun submitTransactionSubmission() =
        check(
            "SubmitResultRequest/transaction_submission",
            SubmitResultRequest::parseFrom,
            submitResultRequest {
                ref = refOf(TRANSFER_ID)
                transactionSubmission =
                    TransactionSubmission.newBuilder().setSignature(SIGNATURE).build()
            },
        )

    @Test
    fun listPendingPage() =
        check(
            "ListPendingResponse/page",
            ListPendingResponse::parseFrom,
            listPendingResponse {
                requests += ACK_PENDING
                requests += SWAP_PENDING
                nextPageToken = "cGFnZS0y"
            },
        )

    @Test
    fun errorDetailInvalidState() =
        check(
            "RequestErrorDetail/invalid_state",
            RequestErrorDetail::parseFrom,
            requestErrorDetail {
                error = RequestError.REQUEST_ERROR_INVALID_STATE
                request =
                    ACK_PENDING.toBuilder()
                        .setState(RequestState.REQUEST_STATE_CANCELLED)
                        .setUpdatedAt(at("2026-09-11T12:05:00Z"))
                        .setOutcome(
                            Outcome.newBuilder()
                                .setDetail("The agent cancelled the request.")
                                .build()
                        )
                        .build()
            },
        )

    @Test
    fun walletBindingMainnet() =
        check(
            "WalletBinding/mainnet",
            WalletBinding::parseFrom,
            walletBinding {
                wallet = WALLET
                network = Network.NETWORK_MAINNET
                boundAt = at("2026-09-12T09:30:00Z")
            },
        )

    @Test
    fun publishWalletDevnet() {
        val request = publishWalletRequest {
            connectionId = CONNECTION_A
            binding = walletBinding {
                wallet = RECIPIENT
                network = Network.NETWORK_DEVNET
            }
        }
        check("PublishWalletRequest/devnet", PublishWalletRequest::parseFrom, request)
        // The phone doesn't set bound_at: the sidecar stamps it with its own clock.
        assertFalse(request.binding.hasBoundAt())
    }

    @Test
    fun publishWalletCleared() {
        val cleared = publishWalletRequest { connectionId = CONNECTION_A }
        check("PublishWalletRequest/cleared", PublishWalletRequest::parseFrom, cleared)
        // An absent binding is how the phone says no wallet is connected; it isn't an empty one.
        assertFalse(
            PublishWalletRequest.parseFrom(bytes("PublishWalletRequest/cleared")).hasBinding()
        )
    }

    @Test
    fun coversEveryFixture() {
        val dir = File(checkNotNull(javaClass.getResource("/$PACKAGE")) { "no fixtures" }.toURI())
        val names =
            dir.walk()
                .filter { it.extension == "json" }
                .map { it.relativeTo(dir).path.removeSuffix(".json") }
                .sorted()
                .toList()
        assertEquals(FIXTURES.sorted(), names)
    }

    private fun <T : MessageLite> check(name: String, parse: (ByteArray) -> T, expected: T) {
        val bytes = bytes(name)
        assertEquals(expected, parse(bytes))
        assertArrayEquals(bytes, expected.toByteArray())
    }

    private fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/$PACKAGE/$name.binpb")) {
                "Missing fixture $name.binpb; run pnpm generate"
            }
            .use { it.readBytes() }

    private companion object {
        const val PACKAGE = "seekervault/request/v1"
        const val CONNECTION_A = "5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f"
        const val CONNECTION_B = "a7e9c1b3-4d5f-4a6b-8c7d-9e0f1a2b3c4d"
        const val ACK_ID = "3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c"
        const val TRANSFER_MAX_ID = "8e1d2c3b-4a5f-4e6d-9c8b-7a6f5e4d3c2b"
        const val TRANSFER_ID = "f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19"
        const val SIGN_TEXT_ID = "c4b3a291-8f7e-4d6c-a5b4-c3d2e1f0a9b8"
        const val SIGN_DATA_ID = "d5c4b3a2-9180-4f7e-b6d5-c4b3a2918070"
        const val SWAP_ID = "e6d5c4b3-a291-4807-8f6e-d5c4b3a29180"
        const val STAKING_CONNECTION = "7c1f2e3d-4a5b-4c6d-8e9f-0a1b2c3d4e5f"
        const val STAKING_UNSTAKE_ID = "b3a29180-d5c4-4b3a-9180-e6d5c4b3a291"
        const val STAKING_WITHDRAW_ID = "c4b3a291-80d5-4c4b-8a91-80e6d5c4b3a2"
        const val WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
        const val RECIPIENT = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
        const val USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

        val FIXTURES =
            listOf(
                "ActionRequest/ack_pending",
                "ActionRequest/same_id_other_connection",
                "ActionRequest/transfer_max_amount",
                "ActionRequest/transfer_confirmed",
                "ActionRequest/sign_message_text",
                "ActionRequest/sign_message_data",
                "ActionRequest/swap_pending",
                "ActionRequest/staking_unstake_pending",
                "ActionRequest/staking_withdraw_confirmed",
                "ActionRequest/empty",
                "PreparedTransaction/v2",
                "PreparedTransaction/max_values",
                "SubmitResultRequest/approval",
                "SubmitResultRequest/rejection",
                "SubmitResultRequest/transaction_submission",
                "ListPendingResponse/page",
                "RequestErrorDetail/invalid_state",
                "WalletBinding/mainnet",
                "PublishWalletRequest/devnet",
                "PublishWalletRequest/cleared",
            )

        /** A staking approval's hash and signature, as the cross-runtime fixture writes them. */
        val STAKING_CONTENT_HASH: ByteString =
            base64("F/////////////////////////////////////////8=")
        val STAKING_SIGNATURE: ByteString =
            base64(
                "Ki8qLyovKi8qLyovKi8qLyovKi8qLyovKi8qLyovKi8qLyovKi8qLyovKi8qLyovKi8qLyovKi8qLw=="
            )

        val SIGNATURE: ByteString =
            base64(
                "yZ1sTYjILaMRLXxssKRlwCwu8+u7k+5TLYEsifXX09LsGTcQuwLUAYO/AZi0vgqil4r4TfByoxEeJqwG0+4Ukw=="
            )
        val TRANSACTION: ByteString =
            base64(
                "AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                    "AACAkiPGFhC6UHqONG9lDlud52dvDkGPV1+GpHkvIbgyn6U="
            )

        /** A decomposed e-acute, a precomposed one, two spaces, and a ZWJ emoji sequence. */
        val MESSAGE_TEXT =
            "Sign in to Example\r\ne" +
                cp(0x301) +
                " caf" +
                cp(0xE9) +
                "  " +
                cp(0x1F469, 0x200D, 0x1F4BB) +
                "\n"
        val ACK_TEXT =
            "Deploy finished. " +
                cp(0x41F, 0x440, 0x438, 0x432, 0x435, 0x442) +
                " " +
                cp(0x1F44B, 0x1F3FD) +
                "\nTap OK if you saw this."

        val SOL: Asset = asset { nativeSol = Asset.NativeSol.getDefaultInstance() }
        val USDC_ASSET: Asset = asset { tokenMint = USDC }
        val APPROVAL_V2: Approval = approval {
            preparedVersion = 2
            contentHash = sha256(TRANSACTION.toByteArray())
        }

        val ACK_PENDING =
            request(
                ACK_ID,
                action { ack = ackAction { text = ACK_TEXT } },
                RequestState.REQUEST_STATE_PENDING,
                created = "2026-09-11T12:00:00Z",
                expires = "2026-09-12T12:00:00Z",
                note = "Checking that queued requests reach the phone",
            )

        val SWAP_PENDING =
            request(
                SWAP_ID,
                action {
                    swap = swapAction {
                        wallet = WALLET
                        network = Network.NETWORK_MAINNET
                        inputAsset = USDC_ASSET
                        outputAsset = SOL
                        inputAmount = "9007199254740993"
                        slippageBps = 50
                    }
                },
                RequestState.REQUEST_STATE_PENDING,
                created = "2026-09-11T12:00:00.001Z",
                expires = "2026-09-11T12:15:00.001Z",
                note = "Rebalance: move idle USDC into SOL",
            )

        val PREPARED_V2 = preparedTransaction {
            ref = refOf(TRANSFER_ID)
            version = 2
            transaction = TRANSACTION
            // The fixture's hash must be the SHA-256 of its transaction bytes.
            contentHash = sha256(TRANSACTION.toByteArray())
            preparedAt = at("2026-09-11T12:02:30Z")
            lastValidBlockHeight = 412_345_678L
            estimatedExpiry = at("2026-09-11T12:03:30Z")
            // What the owner pays besides the amount: the network fee, and the rent for the
            // recipient's new token account (SAW-019).
            feeLamports = 5_000L
            rentLamports = 2_039_280L
        }

        fun request(
            requestId: String,
            action: Action,
            state: RequestState,
            created: String,
            expires: String,
            updated: String = created,
            note: String = "",
            connectionId: String = CONNECTION_A,
            outcome: Outcome? = null,
        ): ActionRequest = actionRequest {
            ref = refOf(requestId, connectionId)
            this.action = action
            agentNote = note
            this.state = state
            createdAt = at(created)
            expiresAt = at(expires)
            updatedAt = at(updated)
            if (outcome != null) this.outcome = outcome
        }

        fun transfer(network: Network, asset: Asset, amount: String): Action = action {
            transfer = transferAction {
                wallet = WALLET
                this.network = network
                recipient = RECIPIENT
                this.asset = asset
                this.amount = amount
            }
        }

        fun refOf(requestId: String, connectionId: String = CONNECTION_A): RequestRef = requestRef {
            this.connectionId = connectionId
            this.requestId = requestId
        }

        fun at(instant: String): Timestamp =
            Instant.parse(instant).let {
                timestamp {
                    seconds = it.epochSecond
                    nanos = it.nano
                }
            }

        fun sha256(bytes: ByteArray): ByteString =
            ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun base64(text: String): ByteString = ByteString.copyFrom(Base64.getDecoder().decode(text))

        fun cp(vararg codePoints: Int): String = String(codePoints, 0, codePoints.size)
    }
}
