package io.github.brrenat.seekervault.confirmations

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityLog
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.CONNECTION
import io.github.brrenat.seekervault.activity.OTHER_REQUEST
import io.github.brrenat.seekervault.activity.REQUEST
import io.github.brrenat.seekervault.activity.WALLET
import io.github.brrenat.seekervault.activity.connection
import io.github.brrenat.seekervault.activity.operationRecord
import io.github.brrenat.seekervault.activity.result
import io.github.brrenat.seekervault.activity.storage.ActivityStore
import io.github.brrenat.seekervault.activity.transferRequest
import io.github.brrenat.seekervault.confirmations.storage.TrackingStore
import io.github.brrenat.seekervault.connections.ApprovedTransaction
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.SigningOutcome
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.solana.SolanaException
import io.github.brrenat.seekervault.solana.SolanaProblem
import io.github.brrenat.seekervault.transactions.messageBytes
import io.github.brrenat.seekervault.transactions.recentBlockhashOf
import io.github.brrenat.seekervault.wallet.encodeBase58
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The device-side confirmation tracker (SEE-165): what counts as an answer, what doesn't, and that
 * nothing it does can roll a record back, recreate one the owner cleared, or reach a wallet.
 *
 * The chain is a fake with a scripted answer per call, so every case in the ticket's matrix is a
 * deterministic test rather than a hope about a public cluster.
 */
@RunWith(AndroidJUnit4::class)
class ConfirmationTrackerTest {
    @get:Rule val folder = TemporaryFolder()

    private var now = SENT_AT
    private val activityStore by lazy { ActivityStore(File(folder.root, "activity")) }
    private val log by lazy { ActivityLog(activityStore) { now } }
    private val trackingStore by lazy { TrackingStore(File(folder.root, "confirmations")) }
    private val chain = FakeChain()
    private val tracker by lazy {
        ConfirmationTracker(
                trackingStore,
                log,
                ChainEndpoints(listOf(ChainEndpoint("https://rpc.example.com/KEY", null))) {
                    chain
                },
            ) {
                now
            }
            .also { log.onClear(it::clear) }
    }

    private val key = RequestKey(CONNECTION, REQUEST)

    @Test
    fun aDelayedConfirmationIsFollowedToFinalizedAndVerifiedAgainstTheApprovedMessage() =
        runBlocking {
            send()
            // Not visible yet, and far too soon for any conclusion about expiry.
            chain.status = null
            advance(Duration.ofSeconds(2))
            tracker.checkDue()
            assertEquals(ChainState.Checking, check().state)
            assertEquals(ChainReason.NotYetVisible, check().reason)
            assertEquals(0, chain.blockhashCalls)
            assertEquals(ActivityOutcome.Sent, record().outcome)

            // Confirmed: the body is fetched and compared before anything is settled.
            chain.status = SignatureStatus(100, ChainLevel.Confirmed, null)
            chain.body = ChainTransaction(100, signedWire(), null)
            advance(Duration.ofMinutes(1))
            tracker.checkDue()
            assertEquals(ChainState.Confirmed, check().state)
            assertEquals(ChainLevel.Confirmed, check().level)
            assertEquals(ChainReason.AwaitingFinality, check().reason)
            assertEquals("rpc.example.com", check().host)
            assertEquals(ActivityOutcome.Confirmed, record().outcome)
            assertNotNull(check().nextCheckAt)

            chain.status = SignatureStatus(132, ChainLevel.Finalized, null)
            advance(Duration.ofMinutes(1))
            tracker.checkDue()
            assertEquals(ChainLevel.Finalized, check().level)
            assertNull(check().nextCheckAt)
            assertNull(tracker.nextDue())
            // The host, never the URL: the path carried a key.
            assertFalse(activityStore.get(CONNECTION, REQUEST).toString().contains("KEY"))
        }

