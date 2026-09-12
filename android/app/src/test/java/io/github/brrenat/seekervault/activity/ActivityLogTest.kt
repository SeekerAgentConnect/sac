package io.github.brrenat.seekervault.activity

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.activity.storage.ActivityStore
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.ApprovedTransaction
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.SigningOutcome
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.RequestState
import java.io.File
import java.io.IOException
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * What the history records, and what it refuses to record. A transfer goes through several answers
 * on its way, and they are one record; an approval that never committed is none.
 */
@RunWith(AndroidJUnit4::class)
class ActivityLogTest {
    @get:Rule val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "files/activity") }
    private val store by lazy { ActivityStore(dir) }
    private val now = Instant.parse("2026-09-11T12:05:00Z")
    private val log by lazy { ActivityLog(store) { now } }

    @Test
    fun theHistoryIsNotKnownUntilItHasBeenReadOffTheDisk() {
        // Anything that counts what this phone has done has to tell "nothing here" from "nobody
        // has looked". An empty list before a read is the second, and a daily threshold measured
        // against it would report the day as empty (docs/policy.md#counters).
        assertFalse(log.loaded.value)
        assertEquals(emptyList<ActivityRecord>(), log.records.value)

        log.load()

        assertTrue(log.loaded.value)
    }

    @Test
    fun aReadThatFailsLeavesTheHistoryUnknownRatherThanEmpty() {
        File(folder.root, "files").mkdirs()
        // A file where the directory has to go: the history is there and can't be listed.
        dir.writeText("in the way")

        assertThrows(IOException::class.java) { log.load() }

        assertFalse(log.loaded.value)
    }

    @Test
    fun clearingIsAHistoryTheOwnerKnowsIsEmpty() {
        log.load()
        log.record(result(transferRequest()), connection())

        log.clear()

        assertTrue(log.loaded.value)
        assertEquals(emptyList<ActivityRecord>(), log.records.value)
    }

    @Test
    fun keepsTheAssessmentTheOwnerReadThroughEveryLaterWriteOfTheRecord() {
        log.reviewed(RequestKey(CONNECTION, REQUEST), reviewedPolicy())
        log.record(
            result(transferRequest(), delivery = Delivery.Waiting),
            connection(),
        )
        assertEquals(reviewedPolicy(), log.records.value.single().policy)

        // A status checked later rewrites the record. What the owner read when they answered is
        // not something the chain's answer knows, so it must not take it away.
        val reopened = ActivityLog(ActivityStore(dir)) { now }
        reopened.load()
        reopened.record(
            result(
                transferRequest(state = RequestState.REQUEST_STATE_CONFIRMED),
                signing = SigningOutcome.Sent(signatureBytes()),
            ),
            connection(),
        )
        assertEquals(ActivityOutcome.Confirmed, reopened.records.value.single().outcome)
        assertEquals(reviewedPolicy(), reopened.records.value.single().policy)
    }

    @Test
    fun recordsNoAssessmentForARequestNoneWasNotedFor() {
        log.record(result(ackRequest(), answer = Answer.Acknowledge), connection())
        assertNull(log.records.value.single().policy)
    }

    @Test
    fun recordsOneTransferThroughEveryStepItTakes() {
        val approved =
            ApprovedTransaction(
                version = 2,
                contentHash = ByteString.copyFrom(ByteArray(32)),
                transaction = ByteString.copyFrom(ByteArray(101)),
            )
        // The sidecar accepted the approval: this is the commit point, and the first record.
        log.record(
            result(
                transferRequest(state = RequestState.REQUEST_STATE_PROCESSING),
                delivery = Delivery.Waiting,
                approvedTransaction = approved,
            ),
            connection(),
        )
        assertEquals(1, log.records.value.size)
        assertEquals(ActivityOutcome.Waiting, log.records.value.single().outcome)
        assertEquals(2, log.records.value.single().transfer?.preparedVersion)

        val signature = signatureBytes()
        log.record(
            result(
                transferRequest(
                    state = RequestState.REQUEST_STATE_SUBMITTED,
                    signature = signature,
                ),
                signing = SigningOutcome.Sent(signature),
                approvedTransaction = approved,
            ),
            connection(),
        )
        assertEquals(1, log.records.value.size)
        assertEquals(ActivityOutcome.Sent, log.records.value.single().outcome)

        log.record(
            result(
                transferRequest(
                    state = RequestState.REQUEST_STATE_CONFIRMED,
                    signature = signature,
                    endpoint = "api.devnet.solana.com",
                    detail = "The transfer succeeded on chain in slot 298471553.",
                ),
                signing = SigningOutcome.Sent(signature),
                approvedTransaction = approved,
            ),
            connection(),
        )
        val record = log.records.value.single()
        assertEquals(ActivityOutcome.Confirmed, record.outcome)
        assertEquals("api.devnet.solana.com", record.checkedWith)
        assertEquals("The transfer succeeded on chain in slot 298471553.", record.detail)
        assertTrue(record.signatureIsTransaction)
        assertNotNull(explorerUrl(record))
        // The reviewed terms, in the base units the transaction carried.
        assertEquals("1500000", record.transfer?.amount)
        assertEquals(RECIPIENT, record.transfer?.recipient)
        assertEquals(Network.NETWORK_DEVNET, record.transfer?.network)
        assertEquals("Hermes", record.source)
        assertEquals("sidecar.example:8443", record.serverHost)
    }

    @Test
    fun theSameResultTwiceIsStillOneRecord() {
        val signature = signatureBytes()
        val sent =
            result(
                transferRequest(
                    state = RequestState.REQUEST_STATE_SUBMITTED,
                    signature = signature,
                ),
                signing = SigningOutcome.Sent(signature),
            )
        repeat(3) { log.record(sent, connection()) }
        assertEquals(1, log.records.value.size)
        assertEquals(1, ActivityStore(dir).list().size)
        assertEquals(signature.toByteArray().size, 64)
    }

    @Test
    fun survivesARestartAndKeepsTheSameRecord() {
        val signature = signatureBytes()
        log.record(
            result(
                transferRequest(
                    state = RequestState.REQUEST_STATE_SUBMITTED,
                    signature = signature,
                ),
                signing = SigningOutcome.Sent(signature),
            ),
            connection(),
        )
        // A new log on the same directory, as after the app is killed and opened again.
        val reopened = ActivityLog(ActivityStore(dir)) { now }
        assertEquals(emptyList<ActivityRecord>(), reopened.records.value)
        reopened.load()
        assertEquals(log.records.value, reopened.records.value)

        // The confirmation arrives afterwards, and lands on that same record.
        reopened.record(
            result(
                transferRequest(
                    state = RequestState.REQUEST_STATE_CONFIRMED,
                    signature = signature,
                    endpoint = "api.devnet.solana.com",
                ),
                signing = SigningOutcome.Sent(signature),
            ),
            connection(),
        )
        assertEquals(1, reopened.records.value.size)
        assertEquals(ActivityOutcome.Confirmed, reopened.records.value.single().outcome)
    }

    @Test
    fun recordsAMessageSignatureAsASignatureAndNeverAsAPayment() {
        log.record(
            result(messageRequest(), signing = SigningOutcome.Signed(signatureBytes(3))),
            connection(),
        )
        val record = log.records.value.single()
        assertEquals(ActivityKind.MessageSignature, record.kind)
        assertEquals(ActivityOutcome.MessageSigned, record.outcome)
        assertNotNull(record.signature)
        assertEquals(false, record.signatureIsTransaction)
        assertNull(record.transfer)
        assertNull(explorerUrl(record))
    }

    @Test
    fun recordsAnAcknowledgementAndARejection() {
        log.record(
            result(ackRequest(), answer = Answer.Acknowledge, approved = false),
            connection(),
        )
        assertEquals(ActivityOutcome.Acknowledged, log.records.value.single().outcome)
        assertEquals(ActivityKind.Acknowledgement, log.records.value.single().kind)

        log.record(
            result(
                transferRequest(state = RequestState.REQUEST_STATE_REJECTED),
                answer = Answer.Reject,
                approved = false,
            ),
            connection(),
        )
        assertEquals(
            ActivityOutcome.Rejected,
            log.records.value.first { it.requestId == REQUEST }.outcome,
        )
    }

    @Test
    fun recordsNothingForAnApprovalTheServerNeverAccepted() {
        // The wallet was never opened for this one: the sidecar refused the approval, the answer is
        // removed again, and the request goes back to waiting for the owner. There is nothing that
        // happened, so there is nothing to have a record of.
        log.record(
            result(
                transferRequest(state = RequestState.REQUEST_STATE_PENDING),
                delivery = Delivery.Waiting,
                approved = false,
                approvedTransaction =
                    ApprovedTransaction(
                        version = 1,
                        contentHash = ByteString.copyFrom(ByteArray(32)),
                        transaction = ByteString.copyFrom(ByteArray(101)),
                    ),
            ),
            connection(),
        )
        assertEquals(emptyList<ActivityRecord>(), log.records.value)
        assertEquals(emptyList<ActivityRecord>(), ActivityStore(dir).list())
    }

    @Test
    fun saysNobodyKnowsWhenNobodyKnows() {
        log.record(
            result(
                transferRequest(state = RequestState.REQUEST_STATE_UNKNOWN),
                signing =
                    SigningOutcome.Unresolved(
                        "The wallet's answer never reached this phone, so it never learned whether the transaction was sent."
                    ),
            ),
            connection(),
        )
        val record = log.records.value.single()
        assertEquals(ActivityOutcome.Unknown, record.outcome)
        assertNull(record.signature)
        assertNull(explorerUrl(record))
        assertTrue(record.detail.orEmpty().contains("never learned"))
    }

    @Test
    fun keepsAnAnswerThatNeverReachedTheServer() {
        log.record(
            result(ackRequest(), answer = Answer.Acknowledge, delivery = Delivery.Undeliverable),
            connection(),
        )
        assertEquals(ActivityOutcome.NotDelivered, log.records.value.single().outcome)
    }

    @Test
    fun clearsEverythingWhenTheOwnerAsks() {
        log.record(result(ackRequest(), answer = Answer.Acknowledge), connection())
        assertEquals(1, log.records.value.size)
        log.clear()
        assertEquals(emptyList<ActivityRecord>(), log.records.value)
        assertEquals(emptyList<ActivityRecord>(), ActivityStore(dir).list())
    }

    @Test
    fun fallsBackToTheConnectionIdWhenTheConnectionIsGone() {
        // The connection was removed, and the record of what it asked for stays.
        log.record(result(ackRequest(), answer = Answer.Acknowledge), null)
        assertEquals(CONNECTION, log.records.value.single().source)
        assertEquals("", log.records.value.single().serverHost)
    }
}
