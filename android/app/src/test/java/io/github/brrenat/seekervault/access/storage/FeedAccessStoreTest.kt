package io.github.brrenat.seekervault.access.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.access.storage.FeedAccessStore.Record
import io.github.brrenat.seekervault.access.storage.FeedAccessStore.State
import java.io.File
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Where this phone's access to each restricted feed stands, on disk (SEE-156).
 *
 * What this defends is that the record is a record and not a capability. It holds a decision and
 * what the decision was bound to; the device key stays in the Keystore and the session stays in the
 * vault, so a file read off this directory reads a feed for nobody. The rest is the ordinary
 * discipline the other stores keep: one document per connection, an identifier that is checked
 * before it is ever used as a filename, and a document from a version this build does not know
 * read as absent rather than guessed at.
 */
@RunWith(AndroidJUnit4::class)
class FeedAccessStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "no_backup/feed-access") }
    private val store by lazy { FeedAccessStore(dir) }

    private val pending =
        Record(
            connectionId = CONNECTION_A,
            serverId = SERVER_A,
            wallet = WALLET,
            installation = "0f1e2d3c4b5a69788796",
            requestId = "a1b2c3d4-e5f6-4789-abcd-0123456789ab",
            state = State.Pending,
            updatedAt = Instant.ofEpochMilli(1_790_337_600_000),
        )

    @Test
    fun readsBackWhatItStored() {
        store.put(pending)
        assertEquals(pending, store.get(CONNECTION_A))
        // Another store over the same files, as after a restart: an access decision outlives the
        // process that learned it, which is what makes a reconnect silent.
        assertEquals(pending, FeedAccessStore(dir).get(CONNECTION_A))
    }

    @Test
    fun aConnectedRecordKeepsWhenItsGrantRunsOut() {
        val connected =
            pending.copy(
                state = State.Connected,
                grantUntil = Instant.ofEpochMilli(1_790_341_200_000),
            )
        store.put(connected)
        assertEquals(connected, store.get(CONNECTION_A))
    }

    @Test
    fun eachConnectionIsItsOwnDocument() {
        val other = pending.copy(connectionId = CONNECTION_B, serverId = SERVER_B)
        store.put(pending)
        store.put(other)
        assertEquals(setOf(pending, other), store.all().toSet())
        store.delete(CONNECTION_A)
        assertNull(store.get(CONNECTION_A))
        assertEquals(listOf(other), store.all())
    }

    @Test
    fun theWholeStateMachineSurvivesTheRoundTrip() {
        for (state in State.entries) {
            store.put(pending.copy(state = state))
            assertEquals(state, store.get(CONNECTION_A)?.state)
        }
    }

    @Test
    fun keepsNoSecretOnDisk() {
        store.put(
            pending.copy(state = State.Connected, grantUntil = Instant.ofEpochMilli(1_790_341_200_000))
        )
        val text = File(dir, "$CONNECTION_A.json").readText()
        // The three things that would actually read the feed. None of them is the store's to hold:
        // the session is sealed in the vault beside a phone credential, and the device key never
        // leaves the Keystore at all.
        assertTrue("session" !in text.lowercase())
        assertTrue("invitation" !in text.lowercase())
        assertTrue("private" !in text.lowercase())
        // And no address to reach anybody at, for the same reason nothing else on this phone
        // persists one (SEE-94): the origin is read off the manifest every time.
        assertTrue("http" !in text.lowercase())
    }

    @Test
    fun anIdentifierThatIsNotOneIsNeverUsedAsAFilename() {
        assertNull(store.get("../../etc/passwd"))
        assertNull(store.get(""))
        store.delete("../../etc/passwd")
        assertThrows(IllegalArgumentException::class.java) {
            store.put(pending.copy(connectionId = "not a connection"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.put(pending.copy(serverId = "not a server"))
        }
    }

    @Test
    fun aDocumentThisBuildCannotReadIsAbsentRatherThanGuessedAt() {
        store.put(pending)
        val file = File(dir, "$CONNECTION_A.json")
        val held = JSONObject(file.readText())

        file.writeText(JSONObject(held.toString()).put("version", 99).toString())
        assertNull(store.get(CONNECTION_A))

        file.writeText(JSONObject(held.toString()).put("state", "approved-ish").toString())
        assertNull(store.get(CONNECTION_A))

        file.writeText("{")
        assertNull(store.get(CONNECTION_A))

        // A document naming another connection is not this connection's, whatever it is called.
        file.writeText(JSONObject(held.toString()).put("connectionId", CONNECTION_B).toString())
        assertNull(store.get(CONNECTION_A))

        // And an unreadable document does not take the rest of the directory with it.
        store.put(pending.copy(connectionId = CONNECTION_B, serverId = SERVER_B))
        assertEquals(listOf(CONNECTION_B), store.all().map { it.connectionId })
    }

    @Test
    fun aDirectoryThatIsNotThereYetIsEmptyRatherThanFatal() {
        assertEquals(emptyList<Record>(), store.all())
        assertNull(store.get(CONNECTION_A))
    }

    private companion object {
        const val CONNECTION_A = "0b8e2b1c-3f4d-4e5a-9b6c-7d8e9f0a1b2c"
        const val CONNECTION_B = "5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f"
        const val SERVER_A = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val SERVER_B = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
        const val WALLET = "9C6hybhQ6Aycep9jaUnP6uL9ZYvDjUp1aSkFWPUFJtpj"
    }
}
