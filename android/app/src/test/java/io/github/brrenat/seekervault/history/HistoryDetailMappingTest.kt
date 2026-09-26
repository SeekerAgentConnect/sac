package io.github.brrenat.seekervault.history

import io.github.brrenat.seekervault.activity.ANSWERED_AT
import io.github.brrenat.seekervault.activity.CONNECTION
import io.github.brrenat.seekervault.activity.RECIPIENT
import io.github.brrenat.seekervault.activity.ackRequest
import io.github.brrenat.seekervault.activity.connection
import io.github.brrenat.seekervault.activity.messageRequest
import io.github.brrenat.seekervault.activity.result
import io.github.brrenat.seekervault.activity.signatureBytes
import io.github.brrenat.seekervault.activity.transferRequest
import io.github.brrenat.seekervault.confirmations.ChainCheck
import io.github.brrenat.seekervault.confirmations.ChainLevel
import io.github.brrenat.seekervault.confirmations.ChainReason
import io.github.brrenat.seekervault.confirmations.ChainState
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.ServerColour
import io.github.brrenat.seekervault.connections.SigningOutcome
import io.github.brrenat.seekervault.designsystem.HistoryDetailEnvironment
import io.github.brrenat.seekervault.designsystem.HistoryDetailEvent
import io.github.brrenat.seekervault.designsystem.HistoryDetailExecutionState
import io.github.brrenat.seekervault.designsystem.HistoryDetailOrigin
import io.github.brrenat.seekervault.designsystem.HistoryDetailResponse
import io.github.brrenat.seekervault.designsystem.HistoryDetailRow
import io.github.brrenat.seekervault.designsystem.HistoryDetailRowLayout
import io.github.brrenat.seekervault.designsystem.HistoryDetailStatus
import io.github.brrenat.seekervault.designsystem.HistoryDetailTransactionStatus
import io.github.brrenat.seekervault.designsystem.SourceColour
import io.github.brrenat.seekervault.operations.choiceRows
import io.github.brrenat.seekervault.operations.predictionProposal
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterField
import io.github.brrenat.seekervault.plugins.ParameterForm
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterOption
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.proposals.PUBLISHED
import io.github.brrenat.seekervault.proposals.ProposalDismissal
import io.github.brrenat.seekervault.proposals.ProposalExecution
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.binding
import io.github.brrenat.seekervault.proposals.choice
import io.github.brrenat.seekervault.proposals.proposal
import io.github.brrenat.seekervault.proposals.wireProposal
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.confirmation
import io.github.brrenat.seekervault.wallet.encodeBase58
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record page is built from stored records only, and each supplied variant keeps exactly the
 * sections its reference shows (docs/design/history-details/TASK.md §6).
 */
class HistoryDetailMappingTest {
    private val clock = HistoryDetailClock(ZoneOffset.UTC, Locale.US)
    private val owner = connection("Personal MCP").copy(colour = ServerColour.Sky)