    @Test
    fun processedIsNotAResult() = runBlocking {
        send()
        chain.status = SignatureStatus(100, ChainLevel.Processed, null)
        chain.body = ChainTransaction(100, signedWire(), null)
        advance(Duration.ofSeconds(3))
        tracker.checkDue()
        assertEquals(ChainState.Checking, check().state)
        assertEquals(ChainLevel.Processed, check().level)
        assertEquals(ChainReason.ProcessedOnly, check().reason)
        assertEquals(0, chain.bodyCalls)
        assertEquals(ActivityOutcome.Sent, record().outcome)
    }

    @Test
    fun aFinalizedSuccessSettlesAtOnce() = runBlocking {
        send()
        chain.status = SignatureStatus(7, ChainLevel.Finalized, null)
        chain.body = ChainTransaction(7, signedWire(), null)
        advance(Duration.ofSeconds(3))
        tracker.checkDue()
        assertEquals(ChainState.Confirmed, check().state)
        assertEquals(ChainLevel.Finalized, check().level)
        assertEquals(7L, check().slot)
        assertFalse(trackingStore.get(key)!!.unfinished)
    }

    @Test
    fun aChainErrorIsAFailureOnChainAndSpendsNothingButTheFee() = runBlocking {
        send()
        chain.status = SignatureStatus(9, ChainLevel.Finalized, """{"InstructionError":[0,"X"]}""")
        chain.body = ChainTransaction(9, signedWire(), """{"InstructionError":[0,"X"]}""")
        advance(Duration.ofSeconds(3))
        tracker.checkDue()
        assertEquals(ChainState.Failed, check().state)
        assertEquals("""{"InstructionError":[0,"X"]}""", check().chainError)
        assertEquals(ActivityOutcome.ChainFailed, record().outcome)
    }

    @Test
    fun aDifferentTransactionUnderTheSignatureSettlesNothing() = runBlocking {
        send()
        chain.status = SignatureStatus(9, ChainLevel.Finalized, null)
        chain.body = ChainTransaction(9, signedWire(blockhashFill = 99), null)
        advance(Duration.ofSeconds(3))
        tracker.checkDue()
        assertEquals(ChainState.Unresolved, check().state)
        assertEquals(ChainReason.Mismatch, check().reason)
        assertNull(check().nextCheckAt)
        // Never a success: the record stays sent.
        assertEquals(ActivityOutcome.Sent, record().outcome)
    }

    @Test
    fun aStatusWithoutItsTransactionBodyIsInconclusive() = runBlocking {
        send()
        chain.status = SignatureStatus(9, ChainLevel.Confirmed, null)
        chain.body = null
        advance(Duration.ofSeconds(3))
        tracker.checkDue()
        assertEquals(ChainState.Checking, check().state)
        assertEquals(ChainReason.BodyNotServed, check().reason)
        assertEquals(ActivityOutcome.Sent, record().outcome)
    }

    @Test
    fun timeoutsRateLimitsAndMalformedAnswersAreRetriedWithBackoffAndThenRecover() = runBlocking {
        send()
        advance(Duration.ofSeconds(3))
        chain.failure = SolanaException(SolanaProblem.Unreachable, "timeout")
        tracker.checkDue()
        assertEquals(ChainReason.Unreachable, check().reason)
        val first = checkNotNull(check().nextCheckAt)

        now = first
        chain.failure = SolanaException(SolanaProblem.RateLimited)
        tracker.checkDue()
        assertEquals(ChainReason.RateLimited, check().reason)
        val second = checkNotNull(check().nextCheckAt)
        // Backoff grows.
        assertTrue(Duration.between(now, second) > Duration.between(SENT_AT.plusSeconds(3), first))

        now = second
        chain.failure = SolanaException(SolanaProblem.Unusable, "not json")
        tracker.checkDue()
        assertEquals(ChainReason.Unusable, check().reason)
        assertEquals(ChainState.Checking, check().state)
        assertEquals(ActivityOutcome.Sent, record().outcome)

        now = checkNotNull(check().nextCheckAt)
        chain.failure = null
        chain.status = SignatureStatus(9, ChainLevel.Finalized, null)
        chain.body = ChainTransaction(9, signedWire(), null)
        tracker.checkDue()
        assertEquals(ChainState.Confirmed, check().state)
        assertEquals(0, trackingStore.get(key)!!.attempts)
    }

