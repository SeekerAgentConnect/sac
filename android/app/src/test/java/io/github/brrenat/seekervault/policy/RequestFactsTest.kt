package io.github.brrenat.seekervault.policy

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Asset
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.ackAction
import io.github.brrenat.seekervault.request.v1.action
import io.github.brrenat.seekervault.request.v1.actionRequest
import io.github.brrenat.seekervault.request.v1.asset
import io.github.brrenat.seekervault.request.v1.preparedTransaction
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.request.v1.signMessageAction
import io.github.brrenat.seekervault.request.v1.transferAction
import io.github.brrenat.seekervault.transactions.COMPUTE_BUDGET_PROGRAM
import io.github.brrenat.seekervault.transactions.SYSTEM_PROGRAM
import io.github.brrenat.seekervault.transactions.TransferInspection
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.transactions.inspectTransfer
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the facts a policy is applied to come from (docs/policy.md#what-is-evaluated): the
 * transaction's own bytes, read by the phone, and never the agent's or the sidecar's account of
 * them. These run against the transactions the sidecar really builds
 * (`fixtures/transactions/cases.json`), so the facts are the ones a real review would have.
 */
@RunWith(AndroidJUnit4::class)
class RequestFactsTest {
    private val cases =
        JSONObject(
                checkNotNull(javaClass.getResourceAsStream("/transactions/cases.json")).use {
                    it.readBytes().decodeToString()
                }
            )
            .getJSONArray("cases")

    private fun case(name: String): JSONObject =
        (0 until cases.length())
            .map { cases.getJSONObject(it) }
            .first { it.getString("name") == name }

    private fun decode(base64: String) = Base64.getDecoder().decode(base64)

    private fun requestOf(fields: JSONObject, note: String = ""): ActionRequest = actionRequest {
        ref = requestRef {
            connectionId = CONNECTION
            requestId = "f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19"
        }
        agentNote = note
        action = action {
            transfer = transferAction {
                wallet = fields.getString("wallet")
                network = Network.NETWORK_DEVNET
                recipient = fields.getString("recipient")
                amount = fields.getString("amount")
                asset =
                    if (fields.has("tokenMint")) {
                        asset { tokenMint = fields.getString("tokenMint") }
                    } else {
                        asset { nativeSol = Asset.NativeSol.getDefaultInstance() }
                    }
            }
        }
    }

    private fun inspect(
        case: JSONObject,
        note: String = "",
    ): Pair<ActionRequest, TransferInspection> {
        val fields = case.getJSONObject("request")
        val request = requestOf(fields, note)
        val prepared = preparedTransaction {
            version = case.getInt("version")
            transaction = ByteString.copyFrom(decode(case.getString("transaction")))
            contentHash = ByteString.copyFrom(decode(case.getString("contentHash")))
        }
        val wallet =
            SelectedWallet(
                address = fields.getString("wallet"),
                network = WalletNetwork.Devnet,
                selectedAt = NOW,
            )
        return request to inspectTransfer(request, prepared, wallet)
    }

    private fun factsOf(name: String, note: String = ""): RequestFacts {
        val (request, inspection) = inspect(case(name), note)
        return policyFacts(CONNECTION, request, Network.NETWORK_DEVNET, inspection)
    }

    @Test
    fun aSolTransferReadsAsWhatTheBytesDo() {
        val fields = case("sol_transfer").getJSONObject("request")
        val facts = factsOf("sol_transfer")

        assertEquals(PolicyAction.Transfer, facts.action)
        assertTrue(facts.movesValue)
        assertEquals(PolicyAsset.sol(Network.NETWORK_DEVNET), facts.asset)
        assertEquals(fields.getString("wallet"), facts.wallet)
        assertEquals(fields.getString("recipient"), facts.recipient)
        assertEquals(fields.getString("amount").toULong(), facts.amount)
        assertEquals(listOf(SYSTEM_PROGRAM), facts.programs)
        assertTrue(facts.fullyRead)
    }

    @Test
    fun anAgentsNoteIsNotAFact() {
        // The same transaction, with the agent calling it something else. Nothing it wrote is read.
        val plain = factsOf("sol_transfer")
        val claimed = factsOf("sol_transfer", note = "0.000001 SOL to the treasury, as agreed")

        assertEquals(plain, claimed)
    }

    @Test
    fun theChainComesFromTheOwnersWalletAndNotFromTheRequest() {
        val (request, inspection) = inspect(case("sol_transfer"))

        val devnet = policyFacts(CONNECTION, request, Network.NETWORK_DEVNET, inspection)
        val mainnet = policyFacts(CONNECTION, request, Network.NETWORK_MAINNET, inspection)

        assertEquals(PolicyAsset.sol(Network.NETWORK_DEVNET), devnet.asset)
        assertEquals(PolicyAsset.sol(Network.NETWORK_MAINNET), mainnet.asset)
    }

    @Test
    fun withNoWalletConnectedThereIsNoAssetToNameARuleFor() {
        val (request, inspection) = inspect(case("sol_transfer"))

        val facts = policyFacts(CONNECTION, request, Network.NETWORK_UNSPECIFIED, inspection)

        assertNull(facts.asset)
        assertNull(facts.scope)
    }

    @Test
    fun aTokenTransferNamesItsMintAndItsDecimals() {
        val fields = case("token_transfer_creates_account").getJSONObject("request")
        val facts = factsOf("token_transfer_creates_account")

        assertEquals(
            PolicyAsset.token(Network.NETWORK_DEVNET, fields.getString("tokenMint")),
            facts.asset,
        )
        assertEquals(fields.getString("recipient"), facts.recipient)
        assertEquals(
            case("token_transfer_creates_account").getJSONObject("facts").getInt("decimals"),
            facts.decimals,
        )
        assertTrue(facts.fullyRead)
    }

    @Test
    fun aTokenTransferTheBytesDontVouchForHasNoRecipient() {
        // The destination derives to the recipient's address and nothing in the transaction makes
        // the chain check that the account is still theirs, so no recipient rule can be applied to
        // it (SAW-020). The phone read the whole transaction; what it read doesn't say who gets it.
        val name = "token_destination_authority_changed"
        val facts = factsOf(name)

        assertNull(facts.recipient)
        assertTrue(facts.fullyRead)
        val policy =
            ConnectionPolicy.default(CONNECTION, NOW)
                .copy(
                    recipients =
                        Allowlist.of(case(name).getJSONObject("request").getString("recipient"))
                )

        val decision = evaluate(policy, facts)

        assertFalse(decision.allowed)
        assertEquals(listOf("recipient_unverified"), decision.reasonCodes)
    }

    @Test
    fun aTransactionWithAnInstructionNobodyReadIsNotFullyRead() {
        val (_, inspection) = inspect(case("unknown_program_alongside_the_transfer"))
        val facts = factsOf("unknown_program_alongside_the_transfer")

        assertEquals(Verdict.Unverified, inspection.verdict)
        assertFalse(inspection.approvable)
        assertFalse(facts.fullyRead)
    }

    @Test
    fun unknownCoverageCannotProduceAllowedHoweverWellTheRestMatches() {
        val fields = case("unknown_program_alongside_the_transfer").getJSONObject("request")
        val facts = factsOf("unknown_program_alongside_the_transfer")
        // Rules written to match this exact transfer, down to the amount.
        val policy =
            ConnectionPolicy.default(CONNECTION, NOW)
                .copy(
                    actions = Allowlist.of(PolicyAction.Transfer),
                    assets = Allowlist.of(PolicyAsset.sol(Network.NETWORK_DEVNET)),
                    recipients = Allowlist.of(fields.getString("recipient")),
                    limits =
                        mapOf(
                            PolicyAsset.sol(Network.NETWORK_DEVNET) to
                                AssetLimits(perOperation = ULong.MAX_VALUE)
                        ),
                )

        val decision = evaluate(policy, facts, spentToday(facts))

        assertFalse(decision.allowed)
        assertEquals(listOf("request_unverified"), decision.reasonCodes)
    }

    @Test
    fun aPriorityFeeIsAProgramTheTransactionCalls() {
        val facts = factsOf("compute_budget_priority_fee")

        assertTrue(checkNotNull(facts.programs).contains(COMPUTE_BUDGET_PROGRAM))
        assertTrue(facts.fullyRead)
        // A policy that lists the programs lists this one too, rather than having an exception
        // written into it that the owner can't see.
        val policy =
            ConnectionPolicy.default(CONNECTION, NOW).copy(programs = Allowlist.of(SYSTEM_PROGRAM))

        assertEquals(listOf("program_not_allowed"), evaluate(policy, facts).reasonCodes)
    }

    @Test
    fun aTransactionThatCouldNotBeReadEstablishesNothing() {
        val (request, inspection) = inspect(case("truncated"))

        val facts = policyFacts(CONNECTION, request, Network.NETWORK_DEVNET, inspection)

        assertEquals(Verdict.Invalid, inspection.verdict)
        assertNull(facts.asset)
        assertNull(facts.amount)
        assertNull(facts.programs)
        assertFalse(facts.fullyRead)
        assertTrue(facts.movesValue)
    }

    @Test
    fun anAcknowledgementMovesNothing() {
        val request = actionRequest {
            ref = requestRef {
                connectionId = CONNECTION
                requestId = "f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19"
            }
            action = action { ack = ackAction { text = "ping" } }
        }

        val facts = policyFacts(CONNECTION, request, Network.NETWORK_DEVNET)

        assertEquals(PolicyAction.Acknowledgement, facts.action)
        assertFalse(facts.movesValue)
        assertTrue(facts.fullyRead)
        assertNull(facts.scope)
    }

    @Test
    fun aMessageSignatureMovesNothingEither() {
        val request = actionRequest {
            ref = requestRef {
                connectionId = CONNECTION
                requestId = "f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19"
            }
            action = action {
                signMessage = signMessageAction {
                    wallet = WALLET
                    text = "hello"
                }
            }
        }

        val facts = policyFacts(CONNECTION, request, Network.NETWORK_DEVNET)

        assertEquals(PolicyAction.MessageSignature, facts.action)
        assertFalse(facts.movesValue)
    }

    @Test
    fun aTransferWithNoPreparationYetEstablishesNothingAboutWhatItMoves() {
        val request = requestOf(case("sol_transfer").getJSONObject("request"))

        val facts = policyFacts(CONNECTION, request, Network.NETWORK_DEVNET)

        assertEquals(PolicyAction.Transfer, facts.action)
        assertTrue(facts.movesValue)
        assertFalse(facts.fullyRead)
        assertNull(facts.amount)
    }
}
