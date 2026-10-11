package io.github.brrenat.seekervault.transactions

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.wallet.decodeBase58
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The approved message against what the chain holds, on a real stake whose wallet added a compute
 * budget and Lighthouse assertions before sending it (`fixtures/transactions/wallet-added.json`).
 */
@RunWith(AndroidJUnit4::class)
class ApprovedMessageTest {
    private val fixture =
        JSONObject(
            checkNotNull(javaClass.getResourceAsStream("/transactions/wallet-added.json")) {
                    "fixtures/transactions/wallet-added.json is missing"
                }
                .use { it.readBytes().decodeToString() }
        )
    private val approved = Base64.getDecoder().decode(fixture.getString("approvedMessage"))
    private val sent =
        checkNotNull(
            messageBytes(Base64.getDecoder().decode(fixture.getString("onChainTransaction")))
        )

    @Test
    fun whatTheWalletAddedOfItsOwnIsStillTheApprovedTransaction() {
        assertFalse(approved.contentEquals(sent))
        assertTrue(isApprovedMessage(approved, sent))
        assertTrue(isApprovedMessage(approved, approved))
    }

    @Test
    fun anyOtherChangeIsAnotherTransaction() {
        // The stake's amount, 1 SKR in base units, changed by one unit.
        val amount = byteArrayOf(0x40, 0x42, 0x0f, 0, 0, 0, 0, 0)
        val at = sent.indexOf(amount)
        assertTrue(at > 0)
        assertFalse(isApprovedMessage(approved, sent.copyOf().also { it[at] = 0x41 }))

        // Another blockhash.
        val blockhash = decoded(approved).recentBlockhash
        val other =
            sent.copyOf().also { bytes ->
                val hash = checkNotNull(decodeBase58(blockhash))
                bytes[bytes.indexOf(hash)] = (hash[0] + 1).toByte()
            }
        assertFalse(isApprovedMessage(approved, other))

        // One more account read-only: the header's last count, which moves writability.
        assertFalse(
            isApprovedMessage(approved, sent.copyOf().also { it[3] = (it[3] + 1).toByte() })
        )

        // And bytes that are not a message at all.
        assertFalse(isApprovedMessage(approved, byteArrayOf(1, 2, 3)))
    }

    @Test
    fun anInstructionOfAnyOtherProgramIsNotTheWallets() {
        val read = decoded(sent)
        assertNotNull(read.instructions.firstOrNull { read.programOf(it) == LIGHTHOUSE_PROGRAM })
        // What the approved message itself holds is not something a wallet may leave out.
        assertFalse(isApprovedMessage(sent, approved.copyOf(approved.size - 1)))
    }

    private fun decoded(message: ByteArray): DecodedTransaction =
        (decodeTransaction(byteArrayOf(0) + message, resolvable = true) as DecodeResult.Decoded)
            .transaction

    private fun ByteArray.indexOf(part: ByteArray): Int =
        (0..size - part.size).firstOrNull { start ->
            part.indices.all { this[start + it] == part[it] }
        } ?: -1
}