    @Test
    fun restoredConnectivityMakesOnlyUnreachableChecksDueAtOnce() = runBlocking {
        send()
        chain.failure = SolanaException(SolanaProblem.Unreachable)
        // Deep into the backoff, where the next attempt is minutes away.
        repeat(8) {
            now = checkNotNull(tracker.nextDue())
            tracker.checkDue()
        }
        val attempts = trackingStore.get(key)!!.attempts
        assertTrue(checkNotNull(tracker.nextDue()) > now.plusSeconds(60))

        assertTrue(tracker.connectivityRestored())
        assertEquals(now, tracker.nextDue())
        assertEquals(ChainReason.Unreachable, check().reason)
        assertEquals(attempts, trackingStore.get(key)!!.attempts)

        chain.failure = null
        chain.status = SignatureStatus(9, ChainLevel.Finalized, null)
        chain.body = ChainTransaction(9, signedWire(), null)
        tracker.checkDue()
        assertEquals(ChainState.Confirmed, check().state)
    }

    @Test
    fun restoredConnectivityLeavesAnEndpointThatAnsweredOnItsBackoff() = runBlocking {
        send()
        now = checkNotNull(tracker.nextDue())
        chain.failure = SolanaException(SolanaProblem.RateLimited)
        tracker.checkDue()
        val next = checkNotNull(tracker.nextDue())

        assertFalse(tracker.connectivityRestored())
        assertEquals(next, tracker.nextDue())
    }

    @Test
    fun automaticChecksStopAfterTheirBudgetAndSaySo() = runBlocking {
        send()
        chain.failure = SolanaException(SolanaProblem.Unreachable)
        repeat(ConfirmationTracker.MAX_ATTEMPTS) {
            now = tracker.nextDue() ?: return@repeat
            tracker.checkDue()
        }
        assertEquals(ChainState.Unresolved, check().state)
        assertEquals(ChainReason.GaveUp, check().reason)
        assertNull(tracker.nextDue())
        // The owner can still ask, and an answer then settles it.
        chain.failure = null
        chain.status = SignatureStatus(9, ChainLevel.Finalized, null)
        chain.body = ChainTransaction(9, signedWire(), null)
        tracker.check(key)
        assertEquals(ChainState.Confirmed, check().state)
    }

    @Test
    fun expiryIsProvenOnlyByAnInvalidBlockhashAndAnEmptyLedgerSearch() = runBlocking {
        send()
        chain.status = null
        // Too soon: no conclusion, and the blockhash isn't even asked about.
        advance(Duration.ofMinutes(1))
        tracker.check(key)
        assertEquals(ChainState.Checking, check().state)
        assertEquals(0, chain.blockhashCalls)

        // Late enough, but the blockhash can still be used: it can still land.
        advance(Duration.ofMinutes(5))
        chain.blockhashValid = true
        tracker.check(key)
        assertEquals(ChainState.Checking, check().state)
        assertEquals(ChainReason.NotYetVisible, check().reason)
        assertEquals(0, chain.historyCalls)

        // Now it can't, and the ledger has nothing: it never landed.
        chain.blockhashValid = false
        tracker.check(key)
        assertEquals(1, chain.historyCalls)
        assertEquals(ChainState.Expired, check().state)
        assertEquals(ActivityOutcome.ChainFailed, record().outcome)
        assertNull(check().nextCheckAt)
    }