    @Test
    fun anApprovedConfirmedTransferHasOneConfirmedTransactionOnTheRightCluster() {
        val signature = signatureBytes()
        val request =
            transferRequest(
                    network = Network.NETWORK_MAINNET,
                    amount = "400000000",
                    state = RequestState.REQUEST_STATE_CONFIRMED,
                    signature = signature,
                )
                .toBuilder()
                .setAgentNote("Pay Dana back for the concert tickets.")
                .setUpdatedAt(
                    com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(ANSWERED_AT.plusSeconds(19).epochSecond)
                )
                .build()
        val model =
            privateHistoryDetail(
                result(request, signing = SigningOutcome.Sent(signature))
                    .copy(settledAt = ANSWERED_AT.plusSeconds(5)),
                owner,
                clock,
            )

        assertEquals(HistoryDetailOrigin.Request, model.header.origin)
        assertEquals("Personal MCP", model.header.sourceName)
        assertEquals(SourceColour.Sky, model.header.sourceColour)
        assertEquals("Send 0.4 SOL", model.header.title)
        assertEquals(HistoryDetailEnvironment.Production, model.header.environment)
        assertEquals("Mainnet", model.header.networkText)
        assertEquals(HistoryDetailStatus.Approved, model.status.status)
        assertNull(model.delivery)
        assertEquals(HistoryDetailExecutionState.Confirmed, model.execution?.state)
        assertEquals("Confirmed on Mainnet", model.execution?.title)
        val sent = model.response as HistoryDetailResponse.Sent
        assertEquals("Sep 11, 12:00:00 PM", sent.timestampText.plain())
        assertEquals(
            HistoryDetailRow("Delivered to", "Personal MCP, 12:00 PM"),
            sent.rows[1].let { it.copy(value = it.value.plain()) },
        )

        val transaction = model.transactions.single()
        val base58 = encodeBase58(signature.toByteArray())
        assertEquals("Transfer", transaction.label)
        assertEquals(HistoryDetailTransactionStatus.Confirmed, transaction.status)
        assertEquals("https://explorer.solana.com/tx/$base58", transaction.explorerUrl)
        assertEquals("View on explorer · Mainnet", transaction.explorerLabel)

        assertEquals("Pay Dana back for the concert tickets.", model.originalDescription)
        assertTrue(
            HistoryDetailRow("Recipient", RECIPIENT, HistoryDetailRowLayout.Block) in
                model.originalRows
        )
        assertTrue(model.timeline.any { it.event == HistoryDetailEvent.Confirmed })
        assertEquals(listOf("Request ID", "Signature"), model.identifiers.map { it.label })
        assertEquals(HistoryDetailCopy.ProductionFootnote, model.footnote)
    }

    @Test
    fun aPendingTransferSaysApprovingIsNotSuccessAndItsChipReadsPending() {
        val signature = signatureBytes()
        val request =
            transferRequest(
                network = Network.NETWORK_DEVNET,
                state = RequestState.REQUEST_STATE_SUBMITTED,
                signature = signature,
            )
        val model =
            privateHistoryDetail(
                result(request, signing = SigningOutcome.Sent(signature)),
                owner,
                clock,
            )

        assertEquals(HistoryDetailStatus.Approved, model.status.status)
        assertEquals(HistoryDetailExecutionState.Pending, model.execution?.state)
        assertTrue(model.execution!!.body.contains("doesn't mean it went through"))
        assertEquals(HistoryDetailTransactionStatus.Pending, model.transactions.single().status)
        assertTrue(model.transactions.single().explorerUrl!!.endsWith("?cluster=devnet"))
        assertFalse(model.timeline.any { it.event == HistoryDetailEvent.Confirmed })
    }

    @Test
    fun aFailedTransferIsStillApprovedAndKeepsTheRawErrorUnderIdentifiers() {
        val signature = signatureBytes()
        val request =
            transferRequest(
                    network = Network.NETWORK_MAINNET,
                    state = RequestState.REQUEST_STATE_FAILED,
                    signature = signature,
                    detail = "The wallet didn't hold enough SOL for the amount plus the fee.",
                )
                .toBuilder()
                .let { builder ->
                    builder.setOutcome(
                        builder.outcome
                            .toBuilder()
                            .setConfirmation(
                                confirmation { chainError = "InstructionError(0, Custom(1))" }
                            )
                    )
                }
                .build()
        val model =
            privateHistoryDetail(
                result(request, signing = SigningOutcome.Sent(signature)),
                owner,
                clock,
            )

        assertEquals(HistoryDetailStatus.Approved, model.status.status)
        assertEquals(HistoryDetailExecutionState.Failed, model.execution?.state)
        assertEquals(
            "The wallet didn't hold enough SOL for the amount plus the fee.",
            model.execution?.failureReason,
        )
        assertEquals(HistoryDetailTransactionStatus.Failed, model.transactions.single().status)
        assertEquals(
            HistoryDetailRow("Error", "InstructionError(0, Custom(1))"),
            model.identifiers.last(),
        )
        // The readable reason is the execution card's; the raw error is only an identifier.
        assertFalse(model.execution!!.body.contains("InstructionError"))
    }

