package io.github.brrenat.seekervault.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.connectrpc.okhttp.ConnectOkHttpClient
import io.github.brrenat.seekervault.connections.ConnectConnectionGateway
import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.connections.FcmTokenUpdate
import io.github.brrenat.seekervault.connections.PairingCode
import io.github.brrenat.seekervault.connections.PairingCodeResult
import io.github.brrenat.seekervault.connections.PairingCodes
import io.github.brrenat.seekervault.connections.RealSidecar
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.sync.storage.SyncStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Stage 5.3's joined phone acceptance path. Real MCP requests enter two real sidecar processes and
 * the production Android HTTP/2 synchronization repository consumes their authoritative state. FCM
 * delivery itself is deliberately replaced by explicit delayed, duplicate, and dropped hint timing;
 * only the physical Seeker run can prove Firebase and Android OS delivery.
 */
@RunWith(AndroidJUnit4::class)
class Stage53AcceptanceTest {
    @get:Rule val folder = TemporaryFolder()

    private val sidecars = mutableListOf<RealSidecar>()
    private val key = softwareKey()

    @After
    fun stop() {
        sidecars.forEach(RealSidecar::close)
    }

    @Test
    fun delayedDroppedAndDuplicateHintsConvergeTwoSidecarsWithoutDuplicateState() = runBlocking {
        val a = sidecar()
        val b = sidecar()
        val app = repository()
        app.load()
        val onA = app.pair(code(a))
        val onB = app.pair(code(b))
        assertTrue(app.synchronizeAll().values.all { it is SynchronizeOutcome.Updated })

        // Registration travels to each sidecar through that connection's stored credential.
        // Rotation and a delayed compare-clear are idempotent and never affect the other URL.
        assertTrue(app.setFcmToken(onA.id, FcmTokenUpdate.Register(OLD_TARGET)))
        assertTrue(app.setFcmToken(onB.id, FcmTokenUpdate.Register(OLD_TARGET)))
        assertTrue(app.setFcmToken(onA.id, FcmTokenUpdate.Register(CURRENT_TARGET)))
        assertTrue(app.setFcmToken(onA.id, FcmTokenUpdate.ClearIfCurrent(OLD_TARGET)))
        assertFalse(app.setFcmToken(OTHER_CONNECTION, FcmTokenUpdate.Register(CURRENT_TARGET)))

        val firstA = a.requestAck("A while no ping arrives", "stage53-a-dropped")
        val firstB = b.requestAck("B while no ping arrives", "stage53-b-dropped")
        assertTrue(app.pendingKeys().isEmpty())

        // No Firebase callback is invoked. Stage 5.2's periodic runner still reloads every
        // usable connection and discovers both durable requests.
        assertEquals(BackgroundSyncDecision.Complete, periodic(app).run())
        assertEquals(
            setOf(RequestKey(onA.id, firstA), RequestKey(onB.id, firstB)),
            app.pendingKeys(),
        )

        // A delayed hint arrives only after the request was canceled. Authoritative Sync sees
        // the final state, so no stale request or new-request notification is invented.
        val goneBeforeDelivery =
            a.requestAck("Created and canceled before delivery", "stage53-a-delayed")
        assertEquals("CANCELLED", a.cancel(goneBeforeDelivery))
        val changes = mutableListOf<Pair<Set<RequestKey>, Set<RequestKey>>>()
        assertEquals(BackgroundSyncDecision.Complete, push(app, changes).run())
        assertFalse(app.pendingKeys().any { it.requestId == goneBeforeDelivery })
        assertTrue(changes.single().second - changes.single().first == emptySet<RequestKey>())

        // Duplicate pings run the same fetch twice. The second sees no delta, and the cache
        // contains one copy of the request rather than one copy per transport signal.
        val current = a.requestAck("One request despite duplicate pings", "stage53-a-duplicate")
        assertEquals(BackgroundSyncDecision.Complete, push(app, changes).run())
        assertEquals(BackgroundSyncDecision.Complete, push(app, changes).run())
        assertEquals(1, app.pendingKeys().count { it == RequestKey(onA.id, current) })
        assertEquals(
            listOf(RequestKey(onA.id, current)),
            changes.flatMap { (before, after) -> after - before },
        )

        // A repository created without an Activity models a process-absent worker. With no
        // Firebase app or callback involved, it reloads Stage 5.2 storage and recovers B.
        val processAbsent = repository()
        val missedAgain = b.requestAck("Recovered after process absence", "stage53-b-process")
        assertEquals(BackgroundSyncDecision.Complete, periodic(processAbsent).run())
        assertTrue(RequestKey(onB.id, missedAgain) in processAbsent.pendingKeys())

        // Revocation removes only A. A later recovery still advances B, and registration can
        // no longer be published for the revoked connection.
        a.revokePairedPhone()
        val afterRevocation = b.requestAck("B remains independent", "stage53-b-revoked-a")
        assertEquals(BackgroundSyncDecision.Complete, push(processAbsent, changes).run())
        assertFalse(processAbsent.connection(onA.id)?.usable ?: true)
        assertTrue(processAbsent.connection(onB.id)?.usable == true)
        assertTrue(RequestKey(onB.id, afterRevocation) in processAbsent.pendingKeys())
        assertFalse(processAbsent.setFcmToken(onA.id, FcmTokenUpdate.Register(CURRENT_TARGET)))

        for (privateValue in listOf(OLD_TARGET, CURRENT_TARGET)) {
            assertFalse(a.output.contains(privateValue))
            assertFalse(b.output.contains(privateValue))
        }
    }