    @Test
    fun aLandedTransactionMissingFromAPrunedLedgerIsNeverCalledExpired() = runBlocking {
        send()
        // Resumed days later. The transaction landed, but this endpoint has pruned the ledger
        // since, and its long-term storage (if any) had nothing: the search comes back empty.
        advance(Duration.ofDays(3))
        chain.status = null
        chain.blockhashValid = false
        chain.history = null
        chain.retainedSince = now.minus(Duration.ofDays(1))
        tracker.check(key)

        assertEquals(ChainState.Checking, check().state)
        assertEquals(ChainReason.HistoryNotRetained, check().reason)
        assertEquals(ActivityOutcome.Sent, record().outcome)
        assertNotNull(check().nextCheckAt)

        // An endpoint that can't say how far back it reaches proves nothing either.
        chain.retainedSince = null
        tracker.check(key)
        assertEquals(ChainState.Checking, check().state)
        assertEquals(ChainReason.HistoryNotRetained, check().reason)

        // Another endpoint still has it: it is found, verified and settled.
        chain.history = SignatureStatus(5, ChainLevel.Finalized, null)
        chain.body = ChainTransaction(5, signedWire(), null)
        tracker.check(key)
        assertEquals(ChainState.Confirmed, check().state)
        assertEquals(ActivityOutcome.Confirmed, record().outcome)
    }

    @Test
    fun aCaptureTheProcessStoppedBeforeSigningIsRepairedFromTheStoredAnswer() = runBlocking {
        // The direct path: the capture is written, the wallet answers, the answer is stored — and
        // the process stops before the tracker is told the signature.
        val direct =
            result(
                transferRequest(),
                signing = SigningOutcome.Sent(sig()),
                approvedTransaction = approved(),
            )
        tracker.expect(submission())
        log.record(direct, connection())
        assertNull(trackingStore.get(key)!!.signature)

        // The next start reconciles the stored answer into the capture it was meant for.
        tracker.load()
        tracker.backfill(listOf(direct), log.records.value)

        val repaired = trackingStore.get(key)!!
        assertEquals(encodeBase58(SIGNATURE), repaired.signature)
        assertEquals(ANSWERED, repaired.submittedAt)
        assertEquals(ChainState.Checking, repaired.check.state)
        assertEquals(now, tracker.nextDue())
        chain.status = SignatureStatus(7, ChainLevel.Finalized, null)
        chain.body = ChainTransaction(7, signedWire(), null)
        tracker.checkDue()
        assertEquals(ChainState.Confirmed, check().state)
    }

    @Test
    fun aFeedOperationsStoredSignatureRepairsItsCaptureAndNothingElse() = runBlocking {
        tracker.expect(
            Submission(
                key,
                TrackingOrigin.Operation,
                Network.NETWORK_DEVNET,
                WALLET,
                unsignedWire(),
            )
        )
        val settledAt = SENT_AT.minusSeconds(30)
        tracker.recovered(key, SIGNATURE, settledAt)
        assertEquals(encodeBase58(SIGNATURE), trackingStore.get(key)!!.signature)
        assertEquals(settledAt, trackingStore.get(key)!!.submittedAt)
        assertEquals(now, tracker.nextDue())

        // Told again, with anything, it changes nothing: the first signature recorded stands.
        tracker.recovered(key, ByteArray(64) { 1 }, SENT_AT)
        assertEquals(encodeBase58(SIGNATURE), trackingStore.get(key)!!.signature)
        assertEquals(settledAt, trackingStore.get(key)!!.submittedAt)
        // And there is nothing to repair where nothing was captured.
        tracker.recovered(RequestKey(CONNECTION, OTHER_REQUEST), SIGNATURE, SENT_AT)
        assertNull(trackingStore.get(RequestKey(CONNECTION, OTHER_REQUEST)))
    }

    @Test
    fun anOldSignatureIsFoundByALedgerSearchAndStillVerified() = runBlocking {
        send()
        advance(Duration.ofHours(2))
        chain.status = null
        chain.blockhashValid = false
        chain.history = SignatureStatus(5, ChainLevel.Finalized, null)
        chain.body = ChainTransaction(5, signedWire(), null)
        tracker.check(key)
        assertEquals(ChainState.Confirmed, check().state)
        assertEquals(1, chain.bodyCalls)
    }