    @Test
    fun aDeclineHasNoExecutionAndNoTransactions() {
        val model =
            privateHistoryDetail(
                result(transferRequest(), answer = Answer.Reject, approved = false),
                owner,
                clock,
            )

        assertEquals(HistoryDetailStatus.Declined, model.status.status)
        assertEquals("You declined on this phone. Personal MCP was told.", model.status.explanation)
        assertEquals(
            HistoryDetailRow("Decision", "Declined"),
            (model.response as HistoryDetailResponse.Sent).rows.first(),
        )
        assertNull(model.execution)
        assertTrue(model.transactions.isEmpty())
        assertEquals(
            listOf(HistoryDetailEvent.Received, HistoryDetailEvent.Declined),
            model.timeline.map { it.event },
        )
    }

    @Test
    fun anUnacknowledgedAnswerShowsTheDeliveryProblemAndOnlyAWaitingOneCanBeSentAgain() {
        val waiting =
            privateHistoryDetail(
                result(ackRequest(), answer = Answer.Acknowledge, delivery = Delivery.Waiting)
                    .copy(lastFailure = CheckOutcome.Unreachable),
                owner,
                clock,
                sending = true,
            )
        assertNotNull(waiting.delivery)
        assertTrue(waiting.delivery!!.canSendAgain)
        assertTrue(waiting.delivery!!.sending)
        assertTrue(waiting.delivery!!.title.startsWith("Personal MCP hasn't confirmed"))
        // Delivery and execution are independent: nothing ran for an acknowledgement.
        assertNull(waiting.execution)

        val undeliverable =
            privateHistoryDetail(
                result(
                    ackRequest(),
                    answer = Answer.Acknowledge,
                    delivery = Delivery.Undeliverable,
                ),
                owner,
                clock,
            )
        assertFalse(undeliverable.delivery!!.canSendAgain)

        val delivered =
            privateHistoryDetail(result(ackRequest(), answer = Answer.Acknowledge), owner, clock)
        assertNull(delivered.delivery)
    }

    @Test
    fun aRequestCancelledBeforeTheAnswerLandedShowsTheServersReason() {
        val request =
            transferRequest(state = RequestState.REQUEST_STATE_CANCELLED, detail = "Replaced.")
                .toBuilder()
                .setUpdatedAt(
                    com.google.protobuf.Timestamp.newBuilder()
                        .setSeconds(ANSWERED_AT.plusSeconds(60).epochSecond)
                )
                .build()
        val model =
            privateHistoryDetail(
                result(request, answer = Answer.Reject, delivery = Delivery.Superseded),
                owner,
                clock,
            )

        assertEquals(HistoryDetailStatus.Cancelled, model.status.status)
        assertEquals("Replaced.", model.status.reason)
        assertEquals("Reason given by Personal MCP", model.status.reasonLabel)
        assertTrue(model.timeline.any { it.event == HistoryDetailEvent.Cancelled })
        assertNull(model.execution)
    }

    @Test
    fun aSignedMessageHasASignedResultTheMessageAsABlockAndNoTransactions() {
        val signature = signatureBytes(3)
        val model =
            privateHistoryDetail(
                result(messageRequest(), signing = SigningOutcome.Signed(signature)),
                owner,
                clock,
            )

        assertEquals("Sign a message", model.header.title)
        assertNull(model.header.networkText)
        assertEquals(HistoryDetailExecutionState.Signed, model.execution?.state)
        assertEquals("Signed · no transaction", model.execution?.title)
        assertTrue(model.transactions.isEmpty())
        assertEquals(
            HistoryDetailRow("Message", "Sign in to Example", HistoryDetailRowLayout.Block),
            model.originalRows.first(),
        )
        assertEquals(
            HistoryDetailRow("Message signature", encodeBase58(signature.toByteArray())),
            model.identifiers[1],
        )
    }

