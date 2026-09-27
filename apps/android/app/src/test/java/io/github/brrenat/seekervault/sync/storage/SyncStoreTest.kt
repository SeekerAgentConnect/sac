package io.github.brrenat.seekervault.sync.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.sync.ConnectionSyncState
import io.github.brrenat.seekervault.sync.ServerRequest
import io.github.brrenat.seekervault.sync.UpdateAvailability
import io.github.brrenat.seekervault.sync.UpdateEndpoint
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "files/sync") }
    private val store by lazy { SyncStore(dir) }

    @Test
    fun keepsACompleteConnectionDocumentAndNoCredential() {
        val request = FakeConnectionGateway.request(CONNECTION_ID, REQUEST_ID)
        val state =
            ConnectionSyncState(
                connectionId = CONNECTION_ID,
                availability = UpdateAvailability.Available,
                endpoint = UpdateEndpoint(1, "https://sidecar.example"),
                serverInstanceId = "instance",
                cursor = "cursor-8",
                lastSuccessfulSync = Instant.parse("2026-09-13T12:00:00Z"),
                nextKnownIndex = 7,
                requests =
                    mapOf(
                        REQUEST_ID to
                            ServerRequest(RequestKey(CONNECTION_ID, REQUEST_ID), 8, request)
                    ),
                syncing = true,
            )
        store.put(state)

        val reopened = checkNotNull(SyncStore(dir).get(CONNECTION_ID))
        assertEquals(state.copy(syncing = false), reopened)
        assertFalse(File(dir, "$CONNECTION_ID.json").readText().contains("phone-secret"))
    }

    @Test
    fun interruptedAtomicWriteKeepsTheLastCompleteDocument() {
        store.put(
            ConnectionSyncState(
                CONNECTION_ID,
                serverInstanceId = "instance",
                cursor = "complete",
            )
        )
        // Android AtomicFile stages replacement bytes beside the base file. A process death before
        // finishWrite leaves this file; readFully must still return the prior complete base.
        File(dir, "$CONNECTION_ID.json.new").writeText("{interrupted")

        assertEquals("complete", SyncStore(dir).get(CONNECTION_ID)?.cursor)
    }

    @Test
    fun damagedOrFutureStateForcesRecoveryInsteadOfLookingEmptyAndValid() {
        store.put(
            ConnectionSyncState(
                CONNECTION_ID,
                serverInstanceId = "old-instance",
                cursor = "old",
            )
        )
        File(dir, "$CONNECTION_ID.json").writeText("""{"version":2}""")
        assertNull(SyncStore(dir).get(CONNECTION_ID))
        assertEquals(emptyList<ConnectionSyncState>(), SyncStore(dir).list())
    }

    private companion object {
        const val CONNECTION_ID = "11111111-1111-4111-8111-111111111111"
        const val REQUEST_ID = "22222222-2222-4222-8222-222222222222"
    }
}
