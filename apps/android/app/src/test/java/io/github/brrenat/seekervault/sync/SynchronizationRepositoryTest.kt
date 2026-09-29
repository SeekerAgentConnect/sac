package io.github.brrenat.seekervault.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.sync.storage.SyncStore
import io.github.brrenat.seekervault.update.v1.ConnectionRevoked
import io.github.brrenat.seekervault.update.v1.RemovalReason
import io.github.brrenat.seekervault.update.v1.RequestChanged
import io.github.brrenat.seekervault.update.v1.RequestRemoved
import io.github.brrenat.seekervault.update.v1.SubscribeResponse
import io.github.brrenat.seekervault.update.v1.SyncRequest
import io.github.brrenat.seekervault.update.v1.SyncResponse
import io.github.brrenat.seekervault.update.v1.SyncedRequest
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SynchronizationRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private val host = FakeHost()
    private val transport = FakeTransport()
    private val store by lazy { SyncStore(File(folder.root, "files/sync")) }
    private var clock = Instant.parse("2026-09-13T12:00:00Z")

    private fun repository() =
        SynchronizationRepository(store, transport, host, { clock }, Dispatchers.Unconfined)

    @Test
    fun headlessSyncRestoresPendingStateAfterProcessDeath() = runTest {
        val pending = request(A_REQUEST)
        host.add(A)
        transport.pages[A] = ArrayDeque(listOf(snapshot(A, listOf(pending to 1))))
        val first = repository()

        assertTrue(first.synchronizeAll()[A] is SynchronizeOutcome.Updated)
        assertEquals(listOf(A_REQUEST), host.applied.getValue(A).pending.map { it.ref.requestId })
        assertEquals(clock, host.applied.getValue(A).lastSuccessfulSync)

        host.applied.clear()
        val restarted = repository()
        restarted.load()
        assertEquals(listOf(A_REQUEST), host.applied.getValue(A).pending.map { it.ref.requestId })
        assertEquals("cursor-1", restarted.state.value.connections.getValue(A).cursor)
    }

    @Test
    fun completePagesReplacePendingButKeepTerminalStatusForActivity() = runTest {
        host.add(A)
        val old = request(OLD_REQUEST)
        transport.pages[A] = ArrayDeque(listOf(snapshot(A, listOf(old to 1))))
        val repository = repository()
        repository.synchronize(A)

        clock = clock.plusSeconds(60)
        val pending = request(A_REQUEST)
        val expired = request(EXPIRED_REQUEST, RequestState.REQUEST_STATE_EXPIRED)
        transport.pages[A] =
            ArrayDeque(
                listOf(
                    snapshot(
                        A,
                        listOf(pending to 1),
                        next = "page-2",
                        cursor = "cursor-2",
                    ),
                    snapshot(A, listOf(expired to 4), cursor = "cursor-2"),
                )
            )
        repository.synchronize(A)

        val state = repository.state.value.connections.getValue(A)
        assertEquals(listOf(A_REQUEST), state.pending.map { it.ref.requestId })
        assertEquals(RequestState.REQUEST_STATE_EXPIRED, state.requests[EXPIRED_REQUEST]?.state)
        assertNull(state.requests[OLD_REQUEST])
        assertEquals(3, transport.requests[A]?.size)
        assertTrue(transport.requests.getValue(A).last().knownNonterminalList.isEmpty())
    }

    @Test
    fun duplicateAndStaleEventsAreIdempotentButGapConflictAndRollbackRequireSync() = runTest {
        host.add(A)
        val pending = request(A_REQUEST)
        transport.pages[A] = ArrayDeque(listOf(snapshot(A, listOf(pending to 1))))
        val repository = repository()
        repository.synchronize(A)
        val generation = repository.beginStream(A)

        val processing = pending.withState(RequestState.REQUEST_STATE_PROCESSING)
        assertEquals(
            EventApplyOutcome.Applied,
            repository.applyEvent(A, generation, changed(A, processing, 2, "cursor-2")),
        )
        assertEquals(
            EventApplyOutcome.Ignored,
            repository.applyEvent(A, generation, changed(A, processing, 2, "cursor-2b")),
        )
        assertEquals(
            EventApplyOutcome.Ignored,
            repository.applyEvent(A, generation, changed(A, pending, 1, "cursor-stale")),
        )
        assertEquals("cursor-2", repository.state.value.connections.getValue(A).cursor)
        assertEquals(
            RequestState.REQUEST_STATE_PROCESSING,
            host.applied.getValue(A).pending.singleOrNull()?.state
                ?: repository.state.value.connections
                    .getValue(A)
                    .requests
                    .getValue(A_REQUEST)
                    .state,
        )

        assertEquals(
            EventApplyOutcome.FullSyncRequired,
            repository.applyEvent(
                A,
                generation,
                changed(A, processing.withState(RequestState.REQUEST_STATE_SUBMITTED), 4, "gap"),
            ),
        )
        assertTrue(repository.state.value.connections.getValue(A).fullSyncRequired)

        transport.pages[A] = ArrayDeque(listOf(snapshot(A, listOf(processing to 2))))
        repository.synchronize(A)
        assertEquals(
            EventApplyOutcome.FullSyncRequired,
            repository.applyEvent(A, generation, changed(A, pending, 3, "rollback")),
        )
    }

    @Test
    fun removalIsRevisionedAndNeverDeletesPhoneOwnedActivity() = runTest {
        host.add(A)
        val pending = request(A_REQUEST)
        transport.pages[A] = ArrayDeque(listOf(snapshot(A, listOf(pending to 1))))
        val repository = repository()
        repository.synchronize(A)
        val generation = repository.beginStream(A)

        assertEquals(
            EventApplyOutcome.Applied,
            repository.applyEvent(A, generation, removed(A, A_REQUEST, 2)),
        )
        assertTrue(repository.state.value.connections.getValue(A).pending.isEmpty())
        assertEquals(
            RemovalReason.REMOVAL_REASON_RETENTION,
            repository.state.value.connections.getValue(A).requests.getValue(A_REQUEST).removed,
        )
        assertEquals(0, host.activityDeletes)
    }

    @Test
    fun overlappingPushPeriodicAndStreamUpdatesUseOneConnectionCoordinator() = runTest {
        host.add(A)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.beforeSync = {
            entered.complete(Unit)
            release.await()
        }
        val pending = request(A_REQUEST)
        transport.pages[A] = ArrayDeque(listOf(snapshot(A, listOf(pending to 1))))
        val repository = repository()
        val connections = listOf(connection(A))
        val push =
            PushSyncRunner(
                load = repository::load,
                connections = { connections },
                foreground = { ForegroundUpdatesState() },
                synchronizeConnections = repository::synchronizeConnections,
            )
        val periodic =
            BackgroundSyncRunner(
                load = repository::load,
                connections = { connections },
                foreground = { ForegroundUpdatesState() },
                synchronizeConnections = repository::synchronizeConnections,
            )
        val generation = repository.beginStream(A)

        val first = async { push.run() }
        entered.await()
        val second = async { periodic.run() }
        runCurrent()
        assertEquals(
            EventApplyOutcome.Buffered,
            repository.applyEvent(
                A,
                generation,
                changed(A, pending.withState(RequestState.REQUEST_STATE_PROCESSING), 2, "stream"),
            ),
        )
        release.complete(Unit)

        assertEquals(BackgroundSyncDecision.Complete, first.await())
        assertEquals(BackgroundSyncDecision.Complete, second.await())
        assertEquals(1, transport.syncCalls[A])
        assertEquals(1, host.retries[A])
        assertEquals(
            2L,
            repository.state.value.connections.getValue(A).requests.getValue(A_REQUEST).revision,
        )
    }

    /**
     * A request an event created while the snapshot was being read happened during the read, so it
     * is handed over as news; the snapshot's own requests are not. An event after it always is
     * (SEE-175).
     */
    @Test
    fun aRequestCreatedByAnEventBufferedDuringASnapshotIsHandedOverAsLive() = runTest {
        host.add(A)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.beforeSync = {
            entered.complete(Unit)
            release.await()
        }
        transport.pages[A] = ArrayDeque(listOf(snapshot(A, listOf(request(A_REQUEST) to 1))))
        val repository = repository()
        val generation = repository.beginStream(A)

        val sync = async { repository.synchronize(A) }
        entered.await()
        runCurrent()
        assertEquals(
            EventApplyOutcome.Buffered,
            repository.applyEvent(A, generation, changed(A, request(B_REQUEST), 1, "during")),
        )
        release.complete(Unit)
        sync.await()

        assertEquals(SyncDelivery.Snapshot(live = setOf(B_REQUEST)), host.deliveries[A])
        assertEquals(
            setOf(A_REQUEST, B_REQUEST),
            host.applied.getValue(A).pending.map { it.ref.requestId }.toSet(),
        )

        assertEquals(
            EventApplyOutcome.Applied,
            repository.applyEvent(A, generation, changed(A, request(OLD_REQUEST), 1, "after")),
        )
        assertEquals(SyncDelivery.Event, host.deliveries[A])
    }

    /**
     * The snapshot can catch a request that a live event buffered during it also carries: the
     * request was still created while the snapshot was read, and it is handed over as news all the
     * same. A replayed event buffered beside it is history and is not (SEE-175 review).
     */
    @Test
    fun aLiveEventTheSnapshotAlsoCaughtIsStillHandedOverButAReplayIsNot() = runTest {
        host.add(A)
        // The same documents on both paths, as the server sends them.
        val (a, b, old) = listOf(A_REQUEST, B_REQUEST, OLD_REQUEST).map { request(it) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.beforeSync = {
            entered.complete(Unit)
            release.await()
        }
        transport.pages[A] =
            ArrayDeque(
                listOf(
                    snapshot(
                        A,
                        listOf(
                            a to 1,
                            b to 1,
                            old to 1,
                        ),
                    )
                )
            )
        val repository = repository()
        val generation = repository.beginStream(A)

        val sync = async { repository.synchronize(A) }
        entered.await()
        runCurrent()
        repository.applyEvent(
            A,
            generation,
            changed(A, old, 1, "replayed"),
            replayed = true,
        )
        repository.applyEvent(A, generation, changed(A, b, 1, "during"))
        release.complete(Unit)
        sync.await()

        assertEquals(SyncDelivery.Snapshot(live = setOf(B_REQUEST)), host.deliveries[A])
        assertEquals(
            setOf(A_REQUEST, B_REQUEST, OLD_REQUEST),
            host.applied.getValue(A).pending.map { it.ref.requestId }.toSet(),
        )
    }

    /**
     * A live event that reaches the phone only after the snapshot that caught it was applied is
     * ignored as a duplicate, and the host is told so it can recognise the handoff. A replayed
     * duplicate says nothing, and a replayed change is applied as a replay (SEE-175 review).
     */
    @Test
    fun aLiveDuplicateOfWhatTheSnapshotHeldIsOfferedToTheHostAsAHandoff() = runTest {
        host.add(A)
        val (a, b) = listOf(A_REQUEST, B_REQUEST).map { request(it) }
        transport.pages[A] = ArrayDeque(listOf(snapshot(A, listOf(a to 1, b to 1))))
        val repository = repository()
        val generation = repository.beginStream(A)
        repository.synchronize(A)

        assertEquals(
            EventApplyOutcome.Ignored,
            repository.applyEvent(
                A,
                generation,
                changed(A, b, 1, "replayed"),
                replayed = true,
            ),
        )
        assertEquals(emptyList<Pair<String, String>>(), host.handedOver)

        assertEquals(
            EventApplyOutcome.Ignored,
            repository.applyEvent(A, generation, changed(A, a, 1, "late")),
        )
        assertEquals(listOf(A to A_REQUEST), host.handedOver)

        assertEquals(
            EventApplyOutcome.Applied,
            repository.applyEvent(
                A,
                generation,
                changed(A, request(OLD_REQUEST), 1, "missed"),
                replayed = true,
            ),
        )
        assertEquals(SyncDelivery.Replayed, host.deliveries[A])
    }

    @Test
    fun recoverySyncFetchesOnlyTheRequestedActiveConnections() = runTest {
        host.add(A)
        host.add(B)
        transport.pages[A] = ArrayDeque(listOf(snapshot(A, emptyList())))
        transport.pages[B] = ArrayDeque(listOf(snapshot(B, emptyList())))

        val outcomes = repository().synchronizeConnections(setOf(B, C))

        assertEquals(setOf(B), outcomes.keys)
        assertEquals(null, transport.syncCalls[A])
        assertEquals(1, transport.syncCalls[B])
    }

    @Test
    fun streamBarrierRunsAfterAnOlderManualSyncInsteadOfBeingCoalescedAway() = runTest {
        host.add(A)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.beforeSync = {
            entered.complete(Unit)
            release.await()
        }
        val pending = request(A_REQUEST)
        transport.pages[A] =
            ArrayDeque(
                listOf(
                    snapshot(A, listOf(pending to 1), cursor = "manual"),
                    snapshot(A, listOf(pending to 1), cursor = "stream"),
                )
            )
        val repository = repository()
        val manual = async { repository.synchronize(A) }
        entered.await()
        val stream = async { repository.synchronize(A, "barrier") }
        release.complete(Unit)

        assertTrue(manual.await() is SynchronizeOutcome.Updated)
        assertTrue(stream.await() is SynchronizeOutcome.Updated)
        assertEquals(2, transport.syncCalls[A])
        assertEquals("barrier", transport.requests.getValue(A).last().subscriptionCursor)
    }

    @Test
    fun aCallerWhoseCoalescedRunIsAbandonedTakesTheLeadRatherThanInheritingItsEnd() = runTest {
        host.add(A)
        val entered = CompletableDeferred<Unit>()
        transport.beforeSync = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val pending = request(A_REQUEST)
        transport.pages[A] = ArrayDeque(listOf(snapshot(A, listOf(pending to 1))))
        val repository = repository()
        val abandoned = async { repository.synchronize(A) }
        entered.await()
        val joined = async { repository.synchronize(A) }
        runCurrent()

        // The run this call joined belonged to somebody else — a Retry whose screen closed, a
        // worker whose job ended — and cancelling it must not cancel a caller that is still alive.
        // The foreground owner is one such caller, and nothing starts another one while the
        // connection stays paired, so inheriting this would stop it watching for good (SEE-152).
        transport.beforeSync = {}
        abandoned.cancel()

        assertTrue(joined.await() is SynchronizeOutcome.Updated)
        assertEquals(listOf(A_REQUEST), host.applied.getValue(A).pending.map { it.ref.requestId })
        assertEquals(2, transport.syncCalls[A])
    }

    @Test
    fun boundedStreamOverflowImmediatelyRecoversWithAFullSnapshot() = runTest {
        host.add(A)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.beforeSync = {
            entered.complete(Unit)
            release.await()
        }
        val pending = request(A_REQUEST)
        transport.pages[A] =
            ArrayDeque(
                listOf(
                    snapshot(A, emptyList(), cursor = "incomplete"),
                    snapshot(A, listOf(pending to 1), cursor = "recovered"),
                )
            )
        val repository = repository()
        val generation = repository.beginStream(A)
        val syncing = async { repository.synchronize(A, "barrier") }
        entered.await()
        repeat(513) { index ->
            repository.applyEvent(A, generation, changed(A, pending, 1, "buffer-$index"))
        }
        release.complete(Unit)

        val outcome = syncing.await() as SynchronizeOutcome.Updated
        assertEquals(2, transport.syncCalls[A])
        assertEquals("", transport.requests.getValue(A).last().subscriptionCursor)
        assertEquals("recovered", outcome.state.cursor)
        assertFalse(outcome.state.fullSyncRequired)
        assertEquals(listOf(A_REQUEST), outcome.state.pending.map { it.ref.requestId })
    }

    @Test
    fun finalBufferDrainClosesBeforeAStreamEventCanBeStranded() = runTest {
        host.add(A)
        val cacheEntered = CompletableDeferred<Unit>()
        val releaseCache = CompletableDeferred<Unit>()
        host.beforeApplyCache = { state ->
            if (state.cursor == "snapshot") {
                cacheEntered.complete(Unit)
                releaseCache.await()
            }
        }
        val pending = request(A_REQUEST)
        transport.pages[A] =
            ArrayDeque(listOf(snapshot(A, listOf(pending to 1), cursor = "snapshot")))
        val repository = repository()
        val generation = repository.beginStream(A)
        val syncing = async { repository.synchronize(A) }
        cacheEntered.await()

        val processing = pending.withState(RequestState.REQUEST_STATE_PROCESSING)
        val event = async {
            repository.applyEvent(A, generation, changed(A, processing, 2, "live"))
        }
        runCurrent()
        assertFalse(event.isCompleted)

        releaseCache.complete(Unit)
        assertTrue(syncing.await() is SynchronizeOutcome.Updated)
        assertEquals(EventApplyOutcome.Applied, event.await())
        assertEquals("live", repository.state.value.connections.getValue(A).cursor)
        assertEquals(2L, host.applied.getValue(A).requests.getValue(A_REQUEST).revision)
    }

    @Test
    fun oneUnreachableServerDoesNotBlockAHealthyConnection() = runTest {
        host.add(A)
        host.add(B)
        transport.failures[A] =
            UpdateTransportException(
                UpdateTransportException.Kind.Unreachable,
                "offline without a secret",
            )
        transport.pages[B] =
            ArrayDeque(listOf(snapshot(B, listOf(request(B_REQUEST, connection = B) to 1))))

        val outcomes = repository().synchronizeAll()

        assertEquals(CheckOutcome.Unreachable, (outcomes[A] as SynchronizeOutcome.Failed).failure)
        assertTrue(outcomes[B] is SynchronizeOutcome.Updated)
        assertEquals(listOf(B_REQUEST), host.applied.getValue(B).pending.map { it.ref.requestId })
    }

    @Test
    fun headlessSyncBoundsConcurrentServersWithoutSerializingThem() = runTest {
        val ids = listOf(A, B, C, D, E)
        ids.forEach { id ->
            host.add(id)
            transport.pages[id] = ArrayDeque(listOf(snapshot(id, emptyList())))
        }
        var active = 0
        var maximum = 0
        val fourEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.onSync = {
            active++
            maximum = maxOf(maximum, active)
            if (active == 4) fourEntered.complete(Unit)
            release.await()
            active--
        }

        val running = async { repository().synchronizeAll() }
        fourEntered.await()
        assertEquals(4, transport.syncCalls.values.sum())
        release.complete(Unit)

        assertEquals(ids.toSet(), running.await().keys)
        assertEquals(4, maximum)
    }

    @Test
    fun headlessTimeoutIsRetryableAndDoesNotStarveAnotherServer() = runTest {
        host.add(A)
        host.add(B)
        transport.pages[B] = ArrayDeque(listOf(snapshot(B, emptyList())))
        transport.onSync = { id -> if (id == A) awaitCancellation() }

        val running = async { repository().synchronizeAll() }
        runCurrent()
        assertEquals(1, transport.syncCalls[B])
        advanceTimeBy(10 * 60 * 1_000L - 1)
        runCurrent()
        assertFalse(running.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        val outcomes = running.await()

        assertEquals(
            CheckOutcome.Unreachable,
            (outcomes.getValue(A) as SynchronizeOutcome.Failed).failure,
        )
        assertTrue(outcomes.getValue(B) is SynchronizeOutcome.Updated)
        assertEquals(1, transport.syncCalls[B])
    }

    @Test
    fun failedSnapshotClearsTheCursorSoLaterEventsCannotSkipItsGap() = runTest {
        host.add(A)
        val pending = request(A_REQUEST)
        transport.pages[A] = ArrayDeque(listOf(snapshot(A, listOf(pending to 1))))
        val repository = repository()
        repository.synchronize(A)
        val generation = repository.beginStream(A)

        transport.failures[A] =
            UpdateTransportException(UpdateTransportException.Kind.Unreachable, "offline")
        assertTrue(repository.synchronize(A) is SynchronizeOutcome.Failed)
        assertEquals("", repository.state.value.connections.getValue(A).cursor)
        assertEquals(
            EventApplyOutcome.FullSyncRequired,
            repository.applyEvent(
                A,
                generation,
                changed(
                    A,
                    pending.withState(RequestState.REQUEST_STATE_PROCESSING),
                    2,
                    "later",
                ),
            ),
        )
        assertEquals(
            1L,
            repository.state.value.connections.getValue(A).requests[A_REQUEST]?.revision,
        )
    }

    @Test
    fun capabilityStatesKeepLegacyRefreshAvailableWithoutTrustingAnInvalidOrigin() = runTest {
        host.add(A)
        transport.unconfigured += A
        val unconfigured = repository()
        assertEquals(
            SynchronizeOutcome.Legacy(UpdateAvailability.NotConfigured),
            unconfigured.synchronize(A),
        )
        assertEquals(
            UpdateAvailability.NotConfigured,
            unconfigured.state.value.connections.getValue(A).availability,
        )

        transport.unconfigured.clear()
        transport.endpoints[A] = UpdateEndpoint(1, "https://attacker.example")
        val incompatible = repository()
        assertEquals(
            SynchronizeOutcome.Legacy(UpdateAvailability.Incompatible),
            incompatible.synchronize(A),
        )
        assertNull(incompatible.state.value.connections.getValue(A).endpoint)

        store.delete(A)
        transport.endpoints.clear()
        transport.failures[A] =
            UpdateTransportException(UpdateTransportException.Kind.UpgradeRequired, "old")
        val old = repository()
        assertEquals(
            SynchronizeOutcome.Legacy(UpdateAvailability.UpgradeRequired),
            old.synchronize(A),
        )
    }

    @Test
    fun cachedEndpointIsRevalidatedAgainstTheImmutablePairedOrigin() = runTest {
        host.add(A)
        store.put(
            ConnectionSyncState(
                A,
                availability = UpdateAvailability.Available,
                endpoint = UpdateEndpoint(1, "https://attacker.example"),
            )
        )
        transport.pages[A] = ArrayDeque(listOf(snapshot(A, emptyList())))

        val outcome = repository().synchronize(A) as SynchronizeOutcome.Updated

        assertEquals(1, transport.discoverCalls[A])
        assertEquals("https://$A.example", outcome.state.endpoint?.grpcUrl)
    }

    @Test
    fun activitySetsLargerThanTheProtocolLimitRotateAcrossSyncs() = runTest {
        host.add(A)
        host.local[A] =
            (0 until 105).map { index ->
                val requestId = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
                LocalRequestState(RequestKey(A, requestId), RequestState.REQUEST_STATE_UNKNOWN)
            }
        transport.pages[A] = ArrayDeque(listOf(snapshot(A, emptyList(), cursor = "first")))
        val repository = repository()
        repository.synchronize(A)
        transport.pages[A] = ArrayDeque(listOf(snapshot(A, emptyList(), cursor = "second")))
        repository.synchronize(A)

        val batches =
            transport.requests.getValue(A).map { page ->
                page.knownNonterminalList.map { it.ref.requestId }
            }
        assertEquals(100, batches[0].size)
        assertEquals(100, batches[1].size)
        assertEquals(105, (batches[0] + batches[1]).toSet().size)
    }

    @Test
    fun snapshotCanContinuePastOneHundredMessageBoundPages() = runTest {
        host.add(A)
        val pageCount = 101
        transport.pages[A] =
            ArrayDeque(
                (0 until pageCount).map { index ->
                    val requestId = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
                    snapshot(
                        A,
                        listOf(request(requestId) to 1),
                        next = if (index == pageCount - 1) "" else "page-${index + 1}",
                        cursor = "large-snapshot",
                    )
                }
            )

        val outcome = repository().synchronize(A) as SynchronizeOutcome.Updated

        assertEquals(pageCount, transport.syncCalls[A])
        assertEquals(pageCount, outcome.state.pending.size)
        assertEquals("large-snapshot", outcome.state.cursor)
    }

    @Test
    fun cachedEndpointProtocolFailureBecomesUpgradeRequiredWithoutRetryLoop() = runTest {
        host.add(A)
        transport.pages[A] = ArrayDeque(listOf(snapshot(A, emptyList())))
        val repository = repository()
        assertTrue(repository.synchronize(A) is SynchronizeOutcome.Updated)

        transport.failures[A] =
            UpdateTransportException(UpdateTransportException.Kind.UpgradeRequired, "version")
        assertEquals(
            SynchronizeOutcome.Legacy(UpdateAvailability.UpgradeRequired),
            repository.synchronize(A),
        )

        val state = repository.state.value.connections.getValue(A)
        assertEquals(UpdateAvailability.UpgradeRequired, state.availability)
        assertNull(state.endpoint)
        assertEquals(2, transport.syncCalls[A])
    }

    @Test
    fun revocationBufferedDuringFetchRemovesCacheAndLateResponseCannotRecreateIt() = runTest {
        host.add(A)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.beforeSync = {
            entered.complete(Unit)
            release.await()
        }
        transport.pages[A] = ArrayDeque(listOf(snapshot(A, listOf(request(A_REQUEST) to 1))))
        val repository = repository()
        val generation = repository.beginStream(A)
        val syncing = async { repository.synchronize(A) }
        entered.await()
        assertEquals(
            EventApplyOutcome.Buffered,
            repository.applyEvent(A, generation, revoked(A)),
        )
        release.complete(Unit)

        assertEquals(SynchronizeOutcome.Removed, syncing.await())
        assertTrue(A in host.revoked)
        assertNull(store.get(A))
        assertNull(repository.state.value.connections[A])
    }

    @Test
    fun deletingDuringFetchCancelsItAndAStaleGenerationCannotWrite() = runBlocking {
        host.add(A)
        val entered = CompletableDeferred<Unit>()
        transport.beforeSync = {
            entered.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        transport.pages[A] = ArrayDeque(listOf(snapshot(A, listOf(request(A_REQUEST) to 1))))
        val repository = repository()
        val generation = repository.beginStream(A)
        val syncing = async { repository.synchronize(A) }
        entered.await()
        host.ids.remove(A)
        repository.remove(A)
        syncing.join()

        assertNull(store.get(A))
        assertEquals(
            EventApplyOutcome.Removed,
            repository.applyEvent(A, generation, changed(A, request(A_REQUEST), 1, "late")),
        )
        assertFalse(host.applied.containsKey(A))
    }

    private fun request(
        requestId: String,
        state: RequestState = RequestState.REQUEST_STATE_PENDING,
        connection: String = A,
    ): ActionRequest = FakeConnectionGateway.request(connection, requestId).withState(state)

    private fun ActionRequest.withState(state: RequestState): ActionRequest =
        toBuilder().setState(state).build()

    private fun snapshot(
        connectionId: String,
        requests: List<Pair<ActionRequest, Long>>,
        next: String = "",
        cursor: String = "cursor-1",
    ): SyncResponse =
        SyncResponse.newBuilder()
            .setConnectionId(connectionId)
            .setServerInstanceId("instance-$connectionId")
            .setSnapshotCursor(cursor)
            .setNextPageToken(next)
            .addAllRequests(
                requests.map { (request, revision) ->
                    SyncedRequest.newBuilder().setRequest(request).setRevision(revision).build()
                }
            )
            .build()

    private fun changed(
        connectionId: String,
        request: ActionRequest,
        revision: Long,
        cursor: String,
    ): SubscribeResponse =
        SubscribeResponse.newBuilder()
            .setConnectionId(connectionId)
            .setServerInstanceId("instance-$connectionId")
            .setCursor(cursor)
            .setRequestChanged(
                RequestChanged.newBuilder().setRequest(request).setRevision(revision)
            )
            .build()

    private fun removed(
        connectionId: String,
        requestId: String,
        revision: Long,
    ): SubscribeResponse =
        SubscribeResponse.newBuilder()
            .setConnectionId(connectionId)
            .setServerInstanceId("instance-$connectionId")
            .setCursor("removed-$revision")
            .setRequestRemoved(
                RequestRemoved.newBuilder()
                    .setRef(
                        io.github.brrenat.seekervault.request.v1.RequestRef.newBuilder()
                            .setConnectionId(connectionId)
                            .setRequestId(requestId)
                    )
                    .setRevision(revision)
                    .setReason(RemovalReason.REMOVAL_REASON_RETENTION)
            )
            .build()

    private fun revoked(connectionId: String): SubscribeResponse =
        SubscribeResponse.newBuilder()
            .setConnectionId(connectionId)
            .setServerInstanceId("instance-$connectionId")
            .setCursor("revoked")
            .setRevoked(ConnectionRevoked.getDefaultInstance())
            .build()

    private fun connection(id: String) =
        Connection(
            id = id,
            label = id.take(4),
            serverUrl = "https://$id.example",
            serverId = "server-$id",
            deviceName = "Seeker",
            pairedAt = Instant.parse("2026-09-14T12:00:00Z"),
        )

    private class FakeHost : SynchronizationHost {
        val ids = linkedSetOf<String>()
        val accesses = mutableMapOf<String, SyncConnection>()
        val applied = mutableMapOf<String, ConnectionSyncState>()
        val deliveries = mutableMapOf<String, SyncDelivery>()
        val handedOver = mutableListOf<Pair<String, String>>()
        val retries = mutableMapOf<String, Int>()
        val local = mutableMapOf<String, List<LocalRequestState>>()
        val authoritative = mutableMapOf<String, Map<String, ActionRequest>>()
        val failures = mutableMapOf<String, CheckOutcome>()
        val revoked = mutableSetOf<String>()
        var activityDeletes = 0
        var beforeApplyCache: suspend (ConnectionSyncState) -> Unit = {}

        fun add(connectionId: String) {
            ids += connectionId
            accesses[connectionId] =
                SyncConnection("https://$connectionId.example", "secret-$connectionId")
        }

        override suspend fun connectionIds(): Set<String> = ids.toSet()

        override suspend fun access(connectionId: String): SyncConnection? =
            accesses[connectionId]?.takeIf { connectionId in ids }

        override suspend fun retryRecordedResults(connectionId: String) {
            retries[connectionId] = retries.getOrDefault(connectionId, 0) + 1
        }

        override suspend fun nonterminalActivity(connectionId: String): List<LocalRequestState> =
            local[connectionId].orEmpty()

        override suspend fun authoritativeRequests(
            connectionId: String
        ): Map<String, ActionRequest> = authoritative[connectionId].orEmpty()

        override suspend fun applyCache(state: ConnectionSyncState) {
            beforeApplyCache(state)
            applied[state.connectionId] = state
        }

        override suspend fun applyCache(state: ConnectionSyncState, delivery: SyncDelivery) {
            deliveries[state.connectionId] = delivery
            applyCache(state)
        }

        override suspend fun handOver(connectionId: String, requestId: String) {
            handedOver += connectionId to requestId
        }

        override suspend fun recordFailure(connectionId: String, failure: CheckOutcome) {
            failures[connectionId] = failure
        }

        override suspend fun revoke(connectionId: String) {
            revoked += connectionId
            ids -= connectionId
            accesses -= connectionId
        }
    }

    private class FakeTransport : UpdateTransport {
        val pages = mutableMapOf<String, ArrayDeque<SyncResponse>>()
        val requests = mutableMapOf<String, MutableList<SyncRequest>>()
        val failures = mutableMapOf<String, UpdateTransportException>()
        val endpoints = mutableMapOf<String, UpdateEndpoint>()
        val unconfigured = mutableSetOf<String>()
        val discoverCalls = mutableMapOf<String, Int>()
        val syncCalls = mutableMapOf<String, Int>()
        var beforeSync: suspend () -> Unit = {}
        var onSync: suspend (String) -> Unit = {}

        override suspend fun discover(
            serverUrl: String,
            credential: String,
            connectionId: String,
        ): UpdateEndpoint? {
            discoverCalls[connectionId] = discoverCalls.getOrDefault(connectionId, 0) + 1
            failures[connectionId]?.let { throw it }
            if (connectionId in unconfigured) return null
            return endpoints[connectionId] ?: UpdateEndpoint(1, "https://$connectionId.example")
        }

        override suspend fun sync(
            endpoint: UpdateEndpoint,
            credential: String,
            request: SyncRequest,
        ): SyncResponse {
            val id = request.connectionId
            syncCalls[id] = syncCalls.getOrDefault(id, 0) + 1
            failures[id]?.let { throw it }
            requests.getOrPut(id) { mutableListOf() } += request
            beforeSync()
            onSync(id)
            return checkNotNull(pages[id]?.removeFirstOrNull()) { "no page for $id" }
        }
    }

    private companion object {
        const val A = "11111111-1111-4111-8111-111111111111"
        const val B = "22222222-2222-4222-8222-222222222222"
        const val C = "33333333-3333-4333-8333-333333333333"
        const val D = "44444444-4444-4444-8444-444444444444"
        const val E = "55555555-5555-4555-8555-555555555555"
        const val A_REQUEST = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val B_REQUEST = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val OLD_REQUEST = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val EXPIRED_REQUEST = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    }
}
