package io.github.brrenat.seekervault.connections.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "files/connections") }
    private val store by lazy { ConnectionStore(dir) }
    private val a =
        Connection(
            id = "0b8e2b1c-3f4d-4e5a-9b6c-7d8e9f0a1b2c",
            label = "Home Mac ✓",
            serverUrl = "https://mac.tailnet.ts.net",
            serverId = "9fda5035-f3b4-4ec3-a68a-5e6caa02397a",
            deviceName = "Seeker",
            pairedAt = Instant.parse("2026-09-11T12:00:00.123Z"),
        )
    private val b =
        a.copy(
            id = "5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f",
            label = "VPS",
            serverUrl = "https://vps.example.com",
            pairedAt = Instant.parse("2026-09-11T13:00:00Z"),
        )

    @Test
    fun keepsEveryFieldAcrossARestart() {
        val full =
            a.copy(
                revokedAt = Instant.parse("2026-09-12T08:00:00Z"),
                lastCheck =
                    Connection.Check(
                        Instant.parse("2026-09-11T12:05:00Z"),
                        CheckOutcome.Ok,
                        pending = 100,
                        morePending = true,
                    ),
            )
        store.put(full)
        store.put(b.copy(lastCheck = Connection.Check(b.pairedAt, CheckOutcome.Unreachable)))
        val reopened = ConnectionStore(dir)
        assertEquals(full, reopened.get(a.id))
        assertEquals(CheckOutcome.Unreachable, reopened.get(b.id)?.lastCheck?.outcome)
        assertNull(reopened.get(b.id)?.lastCheck?.pending)
    }

    @Test
    fun listsOldestFirstAndRemovesOneAtATime() {
        store.put(b)
        store.put(a)
        assertEquals(listOf(a.id, b.id), store.list().map { it.id })
        store.delete(a.id)
        assertEquals(listOf(b), store.list())
        store.put(b.copy(label = "Renamed"))
        assertEquals(listOf("Renamed"), store.list().map { it.label })
    }

    @Test
    fun skipsDamagedFilesAndFilesUnderAnotherName() {
        store.put(a)
        File(dir, "${b.id}.json").writeText("{not json")
        File(dir, "notes.json").writeText("{}")
        // a's metadata under another connection's name must not become that connection.
        val c = "7c6b5a49-3827-4615-a0b9-c8d7e6f5a4b3"
        File(dir, "$a.id.json".replace("$a.id", c)).writeText(File(dir, "${a.id}.json").readText())
        assertEquals(listOf(a.id), store.list().map { it.id })
        assertNull(store.get(c))
    }

    @Test
    fun namesFilesOnlyByConnectionIds() {
        assertThrows(IllegalArgumentException::class.java) {
            store.put(a.copy(id = "../../shared_prefs/x"))
        }
        assertNull(store.get("../../shared_prefs/x"))
    }
}