    @Test
    fun aWalletThatNeverAnsweredIsNeitherSignedNorSent() {
        val model =
            privateHistoryDetail(
                result(transferRequest(), signing = SigningOutcome.Unresolved("App closed.")),
                owner,
                clock,
            )
        assertEquals(HistoryDetailStatus.Approved, model.status.status)
        assertTrue(model.status.explanation.contains("never learned"))
        // Nothing to look up without a signature; the owner is pointed at the wallet (SEE-165).
        assertTrue(model.status.explanation.contains("wallet's own history"))
        assertNull(model.execution)
        assertTrue(model.transactions.isEmpty())
    }

    /** The JDK's locale data puts a narrow no-break space before AM and PM. */
    private fun String.plain(): String = replace(Char(NARROW_NO_BREAK_SPACE), ' ')

    // Signals ---------------------------------------------------------------------------------

    private val feed = owner.copy(id = CONNECTION, label = "Trader Signals")

    private fun record(
        execution: ProposalExecution? = null,
        dismissed: ProposalDismissal? = null,
    ): ProposalRecord =
        ProposalRecord(
            connectionId = CONNECTION,
            proposal = proposal(wireProposal(note = "SOL broke out of its range.")),
            dismissed = dismissed,
            execution = execution,
        )

    @Test
    fun aDismissedSignalSaysFeedsAreNotToldAndHasNoExecution() {
        val at = PUBLISHED.plusSeconds(120)
        val model =
            signalHistoryDetail(
                record(dismissed = ProposalDismissal(1, at)),
                ProposalStanding.Dismissed(at),
                feed,
                clock = clock,
            )

        assertEquals(HistoryDetailOrigin.Signal, model.header.origin)
        assertEquals("Trader Signals", model.header.sourceName)
        assertEquals(HistoryDetailStatus.Dismissed, model.status.status)
        val sent = model.response as HistoryDetailResponse.Sent
        assertEquals("Feeds aren't told when you dismiss a signal.", sent.note)
        assertNull(model.execution)
        assertTrue(model.transactions.isEmpty())
        assertEquals("SOL broke out of its range.", model.originalDescription)
        assertEquals(
            listOf(HistoryDetailEvent.Received, HistoryDetailEvent.Dismissed),
            model.timeline.map { it.event },
        )
    }

    @Test
    fun anExpiredSignalHasNoResponseAndSaysItIsNotADecline() {
        val model = signalHistoryDetail(record(), ProposalStanding.Expired, feed, clock = clock)

        assertEquals(HistoryDetailStatus.Expired, model.status.status)
        val none = model.response as HistoryDetailResponse.None
        assertTrue(none.explanation.contains("This isn't recorded as a decline."))
        assertNull(model.execution)
        assertTrue(model.transactions.isEmpty())
    }

    @Test
    fun aSandboxSimulationHasTheSimulatedCardAndNoTransactionOrExplorerLink() {
        val base = record()
        val execution =
            ProposalExecution(
                binding =
                    binding(
                        base.proposal,
                        choice(25_000_000UL),
                        environment = PluginEnvironment.Sandbox,
                        network = Network.NETWORK_DEVNET,
                    ),
                startedAt = ANSWERED_AT,
                outcome = ProposalOutcome.Simulated,
                settledAt = ANSWERED_AT.plusSeconds(2),
            )
        val model =
            signalHistoryDetail(
                base.copy(execution = execution),
                ProposalStanding.Executed(ProposalOutcome.Simulated),
                feed,
                choice = listOf(HistoryDetailRow("Amount you entered", "25")),
                clock = clock,
            )

        assertEquals(HistoryDetailEnvironment.Sandbox, model.header.environment)
        assertEquals("Devnet", model.header.networkText)
        assertEquals(
            listOf(
                HistoryDetailRow("Decision", "Approved (simulation)"),
                HistoryDetailRow("Amount you entered", "25"),
            ),
            (model.response as HistoryDetailResponse.Sent).rows,
        )
        assertEquals(HistoryDetailExecutionState.Simulated, model.execution?.state)
        assertTrue(model.transactions.isEmpty())
        assertFalse(model.identifiers.any { it.label == "Signature" })
        assertEquals(HistoryDetailCopy.SandboxFootnote, model.footnote)
    }

