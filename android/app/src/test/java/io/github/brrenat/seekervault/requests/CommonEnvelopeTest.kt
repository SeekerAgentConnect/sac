package io.github.brrenat.seekervault.requests

import com.google.protobuf.timestamp
import io.github.brrenat.seekervault.proposals.PROPOSAL_A
import io.github.brrenat.seekervault.proposals.PUBLISHED
import io.github.brrenat.seekervault.proposals.ProposalExpectation
import io.github.brrenat.seekervault.proposals.ProposalProblem
import io.github.brrenat.seekervault.proposals.ProposalResult
import io.github.brrenat.seekervault.proposals.ProposalValueKind
import io.github.brrenat.seekervault.proposals.SWAP
import io.github.brrenat.seekervault.proposals.proposal
import io.github.brrenat.seekervault.proposals.proposalFrom
import io.github.brrenat.seekervault.proposals.wireProposal
import io.github.brrenat.seekervault.request.v1.Asset
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.action
import io.github.brrenat.seekervault.request.v1.actionRequest
import io.github.brrenat.seekervault.request.v1.asset
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.request.v1.transferAction
import io.github.brrenat.seekervault.request.v2.Audience
import io.github.brrenat.seekervault.request.v2.FeedAudience
import io.github.brrenat.seekervault.request.v2.OwnerInput
import io.github.brrenat.seekervault.request.v2.OwnerInputKind
import io.github.brrenat.seekervault.request.v2.Presentation
import io.github.brrenat.seekervault.request.v2.PresentationCategory
import io.github.brrenat.seekervault.request.v2.PrivateAudience
import io.github.brrenat.seekervault.request.v2.Request
import io.github.brrenat.seekervault.request.v2.RequestIdentity
import io.github.brrenat.seekervault.request.v2.RequestLifecycle
import io.github.brrenat.seekervault.request.v2.RequestStatus
import io.github.brrenat.seekervault.request.v2.ResultHandling
import io.github.brrenat.seekervault.request.v2.ResultMode
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.SWAP_PLUGIN
import io.github.brrenat.seekervault.servers.channelFor
import org.junit.Assert.assertEquals
import org.junit.Test

class CommonEnvelopeTest {
    @Test
    fun privateAndFeedRecordsNormalizeIntoOneContractWithoutLocalResults() {
        val direct = actionRequest {
            ref = requestRef {
                connectionId = SERVER_B
                requestId = PROPOSAL_A
            }
            action = action {
                transfer = transferAction {
                    wallet = WALLET
                    network = Network.NETWORK_MAINNET
                    recipient = RECIPIENT
                    asset = asset { nativeSol = Asset.NativeSol.getDefaultInstance() }
                    amount = "42"
                }
            }
            agentNote = "Pay the invoice"
            state = RequestState.REQUEST_STATE_SUBMITTED
            createdAt = timestamp { seconds = PUBLISHED.epochSecond }
            updatedAt = timestamp { seconds = PUBLISHED.plusSeconds(2).epochSecond }
            expiresAt = timestamp { seconds = PUBLISHED.plusSeconds(60).epochSecond }
        }
        val privateEnvelope = direct.commonEnvelope()
        val feedEnvelope = proposal(wireProposal(note = "Follow the allocation")).commonEnvelope()

        assertEquals(
            PresentationCategory.PRESENTATION_CATEGORY_REQUEST,
            privateEnvelope.presentation.category,
        )
        assertEquals(Audience.AudienceCase.PRIVATE, privateEnvelope.audience.audienceCase)
        assertEquals(ResultMode.RESULT_MODE_RETURN_TO_ORIGIN, privateEnvelope.resultHandling.mode)
        assertEquals(RequestStatus.REQUEST_STATUS_SUBMITTED, privateEnvelope.lifecycle.status)
        assertEquals(
            "42",
            privateEnvelope.action.parametersList.single { it.key == "amount" }.integer,
        )

        assertEquals(
            PresentationCategory.PRESENTATION_CATEGORY_SIGNAL,
            feedEnvelope.presentation.category,
        )
        assertEquals(Audience.AudienceCase.FEED, feedEnvelope.audience.audienceCase)
        assertEquals(ResultMode.RESULT_MODE_DEVICE_LOCAL, feedEnvelope.resultHandling.mode)
        assertEquals(PROPOSAL_A, feedEnvelope.identity.requestId)

        // Neither adapter has a common-envelope field to copy the selected wallet, outcome,
        // prepared bytes, decision or owner answer into. Those remain in their existing local or
        // authenticated-origin record; the broadcast contract's structure is pinned in Go too.
    }