    @Test
    fun anUnreadableBlockhashNeverProvesExpiry() = runBlocking {
        // An operation whose bytes this build can't decode still has its message compared, but
        // without a blockhash nothing can prove it expired.
        tracker.expect(
            Submission(key, TrackingOrigin.Operation, Network.NETWORK_DEVNET, WALLET, opaque())
        )
        log.record(operationRecord(network = Network.NETWORK_DEVNET, signature = null))
        tracker.submitted(key, SIGNATURE)
        advance(Duration.ofDays(1))
        chain.status = null
        chain.blockhashValid = false
        tracker.check(key)
        assertEquals(ChainState.Checking, check().state)
        assertEquals(0, chain.blockhashCalls)
    }

    @Test
    fun theClusterIsTheOneTheRecordWasBoundToAndAnEndpointForAnotherIsRefused() = runBlocking {
        send()
        // The only endpoint serves mainnet; the record is devnet's.
        chain.genesis = GENESIS_HASHES.getValue(Network.NETWORK_MAINNET)
        advance(Duration.ofHours(1))
        chain.status = null
        chain.blockhashValid = false
        tracker.check(key)
        assertEquals(ChainState.Checking, check().state)
        assertEquals(ChainReason.WrongCluster, check().reason)
        // Nothing about the other cluster was read as evidence.
        assertEquals(0, chain.statusCalls)
        assertEquals(Network.NETWORK_DEVNET, trackingStore.get(key)!!.network)
    }

    @Test
    fun aBuildWithNoEndpointChecksNothingAndSaysSo() = runBlocking {
        val none =
            ConfirmationTracker(trackingStore, log, ChainEndpoints(emptyList()) { chain }) { now }
        none.expect(submission())
        log.record(
            result(
                transferRequest(),
                signing = SigningOutcome.Sent(sig()),
                approvedTransaction = approved(),
            ),
            connection(),
        )
        none.submitted(key, SIGNATURE)
        advance(Duration.ofSeconds(3))
        none.checkDue()
        assertEquals(ChainReason.NoEndpoint, trackingStore.get(key)!!.check.reason)
        assertNull(none.nextDue())
    }

    @Test
    fun aRecordStaysCheckableAfterItsConnectionIsGone() = runBlocking {
        // The tracker never reads connections: removing one leaves its tracking and History row.
        send()
        chain.status = SignatureStatus(3, ChainLevel.Finalized, null)
        chain.body = ChainTransaction(3, signedWire(), null)
        advance(Duration.ofSeconds(3))
        tracker.checkDue()
        assertEquals(ActivityOutcome.Confirmed, record().outcome)
    }

    @Test
    fun clearingHistoryForgetsTrackingAndAnAnswerInFlightWritesNothingBack() = runBlocking {
        send()
        advance(Duration.ofSeconds(3))
        chain.status = SignatureStatus(3, ChainLevel.Finalized, null)
        chain.body = ChainTransaction(3, signedWire(), null)
        // The owner clears History while the endpoint is answering.
        chain.beforeStatus = { log.clear() }
        tracker.checkDue()
        assertNull(activityStore.get(CONNECTION, REQUEST))
        assertNull(trackingStore.get(key))
        assertTrue(tracker.checks.value.isEmpty())
        // And a server's late answer about the same request can't recreate the row either.
        log.record(
            result(
                transferRequest(state = RequestState.REQUEST_STATE_CONFIRMED),
                signing = SigningOutcome.Sent(sig()),
                approvedTransaction = approved(),
            ),
            connection(),
        )
        assertNull(activityStore.get(CONNECTION, REQUEST))
    }

    @Test
    fun aLateServerUpdateNeverRollsBackTheVerifiedResult() = runBlocking {
        send()
        chain.status = SignatureStatus(9, ChainLevel.Finalized, "err")
        chain.body = ChainTransaction(9, signedWire(), "err")
        advance(Duration.ofSeconds(3))
        tracker.checkDue()
        assertEquals(ActivityOutcome.ChainFailed, record().outcome)

        // The server is behind (still SUBMITTED), then disagrees (CONFIRMED). The phone's own
        // verification of the approved bytes stands, and there is still one row.
        log.record(
            result(
                transferRequest(),
                signing = SigningOutcome.Sent(sig()),
                approvedTransaction = approved(),
            ),
            connection(),
        )
        assertEquals(ActivityOutcome.ChainFailed, record().outcome)
        log.reconcile(transferRequest(state = RequestState.REQUEST_STATE_CONFIRMED))
        assertEquals(ActivityOutcome.ChainFailed, record().outcome)
        assertEquals(ChainState.Failed, record().chain?.state)
        assertEquals(1, activityStore.list().size)
    }