    @Test
    fun aSubmittedSignalIsPendingUntilThePhoneHasCheckedIt() {
        val base = record()
        val signature = signatureBytes(9)
        val outcome = ProposalOutcome.Submitted(signature)
        val model =
            signalHistoryDetail(
                base.copy(
                    execution =
                        ProposalExecution(
                            binding = binding(base.proposal, choice(1UL)),
                            startedAt = ANSWERED_AT,
                            outcome = outcome,
                            settledAt = ANSWERED_AT.plusSeconds(3),
                        )
                ),
                ProposalStanding.Executed(outcome),
                feed,
                clock = clock,
            )

        assertEquals(HistoryDetailExecutionState.Pending, model.execution?.state)
        val transaction = model.transactions.single()
        assertEquals("Swap", transaction.label)
        assertEquals(HistoryDetailTransactionStatus.Pending, transaction.status)
        assertEquals(
            "https://explorer.solana.com/tx/${encodeBase58(signature.toByteArray())}",
            transaction.explorerUrl,
        )
    }

    // SEE-165: what the phone itself found on chain ---------------------------------------------

    private val verified =
        ChainCheck(
            state = ChainState.Confirmed,
            level = ChainLevel.Finalized,
            slot = 812L,
            checkedAt = ANSWERED_AT.plusSeconds(20),
            checks = 2,
            host = "rpc.example.com",
        )

    @Test
    fun thePhonesOwnVerifiedResultIsShownWhileTheServerIsStillBehind() {
        val signature = signatureBytes()
        val request = transferRequest(state = RequestState.REQUEST_STATE_SUBMITTED)
        val model =
            privateHistoryDetail(
                result(request, signing = SigningOutcome.Sent(signature)),
                owner,
                clock,
                chain = verified,
            )

        assertEquals(HistoryDetailExecutionState.Confirmed, model.execution?.state)
        assertTrue(model.execution!!.body.contains("This phone checked"))
        assertTrue(model.execution!!.body.contains("rpc.example.com"))
        assertTrue(model.execution!!.rows.contains(HistoryDetailRow("Commitment", "Finalized")))
        assertNull(model.execution!!.checkStatus)
        assertEquals(HistoryDetailTransactionStatus.Confirmed, model.transactions.single().status)
        assertTrue(model.timeline.any { it.event == HistoryDetailEvent.Confirmed })
        // Local confirmation says nothing about delivery: a response still waiting still says so.
        val waiting =
            privateHistoryDetail(
                result(
                    request,
                    signing = SigningOutcome.Sent(signature),
                    delivery = Delivery.Waiting,
                ),
                owner,
                clock,
                chain = verified,
            )
        assertNotNull(waiting.delivery)
        assertEquals(HistoryDetailExecutionState.Confirmed, waiting.execution?.state)
    }

    @Test
    fun aLocallyVerifiedFailureIsNotOverruledByTheServer() {
        val signature = signatureBytes()
        val model =
            privateHistoryDetail(
                result(
                    transferRequest(state = RequestState.REQUEST_STATE_CONFIRMED),
                    signing = SigningOutcome.Sent(signature),
                ),
                owner,
                clock,
                chain =
                    verified.copy(state = ChainState.Failed, chainError = "custom program error"),
            )
        assertEquals(HistoryDetailExecutionState.Failed, model.execution?.state)
        // The readable reason on the card, the chain's raw error under Identifiers (SEE-161).
        assertFalse(model.execution!!.failureReason!!.contains("custom program error"))
        assertEquals(HistoryDetailRow("Error", "custom program error"), model.identifiers.last())
        assertEquals(HistoryDetailTransactionStatus.Failed, model.transactions.single().status)
    }

    @Test
    fun aProvenExpiryIsNeverLandedAndOffersNoCheck() {
        val model =
            privateHistoryDetail(
                result(transferRequest(), signing = SigningOutcome.Sent(signatureBytes())),
                owner,
                clock,
                chain = ChainCheck(ChainState.Expired, checkedAt = ANSWERED_AT.plusSeconds(600)),
            )
        assertEquals("Expired · never landed", model.execution?.title)
        assertTrue(model.execution!!.body.contains("Nothing was spent"))
        assertNull(model.execution!!.checkStatus)
        assertEquals(HistoryDetailTransactionStatus.Failed, model.transactions.single().status)
    }

