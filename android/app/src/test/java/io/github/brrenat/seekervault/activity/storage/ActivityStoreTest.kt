package io.github.brrenat.seekervault.activity.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.CONNECTION
import io.github.brrenat.seekervault.activity.MINT
import io.github.brrenat.seekervault.activity.OTHER_REQUEST
import io.github.brrenat.seekervault.activity.REQUEST
import io.github.brrenat.seekervault.activity.record
import io.github.brrenat.seekervault.request.v1.Network
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The owner's history on disk: restarts, damaged files, and what counts as the same record. */
@RunWith(AndroidJUnit4::class)
class ActivityStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "files/activity") }
    private val store by lazy { ActivityStore(dir) }

    @Test
    fun keepsEveryFieldAcrossARestart() {
        val confirmed = record(detail = "The transfer succeeded on chain in slot 298471553.")
        store.put(confirmed)
        // A new store on the same directory is what a restart is: nothing is held in memory.
        assertEquals(confirmed, ActivityStore(dir).get(CONNECTION, REQUEST))
        assertEquals(listOf(confirmed), ActivityStore(dir).list())
    }

    @Test
    fun keepsATokenTransferOnItsOwnClusterAndAMessageWithNoClusterAtAll() {
        val token = record(network = Network.NETWORK_MAINNET, mint = MINT)
        val message =
            record(
                requestId = OTHER_REQUEST,
                kind = ActivityKind.MessageSignature,
                outcome = ActivityOutcome.MessageSigned,
                checkedWith = null,
            )
        store.put(token)
        store.put(message)
        val reopened = ActivityStore(dir)
        assertEquals(Network.NETWORK_MAINNET, reopened.get(CONNECTION, REQUEST)?.transfer?.network)
        assertEquals(MINT, reopened.get(CONNECTION, REQUEST)?.transfer?.mint)
        val stored = reopened.get(CONNECTION, OTHER_REQUEST)
        assertEquals(message, stored)
        assertNull(stored?.transfer)
        // Whatever a message carries, it is never a transaction.
        assertEquals(false, stored?.signatureIsTransaction)
    }

    @Test
    fun replacesTheSameRequestsRecordRatherThanAddingAnother() {
        val sent = record(outcome = ActivityOutcome.Sent, checkedWith = null)
        store.put(sent)
        val confirmed = sent.copy(outcome = ActivityOutcome.Confirmed, checkedWith = "rpc.example")
        store.put(confirmed)
        assertEquals(listOf(confirmed), store.list())
    }

    @Test
    fun refusesToWriteOverARecordOfSomethingElse() {
        // The request ID is the same, and the payment isn't: another wallet, another cluster, or
        // another asset. Whatever produced that, it is not an update to what is stored, and the
        // stored record stands.
        val first = record()
        store.put(first)
        for (other in
            listOf(
                first.copy(transfer = first.transfer?.copy(wallet = "another-wallet")),
                first.copy(transfer = first.transfer?.copy(network = Network.NETWORK_MAINNET)),
                first.copy(transfer = first.transfer?.copy(mint = MINT)),
            )) {
            assertEquals(first, store.put(other))
            assertEquals(first, store.get(CONNECTION, REQUEST))
        }
    }

    @Test
    fun skipsADamagedFileInsteadOfLosingTheRest() {
        store.put(record())
        val other =
            record(requestId = OTHER_REQUEST, answeredAt = Instant.parse("2026-09-12T09:00:00Z"))
        store.put(other)
        File(dir, "$CONNECTION/$REQUEST.json").writeText("{ not json")
        assertNull(store.get(CONNECTION, REQUEST))
        assertEquals(listOf(other), store.list())
    }

    @Test
    fun ordersNewestFirst() {
        val older = record(answeredAt = Instant.parse("2026-09-10T09:00:00Z"))
        val newer =
            record(requestId = OTHER_REQUEST, answeredAt = Instant.parse("2026-09-12T09:00:00Z"))
        store.put(older)
        store.put(newer)
        assertEquals(listOf(newer, older), store.list())
    }

    @Test
    fun clearsEverythingWhenTheOwnerAsks() {
        store.put(record())
        store.put(record(requestId = OTHER_REQUEST))
        assertTrue(store.list().isNotEmpty())
        store.clear()
        assertEquals(emptyList<Any>(), store.list())
        assertEquals(emptySet<String>(), store.connectionIds())
    }

    @Test
    fun takesOnlyIdsThatCanNameAFile() {
        assertThrows(IllegalArgumentException::class.java) {
            store.put(record().copy(connectionId = "../escape"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.put(record(requestId = "../escape"))
        }
        assertNull(store.get("../escape", REQUEST))
        assertNull(store.get(CONNECTION, "../escape"))
    }
}
