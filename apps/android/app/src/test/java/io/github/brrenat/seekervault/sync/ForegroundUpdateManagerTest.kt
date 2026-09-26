package io.github.brrenat.seekervault.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.connectrpc.Code
import com.connectrpc.ConnectException
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.sync.storage.SyncStore
import io.github.brrenat.seekervault.update.v1.ReplayComplete
import io.github.brrenat.seekervault.update.v1.RequestChanged
import io.github.brrenat.seekervault.update.v1.ResumeDisposition
import io.github.brrenat.seekervault.update.v1.ServerHeartbeat
import io.github.brrenat.seekervault.update.v1.ServerReady
import io.github.brrenat.seekervault.update.v1.SubscribeResponse
import io.github.brrenat.seekervault.update.v1.SyncRequest
import io.github.brrenat.seekervault.update.v1.SyncResponse
import io.github.brrenat.seekervault.update.v1.SyncedRequest
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ForegroundUpdateManagerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun rotationAndNavigationKeepOneStreamWhileBackgroundClosesAndReturnReconciles() = runTest {
        val fixture = fixture(A)
        fixture.manager.onForeground()
        runCurrent()

        assertEquals(ForegroundConnectionState.Live, fixture.manager.state.value.connections[A])
        assertEquals(1, fixture.transport.subscribeCalls[A])

        // Repeated starts cover screen navigation and a rotation whose stop is intentionally
        // ignored by MainActivity: neither is another application foreground transition.
        fixture.manager.onForeground()
        fixture.manager.onForeground()
        runCurrent()
        assertEquals(1, fixture.transport.subscribeCalls[A])

        fixture.manager.onBackground()
        runCurrent()
        assertEquals(
            ForegroundConnectionState.Background,
            fixture.manager.state.value.connections[A],
        )
        assertTrue(fixture.transport.subscriptions.getValue(A).single().closed)

        fixture.manager.onForeground()
        runCurrent()
        assertEquals(2, fixture.transport.subscribeCalls[A])
        assertEquals(2, fixture.transport.syncCalls[A])
        assertEquals(ForegroundConnectionState.Live, fixture.manager.state.value.connections[A])

        val second = fixture.transport.subscriptions.getValue(A).last()
        fixture.manager.onBackground()
        fixture.manager.onForeground()
        runCurrent()
        assertTrue(second.closed)
        assertEquals(3, fixture.transport.subscribeCalls[A])
        assertEquals(ForegroundConnectionState.Live, fixture.manager.state.value.connections[A])
    }

    @Test
    fun oneUnavailableServerRetriesWithoutDisturbingTheOther() = runTest {
        val fixture = fixture(A, B)
        fixture.transport.syncFailures[A] = 1
        fixture.manager.onForeground()
        runCurrent()

        assertEquals(
            ForegroundConnectionState.Unreachable(CheckOutcome.Unreachable),
            fixture.manager.state.value.connections[A],
        )
        assertEquals(ForegroundConnectionState.Live, fixture.manager.state.value.connections[B])
        assertEquals(1, fixture.transport.subscribeCalls[B])

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ForegroundConnectionState.Live, fixture.manager.state.value.connections[A])
        assertEquals(1, fixture.transport.subscribeCalls[A])
        assertEquals(1, fixture.transport.subscribeCalls[B])
    }

    @Test
    fun authenticationAndVersionFailuresAreActionableInsteadOfTransientOutages() = runTest {
        val fixture = fixture(A, B)
        fixture.transport.subscribeFailures[A] =
            UpdateTransportException(UpdateTransportException.Kind.Unauthenticated, "revoked")
        fixture.transport.subscribeFailures[B] =
            UpdateTransportException(UpdateTransportException.Kind.UpgradeRequired, "old")
        fixture.manager.onForeground()
        runCurrent()

        assertEquals(ForegroundConnectionState.Revoked, fixture.manager.state.value.connections[A])
        assertEquals(
            ForegroundConnectionState.Unsupported(UpdateAvailability.UpgradeRequired),
            fixture.manager.state.value.connections[B],
        )
        assertTrue(A !in fixture.host.connectionIds())
    }

    @Test
    fun pairingStartsAStreamAndRemovalStopsItWithoutAReconnect() = runTest {
        val fixture = fixture(A)
        fixture.manager.onForeground()
        runCurrent()

        fixture.add(B)
        runCurrent()
        assertEquals(ForegroundConnectionState.Live, fixture.manager.state.value.connections[B])
        assertEquals(1, fixture.transport.subscribeCalls[B])

        val stream = fixture.transport.subscriptions.getValue(B).single()
        fixture.remove(B)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(stream.closed)
        assertEquals(1, fixture.transport.subscribeCalls[B])
        assertTrue(B !in fixture.manager.state.value.connections)
    }

    @Test
    fun eventDuringRequiredSnapshotIsBufferedThenPublishesReactivePendingState() = runTest {
        val fixture = fixture(A)
        fixture.transport.resume[A] = ResumeDisposition.RESUME_DISPOSITION_FULL_SYNC_REQUIRED
        val secondSyncEntered = CompletableDeferred<Unit>()
        val releaseSecondSync = CompletableDeferred<Unit>()
        fixture.transport.beforeSync = { id, call ->
            if (id == A && call == 2) {
                secondSyncEntered.complete(Unit)
                releaseSecondSync.await()
            }
        }
        fixture.manager.onForeground()
        runCurrent()
        assertTrue(secondSyncEntered.isCompleted)

        fixture.transport.subscriptions
            .getValue(A)
            .single()
            .send(changed(A, REQUEST, 1, "cursor-3"))
        runCurrent()
        assertTrue(fixture.host.applied.getValue(A).pending.isEmpty())

        releaseSecondSync.complete(Unit)
        runCurrent()
        assertEquals(
            listOf(REQUEST),
            fixture.host.applied.getValue(A).pending.map { it.ref.requestId },
        )
        assertEquals(ForegroundConnectionState.Live, fixture.manager.state.value.connections[A])
    }

    @Test
    fun unansweredHeartbeatDeadlineClosesThenReconnectsWithBoundedBackoff() = runTest {
        val fixture = fixture(A)
        fixture.manager.onForeground()
        runCurrent()
        val first = fixture.transport.subscriptions.getValue(A).single()

        repeat(3) {
            advanceTimeBy(15_000)
            runCurrent()
        }
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), first.heartbeats)
        assertTrue(first.closed)
        assertEquals(
            ForegroundConnectionState.Unreachable(CheckOutcome.Failed),
            fixture.manager.state.value.connections[A],
        )

        advanceTimeBy(999)
        runCurrent()
        assertEquals(2, fixture.transport.subscribeCalls[A])
        assertEquals(ForegroundConnectionState.Live, fixture.manager.state.value.connections[A])
    }

    @Test
    fun incomingServerHeartbeatsDoNotSuppressPeriodicClientHeartbeats() = runTest {
        val fixture = fixture(A)
        fixture.manager.onForeground()
        runCurrent()
        val stream = fixture.transport.subscriptions.getValue(A).single()

        repeat(7) { sequence ->
            advanceTimeBy(7_000)
            stream.send(serverHeartbeat(A, sequence.toLong()))
            runCurrent()
        }

        assertEquals(listOf(1L, 2L, 3L), stream.heartbeats)
        assertFalse(stream.closed)
        assertEquals(1, fixture.transport.subscribeCalls[A])
        assertEquals(ForegroundConnectionState.Live, fixture.manager.state.value.connections[A])
    }

    // SEE-152. A direct connection that stopped recovering after an interruption had not lost the
    // server: it had lost its owner. Every case below ends ownership in `maintainConnection`, and
    // `ownConnections` starts an owner only when the connection *list* changes, so the row froze on
    // whatever was published last and nothing but a new pairing brought it back.

    @Test
    fun aStreamThatNeverBecomesReadyComesBackInsteadOfStrandingTheConnection() = runTest {
        val fixture = fixture(A)
        fixture.transport.silentHandshakes[A] = 1
        fixture.manager.onForeground()
        runCurrent()
        assertEquals(1, fixture.transport.subscribeCalls[A])
        assertEquals(
            ForegroundConnectionState.Connecting,
            fixture.manager.state.value.connections[A],
        )

        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(
            ForegroundConnectionState.Unreachable(CheckOutcome.Failed),
            fixture.manager.state.value.connections[A],
        )
        assertTrue(fixture.transport.subscriptions.getValue(A).single().closed)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, fixture.transport.subscribeCalls[A])
        assertEquals(ForegroundConnectionState.Live, fixture.manager.state.value.connections[A])
    }

    @Test
    fun anAbandonedCoalescedSnapshotDoesNotEndTheOwnersWatchOverTheConnection() = runTest {
        val fixture = fixture(A)
        val entered = CompletableDeferred<Unit>()
        fixture.transport.beforeSync = { id, call ->
            if (id == A && call == 1) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        // A Retry whose screen closes, or a worker whose job ends, while the owner has joined its
        // run: cancelling it must not cancel the owner along with it.
        val abandoned = backgroundScope.launch { fixture.repository.synchronize(A) }
        runCurrent()
        assertTrue(entered.isCompleted)

        fixture.manager.onForeground()
        runCurrent()
        assertEquals(
            ForegroundConnectionState.Connecting,
            fixture.manager.state.value.connections[A],
        )

        abandoned.cancel()
        runCurrent()
        assertEquals(ForegroundConnectionState.Live, fixture.manager.state.value.connections[A])
        assertEquals(2, fixture.transport.syncCalls[A])
        assertEquals(1, fixture.transport.subscribeCalls[A])
    }

    @Test
    fun anUnclassifiedSnapshotFailureIsRetriedInsteadOfEndingTheOwner() = runTest {
        val fixture = fixture(A)
        fixture.transport.syncCrashes[A] = 1
        fixture.manager.onForeground()
        runCurrent()
        assertEquals(
            ForegroundConnectionState.Unreachable(CheckOutcome.Failed),
            fixture.manager.state.value.connections[A],
        )

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(ForegroundConnectionState.Live, fixture.manager.state.value.connections[A])
        assertEquals(1, fixture.transport.subscribeCalls[A])
    }

    @Test
    fun anInvalidSnapshotReconnectsInsteadOfBeingReadAsAnOutdatedSidecar() = runTest {
        val fixture = fixture(A)
        fixture.manager.onForeground()
        runCurrent()
        val first = fixture.transport.subscriptions.getValue(A).single()

        // FAILED_PRECONDITION is the sidecar's answer both for a protocol it will not speak and for
        // a snapshot this phone has to replace. Only the second is recoverable, and the code alone
        // cannot tell them apart — the transport's own classification can.
        first.fail(
            UpdateTransportException(
                UpdateTransportException.Kind.SnapshotInvalid,
                "the snapshot expired",
                ConnectException(Code.FAILED_PRECONDITION),
            )
        )
        runCurrent()
        assertEquals(
            ForegroundConnectionState.Unreachable(CheckOutcome.Failed),
            fixture.manager.state.value.connections[A],
        )

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, fixture.transport.subscribeCalls[A])
        assertEquals(ForegroundConnectionState.Live, fixture.manager.state.value.connections[A])
    }

    @Test
    fun anInterruptionEndsWithTheStreamBackAndWhatArrivedDuringItSynchronised() = runTest {
        val fixture = fixture(A)
        fixture.manager.onForeground()
        runCurrent()
        assertEquals(ForegroundConnectionState.Live, fixture.manager.state.value.connections[A])
        val live = fixture.transport.subscriptions.getValue(A).single()

        // Networking off. The stream dies and every call fails for as long as it stays off.
        fixture.transport.offline = true
        live.fail(IOException("the network went away"))
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(
            ForegroundConnectionState.Unreachable(CheckOutcome.Unreachable),
            fixture.manager.state.value.connections[A],
        )

        // The agent publishes a request while the phone cannot hear it, so the sidecar can no
        // longer replay from where this phone stopped.
        fixture.transport.snapshots[A] = listOf(waiting(A, REQUEST))
        fixture.transport.resume[A] = ResumeDisposition.RESUME_DISPOSITION_FULL_SYNC_REQUIRED
        // The first stream after an interruption is the one that is opened through a route that
        // has forgotten this phone: it is accepted and then says nothing at all.
        fixture.transport.silentHandshakes[A] = 1

        // Networking back on. Nothing else happens: no re-pairing, no new connection.
        fixture.transport.offline = false
        advanceTimeBy(30_001)
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(ForegroundConnectionState.Live, fixture.manager.state.value.connections[A])
        assertEquals(
            listOf(REQUEST),
            fixture.host.applied.getValue(A).pending.map { it.ref.requestId },
        )
    }

    private fun TestScope.fixture(vararg ids: String): Fixture {
        val connections = MutableStateFlow<List<Connection>>(emptyList())
        val host = FakeHost()
        val transport = FakeTransport()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository =
            SynchronizationRepository(
                SyncStore(File(folder.root, "sync")),
                transport,
                host,
                io = dispatcher,
            )
        val fixture = Fixture(connections, host, transport, repository, dispatcher, backgroundScope)
        ids.forEach(fixture::add)
        return fixture
    }

    private inner class Fixture(
        val connections: MutableStateFlow<List<Connection>>,
        val host: FakeHost,
        val transport: FakeTransport,
        val repository: SynchronizationRepository,
        dispatcher: CoroutineDispatcher,
        ownerScope: kotlinx.coroutines.CoroutineScope,
    ) {
        val manager =
            ForegroundUpdateManager(
                connections,
                repository,
                dispatcher = dispatcher,
                sleep = { delay(it) },
                jitter = { it },
                ownerScope = ownerScope,
            )

        fun add(id: String) {
            host.add(id)
            connections.value = connections.value + connection(id)
        }

        fun remove(id: String) {
            host.remove(id)
            connections.value = connections.value.filterNot { it.id == id }
        }
    }

    private class FakeHost : SynchronizationHost {
        private val ids = linkedSetOf<String>()
        private val access = mutableMapOf<String, SyncConnection>()
        val applied = mutableMapOf<String, ConnectionSyncState>()

        fun add(id: String) {
            ids += id
            access[id] = SyncConnection("https://$id.example", "secret-$id")
        }

        fun remove(id: String) {
            ids -= id
            access -= id
        }

        override suspend fun connectionIds(): Set<String> = ids.toSet()

        override suspend fun access(connectionId: String): SyncConnection? = access[connectionId]

        override suspend fun retryRecordedResults(connectionId: String) = Unit

        override suspend fun nonterminalActivity(connectionId: String) =
            emptyList<LocalRequestState>()

        override suspend fun authoritativeRequests(connectionId: String) =
            emptyMap<String, ActionRequest>()

        override suspend fun applyCache(state: ConnectionSyncState) {
            applied[state.connectionId] = state
        }

        override suspend fun recordFailure(connectionId: String, failure: CheckOutcome) = Unit

        override suspend fun revoke(connectionId: String) {
            remove(connectionId)
        }
    }

    private class FakeTransport : UpdateTransport {
        val syncCalls = mutableMapOf<String, Int>()
        val subscribeCalls = mutableMapOf<String, Int>()
        val syncFailures = mutableMapOf<String, Int>()
        val subscribeFailures = mutableMapOf<String, UpdateTransportException>()
        val resume = mutableMapOf<String, ResumeDisposition>()
        val subscriptions = mutableMapOf<String, MutableList<FakeSubscription>>()
        val syncCrashes = mutableMapOf<String, Int>()
        val snapshots = mutableMapOf<String, List<ActionRequest>>()
        val silentHandshakes = mutableMapOf<String, Int>()
        var offline = false
        var beforeSync: suspend (String, Int) -> Unit = { _, _ -> }

        override suspend fun discover(
            serverUrl: String,
            credential: String,
            connectionId: String,
        ) = UpdateEndpoint(1, serverUrl)

        override suspend fun sync(
            endpoint: UpdateEndpoint,
            credential: String,
            request: SyncRequest,
        ): SyncResponse {
            val id = request.connectionId
            val call = syncCalls.getOrDefault(id, 0) + 1
            syncCalls[id] = call
            val failures = syncFailures.getOrDefault(id, 0)
            if (offline || failures > 0) {
                if (failures > 0) syncFailures[id] = failures - 1
                throw UpdateTransportException(
                    UpdateTransportException.Kind.Unreachable,
                    "offline",
                )
            }
            val crashes = syncCrashes.getOrDefault(id, 0)
            if (crashes > 0) {
                syncCrashes[id] = crashes - 1
                // Nothing the repository classifies: the shape of a defect rather than an outage.
                throw IllegalStateException("the snapshot reader broke")
            }
            beforeSync(id, call)
            return SyncResponse.newBuilder()
                .setConnectionId(id)
                .setServerInstanceId("instance-$id")
                .setSnapshotCursor("cursor-$call")
                .addAllRequests(
                    snapshots[id].orEmpty().map { request ->
                        SyncedRequest.newBuilder().setRequest(request).setRevision(1).build()
                    }
                )
                .build()
        }

        override suspend fun subscribe(
            endpoint: UpdateEndpoint,
            credential: String,
            connectionId: String,
            resumeCursor: String,
            serverInstanceId: String,
        ): UpdateSubscription {
            subscribeFailures[connectionId]?.let { throw it }
            if (offline) {
                throw UpdateTransportException(
                    UpdateTransportException.Kind.Unreachable,
                    "offline",
                )
            }
            subscribeCalls[connectionId] = subscribeCalls.getOrDefault(connectionId, 0) + 1
            val disposition = resume[connectionId] ?: ResumeDisposition.RESUME_DISPOSITION_REPLAYING
            return FakeSubscription(connectionId).also { stream ->
                subscriptions.getOrPut(connectionId) { mutableListOf() } += stream
                val silent = silentHandshakes.getOrDefault(connectionId, 0)
                if (silent > 0) {
                    // Accepted, and then nothing: the sidecar never answers the subscribe.
                    silentHandshakes[connectionId] = silent - 1
                    return@also
                }
                stream.send(ready(connectionId, disposition, resumeCursor.ifEmpty { "barrier" }))
                if (disposition == ResumeDisposition.RESUME_DISPOSITION_REPLAYING) {
                    stream.send(replayComplete(connectionId, resumeCursor))
                }
            }
        }
    }

    private class FakeSubscription(val connectionId: String) : UpdateSubscription {
        private val channel = Channel<SubscribeResponse>(Channel.UNLIMITED)
        override val responses = channel
        val heartbeats = mutableListOf<Long>()
        var closed = false

        fun send(response: SubscribeResponse) {
            channel.trySend(response).getOrThrow()
        }

        /** Ends the stream the way a transport failure does, with the reason it failed for. */
        fun fail(cause: Throwable) {
            channel.close(cause)
        }

        override suspend fun heartbeat(sequence: Long, appliedCursor: String, sentAt: Instant) {
            heartbeats += sequence
        }

        override suspend fun close() {
            closed = true
            channel.close()
        }
    }

    private companion object {
        const val A = "11111111-1111-4111-8111-111111111111"
        const val B = "22222222-2222-4222-8222-222222222222"
        const val REQUEST = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

        fun connection(id: String) =
            Connection(
                id = id,
                label = id.take(4),
                serverUrl = "https://$id.example",
                serverId = "server-$id",
                deviceName = "Seeker",
                pairedAt = Instant.parse("2026-09-14T12:00:00Z"),
            )

        fun ready(id: String, disposition: ResumeDisposition, cursor: String) =
            SubscribeResponse.newBuilder()
                .setConnectionId(id)
                .setServerInstanceId("instance-$id")
                .setCursor(cursor)
                .setReady(
                    ServerReady.newBuilder()
                        .setProtocolVersion(1)
                        .setResume(disposition)
                        .setHeartbeatIntervalSeconds(15)
                        .setMaxMessageBytes(65_536)
                        .setMaxPageSize(50)
                )
                .build()

        fun replayComplete(id: String, cursor: String) =
            SubscribeResponse.newBuilder()
                .setConnectionId(id)
                .setServerInstanceId("instance-$id")
                .setCursor(cursor)
                .setReplayComplete(ReplayComplete.newBuilder().setThroughCursor(cursor))
                .build()

        /** One unchanging request, so a repeated snapshot page is the same page. */
        fun waiting(id: String, requestId: String) =
            FakeConnectionGateway.request(
                id,
                requestId,
                createdAt = Instant.parse("2026-09-24T23:07:52Z"),
            )

        fun changed(id: String, requestId: String, revision: Long, cursor: String) =
            SubscribeResponse.newBuilder()
                .setConnectionId(id)
                .setServerInstanceId("instance-$id")
                .setCursor(cursor)
                .setRequestChanged(
                    RequestChanged.newBuilder()
                        .setRequest(FakeConnectionGateway.request(id, requestId))
                        .setRevision(revision)
                )
                .build()

        fun serverHeartbeat(id: String, sequence: Long) =
            SubscribeResponse.newBuilder()
                .setConnectionId(id)
                .setServerInstanceId("instance-$id")
                .setCursor("heartbeat-$sequence")
                .setHeartbeat(ServerHeartbeat.getDefaultInstance())
                .build()
    }
}
