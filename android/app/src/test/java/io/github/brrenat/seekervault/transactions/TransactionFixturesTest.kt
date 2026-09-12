package io.github.brrenat.seekervault.transactions

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.actionRequest
import io.github.brrenat.seekervault.request.v1.preparedTransaction
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.time.Instant
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The transfer transactions the sidecar builds, decoded here
 * (docs/testing/transaction-fixtures.md).
 *
 * This is what makes the phone's review independent rather than merely separate: every case is a
 * real transaction produced by `sidecar/src/solana/`, and the phone has to reach the same verdict
 * about it from the bytes alone — including for the cases that are valid transactions and simply
 * are not the one the owner was asked to approve.
 */
@RunWith(AndroidJUnit4::class)
class TransactionFixturesTest {
    private val fixtures: JSONObject =
        JSONObject(
            checkNotNull(javaClass.getResourceAsStream("/transactions/cases.json")) {
                    "fixtures/transactions/cases.json is missing; run `node sidecar/src/testing/transaction-fixtures.ts`"
                }
                .use { it.readBytes().decodeToString() }
        )

    private val cases = fixtures.getJSONArray("cases")

    @Test
    fun everyCaseReachesTheVerdictTheSidecarRecorded() {
        assertTrue("the fixtures were found", cases.length() >= 20)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val inspection = inspect(case)
            assertEquals(
                "$name: ${case.getString("description")}",
                case.getString("verdict"),
                inspection.verdict.name.lowercase(),
            )
            assertEquals(
                "$name findings",
                (0 until case.getJSONArray("findings").length()).map {
                    case.getJSONArray("findings").getString(it)
                },
                inspection.findings.map { it.name }.sorted(),
            )
        }
    }

    @Test
    fun everyCaseReadsTheSameAddressesAndBaseUnitsOutOfTheBytes() {
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val facts = inspect(case).facts
            if (case.isNull("facts")) {
                assertEquals("$name: nothing should have been read", null, facts)
                continue
            }
            val expected = case.getJSONObject("facts")
            assertNotNull("$name: the transfer should have been read", facts)
            checkNotNull(facts)
            assertEquals(
                "$name recipient",
                if (expected.isNull("recipient")) null else expected.getString("recipient"),
                facts.recipient,
            )
            assertEquals(
                "$name destinationAccount",
                if (expected.isNull("destinationAccount")) null
                else expected.getString("destinationAccount"),
                facts.destinationAccount,
            )
            // The amount is compared as a string, so a value above 2^53 can't quietly change.
            assertEquals("$name amount", expected.getString("amount"), facts.amount.toString())
            assertEquals(
                "$name mint",
                if (expected.isNull("mint")) null else expected.getString("mint"),
                facts.mint,
            )
            assertEquals("$name decimals", expected.getInt("decimals"), facts.decimals)
            assertEquals(
                "$name ensuresRecipientAccount",
                expected.getBoolean("ensuresRecipientAccount"),
                facts.ensuresRecipientAccount,
            )
        }
    }

    @Test
    fun nothingUnreadIsEverPresentedAsFullyVerified() {
        var covered = 0
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val inspection = inspect(case)
            val facts = inspection.facts ?: continue
            if (facts.recognizedInstructions == facts.instructionCount) continue
            covered++
            assertTrue(
                "${case.getString("name")}: an unread instruction must not read as verified",
                inspection.verdict != Verdict.Verified,
            )
            assertTrue(
                "${case.getString("name")}: an unread instruction must not be approvable",
                !inspection.approvable,
            )
        }
        assertTrue("cases with an unread instruction were found", covered >= 2)
    }

    @Test
    fun onlyAFullyReadAndMatchingTransactionIsApprovable() {
        val approvable =
            (0 until cases.length())
                .map { cases.getJSONObject(it) }
                .filter { inspect(it).approvable }
                .map { it.getString("name") }
        assertEquals(
            listOf(
                "sol_transfer",
                "token_transfer_existing_account",
                "token_transfer_creates_account",
                "token_transfer_max_amount",
                "token_transfer_zero_decimals",
                "fake_ticker_in_the_note",
                "compute_budget_priority_fee",
                "legacy_message",
            ),
            approvable,
        )
    }

    /**
     * The case that derivation alone would get wrong. A classic SPL token account's authority can
     * be handed to somebody else after its address was derived, so a destination that derives
     * correctly proves only what the account is called. What proves whose it is now is the
     * associated-account instruction in the transaction itself, which the chain enforces.
     */
    @Test
    fun aDerivedAddressAloneNeverEstablishesWhoReceivesTheTokens() {
        val unchecked = inspect(case("token_destination_authority_changed"))
        val vouched = inspect(case("token_transfer_existing_account"))
        // The very same destination account, in both.
        assertEquals(vouched.facts?.destinationAccount, unchecked.facts?.destinationAccount)
        assertEquals(listOf(Finding.DestinationOwnerUnchecked), unchecked.findings)
        assertEquals(Verdict.Invalid, unchecked.verdict)
        assertTrue("an unestablished owner is not approvable", !unchecked.approvable)
        assertEquals("no wallet may be named for it", null, unchecked.facts?.recipient)
        assertTrue(!checkNotNull(unchecked.facts).ensuresRecipientAccount)
        // And with the instruction that has the chain check it, the same transfer is verified.
        assertEquals(Verdict.Verified, vouched.verdict)
        assertTrue(checkNotNull(vouched.facts).ensuresRecipientAccount)
        assertNotNull(vouched.facts?.recipient)
    }

    @Test
    fun theAgentsNoteIsNeverEvidence() {
        // The note claims a ticker; the phone reads a mint address and base units, and its verdict
        // is the same as for the identical transfer with no note at all.
        val claimed = case("fake_ticker_in_the_note")
        val plain = case("token_transfer_creates_account")
        assertTrue(claimed.getJSONObject("request").getString("note").contains("USDC"))
        assertEquals(inspect(plain).verdict, inspect(claimed).verdict)
        assertEquals(inspect(plain).facts?.mint, inspect(claimed).facts?.mint)
        // Nothing the phone reads out of a transaction carries a name for a note to contradict.
        assertEquals(
            inspect(claimed).facts?.mint,
            claimed.getJSONObject("request").getString("tokenMint"),
        )
    }

    private fun case(name: String): JSONObject =
        (0 until cases.length())
            .map { cases.getJSONObject(it) }
            .first { it.getString("name") == name }

    /**
     * Rebuilds the request and the preparation the phone would have been handed, and reads them.
     */
    private fun inspect(case: JSONObject): TransferInspection {
        val fields = case.getJSONObject("request")
        val request = actionRequest {
            ref = requestRef
            action = transferActionOf(fields)
            agentNote = if (fields.has("note")) fields.getString("note") else ""
        }
        val prepared = preparedTransaction {
            version = case.getInt("version")
            transaction = ByteString.copyFrom(decode(case.getString("transaction")))
            contentHash = ByteString.copyFrom(decode(case.getString("contentHash")))
        }
        return inspectTransfer(request, prepared, wallet(fields.getString("wallet")))
    }

    private fun transferActionOf(fields: JSONObject) =
        io.github.brrenat.seekervault.request.v1.action {
            transfer =
                io.github.brrenat.seekervault.request.v1.transferAction {
                    wallet = fields.getString("wallet")
                    network = Network.NETWORK_DEVNET
                    recipient = fields.getString("recipient")
                    amount = fields.getString("amount")
                    asset =
                        io.github.brrenat.seekervault.request.v1.asset {
                            if (fields.has("tokenMint")) tokenMint = fields.getString("tokenMint")
                            else
                                nativeSol =
                                    io.github.brrenat.seekervault.request.v1.Asset.NativeSol
                                        .getDefaultInstance()
                        }
                }
        }

    private fun wallet(address: String) =
        SelectedWallet(
            address = address,
            network = WalletNetwork.Devnet,
            selectedAt = Instant.parse("2026-09-12T12:00:00Z"),
        )

    private fun decode(base64: String): ByteArray = Base64.getDecoder().decode(base64)

    private val requestRef =
        io.github.brrenat.seekervault.request.v1.requestRef {
            connectionId = "5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f"
            requestId = "f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19"
        }
}
