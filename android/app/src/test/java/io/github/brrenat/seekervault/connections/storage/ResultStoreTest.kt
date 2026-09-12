package io.github.brrenat.seekervault.connections.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.connections.SigningOutcome
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Stored answers on Robolectric: restarts, identical request IDs, and damaged files. */
@RunWith(AndroidJUnit4::class)
class ResultStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "files/results") }
    private val store by lazy { ResultStore(dir) }

    private fun result(
        connectionId: String,
        requestId: String = SAME_REQUEST,
        answer: Answer = Answer.Acknowledge,
        delivery: Delivery = Delivery.Waiting,
    ) =
        LocalResult(
            connectionId = connectionId,
            requestId = requestId,
            answer = answer,
            answeredAt = Instant.parse("2026-09-11T12:00:00.250Z"),
            request = FakeConnectionGateway.request(connectionId, requestId, "Deploy ✓ finished"),
            delivery = delivery,
        )

    @Test
    fun keepsEveryFieldAcrossARestart() {
        val waiting = result(A).copy(lastFailure = CheckOutcome.Unreachable)
        store.put(waiting)
        val reopened = ResultStore(dir)
        assertEquals(waiting, reopened.get(A, SAME_REQUEST))
        assertEquals("Deploy ✓ finished", reopened.get(A, SAME_REQUEST)?.request?.action?.ack?.text)
        val settled =
            waiting.copy(
                delivery = Delivery.Accepted,
                lastFailure = null,
                settledAt = Instant.parse("2026-09-19T08:30:00.125Z"),
            )
        store.put(settled)
        assertEquals(settled, ResultStore(dir).get(A, SAME_REQUEST))
    }

    @Test
    fun keepsAnApprovalAndWhateverTheWalletDidAcrossARestart() {
        val approved = result(A, answer = Answer.Approve)
        store.put(approved)
        assertEquals(approved, ResultStore(dir).get(A, SAME_REQUEST))
        for (outcome in
            listOf(
                SigningOutcome.Signed(ByteString.copyFrom(ByteArray(64) { it.toByte() })),
                SigningOutcome.Declined,
                SigningOutcome.Failed("The wallet is locked."),
            )) {
            val signed = approved.copy(approved = true, signing = outcome)
            store.put(signed)
            assertEquals(signed, ResultStore(dir).get(A, SAME_REQUEST))
        }
    }

    @Test
    fun readsAnAnswerStoredBeforeApprovalsExisted() {
        // A version 1 file: an acknowledgement or a rejection, with no approval in it.
        val old = result(A, answer = Answer.Reject, delivery = Delivery.Accepted)
        store.put(old)
        val file = File(File(dir, A), "$SAME_REQUEST.json")
        file.writeText(file.readText().replace("\"version\":2", "\"version\":1"))
        val read = ResultStore(dir).get(A, SAME_REQUEST)
        assertEquals(Answer.Reject, read?.answer)
        assertEquals(Delivery.Accepted, read?.delivery)
        assertEquals(false, read?.approved)
        assertNull(read?.signing)
    }

    @Test
    fun keepsIdenticalRequestIdsOnTwoConnectionsApart() {
        store.put(result(A, answer = Answer.Acknowledge))
        store.put(result(B, answer = Answer.Reject))
        assertEquals(Answer.Acknowledge, store.get(A, SAME_REQUEST)?.answer)
        assertEquals(Answer.Reject, store.get(B, SAME_REQUEST)?.answer)
        assertEquals(setOf(A, B), store.connectionIds())
        assertEquals(2, store.list().size)
        store.deleteConnection(A)
        assertNull(store.get(A, SAME_REQUEST))
        assertEquals(listOf(B), store.list().map { it.connectionId })
    }

    @Test
    fun skipsDamagedFilesAndFilesUnderAnotherName() {
        store.put(result(A))
        File(dir, "$A/$OTHER_REQUEST.json").writeText("{not json")
        File(dir, "$A/$THIRD_REQUEST.json")
            .writeText(
                File(dir, "$A/$SAME_REQUEST.json")
                    .readText()
                    .replace(SAME_REQUEST, THIRD_REQUEST)
                    .replace("2026-09-11T12:00:00.250Z", "yesterday")
            )
        // A's answer copied under B's name must not become B's.
        File(dir, B).mkdirs()
        File(dir, "$A/$SAME_REQUEST.json").copyTo(File(dir, "$B/$SAME_REQUEST.json"))
        assertEquals(listOf(A), store.list().map { it.connectionId })
        assertNull(store.get(B, SAME_REQUEST))
        assertNull(store.get(A, OTHER_REQUEST))
        assertNull(store.get(A, THIRD_REQUEST)) // a malformed timestamp
    }

    @Test
    fun namesFilesOnlyByUuids() {
        assertThrows(IllegalArgumentException::class.java) { store.put(result("../prefs")) }
        assertThrows(IllegalArgumentException::class.java) {
            store.put(result(A, requestId = "../../x"))
        }
        assertNull(store.get(A, "../../x"))
    }

    private companion object {
        const val A = "0b8e2b1c-3f4d-4e5a-9b6c-7d8e9f0a1b2c"
        const val B = "5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f"
        const val SAME_REQUEST = "7c6b5a49-3827-4615-a0b9-c8d7e6f5a4b3"
        const val OTHER_REQUEST = "de03846e-d435-4705-b2e3-ec67da539f12"
        const val THIRD_REQUEST = "1f0e2d3c-4b5a-4698-8776-5a4b3c2d1e0f"
    }
}