    @Test
    fun aServersSettledWordStillCountsWhereThePhoneHasNone() {
        log.record(
            result(
                transferRequest(state = RequestState.REQUEST_STATE_CONFIRMED),
                signing = SigningOutcome.Sent(sig()),
                approvedTransaction = approved(),
            ),
            connection(),
        )
        assertEquals(ActivityOutcome.Confirmed, record().outcome)
    }

    @Test
    fun overlappingForegroundManualAndWorkerRunsCheckOnceAtATime() = runBlocking {
        send()
        advance(Duration.ofSeconds(3))
        chain.status = SignatureStatus(9, ChainLevel.Finalized, null)
        chain.body = ChainTransaction(9, signedWire(), null)
        val gate = CompletableDeferred<Unit>()
        chain.suspendStatus = gate
        val foreground = async { tracker.checkDue() }
        while (chain.statusCalls == 0) kotlinx.coroutines.yield()
        // A worker pass that finds the foreground run going skips it rather than asking again.
        val worker = async { tracker.checkDue() }
        worker.await()
        assertEquals(1, chain.statusCalls)
        val manual = async { tracker.check(key) }
        gate.complete(Unit)
        foreground.await()
        manual.await()
        assertEquals(ChainState.Confirmed, check().state)
        // The manual check found it already final and asked nothing more.
        assertEquals(1, chain.statusCalls)
        assertEquals(1, activityStore.list().size)
    }

    @Test
    fun onlySentTransactionsAreFollowedAndTheWalletIsNeverAsked() = runBlocking {
        // Declined, failed, rehearsed: nothing was sent, so the capture is dropped.
        tracker.expect(submission())
        tracker.abandoned(key)
        assertNull(trackingStore.get(key))
        // A signature, once recorded, is not replaced by a second one.
        send()
        tracker.submitted(key, ByteArray(64) { 1 })
        assertEquals(encodeBase58(SIGNATURE), trackingStore.get(key)!!.signature)
        // And abandoning after a signature is recorded changes nothing.
        tracker.abandoned(key)
        assertNotNull(trackingStore.get(key))
    }

    @Test
    fun backfillTracksWhatStillHasItsContextAndSaysWhatDoesNot() = runBlocking {
        val direct =
            result(
                transferRequest(),
                signing = SigningOutcome.Sent(sig()),
                approvedTransaction = approved(),
            )
        log.record(direct, connection())
        // An operation from before this build: a signature, and nothing to compare it with.
        val legacy = operationRecord(requestId = OTHER_REQUEST)
        log.record(legacy)
        log.load()

        tracker.backfill(listOf(direct), log.records.value)

        assertEquals(ChainState.Checking, trackingStore.get(key)!!.check.state)
        assertEquals(ANSWERED, trackingStore.get(key)!!.submittedAt)
        val missing = activityStore.get(CONNECTION, OTHER_REQUEST)!!.chain
        assertEquals(ChainState.Unresolved, missing?.state)
        assertEquals(ChainReason.MissingContext, missing?.reason)
        assertNull(trackingStore.get(RequestKey(CONNECTION, OTHER_REQUEST)))
        // Running it again changes nothing.
        tracker.backfill(listOf(direct), log.records.value)
        assertEquals(2, activityStore.list().size)
    }

    @Test
    fun aStakingActionIsFollowedAndLinkedOnItsOwnCluster() {
        val staking = io.github.brrenat.seekervault.activity.record(kind = ActivityKind.Staking)
        assertTrue(staking.copy(signature = "x").signatureIsTransaction)
    }