    @Test
    fun anUnsettledCheckSaysWhenItWasTriedAndOffersCheckStatus() {
        val model =
            privateHistoryDetail(
                result(transferRequest(), signing = SigningOutcome.Sent(signatureBytes())),
                owner,
                clock,
                chain =
                    ChainCheck(
                        state = ChainState.Checking,
                        checkedAt = ANSWERED_AT.plusSeconds(30),
                        reason = ChainReason.RateLimited,
                        nextCheckAt = ANSWERED_AT.plusSeconds(90),
                    ),
                checkingChain = true,
            )
        assertEquals(HistoryDetailExecutionState.Pending, model.execution?.state)
        val body = model.execution!!.body.plain()
        assertTrue(body, body.contains("slow down"))
        assertTrue(body, body.contains("Last checked"))
        assertTrue(body, body.contains("Next check"))
        assertEquals(true, model.execution!!.checkStatus?.checking)
    }

    @Test
    fun aConfirmedPredictionOrderIsNeverCalledFilled() {
        val base =
            ProposalRecord(
                connectionId = CONNECTION,
                proposal = proposal(predictionProposal()),
            )
        val signature = signatureBytes(9)
        val outcome = ProposalOutcome.Submitted(signature)
        val model =
            signalHistoryDetail(
                base.copy(
                    execution =
                        ProposalExecution(
                            binding = binding(base.proposal, choice(1UL)),
                            startedAt = ANSWERED_AT,
                            outcome = outcome,
                            settledAt = ANSWERED_AT.plusSeconds(3),
                        )
                ),
                ProposalStanding.Executed(outcome),
                feed,
                clock = clock,
                chain = verified,
            )
        assertEquals(HistoryDetailExecutionState.Confirmed, model.execution?.state)
        assertTrue(model.execution!!.body.contains("doesn't mean the order filled"))
        assertFalse(model.toString().contains("Filled"))
        assertEquals(HistoryDetailTransactionStatus.Confirmed, model.transactions.single().status)
    }

    @Test
    fun aCancelledSignalSaysTheFeedWithdrewIt() {
        val model = signalHistoryDetail(record(), ProposalStanding.Cancelled, feed, clock = clock)
        assertEquals(HistoryDetailStatus.Cancelled, model.status.status)
        assertTrue(model.status.explanation.startsWith("Trader Signals withdrew it"))
        assertTrue(model.response is HistoryDetailResponse.None)
    }

    @Test
    fun theChoiceIsWrittenInTheFormsOwnWordsAndUnknownKeysAsStored() {
        val side = ParameterKey("side")
        val amount = ParameterKey("amount")
        val slippage = ParameterKey("slippage_bps")
        val yes = ParameterKey("yes")
        val form =
            ParameterForm(
                fields =
                    listOf(
                        ParameterField(
                            side,
                            1,
                            ParameterKind.Choice(listOf(ParameterOption(yes, 2))),
                        ),
                        ParameterField(
                            amount,
                            3,
                            ParameterKind.Amount(mint = "USDC", decimals = 6),
                        ),
                    )
            )
        val rows =
            choiceRows(
                ParameterChoice(
                    mapOf(
                        slippage to ParameterValue.Count(50u),
                        amount to ParameterValue.Amount(25_000_000UL),
                        side to ParameterValue.Selected(yes),
                    )
                ),
                form,
            ) { id ->
                when (id) {
                    1 -> "Side you chose"
                    2 -> "Yes"
                    else -> "Stake you entered"
                }
            }

        assertEquals(
            listOf(
                HistoryDetailRow("Side you chose", "Yes"),
                HistoryDetailRow("Stake you entered", "25"),
                HistoryDetailRow("slippage_bps", "50"),
            ),
            rows,
        )
    }
}

private const val NARROW_NO_BREAK_SPACE = 0x202F