    private fun sidecar() = RealSidecar(productionUpdates = true).also { sidecars += it }

    private fun repository(): ConnectionRepository {
        val http = ConnectOkHttpClient.configureClient(OkHttpClient.Builder()).build()
        return ConnectionRepository(
            store = ConnectionStore(File(folder.root, "files/connections")),
            vault = CredentialVault(File(folder.root, "no_backup/credentials")) { key },
            results = ResultStore(File(folder.root, "files/results")),
            gateway = ConnectConnectionGateway(http),
            deviceName = "Seeker",
            io = Dispatchers.IO,
            syncStore = SyncStore(File(folder.root, "files/sync")),
            updateTransport = ConnectUpdateTransport(http),
        )
    }

    private fun code(sidecar: RealSidecar): PairingCode =
        (PairingCodes.parse(sidecar.pairingCode()) { it == "127.0.0.1" } as PairingCodeResult.Valid)
            .code

    private fun periodic(repository: ConnectionRepository) =
        BackgroundSyncRunner(
            load = repository::load,
            connections = { repository.connections.value },
            foreground = { ForegroundUpdatesState() },
            synchronizeConnections = repository::synchronizeConnections,
        )

    private fun push(
        repository: ConnectionRepository,
        changes: MutableList<Pair<Set<RequestKey>, Set<RequestKey>>>,
    ) =
        PushSyncRunner(
            load = repository::load,
            connections = { repository.connections.value },
            foreground = { ForegroundUpdatesState() },
            synchronizeConnections = repository::synchronizeConnections,
            pending = { repository.pendingKeys() },
            reconcileNotifications = { before, after -> changes += before to after },
        )

    private fun ConnectionRepository.pendingKeys(): Set<RequestKey> =
        inbox.value.pending.values.flatten().mapTo(mutableSetOf()) {
            RequestKey(it.ref.connectionId, it.ref.requestId)
        }

    private companion object {
        const val OLD_TARGET = "private-stage53-target-before-rotation"
        const val CURRENT_TARGET = "private-stage53-target-after-rotation"
        const val OTHER_CONNECTION = "ffffffff-ffff-4fff-8fff-ffffffffffff"
    }
}
