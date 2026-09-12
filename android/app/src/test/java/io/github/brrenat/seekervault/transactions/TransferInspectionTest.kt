package io.github.brrenat.seekervault.transactions

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.request.v1.Asset
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.action
import io.github.brrenat.seekervault.request.v1.actionRequest
import io.github.brrenat.seekervault.request.v1.asset
import io.github.brrenat.seekervault.request.v1.preparedTransaction
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.request.v1.transferAction
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The checks the shared fixtures can't make (SAW-020). Every fixture is built for the wallet its
 * request names, so the cases where the phone's own wallet is the thing that doesn't fit are here.
 */
@RunWith(AndroidJUnit4::class)
class TransferInspectionTest {
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

    private val sol = case("sol_transfer")
    private val fields = sol.getJSONObject("request")

    private fun request(
        wallet: String = fields.getString("wallet"),
        network: Network = Network.NETWORK_DEVNET,
    ) = actionRequest {
        ref = requestRef {
            connectionId = "5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f"
            requestId = "f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19"
        }
        action = action {
            transfer = transferAction {
                this.wallet = wallet
                this.network = network
                recipient = fields.getString("recipient")
                amount = fields.getString("amount")
                asset = asset { nativeSol = Asset.NativeSol.getDefaultInstance() }
            }
        }
    }

    private fun prepared(bytes: ByteArray = decode(sol.getString("transaction"))) =
        preparedTransaction {
            version = 1
            transaction = ByteString.copyFrom(bytes)
            contentHash = ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(bytes))
        }

    private fun wallet(
        address: String = fields.getString("wallet"),
        network: WalletNetwork = WalletNetwork.Devnet,
    ) =
        SelectedWallet(
            address = address,
            network = network,
            selectedAt = Instant.parse("2026-09-12T12:00:00Z"),
        )

    private fun decode(base64: String) = Base64.getDecoder().decode(base64)

    @Test
    fun acceptsTheTransferItWasBuiltFor() {
        val inspection = inspectTransfer(request(), prepared(), wallet())
        assertEquals(Verdict.Verified, inspection.verdict)
        assertEquals(emptyList<Finding>(), inspection.findings)
        assertTrue(inspection.approvable)
        assertEquals(1, inspection.version)
    }

    @Test
    fun refusesItWithNoWalletConnected() {
        val inspection = inspectTransfer(request(), prepared(), null)
        assertEquals(Verdict.Invalid, inspection.verdict)
        assertTrue(Finding.NoWallet in inspection.findings)
        assertTrue(!inspection.approvable)
    }

    @Test
    fun refusesItWhenTheConnectedWalletIsAnotherOne() {
        val other = case("changed_recipient").getJSONObject("request").getString("recipient")
        val inspection = inspectTransfer(request(), prepared(), wallet(address = other))
        assertEquals(Verdict.Invalid, inspection.verdict)
        assertTrue(Finding.OtherWallet in inspection.findings)
    }

    @Test
    fun refusesItWhenTheWalletIsOnAnotherNetwork() {
        val inspection =
            inspectTransfer(request(), prepared(), wallet(network = WalletNetwork.Mainnet))
        assertEquals(Verdict.Invalid, inspection.verdict)
        assertTrue(Finding.NetworkMismatch in inspection.findings)
    }

    @Test
    fun refusesARequestThatIsNotATransfer() {
        val ack = actionRequest {
            ref = requestRef { requestId = "f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19" }
        }
        val inspection = inspectTransfer(ack, prepared(), wallet())
        assertEquals(Verdict.Invalid, inspection.verdict)
        assertEquals(listOf(Finding.NoTransfer), inspection.findings)
        assertEquals(null, inspection.facts)
    }

    @Test
    fun neverReadsThePreparedVersionFromAnythingButTheResponse() {
        val inspection =
            inspectTransfer(
                request(),
                preparedTransaction {
                    version = 7
                    transaction = ByteString.copyFrom(decode(sol.getString("transaction")))
                    contentHash = ByteString.copyFrom(decode(sol.getString("contentHash")))
                },
                wallet(),
            )
        assertEquals(7, inspection.version)
        assertEquals(Verdict.Verified, inspection.verdict)
    }

    @Test
    fun writesBaseUnitsWithTheirDecimalPointExactly() {
        assertEquals("1.5", formatBaseUnits(1_500_000UL, 6))
        assertEquals("0.000001", formatBaseUnits(1UL, 6))
        assertEquals("2.5", formatBaseUnits(2_500_000_000UL, 9))
        assertEquals("0", formatBaseUnits(0UL, 9))
        assertEquals("7", formatBaseUnits(7UL, 0))
        // The largest u64 has no exact double, so a formatter that went through one would be wrong
        // here by thousands.
        assertEquals("18446744073709.551615", formatBaseUnits(ULong.MAX_VALUE, 6))
        assertEquals("18446744073709551615", formatBaseUnits(ULong.MAX_VALUE, 0))
    }
}