    @Test
    fun theMessageRegionIsEverythingAfterTheSignatures() {
        val wire = unsignedWire()
        val message = messageBytes(wire)!!
        assertEquals(wire.size - 65, message.size)
        assertTrue(messageBytes(signedWire())!!.contentEquals(message))
        assertNull(messageBytes(byteArrayOf(0)))
        assertNull(messageBytes(byteArrayOf(1, 2, 3)))
        assertEquals(encodeBase58(ByteArray(32) { 5 }), recentBlockhashOf(wire))
    }

    // What the direct path does when the wallet answers: capture, then the signature.
    private fun send() {
        tracker.expect(submission())
        log.record(
            result(
                transferRequest(),
                signing = SigningOutcome.Sent(sig()),
                approvedTransaction = approved(),
            ),
            connection(),
        )
        tracker.submitted(key, SIGNATURE)
    }

    private fun advance(by: Duration) {
        now = now.plus(by)
    }

    private fun check(): ChainCheck = checkNotNull(trackingStore.get(key)).check

    private fun record() = checkNotNull(activityStore.get(CONNECTION, REQUEST))

    private fun submission() =
        Submission(key, TrackingOrigin.Direct, Network.NETWORK_DEVNET, WALLET, unsignedWire())

    private fun approved() =
        ApprovedTransaction(
            version = 1,
            contentHash = ByteString.copyFrom(ByteArray(32)),
            transaction = ByteString.copyFrom(unsignedWire()),
        )

    private fun sig() = ByteString.copyFrom(SIGNATURE)

    /** A chain whose every answer the test sets. */
    private class FakeChain : ChainReader {
        override val host = "rpc.example.com"
        var genesis = GENESIS_HASHES.getValue(Network.NETWORK_DEVNET)
        var status: SignatureStatus? = null
        var history: SignatureStatus? = null
        var body: ChainTransaction? = null
        var blockhashValid = true
        /** How far back the endpoint's own ledger reaches; by default, all the way. */
        var retainedSince: Instant? = Instant.EPOCH
        var failure: SolanaException? = null
        var beforeStatus: () -> Unit = {}
        var suspendStatus: CompletableDeferred<Unit>? = null
        var statusCalls = 0
        var historyCalls = 0
        var bodyCalls = 0
        var blockhashCalls = 0

        override suspend fun genesisHash(): String = genesis

        override suspend fun statuses(
            signatures: List<String>,
            searchHistory: Boolean,
        ): List<SignatureStatus?> {
            failure?.let { throw it }
            if (searchHistory) {
                historyCalls++
                return signatures.map { history }
            }
            statusCalls++
            beforeStatus()
            suspendStatus?.await()
            return signatures.map { status }
        }

        override suspend fun transaction(signature: String): ChainTransaction? {
            bodyCalls++
            return body
        }

        override suspend fun blockhashValid(blockhash: String): Boolean {
            blockhashCalls++
            return blockhashValid
        }

        override suspend fun retainedSince(): Instant? = retainedSince
    }

    private companion object {
        val SENT_AT: Instant = Instant.parse("2026-09-26T12:00:00Z")
        val ANSWERED: Instant = Instant.parse("2026-09-11T12:00:00.250Z")
        val SIGNATURE = ByteArray(64) { (it + 7).toByte() }

        /** A legacy message: one signer, one program, one instruction, and a known blockhash. */
        fun message(blockhashFill: Int = 5): ByteArray =
            byteArrayOf(1, 0, 1, 2) +
                ByteArray(32) { 1 } +
                ByteArray(32) { 2 } +
                ByteArray(32) { blockhashFill.toByte() } +
                byteArrayOf(1, 1, 1, 0, 0)

        fun unsignedWire(): ByteArray = byteArrayOf(1) + ByteArray(64) + message()

        fun signedWire(blockhashFill: Int = 5): ByteArray =
            byteArrayOf(1) + SIGNATURE + message(blockhashFill)

        /** Bytes with a signature slot and a message this build's decoder won't read. */
        fun opaque(): ByteArray = byteArrayOf(1) + ByteArray(64) + byteArrayOf(0x85.toByte(), 9, 9)
    }
}