    @Test
    fun commonFeedValidationKeepsPresentationAndOwnerInputDeclarations() {
        val message = commonFeedRequest()
        val result = proposalFrom(message, ProposalExpectation(SERVER_B)) as ProposalResult.Valid

        assertEquals("Swap SOL for USDC", result.proposal.title)
        assertEquals("Amount", result.proposal.ownerInputs.single().label)
        assertEquals("139420000", result.proposal.value("published_price"))
        assertEquals(ProposalValueKind.Integer, result.proposal.values.single().kind)
        assertEquals(
            "139420000",
            result.proposal.commonEnvelope().action.parametersList.single().integer,
        )
    }

    @Test
    fun aFeedCannotUseAPrivateAudienceOrReturnAResult() {
        val privateAudience =
            commonFeedRequest()
                .toBuilder()
                .setAudience(
                    Audience.newBuilder()
                        .setPrivate(PrivateAudience.newBuilder().setRecipientId("device"))
                )
                .build()
        val returned =
            commonFeedRequest()
                .toBuilder()
                .setResultHandling(
                    ResultHandling.newBuilder().setMode(ResultMode.RESULT_MODE_RETURN_TO_ORIGIN)
                )
                .build()

        assertEquals(
            ProposalProblem.WrongAudience,
            (proposalFrom(privateAudience, ProposalExpectation(SERVER_B)) as ProposalResult.Invalid)
                .problem,
        )
        assertEquals(
            ProposalProblem.WrongAudience,
            (proposalFrom(returned, ProposalExpectation(SERVER_B)) as ProposalResult.Invalid)
                .problem,
        )
    }

    private fun commonFeedRequest(): Request =
        proposal(wireProposal())
            .commonEnvelope()
            .toBuilder()
            .setPresentation(
                Presentation.newBuilder()
                    .setTitle("Swap SOL for USDC")
                    .setDescription("Follow the allocation")
                    .setCategory(PresentationCategory.PRESENTATION_CATEGORY_SIGNAL)
            )
            .setIdentity(
                RequestIdentity.newBuilder()
                    .setSourceId(SERVER_B)
                    .setScope(channelFor(SERVER_B))
                    .setRequestId(PROPOSAL_A)
            )
            .setLifecycle(
                RequestLifecycle.newBuilder()
                    .setRevision(1)
                    .setStatus(RequestStatus.REQUEST_STATUS_OPEN)
                    .setCreatedAt(timestamp { seconds = PUBLISHED.epochSecond })
                    .setUpdatedAt(timestamp { seconds = PUBLISHED.epochSecond })
                    .setExpiresAt(timestamp { seconds = PUBLISHED.plusSeconds(3600).epochSecond })
            )
            .setAction(
                io.github.brrenat.seekervault.request.v2.ActionCapability.newBuilder()
                    .setCapabilityId(SWAP)
                    .setCapabilityVersion(1)
                    .setPluginId(SWAP_PLUGIN)
                    .addParameters(
                        io.github.brrenat.seekervault.request.v2.Value.newBuilder()
                            .setKey("published_price")
                            .setInteger("139420000")
                    )
            )
            .addOwnerInputs(
                OwnerInput.newBuilder()
                    .setKey("input_amount")
                    .setLabel("Amount")
                    .setKind(OwnerInputKind.OWNER_INPUT_KIND_AMOUNT)
                    .setRequired(true)
                    .setMinimum("1")
            )
            .setAudience(
                Audience.newBuilder()
                    .setFeed(FeedAudience.newBuilder().setChannel(channelFor(SERVER_B)))
            )
            .setResultHandling(
                ResultHandling.newBuilder().setMode(ResultMode.RESULT_MODE_DEVICE_LOCAL)
            )
            .build()

    private companion object {
        const val WALLET = "6xJ8QGkQ6Qx1e8YpQ2CqZ9bJ7jY7N3tFh5T9Jr2vQ4dM"
        const val RECIPIENT = "8YqPZcJgTkVV1w7M6kC4t2u8D1n3a5F7h9L2s4B6v8Nq"
    }
}
